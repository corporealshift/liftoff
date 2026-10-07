package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightPlanDaoTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var sortieDao: SortieDao
    private lateinit var missionDao: MissionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        flightPlanDao = db.flightPlanDao()
        sortieDao = db.sortieDao()
        missionDao = db.missionDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private suspend fun makeSortie(): Long {
        val mId = missionDao.insert(Mission(weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        return sortieDao.insert(Sortie(missionId = mId, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
    }

    @Test
    fun writePlanThenGetPlanKeepsStoredOrder() = runBlocking {
        val sortieId = makeSortie()
        val draft = FlightPlanDraft(
            source = FlightPlanSource.GENERATED,
            title = "Test Plan",
            estimatedMinutes = 30,
            warmup = "warmup notes",
            notes = null,
            runKind = "steady",
            targetDistance = 1000.0,
            targetPace = "5:00",
            rawJson = "{}",
            exercises = listOf(
                ExerciseDraft(name = "Bench Press", equipmentIds = listOf("barbell"), restSeconds = 60, notes = null, sets = listOf(
                    SetDraft(reps = 8, seconds = null, weight = 60.0),
                    SetDraft(reps = 8, seconds = null, weight = 70.0)
                )),
                ExerciseDraft(name = "Squat", equipmentIds = listOf("barbell"), restSeconds = 120, notes = null, sets = listOf(
                    SetDraft(reps = 5, seconds = null, weight = 80.0)
                ))
            ),
            segments = listOf(
                RunSegmentDraft(description = "Warmup run", distance = 400.0, minutes = 3.0),
                RunSegmentDraft(description = "Main effort", distance = 600.0, minutes = 4.0)
            )
        )

        val planId = flightPlanDao.writePlan(sortieId, draft)
        assertTrue(planId > 0)

        val detail = flightPlanDao.getPlan(sortieId)!!
        assertEquals("Test Plan", detail.plan.title)
        assertEquals(2, detail.exercises.size)
        assertEquals("Bench Press", detail.exercises[0].displayName)
        assertEquals("Squat", detail.exercises[1].displayName)
        assertEquals(2, detail.exercises[0].sets.size)
        assertEquals(1, detail.exercises[1].sets.size)
        assertEquals(2, detail.segments.size)
        assertEquals("Warmup run", detail.segments[0].description)
    }

    @Test
    fun getPlanIsNullWithoutPlan() = runBlocking {
        val sortieId = makeSortie()
        assertNull(flightPlanDao.getPlan(sortieId))
    }

    @Test
    fun writePlanReplacesExistingPlan() = runBlocking {
        val sortieId = makeSortie()
        val draft1 = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Plan 1", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(SetDraft(reps = 5, seconds = null, weight = 50.0)))),
            segments = emptyList()
        )
        flightPlanDao.writePlan(sortieId, draft1)

        val draft2 = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Plan 2", estimatedMinutes = 30, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(
                ExerciseDraft(name = "Squat", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(SetDraft(reps = 5, seconds = null, weight = 60.0))),
                ExerciseDraft(name = "Deadlift", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(SetDraft(reps = 3, seconds = null, weight = 80.0)))
            ),
            segments = listOf(RunSegmentDraft(description = "run", distance = null, minutes = null))
        )
        flightPlanDao.writePlan(sortieId, draft2)

        val detail = flightPlanDao.getPlan(sortieId)!!
        assertEquals("Plan 2", detail.plan.title)
        assertEquals(2, detail.exercises.size)
        assertEquals(1, detail.segments.size)

        // Verify only one FlightPlan row exists for this sortie
        val planCount = db.query("SELECT COUNT(*) FROM flightPlan WHERE sortieId = ?", arrayOf(sortieId)).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        assertEquals(1, planCount)
    }

    @Test
    fun failedWritePlanLeavesOldPlanIntact() = runBlocking {
        val sortieId = makeSortie()
        val draft1 = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Old Plan", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(SetDraft(reps = 5, seconds = null, weight = 50.0)))),
            segments = emptyList()
        )
        flightPlanDao.writePlan(sortieId, draft1)

        val oldDetail = flightPlanDao.getPlan(sortieId)!!
        assertEquals("Old Plan", oldDetail.plan.title)
        assertEquals(1, oldDetail.exercises.size)

        // Now try to write a plan with a bad exercise name — should throw and leave old plan intact
        val badDraft = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Bad Plan", estimatedMinutes = 30, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "!!!", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = emptyList())),
            segments = emptyList()
        )

        try {
            flightPlanDao.writePlan(sortieId, badDraft)
            assertTrue("writePlan should have thrown for blank exercise name", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Old plan should still be intact
        val remaining = flightPlanDao.getPlan(sortieId)!!
        assertEquals("Old Plan", remaining.plan.title)
    }

    @Test
    fun duplicateSortieIdIsRejected() = runBlocking {
        val sortieId = makeSortie()
        flightPlanDao.insertPlan(FlightPlan(sortieId = sortieId, source = FlightPlanSource.GENERATED, title = "P1", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}"))
        try {
            flightPlanDao.insertPlan(FlightPlan(sortieId = sortieId, source = FlightPlanSource.GENERATED, title = "P2", estimatedMinutes = 30, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}"))
            assertTrue("Should have thrown SQLiteConstraintException for duplicate sortieId", false)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun updateSetActualsChangesValuesAndStatus() = runBlocking {
        val sortieId = makeSortie()
        val draft = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Test Plan", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 8, seconds = null, weight = 60.0),
                SetDraft(reps = 8, seconds = null, weight = 70.0)
            ))),
            segments = emptyList()
        )
        flightPlanDao.writePlan(sortieId, draft)

        val detail = flightPlanDao.getPlan(sortieId)!!
        val setId = detail.exercises[0].sets[1].id

        val actualW: Double? = 72.50
        flightPlanDao.updateSetActuals(setId, actualReps = 7, actualSeconds = null, actualWeight = actualW, status = SetStatus.DONE)

        val updatedDetail = flightPlanDao.getPlan(sortieId)!!
        val updatedSet = updatedDetail.exercises[0].sets[1]
        assertEquals(7, updatedSet.actualReps)
        val aw = updatedSet.actualWeight!!
        assertEquals(72.5, aw, 0.001)
        assertEquals(SetStatus.DONE, updatedSet.status)
    }

    @Test
    fun addExtraSetAppendsAfterLastSetAsAdded() = runBlocking {
        val sortieId = makeSortie()
        val draft = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Test Plan", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 8, seconds = null, weight = 60.0),
                SetDraft(reps = 8, seconds = null, weight = 70.0)
            ))),
            segments = emptyList()
        )
        flightPlanDao.writePlan(sortieId, draft)

        val detail = flightPlanDao.getPlan(sortieId)!!
        val exerciseId = detail.exercises[0].plannedExercise.id

        val extraSetId = flightPlanDao.addExtraSet(exerciseId, reps = 6, seconds = null, weight = 75.0)
        assertTrue(extraSetId > 0)

        val updatedDetail = flightPlanDao.getPlan(sortieId)!!
        val extraSet = updatedDetail.exercises[0].sets.find { it.id == extraSetId }
        assertTrue(extraSet != null)
        assertEquals(2, extraSet!!.order) // was last index 1, new one is 2
        assertEquals(true, extraSet.added)
        assertEquals(SetStatus.OPEN, extraSet.status)
        assertEquals(6, extraSet.reps)
    }

    @Test
    fun updateExerciseSetsSkippedAndUserNotes() = runBlocking {
        val sortieId = makeSortie()
        val draft = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Test Plan", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(SetDraft(reps = 8, seconds = null, weight = 60.0)))),
            segments = emptyList()
        )
        flightPlanDao.writePlan(sortieId, draft)

        val detail = flightPlanDao.getPlan(sortieId)!!
        val exerciseId = detail.exercises[0].plannedExercise.id

        flightPlanDao.updateExercise(exerciseId, skipped = true, userNotes = "felt weak today")

        val updatedDetail = flightPlanDao.getPlan(sortieId)!!
        assertEquals(true, updatedDetail.exercises[0].plannedExercise.skipped)
        assertEquals("felt weak today", updatedDetail.exercises[0].plannedExercise.userNotes)
    }
}
