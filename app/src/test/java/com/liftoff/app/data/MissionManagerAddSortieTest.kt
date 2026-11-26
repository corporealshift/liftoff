package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.coach.GenerationContext
import com.liftoff.app.domain.MAX_PATTERN_LENGTH
import com.liftoff.app.domain.sortieTypesOf
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * What `MissionManager.addSortie` writes (DESIGN.md §4.1, §4.2, §9, amended 2026-11-26): the gates it copies
 * from `swapSortieType` and `resetWeek`, the one transaction that appends the letter and the sortie together,
 * and which arm prepares the new sortie once it becomes the current one — the simple "Run" draft, a FLIGHT_PLAN
 * generation, or nothing at all when Mission Control is not configured.
 *
 * The append itself is `domain.addSortie`'s and SortieAddTest covers it; every case here is about the rows the
 * manager wrote, because the Mission tab and the Launchpad read the database rather than the return value.
 *
 * The fixture is MissionManagerSwapTest's: Robolectric, in-memory Room, and a real SettingsStore over a real
 * PreferenceDataStore in the Robolectric cache directory. `configureCoach()` and
 * `settingsStore.setGenerateRunPlans(...)` are the two knobs that decide which arm prepares the new sortie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerAddSortieTest {

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

    // --- Fixture helpers, in MissionManagerSwapTest's shape ---

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

    private fun sortiesOf(missionId: Long): List<Sortie> = runBlocking { sortieDao.getForMission(missionId) }

    private fun weekOf(missionId: Long): Mission = runBlocking { missionDao.get(missionId)!! }

    /** The coach's own reply, stored the way the worker stores it. */
    private suspend fun storeCoachPlan(sortieId: Long, fixture: String, context: GenerationContext) =
        manager.storePlan(sortieId, CoachFixtures.validReply(fixture, context))

    // --- The add ---

    // Must show: on this week's ACTIVE Mission, the pattern gains the type's letter on the end, one new PENDING
    // sortie takes the index that letter is at, and no other row moves — not even the sortie in flight.
    @Test
    fun addToActiveWeekAppendsPendingSortie() = runBlocking {
        // Mission Control configured and run generation on, so an add that reached advance would queue
        // something: the empty Generation table below is the add not reaching it, not the absence of a coach.
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RLR", MissionStatus.ACTIVE)
        val inFlight = insertSortie(missionId, 0, SortieType.RUN, "tempo, 20 minutes", SortieState.PLANNED)
        insertSortie(missionId, 1, SortieType.LIFT, "full body")
        insertSortie(missionId, 2, SortieType.RUN, "easy")
        manager.launch(inFlight)
        assertEquals(SortieState.IN_FLIGHT, sortieDao.get(inFlight)!!.state)
        val before = sortiesOf(missionId)

        val queued = mutableListOf<Long>()
        manager.addSortie(missionId, SortieType.LIFT) { queued.add(it) }

        val week = weekOf(missionId)
        assertEquals("the add wrote the same Mission row", missionId, week.id)
        assertEquals("RLRL", week.pattern)
        assertEquals(MissionStatus.ACTIVE, week.status)

        val after = sortiesOf(missionId)
        assertEquals(before.size + 1, after.size)
        assertEquals("every earlier sortie keeps its own row", before, after.take(before.size))
        assertEquals(SortieState.IN_FLIGHT, sortieDao.get(inFlight)!!.state)

        val added = after.last()
        assertEquals(before.size, added.index)
        assertEquals(week.pattern.length - 1, added.index)
        assertEquals(SortieType.LIFT, added.type)
        assertEquals("full body", added.focus)
        assertEquals(SortieState.PENDING, added.state)
        assertEquals(missionId, added.missionId)
        assertNull(added.launchedAt)
        assertNull(added.notes)

        // The sortie in front of the owner is in flight, so the new one is not the current sortie and advance
        // prepared nothing.
        assertTrue("nothing was queued", queued.isEmpty())
        assertEquals(0, generationDao.countAll())
        assertEquals(0, flightPlanDao.countPlans())
    }

    // Must show: a week whose planned days are all landed is CLOSED; adding a lift reopens it to ACTIVE and
    // prepares the new sortie with a FLIGHT_PLAN generation, which the caller is handed after the commit.
    @Test
    fun addToClosedWeekReopensAndPlansIt() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RLR", MissionStatus.CLOSED)
        insertSortie(missionId, 0, SortieType.RUN, "easy", SortieState.LANDED)
        insertSortie(missionId, 1, SortieType.LIFT, "full body", SortieState.LANDED)
        insertSortie(missionId, 2, SortieType.RUN, "easy", SortieState.LANDED)
        assertEquals(MissionStatus.CLOSED, weekOf(missionId).status)

        val queued = mutableListOf<Long>()
        manager.addSortie(missionId, SortieType.LIFT) { queued.add(it) }

        val week = weekOf(missionId)
        assertEquals("RLRL", week.pattern)
        assertEquals("a closed week takes a workout it didn't plan by reopening (§4.2)", MissionStatus.ACTIVE, week.status)

        val added = sortiesOf(missionId).last()
        assertEquals(3, added.index)
        assertEquals(SortieType.LIFT, added.type)
        assertEquals("every earlier day is done, so the new sortie is the current one", SortieState.PENDING, added.state)

        assertEquals(1, queued.size)
        val fresh = generationDao.get(queued.single())!!
        assertEquals(GenerationKind.FLIGHT_PLAN, fresh.kind)
        assertEquals(GenerationStatus.QUEUED, fresh.status)
        assertEquals(added.id, fresh.sortieId)
        assertEquals(missionId, fresh.missionId)
        assertEquals(1, generationDao.countAll())
        assertNull("a generation is what prepares a lift day; no plan was written for it", flightPlanDao.getPlan(added.id))
    }

    // Must show: on a reopened week, a run day added with Generate run plans off gets the simple "Run" draft
    // and is left PLANNED, with no generation queued.
    @Test
    fun closedRunWeekWithoutRunGenerationGetsASimplePlan() = runBlocking {
        // Mission Control is configured here, so "no generation" is the toggle's doing and not the coach's absence.
        configureCoach()
        settingsStore.setGenerateRunPlans(false)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "LL", MissionStatus.CLOSED)
        insertSortie(missionId, 0, SortieType.LIFT, "full body", SortieState.LANDED)
        insertSortie(missionId, 1, SortieType.LIFT, "legs", SortieState.LANDED)

        val queued = mutableListOf<Long>()
        manager.addSortie(missionId, SortieType.RUN) { queued.add(it) }

        assertEquals("LLR", weekOf(missionId).pattern)
        val added = sortiesOf(missionId).last()
        assertEquals(SortieType.RUN, added.type)
        assertEquals("easy", added.focus)
        assertEquals(SortieState.PLANNED, added.state)

        val plan = flightPlanDao.getPlan(added.id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
        assertEquals("Run", plan.plan.title)
        assertEquals("easy", plan.plan.notes)
        assertTrue(plan.exercises.isEmpty())
        assertTrue(plan.segments.isEmpty())

        assertTrue(queued.isEmpty())
        assertEquals(0, generationDao.countAll())
    }

    // Must show: with Mission Control not configured, a reopened week still takes the sortie and reopens, but
    // the new sortie stays PENDING and nothing is queued.
    @Test
    fun closedWeekWithoutACoachReopensAndQueuesNothing() = runBlocking {
        // configureCoach() is deliberately not called: host and token stay empty.
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.CLOSED)
        insertSortie(missionId, 0, SortieType.RUN, "easy", SortieState.LANDED)

        val queued = mutableListOf<Long>()
        manager.addSortie(missionId, SortieType.LIFT) { queued.add(it) }

        assertEquals("RL", weekOf(missionId).pattern)
        assertEquals(MissionStatus.ACTIVE, weekOf(missionId).status)
        val added = sortiesOf(missionId).last()
        assertEquals(SortieState.PENDING, added.state)
        assertTrue(queued.isEmpty())
        assertEquals("no host and no token, so nothing was queued", 0, generationDao.countAll())
        assertEquals(0, flightPlanDao.countPlans())
    }

    // Must show: an add while a sortie is already planned leaves that sortie, its generated Flight Plan and its
    // rows exactly as they were, and queues nothing.
    @Test
    fun addDoesNotTouchTheCurrentSortie() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RL", MissionStatus.ACTIVE)
        val current = insertSortie(missionId, 0, SortieType.LIFT, "full body")
        insertSortie(missionId, 1, SortieType.RUN, "easy")
        assertEquals(StoreOutcome.Stored, storeCoachPlan(current, CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))
        val planBefore = flightPlanDao.getPlan(current)!!
        assertEquals(SortieState.PLANNED, sortieDao.get(current)!!.state)
        val exercisesBefore = flightPlanDao.countPlannedExercises()
        val setsBefore = flightPlanDao.countPlannedSets()
        assertTrue(exercisesBefore > 0)

        val queued = mutableListOf<Long>()
        manager.addSortie(missionId, SortieType.LIFT) { queued.add(it) }

        val sortie = sortieDao.get(current)!!
        assertEquals("the current sortie", sortieDao.get(current)!!.state, SortieState.PLANNED)
        assertEquals(SortieType.LIFT, sortie.type)
        assertEquals("full body", sortie.focus)
        assertEquals(planBefore, flightPlanDao.getPlan(current)!!)
        assertEquals(exercisesBefore, flightPlanDao.countPlannedExercises())
        assertEquals(setsBefore, flightPlanDao.countPlannedSets())

        assertEquals("RLL", weekOf(missionId).pattern)
        assertEquals(SortieState.PENDING, sortiesOf(missionId).last().state)
        assertTrue(queued.isEmpty())
        assertEquals(0, generationDao.countAll())
    }

    // Must show: the pattern and the sorties stay the same length and the same types, letter by letter, all the
    // way to the seven-sortie cap, and the eighth add is refused.
    @Test
    fun patternAndSortieCountsStayAligned() = runBlocking {
        // No coach, run generation on: an add that reached advance would find nothing to queue, so every row
        // change below is the add's own.
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "R", MissionStatus.ACTIVE)
        insertSortie(missionId, 0, SortieType.RUN, "easy")

        for (type in listOf(
            SortieType.LIFT, SortieType.RUN, SortieType.LIFT, SortieType.LIFT, SortieType.RUN, SortieType.LIFT
        )) {
            manager.addSortie(missionId, type)
            val week = weekOf(missionId)
            val sorties = sortiesOf(missionId)
            assertEquals("pattern '${week.pattern}' and its sorties", sortieTypesOf(week.pattern), sorties.map { it.type })
            assertEquals(week.pattern.length, sorties.size)
            assertEquals(
                "each letter is the type of the sortie at its index",
                (0 until week.pattern.length).toList(),
                sorties.map { it.index }
            )
        }

        val full = weekOf(missionId)
        assertEquals(MAX_PATTERN_LENGTH, full.pattern.length)
        assertEquals(MAX_PATTERN_LENGTH, sortiesOf(missionId).size)
        assertRefused("an eighth sortie", missionId, SortieType.RUN, "This week already holds 7 sorties")
        assertEquals(full, weekOf(missionId))
    }

    /**
     * Run the add on [missionId] with [type], show it refused with [expectedMessage], and show it wrote nothing:
     * the Mission row, every sortie row, the Flight Plan counts and every Generation row are the ones that were
     * there before. The counts rather than the plan rows because a refused add must not have touched the plans
     * its sorties held, and the rows of a plan are exactly what an add is supposed to leave alone.
     */
    private suspend fun assertRefused(what: String, missionId: Long, type: SortieType, expectedMessage: String) {
        val missionBefore = missionDao.get(missionId)!!
        val sortiesBefore = sortieDao.getForMission(missionId)
        val plansBefore = flightPlanDao.countPlans()
        val exercisesBefore = flightPlanDao.countPlannedExercises()
        val segmentsBefore = flightPlanDao.countRunSegments()
        val generationsBefore = generationDao.getAll()
        val queued = mutableListOf<Long>()

        try {
            manager.addSortie(missionId, type) { queued.add(it) }
            throw AssertionError("$what: the add should have been refused")
        } catch (expected: IllegalStateException) {
            assertEquals("$what: which gate refused", expectedMessage, expected.message)
        }

        assertEquals("$what: the Mission row", missionBefore, missionDao.get(missionId)!!)
        assertEquals("$what: the sortie rows", sortiesBefore, sortieDao.getForMission(missionId))
        assertEquals("$what: the Flight Plans", plansBefore, flightPlanDao.countPlans())
        assertEquals("$what: the planned exercises", exercisesBefore, flightPlanDao.countPlannedExercises())
        assertEquals("$what: the run segments", segmentsBefore, flightPlanDao.countRunSegments())
        assertEquals("$what: the Generations", generationsBefore, generationDao.getAll())
        assertTrue("$what: nothing was queued", queued.isEmpty())
    }

    // Must show: a Mission that is not the current week's is refused, and nothing is written.
    @Test
    fun refusesNonCurrentWeek() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        // The week before the clock's week (2026-01-12), ACTIVE and with room for another sortie.
        val missionId = insertMission(LocalDate.of(2026, 1, 5), "RLR", MissionStatus.ACTIVE)
        insertSortie(missionId, 0, SortieType.RUN, "easy")
        insertSortie(missionId, 1, SortieType.LIFT, "full body")
        insertSortie(missionId, 2, SortieType.RUN, "easy")

        assertRefused(
            "an ACTIVE Mission that is not the current week's",
            missionId,
            SortieType.LIFT,
            "Can only add a sortie to the current week"
        )
    }

    // Must show: a DRAFT Mission of the current week is refused, and nothing is written.
    @Test
    fun refusesDraftMission() = runBlocking {
        configureCoach()
        settingsStore.setGenerateRunPlans(true)
        val missionId = insertMission(LocalDate.of(2026, 1, 12), "RLR", MissionStatus.DRAFT)
        insertSortie(missionId, 0, SortieType.RUN, "easy")
        insertSortie(missionId, 1, SortieType.LIFT, "full body")
        insertSortie(missionId, 2, SortieType.RUN, "easy")

        assertRefused("a DRAFT Mission of the current week", missionId, SortieType.LIFT, "Mission must be confirmed")
        assertEquals("the week is still being planned as a whole", MissionStatus.DRAFT, weekOf(missionId).status)
        assertEquals("RLR", weekOf(missionId).pattern)
    }
}
