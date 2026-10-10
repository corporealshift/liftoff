package com.liftoff.app.ui.inflight

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.robolectric.annotation.Config
import com.liftoff.app.AppContainer
import com.liftoff.app.data.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Verify that the In-Flight placeholder shows the sortie number (index + 1), not the database id. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class InFlightScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var container: AppContainer
    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context)
    }

    @After
    fun tearDown() {
        container.database.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    // ── inFlightShowsSortieNumberNotDatabaseId ────────────────────────
    // The In-Flight screen reads the sortie by its database id (a long that grows across weeks)
    // and must display "SORTIE N" where N = index + 1, not the raw row id. With a manually
    // inserted sortie whose db id is large and index = 3, the eyebrow must contain "SORTIE 4".

    @Test
    fun inFlightShowsSortieNumberNotDatabaseId() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // Insert a sortie with index = 3 (so number = 4). DB id is auto-generated.
            container.database.sortieDao().insert(Sortie(
                missionId = mission.id,
                index = 3,
                type = SortieType.RUN,
                focus = "easy jog",
                focusRationale = null,
                state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(),
                landedAt = null,
                scrubReason = null,
                notes = null,
                runDistance = null,
                runMinutes = null,
            ))

            val sorties = container.database.sortieDao().getForMission(mission.id)
            val sortieId = sorties[0].id  // db id is auto-generated, likely large (e.g. 999)

            composeRule.setContent {
                val shellScope = rememberCoroutineScope()
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            // Wait for the async LaunchedEffect to load both plan title and sortie number.
            composeRule.waitForIdle()

            // The eyebrow must contain "SORTIE 4" (index 3 + 1), NOT the db id.
            composeRule.onNodeWithText("IN FLIGHT · SORTIE 4", ignoreCase = false).assertIsDisplayed()
        }
    }

    // ── inFlightFallbackTitleUsesSortieNumber ─────────────────────────
    // When no plan exists, the title falls back to "Sortie N" (index + 1), not the db id.

    @Test
    fun inFlightFallbackTitleUsesSortieNumber() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // Insert a sortie with index 0 (so number = 1), no plan.
            container.database.sortieDao().insert(Sortie(
                missionId = mission.id,
                index = 0,
                type = SortieType.LIFT,
                focus = "Bench",
                focusRationale = null,
                state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(),
                landedAt = null,
                scrubReason = null,
                notes = null,
                runDistance = null,
                runMinutes = null,
            ))

            val sorties = container.database.sortieDao().getForMission(mission.id)
            val sortieId = sorties[0].id  // db id ≠ 1

            composeRule.setContent {
                val shellScope = rememberCoroutineScope()
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            composeRule.waitForIdle()

            // The eyebrow must contain "SORTIE 1" (index 0 + 1).
            composeRule.onNodeWithText("IN FLIGHT · SORTIE 1", ignoreCase = false).assertIsDisplayed()
        }
    }
}
