package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.FlightPlanDetail
import com.liftoff.app.data.FlightPlanDraft
import com.liftoff.app.data.FlightPlanDao
import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SetDraft
import com.liftoff.app.data.ExerciseDraft
import com.liftoff.app.domain.markPlanned
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
 * Re-fly on the Launchpad's PENDING screen, rendered through LaunchpadContent on a view model built on
 * in-memory Room: the offer comes from the derived state, one tap writes the copy, and the screen moves
 * on its own to the Planned state. No confirmation dialog is opened anywhere, so no Dialog and no text
 * field is ever reached.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadReFlyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val escaped = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, t -> escaped.add(t) }
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

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
        // The compose rule tears down after @After, so the screen's stateIn(Lazily) flow is still
        // collecting here and has to stop before the database closes.
        runBlocking { job.cancelAndJoin() }
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    private fun liftPlan(title: String) = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = title, estimatedMinutes = 45,
        warmup = null, notes = null, runKind = null, targetDistance = null,
        targetPace = null, rawJson = "{}",
        exercises = listOf(
            ExerciseDraft(
                name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                sets = listOf(SetDraft(reps = 8, seconds = null, weight = 135.0))
            )
        ),
        segments = emptyList()
    )

    private fun render(viewModel: LaunchpadViewModel) {
        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }
    }

    /** The action row's own controls, so a wait on them is a wait on the derived state arriving. */
    private fun awaitOnScreen(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A two-LIFT week whose first sortie landed with a Flight Plan, so the sortie that follows is the
     * current PENDING one with a source to re-fly from. Returns (the landed source, the current sortie).
     */
    private suspend fun landedSourceThenPending(): Pair<Sortie, Sortie> {
        settingsStore.setDefaultPattern("LL")
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        val first = sortieDao.getForMission(mission.id)[0]
        flightPlanDao.writePlan(first.id, liftPlan("Full Body A"))
        sortieDao.update(markPlanned(first))
        manager.launch(first.id)
        manager.land(first.id)

        val sorties = sortieDao.getForMission(mission.id)
        assertEquals(SortieState.LANDED, sorties[0].state)
        assertEquals(SortieState.PENDING, sorties[1].state)
        return sorties[0] to sorties[1]
    }

    /** A two-LIFT week with nothing ever landed: the current sortie is PENDING with no source. */
    private suspend fun pendingWithNothingLanded(): Sortie {
        settingsStore.setDefaultPattern("LL")
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)
        val current = sortieDao.getForMission(mission.id)[0]
        assertEquals(SortieState.PENDING, current.state)
        return current
    }

    /** The re-flown plan once it has reached the database, bounded as every wait here is. */
    private suspend fun awaitReFlownPlan(sortieId: Long): FlightPlanDetail {
        var plan: FlightPlanDetail? = null
        try {
            withTimeout(5_000L) {
                plan = flightPlanDao.getPlan(sortieId)
                while (plan == null || plan!!.plan.source != FlightPlanSource.REFLY) {
                    delay(25)
                    plan = flightPlanDao.getPlan(sortieId)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "sortie $sortieId never got a REFLY plan; last=$plan " +
                    "state=${sortieDao.get(sortieId)?.state} escaped=${escaped.map { it.toString() }}"
            )
        }
        return plan!!
    }

    // The PENDING screen shows a Re-fly control alongside Scrub when a source exists — Scrub is still
    // there and still works. The source is a landed LIFT with a plan, so the offer is the derivation's.
    @Test
    fun pendingScreenShowsReFlyBesideScrubWhenASourceExists() {
        runBlocking {
            val (source, current) = landedSourceThenPending()
            val viewModel = LaunchpadViewModel(db, manager, scope)
            render(viewModel)

            awaitOnScreen("RE-FLY")

            composeRule.onNodeWithText("RE-FLY").assertIsDisplayed()
            composeRule.onNodeWithText("RE-FLY").assertIsEnabled()
            // Scrub shares the row and is not displaced by it.
            composeRule.onNodeWithText("SCRUB").assertIsDisplayed()
            composeRule.onNodeWithText("SCRUB").assertIsEnabled()
            val reFly = composeRule.onNodeWithText("RE-FLY").fetchSemanticsNode()
            val scrub = composeRule.onNodeWithText("SCRUB").fetchSemanticsNode()
            assertTrue(
                "Re-fly sits in the action row above Scrub: reFly=${reFly.boundsInRoot} scrub=${scrub.boundsInRoot}",
                reFly.boundsInRoot.top < scrub.boundsInRoot.top,
            )
            // The line that stands in when there is no source is not shown here.
            composeRule.onAllNodesWithText("No earlier plan to re-fly.").assertCountEquals(0)

            assertEquals(SortieState.LANDED, sortieDao.get(source.id)!!.state)
            assertEquals(SortieState.PENDING, sortieDao.get(current.id)!!.state)
            assertTrue(escaped.isEmpty())
        }
    }

    // The PENDING screen with no source shows no Re-fly control at all — not a disabled one — and shows a
    // short line saying why. Scrub is still there.
    // Decision 5 (no source plan): a short line in the action row where the button would have been.
    @Test
    fun pendingScreenShowsAMutedLineAndNoReFlyWhenNoSourceExists() {
        runBlocking {
            val current = pendingWithNothingLanded()
            val viewModel = LaunchpadViewModel(db, manager, scope)
            render(viewModel)

            awaitOnScreen("SCRUB")

            // Nothing reading 're-fly' in any case is drawn, so there is no dimmed button either.
            composeRule.onAllNodesWithText("RE-FLY", ignoreCase = true).assertCountEquals(0)
            composeRule.onNodeWithText("No earlier plan to re-fly.").assertIsDisplayed()
            composeRule.onNodeWithText("SCRUB").assertIsDisplayed()
            composeRule.onNodeWithText("SCRUB").assertIsEnabled()

            assertEquals(SortieState.PENDING, sortieDao.get(current.id)!!.state)
            assertNull(flightPlanDao.getPlan(current.id))
            assertTrue(escaped.isEmpty())
        }
    }

    // One tap on Re-fly, with no confirmation step in between, writes the REFLY plan onto the current
    // PENDING sortie and the Launchpad moves to its Planned state showing the copied plan's title. The
    // source plan in history is untouched.
    // Decision 3 (one tap, no confirm dialog): tapping Re-fly acts immediately; only Scrub asks.
    @Test
    fun tappingReFlyCopiesThePlanAndMovesTheLaunchpadToPlanned() {
        runBlocking {
            val (source, current) = landedSourceThenPending()
            val sourceBefore = flightPlanDao.getPlan(source.id)!!
            val viewModel = LaunchpadViewModel(db, manager, scope)
            render(viewModel)

            awaitOnScreen("RE-FLY")
            composeRule.onNodeWithText("RE-FLY").performClick()

            // No dialog opened, so the screen is already on the planned sortie by the time the copied
            // title appears: the title is the source plan's, and the action row is the Planned one.
            awaitOnScreen("FULL BODY A")
            composeRule.onNodeWithText("FULL BODY A").assertIsDisplayed()
            composeRule.onNodeWithText("LAUNCH").assertIsDisplayed()
            composeRule.onAllNodesWithText("RE-FLY").assertCountEquals(0)

            val copied = awaitReFlownPlan(current.id)
            assertEquals(FlightPlanSource.REFLY, copied.plan.source)
            assertEquals("Full Body A", copied.plan.title)
            assertEquals(SortieState.PLANNED, sortieDao.get(current.id)!!.state)

            assertEquals(sourceBefore, flightPlanDao.getPlan(source.id))
            assertEquals(SortieState.LANDED, sortieDao.get(source.id)!!.state)
            assertTrue(escaped.isEmpty())
        }
    }
}
