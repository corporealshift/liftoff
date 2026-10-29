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
 * What the PENDING card says about the coach for this sortie, in the slot the fixed "Flight Plan not ready yet."
 * line used to hold. Each case reaches a real PENDING card the way the screen does — host and token set, pattern
 * "LRL" so the current sortie is a LIFT, onAppOpen then continueWithoutOutline — and then changes what the card
 * answers by writing to the FLIGHT_PLAN row continueWithoutOutline queued. Nothing here builds a state by hand.
 *
 * The three cases are the statuses that carry no Retry: the coach is working on the plan, Mission Control cannot
 * be reached and the worker is already retrying on its own, and Mission Control was never set up. The plan's
 * Decision "Retry appears only under a failure, not under Offline or NotConfigured" is what the absence of RETRY
 * in each of them shows.
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
     * draft activated with no outline. The pattern starts with L so the current sortie is a LIFT and [advance]
     * queued a QUEUED FLIGHT_PLAN row for it; left unconfigured, it queued none and the card has no row to
     * answer.
     */
    private suspend fun pendingCard(configured: Boolean = true): LaunchpadViewModel {
        settingsStore.setDefaultPattern("LRL")
        if (configured) {
            settingsStore.setDaemonHost("127.0.0.1")
            settingsStore.setDaemonToken("t")
        }
        missionId = manager.onAppOpen().id
        manager.continueWithoutOutline(missionId) { queuedRow = it }
        sortieId = db.sortieDao().getForMission(missionId).first { it.index == 0 }.id
        // Neither helper is asked to do anything real: the row written by continueWithoutOutline is the whole
        // status, and nothing here asks WorkManager to run it.
        return LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { },
            cancelGeneration = { },
        )
    }

    /** The row continueWithoutOutline queued for this sortie, read back the way the card's join reads it. */
    private suspend fun openRow(): Generation =
        db.generationDao().getOpenFlightPlanForSortie(sortieId).single().also {
            assertEquals(queuedRow, it.id)
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

    private companion object {
        const val PLANNING_LINE = "Coach is planning\u2026"
        const val OFFLINE_LINE = "Mission Control offline \u2014 retrying"
        const val FIXED_LINE = "Flight Plan not ready yet."
    }
}
