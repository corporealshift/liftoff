package com.liftoff.app.domain

import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState

object SortieTransitions {
    private val legalTransitions = setOf(
        SortieState.PENDING to SortieState.PLANNED,
        SortieState.PENDING to SortieState.SCRUBBED,
        SortieState.PLANNED to SortieState.IN_FLIGHT,
        SortieState.PLANNED to SortieState.SCRUBBED,
        SortieState.IN_FLIGHT to SortieState.LANDED,
        SortieState.IN_FLIGHT to SortieState.SCRUBBED,
    )

    fun canTransition(from: SortieState, to: SortieState): Boolean =
        from to to in legalTransitions

    fun requireTransition(from: SortieState, to: SortieState) {
        if (!canTransition(from, to)) {
            throw IllegalTransitionException(
                "Illegal transition: $from → $to"
            )
        }
    }
}

fun currentSortie(sorties: List<Sortie>): Sortie? =
    sorties.sortedBy { it.index }.firstOrNull {
        it.state != SortieState.LANDED && it.state != SortieState.SCRUBBED
    }

fun requireNoneInFlight(inFlight: List<Sortie>) {
    if (inFlight.isNotEmpty()) {
        throw IllegalStateException("A sortie is already in flight")
    }
}

fun launch(sortie: Sortie, at: Long): Sortie {
    SortieTransitions.requireTransition(sortie.state, SortieState.IN_FLIGHT)
    return sortie.copy(state = SortieState.IN_FLIGHT, launchedAt = at)
}

fun land(sortie: Sortie, at: Long): Sortie {
    SortieTransitions.requireTransition(sortie.state, SortieState.LANDED)
    return sortie.copy(state = SortieState.LANDED, landedAt = at)
}

fun scrub(sortie: Sortie, reason: String?): Sortie {
    val cleanReason = if (reason.isNullOrEmpty()) null else reason
    SortieTransitions.requireTransition(sortie.state, SortieState.SCRUBBED)
    return sortie.copy(state = SortieState.SCRUBBED, scrubReason = cleanReason)
}

fun markPlanned(sortie: Sortie): Sortie {
    SortieTransitions.requireTransition(sortie.state, SortieState.PLANNED)
    return sortie.copy(state = SortieState.PLANNED)
}
