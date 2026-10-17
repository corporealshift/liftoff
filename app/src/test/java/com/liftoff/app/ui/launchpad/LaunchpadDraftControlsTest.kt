package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
 * What the draft state renders for its −/+ controls, through LaunchpadContent on a view model built
 * on in-memory Room: AppContainer is what opens an on-disk liftoff.db, so the screen body is rendered
 * apart from it. No Dialog and no text field anywhere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class LaunchpadDraftControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

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

    /** A mission left in DRAFT by onAppOpen alone, at the given pattern. */
    private suspend fun draft(pattern: String): LaunchpadViewModel {
        settingsStore.setDefaultPattern(pattern)
        manager.onAppOpen()
        return LaunchpadViewModel(db, manager, scope)
    }

    // draftDrawsOneStepperPair — the draft draws its −/+ pair once, in the bottom bar beside the
    // 'N sorties' label. The row under the chips used to draw a second pair with an ASCII '-', so
    // counting '+' is what shows the duplicate is gone; the chips still render.
    @Test
    fun draftDrawsOneStepperPair() {
        val viewModel = runBlocking { draft("RLRLR") }

        composeRule.setContent {
            LiftoffTheme {
                LaunchpadContent(viewModel = viewModel, onOpenInFlight = {})
            }
        }

        // The Draft reaches the screen through Room's flow, so wait for it before reading anything.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("5 sorties").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText("+").assertCountEquals(1)
        composeRule.onAllNodesWithText("\u2212").assertCountEquals(1)
        composeRule.onAllNodesWithText("-").assertCountEquals(0)
        // The chips the stepper sits beside are still there.
        composeRule.onAllNodesWithText("R").assertCountEquals(3)
        composeRule.onAllNodesWithText("L").assertCountEquals(2)
    }
}
