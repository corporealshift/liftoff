package com.liftoff.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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

    // The bottom-bar label "MISSION" and the Mission screen title both match hasText("MISSION"),
    // so onNodeWithText throws "expected exactly 1 node". Exclude the selectable (bottom bar) item.
    private val missionTitle = hasText("MISSION") and !isSelectable()

    // CI's runner expired this wait on a tree that passed locally, so the shell's waits get headroom
    // instead of the five seconds every other Compose wait in the repo uses.
    private fun waitFor(matcher: SemanticsMatcher) {
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // Mission Control opens on a click, so its heads wait like the tabs do before they are asserted.
    private fun waitForHead(head: String) {
        waitFor(hasText(head))
        composeRule.onNodeWithText(head).assertIsDisplayed()
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
        waitFor(hasText("CONFIRM"))
    }

    @Test
    fun bottomBarReachesEveryTab() {
        // Navigate to Mission via bottom bar — check the real screen title.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        waitFor(missionTitle)

        // Navigate to Landed via bottom bar.
        composeRule.onNodeWithContentDescription("Landed").performClick()
        waitFor(hasText("No history yet."))

        // Navigate back to Launchpad.
        composeRule.onNodeWithContentDescription("Launchpad").performClick()
        waitFor(hasText("CONFIRM"))
    }

    @Test
    fun systemBackFromATabGoesToLaunchpad() {
        // Go to Mission tab — check the real screen title.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        waitFor(missionTitle)

        // System back should go to Launchpad.
        pressBack()
        waitFor(hasText("CONFIRM"))
    }

    @Test
    fun systemBackFromLaunchpadFinishesTheActivity() {
        // On Launchpad with Mission Control closed, back should finish the activity.
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        assert(composeRule.activity.isFinishing) { "Activity should finish when back is pressed on Launchpad" }
    }

    @Test
    fun selectedTabSurvivesRecreation() {
        // Navigate to Mission tab — check the real screen title.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        waitFor(missionTitle)

        // Recreate the activity (simulates rotation).
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Should still be on Mission tab.
        waitFor(missionTitle)
    }

    @Test
    fun missionControlSurvivesRecreation() {
        // Open Mission Control via the sliders button.
        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        waitForHead("CONNECTION")

        // Recreate the activity.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Mission Control should still be open.
        waitForHead("CONNECTION")
    }

    @Test
    fun slidersButtonOpensMissionControlWithoutBottomBar() {
        // Click the sliders button to open Mission Control.
        composeRule.onNodeWithContentDescription("Mission Control").performClick()

        // First section head should be displayed.
        waitForHead("CONNECTION")

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
        waitForHead("SETTINGS")
        waitForHead("MISSION CONTROL")
    }

    @Test
    fun missionControlBackButtonReturnsToTabItWasOpenedFrom() {
        // Navigate to Landed tab, then open Mission Control.
        composeRule.onNodeWithContentDescription("Landed").performClick()
        waitFor(hasText("No history yet."))

        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        waitForHead("CONNECTION")

        // Click the back button in Mission Control.
        composeRule.onNodeWithContentDescription("Back").performClick()

        // Should return to Landed tab.
        waitFor(hasText("No history yet."))
    }

    @Test
    fun systemBackFromMissionControlReturnsToTabItWasOpenedFrom() {
        // Navigate to Mission tab — check the real screen title.
        composeRule.onNodeWithContentDescription("Mission").performClick()
        waitFor(missionTitle)

        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        waitForHead("CONNECTION")

        // System back should close Mission Control and return to the tab it was opened from.
        pressBack()
        waitFor(missionTitle)
    }

    @Test
    fun navBarIconsAreLightOnTabsAndDarkOnMissionControl() {
        // On tabs, the bottom bar background is Ink (dark), so nav bar icons should be light.
        waitFor(hasText("CONFIRM"))

        val a = composeRule.activity
        fun light(): Boolean = WindowCompat.getInsetsController(a.window, a.window.decorView)
            .isAppearanceLightNavigationBars

        composeRule.waitForIdle()
        assert(!light()) { "Nav bar icons should be light on tabs" }

        // When Mission Control is open, the nav bar should be dark icons (cream background).
        composeRule.onNodeWithContentDescription("Mission Control").performClick()
        waitForHead("CONNECTION")

        composeRule.waitForIdle()
        assert(light()) { "Nav bar icons should be dark on Mission Control" }
    }

    @Test
    fun landedScreenShowsItsEmptyStateCopy() {
        // The shell's fixture has no finished sorties, so the real Landed screen reads as its empty state.
        composeRule.onNodeWithContentDescription("Landed").performClick()
        waitFor(hasText("HISTORY"))
        waitFor(hasText("No history yet."))
    }

    private companion object {
        private const val WAIT_MS = 20_000L
    }
}
