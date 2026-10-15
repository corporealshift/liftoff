package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SetStatus
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.WeightUnit
import java.time.LocalDate

/**
 * The plain input types §7.4's prompt builder is a function from.
 *
 * They are deliberately not Room rows: the builder stays a pure formatter, and everything that
 * needs a clock, a zone or a query (the history window, a sortie's date, which equipment is active)
 * is settled before the input is built. `data/Enums.kt` and `settings/Settings.kt` are reused rather
 * than mirrored here, so §2 vocabulary lives in one place.
 */

/** §7.4 item 3. */
data class PromptProfile(
    val objectives: String,
    val constraints: String,
    val sortieLengthMinutes: Int,
    val weightUnit: WeightUnit,
    val distanceUnit: DistanceUnit,
)

/** §7.4 item 4. [notes] empty or blank means the item has none, and the line carries no third field. */
data class PromptEquipment(val key: String, val name: String, val notes: String = "")

/** §7.4 item 5: the week as the prompt sees it. */
data class PromptWeek(val pattern: String, val sorties: List<PromptSortie>)

data class PromptSortie(
    val index: Int,
    val type: SortieType,
    val state: SortieState,
    /** null when no outline focus is stored for this position. */
    val focus: String? = null,
)

/**
 * §7.4 item 6, one set.
 *
 * [reps]/[seconds]/[weight] are the actuals as recorded, so they are null for a set that was never
 * done; [plannedReps]/[plannedSeconds]/[plannedWeight] are what the coach proposed, which is what an
 * unfinished set reads on and what a deviation names. Exactly one of reps and seconds is ever written
 * on a set (§7.6).
 */
data class PromptHistorySet(
    val reps: Int?,
    val seconds: Int?,
    val weight: Double?,
    val status: SetStatus,
    val plannedReps: Int? = null,
    val plannedSeconds: Int? = null,
    val plannedWeight: Double? = null,
) {
    init {
        require(reps != null || seconds != null || plannedReps != null || plannedSeconds != null) {
            "a set has either reps or seconds (§7.6)"
        }
    }
}

/** §7.4 item 6, one exercise of a history sortie. [note] is the owner's, never the coach's. */
data class PromptHistoryExercise(
    val name: String,
    val sets: List<PromptHistorySet>,
    val note: String? = null,
)

/**
 * §7.4 item 6, one landed or scrubbed sortie.
 *
 * [date] is the date the history window counted this sortie in by, and the one the block prints;
 * [exercises] and [notes] are already newest-first / as-recorded, which is the loader's job.
 */
data class PromptHistorySortie(
    val date: LocalDate,
    val type: SortieType,
    val title: String,
    val state: SortieState,
    val scrubReason: String? = null,
    val exercises: List<PromptHistoryExercise> = emptyList(),
    val notes: String? = null,
    val runDistance: Double? = null,
    val runMinutes: Double? = null,
)

/**
 * One prompt's whole input (§7.2). [sortieIndex] is the 0-based `Sortie.index` a FLIGHT_PLAN is
 * writing, and must be one of this week's sorties; null only for an OUTLINE, which writes the week.
 */
data class CoachPromptInput(
    val kind: GenerationKind,
    val profile: PromptProfile,
    val equipment: List<PromptEquipment>,
    val week: PromptWeek,
    val history: List<PromptHistorySortie>,
    val sortieIndex: Int? = null,
) {
    init {
        require(kind != GenerationKind.FLIGHT_PLAN || sortieIndex != null) {
            "a FLIGHT_PLAN prompt needs the sortie index it is writing"
        }
        require(sortieIndex == null || week.sorties.any { it.index == sortieIndex }) {
            "sortie $sortieIndex is not a sortie of this week"
        }
    }

    /** The sortie a FLIGHT_PLAN prompt is writing; null for an OUTLINE prompt. */
    val plannedSortie: PromptSortie?
        get() = week.sorties.firstOrNull { it.index == sortieIndex }
}
