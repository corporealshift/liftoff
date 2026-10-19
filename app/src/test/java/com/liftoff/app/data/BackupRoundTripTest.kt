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
 * The brief's headline: export one in-memory database, import the bytes into a second fresh one, and compare
 * every row of all nine tables, ids included.
 *
 * The seed is placed so the import's `onAppOpen()` has nothing to roll over or create — the ACTIVE Mission in
 * the clock's current week, the CLOSED ones in past weeks, the DRAFT in the following week — or the two
 * databases would differ for a reason that is not a row going missing on the way out and back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {

    @get:Rule
    val storage = TemporaryFolder()

    private lateinit var source: LiftoffDatabase
    private lateinit var target: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore

    // A Monday, so the clock's current week is the week the ACTIVE Mission sits in.
    private val clock = Clock.fixed(Instant.parse("2026-01-12T09:00:00Z"), ZoneOffset.UTC)
    private val weekBefore = LocalDate.of(2025, 12, 29)
    private val lastWeek = LocalDate.of(2026, 1, 5)
    private val thisWeek = LocalDate.of(2026, 1, 12)
    private val nextWeek = LocalDate.of(2026, 1, 19)

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
        target.close()
        source.close()
    }

    private fun newDatabase(): LiftoffDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        weekStart: LocalDate,
        pattern: String,
        status: MissionStatus,
        outlineNotes: String?,
    ): Long = source.missionDao().insert(
        Mission(weekStart = weekStart, pattern = pattern, status = status, outlineNotes = outlineNotes)
    )

    private suspend fun seedSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        state: SortieState,
        focus: String? = null,
        focusRationale: String? = null,
        launchedAt: Long? = null,
        landedAt: Long? = null,
        scrubReason: String? = null,
        notes: String? = null,
        runDistance: Double? = null,
        runMinutes: Double? = null,
    ): Long = source.sortieDao().insert(
        Sortie(
            missionId = missionId, index = index, type = type, focus = focus,
            focusRationale = focusRationale, state = state, launchedAt = launchedAt,
            landedAt = landedAt, scrubReason = scrubReason, notes = notes,
            runDistance = runDistance, runMinutes = runMinutes
        )
    )

    private suspend fun seedPlan(
        sortieId: Long,
        source: FlightPlanSource,
        title: String,
        estimatedMinutes: Int?,
        warmup: String?,
        notes: String?,
        runKind: String?,
        targetDistance: Double?,
        targetPace: String?,
        rawJson: String,
    ): Long = this.source.flightPlanDao().insertPlan(
        FlightPlan(
            sortieId = sortieId, source = source, title = title,
            estimatedMinutes = estimatedMinutes, warmup = warmup, notes = notes,
            runKind = runKind, targetDistance = targetDistance, targetPace = targetPace,
            rawJson = rawJson
        )
    )

    private suspend fun seedPlannedExercise(
        flightPlanId: Long,
        order: Int,
        exerciseId: Long,
        equipmentIds: List<String>,
        restSeconds: Int?,
        notes: String?,
        skipped: Boolean,
        userNotes: String?,
    ): Long = source.flightPlanDao().insertExercise(
        PlannedExercise(
            flightPlanId = flightPlanId, `order` = order, exerciseId = exerciseId,
            equipmentIds = equipmentIds, restSeconds = restSeconds, notes = notes,
            skipped = skipped, userNotes = userNotes
        )
    )

    private suspend fun seedSet(
        plannedExerciseId: Long,
        order: Int,
        reps: Int?,
        seconds: Int?,
        weight: Double?,
        actualReps: Int? = null,
        actualSeconds: Int? = null,
        actualWeight: Double? = null,
        status: SetStatus = SetStatus.OPEN,
    ): Long = source.flightPlanDao().insertSet(
        PlannedSet(
            plannedExerciseId = plannedExerciseId, `order` = order, reps = reps, seconds = seconds,
            weight = weight, actualReps = actualReps, actualSeconds = actualSeconds,
            actualWeight = actualWeight, status = status, added = false
        )
    )

    /**
     * The brief's seed, in foreign-key order: Missions in each status, sorties in each state, a lift plan and
     * a run plan with recorded actuals and an added set, exercises, generations, and equipment both active
     * and inactive.
     */
    private suspend fun seedTheBriefsSeed() {
        val closedTwoWeeksAgo = seedMission(weekBefore, "RLR", MissionStatus.CLOSED, "squat week")
        val closedLastWeek = seedMission(lastWeek, "RLL", MissionStatus.CLOSED, null)
        val currentWeek = seedMission(thisWeek, "RLRLR", MissionStatus.ACTIVE, "deload week")
        seedMission(nextWeek, "RLRLR", MissionStatus.DRAFT, null)

        val squat = source.exerciseDao().insert(Exercise(normalizedName = "squat", displayName = "Squat"))
        val bench = source.exerciseDao().insert(Exercise(normalizedName = "bench", displayName = "Bench press"))
        val row = source.exerciseDao().insert(Exercise(normalizedName = "barbell row", displayName = "Barbell Row"))

        source.equipmentDao().add(Equipment(key = "barbell", name = "Barbell", notes = "20 kg", active = true))
        source.equipmentDao().add(Equipment(key = "collar", name = "Collar", notes = "", active = true))
        source.equipmentDao().add(Equipment(key = "kettlebell", name = "Kettlebell", notes = "16 kg", active = false))
        source.equipmentDao().add(Equipment(key = "band", name = "Band", notes = "light", active = false))

        // Two weeks ago: a landed lift with a full plan, and a landed run with segments.
        val landedLift = seedSortie(
            closedTwoWeeksAgo, 0, SortieType.LIFT, SortieState.LANDED,
            focus = "squat", focusRationale = "progress",
            launchedAt = millis("2025-12-30T18:30:00Z"), landedAt = millis("2025-12-30T19:25:00Z"),
            notes = "felt strong"
        )
        val landedRun = seedSortie(
            closedTwoWeeksAgo, 1, SortieType.RUN, SortieState.LANDED,
            focus = "easy", launchedAt = millis("2026-01-02T07:00:00Z"), landedAt = millis("2026-01-02T07:28:00Z"),
            runDistance = 5.4, runMinutes = 27.75
        )
        val liftPlan = seedPlan(
            landedLift, FlightPlanSource.GENERATED, "Squat day", 45,
            warmup = "jog 5, then empty bar", notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = """{"title":"Squat day","sets":[{"reps":5,"weight":100.0}]}"""
        )
        val squatExercise = seedPlannedExercise(
            liftPlan, 0, squat, listOf("barbell", "collar"), 180,
            notes = "top set", skipped = false, userNotes = null
        )
        seedSet(squatExercise, 0, reps = 5, seconds = null, weight = 100.0, actualReps = 5, actualWeight = 100.0, status = SetStatus.DONE)
        seedSet(squatExercise, 1, reps = 5, seconds = null, weight = 105.0, actualReps = 4, actualWeight = 102.5, status = SetStatus.DONE)
        seedSet(squatExercise, 2, reps = null, seconds = 45, weight = null, actualSeconds = 42, status = SetStatus.DONE)
        // The added set, through the same DAO path the In-Flight screen uses.
        source.flightPlanDao().addExtraSet(squatExercise, reps = 3, seconds = null, weight = 110.0)
        val skippedExercise = seedPlannedExercise(
            liftPlan, 1, bench, emptyList(), null,
            notes = null, skipped = true, userNotes = "elbow hurt"
        )
        seedSet(skippedExercise, 0, reps = 8, seconds = null, weight = 60.0, status = SetStatus.SKIPPED)

        val runPlan = seedPlan(
            landedRun, FlightPlanSource.SIMPLE_RUN, "Easy run", 30,
            warmup = null, notes = "conversational", runKind = "easy", targetDistance = 5.0,
            targetPace = "6:00/km", rawJson = "{}"
        )
        source.flightPlanDao().insertSegment(
            RunSegment(flightPlanId = runPlan, `order` = 0, description = "1 km warm-up", distance = 1.0, minutes = 6.5)
        )
        source.flightPlanDao().insertSegment(
            RunSegment(flightPlanId = runPlan, `order` = 1, description = "3 km steady", distance = 3.0, minutes = 18.0)
        )
        source.flightPlanDao().insertSegment(
            RunSegment(flightPlanId = runPlan, `order` = 2, description = "cool-down", distance = 1.0, minutes = 3.25)
        )

        // Last week: a scrubbed lift and a landed run, with no plan under the scrubbed one.
        seedSortie(
            closedLastWeek, 0, SortieType.LIFT, SortieState.SCRUBBED,
            focus = "bench", scrubReason = "week ended"
        )
        seedSortie(
            closedLastWeek, 1, SortieType.RUN, SortieState.LANDED,
            launchedAt = millis("2026-01-08T07:10:00Z"), landedAt = millis("2026-01-08T07:28:00Z"),
            runDistance = 3.2, runMinutes = 18.0
        )

        // This week: one sortie in each of the three states a live week holds.
        val inFlightLift = seedSortie(
            currentWeek, 0, SortieType.LIFT, SortieState.IN_FLIGHT,
            focus = "row", focusRationale = "back day", launchedAt = millis("2026-01-12T08:55:00Z")
        )
        seedSortie(currentWeek, 1, SortieType.RUN, SortieState.PENDING)
        val plannedLift = seedSortie(currentWeek, 2, SortieType.LIFT, SortieState.PLANNED, focus = "squat")

        val inFlightPlan = seedPlan(
            inFlightLift, FlightPlanSource.REFLY, "Re-fly: Squat day", 50,
            warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null,
            rawJson = "{\"sets\":[]}"
        )
        val rowExercise = seedPlannedExercise(
            inFlightPlan, 0, row, listOf("collar"), 90,
            notes = null, skipped = false, userNotes = null
        )
        seedSet(rowExercise, 0, reps = 10, seconds = null, weight = 40.0)
        seedSet(rowExercise, 1, reps = null, seconds = 45, weight = null)
        // A plan with nothing under it, which is what a generated plan looks like before its rows arrive.
        seedPlan(
            plannedLift, FlightPlanSource.GENERATED, "Squat day", 40,
            warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null,
            rawJson = "{\"sets\":[]}"
        )

        source.generationDao().insert(
            Generation(
                kind = GenerationKind.FLIGHT_PLAN, missionId = closedTwoWeeksAgo, sortieId = landedLift,
                status = GenerationStatus.SUCCEEDED, nabuSessionId = "nabu-7", lastEventId = "evt-12",
                attempt = 1, error = null,
                createdAt = millis("2025-12-29T20:00:00Z"), finishedAt = millis("2025-12-29T20:01:30Z")
            )
        )
        source.generationDao().insert(
            Generation(
                kind = GenerationKind.OUTLINE, missionId = currentWeek, sortieId = null,
                status = GenerationStatus.FAILED, nabuSessionId = null, lastEventId = null,
                attempt = 2, error = "daemon unreachable",
                createdAt = millis("2026-01-12T06:00:00Z"), finishedAt = millis("2026-01-12T06:00:20Z")
            )
        )
        source.generationDao().insert(
            Generation(
                kind = GenerationKind.FLIGHT_PLAN, missionId = currentWeek, sortieId = plannedLift,
                status = GenerationStatus.QUEUED, nabuSessionId = "nabu-8", lastEventId = null,
                attempt = 0, error = null, createdAt = millis("2026-01-12T08:00:00Z"), finishedAt = null
            )
        )
    }

    private fun millis(instant: String): Long = Instant.parse(instant).toEpochMilli()

    /** Every row of every table, in the order the export reads them. */
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
     * One table read out of both databases. The seed has to hold rows here, or an empty table comparing equal
     * to an empty table would say nothing about the round trip.
     */
    private fun <T> compareTable(label: String, from: List<T>, into: List<T>, idOf: (T) -> Long) {
        assertTrue("$label: the seed put no row in this table", from.isNotEmpty())
        assertEquals("$label: a different number of rows came back", from.size, into.size)
        assertEquals("$label: the ids are not the file's ids", from.map(idOf), into.map(idOf))
        assertEquals("$label: the rows are not identical", from, into)
    }

    // The brief's seed, so the coverage the Done-when names is checked rather than assumed.
    private fun assertTheSeedIsTheBriefsSeed(seed: Snapshot) {
        assertEquals(
            "a Mission per status",
            setOf(MissionStatus.DRAFT, MissionStatus.ACTIVE, MissionStatus.CLOSED),
            seed.missions.map { it.status }.toSet()
        )
        assertEquals(
            "the ACTIVE Mission is in the clock's current week, CLOSED ones in past weeks, the DRAFT in the next",
            listOf(
                MissionStatus.CLOSED to weekBefore,
                MissionStatus.CLOSED to lastWeek,
                MissionStatus.ACTIVE to thisWeek,
                MissionStatus.DRAFT to nextWeek
            ),
            seed.missions.map { it.status to it.weekStart }
        )
        assertEquals(
            "a sortie per state",
            setOf(
                SortieState.PENDING, SortieState.PLANNED, SortieState.IN_FLIGHT,
                SortieState.LANDED, SortieState.SCRUBBED
            ),
            seed.sorties.map { it.state }.toSet()
        )
        val liftSorties = seed.sorties.filter { it.type == SortieType.LIFT }.map { it.id }.toSet()
        val runSorties = seed.sorties.filter { it.type == SortieType.RUN }.map { it.id }.toSet()
        assertTrue("no lift plan", seed.plans.any { it.sortieId in liftSorties })
        assertTrue("no run plan", seed.plans.any { it.sortieId in runSorties })
        assertTrue("no run segments on the run plan", seed.runSegments.isNotEmpty())
        assertTrue("no recorded set actuals", seed.plannedSets.any { it.actualReps != null || it.actualSeconds != null })
        assertTrue("no added set", seed.plannedSets.any { it.added })
        assertTrue("no recorded run actuals", seed.sorties.any { it.runDistance != null && it.runMinutes != null })
        assertTrue("no generations", seed.generations.isNotEmpty())
        assertTrue("no active equipment", seed.equipment.any { it.active })
        assertTrue("no inactive equipment", seed.equipment.any { !it.active })
    }

    // The brief's Done-when, first bullet: export and then import into a new in-memory database gives an
    // identical database — every row in every table compares equal, ids included.
    @Test
    fun exportThenImportIntoAFreshDatabaseGivesAnIdenticalDatabase() = runBlocking<Unit> {
        withTimeout(BOUND_MILLIS) {
            seedTheBriefsSeed()
            val before = snapshotOf(source)
            assertTheSeedIsTheBriefsSeed(before)

            val file = exportOf(source)
            val result = importDatabase(target, ByteArrayInputStream(file), managerFor(target))
            assertEquals(
                "the import reported the wrong counts",
                ImportResult.Restored(missions = before.missions.size, sorties = before.sorties.size),
                result
            )

            val after = snapshotOf(target)
            compareTable("mission", before.missions, after.missions) { it.id }
            compareTable("sortie", before.sorties, after.sorties) { it.id }
            compareTable("flightPlan", before.plans, after.plans) { it.id }
            compareTable("plannedExercise", before.plannedExercises, after.plannedExercises) { it.id }
            compareTable("plannedSet", before.plannedSets, after.plannedSets) { it.id }
            compareTable("runSegment", before.runSegments, after.runSegments) { it.id }
            compareTable("exercise", before.exercises, after.exercises) { it.id }
            compareTable("generation", before.generations, after.generations) { it.id }
            compareTable("equipment", before.equipment, after.equipment) { it.id }

            assertEquals("the whole database", before, after)
        }
    }

    private companion object {
        // Every wait in this class is bounded, as the brief requires.
        const val BOUND_MILLIS = 30_000L
    }
}
