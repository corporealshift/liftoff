package com.liftoff.app.ui.sortie

import com.liftoff.app.data.*
import com.liftoff.app.ui.theme.ChipState
import com.liftoff.app.ui.theme.PatternChip
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PlanFormatTest {

    // ── formatWeekDate ────────────────────────────────────────────────

    @Test
    fun formatWeekDateOCT5() {
        assertEquals("OCT 5", formatWeekDate(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun formatWeekDateJAN12() {
        assertEquals("JAN 12", formatWeekDate(LocalDate.of(2026, 1, 12)))
    }

    @Test
    fun formatWeekDateSingleDigitDay() {
        assertEquals("MAR 3", formatWeekDate(LocalDate.of(2026, 3, 3)))
    }

    // ── formatEyebrow ─────────────────────────────────────────────────

    @Test
    fun formatEyebrowLift() {
        val actual = formatEyebrow(
            weekStart = LocalDate.of(2026, 10, 5),
            sortieIndex = 1,
            patternLength = 5,
            type = SortieType.LIFT
        )
        assertEquals("WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT", actual)
    }

    @Test
    fun formatEyebrowRun() {
        val actual = formatEyebrow(
            weekStart = LocalDate.of(2026, 1, 12),
            sortieIndex = 0,
            patternLength = 5,
            type = SortieType.RUN
        )
        assertEquals("WEEK OF JAN 12 · SORTIE 1 OF 5 · RUN", actual)
    }

    // ── formatExerciseLoad ────────────────────────────────────────────

    private fun makeExercise(name: String, sets: List<PlannedSet>): PlannedExerciseDetail {
        val pe = PlannedExercise(
            id = 1, flightPlanId = 1, `order` = 0,
            exerciseId = 1, equipmentIds = emptyList(),
            restSeconds = null, notes = null, skipped = false, userNotes = null
        )
        return PlannedExerciseDetail(pe, name, sets)
    }

    private fun makeSet(
        reps: Int? = null,
        seconds: Int? = null,
        weight: Double? = null
    ): PlannedSet {
        return PlannedSet(
            id = 1, plannedExerciseId = 1, `order` = 0,
            reps = reps, seconds = seconds, weight = weight,
            actualReps = null, actualSeconds = null, actualWeight = null,
            status = SetStatus.OPEN, added = false
        )
    }

    // Standard lift: 3×8 with weight 135 → "3×8 · 135"
    @Test
    fun formatExerciseLoadStandard() {
        val exercise = makeExercise("Bench Press", listOf(
            makeSet(reps = 8, weight = 135.0),
            makeSet(reps = 8, weight = 135.0),
            makeSet(reps = 8, weight = 135.0),
        ))
        assertEquals("3×8 · 135", formatExerciseLoad(exercise))
    }

    // Weight 135.0 drops trailing .0 → "135"
    @Test
    fun formatExerciseLoadWeightTrailingZero() {
        val exercise = makeExercise("Squat", listOf(
            makeSet(reps = 5, weight = 135.0),
            makeSet(reps = 5, weight = 135.0),
        ))
        assertEquals("2×5 · 135", formatExerciseLoad(exercise))
    }

    // Weight 22.5 keeps decimal → "22.5"
    @Test
    fun formatExerciseLoadWeightDecimal() {
        val exercise = makeExercise("Curl", listOf(
            makeSet(reps = 12, weight = 22.5),
            makeSet(reps = 12, weight = 22.5),
        ))
        assertEquals("2×12 · 22.5", formatExerciseLoad(exercise))
    }

    // Null weight → "BW"
    @Test
    fun formatExerciseLoadBodyweight() {
        val exercise = makeExercise("Push-up", listOf(
            makeSet(reps = 10, weight = null),
            makeSet(reps = 10, weight = null),
        ))
        assertEquals("2×10 · BW", formatExerciseLoad(exercise))
    }

    // Timed sets: seconds → "45 s"
    @Test
    fun formatExerciseLoadTimedSets() {
        val exercise = makeExercise("Plank", listOf(
            makeSet(seconds = 45),
            makeSet(seconds = 45),
            makeSet(seconds = 45),
        ))
        assertEquals("3×45 s · BW", formatExerciseLoad(exercise))
    }

    // Differing sets: joined with "/"
    @Test
    fun formatExerciseLoadDifferingSets() {
        val exercise = makeExercise("Deadlift", listOf(
            makeSet(reps = 8, weight = 135.0),
            makeSet(reps = 8, weight = 135.0),
            makeSet(reps = 6, weight = 135.0),
        ))
        assertEquals("3×8/8/6 · 135", formatExerciseLoad(exercise))
    }

    // Single set → "1 set" in plan head (tested below)
    @Test
    fun formatExerciseLoadSingleSet() {
        val exercise = makeExercise("Bench Press", listOf(
            makeSet(reps = 8, weight = 135.0),
        ))
        assertEquals("1×8 · 135", formatExerciseLoad(exercise))
    }

    // Empty sets → ""
    @Test
    fun formatExerciseLoadNoSets() {
        val exercise = makeExercise("Empty", emptyList())
        assertEquals("", formatExerciseLoad(exercise))
    }

    // ── formatPlanHead ────────────────────────────────────────────────

    private fun makePlan(
        estimatedMinutes: Int?,
        exercises: List<PlannedExerciseDetail> = emptyList()
    ): FlightPlanDetail {
        val fp = FlightPlan(
            id = 1, sortieId = 1, source = FlightPlanSource.GENERATED,
            title = "Test Plan", estimatedMinutes = estimatedMinutes,
            warmup = null, notes = null, runKind = null,
            targetDistance = null, targetPace = null, rawJson = "{}"
        )
        return FlightPlanDetail(fp, exercises, emptyList())
    }

    // Normal head with minutes and no exercises: "≈55 min · 0 sets"
    @Test
    fun formatPlanHeadNormal() {
        val plan = makePlan(estimatedMinutes = 55)
        assertEquals("≈55 min · 0 sets", formatPlanHead(plan))
    }

    // Null estimate → no minutes, just sets
    @Test
    fun formatPlanHeadNullEstimate() {
        val plan = makePlan(estimatedMinutes = null)
        assertEquals("0 sets", formatPlanHead(plan))
    }

    // Singular "1 set"
    @Test
    fun formatPlanHeadSingularSet() {
        val exercise = makeExercise("Bench Press", listOf(
            makeSet(reps = 8, weight = 135.0),
        ))
        val plan = makePlan(estimatedMinutes = 20, exercises = listOf(exercise))
        assertEquals("≈20 min · 1 set", formatPlanHead(plan))
    }

    // Multiple exercises: total sets summed
    @Test
    fun formatPlanHeadMultipleExercises() {
        val ex1 = makeExercise("Bench Press", listOf(
            makeSet(reps = 8, weight = 135.0),
            makeSet(reps = 8, weight = 135.0),
        ))
        val ex2 = makeExercise("Squat", listOf(
            makeSet(reps = 5, weight = 135.0),
            makeSet(reps = 5, weight = 135.0),
            makeSet(reps = 5, weight = 135.0),
        ))
        val plan = makePlan(estimatedMinutes = 45, exercises = listOf(ex1, ex2))
        assertEquals("≈45 min · 5 sets", formatPlanHead(plan))
    }

    // ── deriveChips ───────────────────────────────────────────────────

    private fun makeSortie(index: Int, state: SortieState): Sortie {
        return Sortie(
            id = index.toLong(), missionId = 1, index = index,
            type = if (index % 2 == 0) SortieType.LIFT else SortieType.RUN,
            focus = "test", focusRationale = null, state = state,
            launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        )
    }

    @Test
    fun deriveChipsLanded() {
        val sorties = listOf(makeSortie(0, SortieState.LANDED))
        val chips = deriveChips("R", sorties)
        assertEquals(1, chips.size)
        assertEquals("R", chips[0].letter)
        assertEquals(ChipState.Landed, chips[0].state)
    }

    @Test
    fun deriveChipsScrubbed() {
        val sorties = listOf(makeSortie(0, SortieState.SCRUBBED))
        val chips = deriveChips("R", sorties)
        assertEquals(1, chips.size)
        assertEquals("R", chips[0].letter)
        assertEquals(ChipState.Scrubbed, chips[0].state)
    }

    @Test
    fun deriveChipsUpcoming() {
        val sorties = listOf(makeSortie(0, SortieState.PENDING))
        val chips = deriveChips("R", sorties)
        assertEquals(1, chips.size)
        assertEquals("R", chips[0].letter)
        assertEquals(ChipState.Upcoming, chips[0].state)
    }

    @Test
    fun deriveChipsMixedStates() {
        val sorties = listOf(
            makeSortie(0, SortieState.LANDED),
            makeSortie(1, SortieState.SCRUBBED),
            makeSortie(2, SortieState.PLANNED),
            makeSortie(3, SortieState.PENDING),
        )
        val chips = deriveChips("RLRL", sorties)
        assertEquals(4, chips.size)
        assertEquals(ChipState.Landed, chips[0].state)
        assertEquals(ChipState.Scrubbed, chips[1].state)
        assertEquals(ChipState.Upcoming, chips[2].state)
        assertEquals(ChipState.Upcoming, chips[3].state)
    }

    @Test
    fun deriveChipsMissingSortieIsUpcoming() {
        val sorties = listOf(makeSortie(0, SortieState.LANDED))
        // Pattern has 3 letters but only sortie 0 exists.
        val chips = deriveChips("RLR", sorties)
        assertEquals(3, chips.size)
        assertEquals(ChipState.Landed, chips[0].state)
        assertEquals(ChipState.Upcoming, chips[1].state)
        assertEquals(ChipState.Upcoming, chips[2].state)
    }
}
