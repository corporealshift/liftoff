package com.liftoff.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachPromptBuilder
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.settings.WeightUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The loader's reads, against an in-memory database with the date, the zone and the window injected.
 *
 * 'Today' is fixed at 2026-10-29 (a Thursday) and the configured window at 28 days, so the window
 * edge is a date a reader can check by hand: 2026-10-01 is 28 days back and in, 2026-09-30 is 29 and
 * out. Each test asserts the input values the builder is then handed, which is what makes the prompt
 * come from the database rather than from a fixture written beside the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptInputLoaderTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var equipmentDao: EquipmentDao
    private lateinit var settingsStore: SettingsStore

    private val today = LocalDate.of(2026, 10, 29)

    /** This week's Monday, which is also the `weekStart` a timestamp-less sortie is dated by. */
    private val thisWeekStart = LocalDate.of(2026, 10, 26)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()
        equipmentDao = db.equipmentDao()

        // A name of its own per test, so a unit set in one test cannot read as the default in the next.
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "prompt_loader_${UUID.randomUUID()}.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)
        settingsStore.setObjectives("Get stronger at the basics")
        settingsStore.setConstraints("45 minutes at home, no pull-up bar")
        settingsStore.setSortieLengthMinutes(45)
        settingsStore.setHistoryWindowDays(28)
    }

    @After
    fun closeDb() {
        db.close()
    }

    /** The loader under test: the same fixed 'today' every time, so a window edge is a fixed date. */
    private fun loader(
        windowDays: Int? = null,
        zone: () -> ZoneId = { ZoneOffset.UTC },
    ) = PromptInputLoader(
        db, settingsStore,
        clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
        zone = zone,
        historyWindowDays = windowDays,
    )

    // ─── the window ─────────────────────────────────────────────────────────────

    /** Day 28 back is inside the window, day 29 back is outside it (§7.4 item 6). */
    @Test
    fun day28IsInsideTheWindowAndDay29IsOutside() = runBlocking {
        val current = bareThisWeek()
        // Both sorties are in one week's Mission, so what separates them is each sortie's own date.
        val pastId = insertMission(LocalDate.of(2026, 9, 28))
        insertSortie(pastId, 0, SortieType.LIFT, state = SortieState.LANDED, landedAt = millis("2026-10-01T07:30:00Z"))
        insertSortie(pastId, 1, SortieType.LIFT, state = SortieState.LANDED, landedAt = millis("2026-09-30T23:59:00Z"))

        val history = loader().outlineInput(current).history

        assertEquals(
            "2026-10-01 is 28 days before 2026-10-29 and must be in; 2026-09-30 is 29 and must be out",
            listOf(LocalDate.of(2026, 10, 1)),
            history.map { it.date },
        )
    }

    /** A window passed to the loader replaces the one the owner configured in settings. */
    @Test
    fun customWindowOverridesTheConfiguredHistoryWindow() = runBlocking {
        val current = bareThisWeek()
        val recentId = insertMission(LocalDate.of(2026, 10, 19))
        insertSortie(recentId, 0, SortieType.RUN, state = SortieState.LANDED, landedAt = millis("2026-10-20T06:00:00Z"))
        val olderId = insertMission(LocalDate.of(2026, 10, 5))
        insertSortie(olderId, 0, SortieType.LIFT, state = SortieState.LANDED, landedAt = millis("2026-10-05T06:00:00Z"))

        val configured = loader().outlineInput(current).history
        assertEquals("the configured 28-day window holds both", 2, configured.size)

        // The older sortie is 24 days back: inside the configured 28, outside the injected 14.
        val injected = loader(windowDays = 14).outlineInput(current).history
        assertEquals(
            listOf(LocalDate.of(2026, 10, 20)),
            injected.map { it.date },
        )
    }

    /** Newest first whatever order the rows came back in, with the tie-break the plan settled (decision 13). */
    @Test
    fun historyIsNewestFirst() = runBlocking {
        val current = bareThisWeek()
        val olderWeek = insertMission(LocalDate.of(2026, 10, 12))
        insertSortie(olderWeek, 0, SortieType.RUN, focus = "older run", state = SortieState.LANDED, landedAt = millis("2026-10-18T15:00:00Z"))
        val lastWeek = insertMission(LocalDate.of(2026, 10, 19))
        insertSortie(lastWeek, 0, SortieType.LIFT, focus = "morning lift", state = SortieState.LANDED, landedAt = millis("2026-10-19T06:00:00Z"))
        insertSortie(lastWeek, 1, SortieType.RUN, focus = "evening run", state = SortieState.LANDED, landedAt = millis("2026-10-19T20:00:00Z"))

        val history = loader().outlineInput(current).history

        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 19) to "evening run",
                // Two sorties on one day read index-descending, the order the owner did them in.
                LocalDate.of(2026, 10, 19) to "morning lift",
                LocalDate.of(2026, 10, 18) to "older run",
            ),
            history.map { it.date to it.title },
        )
    }

    /** An inactive item is not in the equipment list the coach may take ids from. */
    @Test
    fun inactiveEquipmentIsLeftOutOfTheEquipmentList() = runBlocking {
        val barbellId = equipmentDao.add(Equipment(key = "bb", name = "barbell", notes = "20 lb bar, plates to 45 lb"))
        equipmentDao.add(Equipment(key = "db", name = "dumbbells", notes = ""))
        val kettlebellId = equipmentDao.add(Equipment(key = "kb", name = "kettlebell", notes = "one 16 kg bell"))
        equipmentDao.deactivate(kettlebellId)

        val equipment = loader().outlineInput(bareThisWeek()).equipment

        assertEquals(listOf("bb", "db"), equipment.map { it.key })
        assertEquals("20 lb bar, plates to 45 lb", equipment.first { it.key == "bb" }.notes)
        // An active item with no notes reads as no note, which is what the builder writes as two fields.
        assertEquals("", equipment.first { it.key == "db" }.notes)
        assertFalse("an inactive item reached the prompt", equipment.any { it.key == "kb" })
        assertFalse("the inactive item's note reached the prompt", equipment.any { it.notes.contains("16 kg") })
        assertTrue(barbellId > 0 && kettlebellId > 0)
    }

    // ─── the inputs the builder is handed ───────────────────────────────────────

    /** The OUTLINE input: the profile from settings, the active equipment, and this week from the rows. */
    @Test
    fun outlineInputReadsProfileEquipmentAndWeekFromTheDatabase() = runBlocking {
        settingsStore.setWeightUnit(WeightUnit.KG)
        settingsStore.setDistanceUnit(DistanceUnit.KM)
        equipmentDao.add(Equipment(key = "bb", name = "barbell", notes = "20 lb bar, plates to 45 lb"))
        equipmentDao.add(Equipment(key = "kb", name = "kettlebell", notes = ""))

        val missionId = currentMission()
        val week = sortieDao.getForMission(missionId)
        assertEquals(5, week.size)

        val input = loader().outlineInput(missionId)

        assertEquals(GenerationKind.OUTLINE, input.kind)
        assertNull("an OUTLINE writes the week, not one sortie", input.sortieIndex)
        assertNull(input.plannedSortie)

        assertEquals("Get stronger at the basics", input.profile.objectives)
        assertEquals("45 minutes at home, no pull-up bar", input.profile.constraints)
        assertEquals(45, input.profile.sortieLengthMinutes)
        assertEquals(WeightUnit.KG, input.profile.weightUnit)
        assertEquals(DistanceUnit.KM, input.profile.distanceUnit)

        assertEquals(listOf("barbell", "kettlebell"), input.equipment.map { it.name })

        assertEquals("RLRLR", input.week.pattern)
        assertEquals(
            listOf(
                Triple(0, SortieType.RUN, SortieState.LANDED),
                Triple(1, SortieType.LIFT, SortieState.PENDING),
                Triple(2, SortieType.RUN, SortieState.PLANNED),
                Triple(3, SortieType.LIFT, SortieState.PENDING),
                Triple(4, SortieType.RUN, SortieState.IN_FLIGHT),
            ),
            input.week.sorties.map { Triple(it.index, it.type, it.state) },
        )
        assertEquals(listOf("Easy 5k", "Push + core"), input.week.sorties.mapNotNull { it.focus })
    }

    /** The FLIGHT_PLAN input says which sortie of the week is being written. */
    @Test
    fun flightPlanInputNamesTheSortieBeingPlanned() = runBlocking {
        val missionId = currentMission()

        val input = loader().flightPlanInput(missionId, 1)

        assertEquals(GenerationKind.FLIGHT_PLAN, input.kind)
        assertEquals(1, input.sortieIndex)
        assertEquals(1, input.plannedSortie?.index)
        assertEquals(SortieType.LIFT, input.plannedSortie?.type)
        assertEquals(SortieState.PENDING, input.plannedSortie?.state)
        assertEquals("Push + core", input.plannedSortie?.focus)
        // Naming one sortie does not narrow the week the coach is planning inside.
        assertEquals(5, input.week.sorties.size)

        val other = loader().flightPlanInput(missionId, 4)
        assertEquals(4, other.plannedSortie?.index)
        assertEquals(SortieType.RUN, other.plannedSortie?.type)
        assertNull(other.plannedSortie?.focus)
    }

    /** A Mission that has no sortie rows yet still has a week: its pattern, every position pending. */
    @Test
    fun missionWithNoSortieRowsReadsTheWeekFromItsPattern() = runBlocking {
        val missionId = insertMission(thisWeekStart, pattern = "RLR", status = MissionStatus.DRAFT)

        val week = loader().outlineInput(missionId).week

        assertEquals("RLR", week.pattern)
        assertEquals(
            listOf(SortieType.RUN, SortieType.LIFT, SortieType.RUN),
            week.sorties.map { it.type },
        )
        assertEquals(listOf(0, 1, 2), week.sorties.map { it.index })
        assertEquals(
            listOf(SortieState.PENDING, SortieState.PENDING, SortieState.PENDING),
            week.sorties.map { it.state },
        )
        assertTrue("a draft carries no focus to anchor the coach on", week.sorties.all { it.focus == null })
    }

    // ─── what a history block is built from ─────────────────────────────────────

    /** A landed sortie's history carries the sets as done and the owner's notes, not the coach's. */
    @Test
    fun historyCarriesActualSetsAndExerciseNotes() = runBlocking {
        val current = bareThisWeek()
        val missionId = insertMission(LocalDate.of(2026, 10, 19))
        val sortieId = insertSortie(
            missionId, 0, SortieType.LIFT, state = SortieState.LANDED,
            landedAt = millis("2026-10-20T10:00:00Z"),
            notes = "left shoulder twinge on last set",
        )
        flightPlanDao.writePlan(
            sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED,
                title = "Legs + pull",
                estimatedMinutes = 45,
                warmup = null,
                notes = "coach: keep the tempo slow",
                runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(
                        name = "Back squat", equipmentIds = listOf("bb"), restSeconds = null,
                        notes = "coach: brace hard",
                        sets = listOf(
                            SetDraft(reps = 8, seconds = null, weight = 135.0),
                            SetDraft(reps = 8, seconds = null, weight = 135.0),
                            SetDraft(reps = 8, seconds = null, weight = 135.0),
                        ),
                    ),
                    ExerciseDraft(
                        name = "Barbell row", equipmentIds = listOf("bb"), restSeconds = null,
                        notes = null,
                        sets = listOf(
                            SetDraft(reps = 10, seconds = null, weight = 95.0),
                            SetDraft(reps = 10, seconds = null, weight = 95.0),
                        ),
                    ),
                ),
                segments = emptyList(),
            )
        )
        val detail = flightPlanDao.getPlan(sortieId)!!
        val squatSets = detail.exercises[0].sets
        val rowSets = detail.exercises[1].sets
        flightPlanDao.updateSetActuals(squatSets[0].id, 8, null, 135.0, SetStatus.DONE)
        flightPlanDao.updateSetActuals(squatSets[1].id, 8, null, 135.0, SetStatus.DONE)
        // The third set was done for fewer reps than the coach proposed.
        flightPlanDao.updateSetActuals(squatSets[2].id, 6, null, 135.0, SetStatus.DONE)
        flightPlanDao.updateSetActuals(rowSets[0].id, 10, null, 95.0, SetStatus.DONE)
        flightPlanDao.skipSet(rowSets[1].id)
        flightPlanDao.saveExerciseNote(detail.exercises[1].plannedExercise.id, "lower back tired")

        val input = loader().outlineInput(current)
        val history = input.history

        assertEquals(1, history.size)
        val block = history[0]
        assertEquals(LocalDate.of(2026, 10, 20), block.date)
        assertEquals("Legs + pull", block.title)
        assertEquals(SortieState.LANDED, block.state)
        assertEquals("left shoulder twinge on last set", block.notes)

        val squat = block.exercises[0]
        assertEquals("Back squat", squat.name)
        assertNull(squat.note)
        assertEquals(
            listOf(
                Triple(8, 8, 135.0),
                Triple(8, 8, 135.0),
                // The actual is what the block reads on; the planned value is what a deviation names.
                Triple(6, 8, 135.0),
            ),
            squat.sets.map { Triple(it.reps, it.plannedReps, it.weight) },
        )
        assertTrue(squat.sets.all { it.status == SetStatus.DONE })

        val row = block.exercises[1]
        assertEquals("lower back tired", row.note)
        assertEquals(listOf(SetStatus.DONE, SetStatus.SKIPPED), row.sets.map { it.status })
        assertEquals(listOf(10, null), row.sets.map { it.reps })
        assertEquals(listOf(95.0, 95.0), row.sets.map { it.plannedWeight })

        // The same input handed to the builder: the coach's own words must not come back at it.
        val rendered = CoachPromptBuilder.prompt(input)
        assertTrue("the history block did not reach the prompt", rendered.contains("Legs + pull"))
        assertFalse("the coach's Flight Plan note was echoed back", rendered.contains("keep the tempo slow"))
        assertFalse("the coach's exercise note was echoed back", rendered.contains("brace hard"))
    }

    /** A run's actuals are its distance and duration (§5.3), read off the Sortie row. */
    @Test
    fun runSortieCarriesDistanceAndMinutes() = runBlocking {
        val current = bareThisWeek()
        val missionId = insertMission(LocalDate.of(2026, 10, 19))
        insertSortie(
            missionId, 0, SortieType.RUN, state = SortieState.LANDED,
            landedAt = millis("2026-10-19T18:00:00Z"),
            runDistance = 3.1, runMinutes = 28.0,
        )
        val liftMissionId = insertMission(LocalDate.of(2026, 10, 12))
        insertSortie(
            liftMissionId, 0, SortieType.LIFT, state = SortieState.LANDED,
            landedAt = millis("2026-10-12T09:00:00Z"),
        )

        val history = loader().outlineInput(current).history

        assertEquals(listOf(SortieType.RUN, SortieType.LIFT), history.map { it.type })
        assertEquals(3.1, history[0].runDistance!!, 0.0)
        assertEquals(28.0, history[0].runMinutes!!, 0.0)
        assertNull("a lift has no distance or duration", history[1].runDistance)
        assertNull(history[1].runMinutes)
    }

    /** The date is landedAt read in the injected zone — not the week, and not assumed to be UTC. */
    @Test
    fun landedSortieDateComesFromLandedAtInTheInjectedZone() = runBlocking {
        // Landed late on the Sunday of its own week, so its weekStart (2026-09-28, 31 days back) is
        // neither the date printed nor the date the window counts by.
        val current = bareThisWeek()
        val missionId = insertMission(LocalDate.of(2026, 9, 28))
        insertSortie(missionId, 0, SortieType.LIFT, state = SortieState.LANDED, landedAt = millis("2026-10-04T22:30:00Z"))

        val utc = loader().outlineInput(current).history
        assertEquals(listOf(LocalDate.of(2026, 10, 4)), utc.map { it.date })

        // The same instant is the next day nine hours east, so the zone is what the date is read in.
        val tokyo = loader(zone = { ZoneId.of("Asia/Tokyo") }).outlineInput(current).history
        assertEquals(listOf(LocalDate.of(2026, 10, 5)), tokyo.map { it.date })
    }

    /**
     * A scrub records no timestamp (plan decision 1), so it is dated by its Mission's weekStart —
     * which is also the date the window counts it in or out by.
     */
    @Test
    fun scrubbedSortieWithoutATimestampFallsBackToItsWeekStart() = runBlocking {
        val current = bareThisWeek()
        val recentId = insertMission(LocalDate.of(2026, 10, 19))
        insertSortie(recentId, 0, SortieType.LIFT, state = SortieState.SCRUBBED, scrubReason = "week ended")
        // The same kind of row, five weeks back: its fallback date puts it outside the window.
        val longAgoId = insertMission(LocalDate.of(2026, 9, 21))
        insertSortie(longAgoId, 0, SortieType.RUN, state = SortieState.SCRUBBED, scrubReason = "week ended")

        val history = loader().outlineInput(current).history

        assertEquals(1, history.size)
        assertEquals(LocalDate.of(2026, 10, 19), history[0].date)
        assertEquals(SortieState.SCRUBBED, history[0].state)
        assertEquals("week ended", history[0].scrubReason)
    }

    // ─── fixtures ───────────────────────────────────────────────────────────────

    /**
     * This week's Mission with no sortie rows, so it adds nothing to the history the tests are
     * counting. A finished sortie here would be dated by this week's `weekStart` and read as history.
     */
    private suspend fun bareThisWeek(): Long =
        insertMission(thisWeekStart, pattern = "RLRLR", status = MissionStatus.ACTIVE)

    /** This week's Mission, with its five sortie rows already confirmed. */
    private suspend fun currentMission(): Long {
        val missionId = bareThisWeek()
        sortieDao.insertAll(
            listOf(
                sortieRow(missionId, 0, SortieType.RUN, "Easy 5k", SortieState.LANDED),
                sortieRow(missionId, 1, SortieType.LIFT, "Push + core", SortieState.PENDING),
                sortieRow(missionId, 2, SortieType.RUN, null, SortieState.PLANNED),
                sortieRow(missionId, 3, SortieType.LIFT, null, SortieState.PENDING),
                sortieRow(missionId, 4, SortieType.RUN, null, SortieState.IN_FLIGHT),
            )
        )
        return missionId
    }

    private suspend fun insertMission(
        weekStart: LocalDate,
        pattern: String = "RLRLR",
        status: MissionStatus = MissionStatus.ACTIVE,
    ): Long = missionDao.insert(Mission(weekStart = weekStart, pattern = pattern, status = status, outlineNotes = null))

    private suspend fun insertSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        focus: String? = null,
        state: SortieState,
        launchedAt: Long? = null,
        landedAt: Long? = null,
        scrubReason: String? = null,
        notes: String? = null,
        runDistance: Double? = null,
        runMinutes: Double? = null,
    ): Long = sortieDao.insert(
        sortieRow(missionId, index, type, focus, state, launchedAt, landedAt, scrubReason, notes, runDistance, runMinutes)
    )

    private fun sortieRow(
        missionId: Long,
        index: Int,
        type: SortieType,
        focus: String?,
        state: SortieState,
        launchedAt: Long? = null,
        landedAt: Long? = null,
        scrubReason: String? = null,
        notes: String? = null,
        runDistance: Double? = null,
        runMinutes: Double? = null,
    ) = Sortie(
        missionId = missionId, index = index, type = type, focus = focus,
        focusRationale = null, state = state, launchedAt = launchedAt, landedAt = landedAt,
        scrubReason = scrubReason, notes = notes, runDistance = runDistance, runMinutes = runMinutes,
    )

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()
}
