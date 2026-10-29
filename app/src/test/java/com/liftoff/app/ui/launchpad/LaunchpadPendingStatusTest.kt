package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * What the PENDING card says about the coach for this sortie, in the slot the fixed "Flight Plan not ready yet."
 * line used to hold. Each case reaches a real PENDING card the way the screen does — host and token set, pattern
 * "LRL" so the current sortie is a LIFT, onAppOpen then continueWithoutOutline — and then changes what the card
 * answers by writing to the FLIGHT_PLAN row continueWithoutOutline queued. Nothing here builds a state by hand.
 *
 * The first three cases are the statuses that carry no Retry: the coach is working on the plan, Mission Control
 * cannot be reached and the worker is already retrying on its own, and Mission Control was never set up. The plan's
 * Decision "Retry appears only under a failure, not under Offline or NotConfigured" is what the absence of RETRY
 * in each of them shows. The next two are the failure itself: the reason with each validator error on its own line,
 * and Retry drawn there. The last one is the other half of the same guard — a RUN sortie whose failure the app
 * cannot retry, because 'Generate run plans' is off — which is the only place the card's regenerateAvailable is
 * observable at all.
 *
 * The harness is LaunchpadDraftStatusTest's: in-memory Room, a real SettingsStore, MissionManager on a fixed UTC
 * clock, and LaunchpadContent rendered apart from AppContainer, which would open an on-disk liftoff.db. Neither
 * generation helper is asked to do anything real, so no WorkManager runs here. No Dialog, no text field.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadPendingStatusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    /** The week this case is rendering, so a write lands on this mission's sortie and not another's. */
    private var missionId: Long = -1L
    private var sortieId: Long = -1L

    /** The FLIGHT_PLAN row continueWithoutOutline queued for the current sortie; -1 when it queued none. */
    private var queuedRow: Long = -1L

    /** Every id the view model's enqueuer was handed, in the order it was handed them. */
    private val queuedIds = mutableListOf<Long>()

    /** Every id the view model's cancel helper was handed, in the order it was handed them. */
    private val cancelledIds = mutableListOf<Long>()

    /** The fixed clock's own instant, so a markFailed can be stamped later than the row it marks. */
    private val seededAt: Long = Instant.parse("2026-01-12T00:00:00Z").toEpochMilli()

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
     * The week the card is read on, built the way the screen builds it: settings first, then the current week's
     * draft activated with no outline. [pattern] starts with L so the current sortie is a LIFT and [advance]
     * queued a QUEUED FLIGHT_PLAN row for it; left unconfigured, it queued none and the card has no row to
     * answer. Passing [pattern] null leaves the store's own default "RLRLR" in place, so the current sortie is
     * the pattern's first character: a RUN. [runPlans] is written before [onAppOpen] because
     * continueWithoutOutline is what prepares the current sortie and reads the setting then — with it on, that
     * run sortie waits on the coach instead of getting the simple plan, so it stays PENDING with a row of its own.
     */
    private suspend fun pendingCard(
        configured: Boolean = true,
        pattern: String? = "LRL",
        runPlans: Boolean = false,
    ): LaunchpadViewModel {
        if (pattern != null) settingsStore.setDefaultPattern(pattern)
        if (configured) {
            settingsStore.setDaemonHost("127.0.0.1")
            settingsStore.setDaemonToken("t")
        }
        if (runPlans) settingsStore.setGenerateRunPlans(true)
        missionId = manager.onAppOpen().id
        manager.continueWithoutOutline(missionId) { queuedRow = it }
        sortieId = db.sortieDao().getForMission(missionId).first { it.index == 0 }.id
        // Neither helper is asked to do anything real: the row written by continueWithoutOutline is the whole
        // status, and nothing here asks WorkManager to run it. They only record what they were handed.
        return LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { queuedIds.add(it) },
            cancelGeneration = { cancelledIds.add(it) },
        )
    }

    /** The row continueWithoutOutline queued for this sortie, read back the way the card's join reads it. */
    private suspend fun openRow(): Generation =
        db.generationDao().getOpenFlightPlanForSortie(sortieId).single().also {
            assertEquals(queuedRow, it.id)
        }

    /**
     * That row, marked FAILED with [error] by the worker's own write. Its finishedAt is later than the createdAt
     * every row here carries, which is the fixed clock's instant.
     */
    private suspend fun failOpenRow(error: String) {
        val row = openRow()
        assertEquals(1, db.generationDao().markFailed(row.id, error, seededAt + 60_000))
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun render(viewModel: LaunchpadViewModel) {
        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }
    }

    // pendingCardShowsThePlanningLineAndNoRetry — the QUEUED FLIGHT_PLAN row continueWithoutOutline queued is this
    // sortie's newest, so the card says the coach is working on the plan instead of the line it drew before it had
    // a status to report. Nothing has failed, so there is no Retry to offer.
    @Test
    fun pendingCardShowsThePlanningLineAndNoRetry() {
        val viewModel = runBlocking { pendingCard() }
        render(viewModel)

        awaitText(PLANNING_LINE)

        composeRule.onNodeWithText(PLANNING_LINE).assertIsDisplayed()
        composeRule.onNodeWithText(FIXED_LINE).assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()

        val stored = runBlocking { db.generationDao().get(queuedRow) }!!
        assertEquals(GenerationStatus.QUEUED, stored.status)
        assertEquals(sortieId, stored.sortieId)
    }

    // pendingCardShowsTheOfflineLineAndNoRetry — that same row, still open, carrying the mark the worker writes
    // when Mission Control cannot be reached. The card says so in the same slot, and offers no Retry: the worker
    // is already retrying on its own, and a manual one would supersede the open row and ask for the same thing.
    @Test
    fun pendingCardShowsTheOfflineLineAndNoRetry() {
        val viewModel = runBlocking {
            val model = pendingCard()
            val row = openRow()
            db.generationDao().update(row.copy(error = Generation.COACH_OFFLINE))
            model
        }
        render(viewModel)

        awaitText(OFFLINE_LINE)

        composeRule.onNodeWithText(OFFLINE_LINE).assertIsDisplayed()
        composeRule.onNodeWithText(FIXED_LINE).assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()

        // The mark alone: the row is still open, so this is not a failure wearing an offline label.
        val stored = runBlocking { db.generationDao().get(queuedRow) }!!
        assertEquals(GenerationStatus.QUEUED, stored.status)
        assertEquals(Generation.COACH_OFFLINE, stored.error)
    }

    // pendingCardShowsThatMissionControlIsNotSetUpAndNoRetry — host and token left blank, so advance queued no
    // row for the sortie and the card has nothing to report but where the host and token go. A Retry there would
    // be refused, so the card draws none.
    @Test
    fun pendingCardShowsThatMissionControlIsNotSetUpAndNoRetry() {
        val viewModel = runBlocking { pendingCard(configured = false) }
        render(viewModel)

        awaitText(NOT_CONFIGURED_MESSAGE)

        composeRule.onNodeWithText(NOT_CONFIGURED_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText(PLANNING_LINE).assertDoesNotExist()
        composeRule.onNodeWithText(OFFLINE_LINE).assertDoesNotExist()
        composeRule.onNodeWithText(FIXED_LINE).assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()

        assertEquals(
            "advance queued nothing for the sortie without a host and token",
            0L,
            runBlocking { db.generationDao().countAll() },
        )
    }

    // pendingCardShowsAFailedGenerationsReasonAndEachValidatorErrorOnItsOwnLineAndRetry — the row marked FAILED by
    // the worker's own write, carrying a refusal that names two things the coach got wrong. The domain splits it
    // into a reason and a list, and the card draws each on its own line: the row's own words, never re-split here.
    // Retry is drawn because this is a failure the app could ask the coach about again.
    @Test
    fun pendingCardShowsAFailedGenerationsReasonAndEachValidatorErrorOnItsOwnLineAndRetry() {
        val viewModel = runBlocking {
            val model = pendingCard()
            failOpenRow("invalid output: sets must not be empty; reps must be at least 1")
            model
        }
        render(viewModel)

        awaitText("invalid output")

        composeRule.onNodeWithText("invalid output").assertIsDisplayed()
        composeRule.onNodeWithText("sets must not be empty").assertIsDisplayed()
        composeRule.onNodeWithText("reps must be at least 1").assertIsDisplayed()
        // Each exact-text match found its own node, so nothing here is one line wearing the whole error string.
        composeRule.onNodeWithText("invalid output: sets must not be empty; reps must be at least 1")
            .assertDoesNotExist()
        composeRule.onNodeWithText(FIXED_LINE).assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertIsDisplayed().assertIsEnabled()

        val stored = runBlocking { db.generationDao().get(queuedRow) }!!
        assertEquals(GenerationStatus.FAILED, stored.status)
        assertEquals(0, queuedIds.size)
    }

    // tappingRetryOnAFailedPendingCardQueuesAFreshGenerationAndShowsThePlanningLine — tapping that Retry goes
    // through the view model's regenerate, which queues a brand-new QUEUED row rather than resuming the failed
    // one. The card answers with the newest non-cancelled row, so the fresh row wins createdAt DESC, id DESC and
    // the failure's lines are gone; the failed row keeps its FAILED status and its reason, because a regenerate
    // supersedes it instead of rewriting it, and it was never open so the cancel helper was handed nothing.
    @Test
    fun tappingRetryOnAFailedPendingCardQueuesAFreshGenerationAndShowsThePlanningLine() {
        val viewModel = runBlocking {
            val model = pendingCard()
            failOpenRow("invalid output: sets must not be empty; reps must be at least 1")
            model
        }
        render(viewModel)

        awaitText("RETRY")
        composeRule.onNodeWithText("RETRY").performClick()

        awaitText(PLANNING_LINE)

        composeRule.onNodeWithText(PLANNING_LINE).assertIsDisplayed()
        composeRule.onNodeWithText("invalid output").assertDoesNotExist()
        composeRule.onNodeWithText("sets must not be empty").assertDoesNotExist()
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()

        val failed = runBlocking { db.generationDao().get(queuedRow) }!!
        assertEquals(GenerationStatus.FAILED, failed.status)
        assertEquals("invalid output: sets must not be empty; reps must be at least 1", failed.error)

        val open = runBlocking { db.generationDao().getOpenFlightPlanForSortie(sortieId) }
        assertEquals(1, open.size)
        assertEquals(GenerationStatus.QUEUED, open.single().status)
        assertEquals(listOf(open.single().id), queuedIds)
        assertTrue("the fresh row is the newer id", open.single().id > queuedRow)
        assertEquals(0, cancelledIds.size)
    }

    // retryIsAbsentOnAFailedRunSortieWhileGenerateRunPlansIsOffAndDrawnWhileItIsOn — the same failure on a RUN
    // sortie, which is the only card whose Retry depends on regenerateAvailable rather than on the failure alone.
    // 'Generate run plans' is on while the sortie is prepared, so the run waits on the coach and Retry is drawn;
    // switching it off under the open card re-emits the card through the settings flow in its combine, and the
    // Retry goes away while the failure's own words stay. Nothing re-prepares the sortie here, so it is still
    // PENDING with the same failed row: the toggle decides what the app would accept, not what already happened.
    @Test
    fun retryIsAbsentOnAFailedRunSortieWhileGenerateRunPlansIsOffAndDrawnWhileItIsOn() {
        val viewModel = runBlocking {
            val model = pendingCard(pattern = null, runPlans = true)
            failOpenRow("coach refused")
            model
        }
        render(viewModel)

        awaitText("coach refused")
        composeRule.onNodeWithText("coach refused").assertIsDisplayed()
        composeRule.onNodeWithText("RETRY").assertIsDisplayed().assertIsEnabled()

        val runSortie = runBlocking { db.sortieDao().get(sortieId) }!!
        assertEquals(SortieType.RUN, runSortie.type)
        assertTrue("advance queued a row for the run sortie", queuedRow > 0L)

        runBlocking { settingsStore.setGenerateRunPlans(false) }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("RETRY").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
        composeRule.onNodeWithText("coach refused").assertIsDisplayed()
        composeRule.onNodeWithText(PLANNING_LINE).assertDoesNotExist()

        val after = runBlocking { db.sortieDao().get(sortieId) }!!
        assertEquals(SortieState.PENDING, after.state)
        assertEquals(GenerationStatus.FAILED, runBlocking { db.generationDao().get(queuedRow) }!!.status)
        assertEquals(0, queuedIds.size)
    }

    private companion object {
        const val PLANNING_LINE = "Coach is planning\u2026"
        const val OFFLINE_LINE = "Mission Control offline \u2014 retrying"
        const val FIXED_LINE = "Flight Plan not ready yet."
    }
}
