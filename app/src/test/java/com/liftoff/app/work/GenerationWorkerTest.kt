package com.liftoff.app.work

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.liftoff.app.LiftoffApplication
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.Equipment
import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.rho.DaemonScript
import com.liftoff.app.rho.FakeDaemon
import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.protocol.RhoJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * The worker's own harness: `doWork()` over a real socket to a scripted [FakeDaemon], on the
 * application's own container.
 *
 * A [androidx.work.CoroutineWorker] reaches its dependencies through `applicationContext`, so the
 * container under test is the one the application built — a second `AppContainer` would open a second
 * DataStore and a second Room database on the same two files. The daemon's address, token and coach
 * workspace are settings the worker reads, so they are written into the container's `settingsStore`
 * rather than passed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as LiftoffApplication).container

    private lateinit var daemon: FakeDaemon

    /** Long enough for a turn the fake answers at once, short enough to fail rather than hang. */
    private val turnWait = 30.seconds

    @Before
    fun seedContainer() = runBlocking {
        daemon = FakeDaemon().also { it.start() }
        container.settingsStore.setDaemonHost(daemon.host())
        container.settingsStore.setDaemonPort(daemon.port())
        container.settingsStore.setDaemonToken(SECRET_TOKEN)
        container.settingsStore.setCoachWorkspacePath(COACH_WORKSPACE)
        // insertAll, not add(): add() rejects the hyphen in CoachFixtures.KEYS's `pullup-bar`.
        container.database.equipmentDao().insertAll(CoachFixtures.KEYS.map { Equipment(key = it, name = it) })
    }

    @After
    fun tearDown() {
        daemon.stop()
        container.database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.preferencesDataStoreFile(SETTINGS_FILE).delete()
    }

    /**
     * outline happy path: Result.success(), the row SUCCEEDED with nabuSessionId and lastEventId set, the
     * Mission ACTIVE with sortie 1 current. Seeded with onAppOpen() only, kind OUTLINE, no sortie, and the
     * outline fixture as the reply. It also shows what the worker hands the turn: the create frame carries the
     * labels ["liftoff", "liftoff:outline"] — never a label starting with run: — and a one-line description
     * built from the row, and the prompt frame carries the client_id built from the row's id and its attempt,
     * which is `<id>-0` for a fresh row because the attempt is read and never bumped.
     */
    @Test
    fun anOutlineGenerationStoresTheReplyAndMarksTheRowSucceeded() {
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = runBlocking {
            container.database.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = mission.id,
                    status = GenerationStatus.QUEUED,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        // What the worker starts from: a DRAFT with no sorties yet, so the ACTIVE mission and its current
        // sortie are both things this run did.
        assertEquals(MissionStatus.DRAFT, runBlocking { container.database.missionDao().get(mission.id) }!!.status)
        assertEquals(emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the row kept the session the turn ran on", daemon.sessionId, row.nabuSessionId)
        // The turn's last event is the idle state_change; the stop that follows is not part of the turn.
        val idle = daemon.log.single { it["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull == "idle" }
        assertEquals("the row kept the cursor the turn ended at", eventId(idle), row.lastEventId)
        assertNotNull(row.finishedAt)

        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals(MissionStatus.ACTIVE, storedMission.status)
        val sorties = runBlocking { container.database.sortieDao().getForMission(mission.id) }
        val current = currentSortie(sorties)
        assertNotNull("the outline left no current sortie in $sorties", current)
        assertEquals("sortie 1 is the one at index 0", 0, current!!.index)

        val created = frameFor("rho.session.create")
        assertEquals("the create frame used the workspace from settingsStore", COACH_WORKSPACE, created["workspace"]?.jsonPrimitive?.contentOrNull)
        val options = created.jsonObject("options")
        val labels = options["labels"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("liftoff", "liftoff:outline"), labels)
        assertTrue("no label may start with run:, rho's runner acts on those: $labels", labels.orEmpty().none { it.startsWith("run:") })
        val description = options["description"]?.jsonPrimitive?.contentOrNull
        assertTrue("the description is one line naming this generation: $description", description != null)
        assertTrue("the description is one line: $description", '\n' !in description!!)
        assertTrue("the description names the row it belongs to: $description", description.contains("$generationId"))

        val prompted = frameFor("rho.session.send_prompt")
        assertEquals("$generationId-0", prompted["client_id"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * lift Flight Plan happy path: the sortie PLANNED, and the plan stored with source GENERATED. Seeded with
     * onAppOpen(), confirm(id), then a scrub of the index-0 sortie, which leaves index 1 — a LIFT under the
     * default RLRLR pattern — current and PENDING; the row is kind FLIGHT_PLAN with that sortie's id and the
     * lift fixture is the reply.
     */
    @Test
    fun aLiftPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan() {
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.LIFT_PLAN)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        runBlocking { container.missionManager.confirm(mission.id) }
        // With run generation off (the default), confirm already wrote the simple plan on the index-0 run, so
        // scrubbing it is what moves the week onto the lift sortie the coach is asked for.
        val scrubbed = sortieAt(mission.id, 0)
        assertEquals(SortieType.RUN, scrubbed.type)
        runBlocking { container.missionManager.scrub(scrubbed.id) }

        val lift = sortieAt(mission.id, 1)
        assertEquals("index 1 is a LIFT under RLRLR", SortieType.LIFT, lift.type)
        assertAwaitingGeneration(lift)

        val generationId = insertFlightPlanGeneration(mission.id, lift.id)
        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)
        assertPlannedWithAGeneratedPlan(generationId, lift.id)
    }

    /**
     * run Flight Plan happy path: the same, for the run. Seeded with setGenerateRunPlans(true) before
     * onAppOpen() — which is what leaves a run sortie for the coach instead of writing the app's own simple
     * plan on it — then confirm(id), leaving index 0 a current PENDING RUN; the row is kind FLIGHT_PLAN with
     * that sortie's id and the run fixture is the reply.
     */
    @Test
    fun aRunPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan() {
        // Before onAppOpen(): confirm() is what prepares the current sortie, and it reads this setting then.
        runBlocking { container.settingsStore.setGenerateRunPlans(true) }
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.RUN_PLAN)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        runBlocking { container.missionManager.confirm(mission.id) }

        val run = sortieAt(mission.id, 0)
        assertEquals("index 0 is a RUN under RLRLR", SortieType.RUN, run.type)
        assertAwaitingGeneration(run)

        val generationId = insertFlightPlanGeneration(mission.id, run.id)
        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)
        assertPlannedWithAGeneratedPlan(generationId, run.id)
    }

    /**
     * a missing row returns failure, and do nothing: an id no `Generation` row has. `doWork()` returns
     * Result.failure(), the fake receives no session frame — no create, no prompt — and no frame of any other
     * kind either, and no row is written for the id it was given.
     */
    @Test
    fun aGenerationRowThatDoesNotExistReturnsFailureAndSendsNothingToTheDaemon() {
        val mission = runBlocking { container.missionManager.onAppOpen() }
        // A row this mission did have, taken back out again: the id is one the worker is genuinely asked
        // for, with nothing behind it.
        val goneId = runBlocking {
            val generations = container.database.generationDao()
            val id = generations.insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = mission.id,
                    status = GenerationStatus.QUEUED,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            generations.delete(generations.get(id)!!)
            id
        }
        assertNull("row $goneId survived its delete", runBlocking { container.database.generationDao().get(goneId) })
        val rowsBefore = runBlocking { container.database.generationDao().countAll() }

        val result = runWorker(goneId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        assertNothingReachedTheDaemon()
        assertNull("the worker wrote a row for the id it was given", runBlocking { container.database.generationDao().get(goneId) })
        val rowsAfter = runBlocking { container.database.generationDao().getAll() }
        assertEquals("the worker wrote rows of its own: $rowsAfter", rowsBefore, rowsAfter.size.toLong())
    }

    /**
     * a SUCCEEDED row is left unchanged: a row that already finished, with its finishedAt, error,
     * nabuSessionId and lastEventId filled in. `doWork()` returns Result.success() and the row still reads
     * exactly as it was seeded — not marked RUNNING, not re-stamped, not stored over — and the fake receives
     * no session frame.
     */
    @Test
    fun aGenerationRowThatAlreadyFinishedIsLeftAsItIsAndReportedAsDone() {
        val mission = runBlocking { container.missionManager.onAppOpen() }
        val seeded = Generation(
            kind = GenerationKind.OUTLINE,
            missionId = mission.id,
            status = GenerationStatus.SUCCEEDED,
            nabuSessionId = "session-the-finished-generation-ran-on",
            lastEventId = "01E000000000000000000000007",
            attempt = 2,
            error = "left there by the attempt that finished it",
            createdAt = SEEDED_CREATED_AT,
            finishedAt = SEEDED_FINISHED_AT,
        )
        val generationId = runBlocking { container.database.generationDao().insert(seeded) }
        // The seeded row as the table holds it, so the equality below means "nothing changed it", not
        // "Room reads it back differently".
        val expected = seeded.copy(id = generationId)
        assertEquals("the table does not hold the row it was given", expected, runBlocking { container.database.generationDao().get(generationId) })

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals("the row was marked RUNNING", GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the row was re-stamped", SEEDED_FINISHED_AT, row.finishedAt)
        assertEquals("the row's error was written over", seeded.error, row.error)
        assertEquals("the row's session was written over", seeded.nabuSessionId, row.nabuSessionId)
        assertEquals("the row's cursor was written over", seeded.lastEventId, row.lastEventId)
        assertEquals("the row no longer reads as it was seeded", expected, row)

        assertNothingReachedTheDaemon()
    }

    private fun runWorker(generationId: Long): ListenableWorker.Result = runBlocking {
        withTimeout(turnWait) {
            TestListenableWorkerBuilder<GenerationWorker>(context)
                .setInputData(workDataOf(GenerationWorker.GENERATION_ID to generationId))
                .build()
                .doWork()
        }
    }

    private fun sortieAt(missionId: Long, index: Int): Sortie =
        runBlocking { container.database.sortieDao().getForMission(missionId).single { it.index == index } }

    private fun insertFlightPlanGeneration(missionId: Long, sortieId: Long): Long = runBlocking {
        container.database.generationDao().insert(
            Generation(
                kind = GenerationKind.FLIGHT_PLAN,
                missionId = missionId,
                sortieId = sortieId,
                status = GenerationStatus.QUEUED,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * What the worker is starting from: this sortie is the current one and nothing has planned it yet, so the
     * PLANNED state and the stored plan below are both things this run did. `storePlan` refuses a sortie that
     * is not the current one, so a case that did not check this would pass on a plan confirm() had already
     * written.
     */
    private fun assertAwaitingGeneration(sortie: Sortie) {
        val current = currentSortie(runBlocking { container.database.sortieDao().getForMission(sortie.missionId) })
        assertNotNull("no sortie is current in this mission", current)
        assertEquals("the worker is asked about the current sortie", sortie.id, current!!.id)
        assertEquals(SortieState.PENDING, runBlocking { container.database.sortieDao().get(sortie.id) }!!.state)
        assertNull("the app already wrote a plan on this sortie", runBlocking { container.database.flightPlanDao().getPlan(sortie.id) })
    }

    /** The row's end state, the sortie it planned, the plan it stored, and the labels it asked the session with. */
    private fun assertPlannedWithAGeneratedPlan(generationId: Long, sortieId: Long) {
        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals(GenerationKind.FLIGHT_PLAN, row.kind)
        assertEquals(sortieId, row.sortieId)
        assertNotNull(row.finishedAt)

        assertEquals("the sortie the row named", SortieState.PLANNED, runBlocking { container.database.sortieDao().get(sortieId) }!!.state)
        val plan = runBlocking { container.database.flightPlanDao().getPlan(sortieId) }
        assertNotNull("no FlightPlan was stored on sortie $sortieId", plan)
        assertEquals(FlightPlanSource.GENERATED, plan!!.plan.source)

        val labels = frameFor("rho.session.create").jsonObject("options")["labels"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("liftoff", "liftoff:flight-plan"), labels)
    }

    /**
     * The row guards run before anything is opened, so the daemon sees nothing: no session created, no
     * prompt sent, and no frame of any other kind — not even the hello the connection check would send.
     */
    private fun assertNothingReachedTheDaemon() {
        assertTrue("the worker created a session: ${daemon.framesFor("rho.session.create")}", daemon.framesFor("rho.session.create").isEmpty())
        assertTrue("the worker sent a prompt: ${daemon.framesFor("rho.session.send_prompt")}", daemon.framesFor("rho.session.send_prompt").isEmpty())
        assertTrue("the worker sent the daemon this: ${daemon.frames}", daemon.frames.isEmpty())
    }

    /** The params of the one frame this worker sent calling [method], failing if there was not exactly one. */
    private fun frameFor(method: String): JsonObject {
        val frames = daemon.framesFor(method)
        assertEquals("expected exactly one $method frame, got $frames", 1, frames.size)
        val params = RhoJson.decodeFromString(Rpc.serializer(), frames.first()).params?.jsonObject
        assertTrue("the $method frame carried no params: ${frames.first()}", params != null)
        return params!!
    }

    private fun JsonObject.jsonObject(key: String): JsonObject {
        val value = this[key]?.jsonObject
        assertTrue("the frame has no \"$key\" object: $this", value != null)
        return value!!
    }

    private fun eventId(event: JsonObject): String {
        val id = event["id"]?.jsonPrimitive?.contentOrNull
        assertTrue("this event has no id: $event", id != null)
        return id!!
    }

    private companion object {
        const val SECRET_TOKEN = "sekrit"
        const val COACH_WORKSPACE = "/repo/coach"
        const val DATABASE_NAME = "liftoff.db"
        const val SETTINGS_FILE = "settings"
        // A finished row's own stamps, fixed so the test can tell them from anything the worker wrote.
        const val SEEDED_CREATED_AT = 1_760_000_000_000L
        const val SEEDED_FINISHED_AT = 1_760_000_600_000L
    }
}
