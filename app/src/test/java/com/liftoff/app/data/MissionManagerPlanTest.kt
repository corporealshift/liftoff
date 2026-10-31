package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.coach.CoachLiftPlan
import com.liftoff.app.coach.CoachRunPlan
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * `MissionManager.storePlan` fed from the valid reply fixtures: what a validated lift plan and a validated
 * run plan leave in Room, and what the sortie reads afterwards.
 *
 * The fixtures plan the sortie at a given pattern position — the lift at 1, the run at 0 — so each test first
 * makes that sortie the current one and leaves it PENDING. Clock and zone are fixed the way
 * [MissionManagerTest] fixes them, so the same week is current here as on the CI runner in UTC.
 *
 * A refusal test reads the mission, the week's sorties and every plan row back and requires them unchanged.
 * `writePlan` deletes the sortie's existing plan, so a guard that ran after it would leave a hole here even
 * though the returned outcome still named the refusal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerPlanTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var missionDao: MissionDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()
        exerciseDao = db.exerciseDao()
        missionDao = db.missionDao()

        // A file of its own, so turning run generation on here cannot reach another test class.
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "plan-${UUID.randomUUID()}.preferences_pb")
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

    /** The week the fixture context describes: pattern RLRLR, five PENDING sorties. */
    private suspend fun confirmedWeek(): Mission {
        val mission = manager.onAppOpen()
        manager.continueWithoutOutline(mission.id)
        return mission
    }

    /**
     * The lift fixture plans the sortie at pattern position 1. Scrubbing the run at index 0 through the
     * manager makes index 1 current, and it stays PENDING: `advance` prepares a lift sortie by waiting for a
     * generation, not by writing a plan.
     */
    private suspend fun currentLiftSortie(): Sortie {
        val mission = confirmedWeek()
        val sorties = sortieDao.getForMission(mission.id).sortedBy { it.index }
        manager.scrub(sorties[0].id, "test: to make the lift sortie current")
        return sortieDao.get(sorties[1].id)!!
    }

    /**
     * The run fixture plans the sortie at position 0. With run generation on, `advance` leaves that current
     * sortie PENDING instead of writing the simple "Run" plan for it, which is the state a generation would
     * answer into.
     */
    private suspend fun currentRunSortie(): Sortie {
        settingsStore.setGenerateRunPlans(true)
        val mission = confirmedWeek()
        return sortieDao.getForMission(mission.id).first { it.index == 0 }
    }

    // ── a validated lift plan ────────────────────────────────────────────────

    @Test
    fun aValidatedLiftPlanIsStoredWithItsExercisesAndSetsAndTheSortieBecomesPlanned() = runBlocking {
        val sortie = currentLiftSortie()
        assertEquals(SortieType.LIFT, sortie.type)
        assertEquals(SortieState.PENDING, sortie.state)
        assertNull("a lift sortie waits for a plan, it is not written one", flightPlanDao.getPlan(sortie.id))

        val valid = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)
        val plan = valid.plan as CoachLiftPlan

        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, valid))

        val stored = flightPlanDao.getPlan(sortie.id)!!
        assertEquals("Push + core", stored.plan.title)
        assertEquals(65, stored.plan.estimatedMinutes)
        assertEquals("3 minutes rowing, then band pull-aparts", stored.plan.warmup)
        assertEquals("Legs stay light: the tempo intervals land the day after this one.", stored.plan.notes)

        // Reply order, each name resolved through the existing resolver: four names this database had never
        // seen, so four rows of its own.
        assertEquals(listOf(0, 1, 2, 3), stored.exercises.map { it.plannedExercise.order })
        assertEquals(
            listOf("Bench Press", "Pendlay Row", "Front Squat", "Plank"),
            stored.exercises.map { it.displayName }
        )
        assertEquals(
            plan.exercises.map { exerciseDao.resolve(it.name).id },
            stored.exercises.map { it.plannedExercise.exerciseId }
        )
        assertEquals(4L, exerciseDao.countAll())

        // The coach's equipment keys are the equipment ids, rest and notes as they arrived.
        assertEquals(
            listOf(listOf("barbell", "bench"), listOf("barbell"), listOf("barbell", "bench"), listOf("bench")),
            stored.exercises.map { it.plannedExercise.equipmentIds }
        )
        assertEquals(listOf(120, 90, 150, 60), stored.exercises.map { it.plannedExercise.restSeconds })
        assertEquals(
            "Same bar as last week; add weight only if all three sets were hit.",
            stored.exercises[0].plannedExercise.notes
        )
        assertEquals(
            "Bench set to the low height so the brace stays horizontal.",
            stored.exercises[3].plannedExercise.notes
        )
        // The two exercises the fixture gives no notes.
        assertNull(stored.exercises[1].plannedExercise.notes)
        assertNull(stored.exercises[2].plannedExercise.notes)

        val bench = stored.exercises[0].sets
        assertEquals(listOf(0, 1, 2), bench.map { it.order })
        assertEquals(listOf(5, 5, 5), bench.map { it.reps })
        assertEquals(listOf<Int?>(null, null, null), bench.map { it.seconds })
        assertEquals(listOf(60.0, 60.0, 62.5), bench.map { it.weight })

        // The one set the fixture plans by time rather than reps.
        val squatSets = stored.exercises[2].sets
        assertEquals(listOf<Int?>(10, null), squatSets.map { it.reps })
        assertEquals(listOf(null, 40), squatSets.map { it.seconds })
        assertEquals(listOf(40.0, 20.0), squatSets.map { it.weight })

        val plankSets = stored.exercises[3].sets
        assertEquals(listOf<Int?>(null, null), plankSets.map { it.reps })
        assertEquals(listOf(45, 45), plankSets.map { it.seconds })
        assertEquals(listOf<Double?>(null, null), plankSets.map { it.weight })

        // Planned and not yet done: nothing has an actual, and no exercise is skipped.
        val sets = stored.exercises.flatMap { it.sets }
        assertEquals(9, sets.size)
        assertEquals(List(9) { SetStatus.OPEN }, sets.map { it.status })
        assertTrue(sets.all { it.actualReps == null && it.actualSeconds == null && it.actualWeight == null })
        assertTrue(stored.exercises.none { it.plannedExercise.skipped })
        assertTrue(stored.segments.isEmpty())

        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
    }

    @Test
    fun aStoredCoachPlanIsMarkedGeneratedAndHoldsTheValidatedJson() = runBlocking {
        val sortie = currentLiftSortie()

        val plain = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)
        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, plain))
        val first = flightPlanDao.getPlan(sortie.id)!!.plan
        assertEquals(FlightPlanSource.GENERATED, first.source)
        assertEquals(plain.json, first.rawJson)

        // The same plan arriving inside a Markdown code fence. What is stored is the object the validator
        // accepted, not the reply it arrived in.
        val fencedReply = CoachFixtures.fixture(CoachFixtures.LIFT_PLAN_FENCED)
        assertTrue(fencedReply.trimStart().startsWith("```json"))
        val fenced = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN_FENCED, CoachFixtures.LIFT_CONTEXT)
        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, fenced))

        val stored = flightPlanDao.getPlan(sortie.id)!!.plan
        assertEquals(FlightPlanSource.GENERATED, stored.source)
        assertEquals(plain.json, stored.rawJson)
        assertNotEquals("the reply the caller sent, fence and all, is not what is stored", fencedReply, stored.rawJson)
        assertTrue(stored.rawJson.startsWith("{") && stored.rawJson.endsWith("}"))
        assertTrue("no fence markers survive in the stored text", !stored.rawJson.contains("```"))

        // The stored text is the fixture's object and nothing around it.
        val fixtureObject = Json.parseToJsonElement(CoachFixtures.fixture(CoachFixtures.LIFT_PLAN))
        assertEquals(fixtureObject, Json.parseToJsonElement(stored.rawJson))
        assertEquals(fixtureObject.toString(), stored.rawJson)
    }

    // ── a validated run plan ─────────────────────────────────────────────────

    @Test
    fun aValidatedRunPlanIsStoredWithItsKindTargetDistancePaceAndSegments() = runBlocking {
        val sortie = currentRunSortie()
        assertEquals(SortieType.RUN, sortie.type)
        assertEquals(SortieState.PENDING, sortie.state)
        assertNull(
            "with run generation on, the current run sortie waits for a plan",
            flightPlanDao.getPlan(sortie.id)
        )

        val valid = CoachFixtures.validReply(CoachFixtures.RUN_PLAN, CoachFixtures.RUN_CONTEXT)
        val plan = valid.plan as CoachRunPlan

        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, valid))

        val stored = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(FlightPlanSource.GENERATED, stored.plan.source)
        // The coach's own title, not the simple plan's "Run".
        assertEquals("Tempo intervals", stored.plan.title)
        assertNotEquals("Run", stored.plan.title)
        assertEquals("tempo", stored.plan.runKind)
        assertEquals(6.5, stored.plan.targetDistance!!, 0.0)
        assertEquals("8:15/mi", stored.plan.targetPace)
        assertEquals(plan.notes, stored.plan.notes)
        assertTrue(stored.exercises.isEmpty())

        assertEquals(listOf(0, 1, 2, 3), stored.segments.map { it.order })
        assertEquals(
            listOf(
                "6 minutes easy to warm up",
                "4 x 90 seconds at tempo, 90 seconds float",
                "3 minutes at easy effort",
                "Warm-down jog",
            ),
            stored.segments.map { it.description }
        )
        assertEquals(listOf(1.0, 3.5, 0.5, 1.5), stored.segments.map { it.distance })
        assertEquals(listOf(6.0, 16.0, 3.0, 12.0), stored.segments.map { it.minutes })
        assertEquals(
            plan.segments.map { listOf(it.description, it.distance, it.minutes) },
            stored.segments.map { listOf(it.description, it.distance, it.minutes) }
        )

        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
    }

    // ── a second plan for the same sortie ────────────────────────────────────

    @Test
    fun aSecondPlanForTheSameSortieReplacesThePlanAlreadyThere() = runBlocking {
        val sortie = currentLiftSortie()
        val valid = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)

        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, valid))
        val firstPlanId = flightPlanDao.getPlan(sortie.id)!!.plan.id
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
        val plansBefore = flightPlanDao.countPlans()
        val exercisesBefore = flightPlanDao.countPlannedExercises()
        val setsBefore = flightPlanDao.countPlannedSets()

        // A repair round or a retry answers the same sortie again: it is stored, and it replaces.
        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, valid))

        val stored = flightPlanDao.getPlan(sortie.id)!!
        assertNotEquals(firstPlanId, stored.plan.id)
        // The counts are compared with the first store rather than with 1, because the run sortie at index 0
        // keeps the simple plan it was written before its scrub.
        assertEquals(plansBefore, flightPlanDao.countPlans())
        assertEquals(exercisesBefore, flightPlanDao.countPlannedExercises())
        assertEquals(setsBefore, flightPlanDao.countPlannedSets())
        assertEquals(4, stored.exercises.size)
        assertEquals(9, stored.exercises.sumOf { it.sets.size })
        assertEquals("Push + core", stored.plan.title)
        assertEquals(SortieState.PLANNED, sortieDao.get(sortie.id)!!.state)
    }

    // ── refusals: a stale result changes nothing ───────────────────────────────

    /** Everything a store operation could write, read back in one go. */
    private data class WeekState(
        val mission: Mission,
        val sorties: List<Sortie>,
        val plans: List<FlightPlan>,
        val exercises: List<PlannedExercise>,
        val sets: List<PlannedSet>,
        val segments: List<RunSegment>,
    )

    private suspend fun weekState(missionId: Long): WeekState = WeekState(
        mission = missionDao.get(missionId)!!,
        sorties = sortieDao.getForMission(missionId),
        plans = flightPlanDao.getAllPlans(),
        exercises = flightPlanDao.getAllPlannedExercises(),
        sets = flightPlanDao.getAllPlannedSets(),
        segments = flightPlanDao.getAllRunSegments(),
    )

    /** The refusal, and the reason it has to name. */
    private fun refusalReason(outcome: StoreOutcome, names: String): String {
        assertTrue("$names: the outcome was $outcome", outcome is StoreOutcome.Refused)
        val reason = (outcome as StoreOutcome.Refused).reason
        assertTrue("the refusal should name $names, got \"$reason\"", reason.contains(names))
        return reason
    }

    private suspend fun assertNothingChanged(missionId: Long, before: WeekState) {
        val after = weekState(missionId)
        assertEquals("the mission row is untouched", before.mission, after.mission)
        assertEquals("no sortie moved", before.sorties, after.sorties)
        assertEquals("no plan was written or deleted", before.plans, after.plans)
        assertEquals("no planned exercise was written or deleted", before.exercises, after.exercises)
        assertEquals("no set was written or deleted", before.sets, after.sets)
        assertEquals("no segment was written or deleted", before.segments, after.segments)
    }

    @Test
    fun aPlanForASortieThatIsNoLongerCurrentChangesNothing() = runBlocking {
        val current = currentLiftSortie()
        val missionId = current.missionId
        // The week's other LIFT sortie: the right type for this plan, still PENDING, but index 1 is the
        // current one, so a result for index 3 has arrived after the week moved on.
        val stale = sortieDao.getForMission(missionId).first { it.index == 3 }
        assertEquals(SortieType.LIFT, stale.type)
        assertEquals(SortieState.PENDING, stale.state)
        assertNotEquals(current.id, stale.id)
        assertNull(flightPlanDao.getPlan(stale.id))

        val valid = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)
        // The current sortie holds the plan this same reply was asked for, so "untouched" has something to keep.
        assertEquals(StoreOutcome.Stored, manager.storePlan(current.id, valid))
        val currentPlanId = flightPlanDao.getPlan(current.id)!!.plan.id
        val before = weekState(missionId)

        refusalReason(manager.storePlan(stale.id, valid), "not the current sortie")

        assertNothingChanged(missionId, before)
        assertNull("the stale sortie still holds no plan", flightPlanDao.getPlan(stale.id))
        assertEquals(SortieState.PENDING, sortieDao.get(stale.id)!!.state)
        assertEquals(SortieState.PLANNED, sortieDao.get(current.id)!!.state)
        assertEquals(
            "the current sortie keeps the plan it already had",
            currentPlanId, flightPlanDao.getPlan(current.id)!!.plan.id
        )
    }

    @Test
    fun aPlanForASortieThatIsInFlightLandedOrScrubbedChangesNothing() = runBlocking {
        val sortie = currentLiftSortie()
        val missionId = sortie.missionId
        val scrubbed = sortieDao.getForMission(missionId).first { it.index == 0 }
        assertEquals(SortieType.RUN, scrubbed.type)
        assertEquals(SortieState.SCRUBBED, scrubbed.state)

        val lift = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)
        val run = CoachFixtures.validReply(CoachFixtures.RUN_PLAN, CoachFixtures.RUN_CONTEXT)

        // IN_FLIGHT: the plan arrives for the current sortie after the owner started it.
        assertEquals(StoreOutcome.Stored, manager.storePlan(sortie.id, lift))
        manager.launch(sortie.id)
        assertEquals(SortieState.IN_FLIGHT, sortieDao.get(sortie.id)!!.state)
        var before = weekState(missionId)
        refusalReason(manager.storePlan(sortie.id, lift), "IN_FLIGHT")
        assertNothingChanged(missionId, before)

        // LANDED: the same sortie, after the owner finished it. Its plan and its sets are still there.
        manager.land(sortie.id)
        assertEquals(SortieState.LANDED, sortieDao.get(sortie.id)!!.state)
        val landedPlanId = flightPlanDao.getPlan(sortie.id)!!.plan.id
        before = weekState(missionId)
        refusalReason(manager.storePlan(sortie.id, lift), "LANDED")
        assertNothingChanged(missionId, before)
        assertEquals(
            "the plan the owner landed is still the one they landed",
            landedPlanId, flightPlanDao.getPlan(sortie.id)!!.plan.id
        )

        // SCRUBBED: a run sortie the owner scrubbed, holding the simple plan it was written before the scrub.
        val scrubbedPlanId = flightPlanDao.getPlan(scrubbed.id)!!.plan.id
        assertEquals(FlightPlanSource.SIMPLE_RUN, flightPlanDao.getPlan(scrubbed.id)!!.plan.source)
        before = weekState(missionId)
        refusalReason(manager.storePlan(scrubbed.id, run), "SCRUBBED")
        assertNothingChanged(missionId, before)
        assertEquals(
            "the scrubbed sortie keeps the plan it already had",
            scrubbedPlanId, flightPlanDao.getPlan(scrubbed.id)!!.plan.id
        )
    }
}
