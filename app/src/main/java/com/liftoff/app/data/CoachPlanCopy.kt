package com.liftoff.app.data

import com.liftoff.app.coach.CoachLiftPlan
import com.liftoff.app.coach.CoachOutline
import com.liftoff.app.coach.CoachRunPlan
import com.liftoff.app.domain.sortieTypesOf

/**
 * A validated lift plan as a draft for its sortie. [json] is the object the validator accepted — DESIGN.md §8's
 * `rawJson` — not the reply it arrived in.
 *
 * The coach's own numbers are stored unchanged, because the validator has already held them against the schema,
 * the equipment list and the sortie length. Equipment keys go straight to [ExerciseDraft.equipmentIds], which is
 * where `PlannedExercise` keeps them. Exercise and set order is the reply's; `writePlan` numbers them.
 */
internal fun coachLiftDraft(plan: CoachLiftPlan, json: String): FlightPlanDraft = FlightPlanDraft(
    source = FlightPlanSource.GENERATED,
    title = plan.title,
    estimatedMinutes = plan.estimatedMinutes,
    warmup = plan.warmup,
    notes = plan.notes,
    runKind = null,
    targetDistance = null,
    targetPace = null,
    rawJson = json,
    exercises = plan.exercises.map { exercise ->
        ExerciseDraft(
            name = exercise.name,
            equipmentIds = exercise.equipment,
            restSeconds = exercise.restSeconds,
            notes = exercise.notes,
            sets = exercise.sets.map { SetDraft(it.reps, it.seconds, it.weight) }
        )
    },
    segments = emptyList()
)

/**
 * A validated run plan as a draft. The run schema has no estimated minutes or warmup — the segments carry the
 * time — and the coach's own title is kept, so a generated run reads like the coach wrote it rather than like
 * the simple plan's "Run".
 */
internal fun coachRunDraft(plan: CoachRunPlan, json: String): FlightPlanDraft = FlightPlanDraft(
    source = FlightPlanSource.GENERATED,
    title = plan.title,
    estimatedMinutes = null,
    warmup = null,
    notes = plan.notes,
    runKind = plan.kind,
    targetDistance = plan.targetDistance,
    targetPace = plan.targetPace,
    rawJson = json,
    exercises = emptyList(),
    segments = plan.segments.map { RunSegmentDraft(it.description, it.distance, it.minutes) }
)

/**
 * A validated outline as the week it describes: the Mission activated with its notes, and one sortie per
 * pattern letter in the pattern's own order (DESIGN.md §4.3), each taking the focus and rationale of the
 * outline entry at the same position. Everything else about a sortie is what it has before anything happens
 * to it, so it is left null here exactly as [com.liftoff.app.domain.confirmWithoutOutline] leaves it.
 */
internal fun activateWithOutline(mission: Mission, outline: CoachOutline): Pair<Mission, List<Sortie>> {
    val sorties = mission.pattern.mapIndexed { index, letter ->
        val entry = outline.sorties[index]
        Sortie(
            missionId = mission.id,
            index = index,
            type = if (letter == 'R') SortieType.RUN else SortieType.LIFT,
            focus = entry.focus,
            focusRationale = entry.rationale,
            state = SortieState.PENDING,
            launchedAt = null,
            landedAt = null,
            scrubReason = null,
            notes = null,
            runDistance = null,
            runMinutes = null,
        )
    }
    return mission.copy(status = MissionStatus.ACTIVE, outlineNotes = outline.notes) to sorties
}

/**
 * Whether the outline still describes this Mission's pattern. A draft's pattern is editable until it is
 * confirmed (§4.1), so the pattern the outline was validated against is not necessarily the one it is
 * offered to.
 */
internal fun outlineMatchesPattern(outline: CoachOutline, pattern: String): Boolean {
    val types = sortieTypesOf(pattern)
    if (outline.sorties.size != types.size) return false
    return outline.sorties.indices.all { index ->
        outline.sorties[index].index == index && outline.sorties[index].sortieType == types[index]
    }
}
