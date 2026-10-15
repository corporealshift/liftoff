package com.liftoff.app.data

import com.liftoff.app.coach.CoachPromptInput
import com.liftoff.app.coach.PromptEquipment
import com.liftoff.app.coach.PromptHistoryExercise
import com.liftoff.app.coach.PromptHistorySet
import com.liftoff.app.coach.PromptHistorySortie
import com.liftoff.app.coach.PromptProfile
import com.liftoff.app.coach.PromptSortie
import com.liftoff.app.coach.PromptWeek
import com.liftoff.app.domain.sortieTypesOf
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * §7.2's gathering step: the Room + settings reads that turn one Mission (and one of its sorties)
 * into [CoachPromptInput].
 *
 * It lives in `data` rather than `coach` because ARCHITECTURE invariant 2 keeps `coach/` pure, and
 * everything here needs a query, a clock or a zone. The window and the newest-first total order
 * (plan decision 13) are settled in this file, so the builder stays a formatter that never sees a
 * date it has to interpret. The date, the zone and the window are injected for the same reason
 * [MissionManager] injects them.
 */
class PromptInputLoader(
    private val db: LiftoffDatabase,
    private val settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Overrides the configured [Settings.historyWindowDays] when set. */
    private val historyWindowDays: Int? = null,
) {

    suspend fun outlineInput(missionId: Long): CoachPromptInput =
        input(GenerationKind.OUTLINE, missionId, null)

    suspend fun flightPlanInput(missionId: Long, sortieIndex: Int): CoachPromptInput =
        input(GenerationKind.FLIGHT_PLAN, missionId, sortieIndex)

    private suspend fun input(
        kind: GenerationKind,
        missionId: Long,
        sortieIndex: Int?,
    ): CoachPromptInput {
        val settings = settingsStore.settings.first()
        val mission = db.missionDao().get(missionId)
            ?: error("Mission $missionId has no row to build a prompt from")
        val today = clock.instant().atZone(zone()).toLocalDate()
        return CoachPromptInput(
            kind = kind,
            profile = PromptProfile(
                objectives = settings.objectives,
                constraints = settings.constraints,
                sortieLengthMinutes = settings.sortieLengthMinutes,
                weightUnit = settings.weightUnit,
                distanceUnit = settings.distanceUnit,
            ),
            // observeActive is already `active = 1`, so an inactive item never reaches the prompt.
            equipment = db.equipmentDao().observeActive().first()
                .map { PromptEquipment(key = it.key, name = it.name, notes = it.notes) },
            week = weekOf(mission),
            history = historyOf(today, historyWindowDays ?: settings.historyWindowDays),
            sortieIndex = sortieIndex,
        )
    }

    private suspend fun weekOf(mission: Mission): PromptWeek {
        val rows = db.sortieDao().getForMission(mission.id)
        // Plan decision 0: an unconfirmed Mission has no sortie rows yet, but its pattern is already
        // the week the outline prompt has to describe.
        if (rows.isEmpty()) {
            return PromptWeek(
                pattern = mission.pattern,
                sorties = sortieTypesOf(mission.pattern).mapIndexed { index, type ->
                    PromptSortie(index = index, type = type, state = SortieState.PENDING)
                },
            )
        }
        return PromptWeek(
            pattern = mission.pattern,
            sorties = rows.map { PromptSortie(it.index, it.type, it.state, it.focus) },
        )
    }

    private suspend fun historyOf(today: LocalDate, windowDays: Int): List<PromptHistorySortie> {
        val finished = db.sortieDao().getFinishedNewestFirst()
        if (finished.isEmpty()) return emptyList()
        val missions = db.missionDao().getByIds(finished.map { it.missionId }.distinct()).associateBy { it.id }
        val earliest = today.minusDays(windowDays.toLong())

        val dated = finished.mapNotNull { sortie ->
            val mission = missions[sortie.missionId] ?: return@mapNotNull null
            val date = dateOf(sortie, mission)
            // The window is inclusive at the far edge: day 28 back is in, day 29 back is out.
            if (date.isBefore(earliest)) null else Triple(sortie, mission, date)
        }
        // Plan decision 13: a total order, so two prompts built from the same rows are byte-identical.
        return dated
            .sortedWith(
                compareByDescending<Triple<Sortie, Mission, LocalDate>> { it.third }
                    .thenByDescending { it.second.weekStart }
                    .thenByDescending { it.first.index }
            )
            .map { (sortie, _, date) -> historySortieOf(sortie, date) }
    }

    /**
     * Plan decision 1: `landedAt`, else `launchedAt`, else the Mission's `weekStart`. A scrub records
     * no timestamp at all, and a scrub is the one thing §7.1.1 tells the coach to plan around, so it
     * is dated by its week rather than left out of the history.
     */
    private fun dateOf(sortie: Sortie, mission: Mission): LocalDate {
        val inZone = zone()
        return sortie.landedAt?.let { Instant.ofEpochMilli(it).atZone(inZone).toLocalDate() }
            ?: sortie.launchedAt?.let { Instant.ofEpochMilli(it).atZone(inZone).toLocalDate() }
            ?: mission.weekStart
    }

    private suspend fun historySortieOf(
        sortie: Sortie,
        date: LocalDate,
    ): PromptHistorySortie {
        val detail = db.flightPlanDao().getPlan(sortie.id)
        return PromptHistorySortie(
            date = date,
            type = sortie.type,
            title = titleOf(sortie, detail),
            state = sortie.state,
            scrubReason = sortie.scrubReason,
            exercises = detail?.exercises.orEmpty().map { exercise ->
                PromptHistoryExercise(
                    name = exercise.displayName,
                    sets = exercise.sets.map { it.toPromptSet() },
                    // The owner's note only; PlannedExercise.notes is the coach's own words.
                    note = exercise.plannedExercise.userNotes?.takeIf { it.isNotBlank() },
                )
            },
            notes = sortie.notes?.takeIf { it.isNotBlank() },
            runDistance = sortie.runDistance,
            runMinutes = sortie.runMinutes,
        )
    }

    /** Plan decision 9: the plan's title, else the sortie's focus, so a scrubbed-before-planning sortie is still identifiable. */
    private fun titleOf(sortie: Sortie, detail: FlightPlanDetail?): String =
        detail?.plan?.title?.takeIf { it.isNotBlank() }
            ?: sortie.focus?.takeIf { it.isNotBlank() }
            ?: "untitled"

    /** A set reads on what was recorded, and on the plan's values when nothing was recorded. */
    private fun PlannedSet.toPromptSet(): PromptHistorySet =
        PromptHistorySet(
            reps = actualReps,
            seconds = actualSeconds,
            weight = actualWeight,
            status = status,
            plannedReps = reps,
            plannedSeconds = seconds,
            plannedWeight = weight,
        )
}
