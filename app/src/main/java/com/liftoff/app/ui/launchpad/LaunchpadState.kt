package com.liftoff.app.ui.launchpad

import com.liftoff.app.data.*
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.ui.sortie.deriveChips
import com.liftoff.app.ui.sortie.formatEyebrow
import com.liftoff.app.ui.sortie.formatWeekDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.*

/** Sealed LaunchpadState for every mission lifecycle stage. */
sealed interface LaunchpadState {
    /** Waiting for onAppOpen to settle the current week and mission row. */
    object Loading : LaunchpadState

    /** Mission is DRAFT — editable pattern chips + Confirm. */
    data class Draft(
        val weekStart: java.time.LocalDate,
        val pattern: String,
        val missionId: Long,
    ) : LaunchpadState

    /** Current sortie is PLANNED — launch or scrub available. */
    data class Planned(
        val eyebrow: String,
        val title: String,
        val chips: List<com.liftoff.app.ui.theme.PatternChip>,
        val plan: FlightPlanDetail,
        val sortieId: Long,
        val sortieIndex: Int,
        val sortieType: SortieType,
        val sortieFocus: String?,
    ) : LaunchpadState

    /** Current sortie is PENDING — no flight plan yet. */
    data class Pending(
        val eyebrow: String,
        val title: String,
        val chips: List<com.liftoff.app.ui.theme.PatternChip>,
        val sortieId: Long,
        val sortieIndex: Int,
    ) : LaunchpadState

    /** Current sortie is IN_FLIGHT — resume available. */
    data class InFlight(
        val eyebrow: String,
        val title: String,
        val chips: List<com.liftoff.app.ui.theme.PatternChip>,
        val plan: FlightPlanDetail?,
        val sortieId: Long,
        val sortieIndex: Int,
        val sortieType: SortieType,
        val sortieFocus: String?,
    ) : LaunchpadState

    /** Mission is CLOSED or ACTIVE with no current sortie. */
    data class Closed(
        val weekStart: java.time.LocalDate,
        val chips: List<com.liftoff.app.ui.theme.PatternChip>,
        val landedCount: Int,
        val total: Int,
    ) : LaunchpadState
}

/** Derivation class: LaunchpadState from LiftoffDatabase + MissionManager. */
class LaunchpadStates(
    private val db: LiftoffDatabase,
    private val manager: com.liftoff.app.data.MissionManager,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<LaunchpadState> {
        return manager.currentWeekStart
            .flatMapLatest { ws ->
                if (ws == null) flowOf(LaunchpadState.Loading)
                else db.missionDao().observeWeek(ws)
                    .combine(db.flightPlanDao().observeChanges()) { missionWithSorties: MissionWithSorties?, _ ->
                        derive(missionWithSorties)
                    }
            }
            .flowOn(Dispatchers.IO)
    }

    private suspend fun derive(missionWithSorties: MissionWithSorties?): LaunchpadState {
        val mission = missionWithSorties?.mission ?: return LaunchpadState.Loading
        val sorties = missionWithSorties.sorties

        return when (mission.status) {
            MissionStatus.DRAFT -> LaunchpadState.Draft(
                weekStart = mission.weekStart,
                pattern = mission.pattern,
                missionId = mission.id,
            )

            MissionStatus.CLOSED -> deriveClosed(mission, sorties)

            MissionStatus.ACTIVE -> {
                val current = currentSortie(sorties)
                if (current == null) {
                    deriveClosed(mission, sorties)
                } else {
                    deriveActive(sorties, mission.pattern, mission.weekStart, current)
                }
            }
        }
    }

    private fun deriveClosed(
        mission: Mission,
        sorties: List<Sortie>,
    ): LaunchpadState.Closed {
        val chips = deriveChips(mission.pattern, sorties)
        val landedCount = sorties.count { it.state == SortieState.LANDED }
        return LaunchpadState.Closed(
            weekStart = mission.weekStart,
            chips = chips,
            landedCount = landedCount,
            total = mission.pattern.length,
        )
    }

    private suspend fun deriveActive(
        sorties: List<Sortie>,
        pattern: String,
        weekStart: java.time.LocalDate,
        current: Sortie,
    ): LaunchpadState {
        val chips = deriveChips(pattern, sorties)
        val eyebrow = formatEyebrow(weekStart, current.index, pattern.length, current.type)

        return when (current.state) {
            SortieState.PLANNED -> {
                db.flightPlanDao().getPlan(current.id)?.let { plan ->
                    LaunchpadState.Planned(
                        eyebrow = eyebrow,
                        title = plan.plan.title,
                        chips = chips,
                        plan = plan,
                        sortieId = current.id,
                        sortieIndex = current.index,
                        sortieType = current.type,
                        sortieFocus = current.focus,
                    )
                } ?: run {
                    val title = current.focus ?: when (current.type) {
                        SortieType.RUN -> "Run"
                        SortieType.LIFT -> "Lift"
                    }
                    LaunchpadState.Pending(
                        eyebrow = eyebrow,
                        title = title,
                        chips = chips,
                        sortieId = current.id,
                        sortieIndex = current.index,
                    )
                }
            }

            SortieState.PENDING -> {
                val title = current.focus ?: when (current.type) {
                    SortieType.RUN -> "Run"
                    SortieType.LIFT -> "Lift"
                }
                LaunchpadState.Pending(
                    eyebrow = eyebrow,
                    title = title,
                    chips = chips,
                    sortieId = current.id,
                    sortieIndex = current.index,
                )
            }

            SortieState.IN_FLIGHT -> {
                val plan = db.flightPlanDao().getPlan(current.id)
                val title = plan?.plan?.title ?: current.focus ?: when (current.type) {
                    SortieType.RUN -> "Run"
                    SortieType.LIFT -> "Lift"
                }
                LaunchpadState.InFlight(
                    eyebrow = eyebrow,
                    title = title,
                    chips = chips,
                    plan = plan,
                    sortieId = current.id,
                    sortieIndex = current.index,
                    sortieType = current.type,
                    sortieFocus = current.focus,
                )
            }

            else -> {
                val title = current.focus ?: when (current.type) {
                    SortieType.RUN -> "Run"
                    SortieType.LIFT -> "Lift"
                }
                LaunchpadState.Pending(
                    eyebrow = eyebrow,
                    title = title,
                    chips = chips,
                    sortieId = current.id,
                    sortieIndex = current.index,
                )
            }
        }
    }
}
