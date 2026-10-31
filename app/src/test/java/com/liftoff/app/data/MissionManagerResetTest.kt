package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class MissionManagerResetTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var manager: MissionManager

    // 2026-01-14 is a Wednesday, so the current week starts Monday 2026-01-12.
    private val weekStart = LocalDate.of(2026, 1, 12)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
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

    private fun insertMission(status: MissionStatus, week: LocalDate = weekStart): Long = runBlocking {
        db.missionDao().insert(Mission(weekStart = week, pattern = "RLL", status = status, outlineNotes = "notes"))
    }

    private fun insertSortie(missionId: Long, index: Int, type: SortieType, state: SortieState): Long = runBlocking {
        db.sortieDao().insert(Sortie(
            missionId = missionId, index = index, type = type, focus = "focus",
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
    }

    private fun insertGeneration(
        missionId: Long, kind: GenerationKind, status: GenerationStatus, sortieId: Long? = null
    ): Long = runBlocking {
        db.generationDao().insert(Generation(
            kind = kind, missionId = missionId, sortieId = sortieId, status = status, createdAt = 1L
        ))
    }

    private val liftDraft = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = "Upper A", estimatedMinutes = 45,
        warmup = null, notes = null, runKind = null,
        targetDistance = null, targetPace = null, rawJson = "{}",
        exercises = listOf(
            ExerciseDraft(
                name = "Bench Press", equipmentIds = listOf("bb"), restSeconds = 120, notes = null,
                sets = listOf(SetDraft(8, null, 60.0))
            )
        ),
        segments = emptyList()
    )

    @Test
    fun `reset deletes the week and leaves a draft with the same pattern`() = runBlocking {
        val missionId = insertMission(MissionStatus.ACTIVE)
        val landed = insertSortie(missionId, 0, SortieType.RUN, SortieState.LANDED)
        val current = insertSortie(missionId, 1, SortieType.LIFT, SortieState.IN_FLIGHT)
        insertSortie(missionId, 2, SortieType.LIFT, SortieState.PENDING)
        db.flightPlanDao().writePlan(current, liftDraft)

        manager.resetWeek(missionId)

        assertNull(db.missionDao().get(missionId))
        assertNull(db.sortieDao().get(landed))
        assertNull(db.sortieDao().get(current))
        assertNull(db.flightPlanDao().getPlan(current))
        val draft = db.missionDao().getByWeekStart(weekStart)!!
        assertNotEquals(missionId, draft.id)
        assertEquals(MissionStatus.DRAFT, draft.status)
        assertEquals("RLL", draft.pattern)
        assertNull(draft.outlineNotes)
        assertTrue(db.sortieDao().getForMission(draft.id).isEmpty())
    }

    @Test
    fun `reset hands back every open generation of the week and none that finished`() = runBlocking {
        val missionId = insertMission(MissionStatus.ACTIVE)
        val sortie = insertSortie(missionId, 0, SortieType.LIFT, SortieState.PENDING)
        insertGeneration(missionId, GenerationKind.OUTLINE, GenerationStatus.SUCCEEDED)
        val running = insertGeneration(missionId, GenerationKind.FLIGHT_PLAN, GenerationStatus.RUNNING, sortie)
        val queued = insertGeneration(missionId, GenerationKind.FLIGHT_PLAN, GenerationStatus.QUEUED, sortie)
        insertGeneration(missionId, GenerationKind.FLIGHT_PLAN, GenerationStatus.FAILED, sortie)

        val otherWeek = insertMission(MissionStatus.CLOSED, weekStart.minusWeeks(1))
        insertGeneration(otherWeek, GenerationKind.FLIGHT_PLAN, GenerationStatus.QUEUED)

        val toCancel = manager.resetWeek(missionId)

        assertEquals(setOf(running, queued), toCancel.toSet())
    }

    @Test
    fun `a closed current week can be reset`() = runBlocking {
        val missionId = insertMission(MissionStatus.CLOSED)
        insertSortie(missionId, 0, SortieType.RUN, SortieState.LANDED)

        manager.resetWeek(missionId)

        assertEquals(MissionStatus.DRAFT, db.missionDao().getByWeekStart(weekStart)!!.status)
    }

    @Test
    fun `reset leaves other weeks alone`() = runBlocking {
        val lastWeek = insertMission(MissionStatus.CLOSED, weekStart.minusWeeks(1))
        val lastWeekSortie = insertSortie(lastWeek, 0, SortieType.RUN, SortieState.LANDED)
        val missionId = insertMission(MissionStatus.ACTIVE)

        manager.resetWeek(missionId)

        assertEquals(SortieState.LANDED, db.sortieDao().get(lastWeekSortie)!!.state)
    }

    @Test
    fun `a draft is refused and kept`() = runBlocking {
        val missionId = insertMission(MissionStatus.DRAFT)
        try {
            manager.resetWeek(missionId)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(missionId, db.missionDao().getByWeekStart(weekStart)!!.id)
    }

    @Test
    fun `a past week is refused and kept`() = runBlocking {
        val lastWeek = insertMission(MissionStatus.CLOSED, weekStart.minusWeeks(1))
        try {
            manager.resetWeek(lastWeek)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(lastWeek, db.missionDao().get(lastWeek)!!.id)
    }

    @Test
    fun `a plan that arrives after the reset is refused`() = runBlocking {
        val missionId = insertMission(MissionStatus.ACTIVE)
        val sortie = insertSortie(missionId, 0, SortieType.LIFT, SortieState.PENDING)

        manager.resetWeek(missionId)

        val valid = com.liftoff.app.coach.CoachReplyResult.Valid(
            plan = com.liftoff.app.coach.CoachLiftPlan(
                title = "Upper A", estimatedMinutes = 45, warmup = null, exercises = emptyList(), notes = ""
            ),
            json = "{}"
        )
        assertTrue(manager.storePlan(sortie, valid) is StoreOutcome.Refused)
    }
}
