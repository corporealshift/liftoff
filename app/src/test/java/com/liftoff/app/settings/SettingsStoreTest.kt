package com.liftoff.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var dataStore: androidx.datastore.core.DataStore<Preferences>
    private lateinit var store: SettingsStore
    private var scopeJob = Job()

    private fun makeDataStore(): androidx.datastore.core.DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined + scopeJob)
        ) {
            File(tmp.root, "settings.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        scopeJob.cancel()
    }

    private fun setUpStore(): SettingsStore {
        dataStore = makeDataStore()
        return SettingsStore(dataStore)
    }

    @Test
    fun freshStoreHasAllDefaults() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        val s = store.settings.first()

        assertEquals("", s.daemonHost)
        assertEquals(8737, s.daemonPort)
        assertEquals("", s.daemonToken)
        assertEquals("", s.coachWorkspacePath)
        assertEquals("RLRLR", s.defaultPattern)
        assertEquals(60, s.sortieLengthMinutes)
        assertEquals(WeightUnit.LB, s.weightUnit)
        assertEquals(DistanceUnit.MI, s.distanceUnit)
        assertEquals(28, s.historyWindowDays)
        assertFalse(s.generateRunPlans)
        assertEquals("", s.objectives)
        assertEquals("", s.constraints)

        // Also verify the data class equals all-defaults instance
        assertEquals(Settings(), s)
    }

    @Test
    fun roundTripsDaemonHost() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDaemonHost("192.168.1.50")
        val s = store.settings.first()
        assertEquals("192.168.1.50", s.daemonHost)
    }

    @Test
    fun roundTripsDaemonPort() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDaemonPort(1)
        assertEquals(1, store.settings.first().daemonPort)
        store.setDaemonPort(65535)
        assertEquals(65535, store.settings.first().daemonPort)
    }

    @Test
    fun roundTripsDaemonToken() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDaemonToken("my-secret-token")
        val s = store.settings.first()
        assertEquals("my-secret-token", s.daemonToken)
    }

    @Test
    fun roundTripsCoachWorkspacePath() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setCoachWorkspacePath("/home/user/workspace")
        val s = store.settings.first()
        assertEquals("/home/user/workspace", s.coachWorkspacePath)
    }

    @Test
    fun roundTripsDefaultPattern() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDefaultPattern("R")
        assertEquals("R", store.settings.first().defaultPattern)
        store.setDefaultPattern("RLRLRLR")
        assertEquals("RLRLRLR", store.settings.first().defaultPattern)
    }

    @Test
    fun roundTripsSortieLengthMinutes() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setSortieLengthMinutes(1)
        assertEquals(1, store.settings.first().sortieLengthMinutes)
        store.setSortieLengthMinutes(120)
        assertEquals(120, store.settings.first().sortieLengthMinutes)
    }

    @Test
    fun roundTripsWeightUnit() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setWeightUnit(WeightUnit.KG)
        assertEquals(WeightUnit.KG, store.settings.first().weightUnit)
        store.setWeightUnit(WeightUnit.LB)
        assertEquals(WeightUnit.LB, store.settings.first().weightUnit)
    }

    @Test
    fun roundTripsDistanceUnit() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDistanceUnit(DistanceUnit.KM)
        assertEquals(DistanceUnit.KM, store.settings.first().distanceUnit)
        store.setDistanceUnit(DistanceUnit.MI)
        assertEquals(DistanceUnit.MI, store.settings.first().distanceUnit)
    }

    @Test
    fun roundTripsHistoryWindowDays() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setHistoryWindowDays(1)
        assertEquals(1, store.settings.first().historyWindowDays)
        store.setHistoryWindowDays(90)
        assertEquals(90, store.settings.first().historyWindowDays)
    }

    @Test
    fun roundTripsGenerateRunPlans() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setGenerateRunPlans(true)
        assertTrue(store.settings.first().generateRunPlans)
        store.setGenerateRunPlans(false)
        assertFalse(store.settings.first().generateRunPlans)
    }

    @Test
    fun roundTripsObjectives() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setObjectives("Build strength, improve mobility")
        assertEquals("Build strength, improve mobility", store.settings.first().objectives)
    }

    @Test
    fun roundTripsConstraints() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setConstraints("No elbow flare")
        assertEquals("No elbow flare", store.settings.first().constraints)
    }

    @Test
    fun settingOneFieldLeavesOthersUnchanged() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        // Set a non-default value for one field
        store.setDaemonHost("10.0.0.1")
        val s = store.settings.first()
        assertEquals("10.0.0.1", s.daemonHost)
        // Everything else stays default
        assertEquals(8737, s.daemonPort)
        assertEquals("", s.daemonToken)
        assertEquals("", s.coachWorkspacePath)
        assertEquals("RLRLR", s.defaultPattern)
        assertEquals(60, s.sortieLengthMinutes)
        assertEquals(WeightUnit.LB, s.weightUnit)
        assertEquals(DistanceUnit.MI, s.distanceUnit)
        assertEquals(28, s.historyWindowDays)
        assertFalse(s.generateRunPlans)
        assertEquals("", s.objectives)
        assertEquals("", s.constraints)
    }

    @Test
    fun settingsFlowEmitsUpdates() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()

        // Collect initial value
        val s1 = store.settings.first()
        assertEquals("", s1.daemonHost)

        // Write a new value
        store.setDaemonHost("new-host")

        // Collect again — should see the update
        val s2 = store.settings.first()
        assertEquals("new-host", s2.daemonHost)
    }

    @Test
    fun valuesPersistAcrossStoreInstances() = runBlocking(Dispatchers.Unconfined + Job()) {
        // First store writes a value
        val store1 = setUpStore()
        store1.setDaemonHost("persist-host")
        store1.setDaemonPort(9999)

        // Cancel the scope so the first DataStore fully shuts down before we create a new one.
        // We need to reassign because scopeJob is a var now.
        val oldJob = scopeJob
        scopeJob = Job()
        oldJob.cancel()

        // Create a new DataStore and store on the same file
        dataStore = makeDataStore()
        val store2 = SettingsStore(dataStore)

        val s = store2.settings.first()
        assertEquals("persist-host", s.daemonHost)
        assertEquals(9999, s.daemonPort)
    }

    @Test
    fun rejectsInvalidPatternAndKeepsPrevious() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        // Set a valid non-default pattern first
        store.setDefaultPattern("RRL")
        assertEquals("RRL", store.settings.first().defaultPattern)

        val invalidPatterns = listOf("", "RLRLRLRL", "RLX", "rlr", "R L")
        for (pattern in invalidPatterns) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking(Dispatchers.Unconfined + Job()) { store.setDefaultPattern(pattern) }
            }
            // Previous value should still be stored
            assertEquals("RRL", store.settings.first().defaultPattern)
        }
    }

    @Test
    fun rejectsPortOutOfRangeAndKeepsPrevious() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDaemonPort(9000)
        assertEquals(9000, store.settings.first().daemonPort)

        val invalidPorts = listOf(0, 65536, -1)
        for (port in invalidPorts) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking(Dispatchers.Unconfined + Job()) { store.setDaemonPort(port) }
            }
            assertEquals(9000, store.settings.first().daemonPort)
        }
    }

    @Test
    fun rejectsNonPositiveSortieLengthAndKeepsPrevious() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setSortieLengthMinutes(45)
        assertEquals(45, store.settings.first().sortieLengthMinutes)

        val invalidLengths = listOf(0, -5)
        for (length in invalidLengths) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking(Dispatchers.Unconfined + Job()) { store.setSortieLengthMinutes(length) }
            }
            assertEquals(45, store.settings.first().sortieLengthMinutes)
        }
    }

    @Test
    fun rejectsNonPositiveHistoryWindowAndKeepsPrevious() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setHistoryWindowDays(14)
        assertEquals(14, store.settings.first().historyWindowDays)

        val invalidWindows = listOf(0, -1)
        for (window in invalidWindows) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking(Dispatchers.Unconfined + Job()) { store.setHistoryWindowDays(window) }
            }
            assertEquals(14, store.settings.first().historyWindowDays)
        }
    }

    @Test
    fun rejectsLowercasePatternAndKeepsPrevious() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        store.setDefaultPattern("RLR")
        assertEquals("RLR", store.settings.first().defaultPattern)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking(Dispatchers.Unconfined + Job()) { store.setDefaultPattern("rlr") }
        }
        assertEquals("RLR", store.settings.first().defaultPattern)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking(Dispatchers.Unconfined + Job()) { store.setDefaultPattern("Rl") }
        }
        assertEquals("RLR", store.settings.first().defaultPattern)
    }

    @Test
    fun unreadableStoredUnitsFallBackToDefaults() = runBlocking(Dispatchers.Unconfined + Job()) {
        // Write corrupt unit values directly via DataStore
        dataStore = makeDataStore()
        runBlocking(Dispatchers.Unconfined + Job()) {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.WEIGHT_UNIT] = "stone"
                prefs[SettingsKeys.DISTANCE_UNIT] = "furlong"
            }
        }
        store = SettingsStore(dataStore)

        val s = store.settings.first()
        assertEquals(WeightUnit.LB, s.weightUnit)
        assertEquals(DistanceUnit.MI, s.distanceUnit)
    }

    @Test
    fun freeTextFieldsAreStoredVerbatim() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()
        // Values with leading/trailing spaces and newlines should be stored verbatim
        store.setDaemonHost(" host-with-spaces ")
        store.setDaemonToken("\ntoken\n")
        store.setCoachWorkspacePath(" /path/with/spaces ")
        store.setObjectives("  obj  ")
        store.setConstraints("  con  ")

        val s = store.settings.first()
        assertEquals(" host-with-spaces ", s.daemonHost)
        assertEquals("\ntoken\n", s.daemonToken)
        assertEquals(" /path/with/spaces ", s.coachWorkspacePath)
        assertEquals("  obj  ", s.objectives)
        assertEquals("  con  ", s.constraints)
    }

    @Test
    fun invalidValueThrowsIllegalArgumentException() = runBlocking(Dispatchers.Unconfined + Job()) {
        store = setUpStore()

        // Pattern rejection throws IllegalArgumentException
        try {
            runBlocking(Dispatchers.Unconfined + Job()) { store.setDefaultPattern("") }
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }

        // Port rejection throws IllegalArgumentException
        try {
            runBlocking(Dispatchers.Unconfined + Job()) { store.setDaemonPort(0) }
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }

        // Sortie length rejection throws IllegalArgumentException
        try {
            runBlocking(Dispatchers.Unconfined + Job()) { store.setSortieLengthMinutes(-1) }
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }

        // History window rejection throws IllegalArgumentException
        try {
            runBlocking(Dispatchers.Unconfined + Job()) { store.setHistoryWindowDays(0) }
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }
}
