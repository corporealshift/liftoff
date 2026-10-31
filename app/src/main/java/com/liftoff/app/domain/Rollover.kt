package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import java.time.LocalDate

const val WEEK_ENDED = "week ended"

data class RolloverResult(val mission: Mission, val scrubbed: List<Sortie>)

fun rollover(mission: Mission, sorties: List<Sortie>, today: LocalDate): RolloverResult? {
    // Nothing to do if already closed or week hasn't ended
    if (mission.status == MissionStatus.CLOSED) return null
    if (!weekHasEnded(mission.weekStart, today)) return null

    val scrubbed = sorties.filterNot {
        it.state == SortieState.LANDED || it.state == SortieState.SCRUBBED
    }.map { sortie ->
        sortie.copy(
            state = SortieState.SCRUBBED,
            scrubReason = WEEK_ENDED
        )
    }

    val closedMission = mission.copy(status = MissionStatus.CLOSED)
    return RolloverResult(closedMission, scrubbed)
}
