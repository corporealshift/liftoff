package com.liftoff.app.work

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import com.liftoff.app.LiftoffApplication
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.rho.FakeDaemon
import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.protocol.RhoJson
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
 * The cancel helper's harness, which the later cancel cases reuse: the application's own container, a scripted
 * [FakeDaemon] whose address the container reads out of its `settingsStore`, and WorkManager initialised the way
 * [GenerationEnqueuerTest] does — the helper cancels work as well as marking the row, so the work has to be real
 * work that can be read back.
 *
 * The work is scheduled through `container.generationEnqueuer()` first, so what the cancel cancels is the enqueuer's
 * own unique work and not a name this test made up. The network constraint stays unmet, so no worker ever runs
 * here: everything the daemon hears came from the helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationCancellerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The application's own container: a second AppContainer would open a second DataStore and Room on the same files. */
    private val container get() = (context as LiftoffApplication).container

    private lateinit var daemon: FakeDaemon

    /** Long enough for a stop the fake answers at once, short enough to fail rather than hang. */
    private val cancelWait = 30.seconds

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        runBlocking {
            daemon = FakeDaemon().also { it.start() }
            container.settingsStore.setDaemonHost(daemon.host())
            container.settingsStore.setDaemonPort(daemon.port())
            container.settingsStore.setDaemonToken(SECRET_TOKEN)
            container.settingsStore.setCoachWorkspacePath(COACH_WORKSPACE)
        }
    }

    @After
    fun tearDown() {
        daemon.stop()
        WorkManagerTestInitHelper.closeWorkDatabase()
        container.database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.preferencesDataStoreFile(SETTINGS_FILE).delete()
    }

    /**
     * A row that is still going and has a session to give back: the row reads CANCELLED with `finishedAt` stamped,
     * exactly one `rho.session.stop` reached the fake and names the session the row carried, the work the enqueuer
     * scheduled under that generation's own name now reads CANCELLED, and nothing was stored — the mission is still
     * the DRAFT with no sorties that onAppOpen() left.
     */
    @Test
    fun cancellingAGenerationStopsItsSessionAndMarksTheRowCancelled() {
        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = seedQueuedGeneration(mission, daemon.sessionId)

        val scheduled = container.generationEnqueuer().enqueue(generationId)
        assertEquals(
            "the enqueuer's work should still be waiting before the cancel",
            WorkInfo.State.ENQUEUED,
            workUnder(generationId).single().state,
        )

        cancel(generationId)

        val row = readGeneration(generationId)
        assertEquals(GenerationStatus.CANCELLED, row.status)
        assertNotNull("a cancelled row has to say when it ended", row.finishedAt)

        val stopped = frameFor("rho.session.stop")
        assertEquals(
            "the stop named the session the row carried, not one the fake made up",
            daemon.sessionId,
            stopped["session_id"]?.jsonPrimitive?.contentOrNull,
        )

        val items = workUnder(generationId)
        assertEquals(1, items.size)
        assertEquals(WorkInfo.State.CANCELLED, items.single().state)
        assertEquals("the cancelled work is the item the enqueuer scheduled", scheduled.id, items.single().id)

        assertNothingStored(mission)
    }

    /**
     * The same cancel with nothing to connect to: the settings the container reads are pointed at a loopback port
     * this test opened and closed, so the connect is refused. The call returns without throwing, and the row and
     * its work are still cancelled — the local half happens whatever the daemon does.
     */
    @Test
    fun cancellingAGenerationWithTheDaemonUnreachableStillCancelsTheRowAndItsWork() {
        val mission = runBlocking { container.missionManager.onAppOpen() }
        val generationId = seedQueuedGeneration(mission, daemon.sessionId)
        container.generationEnqueuer().enqueue(generationId)

        // Built while the fake is still reachable: the client is asked for at stop time, so this cancel still
        // reads the settings it is pointed at when it tries to stop the session.
        val canceller = container.generationCanceller()
        pointSettingsAtNothing()

        runBlocking { withTimeout(cancelWait) { canceller.cancel(generationId) } }

        val row = readGeneration(generationId)
        assertEquals(GenerationStatus.CANCELLED, row.status)
        assertNotNull("a cancelled row has to say when it ended", row.finishedAt)

        val items = workUnder(generationId)
        assertEquals(1, items.size)
        assertEquals(WorkInfo.State.CANCELLED, items.single().state)

        assertTrue(
            "an unreachable daemon still got a stop attempt: ${daemon.framesFor("rho.session.stop")}",
            daemon.framesFor("rho.session.stop").isEmpty(),
        )
        assertNothingStored(mission)
    }

    /** A QUEUED outline row over [mission], carrying [sessionId] as the session its worker opened. */
    private fun seedQueuedGeneration(mission: Mission, sessionId: String): Long = runBlocking {
        container.database.generationDao().insert(
            Generation(
                kind = GenerationKind.OUTLINE,
                missionId = mission.id,
                status = GenerationStatus.QUEUED,
                nabuSessionId = sessionId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun cancel(generationId: Long) = runBlocking {
        withTimeout(cancelWait) { container.generationCanceller().cancel(generationId) }
    }

    private fun readGeneration(generationId: Long): Generation {
        val row = readBounded { container.database.generationDao().get(generationId) }
        assertTrue("generation $generationId has no row to read back", row != null)
        return row!!
    }

    /**
     * An outline was the only thing this generation could have stored, and storing one activates the Mission and
     * writes its sorties. Both are still absent, so nothing this generation produced reached the app.
     */
    private fun assertNothingStored(mission: Mission) {
        val stored = readBounded { container.database.missionDao().get(mission.id) }!!
        assertEquals("the cancel stored an outline over this Mission", MissionStatus.DRAFT, stored.status)
        assertEquals("the cancel wrote sorties", emptyList<Any>(), readBounded { container.database.sortieDao().getForMission(mission.id) })
    }

    /**
     * Points the settings the container reads at a loopback port nothing is listening on: a socket opened on a free
     * port and closed again, so the connect is refused rather than waiting out the client's own connect timeout.
     */
    private fun pointSettingsAtNothing() = runBlocking {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        container.settingsStore.setDaemonHost("127.0.0.1")
        container.settingsStore.setDaemonPort(closedPort)
    }

    /**
     * What WorkManager has under this generation's unique name. Spelled out here rather than shared with the
     * helper, so a name that stops carrying the generation id fails instead of matching itself.
     */
    private fun workUnder(generationId: Long): List<WorkInfo> = runBlocking {
        withTimeout(READ_BOUND_MILLIS.seconds) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork("gen-$generationId").await()
        }
    }

    /** The params of the one frame this helper sent calling [method], failing if there was not exactly one. */
    private fun frameFor(method: String): JsonObject {
        val frames = daemon.framesFor(method)
        assertEquals("expected exactly one $method frame, got $frames", 1, frames.size)
        val params = RhoJson.decodeFromString(Rpc.serializer(), frames.first()).params?.jsonObject
        assertTrue("the $method frame carried no params: ${frames.first()}", params != null)
        return params!!
    }

    private fun <T> readBounded(block: suspend () -> T): T =
        runBlocking { withTimeout(READ_BOUND_MILLIS.seconds) { block() } }

    private companion object {
        const val SECRET_TOKEN = "sekrit"
        const val COACH_WORKSPACE = "/repo/coach"
        const val DATABASE_NAME = "liftoff.db"
        const val SETTINGS_FILE = "settings"

        /** Every wait in this class is bounded, so a stuck read or a stuck stop fails rather than eating the build's timeout. */
        const val READ_BOUND_MILLIS = 5
    }
}
