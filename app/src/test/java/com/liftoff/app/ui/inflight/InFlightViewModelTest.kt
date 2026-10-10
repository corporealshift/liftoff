package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.settings.WeightUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Tests of each action through the holder's StateFlow, plus fresh-read equality. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightViewModelTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var sortieDao: SortieDao
    private lateinit var missionDao: MissionDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var vm: InFlightViewModel
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        flightPlanDao = db.flightPlanDao()
        sortieDao = db.sortieDao()
        missionDao = db.missionDao()

        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        val sortieId = buildFixture()
        vm = InFlightViewModel(db, settingsStore, sortieId, scope)
    }

    @After
    fun tearDown() {
        job.cancel()
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    // ── Fixture setup ────────────────────────────────────────────────

    private suspend fun buildFixture(): Long {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))
        val sortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                    SetDraft(reps = 10, seconds = null, weight = 35.0),
                    SetDraft(reps = 10, seconds = null, weight = 35.0),
                    SetDraft(reps = 10, seconds = null, weight = 35.0)
                )),
                ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                    SetDraft(reps = 12, seconds = null, weight = null),
                    SetDraft(reps = 12, seconds = null, weight = null)
                )),
                ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                    SetDraft(reps = 8, seconds = null, weight = 20.0),
                    SetDraft(reps = null, seconds = 45, weight = null)
                ))
            ),
            segments = emptyList()
        ))
        return sortieId
    }

    private suspend fun buildFixtureWithSortieId(sortieId: Long) {
        missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))
        sortieDao.insert(Sortie(
            missionId = 0L, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                    SetDraft(reps = 10, seconds = null, weight = 35.0),
                    SetDraft(reps = 10, seconds = null, weight = 35.0),
                    SetDraft(reps = 10, seconds = null, weight = 35.0)
                )),
                ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                    SetDraft(reps = 12, seconds = null, weight = null),
                    SetDraft(reps = 12, seconds = null, weight = null)
                )),
                ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                    SetDraft(reps = 8, seconds = null, weight = 20.0),
                    SetDraft(reps = null, seconds = 45, weight = null)
                ))
            ),
            segments = emptyList()
        ))
    }

    // ── One-tap check and uncheck ────────────────────────────────────
    // Check gives DONE with actuals = planned, 1/7 and a DONE segment. Uncheck gives OPEN, null actuals, 0/7.

    @Test
    fun checkAndUncheck() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSet1Id = state.cards[0].sets[0].setId

        // Check set 1
        vm.check(benchSet1Id)
        val afterCheck = withTimeout(5_000) {
            vm.state.first { s -> (s as? InFlightState.Ready)?.doneCount == 1 }
        } as InFlightState.Ready

        assertEquals(1, afterCheck.doneCount)
        assertEquals(7, afterCheck.setCount)
        assertEquals(SetStatus.DONE, afterCheck.cards[0].sets[0].status)
        val checkedSet = afterCheck.cards[0].sets[0]
        assertEquals(10, checkedSet.actualReps)
        assertEquals(35.0, checkedSet.actualWeight!!, 0.001)

        // Uncheck set 1
        vm.uncheck(benchSet1Id)
        val afterUncheck = withTimeout(5_000) {
            vm.state.first { s -> (s as? InFlightState.Ready)?.doneCount == 0 }
        } as InFlightState.Ready

        assertEquals(0, afterUncheck.doneCount)
        assertEquals(SetStatus.OPEN, afterUncheck.cards[0].sets[0].status)
        assertNull(afterUncheck.cards[0].sets[0].actualReps)
        assertNull(afterUncheck.cards[0].sets[0].actualWeight)
    }

    // ── Edit with a deviation keeps the planned values ───────────────
    // Edit gives DONE with actual 8 reps / 40.0. Planned stays 10 / 35.0, and both deviation flags are set.

    @Test
    fun editWithDeviation() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSet1Id = state.cards[0].sets[0].setId

        vm.saveEdit(benchSet1Id, 8, null, 40.0)
        val afterEdit = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].sets[0].status == SetStatus.DONE &&
                    r.cards[0].sets[0].actualReps == 8
            }
        } as InFlightState.Ready

        val edited = afterEdit.cards[0].sets[0]
        assertEquals(8, edited.actualReps)
        assertEquals(40.0, edited.actualWeight!!, 0.001)
        // Planned values unchanged
        assertEquals(10, edited.plannedReps)
        assertEquals(35.0, edited.plannedWeight!!, 0.001)
        // Deviations
        assertTrue(edited.weightDeviates)
        assertTrue(edited.countDeviates)
        // Labels show actuals
        assertEquals("40 LB", edited.weightLabel)
    }

    // ── Finishing an exercise activates the next one ─────────────────
    // Checking every bench set makes bench DONE (3/3), push-up ACTIVE and current set = push-up set 1.

    @Test
    fun finishExercise() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSets = state.cards[0].sets.map { it.setId }

        // Check all three bench sets
        benchSets.forEach { vm.check(it) }
        val afterFinish = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].status == CardStatus.DONE && r.cards[1].status == CardStatus.ACTIVE
            }
        } as InFlightState.Ready

        assertEquals(CardStatus.DONE, afterFinish.cards[0].status)
        assertEquals(3, afterFinish.cards[0].doneCount)
        assertEquals(3, afterFinish.cards[0].setCount)
        assertEquals(CardStatus.ACTIVE, afterFinish.cards[1].status)
        assertEquals("Push-up", afterFinish.cards[1].name)
        assertNotNull(afterFinish.currentSetId)
        assertEquals(afterFinish.cards[1].sets[0].setId, afterFinish.currentSetId)
    }

    // ── Skip and reopen a set ────────────────────────────────────────
    // Skipped set: counter unchanged, SKIPPED segment. Reopen makes it OPEN again.

    @Test
    fun skipAndReopenSet() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSet2Id = state.cards[0].sets[1].setId
        val initialDoneCount = state.doneCount

        // Skip set 2
        vm.skipSet(benchSet2Id)
        val afterSkip = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].sets[1].status == SetStatus.SKIPPED
            }
        } as InFlightState.Ready

        assertEquals(initialDoneCount, afterSkip.doneCount)
        assertEquals(SetStatus.SKIPPED, afterSkip.cards[0].sets[1].status)

        // Reopen set 2
        vm.reopenSet(benchSet2Id)
        val afterReopen = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].sets[1].status == SetStatus.OPEN
            }
        } as InFlightState.Ready

        assertEquals(SetStatus.OPEN, afterReopen.cards[0].sets[1].status)
    }

    // ── Skip and unskip an exercise ──────────────────────────────────
    // Skip bench after checking set 1: card SKIPPED, sets 2-3 SKIPPED, set 1 keeps its actuals, push-up ACTIVE.
    // Unskip reopens sets 2-3 and bench is ACTIVE again.

    @Test
    fun skipAndUnskipExercise() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSet1Id = state.cards[0].sets[0].setId
        val benchExerciseId = state.cards[0].plannedExerciseId

        // Check set 1 first
        vm.check(benchSet1Id)
        withTimeout(5_000) { vm.state.first { (it as? InFlightState.Ready)?.doneCount == 1 } }

        // Skip bench exercise
        vm.skipExercise(benchExerciseId)
        val afterSkip = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].status == CardStatus.SKIPPED && r.cards[1].status == CardStatus.ACTIVE
            }
        } as InFlightState.Ready

        // Bench card is SKIPPED
        assertEquals(CardStatus.SKIPPED, afterSkip.cards[0].status)
        // Set 1 stays DONE with actuals
        assertEquals(SetStatus.DONE, afterSkip.cards[0].sets[0].status)
        assertEquals(10, afterSkip.cards[0].sets[0].actualReps)
        // Sets 2-3 are SKIPPED
        assertEquals(SetStatus.SKIPPED, afterSkip.cards[0].sets[1].status)
        assertEquals(SetStatus.SKIPPED, afterSkip.cards[0].sets[2].status)
        // Push-up is ACTIVE
        assertEquals(CardStatus.ACTIVE, afterSkip.cards[1].status)

        // Unskip bench exercise
        vm.unskipExercise(benchExerciseId)
        val afterUnskip = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].status == CardStatus.ACTIVE
            }
        } as InFlightState.Ready

        // Bench is ACTIVE again
        assertEquals(CardStatus.ACTIVE, afterUnskip.cards[0].status)
        // Set 1 still DONE
        assertEquals(SetStatus.DONE, afterUnskip.cards[0].sets[0].status)
        // Sets 2-3 reopened to OPEN
        assertEquals(SetStatus.OPEN, afterUnskip.cards[0].sets[1].status)
        assertEquals(SetStatus.OPEN, afterUnskip.cards[0].sets[2].status)
    }

    // ── Add a set ────────────────────────────────────────────────────
    // The added set copies the last set's actuals when it is DONE and its planned values otherwise.
    // It is OPEN, added and labelled 'SET 4'. A done card turns ACTIVE again and the counter becomes x/8.

    @Test
    fun addSet() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSets = state.cards[0].sets.map { it.setId }
        val benchExerciseId = state.cards[0].plannedExerciseId

        // Check sets 1 and 2, edit set 3 to 8 reps
        vm.check(benchSets[0])
        vm.check(benchSets[1])
        vm.saveEdit(benchSets[2], 8, null, 35.0)
        withTimeout(5_000) {
            vm.state.first { s -> (s as? InFlightState.Ready)?.doneCount == 3 }
        }

        // Add a set to bench
        vm.addSet(benchExerciseId)
        val afterAdd = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.setCount == 8 && r.cards[0].sets.size == 4
            }
        } as InFlightState.Ready

        // New set is SET 4, OPEN, added
        val newSet = afterAdd.cards[0].sets[3]
        assertEquals("SET 4", newSet.label)
        assertEquals(SetStatus.OPEN, newSet.status)
        assertTrue(newSet.added)
        // Pre-filled from last set's actuals (8 reps, 35.0 weight)
        assertEquals(8, newSet.plannedReps)
        assertEquals(35.0, newSet.plannedWeight!!, 0.001)
        // Bench card is ACTIVE again, push-up is UPCOMING
        assertEquals(CardStatus.ACTIVE, afterAdd.cards[0].status)
        assertEquals(CardStatus.UPCOMING, afterAdd.cards[1].status)

        // Also test adding after reopening last set (pre-filled with planned values)
        val kbSets = afterAdd.cards[2].sets.map { it.setId }
        // Reopen the KB sets if any were skipped — just add a set to push-up instead
        val pushupExerciseId = afterAdd.cards[1].plannedExerciseId
        vm.addSet(pushupExerciseId)
        val afterAdd2 = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[1].sets.size == 3
            }
        } as InFlightState.Ready

        val addedPushupSet = afterAdd2.cards[1].sets[2]
        assertEquals("SET 3", addedPushupSet.label)
        assertEquals(SetStatus.OPEN, addedPushupSet.status)
        assertTrue(addedPushupSet.added)
        // Pre-filled from planned values (12 reps, no weight = BW)
        assertEquals(12, addedPushupSet.plannedReps)
        assertNull(addedPushupSet.plannedWeight)
    }

    // ── Fresh database read equals the emitted state ─────────────────
    // After a mix of actions, the state the flow emitted equals deriveInFlight on a fresh getPlan read.

    @Test
    fun freshReadEqualsFlow() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchSets = state.cards[0].sets.map { it.setId }
        val pushupSets = state.cards[1].sets.map { it.setId }
        val kbSets = state.cards[2].sets.map { it.setId }
        val benchExerciseId = state.cards[0].plannedExerciseId

        // Mix of actions
        vm.check(benchSets[0])                    // check set 1
        vm.saveEdit(benchSets[1], 8, null, 40.0) // edit set 2
        vm.skipSet(kbSets[0])                     // skip KB set 1
        vm.skipExercise(benchExerciseId)          // skip bench exercise
        vm.addSet(benchExerciseId)                // add a set to bench

        // Wait for steady state after actions (doneCount=2, setCount=8 is unique to this action mix).
        val flowState = withTimeout(5_000) {
            vm.state.first { s ->
                (s as? InFlightState.Ready)?.let { r -> r.doneCount == 2 && r.setCount == 8 } ?: false
            }
        } as InFlightState.Ready

        // Fresh read from DB
        val freshPlan = flightPlanDao.getPlan(flowState.sortieId)!!
        val freshSortie = sortieDao.get(freshPlan.plan.sortieId)!!
        val derivedState = deriveInFlight(freshPlan, freshSortie, WeightUnit.LB) as InFlightState.Ready

        assertEquals(flowState, derivedState)
    }

    // ── No plan gives the no checklist state ─────────────────────────
    // The holder emits NoChecklist for a sortie with no plan.

    @Test
    fun noPlan() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 2, 2),
            pattern = "pull", status = MissionStatus.DRAFT, outlineNotes = null
        ))
        val noPlanSortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 2, type = SortieType.LIFT,
            focus = "Legs", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))

        val noPlanVm = InFlightViewModel(db, settingsStore, noPlanSortieId, scope)
        val noCheckState = withTimeout(5_000) {
            noPlanVm.state.first { it is InFlightState.NoChecklist }
        } as InFlightState.NoChecklist

        assertTrue(noCheckState is InFlightState.NoChecklist)
        assertEquals("IN FLIGHT \u00b7 SORTIE 3", noCheckState.eyebrow)
        assertEquals("Legs", noCheckState.title)
    }
}
