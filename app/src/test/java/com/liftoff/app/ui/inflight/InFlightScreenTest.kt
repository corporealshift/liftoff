package com.liftoff.app.ui.inflight

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** Create a CoroutineScope for ViewModel construction in tests. */
    private fun scopeForTest(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())

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

            // Wait for async Room flow to emit state.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("IN FLIGHT \u00b7 SORTIE 4", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // The eyebrow must contain "SORTIE 4" (index 3 + 1), NOT the db id.
            composeRule.onNodeWithText("IN FLIGHT \u00b7 SORTIE 4", ignoreCase = false).assertIsDisplayed()
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

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("IN FLIGHT \u00b7 SORTIE 1", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // The eyebrow must contain "SORTIE 1" (index 0 + 1).
            composeRule.onNodeWithText("IN FLIGHT \u00b7 SORTIE 1", ignoreCase = false).assertIsDisplayed()
        }
    }

    // ── seededLiftPlanShowsHeaderProgressAndCards ─────────────────────
    // On a seeded lift plan (3 exercises, 7 sets), after checking all sets of the first
    // exercise, the screen shows the header counter "03/07", a collapsed done row reading
    // "3/3 LANDED", the active card's uppercase name ("PUSH-UP"), and an upcoming card's
    // uppercase name ("KETTLEBELL HOLD").

    @Test
    fun seededLiftPlanShowsHeaderProgressAndCards() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // Build the same fixture as InFlightViewModelTest: 3 exercises, 7 sets total.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 10, seconds = null, weight = 35.0),
                        SetDraft(reps = 10, seconds = null, weight = 35.0),
                        SetDraft(reps = 10, seconds = null, weight = 35.0)
                    )),
                    ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 12, seconds = null, weight = null),
                        SetDraft(reps = 12, seconds = null, weight = null)
                    )),
                    ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 20.0),
                        SetDraft(reps = null, seconds = 45, weight = null)
                    ))
                ),
                segments = emptyList()
            ))

            // Check all 3 bench sets via the ViewModel so Room is updated.
            val vm = InFlightViewModel(container.database, settingsStore, sortieId, scopeForTest())
            val readyState = withTimeout(5_000) {
                vm.state.first { it is InFlightState.Ready } as InFlightState.Ready
            }
            val benchSets = readyState.cards[0].sets.map { it.setId }
            for (setId in benchSets) {
                vm.check(setId)
            }
            withTimeout(5_000) {
                vm.state.first { s -> (s as? InFlightState.Ready)?.doneCount == 3 }
            }

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            // Wait for async Room flow to emit state.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("03/07\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Counter shows "03/07"
            composeRule.onNodeWithText("03/07\nSETS", ignoreCase = false).assertIsDisplayed()

            // Collapsed done row says "3/3 LANDED"
            composeRule.onNodeWithText("3/3 LANDED", ignoreCase = false).assertIsDisplayed()

            // Active card (Push-up) shows uppercase name
            composeRule.onNodeWithText("PUSH-UP", ignoreCase = false).assertIsDisplayed()

            // Upcoming card (Kettlebell Hold) shows uppercase name
            composeRule.onNodeWithText("KETTLEBELL HOLD", ignoreCase = false).assertIsDisplayed()
        }
    }

    // ── checkingASetMarksItDoneInRoomAndUpdatesCounter ────────────────
    // On a seeded test database, tapping an open set's check box updates the counter and
    // Room holds that set as DONE with actuals equal to the planned values.

    @Test
    fun checkingASetMarksItDoneInRoomAndUpdatesCounter() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // Build fixture: 3 exercises, 7 sets total.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 10, seconds = null, weight = 35.0),
                        SetDraft(reps = 10, seconds = null, weight = 35.0),
                        SetDraft(reps = 10, seconds = null, weight = 35.0)
                    )),
                    ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 12, seconds = null, weight = null),
                        SetDraft(reps = 12, seconds = null, weight = null)
                    )),
                    ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 20.0),
                        SetDraft(reps = null, seconds = 45, weight = null)
                    ))
                ),
                segments = emptyList()
            ))

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            // Wait for initial counter "00/07" to appear.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("00/07\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Tap the check box for Bench Press SET 1.
            composeRule.onNodeWithContentDescription("Check off Bench Press SET 1").performClick()

            // Wait for counter to update to "01/07".
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("01/07\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Assert in Room: first set is DONE with actuals equal to planned values.
            val plan = container.database.flightPlanDao().getPlan(sortieId)!!
            val benchSets = plan.exercises[0].sets
            assertEquals(SetStatus.DONE, benchSets[0].status)
            assertEquals(10, benchSets[0].actualReps)
            assertEquals(35.0, benchSets[0].actualWeight!!, 0.001)
        }
    }

    // ── noChecklistLandLandsAndCallsOnBack ────────────────────────────
    // On a sortie with no checklist (a RUN sortie), the footer's 'Land' lands it through the
    // MissionManager and calls the onBack callback. There is no checklist, so there are no open
    // sets to warn about and no confirmation dialog: the land happens on the first tap.

    @Test
    fun noChecklistLandLandsAndCallsOnBack() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // Insert a RUN sortie with no flight plan → NoChecklist state.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id,
                index = 0,
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

            // Landing advances the Mission, and advance() only works on an ACTIVE one.
            container.database.missionDao().update(mission.copy(status = MissionStatus.ACTIVE))

            // The callback is what the shell uses to leave In-Flight, so answering it here is how the
            // test takes the screen down: its Room flow is cancelled with it, before the database is
            // read again and before tearDown closes it.
            var backCalled by mutableStateOf(false)

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    if (!backCalled) {
                        InFlightScreen(
                            sortieId = sortieId,
                            container = container,
                            onBack = { backCalled = true },
                        )
                    }
                }
            }

            // Wait until the no-checklist message appears.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("No checklist for this sortie.", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("LAND", ignoreCase = false).performClick()

            composeRule.waitUntil(5_000) { backCalled }

            // No confirmation dialog opened, so the land is already done by the time the screen is gone.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("No checklist for this sortie.", ignoreCase = false).fetchSemanticsNodes().isEmpty()
            }

            val sortie = container.database.sortieDao().get(sortieId)!!
            assertEquals(SortieState.LANDED, sortie.state)
            assertTrue("onBack callback should have been called", backCalled)
        }
    }

    // ── landFooterOffersLandAndScrubAndNoBack ─────────────────────────
    // The footer of a loaded sortie offers Land and Scrub and no longer offers Back, on both the
    // checklist state and the no-checklist state. Nothing is clicked: this only reads the nodes the
    // owner is shown.

    @Test
    fun landFooterOffersLandAndScrubAndNoBack() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // A LIFT sortie with a plan (2 exercises, 5 sets) → Ready.
            val liftId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(liftId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 10, seconds = null, weight = 35.0),
                        SetDraft(reps = 10, seconds = null, weight = 35.0)
                    )),
                    ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 12, seconds = null, weight = null),
                        SetDraft(reps = 12, seconds = null, weight = null)
                    )),
                    ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 20.0)
                    ))
                ),
                segments = emptyList()
            ))

            // A RUN sortie with no plan → NoChecklist.
            val runId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 0, type = SortieType.RUN,
                focus = "easy jog", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))

            // One composition, two sorties: setContent can only be called once per test.
            var sortieId by mutableStateOf(liftId)

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    key(sortieId) {
                        InFlightScreen(
                            sortieId = sortieId,
                            container = container,
                            onBack = {},
                        )
                    }
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("00/05\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }
            assertFooterOffersLandAndScrub()

            sortieId = runId

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("No checklist for this sortie.", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }
            assertFooterOffersLandAndScrub()
        }
    }

    /** The footer's Land button and Scrub action, and the absence of the Back action it replaced. */
    private fun assertFooterOffersLandAndScrub() {
        composeRule.onNodeWithText("LAND", ignoreCase = false).assertExists()
        composeRule.onNodeWithText("SCRUB", ignoreCase = false).assertExists()
        assertTrue(
            "the footer must not offer Back",
            composeRule.onAllNodesWithText("BACK", ignoreCase = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    // ── footerAddSetAndSkipUnskip ────────────────────────────────────
    // Tapping '+ Add set' on the active card adds one more set to that exercise
    // and increases the counter total. Tapping 'Skip' then 'Unskip' on an
    // upcoming card toggles the exercise's `skipped` flag in Room.

    @Test
    fun footerAddSetAndSkipUnskip() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // Build fixture: 2 exercises, 5 sets total.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 10, seconds = null, weight = 35.0),
                        SetDraft(reps = 10, seconds = null, weight = 35.0)
                    )),
                    ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 12, seconds = null, weight = null),
                        SetDraft(reps = 12, seconds = null, weight = null)
                    )),
                    ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 20.0)
                    ))
                ),
                segments = emptyList()
            ))

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            // Wait for initial counter "00/05" to appear.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("00/05\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // ── Tap '+ Add set' on the active card (Bench Press, index 0) ──
            // FooterCell upper-cases labels, so text is "+ ADD SET".
            composeRule.onAllNodesWithText("+ ADD SET", ignoreCase = false).get(0).performClick()

            // Counter total should go up: "00/06"
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("00/06\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Room should have one more set for the Bench Press exercise.
            val planAfterAdd = container.database.flightPlanDao().getPlan(sortieId)!!
            val benchExercise = planAfterAdd.exercises.first { it.displayName == "Bench Press" }
            assertEquals(3, benchExercise.sets.size)

            // ── Tap 'Skip' on the Kettlebell Hold card (index 2) ─────────
            composeRule.onAllNodesWithText("SKIP", ignoreCase = false).get(2).performClick()

            // After skipping, the card collapses to a row showing its name.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Kettlebell Hold").fetchSemanticsNodes().isNotEmpty()
            }

            // Room: Kettlebell Hold should be skipped.
            val planAfterSkip = container.database.flightPlanDao().getPlan(sortieId)!!
            val kettlebellExercise = planAfterSkip.exercises.first { it.displayName == "Kettlebell Hold" }
            assertTrue(kettlebellExercise.plannedExercise.skipped)

            // ── Expand the skipped row, then Unskip ──────────────────────
            composeRule.onNodeWithText("Kettlebell Hold").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("UNSKIP", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("UNSKIP").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("UNSKIP", ignoreCase = false).fetchSemanticsNodes().isEmpty()
            }

            // Room: Kettlebell Hold should no longer be skipped.
            val planAfterUnskip = container.database.flightPlanDao().getPlan(sortieId)!!
            val kettlebellExercise2 = planAfterUnskip.exercises.first { it.displayName == "Kettlebell Hold" }
            assertFalse(kettlebellExercise2.plannedExercise.skipped)
        }
    }

    // ── doneBodyweightSetStepperShowsBW ───────────────────────────────
    // A DONE set whose actualWeight is null (bodyweight) must open in the
    // stepper showing "BW" (0), not the planned weight. Tapping the value cell
    // opens the stepper; the weight line reads "BW". After saving, Room still
    // holds actualWeight = null.

    @Test
    fun doneBodyweightSetStepperShowsBW() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "BW", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "BW workout", estimatedMinutes = 20,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Pull-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 35.0),
                        SetDraft(reps = 8, seconds = null, weight = 40.0)
                    ))
                ),
                segments = emptyList()
            ))

            // Seed Room directly: SET 1 done as BW (null weight), SET 2 still OPEN.
            val plan = container.database.flightPlanDao().getPlan(sortieId)!!
            val setId1 = plan.exercises[0].sets[0].id
            container.database.flightPlanDao().saveSetEdit(setId1, 8, null, null)

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            // Wait for the "Pull-up SET 1 done" content description (CheckBox DONE label).
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription("Pull-up SET 1 done", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Tap the weight cell ("BW") to open the stepper dialog.
            composeRule.onNodeWithText("BW").performClick()

            // Stepper title must appear, confirming the dialog is open.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("PULL-UP · SET 1", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Planned weight must NOT be present in the stepper (the fix seeds from actuals, not planned).
            // "BW" appears twice: once in the set row on screen, once in the stepper.
            composeRule.onAllNodesWithText("35 LB").assertCountEquals(0)
            composeRule.onAllNodesWithText("BW").assertCountEquals(2)

            // Fix reps: tap the second "+" button (count line).
            composeRule.onAllNodesWithText("+")[1].performClick()

            // Tap Save.
            composeRule.onNodeWithText("SAVE").performClick()

            // Wait for the screen to redraw from Room: the stepper title must be gone.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("PULL-UP · SET 1", ignoreCase = false).fetchSemanticsNodes().isEmpty()
            }

            // Then verify Room: status DONE, reps=9, weight=null.
            val planAfter = container.database.flightPlanDao().getPlan(sortieId)!!
            assertEquals(SetStatus.DONE, planAfter.exercises[0].sets[0].status)
            assertEquals(9, planAfter.exercises[0].sets[0].actualReps)
            assertTrue("actualWeight should remain null for BW", planAfter.exercises[0].sets[0].actualWeight == null)
        }
    }

    // ── openSetStepperShowsPlannedWeight ──────────────────────────────
    // An OPEN set must seed the stepper from the planned weight, not actuals.
    // A planned 25 LB set should show "25 LB" in the stepper dialog.

    @Test
    fun openSetStepperShowsPlannedWeight() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Press", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Press day", estimatedMinutes = 20,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Overhead Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 6, seconds = null, weight = 25.0),
                        SetDraft(reps = 6, seconds = null, weight = 30.0)
                    ))
                ),
                segments = emptyList()
            ))

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            // Wait for initial counter.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("00/02\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            // Tap the weight cell ("25 LB") to open the stepper dialog.
            composeRule.onNodeWithText("25 LB").performClick()

            // The stepper weight line should show "25 LB" (the planned weight).
            // After opening, there are 2 occurrences: one on main screen, one in stepper.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("25 LB", ignoreCase = false).fetchSemanticsNodes().size >= 2
            }

            // Cancel (UnderlinedTextButton uppercases).
            composeRule.onNodeWithText("CANCEL").performClick()
        }
    }

    // ── seededExerciseNoteShowsMutedLineOnCard ────────────────────────
    // A note stored on one exercise shows on that card as a muted display line, with nothing
    // opened: the line comes from the state derived from Room, so no dialog window is on screen.

    @Test
    fun seededExerciseNoteShowsMutedLineOnCard() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Press", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Press day", estimatedMinutes = 20,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Overhead Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 6, seconds = null, weight = 25.0),
                        SetDraft(reps = 6, seconds = null, weight = 30.0)
                    )),
                    ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 12, seconds = null, weight = null)
                    ))
                ),
                segments = emptyList()
            ))

            // Seed straight through the DAO — a dumb write, no view model involved.
            val plan = container.database.flightPlanDao().getPlan(sortieId)!!
            container.database.flightPlanDao().saveExerciseNote(
                plan.exercises[0].plannedExercise.id, "Left shoulder twinge"
            )

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Left shoulder twinge", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Left shoulder twinge", ignoreCase = false).assertIsDisplayed()
            // Nothing was opened, so the line is the card's own, not a dialog's field.
            composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        }
    }

    // ── sortieNoteActionAndLineShowOnChecklistState ───────────────────
    // On a lift sortie that has a Flight Plan, the 'Sortie note' action sits under the header
    // bands above the cards, and a note stored on the sortie row shows as a muted line under it.
    // UnderlinedTextButton upper-cases, so the action reads "SORTIE NOTE".

    @Test
    fun sortieNoteActionAndLineShowOnChecklistState() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Press", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Press day", estimatedMinutes = 20,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Overhead Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 6, seconds = null, weight = 25.0),
                        SetDraft(reps = 6, seconds = null, weight = 30.0)
                    )),
                    ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 12, seconds = null, weight = null)
                    ))
                ),
                segments = emptyList()
            ))

            // Seed the sortie row's own note straight through the DAO — a dumb write.
            container.database.sortieDao().updateNotes(sortieId, "Bar felt balanced")

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Bar felt balanced", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("SORTIE NOTE", ignoreCase = false).assertIsDisplayed()
            composeRule.onNodeWithText("SORTIE NOTE", ignoreCase = false).assertIsEnabled()
            composeRule.onNodeWithText("Bar felt balanced", ignoreCase = false).assertIsDisplayed()
            // The line is the screen's own; nothing was opened to make it appear.
            composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        }
    }

    // ── seededSortieNoteShowsOnNoChecklistState ───────────────────────
    // The no-checklist state used to show only the placeholder. The action and the note line are
    // added there too, so both the stored note and "No checklist for this sortie." are on screen.

    @Test
    fun seededSortieNoteShowsOnNoChecklistState() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // A RUN sortie with no Flight Plan → NoChecklist state, carrying its own note.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id,
                index = 0,
                type = SortieType.RUN,
                focus = "easy jog",
                focusRationale = null,
                state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(),
                landedAt = null,
                scrubReason = null,
                notes = "Felt strong",
                runDistance = null,
                runMinutes = null,
            ))

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            composeRule.waitUntil(5_000) {
                val note = composeRule.onAllNodesWithText("Felt strong", ignoreCase = false).fetchSemanticsNodes()
                val placeholder = composeRule.onAllNodesWithText("No checklist for this sortie.", ignoreCase = false).fetchSemanticsNodes()
                note.isNotEmpty() && placeholder.isNotEmpty()
            }

            composeRule.onNodeWithText("Felt strong", ignoreCase = false).assertIsDisplayed()
            composeRule.onNodeWithText("No checklist for this sortie.", ignoreCase = false).assertIsDisplayed()
            composeRule.onNodeWithText("SORTIE NOTE", ignoreCase = false).assertIsDisplayed()
            composeRule.onNodeWithText("SORTIE NOTE", ignoreCase = false).assertIsEnabled()
            composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        }
    }

    // ── noteCellOnActiveCardOpensDialogAndSaves ─────────────────────
    // The card's Note cell is enabled, and no editor is on screen until it is tapped.
    // Opening the dialog is not asserted here: a BasicTextField inside a Dialog never lets Compose
    // go idle under Robolectric (plan Decision 13). NoteDialogTest covers the dialog's pre-fill
    // and Save, InFlightViewModelTest the trim rule.

    @Test
    fun noteCellOnActiveCardOpensDialogAndSaves() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // One exercise only, so the screen has exactly one "NOTE" cell to tap.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id, index = 1, type = SortieType.LIFT,
                focus = "Press", focusRationale = null, state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(), landedAt = null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null
            ))
            container.database.flightPlanDao().writePlan(sortieId, FlightPlanDraft(
                source = FlightPlanSource.GENERATED, title = "Press day", estimatedMinutes = 20,
                warmup = null, notes = null, runKind = null, targetDistance = null,
                targetPace = null, rawJson = "{}",
                exercises = listOf(
                    ExerciseDraft(name = "Overhead Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                        SetDraft(reps = 6, seconds = null, weight = 25.0),
                        SetDraft(reps = 6, seconds = null, weight = 30.0)
                    ))
                ),
                segments = emptyList()
            ))

            // Seed untrimmed, straight through the DAO — a dumb write that keeps the spaces.
            val plan = container.database.flightPlanDao().getPlan(sortieId)!!
            container.database.flightPlanDao().saveExerciseNote(
                plan.exercises[0].plannedExercise.id, "  Left shoulder twinge  "
            )

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("00/02\nSETS", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("NOTE", ignoreCase = false).assertIsDisplayed()
            composeRule.onNodeWithText("NOTE", ignoreCase = false).assertIsEnabled()
            // No editor on screen yet: the field can only come from the dialog.
            composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        }
    }

    // ── sortieNoteActionOnNoChecklistOpensDialogAndSaves ────────────
    // The no-checklist state had no way to reach a note at all before this run: the 'Sortie note'
    // action is on screen and enabled there, with no editor until it is tapped.
    // Opening the dialog is not asserted here: a BasicTextField inside a Dialog never lets Compose
    // go idle under Robolectric (plan Decision 13). NoteDialogTest covers the dialog's pre-fill
    // and Save, InFlightViewModelTest the trim rule.

    @Test
    fun sortieNoteActionOnNoChecklistOpensDialogAndSaves() {
        runBlocking {
            val settingsStore = com.liftoff.app.settings.SettingsStore(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                    File(context.cacheDir, "settings.preferences_pb")
                }
            )
            val manager = MissionManager(container.database, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
            val mission = manager.onAppOpen()

            // A RUN sortie with no Flight Plan → NoChecklist state, note seeded untrimmed on the row.
            val sortieId = container.database.sortieDao().insert(Sortie(
                missionId = mission.id,
                index = 0,
                type = SortieType.RUN,
                focus = "easy jog",
                focusRationale = null,
                state = SortieState.IN_FLIGHT,
                launchedAt = System.currentTimeMillis(),
                landedAt = null,
                scrubReason = null,
                notes = "  Felt strong  ",
                runDistance = null,
                runMinutes = null,
            ))

            composeRule.setContent {
                com.liftoff.app.ui.theme.LiftoffTheme {
                    InFlightScreen(
                        sortieId = sortieId,
                        container = container,
                        onBack = {},
                    )
                }
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("No checklist for this sortie.", ignoreCase = false).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("SORTIE NOTE", ignoreCase = false).assertIsDisplayed()
            composeRule.onNodeWithText("SORTIE NOTE", ignoreCase = false).assertIsEnabled()
            // No editor on screen yet: the field can only come from the dialog.
            composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        }
    }
}
