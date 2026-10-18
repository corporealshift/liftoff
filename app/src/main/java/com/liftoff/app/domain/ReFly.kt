package com.liftoff.app.domain

import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType

/** A finished sortie the chooser may weigh, and whether it has a Flight Plan to copy. */
data class ReflyCandidate(
    val sortie: Sortie,
    val hasPlan: Boolean,
)

/**
 * The Flight Plan to re-fly onto [current]: the most recently landed sortie of the same type that has a
 * plan, preferring one whose focus matches [current]'s. Null when no such plan exists.
 *
 * A SCRUBBED sortie and a finished sortie with no plan are never sources. A blank focus on either side
 * is no match, so the choice falls back to the most recent plan of the same type.
 */
fun chooseReflySource(current: Sortie, candidates: List<ReflyCandidate>): ReflyCandidate? {
    val focus = normalizedFocus(current.focus)
    val copyable = candidates.filter {
        it.hasPlan && it.sortie.state == SortieState.LANDED && it.sortie.type == current.type
    }
    val (sameFocus, anyFocus) = copyable.partition {
        focus != null && normalizedFocus(it.sortie.focus) == focus
    }
    return (if (sameFocus.isNotEmpty()) sameFocus else anyFocus).maxWithOrNull(landedOldestFirst)
}

/** Focuses match on exact text after trimming and case-folding; blank or absent focus matches nothing. */
private fun normalizedFocus(focus: String?): String? =
    focus?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

// "Most recent" is when the sortie landed, not when its plan row was last written. A missing landed
// time is the smallest value, so such a candidate sorts as the oldest one.
private val landedOldestFirst: Comparator<ReflyCandidate> =
    compareBy { it.sortie.landedAt }
