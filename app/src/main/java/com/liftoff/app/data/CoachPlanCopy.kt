package com.liftoff.app.data

import com.liftoff.app.coach.CoachLiftPlan
import com.liftoff.app.coach.CoachRunPlan

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
