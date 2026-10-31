package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.domain.markPlanned
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Landing and scrubbing driven through the In-Flight view model against in-memory Room, on a
 * Mission the app itself created: continue an RLRLR week without an outline, land the first run, then launch sortie 1
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
    private lateinit var generationDao: GenerationDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private val job = Job()
    // The handler records anything a launched coroutine fails with, so a test can assert that nothing
    // escaped the view model instead of relying on the default handler cancelling the job.
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
        generationDao = db.generationDao()

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

    /**
     * The same path with Mission Control configured and a recording lambda in place of WorkManager, so the id an
     * action queued is something the test can see. The run-plan toggle goes on only after landSortie0: on before
     * the seeding, sortie 0 is left PENDING awaiting a generated plan and landSortie0 cannot launch it; off, the
     * run that landing this lift makes current gets the simple 'Run' plan and nothing is queued at all.
     * enqueueThrows makes that lambda refuse every id it is handed, the way the WorkManager call it stands in for
     * can, so a test can see what the view model does with a failure it did not cause.
     */
    private suspend fun launchSortie1WithEnqueue(
        coachConfigured: Boolean = true,
        generateRunPlans: Boolean = true,
        enqueueThrows: Boolean = false,
    ): EnqueueRecording {
        if (coachConfigured) {
            settingsStore.setDaemonHost("127.0.0.1")
            settingsStore.setDaemonToken("token-under-test")
        }
        val mission = seedMission()
        landSortie0(mission.id)
        settingsStore.setGenerateRunPlans(generateRunPlans)
        val sortie = seedSortie1LiftPlan(mission.id)
        manager.launch(sortie.id)
        val ids = CopyOnWriteArrayList<Long>()
        val vm = InFlightViewModel(db, settingsStore, sortie.id, scope, manager) {
            ids.add(it)
            if (enqueueThrows) throw IllegalStateException("the enqueue refused generation $it")
        }
        return EnqueueRecording(vm, awaitReady(vm), ids)
    }

    /** The view model on that fixture, the state it reached, and the ids its recording enqueue was handed. */
    private class EnqueueRecording(
        val vm: InFlightViewModel,
        val ready: InFlightState.Ready,
        val ids: CopyOnWriteArrayList<Long>,
    )

    private suspend fun awaitReady(vm: InFlightViewModel) = withTimeout(5_000) {
        vm.state.first { it is InFlightState.Ready }
    } as InFlightState.Ready

    private suspend fun awaitRun(vm: InFlightViewModel) = withTimeout(5_000) {
        vm.state.first { it is InFlightState.Run }
    } as InFlightState.Run

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

    // ── A run shows its plan and lands with no confirmation ──────────
    // The same land call on a RUN sortie: it carries the simple 'Run' plan the manager wrote, and it
    // lands with no confirmation because a run has no sets to leave unchecked.

    @Test
    fun landingARunNeedsNoChecklist() = runBlocking {
        val mission = seedMission()
        val runSortie = sortieDao.getForMission(mission.id)[0]
        manager.launch(runSortie.id)

        val vm = InFlightViewModel(db, settingsStore, runSortie.id, scope, manager)
        val run = awaitRun(vm)
        assertEquals(1, run.sortieNumber)

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

    // ── Scrubbing keeps what was already logged ──────────────────────
    // The view model's scrub on the same seeded lift sortie: the sortie is SCRUBBED, the sets already
    // checked keep their log (nothing is rewritten as not done), the reason the owner gave is stored
    // with the stray spaces a keyboard leaves trimmed off it, and the Mission has moved on to the
    // sortie after it.

    @Test
    fun scrubFromInFlightKeepsCheckedSetsAndStoresReason() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId
        val missionId = sortieDao.get(sortieId)!!.missionId
        val sets = allSetIds(ready)
        sets.take(2).forEach { vm.check(it) }
        withTimeout(5_000) {
            vm.state.first { (it as? InFlightState.Ready)?.let { r -> r.doneCount == 2 && r.openCount == 5 } ?: false }
        }

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        vm.scrub("  felt sick  ") { onDoneRuns++; done.complete(Unit) }
        awaitDone(done)

        assertEquals(1, onDoneRuns)
        val scrubbed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("felt sick", scrubbed.scrubReason)
        assertNull(vm.message.value)

        val after = flightPlanDao.getPlan(sortieId)!!.exercises.flatMap { it.sets }
        assertEquals(2, after.count { it.status == SetStatus.DONE })
        assertEquals(5, after.count { it.status == SetStatus.OPEN })
        assertEquals(0, after.count { it.status == SetStatus.SKIPPED })

        // The Mission advanced to the run after the scrubbed lift, which was prepared as usual.
        val sorties = sortieDao.getForMission(missionId)
        assertEquals(SortieState.SCRUBBED, sorties[1].state)
        assertEquals(SortieType.RUN, sorties[2].type)
        assertEquals(SortieState.PLANNED, sorties[2].state)
        assertEquals(SortieState.PENDING, sorties[3].state)
        assertEquals(MissionStatus.ACTIVE, missionDao.get(missionId)!!.status)
    }

    // ── A reason of only spaces stores nothing ───────────────────────
    // Typed at In-Flight: the view model normalises the reason the way it normalises a note, so the
    // sortie is scrubbed with no reason on it rather than a run of spaces.

    @Test
    fun scrubStoresWhitespaceReasonAsNull() = runBlocking {
        val (vm, ready) = launchSortie1()

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        vm.scrub("   ") { onDoneRuns++; done.complete(Unit) }
        awaitDone(done)

        assertEquals(1, onDoneRuns)
        val scrubbed = sortieDao.get(ready.sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertNull(scrubbed.scrubReason)
        assertNull(vm.message.value)
    }

    // ── A scrub the manager rejects is a message, not a crash ────────
    // The same message flow land uses: the sortie has already been scrubbed, so it is no longer the
    // current one and MissionManager.scrub refuses it. The refusal is a short line, the completion
    // callback does not run, and nothing escapes into the screen's scope.

    @Test
    fun scrubbingASortieThatIsNoLongerCurrentIsRejectedWithoutCrash() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId

        val firstDone = CompletableDeferred<Unit>()
        vm.scrub("felt sick") { firstDone.complete(Unit) }
        awaitDone(firstDone)
        val scrubbed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("felt sick", scrubbed.scrubReason)
        assertNull(vm.message.value)

        val rejecting = InFlightViewModel(db, settingsStore, sortieId, scope, manager)
        withTimeout(5_000) { rejecting.state.first { it is InFlightState.Ready } }

        val secondDone = CompletableDeferred<Unit>()
        rejecting.scrub("second try") { secondDone.complete(Unit) }

        val shown = withTimeout(5_000) { rejecting.message.first { it != null } }!!
        assertTrue("message should be a short line, was: $shown", shown.isNotBlank() && shown.length <= 60)
        assertNull(withTimeoutOrNull(1_000) { secondDone.await() })
        assertTrue("a rejected scrub threw out of the view model: $escaped", escaped.isEmpty())

        // The refusal changed nothing: still scrubbed once, with the reason the first scrub stored.
        val after = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, after.state)
        assertEquals("felt sick", after.scrubReason)
    }

    // ── A double tap lands once ──────────────────────────────────────
    // Two land calls back to back. The view model's guard is what takes the second one, so the
    // worker that runs the write is held until both taps have arrived: the first land is still in
    // progress when the second call comes, and the manager is never asked to land it twice.

    @Test
    fun doubleTapLandsOnce() = runBlocking {
        val mission = seedMission()
        landSortie0(mission.id)
        val sortie = seedSortie1LiftPlan(mission.id)
        manager.launch(sortie.id)

        val worker = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        val workerJob = Job()
        val heldScope = CoroutineScope(
            worker.asCoroutineDispatcher() + workerJob +
                CoroutineExceptionHandler { _, e -> escaped += e }
        )
        worker.submit { held.await(5, TimeUnit.SECONDS) }
        try {
            val vm = InFlightViewModel(db, settingsStore, sortie.id, heldScope, manager)

            val done = CompletableDeferred<Unit>()
            var onDoneRuns = 0
            vm.land { onDoneRuns++; done.complete(Unit) }
            vm.land { onDoneRuns++; done.complete(Unit) }
            assertEquals("no land should have run while its thread was held", 0, onDoneRuns)

            held.countDown()
            awaitDone(done)
            assertEquals(1, onDoneRuns)
            assertEquals(SortieState.LANDED, sortieDao.get(sortie.id)!!.state)
            // A second land that got past the guard would be rejected by the manager and show a
            // message inside this window. The guard means there is nothing to show.
            assertNull(withTimeoutOrNull(1_000) { vm.message.first { it != null } })
            assertTrue("a land threw out of the view model: $escaped", escaped.isEmpty())

            // The Mission advanced exactly one place: the landed lift, then the run that follows it,
            // which is PLANNED rather than landed or scrubbed. Nothing after it moved.
            val sorties = sortieDao.getForMission(mission.id)
            assertEquals(SortieState.LANDED, sorties[1].state)
            assertEquals(1, sorties.count { it.state == SortieState.LANDED && it.index >= 1 })
            assertEquals(SortieType.RUN, sorties[2].type)
            assertEquals(SortieState.PLANNED, sorties[2].state)
            assertEquals(SortieState.PENDING, sorties[3].state)
            assertEquals(SortieState.PENDING, sorties[4].state)
        } finally {
            held.countDown()
            workerJob.cancel()
            worker.shutdownNow()
        }
    }

    // ── A land the manager rejects is a message, not a crash ─────────
    // The sortie is already LANDED, so MissionManager.land refuses it. The refusal is mixed types —
    // a check() IllegalStateException here — so the view model catches Exception and shows a short
    // line instead of letting anything escape into the screen's scope.

    @Test
    fun secondLandIsRejectedWithoutCrash() = runBlocking {
        val (vm, ready) = launchSortie1()
        val sortieId = ready.sortieId

        val firstDone = CompletableDeferred<Unit>()
        vm.land { firstDone.complete(Unit) }
        awaitDone(firstDone)
        val landed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.LANDED, landed.state)
        val landedAt = landed.landedAt
        assertNotNull(landedAt)
        assertNull(vm.message.value)

        // The same scope as the first landing, whose handler records anything a launched coroutine
        // fails with, so "throws nothing out of the view model" is something the test can see.
        val rejecting = InFlightViewModel(db, settingsStore, sortieId, scope, manager)
        withTimeout(5_000) { rejecting.state.first { it is InFlightState.Ready } }

        val secondDone = CompletableDeferred<Unit>()
        rejecting.land { secondDone.complete(Unit) }

        val shown = withTimeout(5_000) { rejecting.message.first { it != null } }!!
        assertTrue("message should be a short line, was: $shown", shown.isNotBlank() && shown.length <= 60)
        assertNull(withTimeoutOrNull(1_000) { secondDone.await() })
        assertTrue("a rejected land threw out of the view model: $escaped", escaped.isEmpty())

        // The refusal changed nothing: still landed once, at the time the first landing gave it.
        val after = sortieDao.get(sortieId)!!
        assertEquals(SortieState.LANDED, after.state)
        assertEquals(landedAt, after.landedAt)
    }

    // ── Landing hands the next sortie's queued plan to the enqueue ───
    // Mission Control configured, so landing the in-flight lift queues a generation for the run that landing
    // makes current. The recording lambda is handed exactly one id, and that id names sortie 2's QUEUED
    // FLIGHT_PLAN row - not the open row the fixture's own landSortie0 already queued for the lift it landed.

    @Test
    fun landFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce() = runBlocking {
        val enq = launchSortie1WithEnqueue()
        val sortieId = enq.ready.sortieId
        val missionId = sortieDao.get(sortieId)!!.missionId

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        enq.vm.land { onDoneRuns++; done.complete(Unit) }
        awaitDone(done)

        assertEquals(1, onDoneRuns)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
        assertNull(enq.vm.message.value)

        val next = sortieDao.getForMission(missionId)[2]
        assertEquals(SortieType.RUN, next.type)

        assertEquals("the enqueue was handed ${enq.ids}", 1, enq.ids.size)
        val row = generationDao.get(enq.ids[0])
        assertNotNull("the recorded id names no generation row", row)
        assertEquals(GenerationKind.FLIGHT_PLAN, row!!.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(next.id, row.sortieId)

        val forTheLandedLift = generationDao.getNewestNonCancelledForSortie(sortieId)!!
        assertNotEquals("sortie 1's own queued row is not what the land handed over", forTheLandedLift.id, enq.ids[0])
    }

    // ── Scrubbing hands the same id, once ────────────────────────────
    // The same fixture through scrub: one id, naming the QUEUED FLIGHT_PLAN row of the sortie the scrub made
    // current, and the sortie SCRUBBED.

    @Test
    fun scrubFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce() = runBlocking {
        val enq = launchSortie1WithEnqueue()
        val sortieId = enq.ready.sortieId
        val missionId = sortieDao.get(sortieId)!!.missionId

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        enq.vm.scrub("felt sick") { onDoneRuns++; done.complete(Unit) }
        awaitDone(done)

        assertEquals(1, onDoneRuns)
        val scrubbed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("felt sick", scrubbed.scrubReason)
        assertNull(enq.vm.message.value)

        val next = sortieDao.getForMission(missionId)[2]
        assertEquals(SortieType.RUN, next.type)

        assertEquals("the enqueue was handed ${enq.ids}", 1, enq.ids.size)
        val row = generationDao.get(enq.ids[0])
        assertNotNull("the recorded id names no generation row", row)
        assertEquals(GenerationKind.FLIGHT_PLAN, row!!.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(next.id, row.sortieId)

        val forTheScrubbedLift = generationDao.getNewestNonCancelledForSortie(sortieId)!!
        assertNotEquals("sortie 1's own queued row is not what the scrub handed over", forTheScrubbedLift.id, enq.ids[0])
    }

    // ── No Mission Control: nothing is handed over, the sortie still lands ──
    // The same settings as the case above, minus the host and the token, so what queues nothing is the missing
    // Mission Control and not the run-plan toggle. The land is not a failure: the sortie lands, onDone ran, and
    // no message was raised.

    @Test
    fun landWithMissionControlUnsetEnqueuesNothingAndStillLands() = runBlocking {
        val enq = launchSortie1WithEnqueue(coachConfigured = false)
        val sortieId = enq.ready.sortieId

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        enq.vm.land { onDoneRuns++; done.complete(Unit) }
        awaitDone(done)

        assertEquals(1, onDoneRuns)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
        assertNull(enq.vm.message.value)
        assertTrue("the enqueue was handed ${enq.ids}", enq.ids.isEmpty())
    }

    // ── An enqueue that throws is not a failed land ──────────────────
    // The WorkManager call the enqueue stands in for can refuse on its own. The sortie did land, so the land is not
    // a failure: the message is watched for the whole window the action takes rather than only at the moment onDone
    // fires, and the QUEUED row the manager wrote stays in Room as the record that this plan is still owed.

    @Test
    fun landWithAFailingEnqueueStillLandsAndShowsNoMessage() = runBlocking {
        val enq = launchSortie1WithEnqueue(enqueueThrows = true)
        val sortieId = enq.ready.sortieId
        val missionId = sortieDao.get(sortieId)!!.missionId

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        enq.vm.land { onDoneRuns++; done.complete(Unit) }

        assertNull(
            "a failed enqueue was turned into a message on a sortie that landed",
            withTimeoutOrNull(2_000) { enq.vm.message.first { it != null } },
        )
        awaitDone(done)
        assertEquals(1, onDoneRuns)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
        assertTrue("a failing enqueue escaped into the screen's scope: $escaped", escaped.isEmpty())

        assertEquals("the enqueue was handed ${enq.ids}", 1, enq.ids.size)
        val row = generationDao.get(enq.ids[0])
        assertNotNull("the QUEUED row this land queued is not in the database", row)
        assertEquals(GenerationKind.FLIGHT_PLAN, row!!.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(sortieDao.getForMission(missionId)[2].id, row.sortieId)
    }

    // ── The same through scrub ───────────────────────────────────────
    // A scrub whose enqueue throws still scrubs: the sortie is SCRUBBED with the reason it was given, onDone ran
    // once, nothing reached the scope's handler, and the row for the sortie the scrub made current is still there.

    @Test
    fun scrubWithAFailingEnqueueStillScrubbsAndShowsNoMessage() = runBlocking {
        val enq = launchSortie1WithEnqueue(enqueueThrows = true)
        val sortieId = enq.ready.sortieId
        val missionId = sortieDao.get(sortieId)!!.missionId

        val done = CompletableDeferred<Unit>()
        var onDoneRuns = 0
        enq.vm.scrub("felt sick") { onDoneRuns++; done.complete(Unit) }

        assertNull(
            "a failed enqueue was turned into a message on a sortie that scrubbed",
            withTimeoutOrNull(2_000) { enq.vm.message.first { it != null } },
        )
        awaitDone(done)
        assertEquals(1, onDoneRuns)
        val scrubbed = sortieDao.get(sortieId)!!
        assertEquals(SortieState.SCRUBBED, scrubbed.state)
        assertEquals("felt sick", scrubbed.scrubReason)
        assertTrue("a failing enqueue escaped into the screen's scope: $escaped", escaped.isEmpty())

        assertEquals("the enqueue was handed ${enq.ids}", 1, enq.ids.size)
        val row = generationDao.get(enq.ids[0])
        assertNotNull("the QUEUED row this scrub queued is not in the database", row)
        assertEquals(GenerationKind.FLIGHT_PLAN, row!!.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(sortieDao.getForMission(missionId)[2].id, row.sortieId)
    }
}
