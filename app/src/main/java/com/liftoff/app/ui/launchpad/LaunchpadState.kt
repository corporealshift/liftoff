package com.liftoff.app.ui.launchpad

import com.liftoff.app.data.*
import com.liftoff.app.domain.CoachStatus
import com.liftoff.app.domain.chooseReflySource
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.domain.deriveCoachStatus
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

    /** Mission is DRAFT — editable pattern chips + Confirm, plus what its outline generation is doing. */
    data class Draft(
        val weekStart: java.time.LocalDate,
        val pattern: String,
        val missionId: Long,
        val coachStatus: CoachStatus,
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
        /** Whether a landed sortie of this sortie's type has a Flight Plan to re-fly from. */
        val reFlyAvailable: Boolean,
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
                    .flatMapLatest { missionWithSorties -> derive(missionWithSorties) }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * One Flow per mission emission. The DRAFT arm is the only one that joins flows of its own: its coach status
     * has two live sources, and the newest outline row is keyed by a mission id this is the first place that knows.
     */
    private fun derive(missionWithSorties: MissionWithSorties?): Flow<LaunchpadState> {
        val mission = missionWithSorties?.mission ?: return flowOf(LaunchpadState.Loading)
        val sorties = missionWithSorties.sorties
        val planChanges = db.flightPlanDao().observeChanges()

        return when (mission.status) {
            MissionStatus.DRAFT -> combine(
                planChanges,
                db.generationDao().observeNewestNonCancelledOutlineForMission(mission.id),
                manager.coachConfigured,
            ) { _, outline, configured ->
                LaunchpadState.Draft(
                    weekStart = mission.weekStart,
                    pattern = mission.pattern,
                    missionId = mission.id,
                    coachStatus = deriveCoachStatus(
                        newestOutlineStatus = outline?.status,
                        newestOutlineError = outline?.error,
                        newestOutlineIsCoachOffline = outline?.isCoachOffline ?: false,
                        coachConfigured = configured,
                    ),
                )
            }.distinctUntilChanged()

            MissionStatus.CLOSED -> planChanges.map { deriveClosed(mission, sorties) }

            MissionStatus.ACTIVE -> planChanges.map {
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
                        // refly refuses a PLANNED sortie, so this state carries no source for it.
                        reFlyAvailable = false,
                    )
                }
            }

            SortieState.PENDING -> {
                val title = current.focus ?: when (current.type) {
                    SortieType.RUN -> "Run"
                    SortieType.LIFT -> "Lift"
                }
                // The only branch that reads history: derive runs on every emission. It asks the question
                // MissionManager.refly asks, so the offer and the act cannot disagree.
                val candidates = reflyCandidates(db.sortieDao(), db.flightPlanDao(), current)
                LaunchpadState.Pending(
                    eyebrow = eyebrow,
                    title = title,
                    chips = chips,
                    sortieId = current.id,
                    sortieIndex = current.index,
                    reFlyAvailable = chooseReflySource(current, candidates) != null,
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
                    // currentSortie never returns a LANDED or SCRUBBED sortie, and refly refuses them anyway.
                    reFlyAvailable = false,
                )
            }
        }
    }
}
