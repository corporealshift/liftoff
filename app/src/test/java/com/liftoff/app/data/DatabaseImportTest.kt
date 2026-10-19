package com.liftoff.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * be refused.
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
     * One occupant of the target database. Each is refused with a message, and every table reads exactly the
     * same afterwards — including the current week, which stays empty because `onAppOpen()` never ran.
     */
    private suspend fun refusedWithoutChange(label: String, file: ByteArray, occupy: suspend (LiftoffDatabase) -> Unit) {
        val db = newDatabase()
        occupy(db)
        val before = snapshotOf(db)

        val result = importDatabase(db, ByteArrayInputStream(file), managerFor(db))

        assertTrue("$label: expected a refusal, got $result", result is ImportResult.Refused)
        val message = (result as ImportResult.Refused).message
        assertTrue("$label: the refusal says nothing: '$message'", message.isNotBlank())
        assertTrue("$label: the refusal does not say why: '$message'", message.contains("empty"))
        assertEquals("$label: the import changed the database", before, snapshotOf(db))
    }

    // An ACTIVE Mission with a sortie; a DRAFT Mission with one sortie; a lone equipment row — each refused
    // with a message, every table reading exactly the same afterwards.
    @Test
    fun importRefusesANonEmptyDatabaseAndChangesNothing() = runBlocking {
        withTimeout(BOUND_MILLIS) {
            val missionId = seedMission(source, lastWeek, "RLR", MissionStatus.ACTIVE)
            seedSortie(source, missionId, 0, state = SortieState.LANDED)
            val file = exportOf(source)

            refusedWithoutChange("an ACTIVE Mission with a sortie", file) { db ->
                val id = seedMission(db, thisWeek, "RLR", MissionStatus.ACTIVE)
                seedSortie(db, id, 0, state = SortieState.LANDED)
            }
            // The fresh-draft exception needs a draft with nothing under it; this draft has a sortie.
            refusedWithoutChange("a DRAFT Mission with one sortie", file) { db ->
                val id = seedMission(db, thisWeek, "RLRLR", MissionStatus.DRAFT)
                seedSortie(db, id, 0)
            }
            refusedWithoutChange("a lone equipment row", file) { db ->
                db.equipmentDao().add(Equipment(key = "kettlebell", name = "Kettlebell"))
            }
        }
    }

    // The draft a cold start made is replaced even when it holds the same id and the same week as the file's
    // current-week Mission: the file lands, the draft is gone, no second Mission for the current week, no
    // duplicate id.
    @Test
    fun importAcceptsADatabaseHoldingOnlyAFreshDraftAndReplacesIt() = runBlocking {
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
    fun importRunsOnAppOpenSoTheCurrentWeekIsSettled() = runBlocking {
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
    fun importReportsHowMuchWasRestored() = runBlocking {
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
    }
}
