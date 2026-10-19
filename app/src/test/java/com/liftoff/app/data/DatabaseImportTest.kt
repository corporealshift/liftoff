package com.liftoff.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Import over two in-memory databases and a byte array: the file the export wrote, put back into a
 * database that either holds nothing, holds only the draft a cold start made, or holds training data and must
 * be refused. Every refusal here is followed by a read of every table, so "changes nothing" is measured, not
 * assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseImportTest {

    @get:Rule
    val storage = TemporaryFolder()

    /** `source` is exported into the file; `target` is the database the file is imported into. */
    private lateinit var source: LiftoffDatabase
    private lateinit var target: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private val opened = mutableListOf<LiftoffDatabase>()

    // A Monday, so the current week is the one every Mission here is placed against.
    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)
    private val thisWeek = LocalDate.of(2026, 1, 12)
    private val lastWeek = LocalDate.of(2026, 1, 5)
    private val weekBefore = LocalDate.of(2025, 12, 29)

    @Before
    fun setUp() {
        source = newDatabase()
        target = newDatabase()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create { File(storage.root, "settings.preferences_pb") }
        )
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun newDatabase(): LiftoffDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { opened += it }
    }

    private fun managerFor(db: LiftoffDatabase) = MissionManager(
        db, settingsStore, clock = clock, zone = { ZoneOffset.UTC }
    )

    private suspend fun exportOf(db: LiftoffDatabase): ByteArray {
        val out = ByteArrayOutputStream()
        exportDatabase(db, out, clock)
        return out.toByteArray()
    }

    private suspend fun seedMission(
        db: LiftoffDatabase,
        weekStart: LocalDate,
        pattern: String,
        status: MissionStatus,
        id: Long = 0L,
        outlineNotes: String? = null,
    ): Long = db.missionDao().insert(
        Mission(
            id = id, weekStart = weekStart, pattern = pattern,
            status = status, outlineNotes = outlineNotes
        )
    )

    private suspend fun seedSortie(
        db: LiftoffDatabase,
        missionId: Long,
        index: Int,
        type: SortieType = SortieType.RUN,
        state: SortieState = SortieState.PENDING,
    ): Long = db.sortieDao().insert(
        Sortie(
            missionId = missionId, index = index, type = type, focus = null,
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        )
    )

    /** mission → sortie → exercise → flightPlan → plannedExercise → plannedSet, the chain a file can point at. */
    private suspend fun seedPlanChain(db: LiftoffDatabase): Long {
        val missionId = seedMission(db, lastWeek, "RL", MissionStatus.CLOSED)
        val sortieId = seedSortie(db, missionId, 0, SortieType.LIFT, SortieState.LANDED)
        val exerciseId = db.exerciseDao().insert(Exercise(normalizedName = "squat", displayName = "Squat"))
        val planId = db.flightPlanDao().insertPlan(
            FlightPlan(
                sortieId = sortieId, source = FlightPlanSource.GENERATED, title = "Squat day",
                estimatedMinutes = 45, warmup = null, notes = null, runKind = null,
                targetDistance = null, targetPace = null, rawJson = "{\"sets\":[]}"
            )
        )
        val plannedExerciseId = db.flightPlanDao().insertExercise(
            PlannedExercise(
                flightPlanId = planId, `order` = 0, exerciseId = exerciseId,
                equipmentIds = listOf("barbell"), restSeconds = 90, notes = null,
                skipped = false, userNotes = null
            )
        )
        db.flightPlanDao().insertSet(
            PlannedSet(
                plannedExerciseId = plannedExerciseId, `order` = 0, reps = 5, seconds = null,
                weight = 100.0, actualReps = 5, actualSeconds = null, actualWeight = 100.0,
                status = SetStatus.DONE, added = false
            )
        )
        return exerciseId
    }

    /** Every row of every table, so a refusal can be proved against the whole database. */
    private data class Snapshot(
        val missions: List<Mission>,
        val sorties: List<Sortie>,
        val plans: List<FlightPlan>,
        val plannedExercises: List<PlannedExercise>,
        val plannedSets: List<PlannedSet>,
        val runSegments: List<RunSegment>,
        val exercises: List<Exercise>,
        val generations: List<Generation>,
        val equipment: List<Equipment>,
    )

    private suspend fun snapshotOf(db: LiftoffDatabase) = Snapshot(
        missions = db.missionDao().getAll(),
        sorties = db.sortieDao().getAll(),
        plans = db.flightPlanDao().getAllPlans(),
        plannedExercises = db.flightPlanDao().getAllPlannedExercises(),
        plannedSets = db.flightPlanDao().getAllPlannedSets(),
        runSegments = db.flightPlanDao().getAllRunSegments(),
        exercises = db.exerciseDao().getAll(),
        generations = db.generationDao().getAll(),
        equipment = db.equipmentDao().getAll(),
    )

    /**
     * One attempt at a file the app should not accept. The target holds only the DRAFT Mission a cold start
     * made — the state a real phone is in when a backup is picked — so a row that got in and was thrown away,
     * or a draft that was deleted and never put back, shows up as a table reading differently afterwards.
     * `says` is the one thing the refusal has to name, so a refusal that gives the wrong reason reads as a
     * failure even when it refuses.
     */
    private suspend fun refusedWithoutChange(
        label: String,
        file: ByteArray,
        says: String,
        occupy: suspend (LiftoffDatabase) -> Unit = { it.seedAFreshDraft() },
    ): String {
        val db = newDatabase()
        occupy(db)
        val before = snapshotOf(db)

        val result = importDatabase(db, ByteArrayInputStream(file), managerFor(db))

        assertTrue("$label: expected a refusal, got $result", result is ImportResult.Refused)
        val message = (result as ImportResult.Refused).message
        assertTrue("$label: the refusal says nothing: '$message'", message.isNotBlank())
        assertTrue("$label: the refusal does not name '$says': '$message'", message.contains(says))
        assertEquals("$label: the import changed the database", before, snapshotOf(db))
        return message
    }

    /** The DRAFT Mission `onAppOpen()` makes on every cold start, and nothing else. */
    private suspend fun LiftoffDatabase.seedAFreshDraft(): Long =
        seedMission(this, thisWeek, "RLRLR", MissionStatus.DRAFT)

    /** A file the app can read: it lands in place of the draft, and nothing else is added. */
    private suspend fun acceptedFile(label: String, text: String) {
        val db = newDatabase()
        db.seedAFreshDraft()

        val result = importDatabase(db, ByteArrayInputStream(text.encodeToByteArray()), managerFor(db))

        assertTrue("$label: expected the file to land, got $result", result is ImportResult.Restored)
        assertEquals("$label: the wrong counts were restored", ImportResult.Restored(missions = 1, sorties = 1), result)
        assertEquals("$label: the file's Mission did not land", 1, db.missionDao().getAll().size)
    }

    /** Both versions off the file's own header, so a test can tell a version check from a parse failure. */
    private fun headerOf(text: String): Pair<Int, Int> {
        val header = Json.parseToJsonElement(text).jsonObject.getValue("header").jsonObject
        return header.getValue("format_version").jsonPrimitive.int to
            header.getValue("schema_version").jsonPrimitive.int
    }

    private fun withFormatVersion(text: String, to: Int): String {
        val changed = text.replace("\"format_version\": $BACKUP_FORMAT_VERSION", "\"format_version\": $to")
        assertTrue("the file's format_version was not changed", changed != text)
        return changed
    }

    private fun withSchemaVersion(text: String, from: Int, to: Int): String {
        val changed = text.replace("\"schema_version\": $from", "\"schema_version\": $to")
        assertTrue("the file's schema_version was not changed", changed != text)
        return changed
    }

    /** A key this app has never seen, once at the top of the file and once inside a Mission row. */
    private fun withUnseenKeys(text: String): String {
        val atTheTop = text.replace(
            "\"missions\": [",
            "\"unseen_at_the_top\": \"from a newer app\", \"missions\": ["
        )
        assertTrue("no unseen key was added at the top of the file", atTheTop != text)
        val inARow = atTheTop.replace(
            "\"pattern\":",
            "\"unseen_in_a_row\": \"from a newer app\", \"pattern\":"
        )
        assertTrue("no unseen key was added inside a Mission row", inARow != atTheTop)
        return inARow
    }


    // An ACTIVE Mission with a sortie; a DRAFT Mission with one sortie; a lone equipment row — each refused
    // with a message, every table reading exactly the same afterwards.
    @Test
    fun importRefusesANonEmptyDatabaseAndChangesNothing() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val missionId = seedMission(source, lastWeek, "RLR", MissionStatus.ACTIVE)
            seedSortie(source, missionId, 0, state = SortieState.LANDED)
            val file = exportOf(source)

            refusedWithoutChange("an ACTIVE Mission with a sortie", file, says = "empty") { db ->
                val id = seedMission(db, thisWeek, "RLR", MissionStatus.ACTIVE)
                seedSortie(db, id, 0, state = SortieState.LANDED)
            }
            // The fresh-draft exception needs a draft with nothing under it; this draft has a sortie.
            refusedWithoutChange("a DRAFT Mission with one sortie", file, says = "empty") { db ->
                val id = seedMission(db, thisWeek, "RLRLR", MissionStatus.DRAFT)
                seedSortie(db, id, 0)
            }
            refusedWithoutChange("a lone equipment row", file, says = "empty") { db ->
                db.equipmentDao().add(Equipment(key = "kettlebell", name = "Kettlebell"))
            }
        }
    }

    // A file cut off half-way through, a file whose JSON does not parse, a file holding nothing at all, and two
    // valid JSON documents that are simply something else — an array, and an object with none of this file's
    // parts. Each refused with a message of its own, the database reading exactly the same afterwards.
    @Test
    fun importRefusesAFileThatIsNotALiftoffExportAndChangesNothing() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val missionId = seedMission(source, thisWeek, "RLR", MissionStatus.ACTIVE)
            seedSortie(source, missionId, 0, state = SortieState.LANDED)
            val text = exportOf(source).decodeToString()

            refusedWithoutChange(
                "a file cut off half-way through",
                text.substring(0, text.length / 2).encodeToByteArray(),
                says = "Liftoff"
            )
            refusedWithoutChange("a file whose JSON does not parse", BROKEN_JSON.encodeToByteArray(), says = "Liftoff")
            refusedWithoutChange("a file holding nothing at all", ByteArray(0), says = "Liftoff")
            refusedWithoutChange("a JSON array instead of a document", "[1, 2, 3]".encodeToByteArray(), says = "Liftoff")
            refusedWithoutChange(
                "a JSON object with none of this file's parts",
                """{"title": "not a backup", "count": 2}""".encodeToByteArray(),
                says = "Liftoff"
            )
        }
    }

    // A format_version this app has never written, and a Room schema version newer than the app's own: each
    // refused with a message of its own, each leaving the database exactly as it was. The opposite half is
    // asserted here rather than in a test of its own — a file at the app's own versions, one from an older
    // schema version, and one carrying a key this app has never seen inside a format version it does know, all
    // three land.
    @Test
    fun importRefusesAVersionItCannotReadAndChangesNothing() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val missionId = seedMission(source, thisWeek, "RLR", MissionStatus.ACTIVE)
            seedSortie(source, missionId, 0, state = SortieState.LANDED)
            val text = exportOf(source).decodeToString()
            val (ownFormat, ownSchema) = headerOf(text)
            assertEquals(BACKUP_FORMAT_VERSION, ownFormat)
            assertEquals("the file came from the database's own schema version", source.openHelper.readableDatabase.version, ownSchema)

            val newerFormat = refusedWithoutChange(
                "a format_version this app has never written",
                withFormatVersion(text, ownFormat + 1).encodeToByteArray(),
                says = "newer"
            )
            val unknownFormat = refusedWithoutChange(
                "a format_version this app has no reading of",
                withFormatVersion(text, ownFormat - 1).encodeToByteArray(),
                says = "format"
            )
            val newerSchema = refusedWithoutChange(
                "a Room schema version newer than the app's own",
                withSchemaVersion(text, ownSchema, ownSchema + 1).encodeToByteArray(),
                says = "newer"
            )
            assertTrue(
                "an unreadable format version and an unreadable schema version give the same message: '$unknownFormat'",
                unknownFormat != newerSchema && unknownFormat != newerFormat
            )

            acceptedFile("a file at the app's own versions", text)
            acceptedFile("a file from an older Room schema version", withSchemaVersion(text, ownSchema, ownSchema - 1))
            acceptedFile("a file carrying a key this app has never seen", withUnseenKeys(text))
        }
    }

    // A file that parses, carries the app's own versions, and still cannot be imported: its plannedExercise
    // points at an exercise id the file does not contain. Refused — and nothing half-written. The draft the
    // cold start made is deleted inside the same transaction the rows go into, so it reading exactly as it did
    // before the attempt is what proves the whole transaction went back.
    @Test
    fun importWritesNothingWhenAFileHasADanglingReference() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val exerciseId = seedPlanChain(source)
            val text = exportOf(source).decodeToString()
            val dangling = text.replace("\"exercise_id\": $exerciseId", "\"exercise_id\": $MISSING_EXERCISE_ID")
            assertTrue("the file's exercise reference was not broken", dangling != text)

            val (formatVersion, schemaVersion) = headerOf(dangling)
            assertEquals("the file still carries a format version this app knows", BACKUP_FORMAT_VERSION, formatVersion)
            assertEquals(
                "the file still carries a schema version this app can read",
                source.openHelper.readableDatabase.version,
                schemaVersion
            )

            refusedWithoutChange(
                "a plannedExercise pointing at an exercise the file does not contain",
                dangling.encodeToByteArray(),
                says = "incomplete"
            )
        }
    }

    // The draft a cold start made is replaced even when it holds the same id and the same week as the file's
    // current-week Mission: the file lands, the draft is gone, no second Mission for the current week, no
    // duplicate id.
    @Test
    fun importAcceptsADatabaseHoldingOnlyAFreshDraftAndReplacesIt() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val fileMissionId = seedMission(
                source, thisWeek, "RLLR", MissionStatus.ACTIVE, id = 1L, outlineNotes = "from the file"
            )
            val fileSortieId = seedSortie(source, fileMissionId, 0, SortieType.LIFT, SortieState.LANDED)
            val file = exportOf(source)

            // The target holds only onAppOpen()'s draft, colliding with the file on both id and week.
            val draftId = seedMission(target, thisWeek, "RLRLR", MissionStatus.DRAFT, id = 1L)

            val result = importDatabase(target, ByteArrayInputStream(file), managerFor(target))
            assertTrue("expected the file to land, got $result", result is ImportResult.Restored)

            val missions = target.missionDao().getAll()
            assertEquals("the draft left more than one Mission behind", 1, missions.size)
            val landed = missions.single()
            assertEquals("the file's Mission did not keep its id", fileMissionId, landed.id)
            assertEquals("the file's Mission did not take the draft's place", draftId, landed.id)
            assertEquals("the draft's pattern survived", "RLLR", landed.pattern)
            assertEquals("the draft's status survived", MissionStatus.ACTIVE, landed.status)
            assertEquals("from the file", landed.outlineNotes)
            assertEquals(
                "a second Mission was made for the current week", landed, target.missionDao().getByWeekStart(thisWeek)
            )
            assertEquals("a Mission id was duplicated", listOf(fileMissionId), missions.map { it.id })
            assertEquals(
                "the file's sortie did not land with its own id",
                listOf(fileSortieId), target.sortieDao().getAll().map { it.id }
            )
        }
    }

    // A file whose Missions are all in past weeks: afterwards the current week has a Mission, and the file's
    // unclosed past Mission is rolled over, not duplicated.
    @Test
    fun importRunsOnAppOpenSoTheCurrentWeekIsSettled() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val openPast = seedMission(source, lastWeek, "RLR", MissionStatus.ACTIVE)
            seedSortie(source, openPast, 0)
            seedSortie(source, openPast, 1, state = SortieState.LANDED)
            seedMission(source, weekBefore, "RL", MissionStatus.CLOSED)
            val file = exportOf(source)

            val result = importDatabase(target, ByteArrayInputStream(file), managerFor(target))
            assertTrue("expected the file to land, got $result", result is ImportResult.Restored)

            val current = target.missionDao().getByWeekStart(thisWeek)
            assertTrue("the current week has no Mission", current != null)
            assertEquals(MissionStatus.DRAFT, current!!.status)

            val past = target.missionDao().get(openPast)!!
            assertEquals("the file's unclosed past Mission was not rolled over", MissionStatus.CLOSED, past.status)
            assertEquals(
                "a past week was duplicated",
                listOf(weekBefore, lastWeek, thisWeek),
                target.missionDao().getAll().map { it.weekStart }.sorted()
            )
            assertEquals(
                "the rollover did not scrub the past week's unfinished sortie",
                listOf(SortieState.SCRUBBED, SortieState.LANDED),
                target.sortieDao().getForMission(openPast).map { it.state }
            )
        }
    }

    // The action reports the file's Mission count and its sortie count.
    @Test
    fun importReportsHowMuchWasRestored() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            val older = seedMission(source, weekBefore, "R", MissionStatus.CLOSED)
            val olderAgain = seedMission(source, lastWeek, "RL", MissionStatus.CLOSED)
            seedSortie(source, older, 0, state = SortieState.LANDED)
            seedSortie(source, older, 1, SortieType.LIFT, SortieState.SCRUBBED)
            seedSortie(source, olderAgain, 0, state = SortieState.LANDED)
            val file = exportOf(source)

            val result = importDatabase(target, ByteArrayInputStream(file), managerFor(target))

            assertEquals(ImportResult.Restored(missions = 2, sorties = 3), result)
        }
    }

    private companion object {
        // Every wait in this class is bounded, as the brief requires.
        const val BOUND_MILLIS = 30_000L

        // A JSON object that opens but never closes, and a comma with no value after it.
        const val BROKEN_JSON = """{"header": {"format_version": 1, "schema_version": 1, "exported_at": 1767000000000,}}"""

        // An exercise id no seeded database here holds, so a row pointing at it points outside the file.
        const val MISSING_EXERCISE_ID = 9_999_999L
    }
}
