package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SortieType

/**
 * What one generation was asked for (DESIGN.md §7.6), so the reply can be checked against the
 * request rather than only against its schema.
 */
data class GenerationContext(
    val kind: GenerationKind,
    /** The Mission pattern, e.g. "RLRLR"; `domain/Pattern.kt`'s `sortieTypesOf` is what reads it. */
    val pattern: String,
    /** null only for an outline, which plans the whole week rather than one sortie. */
    val sortieType: SortieType? = null,
    /** Active keys only; a deactivated key is absent, and this validator cannot tell it from an unknown one. */
    val equipmentKeys: List<String>,
    val sortieLengthMinutes: Int,
) {
    init {
        require(kind != GenerationKind.FLIGHT_PLAN || sortieType != null) {
            "a FLIGHT_PLAN generation needs the sortie type it is planning"
        }
    }
}

/** A reply that passed every check, in plain coach types rather than Room rows. */
sealed interface CoachPlan

/**
 * The reply is either a plan or the list of `path: problem` lines a repair prompt is built from
 * unchanged (§7.3 step 5).
 */
sealed interface CoachReplyResult {

    data class Valid(val plan: CoachPlan) : CoachReplyResult

    data class Invalid(val errors: List<String>) : CoachReplyResult
}

/** §7.5.1 */
data class CoachOutline(
    val sorties: List<CoachSortieFocus>,
    val notes: String,
) : CoachPlan

data class CoachSortieFocus(
    val index: Int,
    val sortieType: SortieType,
    val focus: String,
    val rationale: String?,
)

/** §7.5.2 */
data class CoachLiftPlan(
    val title: String,
    val estimatedMinutes: Int,
    val warmup: String?,
    val exercises: List<CoachExercise>,
    val notes: String,
) : CoachPlan

data class CoachExercise(
    val name: String,
    val equipment: List<String>,
    val sets: List<CoachSet>,
    val restSeconds: Int?,
    val notes: String?,
)

/** Exactly one of [reps] and [seconds] is set (§7.6); [weight] is in the owner's configured unit. */
data class CoachSet(
    val reps: Int?,
    val seconds: Int?,
    val weight: Double?,
)

/** §7.5.3. `kind` stays the schema's word; the schema's `enum` is the only thing that constrains it. */
data class CoachRunPlan(
    val title: String,
    val kind: String,
    val targetDistance: Double?,
    val targetPace: String?,
    val segments: List<CoachSegment>,
    val notes: String,
) : CoachPlan

data class CoachSegment(
    val description: String,
    val distance: Double?,
    val minutes: Double?,
)
