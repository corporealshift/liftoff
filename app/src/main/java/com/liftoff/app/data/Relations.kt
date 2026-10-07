package com.liftoff.app.data

import androidx.room.Embedded
import androidx.room.Relation

data class MissionWithSorties(
    @Embedded val mission: Mission,
    @Relation(
        parentColumn = "id",
        entityColumn = "missionId"
    )
    val sorties: List<Sortie>
)

// Query POJO for PlannedExercise with its Exercise display name
data class PlannedExerciseRow(
    @Embedded val plannedExercise: PlannedExercise,
    val displayName: String
)

// Read model — assembled in the DAO from ordered queries
data class FlightPlanDetail(
    val plan: FlightPlan,
    val exercises: List<PlannedExerciseDetail>,
    val segments: List<RunSegment>
)

data class PlannedExerciseDetail(
    val plannedExercise: PlannedExercise,
    val displayName: String,
    val sets: List<PlannedSet>
)

// Write input types
data class FlightPlanDraft(
    val source: FlightPlanSource,
    val title: String,
    val estimatedMinutes: Int?,
    val warmup: String?,
    val notes: String?,
    val runKind: String?,
    val targetDistance: Double?,
    val targetPace: String?,
    val rawJson: String,
    val exercises: List<ExerciseDraft>,
    val segments: List<RunSegmentDraft>
)

data class ExerciseDraft(
    val name: String,
    val equipmentIds: List<String>,
    val restSeconds: Int?,
    val notes: String?,
    val sets: List<SetDraft>
)

data class SetDraft(
    val reps: Int?,
    val seconds: Int?,
    val weight: Double?
)

data class RunSegmentDraft(
    val description: String,
    val distance: Double?,
    val minutes: Double?
)
