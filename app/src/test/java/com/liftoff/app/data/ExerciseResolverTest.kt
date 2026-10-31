package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseResolverTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var exerciseDao: ExerciseDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        exerciseDao = db.exerciseDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun spellingVariantsResolveToOneRow() = runBlocking {
        val e1 = exerciseDao.resolve("Bench Press")
        val e2 = exerciseDao.resolve("bench  press")
        val e3 = exerciseDao.resolve("BENCH PRESS.")

        assertEquals(e1.id, e2.id)
        assertEquals(e2.id, e3.id)

        val all = exerciseDao.getAll()
        assertEquals(1, all.size)
        assertEquals("Bench Press", all[0].displayName)
    }

    @Test
    fun writePlanReusesResolvedExercise() = runBlocking {
        exerciseDao.resolve("Bench Press")
        val mId = db.missionDao().insert(Mission(weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        val sortieId = db.sortieDao().insert(Sortie(missionId = mId, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val planDao = db.flightPlanDao()
        val draft = FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Plan", estimatedMinutes = 20, warmup = null, notes = null, runKind = null, targetDistance = null, targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "bench press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(SetDraft(reps = 5, seconds = null, weight = 50.0)))),
            segments = emptyList()
        )
        planDao.writePlan(sortieId, draft)

        val all = exerciseDao.getAll()
        assertEquals(1, all.size)
        assertEquals("Bench Press", all[0].displayName)
    }

    @Test
    fun blankNameIsRejected() = runBlocking {
        try {
            exerciseDao.resolve("!!!")
            assertTrue("resolve should throw for symbols-only name", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }

        try {
            exerciseDao.resolve("   ")
            assertTrue("resolve should throw for whitespace name", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val all = exerciseDao.getAll()
        assertEquals(0, all.size)
    }

    @Test
    fun duplicateNormalizedNameIsRejected() = runBlocking {
        exerciseDao.insert(Exercise(normalizedName = "bench press", displayName = "Bench Press"))
        try {
            exerciseDao.insert(Exercise(normalizedName = "bench press", displayName = "Benchpress"))
            assertTrue("Should have thrown SQLiteConstraintException for duplicate normalizedName", false)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
    }
}
