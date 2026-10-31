package com.liftoff.app.ui.sortie

import com.liftoff.app.data.FlightPlanDetail
import com.liftoff.app.data.PlannedExerciseDetail
import com.liftoff.app.data.PlannedSet
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.ui.theme.ChipState
import com.liftoff.app.ui.theme.PatternChip
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MonthDayFmt = DateTimeFormatter.ofPattern("MMM d").withLocale(java.util.Locale.US)

/** Format a week-start date as `OCT 5`. */
fun formatWeekDate(date: LocalDate): String = date.format(MonthDayFmt).uppercase()

/** Full eyebrow: `WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT`. */
fun formatEyebrow(weekStart: LocalDate, sortieIndex: Int, patternLength: Int, type: SortieType): String {
    val label = when (type) {
        SortieType.RUN -> "RUN"
        SortieType.LIFT -> "LIFT"
    }
    return "WEEK OF ${formatWeekDate(weekStart)} · SORTIE ${sortieIndex + 1} OF $patternLength · $label"
}

/** Format a single set's rep/second value for display. */
private fun formatSetUnit(set: PlannedSet): String {
    return if (set.seconds != null) "${set.seconds} s" else set.reps.toString()
}

/** Drop the trailing `.0` a whole number prints with: `135.0` → `135`, `22.5` unchanged. */
private fun trimTrailingZero(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** Format weight for display: `135` (no trailing `.0`), `22.5`, or empty string for BW. */
internal fun formatWeight(weight: Double?): String =
    if (weight == null) "" else trimTrailingZero(weight)

/**
 * Format a distance for display: `5 mi`, `5.5 km`. The stored number is shown as-is in the
 * configured unit, never converted (plan Decision 6).
 */
internal fun formatDistance(value: Double, unit: DistanceUnit): String {
    val label = when (unit) {
        DistanceUnit.MI -> "mi"
        DistanceUnit.KM -> "km"
    }
    return "${trimTrailingZero(value)} $label"
}

/**
 * Format a duration in minutes for display: `10 min`, `2.5 min`. Minutes are shown as stored, not
 * rounded — a `2.5 min` interval rounded to `3 min` is a different workout (plan Decision).
 */
internal fun formatMinutes(value: Double): String = "${trimTrailingZero(value)} min"

/** Format a run kind as a label rather than the schema's lowercase word: `tempo` → `Tempo` (plan Decision 7). */
internal fun formatRunKind(kind: String): String =
    kind.trim().lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercase(java.util.Locale.ROOT) }

/** Format the load line for one exercise: `N×reps · weight` or `3×45 s`. */
fun formatExerciseLoad(exercise: PlannedExerciseDetail): String {
    val sets = exercise.sets
    if (sets.isEmpty()) return ""

    val count = sets.size
    val unit = formatSetUnit(sets[0])
    val weight = formatWeight(sets[0].weight)

    // Check if all sets are identical.
    val allSame = sets.all { s ->
        s.reps == sets[0].reps && s.seconds == sets[0].seconds && s.weight == sets[0].weight
    }

    return if (allSame) {
        if (weight.isEmpty()) "$count×$unit · BW" else "$count×$unit · $weight"
    } else {
        // Differing sets: join values with `/`.
        val values = sets.map(::formatSetUnit).joinToString("/")
        if (weight.isEmpty()) "$count×$values · BW" else "$count×$values · $weight"
    }
}

/** Format the Flight Plan head: `≈55 min · 14 sets`. */
fun formatPlanHead(plan: FlightPlanDetail): String {
    val minutes = plan.plan.estimatedMinutes
    val totalSets = plan.exercises.sumOf { it.sets.size }

    val minuteStr = if (minutes != null) "≈$minutes min" else null
    val setLabel = if (totalSets == 1) "1 set" else "$totalSets sets"

    return if (minuteStr != null) "$minuteStr · $setLabel" else setLabel
}

/** Derive chip states from a pattern and its sorties. */
fun deriveChips(pattern: String, sorties: List<Sortie>): List<PatternChip> {
    val stateByIndex = sorties.associateBy { it.index }
    val currentIndex = currentSortie(sorties)?.index

    return pattern.mapIndexed { index, ch ->
        val letter = ch.toString()
        val state = when (val sortieState = stateByIndex[index]?.state) {
            SortieState.LANDED -> ChipState.Landed
            SortieState.SCRUBBED -> ChipState.Scrubbed
            else -> if (index == currentIndex) ChipState.Current else ChipState.Upcoming
        }
        PatternChip(letter, state)
    }
}
