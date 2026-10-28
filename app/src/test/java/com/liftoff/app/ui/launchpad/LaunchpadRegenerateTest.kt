package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.FlightPlan
import com.liftoff.app.data.FlightPlanDao
import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionDao
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.PlannedExercise
import com.liftoff.app.data.PlannedSet
import com.liftoff.app.data.RunSegment
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.data.StoreOutcome
import com.liftoff.app.domain.SortieGenerationStatus
import com.liftoff.app.domain.currentSortie
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * [LaunchpadViewModel.regenerate] and [MissionManager.regenerateFlightPlan] on the states where asking succeeds —
 * the open FLIGHT_PLAN row a second ask supersedes, the FAILED row it leaves alone, the PLANNED sortie whose plan
 * it must not touch — on each state where asking is refused, every refusal writing nothing, and on the Launchpad
 * states the action is offered on and the ones it returns null on. View model and DAO
 * only: the harness is LaunchpadConfirmTest's, so nothing here touches Compose, WorkManager or a daemon, and a
 * failed row is seeded straight through the generation DAO rather than by a worker.
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
     * recorded here, because the manager is called directly with its default onQueued. The pattern defaults to
     * one starting with L, so the current sortie is a LIFT: the kind Mission Control is asked about while run
     * generation is off. A pattern starting with R makes the current sortie a RUN instead.
     */
    private suspend fun activeWeek(pattern: String = "LRL"): Triple<LaunchpadViewModel, Long, Sortie> {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        settingsStore.setDefaultPattern(pattern)
        manager.onAppOpen()
        val missionId = missionDao.getByWeekStart(currentWeekStart(clock, ZoneOffset.UTC))!!.id
        manager.continueWithoutOutline(missionId)
        val sortie = sortieDao.getForMission(missionId).first { it.index == 0 }
        return Triple(newViewModel(), missionId, sortie)
    }

    /** The view model under test, both of its callbacks recording the ids they were handed. */
    private fun newViewModel(): LaunchpadViewModel = LaunchpadViewModel(
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

    /**
     * The Closed card, awaited for the same reason as the two above and read down to the rows it was derived
     * from: this week, this many landed sorties, this many sorties in the pattern.
     */
    private suspend fun awaitClosed(
        vm: LaunchpadViewModel,
        weekStart: java.time.LocalDate,
        landedCount: Int,
        total: Int,
    ): LaunchpadState.Closed {
        val state = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Closed } }
        val closed = state as LaunchpadState.Closed
        assertEquals(weekStart, closed.weekStart)
        assertEquals(landedCount, closed.landedCount)
        assertEquals(total, closed.total)
        return closed
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

    /**
     * The manager asked directly, with the view model's own recording lambdas wired in as its callbacks: the
     * refusal is an [IllegalStateException], and neither lambda was handed an id. A manager that accepted instead
     * hands back the ids it would have had cancelled, so the fail names what it queued.
     */
    private suspend fun assertManagerRefuses(sortieId: Long) {
        try {
            val superseded = manager.regenerateFlightPlan(sortieId) { queuedIds.add(it) }
            for (id in superseded) {
                cancelledIds.add(id)
            }
            fail("regenerateFlightPlan accepted sortie $sortieId and handed back $superseded")
        } catch (expected: IllegalStateException) {
        }
        assertTrue(queuedIds.isEmpty())
        assertTrue(cancelledIds.isEmpty())
    }

    /** Every table a refusal is answerable for, read in one go so before and after compare as one value. */
    private data class Tables(
        val generations: List<Generation>,
        val missions: List<Mission>,
        val sorties: List<Sortie>,
        val plans: List<FlightPlan>,
        val exercises: List<PlannedExercise>,
        val sets: List<PlannedSet>,
        val segments: List<RunSegment>,
    )

    private suspend fun snapshot(): Tables = Tables(
        generations = generationDao.getAll(),
        missions = missionDao.getAll(),
        sorties = sortieDao.getAll(),
        plans = flightPlanDao.getAllPlans(),
        exercises = flightPlanDao.getAllPlannedExercises(),
        sets = flightPlanDao.getAllPlannedSets(),
        segments = flightPlanDao.getAllRunSegments(),
    )

    /**
     * "Writes nothing": each table holds exactly the rows it held before, in the same order and with the same
     * statuses; no id reached either lambda; nothing reached the scope's exception handler.
     */
    private suspend fun assertWroteNothing(before: Tables) {
        assertEquals(before, snapshot())
        assertTrue(queuedIds.isEmpty())
        assertTrue(cancelledIds.isEmpty())
        assertTrue(escaped.isEmpty())
    }

    /** One sortie of a Mission seeded straight through the DAO, the way MissionManagerReFlyTest seeds them. */
    private suspend fun insertSortieOf(
        missionId: Long,
        index: Int,
        state: SortieState,
        type: SortieType = SortieType.LIFT,
    ): Sortie {
        val sortie = Sortie(
            missionId = missionId, index = index, type = type, focus = "upper",
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        )
        return sortie.copy(id = sortieDao.insert(sortie))
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
        assertEquals(SortieGenerationStatus.Failed(FAILED_ERROR), before.generationStatus)

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

        // vm.state still holds the card from before the ask when that ask's write commits, so the wait is for the
        // card to move rather than for a Pending: it was already Pending.
        val after = withTimeout(5_000L) {
            vm.state.first { it is LaunchpadState.Pending && it.generationStatus != before.generationStatus }
        } as LaunchpadState.Pending
        // The FAILED row the ask was a Retry of, then the fresh QUEUED row the ask wrote. Nothing else moved.
        assertEquals(SortieGenerationStatus.Planning, after.generationStatus)
        assertEquals(before.copy(generationStatus = SortieGenerationStatus.Planning), after)
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

    // Unconfigured is a refusal here rather than a ConfirmOutcome.CoachNotConfigured for the caller to branch on:
    // the action still starts on a Pending card, and the manager's IllegalStateException is caught inside the Job
    // rather than reaching the scope's handler. Had the manager accepted, the row that activating this week queued
    // would now be cancelled and a second one open, so the unchanged table is also the proof that it refused.
    @Test
    fun regenerateWithoutAHostOrTokenIsRefusedAndWritesNothing() = runBlocking {
        val (vm, _, sortie) = activeWeek()
        settingsStore.setDaemonHost("")
        settingsStore.setDaemonToken("")
        val stateBefore = awaitPending(vm, sortie)
        val before = snapshot()

        joinAction(vm.regenerate(), vm)

        assertWroteNothing(before)
        assertEquals(stateBefore, vm.state.value)
    }

    // The view model offers nothing on an InFlight card, so this is the manager asked directly. The sortie the owner
    // has launched is not the one to ask Mission Control about, and its plan and its exercises stay as they were —
    // as does the row activating this week queued, still open because nothing arrived to supersede it.
    @Test
    fun regenerateOnAnInFlightSortieIsRefusedAndWritesNothing() = runBlocking {
        val (_, _, sortie) = activeWeek()
        assertEquals(
            StoreOutcome.Stored,
            manager.storePlan(
                sortie.id,
                CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
            ),
        )
        manager.launch(sortie.id)
        val planBefore = flightPlanDao.getPlan(sortie.id)!!
        val before = snapshot()

        assertManagerRefuses(sortie.id)

        assertWroteNothing(before)
        assertEquals(SortieState.IN_FLIGHT, sortieDao.get(sortie.id)!!.state)
        val planAfter = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(planBefore, planAfter)
        assertEquals(
            planBefore.exercises.map { it.displayName },
            planAfter.exercises.map { it.displayName },
        )
        // Still the one open row on this sortie: nothing was queued, so nothing superseded it.
        assertEquals(GenerationStatus.QUEUED, openPlanRowFor(sortie.id).status)
    }

    // A PENDING sortie of the current ACTIVE Mission that is not the current one. Index 2 is a LIFT on purpose:
    // index 1 of "LRL" is a RUN, which the run toggle refuses too, and a case two guards refuse cannot show that
    // dropping either one of them is what let the ask through.
    @Test
    fun regenerateOnASortieThatIsNotCurrentIsRefusedAndWritesNothing() = runBlocking {
        val (_, missionId, current) = activeWeek()
        val later = sortieDao.getForMission(missionId).first { it.index == 2 }
        assertEquals(SortieType.LIFT, later.type)
        assertEquals(SortieState.PENDING, later.state)
        assertTrue(later.id != current.id)
        val before = snapshot()

        assertManagerRefuses(later.id)

        assertWroteNothing(before)
        assertEquals(later, sortieDao.get(later.id))
        assertEquals(current, sortieDao.get(current.id)!!)
    }

    // An ACTIVE Mission of a past week with a PENDING current sortie, seeded through the DAOs the way
    // MissionManagerReFlyTest seeds its ACTIVE mission. onAppOpen is deliberately not called first: its rollover
    // sweep would close this Mission before the guard was ever asked, and the refusal would prove nothing.
    @Test
    fun regenerateOnASortieOfAWeekThatIsNotCurrentIsRefusedAndWritesNothing() = runBlocking {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        val missionId = missionDao.insert(
            Mission(
                weekStart = currentWeekStart(clock, ZoneOffset.UTC).minusWeeks(1),
                pattern = "LLL", status = MissionStatus.ACTIVE, outlineNotes = null,
            )
        )
        val sortie = insertSortieOf(missionId, 0, SortieState.PENDING)
        val before = snapshot()

        assertManagerRefuses(sortie.id)

        assertWroteNothing(before)
        assertEquals(MissionStatus.ACTIVE, missionDao.get(missionId)!!.status)
        assertEquals(SortieState.PENDING, sortieDao.get(sortie.id)!!.state)
    }

    // DESIGN.md §7.2: a run sortie goes to Mission Control only while 'Generate run plans' is on, and this is the
    // only place that can enforce it — the worker never reads the toggle. The manager reads settings per call, so
    // both halves run here: refused while the toggle is off, accepted once it is on, and the simple plan advance
    // wrote for this sortie is the plan it holds before and after.
    @Test
    fun regenerateOnARunSortieFollowsTheGenerateRunPlansToggle() = runBlocking {
        val (_, missionId, sortie) = activeWeek(pattern = "RLL")
        assertEquals(SortieType.RUN, sortie.type)
        assertFalse(settingsStore.settings.first().generateRunPlans)
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
        val planBefore = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, planBefore.plan.source)
        val before = snapshot()

        assertManagerRefuses(sortie.id)
        assertWroteNothing(before)

        settingsStore.setGenerateRunPlans(true)
        val superseded = manager.regenerateFlightPlan(sortie.id) { queuedIds.add(it) }

        // advance queued no row for a run sortie while the toggle was off, so this ask had nothing to supersede.
        assertTrue(superseded.isEmpty())
        val enqueued = queuedRowEnqueued(missionId, sortie.id)
        assertEquals(before.generations.size + 1, generationDao.getAll().size)
        assertTrue(before.generations.none { it.id == enqueued.id })
        assertEquals(planBefore, flightPlanDao.getPlan(sortie.id))
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
        assertTrue(escaped.isEmpty())
    }

    // The ACTIVE check's own case: a DRAFT of the current week holding a PENDING current sortie of its own. Every
    // other refusal in this class is on an ACTIVE Mission, so without this one no test would notice the ACTIVE
    // check being dropped.
    @Test
    fun regenerateOnAMissionThatIsNotActiveIsRefusedAndWritesNothing() = runBlocking {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        settingsStore.setDefaultPattern("LLL")
        val draft = manager.onAppOpen()
        assertEquals(MissionStatus.DRAFT, draft.status)
        val sortie = insertSortieOf(draft.id, 0, SortieState.PENDING)
        val before = snapshot()

        assertManagerRefuses(sortie.id)

        assertWroteNothing(before)
        assertEquals(MissionStatus.DRAFT, missionDao.get(draft.id)!!.status)
        assertEquals(SortieState.PENDING, sortieDao.get(sortie.id)!!.state)
    }

    // The action's own state gate, which is what stops every case above from passing on an action that did nothing
    // at all: a Job on the two cards regenerate is offered on, null on each of the four it is not. Each half awaits
    // the card it is about to act on, so a card the derivation has not produced yet cannot be mistaken for a refusal,
    // and every Job is joined before its enqueued row is read back. The week is one sortie long so that landing it
    // is what closes the week.
    @Test
    fun regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState() = runBlocking {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        settingsStore.setDefaultPattern("L")
        val vm = newViewModel()

        // Loading: onAppOpen has not settled the week, so the manager has no current week to derive a card from.
        assertNull(manager.currentWeekStart.value)
        assertNull(missionDao.getByWeekStart(currentWeekStart(clock, ZoneOffset.UTC)))
        assertEquals(LaunchpadState.Loading, withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Loading } })
        assertNull("regenerate was offered on a Loading Launchpad", vm.regenerate())

        // Draft: the week settled, its current sortie not yet started.
        val draft = manager.onAppOpen()
        assertEquals(MissionStatus.DRAFT, draft.status)
        val draftCard = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Draft } }
            as LaunchpadState.Draft
        assertEquals(draft.id, draftCard.missionId)
        assertEquals(draft.weekStart, draftCard.weekStart)
        assertNull("regenerate was offered on a Draft week", vm.regenerate())

        // Pending: the week activated with no outline, so the current sortie is PENDING and holds the open
        // FLIGHT_PLAN row activating the week queued. The action returns a Job, and the id it enqueued reads back as
        // the one open row on this sortie.
        manager.continueWithoutOutline(draft.id)
        val sortie = sortieDao.getForMission(draft.id).single()
        assertEquals(SortieType.LIFT, sortie.type)
        awaitPending(vm, sortie)
        joinAction(vm.regenerate(), vm)
        assertEquals(1, queuedIds.size)
        assertEquals(queuedIds.single(), openPlanRowFor(sortie.id).id)

        // Planned: the plan a validated coach reply stored, which is what keeps the card Planned and launchable.
        assertEquals(
            StoreOutcome.Stored,
            manager.storePlan(
                sortie.id,
                CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
            ),
        )
        awaitPlanned(vm, sortie)
        joinAction(vm.regenerate(), vm)
        assertEquals(2, queuedIds.size)
        assertEquals(queuedIds.last(), openPlanRowFor(sortie.id).id)
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)

        // InFlight: the owner launched this sortie, and this is the card regenerate is not offered on.
        manager.launch(sortie.id)
        val inFlight = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.InFlight } }
            as LaunchpadState.InFlight
        assertEquals(sortie.id, inFlight.sortieId)
        assertNull("regenerate was offered on an InFlight card", vm.regenerate())

        // Closed, the first way: the week's only sortie landed, which is what closes a week.
        manager.land(sortie.id)
        assertEquals(MissionStatus.CLOSED, missionDao.get(draft.id)!!.status)
        awaitClosed(vm, draft.weekStart, landedCount = 1, total = 1)
        assertNull("regenerate was offered on a Closed Mission", vm.regenerate())

        // Closed, the other way: an ACTIVE Mission with no current sortie. The manager closes a week as soon as its
        // last sortie is done, so this state is written straight through the DAO — not a lifecycle transition, the
        // rows the derivation has to answer for. A fresh view model, because the card above was already Closed: a
        // second one is derived from these rows rather than left over from the Mission that was CLOSED.
        missionDao.update(missionDao.get(draft.id)!!.copy(status = MissionStatus.ACTIVE))
        assertNull(currentSortie(sortieDao.getForMission(draft.id)))
        val active = newViewModel()
        assertEquals(MissionStatus.ACTIVE, missionDao.get(draft.id)!!.status)
        awaitClosed(active, draft.weekStart, landedCount = 1, total = 1)
        assertNull("regenerate was offered on an ACTIVE Mission with no current sortie", active.regenerate())

        // Nothing any null state was asked for: the only ids either lambda holds are the two the Jobs handed over.
        assertEquals(2, queuedIds.size)
        assertEquals(2, cancelledIds.size)
        assertTrue(escaped.isEmpty())
    }

    private companion object {
        const val VALID_HOST = "127.0.0.1"
        const val VALID_TOKEN = "token-under-test"
        const val FAILED_ERROR = "the coach's reply was not a flight plan"
    }
}
