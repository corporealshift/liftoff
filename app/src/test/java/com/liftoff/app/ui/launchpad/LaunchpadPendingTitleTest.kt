package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.StoreOutcome
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * How tall the PENDING card's title is, and where the status line sits under it.
 *
 * The PENDING title is the sortie's own focus (LaunchpadState.kt:193), which the coach writes long and in
 * all-caps; the issue's is "Lower Strength – Squat & Hinge, plus core". Drawn at the display size with no cap
 * it runs to five lines and pushes the status line to mid-screen, which is the complaint. These cases read the
 * rendered card's own geometry: the title node's height, and the status line node's top edge.
 *
 * The other three cases are the ones that say what the cap must not do. A focus that already fits is untouched,
 * so the short-focus card is still one line at the full 68 sp. The cap is a line cap and not a rule tuned to
 * the one length the issue happens to report, so a 112-character focus holds the same bound. And the fit lives
 * at the PENDING card's call site only, so the PLANNED card of the same sortie — same long focus on the row,
 * the plan's own short title drawn — is still one line at the full display size.
 *
 * The measurement is only real in Robolectric's native graphics mode: the default mode makes every character
 * 1 dp wide and every line 35 dp tall whatever the font size, so a five-line title is indistinguishable from a
 * one-line one. Density is 1.0 under these qualifiers, so a boundsInRoot pixel is already a dp, and the card's
 * inner Column is 411 − 2×20 = 371 dp wide.
 *
 * The harness is LaunchpadPendingStatusTest's: in-memory Room, a real SettingsStore, MissionManager on a fixed
 * UTC clock, and LaunchpadContent rendered apart from AppContainer. The focus is written onto the sortie row
 * continueWithoutOutline created, and the QUEUED FLIGHT_PLAN row it queued is what makes the status line read
 * "Coach is planning…". The PLANNED card is reached the way LaunchpadPlannedActionsTest's plannedLift reaches
 * one: that row closed with markSucceeded, then the coach's validated reply stored through storePlan.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchpadPendingTitleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    private var missionId: Long = -1L
    private var sortieId: Long = -1L

    /** The FLIGHT_PLAN row continueWithoutOutline queued for the lift sortie; -1 when it queued none. */
    private var queuedRow: Long = -1L

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
        // The compose rule tears down after @After, so the view model's Room flow is still collecting here.
        runBlocking { job.cancelAndJoin() }
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    /**
     * The week the card is read on, built the way the screen builds it, with [focus] written onto the sortie
     * row continueWithoutOutline created — the row it creates carries no focus at all, so a card that shows a
     * focus is a card whose row was written. The QUEUED FLIGHT_PLAN row it queued stays open, so the status
     * line is the planning one.
     */
    private suspend fun pendingCardWithFocus(focus: String): LaunchpadViewModel {
        settingsStore.setDefaultPattern("LRL")
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("t")
        missionId = manager.onAppOpen().id
        manager.continueWithoutOutline(missionId) { queuedRow = it }
        sortieId = db.sortieDao().getForMission(missionId).first { it.index == 0 }.id
        val row = db.sortieDao().get(sortieId)!!
        db.sortieDao().update(row.copy(focus = focus))
        return LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { },
            cancelGeneration = { },
        )
    }

    /**
     * The same sortie moved to PLANNED the way a generation moves it, as LaunchpadPlannedActionsTest's
     * plannedLift does it: the row continueWithoutOutline queued is closed first, because storePlan leaves it
     * alone and a freshly-stored PLANNED card would otherwise be RePlanning. [focus] stays on the sortie row —
     * the card's title is the plan's own title, so the row's focus is what a fit that reached this card would
     * have to have shrunk.
     */
    private suspend fun plannedCardWithFocus(focus: String): LaunchpadViewModel {
        val viewModel = pendingCardWithFocus(focus)
        assertTrue("continueWithoutOutline queued no FLIGHT_PLAN row for the lift sortie", queuedRow > 0L)
        db.generationDao().markSucceeded(queuedRow, seededAt + 60_000)
        val stored = manager.storePlan(
            sortieId,
            CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
        )
        assertEquals(StoreOutcome.Stored, stored)
        return viewModel
    }

    private fun render(viewModel: LaunchpadViewModel) {
        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The card's title node, found by the first words of what the title reads rather than by all of it. */
    private fun titleNodes(head: String) =
        composeRule.onAllNodesWithText(head, substring = true).fetchSemanticsNodes()

    /**
     * The title as the card finally draws it. A title that measures itself and shrinks recomposes until it
     * settles, so the measurement waits for the height to stop moving instead of reading the first frame.
     */
    private fun awaitSettledTitle(head: String): SemanticsNode {
        var previous = -1f
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val nodes = titleNodes(head)
            if (nodes.size != 1) return@waitUntil false
            val height = nodes.single().boundsInRoot.height
            val settled = height > 0f && height == previous
            previous = height
            settled
        }
        return titleNodes(head).single()
    }

    // pendingTitleWithALongFocusIsAtMostTwoLinesTall — the focus the issue reports, on the sortie row, drawn at
    // the display size with no cap: the title node measures 329 dp tall on the untouched card, five lines
    // running down past the chips row and the status line. Two lines at 68 sp is 143 dp and three is 205 dp, so
    // 175 dp is the cap the card has to hold whatever size the title ends up using.
    @Test
    fun pendingTitleWithALongFocusIsAtMostTwoLinesTall() {
        val viewModel = runBlocking { pendingCardWithFocus(LONG_FOCUS) }
        render(viewModel)
        awaitText(PLANNING_LINE)

        val title = awaitSettledTitle(TITLE_HEAD)
        val height = title.boundsInRoot.height
        assertTrue(
            "the PENDING title must hold two lines: height=$height dp bounds=${title.boundsInRoot}",
            height <= MAX_TITLE_HEIGHT_DP,
        )
    }

    // pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus — the same card, and the thing the issue actually
    // complains about: the title's height is what moves everything under it, so the untouched card's status line
    // sits at 435 dp from the top of the render. A title capped at two lines leaves it at 249 dp at 68 sp and at
    // 198 dp at 44 sp, and three lines at 68 sp put it at 311 dp, so 280 dp is the bound that a title still
    // running on cannot reach even if its own node looks capped.
    @Test
    fun pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus() {
        val viewModel = runBlocking { pendingCardWithFocus(LONG_FOCUS) }
        render(viewModel)
        awaitText(PLANNING_LINE)
        val title = awaitSettledTitle(TITLE_HEAD)

        val status = composeRule.onNodeWithText(PLANNING_LINE).fetchSemanticsNode()
        val top = status.boundsInRoot.top
        assertTrue(
            "the status line must stay near the top of the card: title=${title.boundsInRoot} " +
                "status top=$top dp status=${status.boundsInRoot}",
            top <= MAX_STATUS_TOP_DP,
        )
    }

    // pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize — the plan's Decision "A focus that already fits
    // is untouched": the card nobody complained about is the common one. "BENCH PRESS" is one line at 68 sp on
    // the untouched card at 81 dp, and 75–110 dp is that line and nothing smaller — the same words at 44 sp is
    // 59 dp and at 34 sp is 47 dp — so a cap that shrinks every title, or draws the focus at a smaller style,
    // fails here. 35 dp is what Robolectric's default graphics mode reports whatever the size, which is the
    // sign that a case is not measuring the card at all.
    @Test
    fun pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize() {
        val viewModel = runBlocking { pendingCardWithFocus(SHORT_FOCUS) }
        render(viewModel)
        awaitText(PLANNING_LINE)

        val title = awaitSettledTitle(SHORT_TITLE_HEAD)
        val height = title.boundsInRoot.height
        assertTrue(
            "the short focus must still be one line at the display size: height=$height dp " +
                "bounds=${title.boundsInRoot}",
            height >= ONE_LINE_HEIGHT_MIN_DP && height <= ONE_LINE_HEIGHT_MAX_DP,
        )
    }

    // pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall — the cap is a line cap, not a rule sized to
    // the 41-character focus this issue happens to report. The same card with a 112-character focus on the row
    // measures 701 dp tall on the untouched card, eleven lines, so the same 175 dp bound has to hold: the title
    // reaches the floor of the ladder and the cap holds there with an ellipsis. A character count that lets a
    // longer focus run on, or a ladder with no floor, fails here.
    @Test
    fun pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall() {
        val viewModel = runBlocking { pendingCardWithFocus(UNREASONABLY_LONG_FOCUS) }
        render(viewModel)
        awaitText(PLANNING_LINE)

        val title = awaitSettledTitle(TITLE_HEAD)
        val height = title.boundsInRoot.height
        assertTrue(
            "the cap must hold at the floor too: height=$height dp bounds=${title.boundsInRoot}",
            height <= MAX_TITLE_HEIGHT_DP,
        )
    }

    // plannedCardTitleIsStillOneLineAtTheFullDisplaySize — the plan's Decision "Only the PENDING title changes;
    // PLANNED keeps its display type". The same sortie, with the long focus still written on its row, moved to
    // PLANNED the way a generation moves it; the card then draws the plan's own short title, so the node to
    // measure is "PUSH + CORE", which is 81 dp tall on the untouched card. A fit that reached PlannedContent, or
    // one that changed DisplayTitle or screenTitle for every caller, fails here.
    @Test
    fun plannedCardTitleIsStillOneLineAtTheFullDisplaySize() {
        val viewModel = runBlocking { plannedCardWithFocus(LONG_FOCUS) }
        render(viewModel)
        awaitText(PLANNED_TITLE)

        val title = awaitSettledTitle(PLANNED_TITLE)
        val height = title.boundsInRoot.height
        assertTrue(
            "the PLANNED card's title must still be one line at the display size: height=$height dp " +
                "bounds=${title.boundsInRoot}",
            height >= ONE_LINE_HEIGHT_MIN_DP && height <= ONE_LINE_HEIGHT_MAX_DP,
        )

        // The card measured is a real PLANNED card of the sortie whose row carries the long focus.
        runBlocking {
            assertEquals(SortieState.PLANNED, db.sortieDao().get(sortieId)!!.state)
            assertEquals(LONG_FOCUS, db.sortieDao().get(sortieId)!!.focus)
            assertEquals(PLANNED_PLAN_TITLE, db.flightPlanDao().getPlan(sortieId)!!.plan.title)
        }
    }

    private companion object {
        /** The focus the issue reports, 41 characters, the dash an en dash (U+2013). */
        const val LONG_FOCUS = "Lower Strength \u2013 Squat & Hinge, plus core"

        /** A focus no coach would write, 112 characters, same en dash. */
        const val UNREASONABLY_LONG_FOCUS =
            "Lower Strength \u2013 Squat, Hinge, Core, Lunges, Calf Raises, Seated Cable Rows, " +
                "Face Pulls, Hamstring Curls and Abs"

        const val SHORT_FOCUS = "Bench Press"

        /** The card uppercases the title, so these are its own first words. */
        const val TITLE_HEAD = "LOWER STRENGTH"

        const val SHORT_TITLE_HEAD = "BENCH PRESS"

        /** CoachFixtures.LIFT_PLAN's title, uppercased by DisplayTitle. */
        const val PLANNED_TITLE = "PUSH + CORE"
        const val PLANNED_PLAN_TITLE = "Push + core"

        const val PLANNING_LINE = "Coach is planning\u2026"

        const val MAX_TITLE_HEIGHT_DP = 175f
        const val MAX_STATUS_TOP_DP = 280f

        /** One line at 68 sp measures 81 dp, so this is that line and no smaller size. */
        const val ONE_LINE_HEIGHT_MIN_DP = 75f
        const val ONE_LINE_HEIGHT_MAX_DP = 110f
    }
}
