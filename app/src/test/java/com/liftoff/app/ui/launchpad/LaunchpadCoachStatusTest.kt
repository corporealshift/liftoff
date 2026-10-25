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
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionDao
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.domain.CoachStatus
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.settings.WeightUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The draft's coach status as [LaunchpadStates.observe] derives it live: one subscription to observe(), opened
 * before the writes and left open through every one of them, with each emission read out of that one collection.
 * A second observe() or a fresh .first after a write would re-subscribe and prove nothing about the join being
 * live, so there is one collector job per case here and it stays launched until the case ends.
 *
 * Room, DataStore and MissionManager are the real ones; no WorkManager, no daemon, no screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadCoachStatusTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)
    private val weekStart = LocalDate.parse("2026-01-12")

    /** Rows are ordered by createdAt, so each write in a case gets its own later instant. */
    private val seededAt = Instant.parse("2026-01-12T00:00:00Z").toEpochMilli()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var generationDao: GenerationDao
    private lateinit var missionDao: MissionDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private lateinit var states: LaunchpadStates
    private lateinit var settingsFile: File

    /** Everything the one subscription of this case has emitted, in the order it arrived. */
    private val emissions = CopyOnWriteArrayList<LaunchpadState>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        generationDao = db.generationDao()
        missionDao = db.missionDao()

        // A file of its own, so a setting changed here cannot reach another test class.
        settingsFile = File(context.cacheDir, "coach-status-${UUID.randomUUID()}.preferences_pb")
        settingsStore = SettingsStore(PreferenceDataStoreFactory.create { settingsFile })

        manager = MissionManager(db, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
        states = LaunchpadStates(db, manager)
    }

    @After
    fun tearDown() {
        db.close()
        settingsFile.delete()
    }

    // ── The one subscription ───────────────────────────────────────────

    /** Opens observe() once, into [emissions], and returns the job that keeps it open. */
    private fun CoroutineScope.openSubscription(): Job =
        launch { states.observe().collect { emissions.add(it) } }

    private fun drafts(): List<LaunchpadState.Draft> = emissions.filterIsInstance<LaunchpadState.Draft>()

    /** A Draft this same collection has already emitted; bounded so a status that never arrives fails. */
    private suspend fun awaitDraft(where: (LaunchpadState.Draft) -> Boolean): LaunchpadState.Draft =
        withTimeout(AWAIT_MS) {
            var found: LaunchpadState.Draft? = null
            while (found == null) {
                found = drafts().firstOrNull(where)
                if (found == null) delay(POLL_MS)
            }
            found!!
        }

    /**
     * Nothing new arrived out of the same collection within the wait: [emissions] still holds exactly what it
     * held at [before], and the newest Draft still says what it said then.
     */
    private suspend fun assertNothingNewEmitted(before: Int, newestStatus: CoachStatus, what: String) {
        val extra = withTimeoutOrNull(SETTLE_MS) {
            var next: LaunchpadState? = null
            while (next == null) {
                if (emissions.size > before) {
                    next = emissions[before]
                } else {
                    delay(POLL_MS)
                }
            }
            next
        }
        val note = "$what left the one subscription's emissions unchanged"
        assertNull(note, extra)
        assertEquals(note, before, emissions.size)
        assertEquals(note, newestStatus, drafts().last().coachStatus)
    }

    /** A generation row of the kind Confirm writes: an OUTLINE row for [missionId], open unless told otherwise. */
    private fun outlineRow(
        missionId: Long,
        status: GenerationStatus,
        createdAt: Long,
        finishedAt: Long? = null,
    ) = Generation(
        kind = GenerationKind.OUTLINE,
        missionId = missionId,
        sortieId = null,
        status = status,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )

    private fun at(step: Int): Long = seededAt + step * 1_000L

    private suspend fun coachIsSetUp() {
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("coach-token")
    }

    // ── A QUEUED OUTLINE row written to Room turns the draft planning ──

    @Test
    fun theDraftReportsPlanningWhenAQueuedOutlineRowIsWrittenToRoom() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()
        val collecting = openSubscription()
        try {
            val editable = awaitDraft { it.coachStatus is CoachStatus.Editable }
            assertEquals(weekStart, editable.weekStart)
            assertEquals(mission.id, editable.missionId)
            assertTrue("nothing is open, so Confirm is the way on", editable.coachStatus.offersConfirm)
            assertFalse(editable.coachStatus.offersContinueWithoutOutline)

            val before = emissions.size
            val row = generationDao.insert(outlineRow(editable.missionId, GenerationStatus.QUEUED, at(1)))

            val planning = awaitDraft { it.coachStatus is CoachStatus.Planning }
            assertEquals("the same draft, not a re-subscribed one", editable.missionId, planning.missionId)
            assertTrue("one new emission out of the one collection", emissions.size > before)
            assertFalse("the pattern is frozen while the outline is open", planning.coachStatus.offersConfirm)
            assertTrue(planning.coachStatus.offersContinueWithoutOutline)
            assertFalse(planning.coachStatus.offersRetry)
            assertEquals(GenerationStatus.QUEUED, generationDao.get(row)!!.status)
        } finally {
            collecting.cancel()
        }
    }

    // ── The same row takes the coach-offline mark: offline, still QUEUED ──

    @Test
    fun theDraftReportsOfflineWhenItsOutlineRowTakesTheCoachOfflineMark() = runBlocking {
        coachIsSetUp()
        manager.onAppOpen()
        val collecting = openSubscription()
        try {
            val draft = awaitDraft { it.coachStatus is CoachStatus.Editable }
            val row = generationDao.insert(outlineRow(draft.missionId, GenerationStatus.QUEUED, at(1)))
            awaitDraft { it.coachStatus is CoachStatus.Planning }

            generationDao.setCoachOfflineMark(row, Generation.COACH_OFFLINE)

            val offline = awaitDraft { it.coachStatus is CoachStatus.Offline }
            assertEquals(draft.missionId, offline.missionId)
            assertFalse(offline.coachStatus.offersConfirm)
            assertFalse(offline.coachStatus.offersRetry)
            assertTrue(offline.coachStatus.offersContinueWithoutOutline)

            // The mark alone: the row is still QUEUED, so this is not a failure wearing an offline label.
            val stored = generationDao.get(row)!!
            assertEquals(GenerationStatus.QUEUED, stored.status)
            assertEquals(Generation.COACH_OFFLINE, stored.error)
        } finally {
            collecting.cancel()
        }
    }

    // ── The row is marked failed: failed, carrying the reason word for word ──

    @Test
    fun theDraftReportsFailedWithTheExactReasonItsOutlineRowWasMarkedFailedWith() = runBlocking {
        coachIsSetUp()
        manager.onAppOpen()
        val collecting = openSubscription()
        try {
            val draft = awaitDraft { it.coachStatus is CoachStatus.Editable }
            val row = generationDao.insert(outlineRow(draft.missionId, GenerationStatus.QUEUED, at(1)))
            awaitDraft { it.coachStatus is CoachStatus.Planning }

            // A validator's reason: punctuation and inner spacing, so a rewrite or a trim is caught.
            val reason = "reply rejected: 3 of 5 sets name a barbell the owner does not own"
            generationDao.markFailed(row, reason, at(2))

            val failed = awaitDraft { it.coachStatus is CoachStatus.Failed }
            assertEquals(draft.missionId, failed.missionId)
            assertEquals(reason, (failed.coachStatus as CoachStatus.Failed).reason)
            assertTrue(failed.coachStatus.offersRetry)
            assertTrue(failed.coachStatus.offersContinueWithoutOutline)
            assertFalse(failed.coachStatus.offersConfirm)
            assertEquals(reason, generationDao.get(row)!!.error)
        } finally {
            collecting.cancel()
        }
    }

    // ── Unrelated writes leave the collected emissions alone ───────────
    // Each row below is a row a join that ignored kind, status or missionId would answer for. The last write is
    // the control: the subscription is still open, so the silence above it is the wiring, not a closed collector.

    @Test
    fun theDraftDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()
        // Seeded before the subscription opens, so the mission table is not written while it is collecting.
        val otherMissionId = missionDao.insert(
            Mission(
                weekStart = weekStart.minusDays(7),
                pattern = "RL",
                status = MissionStatus.CLOSED,
                outlineNotes = null,
            )
        )

        val collecting = openSubscription()
        try {
            val draft = awaitDraft { it.coachStatus is CoachStatus.Editable }
            assertEquals(mission.id, draft.missionId)

            var before = emissions.size
            val elsewhere = generationDao.insert(outlineRow(otherMissionId, GenerationStatus.QUEUED, at(1)))
            assertEquals(GenerationStatus.QUEUED, generationDao.get(elsewhere)!!.status)
            assertNothingNewEmitted(before, draft.coachStatus, "another mission's outline row")

            before = emissions.size
            val planRow = generationDao.insert(
                Generation(
                    kind = GenerationKind.FLIGHT_PLAN,
                    missionId = draft.missionId,
                    sortieId = null,
                    status = GenerationStatus.QUEUED,
                    createdAt = at(2),
                )
            )
            assertEquals(GenerationKind.FLIGHT_PLAN, generationDao.get(planRow)!!.kind)
            assertNothingNewEmitted(before, draft.coachStatus, "a FLIGHT_PLAN row for the same mission")

            before = emissions.size
            // Inserted already cancelled: cancelling a row the draft is showing would legitimately change it.
            val cancelled = generationDao.insert(
                outlineRow(draft.missionId, GenerationStatus.CANCELLED, at(3), finishedAt = at(3))
            )
            assertEquals(GenerationStatus.CANCELLED, generationDao.get(cancelled)!!.status)
            assertNothingNewEmitted(before, draft.coachStatus, "a cancelled outline row for the same mission")

            before = emissions.size
            generationDao.insert(outlineRow(draft.missionId, GenerationStatus.QUEUED, at(4)))
            val planning = awaitDraft { it.coachStatus is CoachStatus.Planning }
            assertTrue("the subscription was still open for this write", emissions.size > before)
            assertEquals(draft.missionId, planning.missionId)
        } finally {
            collecting.cancel()
        }
    }

    // ── The settings travel to the same subscription ───────────────────

    @Test
    fun theDraftFollowsTheSettingsFromNotConfiguredToEditableWithoutReSubscribing() = runBlocking {
        // The case the harness default says otherwise about: no host, no token.
        val mission = manager.onAppOpen()
        val collecting = openSubscription()
        try {
            val notConfigured = awaitDraft { it.coachStatus is CoachStatus.NotConfigured }
            assertEquals(mission.id, notConfigured.missionId)
            assertFalse("Confirm would only come back as a refusal", notConfigured.coachStatus.offersConfirm)
            assertTrue(notConfigured.coachStatus.offersContinueWithoutOutline)

            var before = emissions.size
            settingsStore.setWeightUnit(WeightUnit.KG)
            assertNothingNewEmitted(before, notConfigured.coachStatus, "a weight-unit edit")
            // The write itself landed; only the coach's configured-ness did not change.
            assertEquals(WeightUnit.KG, settingsStore.settings.first().weightUnit)
            assertEquals("", settingsStore.settings.first().daemonHost)

            settingsStore.setDaemonHost("127.0.0.1")
            settingsStore.setDaemonToken("coach-token")

            val editable = awaitDraft { it.coachStatus is CoachStatus.Editable }
            assertEquals("the same draft, not a re-subscribed one", notConfigured.missionId, editable.missionId)
            assertTrue(editable.coachStatus.offersConfirm)
        } finally {
            collecting.cancel()
        }
    }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 25L
    }
}
