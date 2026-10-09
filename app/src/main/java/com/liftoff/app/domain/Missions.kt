package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType

object MissionLifecycle {
    private val legalTransitions = setOf(
        MissionStatus.DRAFT to MissionStatus.ACTIVE,
        MissionStatus.ACTIVE to MissionStatus.CLOSED,
        MissionStatus.DRAFT to MissionStatus.CLOSED,
    )

    fun canTransition(from: MissionStatus, to: MissionStatus): Boolean =
        from to to in legalTransitions

    fun requireTransition(from: MissionStatus, to: MissionStatus) {
        if (!canTransition(from, to)) {
            throw IllegalTransitionException(
                "Illegal transition: $from → $to"
            )
        }
    }
}

fun newDraft(weekStart: java.time.LocalDate, defaultPattern: String): Mission =
    Mission(
        weekStart = weekStart,
        pattern = defaultPattern,
        status = MissionStatus.DRAFT,
        outlineNotes = null,
    )

fun overridePattern(mission: Mission, pattern: String): Mission {
    if (mission.status != MissionStatus.DRAFT) {
        throw IllegalStateException("Pattern can only be overridden on a DRAFT mission")
    }
    if (!isValidPattern(pattern)) {
        throw IllegalArgumentException("Invalid pattern: '$pattern'")
    }
    return mission.copy(pattern = pattern)
}

fun confirmWithoutOutline(mission: Mission): Pair<Mission, List<Sortie>> {
    if (mission.status != MissionStatus.DRAFT) {
        throw IllegalStateException("Can only confirm a DRAFT mission")
    }
    val activeMission = mission.copy(status = MissionStatus.ACTIVE)
    val sorties = mission.pattern.mapIndexed { index, ch ->
        Sortie(
            missionId = 0, // set by DAO insert
            index = index,
            type = if (ch == 'R') SortieType.RUN else SortieType.LIFT,
            focus = if (ch == 'R') "easy" else "full body",
            focusRationale = null,
            state = SortieState.PENDING,
            launchedAt = null,
            landedAt = null,
            scrubReason = null,
            notes = null,
            runDistance = null,
            runMinutes = null,
        )
    }
    return activeMission to sorties
}

fun allSortiesDone(sorties: List<Sortie>): Boolean =
    sorties.all { it.state == SortieState.LANDED || it.state == SortieState.SCRUBBED }
