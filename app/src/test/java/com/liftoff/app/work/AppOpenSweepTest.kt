package com.liftoff.app.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionDao
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The sweep against in-memory Room with the container's two helpers replaced by recording lambdas: no
 * WorkManager, no screen, no daemon, and no Mission Control host or token anywhere in this class, because the
 * sweep consults no settings. Every assertion is on the exact ids each lambda was handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppOpenSweepTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var generationDao: GenerationDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    private val enqueued = mutableListOf<Long>()
    private val cancelled = mutableListOf<Long>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        generationDao = db.generationDao()

        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = managerAt(Instant.parse("2026-01-12T00:00:00Z"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun managerAt(instant: Instant): MissionManager = MissionManager(
        db, settingsStore,
        clock = Clock.fixed(instant, ZoneOffset.UTC),
        zone = { ZoneOffset.UTC }
    )

    /** A manager on a later instant: how a case makes the week-ended rollover run. */
    private fun advanceClock(days: Int) {
        manager = managerAt(Instant.parse("2026-01-12T00:00:00Z").plusSeconds(days.toLong() * 24 * 3600))
    }

    private fun insertMission(weekStart: LocalDate, pattern: String, status: MissionStatus): Long = runBlocking {
        missionDao.insert(Mission(
            weekStart = weekStart, pattern = pattern, status = status, outlineNotes = null
        ))
    }

    private fun insertSortie(missionId: Long, index: Int, type: SortieType, focus: String?, state: SortieState): Long = runBlocking {
        sortieDao.insert(Sortie(
            missionId = missionId, index = index, type = type, focus = focus,
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
    }

    /**
     * One Generation row seeded directly: the sweep only acts on rows the app already asked for, and a row the
     * manager queues on its own would be one the case did not write. [createdAt] staggers the rows so the order
     * the sweep reads them in is the order the case listed them in.
     */
    private suspend fun seedGeneration(
        kind: GenerationKind,
        missionId: Long,
        sortieId: Long?,
        status: GenerationStatus,
        createdAt: Long,
    ): Generation {
        val decided = status == GenerationStatus.SUCCEEDED ||
            status == GenerationStatus.FAILED ||
            status == GenerationStatus.CANCELLED
        val id = generationDao.insert(Generation(
            kind = kind,
            missionId = missionId,
            sortieId = sortieId,
            status = status,
            error = if (status == GenerationStatus.FAILED) "coach gave up" else null,
            createdAt = createdAt,
            finishedAt = if (decided) 1L else null
        ))
        return generationDao.get(id)!!
    }

    /** The sweep with the enqueue and cancel helpers recorded rather than run. */
    private fun sweep(): AppOpenSweep = AppOpenSweep(
        db,
        enqueue = { enqueued += it },
        cancel = { cancelled += it }
    )

    // ── nothing to repair ──────────────────────────────────────────────

    /**
     * With no open row the sweep asks neither helper for anything — not an id, not a zero, not a null. This is
     * what keeps WorkManager out of the shell tests, which run MainActivity.onCreate over a database with no
     * Generation rows and WorkManager never initialized.
     */
    @Test
    fun anEmptyGenerationTableCallsNeitherLambda() = runBlocking {
        val mission = manager.onAppOpen()
        assertEquals(0L, generationDao.countAll())

        sweep().run(mission.weekStart)

        assertEquals(emptyList<Long>(), enqueued)
        assertEquals(emptyList<Long>(), cancelled)
    }

    // ── the rows the sweep wants ───────────────────────────────────────

    /**
     * A QUEUED FLIGHT_PLAN row on the current week's ACTIVE Mission's current PENDING sortie is resumed under the
     * row it already has, rather than cancelled and replaced by a fresh generation.
     */
    @Test
    fun queuedFlightPlanForTheCurrentPendingSortieIsEnqueuedNotCancelled() = runBlocking {
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.ACTIVE)
        val current = insertSortie(missionId, 0, SortieType.LIFT, "full body", SortieState.PENDING)
        insertSortie(missionId, 1, SortieType.RUN, "easy", SortieState.PENDING)
        val row = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.QUEUED, 1000L)

        val mission = manager.onAppOpen()
        assertEquals(missionId, mission.id)

        sweep().run(mission.weekStart)

        assertEquals(listOf(row.id), enqueued)
        assertEquals(emptyList<Long>(), cancelled)
        val handed = generationDao.get(enqueued.single())!!
        assertEquals(GenerationKind.FLIGHT_PLAN, handed.kind)
        assertEquals(current, handed.sortieId)
        assertEquals(GenerationStatus.QUEUED, handed.status)
    }

    /**
     * A QUEUED OUTLINE row whose missionId is the current week's DRAFT Mission and whose sortieId is null is still
     * what that draft is waiting on.
     */
    @Test
    fun queuedOutlineForTheCurrentDraftMissionIsEnqueuedNotCancelled() = runBlocking {
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        val row = seedGeneration(GenerationKind.OUTLINE, missionId, null, GenerationStatus.QUEUED, 1000L)

        val mission = manager.onAppOpen()
        assertEquals(missionId, mission.id)
        assertEquals(MissionStatus.DRAFT, mission.status)

        sweep().run(mission.weekStart)

        assertEquals(listOf(row.id), enqueued)
        assertEquals(emptyList<Long>(), cancelled)
        val handed = generationDao.get(enqueued.single())!!
        assertEquals(GenerationKind.OUTLINE, handed.kind)
        assertEquals(missionId, handed.missionId)
        assertNull(handed.sortieId)
    }

    /**
     * A RUNNING FLIGHT_PLAN row on the current sortie that is already PLANNED — a re-fly whose cancel was lost, or
     * a turn still in progress — is wanted too: only PENDING or only QUEUED would miss it.
     */
    @Test
    fun runningFlightPlanForTheCurrentPlannedSortieIsEnqueuedNotCancelled() = runBlocking {
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.ACTIVE)
        val current = insertSortie(missionId, 0, SortieType.LIFT, "full body", SortieState.PLANNED)
        insertSortie(missionId, 1, SortieType.RUN, "easy", SortieState.PENDING)
        val row = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.RUNNING, 1000L)

        val mission = manager.onAppOpen()
        assertEquals(missionId, mission.id)
        assertEquals(MissionStatus.ACTIVE, mission.status)

        sweep().run(mission.weekStart)

        assertEquals(listOf(row.id), enqueued)
        assertEquals(emptyList<Long>(), cancelled)
        val handed = generationDao.get(enqueued.single())!!
        assertEquals(GenerationStatus.RUNNING, handed.status)
        assertEquals(current, handed.sortieId)
        assertEquals(SortieState.PLANNED, sortieDao.get(current)!!.state)
    }
}
