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
 * What the draft draws for each coach status it can be in, and the one line it draws under its pattern. The
 * editable one, where the pattern is the owner's to reshape and Confirm is the way on; the planning and offline
 * ones, where an open outline has taken the pattern away and left only the way out; the failed one, which says
 * why in the row's own words and offers Retry; and the not-configured one, which says where the host and token
 * go. The status arrives from OUTLINE rows seeded for the draft's own mission the way LaunchpadCoachStatusTest
 * seeds them, so the screen is reading the same join the app reads.
 *
 * The last two cases are the plan's Decisions rather than the brief's requirements: that the draft carries one
 * line, a Retry that found nothing configured replacing a failed draft's reason instead of adding to it, and
 * that the next Retry clears that report.
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

    /**
     * A mission left in DRAFT by onAppOpen alone, at the given pattern. [configured] is whether Mission
     * Control has a host and token to ask; left blank, the draft has nothing to queue.
     */
    private suspend fun draft(pattern: String, configured: Boolean = true): LaunchpadViewModel {
        settingsStore.setDefaultPattern(pattern)
        if (configured) {
            settingsStore.setDaemonHost("127.0.0.1")
            settingsStore.setDaemonToken("coach-token")
        }
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

    /** An open outline row carrying the mark the worker writes when Mission Control cannot be reached. */
    private suspend fun offlineOutlineRow(step: Int): Long =
        db.generationDao().insert(
            Generation(
                kind = GenerationKind.OUTLINE,
                missionId = missionId,
                sortieId = null,
                status = GenerationStatus.QUEUED,
                error = Generation.COACH_OFFLINE,
                createdAt = at(step),
            )
        )

    /**
     * An outline row that gave up, carrying [reason]. It sits at the manager's own clock instant, so the row a
     * later Retry queues — same instant, later id, and the queries order createdAt then id — is the newest and
     * the draft moves on from this failure.
     */
    private suspend fun failedOutlineRow(reason: String): Long {
        val id = db.generationDao().insert(
            Generation(
                kind = GenerationKind.OUTLINE,
                missionId = missionId,
                sortieId = null,
                status = GenerationStatus.QUEUED,
                createdAt = at(0),
            )
        )
        db.generationDao().markFailed(id, reason, at(1))
        return id
    }

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

    /** The chips and one −/+ pair, drawn and available: the pattern is still the owner's to reshape. */
    private fun assertChipsAndStepperDrawnAndEnabled(reds: Int, blues: Int) {
        val r = composeRule.onAllNodesWithText("R").assertCountEquals(reds)
        val l = composeRule.onAllNodesWithText("L").assertCountEquals(blues)
        for (i in 0 until reds) r[i].assertIsEnabled()
        for (i in 0 until blues) l[i].assertIsEnabled()
        composeRule.onAllNodesWithText("+").assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2212").assertCountEquals(1)
        composeRule.onNodeWithText("+").assertIsEnabled()
        composeRule.onNodeWithText("\u2212").assertIsEnabled()
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

    // offlineDraftDrawsItsStatusLineAndTheSameActionsAsPlanning — an outline row that is still open and carries
    // the mark the worker writes when Mission Control cannot be reached. The draft says so, and offers the same
    // one way out as planning: the worker is retrying on its own, so there is no Retry button here.
    @Test
    fun offlineDraftDrawsItsStatusLineAndTheSameActionsAsPlanning() {
        var row = -1L
        val viewModel = runBlocking {
            val model = draft("RLRLR")
            row = offlineOutlineRow(1)
            model
        }
        render(viewModel)

        awaitText("Mission Control offline", substring = true)

        composeRule.onNodeWithText("Mission Control offline \u2014 retrying").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsEnabled()
        composeRule.onNodeWithText("CONFIRM").assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
        // The same actions as planning, down to the stepper being absent rather than dimmed.
        composeRule.onAllNodesWithText("+").assertCountEquals(0)
        composeRule.onAllNodesWithText("\u2212").assertCountEquals(0)
        composeRule.onNodeWithText("5 sorties").assertDoesNotExist()
        val r = composeRule.onAllNodesWithText("R").assertCountEquals(3)
        val l = composeRule.onAllNodesWithText("L").assertCountEquals(2)
        for (i in 0 until 3) r[i].assertIsNotEnabled()
        for (i in 0 until 2) l[i].assertIsNotEnabled()

        // The mark alone: the row is still open, so this is not a failure wearing an offline label.
        val stored = runBlocking { db.generationDao().get(row) }!!
        assertEquals(GenerationStatus.QUEUED, stored.status)
        assertEquals(Generation.COACH_OFFLINE, stored.error)
    }

    // failedDraftDrawsItsReasonAndRetryAndContinueWithoutOutlineAndNoConfirm — a FAILED outline row's own words,
    // punctuation and inner spacing intact, with Retry in Confirm's slot and the way out below it. The pattern is
    // no longer frozen, because nothing is open any more, so the chips and the stepper are back.
    @Test
    fun failedDraftDrawsItsReasonAndRetryAndContinueWithoutOutlineAndNoConfirm() {
        val reason = "reply rejected: 3 of 5 sets name a barbell the owner does not own"
        val viewModel = runBlocking {
            val model = draft("RLRLR")
            failedOutlineRow(reason)
            model
        }
        render(viewModel)

        awaitText(reason)

        composeRule.onNodeWithText(reason).assertIsDisplayed()
        composeRule.onNodeWithText("RETRY").assertIsDisplayed()
        composeRule.onNodeWithText("RETRY").assertIsEnabled()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsEnabled()
        composeRule.onNodeWithText("CONFIRM").assertDoesNotExist()
        assertChipsAndStepperDrawnAndEnabled(reds = 3, blues = 2)

        // The tap that proves RETRY reaches retry(): the row it queues is this mission's newest outline, so the
        // draft moves from the failure to the coach working on the week.
        composeRule.onNodeWithText("RETRY").performClick()
        awaitText("Coach is planning the week", substring = true)

        composeRule.onNodeWithText("Coach is planning the week\u2026").assertIsDisplayed()
        composeRule.onNodeWithText(reason).assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
    }

    // notConfiguredDraftDrawsItsSetupLineAndContinueWithoutOutlineAndNoConfirmOrRetry — no outline row worth
    // reporting and no host or token: the draft says where they go and offers the way out. Confirm would only
    // come back as a refusal, and there is no ask to retry.
    @Test
    fun notConfiguredDraftDrawsItsSetupLineAndContinueWithoutOutlineAndNoConfirmOrRetry() {
        val viewModel = runBlocking { draft("RLRLR", configured = false) }
        render(viewModel)

        awaitText(NOT_CONFIGURED_MESSAGE)

        composeRule.onNodeWithText(NOT_CONFIGURED_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE WITHOUT OUTLINE").assertIsEnabled()
        composeRule.onNodeWithText("CONFIRM").assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
        assertChipsAndStepperDrawnAndEnabled(reds = 3, blues = 2)
        // The status line, not a report: neither of the other two lines the draft can draw is here.
        composeRule.onNodeWithText("Coach is planning the week", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Mission Control offline", substring = true).assertDoesNotExist()
    }

    // A FAILED row outranks blank settings, so the draft has one thing to say. Tapping Retry finds nothing
    // configured and its report takes that same slot: the reason is gone, not joined by a second line.
    @Test
    fun failedDraftWithBlankSettingsShowsItsReasonAloneAndARetryReportsInTheSameSlot() {
        val reason = "Mission Control closed the socket before the outline arrived"
        var row = -1L
        val viewModel = runBlocking {
            val model = draft("RLRLR", configured = false)
            row = failedOutlineRow(reason)
            model
        }
        render(viewModel)

        awaitText(reason)

        composeRule.onNodeWithText(reason).assertIsDisplayed()
        // The reason and nothing else.
        composeRule.onNodeWithText(NOT_CONFIGURED_MESSAGE).assertDoesNotExist()
        composeRule.onNodeWithText("Coach is planning the week", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Mission Control offline", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertIsDisplayed()

        composeRule.onNodeWithText("RETRY").performClick()
        awaitText(NOT_CONFIGURED_MESSAGE)

        // One line, replaced rather than added to: the sentence on screen once, the reason no longer drawn.
        composeRule.onAllNodesWithText(NOT_CONFIGURED_MESSAGE).assertCountEquals(1)
        composeRule.onNodeWithText(reason).assertDoesNotExist()
        // The refusal wrote no row, so the FAILED row is still the newest for the next Retry to answer.
        assertEquals(GenerationStatus.FAILED, runBlocking { db.generationDao().get(row) }!!.status)
        assertEquals(1L, runBlocking { db.generationDao().countAll() })
    }

    // The report a Retry found must not outlive the owner setting Mission Control up: the next Retry clears it,
    // reaches the coach, and the draft shows the week being planned with nothing saying it isn't set up.
    @Test
    fun retryClearsTheNotConfiguredReportAndTheNextRetryReachesMissionControl() {
        val reason = "reply rejected: the outline named a barbell the owner does not own"
        val viewModel = runBlocking {
            val model = draft("RLRLR", configured = false)
            failedOutlineRow(reason)
            model
        }
        render(viewModel)

        awaitText(reason)

        // The report has to exist before it can be cleared, and only a Retry with nothing configured writes one.
        composeRule.onNodeWithText("RETRY").performClick()
        awaitText(NOT_CONFIGURED_MESSAGE)

        runBlocking {
            settingsStore.setDaemonHost("127.0.0.1")
            settingsStore.setDaemonToken("coach-token")
        }

        composeRule.onNodeWithText("RETRY").performClick()
        awaitText("Coach is planning the week", substring = true)

        composeRule.onNodeWithText("Coach is planning the week\u2026").assertIsDisplayed()
        composeRule.onAllNodesWithText(NOT_CONFIGURED_MESSAGE).assertCountEquals(0)
        composeRule.onNodeWithText(reason).assertDoesNotExist()
    }
}
