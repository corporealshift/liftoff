package com.liftoff.app.ui.inflight

import com.liftoff.app.data.*
import com.liftoff.app.settings.WeightUnit
import com.liftoff.app.ui.sortie.formatWeight

/** Derived state for the In-Flight checklist screen. */
sealed interface InFlightState {
    object Loading : InFlightState

    /** A sortie with no plan or no exercises (e.g. a RUN sortie). */
    data class NoChecklist(
        val sortieId: Long,
        val eyebrow: String,
        val title: String,
    ) : InFlightState

    /** Full checklist state for a LIFT sortie with exercises. */
    data class Ready(
        val sortieId: Long,
        val eyebrow: String,          // "IN FLIGHT · SORTIE n", n = sortie.index + 1
        val title: String,            // plan.title (if not blank), else focus, else "Sortie n"
        val doneCount: Int,           // DONE sets over all exercises, added sets included
        val setCount: Int,            // total sets, added sets included
        val progress: List<SetStatus>,// one per set in display order
        val cards: List<ExerciseCard>,
        val currentSetId: Long?,      // first OPEN set of the ACTIVE card; null if no active card
    ) : InFlightState
}

enum class CardStatus { SKIPPED, DONE, ACTIVE, UPCOMING }

data class ExerciseCard(
    val plannedExerciseId: Long,
    val number: Int,                  // 1-based position among exercises
    val name: String,                 // displayName
    val status: CardStatus,
    val doneCount: Int,               // DONE sets in this exercise
    val setCount: Int,
    val sets: List<SetRow>,
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
    val added: Boolean,
    val weightDeviates: Boolean,      // DONE only: actualWeight != plannedWeight
    val countDeviates: Boolean,       // DONE only: actualReps != reps || actualSeconds != seconds
)

/** Pure derivation function — no I/O. Tests can call it on a fresh `getPlan` read. */
fun deriveInFlight(plan: FlightPlanDetail?, sortie: Sortie, unit: WeightUnit): InFlightState {
    val sortieNumber = sortie.index + 1
    val eyebrow = "IN FLIGHT \u00b7 SORTIE $sortieNumber"

    // Title: plan.title (if not blank), else sortie.focus (if not blank), else "Sortie n"
    val title = when {
        !plan?.plan?.title.isNullOrBlank() -> plan!!.plan.title
        !sortie.focus.isNullOrBlank() -> sortie.focus
        else -> "Sortie $sortieNumber"
    }

    // No checklist: run sorties or a plan with zero exercises
    if (plan == null || plan.exercises.isEmpty()) {
        return InFlightState.NoChecklist(sortie.id, eyebrow, title)
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
        eyebrow = eyebrow,
        title = title,
        doneCount = allProgress.count { it == SetStatus.DONE },
        setCount = allProgress.size,
        progress = allProgress,
        cards = cards,
        currentSetId = currentSetId,
    )
}
