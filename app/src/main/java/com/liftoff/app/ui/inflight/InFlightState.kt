package com.liftoff.app.ui.inflight

import com.liftoff.app.data.*
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.WeightUnit
import com.liftoff.app.ui.sortie.formatDistance
import com.liftoff.app.ui.sortie.formatMinutes
import com.liftoff.app.ui.sortie.formatRunKind
import com.liftoff.app.ui.sortie.formatWeight

/** Derived state for the In-Flight checklist screen. */
sealed interface InFlightState {
    object Loading : InFlightState

    /** A sortie with no plan or no exercises (a run sortie whose plan never got written). */
    data class NoChecklist(
        val sortieId: Long,
        val sortieNumber: Int,        // sortie.index + 1, for a dialog title
        val eyebrow: String,
        val title: String,
        val sortieNote: String?,
    ) : InFlightState

    /** A RUN sortie with a Flight Plan: its kind, target distance/pace and segments. */
    data class Run(
        val sortieId: Long,
        val sortieNumber: Int,        // sortie.index + 1, for a dialog title
        val eyebrow: String,          // "IN FLIGHT · SORTIE n", n = sortie.index + 1
        val title: String,            // plan.title (if not blank), else focus, else "Sortie n"
        val notesLine: String?,       // plan notes, else focus; null when it would repeat the title
        val kind: String?,            // formatRunKind(runKind), null when the plan has none
        val targetDistance: String?,  // formatDistance(targetDistance, distanceUnit)
        val targetPace: String?,      // as written in the plan
        val segments: List<RunSegmentRow>,
        val sortieNote: String?,      // the sortie row's own note
        val distanceUnit: DistanceUnit, // the configured unit, so the land dialog can label its field
    ) : InFlightState

    /** Full checklist state for a LIFT sortie with exercises. */
    data class Ready(
        val sortieId: Long,
        val sortieNumber: Int,        // sortie.index + 1, for a dialog title
        val eyebrow: String,          // "IN FLIGHT · SORTIE n", n = sortie.index + 1
        val title: String,            // plan.title (if not blank), else focus, else "Sortie n"
        val weightUnit: WeightUnit,   // user's configured unit for the stepper
        val doneCount: Int,           // DONE sets over all exercises, added sets included
        val setCount: Int,            // total sets, added sets included
        val openCount: Int,           // OPEN sets: what landing would record as not done
        val progress: List<SetStatus>,// one per set in display order
        val cards: List<ExerciseCard>,
        val currentSetId: Long?,      // first OPEN set of the ACTIVE card; null if no active card
        val sortieNote: String?,      // the sortie row's own note
    ) : InFlightState
}

/** A skipped exercise's sets are already SKIPPED, so they are not counted here. */
fun needsLandConfirmation(state: InFlightState): Boolean =
    state is InFlightState.Ready && state.openCount > 0

enum class CardStatus { SKIPPED, DONE, ACTIVE, UPCOMING }

data class ExerciseCard(
    val plannedExerciseId: Long,
    val number: Int,                  // 1-based position among exercises
    val name: String,                 // displayName
    val status: CardStatus,
    val doneCount: Int,               // DONE sets in this exercise
    val setCount: Int,
    val sets: List<SetRow>,
    val note: String?,                // plannedExercise.userNotes
)

data class SetRow(
    val setId: Long,
    val label: String,                // "SET n", 1-based within the exercise
    val status: SetStatus,
    val plannedReps: Int?,
    val plannedSeconds: Int?,
    val plannedWeight: Double?,
    val actualReps: Int?,
    val actualSeconds: Int?,
    val actualWeight: Double?,
    val weightLabel: String,          // "35 LB" / "20 KG" / "BW"
    val countLabel: String,           // "× 10" / "45 s"
    val plannedWeightLabel: String,   // planned weight formatted like weightLabel: "35 LB" / "BW"
    val plannedCountLabel: String,    // planned count: bare reps "10" or seconds "45 s"
    val added: Boolean,
    val weightDeviates: Boolean,      // DONE only: actualWeight != plannedWeight
    val countDeviates: Boolean,       // DONE only: actualReps != reps || actualSeconds != seconds
)

/** A run plan's segment as the screen shows it. Not the Room entity `data/RunSegment.kt`. */
data class RunSegmentRow(
    val description: String,
    val detail: String?,              // "800 mi · 4 min"; null when the segment has neither
)

/** Pure derivation function — no I/O. Tests can call it on a fresh `getPlan` read. */
fun deriveInFlight(
    plan: FlightPlanDetail?,
    sortie: Sortie,
    unit: WeightUnit,
    distanceUnit: DistanceUnit = DistanceUnit.MI,
): InFlightState {
    val sortieNumber = sortie.index + 1
    val eyebrow = "IN FLIGHT \u00b7 SORTIE $sortieNumber"

    // Title: plan.title (if not blank), else sortie.focus (if not blank), else "Sortie n"
    val title = when {
        !plan?.plan?.title.isNullOrBlank() -> plan!!.plan.title
        !sortie.focus.isNullOrBlank() -> sortie.focus
        else -> "Sortie $sortieNumber"
    }

    // A run plan carries segments, not exercises, so it is read before the no-checklist fall-through.
    if (sortie.type == SortieType.RUN && plan != null) {
        val notesLine = when {
            !plan.plan.notes.isNullOrBlank() -> plan.plan.notes
            !sortie.focus.isNullOrBlank() -> sortie.focus
            else -> null
        }?.takeIf { it != title }

        return InFlightState.Run(
            sortieId = sortie.id,
            sortieNumber = sortieNumber,
            eyebrow = eyebrow,
            title = title,
            notesLine = notesLine,
            kind = plan.plan.runKind?.takeIf { it.isNotBlank() }?.let(::formatRunKind),
            targetDistance = plan.plan.targetDistance?.let { formatDistance(it, distanceUnit) },
            targetPace = plan.plan.targetPace?.takeIf { it.isNotBlank() },
            segments = plan.segments.map { segment ->
                val detail = listOfNotNull(
                    segment.distance?.let { formatDistance(it, distanceUnit) },
                    segment.minutes?.let { formatMinutes(it) },
                ).joinToString(" \u00b7 ").takeIf { it.isNotEmpty() }
                RunSegmentRow(description = segment.description, detail = detail)
            },
            sortieNote = sortie.notes,
            distanceUnit = distanceUnit,
        )
    }

    // No checklist: a sortie with no plan, or a plan with zero exercises
    if (plan == null || plan.exercises.isEmpty()) {
        return InFlightState.NoChecklist(sortie.id, sortieNumber, eyebrow, title, sortie.notes)
    }

    val allProgress = mutableListOf<SetStatus>()
    val cards = mutableListOf<ExerciseCard>()

    for ((exerciseIdx, exerciseDetail) in plan.exercises.withIndex()) {
        val sets = exerciseDetail.sets
        val exerciseNumber = exerciseIdx + 1

        // Build set rows
        val setRows = sets.mapIndexed { setIdx, plannedSet ->
            val displayReps = if (plannedSet.status == SetStatus.DONE) plannedSet.actualReps else plannedSet.reps
            val displaySeconds = if (plannedSet.status == SetStatus.DONE) plannedSet.actualSeconds else plannedSet.seconds
            val displayWeight = if (plannedSet.status == SetStatus.DONE) plannedSet.actualWeight else plannedSet.weight

            // Weight label: "BW" when null, otherwise formatted weight + unit name
            val weightLabel = if (displayWeight == null) {
                "BW"
            } else {
                "${formatWeight(displayWeight)} ${unit.name}"
            }

            // Count label: seconds takes precedence over reps (same as formatSetUnit)
            val countLabel = if (displaySeconds != null) {
                "${displaySeconds} s"
            } else if (displayReps != null) {
                "\u00d7 $displayReps"
            } else {
                ""
            }

            // Deviation flags: only meaningful for DONE sets
            val weightDeviates = if (plannedSet.status == SetStatus.DONE) {
                plannedSet.actualWeight != plannedSet.weight
            } else false

            val countDeviates = if (plannedSet.status == SetStatus.DONE) {
                plannedSet.actualReps != plannedSet.reps || plannedSet.actualSeconds != plannedSet.seconds
            } else false

            // Planned labels: use the planned values (not actuals) for "of <planned>" display
            val plannedWeightLabel = if (plannedSet.weight == null) {
                "BW"
            } else {
                "${formatWeight(plannedSet.weight)} ${unit.name}"
            }

            val plannedCountLabel = if (plannedSet.seconds != null) {
                "${plannedSet.seconds} s"
            } else if (plannedSet.reps != null) {
                "${plannedSet.reps}"
            } else {
                ""
            }

            SetRow(
                setId = plannedSet.id,
                label = "SET ${setIdx + 1}",
                status = plannedSet.status,
                plannedReps = plannedSet.reps,
                plannedSeconds = plannedSet.seconds,
                plannedWeight = plannedSet.weight,
                actualReps = plannedSet.actualReps,
                actualSeconds = plannedSet.actualSeconds,
                actualWeight = plannedSet.actualWeight,
                weightLabel = weightLabel,
                countLabel = countLabel,
                plannedWeightLabel = plannedWeightLabel,
                plannedCountLabel = plannedCountLabel,
                added = plannedSet.added,
                weightDeviates = weightDeviates,
                countDeviates = countDeviates,
            )
        }

        allProgress.addAll(setRows.map { it.status })

        val doneCount = setRows.count { it.status == SetStatus.DONE }
        val hasOpenSet = setRows.any { it.status == SetStatus.OPEN }

        cards.add(ExerciseCard(
            plannedExerciseId = exerciseDetail.plannedExercise.id,
            number = exerciseNumber,
            name = exerciseDetail.displayName,
            status = when {
                exerciseDetail.plannedExercise.skipped -> CardStatus.SKIPPED
                !hasOpenSet -> CardStatus.DONE  // every set is DONE or SKIPPED (including zero sets)
                else -> CardStatus.UPCOMING      // placeholder; resolved below
            },
            doneCount = doneCount,
            setCount = sets.size,
            sets = setRows,
            note = exerciseDetail.plannedExercise.userNotes,
        ))
    }

    // Find the ACTIVE card: first non-skipped, non-done exercise with an OPEN set
    var activeIdx = -1
    for ((idx, card) in cards.withIndex()) {
        if (card.status != CardStatus.SKIPPED && card.status != CardStatus.DONE &&
            card.sets.any { it.status == SetStatus.OPEN }) {
            activeIdx = idx
            break
        }
    }

    // Update ACTIVE / UPCOMING statuses
    for (idx in cards.indices) {
        val card = cards[idx]
        if (card.status != CardStatus.SKIPPED && card.status != CardStatus.DONE) {
            cards[idx] = card.copy(status = if (idx == activeIdx) CardStatus.ACTIVE else CardStatus.UPCOMING)
        }
    }

    // Current set: first OPEN set of the ACTIVE card
    val currentSetId = if (activeIdx >= 0) {
        cards[activeIdx].sets.find { it.status == SetStatus.OPEN }?.setId
    } else null

    return InFlightState.Ready(
        sortieId = sortie.id,
        sortieNumber = sortieNumber,
        eyebrow = eyebrow,
        title = title,
        weightUnit = unit,
        doneCount = allProgress.count { it == SetStatus.DONE },
        setCount = allProgress.size,
        openCount = allProgress.count { it == SetStatus.OPEN },
        progress = allProgress,
        cards = cards,
        currentSetId = currentSetId,
        sortieNote = sortie.notes,
    )
}
