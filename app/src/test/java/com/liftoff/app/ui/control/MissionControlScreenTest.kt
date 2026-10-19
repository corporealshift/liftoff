package com.liftoff.app.ui.control

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.liftoff.app.data.ImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2800dp")  // tall window so all content, BACKUP card included, is on-screen
@OptIn(ExperimentalCoroutinesApi::class)
class MissionControlScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: com.liftoff.app.settings.SettingsStore
    private var scopeJob = Job()
    private var database: com.liftoff.app.data.LiftoffDatabase? = null

    // The BACKUP tests drive the screen's own view model, so they own its scope and its worker thread:
    // the work has to leave the thread that calls the action, like it does in the app.
    private val backupScopeJob = Job()
    private val backupScope = CoroutineScope(backupScopeJob + Dispatchers.Unconfined)
    private val backupWorkerPool: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, BACKUP_WORKER_THREAD) }
    private val backupWorker: ExecutorCoroutineDispatcher = backupWorkerPool.asCoroutineDispatcher()

    // The screen observes Room through flows, so the content leaves the composition before tearDown
    // closes the database it is reading.
    private val mounted = MutableStateFlow(true)
    private var contentMounted = false

    private fun newStore(fileName: String): com.liftoff.app.settings.SettingsStore {
        dataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + scopeJob)
        ) {
            File(tmp.root, fileName)
        }
        return com.liftoff.app.settings.SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        try {
            if (contentMounted) {
                mounted.value = false
                composeRule.waitForIdle()
                contentMounted = false
            }
        } finally {
            backupScopeJob.cancel()
            backupWorker.close()
            scopeJob.cancel()
            database?.close()
            database = null
        }
    }

    // =========================================================================
    // opensShowingStoredValues — Robolectric: opening Mission Control shows
    // stored host, port, pattern etc.
    // =========================================================================

    @Test
    fun opensShowingStoredValues() {
        runBlocking {
            val store = newStore("test1.preferences_pb")

            store.setDaemonHost("192.168.1.50")
            store.setDaemonPort(9999)
            store.setDaemonToken("tok-123")
            store.setCoachWorkspacePath("/coaches/workspace")
            store.setDefaultPattern("RRRLL")
            store.setSortieLengthMinutes(45)
            store.setHistoryWindowDays(7)
            store.setWeightUnit(com.liftoff.app.settings.WeightUnit.KG)
            store.setDistanceUnit(com.liftoff.app.settings.DistanceUnit.KM)
            store.setGenerateRunPlans(true)
            store.setObjectives("Build strength")
            store.setConstraints("No elbow flare")

            launchScreen(store)

            composeRule.onNodeWithText("192.168.1.50").assertIsDisplayed()
            composeRule.onNodeWithText("9999").assertIsDisplayed()
            // Token is masked by default — tap to reveal it.
            composeRule.onNodeWithContentDescription("Show token").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("tok-123").assertIsDisplayed()
            composeRule.onNodeWithText("/coaches/workspace").assertIsDisplayed()

            // Pattern chips render as individual characters, not concatenated.
            val rNodes = composeRule.onAllNodesWithText("R").fetchSemanticsNodes()
            assertEquals(3, rNodes.size)
            val lNodes = composeRule.onAllNodesWithText("L").fetchSemanticsNodes()
            assertEquals(2, lNodes.size)

            composeRule.onNodeWithText("45").assertIsDisplayed()
            composeRule.onNodeWithText("7").assertIsDisplayed()
        }
    }

    // =========================================================================
    // invalidPortShowsErrorMessage — Robolectric: an invalid port shows its
    // message on screen and the store keeps the old port.
    // =========================================================================

    @Test
    fun invalidPortShowsErrorMessage() {
        runBlocking {
            val store = newStore("test2.preferences_pb")
            store.setDaemonPort(5000)
            launchScreen(store)

            composeRule.onNodeWithText("5000").assertIsDisplayed()

            // Find the Port text field by its label and type invalid input.
            composeRule.onNodeWithText("Port").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Port").performTextInput("abc")
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Port must be a number from 1 to 65535").assertIsDisplayed()
            // Store should keep the old port.
            assertEquals(5000, store.settings.first().daemonPort)
        }
    }

    // =========================================================================
    // patternButtonsAndChipTapEditPattern — Robolectric: tapping +, - and a
    // chip changes the stored pattern accordingly.
    // =========================================================================

    @Test
    fun patternButtonsAndChipTapEditPattern() {
        runBlocking {
            val store = newStore("test3.preferences_pb")
            store.setDefaultPattern("RLR")
            launchScreen(store)

            // Pattern chips render as individual characters.
            assertEquals(2, composeRule.onAllNodesWithText("R").fetchSemanticsNodes().size)
            assertEquals(1, composeRule.onAllNodesWithText("L").fetchSemanticsNodes().size)

            // Tap the + button to add a chip.
            composeRule.onNodeWithText("+").performClick()
            composeRule.waitForIdle()

            assertEquals("RLRR", store.settings.first().defaultPattern)

            // Tap the - button to remove the last chip.
            composeRule.onNodeWithText("-").performClick()
            composeRule.waitForIdle()

            assertEquals("RLR", store.settings.first().defaultPattern)

            // Tap a chip to toggle it (L → R).
            composeRule.onAllNodesWithText("L")[0].performClick()
            composeRule.waitForIdle()

            assertEquals("RRR", store.settings.first().defaultPattern)
        }
    }

    // =========================================================================
    // tappingUnitSegmentsSavesUnits — Robolectric: tapping unit segments
    // saves the selection.
    // =========================================================================

    @Test
    fun tappingUnitSegmentsSavesUnits() {
        runBlocking {
            val store = newStore("test4.preferences_pb")
            store.setWeightUnit(com.liftoff.app.settings.WeightUnit.LB)
            store.setDistanceUnit(com.liftoff.app.settings.DistanceUnit.MI)
            launchScreen(store)

            // LB should be selected.
            composeRule.onNodeWithText("LB").assertIsSelected()

            // Tap KG to change weight unit.
            composeRule.onNodeWithText("KG").performClick()
            composeRule.waitForIdle()
            assertEquals(com.liftoff.app.settings.WeightUnit.KG, store.settings.first().weightUnit)
            composeRule.onNodeWithText("KG").assertIsSelected()

            // Tap KM to change distance unit.
            composeRule.onNodeWithText("KM").performClick()
            composeRule.waitForIdle()
            assertEquals(com.liftoff.app.settings.DistanceUnit.KM, store.settings.first().distanceUnit)
            composeRule.onNodeWithText("KM").assertIsSelected()
        }
    }

    // =========================================================================
    // showsEverySectionHeadInOrder — Robolectric: one card per section, each
    // with its head in order.
    // =========================================================================

    @Test
    fun showsEverySectionHeadInOrder() {
        runBlocking {
            val store = newStore("test5.preferences_pb")
            launchScreen(store)

            // Verify all section heads appear and are displayed.
            val heads = listOf(
                "CONNECTION", "COACH", "MISSION", "UNITS", "RUNS",
                "OBJECTIVES AND CONSTRAINTS", "EQUIPMENT", "BACKUP"
            )
            for (head in heads) {
                composeRule.onNodeWithText(head).assertIsDisplayed()
            }
        }
    }

    // =========================================================================
    // showsNoUnbuiltPlaceholders — Robolectric: no unbuilt Test connection placeholder.
    // =========================================================================

    @Test
    fun showsNoUnbuiltPlaceholders() {
        runBlocking {
            val store = newStore("test6.preferences_pb")
            launchScreen(store)

            composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

            // These texts should NOT appear on screen.
            assertDoesNotExist("Test connection", ignoreCase = true)
        }
    }

    private fun assertDoesNotExist(text: String, ignoreCase: Boolean = false) {
        val found = try {
            composeRule.onNodeWithText(text, ignoreCase = ignoreCase).assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            if (e.message?.contains("No nodes were found") == true ||
                e.message?.contains("but was displayed") != true) {
                false
            } else {
                throw e
            }
        }
        if (found) {
            throw AssertionError("Expected '$text' to not exist on screen")
        }
    }

    // =========================================================================
    // typingValidValueSavesWithoutSaveButton — Robolectric: typing a valid
    // value saves without needing a Save button.
    // =========================================================================

    @Test
    fun typingValidValueSavesWithoutSaveButton() {
        runBlocking {
            val store = newStore("test7.preferences_pb")
            store.setDaemonHost("old-host")
            launchScreen(store)

            composeRule.onNodeWithText("old-host").assertIsDisplayed()

            // Use performTextReplacement to replace the full field content.
            composeRule.onNodeWithText("Daemon host").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Daemon host").performTextReplacement("new-host")
            composeRule.waitForIdle()

            assertEquals("new-host", store.settings.first().daemonHost)
        }
    }

    // =========================================================================
    // patternButtonsDisabledAtLimits — Robolectric: + disabled at 7, -
    // disabled at 1.
    // =========================================================================

    @Test
    fun patternButtonsDisabledAtLimits() {
        runBlocking {
            val store = newStore("test8.preferences_pb")
            store.setDefaultPattern("RRRRRRR")
            launchScreen(store)

            // Verify 7 chips.
            assertEquals(7, composeRule.onAllNodesWithText("R").fetchSemanticsNodes().size)

            // The + button should be disabled — tapping it should not change the pattern.
            val initialPattern = store.settings.first().defaultPattern
            composeRule.onNodeWithText("+").performClick()
            composeRule.waitForIdle()
            assertEquals("Adding a chip at 7 should be ignored", initialPattern, store.settings.first().defaultPattern)

            // Tap - six times to get down to "R".
            for (i in 1..6) {
                composeRule.onNodeWithText("-").performClick()
                composeRule.waitForIdle()
            }
            assertEquals(1, composeRule.onAllNodesWithText("R").fetchSemanticsNodes().size)

            // Now - should be disabled.
            val patternBefore = store.settings.first().defaultPattern
            composeRule.onNodeWithText("-").performClick()
            composeRule.waitForIdle()
            assertEquals("Removing a chip at 1 should be ignored", patternBefore, store.settings.first().defaultPattern)
        }
    }

    // =========================================================================
    // tappingGenerateRunPlansRowTogglesSetting — Robolectric: tapping the
    // Generate run plans row toggles the setting.
    // =========================================================================

    @Test
    fun tappingGenerateRunPlansRowTogglesSetting() {
        runBlocking {
            val store = newStore("test9.preferences_pb")
            store.setGenerateRunPlans(false)
            launchScreen(store)

            composeRule.onNodeWithText("CONNECTION").assertIsDisplayed()

            assertEquals(false, store.settings.first().generateRunPlans)

            // The whole row should be clickable.
            composeRule.onNodeWithText("Generate run plans").performClick()
            composeRule.waitForIdle()

            assertEquals(true, store.settings.first().generateRunPlans)

            // Tap again to toggle off.
            composeRule.onNodeWithText("Generate run plans").performClick()
            composeRule.waitForIdle()

            assertEquals(false, store.settings.first().generateRunPlans)
        }
    }

    // =========================================================================
    // tokenRevealButtonShowsAndHidesToken — Robolectric: eye icon toggles
    // masking.
    // =========================================================================

    @Test
    fun tokenRevealButtonShowsAndHidesToken() {
        runBlocking {
            val store = newStore("test10.preferences_pb")
            store.setDaemonToken("secret-token")
            launchScreen(store)

            // Token is masked by default — the plaintext should NOT be visible.
            assertDoesNotExist("secret-token")

            // The eye icon should be present — tap "Show token" to reveal.
            composeRule.onNodeWithContentDescription("Show token").performClick()
            composeRule.waitForIdle()

            // Now the token text is visible.
            composeRule.onNodeWithText("secret-token").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Hide token").assertIsDisplayed()

            // Tap "Hide token" to mask again.
            composeRule.onNodeWithContentDescription("Hide token").performClick()
            composeRule.waitForIdle()

            assertDoesNotExist("secret-token")

            // The stored token should be unchanged throughout.
            assertEquals("secret-token", store.settings.first().daemonToken)
        }
    }

    // =========================================================================
    // neverSaysSession — Robolectric: the screen never says "session".
    // =========================================================================

    @Test
    fun neverSaysSession() {
        runBlocking {
            val store = newStore("test11.preferences_pb")
            launchScreen(store)

            // Collect all visible text by checking common section/field labels.
            val allPossibleTexts = listOf(
                "CONNECTION", "COACH", "MISSION", "UNITS", "RUNS",
                "OBJECTIVES AND CONSTRAINTS", "EQUIPMENT",
                "Daemon host", "Port", "Token",
                "Coach workspace path",
                "Sortie length (minutes)", "History window (days)",
                "Weight", "Distance",
                "Generate run plans",
                "Objectives", "Constraints",
                "Add equipment", "Show deactivated"
            )

            val allText = StringBuilder()
            for (text in allPossibleTexts) {
                try {
                    composeRule.onNodeWithText(text).assertIsDisplayed()
                    allText.append(text.lowercase()).append(" ")
                } catch (_: AssertionError) {
                    // Text not found on screen, skip.
                }
            }

            val hasSession = Regex("""\bsession\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(allText.toString())

            assert(!hasSession) { "Screen should not contain the word 'session': found in $allText" }
        }
    }

    // =========================================================================
    // backupShowsExportAndImportEnabledAndTheExclusionLine — the brief's "A BACKUP section in Mission
    // Control with an Export button and an Import button" plus "a line saying settings and the daemon
    // token are not in the file". Present is not enough: a button that cannot be pressed is not an
    // action. Neither button is clicked, because a click opens a system picker that never arrives.
    // =========================================================================

    @Test
    fun backupShowsExportAndImportEnabledAndTheExclusionLine() {
        runBlocking {
            val store = newStore("backup1.preferences_pb")
            launchScreen(store)

            backupNode("BACKUP")
            backupNode("EXPORT").assertIsEnabled()
            backupNode("IMPORT").assertIsEnabled()
            backupNode("Settings and the daemon token are not in this file.")
        }
    }

    // =========================================================================
    // successAndFailureRowsAreDrawnFromTheState — the brief's "The result shows as a row ... stays until
    // the next action". The screen's injected view model is handed an in-memory stream; the work runs on
    // the injected dispatcher, which waitForIdle() does not wait for, so the row is waited for, then the
    // screen is idled and the row read again: a row that survives the idle stays until the next action
    // replaces it.
    // =========================================================================

    @Test
    fun successAndFailureRowsAreDrawnFromTheState() {
        runBlocking {
            val store = newStore("backup2.preferences_pb")
            val fileBytes = """{"header":{},"missions":[],"sorties":[]}""".toByteArray()
            val written = ByteArrayOutputStream()
            val viewModel = backupVm(
                exportWork = { out -> out.write(fileBytes) },
                importWork = { ImportResult.Refused(REFUSAL_MESSAGE) },
            )
            launchScreen(store, backupViewModel = viewModel)

            backupNode("BACKUP")
            // Idle draws no result row.
            composeRule.onNodeWithText(EXPORTED_ROW).assertDoesNotExist()

            viewModel.runExport { written }
            waitForRow(EXPORTED_ROW)
            composeRule.waitForIdle()
            backupNode(EXPORTED_ROW)
            assertArrayEquals(
                "the export wrote to the stream the screen was handed",
                fileBytes,
                written.toByteArray(),
            )

            // The data layer's own sentence, drawn unchanged, and it replaces the row above it.
            viewModel.runImport { ByteArrayInputStream(fileBytes) }
            waitForRow(REFUSAL_MESSAGE)
            composeRule.waitForIdle()
            backupNode(REFUSAL_MESSAGE)
            composeRule.onNodeWithText(EXPORTED_ROW).assertDoesNotExist()
        }
    }

    // =========================================================================
    // bothBackupButtonsAreDisabledWhileWorkIsRunning — the plan's decision that both buttons go quiet
    // while a backup action runs, and the row says which one. The fake export is parked on a latch so
    // Busy is observable on screen; the parked fake is released before the test ends and the buttons are
    // read enabled again, so neither direction of the assertion passes vacuously.
    // =========================================================================

    @Test
    fun bothBackupButtonsAreDisabledWhileWorkIsRunning() {
        runBlocking {
            val store = newStore("backup3.preferences_pb")
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val viewModel = backupVm(exportWork = {
                started.countDown()
                assertTrue("the parked export was never released", release.await(WAIT_MS, TimeUnit.MILLISECONDS))
            })
            try {
                launchScreen(store, backupViewModel = viewModel)
                viewModel.runExport { ByteArrayOutputStream() }
                assertTrue("the parked export never started", started.await(WAIT_MS, TimeUnit.MILLISECONDS))

                waitForRow("Exporting…")
                backupNode("EXPORT").assertIsNotEnabled()
                backupNode("IMPORT").assertIsNotEnabled()
                // The mirror: a button outside the card is still pressable while the backup row works.
                composeRule.onNodeWithText("ADD EQUIPMENT").performScrollTo().assertIsEnabled()
            } finally {
                release.countDown()
            }

            waitForRow(EXPORTED_ROW)
            backupNode("EXPORT").assertIsEnabled()
            backupNode("IMPORT").assertIsEnabled()
        }
    }

    // =========================================================================
    // Helper: launch the screen in the test activity
    // =========================================================================

    private fun launchScreen(
        store: com.liftoff.app.settings.SettingsStore,
        backupViewModel: BackupViewModel? = null,
    ) {
        if (database == null) {
            database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                com.liftoff.app.data.LiftoffDatabase::class.java,
            ).build()
        }
        val db = database!!
        val equipmentDao = db.equipmentDao()
        val missionManager = com.liftoff.app.data.MissionManager(db, store)

        contentMounted = true
        composeRule.setContent {
            if (mounted.collectAsState().value) {
                val shellScope = rememberCoroutineScope()
                com.liftoff.app.ui.theme.LiftoffTheme {
                    com.liftoff.app.ui.control.MissionControlScreen(
                        settingsStore = store,
                        scope = shellScope,
                        equipmentDao = equipmentDao,
                        database = db,
                        missionManager = missionManager,
                        onBack = {},
                        backupViewModel = backupViewModel,
                    )
                }
            }
        }
    }

    /** The screen's backup view model, with fake work on a worker thread this test owns. */
    private fun backupVm(
        exportWork: suspend (OutputStream) -> Unit = {},
        importWork: suspend (InputStream) -> ImportResult = { ImportResult.Restored(0, 0) },
    ): BackupViewModel = BackupViewModel(
        scope = backupScope,
        dispatcher = backupWorker,
        exportWork = exportWork,
        importWork = importWork,
    )

    /** The BACKUP card sits last, so it is scrolled into view before anything on it is read. */
    private fun backupNode(text: String): SemanticsNodeInteraction =
        composeRule.onNodeWithText(text, ignoreCase = false).performScrollTo().assertIsDisplayed()

    /** waitForIdle() does not wait for the injected dispatcher, so the row is waited for by name. */
    private fun waitForRow(text: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        private const val BACKUP_WORKER_THREAD = "backup-worker-under-test"
        private const val WAIT_MS = 5_000L
        private const val EXPORTED_ROW = "Exported the whole database."
        private const val REFUSAL_MESSAGE =
            "Import needs an empty database. This phone already has training data."
    }
}
