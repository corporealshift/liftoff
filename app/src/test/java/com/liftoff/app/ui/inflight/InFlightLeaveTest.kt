package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.domain.WEEK_ENDED
import com.liftoff.app.domain.markPlanned
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The In-Flight screen leaving once its sortie is no longer in flight, driven through the view model's
 * watch on its own sortie row against in-memory Room: the fixture InFlightLandScrubTest already uses —
 * continue an RLRLR week without an outline, land the first run, launch sortie 1 on a seeded lift plan.
 *
 * The leave callback counts into an AtomicInteger because Room delivers an emission on its own thread and
 * the test thread can only poll it. Every test waits for 1 under a timeout, then waits a short bounded
 * time and asserts it is still 1: that is what "leaves once, not twice" means. The last two tests are the
 * pair that shows the write guard bites only after leaving: while the sortie is still IN_FLIGHT the screen
 * writes as normal, and after it has left a check reaches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightLeaveTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private val job = Job()
    // The handler records anything a launched coroutine fails with, so a watcher that throws out of its
    // collect shows up as a failed assertion rather than a wait that times out.
    private val escaped = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, e -> escaped += e }
    )

    private val fixedInstant = Instant.parse("2026-01-12T00:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        job.cancel()
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    // ── Fixture ──────────────────────────────────────────────────────

    /** onAppOpen + continueWithoutOutline on the default RLRLR pattern; sortie 0 is a PLANNED run. */
    private suspend fun seedMission(): Mission {
        val mission = manager.onAppOpen()
        manager.continueWithoutOutline(mission.id)
        return missionDao.get(mission.id)!!
    }

    /** Launch and land sortie 0 through the manager, the way the Launchpad does. */
    private suspend fun landSortie0(missionId: Long) {
        val sorties = sortieDao.getForMission(missionId)
        manager.launch(sorties[0].id)
        manager.land(sorties[0].id)
    }

    /** Sortie 1 is the LIFT left PENDING by landing sortie 0; give it a plan and make it PLANNED. */
    private suspend fun seedSortie1LiftPlan(missionId: Long): Sortie {
        val sortie = sortieDao.getForMission(missionId)[1]
        flightPlanDao.writePlan(sortie.id, liftPlanDraft())
        val planned = markPlanned(sortie)
        sortieDao.update(planned)
        return planned
    }

    private fun liftPlanDraft() = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = "Upper Push", estimatedMinutes = 40,
        warmup = null, notes = null, runKind = null, targetDistance = null,
        targetPace = null, rawJson = "{}",
        exercises = listOf(
            ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 10, seconds = null, weight = 35.0),
                SetDraft(reps = 10, seconds = null, weight = 35.0),
                SetDraft(reps = 10, seconds = null, weight = 35.0)
            )),
            ExerciseDraft(name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 12, seconds = null, weight = null),
                SetDraft(reps = 12, seconds = null, weight = null)
            )),
            ExerciseDraft(name = "Kettlebell Hold", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 8, seconds = null, weight = 20.0),
                SetDraft(reps = null, seconds = 45, weight = null)
            ))
        ),
        segments = emptyList()
    )

    /** The whole path up to an in-flight LIFT sortie 1, and the view model that reads it. */
    private suspend fun launchSortie1(): Pair<InFlightViewModel, InFlightState.Ready> {
        val mission = seedMission()
        landSortie0(mission.id)
        val sortie = seedSortie1LiftPlan(mission.id)
        manager.launch(sortie.id)
        val vm = InFlightViewModel(db, settingsStore, sortie.id, scope, manager)
        val ready = awaitReady(vm)
        return vm to ready
    }

    private suspend fun awaitReady(vm: InFlightViewModel) = withTimeout(5_000) {
        vm.state.first { it is InFlightState.Ready }
    } as InFlightState.Ready

    /** A view model that is watching its own sortie row, counting each leave into [count]. */
    private fun watching(vm: InFlightViewModel, count: AtomicInteger) {
        vm.watchLeave { count.incrementAndGet() }
    }

    /**
     * The leave happened, and it happened once: after a bounded settle the count is still exactly 1, so
     * nothing else decided to leave the same screen a second time.
     */
    private suspend fun awaitOneLeave(count: AtomicInteger) {
        withTimeout(5_000) {
            while (count.get() == 0) delay(25)
        }
        delay(500)
        assertEquals("the screen must leave exactly once, not twice", 1, count.get())
        assertTrue("a leave callback threw out of the view model: $escaped", escaped.isEmpty())
    }

    /** The MissionManagerTest shape: the same database, a manager on a clock a week on. */
    private fun advanceClock(days: Int) {
        val newInstant = fixedInstant.plusSeconds(days.toLong() * 24 * 3600)
        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(newInstant, ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
    }

    /** The set as the database holds it, read through the plan rather than through the screen's state. */
    private suspend fun setInDb(sortieId: Long, setId: Long): PlannedSet? =
        flightPlanDao.getPlan(sortieId)?.exercises?.flatMap { it.sets }?.firstOrNull { it.id == setId }

    /** A bounded wait for something that must NOT happen: true if the set became DONE within 2 s. */
    private suspend fun setBecomesDone(sortieId: Long, setId: Long): Boolean = try {
        withTimeout(2_000) {
            while (setInDb(sortieId, setId)?.status != SetStatus.DONE) delay(25)
        }
        true
    } catch (e: TimeoutCancellationException) {
        false
    }

    // ── The week-ended rollover when the app returns to the foreground ──
    // A manager on a clock past the week scrubs the IN_FLIGHT sortie with reason 'week ended' and the
    // screen leaves once. The DESIGN.md §6 safety net: a sortie left open over the weekend does not stay
    // open, and the In-Flight screen that was showing it goes back to the Launchpad.

    @Test
    fun rolloverScrubLeavesTheInFlightScreenOnce() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val count = AtomicInteger(0)
        watching(vm, count)

        advanceClock(7)
        manager.onAppOpen()

        awaitOneLeave(count)

        val scrubbed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals(WEEK_ENDED, scrubbed.scrubReason)
    }

    // ── Landed by something other than this screen ───────────────────
    // The manager, not the view model, lands the sortie: the screen still notices and leaves once.

    @Test
    fun landElsewhereLeavesTheInFlightScreenOnce() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val count = AtomicInteger(0)
        watching(vm, count)

        manager.land(sortieId)

        awaitOneLeave(count)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
    }

    // ── The sortie no longer exists ──────────────────────────────────
    // Its row is deleted, so the screen would be offering Land and Scrub for a row that is gone. It
    // leaves once.

    @Test
    fun deletedSortieLeavesTheInFlightScreenOnce() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val count = AtomicInteger(0)
        watching(vm, count)

        sortieDao.deleteById(sortieId)

        awaitOneLeave(count)
        assertNull(sortieDao.get(sortieId))
    }

    // ── A restored screen pointing at a sortie that has since landed ──
    // The sortie is already LANDED before the view model is built and the watch starts, and the leave
    // callback still fires exactly once: the first emission observeById produces is the LANDED row.
    // Without this case a watcher that only reacted to a change after it started would pass the three
    // tests above and leave the restored screen stuck on a landed sortie.

    @Test
    fun alreadyLandedSortieLeavesOnceWhenTheWatchStarts() = runBlocking {
        val mission = seedMission()
        landSortie0(mission.id)
        val sortie = seedSortie1LiftPlan(mission.id)
        manager.launch(sortie.id)
        manager.land(sortie.id)
        assertEquals(SortieState.LANDED, sortieDao.get(sortie.id)!!.state)

        val count = AtomicInteger(0)
        val vm = InFlightViewModel(db, settingsStore, sortie.id, scope, manager)
        watching(vm, count)

        awaitOneLeave(count)
        assertEquals(SortieState.LANDED, sortieDao.get(sortie.id)!!.state)
    }

    // ── A Land done on this screen leaves once, not twice ────────────
    // The screen's own Land both calls its done callback and re-emits the sortie row, so the watcher
    // fires too. One leave, and the sortie is LANDED.

    @Test
    fun landOnThisScreenLeavesOnlyOnce() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val count = AtomicInteger(0)
        watching(vm, count)

        vm.land { count.incrementAndGet() }

        awaitOneLeave(count)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
        assertNull(vm.message.value)
    }

    // ── The same for a Scrub done on this screen ─────────────────────
    // Its own fixture, its own sortie: one leave, and the sortie is SCRUBBED with the reason given.

    @Test
    fun scrubOnThisScreenLeavesOnlyOnce() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val count = AtomicInteger(0)
        watching(vm, count)

        vm.scrub("sore knee") { count.incrementAndGet() }

        awaitOneLeave(count)
        val scrubbed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("sore knee", scrubbed.scrubReason)
        assertNull(vm.message.value)
    }

    // ── The control: the sortie is still IN_FLIGHT, so the screen keeps writing ──
    // A checked set and a sortie note — the note rewrites the sortie row, so observeById does re-emit —
    // must not leave the screen, and both writes must land. Without this, a watcher that fired on any
    // re-emission, or a write guard that never let a write through, would pass the tests above.

    @Test
    fun noLeaveWhileTheSortieIsStillInFlight() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val setId = ready.currentSetId ?: ready.cards[0].sets[0].setId
        val count = AtomicInteger(0)
        watching(vm, count)

        vm.check(setId)
        vm.saveSortieNote("left shoulder twinge")

        val wrote = setBecomesDone(sortieId, setId)
        delay(500)

        assertTrue("a write to a sortie still IN_FLIGHT must land in the database", wrote)
        assertEquals("a sortie still IN_FLIGHT must not leave the screen", 0, count.get())
        assertTrue("a leave callback threw out of the view model: $escaped", escaped.isEmpty())
        assertTrue("the state flow must still show the checklist", vm.state.value is InFlightState.Ready)
        assertEquals(SetStatus.DONE, setInDb(sortieId, setId)?.status)
        assertEquals("left shoulder twinge", sortieDao.get(sortieId)?.notes)
    }

    // ── After the screen has left, it writes nothing ─────────────────
    // Left through the rollover scrub, which touches only the sortie row so the sets are still OPEN (a
    // Land would mark them not done and hide the check). The check afterwards never reaches the database.

    @Test
    fun afterLeavingTheScreenWritesNothing() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val setId = ready.currentSetId ?: ready.cards[0].sets[0].setId
        assertEquals("the fixture's set must start OPEN", SetStatus.OPEN, setInDb(sortieId, setId)?.status)

        val count = AtomicInteger(0)
        watching(vm, count)

        advanceClock(7)
        manager.onAppOpen()

        withTimeout(5_000) { while (count.get() == 0) delay(25) }
        assertEquals("the rollover scrub must leave the sets OPEN", SetStatus.OPEN, setInDb(sortieId, setId)?.status)

        vm.check(setId)

        assertFalse("after leaving, the screen must not write to that sortie", setBecomesDone(sortieId, setId))
        assertEquals(1, count.get())
        assertTrue("a leave callback threw out of the view model: $escaped", escaped.isEmpty())
    }
}
