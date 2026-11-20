package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.StoreOutcome
import com.liftoff.app.settings.SettingsStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launching from the Launchpad, through its view model and in-memory Room. The cancel lambda records the id it is
 * handed and marks the row CANCELLED the way `GenerationCanceller` marks it, so a cancel read back is a real row —
 * but it is never the real canceller, which reaches for WorkManager and a daemon this harness has neither of. The
 * gate the lambda parks on stands in for that daemon round trip: it stays shut until the test opens it, so the test
 * can say whether Launch waited for its cancels or navigated through them. View model and DAO only, the harness
 * being LaunchpadRegenerateTest's: no Compose, no WorkManager, no daemon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadLaunchTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var generationDao: GenerationDao
    private lateinit var sortieDao: SortieDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val queuedIds = CopyOnWriteArrayList<Long>()
    private val cancelledIds = CopyOnWriteArrayList<Long>()
    private val launchedIds = CopyOnWriteArrayList<Long>()
    private val escaped = CopyOnWriteArrayList<Throwable>()

    /** Opened only once Launch has already navigated; until then every cancel is parked on it. */
    private val cancelGate = CompletableDeferred<Unit>()

    private val screenJob = Job()
    private val actionJob = Job()

    // The screen's scope and the action scope are separate objects, so a cancel started on the wrong one is a
    // difference this class can see. Both are Unconfined, so each begins its work the moment it is handed it.
    private val screenScope = CoroutineScope(Dispatchers.Unconfined + screenJob + CoroutineExceptionHandler { _, t -> escaped.add(t) })
    private val actionScope = CoroutineScope(Dispatchers.Unconfined + actionJob + CoroutineExceptionHandler { _, t -> escaped.add(t) })

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        generationDao = db.generationDao()
        sortieDao = db.sortieDao()

        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = MissionManager(db, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
    }

    @After
    fun tearDown() {
        // stateIn(Lazily) keeps the Room flow collecting for the scope's lifetime.
        screenJob.cancel()
        actionJob.cancel()
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    /** The view model under test, its two callbacks recording the ids they were handed. */
    private fun newViewModel(): LaunchpadViewModel = LaunchpadViewModel(
        db = db,
        manager = manager,
        scope = screenScope,
        queueGeneration = { queuedIds.add(it) },
        cancelGeneration = { id ->
            // The row is marked cancelled as GenerationCanceller marks it, so a cancel read back is real. The gate
            // is the daemon round trip it would then make, and it is the reason Launch must not wait here.
            cancelledIds.add(id)
            cancelGate.await()
            generationDao.markCancelled(id, clock.millis())
        },
        actionScope = actionScope,
    )

    /** The PLANNED sortie, the open FLIGHT_PLAN row it still holds, and the view model over them. */
    private class LaunchFixture(
        val viewModel: LaunchpadViewModel,
        val mission: Mission,
        val sortie: Sortie,
        val openRow: Generation,
    )

    /**
     * The week Launch is pressed on, built the way LaunchpadRegenerateTest builds one: settings first, because
     * onAppOpen reads defaultPattern for the new Mission, then the week activated with no outline. Its current
     * sortie is a LIFT, so activating the week leaves one open QUEUED FLIGHT_PLAN row on it — the turn a Regenerate
     * would be superseding — and a validated coach plan then moves that sortie to PLANNED without closing the row.
     * The view model is built after all of it, so the first card it derives is the Planned one Launch is pressed on.
     */
    private suspend fun plannedSortieHoldingAnOpenPlan(): LaunchFixture {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        settingsStore.setDefaultPattern("LRL")
        val mission = manager.onAppOpen()
        manager.continueWithoutOutline(mission.id)
        val sortie = sortieDao.getForMission(mission.id).first { it.index == 0 }
        assertEquals(
            StoreOutcome.Stored,
            manager.storePlan(
                sortie.id,
                CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
            ),
        )
        val rows = generationDao.getOpenFlightPlanForSortie(sortie.id)
        assertEquals(1, rows.size)
        return LaunchFixture(newViewModel(), mission, sortie, rows.single())
    }

    /**
     * The Launchpad's Planned card for [sortie]. launch reads state.value, so a card that has not been derived yet
     * would make the action a silent no-op and every read-back below trivially true.
     */
    private suspend fun awaitPlanned(vm: LaunchpadViewModel, sortie: Sortie): LaunchpadState.Planned {
        val state = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Planned } }
        val planned = state as LaunchpadState.Planned
        assertEquals(sortie.id, planned.sortieId)
        return planned
    }

    /**
     * The moment Launch handed the sortie to the shell. launch returns Unit — the cancels are deliberately not part
     * of any Job a test could join — so the wait is on the callback, the way LaunchpadScrubTest waits on the row.
     */
    private suspend fun awaitLaunched(vm: LaunchpadViewModel, sortie: Sortie) {
        try {
            withTimeout(5_000L) {
                while (launchedIds.isEmpty()) delay(25)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "Launch never navigated for sortie ${sortie.id}; state=${vm.state.value} " +
                    "row=${sortieDao.get(sortie.id)} cancelled=$cancelledIds escaped=$escaped"
            )
        }
    }

    /** The sortie row once Launch has written it IN_FLIGHT. */
    private suspend fun awaitInFlight(vm: LaunchpadViewModel, sortie: Sortie): Sortie {
        var row: Sortie? = null
        try {
            withTimeout(5_000L) {
                row = sortieDao.get(sortie.id)
                while (row == null || row!!.state != SortieState.IN_FLIGHT) {
                    delay(25)
                    row = sortieDao.get(sortie.id)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "sortie ${sortie.id} never became IN_FLIGHT; last=$row state=${vm.state.value} " +
                    "launched=$launchedIds cancelled=$cancelledIds escaped=$escaped"
            )
        }
        return row!!
    }

    /**
     * The cancel had begun. Asked for rather than asserted straight after the completion callback: the action scope
     * is Unconfined, and a nested unconfined loop defers a launch it is asked to start, so a cancel can begin a
     * moment after the callback rather than before it. Asked for while the gate is still shut, so what it proves is
     * still that a cancel is parked there and not skipped.
     */
    private suspend fun awaitCancelStarted(id: Long, cancelledIds: List<Long>) {
        try {
            withTimeout(5_000L) {
                while (id !in cancelledIds) delay(25)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("no cancel was started for generation $id; cancelled=$cancelledIds escaped=$escaped")
        }
    }

    /** The generation row once its cancel has marked it CANCELLED. */
    private suspend fun awaitCancelledPlan(vm: LaunchpadViewModel, row: Generation): Generation {
        var current: Generation? = null
        try {
            withTimeout(5_000L) {
                current = generationDao.get(row.id)
                while (current == null || current!!.status != GenerationStatus.CANCELLED) {
                    delay(25)
                    current = generationDao.get(row.id)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "generation ${row.id} never read back CANCELLED; last=$current state=${vm.state.value} " +
                    "cancelled=$cancelledIds escaped=$escaped"
            )
        }
        return current!!
    }

    // A PLANNED sortie still holding the open FLIGHT_PLAN row its Regenerate left on it. Pressing Launch cancels
    // that row — the id reaches the cancel lambda and the row reads back CANCELLED from Room — and Launch still
    // navigates: the row is still open at the moment onLaunched has already fired, so Launch cannot have waited for
    // a cancel that was parked on the gate the whole time.
    @Test
    fun launchFromTheLaunchpadCancelsTheSortiesOpenFlightPlanGenerationAndStillNavigates() = runBlocking {
        val fixture = plannedSortieHoldingAnOpenPlan()
        val vm = fixture.viewModel
        awaitPlanned(vm, fixture.sortie)
        assertEquals(GenerationStatus.QUEUED, generationDao.get(fixture.openRow.id)!!.status)

        vm.launch { launchedIds.add(it) }

        awaitLaunched(vm, fixture.sortie)
        assertEquals(listOf(fixture.sortie.id), launchedIds.toList())

        // The cancel has started on the action scope, and it is still parked there while Launch has navigated.
        awaitCancelStarted(fixture.openRow.id, cancelledIds)
        assertEquals(GenerationStatus.QUEUED, generationDao.get(fixture.openRow.id)!!.status)

        cancelGate.complete(Unit)
        val cancelled = awaitCancelledPlan(vm, fixture.openRow)
        assertEquals(fixture.openRow.id, cancelled.id)
        assertEquals(fixture.sortie.id, cancelled.sortieId)
        assertEquals(GenerationStatus.CANCELLED, cancelled.status)

        val inFlight = awaitInFlight(vm, fixture.sortie)
        assertEquals(SortieState.IN_FLIGHT, inFlight.state)
        val card = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.InFlight } }
        assertEquals(fixture.sortie.id, (card as LaunchpadState.InFlight).sortieId)

        // Launch schedules nothing, and the row it cancelled is not handed to the enqueuer either.
        assertTrue(queuedIds.isEmpty())
        assertTrue(escaped.isEmpty())
    }

    private companion object {
        const val VALID_HOST = "127.0.0.1"
        const val VALID_TOKEN = "token-under-test"
    }
}
