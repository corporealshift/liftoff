package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The export file, driven over an in-memory database and a byte stream: the whole database in one
 * document, ids included, both versions in the header, snake_case keys, and nothing that belongs to
 * settings or the coach daemon in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseExportTest {

    private lateinit var db: LiftoffDatabase

    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The id each seeded row ends up with in the database. */
    private class Seed(
        val missionId: Long,
        val sortieId: Long,
        val flightPlanId: Long,
        val plannedExerciseId: Long,
        val plannedSetId: Long,
        val runSegmentId: Long,
        val exerciseId: Long,
        val generationId: Long,
        val equipmentId: Long
    )

    // One row in each of the nine tables, inserted in foreign-key order.
    private fun seedOneRowPerTable(): Seed = runBlocking {
        withTimeout(BOUND_MILLIS) {
            val missionId = db.missionDao().insert(
                Mission(
                    weekStart = LocalDate.of(2026, 10, 12),
                    pattern = "RLRLR",
                    status = MissionStatus.ACTIVE,
                    outlineNotes = "squat week"
                )
            )
            val exerciseId = db.exerciseDao().insert(
                Exercise(normalizedName = "squat", displayName = "Squat")
            )
            val equipmentId = db.equipmentDao().add(
                Equipment(key = "barbell", name = "Barbell", notes = "20 kg", active = true)
            )
            val sortieId = db.sortieDao().insert(
                Sortie(
                    missionId = missionId, index = 0, type = SortieType.LIFT, focus = "squat",
                    focusRationale = "progress", state = SortieState.LANDED,
                    launchedAt = 1760000000000L, landedAt = 1760003600000L, scrubReason = null,
                    notes = "felt good", runDistance = null, runMinutes = null
                )
            )
            val flightPlanId = db.flightPlanDao().insertPlan(
                FlightPlan(
                    sortieId = sortieId, source = FlightPlanSource.GENERATED, title = "Squat day",
                    estimatedMinutes = 45, warmup = "jog 5", notes = null, runKind = null,
                    targetDistance = null, targetPace = null, rawJson = "{\"sets\":[]}"
                )
            )
            val plannedExerciseId = db.flightPlanDao().insertExercise(
                PlannedExercise(
                    flightPlanId = flightPlanId, `order` = 0, exerciseId = exerciseId,
                    equipmentIds = listOf("barbell", "collar"), restSeconds = 90,
                    notes = "coach note", skipped = false, userNotes = "loosen the collars"
                )
            )
            val plannedSetId = db.flightPlanDao().insertSet(
                PlannedSet(
                    plannedExerciseId = plannedExerciseId, `order` = 0, reps = 5, seconds = null,
                    weight = 100.0, actualReps = 5, actualSeconds = null, actualWeight = 100.0,
                    status = SetStatus.DONE, added = false
                )
            )
            val runSegmentId = db.flightPlanDao().insertSegment(
                RunSegment(
                    flightPlanId = flightPlanId, `order` = 0, description = "3 miles easy",
                    distance = 3.0, minutes = 30.0
                )
            )
            val generationId = db.generationDao().insert(
                Generation(
                    kind = GenerationKind.FLIGHT_PLAN, missionId = missionId, sortieId = sortieId,
                    status = GenerationStatus.SUCCEEDED, nabuSessionId = "nabu-1",
                    lastEventId = "evt-9", attempt = 1, error = null,
                    createdAt = 1760000000000L, finishedAt = 1760000600000L
                )
            )
            Seed(
                missionId, sortieId, flightPlanId, plannedExerciseId, plannedSetId,
                runSegmentId, exerciseId, generationId, equipmentId
            )
        }
    }

    private fun exportBytes(): ByteArray = runBlocking {
        withTimeout(BOUND_MILLIS) {
            val out = ByteArrayOutputStream()
            exportDatabase(db, out, clock)
            out.toByteArray()
        }
    }

    // The brief's Export: every row of every table, ids included, plus a format version and the Room schema version.
    @Test
    fun exportWritesEveryRowOfEveryTableWithIdsAndBothVersions() {
        val seed = seedOneRowPerTable()
        val file = parseJson(exportBytes())

        assertEquals(
            "the file is a header plus one array per table",
            TABLE_KEYS, file.keys.toSet()
        )

        val header = file.obj("header")
        assertEquals(BACKUP_FORMAT_VERSION, header.int("format_version"))
        // Read the database's own version here rather than trusting the export's arithmetic.
        val databaseVersion = db.openHelper.readableDatabase.version
        assertEquals(databaseVersion, header.int("schema_version"))
        assertEquals(1, databaseVersion)
        assertEquals(clock.millis(), header.long("exported_at"))

        val mission = file.onlyRow("missions")
        assertEquals(seed.missionId, mission.long("id"))
        assertEquals("2026-10-12", mission.string("week_start"))
        assertEquals("RLRLR", mission.string("pattern"))
        assertEquals("ACTIVE", mission.string("status"))
        assertEquals("squat week", mission.string("outline_notes"))

        val sortie = file.onlyRow("sorties")
        assertEquals(seed.sortieId, sortie.long("id"))
        assertEquals(seed.missionId, sortie.long("mission_id"))
        assertEquals("LANDED", sortie.string("state"))
        assertEquals(1760003600000L, sortie.long("landed_at"))

        val plan = file.onlyRow("flight_plans")
        assertEquals(seed.flightPlanId, plan.long("id"))
        assertEquals(seed.sortieId, plan.long("sortie_id"))
        assertEquals("{\"sets\":[]}", plan.string("raw_json"))

        val exercise = file.onlyRow("planned_exercises")
        assertEquals(seed.plannedExerciseId, exercise.long("id"))
        assertEquals(seed.flightPlanId, exercise.long("flight_plan_id"))
        assertEquals(seed.exerciseId, exercise.long("exercise_id"))
        assertEquals(0, exercise.int("order"))
        assertEquals(
            listOf("barbell", "collar"),
            exercise.value("equipment_ids").jsonArray.map { it.jsonPrimitive.content }
        )

        val set = file.onlyRow("planned_sets")
        assertEquals(seed.plannedSetId, set.long("id"))
        assertEquals(seed.plannedExerciseId, set.long("planned_exercise_id"))
        assertEquals(0, set.int("order"))
        assertEquals("DONE", set.string("status"))
        assertEquals(5, set.int("actual_reps"))

        val segment = file.onlyRow("run_segments")
        assertEquals(seed.runSegmentId, segment.long("id"))
        assertEquals(seed.flightPlanId, segment.long("flight_plan_id"))
        assertEquals(0, segment.int("order"))
        assertEquals("3 miles easy", segment.string("description"))

        assertEquals(seed.exerciseId, file.onlyRow("exercises").long("id"))
        assertEquals("squat", file.onlyRow("exercises").string("normalized_name"))

        val generation = file.onlyRow("generations")
        assertEquals(seed.generationId, generation.long("id"))
        assertEquals(seed.missionId, generation.long("mission_id"))
        assertEquals("SUCCEEDED", generation.string("status"))

        val equipment = file.onlyRow("equipment")
        assertEquals(seed.equipmentId, equipment.long("id"))
        assertEquals("barbell", equipment.string("key"))
        assertTrue("active is kept", equipment.value("active").jsonPrimitive.boolean)
    }

    // Field names in the file are snake_case, and the file leaves out settings and the daemon token.
    @Test
    fun exportFileUsesSnakeCaseKeysAndCarriesNoSettingsOrToken() {
        seedOneRowPerTable()
        val file = parseJson(exportBytes())

        val keys = everyKey(file)
        assertTrue("the export carries no keys at all", keys.isNotEmpty())
        for (key in keys) {
            assertTrue("'$key' is not snake_case", SNAKE_CASE.matches(key))
        }
        // The three keys a camelCase column or a backticked Kotlin name had to become.
        assertTrue("week_start is missing", keys.contains("week_start"))
        assertTrue("equipment_ids is missing", keys.contains("equipment_ids"))
        assertTrue("order is missing", keys.contains("order"))

        for (key in keys) {
            val flattened = key.replace("_", "").lowercase()
            assertTrue(
                "the export carries '$key', which is a setting or a daemon token",
                flattened !in SETTINGS_KEYS &&
                    !key.contains("token", ignoreCase = true) &&
                    !key.contains("daemon", ignoreCase = true) &&
                    !key.contains("setting", ignoreCase = true)
            )
        }
    }

    private fun parseJson(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    /** Every key in the document, at any depth, so a stray camelCase or settings key cannot hide. */
    private fun everyKey(element: JsonElement): Set<String> {
        val keys = mutableSetOf<String>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    keys += key
                    walk(value)
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(element)
        return keys
    }

    private fun JsonObject.value(key: String): JsonElement {
        val element = this[key]
        if (element == null) throw AssertionError("the export has no '$key' key")
        return element
    }

    private fun JsonObject.obj(key: String): JsonObject = value(key).jsonObject
    private fun JsonObject.long(key: String): Long = value(key).jsonPrimitive.long
    private fun JsonObject.int(key: String): Int = value(key).jsonPrimitive.int
    private fun JsonObject.string(key: String): String =
        value(key).jsonPrimitive.contentOrNull ?: throw AssertionError("'$key' is not a string")

    // Each table holds exactly the one row that was seeded in it.
    private fun JsonObject.onlyRow(key: String): JsonObject {
        val element = value(key)
        if (element !is JsonArray) throw AssertionError("'$key' is not an array")
        assertEquals("$key should hold exactly the one seeded row", 1, element.size)
        return element[0].jsonObject
    }

    private companion object {
        // Every wait in this class is bounded, as the brief requires.
        const val BOUND_MILLIS = 30_000L

        val TABLE_KEYS = setOf(
            "header", "missions", "sorties", "flight_plans", "planned_exercises",
            "planned_sets", "run_segments", "exercises", "generations", "equipment"
        )

        val SNAKE_CASE = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

        // Every name SettingsStore keeps, underscore removed so either spelling matches.
        val SETTINGS_KEYS = setOf(
            "daemonhost", "daemonport", "daemontoken", "coachworkspacepath", "defaultpattern",
            "sortielengthminutes", "weightunit", "distanceunit", "historywindowdays",
            "generaterunplans", "objectives", "constraints", "settings", "setting", "token"
        )
    }
}
