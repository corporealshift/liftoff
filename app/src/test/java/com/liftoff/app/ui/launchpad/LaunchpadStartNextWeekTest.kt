package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.theme.LiftoffTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Start next week on the Launchpad's closed card (DESIGN.md §4.2, amended 2026-11-08): offered on the calendar
 * week's closed Mission, one tap moves the screen to next week's draft, and an early week's own closed card
 * offers nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadStartNextWeekTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val escaped = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, t -> escaped.add(t) }
    )

    // PrimaryButton draws its label in capitals, so that is the text on the node.
    private val START_LABEL = "START NEXT WEEK"

    // A Sunday: the calendar week started 2026-01-12, and next week starts 2026-01-19.
    private val nextWeek = LocalDate.of(2026, 1, 19)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        settingsStore = SettingsStore(PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        })
        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(Instant.parse("2026-01-18T12:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    private fun viewModel() = LaunchpadViewModel(
        db = db, manager = manager, scope = scope, queueGeneration = {}, cancelGeneration = {},
    )

    private fun render(viewModel: LaunchpadViewModel) {
        composeRule.setContent {
            LiftoffTheme { LaunchpadContent(viewModel = viewModel, onOpenInFlight = {}) }
        }
    }

    /** A one-run week flown and landed, which closes the current week's Mission. */
    private suspend fun flyAndCloseTheWeek(): Long {
        settingsStore.setDefaultPattern("R")
        val mission = manager.onAppOpen()
        manager.continueWithoutOutline(mission.id)
        val run = db.sortieDao().getForMission(mission.id).single()
        manager.launch(run.id)
        manager.land(run.id, runDistance = 5.0, runMinutes = 30.0)
        assertEquals(MissionStatus.CLOSED, db.missionDao().get(mission.id)!!.status)
        return mission.id
    }

    @Test
    fun theClosedCalendarWeekOffersStartNextWeekAndOneTapOpensItsDraft() {
        runBlocking { flyAndCloseTheWeek() }
        val vm = viewModel()
        render(vm)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(START_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        val closed = vm.state.value as LaunchpadState.Closed
        assertTrue(closed.canStartNextWeek)

        composeRule.onNodeWithText(START_LABEL).performClick()

        val draft = runBlocking {
            withTimeout(5_000) { vm.state.first { it is LaunchpadState.Draft } } as LaunchpadState.Draft
        }
        assertEquals(nextWeek, draft.weekStart)
        assertEquals(MissionStatus.DRAFT, runBlocking { db.missionDao().getByWeekStart(nextWeek) }!!.status)
        assertTrue("escaped: $escaped", escaped.isEmpty())
    }

    @Test
    fun aWeekStartedEarlyOffersNoSecondStartOnceItClosesToo() {
        runBlocking {
            flyAndCloseTheWeek()
            val early = manager.startNextWeek()
            db.missionDao().update(early.copy(status = MissionStatus.CLOSED))
        }
        val vm = viewModel()
        render(vm)

        val closed = runBlocking {
            withTimeout(5_000) { vm.state.first { it is LaunchpadState.Closed } } as LaunchpadState.Closed
        }
        assertEquals(nextWeek, closed.weekStart)
        assertFalse(closed.canStartNextWeek)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(START_LABEL).assertCountEquals(0)
    }
}
