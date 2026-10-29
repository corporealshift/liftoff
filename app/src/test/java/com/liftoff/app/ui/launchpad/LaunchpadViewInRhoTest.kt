package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.SortieState
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Whether the PENDING card offers View in rho, and what its tap does. The id is already on the card's state —
 * LaunchpadState.Pending.rhoSessionId, from this sortie's newest non-cancelled FLIGHT_PLAN generation row — so
 * these cases only ask what the card draws: the control above Scrub in the bar, the 'rho session <id>' line under
 * the card's status lines, and neither at all when the row carries no id or when there is no row.
 *
 * Each case reaches a real PENDING card the way the screen does — host and token set, pattern "LRL" so the current
 * sortie is a LIFT, onAppOpen then continueWithoutOutline — and then changes what that row carries. The harness is
 * LaunchpadPendingStatusTest's: in-memory Room, a real SettingsStore, MissionManager on a fixed UTC clock, and
 * LaunchpadContent rendered apart from AppContainer. Neither generation helper is asked to do anything real, so no
 * WorkManager runs here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadViewInRhoTest {

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
     * draft activated with no outline. Left [configured], continueWithoutOutline queued one QUEUED FLIGHT_PLAN row
     * for the LIFT sortie and left it carrying no session id; unconfigured, it queued none at all.
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
        // Neither helper is asked to do anything real: the row continueWithoutOutline wrote is the whole status.
        // They only record what they were handed.
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

    /** That row, carrying [SESSION_ID] the way the worker writes it once rho answers. */
    private suspend fun putIdOnTheRow(): Generation {
        val row = openRow()
        db.generationDao().update(row.copy(nabuSessionId = SESSION_ID))
        return row
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

    // pendingCardWithASessionIdShowsViewInRhoAndTappingItRevealsTheSessionLine — the id is on the card's row, so the
    // bar offers the control; the tap prints the id as a line and opens or queues nothing.
    @Test
    fun pendingCardWithASessionIdShowsViewInRhoAndTappingItRevealsTheSessionLine() {
        val viewModel = runBlocking {
            val model = pendingCard()
            putIdOnTheRow()
            model
        }
        render(viewModel)

        awaitText(VIEW_IN_RHO)
        composeRule.onNodeWithText(VIEW_IN_RHO).assertIsDisplayed().assertIsEnabled()

        // The id is not on the card until the owner asks for it.
        composeRule.onAllNodesWithText(SESSION_ID, substring = true).assertCountEquals(0)

        composeRule.onNodeWithText(VIEW_IN_RHO).performClick()

        awaitText("rho session $SESSION_ID")
        composeRule.onNodeWithText("rho session $SESSION_ID").assertIsDisplayed()

        // The id arrived as an extra line, not in place of what the card already said.
        composeRule.onNodeWithText(PLANNING_LINE).assertIsDisplayed()
        composeRule.onNodeWithText("SCRUB").assertIsDisplayed().assertIsEnabled()

        // Both positions the brief states, each inside one Scaffold slot: the control above Scrub in the bar's
        // Column, the id line under the status line in the content Column. Nothing is compared across the two.
        val view = composeRule.onNodeWithText(VIEW_IN_RHO).fetchSemanticsNode()
        val scrub = composeRule.onNodeWithText("SCRUB").fetchSemanticsNode()
        assertTrue(
            "View in rho sits above Scrub in the bar: view=${view.boundsInRoot} scrub=${scrub.boundsInRoot}",
            view.boundsInRoot.bottom <= scrub.boundsInRoot.top,
        )
        val status = composeRule.onNodeWithText(PLANNING_LINE).fetchSemanticsNode()
        val idLine = composeRule.onNodeWithText("rho session $SESSION_ID").fetchSemanticsNode()
        assertTrue(
            "the session line sits under the card's status line: status=${status.boundsInRoot} id=$idLine.boundsInRoot",
            status.boundsInRoot.bottom <= idLine.boundsInRoot.top,
        )

        // The tap revealed the id and changed nothing else: no id was handed to either generation helper, and the
        // row is still the QUEUED row with the id it already carried.
        assertEquals(0, queuedIds.size)
        assertEquals(0, cancelledIds.size)
        val stored = runBlocking { db.generationDao().get(queuedRow) }!!
        assertEquals(GenerationStatus.QUEUED, stored.status)
        assertEquals(SESSION_ID, stored.nabuSessionId)
        assertEquals(sortieId, stored.sortieId)
        assertEquals(SortieState.PENDING, runBlocking { db.sortieDao().get(sortieId) }!!.state)
    }

    // pendingCardWithAGenerationRowThatHasNoSessionIdShowsNoViewInRho — the row is there and open, but rho has not
    // answered it yet, so there is no id to offer and nothing to reveal.
    @Test
    fun pendingCardWithAGenerationRowThatHasNoSessionIdShowsNoViewInRho() {
        val viewModel = runBlocking { pendingCard() }
        render(viewModel)

        // The card the case is asserting on has to be on screen first: during Loading there is no control either.
        awaitText(PLANNING_LINE)
        composeRule.onNodeWithText(PLANNING_LINE).assertIsDisplayed()
        composeRule.onNodeWithText("SCRUB").assertIsDisplayed()

        composeRule.onNodeWithText(VIEW_IN_RHO).assertDoesNotExist()
        composeRule.onAllNodesWithText("rho session", substring = true).assertCountEquals(0)

        val stored = runBlocking { db.generationDao().get(queuedRow) }!!
        assertEquals(GenerationStatus.QUEUED, stored.status)
        assertNull(stored.nabuSessionId)
    }

    // pendingCardWithNoGenerationRowShowsNoViewInRho — Mission Control was never set up, so continueWithoutOutline
    // queued no row at all and the card has no id to offer whatever it says.
    @Test
    fun pendingCardWithNoGenerationRowShowsNoViewInRho() {
        val viewModel = runBlocking { pendingCard(configured = false) }
        render(viewModel)

        awaitText(NOT_CONFIGURED_MESSAGE)
        composeRule.onNodeWithText(NOT_CONFIGURED_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("SCRUB").assertIsDisplayed()

        assertEquals(-1L, queuedRow)
        assertTrue(runBlocking { db.generationDao().getOpenFlightPlanForSortie(sortieId) }.isEmpty())

        composeRule.onNodeWithText(VIEW_IN_RHO).assertDoesNotExist()
        composeRule.onAllNodesWithText("rho session", substring = true).assertCountEquals(0)
    }

    private companion object {
        const val SESSION_ID = "sess-7f3a-liftoff"

        /** UnderlinedTextButton upper-cases its label, so this is how the control reads on screen. */
        const val VIEW_IN_RHO = "VIEW IN RHO"

        const val PLANNING_LINE = "Coach is planning\u2026"
    }
}
