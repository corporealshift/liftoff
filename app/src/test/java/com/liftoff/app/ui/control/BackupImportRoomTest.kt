package com.liftoff.app.ui.control

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.Equipment
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.data.exportDatabase
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The import action and the flows the other tabs read, on one database. The file exported from a seeded
 * database goes into a fresh one through BackupViewModel's app wiring — the real `importDatabase` and
 * `MissionManager`, not a fake — while `observeWeek`, `observeHistory` and `observeActive` are already
 * collecting. Each subscription has read the database as it was before the action ran, so an emission
 * carrying the file's rows is a re-emission: a write that went around Room would leave all three silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImportRoomTest {

    @get:Rule
    val storage = TemporaryFolder()

    /** `source` is exported into the file; `target` is the database the file is imported into. */
    private lateinit var source: LiftoffDatabase
    private lateinit var target: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore
    private val opened = mutableListOf<LiftoffDatabase>()

    private val workerPool: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, WORKER_THREAD) }
    private val worker: ExecutorCoroutineDispatcher = workerPool.asCoroutineDispatcher()

    /** One scope for the flow subscriptions and the view model's work, so teardown stops both. */
    private val scopeJob = SupervisorJob()
    private val broken = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        scopeJob + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure -> broken += failure }
    )

    // A Monday, so the current week is the one the file's live Mission sits in.
    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)
    private val thisWeek = LocalDate.of(2026, 1, 12)
    private val lastWeek = LocalDate.of(2026, 1, 5)

    @Before
    fun setUp() {
        source = newDatabase()
        target = newDatabase()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create { File(storage.root, "settings.preferences_pb") }
        )
    }

    @After
    fun tearDown() {
        scopeJob.cancel()
        runBlocking { withTimeout(BOUND_MILLIS) { scopeJob.join() } }
        worker.close()
        opened.forEach { it.close() }
    }

    private fun newDatabase(): LiftoffDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { opened += it }
    }

    private fun managerFor(db: LiftoffDatabase) = MissionManager(
        db, settingsStore, clock = clock, zone = { ZoneOffset.UTC }
    )

    private suspend fun exportOf(db: LiftoffDatabase): ByteArray {
        val out = ByteArrayOutputStream()
        exportDatabase(db, out, clock)
        return out.toByteArray()
    }

    private suspend fun seedMission(
        db: LiftoffDatabase,
        weekStart: LocalDate,
        pattern: String,
        status: MissionStatus,
        outlineNotes: String? = null,
    ): Long = db.missionDao().insert(
        Mission(
            id = 0L, weekStart = weekStart, pattern = pattern,
            status = status, outlineNotes = outlineNotes
        )
    )

    private suspend fun seedSortie(
        db: LiftoffDatabase,
        missionId: Long,
        index: Int,
        type: SortieType = SortieType.RUN,
        state: SortieState = SortieState.PENDING,
    ): Long = db.sortieDao().insert(
        Sortie(
            missionId = missionId, index = index, type = type, focus = null,
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        )
    )

    /** One subscription, started before the action runs, that keeps every emission it has produced. */
    private class Subscription<T>(val emissions: CopyOnWriteArrayList<T>)

    private fun <T> watch(flow: Flow<T>): Subscription<T> {
        val emissions = CopyOnWriteArrayList<T>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { it -> emissions.add(it) } }
        return Subscription(emissions)
    }

    /** The subscription's read of the database as it was before the import, which it must already have. */
    private suspend fun <T> Subscription<T>.awaitPreImportRead(says: String): T {
        val deadline = System.currentTimeMillis() + BOUND_MILLIS
        while (emissions.isEmpty() && System.currentTimeMillis() < deadline) delay(POLL_MILLIS)
        assertTrue("$says had not read the database before the import", emissions.isNotEmpty())
        return emissions.first()
    }

    /** The emission carrying the file's rows, which only a write Room noticed can bring. */
    private suspend fun <T> Subscription<T>.awaitRestored(says: String, matches: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + BOUND_MILLIS
        while (System.currentTimeMillis() < deadline) {
            for (emission in emissions) if (matches(emission)) return emission
            delay(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $says; it emitted $emissions")
    }

    private fun assertReEmitted(says: String, before: Int, subscription: Subscription<*>) {
        assertTrue(
            "$says never re-emitted after the import; it emitted ${subscription.emissions}",
            subscription.emissions.size > before
        )
    }

    @Test
    fun anImportThroughTheBackupActionReachesRoomsFlows() = runBlocking<Unit> {
        // The database the file is exported from: a live week, a closed one, and the equipment both used.
        val liveWeek = seedMission(source, thisWeek, "RLLR", MissionStatus.ACTIVE, outlineNotes = OUTLINE)
        seedSortie(source, liveWeek, 0, SortieType.RUN, SortieState.LANDED)
        seedSortie(source, liveWeek, 1, SortieType.LIFT, SortieState.PENDING)
        val closedWeek = seedMission(source, lastWeek, "RL", MissionStatus.CLOSED)
        seedSortie(source, closedWeek, 0, SortieType.LIFT, SortieState.LANDED)
        source.equipmentDao().add(Equipment(key = "barbell", name = "Barbell", notes = "the 20 kg one"))
        source.equipmentDao().add(Equipment(key = "kettlebell", name = "Kettlebell"))

        val file = exportOf(source)
        val fileMission = requireNotNull(source.missionDao().getByWeekStart(thisWeek))
        val fileSorties = source.sortieDao().getForMission(liveWeek)
        val fileHistory = source.sortieDao().getAll()
            .filter { it.state == SortieState.LANDED || it.state == SortieState.SCRUBBED }
            .sortedBy { it.id }
        val fileEquipment = source.equipmentDao().getAll().filter { it.active }.sortedBy { it.name }

        // The fresh database, in the state a cold start leaves it: one DRAFT Mission and nothing else.
        val manager = managerFor(target)
        manager.onAppOpen()

        val week = watch(target.missionDao().observeWeek(thisWeek))
        val history = watch(target.sortieDao().observeHistory())
        val equipment = watch(target.equipmentDao().observeActive())

        val draft = requireNotNull(week.awaitPreImportRead("the Launchpad's week flow"))
        assertEquals("the cold start left no draft to replace", MissionStatus.DRAFT, draft.mission.status)
        assertEquals(
            "the Landed tab's flow read a database that already held the file",
            emptyList<Sortie>(), history.awaitPreImportRead("the Landed tab's history flow")
        )
        assertEquals(
            "the Equipment tab's flow read a database that already held the file",
            emptyList<Equipment>(), equipment.awaitPreImportRead("the Equipment tab's equipment flow")
        )
        val emittedBefore = listOf(week, history, equipment).map { it.emissions.size }

        // The app's own wiring: the real data layer against the database these flows observe.
        val model = BackupViewModel(target, manager, scope, worker)
        model.runImport { ByteArrayInputStream(file) }

        val row = withTimeout(BOUND_MILLIS) {
            model.state.first { it is BackupState.Succeeded || it is BackupState.Failed }
        }
        assertEquals(BackupState.Succeeded("Restored 2 missions and 3 sorties."), row)

        val restoredWeek = requireNotNull(
            week.awaitRestored("the Launchpad's week flow to show the file's Mission") {
                it != null && it.mission.outlineNotes == OUTLINE
            }
        )
        assertEquals("the file's Mission did not reach the flow the Launchpad reads", fileMission, restoredWeek.mission)
        assertEquals("that Mission arrived without its sorties", fileSorties, restoredWeek.sorties)

        val restoredHistory = history.awaitRestored("the Landed tab's history flow to show the file's finished sorties") {
            it.map { sortie -> sortie.id }.toSet() == fileHistory.map { it.id }.toSet()
        }
        assertEquals("the history the flow emitted is not the file's", fileHistory, restoredHistory.sortedBy { it.id })

        val restoredEquipment = equipment.awaitRestored("the Equipment tab's flow to show the file's equipment") {
            it.map { item -> item.key }.toSet() == fileEquipment.map { it.key }.toSet()
        }
        assertEquals("the equipment the flow emitted is not the file's", fileEquipment, restoredEquipment.sortedBy { it.name })

        // Each flow had already read the old database, so a new emission is Room's invalidation tracker.
        assertReEmitted("observeWeek", emittedBefore[0], week)
        assertReEmitted("observeHistory", emittedBefore[1], history)
        assertReEmitted("observeActive", emittedBefore[2], equipment)

        assertTrue("a flow subscription failed: $broken", broken.isEmpty())
    }

    private companion object {
        // Every wait in this class is bounded, as the brief requires.
        const val BOUND_MILLIS = 30_000L
        const val POLL_MILLIS = 20L

        const val WORKER_THREAD = "backup-import-worker"
        const val OUTLINE = "from the backup file"
    }
}
