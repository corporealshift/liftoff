package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.WeightUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** Derivation tests on fresh `getPlan` reads against the fixture (3-exercise LIFT sortie). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightStatesTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var sortieDao: SortieDao
    private lateinit var missionDao: MissionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        flightPlanDao = db.flightPlanDao()
        sortieDao = db.sortieDao()
        missionDao = db.missionDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // ── Fixture setup ────────────────────────────────────────────────

    /** Build the fixture: a mission with a LIFT sortie (index=1, state=IN_FLIGHT, focus="Push") and a 3-exercise plan. */
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

    // ── Initial card states, counter, labels and current set ─────────
    // Eyebrow 'IN FLIGHT · SORTIE 2', plan title, counter 0/7, 7 open segments,
    // cards [ACTIVE, UPCOMING, UPCOMING] with 0/3, 0/2, 0/2, labels 35 LB / × 10 / BW / 45 s,
    // current set = first bench set, nothing added, no deviations.

    @Test
    fun initialState() = runBlocking {
        val sortieId = buildFixture()
        val plan = flightPlanDao.getPlan(sortieId)!!
        val sortie = sortieDao.get(sortieId)!!

        val state = deriveInFlight(plan, sortie, WeightUnit.LB) as InFlightState.Ready

        // Eyebrow and title
        assertEquals("IN FLIGHT \u00b7 SORTIE 2", state.eyebrow)
        assertEquals("Upper Push", state.title)

        // Counter
        assertEquals(0, state.doneCount)
        assertEquals(7, state.setCount)

        // All progress segments are OPEN
        assertEquals(7, state.progress.size)
        assertTrue(state.progress.all { it == SetStatus.OPEN })

        // Three cards: [ACTIVE, UPCOMING, UPCOMING] with done/set counts 0/3, 0/2, 0/2
        assertEquals(3, state.cards.size)

        val benchCard = state.cards[0]
        assertEquals("Bench Press", benchCard.name)
        assertEquals(CardStatus.ACTIVE, benchCard.status)
        assertEquals(0, benchCard.doneCount)
        assertEquals(3, benchCard.setCount)
        assertEquals(3, benchCard.sets.size)

        val pushupCard = state.cards[1]
        assertEquals("Push-up", pushupCard.name)
        assertEquals(CardStatus.UPCOMING, pushupCard.status)
        assertEquals(0, pushupCard.doneCount)
        assertEquals(2, pushupCard.setCount)

        val kbCard = state.cards[2]
        assertEquals("Kettlebell Hold", kbCard.name)
        assertEquals(CardStatus.UPCOMING, kbCard.status)
        assertEquals(0, kbCard.doneCount)
        assertEquals(2, kbCard.setCount)

        // Bench set 1 labels: "35 LB" and "× 10"
        val benchSet1 = benchCard.sets[0]
        assertEquals("35 LB", benchSet1.weightLabel)
        assertEquals("\u00d7 10", benchSet1.countLabel)

        // Push-up set 1 labels: "BW" and "× 12"
        val pushupSet1 = pushupCard.sets[0]
        assertEquals("BW", pushupSet1.weightLabel)
        assertEquals("\u00d7 12", pushupSet1.countLabel)

        // Kettlebell Hold set 2: seconds-based, weight null → "BW"
        val kbSet2 = kbCard.sets[1]
        assertEquals("BW", kbSet2.weightLabel)
        assertEquals("45 s", kbSet2.countLabel)

        // Current set is the first bench set
        assertNotNull(state.currentSetId)
        assertEquals(benchSet1.setId, state.currentSetId)

        // Nothing added, no deviations
        assertTrue(state.cards.all { card -> card.sets.all { !it.added && !it.weightDeviates && !it.countDeviates } })
    }

    // ── Weight label in KG ───────────────────────────────────────────
    // Weight label in the configured unit: KG gives '20 KG'.

    @Test
    fun kgUnitLabel() = runBlocking {
        val sortieId = buildFixture()
        val plan = flightPlanDao.getPlan(sortieId)!!
        val sortie = sortieDao.get(sortieId)!!

        val state = deriveInFlight(plan, sortie, WeightUnit.KG) as InFlightState.Ready

        // Bench set 1: 35 KG (integer weight)
        val benchSet1 = state.cards[0].sets[0]
        assertEquals("35 KG", benchSet1.weightLabel)

        // Kettlebell Hold set 1: 20 KG
        val kbSet1 = state.cards[2].sets[0]
        assertEquals("20 KG", kbSet1.weightLabel)

        // Push-up still BW (no weight)
        val pushupSet1 = state.cards[1].sets[0]
        assertEquals("BW", pushupSet1.weightLabel)
    }

    // ── Title fallbacks ──────────────────────────────────────────────
    // A blank plan title falls back to the focus, and then to 'Sortie n'.

    @Test
    fun titleFallbacks() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))

        // Case 1: blank plan title → falls back to focus
        val sortieId1 = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId1, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 10, seconds = null, weight = 35.0)
            ))),
            segments = emptyList()
        ))
        val plan1 = flightPlanDao.getPlan(sortieId1)!!
        val sortie1 = sortieDao.get(sortieId1)!!
        val state1 = deriveInFlight(plan1, sortie1, WeightUnit.LB) as InFlightState.Ready
        assertEquals("Push", state1.title)

        // Case 2: blank title + null focus → "Sortie n"
        val sortieId2 = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = null, focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId2, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 10, seconds = null, weight = 35.0)
            ))),
            segments = emptyList()
        ))
        val plan2 = flightPlanDao.getPlan(sortieId2)!!
        val sortie2 = sortieDao.get(sortieId2)!!
        val state2 = deriveInFlight(plan2, sortie2, WeightUnit.LB) as InFlightState.Ready
        assertEquals("Sortie 2", state2.title)
    }

    // ── No checklist for a sortie with no plan or no exercises ───────
    // Decision: 'A no checklist state still carries the eyebrow and title'. A RUN sortie with a plan
    // is the exception: it gets the run state, which carries the plan's own rows.

    @Test
    fun noChecklist() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))

        // Case 1: LIFT sortie with no plan at all
        val liftNoPlanId = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        val liftNoPlanSortie = sortieDao.get(liftNoPlanId)!!
        val noPlanState = deriveInFlight(null, liftNoPlanSortie, WeightUnit.LB)
        assertTrue(noPlanState is InFlightState.NoChecklist)
        val noCheck = noPlanState as InFlightState.NoChecklist
        assertEquals("IN FLIGHT \u00b7 SORTIE 2", noCheck.eyebrow)
        assertEquals("Push", noCheck.title)

        // Case 2: RUN sortie with a plan that has zero exercises (segments only) — now the run state
        val runSortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.RUN,
            focus = "easy jog", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(runSortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Run Plan", estimatedMinutes = 30,
            warmup = null, notes = null, runKind = "steady", targetDistance = 5000.0,
            targetPace = "5:00", rawJson = "{}",
            exercises = emptyList(),
            segments = listOf(RunSegmentDraft(description = "Warmup", distance = 800.0, minutes = 4.0))
        ))
        val runSortie = sortieDao.get(runSortieId)!!
        val runPlan = flightPlanDao.getPlan(runSortieId)!!
        val runState = deriveInFlight(runPlan, runSortie, WeightUnit.LB)
        assertTrue(runState is InFlightState.Run)
        val run = runState as InFlightState.Run
        assertEquals("IN FLIGHT \u00b7 SORTIE 2", run.eyebrow)
        // Plan title "Run Plan" is not blank, so it wins over focus
        assertEquals("Run Plan", run.title)
        assertEquals("easy jog", run.notesLine)
        assertEquals("Steady", run.kind)
        // The defaulted distanceUnit is MI, and the stored number is shown as-is.
        assertEquals("5000 mi", run.targetDistance)
        assertEquals("5:00", run.targetPace)
        assertEquals(1, run.segments.size)
        assertEquals("Warmup", run.segments[0].description)
        assertEquals("800 mi \u00b7 4 min", run.segments[0].detail)
    }

    // ── Open-set count ───────────────────────────────────────────────
    // The derived state reports how many sets are still OPEN: the full count before anything is
    // checked, it drops as sets are checked, and a skipped exercise's sets are not counted.

    @Test
    fun openCountCountsOpenSets() = runBlocking {
        val sortieId = buildFixture()

        // Nothing checked yet: every one of the 7 sets is open.
        var state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(2, state.sortieNumber)
        assertEquals(7, state.setCount)
        assertEquals(0, state.doneCount)
        assertEquals(7, state.openCount)

        // Checking sets moves them out of the open count, not out of the total.
        flightPlanDao.checkSet(state.cards[0].sets[0].setId)
        flightPlanDao.checkSet(state.cards[0].sets[1].setId)
        state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(2, state.doneCount)
        assertEquals(7, state.setCount)
        assertEquals(5, state.openCount)

        // Skipping an exercise marks its open sets SKIPPED, so they stop counting as open.
        flightPlanDao.skipExercise(state.cards[1].plannedExerciseId)
        state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(CardStatus.SKIPPED, state.cards[1].status)
        assertEquals(2, state.doneCount)
        assertEquals(7, state.setCount)
        assertEquals(3, state.openCount)
        assertEquals(2, state.progress.count { it == SetStatus.SKIPPED })

        // Checking the rest leaves nothing open.
        for (card in state.cards) {
            for (set in card.sets) {
                if (set.status == SetStatus.OPEN) flightPlanDao.checkSet(set.setId)
            }
        }
        state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(0, state.openCount)
    }

    // ── When landing needs confirmation ──────────────────────────────
    // The confirmation decision reads that count: no confirmation when nothing is open,
    // confirmation as soon as one set is open.

    @Test
    fun confirmationNeededOnlyWhenSetsAreOpen() = runBlocking {
        val sortieId = buildFixture()

        // Open sets from the start.
        var state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertTrue(needsLandConfirmation(state))

        // One open set is enough to confirm.
        for (set in state.cards[0].sets) flightPlanDao.checkSet(set.setId)
        for (set in state.cards[2].sets) flightPlanDao.checkSet(set.setId)
        flightPlanDao.checkSet(state.cards[1].sets[0].setId)
        state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(1, state.openCount)
        assertTrue(needsLandConfirmation(state))

        // Nothing open: land straight away.
        flightPlanDao.checkSet(state.cards[1].sets[1].setId)
        state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(0, state.openCount)
        assertFalse(needsLandConfirmation(state))

        // Unchecking one set puts confirmation back.
        flightPlanDao.uncheckSet(state.cards[1].sets[0].setId)
        state = deriveInFlight(flightPlanDao.getPlan(sortieId)!!, sortieDao.get(sortieId)!!, WeightUnit.LB)
            as InFlightState.Ready
        assertEquals(1, state.openCount)
        assertTrue(needsLandConfirmation(state))
    }

    // ── A sortie with no checklist is never confirmed ────────────────
    // A run, a plan with no exercises, and the Loading state never confirm.

    @Test
    fun noChecklistNeedsNoConfirmation() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))

        // Case 1: a RUN sortie with no plan at all.
        val runId = sortieDao.insert(Sortie(
            missionId = missionId, index = 0, type = SortieType.RUN,
            focus = "easy jog", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        val runSortie = sortieDao.get(runId)!!
        val runState = deriveInFlight(null, runSortie, WeightUnit.LB)
        assertTrue(runState is InFlightState.NoChecklist)
        assertEquals(1, (runState as InFlightState.NoChecklist).sortieNumber)
        assertFalse(needsLandConfirmation(runState))

        // Case 2: a plan with no exercises.
        val emptyPlanId = sortieDao.insert(Sortie(
            missionId = missionId, index = 2, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(emptyPlanId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = emptyList(),
            segments = emptyList()
        ))
        val emptyState = deriveInFlight(flightPlanDao.getPlan(emptyPlanId)!!, sortieDao.get(emptyPlanId)!!, WeightUnit.LB)
        assertTrue(emptyState is InFlightState.NoChecklist)
        assertEquals(3, (emptyState as InFlightState.NoChecklist).sortieNumber)
        assertFalse(needsLandConfirmation(emptyState))

        // Case 3: the state before the sortie has been read.
        assertFalse(needsLandConfirmation(InFlightState.Loading))
    }
}
