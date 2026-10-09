package com.liftoff.app.ui.control

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MissionControlViewModelTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: com.liftoff.app.settings.SettingsStore
    private lateinit var vm: MissionControlViewModel
    private var scopeJob = Job()

    @After
    fun tearDown() {
        scopeJob.cancel()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun newStore(fileName: String): com.liftoff.app.settings.SettingsStore {
        dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + scopeJob)
        ) {
            File(tmp.root, fileName)
        }
        return com.liftoff.app.settings.SettingsStore(dataStore)
    }

    private fun newVM(store: com.liftoff.app.settings.SettingsStore): MissionControlViewModel {
        vm = MissionControlViewModel(store, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + scopeJob))
        return vm
    }

    @Suppress("SameParameterValue")
    private fun awaitLoaded(vm: MissionControlViewModel) = runBlocking {
        withTimeout(5_000L) {
            vm.state.first { it.loaded }
        }
    }

    private fun awaitStored(check: (com.liftoff.app.settings.Settings) -> Boolean): com.liftoff.app.settings.Settings = runBlocking {
        try {
            withTimeout(5_000L) {
                store.settings.first(check)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for stored value to match predicate")
        }
    }

    // =========================================================================
    // Loading stored values
    // =========================================================================

    @Test
    fun loadsStoredValues() = runBlocking {
        store = newStore("settings1.preferences_pb")

        // Write non-default values BEFORE creating the VM so init reads them
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

        newVM(store)

        awaitLoaded(vm)

        val s = vm.state.value

        assertTrue(s.loaded)
        assertEquals("192.168.1.50", s.host)
        assertEquals("9999", s.portText)
        assertEquals("tok-123", s.token)
        assertEquals("/coaches/workspace", s.workspacePath)
        assertEquals("RRRLL", s.pattern)
        assertEquals("45", s.sortieLengthText)
        assertEquals("7", s.historyWindowText)
        assertEquals(com.liftoff.app.settings.WeightUnit.KG, s.weightUnit)
        assertEquals(com.liftoff.app.settings.DistanceUnit.KM, s.distanceUnit)
        assertTrue(s.generateRunPlans)
        assertEquals("Build strength", s.objectives)
        assertEquals("No elbow flare", s.constraints)
    }

    // =========================================================================
    // Saving each kind of field
    // =========================================================================

    @Test
    fun savesHost() = runBlocking {
        store = newStore("settings2.preferences_pb")
        newVM(store)

        vm.setHost("10.0.0.1")
        awaitStored { it.daemonHost == "10.0.0.1" }
        assertEquals("10.0.0.1", store.settings.first().daemonHost)
    }

    @Test
    fun savesPort() = runBlocking {
        store = newStore("settings3.preferences_pb")
        newVM(store)

        vm.setPortText("5000")
        awaitStored { it.daemonPort == 5000 }
        assertEquals(5000, store.settings.first().daemonPort)
    }

    @Test
    fun savesToken() = runBlocking {
        store = newStore("settings4.preferences_pb")
        newVM(store)

        vm.setToken("new-token")
        awaitStored { it.daemonToken == "new-token" }
        assertEquals("new-token", store.settings.first().daemonToken)
    }

    @Test
    fun savesWorkspacePath() = runBlocking {
        store = newStore("settings5.preferences_pb")
        newVM(store)

        vm.setWorkspacePath("/path/to/workspace")
        awaitStored { it.coachWorkspacePath == "/path/to/workspace" }
        assertEquals("/path/to/workspace", store.settings.first().coachWorkspacePath)
    }

    @Test
    fun savesSortieLength() = runBlocking {
        store = newStore("settings6.preferences_pb")
        newVM(store)

        vm.setSortieLengthText("90")
        awaitStored { it.sortieLengthMinutes == 90 }
        assertEquals(90, store.settings.first().sortieLengthMinutes)
    }

    @Test
    fun savesHistoryWindow() = runBlocking {
        store = newStore("settings7.preferences_pb")
        newVM(store)

        vm.setHistoryWindowText("14")
        awaitStored { it.historyWindowDays == 14 }
        assertEquals(14, store.settings.first().historyWindowDays)
    }

    @Test
    fun savesWeightUnit() = runBlocking {
        store = newStore("settings8.preferences_pb")
        newVM(store)

        vm.setWeightUnit(com.liftoff.app.settings.WeightUnit.KG)
        awaitStored { it.weightUnit == com.liftoff.app.settings.WeightUnit.KG }
        assertEquals(com.liftoff.app.settings.WeightUnit.KG, store.settings.first().weightUnit)
    }

    @Test
    fun savesDistanceUnit() = runBlocking {
        store = newStore("settings9.preferences_pb")
        newVM(store)

        vm.setDistanceUnit(com.liftoff.app.settings.DistanceUnit.KM)
        awaitStored { it.distanceUnit == com.liftoff.app.settings.DistanceUnit.KM }
        assertEquals(com.liftoff.app.settings.DistanceUnit.KM, store.settings.first().distanceUnit)
    }

    @Test
    fun savesGenerateRunPlans() = runBlocking {
        store = newStore("settings10.preferences_pb")
        newVM(store)

        vm.setGenerateRunPlans(true)
        awaitStored { it.generateRunPlans == true }
        assertTrue(store.settings.first().generateRunPlans)
    }

    @Test
    fun savesObjectives() = runBlocking {
        store = newStore("settings11.preferences_pb")
        newVM(store)

        vm.setObjectives("Improve mobility")
        awaitStored { it.objectives == "Improve mobility" }
        assertEquals("Improve mobility", store.settings.first().objectives)
    }

    @Test
    fun savesConstraints() = runBlocking {
        store = newStore("settings12.preferences_pb")
        newVM(store)

        vm.setConstraints("No heavy loads")
        awaitStored { it.constraints == "No heavy loads" }
        assertEquals("No heavy loads", store.settings.first().constraints)
    }

    @Test
    fun patternToggleFlipsChipAndSaves() = runBlocking {
        store = newStore("settings13.preferences_pb")
        store.setDefaultPattern("RRL")
        newVM(store)

        assertEquals("RRL", vm.state.value.pattern)

        vm.togglePatternChip(0) // R -> L
        awaitStored { it.defaultPattern == "LRL" }
        assertEquals("LRL", vm.state.value.pattern)
        assertEquals("LRL", store.settings.first().defaultPattern)

        vm.togglePatternChip(1) // R -> L
        awaitStored { it.defaultPattern == "LLL" }
        assertEquals("LLL", vm.state.value.pattern)
        assertEquals("LLL", store.settings.first().defaultPattern)
    }

    @Test
    fun patternAddAppendsRAndSaves() = runBlocking {
        store = newStore("settings14.preferences_pb")
        store.setDefaultPattern("RR")
        newVM(store)

        vm.addPatternChip()
        awaitStored { it.defaultPattern == "RRR" }
        assertEquals("RRR", vm.state.value.pattern)
        assertEquals("RRR", store.settings.first().defaultPattern)
    }

    @Test
    fun patternRemoveDropsLastChipAndSaves() = runBlocking {
        store = newStore("settings15.preferences_pb")
        store.setDefaultPattern("RLRL")
        newVM(store)

        vm.removePatternChip()
        awaitStored { it.defaultPattern == "RLR" }
        assertEquals("RLR", vm.state.value.pattern)
        assertEquals("RLR", store.settings.first().defaultPattern)
    }

    // =========================================================================
    // Survives restart: values saved through one VM load in a fresh
    // DataStore + SettingsStore + VM on the same file.
    // =========================================================================

    @Test
    fun survivesRestart() = runBlocking {
        scopeJob.cancelAndJoin()
        scopeJob = Job()
        store = newStore("settings_restart.preferences_pb")

        // First VM writes values
        newVM(store)
        vm.setHost("10.0.0.1")
        vm.setPortText("5000")
        vm.setToken("my-token")
        awaitStored { it.daemonHost == "10.0.0.1" && it.daemonPort == 5000 && it.daemonToken == "my-token" }

        // Cancel the scope so the first DataStore fully shuts down before we create a new one.
        val oldJob = scopeJob
        scopeJob = Job()
        oldJob.cancelAndJoin()

        // Create a new DataStore + Store + VM on the same file (simulating restart)
        store = newStore("settings_restart.preferences_pb")
        newVM(store)

        awaitLoaded(vm)

        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals("10.0.0.1", s.host)
        assertEquals("5000", s.portText)
        assertEquals("my-token", s.token)
    }

    // =========================================================================
    // Rejecting invalid input
    // =========================================================================

    @Test
    fun rejectsInvalidPort() = runBlocking {
        store = newStore("settings16.preferences_pb")
        newVM(store)

        vm.setPortText("9000")
        awaitStored { it.daemonPort == 9000 }

        val invalidInputs = listOf("abc", "", "0", "65536", "-1", "99999999999")
        for (input in invalidInputs) {
            vm.setPortText(input)
            assertNotNull(vm.state.value.portError)
            assertEquals(input.trim(), vm.state.value.portText)
            // Store should still have the old value
            assertEquals(9000, store.settings.first().daemonPort)
        }

        // A valid entry clears the error and saves
        vm.setPortText("1234")
        awaitStored { it.daemonPort == 1234 }
        assertNull(vm.state.value.portError)
        assertEquals(1234, store.settings.first().daemonPort)
    }

    @Test
    fun rejectsInvalidSortieLength() = runBlocking {
        store = newStore("settings17.preferences_pb")
        newVM(store)

        vm.setSortieLengthText("60")
        awaitStored { it.sortieLengthMinutes == 60 }

        val invalidInputs = listOf("abc", "", "0", "-5", "7.5")
        for (input in invalidInputs) {
            vm.setSortieLengthText(input)
            assertNotNull(vm.state.value.sortieLengthError)
            assertEquals(input.trim(), vm.state.value.sortieLengthText)
            assertEquals(60, store.settings.first().sortieLengthMinutes)
        }

        vm.setSortieLengthText("45")
        awaitStored { it.sortieLengthMinutes == 45 }
        assertNull(vm.state.value.sortieLengthError)
        assertEquals(45, store.settings.first().sortieLengthMinutes)
    }

    @Test
    fun rejectsInvalidHistoryWindow() = runBlocking {
        store = newStore("settings18.preferences_pb")
        newVM(store)

        vm.setHistoryWindowText("28")
        awaitStored { it.historyWindowDays == 28 }

        val invalidInputs = listOf("abc", "", "0", "-1")
        for (input in invalidInputs) {
            vm.setHistoryWindowText(input)
            assertNotNull(vm.state.value.historyWindowError)
            assertEquals(input.trim(), vm.state.value.historyWindowText)
            assertEquals(28, store.settings.first().historyWindowDays)
        }

        vm.setHistoryWindowText("14")
        awaitStored { it.historyWindowDays == 14 }
        assertNull(vm.state.value.historyWindowError)
        assertEquals(14, store.settings.first().historyWindowDays)
    }

    // =========================================================================
    // Draft not clobbered
    // =========================================================================

    @Test
    fun draftNotClobberedByOtherSave() = runBlocking {
        store = newStore("settings19.preferences_pb")
        newVM(store)

        vm.setHost("10.0.0.1")
        awaitStored { it.daemonHost == "10.0.0.1" }
        assertEquals(8737, store.settings.first().daemonPort)

        // Type an invalid port
        vm.setPortText("abc")
        assertNotNull(vm.state.value.portError)
        assertEquals("abc", vm.state.value.portText)

        // Save another field — the port draft and error should stay
        vm.setHost("10.0.0.2")
        awaitStored { it.daemonHost == "10.0.0.2" }
        assertNotNull(vm.state.value.portError)
        assertEquals("abc", vm.state.value.portText)
    }

    @Test
    fun reopenShowsStoredValueAfterInvalidDraft() = runBlocking {
        scopeJob.cancelAndJoin()
        scopeJob = Job()
        store = newStore("settings_reopen.preferences_pb")

        // First VM: set valid port, then make an invalid draft
        newVM(store)
        vm.setPortText("5000")
        awaitStored { it.daemonPort == 5000 }
        // Now type invalid — this should NOT write to store
        vm.setPortText("abc")
        assertNotNull(vm.state.value.portError)

        // Cancel and restart the scope so DataStore fully shuts down
        val oldJob = scopeJob
        scopeJob = Job()
        oldJob.cancelAndJoin()

        // Second VM on same file: should see the valid stored port, not the invalid draft
        store = newStore("settings_reopen.preferences_pb")
        newVM(store)

        awaitLoaded(vm)

        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals("5000", s.portText)
        assertNull(s.portError)
    }

    // =========================================================================
    // Pattern editor limits
    // =========================================================================

    @Test
    fun patternAddAtSevenIsRejected() = runBlocking {
        store = newStore("settings20.preferences_pb")
        store.setDefaultPattern("RRRRRRR")
        newVM(store)

        assertEquals(7, vm.state.value.pattern.length)

        vm.addPatternChip()
        awaitStored { it.defaultPattern == "RRRRRRR" }
        assertEquals(7, vm.state.value.pattern.length)
        assertNotNull(vm.state.value.patternError)
        // Store should still have the 7-chip pattern
        assertEquals("RRRRRRR", store.settings.first().defaultPattern)
    }

    @Test
    fun patternRemoveAtOneIsRejected() = runBlocking {
        store = newStore("settings21.preferences_pb")
        store.setDefaultPattern("R")
        newVM(store)

        assertEquals(1, vm.state.value.pattern.length)

        vm.removePatternChip()
        awaitStored { it.defaultPattern == "R" }
        assertEquals(1, vm.state.value.pattern.length)
        assertNotNull(vm.state.value.patternError)
        assertEquals("R", store.settings.first().defaultPattern)
    }

    @Test
    fun canAddChipFalseAtSeven() = runBlocking {
        store = newStore("settings22.preferences_pb")
        store.setDefaultPattern("RRRRRRR")
        newVM(store)

        awaitLoaded(vm)
        assertFalse(vm.state.value.canAddChip)
    }

    @Test
    fun canRemoveChipFalseAtOne() = runBlocking {
        store = newStore("settings23.preferences_pb")
        store.setDefaultPattern("R")
        newVM(store)

        awaitLoaded(vm)
        assertFalse(vm.state.value.canRemoveChip)
    }

    // =========================================================================
    // Numeric input is trimmed
    // =========================================================================

    @Test
    fun numericInputIsTrimmed() = runBlocking {
        store = newStore("settings24.preferences_pb")
        newVM(store)

        vm.setPortText("  8080  ")
        awaitStored { it.daemonPort == 8080 }
        assertNull(vm.state.value.portError)
        assertEquals("8080", vm.state.value.portText)
        assertEquals(8080, store.settings.first().daemonPort)

        vm.setSortieLengthText("  60  ")
        awaitStored { it.sortieLengthMinutes == 60 }
        assertNull(vm.state.value.sortieLengthError)
        assertEquals("60", vm.state.value.sortieLengthText)
        assertEquals(60, store.settings.first().sortieLengthMinutes)

        vm.setHistoryWindowText("  14  ")
        awaitStored { it.historyWindowDays == 14 }
        assertNull(vm.state.value.historyWindowError)
        assertEquals("14", vm.state.value.historyWindowText)
        assertEquals(14, store.settings.first().historyWindowDays)
    }

    // =========================================================================
    // Rejects non-whole and overflow numbers
    // =========================================================================

    @Test
    fun rejectsNonWholeAndOverflowNumbers() = runBlocking {
        store = newStore("settings25.preferences_pb")
        newVM(store)

        // Decimal number should be rejected for port
        vm.setPortText("7.5")
        assertNotNull(vm.state.value.portError)

        // Very large number that overflows Int
        vm.setPortText("999999999999")
        assertNotNull(vm.state.value.portError)

        // Decimal for sortie length
        vm.setSortieLengthText("7.5")
        assertNotNull(vm.state.value.sortieLengthError)

        // Very large number for history window
        vm.setHistoryWindowText("999999999999")
        assertNotNull(vm.state.value.historyWindowError)
    }

    // =========================================================================
    // Token visibility toggle does not write
    // =========================================================================

    @Test
    fun toggleTokenVisibilityDoesNotWrite() = runBlocking {
        store = newStore("settings26.preferences_pb")
        newVM(store)

        vm.setToken("secret")
        awaitStored { it.daemonToken == "secret" }

        assertFalse(vm.state.value.tokenVisible)
        vm.toggleTokenVisibility()
        assertTrue(vm.state.value.tokenVisible)
        // Store should still have the token, unchanged
        assertEquals("secret", store.settings.first().daemonToken)

        vm.toggleTokenVisibility()
        assertFalse(vm.state.value.tokenVisible)
    }
}
