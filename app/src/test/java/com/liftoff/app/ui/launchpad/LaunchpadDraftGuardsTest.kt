package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionDao
import com.liftoff.app.data.MissionManager
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
 * The `>= 7` / `<= 1` guards in LaunchpadViewModel.addChip / removeChip, which the brief says the
 * view model keeps even though the controls are disabled at those limits. View model and DAO only:
 * no Compose, no dialog, nothing typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadDraftGuardsTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
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
        missionDao = db.missionDao()

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
     * A draft left at exactly `pattern` sorties. The mission's pattern is read from
     * settings.defaultPattern inside onAppOpen's transaction, so the limit has to be set first.
     */
    private suspend fun draftAt(pattern: String): Pair<LaunchpadViewModel, LaunchpadState.Draft> {
        settingsStore.setDefaultPattern(pattern)
        manager.onAppOpen()
        val vm = LaunchpadViewModel(db, manager, scope)
        // stateIn is Lazily and both guards read state.value, so the Draft has to be collected
        // before either call or the state is still Loading and the guard is never reached.
        val draft = withTimeout(5_000L) {
            vm.state.first { it is LaunchpadState.Draft && it.pattern.length == pattern.length }
        } as LaunchpadState.Draft
        return vm to draft
    }

    private suspend fun awaitStoredPattern(
        vm: LaunchpadViewModel,
        missionId: Long,
        sorties: Int,
    ): String {
        var last = ""
        try {
            withTimeout(5_000L) {
                last = missionDao.get(missionId)!!.pattern
                while (last.length != sorties) {
                    delay(25)
                    last = missionDao.get(missionId)!!.pattern
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "stored pattern never reached $sorties sorties; last=$last " +
                    "state=${vm.state.value} escaped=${escaped.map { it.toString() }}"
            )
        }
        return last
    }

    // addChip on a seven-sortie draft: the `>= 7` guard returns before any write, so the stored
    // pattern is still 7 long. removeChip on that same draft is the control that shows the view
    // model does write when a guard is not in the way.
    @Test
    fun addChipAtSevenLeavesPatternUnchanged() = runBlocking {
        val (vm, draft) = draftAt("RLRLRLR")
        assertEquals(7, draft.pattern.length)

        vm.addChip()

        assertEquals("RLRLRLR", missionDao.get(draft.missionId)!!.pattern)

        vm.removeChip()
        assertEquals("RLRLRL", awaitStoredPattern(vm, draft.missionId, 6))
        assertTrue(escaped.isEmpty())
    }

    // The mirror: removeChip on a one-sortie draft leaves the stored pattern 1 long, and addChip
    // there is the control that shows the write path works at the lower limit.
    @Test
    fun removeChipAtOneLeavesPatternUnchanged() = runBlocking {
        val (vm, draft) = draftAt("R")
        assertEquals(1, draft.pattern.length)

        vm.removeChip()

        assertEquals("R", missionDao.get(draft.missionId)!!.pattern)

        vm.addChip()
        assertEquals("RR", awaitStoredPattern(vm, draft.missionId, 2))
        assertTrue(escaped.isEmpty())
    }
}
