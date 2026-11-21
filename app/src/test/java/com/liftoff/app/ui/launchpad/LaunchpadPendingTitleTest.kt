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
 * The measurement is only real in Robolectric's native graphics mode: the default mode makes every character
 * 1 dp wide and every line 35 dp tall whatever the font size, so a five-line title is indistinguishable from a
 * one-line one. Density is 1.0 under these qualifiers, so a boundsInRoot pixel is already a dp, and the card's
 * inner Column is 411 − 2×20 = 371 dp wide.
 *
 * The harness is LaunchpadPendingStatusTest's: in-memory Room, a real SettingsStore, MissionManager on a fixed
 * UTC clock, and LaunchpadContent rendered apart from AppContainer. The focus is written onto the sortie row
 * continueWithoutOutline created, and the QUEUED FLIGHT_PLAN row it queued is what makes the status line read
 * "Coach is planning…".
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
        manager.continueWithoutOutline(missionId) { }
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

    /** The card's title node, found by the first words of the focus rather than by all of it. */
    private fun titleNodes() =
        composeRule.onAllNodesWithText(TITLE_HEAD, substring = true).fetchSemanticsNodes()

    /**
     * The title as the card finally draws it. A title that measures itself and shrinks recomposes until it
     * settles, so the measurement waits for the height to stop moving instead of reading the first frame.
     */
    private fun awaitSettledTitle(): SemanticsNode {
        var previous = -1f
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val nodes = titleNodes()
            if (nodes.size != 1) return@waitUntil false
            val height = nodes.single().boundsInRoot.height
            val settled = height > 0f && height == previous
            previous = height
            settled
        }
        return titleNodes().single()
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

        val title = awaitSettledTitle()
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
        val title = awaitSettledTitle()

        val status = composeRule.onNodeWithText(PLANNING_LINE).fetchSemanticsNode()
        val top = status.boundsInRoot.top
        assertTrue(
            "the status line must stay near the top of the card: title=${title.boundsInRoot} " +
                "status top=$top dp status=${status.boundsInRoot}",
            top <= MAX_STATUS_TOP_DP,
        )
    }

    private companion object {
        /** The focus the issue reports, 41 characters, the dash an en dash (U+2013). */
        const val LONG_FOCUS = "Lower Strength \u2013 Squat & Hinge, plus core"

        /** The card uppercases the title, so these are its own first words. */
        const val TITLE_HEAD = "LOWER STRENGTH"

        const val PLANNING_LINE = "Coach is planning\u2026"

        const val MAX_TITLE_HEIGHT_DP = 175f
        const val MAX_STATUS_TOP_DP = 280f
    }
}
