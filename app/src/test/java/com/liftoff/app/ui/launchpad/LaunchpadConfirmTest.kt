package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionDao
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.SortieDao
import com.liftoff.app.domain.currentWeekStart
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The draft's Confirm through [LaunchpadViewModel]: the QUEUED OUTLINE row it leaves for the enqueuer, the id it
 * hands that helper, and the line it shows when Mission Control has no host and token. View model and DAO only —
 * the enqueue and cancel helpers already have GenerationEnqueuerTest and GenerationCancellerTest, so nothing here
 * touches Compose, WorkManager or a daemon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadConfirmTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var generationDao: GenerationDao
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val queuedIds = CopyOnWriteArrayList<Long>()
    private val cancelledIds = CopyOnWriteArrayList<Long>()
    private val escaped = CopyOnWriteArrayList<Throwable>()

    private val job = Job()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, t -> escaped.add(t) }
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        generationDao = db.generationDao()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()

        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = MissionManager(db, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
    }

    @After
    fun tearDown() {
        // stateIn(Lazily) keeps the Room flow collecting for the scope's lifetime.
        job.cancel()
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    /**
     * The week's draft, primed the way the screen primes it: settings first, because onAppOpen reads
     * defaultPattern for the new Mission, then the state collected, because every action returns early while it is
     * still Loading. The pattern starts with L so the first sortie stays PENDING rather than PLANNED.
     */
    private suspend fun primedDraft(
        host: String,
        token: String,
    ): Pair<LaunchpadViewModel, LaunchpadState.Draft> {
        settingsStore.setDaemonHost(host)
        settingsStore.setDaemonToken(token)
        settingsStore.setDefaultPattern("LRL")
        manager.onAppOpen()
        val vm = LaunchpadViewModel(
            db = db,
            manager = manager,
            scope = scope,
            queueGeneration = { queuedIds.add(it) },
            cancelGeneration = {
                // The row is marked cancelled as GenerationCanceller marks it, so a cancel read back is real.
                cancelledIds.add(it)
                generationDao.markCancelled(it, clock.millis())
            },
        )
        val draft = withTimeout(5_000L) {
            vm.state.first { it is LaunchpadState.Draft }
        } as LaunchpadState.Draft
        return vm to draft
    }

    /** A null Job is the action never having started, which would make every assertion below vacuous. */
    private suspend fun joinAction(launched: Job?, vm: LaunchpadViewModel) {
        val started = launched ?: throw AssertionError("action returned no Job; state=${vm.state.value}")
        withTimeout(5_000L) { started.join() }
    }

    private suspend fun assertStillADraft(draft: LaunchpadState.Draft) {
        val weekStart = currentWeekStart(clock, ZoneOffset.UTC)
        val mission = missionDao.get(draft.missionId)!!
        assertEquals(MissionStatus.DRAFT, mission.status)
        assertEquals(weekStart, mission.weekStart)
        assertEquals(draft.missionId, missionDao.getByWeekStart(weekStart)!!.id)
        assertTrue(sortieDao.getForMission(draft.missionId).isEmpty())
    }

    private suspend fun assertNothingQueued() {
        assertEquals(0L, generationDao.countAll())
        assertTrue(generationDao.getAll().isEmpty())
        assertTrue(queuedIds.isEmpty())
        assertTrue(cancelledIds.isEmpty())
    }

    /** The one row a Confirm queued for this draft, read back from the database. */
    private suspend fun queuedOutlineFor(missionId: Long): Generation {
        val rows = generationDao.getAll().filter { it.missionId == missionId }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(GenerationKind.OUTLINE, row.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertNull(row.sortieId)
        return row
    }

    /**
     * One setting the brief names, on its own fresh draft and database: Confirm writes no row and hands nothing
     * to either helper, and reports the line the draft shows. Then the settings are fixed and Confirm again on the
     * same draft, which must clear the line and queue one row.
     */
    private suspend fun confirmReportsNotConfiguredThenQueuesOnceSettingsAreFixed(
        host: String,
        token: String,
    ) {
        val (vm, draft) = primedDraft(host, token)

        joinAction(vm.confirm(), vm)

        assertEquals(NOT_CONFIGURED_MESSAGE, vm.notConfiguredMessage)
        assertNothingQueued()
        assertStillADraft(draft)

        settingsStore.setDaemonHost(VALID_HOST)
        settingsStore.setDaemonToken(VALID_TOKEN)
        joinAction(vm.confirm(), vm)

        assertNull(vm.notConfiguredMessage)
        val row = queuedOutlineFor(draft.missionId)
        assertEquals(listOf(row.id), queuedIds.toList())
        assertTrue(cancelledIds.isEmpty())
        assertStillADraft(draft)
        assertTrue(escaped.isEmpty())
    }

    // Confirm with a host and token set: exactly one QUEUED OUTLINE row for the draft, its sortieId null, its id
    // recorded once by the queue lambda and nothing else, and the week still a draft with no sorties.
    @Test
    fun confirmWithMissionControlConfiguredQueuesOneOutlineGenerationForEnqueuingAndLeavesTheWeekADraft() = runBlocking {
        val (vm, draft) = primedDraft(VALID_HOST, VALID_TOKEN)

        joinAction(vm.confirm(), vm)

        val row = queuedOutlineFor(draft.missionId)
        assertEquals(listOf(row.id), queuedIds.toList())
        assertTrue(cancelledIds.isEmpty())
        assertNull(vm.notConfiguredMessage)
        assertStillADraft(draft)
        assertTrue(escaped.isEmpty())
    }

    // A blank host: the manager's configured rule refuses before the database, so no row and nothing recorded.
    @Test
    fun confirmWithABlankHostQueuesNothingAndReportsTheNotConfiguredMessage() = runBlocking {
        confirmReportsNotConfiguredThenQueuesOnceSettingsAreFixed("", VALID_TOKEN)
    }

    // The same rule trims, so a host that is only whitespace is as unconfigured as an absent one.
    @Test
    fun confirmWithAWhitespaceOnlyHostQueuesNothingAndReportsTheNotConfiguredMessage() = runBlocking {
        confirmReportsNotConfiguredThenQueuesOnceSettingsAreFixed("   ", VALID_TOKEN)
    }

    // A host with no token is the other half of the rule.
    @Test
    fun confirmWithABlankTokenQueuesNothingAndReportsTheNotConfiguredMessage() = runBlocking {
        confirmReportsNotConfiguredThenQueuesOnceSettingsAreFixed(VALID_HOST, "")
    }

    private companion object {
        const val VALID_HOST = "127.0.0.1"
        const val VALID_TOKEN = "token-under-test"
    }
}
