package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerReFlyTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var manager: MissionManager

    private val weekStart = LocalDate.of(2026, 1, 12)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "settings.preferences_pb")
        }
        manager = MissionManager(
            db, SettingsStore(dataStore),
            clock = Clock.fixed(Instant.parse("2026-01-14T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // The guards reject a DRAFT mission, so this fixture starts ACTIVE.
    private fun insertActiveMission(): Long = runBlocking {
        db.missionDao().insert(Mission(
            weekStart = weekStart, pattern = "LLL", status = MissionStatus.ACTIVE, outlineNotes = null
        ))
    }

    private fun insertSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        state: SortieState,
        landedAt: Long? = null
    ): Long = runBlocking {
        sortieDao.insert(Sortie(
            missionId = missionId, index = index, type = type, focus = "upper",
            focusRationale = null, state = state, launchedAt = landedAt?.minus(3_600_000),
            landedAt = landedAt, scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
    }

    private val liftDraft = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = "Upper A", estimatedMinutes = 45,
        warmup = "Band pull-aparts", notes = "Keep rests honest", runKind = null,
        targetDistance = null, targetPace = null, rawJson = """{"title":"Upper A"}""",
        exercises = listOf(
            ExerciseDraft(
                name = "Bench Press", equipmentIds = listOf("barbell", "bench"), restSeconds = 120,
                notes = "Pause on the chest",
                sets = listOf(SetDraft(8, null, 60.0), SetDraft(6, null, 65.0))
            ),
            ExerciseDraft(
                name = "Plank", equipmentIds = emptyList(), restSeconds = 60, notes = "Brace",
                sets = listOf(SetDraft(null, 45, null), SetDraft(null, 30, null))
            )
        ),
        segments = emptyList()
    )

    private val runDraft = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = "Tempo", estimatedMinutes = 40,
        warmup = null, notes = null, runKind = "tempo", targetDistance = 8.0,
        targetPace = "5:00/km", rawJson = "{}", exercises = emptyList(),
        segments = listOf(
            RunSegmentDraft("Easy warm-up", 2.0, 12.0),
            RunSegmentDraft("Tempo", 5.0, 25.0),
            RunSegmentDraft("Cool-down", 1.0, null)
        )
    )

    // Sortie 0 LANDED with a plan carrying actuals unlike the plan, an added bench set and a skipped,
    // noted plank. Sortie 1 is the current PENDING one; sortie 2 is PENDING but later.
    private fun liftFixture(): Triple<Long, Long, Long> = runBlocking {
        val missionId = insertActiveMission()
        val source = insertSortie(missionId, 0, SortieType.LIFT, SortieState.LANDED, landedAt = 1_768_200_000_000)
        flightPlanDao.writePlan(source, liftDraft)

        val plan = flightPlanDao.getPlan(source)!!
        val bench = plan.exercises[0]
        flightPlanDao.saveSetEdit(bench.sets[0].id, 7, null, 62.5)
        flightPlanDao.saveSetEdit(bench.sets[1].id, 5, null, 67.5)
        flightPlanDao.addSet(bench.plannedExercise.id)

        val plank = plan.exercises[1]
        flightPlanDao.saveSetEdit(plank.sets[0].id, null, 40, null)
        flightPlanDao.skipExercise(plank.plannedExercise.id)
        flightPlanDao.saveExerciseNote(plank.plannedExercise.id, "Back twinged")

        val current = insertSortie(missionId, 1, SortieType.LIFT, SortieState.PENDING)
        val later = insertSortie(missionId, 2, SortieType.LIFT, SortieState.PENDING)
        Triple(source, current, later)
    }

    // GenerationDao has no list query, and this commit may not add one.
    private fun generationCount(): Int =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM generation")).use { cursor ->
            cursor.moveToFirst(); cursor.getInt(0)
        }

    private suspend fun assertRefused(sortieId: Long) {
        try {
            manager.refly(sortieId)
            fail("refly should have refused sortie $sortieId")
        } catch (expected: IllegalStateException) {
        }
    }

    // Planned reps/seconds/weight carried, no actuals, every set OPEN, and the copy points at the same
    // exercises in the same order with the same equipment, rests and coach notes and the source's plan fields.
    @Test
    fun reflyCopiesPlannedValuesAndNotActuals() = runBlocking {
        val (source, current, _) = liftFixture()
        val sourcePlan = flightPlanDao.getPlan(source)!!

        manager.refly(current)

        val copy = flightPlanDao.getPlan(current)!!
        assertEquals("Upper A", copy.plan.title)
        assertEquals(45, copy.plan.estimatedMinutes)
        assertEquals("Band pull-aparts", copy.plan.warmup)
        assertEquals("Keep rests honest", copy.plan.notes)

        assertEquals(
            sourcePlan.exercises.map { it.plannedExercise.exerciseId },
            copy.exercises.map { it.plannedExercise.exerciseId }
        )
        assertEquals(listOf("Bench Press", "Plank"), copy.exercises.map { it.displayName })
        assertEquals(listOf(0, 1), copy.exercises.map { it.plannedExercise.order })
        assertEquals(
            listOf(listOf("barbell", "bench"), emptyList()),
            copy.exercises.map { it.plannedExercise.equipmentIds }
        )
        assertEquals(listOf(120, 60), copy.exercises.map { it.plannedExercise.restSeconds })
        assertEquals(
            listOf("Pause on the chest", "Brace"),
            copy.exercises.map { it.plannedExercise.notes }
        )

        val sets = copy.exercises.flatMap { it.sets }
        assertEquals(
            listOf(SetDraft(8, null, 60.0), SetDraft(6, null, 65.0), SetDraft(null, 45, null), SetDraft(null, 30, null)),
            sets.map { SetDraft(it.reps, it.seconds, it.weight) }
        )
        assertTrue(sets.all { it.actualReps == null && it.actualSeconds == null && it.actualWeight == null })
        assertTrue(sets.all { it.status == SetStatus.OPEN })
    }

    // The bench landed with three sets, one added mid-flight; the copy has the two planned ones, in order.
    @Test
    fun reflyDoesNotCopyAddedSets() = runBlocking {
        val (source, current, _) = liftFixture()
        assertEquals(3, flightPlanDao.getPlan(source)!!.exercises[0].sets.size)

        manager.refly(current)

        val benchSets = flightPlanDao.getPlan(current)!!.exercises[0].sets
        assertEquals(listOf(8, 6), benchSets.map { it.reps })
        assertEquals(listOf(0, 1), benchSets.map { it.order })
        assertTrue(benchSets.none { it.added })
    }

    @Test
    fun reflyClearsSkippedFlagsAndUserNotes() = runBlocking {
        val (source, current, _) = liftFixture()
        val sourcePlank = flightPlanDao.getPlan(source)!!.exercises[1]
        assertTrue(sourcePlank.plannedExercise.skipped)
        assertEquals("Back twinged", sourcePlank.plannedExercise.userNotes)

        manager.refly(current)

        val plank = flightPlanDao.getPlan(current)!!.exercises[1]
        assertFalse(plank.plannedExercise.skipped)
        assertNull(plank.plannedExercise.userNotes)
        assertTrue(plank.sets.all { it.status == SetStatus.OPEN })
    }

    @Test
    fun reflyCopiesRunFieldsAndSegments() = runBlocking {
        val missionId = insertActiveMission()
        val source = insertSortie(missionId, 0, SortieType.RUN, SortieState.LANDED, landedAt = 1_768_200_000_000)
        flightPlanDao.writePlan(source, runDraft)
        val current = insertSortie(missionId, 1, SortieType.RUN, SortieState.PENDING)

        manager.refly(current)

        val copy = flightPlanDao.getPlan(current)!!
        assertEquals("tempo", copy.plan.runKind)
        assertEquals(8.0, copy.plan.targetDistance!!, 0.0)
        assertEquals("5:00/km", copy.plan.targetPace)
        assertEquals(listOf(0, 1, 2), copy.segments.map { it.order })
        assertEquals(
            runDraft.segments,
            copy.segments.map { RunSegmentDraft(it.description, it.distance, it.minutes) }
        )
    }

    // Decision 4 (no provenance): source = REFLY and nothing naming the sortie it came from.
    @Test
    fun reflyMarksTheCopyReFlyAndMakesTheSortiePlanned() = runBlocking {
        val (source, current, _) = liftFixture()
        val sourceSortieBefore = sortieDao.get(source)!!
        val sourcePlanBefore = flightPlanDao.getPlan(source)!!
        val currentBefore = sortieDao.get(current)!!

        manager.refly(current)

        val copy = flightPlanDao.getPlan(current)!!
        assertEquals(FlightPlanSource.REFLY, copy.plan.source)
        assertEquals("{}", copy.plan.rawJson)
        assertEquals(currentBefore.copy(state = SortieState.PLANNED), sortieDao.get(current))

        assertEquals(SortieState.LANDED, sortieDao.get(source)!!.state)
        assertEquals(sourceSortieBefore, sortieDao.get(source))
        assertEquals(sourcePlanBefore, flightPlanDao.getPlan(source))
    }

    @Test
    fun reflyNeedsNoNetworkAndWritesNoGenerationRow() = runBlocking {
        // No daemon configured, nothing injected: the copy reads only Room.
        val (source, current, _) = liftFixture()

        manager.refly(current)

        assertNotNull(flightPlanDao.getPlan(current))
        assertEquals(0, generationCount())
        db.query(
            SimpleSQLiteQuery("SELECT COUNT(*) FROM generation WHERE sortieId IN ($source, $current)")
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun reflyRefusesASortieThatIsNotTheCurrentOne() = runBlocking {
        val (source, _, later) = liftFixture()
        val sourcePlanBefore = flightPlanDao.getPlan(source)!!

        assertRefused(source)
        assertEquals(SortieState.LANDED, sortieDao.get(source)!!.state)
        assertEquals(sourcePlanBefore, flightPlanDao.getPlan(source))

        assertRefused(later)
        assertEquals(SortieState.PENDING, sortieDao.get(later)!!.state)
        assertNull(flightPlanDao.getPlan(later))
    }

    @Test
    fun reflyRefusesASortieThatIsNotPending() = runBlocking {
        val (_, current, later) = liftFixture()

        for (state in listOf(SortieState.PLANNED, SortieState.IN_FLIGHT)) {
            val sortie = sortieDao.get(current)!!
            sortieDao.update(sortie.copy(state = state))
            flightPlanDao.writePlan(current, runDraft.copy(title = "Own plan"))
            val planBefore = flightPlanDao.getPlan(current)!!
            val missionId = sortie.missionId
            val sortiesBefore = sortieDao.getForMission(missionId)

            assertRefused(current)

            assertEquals(state, sortieDao.get(current)!!.state)
            assertEquals(planBefore, flightPlanDao.getPlan(current))
            assertEquals(sortiesBefore, sortieDao.getForMission(missionId))
            assertNull(flightPlanDao.getPlan(later))
        }
    }
}
