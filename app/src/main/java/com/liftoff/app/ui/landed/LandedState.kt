package com.liftoff.app.ui.landed

import com.liftoff.app.data.FlightPlanDetail
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.PlannedExerciseDetail
import com.liftoff.app.data.PlannedSet
import com.liftoff.app.data.SetStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.WeightUnit
import com.liftoff.app.ui.inflight.RunSegmentRow
import com.liftoff.app.ui.sortie.formatDistance
import com.liftoff.app.ui.sortie.formatMinutes
import com.liftoff.app.ui.sortie.formatRunKind
import com.liftoff.app.ui.sortie.formatWeight
import com.liftoff.app.ui.sortie.formatWeekDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface LandedHistoryState {
    object Loading : LandedHistoryState
    object Empty : LandedHistoryState
    data class Weeks(val weeks: List<HistoryWeek>) : LandedHistoryState
}

data class HistoryWeek(
    val weekStart: LocalDate,
    val head: String,
    val rows: List<HistoryRow>,
)

data class HistoryRow(
    val sortieId: Long,
    val indexLabel: String,
    val type: SortieType,
    val typeLabel: String,
    val title: String,
    val dateLabel: String,
    val stateLabel: String,
    val scrubReason: String?,
)

/**
 * One read of the missions and plan titles per emission, then group in Kotlin: observeHistory() has
 * already ordered the rows, so no row costs a query of its own.
 */
suspend fun deriveHistory(
    sorties: List<Sortie>,
    db: LiftoffDatabase,
    zone: ZoneId,
): LandedHistoryState {
    // Skipping the empty list keeps getByIds/getTitlesForSortieIds from being handed `IN ()`.
    if (sorties.isEmpty()) return LandedHistoryState.Empty

    val missions = db.missionDao().getByIds(sorties.map { it.missionId }.distinct()).associateBy { it.id }
    val titles = db.flightPlanDao().getTitlesForSortieIds(sorties.map { it.id }).associate { it.sortieId to it.title }

    // Consecutive grouping only: re-sorting here would fight the DAO's weekStart DESC, index DESC.
    val groups = mutableListOf<Pair<LocalDate, MutableList<HistoryRow>>>()
    for (sortie in sorties) {
        val mission = missions[sortie.missionId] ?: continue
        val row = historyRowOf(sortie, mission, titles[sortie.id], zone)
        val last = groups.lastOrNull()
        if (last != null && last.first == mission.weekStart) {
            last.second += row
        } else {
            groups += mission.weekStart to mutableListOf(row)
        }
    }

    if (groups.isEmpty()) return LandedHistoryState.Empty
    return LandedHistoryState.Weeks(
        groups.map { (weekStart, rows) ->
            HistoryWeek(weekStart, "WEEK OF " + formatWeekDate(weekStart), rows.toList())
        }
    )
}

private fun historyRowOf(
    sortie: Sortie,
    mission: Mission,
    planTitle: String?,
    zone: ZoneId,
): HistoryRow {
    // Decision 11: a sortie with neither a plan title nor a focus is named by its type word.
    val typeWord = when (sortie.type) {
        SortieType.RUN -> "Run"
        SortieType.LIFT -> "Lift"
    }
    return HistoryRow(
        sortieId = sortie.id,
        indexLabel = "%02d".format(sortie.index + 1),
        type = sortie.type,
        typeLabel = typeWord.uppercase(),
        title = planTitle ?: sortie.focus?.takeIf { it.isNotBlank() } ?: typeWord,
        dateLabel = formatWeekDate(dateOf(sortie, mission, zone)),
        stateLabel = if (sortie.state == SortieState.SCRUBBED) "SCRUBBED" else "LANDED",
        scrubReason = sortie.scrubReason,
    )
}

/**
 * Decision 2, the rule PromptInputLoader.dateOf already uses: a scrub records no timestamp, so it is
 * dated by its week rather than left without a date.
 */
private fun dateOf(sortie: Sortie, mission: Mission, zone: ZoneId): LocalDate =
    sortie.landedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: sortie.launchedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: mission.weekStart

/** What a finished sortie's detail shows: nothing, a landed lift, a landed run, or a scrub. */
sealed interface LandedDetail {
    object Loading : LandedDetail

    data class Lift(
        val sortieId: Long,
        val sortieNumber: Int,
        val eyebrow: String,
        val title: String,
        val dateLabel: String?,
        val planNote: String?,
        val doneCount: Int,
        val setCount: Int,
        val exercises: List<RecordedExercise>,
        val sortieNote: String?,
    ) : LandedDetail

    data class Run(
        val sortieId: Long,
        val sortieNumber: Int,
        val eyebrow: String,
        val title: String,
        val dateLabel: String?,
        val kind: String?,
        val targetLine: String?,
        val segments: List<RunSegmentRow>,
        val recordedDistance: String?,
        val recordedDuration: String?,
        val planNote: String?,
        val sortieNote: String?,
    ) : LandedDetail

    data class Scrubbed(
        val sortieId: Long,
        val sortieNumber: Int,
        val eyebrow: String,
        val title: String,
        val reason: String?,
        val doneCount: Int,
        val setCount: Int,
        val exercises: List<RecordedExercise>,
        val sortieNote: String?,
    ) : LandedDetail
}

enum class SetMark { DONE, SKIPPED, NOT_DONE }

data class RecordedExercise(
    val number: Int,
    val name: String,
    val exerciseSkipped: Boolean,
    val note: String?,
    val doneCount: Int,
    val setCount: Int,
    val sets: List<RecordedSet>,
)

data class RecordedSet(
    val label: String,
    val mark: SetMark,
    val added: Boolean,
    val plannedLabel: String,
    val actualLabel: String?,
    val weightDeviates: Boolean,
    val countDeviates: Boolean,
)

/** Pure derivation function — no I/O. Tests can call it on a fresh `getPlan` read. */
fun deriveLandedDetail(
    plan: FlightPlanDetail?,
    sortie: Sortie,
    mission: Mission?,
    weightUnit: WeightUnit,
    distanceUnit: DistanceUnit,
    zone: ZoneId,
): LandedDetail {
    val sortieNumber = sortie.index + 1
    val typeWord = if (sortie.type == SortieType.RUN) "Run" else "Lift"
    // The same eyebrow shape MissionScreen's detail already uses.
    val stateWord = if (sortie.state == SortieState.SCRUBBED) "SCRUBBED" else "LANDED"
    val eyebrow = "SORTIE $sortieNumber \u00b7 ${typeWord.uppercase()} \u00b7 $stateWord"
    // Decision 11 again: the detail is named the way the row the user tapped was named.
    val title = when {
        !plan?.plan?.title.isNullOrBlank() -> plan!!.plan.title
        !sortie.focus.isNullOrBlank() -> sortie.focus
        else -> typeWord
    }
    val planNote = plan?.plan?.notes?.takeIf { it.isNotBlank() }

    if (sortie.state == SortieState.SCRUBBED) {
        val all = plan?.exercises.orEmpty().flatMap { it.sets }
        return LandedDetail.Scrubbed(
            sortieId = sortie.id,
            sortieNumber = sortieNumber,
            eyebrow = eyebrow,
            title = title,
            // Decision 2: a scrub recorded no timestamp, so a scrubbed detail shows no date line.
            reason = sortie.scrubReason,
            doneCount = all.count { it.status == SetStatus.DONE },
            setCount = all.size,
            exercises = recordedExercises(plan, weightUnit, onlyDoneSets = true).filter { it.doneCount > 0 },
            sortieNote = sortie.notes,
        )
    }

    // The Landed tab only ever opens a finished sortie; anything else has nothing to show yet.
    if (sortie.state != SortieState.LANDED) return LandedDetail.Loading

    val dateLabel = landedDateLabel(sortie, mission, zone)

    if (sortie.type == SortieType.RUN) {
        return LandedDetail.Run(
            sortieId = sortie.id,
            sortieNumber = sortieNumber,
            eyebrow = eyebrow,
            title = title,
            dateLabel = dateLabel,
            kind = plan?.plan?.runKind?.takeIf { it.isNotBlank() }?.let(::formatRunKind),
            targetLine = listOfNotNull(
                plan?.plan?.targetDistance?.let { formatDistance(it, distanceUnit) },
                plan?.plan?.targetPace?.takeIf { it.isNotBlank() },
            ).joinToString(" \u00b7 ").takeIf { it.isNotEmpty() },
            segments = plan?.segments.orEmpty().map { segment ->
                val detail = listOfNotNull(
                    segment.distance?.let { formatDistance(it, distanceUnit) },
                    segment.minutes?.let { formatMinutes(it) },
                ).joinToString(" \u00b7 ").takeIf { it.isNotEmpty() }
                RunSegmentRow(description = segment.description, detail = detail)
            },
            recordedDistance = sortie.runDistance?.let { formatDistance(it, distanceUnit) },
            recordedDuration = sortie.runMinutes?.let { formatMinutes(it) },
            planNote = planNote,
            sortieNote = sortie.notes,
        )
    }

    val all = plan?.exercises.orEmpty().flatMap { it.sets }
    return LandedDetail.Lift(
        sortieId = sortie.id,
        sortieNumber = sortieNumber,
        eyebrow = eyebrow,
        title = title,
        dateLabel = dateLabel,
        planNote = planNote,
        doneCount = all.count { it.status == SetStatus.DONE },
        setCount = all.size,
        exercises = recordedExercises(plan, weightUnit, onlyDoneSets = false),
        sortieNote = sortie.notes,
    )
}

private fun recordedExercises(
    plan: FlightPlanDetail?,
    weightUnit: WeightUnit,
    onlyDoneSets: Boolean,
): List<RecordedExercise> =
    plan?.exercises.orEmpty().mapIndexed { index, detail ->
        val skipped = detail.plannedExercise.skipped
        val sets = detail.sets.mapIndexed { setIndex, set ->
            recordedSet(set, setIndex + 1, skipped, weightUnit)
        }
        RecordedExercise(
            number = index + 1,
            name = detail.displayName,
            exerciseSkipped = skipped,
            note = detail.plannedExercise.userNotes,
            doneCount = detail.sets.count { it.status == SetStatus.DONE },
            // setCount is every planned set, so the counts read "n of m" even where only the DONE
            // sets are listed (a scrubbed detail).
            setCount = detail.sets.size,
            sets = if (onlyDoneSets) sets.filter { it.mark == SetMark.DONE } else sets,
        )
    }

private fun recordedSet(
    set: PlannedSet,
    position: Int,
    exerciseSkipped: Boolean,
    weightUnit: WeightUnit,
): RecordedSet {
    // Decision 1: only a skip the user chose on purpose reads as "skipped". land() rewrote every
    // still-OPEN set to SKIPPED (markOpenSetsNotDone), and the Land confirmation promised "not done".
    val mark = when {
        set.status == SetStatus.DONE -> SetMark.DONE
        set.status == SetStatus.SKIPPED && exerciseSkipped -> SetMark.SKIPPED
        else -> SetMark.NOT_DONE
    }
    val recorded = set.actualWeight != null || set.actualReps != null || set.actualSeconds != null
    return RecordedSet(
        label = "SET $position",
        mark = mark,
        added = set.added,
        plannedLabel = valueLabel(set.weight, set.reps, set.seconds, weightUnit),
        actualLabel = if (recorded) valueLabel(set.actualWeight, set.actualReps, set.actualSeconds, weightUnit) else null,
        // Decision 8: field by field, and only on a set that was actually done.
        weightDeviates = set.status == SetStatus.DONE && set.actualWeight != set.weight,
        countDeviates = set.status == SetStatus.DONE &&
            (set.actualReps != set.reps || set.actualSeconds != set.seconds),
    )
}

/** `135 LB × 8`, `BW × 8`, `45 s`. A timed bodyweight set is just its duration. */
private fun valueLabel(weight: Double?, reps: Int?, seconds: Int?, weightUnit: WeightUnit): String {
    val count = if (seconds != null) "$seconds s" else reps?.let { "\u00d7 $it" }
    if (weight == null && seconds != null) return count ?: "BW"
    val load = if (weight == null) "BW" else "${formatWeight(weight)} ${weightUnit.name}"
    return if (count == null) load else "$load $count"
}

/** Decision 2's rule, for a detail that has no Mission row to fall back on. */
private fun landedDateLabel(sortie: Sortie, mission: Mission?, zone: ZoneId): String? =
    (sortie.landedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: sortie.launchedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: mission?.weekStart)?.let(::formatWeekDate)
