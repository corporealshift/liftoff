package com.liftoff.app.ui.sortie

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.robolectric.annotation.Config
import com.liftoff.app.data.*

/** Verify that FlightPlanSection renders formatPlanHead(plan) next to the head. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class FlightPlanSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var scopeJob = Job()
    private lateinit var database: com.liftoff.app.data.LiftoffDatabase

    @After
    fun tearDown() {
        scopeJob.cancel()
        database.close()
    }

    // ── flightPlanHeadDisplaysEstimatedMinutesAndSetCount ─────────────
    // Robolectric: FlightPlanSection renders "FLIGHT PLAN" and the
    // formatted head produced by formatPlanHead(plan) — e.g. "≈55 min · 14 sets".

    @Test
    fun flightPlanHeadDisplaysEstimatedMinutesAndSetCount() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                com.liftoff.app.data.LiftoffDatabase::class.java,
            ).build()

            // Build a plan: 3 exercises with 4 sets each = 12 sets, estimated 55 min.
            val fp = FlightPlan(
                id = 1, sortieId = 1, source = FlightPlanSource.GENERATED,
                title = "Test Plan", estimatedMinutes = 55,
                warmup = null, notes = null, runKind = null,
                targetDistance = null, targetPace = null, rawJson = "{}",
            )
            val exercises = listOf(
                PlannedExerciseDetail(
                    plannedExercise = PlannedExercise(
                        id = 1, flightPlanId = 1, `order` = 0,
                        exerciseId = 1, equipmentIds = emptyList(),
                        restSeconds = null, notes = null, skipped = false, userNotes = null,
                    ),
                    displayName = "Bench Press",
                    sets = listOf(
                        PlannedSet(id = 1, plannedExerciseId = 1, `order` = 0, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 2, plannedExerciseId = 1, `order` = 1, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 3, plannedExerciseId = 1, `order` = 2, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 4, plannedExerciseId = 1, `order` = 3, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                    ),
                ),
                PlannedExerciseDetail(
                    plannedExercise = PlannedExercise(
                        id = 2, flightPlanId = 1, `order` = 1,
                        exerciseId = 2, equipmentIds = emptyList(),
                        restSeconds = null, notes = null, skipped = false, userNotes = null,
                    ),
                    displayName = "Squat",
                    sets = listOf(
                        PlannedSet(id = 5, plannedExerciseId = 2, `order` = 0, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 6, plannedExerciseId = 2, `order` = 1, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 7, plannedExerciseId = 2, `order` = 2, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 8, plannedExerciseId = 2, `order` = 3, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                    ),
                ),
                PlannedExerciseDetail(
                    plannedExercise = PlannedExercise(
                        id = 3, flightPlanId = 1, `order` = 2,
                        exerciseId = 3, equipmentIds = emptyList(),
                        restSeconds = null, notes = null, skipped = false, userNotes = null,
                    ),
                    displayName = "Deadlift",
                    sets = listOf(
                        PlannedSet(id = 9, plannedExerciseId = 3, `order` = 0, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 10, plannedExerciseId = 3, `order` = 1, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 11, plannedExerciseId = 3, `order` = 2, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                        PlannedSet(id = 12, plannedExerciseId = 3, `order` = 3, reps = 8, seconds = null, weight = 135.0, actualReps = null, actualSeconds = null, actualWeight = null, status = SetStatus.OPEN, added = false),
                    ),
                ),
            )
            val plan = FlightPlanDetail(fp, exercises, emptyList())

            composeRule.setContent {
                val shellScope = rememberCoroutineScope()
                com.liftoff.app.ui.theme.LiftoffTheme {
                    FlightPlanSection(
                        plan = plan,
                        sortieType = SortieType.LIFT,
                    )
                }
            }

            // "FLIGHT PLAN" must be displayed.
            composeRule.onNodeWithText("FLIGHT PLAN").assertIsDisplayed()

            // formatPlanHead(plan) → "≈55 min · 12 sets" must also be displayed next to it.
            composeRule.onNodeWithText("≈55 min · 12 sets").assertIsDisplayed()
        }
    }
}
