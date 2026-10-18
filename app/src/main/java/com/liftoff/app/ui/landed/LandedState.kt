package com.liftoff.app.ui.landed

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.ui.sortie.formatWeekDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface LandedHistoryState {
    object Loading : LandedHistoryState
    object Empty : LandedHistoryState
    data class Weeks(val weeks: List<HistoryWeek>) : LandedHistoryState
}

data class HistoryWeek(
    val weekStart: LocalDate,
    val head: String,
    val rows: List<HistoryRow>,
)

data class HistoryRow(
    val sortieId: Long,
    val indexLabel: String,
    val type: SortieType,
    val typeLabel: String,
    val title: String,
    val dateLabel: String,
    val stateLabel: String,
    val scrubReason: String?,
)

/**
 * One read of the missions and plan titles per emission, then group in Kotlin: observeHistory() has
 * already ordered the rows, so no row costs a query of its own.
 */
suspend fun deriveHistory(
    sorties: List<Sortie>,
    db: LiftoffDatabase,
    zone: ZoneId,
): LandedHistoryState {
    // Skipping the empty list keeps getByIds/getTitlesForSortieIds from being handed `IN ()`.
    if (sorties.isEmpty()) return LandedHistoryState.Empty

    val missions = db.missionDao().getByIds(sorties.map { it.missionId }.distinct()).associateBy { it.id }
    val titles = db.flightPlanDao().getTitlesForSortieIds(sorties.map { it.id }).associate { it.sortieId to it.title }

    // Consecutive grouping only: re-sorting here would fight the DAO's weekStart DESC, index DESC.
    val groups = mutableListOf<Pair<LocalDate, MutableList<HistoryRow>>>()
    for (sortie in sorties) {
        val mission = missions[sortie.missionId] ?: continue
        val row = historyRowOf(sortie, mission, titles[sortie.id], zone)
        val last = groups.lastOrNull()
        if (last != null && last.first == mission.weekStart) {
            last.second += row
        } else {
            groups += mission.weekStart to mutableListOf(row)
        }
    }

    if (groups.isEmpty()) return LandedHistoryState.Empty
    return LandedHistoryState.Weeks(
        groups.map { (weekStart, rows) ->
            HistoryWeek(weekStart, "WEEK OF " + formatWeekDate(weekStart), rows.toList())
        }
    )
}

private fun historyRowOf(
    sortie: Sortie,
    mission: Mission,
    planTitle: String?,
    zone: ZoneId,
): HistoryRow {
    // Decision 11: a sortie with neither a plan title nor a focus is named by its type word.
    val typeWord = when (sortie.type) {
        SortieType.RUN -> "Run"
        SortieType.LIFT -> "Lift"
    }
    return HistoryRow(
        sortieId = sortie.id,
        indexLabel = "%02d".format(sortie.index + 1),
        type = sortie.type,
        typeLabel = typeWord.uppercase(),
        title = planTitle ?: sortie.focus?.takeIf { it.isNotBlank() } ?: typeWord,
        dateLabel = formatWeekDate(dateOf(sortie, mission, zone)),
        stateLabel = if (sortie.state == SortieState.SCRUBBED) "SCRUBBED" else "LANDED",
        scrubReason = sortie.scrubReason,
    )
}

/**
 * Decision 2, the rule PromptInputLoader.dateOf already uses: a scrub records no timestamp, so it is
 * dated by its week rather than left without a date.
 */
private fun dateOf(sortie: Sortie, mission: Mission, zone: ZoneId): LocalDate =
    sortie.landedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: sortie.launchedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: mission.weekStart
