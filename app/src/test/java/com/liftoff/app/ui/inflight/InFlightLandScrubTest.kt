package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.domain.markPlanned
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Landing and scrubbing driven through the In-Flight view model against in-memory Room, on a
 * Mission the app itself created: confirm an RLRLR week, land the first run, then launch sortie 1
 * on a seeded lift plan. The manager carries a fixed clock so a landed time is a known number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightLandScrubTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

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

    /** onAppOpen + confirm on the default RLRLR pattern; sortie 0 is a PLANNED run. */
    private suspend fun seedMission(): Mission {
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)
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

    private suspend fun awaitNoChecklist(vm: InFlightViewModel) = withTimeout(5_000) {
        vm.state.first { it is InFlightState.NoChecklist }
    } as InFlightState.NoChecklist

    /** onDone is awaited rather than polled, so a callback that never runs fails on a timeout. */
    private suspend fun awaitDone(done: CompletableDeferred<Unit>) {
        withTimeout(5_000) { done.await() }
    }

    private fun allSetIds(ready: InFlightState.Ready) = ready.cards.flatMap { it.sets.map { s -> s.setId } }

    // ── Landing with every set logged ────────────────────────────────
    // Launch → log → Land: the sortie is LANDED, nothing is rewritten as not done, the completion
    // callback (what returns to the Launchpad) ran once, and no failure message was raised.

    @Test
    fun landWithEverySetChecked() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        allSetIds(ready).forEach { vm.check(it) }
        val logged = withTimeout(5_000) {
            vm.state.first { (it as? InFlightState.Ready)?.let { r -> r.doneCount == 7 && r.openCount == 0 } ?: false }
        } as InFlightState.Ready
        assertEquals(7, logged.doneCount)

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        vm.land { onDoneRuns++; done.complete(Unit) }

        awaitDone(done)
        assertEquals(1, onDoneRuns)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
        val sets = flightPlanDao.getPlan(sortieId)!!.exercises.flatMap { it.sets }
        assertEquals(7, sets.size)
        assertTrue(sets.all { it.status == SetStatus.DONE })
        assertNull(vm.message.value)
    }

    // ── Landing with sets left unchecked ─────────────────────────────
    // What the confirmation dialog promises: the unchecked sets are recorded as not done, the
    // checked ones keep their log.

    @Test
    fun landRecordsUncheckedSetsAsNotDone() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val sets = allSetIds(ready)
        sets.take(2).forEach { vm.check(it) }
        withTimeout(5_000) {
            vm.state.first { (it as? InFlightState.Ready)?.let { r -> r.doneCount == 2 && r.openCount == 5 } ?: false }
        }

        val done = CompletableDeferred<Unit>()
        vm.land { done.complete(Unit) }
        awaitDone(done)

        val after = flightPlanDao.getPlan(sortieId)!!.exercises.flatMap { it.sets }
        assertEquals(SetStatus.DONE, after[0].status)
        assertEquals(SetStatus.DONE, after[1].status)
        for (i in 2 until after.size) {
            assertEquals("set ${i + 1}", SetStatus.SKIPPED, after[i].status)
        }
        assertEquals(5, after.count { it.status == SetStatus.SKIPPED })
    }

    // ── The landed time ──────────────────────────────────────────────
    // The sortie is LANDED and carries the time the manager's clock gave it.

    @Test
    fun landStoresLandedTime() = runBlocking {
        val (vm, ready) = launchSortie1()
        val done = CompletableDeferred<Unit>()
        vm.land { done.complete(Unit) }
        awaitDone(done)

        val sortie = sortieDao.get(ready.sortieId)!!
        assertEquals(SortieState.LANDED, sortie.state)
        assertEquals(fixedInstant.toEpochMilli(), sortie.landedAt)
    }

    // ── Landing advances the Mission ─────────────────────────────────
    // The run after the landed lift is the current one, prepared with its simple 'Run' plan rather
    // than left empty.

    @Test
    fun landMakesNextRunSortieCurrentWithSimpleRunPlan() = runBlocking {
        val (vm, ready) = launchSortie1()
        val missionId = sortieDao.get(ready.sortieId)!!.missionId
        val done = CompletableDeferred<Unit>()
        vm.land { done.complete(Unit) }
        awaitDone(done)

        val sorties = sortieDao.getForMission(missionId)
        assertEquals(SortieState.LANDED, sorties[1].state)
        assertEquals(SortieType.RUN, sorties[2].type)
        assertEquals(SortieState.PLANNED, sorties[2].state)

        val plan = flightPlanDao.getPlan(sorties[2].id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
        assertEquals("Run", plan.plan.title)
    }

    // ── A run has no checklist and lands with no confirmation ────────
    // The same land call on a RUN sortie: it lands and the completion callback runs.

    @Test
    fun landingARunNeedsNoChecklist() = runBlocking {
        val mission = seedMission()
        val runSortie = sortieDao.getForMission(mission.id)[0]
        manager.launch(runSortie.id)

        val vm = InFlightViewModel(db, settingsStore, runSortie.id, scope, manager)
        val noChecklist = awaitNoChecklist(vm)
        assertEquals(1, noChecklist.sortieNumber)

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        vm.land { onDoneRuns++; done.complete(Unit) }
        awaitDone(done)

        assertEquals(1, onDoneRuns)
        assertEquals(SortieState.LANDED, sortieDao.get(runSortie.id)!!.state)
        assertNull(vm.message.value)
    }

    // ── Landing the last sortie closes the Mission ───────────────────
    // Reached from In-Flight, not straight from the manager: the view model's land on the final
    // sortie leaves the Mission CLOSED, which is what the Launchpad then shows.

    @Test
    fun landingLastSortieFromInFlightClosesMission() = runBlocking {
        // A two-sortie week, so landing sortie 1 is the last landing.
        settingsStore.setDefaultPattern("RL")
        val mission = seedMission()
        landSortie0(mission.id)
        val sortie = seedSortie1LiftPlan(mission.id)
        manager.launch(sortie.id)

        val vm = InFlightViewModel(db, settingsStore, sortie.id, scope, manager)
        awaitReady(vm)

        val done = CompletableDeferred<Unit>()
        vm.land { done.complete(Unit) }
        awaitDone(done)

        assertEquals(SortieState.LANDED, sortieDao.get(sortie.id)!!.state)
        assertEquals(MissionStatus.CLOSED, missionDao.get(mission.id)!!.status)
    }
}
