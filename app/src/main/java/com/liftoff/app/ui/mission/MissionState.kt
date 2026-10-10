package com.liftoff.app.ui.mission

import com.liftoff.app.data.*
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.ui.sortie.deriveChips
import com.liftoff.app.ui.sortie.formatWeekDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.*

/** Sealed MissionTabState for the Mission tab. */
sealed interface MissionTabState {
    /** Waiting for onAppOpen to settle the current week and mission row. */
    object Loading : MissionTabState

    /** Week is known — mission with its sorties and outline notes. */
    data class Week(
        val weekStart: java.time.LocalDate,
        val status: MissionStatus,
        val chips: List<com.liftoff.app.ui.theme.PatternChip>,
        val outlineNotes: String?,
        val rows: List<SortieRow>,
    ) : MissionTabState
}

/** One row in the sortie list. */
data class SortieRow(
    val id: Long,
    val index: Int,
    val type: SortieType,
    val focus: String?,
    val state: SortieState,
) {
    /** Display label for the row title: "Lift · full body" or just "Run". */
    val title: String = when (type) {
        SortieType.LIFT -> "Lift${focus?.let { " · $it" } ?: ""}"
        SortieType.RUN -> "Run${focus?.let { " · $it" } ?: ""}"
    }

    /** State label for the row detail. */
    val stateLabel: String = when (state) {
        SortieState.PENDING -> "PENDING"
        SortieState.PLANNED -> "PLANNED"
        SortieState.IN_FLIGHT -> "IN FLIGHT"
        SortieState.LANDED -> "LANDED"
        SortieState.SCRUBBED -> "SCRUBBED"
    }
}

/** Sealed SortieDetailState for a selected sortie. */
sealed interface SortieDetailState {
    /** Waiting for plan data to load. */
    object Loading : SortieDetailState

    /** PLANNED, IN_FLIGHT, or PENDING with a flight plan. */
    data class WithPlan(
        val eyebrow: String,
        val title: String,
        val plan: FlightPlanDetail,
        val sortieType: SortieType,
    ) : SortieDetailState

    /** PENDING with no flight plan yet. */
    data class NoPlan(
        val eyebrow: String,
        val title: String,
    ) : SortieDetailState

    /** Sortie has been landed. */
    data class Landed(
        val eyebrow: String,
        val focus: String?,
        val sortieType: SortieType,
        val landedAt: Long,
        val planExerciseCount: Int,
        val planLandedCount: Int,
        val runDistance: Double?,
        val runMinutes: Double?,
        val notes: String?,
    ) : SortieDetailState

    /** Sortie was scrubbed. */
    data class Scrubbed(
        val eyebrow: String,
        val focus: String?,
        val sortieType: SortieType,
        val reason: String?,
        val planExerciseCount: Int,
        val planLandedCount: Int,
    ) : SortieDetailState
}

/** Derivation class: MissionTabState from LiftoffDatabase + MissionManager. */
class MissionStates(
    private val db: LiftoffDatabase,
    private val manager: MissionManager,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<MissionTabState> {
        return manager.currentWeekStart
            .flatMapLatest { ws ->
                if (ws == null) flowOf(MissionTabState.Loading)
                else db.missionDao().observeWeek(ws)
                    .map { missionWithSorties -> derive(missionWithSorties) }
            }
            .flowOn(Dispatchers.IO)
    }

    /** Observe the detail for a single sortie. */
    fun observeSortie(sortieId: Long): Flow<SortieDetailState> =
        db.sortieDao().observeById(sortieId)
            .combine(db.flightPlanDao().observeChanges()) { sortie, _ -> sortie }
            .map { sortie -> if (sortie == null) SortieDetailState.Loading else deriveSortieDetail(sortie) }
            .flowOn(Dispatchers.IO)

    private suspend fun deriveSortieDetail(sortie: Sortie): SortieDetailState {
        val plan = db.flightPlanDao().getPlan(sortie.id)
        val eyebrow = sortieEyebrow(sortie)

        return when (sortie.state) {
            SortieState.PLANNED, SortieState.IN_FLIGHT -> {
                if (plan != null) {
                    SortieDetailState.WithPlan(
                        eyebrow = eyebrow,
                        title = plan.plan.title,
                        plan = plan,
                        sortieType = sortie.type,
                    )
                } else {
                    SortieDetailState.NoPlan(
                        eyebrow = eyebrow,
                        title = sortie.focus ?: sortie.typeLabel(),
                    )
                }
            }

            SortieState.PENDING -> {
                if (plan != null) {
                    SortieDetailState.WithPlan(
                        eyebrow = eyebrow,
                        title = plan.plan.title,
                        plan = plan,
                        sortieType = sortie.type,
                    )
                } else {
                    SortieDetailState.NoPlan(
                        eyebrow = eyebrow,
                        title = sortie.focus ?: sortie.typeLabel(),
                    )
                }
            }

            SortieState.LANDED -> {
                val totalSets = plan?.exercises?.sumOf { it.sets.size } ?: 0
                val landedSets = plan?.exercises?.sumOf { ex ->
                    ex.sets.count { s -> s.status == SetStatus.DONE }
                } ?: 0

                SortieDetailState.Landed(
                    eyebrow = eyebrow,
                    focus = sortie.focus,
                    sortieType = sortie.type,
                    landedAt = sortie.landedAt ?: 0,
                    planExerciseCount = totalSets,
                    planLandedCount = landedSets,
                    runDistance = sortie.runDistance,
                    runMinutes = sortie.runMinutes,
                    notes = sortie.notes,
                )
            }

            SortieState.SCRUBBED -> {
                val totalSets = plan?.exercises?.sumOf { it.sets.size } ?: 0
                val landedSets = plan?.exercises?.sumOf { ex ->
                    ex.sets.count { s -> s.status == SetStatus.DONE }
                } ?: 0

                SortieDetailState.Scrubbed(
                    eyebrow = eyebrow,
                    focus = sortie.focus,
                    sortieType = sortie.type,
                    reason = sortie.scrubReason,
                    planExerciseCount = totalSets,
                    planLandedCount = landedSets,
                )
            }
        }
    }

    private fun Sortie.typeLabel(): String = when (type) {
        SortieType.RUN -> "Run"
        SortieType.LIFT -> "Lift"
    }

    private fun sortieEyebrow(sortie: Sortie): String {
        return "SORTIE ${sortie.index + 1} · ${sortie.type.name.replace("_", " ").uppercase()}${if (sortie.state != SortieState.PENDING) " · ${sortie.state.name.replace("_", " ").uppercase()}" else ""}"
    }

    private suspend fun derive(missionWithSorties: MissionWithSorties?): MissionTabState {
        val mission = missionWithSorties?.mission ?: return MissionTabState.Loading
        val sorties = missionWithSorties.sorties

        return when (mission.status) {
            MissionStatus.DRAFT -> MissionTabState.Week(
                weekStart = mission.weekStart,
                status = mission.status,
                chips = deriveChips(mission.pattern, emptyList()),
                outlineNotes = null,
                rows = emptyList(),
            )

            MissionStatus.CLOSED -> {
                val rows = sorties.map { sortie ->
                    SortieRow(sortie.id, sortie.index, sortie.type, sortie.focus, sortie.state)
                }
                MissionTabState.Week(
                    weekStart = mission.weekStart,
                    status = mission.status,
                    chips = deriveChips(mission.pattern, sorties),
                    outlineNotes = mission.outlineNotes,
                    rows = rows,
                )
            }

            MissionStatus.ACTIVE -> {
                val rows = sorties.map { sortie ->
                    SortieRow(sortie.id, sortie.index, sortie.type, sortie.focus, sortie.state)
                }
                MissionTabState.Week(
                    weekStart = mission.weekStart,
                    status = mission.status,
                    chips = deriveChips(mission.pattern, sorties),
                    outlineNotes = mission.outlineNotes,
                    rows = rows,
                )
            }
        }
    }
}
