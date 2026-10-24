package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.SortieState
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scrubbing from the Launchpad, through its view model and in-memory Room. The reason now arrives as
 * the dialog's Confirm parameter, so what the Launchpad does with it is what gets stored: trimmed at
 * the ends, null when it is blank. No Compose, no dialog, nothing typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadScrubTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var sortieDao: SortieDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val job = Job()
    private val escaped = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, t -> escaped.add(t) }
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        sortieDao = db.sortieDao()

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
        // stateIn(Lazily) keeps the Room flow collecting for the scope's lifetime.
        job.cancel()
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    /**
     * A two-sortie week (R-L) whose current sortie is PLANNED, which is the state the Launchpad's
     * scrub acts on. The mission has to be ACTIVE or MissionManager.scrub refuses it, and the state
     * flow has to be collected before the scrub because confirmScrub reads state.value.
     */
    private suspend fun plannedLaunchpad(): Pair<LaunchpadViewModel, LaunchpadState.Planned> {
        settingsStore.setDefaultPattern("RL")
        val mission = manager.onAppOpen()
        manager.continueWithoutOutline(mission.id)

        val vm = LaunchpadViewModel(db, manager, scope)
        val planned = withTimeout(5_000L) {
            vm.state.first { it is LaunchpadState.Planned }
        } as LaunchpadState.Planned
        return vm to planned
    }

    /** The sortie row once the scrub has reached the database, and the dialog has closed. */
    private suspend fun awaitScrubbed(vm: LaunchpadViewModel, sortieId: Long): Sortie {
        var row: Sortie? = null
        try {
            withTimeout(5_000L) {
                row = sortieDao.get(sortieId)
                while (row == null || row!!.state != SortieState.SCRUBBED) {
                    delay(25)
                    row = sortieDao.get(sortieId)
                }
            }
            // scrubbing is cleared in the launch block's finally, which resumes after the write.
            while (vm.scrubbing) delay(25)
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "sortie $sortieId never became SCRUBBED with the dialog closed; " +
                    "last=$row scrubbing=${vm.scrubbing} state=${vm.state.value} " +
                    "escaped=${escaped.map { it.toString() }}"
            )
        }
        return row!!
    }

    // Scrub with '  sore knee  ': the sortie is SCRUBBED and the stored reason is 'sore knee', so the
    // padding a keyboard leaves is gone rather than stored verbatim. The dialog is closed afterwards.
    @Test
    fun launchpadScrubTrimsTheReason() = runBlocking {
        val (vm, planned) = plannedLaunchpad()

        vm.requestScrub()
        vm.confirmScrub("  sore knee  ")

        val scrubbed = awaitScrubbed(vm, planned.sortieId)
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("sore knee", scrubbed.scrubReason)
        assertFalse(vm.scrubbing)
        assertTrue(escaped.isEmpty())
    }

    // Scrub with '   ': the sortie is still SCRUBBED but its reason is null, not a run of spaces —
    // the same rule In-Flight applies to a reason or a note.
    @Test
    fun launchpadScrubStoresBlankReasonAsNull() = runBlocking {
        val (vm, planned) = plannedLaunchpad()

        vm.requestScrub()
        vm.confirmScrub("   ")

        val scrubbed = awaitScrubbed(vm, planned.sortieId)
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertNull(scrubbed.scrubReason)
        assertFalse(vm.scrubbing)
        assertTrue(escaped.isEmpty())
    }

    // Scrub with '  sore\nknee  ': the stored reason is 'sore\nknee'. Only the ends are trimmed, so a
    // reason typed over two lines keeps its interior line break.
    @Test
    fun launchpadScrubKeepsInteriorLineBreaks() = runBlocking {
        val (vm, planned) = plannedLaunchpad()

        vm.requestScrub()
        vm.confirmScrub("  sore\nknee  ")

        val scrubbed = awaitScrubbed(vm, planned.sortieId)
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("sore\nknee", scrubbed.scrubReason)
        assertFalse(vm.scrubbing)
        assertTrue(escaped.isEmpty())
    }
}
