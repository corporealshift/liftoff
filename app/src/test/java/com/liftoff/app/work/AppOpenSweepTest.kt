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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * One sweep run in its own scope with a CoroutineExceptionHandler and a bounded wait, so whatever leaves the
     * sweep is either recorded in the returned list or is the sweep's own cancellation — never something the
     * test's own scope has to absorb. Returns the sweep's Job and what reached the handler.
     */
    private suspend fun runSweep(
        weekStart: LocalDate,
        enqueue: (Long) -> Unit,
        cancel: suspend (Long) -> Unit,
    ): Pair<Job, List<Throwable>> {
        val escaped = CopyOnWriteArrayList<Throwable>()
        val job = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, cause -> escaped += cause })
            .launch { AppOpenSweep(db, enqueue, cancel).run(weekStart) }
        withTimeout(15_000L) { job.join() }
        return job to escaped
    }

    /**
     * The four open rows the two failure cases share: two on the current week's ACTIVE Mission's current sortie,
     * which the sweep hands to enqueue, and two on sorties that are no longer current, which it hands to cancel.
     * Each helper therefore has a first id to throw on and a next id it must still be handed.
     */
    private fun seedOpenRows(): OpenRows = runBlocking {
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RLRLR", MissionStatus.ACTIVE)
        val landed = insertSortie(missionId, 0, SortieType.LIFT, "push", SortieState.LANDED)
        val scrubbed = insertSortie(missionId, 1, SortieType.RUN, "easy", SortieState.SCRUBBED)
        val current = insertSortie(missionId, 2, SortieType.LIFT, "pull", SortieState.PENDING)
        insertSortie(missionId, 3, SortieType.RUN, "easy", SortieState.PENDING)
        OpenRows(
            missionId = missionId,
            landedRow = seedGeneration(
                GenerationKind.FLIGHT_PLAN, missionId, landed, GenerationStatus.QUEUED, 1000L
            ),
            scrubbedRow = seedGeneration(
                GenerationKind.FLIGHT_PLAN, missionId, scrubbed, GenerationStatus.RUNNING, 1100L
            ),
            wantedQueued = seedGeneration(
                GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.QUEUED, 1200L
            ),
            wantedRunning = seedGeneration(
                GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.RUNNING, 1300L
            ),
        )
    }

    /** The rows [seedOpenRows] wrote. */
    private class OpenRows(
        val missionId: Long,
        val landedRow: Generation,
        val scrubbedRow: Generation,
        val wantedQueued: Generation,
        val wantedRunning: Generation,
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

    // ── the rows whose target has moved on ─────────────────────────────

    /**
     * The week-ended rollover scrubs a past Mission's not-done sorties with 'week ended' and closes it, so the
     * generation still queued for one of those sorties is asking for a sortie that will never happen, and a past
     * draft's outline is asking for a week that is already closed. The sweep is handed the weekStart the rollover
     * returned, so it judges both rows against the week that did the scrubbing.
     */
    @Test
    fun weekEndedRolloverCancelsTheScrubbedSortiesRowAndThePastDraftsOutlineRow() = runBlocking {
        val pastActiveWeek = LocalDate.of(2026, 1, 5)
        val pastActiveId = insertMission(pastActiveWeek, "RLR", MissionStatus.ACTIVE)
        val notDoneSortie = insertSortie(pastActiveId, 0, SortieType.LIFT, "full body", SortieState.PENDING)
        val planRow = seedGeneration(
            GenerationKind.FLIGHT_PLAN, pastActiveId, notDoneSortie, GenerationStatus.QUEUED, 1000L
        )

        val pastDraftWeek = LocalDate.of(2025, 12, 29)
        val pastDraftId = insertMission(pastDraftWeek, "RL", MissionStatus.DRAFT)
        val outlineRow = seedGeneration(GenerationKind.OUTLINE, pastDraftId, null, GenerationStatus.RUNNING, 1100L)

        advanceClock(7)
        val mission = manager.onAppOpen()
        assertEquals(LocalDate.of(2026, 1, 19), mission.weekStart)
        assertEquals(MissionStatus.DRAFT, mission.status)

        // The rollover really ran before the sweep was called: both past Missions closed, the sortie scrubbed.
        assertEquals(MissionStatus.CLOSED, missionDao.get(pastActiveId)!!.status)
        assertEquals(MissionStatus.CLOSED, missionDao.get(pastDraftId)!!.status)
        val scrubbed = sortieDao.get(notDoneSortie)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("week ended", scrubbed.scrubReason)

        sweep().run(mission.weekStart)

        assertEquals(listOf(planRow.id, outlineRow.id), cancelled)
        assertEquals(emptyList<Long>(), enqueued)
        val cancelledPlan = generationDao.get(cancelled[0])!!
        assertEquals(GenerationKind.FLIGHT_PLAN, cancelledPlan.kind)
        assertEquals(pastActiveId, cancelledPlan.missionId)
        assertEquals(notDoneSortie, cancelledPlan.sortieId)
        val cancelledOutline = generationDao.get(cancelled[1])!!
        assertEquals(GenerationKind.OUTLINE, cancelledOutline.kind)
        assertEquals(pastDraftId, cancelledOutline.missionId)
        assertNull(cancelledOutline.sortieId)
    }

    /**
     * A row for a sortie the owner has already landed or scrubbed is asking for a plan nobody will act on, even
     * while its Mission is still the current week's ACTIVE one and a later sortie is now current. Both rows go to
     * cancel, the RUNNING one included: a generation that has left PENDING/PLANNED is stopped mid-turn.
     */
    @Test
    fun rowsForSortiesThatAreNoLongerCurrentAreCancelled() = runBlocking {
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RLRLR", MissionStatus.ACTIVE)
        val landed = insertSortie(missionId, 0, SortieType.LIFT, "push", SortieState.LANDED)
        val scrubbed = insertSortie(missionId, 1, SortieType.RUN, "easy", SortieState.SCRUBBED)
        val current = insertSortie(missionId, 2, SortieType.LIFT, "pull", SortieState.PENDING)
        val landedRow = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, landed, GenerationStatus.QUEUED, 1000L)
        val scrubbedRow = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, scrubbed, GenerationStatus.RUNNING, 1100L)

        val mission = manager.onAppOpen()
        assertEquals(missionId, mission.id)
        assertEquals(MissionStatus.ACTIVE, mission.status)

        sweep().run(mission.weekStart)

        assertEquals(listOf(landedRow.id, scrubbedRow.id), cancelled)
        assertEquals(emptyList<Long>(), enqueued)
        assertEquals(landed, generationDao.get(cancelled[0])!!.sortieId)
        assertEquals(scrubbed, generationDao.get(cancelled[1])!!.sortieId)
        assertEquals(SortieState.PENDING, sortieDao.get(current)!!.state)
    }

    /**
     * A Generation that has already decided its outcome is not reopened by an app open. Three decided rows sit on
     * the same Mission and the same current sortie as the open one, so only their status separates them: none of
     * their ids reaches either helper, and the open row is still resumed.
     */
    @Test
    fun decidedGenerationsAreLeftAlone() = runBlocking {
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.ACTIVE)
        val current = insertSortie(missionId, 0, SortieType.LIFT, "full body", SortieState.PENDING)
        val succeeded = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.SUCCEEDED, 1000L)
        val failed = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.FAILED, 1100L)
        val alreadyCancelled = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.CANCELLED, 1200L)
        val open = seedGeneration(GenerationKind.FLIGHT_PLAN, missionId, current, GenerationStatus.QUEUED, 1300L)

        val mission = manager.onAppOpen()
        assertEquals(missionId, mission.id)

        sweep().run(mission.weekStart)

        assertEquals(listOf(open.id), enqueued)
        assertEquals(emptyList<Long>(), cancelled)
        for (row in listOf(succeeded, failed, alreadyCancelled)) {
            assertFalse(enqueued.contains(row.id))
            assertFalse(cancelled.contains(row.id))
        }
        assertEquals(GenerationStatus.SUCCEEDED, generationDao.get(succeeded.id)!!.status)
        assertEquals(GenerationStatus.FAILED, generationDao.get(failed.id)!!.status)
        assertEquals(GenerationStatus.CANCELLED, generationDao.get(alreadyCancelled.id)!!.status)
    }

    // ── what a failing row does, and what cancellation does ──────────────

    /**
     * One bad row must not stop the rest and the app must not crash on one: both helpers throw on the first id
     * they are handed, and each is still handed the next row's id. The sweep runs in its own scope with a
     * CoroutineExceptionHandler, so a failure that escaped the per-row wrap would have been recorded there and
     * would have left the sweep's own coroutine cancelled.
     */
    @Test
    fun aRowThatFailsDoesNotStopTheSweepOrTheOtherRows() = runBlocking {
        val rows = seedOpenRows()
        val mission = manager.onAppOpen()
        assertEquals(rows.missionId, mission.id)
        assertEquals(MissionStatus.ACTIVE, mission.status)

        val thrown = mutableListOf<String>()
        val (job, escaped) = runSweep(
            mission.weekStart,
            enqueue = { id ->
                enqueued += id
                if (enqueued.size == 1) {
                    thrown += "enqueue"
                    throw IllegalStateException("the enqueue helper cannot reach WorkManager")
                }
            },
            cancel = { id ->
                cancelled += id
                if (cancelled.size == 1) {
                    thrown += "cancel"
                    throw IllegalStateException("the cancel helper cannot reach the daemon")
                }
            },
        )

        assertEquals(listOf("enqueue", "cancel"), thrown)
        assertEquals(listOf(rows.wantedQueued.id, rows.wantedRunning.id), enqueued)
        assertEquals(listOf(rows.landedRow.id, rows.scrubbedRow.id), cancelled)
        assertEquals(emptyList<Throwable>(), escaped)
        assertFalse("the sweep's own coroutine finished normally", job.isCancelled)
    }

    /**
     * Cancellation is the one failure the per-row wrap does not swallow, so a sweep that is being stopped still
     * stops. Each helper in turn throws CancellationException on the first id it is handed: the sweep's own
     * coroutine ends cancelled, the ids after the throwing one are never handed to either helper, and the handler
     * records nothing, because a cancelled coroutine is not a failure.
     */
    @Test
    fun aCancelledSweepStaysCancelledRatherThanCarryingOn() = runBlocking {
        val rows = seedOpenRows()
        val mission = manager.onAppOpen()
        assertEquals(rows.missionId, mission.id)
        assertEquals(MissionStatus.ACTIVE, mission.status)

        val enqStopped = mutableListOf<Long>()
        val canStopped = mutableListOf<Long>()
        val (jobAtEnqueue, escapedAtEnqueue) = runSweep(
            mission.weekStart,
            enqueue = { id ->
                enqStopped += id
                if (enqStopped.size == 1) throw CancellationException("the sweep was cancelled")
            },
            cancel = { id -> canStopped += id },
        )
        assertEquals(listOf(rows.wantedQueued.id), enqStopped)
        assertEquals(emptyList<Long>(), canStopped)
        assertTrue("the sweep ended cancelled", jobAtEnqueue.isCancelled)
        assertEquals(emptyList<Throwable>(), escapedAtEnqueue)

        val enqAfter = mutableListOf<Long>()
        val canAfter = mutableListOf<Long>()
        val (jobAtCancel, escapedAtCancel) = runSweep(
            mission.weekStart,
            enqueue = { id -> enqAfter += id },
            cancel = { id ->
                canAfter += id
                if (canAfter.size == 1) throw CancellationException("the sweep was cancelled")
            },
        )
        assertEquals(listOf(rows.wantedQueued.id, rows.wantedRunning.id), enqAfter)
        assertEquals(listOf(rows.landedRow.id), canAfter)
        assertTrue("the sweep ended cancelled", jobAtCancel.isCancelled)
        assertEquals(emptyList<Throwable>(), escapedAtCancel)
    }
}
