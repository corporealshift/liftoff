package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.data.StoreOutcome
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * What the PLANNED card's action row draws, on the two PLANNED cards the app can actually have: a LIFT whose plan
 * the coach stored, and a RUN that 'Generate run plans' left off. Each case reaches a real PLANNED card the way the
 * screen does — host and token set, the pattern that makes the current sortie, onAppOpen, continueWithoutOutline,
 * and for the lift the coach's own validated reply stored through MissionManager.storePlan — and then reads what
 * the card drew. No state is built by hand.
 *
 * The lift card is the one where Regenerate is offered: it is drawn beside Scrub in one row, not stacked under it,
 * and neither button takes the row for itself. The run card is the other half of the same rule — an unavailable
 * Regenerate is absent, not dimmed, which is the rule PendingActions already follows — so Scrub is drawn alone and
 * full width. The re-planning line is absent from the two render-only cases: a PLANNED card's status is only ever
 * RePlanning or Idle, and here each card's newest row is closed or there is no row at all. The third case taps
 * Regenerate and reads the line back, with the plan the card already held still drawn.
 *
 * The lift fixture has to close the QUEUED FLIGHT_PLAN row continueWithoutOutline queued, with markSucceeded,
 * before it stores the plan: storePlan leaves that row alone, so a freshly-stored PLANNED card would otherwise be
 * RePlanning and would draw the very line these cases say is absent. The run fixture needs no storePlan at all —
 * advance already wrote its simple 'Run' plan and queued no generation row for it while the toggle is off.
 *
 * The harness is LaunchpadPendingStatusTest's: in-memory Room, a real SettingsStore, MissionManager on a fixed UTC
 * clock, and LaunchpadContent rendered apart from AppContainer, which would open an on-disk liftoff.db. Neither
 * generation helper is asked to do anything real, so no WorkManager runs here. SCRUB is never clicked, because
 * clicking it opens the confirmation dialog.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadPlannedActionsTest {

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

    /** The fixed clock's own instant, so a markSucceeded is stamped later than the row it closes. */
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
     * The week the card is read on, built the way the screen builds it: Mission Control set up, the pattern that
     * decides what the current sortie is, then the current week's draft activated with no outline. [pattern] starting
     * with L makes the current sortie a LIFT and queues a QUEUED FLIGHT_PLAN row for it; starting with R makes it a
     * RUN, which with 'Generate runPlans' left off gets the simple plan advance writes and no row at all. Host and
     * token are set in both cases, so a control these cases say is absent is absent for the reason the case names
     * and not because the coach was never configured.
     */
    private suspend fun week(pattern: String): LaunchpadViewModel {
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("t")
        settingsStore.setDefaultPattern(pattern)
        missionId = manager.onAppOpen().id
        manager.continueWithoutOutline(missionId) { queuedRow = it }
        sortieId = db.sortieDao().getForMission(missionId).first { it.index == 0 }.id
        // Neither helper is asked to do anything real: no row is ever open for the worker to pick up, so
        // WorkManager is not reached. They only record what they were handed.
        return LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { queuedIds.add(it) },
            cancelGeneration = { cancelledIds.add(it) },
        )
    }

    /**
     * That lift week moved to PLANNED the way a generation moves it: the row continueWithoutOutline queued closed
     * first, then the coach's validated reply stored. [StoreOutcome.Stored] and the closed row are what make the
     * card Idle rather than RePlanning.
     */
    private suspend fun plannedLift(): LaunchpadViewModel {
        val viewModel = week("LRL")
        assertTrue("continueWithoutOutline queued no FLIGHT_PLAN row for the lift sortie", queuedRow > 0L)
        db.generationDao().markSucceeded(queuedRow, seededAt + 60_000)
        val stored = manager.storePlan(
            sortieId,
            CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
        )
        assertEquals(StoreOutcome.Stored, stored)
        return viewModel
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

    // plannedLiftCardShowsRegenerateBesideScrubAndNoRePlanningLine — the PLANNED lift card the mockup draws: Launch
    // across the top, then Regenerate and Scrub as two underlined buttons in one row under it. "In one row" is what
    // the bounds show — the two overlap vertically, so neither is stacked under the other — and neither spans the
    // row on its own, which is what a weighted pair looks like against the full-width Launch above them. Regenerate
    // is the left-hand one. No re-planning line, because this card's only generation row was closed before the plan
    // was stored.
    @Test
    fun plannedLiftCardShowsRegenerateBesideScrubAndNoRePlanningLine() {
        val viewModel = runBlocking { plannedLift() }
        render(viewModel)

        awaitText("REGENERATE")

        composeRule.onNodeWithText("REGENERATE").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("SCRUB").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("LAUNCH").assertIsDisplayed().assertIsEnabled()

        val regenerate = composeRule.onNodeWithText("REGENERATE").fetchSemanticsNode().boundsInRoot
        val scrub = composeRule.onNodeWithText("SCRUB").fetchSemanticsNode().boundsInRoot
        val rowWidth = composeRule.onNodeWithText("LAUNCH").fetchSemanticsNode().boundsInRoot.width

        assertTrue(
            "Regenerate and Scrub share a row rather than stacking: regenerate=$regenerate scrub=$scrub",
            regenerate.top < scrub.bottom && scrub.top < regenerate.bottom,
        )
        assertTrue(
            "Regenerate is the left-hand button: regenerate=$regenerate scrub=$scrub",
            regenerate.left < scrub.left,
        )
        assertTrue(
            "neither button spans the row on its own: launch=$rowWidth regenerate=${regenerate.width} scrub=${scrub.width}",
            regenerate.width < rowWidth / 2f && scrub.width < rowWidth / 2f,
        )

        composeRule.onNodeWithText(RE_PLANNING_LINE).assertDoesNotExist()

        // The card is answering the rows it was derived from, not a state this test handed it.
        runBlocking {
            assertEquals(SortieState.PLANNED, db.sortieDao().get(sortieId)!!.state)
            assertEquals("Push + core", db.flightPlanDao().getPlan(sortieId)!!.plan.title)
            assertEquals(GenerationStatus.SUCCEEDED, db.generationDao().get(queuedRow)!!.status)
        }
        assertEquals(0, queuedIds.size)
        assertEquals(0, cancelledIds.size)
    }

    // tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine — the tap the card offers. The id
    // the recording enqueuer was handed is read back as a QUEUED FLIGHT_PLAN row on this Mission and this sortie, so
    // it is a generation and not a number; the re-planning line is absent before the tap and drawn after it, while
    // Launch, the plan's own title and Regenerate stay on screen and the plan stays in the table. Nothing is handed
    // to the cancel helper: the row continueWithoutOutline queued was closed before the plan was stored, so
    // regenerateFlightPlan had no open row to hand back, and the one row that appeared is the one the tap queued.
    @Test
    fun tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine() {
        val viewModel = runBlocking { plannedLift() }
        render(viewModel)

        // regenerate() reads state.value and returns null on a card that has not been derived yet, so the tap comes
        // only once the card is drawn; otherwise every read-back below would be true of a tap that did nothing.
        awaitText("REGENERATE")
        composeRule.onNodeWithText(RE_PLANNING_LINE).assertDoesNotExist()
        composeRule.onNodeWithText("REGENERATE").performTouchInput { click() }

        awaitText(RE_PLANNING_LINE)

        composeRule.onNodeWithText(RE_PLANNING_LINE).assertIsDisplayed()
        composeRule.onNodeWithText("LAUNCH").assertIsDisplayed().assertIsEnabled()
        // The plan's title, which the theme upper-cases, is still the card's title.
        composeRule.onNodeWithText("PUSH + CORE").assertIsDisplayed()
        composeRule.onNodeWithText("REGENERATE").assertIsDisplayed().assertIsEnabled()

        assertEquals(1, queuedIds.size)
        assertEquals(0, cancelledIds.size)
        runBlocking {
            val row = db.generationDao().get(queuedIds.single())
                ?: throw AssertionError("the enqueued id ${queuedIds.single()} has no generation row")
            assertEquals(GenerationKind.FLIGHT_PLAN, row.kind)
            assertEquals(GenerationStatus.QUEUED, row.status)
            assertEquals(missionId, row.missionId)
            assertEquals(sortieId, row.sortieId)
            assertTrue(
                "the tap reopened ${queuedRow} instead of queueing a fresh row",
                row.id != queuedRow,
            )
            assertEquals(2L, db.generationDao().countAll())
            assertEquals("Push + core", db.flightPlanDao().getPlan(sortieId)!!.plan.title)
            assertEquals(SortieState.PLANNED, db.sortieDao().get(sortieId)!!.state)
        }
    }

    // plannedRunCardWithRunGenerationOffShowsScrubAndNoRegenerate — DESIGN.md §7.2's other half on the card that
    // already has its plan: a RUN sortie prepared while 'Generate run plans' is off is PLANNED with the simple plan
    // advance wrote for it, so asking the coach again would be refused and the card draws no Regenerate at all —
    // absent, not dimmed. Scrub is drawn alone, and no re-planning line, because advance queued no row for it.
    @Test
    fun plannedRunCardWithRunGenerationOffShowsScrubAndNoRegenerate() {
        val viewModel = runBlocking { week("RLL") }
        render(viewModel)

        awaitText("SCRUB")

        composeRule.onNodeWithText("SCRUB").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("REGENERATE").assertDoesNotExist()
        composeRule.onNodeWithText(RE_PLANNING_LINE).assertDoesNotExist()

        runBlocking {
            val sortie = db.sortieDao().get(sortieId)!!
            assertEquals(SortieType.RUN, sortie.type)
            assertEquals(SortieState.PLANNED, sortie.state)
            assertEquals(FlightPlanSource.SIMPLE_RUN, db.flightPlanDao().getPlan(sortieId)!!.plan.source)
            assertFalse("the toggle is what makes Regenerate absent here", settingsStore.settings.first().generateRunPlans)
            assertTrue("advance queued no row for the run sortie", queuedRow < 0L)
            assertEquals(0L, db.generationDao().countAll())
        }
        assertEquals(0, queuedIds.size)
        assertEquals(0, cancelledIds.size)
    }

    private companion object {
        const val RE_PLANNING_LINE = "Coach is re-planning\u2026"
    }
}
