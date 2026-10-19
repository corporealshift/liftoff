package com.liftoff.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape of a Liftoff backup file: a header plus one array per table, ids included.
 *
 * These DTOs exist so the file's snake_case names never reach the Room entities. Every key is
 * written out with `@SerialName`, because three tables store camelCase columns (`weekStart`,
 * `equipmentIds`, `launchedAt`, …) and `order` is a backticked Kotlin name — kotlinx's default
 * key would be the Kotlin property, not the one the brief asks for.
 */
@Serializable
data class BackupDocument(
    @SerialName("header") val header: BackupHeader,
    @SerialName("missions") val missions: List<BackupMission>,
    @SerialName("sorties") val sorties: List<BackupSortie>,
    @SerialName("flight_plans") val flightPlans: List<BackupFlightPlan>,
    @SerialName("planned_exercises") val plannedExercises: List<BackupPlannedExercise>,
    @SerialName("planned_sets") val plannedSets: List<BackupPlannedSet>,
    @SerialName("run_segments") val runSegments: List<BackupRunSegment>,
    @SerialName("exercises") val exercises: List<BackupExercise>,
    @SerialName("generations") val generations: List<BackupGeneration>,
    @SerialName("equipment") val equipment: List<BackupEquipment>,
)

/** Bump this when the file's shape changes; import refuses a format version it does not know. */
const val BACKUP_FORMAT_VERSION: Int = 1

/**
 * `schema_version` is the Room schema version the rows came from, so a file written by a newer
 * app can be refused instead of half-imported. `exported_at` is epoch milliseconds, like every
 * other time in the file.
 */
@Serializable
data class BackupHeader(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("exported_at") val exportedAt: Long,
)

@Serializable
data class BackupMission(
    @SerialName("id") val id: Long,
    /** An ISO date, not Room's epoch-day number, so a week can be found by eye. */
    @SerialName("week_start") val weekStart: String,
    @SerialName("pattern") val pattern: String,
    @SerialName("status") val status: MissionStatus,
    @SerialName("outline_notes") val outlineNotes: String?,
)

@Serializable
data class BackupSortie(
    @SerialName("id") val id: Long,
    @SerialName("mission_id") val missionId: Long,
    @SerialName("index") val index: Int,
    @SerialName("type") val type: SortieType,
    @SerialName("focus") val focus: String?,
    @SerialName("focus_rationale") val focusRationale: String?,
    @SerialName("state") val state: SortieState,
    @SerialName("launched_at") val launchedAt: Long?,
    @SerialName("landed_at") val landedAt: Long?,
    @SerialName("scrub_reason") val scrubReason: String?,
    @SerialName("notes") val notes: String?,
    @SerialName("run_distance") val runDistance: Double?,
    @SerialName("run_minutes") val runMinutes: Double?,
)

@Serializable
data class BackupFlightPlan(
    @SerialName("id") val id: Long,
    @SerialName("sortie_id") val sortieId: Long,
    @SerialName("source") val source: FlightPlanSource,
    @SerialName("title") val title: String,
    @SerialName("estimated_minutes") val estimatedMinutes: Int?,
    @SerialName("warmup") val warmup: String?,
    @SerialName("notes") val notes: String?,
    @SerialName("run_kind") val runKind: String?,
    @SerialName("target_distance") val targetDistance: Double?,
    @SerialName("target_pace") val targetPace: String?,
    @SerialName("raw_json") val rawJson: String,
)

@Serializable
data class BackupPlannedExercise(
    @SerialName("id") val id: Long,
    @SerialName("flight_plan_id") val flightPlanId: Long,
    @SerialName("order") val order: Int,
    @SerialName("exercise_id") val exerciseId: Long,
    /** A JSON array of equipment keys, not the JSON string Room stores it in. */
    @SerialName("equipment_ids") val equipmentIds: List<String>,
    @SerialName("rest_seconds") val restSeconds: Int?,
    @SerialName("notes") val notes: String?,
    @SerialName("skipped") val skipped: Boolean,
    @SerialName("user_notes") val userNotes: String?,
)

@Serializable
data class BackupPlannedSet(
    @SerialName("id") val id: Long,
    @SerialName("planned_exercise_id") val plannedExerciseId: Long,
    @SerialName("order") val order: Int,
    @SerialName("reps") val reps: Int?,
    @SerialName("seconds") val seconds: Int?,
    @SerialName("weight") val weight: Double?,
    @SerialName("actual_reps") val actualReps: Int?,
    @SerialName("actual_seconds") val actualSeconds: Int?,
    @SerialName("actual_weight") val actualWeight: Double?,
    @SerialName("status") val status: SetStatus,
    @SerialName("added") val added: Boolean,
)

@Serializable
data class BackupRunSegment(
    @SerialName("id") val id: Long,
    @SerialName("flight_plan_id") val flightPlanId: Long,
    @SerialName("order") val order: Int,
    @SerialName("description") val description: String,
    @SerialName("distance") val distance: Double?,
    @SerialName("minutes") val minutes: Double?,
)

@Serializable
data class BackupExercise(
    @SerialName("id") val id: Long,
    @SerialName("normalized_name") val normalizedName: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class BackupGeneration(
    @SerialName("id") val id: Long,
    @SerialName("kind") val kind: GenerationKind,
    @SerialName("mission_id") val missionId: Long,
    @SerialName("sortie_id") val sortieId: Long?,
    @SerialName("status") val status: GenerationStatus,
    @SerialName("nabu_session_id") val nabuSessionId: String?,
    @SerialName("last_event_id") val lastEventId: String?,
    @SerialName("attempt") val attempt: Int,
    @SerialName("error") val error: String?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("finished_at") val finishedAt: Long?,
)

@Serializable
data class BackupEquipment(
    @SerialName("id") val id: Long,
    @SerialName("key") val key: String,
    @SerialName("name") val name: String,
    @SerialName("notes") val notes: String,
    @SerialName("active") val active: Boolean,
)
