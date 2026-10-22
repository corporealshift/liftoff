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
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.MissionStatus
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

    private fun runWorker(generationId: Long): ListenableWorker.Result = runBlocking {
        withTimeout(turnWait) {
            TestListenableWorkerBuilder<GenerationWorker>(context)
                .setInputData(workDataOf(GenerationWorker.GENERATION_ID to generationId))
                .build()
                .doWork()
        }
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
    }
}
