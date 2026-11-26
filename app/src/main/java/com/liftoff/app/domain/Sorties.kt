package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType

object SortieTransitions {
    private val legalTransitions = setOf(
        SortieState.PENDING to SortieState.PLANNED,
        SortieState.PENDING to SortieState.SCRUBBED,
        SortieState.PLANNED to SortieState.PENDING,
        SortieState.PLANNED to SortieState.IN_FLIGHT,
        SortieState.PLANNED to SortieState.SCRUBBED,
        SortieState.IN_FLIGHT to SortieState.LANDED,
        SortieState.IN_FLIGHT to SortieState.SCRUBBED,
        SortieState.IN_FLIGHT to SortieState.PLANNED,
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

fun unlaunch(sortie: Sortie): Sortie {
    // Not markPlanned: that copy leaves launchedAt set, and an un-launched sortie must read as never launched.
    SortieTransitions.requireTransition(sortie.state, SortieState.PLANNED)
    return sortie.copy(state = SortieState.PLANNED, launchedAt = null)
}

fun land(sortie: Sortie, at: Long): Sortie {
    SortieTransitions.requireTransition(sortie.state, SortieState.LANDED)
    return sortie.copy(state = SortieState.LANDED, landedAt = at)
}

fun scrub(sortie: Sortie, reason: String?): Sortie {
    // A reason of only spaces carries nothing; text is stored exactly as passed.
    val cleanReason = if (reason.isNullOrBlank()) null else reason
    SortieTransitions.requireTransition(sortie.state, SortieState.SCRUBBED)
    return sortie.copy(state = SortieState.SCRUBBED, scrubReason = cleanReason)
}

fun markPlanned(sortie: Sortie): Sortie {
    SortieTransitions.requireTransition(sortie.state, SortieState.PLANNED)
    return sortie.copy(state = SortieState.PLANNED)
}

// DESIGN.md §4.2's "Continue without outline" defaults. Named once so a swap and a confirm can't drift apart.
const val DEFAULT_RUN_FOCUS = "easy"
const val DEFAULT_LIFT_FOCUS = "full body"

/**
 * DESIGN.md §4.1, §5.1, §9 (amended 2026-11-23): swap the current sortie's type mid-week.
 * The letter at [Sortie.index] flips in [Mission.pattern] and no other letter or length changes,
 * so the sortie count and the rolling order of §4.3 are untouched.
 */
fun swapSortieType(mission: Mission, sortie: Sortie): Pair<Mission, Sortie> {
    // A PENDING sortie stays PENDING; only a PLANNED one moves, and that move is the swap's own edge.
    if (sortie.state != SortieState.PENDING) {
        SortieTransitions.requireTransition(sortie.state, SortieState.PENDING)
    }
    require(sortie.index in mission.pattern.indices) {
        "Sortie ${sortie.index} has no letter in pattern '${mission.pattern}'"
    }
    val type = if (sortie.type == SortieType.RUN) SortieType.LIFT else SortieType.RUN
    // The letter is written from the new type, so the pattern and the sortie can't end up disagreeing.
    val letter = if (type == SortieType.RUN) 'R' else 'L'
    val pattern = mission.pattern.mapIndexed { index, ch ->
        if (index == sortie.index) letter else ch
    }.joinToString("")
    return mission.copy(pattern = pattern) to sortie.copy(
        type = type,
        focus = if (type == SortieType.RUN) DEFAULT_RUN_FOCUS else DEFAULT_LIFT_FOCUS,
        focusRationale = null,
        state = SortieState.PENDING,
    )
}

/**
 * DESIGN.md §4.1, §4.2, §9 (amended 2026-11-26): add a sortie the week didn't plan.
 * The letter goes on the end of the pattern and the sortie at the matching index, so every earlier
 * sortie keeps its index, its Flight Plan and its history row. A CLOSED week reopens, because the
 * workout that wasn't expected usually arrives after the planned ones have been done.
 */
fun addSortie(mission: Mission, type: SortieType): Pair<Mission, Sortie> {
    // A draft's pattern is edited and confirmed as a whole (§4.2), so it takes no single sortie.
    require(mission.status != MissionStatus.DRAFT) { "Can only add a sortie to a confirmed week" }
    require(mission.pattern.length < MAX_PATTERN_LENGTH) {
        "Pattern '${mission.pattern}' already holds $MAX_PATTERN_LENGTH sorties"
    }
    if (mission.status == MissionStatus.CLOSED) {
        MissionLifecycle.requireTransition(MissionStatus.CLOSED, MissionStatus.ACTIVE)
    }
    // The letter is written from the type, so the pattern and the new sortie can't end up disagreeing.
    val letter = if (type == SortieType.RUN) 'R' else 'L'
    val pattern = mission.pattern + letter
    require(isValidPattern(pattern)) { "Invalid pattern: '$pattern'" }
    val sortie = Sortie(
        id = 0, // set by DAO insert
        missionId = mission.id,
        index = mission.pattern.length,
        type = type,
        focus = if (type == SortieType.RUN) DEFAULT_RUN_FOCUS else DEFAULT_LIFT_FOCUS,
        focusRationale = null,
        state = SortieState.PENDING,
        launchedAt = null,
        landedAt = null,
        scrubReason = null,
        notes = null,
        runDistance = null,
        runMinutes = null,
    )
    return mission.copy(pattern = pattern, status = MissionStatus.ACTIVE) to sortie
}
