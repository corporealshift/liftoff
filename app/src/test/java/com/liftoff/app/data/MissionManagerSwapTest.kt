package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.coach.GenerationContext
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * What `MissionManager.swapSortieType` writes (DESIGN.md §4.1, §5.1, §9, amended 2026-11-23): the gates it
 * copies from `regenerateFlightPlan` plus its own state gate, the one transaction that flips the sortie and
 * the pattern letter in place, the Flight Plan the swap deletes, and which arm prepares the swapped sortie —
 * the simple "Run" draft, a FLIGHT_PLAN generation, or nothing at all when Mission Control is not configured.
 *
 * The flip itself is `domain.swapSortieType`'s and SortieSwapTest covers it; every case here is about the row
 * the manager wrote, because the Launchpad reads the database rather than the return value.
 *
 * The fixture is MissionManagerTest's: Robolectric, in-memory Room, and a real SettingsStore over a real
 * PreferenceDataStore in the Robolectric cache directory. `configureCoach()` and
 * `settingsStore.setGenerateRunPlans(...)` are the two knobs that decide which arm the swap takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerSwapTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var generationDao: GenerationDao
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
        generationDao = db.generationDao()

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

    // --- Fixture helpers, in MissionManagerTest's shape ---

    /** Mission Control pointed at something: what decides queue-or-not, which is not the same as refuse-or-not. */
    private suspend fun configureCoach(host: String = "coach.local", token: String = "t0ken") {
        settingsStore.setDaemonHost(host)
        settingsStore.setDaemonToken(token)
    }

    private fun insertMission(weekStart: LocalDate, pattern: String, status: MissionStatus): Long = runBlocking {
        missionDao.insert(Mission(
            weekStart = weekStart, pattern = pattern, status = status, outlineNotes = null
        ))
    }

    private fun insertSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        focus: String?,
        state: SortieState = SortieState.PENDING
    ): Long = runBlocking {
        sortieDao.insert(Sortie(
            missionId = missionId, index = index, type = type, focus = focus,
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
    }

    private fun sortieAt(missionId: Long, index: Int): Sortie = runBlocking {
        sortieDao.getForMission(missionId).single { it.index == index }
    }

    private fun seedFlightPlan(
        missionId: Long,
        sortieId: Long,
        status: GenerationStatus,
        at: Long
    ): Generation = runBlocking {
        val id = generationDao.insert(
            Generation(
                kind = GenerationKind.FLIGHT_PLAN,
                missionId = missionId,
                sortieId = sortieId,
                status = status,
                createdAt = at
            )
        )
        generationDao.get(id)!!
    }

    /** The coach's own reply, stored the way the worker stores it. */
    private suspend fun storeCoachPlan(sortieId: Long, fixture: String, context: GenerationContext) =
        manager.storePlan(sortieId, CoachFixtures.validReply(fixture, context))

    // --- The swap ---

    // Must show: on a PENDING current sortie of this week's ACTIVE Mission, the same Mission row gets the
    // flipped letter at that index, the same sortie row gets the new type and default focus, and no other
    // sortie or pattern letter changes.
    @Test
    fun swapOnAPendingSortieFlipsTheSortieAndThePatternLetterInPlace() = runBlocking {
        configureCoach()
        // Run generation on, so the swapped-to-run sortie is prepared by a generation and stays PENDING:
        // this case is about the flip, not about which arm prepares the sortie.
        settingsStore.setGenerateRunPlans(true)
        settingsStore.setDefaultPattern("LRLR")
        val draft = manager.onAppOpen()
        manager.continueWithoutOutline(draft.id)

        val current = sortieAt(draft.id, 0)
        assertEquals(SortieType.LIFT, current.type)
        assertEquals(SortieState.PENDING, current.state)
        // A focus the owner typed in for this sortie, so the new type's default focus is a change the test
        // can see being written rather than a value that was already there.
        sortieDao.update(current.copy(focus = "lats and shoulders", focusRationale = "lower back was tight"))
        val patternBefore = missionDao.get(draft.id)!!.pattern
        val sortiesBefore = sortieDao.getForMission(draft.id)

        manager.swapSortieType(current.id)

        val week = missionDao.get(draft.id)!!
        assertEquals("the swap wrote the same Mission row", draft.id, week.id)
        assertEquals(MissionStatus.ACTIVE, week.status)
        assertEquals("RRLR", week.pattern)
        for (index in 1 until patternBefore.length) {
            assertEquals("the letter at index $index", patternBefore[index], week.pattern[index])
        }

        val swapped = sortieDao.get(current.id)!!
        assertEquals("the swap wrote the same sortie row", current.id, swapped.id)
        assertEquals(SortieType.RUN, swapped.type)
        assertEquals("easy", swapped.focus)
        assertNull("the rationale goes with the focus it justified", swapped.focusRationale)
        assertEquals(SortieState.PENDING, swapped.state)

        val sortiesAfter = sortieDao.getForMission(draft.id)
        assertEquals(sortiesBefore.size, sortiesAfter.size)
        for (index in 1 until sortiesBefore.size) {
            assertEquals("the sortie at index $index", sortiesBefore[index], sortiesAfter[index])
        }
    }

    // Must show: on a PLANNED sortie with a generated Flight Plan (coach configured), the old plan's rows
    // (exercises, sets, segments) are gone and a new FLIGHT_PLAN generation is queued for the swapped
    // sortie. Lift -> run with Generate run plans on also queues a generation.
    @Test
    fun swapOnAPlannedSortieDeletesItsFlightPlanAndQueuesAFreshFlightPlanGeneration() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.ACTIVE)
        val sortieId = insertSortie(missionId, 0, SortieType.RUN, "tempo, 20 minutes")
        insertSortie(missionId, 1, SortieType.LIFT, "full body")

        // A run day the coach planned: the plan's rows are its segments, and the sortie is PLANNED.
        assertEquals(StoreOutcome.Stored, storeCoachPlan(sortieId, CoachFixtures.RUN_PLAN, CoachFixtures.RUN_CONTEXT))
        val runPlan = flightPlanDao.getPlan(sortieId)!!
        assertEquals(FlightPlanSource.GENERATED, runPlan.plan.source)
        assertEquals(4, runPlan.segments.size)
        assertEquals(4, flightPlanDao.countRunSegments())
        assertEquals(SortieState.PLANNED, sortieDao.get(sortieId)!!.state)

        val queued = mutableListOf<Long>()
        manager.swapSortieType(sortieId) { queued.add(it) }

        assertNull("the swap deleted the plan the sortie held", flightPlanDao.getPlan(sortieId))
        assertEquals(0, flightPlanDao.countPlans())
        assertEquals("the segments went with it", 0, flightPlanDao.countRunSegments())
        assertEquals("LL", missionDao.get(missionId)!!.pattern)
        assertEquals(SortieType.LIFT, sortieDao.get(sortieId)!!.type)
        assertEquals("a sortie left with no plan cannot stay PLANNED", SortieState.PENDING, sortieDao.get(sortieId)!!.state)

        assertEquals(1, queued.size)
        val fresh = generationDao.get(queued.single())!!
        assertEquals(GenerationKind.FLIGHT_PLAN, fresh.kind)
        assertEquals(GenerationStatus.QUEUED, fresh.status)
        assertEquals(sortieId, fresh.sortieId)

        // The same sortie, now a lift day the coach has planned, swapped back to a run day with Generate run
        // plans on: the exercises and the sets go with that plan too, and it queues a generation as well.
        assertEquals(StoreOutcome.Stored, storeCoachPlan(sortieId, CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))
        val liftPlan = flightPlanDao.getPlan(sortieId)!!
        assertEquals(SortieState.PLANNED, sortieDao.get(sortieId)!!.state)
        assertTrue(liftPlan.exercises.isNotEmpty())
        assertTrue(flightPlanDao.countPlannedSets() > 0)

        manager.swapSortieType(sortieId) { queued.add(it) }

        assertNull(flightPlanDao.getPlan(sortieId))
        assertEquals("the exercises went with it", 0, flightPlanDao.countPlannedExercises())
        assertEquals("and the sets", 0, flightPlanDao.countPlannedSets())
        assertEquals("RL", missionDao.get(missionId)!!.pattern)
        assertEquals(SortieType.RUN, sortieDao.get(sortieId)!!.type)
        assertEquals(2, queued.size)
        assertEquals(GenerationStatus.QUEUED, generationDao.get(queued.last())!!.status)
    }

    // Must show: the sortie's open FLIGHT_PLAN generations from before the swap are returned for the caller
    // to cancel, and the generation the swap queued is not among them.
    @Test
    fun swapReturnsTheSortiesOpenGenerationsToCancelButNotTheOneItQueued() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "LR", MissionStatus.ACTIVE)
        val sortieId = insertSortie(missionId, 0, SortieType.LIFT, "full body")
        val otherSortieId = insertSortie(missionId, 1, SortieType.RUN, "easy")

        val running = seedFlightPlan(missionId, sortieId, GenerationStatus.RUNNING, at = 0)
        val queuedBefore = seedFlightPlan(missionId, sortieId, GenerationStatus.QUEUED, at = 1)
        val succeeded = seedFlightPlan(missionId, sortieId, GenerationStatus.SUCCEEDED, at = 2)
        val otherSortieRow = seedFlightPlan(missionId, otherSortieId, GenerationStatus.QUEUED, at = 3)

        val queuedBySwap = mutableListOf<Long>()
        val toCancel = manager.swapSortieType(sortieId) { queuedBySwap.add(it) }

        assertEquals(
            "newest first, and only this sortie's open Flight Plans",
            listOf(queuedBefore.id, running.id),
            toCancel
        )
        assertFalse("a row that already finished is not a row to cancel", toCancel.contains(succeeded.id))
        assertFalse("another sortie's generation is not this swap's business", toCancel.contains(otherSortieRow.id))

        assertEquals(1, queuedBySwap.size)
        val freshId = queuedBySwap.single()
        assertFalse("the generation this swap queued is not one to cancel", toCancel.contains(freshId))
        assertEquals(GenerationStatus.QUEUED, generationDao.get(freshId)!!.status)

        // None of them was marked CANCELLED inside the swap's transaction: GenerationCanceller skips a row
        // that is not QUEUED or RUNNING, so each one is still cancellable after the commit.
        for (id in toCancel) {
            val row = generationDao.get(id)!!
            assertTrue(
                "generation $id is still ${row.status}, so GenerationCanceller can take it",
                row.status == GenerationStatus.QUEUED || row.status == GenerationStatus.RUNNING
            )
        }
        assertEquals(GenerationStatus.SUCCEEDED, generationDao.get(succeeded.id)!!.status)
    }

    // Must show: swapping a lift to a run with Generate run plans off writes the simple "Run" plan, leaves
    // the sortie PLANNED and queues no generation.
    @Test
    fun swapToARunWithRunGenerationOffWritesASimpleRunPlanAndQueuesNothing() = runBlocking {
        // Mission Control is configured here, so "queues nothing" is the toggle's doing and not the coach's absence.
        configureCoach()
        settingsStore.setGenerateRunPlans(false)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "LR", MissionStatus.ACTIVE)
        val sortieId = insertSortie(missionId, 0, SortieType.LIFT, "full body")
        insertSortie(missionId, 1, SortieType.RUN, "easy")

        assertEquals(StoreOutcome.Stored, storeCoachPlan(sortieId, CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))
        assertEquals(FlightPlanSource.GENERATED, flightPlanDao.getPlan(sortieId)!!.plan.source)
        assertTrue(flightPlanDao.countPlannedExercises() > 0)

        val queued = mutableListOf<Long>()
        val toCancel = manager.swapSortieType(sortieId) { queued.add(it) }

        val plan = flightPlanDao.getPlan(sortieId)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
        assertEquals("Run", plan.plan.title)
        assertEquals("easy", plan.plan.notes)
        assertTrue(plan.exercises.isEmpty())
        assertTrue(plan.segments.isEmpty())
        assertEquals("the coach's exercises and sets went with the plan", 0, flightPlanDao.countPlannedExercises())
        assertEquals(0, flightPlanDao.countPlannedSets())

        val swapped = sortieDao.get(sortieId)!!
        assertEquals(SortieType.RUN, swapped.type)
        assertEquals("easy", swapped.focus)
        assertEquals(SortieState.PLANNED, swapped.state)
        assertEquals("RR", missionDao.get(missionId)!!.pattern)

        assertTrue("nothing was open for this sortie before the swap", toCancel.isEmpty())
        assertTrue("the simple plan needs no generation", queued.isEmpty())
        assertEquals(0, generationDao.countAll())
    }

    // Must show: with the coach not configured, a swap that needs a generation still flips the type, the
    // pattern letter and the focus, leaves the sortie PENDING and queues nothing.
    @Test
    fun swapWithTheCoachNotConfiguredFlipsTheSortieAndLeavesItPending() = runBlocking {
        // configureCoach() is deliberately not called: host and token stay empty.
        settingsStore.setDefaultPattern("RLR")
        val draft = manager.onAppOpen()
        manager.continueWithoutOutline(draft.id)

        val current = sortieAt(draft.id, 0)
        assertEquals(SortieType.RUN, current.type)
        assertEquals(SortieState.PLANNED, current.state)
        assertNotNull("advance wrote the simple run plan", flightPlanDao.getPlan(current.id))

        val queued = mutableListOf<Long>()
        val toCancel = manager.swapSortieType(current.id) { queued.add(it) }

        val swapped = sortieDao.get(current.id)!!
        assertEquals(SortieType.LIFT, swapped.type)
        assertEquals("full body", swapped.focus)
        assertNull(swapped.focusRationale)
        assertEquals("a swap that cannot queue leaves the sortie PENDING", SortieState.PENDING, swapped.state)
        assertEquals("LLR", missionDao.get(draft.id)!!.pattern)
        assertNull("the plan the run day held is gone", flightPlanDao.getPlan(current.id))
        assertEquals(0, flightPlanDao.countPlans())

        assertTrue(queued.isEmpty())
        assertTrue(toCancel.isEmpty())
        assertEquals("no host and no token, so nothing was queued", 0, generationDao.countAll())
    }

    /**
     * Run the swap on [sortieId], show it refused with [expectedMessage], and show it wrote nothing: the Mission
     * row, every sortie row, the Flight Plan counts and every Generation row are the ones that were there before.
     * The counts rather than the plan rows because a refused swap must not have deleted the plan its sortie held,
     * and the rows of a plan are exactly what a swap is supposed to delete.
     */
    private suspend fun assertRefused(
        what: String,
        missionId: Long,
        sortieId: Long,
        expectedMessage: String
    ) {
        val missionBefore = missionDao.get(missionId)!!
        val sortiesBefore = sortieDao.getForMission(missionId).sortedBy { it.index }
        val plansBefore = flightPlanDao.countPlans()
        val exercisesBefore = flightPlanDao.countPlannedExercises()
        val segmentsBefore = flightPlanDao.countRunSegments()
        val generationsBefore = generationDao.getAll()
        val queued = mutableListOf<Long>()

        try {
            manager.swapSortieType(sortieId) { queued.add(it) }
            throw AssertionError("$what: the swap should have been refused")
        } catch (expected: IllegalStateException) {
            assertEquals("$what: which gate refused", expectedMessage, expected.message)
        }

        assertEquals("$what: the Mission row", missionBefore, missionDao.get(missionId)!!)
        assertEquals("$what: the sortie rows", sortiesBefore, sortieDao.getForMission(missionId).sortedBy { it.index })
        assertEquals("$what: the Flight Plans", plansBefore, flightPlanDao.countPlans())
        assertEquals("$what: the planned exercises", exercisesBefore, flightPlanDao.countPlannedExercises())
        assertEquals("$what: the run segments", segmentsBefore, flightPlanDao.countRunSegments())
        assertEquals("$what: the Generations", generationsBefore, generationDao.getAll())
        assertTrue("$what: nothing was queued", queued.isEmpty())
    }

    // Must show: a sortie that is not the current one, a sortie of a Mission that is not ACTIVE, and a sortie of
    // a Mission that is not the current week's are each refused, and nothing is written.
    @Test
    fun swapRefusesASortieThatIsNotCurrentOrNotInTheCurrentWeeksActiveMission() = runBlocking {
        // Mission Control configured and run generation on, so a swap that got past the gates would queue
        // something: each refusal below is a gate refusing, not the absence of a coach.
        configureCoach()
        settingsStore.setGenerateRunPlans(true)

        // One Mission, because the schema holds one per week (Mission.kt:11), moved through each refusal in turn.
        // Index 0 is PENDING throughout, so it is the current sortie and index 1 is a later day of the same week.
        val weekId = insertMission(LocalDate.of(2026, 1, 12), "RLR", MissionStatus.ACTIVE)
        insertSortie(weekId, 0, SortieType.RUN, "easy")
        val laterDay = insertSortie(weekId, 1, SortieType.LIFT, "full body")
        insertSortie(weekId, 2, SortieType.RUN, "easy")
        assertEquals(SortieState.PENDING, sortieAt(weekId, 0).state)
        assertRefused("a later day of the current week", weekId, laterDay, "Sortie must be current")

        // Not ACTIVE: the same current-week Mission made Draft and then Closed, so it is the status gate refusing
        // and not the week gate. Index 0 is still its current sortie.
        for (status in listOf(MissionStatus.DRAFT, MissionStatus.CLOSED)) {
            missionDao.update(missionDao.get(weekId)!!.copy(status = status))
            assertRefused("a $status Mission of the current week", weekId, sortieAt(weekId, 0).id, "Mission must be ACTIVE")
        }

        // Not this week's: ACTIVE again, but its weekStart moved to the week before the clock's week.
        missionDao.update(
            missionDao.get(weekId)!!.copy(status = MissionStatus.ACTIVE, weekStart = LocalDate.of(2026, 1, 5))
        )
        assertRefused(
            "an ACTIVE Mission that is not the current week's",
            weekId,
            sortieAt(weekId, 0).id,
            "Can only swap a sortie of the current week"
        )
    }

    // Must show: an IN_FLIGHT, LANDED or SCRUBBED sortie is refused, and nothing is written.
    @Test
    fun swapRefusesAnInFlightLandedOrScrubbedSortie() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RLR", MissionStatus.ACTIVE)
        val sortieId = insertSortie(missionId, 0, SortieType.RUN, "tempo, 20 minutes", SortieState.PLANNED)
        insertSortie(missionId, 1, SortieType.LIFT, "full body")
        insertSortie(missionId, 2, SortieType.RUN, "easy")

        // In flight the way the Launchpad puts it there: a PLANNED current sortie, launched. An in-flight sortie is
        // still the current one, so this is the swap's own state gate refusing and not the current-sortie gate.
        manager.launch(sortieId)
        assertEquals(SortieState.IN_FLIGHT, sortieDao.get(sortieId)!!.state)
        assertRefused("an IN_FLIGHT sortie", missionId, sortieId, "Sortie must be PENDING or PLANNED")

        manager.land(sortieId)
        assertEquals(SortieState.LANDED, sortieDao.get(sortieId)!!.state)
        // A landed sortie is never the current one — the domain's currentSortie skips LANDED and SCRUBBED — so the
        // refusal here is the current-sortie gate, as in unlaunchIsRefusedForASortieThatIsNotInFlight.
        assertRefused("a LANDED sortie", missionId, sortieId, "Sortie must be current")

        // The same for a scrubbed row: index 1 became the current sortie when index 0 landed, so it can be
        // scrubbed, and the row the scrub left is then refused a swap.
        val scrubbedId = sortieAt(missionId, 1).id
        manager.scrub(scrubbedId, "weather")
        assertEquals(SortieState.SCRUBBED, sortieDao.get(scrubbedId)!!.state)
        assertRefused("a SCRUBBED sortie", missionId, scrubbedId, "Sortie must be current")

        // The week moved on instead of being swapped: index 2 is the current sortie, its type is still the one the
        // pattern says, and the pattern never changed.
        val current = sortieAt(missionId, 2)
        assertEquals(SortieType.RUN, current.type)
        assertEquals("the swap never reached it", SortieState.PENDING, current.state)
        assertEquals("RLR", missionDao.get(missionId)!!.pattern)
    }
}
