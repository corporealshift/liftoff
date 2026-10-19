package com.liftoff.app.data

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock

/**
 * The brief's Export: every row of every table in one JSON document, ids included.
 *
 * Settings and the daemon token need no excluding — they live in DataStore and this never reads
 * DataStore. The file is written indented because a backup is something people open in an editor.
 */
suspend fun exportDatabase(
    db: LiftoffDatabase,
    out: OutputStream,
    clock: Clock = Clock.systemUTC(),
) {
    // One transaction, so a set being checked mid-export cannot land half-way through the file.
    val document = db.withTransaction {
        BackupDocument(
            header = BackupHeader(
                formatVersion = BACKUP_FORMAT_VERSION,
                schemaVersion = roomSchemaVersion(db),
                exportedAt = clock.millis()
            ),
            missions = db.missionDao().getAll().map { it.toBackup() },
            sorties = db.sortieDao().getAll().map { it.toBackup() },
            flightPlans = db.flightPlanDao().getAllPlans().map { it.toBackup() },
            plannedExercises = db.flightPlanDao().getAllPlannedExercises().map { it.toBackup() },
            plannedSets = db.flightPlanDao().getAllPlannedSets().map { it.toBackup() },
            runSegments = db.flightPlanDao().getAllRunSegments().map { it.toBackup() },
            exercises = db.exerciseDao().getAll().map { it.toBackup() },
            generations = db.generationDao().getAll().map { it.toBackup() },
            equipment = db.equipmentDao().getAll().map { it.toBackup() }
        )
    }
    val text = BACKUP_JSON.encodeToString(BackupDocument.serializer(), document)
    out.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
    out.flush()
}

/** Indented, one key per line. Decoding tolerance belongs to import, not here. */
private val BACKUP_JSON = Json { prettyPrint = true }

/**
 * The version the database is actually at. Room's `@Database` annotation has CLASS retention, so it
 * is not readable through reflection; SQLite's own user_version is what the app is running.
 */
internal fun roomSchemaVersion(db: LiftoffDatabase): Int = db.openHelper.readableDatabase.version

private fun Mission.toBackup() = BackupMission(
    id = id,
    weekStart = weekStart.toString(),
    pattern = pattern,
    status = status,
    outlineNotes = outlineNotes
)

private fun Sortie.toBackup() = BackupSortie(
    id = id,
    missionId = missionId,
    index = index,
    type = type,
    focus = focus,
    focusRationale = focusRationale,
    state = state,
    launchedAt = launchedAt,
    landedAt = landedAt,
    scrubReason = scrubReason,
    notes = notes,
    runDistance = runDistance,
    runMinutes = runMinutes
)

private fun FlightPlan.toBackup() = BackupFlightPlan(
    id = id,
    sortieId = sortieId,
    source = source,
    title = title,
    estimatedMinutes = estimatedMinutes,
    warmup = warmup,
    notes = notes,
    runKind = runKind,
    targetDistance = targetDistance,
    targetPace = targetPace,
    rawJson = rawJson
)

private fun PlannedExercise.toBackup() = BackupPlannedExercise(
    id = id,
    flightPlanId = flightPlanId,
    order = `order`,
    exerciseId = exerciseId,
    equipmentIds = equipmentIds,
    restSeconds = restSeconds,
    notes = notes,
    skipped = skipped,
    userNotes = userNotes
)

private fun PlannedSet.toBackup() = BackupPlannedSet(
    id = id,
    plannedExerciseId = plannedExerciseId,
    order = `order`,
    reps = reps,
    seconds = seconds,
    weight = weight,
    actualReps = actualReps,
    actualSeconds = actualSeconds,
    actualWeight = actualWeight,
    status = status,
    added = added
)

private fun RunSegment.toBackup() = BackupRunSegment(
    id = id,
    flightPlanId = flightPlanId,
    order = `order`,
    description = description,
    distance = distance,
    minutes = minutes
)

private fun Exercise.toBackup() = BackupExercise(
    id = id,
    normalizedName = normalizedName,
    displayName = displayName
)

private fun Generation.toBackup() = BackupGeneration(
    id = id,
    kind = kind,
    missionId = missionId,
    sortieId = sortieId,
    status = status,
    nabuSessionId = nabuSessionId,
    lastEventId = lastEventId,
    attempt = attempt,
    error = error,
    createdAt = createdAt,
    finishedAt = finishedAt
)

private fun Equipment.toBackup() = BackupEquipment(
    id = id,
    key = key,
    name = name,
    notes = notes,
    active = active
)
