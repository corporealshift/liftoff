package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * What the draft draws for the two coach statuses that decide its shape: the editable one, where the pattern
 * is the owner's to reshape and Confirm is the way on, and the planning one, where an open outline has taken
 * the pattern away and left only the way out. The status arrives from OUTLINE rows seeded for the draft's own
 * mission the way LaunchpadCoachStatusTest seeds them, so the screen is reading the same join the app reads.
 *
 * The harness is LaunchpadDraftControlsTest's: in-memory Room, a real SettingsStore, MissionManager on a fixed
 * UTC clock, and LaunchpadContent rendered apart from AppContainer, which would open an on-disk liftoff.db.
 * Neither generation helper is asked to do anything real, so no WorkManager runs here. No Dialog, no text field.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadDraftStatusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    /** The draft this case is rendering, so a seeded row belongs to it and not to another mission. */
    private var missionId: Long = -1L

    /** Outline rows are ordered by createdAt, so each seeded row gets its own later instant. */
    private val seededAt = Instant.parse("2026-01-12T00:00:00Z").toEpochMilli()
    private fun at(step: Int): Long = seededAt + step * 1_000L

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

    /** A mission left in DRAFT by onAppOpen alone, at the given pattern, with Mission Control configured. */
    private suspend fun draft(pattern: String): LaunchpadViewModel {
        settingsStore.setDefaultPattern(pattern)
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("coach-token")
        missionId = manager.onAppOpen().id
        // Neither helper is asked to do anything real: the row written here is the whole status, and
        // nothing here asks WorkManager to run it.
        return LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { },
            cancelGeneration = { },
        )
    }

    /** An open generation row of the kind Confirm writes, for the draft this case is rendering. */
    private suspend fun openOutlineRow(step: Int): Long =
        db.generationDao().insert(
            Generation(
                kind = GenerationKind.OUTLINE,
                missionId = missionId,
                sortieId = null,
                status = GenerationStatus.QUEUED,
                createdAt = at(step),
            )
        )

    private fun awaitText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun render(viewModel: LaunchpadViewModel) {
        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }
    }

    // editableDraftDrawsItsChipsStepperAndConfirmAndNoOtherAction — a configured coach with no outline row is
    // the draft the controls were written for: the chips toggle, one −/+ pair and both are available, Confirm
    // is the way on, and the draft has nothing to say about Mission Control under its pattern.
    @Test
    fun editableDraftDrawsItsChipsStepperAndConfirmAndNoOtherAction() {
        val viewModel = runBlocking { draft("RLRLR") }
        render(viewModel)

        // The Draft reaches the screen through Room's flow, so wait for it before reading anything.
        awaitText("5 sorties")

        composeRule.onAllNodesWithText("R").assertCountEquals(3)
        composeRule.onAllNodesWithText("L").assertCountEquals(2)
        composeRule.onAllNodesWithText("+").assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2212").assertCountEquals(1)
        composeRule.onNodeWithText("+").assertIsEnabled()
        composeRule.onNodeWithText("\u2212").assertIsEnabled()
        composeRule.onNodeWithText("CONFIRM").assertIsDisplayed()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertDoesNotExist()
        // Editable is the one status with no line to draw: none of the three lines the draft can carry is here.
        composeRule.onNodeWithText("Coach is planning the week", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Mission Control offline", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(NOT_CONFIGURED_MESSAGE).assertDoesNotExist()

        // The first chip is the pattern's own first character: RLRLR becomes LLRLR. The write goes through
        // Room, so the redraw is waited for rather than assumed.
        composeRule.onAllNodesWithText("R")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("L").fetchSemanticsNodes().size == 3
        }
        composeRule.onAllNodesWithText("R").assertCountEquals(2)
        assertEquals("LLRLR", runBlocking { db.missionDao().get(missionId)!!.pattern })
    }

    // planningDraftDrawsItsStatusLineAndContinueWithoutOutlineAndNoStepperOrConfirm — a QUEUED OUTLINE row for
    // this draft's mission says the coach is writing the week, so the draft says so too and offers only the way
    // out. The stepper reshapes a pattern that is frozen, so it is not drawn at all.
    @Test
    fun planningDraftDrawsItsStatusLineAndContinueWithoutOutlineAndNoStepperOrConfirm() {
        val viewModel = runBlocking {
            val model = draft("RLRLR")
            openOutlineRow(1)
            model
        }
        render(viewModel)

        awaitText("Coach is planning the week", substring = true)

        composeRule.onNodeWithText("Coach is planning the week\u2026").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsEnabled()
        // Not drawn, as opposed to drawn and dimmed: the count is zero, and the label that sat between the
        // two buttons went with them.
        composeRule.onAllNodesWithText("+").assertCountEquals(0)
        composeRule.onAllNodesWithText("\u2212").assertCountEquals(0)
        composeRule.onNodeWithText("5 sorties").assertDoesNotExist()
        composeRule.onNodeWithText("CONFIRM").assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
    }

    // planningDraftDrawsItsPatternChipsReadOnly — the pattern is still the draft's to read while the coach works
    // on it, so the chips are drawn and say they are not available. A tap on one is refused.
    @Test
    fun planningDraftDrawsItsPatternChipsReadOnly() {
        val viewModel = runBlocking {
            val model = draft("RLRLR")
            openOutlineRow(1)
            model
        }
        render(viewModel)

        awaitText("Coach is planning the week", substring = true)

        val reds = composeRule.onAllNodesWithText("R").assertCountEquals(3)
        val blues = composeRule.onAllNodesWithText("L").assertCountEquals(2)
        for (i in 0 until 3) reds[i].assertIsNotEnabled()
        for (i in 0 until 2) blues[i].assertIsNotEnabled()

        // A pointer event at the chip's centre, not a semantics click: it is the tap itself that has to be
        // refused, and a disabled control is the one that never sees it.
        composeRule.onAllNodesWithText("R")[0].performTouchInput { click() }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("R").assertCountEquals(3)
        composeRule.onAllNodesWithText("L").assertCountEquals(2)
        assertEquals("RLRLR", runBlocking { db.missionDao().get(missionId)!!.pattern })
    }
}
