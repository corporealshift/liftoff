package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.FlightPlanDao
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionDao
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.StoreOutcome
import com.liftoff.app.domain.currentWeekStart
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [LaunchpadViewModel.regenerate] on the three states where asking succeeds: the open FLIGHT_PLAN row a second ask
 * supersedes, the FAILED row it leaves alone, and the PLANNED sortie whose plan it must not touch. View model and
 * DAO only — the harness is LaunchpadConfirmTest's, so nothing here touches Compose, WorkManager or a daemon, and
 * the failed row is seeded straight through the generation DAO rather than by a worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadRegenerateTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var generationDao: GenerationDao
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val queuedIds = CopyOnWriteArrayList<Long>()
    private val cancelledIds = CopyOnWriteArrayList<Long>()
    private val escaped = CopyOnWriteArrayList<Throwable>()

    private val job = Job()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, t -> escaped.add(t) }
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        generationDao = db.generationDao()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = MissionManager(db, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
    }

    @After
    fun tearDown() {
        // stateIn(Lazily) keeps the Room flow collecting for the scope's lifetime.
        job.cancel()
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    /**
     * The week a regenerate is asked on, built the way the screen builds it: settings first, because onAppOpen
     * reads defaultPattern for the new Mission, then the week activated with no outline. That leaves one open
     * QUEUED FLIGHT_PLAN row on the current sortie — the row a second ask has to supersede — and its id is not
     * recorded here, because the manager is called directly with its default onQueued. The pattern starts with L
     * so the current sortie is a LIFT, the only kind Mission Control is asked about while run generation is off.
     */
    private suspend fun activeWeek(): Triple<LaunchpadViewModel, Long, Sortie> {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        settingsStore.setDefaultPattern("LRL")
        manager.onAppOpen()
        val missionId = missionDao.getByWeekStart(currentWeekStart(clock, ZoneOffset.UTC))!!.id
        manager.continueWithoutOutline(missionId)
        val sortie = sortieDao.getForMission(missionId).first { it.index == 0 }
        val vm = LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { queuedIds.add(it) },
            cancelGeneration = {
                // The row is marked cancelled as GenerationCanceller marks it, so a cancel read back is real.
                cancelledIds.add(it)
                generationDao.markCancelled(it, clock.millis())
            },
        )
        return Triple(vm, missionId, sortie)
    }

    /** A null Job is the action never having started, which would make every assertion below vacuous. */
    private suspend fun joinAction(launched: Job?, vm: LaunchpadViewModel) {
        val started = launched ?: throw AssertionError("action returned no Job; state=${vm.state.value}")
        withTimeout(5_000L) { started.join() }
    }

    /**
     * The state the action needs first: regenerate returns null on any other state, so a card that has not been
     * derived yet would make the action a silent no-op and every read-back below trivially true.
     */
    private suspend fun awaitPending(vm: LaunchpadViewModel, sortie: Sortie): LaunchpadState.Pending {
        val state = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Pending } }
        val pending = state as LaunchpadState.Pending
        assertEquals(sortie.id, pending.sortieId)
        return pending
    }

    private suspend fun awaitPlanned(vm: LaunchpadViewModel, sortie: Sortie): LaunchpadState.Planned {
        val state = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Planned } }
        val planned = state as LaunchpadState.Planned
        assertEquals(sortie.id, planned.sortieId)
        return planned
    }

    /** The one open FLIGHT_PLAN row activating the week left on this sortie. */
    private suspend fun openPlanRowFor(sortieId: Long): Generation {
        val rows = generationDao.getOpenFlightPlanForSortie(sortieId)
        assertEquals(1, rows.size)
        return rows.single()
    }

    /**
     * The id the enqueue lambda was handed, read back as a QUEUED FLIGHT_PLAN row on this Mission and this
     * sortie, so the id recorded is proved to be a row rather than a number the action made up.
     */
    private suspend fun queuedRowEnqueued(missionId: Long, sortieId: Long): Generation {
        assertEquals(1, queuedIds.size)
        val row = generationDao.get(queuedIds.single())
            ?: throw AssertionError("enqueued id ${queuedIds.single()} has no generation row")
        assertEquals(GenerationKind.FLIGHT_PLAN, row.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(missionId, row.missionId)
        assertEquals(sortieId, row.sortieId)
        return row
    }

    // Supersede: asking twice on a PENDING current sortie queues one fresh row and hands back the row that was
    // still open on that sortie. The older row is pushed back in time first, so "older" is the DAO's createdAt
    // order and not merely the order the ids came out in.
    @Test
    fun regenerateOnAPendingSortieCancelsItsOpenRowAndEnqueuesTheNewQueuedRow() = runBlocking {
        val (vm, missionId, sortie) = activeWeek()
        val older = openPlanRowFor(sortie.id)
        generationDao.update(older.copy(createdAt = clock.millis() - 60_000))

        // A second row of this Mission, on a sortie this ask is not about, so "nothing else changed" names a row
        // the operation had to leave alone rather than an empty set.
        val otherSortie = sortieDao.getForMission(missionId).first { it.index == 2 }
        val otherId = generationDao.insert(
            Generation(
                kind = GenerationKind.FLIGHT_PLAN,
                missionId = missionId,
                sortieId = otherSortie.id,
                status = GenerationStatus.QUEUED,
                createdAt = clock.millis() - 120_000,
            )
        )

        val before = generationDao.getAll()
        awaitPending(vm, sortie)

        joinAction(vm.regenerate(), vm)

        assertEquals(1, queuedIds.size)
        assertEquals(1, cancelledIds.size)
        val enqueued = queuedRowEnqueued(missionId, sortie.id)
        assertTrue(enqueued.id != cancelledIds.single())

        assertEquals(older.id, cancelledIds.single())
        val cancelled = generationDao.get(older.id)!!
        assertEquals(GenerationStatus.CANCELLED, cancelled.status)
        assertTrue(older.id !in queuedIds)
        assertTrue(otherId !in queuedIds)
        assertTrue(otherId !in cancelledIds)

        val after = generationDao.getAll()
        assertEquals(before.size + 1, after.size)
        for (row in before.filter { it.id != older.id }) {
            assertEquals(row, after.first { it.id == row.id })
        }
        assertTrue(escaped.isEmpty())
    }

    // The same action is the Retry a failed Flight Plan needs: a FAILED row is not open, so nothing is cancelled
    // and only the fresh row is enqueued. The failure is seeded through the generation DAO, which is how a failure
    // reaches a row in production — no worker and no daemon.
    @Test
    fun regenerateAfterAFailedFlightPlanQueuesAFreshRowAndLeavesTheFailedRowAlone() = runBlocking {
        val (vm, missionId, sortie) = activeWeek()
        val failed = openPlanRowFor(sortie.id)
        generationDao.markFailed(failed.id, FAILED_ERROR, clock.millis() - 30_000)

        val before = awaitPending(vm, sortie)

        joinAction(vm.regenerate(), vm)

        val enqueued = queuedRowEnqueued(missionId, sortie.id)
        assertTrue(enqueued.id != failed.id)
        assertEquals(listOf(enqueued.id), queuedIds.toList())

        val kept = generationDao.get(failed.id)!!
        assertEquals(GenerationStatus.FAILED, kept.status)
        assertEquals(FAILED_ERROR, kept.error)
        assertEquals(clock.millis() - 30_000, kept.finishedAt!!)
        assertTrue(failed.id !in cancelledIds)
        assertTrue(cancelledIds.isEmpty())

        val after = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Pending } }
        assertEquals(before, after)
        assertTrue(escaped.isEmpty())
    }

    // A PLANNED current LIFT sortie — moved there by a validated coach plan, the way MissionManagerPlanTest does:
    // the ask queues a fresh row and never touches the plan the sortie already holds, so the card is still Planned
    // and still carries that plan.
    @Test
    fun regenerateOnAPlannedSortieKeepsItsPlanAndStaysPlanned() = runBlocking {
        val (vm, missionId, sortie) = activeWeek()
        val older = openPlanRowFor(sortie.id)
        generationDao.update(older.copy(createdAt = clock.millis() - 60_000))

        val stored = manager.storePlan(
            sortie.id,
            CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
        )
        assertEquals(StoreOutcome.Stored, stored)

        val planned = awaitPlanned(vm, sortie)
        val planBefore = flightPlanDao.getPlan(sortie.id)!!

        joinAction(vm.regenerate(), vm)

        val enqueued = queuedRowEnqueued(missionId, sortie.id)
        assertTrue(enqueued.id != older.id)
        assertEquals(listOf(older.id), cancelledIds.toList())
        assertEquals(GenerationStatus.CANCELLED, generationDao.get(older.id)!!.status)

        val planAfter = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(planBefore, planAfter)
        assertEquals(planBefore.plan.title, planAfter.plan.title)
        assertEquals(
            planBefore.exercises.map { it.displayName },
            planAfter.exercises.map { it.displayName },
        )
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)

        val stillPlanned = withTimeout(5_000L) {
            vm.state.first { it is LaunchpadState.Planned }
        } as LaunchpadState.Planned
        assertEquals(planned, stillPlanned)
        assertEquals(planBefore, stillPlanned.plan)
        assertTrue(escaped.isEmpty())
    }

    private companion object {
        const val VALID_HOST = "127.0.0.1"
        const val VALID_TOKEN = "token-under-test"
        const val FAILED_ERROR = "the coach's reply was not a flight plan"
    }
}
