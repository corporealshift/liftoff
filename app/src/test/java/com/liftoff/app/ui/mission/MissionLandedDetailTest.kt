package com.liftoff.app.ui.mission

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.ui.theme.LiftoffTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Mission tab's landed run line, read straight out of the render. `SortieDetailState.Landed`
 * already carries the raw `runDistance`/`runMinutes` the screen formats, so the state object is built
 * here rather than observed: no Room, no view model, no waits.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class MissionLandedDetailTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A landed RUN with only its actuals filled in, so the one line under test is the only text that varies. */
    private fun landedRun(distance: Double?, minutes: Double?) = SortieDetailState.Landed(
        eyebrow = "SORTIE 1 · RUN · LANDED",
        focus = null,
        sortieType = SortieType.RUN,
        landedAt = 1705000000000L,
        planExerciseCount = 0,
        planLandedCount = 0,
        runDistance = distance,
        runMinutes = minutes,
        notes = null,
        distanceUnit = DistanceUnit.MI,
    )

    // landedRunDetailShowsTheRecordedDurationInMinutesAndSeconds — the line the owner sees after landing
    // a run shows the duration as it was recorded: 18.5 min reads `18 min 30 s` and not the `19 min`
    // this line rounded to, a whole 32.0 still reads `32 min`, the `27.566666…` a 27 min 34 s entry
    // stores reads `27 min 34 s`, and a run with no duration carries the em dash.
    @Test
    fun landedRunDetailShowsTheRecordedDurationInMinutesAndSeconds() {
        composeRule.setContent {
            LiftoffTheme {
                Column {
                    LandedDetailView(landedRun(distance = 3.2, minutes = 18.5))
                    LandedDetailView(landedRun(distance = 3.2, minutes = 32.0))
                    LandedDetailView(landedRun(distance = 3.1, minutes = 27.0 + 34.0 / 60.0))
                    LandedDetailView(landedRun(distance = 3.2, minutes = null))
                }
            }
        }

        composeRule.onNodeWithText("3.2 mi · 18 min 30 s").assertIsDisplayed()
        composeRule.onNodeWithText("3.2 mi · 19 min").assertDoesNotExist()

        composeRule.onNodeWithText("3.2 mi · 32 min").assertIsDisplayed()

        composeRule.onNodeWithText("3.1 mi · 27 min 34 s").assertIsDisplayed()
        composeRule.onNodeWithText("3.1 mi · 27 min 33 s").assertDoesNotExist()
        composeRule.onNodeWithText("3.1 mi · 28 min").assertDoesNotExist()

        composeRule.onNodeWithText("3.2 mi · \u2014").assertIsDisplayed()
    }
}
