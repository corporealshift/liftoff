package com.liftoff.app.data

import com.liftoff.app.domain.ReflyCandidate

/**
 * The finished sorties [current] could re-fly from, newest first, each carrying whether it has a Flight
 * Plan. LANDED sorties of the same type only; the ordering and the focus preference are the chooser's.
 */
internal suspend fun reflyCandidates(
    sortieDao: SortieDao,
    flightPlanDao: FlightPlanDao,
    current: Sortie
): List<ReflyCandidate> {
    val landed = sortieDao.getFinishedNewestFirst()
        .filter { it.state == SortieState.LANDED && it.type == current.type }
    if (landed.isEmpty()) return emptyList()

    // getTitlesForSortieIds binds one SQL variable per id, so years of history are asked for in chunks.
    val plannedIds = HashSet<Long>()
    for (chunk in landed.chunked(PLAN_TITLE_CHUNK)) {
        flightPlanDao.getTitlesForSortieIds(chunk.map { it.id }).forEach { plannedIds += it.sortieId }
    }
    return landed.map { ReflyCandidate(it, it.id in plannedIds) }
}

private const val PLAN_TITLE_CHUNK = 100

/**
 * [source]'s plan re-laid as a draft for another sortie: plan fields, exercises, equipment, rests and coach
 * notes unchanged; sets carry their planned values with no actuals, and the ones added mid-flight are left
 * out. Skips and user notes are not in a draft at all — writePlan writes them cleared.
 */
internal fun reflyDraft(source: FlightPlanDetail): FlightPlanDraft = FlightPlanDraft(
    source = FlightPlanSource.REFLY,
    title = source.plan.title,
    estimatedMinutes = source.plan.estimatedMinutes,
    warmup = source.plan.warmup,
    notes = source.plan.notes,
    runKind = source.plan.runKind,
    targetDistance = source.plan.targetDistance,
    targetPace = source.plan.targetPace,
    // A re-flown plan has no coach output of its own; the source row keeps its JSON.
    rawJson = "{}",
    exercises = source.exercises.map { exercise ->
        ExerciseDraft(
            name = exercise.displayName,
            equipmentIds = exercise.plannedExercise.equipmentIds,
            restSeconds = exercise.plannedExercise.restSeconds,
            notes = exercise.plannedExercise.notes,
            sets = exercise.sets.filterNot { it.added }.map { SetDraft(it.reps, it.seconds, it.weight) }
        )
    },
    segments = source.segments.map { RunSegmentDraft(it.description, it.distance, it.minutes) }
)
