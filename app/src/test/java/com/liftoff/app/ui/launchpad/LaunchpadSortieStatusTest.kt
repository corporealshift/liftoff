package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.data.ExerciseDraft
import com.liftoff.app.data.FlightPlanDraft
import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.SetDraft
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieDao
import com.liftoff.app.data.StoreOutcome
import com.liftoff.app.domain.SortieGenerationStatus
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
 * The PENDING and PLANNED cards as [LaunchpadStates.observe] derives them live: one subscription to observe(),
 * opened before the writes and left open through every one of them, with each emission read out of that one
 * collection. A second observe() or a fresh .first after a write would re-subscribe and prove nothing about the
 * join being live, so there is one collector job per case here and it stays launched until the case ends.
 *
 * Room, DataStore and MissionManager are the real ones; no WorkManager, no daemon, no screen. Host and token are
 * set, so the week this fixture builds already carries the QUEUED FLIGHT_PLAN row the current sortie was prepared
 * with — that is where each case's first planning emission comes from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadSortieStatusTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)
    private val weekStart = LocalDate.parse("2026-01-12")

    /** Rows are ordered by createdAt, so each write in a case gets its own later instant. */
    private val seededAt = Instant.parse("2026-01-12T00:00:00Z").toEpochMilli()

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var generationDao: GenerationDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: com.liftoff.app.data.FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private lateinit var states: LaunchpadStates
    private lateinit var settingsFile: File

    /** Everything the one subscription of this case has emitted, in the order it arrived. */
    private val emissions = CopyOnWriteArrayList<LaunchpadState>()

    /** Held so tearDown can only close the database once the collector is cancelled. */
    private var subscription: Job? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        generationDao = db.generationDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        // A file of its own, so a setting changed here cannot reach another test class.
        settingsFile = File(context.cacheDir, "sortie-status-${UUID.randomUUID()}.preferences_pb")
        settingsStore = SettingsStore(PreferenceDataStoreFactory.create { settingsFile })

        manager = MissionManager(db, settingsStore, clock = clock, zone = { ZoneOffset.UTC })
        states = LaunchpadStates(db, manager)
    }

    @After
    fun tearDown() {
        subscription?.cancel()
        db.close()
        settingsFile.delete()
    }

    // ── The one subscription ───────────────────────────────────────────

    /** Opens observe() once, into [emissions], and returns the job that keeps it open. */
    private fun CoroutineScope.openSubscription(): Job =
        launch { states.observe().collect { emissions.add(it) } }.also { subscription = it }

    /** The Pending cards emitted at or after index [from] of this case's one collection. */
    private fun pendings(from: Int = 0): List<LaunchpadState.Pending> =
        emissions.drop(from).filterIsInstance<LaunchpadState.Pending>()

    private fun planneds(from: Int = 0): List<LaunchpadState.Planned> =
        emissions.drop(from).filterIsInstance<LaunchpadState.Planned>()

    /**
     * A Pending card this same collection has already emitted; bounded so a status that never arrives fails.
     * [from] is the index a caller wants the search to start at, so a card the collection showed earlier cannot
     * stand in for the one a write below is meant to cause.
     */
    private suspend fun awaitPending(from: Int = 0, where: (LaunchpadState.Pending) -> Boolean): LaunchpadState.Pending =
        withTimeout(AWAIT_MS) {
            var found: LaunchpadState.Pending? = null
            while (found == null) {
                found = pendings(from).firstOrNull(where)
                if (found == null) delay(POLL_MS)
            }
            found!!
        }

    private suspend fun awaitPlanned(from: Int = 0, where: (LaunchpadState.Planned) -> Boolean): LaunchpadState.Planned =
        withTimeout(AWAIT_MS) {
            var found: LaunchpadState.Planned? = null
            while (found == null) {
                found = planneds(from).firstOrNull(where)
                if (found == null) delay(POLL_MS)
            }
            found!!
        }

    /**
     * Nothing new arrived out of the same collection within the wait: [emissions] still holds exactly what it
     * held at [before], and the newest card is still the one it was showing then.
     */
    private suspend fun assertNothingNewEmitted(before: Int, card: LaunchpadState, what: String) {
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
        assertEquals(note, card, emissions.last())
    }

    /**
     * The week a card is read on, built the way the screen builds it: settings first, then the current week's
     * draft activated with no outline. Host and token are set, so [MissionManager.advance] has already queued the
     * QUEUED FLIGHT_PLAN row for the sortie it made current, and its id is recorded here because every case marks
     * that row. The pattern starts with L so the current sortie is a LIFT: a RUN current sortie is prepared with a
     * plan instead and queues nothing. Sortie 2 is the week's other LIFT, later than the current one.
     */
    private suspend fun activeWeek(pattern: String = "LRL"): Week {
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("coach-token")
        settingsStore.setDefaultPattern(pattern)
        val mission = manager.onAppOpen()
        var queued: Long? = null
        manager.continueWithoutOutline(mission.id) { queued = it }
        val sorties = sortieDao.getForMission(mission.id)
        return Week(mission, sorties.first { it.index == 0 }, sorties.first { it.index == 2 }, queued!!)
    }

    /** A FLIGHT_PLAN row of the kind a generation is: this sortie's, open unless told otherwise. */
    private fun planRow(
        missionId: Long,
        sortieId: Long,
        status: GenerationStatus,
        createdAt: Long,
        error: String? = null,
        finishedAt: Long? = null,
    ) = Generation(
        kind = GenerationKind.FLIGHT_PLAN,
        missionId = missionId,
        sortieId = sortieId,
        status = status,
        error = error,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )

    private fun at(step: Int): Long = seededAt + step * 1_000L

    /** A plan for a sortie this card is not about: written to the same tables, for a sortie of the same week. */
    private val laterLiftDraft = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = "Later Lift", estimatedMinutes = 50,
        warmup = "Row easy", notes = null, runKind = null, targetDistance = null, targetPace = null,
        rawJson = """{"title":"Later Lift"}""",
        exercises = listOf(
            ExerciseDraft(
                name = "Row", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                sets = listOf(SetDraft(10, null, 40.0)),
            )
        ),
        segments = emptyList(),
    )

    private class Week(
        val mission: Mission,
        val current: Sortie,
        val later: Sortie,
        val queuedRow: Long,
    )

    // ── One subscription follows one row from planning through offline to failed ──
    // The row advance queued is the only row this case touches: marked offline, given a session id, then marked
    // failed. Every answer below comes out of the collection the subscription opened before any of that.

    @Test
    fun oneSubscriptionToAPendingSortieFollowsItsRowFromPlanningThroughOfflineToFailed() = runBlocking {
        val week = activeWeek()
        val collecting = openSubscription()
        try {
            val planning = awaitPending { it.generationStatus is SortieGenerationStatus.Planning }
            assertEquals(week.current.id, planning.sortieId)
            assertEquals(week.current.index, planning.sortieIndex)
            assertNull("the row has no session id yet", planning.rhoSessionId)
            assertEquals(
                "the QUEUED row advance queued is the card's first answer",
                GenerationStatus.QUEUED,
                generationDao.get(week.queuedRow)!!.status,
            )

            var before = emissions.size
            generationDao.setCoachOfflineMark(week.queuedRow, Generation.COACH_OFFLINE)
            val offline = awaitPending { it.generationStatus is SortieGenerationStatus.Offline }
            assertTrue("one new emission out of the one collection", emissions.size > before)
            assertEquals(week.current.id, offline.sortieId)
            assertEquals(week.current.index, offline.sortieIndex)
            assertFalse("the coach is working, just unreachable", offline.reFlyAvailable)

            before = emissions.size
            generationDao.saveSessionId(week.queuedRow, RHO_SESSION_ID)
            val carried = awaitPending { it.rhoSessionId == RHO_SESSION_ID }
            assertTrue("the same subscription carried the id in", emissions.size > before)
            assertEquals("still offline, now with the session it is waiting on", carried.generationStatus, offline.generationStatus)

            before = emissions.size
            val reason = "reply rejected: 3 of 5 sets name a barbell the owner does not own"
            generationDao.markFailed(week.queuedRow, reason, at(2))
            val failed = awaitPending { it.generationStatus is SortieGenerationStatus.Failed }
            assertTrue("one new emission out of the one collection", emissions.size > before)
            assertEquals(reason, (failed.generationStatus as SortieGenerationStatus.Failed).reason)
            assertEquals(RHO_SESSION_ID, failed.rhoSessionId)
            assertEquals(week.current.id, failed.sortieId)
            assertEquals(week.current.index, failed.sortieIndex)
            assertEquals(reason, generationDao.get(week.queuedRow)!!.error)

            assertEquals(
                "the one collection answered planning, then offline, then failed",
                listOf(
                    SortieGenerationStatus.Planning,
                    SortieGenerationStatus.Offline,
                    SortieGenerationStatus.Failed(reason),
                ),
                pendings().map { it.generationStatus }.distinct(),
            )
            assertEquals(
                "the same sortie throughout",
                listOf(week.current.id),
                pendings().map { it.sortieId }.distinct(),
            )
            assertEquals(
                "the same index throughout",
                listOf(week.current.index),
                pendings().map { it.sortieIndex }.distinct(),
            )
            assertNothingNewEmitted(emissions.size, failed, "the settled card")
        } finally {
            collecting.cancel()
        }
    }

    // ── A PLANNED card that gets a new row queued for it keeps its plan and says re-planning ──
    // The plan the coach stored came out of the row advance queued, so that row is marked succeeded first: the
    // card is Planned and idle. Queuing a fresh row for the same sortie is what moves it.

    @Test
    fun aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt() = runBlocking {
        val week = activeWeek()
        val collecting = openSubscription()
        try {
            awaitPending { it.generationStatus is SortieGenerationStatus.Planning }

            val stored = manager.storePlan(
                week.current.id,
                CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT),
            )
            assertEquals(StoreOutcome.Stored, stored)
            generationDao.markSucceeded(week.queuedRow, at(1))

            val idle = awaitPlanned { it.generationStatus is SortieGenerationStatus.Idle }
            assertEquals(week.current.id, idle.sortieId)
            val planBefore = flightPlanDao.getPlan(week.current.id)!!
            assertEquals(planBefore, idle.plan)
            assertEquals(planBefore.plan.title, idle.title)

            val before = emissions.size
            generationDao.insert(planRow(week.mission.id, week.current.id, GenerationStatus.QUEUED, at(2)))
            val rePlanning = awaitPlanned { it.generationStatus is SortieGenerationStatus.RePlanning }
            assertTrue("one new emission out of the one collection", emissions.size > before)
            assertEquals("the same sortie, not a re-subscribed card", week.current.id, rePlanning.sortieId)
            assertEquals(week.current.index, rePlanning.sortieIndex)
            assertEquals("the card still shows the plan it showed before", planBefore, rePlanning.plan)
            assertEquals(planBefore.plan.title, rePlanning.title)
            assertEquals(
                GenerationStatus.QUEUED,
                generationDao.getOpenFlightPlanForSortie(week.current.id).single().status,
            )
        } finally {
            collecting.cancel()
        }
    }

    // ── The card reads this sortie's newest non-cancelled row, and nothing else's ──
    // A CANCELLED row newer than this sortie's FAILED row, and an open row newer than both for another sortie of
    // the same week: neither is the row the card answers for.

    @Test
    fun theCardFollowsThisSortiesNewestNonCancelledRowAndNotACancelledOrAnotherSortiesRow() = runBlocking {
        val week = activeWeek()
        val collecting = openSubscription()
        try {
            awaitPending { it.generationStatus is SortieGenerationStatus.Planning }

            val reason = "coach needed input"
            generationDao.markFailed(week.queuedRow, reason, at(1))
            val failed = awaitPending { it.generationStatus is SortieGenerationStatus.Failed }
            assertEquals(reason, (failed.generationStatus as SortieGenerationStatus.Failed).reason)

            var before = emissions.size
            // Inserted already cancelled, so cancelling the row the card is showing is not what is being tested.
            val cancelled = generationDao.insert(
                planRow(week.mission.id, week.current.id, GenerationStatus.CANCELLED, at(2), finishedAt = at(2))
            )
            assertEquals(GenerationStatus.CANCELLED, generationDao.get(cancelled)!!.status)
            assertNothingNewEmitted(before, failed, "a newer cancelled row for this sortie")

            before = emissions.size
            val elsewhere = generationDao.insert(
                planRow(week.mission.id, week.later.id, GenerationStatus.QUEUED, at(3))
            )
            assertEquals(week.later.id, generationDao.get(elsewhere)!!.sortieId)
            assertNothingNewEmitted(before, failed, "a newer open row for another sortie")

            val newest = generationDao.getNewestNonCancelledForSortie(week.current.id)!!
            assertEquals("the row the card answered is still the newest non-cancelled one", week.queuedRow, newest.id)
            assertEquals(GenerationStatus.FAILED, newest.status)
        } finally {
            collecting.cancel()
        }
    }

    // ── Unrelated writes leave the collected emissions alone ───────────
    // Each write below touches a table the joined arms read, but cannot change this card. The last write is the
    // control: the subscription is still open, so the silence above it is the wiring, not a closed collector.

    @Test
    fun theCardDoesNotReEmitForAnotherSortiesRowAPlanForAnotherSortieOrAnUnchangedSetting() = runBlocking {
        val week = activeWeek()
        val collecting = openSubscription()
        try {
            awaitPending { it.generationStatus is SortieGenerationStatus.Planning }
            // Settled on a failure first, so the control write below is a real change this card has to answer.
            val reason = "coach unreachable"
            generationDao.markFailed(week.queuedRow, reason, at(1))
            val failed = awaitPending { it.generationStatus is SortieGenerationStatus.Failed }
            assertEquals(reason, (failed.generationStatus as SortieGenerationStatus.Failed).reason)

            var before = emissions.size
            val elsewhere = generationDao.insert(
                planRow(week.mission.id, week.later.id, GenerationStatus.QUEUED, at(2))
            )
            assertEquals(GenerationStatus.QUEUED, generationDao.get(elsewhere)!!.status)
            assertNothingNewEmitted(before, failed, "another sortie's queued row")

            before = emissions.size
            flightPlanDao.writePlan(week.later.id, laterLiftDraft)
            assertEquals("the plan landed on the later sortie", "Later Lift", flightPlanDao.getPlan(week.later.id)!!.plan.title)
            assertNothingNewEmitted(before, failed, "a plan written for a later sortie")

            before = emissions.size
            settingsStore.setWeightUnit(WeightUnit.KG)
            assertNothingNewEmitted(before, failed, "a weight-unit edit")
            // The write itself landed; only the coach's configured-ness did not change.
            assertEquals(WeightUnit.KG, settingsStore.settings.first().weightUnit)
            assertEquals("127.0.0.1", settingsStore.settings.first().daemonHost)

            before = emissions.size
            generationDao.insert(planRow(week.mission.id, week.current.id, GenerationStatus.QUEUED, at(3)))
            // from = before: this collection already showed a Planning card at the start of the case, so only a
            // card emitted after this write can answer for it.
            val planning = awaitPending(from = before) { it.generationStatus is SortieGenerationStatus.Planning }
            assertTrue("the subscription was still open for this write", emissions.size > before)
            assertEquals(week.current.id, planning.sortieId)
            assertEquals(week.current.index, planning.sortieIndex)
        } finally {
            collecting.cancel()
        }
    }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 25L
        const val RHO_SESSION_ID = "rho-session-7f31"
    }
}
