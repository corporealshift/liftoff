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

    // ── Saving an exercise note ──────────────────────────────────────
    // The note is on the card in the emitted state and on a fresh deriveInFlight read, so it survives a process kill.

    @Test
    fun exerciseNoteSavesAndSurvivesFreshRead() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchExerciseId = state.cards[0].plannedExerciseId
        assertNull(state.cards[0].note)

        vm.saveExerciseNote(benchExerciseId, "Left shoulder twinge, eased after set 2")
        val withNote = withTimeout(5_000) {
            vm.state.first { s ->
                (s as? InFlightState.Ready)?.cards?.get(0)?.note != null
            }
        } as InFlightState.Ready

        assertEquals("Left shoulder twinge, eased after set 2", withNote.cards[0].note)
        // The note is on the Bench Press card only.
        assertNull(withNote.cards[1].note)
        assertNull(withNote.cards[2].note)

        // A fresh read rebuilt from Room carries the same note.
        val sortieId = withNote.sortieId
        val derived = deriveInFlight(
            flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB
        ) as InFlightState.Ready
        assertEquals(withNote, derived)
        assertEquals("Left shoulder twinge, eased after set 2", derived.cards[0].note)
    }

    // ── A note on a skipped exercise changes only the note ───────────
    // The skipped flag stays true, the card stays SKIPPED, and every set keeps its status and actuals.

    @Test
    fun noteOnSkippedExerciseKeepsSkipAndSets() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchExerciseId = state.cards[0].plannedExerciseId
        val sortieId = state.sortieId

        vm.skipExercise(benchExerciseId)
        val skipped = withTimeout(5_000) {
            vm.state.first { s -> (s as? InFlightState.Ready)?.cards?.get(0)?.status == CardStatus.SKIPPED }
        } as InFlightState.Ready
        assertEquals(CardStatus.SKIPPED, skipped.cards[0].status)

        // The exercise's sets as Room holds them before the note is saved.
        val setsBefore = flightPlanDao.getPlan(sortieId)!!.exercises[0].sets

        vm.saveExerciseNote(benchExerciseId, "Shoulder is done for today")
        val withNote = withTimeout(5_000) {
            vm.state.first { s ->
                val r = s as? InFlightState.Ready ?: return@first false
                r.cards[0].note != null && r.cards[0].status == CardStatus.SKIPPED
            }
        } as InFlightState.Ready

        assertEquals("Shoulder is done for today", withNote.cards[0].note)
        // Still skipped, in the state and in the row.
        assertEquals(CardStatus.SKIPPED, withNote.cards[0].status)
        assertTrue(flightPlanDao.getPlan(sortieId)!!.exercises[0].plannedExercise.skipped)

        // Every set is untouched: status and actuals identical to before the note.
        val setsAfter = flightPlanDao.getPlan(sortieId)!!.exercises[0].sets
        assertEquals(setsBefore.size, setsAfter.size)
        setsBefore.forEachIndexed { i, before ->
            val after = setsAfter[i]
            assertEquals(before.id, after.id)
            assertEquals(before.status, after.status)
            assertEquals(before.actualReps, after.actualReps)
            assertEquals(before.actualSeconds, after.actualSeconds)
            assertEquals(before.actualWeight, after.actualWeight)
        }
        assertEquals(setsBefore, setsAfter)
        assertEquals(withNote.cards[0].sets.map { it.status }, setsAfter.map { it.status })
    }

    // ── A blank note clears the note ─────────────────────────────────
    // Whitespace-only text stores null: null on the card in the state, null in plannedExercise.userNotes.

    @Test
    fun blankExerciseNoteStoresNull() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val benchExerciseId = state.cards[0].plannedExerciseId
        val sortieId = state.sortieId

        vm.saveExerciseNote(benchExerciseId, "Tight lower back")
        withTimeout(5_000) {
            vm.state.first { s -> (s as? InFlightState.Ready)?.cards?.get(0)?.note != null }
        }

        vm.saveExerciseNote(benchExerciseId, "   ")
        val cleared = withTimeout(5_000) {
            vm.state.first { s -> (s as? InFlightState.Ready)?.cards?.get(0)?.note == null }
        } as InFlightState.Ready

        assertNull(cleared.cards[0].note)
        assertNull(flightPlanDao.getPlan(sortieId)!!.exercises[0].plannedExercise.userNotes)
    }

    // ── Saving the sortie note on a sortie that has a plan ───────────
    // Only the sortie row changes, so this also proves the state reacts to a sortie-row-only write.

    @Test
    fun sortieNoteSavesOnPlannedSortie() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val sortieId = state.sortieId
        assertNull(state.sortieNote)

        vm.saveSortieNote("Felt strong; bench moved at 40 LB")
        val withNote = withTimeout(5_000) {
            vm.state.first { s -> s is InFlightState.Ready && s.sortieNote != null }
        } as InFlightState.Ready

        assertEquals("Felt strong; bench moved at 40 LB", withNote.sortieNote)
        // No exercise note appeared, so the note came from the sortie row alone.
        assertTrue(withNote.cards.all { it.note == null })
        assertEquals("Felt strong; bench moved at 40 LB", sortieDao.get(sortieId)!!.notes)
        assertEquals(
            withNote,
            deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
        )

        vm.saveSortieNote("   ")
        val cleared = withTimeout(5_000) {
            vm.state.first { s -> s is InFlightState.Ready && s.sortieNote == null }
        } as InFlightState.Ready

        assertNull(cleared.sortieNote)
        assertNull(sortieDao.get(sortieId)!!.notes)
    }

    // ── A sortie with no Flight Plan carries a sortie note too ───────
    // The no-checklist state starts null, the save re-derives it, and the note is on the sortie row.

    @Test
    fun sortieNoteSavesOnSortieWithNoPlan() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 2, 2),
            pattern = "run", status = MissionStatus.DRAFT, outlineNotes = null
        ))
        val runSortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 2, type = SortieType.RUN,
            focus = "Easy 5k", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = 5.0, runMinutes = 25.0
        ))

        val runVm = InFlightViewModel(db, settingsStore, runSortieId, scope)
        val noPlan = withTimeout(5_000) {
            runVm.state.first { it is InFlightState.NoChecklist }
        } as InFlightState.NoChecklist
        assertNull(noPlan.sortieNote)

        runVm.saveSortieNote("Felt strong, 5k at 5:00/km")
        val withNote = withTimeout(5_000) {
            runVm.state.first { s -> s is InFlightState.NoChecklist && s.sortieNote != null }
        } as InFlightState.NoChecklist

        assertEquals("Felt strong, 5k at 5:00/km", withNote.sortieNote)
        val row = sortieDao.get(runSortieId)!!
        assertEquals("Felt strong, 5k at 5:00/km", row.notes)

        // A fresh read with no plan at all still carries the note.
        val derived = deriveInFlight(null, row, WeightUnit.LB) as InFlightState.NoChecklist
        assertEquals(withNote, derived)
        assertEquals("Felt strong, 5k at 5:00/km", derived.sortieNote)
    }

    // ── Padding is dropped, the rest of the text is kept ─────────────
    // Both notes come back trimmed at the ends only; a line break inside a note survives.

    @Test
    fun notesAreStoredTrimmed() = runBlocking {
        val state = vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
        val sortieId = state.sortieId
        val benchExerciseId = state.cards[0].plannedExerciseId

        val typedExercise = "  Left shoulder twinge\neased after set 2  "
        val keptExercise = "Left shoulder twinge\neased after set 2"
        vm.saveExerciseNote(benchExerciseId, typedExercise)
        val withExercise = withTimeout(5_000) {
            vm.state.first { s -> s is InFlightState.Ready && s.cards[0].note == keptExercise }
        } as InFlightState.Ready

        assertEquals(keptExercise, withExercise.cards[0].note)
        assertEquals(keptExercise, flightPlanDao.getPlan(sortieId)!!.exercises[0].plannedExercise.userNotes)

        val typedSortie = "\n felt strong today\n5k at 5:00/km \t"
        val keptSortie = "felt strong today\n5k at 5:00/km"
        vm.saveSortieNote(typedSortie)
        val withBoth = withTimeout(5_000) {
            vm.state.first { s -> s is InFlightState.Ready && s.sortieNote == keptSortie }
        } as InFlightState.Ready

        assertEquals(keptSortie, withBoth.sortieNote)
        assertEquals(keptSortie, sortieDao.get(sortieId)!!.notes)
        // The line break in the middle of each note is still there.
        assertTrue(withBoth.cards[0].note!!.contains("\n"))
        assertTrue(withBoth.sortieNote!!.contains("\n"))
        assertEquals(
            withBoth,
            deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
        )
    }

    // ── A run Land with a bad distance is refused before anything is written ──
    // landRun returns false for a negative, for zero and for text; the error is on the distance
    // field only, the completion callback never runs, the sortie row is still IN_FLIGHT with both
    // run fields null, and no failure message is raised — the manager was never reached.

    @Test
    fun landRunRejectsInvalidDistanceWithoutTouchingTheManager() = runBlocking {
        val sortieId = (vm.state.first { it is InFlightState.Ready } as InFlightState.Ready).sortieId
        var doneCalls = 0

        for (typed in listOf("-3", "0", "abc")) {
            assertFalse("landRun('$typed') was accepted", vm.landRun(typed, "") { doneCalls++ })
            val errors = vm.runErrors.value
            assertNotNull("landRun('$typed') raised no error", errors)
            assertEquals("Distance must be a number above 0", errors?.distanceError)
            assertNull(errors?.minutesError)
            assertNull(vm.message.value)
        }

        assertEquals(0, doneCalls)
        val row = sortieDao.get(sortieId)!!
        assertEquals(SortieState.IN_FLIGHT, row.state)
        assertNull(row.runDistance)
        assertNull(row.runMinutes)
        assertNull(vm.message.value)
    }

    // ── A run Land with a bad duration is refused the same way ─────────
    // A good distance does not rescue a bad duration: the error is on the duration field only and
    // the sortie stays IN_FLIGHT with nothing stored.

    @Test
    fun landRunRejectsInvalidDurationWithoutTouchingTheManager() = runBlocking {
        val sortieId = (vm.state.first { it is InFlightState.Ready } as InFlightState.Ready).sortieId
        var doneCalls = 0

        for (typed in listOf("-10", "0", "abc")) {
            assertFalse("landRun('$typed') was accepted", vm.landRun("5.2", typed) { doneCalls++ })
            val errors = vm.runErrors.value
            assertNotNull("landRun('$typed') raised no error", errors)
            assertNull(errors?.distanceError)
            assertEquals("Duration must be a number above 0", errors?.minutesError)
            assertNull(vm.message.value)
        }

        assertEquals(0, doneCalls)
        val row = sortieDao.get(sortieId)!!
        assertEquals(SortieState.IN_FLIGHT, row.state)
        assertNull(row.runDistance)
        assertNull(row.runMinutes)
        assertNull(vm.message.value)
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
