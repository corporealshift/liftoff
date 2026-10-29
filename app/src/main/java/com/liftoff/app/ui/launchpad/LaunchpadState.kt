package com.liftoff.app.ui.launchpad

import com.liftoff.app.data.*
import com.liftoff.app.domain.CoachStatus
import com.liftoff.app.domain.SortieGenerationStatus
import com.liftoff.app.domain.chooseReflySource
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.domain.deriveCoachStatus
import com.liftoff.app.domain.derivePendingSortieGenerationStatus
import com.liftoff.app.domain.derivePlannedSortieGenerationStatus
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
        /** Re-planning while a new generation is open for this sortie, idle while it is not. */
        val generationStatus: SortieGenerationStatus,
        /** The rho session id this sortie's newest non-cancelled generation row carries, if it carries one. */
        val rhoSessionId: String?,
        /** Whether asking Mission Control again for this sortie would be accepted; see MissionManager.regenerateFlightPlan. */
        val regenerateAvailable: Boolean,
    ) : LaunchpadState

    /** Current sortie is PENDING — no flight plan yet. */
    data class Pending(
        val eyebrow: String,
        val title: String,
        val chips: List<com.liftoff.app.ui.theme.PatternChip>,
        val sortieId: Long,
        val sortieIndex: Int,
        /** What this sortie's newest non-cancelled generation row says, derived live from Room and the settings. */
        val generationStatus: SortieGenerationStatus,
        /** The rho session id this sortie's newest non-cancelled generation row carries, if it carries one. */
        val rhoSessionId: String?,
        /** §5.4: a source plan exists *and* the coach cannot do the work — failed, offline, or not set up. */
        val reFlyAvailable: Boolean,
        /** Whether asking Mission Control again for this sortie would be accepted; see MissionManager.regenerateFlightPlan. */
        val regenerateAvailable: Boolean,
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
     * The ACTIVE arm works out the current sortie for the same reason before it picks flows: only a PENDING or
     * PLANNED card carries a generation status, so only those two arms join this sortie's generation row and the
     * settings, and only those two need distinctUntilChanged — a generation row wakes every arm in the table.
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

            MissionStatus.ACTIVE -> {
                val current = currentSortie(sorties)
                if (current != null &&
                    (current.state == SortieState.PENDING || current.state == SortieState.PLANNED)
                ) {
                    combine(
                        planChanges,
                        db.generationDao().observeNewestNonCancelledForSortie(current.id),
                        manager.coachConfigured,
                        manager.generateRunPlans,
                    ) { _, row, configured, runPlans ->
                        deriveSortieCard(sorties, mission, current, row, configured, runPlans)
                    }.distinctUntilChanged()
                } else {
                    planChanges.map {
                        if (current == null) deriveClosed(mission, sorties)
                        else deriveActive(sorties, mission.pattern, mission.weekStart, current)
                    }
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

    /**
     * A PENDING or PLANNED current sortie's card, from this sortie's newest non-cancelled generation row and the
     * settings. The §5.4 Re-fly rule is the derivation's answer, not a question this file asks of its own.
     */
    private suspend fun deriveSortieCard(
        sorties: List<Sortie>,
        mission: Mission,
        current: Sortie,
        row: Generation?,
        coachConfigured: Boolean,
        generateRunPlans: Boolean,
    ): LaunchpadState {
        val chips = deriveChips(mission.pattern, sorties)
        val eyebrow = formatEyebrow(mission.weekStart, current.index, mission.pattern.length, current.type)
        val title = current.focus ?: when (current.type) {
            SortieType.RUN -> "Run"
            SortieType.LIFT -> "Lift"
        }
        // Mirrors the two settings checks in MissionManager.regenerateFlightPlan, which stays the authority.
        val regenerateAvailable = coachConfigured && (current.type != SortieType.RUN || generateRunPlans)

        if (current.state == SortieState.PLANNED) {
            val planned = derivePlannedSortieGenerationStatus(
                newestGenerationStatus = row?.status,
                newestGenerationSessionId = row?.nabuSessionId,
            )
            val plan = db.flightPlanDao().getPlan(current.id)
            if (plan != null) {
                return LaunchpadState.Planned(
                    eyebrow = eyebrow,
                    title = plan.plan.title,
                    chips = chips,
                    plan = plan,
                    sortieId = current.id,
                    sortieIndex = current.index,
                    sortieType = current.type,
                    sortieFocus = current.focus,
                    generationStatus = planned.status,
                    rhoSessionId = planned.rhoSessionId,
                    regenerateAvailable = regenerateAvailable,
                )
            }
            // The plan row is gone, so the card is the PENDING one and answers what a PENDING card would.
            // It still refuses Re-fly: refly refuses a PLANNED sortie, so offering it would put a control on the
            // card that fails when tapped.
            val pending = derivePendingSortieGenerationStatus(
                newestGenerationStatus = row?.status,
                newestGenerationError = row?.error,
                newestGenerationIsCoachOffline = row?.isCoachOffline ?: false,
                newestGenerationSessionId = row?.nabuSessionId,
                coachConfigured = coachConfigured,
                hasReFlySourcePlan = false,
            )
            return LaunchpadState.Pending(
                eyebrow = eyebrow,
                title = title,
                chips = chips,
                sortieId = current.id,
                sortieIndex = current.index,
                generationStatus = pending.status,
                rhoSessionId = pending.rhoSessionId,
                reFlyAvailable = pending.offersReFly,
                regenerateAvailable = regenerateAvailable,
            )
        }

        // The only branch that reads history: derive runs on every emission. It asks the question
        // MissionManager.refly asks, so the offer and the act cannot disagree.
        val candidates = reflyCandidates(db.sortieDao(), db.flightPlanDao(), current)
        val answer = derivePendingSortieGenerationStatus(
            newestGenerationStatus = row?.status,
            newestGenerationError = row?.error,
            newestGenerationIsCoachOffline = row?.isCoachOffline ?: false,
            newestGenerationSessionId = row?.nabuSessionId,
            coachConfigured = coachConfigured,
            hasReFlySourcePlan = chooseReflySource(current, candidates) != null,
        )
        return LaunchpadState.Pending(
            eyebrow = eyebrow,
            title = title,
            chips = chips,
            sortieId = current.id,
            sortieIndex = current.index,
            generationStatus = answer.status,
            rhoSessionId = answer.rhoSessionId,
            reFlyAvailable = answer.offersReFly,
            regenerateAvailable = regenerateAvailable,
        )
    }

    /**
     * The ACTIVE arms that carry no generation status: IN_FLIGHT, and the fallback currentSortie never returns.
     * They stay on the plain mapping of the plan changes, so no generation row or settings read is joined for them.
     */
    private suspend fun deriveActive(
        sorties: List<Sortie>,
        pattern: String,
        weekStart: java.time.LocalDate,
        current: Sortie,
    ): LaunchpadState {
        val chips = deriveChips(pattern, sorties)
        val eyebrow = formatEyebrow(weekStart, current.index, pattern.length, current.type)

        return when (current.state) {
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
                    // Nothing is open for one either, so the card reports no regeneration rather than guessing at
                    // a row and a settings read this arm does not join.
                    generationStatus = SortieGenerationStatus.Idle,
                    rhoSessionId = null,
                    reFlyAvailable = false,
                    regenerateAvailable = false,
                )
            }
        }
    }
}
