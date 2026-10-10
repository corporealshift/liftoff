package com.liftoff.app.ui.mission

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionStatesTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private lateinit var states: MissionStates

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
        states = MissionStates(db, manager)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    // ── Pattern chips from mixed sortie states ────────────────────────

    @Test
    fun patternChipsFromMixedSortieStates() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RLRLR"))

        sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "sick", notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 2, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 3, type = SortieType.LIFT, focus = "Squat",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 4, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals(5, week.chips.size)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Landed, week.chips[0].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Scrubbed, week.chips[1].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Current, week.chips[2].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Upcoming, week.chips[3].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Upcoming, week.chips[4].state)
    }

    // ── Outline notes present ────────────────────────────────────────

    @Test
    fun outlineNotesPresent() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RL",
            outlineNotes = "Focus on form this week."))

        sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals("Focus on form this week.", week.outlineNotes)
    }

    // ── Outline notes absent ─────────────────────────────────────────

    @Test
    fun outlineNotesAbsent() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RL",
            outlineNotes = null))

        sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertTrue(week.outlineNotes.isNullOrEmpty())
    }

    // ── Sortie rows show index, type, focus, state ───────────────────

    @Test
    fun sortieRowsShowIndexTypeFocusState() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RLR"))

        val id0 = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = "Easy",
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        val id1 = sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Full body",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 2, type = SortieType.RUN, focus = "Tempo",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals(3, week.rows.size)

        assertEquals(id0, week.rows[0].id)
        assertEquals(0, week.rows[0].index)
        assertEquals(SortieType.RUN, week.rows[0].type)
        assertEquals("Easy", week.rows[0].focus)
        assertEquals("LANDED", week.rows[0].stateLabel)
        assertEquals("Run · Easy", week.rows[0].title)

        assertEquals(id1, week.rows[1].id)
        assertEquals(1, week.rows[1].index)
        assertEquals(SortieType.LIFT, week.rows[1].type)
        assertEquals("Full body", week.rows[1].focus)
        assertEquals("PLANNED", week.rows[1].stateLabel)
        assertEquals("Lift · Full body", week.rows[1].title)

        assertEquals(2, week.rows[2].index)
        assertEquals(SortieType.RUN, week.rows[2].type)
        assertEquals("Tempo", week.rows[2].focus)
        assertEquals("PENDING", week.rows[2].stateLabel)
        assertEquals("Run · Tempo", week.rows[2].title)
    }

    // ── Draft mission has no rows ────────────────────────────────────

    @Test
    fun draftMissionHasNoRows() = runBlocking {
        manager.onAppOpen()

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals(MissionStatus.DRAFT, week.status)
        assertTrue(week.rows.isEmpty())
    }

    // ── Planned sortie detail shows flight plan ──────────────────────

    @Test
    fun plannedSortieDetailShowsFlightPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Body", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = 135.0))
                )
            ),
            segments = emptyList()
        ))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.WithPlan)
        val withPlan = detail as SortieDetailState.WithPlan
        assertEquals("Upper Body", withPlan.title)
        assertEquals(SortieType.LIFT, withPlan.sortieType)
        assertEquals(1, withPlan.plan.exercises.size)
    }

    // ── Pending sortie without plan shows no plan ────────────────────

    @Test
    fun pendingSortieWithoutPlanShowsNoPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.NoPlan)
        val noPlan = detail as SortieDetailState.NoPlan
        assertEquals("Bench", noPlan.title)
    }

    // ── Pending sortie with plan shows flight plan ───────────────────

    @Test
    fun pendingSortieWithPlanShowsFlightPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Bench Day", estimatedMinutes = 30,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = emptyList(), segments = emptyList()
        ))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.WithPlan)
        val withPlan = detail as SortieDetailState.WithPlan
        assertEquals("Bench Day", withPlan.title)
    }

    // ── Landed sortie detail shows record ────────────────────────────

    @Test
    fun landedSortieDetailShowsRecord() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 1705000000000L,
            scrubReason = null, notes = "Good pace", runDistance = 3.2, runMinutes = 18.5))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Landed)
        val landed = detail as SortieDetailState.Landed
        assertEquals(SortieType.RUN, landed.sortieType)
        landed.runDistance?.let { assertEquals(3.2, it, 0.01) }
        landed.runMinutes?.let { assertEquals(18.5, it, 0.01) }
        assertEquals("Good pace", landed.notes)
    }

    // ── Scrubbed sortie detail shows reason ──────────────────────────

    @Test
    fun scrubbedSortieDetailShowsReason() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "sick", notes = null, runDistance = null, runMinutes = null))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Scrubbed)
        val scrubbed = detail as SortieDetailState.Scrubbed
        assertEquals("sick", scrubbed.reason)
        assertEquals(SortieType.LIFT, scrubbed.sortieType)
    }

    // ── Landed sortie with multi-set plan shows correct set counts ───

    @Test
    fun landedSortieWithMultiSetPlanShowsCorrectCount() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        // 3 exercises × 3 sets each = 9 total sets; mark 7 as DONE.
        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Full body",
            focusRationale = null, state = SortieState.LANDED, launchedAt = System.currentTimeMillis(),
            landedAt = 1705000000000L, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Full Body", estimatedMinutes = 55,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Squat", equipmentIds = emptyList(), restSeconds = 120, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Row", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 10, seconds = null, weight = 95.0),
                        SetDraft(reps = 10, seconds = null, weight = 95.0),
                        SetDraft(reps = 8, seconds = null, weight = 115.0)
                    )
                )
            ),
            segments = emptyList()
        ))

        // Mark 7 of the 9 sets as DONE.
        for (exercise in flightPlanDao.getPlan(sortieId)!!.exercises) {
            val sets = exercise.sets
            // First two exercises: all 3 sets DONE; third exercise: first set DONE, last 2 OPEN.
            if (exercise.plannedExercise.order < 2) {
                sets.forEach { s -> db.flightPlanDao().updateSetActuals(s.id, s.reps, s.seconds, s.weight, SetStatus.DONE) }
            } else {
                db.flightPlanDao().updateSetActuals(sets[0].id, sets[0].reps, sets[0].seconds, sets[0].weight, SetStatus.DONE)
            }
        }

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Landed)
        val landed = detail as SortieDetailState.Landed
        // planExerciseCount must be total sets (9), not exercise count (3).
        assertEquals(9, landed.planExerciseCount)
        assertEquals(7, landed.planLandedCount)
    }

    // ── Scrubbed sortie with multi-set plan shows correct set counts ─

    @Test
    fun scrubbedSortieWithMultiSetPlanShowsCorrectCount() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        // 2 exercises × 4 sets each = 8 total sets; mark 3 as DONE.
        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "wrist pain", notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Body", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Overhead Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 95.0),
                        SetDraft(reps = 8, seconds = null, weight = 95.0),
                        SetDraft(reps = 6, seconds = null, weight = 105.0),
                        SetDraft(reps = 6, seconds = null, weight = 105.0)
                    )
                )
            ),
            segments = emptyList()
        ))

        // Mark 3 of the 8 sets as DONE (first exercise: first 2 sets; second exercise: first set).
        val plan = flightPlanDao.getPlan(sortieId)!!
        db.flightPlanDao().updateSetActuals(plan.exercises[0].sets[0].id, null, null, null, SetStatus.DONE)
        db.flightPlanDao().updateSetActuals(plan.exercises[0].sets[1].id, null, null, null, SetStatus.DONE)
        db.flightPlanDao().updateSetActuals(plan.exercises[1].sets[0].id, null, null, null, SetStatus.DONE)

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Scrubbed)
        val scrubbed = detail as SortieDetailState.Scrubbed
        // planExerciseCount must be total sets (8), not exercise count (2).
        assertEquals(8, scrubbed.planExerciseCount)
        assertEquals(3, scrubbed.planLandedCount)
    }
}
