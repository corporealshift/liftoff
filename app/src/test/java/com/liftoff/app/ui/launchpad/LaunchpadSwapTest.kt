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
import org.junit.Assert.assertNotNull
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
 * The swap of the current sortie's type (DESIGN.md §9) as the Launchpad offers it: [LaunchpadViewModel.swapSortieType]
 * on the two cards it is offered on and on none of the others, the confirmation it opens over a Flight Plan the
 * coach generated and the nothing its Cancel writes, the swap that runs with no dialog at all on a PENDING card or a
 * card whose plan was not generated, and the confirm that runs the swap and cancels the generations already open on
 * that card. View model and DAO only, on LaunchpadRegenerateTest's harness: nothing here touches Compose,
 * WorkManager or a daemon, so "offered" and "absent" are the action's own return value and [LaunchpadViewModel.swapping].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadSwapTest {

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
     * The week a swap is offered on, built the way the screen builds it: settings first, because onAppOpen reads
     * defaultPattern for the new Mission, then the week activated with no outline. That leaves one open QUEUED
     * FLIGHT_PLAN row on the current sortie — the row a swap has to hand back for the caller to cancel — and its id
     * is not recorded here, because the manager is called directly with its default onQueued. The pattern defaults
     * to one starting with L, so the current sortie is a LIFT and its card offers "Swap to run".
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

    /** The open FLIGHT_PLAN rows this sortie holds, newest first as the DAO returns them. */
    private suspend fun openPlanRowsFor(sortieId: Long): List<Generation> =
        generationDao.getOpenFlightPlanForSortie(sortieId)

    /**
     * The swap asked of the manager directly, with the view model's own recording lambdas wired in as its
     * callbacks: the refusal is an [IllegalStateException] naming its gate, and neither lambda was handed an id. A
     * swap that accepted instead hands back the ids it would have had cancelled, so the fail names what it queued.
     */
    private suspend fun assertSwapRefuses(sortieId: Long, expectedGate: String) {
        try {
            val superseded = manager.swapSortieType(sortieId) { queuedIds.add(it) }
            for (id in superseded) {
                cancelledIds.add(id)
            }
            fail("swapSortieType accepted sortie $sortieId and handed back $superseded")
        } catch (expected: IllegalStateException) {
            assertEquals("which gate refused", expectedGate, expected.message)
        }
        assertTrue(queuedIds.isEmpty())
        assertTrue(cancelledIds.isEmpty())
    }

    /** Every table a refusal or a cancelled dialog is answerable for, read in one go. */
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

    /** The coach's own reply, stored the way the worker stores it. */
    private suspend fun storeCoachPlan(sortieId: Long, fixture: String, context: com.liftoff.app.coach.GenerationContext) =
        manager.storePlan(sortieId, CoachFixtures.validReply(fixture, context))

    // The action's own state gate: a Job on the Pending and Planned cards and null on Loading, Draft, InFlight and
    // both Closed variants. Each half awaits the card it is about to act on, so a card the derivation has not
    // produced yet cannot be mistaken for a refusal. The week is one sortie long so that landing it is what closes
    // it. The label is read off the card the action was offered on, because the label is what the owner sees.
    @Test
    fun swapActionIsOfferedOnPendingAndPlannedAndOnNoOtherState() = runBlocking {
        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        settingsStore.setDefaultPattern("L")
        val vm = newViewModel()

        // Loading: onAppOpen has not settled the week, so there is no card to act on.
        assertNull(manager.currentWeekStart.value)
        assertNull(missionDao.getByWeekStart(currentWeekStart(clock, ZoneOffset.UTC)))
        assertEquals(LaunchpadState.Loading, withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Loading } })
        assertNull("the swap was offered on a Loading Launchpad", vm.swapSortieType())
        assertFalse("a Loading Launchpad opened a confirmation", vm.swapping)

        // Draft: the week settled, its current sortie not yet started.
        val draft = manager.onAppOpen()
        assertEquals(MissionStatus.DRAFT, draft.status)
        val draftCard = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Draft } } as LaunchpadState.Draft
        assertEquals(draft.id, draftCard.missionId)
        assertNull("the swap was offered on a Draft week", vm.swapSortieType())
        assertFalse("a Draft week opened a confirmation", vm.swapping)

        // Pending: the week activated with no outline. The card carries the sortie's type, which is what its label
        // is built from, and the eyebrow already names the type the card was built on.
        manager.continueWithoutOutline(draft.id)
        val sortie = sortieDao.getForMission(draft.id).single()
        assertEquals(SortieType.LIFT, sortie.type)
        val pending = awaitPending(vm, sortie)
        assertEquals(SortieType.LIFT, pending.sortieType)
        assertEquals("Swap to run", swapLabel(pending.sortieType))
        assertTrue("the eyebrow: ${pending.eyebrow}", pending.eyebrow.endsWith("· LIFT"))

        // The Job ran the swap: the same sortie row is a run day, the pattern's only letter flipped with it, and
        // with run generation off the swap prepared that run day with the simple plan, which moves the card.
        joinAction(vm.swapSortieType(), vm)
        assertFalse("a PENDING card opened a confirmation", vm.swapping)
        val asRun = sortieDao.get(sortie.id)!!
        assertEquals(sortie.id, asRun.id)
        assertEquals(SortieType.RUN, asRun.type)
        assertEquals("R", missionDao.get(draft.id)!!.pattern)
        assertEquals(SortieState.PLANNED, asRun.state)

        // Planned: the card the swap just moved the sortie to, holding the simple plan the swap wrote. Its label
        // names the other type, and this card's plan is not one the coach generated, so the action runs.
        val planned = awaitPlanned(vm, sortie)
        assertEquals(SortieType.RUN, planned.sortieType)
        assertEquals("Swap to lift", swapLabel(planned.sortieType))
        assertEquals(FlightPlanSource.SIMPLE_RUN, planned.plan.plan.source)
        joinAction(vm.swapSortieType(), vm)
        assertFalse("a Planned card with a plan that was not generated opened a confirmation", vm.swapping)
        val backToLift = sortieDao.get(sortie.id)!!
        assertEquals(SortieType.LIFT, backToLift.type)
        assertEquals("L", missionDao.get(draft.id)!!.pattern)

        // InFlight: the owner launched this sortie, and this is the card the swap is not offered on.
        assertEquals(StoreOutcome.Stored, storeCoachPlan(sortie.id, CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))
        manager.launch(sortie.id)
        val inFlight = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.InFlight } } as LaunchpadState.InFlight
        assertEquals(sortie.id, inFlight.sortieId)
        assertNull("the swap was offered on an InFlight card", vm.swapSortieType())
        assertFalse("an InFlight card opened a confirmation", vm.swapping)

        // Closed, the first way: the week's only sortie landed, which is what closes a week.
        manager.land(sortie.id)
        assertEquals(MissionStatus.CLOSED, missionDao.get(draft.id)!!.status)
        awaitClosed(vm, draft.weekStart, landedCount = 1, total = 1)
        assertNull("the swap was offered on a Closed Mission", vm.swapSortieType())

        // Closed, the other way: an ACTIVE Mission with no current sortie, written straight through the DAO. A
        // fresh view model, because the card above was already Closed: a second one is derived from these rows.
        missionDao.update(missionDao.get(draft.id)!!.copy(status = MissionStatus.ACTIVE))
        assertNull(currentSortie(sortieDao.getForMission(draft.id)))
        val active = newViewModel()
        awaitClosed(active, draft.weekStart, landedCount = 1, total = 1)
        assertNull("the swap was offered on an ACTIVE Mission with no current sortie", active.swapSortieType())
        assertFalse("a Closed card opened a confirmation", active.swapping)

        // Nothing any null state was asked for: the swap on the PENDING card cancelled the row activating the week
        // queued and queued nothing itself, and the swap on the Planned card queued the lift's generation and had
        // nothing open to cancel.
        assertEquals(1, queuedIds.size)
        assertEquals(1, cancelledIds.size)
        assertTrue(escaped.isEmpty())
    }

    // The two gates the view model cannot see: a Mission that is not ACTIVE, and a sortie that is not the current
    // one. One Mission, because the schema holds one per week (Mission.kt:11), moved from ACTIVE to DRAFT so that
    // index 0 stays its current PENDING sortie throughout and only the gate under test can refuse.
    @Test
    fun swapActionIsAbsentWhenTheMissionIsNotActiveOrTheSortieIsNotCurrent() = runBlocking {
        val (_, missionId, current) = activeWeek()

        // Not current: index 2 of "LRL" is a PENDING LIFT of this week. It is PENDING, so the swap's own state gate
        // cannot refuse it, and it is a LIFT, so the run-generation toggle cannot either: the current-sortie gate is
        // the only thing standing between this sortie and a swap.
        val later = sortieDao.getForMission(missionId).first { it.index == 2 }
        assertEquals(SortieType.LIFT, later.type)
        assertEquals(SortieState.PENDING, later.state)
        assertTrue(later.id != current.id)
        val before = snapshot()

        assertSwapRefuses(later.id, "Sortie must be current")

        assertWroteNothing(before)
        assertEquals(later, sortieDao.get(later.id))
        assertEquals(current, sortieDao.get(current.id)!!)
        assertEquals("LRL", missionDao.get(missionId)!!.pattern)

        // Not ACTIVE: the same Mission made DRAFT, which is also the card the Launchpad offers no swap on. The view
        // model refuses it before the manager is reached, and the manager refuses it too.
        missionDao.update(missionDao.get(missionId)!!.copy(status = MissionStatus.DRAFT))
        val vm = newViewModel()
        val draftCard = withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Draft } } as LaunchpadState.Draft
        assertEquals(missionId, draftCard.missionId)
        assertNull("the swap was offered on a Draft card", vm.swapSortieType())
        assertFalse("a Draft card opened a confirmation", vm.swapping)
        val afterDraft = snapshot()

        assertSwapRefuses(current.id, "Mission must be ACTIVE")

        assertWroteNothing(afterDraft)
        assertEquals(MissionStatus.DRAFT, missionDao.get(missionId)!!.status)
        assertEquals(SortieState.PENDING, sortieDao.get(current.id)!!.state)
        assertEquals("LRL", missionDao.get(missionId)!!.pattern)
    }

    // The confirmation's no. A Planned card whose plan the coach generated is the one card whose swap would have
    // to delete something the owner can see, so the action opens the dialog and writes nothing; Cancel closes it,
    // and every table is still the one it was.
    @Test
    fun swapOnAPlannedCardWithAGeneratedPlanAsksForConfirmationAndACancelWritesNothing() = runBlocking {
        val (vm, missionId, sortie) = activeWeek()
        assertEquals(StoreOutcome.Stored, storeCoachPlan(sortie.id, CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))
        val planned = awaitPlanned(vm, sortie)
        assertEquals(FlightPlanSource.GENERATED, planned.plan.plan.source)
        val planBefore = flightPlanDao.getPlan(sortie.id)!!
        assertTrue("the plan's rows are what a swap deletes", planBefore.exercises.isNotEmpty())
        val open = openPlanRowsFor(sortie.id)
        val before = snapshot()

        assertNull("the swap ran without asking first", vm.swapSortieType())
        assertTrue("no confirmation was opened over a generated plan", vm.swapping)
        // Asking did not act: the card is still the Planned one, still holding the coach's plan.
        assertEquals(planned, vm.state.value)

        vm.cancelSwap()
        assertFalse("the dialog stayed open after Cancel", vm.swapping)

        assertWroteNothing(before)
        assertEquals(SortieType.LIFT, sortieDao.get(sortie.id)!!.type)
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
        assertEquals("LRL", missionDao.get(missionId)!!.pattern)
        assertEquals(planBefore, flightPlanDao.getPlan(sortie.id))
        assertEquals(open, openPlanRowsFor(sortie.id))
        assertEquals(planned, withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Planned } })
    }

    // The confirmation's other half: a PENDING card has no plan for the swap to delete, and a Planned card whose
    // plan was not generated has one the swap replaces in the same breath. Neither asks first; both run in the
    // Job the action returns. Both cards are the same week's, because the schema holds one Mission per week
    // (Mission.kt:11): the second is the run day the first swap prepared, whose plan the app wrote itself.
    @Test
    fun swapOnAPendingOrNonGeneratedPlannedCardRunsWithoutAConfirmDialog() = runBlocking {
        // A PENDING lift day: the swap runs on the tap, and the row activating the week queued is the one it
        // supersedes. Run generation is off, so the swapped-to-run sortie is prepared by the simple plan.
        val (vm, missionId, sortie) = activeWeek()
        val pending = awaitPending(vm, sortie)
        assertEquals(SortieType.LIFT, pending.sortieType)
        assertEquals("Swap to run", swapLabel(pending.sortieType))
        val superseded = openPlanRowsFor(sortie.id).single()

        val launched = vm.swapSortieType()
        assertNotNull("the PENDING card returned no Job", launched)
        assertFalse("a PENDING card opened a confirmation", vm.swapping)
        joinAction(launched, vm)
        assertFalse(vm.swapping)

        val swapped = sortieDao.get(sortie.id)!!
        assertEquals(SortieType.RUN, swapped.type)
        assertEquals("easy", swapped.focus)
        assertNull(swapped.focusRationale)
        assertEquals(SortieState.PLANNED, swapped.state)
        assertEquals("RRL", missionDao.get(missionId)!!.pattern)
        assertEquals(FlightPlanSource.SIMPLE_RUN, flightPlanDao.getPlan(sortie.id)!!.plan.source)
        assertEquals(listOf(superseded.id), cancelledIds.toList())
        assertEquals(GenerationStatus.CANCELLED, generationDao.get(superseded.id)!!.status)
        assertTrue(queuedIds.isEmpty())
        // The Planned card: the run day that swap prepared, holding the simple plan the app wrote for it. Its plan
        // is not one the coach generated, so this card runs too — and a lift day needs a generation, which is what
        // this swap queues. The ids recorded for the first swap are cleared here, because from this line the second
        // card is what is being measured.
        val planned = awaitPlanned(vm, sortie)
        assertEquals(FlightPlanSource.SIMPLE_RUN, planned.plan.plan.source)
        assertEquals(SortieType.RUN, planned.sortieType)
        assertEquals("Swap to lift", swapLabel(planned.sortieType))
        assertTrue("the simple plan queued nothing, so this swap had nothing open", openPlanRowsFor(sortie.id).isEmpty())
        queuedIds.clear()
        cancelledIds.clear()

        val ranSwap = vm.swapSortieType()
        assertNotNull("the Planned card returned no Job", ranSwap)
        assertFalse("a Planned card with a plan that was not generated opened a confirmation", vm.swapping)
        joinAction(ranSwap, vm)
        assertFalse(vm.swapping)

        val swappedBack = sortieDao.get(sortie.id)!!
        assertEquals(SortieType.LIFT, swappedBack.type)
        assertEquals("full body", swappedBack.focus)
        assertEquals("a swap that needs a generation leaves the sortie awaiting one", SortieState.PENDING, swappedBack.state)
        assertEquals("LRL", missionDao.get(missionId)!!.pattern)
        assertNull("the simple plan went with the run day", flightPlanDao.getPlan(sortie.id))
        assertTrue(cancelledIds.isEmpty())
        assertEquals(1, queuedIds.size)
        val fresh = generationDao.get(queuedIds.single())
            ?: throw AssertionError("enqueued id ${queuedIds.single()} has no generation row")
        assertEquals(GenerationKind.FLIGHT_PLAN, fresh.kind)
        assertEquals(GenerationStatus.QUEUED, fresh.status)
        assertEquals(sortie.id, fresh.sortieId)
        assertEquals(missionId, fresh.missionId)
        assertTrue(escaped.isEmpty())
    }

    // The confirmation's yes. The card already has an ask open on it when the swap is tapped; confirming runs the
    // swap and hands that open row to the cancel helper, while the generation the swap queued for the new type is
    // not among the ids it cancelled.
    @Test
    fun confirmingTheSwapDialogRunsTheSwapAndCancelsTheOpenGenerations() = runBlocking {
        // Run generation on, so the swapped-to-run sortie is prepared by a generation of its own: that fresh row
        // is what "not among the ids it cancelled" is measured against.
        settingsStore.setGenerateRunPlans(true)
        val (vm, missionId, sortie) = activeWeek(pattern = "RLL")
        assertEquals(SortieType.RUN, sortie.type)
        // The row activating the week queued is closed before the coach's plan lands, so the only open row on this
        // card afterwards is the one the regenerate below asks for.
        val activated = openPlanRowsFor(sortie.id).single()
        generationDao.markSucceeded(activated.id, clock.millis() + 60_000)
        assertEquals(StoreOutcome.Stored, storeCoachPlan(sortie.id, CoachFixtures.RUN_PLAN, CoachFixtures.RUN_CONTEXT))
        val planned = awaitPlanned(vm, sortie)
        assertEquals(FlightPlanSource.GENERATED, planned.plan.plan.source)
        assertEquals("Swap to lift", swapLabel(planned.sortieType))

        joinAction(vm.regenerate(), vm)
        val open = openPlanRowsFor(sortie.id).single()
        assertEquals(GenerationStatus.QUEUED, open.status)
        assertTrue("the regenerate superseded the row activating the week queued", open.id != activated.id)
        assertEquals(1, queuedIds.size)
        assertEquals(open.id, queuedIds.single())
        assertTrue(cancelledIds.isEmpty())

        assertNull("the swap ran without asking first", vm.swapSortieType())
        assertTrue(vm.swapping)

        joinAction(vm.confirmSwap(), vm)
        assertFalse("the dialog stayed open after the swap ran", vm.swapping)

        assertEquals("the open row this card held when the swap was confirmed", listOf(open.id), cancelledIds.toList())
        assertEquals(GenerationStatus.CANCELLED, generationDao.get(open.id)!!.status)
        assertEquals(2, queuedIds.size)
        val fresh = queuedIds.last()
        assertTrue("the generation this swap queued is not one it cancelled", fresh !in cancelledIds)
        val freshRow = generationDao.get(fresh) ?: throw AssertionError("enqueued id $fresh has no generation row")
        assertEquals(GenerationKind.FLIGHT_PLAN, freshRow.kind)
        assertEquals(GenerationStatus.QUEUED, freshRow.status)
        assertEquals(sortie.id, freshRow.sortieId)
        assertEquals(missionId, freshRow.missionId)

        // The card the owner is left looking at is the swapped one: the same sortie row, now a lift day, with the
        // eyebrow and the label built from the type it became.
        val swapped = sortieDao.get(sortie.id)!!
        assertEquals(SortieType.LIFT, swapped.type)
        assertEquals(SortieState.PENDING, swapped.state)
        assertEquals("LLL", missionDao.get(missionId)!!.pattern)
        assertNull("the generated plan went with the run day", flightPlanDao.getPlan(sortie.id))
        val card = withTimeout(5_000L) {
            vm.state.first { it is LaunchpadState.Pending && it.sortieType == SortieType.LIFT }
        } as LaunchpadState.Pending
        assertTrue("the eyebrow: ${card.eyebrow}", card.eyebrow.endsWith("· LIFT"))
        assertEquals("Swap to run", swapLabel(card.sortieType))
        assertEquals(0, escaped.size)
    }

    private companion object {
        const val VALID_HOST = "127.0.0.1"
        const val VALID_TOKEN = "token-under-test"
    }
}
