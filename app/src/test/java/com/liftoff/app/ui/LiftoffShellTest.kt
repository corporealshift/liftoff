package com.liftoff.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.liftoff.app.LiftoffApplication
import com.liftoff.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class LiftoffShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun pressBack() {
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
    }

    @Before
    fun setup() {
        // Trigger onAppOpen before the UI is composed so Launchpad shows Draft content.
        runBlocking {
            (composeRule.activity.applicationContext as LiftoffApplication)
                .container.missionManager.onAppOpen()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun appOpensOnLaunchpad() {
        // The app opens on the Launchpad tab — check unique screen content.
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText("NEW MISSION").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun bottomBarReachesEveryTab() {
        // Navigate to Mission via bottom bar.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()

        // Navigate to Landed via bottom bar.
        composeRule.onNodeWithContentDescription("Landed").performClick()
        composeRule.onNodeWithText("Landed sorties will appear here, newest first.").assertIsDisplayed()

        // Navigate back to Launchpad.
        composeRule.onNodeWithContentDescription("Launchpad").performClick()
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText("NEW MISSION").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun systemBackFromATabGoesToLaunchpad() {
        // Go to Mission tab.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()

        // System back should go to Launchpad.
        pressBack()
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText("NEW MISSION").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun systemBackFromLaunchpadFinishesTheActivity() {
        // On Launchpad with Mission Control closed, back should finish the activity.
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        assert(composeRule.activity.isFinishing) { "Activity should finish when back is pressed on Launchpad" }
    }

    @Test
    fun selectedTabSurvivesRecreation() {
        // Navigate to Mission tab.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()

        // Recreate the activity (simulates rotation).
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Should still be on Mission tab.
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()
    }

    @Test
    fun missionControlSurvivesRecreation() {
        // Open Mission Control via the sliders button.
        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

        // Recreate the activity.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Mission Control should still be open.
        composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()
    }

    @Test
    fun slidersButtonOpensMissionControlWithoutBottomBar() {
        // Click the sliders button to open Mission Control.
        composeRule.onNodeWithContentDescription("Mission Control").performClick()

        // First section head should be displayed.
        composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

        // The bottom navigation bar icons should NOT be visible on Mission Control screen.
        val navItemMatcher = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Launchpad"))
        assert(composeRule.onAllNodes(navItemMatcher).fetchSemanticsNodes().isEmpty()) {
            "Launchpad nav item should not exist"
        }
    }

    @Test
    fun missionControlShowsSettingsEyebrowAndTitle() {
        // Open Mission Control.
        composeRule.onNodeWithContentDescription("Mission Control").performClick()

        // Check the eyebrow and title are displayed.
        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("MISSION CONTROL").assertIsDisplayed()
    }

    @Test
    fun missionControlBackButtonReturnsToTabItWasOpenedFrom() {
        // Navigate to Landed tab, then open Mission Control.
        composeRule.onNodeWithContentDescription("Landed").performClick()
        composeRule.onNodeWithText("Landed sorties will appear here, newest first.").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

        // Click the back button in Mission Control.
        composeRule.onNodeWithContentDescription("Back").performClick()

        // Should return to Landed tab.
        composeRule.onNodeWithText("Landed sorties will appear here, newest first.").assertIsDisplayed()
    }

    @Test
    fun systemBackFromMissionControlReturnsToTabItWasOpenedFrom() {
        // Navigate to Mission tab, then open Mission Control.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

        // System back should close Mission Control and return to the tab it was opened from.
        pressBack()
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()
    }

    @Test
    fun navBarIconsAreLightOnTabsAndDarkOnMissionControl() {
        // On tabs, the bottom bar background is Ink (dark), so nav bar icons should be light.
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText("NEW MISSION").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        val a = composeRule.activity
        fun light(): Boolean = WindowCompat.getInsetsController(a.window, a.window.decorView)
            .isAppearanceLightNavigationBars

        composeRule.waitForIdle()
        assert(!light()) { "Nav bar icons should be light on tabs" }

        // When Mission Control is open, the nav bar should be dark icons (cream background).
        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

        composeRule.waitForIdle()
        assert(light()) { "Nav bar icons should be dark on Mission Control" }
    }

    @Test
    fun placeholderScreensShowTheirCopy() {
        // Launchpad copy — wait for both texts together since they render as part of one composable.
        composeRule.waitUntil(5_000) {
            var found = false
            try {
                composeRule.onNodeWithText("NEW MISSION").assertIsDisplayed()
                composeRule.onNodeWithText("DRAFT", substring = true).assertIsDisplayed()
                found = true
            } catch (_: AssertionError) {}
            found
        }

        // Mission copy.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("THIS WEEK").assertIsDisplayed()
        composeRule.onNodeWithText("This week's pattern and sorties will appear here.").assertIsDisplayed()

        // Landed copy.
        composeRule.onNodeWithContentDescription("Landed").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("HISTORY").assertIsDisplayed()
        composeRule.onNodeWithText("Landed sorties will appear here, newest first.").assertIsDisplayed()
    }
}
