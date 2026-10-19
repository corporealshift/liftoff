package com.liftoff.app.data

import android.database.SQLException
import androidx.room.withTransaction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/** Either why the file was not imported, or what was restored. */
sealed interface ImportResult {

    /** Why the file was not imported. Mission Control shows this as it is. */
    data class Refused(val message: String) : ImportResult

    /** Decision 1: what was restored, counted in weeks and workouts rather than per table. */
    data class Restored(val missions: Int, val sorties: Int) : ImportResult
}

/**
 * The brief's Import: put a Liftoff backup's rows back into a database, ids kept.
 *
 * Only an empty database is accepted, so restoring a backup can never overwrite training history. The one
 * exception is the DRAFT Mission `MissionManager.onAppOpen()` makes on every cold start: a database holding
 * nothing but that draft counts as empty, and the draft goes away before the file's rows arrive — otherwise
 * the file's own current-week Mission collides with its unique weekStart.
 *
 * Everything that can be read off the file is read before the database is touched: a file that does not
 * parse, a format version this app has never written, a Room schema version newer than the app's own. What
 * is left is decided by the inserts themselves, inside one transaction, so a file that turns out to be
 * incomplete leaves nothing behind.
 */
suspend fun importDatabase(
    db: LiftoffDatabase,
    input: InputStream,
    manager: MissionManager,
): ImportResult {
    val text = input.readBytes().toString(StandardCharsets.UTF_8)
    val document = try {
        IMPORT_JSON.decodeFromString(BackupDocument.serializer(), text)
    } catch (e: SerializationException) {
        return ImportResult.Refused(NOT_A_LIFTOFF_FILE_MESSAGE)
    }

    versionRefusal(db, document)?.let { return ImportResult.Refused(it) }

    val outcome = try {
        db.withTransaction {
            // The emptiness rule is checked in the same transaction the rows go into, so a concurrent
            // onAppOpen() cannot slip a draft in between the check and the inserts.
            val freshDraft = when (val state = emptinessOf(db)) {
                is Emptiness.Occupied -> return@withTransaction ImportResult.Refused(NOT_EMPTY_MESSAGE)
                Emptiness.Empty -> null
                is Emptiness.FreshDraft -> state.mission
            }
            freshDraft?.let { db.missionDao().deleteDraft(it.id) }
            insertInForeignKeyOrder(db, document)
            ImportResult.Restored(missions = document.missions.size, sorties = document.sorties.size)
        }
    } catch (e: SQLException) {
        // A row the file does not contain, or a key that collides with one already in the database. Room
        // rolls the transaction back on the way out, so the rows that did go in are gone again.
        return ImportResult.Refused(INCOMPLETE_FILE_MESSAGE)
    }

    // The imported weeks are settled the way a cold start settles them. The transaction has committed by
    // now, so the screens that observe the database re-emit through Room's invalidation tracker.
    if (outcome is ImportResult.Restored) manager.onAppOpen()
    return outcome
}

/** Unknown keys are tolerated; a key this app has never seen is no reason to refuse a known format. */
private val IMPORT_JSON = Json { ignoreUnknownKeys = true }

private const val NOT_EMPTY_MESSAGE =
    "Import needs an empty database. This phone already has training data."

private const val NOT_A_LIFTOFF_FILE_MESSAGE =
    "That file isn't a Liftoff backup. It may be damaged or from another app."

private const val NEWER_LIFTOFF_MESSAGE =
    "Made by a newer version of Liftoff. Update the app first."

private const val UNKNOWN_FORMAT_MESSAGE =
    "This backup uses a file format this app doesn't understand."

private const val INCOMPLETE_FILE_MESSAGE =
    "This backup is incomplete — a row in it points at a row the file doesn't contain."

/** The file formats this app has ever written. Anything else is a file it cannot describe. */
private val KNOWN_FORMAT_VERSIONS = setOf(BACKUP_FORMAT_VERSION)

/**
 * Decision 3. A format version outside the known set is a document this app has no reading of; a Room
 * schema version newer than the app's own is a set of rows with columns this app does not have. An older
 * schema version is accepted, because its rows are a subset of what this app stores.
 */
private suspend fun versionRefusal(db: LiftoffDatabase, document: BackupDocument): String? {
    val formatVersion = document.header.formatVersion
    if (formatVersion !in KNOWN_FORMAT_VERSIONS) {
        return if (formatVersion > BACKUP_FORMAT_VERSION) NEWER_LIFTOFF_MESSAGE else UNKNOWN_FORMAT_MESSAGE
    }
    return if (document.header.schemaVersion > roomSchemaVersion(db)) NEWER_LIFTOFF_MESSAGE else null
}

private sealed interface Emptiness {
    data object Empty : Emptiness

    /** The one thing import may replace: the DRAFT Mission a cold start made, with nothing under it. */
    data class FreshDraft(val mission: Mission) : Emptiness

    data object Occupied : Emptiness
}

/**
 * Every table must hold no rows. The exception is a single DRAFT Mission, which is only a fresh draft
 * because nothing else in the database refers to it: sortie and generation are the two tables that can
 * point at a Mission, and both are already counted as empty here.
 */
private suspend fun emptinessOf(db: LiftoffDatabase): Emptiness {
    val rowsUnderneath = db.sortieDao().countAll() +
        db.exerciseDao().countAll() +
        db.equipmentDao().countAll() +
        db.generationDao().countAll() +
        db.flightPlanDao().countPlans() +
        db.flightPlanDao().countPlannedExercises() +
        db.flightPlanDao().countPlannedSets() +
        db.flightPlanDao().countRunSegments()
    if (rowsUnderneath != 0L) return Emptiness.Occupied

    val missions = db.missionDao().getAll()
    return when {
        missions.isEmpty() -> Emptiness.Empty
        missions.size == 1 && missions.single().status == MissionStatus.DRAFT ->
            Emptiness.FreshDraft(missions.single())
        else -> Emptiness.Occupied
    }
}

/**
 * mission → exercise → equipment → sortie → flightPlan → plannedExercise → plannedSet → runSegment →
 * generation. plannedExercise needs both its flightPlan and its exercise row, and that FK has no cascade;
 * a generation names both a Mission and a sortie, so it comes last.
 */
private suspend fun insertInForeignKeyOrder(db: LiftoffDatabase, document: BackupDocument) {
    db.missionDao().insertAll(document.missions.map { it.toMission() })
    db.exerciseDao().insertAll(document.exercises.map { it.toExercise() })
    db.equipmentDao().insertAll(document.equipment.map { it.toEquipment() })
    db.sortieDao().insertAll(document.sorties.map { it.toSortie() })
    db.flightPlanDao().insertAllPlans(document.flightPlans.map { it.toFlightPlan() })
    db.flightPlanDao().insertAllPlannedExercises(document.plannedExercises.map { it.toPlannedExercise() })
    db.flightPlanDao().insertAllPlannedSets(document.plannedSets.map { it.toPlannedSet() })
    db.flightPlanDao().insertAllRunSegments(document.runSegments.map { it.toRunSegment() })
    db.generationDao().insertAll(document.generations.map { it.toGeneration() })
}

private fun BackupMission.toMission() = Mission(
    id = id,
    weekStart = LocalDate.parse(weekStart),
    pattern = pattern,
    status = status,
    outlineNotes = outlineNotes
)

private fun BackupSortie.toSortie() = Sortie(
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

private fun BackupFlightPlan.toFlightPlan() = FlightPlan(
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

private fun BackupPlannedExercise.toPlannedExercise() = PlannedExercise(
    id = id,
    flightPlanId = flightPlanId,
    `order` = order,
    exerciseId = exerciseId,
    equipmentIds = equipmentIds,
    restSeconds = restSeconds,
    notes = notes,
    skipped = skipped,
    userNotes = userNotes
)

private fun BackupPlannedSet.toPlannedSet() = PlannedSet(
    id = id,
    plannedExerciseId = plannedExerciseId,
    `order` = order,
    reps = reps,
    seconds = seconds,
    weight = weight,
    actualReps = actualReps,
    actualSeconds = actualSeconds,
    actualWeight = actualWeight,
    status = status,
    added = added
)

private fun BackupRunSegment.toRunSegment() = RunSegment(
    id = id,
    flightPlanId = flightPlanId,
    `order` = order,
    description = description,
    distance = distance,
    minutes = minutes
)

private fun BackupExercise.toExercise() = Exercise(
    id = id,
    normalizedName = normalizedName,
    displayName = displayName
)

private fun BackupGeneration.toGeneration() = Generation(
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

private fun BackupEquipment.toEquipment() = Equipment(
    id = id,
    key = key,
    name = name,
    notes = notes,
    active = active
)
