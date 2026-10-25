package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What the draft state renders for its controls: the −/+ stepper, the way out under Confirm, and the
 * line a Confirm leaves when Mission Control has no host and token — through LaunchpadContent on a view
 * model built on in-memory Room. AppContainer is what opens an on-disk liftoff.db, so the screen body is
 * rendered apart from it. No Dialog and no text field anywhere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadDraftControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()

        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        // The compose rule tears down after @After, so the view model's Room flow is still collecting
        // here and has to stop before the database closes.
        runBlocking { job.cancelAndJoin() }
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    /** A mission left in DRAFT by onAppOpen alone, at the given pattern. */
    private suspend fun draft(pattern: String): LaunchpadViewModel {
        settingsStore.setDefaultPattern(pattern)
        manager.onAppOpen()
        // No generation is queued or cancelled by anything this class drives, so both helpers are no-ops.
        return LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { },
            cancelGeneration = { },
        )
    }

    // draftDrawsOneStepperPair — the draft draws its −/+ pair once, in the bottom bar beside the
    // 'N sorties' label. The row under the chips used to draw a second pair with an ASCII '-', so
    // counting '+' is what shows the duplicate is gone; the chips still render.
    @Test
    fun draftDrawsOneStepperPair() {
        val viewModel = runBlocking { draft("RLRLR") }

        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }

        // The Draft reaches the screen through Room's flow, so wait for it before reading anything.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("5 sorties").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText("+").assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2212").assertCountEquals(1)
        composeRule.onAllNodesWithText("-").assertCountEquals(0)
        // The chips the stepper sits beside are still there.
        composeRule.onAllNodesWithText("R").assertCountEquals(3)
        composeRule.onAllNodesWithText("L").assertCountEquals(2)
    }

    // draftMinusDisabledAtOneSortie — on a one-sortie draft the '−' is still drawn but says it is not
    // available, and '+' is. Dimmed and untappable, rather than present and quietly doing nothing.
    @Test
    fun draftMinusDisabledAtOneSortie() {
        val viewModel = runBlocking { draft("R") }

        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("1 sorties").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("\u2212").assertIsNotEnabled()
        composeRule.onNodeWithText("+").assertIsEnabled()
    }

    // draftPlusDisabledAtSevenSorties — the mirror at the upper limit: '+' is drawn but not enabled,
    // '−' still is.
    @Test
    fun draftPlusDisabledAtSevenSorties() {
        val viewModel = runBlocking { draft("RLRLRLR") }

        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("7 sorties").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("+").assertIsNotEnabled()
        composeRule.onNodeWithText("\u2212").assertIsEnabled()
        // The rest of the bar is untouched: Confirm still sits below the stepper.
        composeRule.onNodeWithText("CONFIRM").assertIsDisplayed()
    }

    // draftAlwaysOffersContinueWithoutOutline — the draft's way out is drawn under Confirm and is
    // tappable with nothing set up behind it: no message, no Mission Control. Tapping it runs the
    // continue-without-outline action, which is the only thing here that takes a draft out of being a
    // draft — the week starts from the pattern it already holds, so Confirm and the stepper are gone.
    @Test
    fun draftAlwaysOffersContinueWithoutOutline() {
        val viewModel = runBlocking { draft("RLRLR") }

        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("5 sorties").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsEnabled()
        composeRule.onNodeWithText("CONFIRM").assertIsDisplayed()

        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("LAUNCH").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("CONFIRM").assertDoesNotExist()
        composeRule.onNodeWithText("5 sorties").assertDoesNotExist()
    }

    // draftShowsTheNotConfiguredMessageWhenItIsSet — the draft draws the line a Confirm left when
    // Mission Control has no host and token, and draws no such line while nothing has gone wrong. The
    // settings this class builds leave both blank, so a Confirm is the manager's CoachNotConfigured.
    @Test
    fun draftShowsTheNotConfiguredMessageWhenItIsSet() {
        val viewModel = runBlocking { draft("RLRLR") }

        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("5 sorties").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText(NOT_CONFIGURED_MESSAGE).assertCountEquals(0)

        val queued = viewModel.confirm()
        runBlocking { withTimeout(5_000L) { queued?.join() } }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(NOT_CONFIGURED_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(NOT_CONFIGURED_MESSAGE).assertIsDisplayed()
    }
}
