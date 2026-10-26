package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "settings.preferences_pb")
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
        db.close()
    }

    private fun advanceClock(days: Int) {
        val base = Instant.parse("2026-01-12T00:00:00Z")
        val newInstant = base.plusSeconds(days.toLong() * 24 * 3600)
        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(newInstant, ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
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

    /** Mission Control pointed at something: a host and a token, which is what makes Confirm able to ask. */
    private suspend fun configureCoach(host: String = "coach.local", token: String = "t0ken") {
        settingsStore.setDaemonHost(host)
        settingsStore.setDaemonToken(token)
    }

    /** The pattern edit the open outline generation must refuse, and the pattern that must still be stored. */
    private suspend fun assertPatternRefused(missionId: Long, pattern: String, stored: String, openAs: String) {
        try {
            manager.setPattern(missionId, pattern)
            throw AssertionError("Should have thrown with the outline generation $openAs")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
        assertEquals(stored, missionDao.get(missionId)!!.pattern)
    }

    /** One OUTLINE generation for [missionId], oldest in the table so any row the manager writes is the newer one. */
    private suspend fun seedOutline(missionId: Long, status: GenerationStatus): Generation {
        val id = db.generationDao().insert(Generation(
            kind = GenerationKind.OUTLINE,
            missionId = missionId,
            status = status,
            error = if (status == GenerationStatus.FAILED) "coach gave up" else null,
            createdAt = 0L,
            finishedAt = if (status == GenerationStatus.FAILED || status == GenerationStatus.SUCCEEDED) 1L else null
        ))
        return db.generationDao().get(id)!!
    }

    /** A Retry that must be refused, with the generation rows, the Mission and its sorties left exactly as they were. */
    private suspend fun assertRetryRefused(missionId: Long, refusedAs: String) {
        val rows = db.generationDao().getAll()
        val mission = missionDao.get(missionId)!!
        val sorties = sortieDao.getForMission(missionId)
        try {
            manager.retryOutline(missionId)
            throw AssertionError("Should have refused a Retry $refusedAs")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
        assertEquals(rows, db.generationDao().getAll())
        assertEquals(mission, missionDao.get(missionId))
        assertEquals(sorties, sortieDao.getForMission(missionId))
    }

    // ── onAppOpen ────────────────────────────────────────────────────────

    @Test
    fun onAppOpenCreatesDraftForCurrentWeek() = runBlocking {
        val mission = manager.onAppOpen()
        assertEquals(MissionStatus.DRAFT, mission.status)
        assertEquals("RLRLR", mission.pattern)
        assertEquals(LocalDate.of(2026, 1, 12), mission.weekStart)
    }

    @Test
    fun onAppOpenDoesNotDuplicateDraft() = runBlocking {
        val mission1 = manager.onAppOpen()
        val mission2 = manager.onAppOpen()
        // Same mission — no duplicate created.
        assertEquals(mission1.id, mission2.id)
        assertEquals(mission1.id, missionDao.getByWeekStart(LocalDate.of(2026, 1, 12))!!.id)
    }

    @Test
    fun onAppOpenRollsOverPastMission() = runBlocking {
        val pastWeek = LocalDate.of(2026, 1, 5) // Previous Monday
        val mId = insertMission(pastWeek, "RLR", MissionStatus.ACTIVE)

        // Sortie 0 IN_FLIGHT with a flight plan that has one exercise and two sets.
        val s0Id = insertSortie(mId, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)
        flightPlanDao.writePlan(s0Id, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Test", estimatedMinutes = null,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(com.liftoff.app.data.ExerciseDraft(
                name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null,
                sets = listOf(
                    com.liftoff.app.data.SetDraft(reps = 10, seconds = null, weight = null),
                    com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = null)
                )
            )),
            segments = emptyList()
        ))

        // Mark first set as DONE, second stays OPEN.
        val plan = flightPlanDao.getPlan(s0Id)!!
        val sets = plan.exercises[0].sets
        assertEquals(2, sets.size)
        db.flightPlanDao().updateSetActuals(sets[0].id, 10, null, null, SetStatus.DONE)

        // Sortie 1 PENDING.
        insertSortie(mId, 1, SortieType.LIFT, "full body", SortieState.PENDING)

        // Advance clock one week so past week has ended.
        advanceClock(7)

        val newMission = manager.onAppOpen()

        // Past mission is CLOSED.
        val past = missionDao.getByWeekStart(pastWeek)!!
        assertEquals(MissionStatus.CLOSED, past.status)

        // Its IN_FLIGHT sortie is SCRUBBED "week ended".
        val sorties = sortieDao.getForMission(mId)
        assertEquals(2, sorties.size)
        assertEquals(SortieState.SCRUBBED, sorties[0].state)
        assertEquals("week ended", sorties[0].scrubReason)

        // Checked sets are still DONE.
        val planAfter = flightPlanDao.getPlan(s0Id)!!
        assertEquals(2, planAfter.exercises[0].sets.size)
        assertEquals(SetStatus.DONE, planAfter.exercises[0].sets[0].status)
        assertEquals(SetStatus.OPEN, planAfter.exercises[0].sets[1].status)

        // New draft for current week.
        assertEquals(MissionStatus.DRAFT, newMission.status)
        assertEquals(LocalDate.of(2026, 1, 19), newMission.weekStart)
        assertEquals(0, sortieDao.getForMission(newMission.id).size)
    }

    // ── setPattern ───────────────────────────────────────────────────────

    @Test
    fun setPatternUpdatesDraftAndRejectsActive() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)
        manager.setPattern(mId, "RL")
        assertEquals("RL", missionDao.get(mId)!!.pattern)

        // Continue without an outline to make it ACTIVE.
        manager.continueWithoutOutline(mId)

        try {
            manager.setPattern(mId, "RR")
            throw AssertionError("Should have thrown")
        } catch (e: IllegalStateException) {
            // Expected — the pattern is frozen once the week is ACTIVE.
        }
    }

    // ── confirm: the queue path ────────────────────────────────────────────────

    @Test
    fun confirmQueuesOneOutlineGenerationAndLeavesTheDraftUntouched() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        val queued = manager.confirm(mId) as ConfirmOutcome.Queued

        val rows = db.generationDao().getOpenOutlineForMission(mId)
        assertEquals(listOf(queued.generationId), rows.map { it.id })
        assertEquals(GenerationKind.OUTLINE, rows[0].kind)
        assertEquals(GenerationStatus.QUEUED, rows[0].status)
        assertNull(rows[0].sortieId)
        assertEquals(MissionStatus.DRAFT, missionDao.get(mId)!!.status)
        assertEquals(0, sortieDao.getForMission(mId).size)
    }

    @Test
    fun confirmWithoutAHostOrTokenQueuesNothingAndSaysSo() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        for ((host, token) in listOf("" to "t0ken", "coach.local" to "", "   " to "   ")) {
            configureCoach(host, token)
            assertEquals(ConfirmOutcome.CoachNotConfigured, manager.confirm(mId))
        }
        assertEquals(0L, db.generationDao().countAll())
        val mission = missionDao.get(mId)!!
        assertEquals(MissionStatus.DRAFT, mission.status)
        assertEquals("RL", mission.pattern)
        assertEquals(0, sortieDao.getForMission(mId).size)
    }

    @Test
    fun confirmingTheSameDraftTwiceReusesTheOpenOutlineGeneration() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        val first = manager.confirm(mId) as ConfirmOutcome.Queued
        val second = manager.confirm(mId) as ConfirmOutcome.Queued
        assertEquals(first.generationId, second.generationId)
        assertEquals(1, db.generationDao().getOpenOutlineForMission(mId).size)
    }

    @Test
    fun theWeeklyPatternIsFrozenWhileAnOutlineGenerationIsOpenAndEditableAgainAfterItFails() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        val generationId = (manager.confirm(mId) as ConfirmOutcome.Queued).generationId

        assertPatternRefused(mId, "RLR", stored = "RL", openAs = "QUEUED")
        db.generationDao().markRunning(generationId)
        assertPatternRefused(mId, "RLR", stored = "RL", openAs = "RUNNING")

        db.generationDao().markFailed(generationId, error = "coach gave up", finishedAt = 1L)
        manager.setPattern(mId, "RLR")
        assertEquals("RLR", missionDao.get(mId)!!.pattern)
    }

    // ── retryOutline ───────────────────────────────────────────────────────────

    @Test
    fun retryOutlineRefusesUnlessTheDraftsNewestOutlineHasFailed() = runBlocking {
        configureCoach()
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)

        // Nothing has been asked for yet, so there is no failed outline to retry.
        assertRetryRefused(missionId, "with no outline generation")

        // The coach is still working on this draft's outline.
        val queued = seedOutline(missionId, GenerationStatus.QUEUED)
        assertRetryRefused(missionId, "while the newest outline is still QUEUED")
        db.generationDao().delete(queued)

        // A SUCCEEDED outline is not a failure: the draft waits on the owner, not on a retry.
        val succeeded = seedOutline(missionId, GenerationStatus.SUCCEEDED)
        assertRetryRefused(missionId, "while the newest outline has SUCCEEDED")
        db.generationDao().delete(succeeded)

        // With no host or token the answer is the outcome confirm gives rather than a throw, and nothing is written.
        val failed = seedOutline(missionId, GenerationStatus.FAILED)
        for ((host, token) in listOf("" to "t0ken", "coach.local" to "", "   " to "   ")) {
            configureCoach(host, token)
            assertEquals(ConfirmOutcome.CoachNotConfigured, manager.retryOutline(missionId))
        }
        assertEquals(listOf(failed), db.generationDao().getAll())
        assertEquals(MissionStatus.DRAFT, missionDao.get(missionId)!!.status)

        configureCoach()
        val retried = manager.retryOutline(missionId) as ConfirmOutcome.Queued
        val newest = db.generationDao().getNewestNonCancelledOutlineForMission(missionId)!!
        assertEquals(retried.generationId, newest.id)
        assertEquals(GenerationKind.OUTLINE, newest.kind)
        assertEquals(GenerationStatus.QUEUED, newest.status)
        assertNull(newest.sortieId)
        assertEquals(MissionStatus.DRAFT, missionDao.get(missionId)!!.status)
        assertEquals(0, sortieDao.getForMission(missionId).size)
        // The row that failed keeps its status and the reason it failed for.
        val kept = db.generationDao().get(failed.id)!!
        assertEquals(GenerationStatus.FAILED, kept.status)
        assertEquals("coach gave up", kept.error)

        // The new row is the newest non-cancelled outline, so a second Retry refuses until this one finishes.
        assertRetryRefused(missionId, "while the newest outline is still QUEUED")

        // Last week's draft is not this week's, whatever its outline did.
        val pastId = insertMission(LocalDate.of(2026, 1, 5), "RL", MissionStatus.DRAFT)
        seedOutline(pastId, GenerationStatus.FAILED)
        assertRetryRefused(pastId, "on last week's draft, 2026-01-05")

        // A week that has been started is no longer a draft, even with a FAILED outline behind it.
        for (row in db.generationDao().getAll()) db.generationDao().delete(row)
        seedOutline(missionId, GenerationStatus.FAILED)
        assertEquals(emptyList<Long>(), manager.continueWithoutOutline(missionId))
        assertRetryRefused(missionId, "on an ACTIVE mission")
    }

    // ── continue without outline ───────────────────────────────────────────────

    @Test
    fun continueWithoutOutlinePlansFirstRunWhenGenerationOff() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)

        val mission = missionDao.get(mId)!!
        assertEquals(MissionStatus.ACTIVE, mission.status)

        val sorties = sortieDao.getForMission(mId)
        assertEquals(2, sorties.size)
        assertEquals(SortieType.RUN, sorties[0].type)
        assertEquals(SortieState.PLANNED, sorties[0].state)
        assertEquals(SortieType.LIFT, sorties[1].type)
        assertEquals(SortieState.PENDING, sorties[1].state)

        // Sortie 0 has a SIMPLE_RUN plan with notes = "easy".
        val plan = flightPlanDao.getPlan(sorties[0].id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
        assertEquals("Run", plan.plan.title)
        assertEquals("easy", plan.plan.notes)
    }

    @Test
    fun continueWithoutOutlineLeavesLiftPending() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "LR", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)

        val sorties = sortieDao.getForMission(mId)
        assertEquals(SortieType.LIFT, sorties[0].type)
        assertEquals(SortieState.PENDING, sorties[0].state)
        assertNull(flightPlanDao.getPlan(sorties[0].id))
    }

    @Test
    fun continueWithoutOutlineLeavesRunPendingWhenGenerationOn() = runBlocking {
        settingsStore.setGenerateRunPlans(true)
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)

        val sorties = sortieDao.getForMission(mId)
        assertEquals(SortieState.PENDING, sorties[0].state)
    }

    @Test
    fun continueWithoutOutlineHandsBackEveryOpenOutlineGeneration() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        val queued = (manager.confirm(mId) as ConfirmOutcome.Queued).generationId
        val running = db.generationDao().insert(Generation(
            kind = GenerationKind.OUTLINE, missionId = mId, status = GenerationStatus.RUNNING, createdAt = 0L
        ))
        // A finished outline row is not open, so it is not handed back to cancel.
        db.generationDao().insert(Generation(
            kind = GenerationKind.OUTLINE, missionId = mId, status = GenerationStatus.FAILED, createdAt = 0L
        ))

        val open = manager.continueWithoutOutline(mId)
        assertEquals(setOf(queued, running), open.toSet())
        assertEquals(2, open.size)
        assertEquals(MissionStatus.ACTIVE, missionDao.get(mId)!!.status)
        assertEquals(2, sortieDao.getForMission(mId).size)
    }

    // ── launch ───────────────────────────────────────────────────────────

    @Test
    fun launchRecordsLaunchedAt() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)

        val sorties = sortieDao.getForMission(mId)
        assertEquals(SortieState.PLANNED, sorties[0].state)

        // Advance clock so launchedAt is distinct.
        advanceClock(1)
        manager.launch(sorties[0].id)

        val updated = sortieDao.get(sorties[0].id)!!
        assertEquals(SortieState.IN_FLIGHT, updated.state)
        assertEquals(Instant.parse("2026-01-13T00:00:00Z").toEpochMilli(), updated.launchedAt)
    }

    @Test
    fun launchRejectedWhileAnotherSortieInFlight() = runBlocking {
        // Mission 1 has an in-flight sortie.
        val m1Id = insertMission(LocalDate.of(2026, 1, 5), "R", MissionStatus.ACTIVE)
        val s1Id = insertSortie(m1Id, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)

        // Mission 2 has a planned sortie.
        val m2Id = insertMission(LocalDate.of(2026, 1, 19), "R", MissionStatus.ACTIVE)
        val s2Id = insertSortie(m2Id, 0, SortieType.RUN, "easy", SortieState.PLANNED)

        try {
            manager.launch(s2Id)
            throw AssertionError("Should have thrown")
        } catch (e: IllegalStateException) {
            // Expected — another sortie is in flight.
        }
    }

    // ── land ─────────────────────────────────────────────────────────────

    @Test
    fun landRecordsLandedTime() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.ACTIVE)
        val sId = insertSortie(mId, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)

        advanceClock(1)
        manager.land(sId)

        val updated = sortieDao.get(sId)!!
        assertEquals(SortieState.LANDED, updated.state)
        assertEquals(Instant.parse("2026-01-13T00:00:00Z").toEpochMilli(), updated.landedAt)
    }

    // A run landed with a distance and a duration: both are stored on the sortie row as the numbers
    // passed in — no conversion, no rounding — in the same row that turns LANDED.
    @Test
    fun landStoresRunDistanceAndRunMinutes() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.ACTIVE)
        val sId = insertSortie(mId, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)

        advanceClock(1)
        manager.land(sId, 5.2, 31.0)

        val updated = sortieDao.get(sId)!!
        assertEquals(SortieState.LANDED, updated.state)
        assertEquals(Instant.parse("2026-01-13T00:00:00Z").toEpochMilli(), updated.landedAt)
        assertEquals(5.2, updated.runDistance!!, 0.0)
        assertEquals(31.0, updated.runMinutes!!, 0.0)
    }

    // The call that passes neither — every existing caller — leaves both fields null, not 0.
    @Test
    fun landWithoutRunActualsLeavesThemNull() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.ACTIVE)
        val sId = insertSortie(mId, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)

        manager.land(sId)

        val updated = sortieDao.get(sId)!!
        assertEquals(SortieState.LANDED, updated.state)
        assertNull("a run that was not measured keeps no distance", updated.runDistance)
        assertNull(updated.runMinutes)
    }

    @Test
    fun landMarksUncheckedSetsNotDone() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.ACTIVE)
        val sId = insertSortie(mId, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)

        // Write a plan with two sets: one DONE, one OPEN.
        flightPlanDao.writePlan(sId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Test", estimatedMinutes = null,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(com.liftoff.app.data.ExerciseDraft(
                name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null,
                sets = listOf(
                    com.liftoff.app.data.SetDraft(reps = 10, seconds = null, weight = null),
                    com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = null)
                )
            )),
            segments = emptyList()
        ))

        val plan = flightPlanDao.getPlan(sId)!!
        val sets = plan.exercises[0].sets
        db.flightPlanDao().updateSetActuals(sets[0].id, 10, null, null, SetStatus.DONE)

        manager.land(sId)

        // The second set should be SKIPPED; the first stays DONE.
        val updated = flightPlanDao.getPlan(sId)!!
        assertEquals(SetStatus.DONE, updated.exercises[0].sets[0].status)
        assertEquals(SetStatus.SKIPPED, updated.exercises[0].sets[1].status)
    }

    @Test
    fun landAdvancesToNextSortie() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RR", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId) // creates sorties

        val sorties = sortieDao.getForMission(mId)
        assertEquals(SortieType.RUN, sorties[0].type)
        assertEquals(SortieState.PLANNED, sorties[0].state)
        assertEquals(SortieType.RUN, sorties[1].type)
        assertEquals(SortieState.PENDING, sorties[1].state)

        // Launch and land sortie 0.
        manager.launch(sorties[0].id)
        manager.land(sorties[0].id)

        // Sortie 1 should now be PLANNED with a simple run plan.
        val updated = sortieDao.getForMission(mId)
        assertEquals(SortieState.PLANNED, updated[1].state)
        val plan = flightPlanDao.getPlan(updated[1].id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
    }

    @Test
    fun landingLastSortieClosesMission() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId) // creates sortie
        val sId = sortieDao.getForMission(mId)[0].id

        // Launch first so the sortie is IN_FLIGHT.
        manager.launch(sId)
        manager.land(sId)

        val mission = missionDao.get(mId)!!
        assertEquals(MissionStatus.CLOSED, mission.status)
    }

    // ── scrub ────────────────────────────────────────────────────────────

    @Test
    fun scrubStoresReasonAndPreparesNextSortie() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RR", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId) // creates sorties

        val sorties = sortieDao.getForMission(mId)
        assertEquals(SortieType.RUN, sorties[0].type)
        assertEquals(SortieState.PLANNED, sorties[0].state)
        assertEquals(SortieType.RUN, sorties[1].type)
        assertEquals(SortieState.PENDING, sorties[1].state)

        // Scrub sortie 0 with a reason.
        manager.scrub(sorties[0].id, "weather")

        val s0 = sortieDao.get(sorties[0].id)!!
        assertEquals(SortieState.SCRUBBED, s0.state)
        assertEquals("weather", s0.scrubReason)

        // Sortie 1 should now be PLANNED with a simple run plan.
        val updated = sortieDao.getForMission(mId)
        assertEquals(SortieState.PLANNED, updated[1].state)
        val plan = flightPlanDao.getPlan(updated[1].id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
    }

    @Test
    fun scrubInFlightKeepsCheckedSets() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 5), "R", MissionStatus.ACTIVE)
        val sId = insertSortie(mId, 0, SortieType.RUN, "easy", SortieState.IN_FLIGHT)

        // Write a plan with two sets.
        flightPlanDao.writePlan(sId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Test", estimatedMinutes = null,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(com.liftoff.app.data.ExerciseDraft(
                name = "Push-up", equipmentIds = emptyList(), restSeconds = null, notes = null,
                sets = listOf(
                    com.liftoff.app.data.SetDraft(reps = 10, seconds = null, weight = null),
                    com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = null)
                )
            )),
            segments = emptyList()
        ))

        // Mark first set as DONE.
        val plan = flightPlanDao.getPlan(sId)!!
        val sets = plan.exercises[0].sets
        db.flightPlanDao().updateSetActuals(sets[0].id, 10, null, null, SetStatus.DONE)

        manager.scrub(sId, "felt sick")

        // Sets should be unchanged.
        val planAfter = flightPlanDao.getPlan(sId)!!
        assertEquals(SetStatus.DONE, planAfter.exercises[0].sets[0].status)
        assertEquals(SetStatus.OPEN, planAfter.exercises[0].sets[1].status)
    }

    @Test
    fun scrubbingLastSortieClosesMission() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId) // creates sortie
        val sId = sortieDao.getForMission(mId)[0].id

        manager.scrub(sId, "weather")

        val mission = missionDao.get(mId)!!
        assertEquals(MissionStatus.CLOSED, mission.status)
    }

    // ── advance queues the Flight Plan, and reports the id ─────────────────────

    /** The mission's FLIGHT_PLAN generation rows, newest first. */
    private suspend fun flightPlanRows(missionId: Long) = db.generationDao().getAll()
        .filter { it.kind == GenerationKind.FLIGHT_PLAN && it.missionId == missionId }

    private suspend fun sortieAt(missionId: Long, index: Int) =
        sortieDao.getForMission(missionId).first { it.index == index }

    // Storing an outline activates the week and prepares sortie 1. With Mission Control configured and run
    // generation on, that preparation is one QUEUED FLIGHT_PLAN row, and storeOutline reports its id.
    @Test
    fun storingAnOutlineQueuesSortieOnesFlightPlanAndReportsItsId() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val draft = manager.onAppOpen()
        val valid = CoachFixtures.validReply(CoachFixtures.OUTLINE, CoachFixtures.OUTLINE_CONTEXT)

        var queued: Long? = null
        assertEquals(StoreOutcome.Stored, manager.storeOutline(draft.id, valid) { queued = it })

        val rows = flightPlanRows(draft.id)
        assertEquals(1, rows.size)
        assertEquals(GenerationStatus.QUEUED, rows[0].status)
        assertEquals(draft.id, rows[0].missionId)
        assertEquals(sortieAt(draft.id, 0).id, rows[0].sortieId)
        assertEquals(SortieState.PENDING, sortieAt(draft.id, 0).state)
        assertEquals(rows[0].id, queued)
    }

    // 'Continue without an outline' hands back the outline rows still open so the caller can cancel them, and
    // queues the plan for the sortie that just became current. The plan it queued is not among the ids to cancel.
    @Test
    fun continuingWithoutAnOutlineQueuesSortieOnesFlightPlanAndStillHandsBackTheOpenOutlineIdsToCancel() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "LR", MissionStatus.DRAFT)
        val outlineId = (manager.confirm(mId) as ConfirmOutcome.Queued).generationId

        var queued: Long? = null
        val toCancel = manager.continueWithoutOutline(mId) { queued = it }

        assertEquals(listOf(outlineId), toCancel)
        assertFalse("the plan just queued must not be cancelled", toCancel.contains(queued!!))

        val rows = flightPlanRows(mId)
        assertEquals(1, rows.size)
        assertEquals(GenerationStatus.QUEUED, rows[0].status)
        assertEquals(sortieAt(mId, 0).id, rows[0].sortieId)
        assertEquals(rows[0].id, queued)
    }

    // Landing the current sortie moves the week on; the sortie that has just become current is a lift waiting on
    // a generation, so land queues exactly one QUEUED FLIGHT_PLAN row for it and reports its id.
    @Test
    fun landingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)
        val s0 = sortieAt(mId, 0)
        assertEquals(SortieState.PLANNED, s0.state)
        assertEquals(0, flightPlanRows(mId).size)

        manager.launch(s0.id)
        val queued = manager.land(s0.id)

        val rows = flightPlanRows(mId)
        assertEquals(1, rows.size)
        assertEquals(GenerationStatus.QUEUED, rows[0].status)
        assertEquals(sortieAt(mId, 1).id, rows[0].sortieId)
        assertEquals(rows[0].id, queued)
    }

    // The same through scrub: the sortie scrub made current gets one QUEUED FLIGHT_PLAN row, and scrub reports
    // its id.
    @Test
    fun scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId() = runBlocking {
        configureCoach()
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)
        val s0 = sortieAt(mId, 0)
        assertEquals(0, flightPlanRows(mId).size)

        val queued = manager.scrub(s0.id, "weather")

        val rows = flightPlanRows(mId)
        assertEquals(1, rows.size)
        assertEquals(GenerationStatus.QUEUED, rows[0].status)
        assertEquals(sortieAt(mId, 1).id, rows[0].sortieId)
        assertEquals(rows[0].id, queued)
    }

    // ── the guard cases: the toggle both ways, an unconfigured Mission Control, never a second row ──

    /** One FLIGHT_PLAN generation row already waiting on [sortieId], older than anything the manager writes. */
    private suspend fun seedFlightPlan(
        missionId: Long,
        sortieId: Long,
        status: GenerationStatus,
        at: Long
    ): Generation {
        val id = db.generationDao().insert(Generation(
            kind = GenerationKind.FLIGHT_PLAN,
            missionId = missionId,
            sortieId = sortieId,
            status = status,
            createdAt = at
        ))
        return db.generationDao().get(id)!!
    }

    /** [sortieId]'s FLIGHT_PLAN generation rows, newest first. */
    private suspend fun flightPlanRowsFor(sortieId: Long) = db.generationDao().getAll()
        .filter { it.kind == GenerationKind.FLIGHT_PLAN && it.sortieId == sortieId }

    /** Every generation row the mission has, whatever its kind or status. */
    private suspend fun generationRowsFor(missionId: Long) =
        db.generationDao().getAll().filter { it.missionId == missionId }

    // Generate run plans on, Mission Control configured: the current run is prepared by a generation, so it gets
    // one QUEUED FLIGHT_PLAN row and not the fallback plan. It stays PENDING because the plan is what the coach
    // will write back.
    @Test
    fun aCurrentRunSortieWithRunGenerationOnQueuesAFlightPlanAndWritesNoSimplePlan() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)

        var queued: Long? = null
        manager.continueWithoutOutline(mId) { queued = it }

        val sortie = sortieAt(mId, 0)
        assertEquals(SortieType.RUN, sortie.type)
        assertEquals(SortieState.PENDING, sortie.state)

        val rows = flightPlanRows(mId)
        assertEquals(1, rows.size)
        assertEquals(GenerationKind.FLIGHT_PLAN, rows[0].kind)
        assertEquals(GenerationStatus.QUEUED, rows[0].status)
        assertEquals(sortie.id, rows[0].sortieId)
        assertEquals(rows[0].id, queued)

        assertNull("the simple 'Run' plan is not written when a generation is queued",
            flightPlanDao.getPlan(sortie.id))
    }

    // Generate run plans off, Mission Control fully configured: the fallback is what prepares the run, so the
    // sortie takes the simple 'Run' plan, moves to PLANNED, and nothing at all is queued.
    @Test
    fun aCurrentRunSortieWithRunGenerationOffGetsTheSimpleRunPlanAndQueuesNothing() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(false)
        val mId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.DRAFT)

        var queued: Long? = null
        manager.continueWithoutOutline(mId) { queued = it }

        val sortie = sortieAt(mId, 0)
        assertEquals(SortieState.PLANNED, sortie.state)
        val plan = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
        assertEquals("Run", plan.plan.title)

        assertNull("nothing queued, so nothing to report", queued)
        assertEquals(0, generationRowsFor(mId).size)
    }

    // The toggle is read at the moment a sortie is prepared. advance only ever prepares the current sortie, and
    // finishing that sortie is the only way to run it again, so driving the manager again here is the app opening
    // on the same week: sortie 0 still holds the same plan row, still PLANNED, still with no generation.
    @Test
    fun turningRunGenerationOnAfterwardsLeavesTheSimpleRunPlanThatSortieAlreadyHas() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(false)
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.DRAFT)
        manager.continueWithoutOutline(mId)

        val held = flightPlanDao.getPlan(sortieAt(mId, 0).id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, held.plan.source)
        assertEquals(0, flightPlanRows(mId).size)

        settingsStore.setGenerateRunPlans(true)
        assertEquals(mId, manager.onAppOpen().id)

        val sortie = sortieAt(mId, 0)
        assertEquals(SortieState.PLANNED, sortie.state)
        val now = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(held.plan.id, now.plan.id)
        assertEquals(FlightPlanSource.SIMPLE_RUN, now.plan.source)
        assertEquals("Run", now.plan.title)
        assertEquals(0, flightPlanRows(mId).size)
    }

    // No host, and again no token: Mission Control cannot be asked, so advance queues nothing, reports nothing,
    // and leaves the sortie that needs a plan PENDING with no plan. Generate run plans are on, so both runs here
    // are sorties that need a generation.
    @Test
    fun aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken() = runBlocking {
        settingsStore.setGenerateRunPlans(true)
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RR", MissionStatus.DRAFT)

        configureCoach(host = "", token = "t0ken")
        var queued: Long? = null
        manager.continueWithoutOutline(mId) { queued = it }

        val first = sortieAt(mId, 0)
        assertEquals(SortieState.PENDING, first.state)
        assertNull(flightPlanDao.getPlan(first.id))
        assertNull(queued)
        assertEquals(0, generationRowsFor(mId).size)

        configureCoach(host = "coach.local", token = "")
        val reported = manager.scrub(first.id, "weather")

        val second = sortieAt(mId, 1)
        assertEquals(SortieState.PENDING, second.state)
        assertNull(flightPlanDao.getPlan(second.id))
        assertNull(reported)
        assertEquals(0, generationRowsFor(mId).size)
    }

    // A sortie that already has an open Flight Plan generation is not asked for a second one; advance reports the
    // row it found instead. Once with that row QUEUED, once with it RUNNING.
    @Test
    fun aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported() = runBlocking {
        val mId = insertMission(LocalDate.of(2026, 1, 12), "RLL", MissionStatus.DRAFT)
        configureCoach(host = "", token = "") // the week is created with Mission Control unreachable
        manager.continueWithoutOutline(mId)
        configureCoach()

        val s0 = sortieAt(mId, 0)
        val queuedRow = seedFlightPlan(mId, sortieAt(mId, 1).id, GenerationStatus.QUEUED, at = 1000L)
        val runningRow = seedFlightPlan(mId, sortieAt(mId, 2).id, GenerationStatus.RUNNING, at = 2000L)

        manager.launch(s0.id)
        val reported = manager.land(s0.id) // sortie 1 is now current and already has an open row

        assertEquals(queuedRow.id, reported)
        assertEquals(listOf(queuedRow), flightPlanRowsFor(sortieAt(mId, 1).id))
        assertEquals(SortieState.PENDING, sortieAt(mId, 1).state)
        assertNull(flightPlanDao.getPlan(sortieAt(mId, 1).id))

        val reportedAgain = manager.scrub(sortieAt(mId, 1).id, "weather") // sortie 2, with its row RUNNING

        assertEquals(runningRow.id, reportedAgain)
        assertEquals(listOf(runningRow), flightPlanRowsFor(sortieAt(mId, 2).id))
        assertEquals(2, flightPlanRows(mId).size)
    }
}
