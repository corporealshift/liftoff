package com.liftoff.app.work

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.liftoff.app.LiftoffApplication
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.coach.CoachPromptBuilder
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
import com.liftoff.app.rho.protocol.EventType
import com.liftoff.app.rho.protocol.RhoJson
import com.liftoff.app.rho.protocol.RpcCodes
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * onAppOpen(), continueWithoutOutline(id), then a scrub of the index-0 sortie, which leaves index 1 — a LIFT under the
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
        runBlocking { container.missionManager.continueWithoutOutline(mission.id) }
        // With run generation off (the default), continueWithoutOutline already wrote the simple plan on the index-0 run, so
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
     * plan on it — then continueWithoutOutline(id), leaving index 0 a current PENDING RUN; the row is kind FLIGHT_PLAN with
     * that sortie's id and the run fixture is the reply.
     */
    @Test
    fun aRunPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan() {
        // Before onAppOpen(): continueWithoutOutline() is what prepares the current sortie, and it reads this setting then.
        runBlocking { container.settingsStore.setGenerateRunPlans(true) }
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.RUN_PLAN)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        runBlocking { container.missionManager.continueWithoutOutline(mission.id) }

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

    /**
     * a row that already has a session id: no `rho.session.create` frame. The row is seeded with the fake's
     * own `sessionId` and with a cursor that is one of the fake's own log events, so this is the resume the
     * stored session and cursor exist for (invariant 6): the events the fake pushes carry the session the
     * runner filters on, and the catch-up read asks for what came after the stored cursor. The turn still
     * runs to its end, the reply is stored, and the row ends SUCCEEDED on the session it was given.
     */
    @Test
    fun aGenerationRowWithASessionIdAlreadyStoredIsResumedWithoutCreatingAnotherSession() {
        // The session log as an earlier attempt left it: this row's session already exists, and its cursor
        // is the last event that attempt read.
        val seededCursor = daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", "a reply from the attempt that did not finish")
        })
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
                    nabuSessionId = daemon.sessionId,
                    lastEventId = seededCursor,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        assertTrue(
            "a row that already named its session was given a new one: ${daemon.framesFor("rho.session.create")}",
            daemon.framesFor("rho.session.create").isEmpty(),
        )

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the row ended on a session other than the one it was given", daemon.sessionId, row.nabuSessionId)
        val idle = daemon.log.single { it["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull == "idle" }
        assertEquals("the row did not reach the end of the turn it resumed", eventId(idle), row.lastEventId)
        assertTrue("the cursor stayed where the row left it: $seededCursor", row.lastEventId!! > seededCursor)
        assertNotNull(row.finishedAt)

        // The turn reaching its end is what stores the reply, so the mission is the other half of it.
        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals(MissionStatus.ACTIVE, storedMission.status)
        val current = currentSortie(runBlocking { container.database.sortieDao().getForMission(mission.id) })
        assertNotNull("the resumed outline left no current sortie", current)

        // Everything the turn did went to the session the row already named.
        assertEquals(
            "the worker subscribed to something other than the stored session",
            daemon.sessionId,
            frameFor("rho.session.subscribe")["session_id"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "the catch-up read did not start at the stored cursor",
            seededCursor,
            frameFor("rho.session.events_after")["last_event_id"]?.jsonPrimitive?.contentOrNull,
        )
        val prompted = frameFor("rho.session.send_prompt")
        assertEquals(daemon.sessionId, prompted["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("$generationId-0", prompted["client_id"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * a prompt the daemon already has is not written a second time: the row is QUEUED with the fake's own
     * `sessionId` and a cursor at the session event that sits before this row's own prompt, and the fake's log is
     * seeded with that prompt as a user message carrying client_id `<id>-0`, then the assistant reply, then an
     * idle end. `doWork()` returns `Result.success()` with no `rho.session.create` frame at all, the catch-up read
     * starts at the cursor the row stored, the reply the log already held is stored on the mission, and the row
     * ends SUCCEEDED with its cursor moved forward to the seeded idle end. The log holds exactly one user message
     * for that client_id: the re-send is answered with the id of the prompt that already landed, so it plays no
     * second turn and the turn is finished by reading what the stored cursor had not reached yet.
     */
    @Test
    fun aGenerationWhosePromptAlreadyLandedResumesFromTheStoredCursorAndStoresTheReply() {
        // The session this row's earlier attempt opened, as the log holds it. The cursor is the event before this
        // row's prompt, so the catch-up read still has the prompt, the reply and the end to come to.
        val seededCursor = daemon.logEvent(EventType.SESSION, buildJsonObject { put("workspace", COACH_WORKSPACE) })

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = runBlocking {
            container.database.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = mission.id,
                    status = GenerationStatus.QUEUED,
                    nabuSessionId = daemon.sessionId,
                    lastEventId = seededCursor,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        val clientId = "$generationId-0"
        // The turn as the earlier attempt left it, seeded rather than scripted: a prompt this daemon already
        // took plays no turn when it is sent again, so there is nothing here for a script to play.
        daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "user")
            put("content", "the prompt this generation already sent")
            put("client_id", clientId)
        })
        daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", CoachFixtures.fixture(CoachFixtures.OUTLINE))
        })
        val idle = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "idle")
            put("reason", "the turn this row's prompt started had already finished")
        })

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        assertTrue(
            "a row that already named its session was given a new one: ${daemon.framesFor("rho.session.create")}",
            daemon.framesFor("rho.session.create").isEmpty(),
        )
        assertEquals(
            "the catch-up read did not start at the stored cursor",
            seededCursor,
            firstFrameFor("rho.session.events_after")["last_event_id"]?.jsonPrimitive?.contentOrNull,
        )

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the row ended on a session other than the one it was given", daemon.sessionId, row.nabuSessionId)
        assertEquals("the row did not reach the end the log already held", idle, row.lastEventId)
        assertTrue("the cursor moved back to where the row left it: $seededCursor", row.lastEventId!! > seededCursor)
        assertNotNull(row.finishedAt)

        // Reaching the end is what stores the reply, so the mission is the other half of the resume.
        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals(MissionStatus.ACTIVE, storedMission.status)
        assertNotNull(
            "the resumed outline left no current sortie",
            currentSortie(runBlocking { container.database.sortieDao().getForMission(mission.id) }),
        )

        assertEquals("this row's prompt reached the log a second time under $clientId", 1, userMessagesFor(clientId))
    }

    /**
     * a cursor the worker already walked past is not where the turn is decided from: the log is seeded as the resume
     * above left it — this row's prompt as a user message carrying client_id `<id>-0`, then the assistant reply, then
     * the idle end — but the row's `lastEventId` is that idle end, so the read from the cursor has nothing after it.
     * `doWork()` returns `Result.success()` all the same: the runner reads again from the prompt's own id and decides
     * the turn as if the cursor had never passed them — the first end after the prompt, the last reply before it — so
     * the outline is stored on the mission and the row ends SUCCEEDED on the session it was given, with attempt 0 and
     * no error. The cursor is not moved back behind the reply it re-read: the row still ends on the idle end it was
     * seeded with. Exactly one `rho.session.send_prompt` frame carries this row's client_id, and the log holds one
     * user message for it: nothing here plays a second turn.
     */
    @Test
    fun aCursorAtTheEndOfTheTurnStillFindsTheReplyTheWorkerAlreadyRead() {
        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = runBlocking {
            container.database.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = mission.id,
                    status = GenerationStatus.QUEUED,
                    nabuSessionId = daemon.sessionId,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        val clientId = "$generationId-0"
        // The turn as the earlier attempt left it in the log: prompt, reply, end.
        val promptId = daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "user")
            put("content", "the prompt this generation already sent")
            put("client_id", clientId)
        })
        val reply = daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", CoachFixtures.fixture(CoachFixtures.OUTLINE))
        })
        val idle = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "idle")
            put("reason", "the turn this row's prompt started had already finished")
        })
        // The cursor is the end of the turn, so the read from it has nothing left to catch up with.
        runBlocking { container.database.generationDao().saveLastEventId(generationId, idle) }

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        assertTrue(
            "a row that already named its session was given a new one: ${daemon.framesFor("rho.session.create")}",
            daemon.framesFor("rho.session.create").isEmpty(),
        )
        val reads = daemon.framesFor("rho.session.events_after").map { frame ->
            RhoJson.decodeFromString(Rpc.serializer(), frame).params?.jsonObject?.get("last_event_id")?.jsonPrimitive?.contentOrNull
        }
        assertEquals("the first read did not start at the cursor the row holds", idle, reads.first())
        assertTrue("no read started at the prompt this row already sent: $reads", reads.contains(promptId))

        val prompts = promptsSent()
        assertEquals("this row sent its prompt more than once: $prompts", 1, prompts.size)
        assertEquals(clientId, prompts.single()["client_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(daemon.sessionId, prompts.single()["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("this row's prompt reached the log a second time under $clientId", 1, userMessagesFor(clientId))

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the row ended on a session other than the one it was given", daemon.sessionId, row.nabuSessionId)
        assertEquals("the cursor moved back from the end the row was seeded with", idle, row.lastEventId)
        assertTrue("the cursor sits behind the reply it had already read: $reply", row.lastEventId!! > reply)
        assertEquals("the row counted an attempt it did not make", 0, row.attempt)
        assertNull("a turn that finished left an error on its row", row.error)
        assertNotNull(row.finishedAt)

        // Reaching the end is what stores the reply, so the mission is the other half of it.
        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals(MissionStatus.ACTIVE, storedMission.status)
        assertNotNull(
            "the reply the log already held was not stored",
            currentSortie(runBlocking { container.database.sortieDao().getForMission(mission.id) }),
        )
    }

    /**
     * a generation asked a second time sends its prompt again under the client_id the row holds, and the daemon
     * delivers it once: the log is seeded with this row's prompt as a user message carrying client_id `<id>-0`,
     * then a paused end, then the assistant reply, then an idle end, and the row goes through `doWork()` twice.
     * The first run stops at the paused end — `Result.retry()`, the row RUNNING with its cursor at that paused
     * event, nothing stored and its session left open — and the second reads on from the cursor the first one
     * left, finding the reply and the idle end: `Result.success()`, the outline stored, the row SUCCEEDED. Both
     * `rho.session.send_prompt` frames carry the same `<generationId>-<attempt>`, built from the attempt the row
     * holds rather than from WorkManager's count, the log holds exactly one user message for it, and there is no
     * second `rho.session.create` frame: the second run is the same session, not a new generation of the same
     * prompt.
     */
    @Test
    fun aRetriedGenerationSendsItsPromptAgainUnderTheSameClientIdAndTheDaemonDeliversItOnce() {
        val seededCursor = daemon.logEvent(EventType.SESSION, buildJsonObject { put("workspace", COACH_WORKSPACE) })

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = runBlocking {
            container.database.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = mission.id,
                    status = GenerationStatus.QUEUED,
                    nabuSessionId = daemon.sessionId,
                    lastEventId = seededCursor,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        // The paused end is seeded between the prompt and the reply so the first run stops there and the second
        // has a turn already played, further along the log, to catch up with.
        daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "user")
            put("content", "the prompt this generation already sent")
            put("client_id", "$generationId-0")
        })
        val paused = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "paused")
            put("reason", "the coach stopped part-way through the turn")
        })
        daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", CoachFixtures.fixture(CoachFixtures.OUTLINE))
        })
        val idle = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "idle")
            put("reason", "the turn this row's prompt started had already finished")
        })

        val first = runWorker(generationId)
        assertTrue("the run that met the paused end returned $first", first is ListenableWorker.Result.Retry)
        val afterFirst = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.RUNNING, afterFirst.status)
        assertEquals("the first run's cursor is not the end it stopped at", paused, afterFirst.lastEventId)
        assertEquals(
            "the run that stopped paused stored an outline",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        assertSessionLeftOpen()

        val second = runWorker(generationId)
        assertTrue("the run that resumed from the stored cursor returned $second", second is ListenableWorker.Result.Success)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the second run did not reach the idle end the log held", idle, row.lastEventId)
        assertEquals("the paused retry bumped the attempt of a generation that ran one turn", 0, row.attempt)
        assertNotNull(row.finishedAt)
        assertEquals(
            "the resumed run stored nothing on the mission",
            MissionStatus.ACTIVE,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )

        val clientId = "$generationId-${row.attempt}"
        val prompts = promptsSent()
        assertEquals("the generation was asked twice and the daemon took $prompts", 2, prompts.size)
        prompts.forEachIndexed { index, frame ->
            assertEquals("the ${index + 1}th prompt went into another session", daemon.sessionId, frame["session_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "the ${index + 1}th prompt was not sent under the client_id the row holds",
                clientId,
                frame["client_id"]?.jsonPrimitive?.contentOrNull,
            )
        }
        assertEquals("the re-send wrote this row's prompt into the log a second time under $clientId", 1, userMessagesFor(clientId))
        assertTrue(
            "the second run opened a session of its own: ${daemon.framesFor("rho.session.create")}",
            daemon.framesFor("rho.session.create").isEmpty(),
        )
    }

    /**
     * one `rho.session.stop` frame after success: exactly one, and it comes after the prompt frame — the stop
     * is what a generation that finished and stored its answer leaves behind, and nothing else sends it. The
     * session it gives up is the one that turn ran on.
     */
    @Test
    fun oneSessionStopFrameIsSentAfterASuccessfulStore() {
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

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)
        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals(
            "the store this stop follows did not happen",
            MissionStatus.ACTIVE,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )

        val methods = methodsSent()
        val stopped = frameFor("rho.session.stop")
        val promptAt = methods.indexOf("rho.session.send_prompt")
        assertTrue("no prompt was sent, so there is nothing for a stop to follow: $methods", promptAt >= 0)
        assertTrue(
            "the session was given up before the prompt it was stopped for: $methods",
            methods.indexOf("rho.session.stop") > promptAt,
        )
        assertEquals(
            "the stop gave up a session other than the one the generation ran on",
            row.nabuSessionId,
            stopped["session_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * a stop the daemon refuses after the store has already succeeded changes nothing: `Result.success()`, the row
     * SUCCEEDED with `error` still null, the outline stored, and exactly one `rho.session.stop` frame. The same
     * happy path as the case above, with `rho.session.stop` refused through [FakeDaemon.refuseMethod] by a code in
     * `RpcCodes.PERMANENT` — the shape a daemon gives a session it has already moved on from. The outcome was
     * decided by the store, so the daemon's refusal is not written onto the row, and the generation does not ask a
     * second time for the session it was just refused.
     */
    @Test
    fun aRefusedStopAfterASuccessfulStoreStillEndsTheGenerationSucceeded() {
        val refusal = "the fake will not stop a session it has already completed"
        daemon.refuseMethod["rho.session.stop"] = RpcCodes.INVALID_TRANSITION to refusal
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertNull("the refused stop was written onto a row that had already succeeded", row.error)
        assertNotNull(row.finishedAt)
        assertEquals("the row lost the session its turn ran on", daemon.sessionId, row.nabuSessionId)

        // The reply was stored before the stop was attempted, which is the outcome a stop cannot change.
        assertEquals(
            "the store this stop was refused after did not happen",
            MissionStatus.ACTIVE,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        assertNotNull(
            "the stored outline left no current sortie",
            currentSortie(runBlocking { container.database.sortieDao().getForMission(mission.id) }),
        )

        assertSessionGivenUpOnce(daemon.sessionId)
    }

    /**
     * invalid reply, then a repair, then success: two prompt frames in one session, client_ids ending -0 then -1,
     * attempt 1, row SUCCEEDED. [CoachFixtures.BAD_JSON] is the first turn's reply and [CoachFixtures.OUTLINE] the
     * repair's, on an OUTLINE row over a mission seeded with onAppOpen() only. One `rho.session.create` frame and
     * both prompts naming the same session: the repair goes into the session the first turn opened. The second
     * prompt's client_id is `<id>-1`, built from the attempt read after the bump, because the daemon dedupes a
     * prompt by client_id and the id the refused reply went out under would have played no turn at all. The second
     * prompt is the repair rather than a repeat of the first — not the original prompt text, and carrying the
     * validator's own error lines from the reply that was refused. The row ends SUCCEEDED with its session id and
     * cursor set, and the valid reply is stored: the mission is ACTIVE with a current sortie.
     */
    @Test
    fun anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds() {
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.BAD_JSON)),
            DaemonScript.State("idle"),
        )
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)
        assertEquals("the repair starts from a DRAFT with no sorties", MissionStatus.DRAFT, runBlocking { container.database.missionDao().get(mission.id) }!!.status)
        assertEquals(emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        // One session for the whole generation: the repair is a second turn on it, not a second session.
        val created = frameFor("rho.session.create")
        assertEquals("the repair turn opened a session of its own", 1, daemon.framesFor("rho.session.create").size)
        assertEquals(COACH_WORKSPACE, created["workspace"]?.jsonPrimitive?.contentOrNull)

        val prompts = promptsSent()
        assertEquals("the refused reply was answered with $prompts", 2, prompts.size)
        val first = prompts.first()
        val repair = prompts.last()
        assertEquals(daemon.sessionId, first["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("the repair went into another session", daemon.sessionId, repair["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("$generationId-0", first["client_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("the repair's client_id is not the attempt the row holds after the bump", "$generationId-1", repair["client_id"]?.jsonPrimitive?.contentOrNull)

        val refusedErrors = CoachFixtures.errorsOf(CoachFixtures.BAD_JSON, CoachFixtures.OUTLINE_CONTEXT)
        val firstContent = first["content"]?.jsonPrimitive?.contentOrNull
        val repairContent = repair["content"]?.jsonPrimitive?.contentOrNull
        assertTrue("the repair sent the original prompt again: $repairContent", repairContent != firstContent)
        assertEquals("the repair is not the prompt §7.3 step 5 hands back", CoachPromptBuilder.repairPrompt(refusedErrors), repairContent)
        for (line in refusedErrors) {
            assertTrue("the repair did not carry the validator's own line \"$line\": $repairContent", repairContent!!.contains(line))
        }

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals("the repair did not leave one bump on the row's attempt", 1, row.attempt)
        assertEquals("the row lost the session its repair ran on", daemon.sessionId, row.nabuSessionId)
        val idles = daemon.log.filter { it["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull == "idle" }
        assertEquals("the repair turn did not end idle: $idles", 2, idles.size)
        assertEquals("the row's cursor is not the end of the repair turn", eventId(idles.last()), row.lastEventId)
        assertNotNull(row.finishedAt)

        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals(MissionStatus.ACTIVE, storedMission.status)
        val current = currentSortie(runBlocking { container.database.sortieDao().getForMission(mission.id) })
        assertNotNull("the repaired outline left no current sortie", current)
    }

    /**
     * two failed repairs end the generation: three prompt frames and no fourth, attempt 2, row FAILED with
     * 'invalid output' and the last errors, nothing stored. [CoachFixtures.BAD_JSON] then
     * [CoachFixtures.SCHEMA_VIOLATION] are scripted so the errors the row ends with are the second reply's, not
     * the first's; the fake repeats its last turn, so the third prompt gets SCHEMA_VIOLATION too. The cap is the
     * generation's repairs rather than one run's, so a row that already has attempt 2 asks the coach no more.
     * `Result.failure()`, and the row's error is the same lines the coach was handed — the last turn's validator
     * error lines, word for word, joined rather than dropped. Nothing is stored: the mission is still DRAFT with
     * no sorties, and the session is given up once.
     */
    @Test
    fun afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing() {
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.BAD_JSON)),
            DaemonScript.State("idle"),
        )
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.SCHEMA_VIOLATION)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        frameFor("rho.session.create")
        val prompts = promptsSent()
        assertEquals("a third refused reply was answered with another prompt: $prompts", 3, prompts.size)
        prompts.forEachIndexed { index, frame ->
            assertEquals(daemon.sessionId, frame["session_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("the ${index + 1}th prompt was not sent under the attempt the row holds then", "$generationId-$index", frame["client_id"]?.jsonPrimitive?.contentOrNull)
        }

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.FAILED, row.status)
        assertEquals("the generation was asked to repair more than twice", 2, row.attempt)
        val lastErrors = CoachFixtures.errorsOf(CoachFixtures.SCHEMA_VIOLATION, CoachFixtures.OUTLINE_CONTEXT)
        val firstErrors = CoachFixtures.errorsOf(CoachFixtures.BAD_JSON, CoachFixtures.OUTLINE_CONTEXT)
        assertEquals("invalid output: " + lastErrors.joinToString("; "), row.error)
        assertTrue(
            "the row kept the first refused reply's errors instead of the last turn's: ${row.error}",
            row.error != "invalid output: " + firstErrors.joinToString("; "),
        )
        for (line in lastErrors) {
            assertTrue("the row's error dropped the line \"$line\": ${row.error}", row.error!!.contains(line))
        }
        assertNotNull(row.finishedAt)
        assertEquals("the row lost the session its last repair ran on", daemon.sessionId, row.nabuSessionId)

        assertEquals("a generation that ran out of repairs stored something", MissionStatus.DRAFT, runBlocking { container.database.missionDao().get(mission.id) }!!.status)
        assertEquals("a generation that ran out of repairs stored sorties: ", emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        assertSessionGivenUpOnce(daemon.sessionId)
    }

    /**
     * blocked ends the generation for good: `Result.failure()` and the row FAILED with 'coach needed input'.
     * Scripted as an assistant line then `State("blocked")` on an OUTLINE row over a mission seeded with
     * onAppOpen() only. A coach that asked for input will not answer itself, so this is not a retry: nothing is
     * stored — the mission is still DRAFT with no sorties — and the session the turn opened is given up once,
     * the way a generation that finished gives up its session.
     */
    @Test
    fun aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput() {
        daemon.script(
            DaemonScript.Assistant("Which equipment do you mean by 'the bar'?"),
            DaemonScript.Assistant("I need the weights you actually have."),
            DaemonScript.State("blocked", "the coach is waiting for input"),
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

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.FAILED, row.status)
        assertEquals("coach needed input", row.error)
        assertNotNull(row.finishedAt)
        // A FAILED row is still the trace of the turn it ran, so it keeps the session that turn reported.
        assertEquals("the row lost the session its blocked turn ran on", daemon.sessionId, row.nabuSessionId)

        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals(MissionStatus.DRAFT, storedMission.status)
        assertEquals("a blocked turn stored something: ", emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        assertSessionGivenUpOnce(daemon.sessionId)
    }

    /**
     * a permanent refusal at a call ends the generation for good: `Result.failure()` and the row FAILED with
     * the daemon's own words. `rho.session.send_prompt` is refused through [FakeDaemon.refuseMethod] with a code
     * in `RpcCodes.PERMANENT` and a message of the fake's own, on an OUTLINE row over a mission seeded with
     * onAppOpen() only. The session was already opened by the time the call was refused, so it is given up once
     * even though the generation died, and the row keeps the session id it died with.
     */
    @Test
    fun aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession() {
        val refusal = "the fake will not take this prompt"
        daemon.refuseMethod["rho.session.send_prompt"] = RpcCodes.INVALID_REQUEST to refusal
        // Scripted but never played: the prompt is refused before the daemon has anything to say.
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

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.FAILED, row.status)
        // The daemon's own message, word for word: a permanent refusal says the same thing every time.
        assertEquals("rho.session.send_prompt: $refusal", row.error)
        assertNotNull(row.finishedAt)
        assertEquals("a row that died this way lost its session", daemon.sessionId, row.nabuSessionId)

        assertEquals("the refused store left something behind: ", MissionStatus.DRAFT, runBlocking { container.database.missionDao().get(mission.id) }!!.status)
        assertSessionGivenUpOnce(daemon.sessionId)
    }

    /**
     * a valid reply whose target has moved on ends the generation for good: `Result.failure()` and the row
     * FAILED with the reason `MissionManager` itself gives. Seeded like the run happy path —
     * setGenerateRunPlans(true) before onAppOpen(), then continueWithoutOutline(id), leaving index 0 a current PENDING RUN —
     * then [MissionManager.scrub] that sortie between inserting the row and running the worker, so the turn
     * still gets a valid run plan and the store turns it away. Nothing is stored, and the session is given up
     * once.
     */
    @Test
    fun aValidReplyForASortieThatHasMovedOnFailsTheGenerationWithTheStoresOwnReason() {
        // Before onAppOpen(): continueWithoutOutline() is what prepares the current sortie, and it reads this setting then.
        runBlocking { container.settingsStore.setGenerateRunPlans(true) }
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.RUN_PLAN)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        runBlocking { container.missionManager.continueWithoutOutline(mission.id) }

        val run = sortieAt(mission.id, 0)
        assertEquals("index 0 is a RUN under RLRLR", SortieType.RUN, run.type)
        assertAwaitingGeneration(run)

        val generationId = insertFlightPlanGeneration(mission.id, run.id)
        // The target moves out from under the row before the worker reaches the store with its valid reply.
        runBlocking { container.missionManager.scrub(run.id) }
        assertEquals(SortieState.SCRUBBED, runBlocking { container.database.sortieDao().get(run.id) }!!.state)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.FAILED, row.status)
        // The store's own sentence for this state, not a generic 'the store refused'.
        assertEquals("sortie is SCRUBBED", row.error)
        assertNotNull(row.finishedAt)
        assertEquals("the row lost the session its turn ran on", daemon.sessionId, row.nabuSessionId)

        assertNull("a plan was stored on the scrubbed sortie", runBlocking { container.database.flightPlanDao().getPlan(run.id) })
        assertEquals(SortieState.SCRUBBED, runBlocking { container.database.sortieDao().get(run.id) }!!.state)

        assertSessionGivenUpOnce(daemon.sessionId)
    }

    /**
     * a refused hello ends the generation for good: `rho.hello` is refused with PROTOCOL_MISMATCH and the
     * fake's own mismatch message naming daemon 2.0 ([FakeDaemon.refuseProtocolMismatch]), on an OUTLINE row
     * over a mission seeded with onAppOpen() only. `Result.failure()`, and the row FAILED with the sentence a
     * user reads in Mission Control's Test connection for that major. The handshake is the connection check's
     * own socket, so no session is opened and none is given up: the hello is the only frame the daemon sees.
     */
    @Test
    fun aProtocolMismatchFromARefusedHelloFailsTheGenerationWithoutOpeningASession() {
        daemon.refuseProtocolMismatch("2.0")
        // Scripted but never played: the hello is refused before there is a session to prompt into.
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        // Word for word the Test-connection row: majors, not the full versions the two sides answered with.
        mismatchFailedRow(generationId, mission.id, "Liftoff speaks rho protocol 1.x, the daemon speaks 2.x")
        assertNoSessionWasOpened()
    }

    /**
     * a hello that is accepted but answers a protocol major this client doesn't speak
     * ([FakeDaemon.answerHelloWithProtocol]) ends it the same way: the check reads the mismatch off the
     * reply rather than off a refusal. Same expectations — `Result.failure()`, the row FAILED with the same
     * sentence for the same major, and no session opened or given up.
     */
    @Test
    fun aProtocolMismatchFromAnAcceptedHelloFailsTheGenerationWithoutOpeningASession() {
        daemon.answerHelloWithProtocol("2.0")
        // Scripted but never played: the reply's version is settled before any session exists.
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        mismatchFailedRow(generationId, mission.id, "Liftoff speaks rho protocol 1.x, the daemon speaks 2.x")
        assertNoSessionWasOpened()
    }

    /**
     * a mismatch that names no version keeps the daemon's own words: `rho.hello` is refused with
     * PROTOCOL_MISMATCH and a message that names no version at all, so the check's `daemonVersion` comes back
     * empty. The row is FAILED with that refusal word for word — not the "1.x / N.x" sentence, and not that
     * sentence with a blank or invented major in it — and no session is opened.
     */
    @Test
    fun aProtocolMismatchThatNamesNoVersionFailsTheGenerationWithTheDaemonsOwnMessage() {
        val refusal = "rho_protocol_mismatch: the daemon will not speak to this protocol"
        daemon.refuseMethod["rho.hello"] = RpcCodes.PROTOCOL_MISMATCH to refusal
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        val row = mismatchFailedRow(generationId, mission.id, refusal)
        // A version nobody named is not filled in with a blank or an invented one.
        assertTrue("the refusal was rewritten into the majors sentence: ${row.error}", "1.x" !in row.error.orEmpty())
        assertNoSessionWasOpened()
    }

    /**
     * a paused turn hands its session back to the daemon: exactly one `rho.session.resume` frame, naming the
     * session the turn stopped in — the id the row still holds afterwards — then `Result.retry()`, with the row
     * left exactly as a resume needs it: RUNNING, with that session id and the cursor the paused turn reported
     * still on it, no error text, its attempt untouched and no finishedAt. Scripted as an assistant line then
     * [DaemonScript.State] "paused" on an OUTLINE row over a mission seeded with onAppOpen() only. A coach that
     * paused mid-turn has not answered, so the next run continues this same turn (invariant 6) instead of asking
     * again. Nothing is stored, and the session is left open: no `rho.session.stop` frame, because the turn that
     * resumes needs the session it paused in.
     */
    @Test
    fun aPausedTurnSendsAResumeForTheSessionItStoppedIn() {
        daemon.script(
            DaemonScript.Assistant("The week is laid out; I need a moment before the plans."),
            DaemonScript.State("paused", "the coach stopped part-way through the turn"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Retry)

        // The turn this row resumes into really ran: a session was opened and a prompt went into it.
        frameFor("rho.session.create")
        assertEquals("$generationId-0", frameFor("rho.session.send_prompt")["client_id"]?.jsonPrimitive?.contentOrNull)

        val paused = daemon.log.single { it["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull == "paused" }
        val resumedSession = frameFor("rho.session.resume")["session_id"]?.jsonPrimitive?.contentOrNull
        assertEquals("the resume named a session other than the one the turn stopped in", daemon.sessionId, resumedSession)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.RUNNING, row.status)
        assertEquals("the row lost the session its paused turn stopped in", resumedSession, row.nabuSessionId)
        assertEquals("the row's cursor is not where the paused turn stopped", eventId(paused), row.lastEventId)
        assertNull("the retry left error text on a row that is still running", row.error)
        assertEquals("the retry bumped the attempt of a generation that ran one turn", 0, row.attempt)
        assertNull("the retry stamped a row that has not finished", row.finishedAt)

        // The half-reply this turn got to is not an answer yet, so nothing reached the mission.
        assertEquals(
            "a paused turn stored an outline",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        assertEquals("a paused turn stored sorties: ", emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        assertSessionLeftOpen()
    }

    /**
     * a resume the daemon refuses for good is swallowed the way a refused stop is: [FakeDaemon.refuseMethod]
     * answers `rho.session.resume` with a code in `RpcCodes.PERMANENT`, and the outcome is still
     * `Result.retry()` — a refusal of the resume never reaches the catch that ends a generation on a permanent
     * code. The row is not FAILED: still RUNNING with its session id and the cursor the paused turn reported,
     * `error` null rather than the daemon's words, its attempt unchanged, no finishedAt, nothing stored, and its
     * session left open. The next attempt reads the session's real state, which is what a swallowed refusal
     * leaves the row in place for.
     */
    @Test
    fun aRefusedResumeStillRetriesAndLeavesTheRowRunningWithItsSessionAndCursor() {
        daemon.refuseMethod["rho.session.resume"] = RpcCodes.INVALID_TRANSITION to "the session is not paused"
        daemon.script(
            DaemonScript.Assistant("The week is laid out; I need a moment before the plans."),
            DaemonScript.State("paused", "the coach stopped part-way through the turn"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Retry)

        val paused = daemon.log.single { it["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull == "paused" }
        val resumed = frameFor("rho.session.resume")
        assertEquals("the resume went into another session", daemon.sessionId, resumed["session_id"]?.jsonPrimitive?.contentOrNull)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.RUNNING, row.status)
        assertEquals("the row lost the session its paused turn stopped in", daemon.sessionId, row.nabuSessionId)
        assertEquals("the row's cursor is not where the paused turn stopped", eventId(paused), row.lastEventId)
        assertNull("a refused resume wrote the daemon's words onto the row", row.error)
        assertEquals("the retry bumped the attempt of a generation that ran one turn", 0, row.attempt)
        assertNull("a refused resume stamped a row that has not finished", row.finishedAt)

        assertEquals(
            "a refused resume stored an outline",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        assertEquals("a refused resume stored sorties: ", emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        assertSessionLeftOpen()
    }

    /**
     * a turn that ends in error is retried the same way: `Result.retry()`, the row still RUNNING with its session
     * id and cursor kept, `error` still null, its attempt unchanged, and no `rho.session.stop` frame. Scripted as
     * an assistant line then [DaemonScript.State] "error". The daemon's own failure is not the generation's: the
     * row is not FAILED and carries no error text, and the two fields a resume reads are left where the turn left
     * them, so the next run picks the same turn back up.
     */
    @Test
    fun aTurnThatEndsInErrorRetriesAndLeavesTheRowRunningWithItsSessionAndCursor() {
        daemon.script(
            DaemonScript.Assistant("Starting with Monday's lift, then—"),
            DaemonScript.State("error", "the coach's turn fell over"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Retry)

        frameFor("rho.session.create")
        assertEquals("$generationId-0", frameFor("rho.session.send_prompt")["client_id"]?.jsonPrimitive?.contentOrNull)

        val errored = daemon.log.single { it["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull == "error" }
        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.RUNNING, row.status)
        assertEquals("the row lost the session its errored turn stopped in", daemon.sessionId, row.nabuSessionId)
        assertEquals("the row's cursor is not where the errored turn stopped", eventId(errored), row.lastEventId)
        assertNull("the retry wrote the daemon's error onto a row that is still running", row.error)
        assertEquals("the retry bumped the attempt of a generation that ran one turn", 0, row.attempt)
        assertNull("the retry stamped a row that has not finished", row.finishedAt)

        assertEquals(
            "an errored turn stored an outline",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        assertEquals("an errored turn stored sorties: ", emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        assertSessionLeftOpen()
    }

    /**
     * offline mark: the settings the container reads point at a loopback port this test opened and closed, so
     * the connection check is refused and `Unreachable` is what the worker gets. It must show Result.Retry, the
     * row still RUNNING and not FAILED with finishedAt null and its session id and cursor as they were, error
     * exactly 'Mission Control offline', Generation.isCoachOffline true for that row, and nothing at all
     * reaching the scripted daemon — not even the hello the check would have sent, let alone a prompt.
     */
    @Test
    fun anUnreachableDaemonMarksTheGenerationOfflineAndRetries() {
        // A turn is queued up and never asked for: the daemon under test is the one nothing listens on.
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        // Seeded with an earlier attempt's session and cursor, so "as they were" is a position this row had.
        val generationId = runBlocking {
            container.database.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = mission.id,
                    status = GenerationStatus.QUEUED,
                    nabuSessionId = "session-of-the-earlier-attempt",
                    lastEventId = "cursor-of-the-earlier-attempt",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        pointSettingsAtNothing()

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Retry)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.RUNNING, row.status)
        assertNull("an unreachable daemon stamped the row as finished", row.finishedAt)
        assertEquals("an unreachable daemon moved the row off its session", "session-of-the-earlier-attempt", row.nabuSessionId)
        assertEquals("an unreachable daemon moved the row off its cursor", "cursor-of-the-earlier-attempt", row.lastEventId)
        assertEquals(0, row.attempt)

        assertEquals("the row does not carry the offline mark", Generation.COACH_OFFLINE, row.error)
        assertTrue("a row carrying '${Generation.COACH_OFFLINE}' is not offline", row.isCoachOffline)

        assertNothingReachedTheDaemon()
        assertEquals(
            "an unreachable daemon stored something",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
    }

    /**
     * the mark cleared: the row the name above leaves behind — RUNNING with 'Mission Control offline' on it —
     * run again with the settings pointed back at the live fake. It must show error null and
     * Generation.isCoachOffline false afterwards, the row not FAILED and carrying no 'coach unreachable' text,
     * and the generation running on to SUCCEEDED with its reply stored. What clears the mark is a daemon that
     * answered, not a generation that finished.
     */
    @Test
    fun aLaterRunThatReachesTheDaemonClearsTheOfflineMark() {
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        pointSettingsAtNothing()
        val offline = runWorker(generationId)
        assertTrue("the first run returned $offline", offline is ListenableWorker.Result.Retry)
        val marked = runBlocking { container.database.generationDao().get(generationId) }!!
        assertTrue("this test's own row carries no mark to clear: ${marked.error}", marked.isCoachOffline)

        pointSettingsAtTheLiveFake()

        val result = runWorker(generationId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertNull("the run that reached the daemon left the offline mark on the row", row.error)
        assertFalse("a row whose daemon answered is still offline", row.isCoachOffline)
        assertTrue("the row that reached the daemon carries a coach-unreachable message: ${row.error}", "coach unreachable" !in row.error.orEmpty())
        assertNotNull(row.finishedAt)

        assertEquals(
            "the reply the daemon answered with was not stored",
            MissionStatus.ACTIVE,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        val sorties = runBlocking { container.database.sortieDao().getForMission(mission.id) }
        assertNotNull("the stored outline left no current sortie: $sorties", currentSortie(sorties))
    }

    /**
     * the 8th attempt: the same closed port, with WorkManager's run-attempt count at 7 — WorkManager counts from
     * 0, so 7 is the 8th. It must show Result.Failure, the row FAILED with error exactly 'coach unreachable' and
     * finishedAt stamped, Generation.isCoachOffline false for it, and the row not left RUNNING with the offline
     * mark. The row's own attempt is a different counter and stays 0. The attempt before it is shown on a second
     * row so the threshold is the 8th rather than merely some number that fails: that one is still Retry, still
     * RUNNING and still carrying the mark.
     */
    @Test
    fun theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable() {
        // A turn is queued up and never asked for: the daemon under test is the one nothing listens on.
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = insertOutlineGeneration(mission.id)

        pointSettingsAtNothing()

        val result = runWorker(generationId, runAttemptCount = 7)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Failure)

        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.FAILED, row.status)
        assertEquals("coach unreachable", row.error)
        assertNotNull("the row that gave up was not stamped", row.finishedAt)
        assertFalse("the 8th attempt left the row RUNNING with the offline mark", row.isCoachOffline)
        assertEquals("the 8th attempt bumped the row's own attempt", 0, row.attempt)
        assertNull("the 8th attempt recorded a session it never reached", row.nabuSessionId)

        assertNothingReachedTheDaemon()
        assertEquals(
            "a generation that gave up stored something",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )

        // The 7th is still waiting, which is what makes 7 the 8th attempt and not just some number that fails.
        val seventhId = insertOutlineGeneration(mission.id)
        val seventh = runWorker(seventhId, runAttemptCount = 6)
        assertTrue("the 7th attempt returned $seventh", seventh is ListenableWorker.Result.Retry)
        val waiting = runBlocking { container.database.generationDao().get(seventhId) }!!
        assertEquals(GenerationStatus.RUNNING, waiting.status)
        assertEquals(Generation.COACH_OFFLINE, waiting.error)
        assertTrue("the 7th attempt is not offline", waiting.isCoachOffline)
        assertNull("the 7th attempt stamped a row that is still waiting", waiting.finishedAt)
    }

    /**
     * cancelled mid-turn: the scripted reply carries no end state, so the fake's turn stays open until the
     * cancel helper's `rho.session.stop` pushes the `completed` state_change that ends it. The worker reaches
     * its store holding a valid outline over a row that is already CANCELLED, and must store nothing, leave
     * the row CANCELLED, and return success so WorkManager is not asked to retry a row it may never write.
     * No stop-frame count is asserted here: the helper's stop and the worker's own are both on the wire.
     */
    @Test
    fun aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone() {
        // The helper cancels this generation's WorkManager work as well as its row, so this case needs
        // WorkManager up; every other case here builds its worker directly and never asks for it.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        try {
            daemon.script(DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)))

            val mission = runBlocking { container.missionManager.onAppOpen() }
            val generationId = insertOutlineGeneration(mission.id)

            val worker = TestListenableWorkerBuilder<GenerationWorker>(context)
                .setInputData(workDataOf(GenerationWorker.GENERATION_ID to generationId))
                .build()

            val result = runBlocking {
                withTimeout(turnWait) {
                    val running = async(Dispatchers.IO) { worker.doWork() }
                    // The helper stops only a session the row already carries, and the worker saves that id
                    // from its own write coroutine, so the prompt frame alone can arrive before there is a
                    // session for the helper to stop.
                    while (daemon.framesFor("rho.session.send_prompt").isEmpty() ||
                        container.database.generationDao().get(generationId)?.nabuSessionId == null
                    ) {
                        delay(20)
                    }
                    container.generationCanceller().cancel(generationId)
                    running.await()
                }
            }

            assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

            val row = runBlocking { container.database.generationDao().get(generationId) }!!
            assertEquals(GenerationStatus.CANCELLED, row.status)
            assertNotNull("a generation cancelled mid-turn lost its finishedAt stamp", row.finishedAt)
            assertEquals("the row was cancelled while its turn was still going", daemon.sessionId, row.nabuSessionId)

            val leftMission = runBlocking { container.database.missionDao().get(mission.id) }!!
            assertEquals("the cancelled generation stored its outline anyway", MissionStatus.DRAFT, leftMission.status)
            assertEquals(
                "the cancelled generation wrote sorties anyway",
                emptyList<Any>(),
                runBlocking { container.database.sortieDao().getForMission(mission.id) },
            )
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    /**
     * the enqueue the worker owes: with Generate run plans on before onAppOpen, storing this outline leaves
     * sortie 1 a current PENDING run that needs a generation, and the worker schedules work for the QUEUED
     * FLIGHT_PLAN row its own store wrote — under `gen-<that row's id>`, read back off WorkManager. WorkManager
     * is up here for that read alone; the enqueue is what the worker does after its store transaction has
     * committed, so the row it names is one WorkManager can already see.
     */
    @Test
    fun aWorkerThatStoresAnOutlineEnqueuesTheFlightPlanItQueuedForSortieOne() {
        // The worker's own enqueue reaches WorkManager, so this case needs it up; every other case here builds
        // its worker directly and never asks for it.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        try {
            // Before onAppOpen(): it is storeOutline's advance that prepares sortie 1, and it reads this setting then.
            runBlocking { container.settingsStore.setGenerateRunPlans(true) }
            daemon.script(
                DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
                DaemonScript.State("idle"),
            )

            val mission = runBlocking { container.missionManager.onAppOpen() }
            val generationId = insertOutlineGeneration(mission.id)

            val result = runWorker(generationId)
            assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

            val row = runBlocking { container.database.generationDao().get(generationId) }!!
            assertEquals(GenerationStatus.SUCCEEDED, row.status)
            assertEquals(
                "the generation that queued a Flight Plan stored no outline",
                MissionStatus.ACTIVE,
                runBlocking { container.database.missionDao().get(mission.id) }!!.status,
            )

            val sortie = sortieAt(mission.id, 0)
            assertEquals("sortie 1 is a RUN under RLRLR", SortieType.RUN, sortie.type)
            val queued = runBlocking { container.database.generationDao().getOpenFlightPlanForSortie(sortie.id) }
            assertEquals("sortie 1 was left needing something other than one Flight Plan row: $queued", 1, queued.size)
            val planRow = queued.single()
            assertEquals(GenerationKind.FLIGHT_PLAN, planRow.kind)
            assertEquals(GenerationStatus.QUEUED, planRow.status)
            assertEquals(mission.id, planRow.missionId)

            val scheduled = workInfosUnder("gen-${planRow.id}")
            assertEquals("nothing is scheduled for the Flight Plan row this queued: $planRow", 1, scheduled.size)
            assertEquals(WorkInfo.State.ENQUEUED, scheduled.single().state)
            val underItsOwnRow = workInfosUnder("gen-$generationId")
            assertTrue(
                "the worker scheduled work for its own outline row instead of the plan it queued: $underItsOwnRow",
                underItsOwnRow.isEmpty(),
            )
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    /**
     * superseded Flight Plan: two FLIGHT_PLAN rows on one sortie, and the older one asked to run first. Its turn
     * ends with a valid plan in hand, but this sortie is waiting for a newer generation, so it stores nothing —
     * the sortie is still PENDING with no plan — its row turns CANCELLED by running, and it returns success so
     * WorkManager does not keep asking the coach for a plan nobody wants. The newer row then runs on the same
     * sortie and stores its plan as any happy path does. The superseded row is inserted second, so it is the
     * newest id and the older generation: what makes it superseded is its `createdAt`, not the DAO's id
     * tie-break.
     */
    @Test
    fun aSupersededFlightPlanGenerationStoresNothingAndTheNewerGenerationStillStoresItsPlan() {
        // One reply per prompt: the fake hands out the next turn for each new prompt it takes.
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.LIFT_PLAN)),
            DaemonScript.State("idle"),
        )
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.LIFT_PLAN)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        runBlocking { container.missionManager.continueWithoutOutline(mission.id) }
        val scrubbed = sortieAt(mission.id, 0)
        assertEquals(SortieType.RUN, scrubbed.type)
        runBlocking { container.missionManager.scrub(scrubbed.id) }

        val lift = sortieAt(mission.id, 1)
        assertAwaitingGeneration(lift)

        val seededAt = System.currentTimeMillis()
        val currentId = insertFlightPlanGeneration(mission.id, lift.id, seededAt)
        val supersededId = insertFlightPlanGeneration(mission.id, lift.id, seededAt - SUPERSEDED_BY)
        assertTrue("the superseded row is also the newest id: $currentId, $supersededId", supersededId > currentId)

        val superseded = runWorker(supersededId)
        assertTrue("doWork() returned $superseded", superseded is ListenableWorker.Result.Success)

        // It ran its turn and reached the store with a valid reply; only the currency check stopped it.
        assertEquals("the superseded generation never reached its turn", 1, daemon.framesFor("rho.session.send_prompt").size)
        val supersededRow = runBlocking { container.database.generationDao().get(supersededId) }!!
        assertEquals(GenerationStatus.CANCELLED, supersededRow.status)
        assertNotNull("a superseded row was left without a finishedAt stamp", supersededRow.finishedAt)
        assertEquals("the superseded row lost the session its turn ran on", daemon.sessionId, supersededRow.nabuSessionId)

        assertAwaitingGeneration(lift)
        assertEquals(
            "the superseded generation wrote over the generation that replaced it",
            GenerationStatus.QUEUED,
            runBlocking { container.database.generationDao().get(currentId) }!!.status,
        )

        val result = runWorker(currentId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)
        assertStoredGeneratedPlan(currentId, lift.id)
        assertEquals("the current generation was answered with $supersededId's turn", 2, daemon.framesFor("rho.session.send_prompt").size)
    }

    /**
     * superseded outline: the same for two OUTLINE rows on one Mission, with no sortie anywhere. The older row
     * runs first, stores nothing — the Mission is left the DRAFT with no sorties it was — and reads CANCELLED
     * with Result.Success; the newer row then stores its outline and reads SUCCEEDED.
     */
    @Test
    fun aSupersededOutlineGenerationStoresNothingAndTheNewerGenerationStillStoresItsOutline() {
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )
        daemon.script(
            DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.OUTLINE)),
            DaemonScript.State("idle"),
        )

        val mission = runBlocking { container.missionManager.onAppOpen() }
        assertEquals("this starts from a DRAFT with no sorties", MissionStatus.DRAFT, runBlocking { container.database.missionDao().get(mission.id) }!!.status)
        assertEquals(emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(mission.id) })

        val seededAt = System.currentTimeMillis()
        val currentId = insertOutlineGeneration(mission.id, seededAt)
        val supersededId = insertOutlineGeneration(mission.id, seededAt - SUPERSEDED_BY)
        assertTrue("the superseded row is also the newest id: $currentId, $supersededId", supersededId > currentId)

        val superseded = runWorker(supersededId)
        assertTrue("doWork() returned $superseded", superseded is ListenableWorker.Result.Success)

        assertEquals("the superseded generation never reached its turn", 1, daemon.framesFor("rho.session.send_prompt").size)
        val supersededRow = runBlocking { container.database.generationDao().get(supersededId) }!!
        assertEquals(GenerationStatus.CANCELLED, supersededRow.status)
        assertNotNull("a superseded row was left without a finishedAt stamp", supersededRow.finishedAt)
        assertEquals("the superseded row lost the session its turn ran on", daemon.sessionId, supersededRow.nabuSessionId)

        assertEquals(
            "the superseded outline stored itself onto its Mission",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(mission.id) }!!.status,
        )
        assertEquals(
            "the superseded outline wrote sorties anyway",
            emptyList<Any>(),
            runBlocking { container.database.sortieDao().getForMission(mission.id) },
        )
        assertEquals(
            "the superseded generation wrote over the generation that replaced it",
            GenerationStatus.QUEUED,
            runBlocking { container.database.generationDao().get(currentId) }!!.status,
        )

        val result = runWorker(currentId)
        assertTrue("doWork() returned $result", result is ListenableWorker.Result.Success)

        val row = runBlocking { container.database.generationDao().get(currentId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals(GenerationKind.OUTLINE, row.kind)
        assertNull("an outline row carried a sortie", row.sortieId)
        assertNotNull(row.finishedAt)

        val storedMission = runBlocking { container.database.missionDao().get(mission.id) }!!
        assertEquals("the newer outline stored nothing", MissionStatus.ACTIVE, storedMission.status)
        assertNotNull(
            "the newer outline left no current sortie",
            currentSortie(runBlocking { container.database.sortieDao().getForMission(mission.id) }),
        )
        assertEquals("the current generation was answered with $supersededId's turn", 2, daemon.framesFor("rho.session.send_prompt").size)
    }

    /**
     * Points the settings the container reads at a loopback port nothing is listening on: a socket opened on a
     * free port and closed again, so the check is refused rather than waiting out its own 5-second connect
     * timeout. The host is the loopback address the socket was bound to, so the refusal is the port's own.
     */
    private fun pointSettingsAtNothing() = runBlocking {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        container.settingsStore.setDaemonHost("127.0.0.1")
        container.settingsStore.setDaemonPort(closedPort)
    }

    /** Back at the live fake, for the run that follows. */
    private fun pointSettingsAtTheLiveFake() = runBlocking {
        container.settingsStore.setDaemonHost(daemon.host())
        container.settingsStore.setDaemonPort(daemon.port())
    }

    /**
     * [runAttemptCount] is WorkManager's own count of how many times this row has been run, 0 for the first;
     * the row's `attempt` is a different counter and the worker never sees it as this.
     */
    private fun runWorker(generationId: Long, runAttemptCount: Int = 0): ListenableWorker.Result = runBlocking {
        withTimeout(turnWait) {
            TestListenableWorkerBuilder<GenerationWorker>(context)
                .setInputData(workDataOf(GenerationWorker.GENERATION_ID to generationId))
                .setRunAttemptCount(runAttemptCount)
                .build()
                .doWork()
        }
    }

    /** What WorkManager holds under a unique name. The wait is bounded so a stuck read cannot eat the build's timeout. */
    private fun workInfosUnder(name: String): List<WorkInfo> =
        runBlocking { withTimeout(WORK_READ_BOUND_MILLIS) { WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).await() } }

    private fun sortieAt(missionId: Long, index: Int): Sortie =
        runBlocking { container.database.sortieDao().getForMission(missionId).single { it.index == index } }

    private fun insertFlightPlanGeneration(
        missionId: Long,
        sortieId: Long,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = runBlocking {
        container.database.generationDao().insert(
            Generation(
                kind = GenerationKind.FLIGHT_PLAN,
                missionId = missionId,
                sortieId = sortieId,
                status = GenerationStatus.QUEUED,
                createdAt = createdAt,
            ),
        )
    }

    /** A QUEUED outline row over [missionId]: what every case that never reaches a turn is asked about. */
    private fun insertOutlineGeneration(missionId: Long, createdAt: Long = System.currentTimeMillis()): Long = runBlocking {
        container.database.generationDao().insert(
            Generation(
                kind = GenerationKind.OUTLINE,
                missionId = missionId,
                status = GenerationStatus.QUEUED,
                createdAt = createdAt,
            ),
        )
    }

    /**
     * What the worker is starting from: this sortie is the current one and nothing has planned it yet, so the
     * PLANNED state and the stored plan below are both things this run did. `storePlan` refuses a sortie that
     * is not the current one, so a case that did not check this would pass on a plan continueWithoutOutline() had already
     * written.
     */
    private fun assertAwaitingGeneration(sortie: Sortie) {
        val current = currentSortie(runBlocking { container.database.sortieDao().getForMission(sortie.missionId) })
        assertNotNull("no sortie is current in this mission", current)
        assertEquals("the worker is asked about the current sortie", sortie.id, current!!.id)
        assertEquals(SortieState.PENDING, runBlocking { container.database.sortieDao().get(sortie.id) }!!.state)
        assertNull("the app already wrote a plan on this sortie", runBlocking { container.database.flightPlanDao().getPlan(sortie.id) })
    }

    /** The row's end state, the sortie it planned, and the plan it stored — with no claim about the frames. */
    private fun assertStoredGeneratedPlan(generationId: Long, sortieId: Long) {
        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.SUCCEEDED, row.status)
        assertEquals(GenerationKind.FLIGHT_PLAN, row.kind)
        assertEquals(sortieId, row.sortieId)
        assertNotNull(row.finishedAt)

        assertEquals("the sortie the row named", SortieState.PLANNED, runBlocking { container.database.sortieDao().get(sortieId) }!!.state)
        val plan = runBlocking { container.database.flightPlanDao().getPlan(sortieId) }
        assertNotNull("no FlightPlan was stored on sortie $sortieId", plan)
        assertEquals(FlightPlanSource.GENERATED, plan!!.plan.source)
    }

    /** The row's end state, the sortie it planned, the plan it stored, and the labels it asked the session with. */
    private fun assertPlannedWithAGeneratedPlan(generationId: Long, sortieId: Long) {
        assertStoredGeneratedPlan(generationId, sortieId)

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

    /**
     * The frame a permanent end leaves behind: exactly one `rho.session.stop`, naming the session this
     * generation ran on. A generation that died gives its session up just as one that finished does.
     */
    private fun assertSessionGivenUpOnce(sessionId: String) {
        val stopped = frameFor("rho.session.stop")
        assertEquals(
            "the stop gave up a session other than the one this generation ran on",
            sessionId,
            stopped["session_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** What a retry leaves behind instead: no `rho.session.stop`, because the session is where it resumes. */
    private fun assertSessionLeftOpen() {
        assertTrue(
            "a retry gave up the session it stopped in: ${daemon.framesFor("rho.session.stop")}",
            daemon.framesFor("rho.session.stop").isEmpty(),
        )
    }

    /**
     * The row a protocol mismatch leaves behind, returned so a case can read its error too: FAILED with
     * [error], stamped, with no session id — the check hung up its own socket without opening one — and
     * nothing stored.
     */
    private fun mismatchFailedRow(generationId: Long, missionId: Long, error: String): Generation {
        val row = runBlocking { container.database.generationDao().get(generationId) }!!
        assertEquals(GenerationStatus.FAILED, row.status)
        assertEquals(error, row.error)
        assertNotNull(row.finishedAt)
        assertNull("a mismatch opened a session anyway", row.nabuSessionId)
        assertEquals(
            "a mismatch stored something",
            MissionStatus.DRAFT,
            runBlocking { container.database.missionDao().get(missionId) }!!.status,
        )
        assertEquals("a mismatch stored something: ", emptyList<Any>(), runBlocking { container.database.sortieDao().getForMission(missionId) })
        return row
    }

    /**
     * What a mismatch leaves on the wire: the hello the connection check sent on its own socket, and nothing
     * else — no session created, no prompt, and no session given up, because a mismatch never opened one.
     */
    private fun assertNoSessionWasOpened() {
        assertTrue(
            "the worker never asked the daemon what protocol it speaks: ${daemon.frames}",
            daemon.framesFor("rho.hello").isNotEmpty(),
        )
        assertTrue("a mismatch created a session: ${daemon.framesFor("rho.session.create")}", daemon.framesFor("rho.session.create").isEmpty())
        assertTrue("a mismatch sent a prompt: ${daemon.framesFor("rho.session.send_prompt")}", daemon.framesFor("rho.session.send_prompt").isEmpty())
        assertTrue("a mismatch gave up a session it never opened: ${daemon.framesFor("rho.session.stop")}", daemon.framesFor("rho.session.stop").isEmpty())
    }

    /** The params of the one frame this worker sent calling [method], failing if there was not exactly one. */
    private fun frameFor(method: String): JsonObject {
        val frames = daemon.framesFor(method)
        assertEquals("expected exactly one $method frame, got $frames", 1, frames.size)
        val params = RhoJson.decodeFromString(Rpc.serializer(), frames.first()).params?.jsonObject
        assertTrue("the $method frame carried no params: ${frames.first()}", params != null)
        return params!!
    }

    /**
     * The params of the first frame this worker sent calling [method], failing if it sent none. A resumed turn
     * reads more than once, so a case that cares which read came first cannot use [frameFor].
     */
    private fun firstFrameFor(method: String): JsonObject {
        val frames = daemon.framesFor(method)
        assertTrue("no $method frame was sent: ${daemon.frames}", frames.isNotEmpty())
        val params = RhoJson.decodeFromString(Rpc.serializer(), frames.first()).params?.jsonObject
        assertTrue("the $method frame carried no params: ${frames.first()}", params != null)
        return params!!
    }

    /** How many user messages in the fake's log carry [clientId] — the prompt a daemon that dedupes by client_id keeps to one. */
    private fun userMessagesFor(clientId: String): Int = daemon.log.count {
        val data = it["data"]?.jsonObject
        data?.get("role")?.jsonPrimitive?.contentOrNull == "user" &&
            data["client_id"]?.jsonPrimitive?.contentOrNull == clientId
    }

    /** Every `rho.session.send_prompt` frame's params, in the order the daemon received them. */
    private fun promptsSent(): List<JsonObject> = daemon.framesFor("rho.session.send_prompt").map { frame ->
        val params = RhoJson.decodeFromString(Rpc.serializer(), frame).params?.jsonObject
        assertTrue("a prompt frame carried no params: $frame", params != null)
        params!!
    }

    /** The calls the worker made, in the order the daemon received them. */
    private fun methodsSent(): List<String> =
        daemon.frames.map { RhoJson.decodeFromString(Rpc.serializer(), it).method.orEmpty() }

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
        // How far apart a superseded generation and the one that replaced it are seeded, so which of two rows
        // for one target is current is decided by createdAt and not by the DAO's id tie-break.
        const val SUPERSEDED_BY = 3_600_000L
        // The bound on a WorkManager read, so a read that never answers fails rather than hangs.
        const val WORK_READ_BOUND_MILLIS = 5_000L
    }
}
