package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.WeightUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** Derivation tests on fresh `getPlan` reads against the fixture (3-exercise LIFT sortie). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightStatesTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var sortieDao: SortieDao
    private lateinit var missionDao: MissionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        flightPlanDao = db.flightPlanDao()
        sortieDao = db.sortieDao()
        missionDao = db.missionDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // ── Fixture setup ────────────────────────────────────────────────

    /** Build the fixture: a mission with a LIFT sortie (index=1, state=IN_FLIGHT, focus="Push") and a 3-exercise plan. */
    private suspend fun buildFixture(): Long {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))
        val sortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
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
        ))
        return sortieId
    }

    // ── Initial card states, counter, labels and current set ─────────
    // Eyebrow 'IN FLIGHT · SORTIE 2', plan title, counter 0/7, 7 open segments,
    // cards [ACTIVE, UPCOMING, UPCOMING] with 0/3, 0/2, 0/2, labels 35 LB / × 10 / BW / 45 s,
    // current set = first bench set, nothing added, no deviations.

    @Test
    fun initialState() = runBlocking {
        val sortieId = buildFixture()
        val plan = flightPlanDao.getPlan(sortieId)!!
        val sortie = sortieDao.get(sortieId)!!

        val state = deriveInFlight(plan, sortie, WeightUnit.LB) as InFlightState.Ready

        // Eyebrow and title
        assertEquals("IN FLIGHT \u00b7 SORTIE 2", state.eyebrow)
        assertEquals("Upper Push", state.title)

        // Counter
        assertEquals(0, state.doneCount)
        assertEquals(7, state.setCount)

        // All progress segments are OPEN
        assertEquals(7, state.progress.size)
        assertTrue(state.progress.all { it == SetStatus.OPEN })

        // Three cards: [ACTIVE, UPCOMING, UPCOMING] with done/set counts 0/3, 0/2, 0/2
        assertEquals(3, state.cards.size)

        val benchCard = state.cards[0]
        assertEquals("Bench Press", benchCard.name)
        assertEquals(CardStatus.ACTIVE, benchCard.status)
        assertEquals(0, benchCard.doneCount)
        assertEquals(3, benchCard.setCount)
        assertEquals(3, benchCard.sets.size)

        val pushupCard = state.cards[1]
        assertEquals("Push-up", pushupCard.name)
        assertEquals(CardStatus.UPCOMING, pushupCard.status)
        assertEquals(0, pushupCard.doneCount)
        assertEquals(2, pushupCard.setCount)

        val kbCard = state.cards[2]
        assertEquals("Kettlebell Hold", kbCard.name)
        assertEquals(CardStatus.UPCOMING, kbCard.status)
        assertEquals(0, kbCard.doneCount)
        assertEquals(2, kbCard.setCount)

        // Bench set 1 labels: "35 LB" and "× 10"
        val benchSet1 = benchCard.sets[0]
        assertEquals("35 LB", benchSet1.weightLabel)
        assertEquals("\u00d7 10", benchSet1.countLabel)

        // Push-up set 1 labels: "BW" and "× 12"
        val pushupSet1 = pushupCard.sets[0]
        assertEquals("BW", pushupSet1.weightLabel)
        assertEquals("\u00d7 12", pushupSet1.countLabel)

        // Kettlebell Hold set 2: seconds-based, weight null → "BW"
        val kbSet2 = kbCard.sets[1]
        assertEquals("BW", kbSet2.weightLabel)
        assertEquals("45 s", kbSet2.countLabel)

        // Current set is the first bench set
        assertNotNull(state.currentSetId)
        assertEquals(benchSet1.setId, state.currentSetId)

        // Nothing added, no deviations
        assertTrue(state.cards.all { card -> card.sets.all { !it.added && !it.weightDeviates && !it.countDeviates } })
    }

    // ── Weight label in KG ───────────────────────────────────────────
    // Weight label in the configured unit: KG gives '20 KG'.

    @Test
    fun kgUnitLabel() = runBlocking {
        val sortieId = buildFixture()
        val plan = flightPlanDao.getPlan(sortieId)!!
        val sortie = sortieDao.get(sortieId)!!

        val state = deriveInFlight(plan, sortie, WeightUnit.KG) as InFlightState.Ready

        // Bench set 1: 35 KG (integer weight)
        val benchSet1 = state.cards[0].sets[0]
        assertEquals("35 KG", benchSet1.weightLabel)

        // Kettlebell Hold set 1: 20 KG
        val kbSet1 = state.cards[2].sets[0]
        assertEquals("20 KG", kbSet1.weightLabel)

        // Push-up still BW (no weight)
        val pushupSet1 = state.cards[1].sets[0]
        assertEquals("BW", pushupSet1.weightLabel)
    }

    // ── Title fallbacks ──────────────────────────────────────────────
    // A blank plan title falls back to the focus, and then to 'Sortie n'.

    @Test
    fun titleFallbacks() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))

        // Case 1: blank plan title → falls back to focus
        val sortieId1 = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId1, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 10, seconds = null, weight = 35.0)
            ))),
            segments = emptyList()
        ))
        val plan1 = flightPlanDao.getPlan(sortieId1)!!
        val sortie1 = sortieDao.get(sortieId1)!!
        val state1 = deriveInFlight(plan1, sortie1, WeightUnit.LB) as InFlightState.Ready
        assertEquals("Push", state1.title)

        // Case 2: blank title + null focus → "Sortie n"
        val sortieId2 = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = null, focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(sortieId2, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "", estimatedMinutes = 40,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(ExerciseDraft(name = "Bench Press", equipmentIds = emptyList(), restSeconds = null, notes = null, sets = listOf(
                SetDraft(reps = 10, seconds = null, weight = 35.0)
            ))),
            segments = emptyList()
        ))
        val plan2 = flightPlanDao.getPlan(sortieId2)!!
        val sortie2 = sortieDao.get(sortieId2)!!
        val state2 = deriveInFlight(plan2, sortie2, WeightUnit.LB) as InFlightState.Ready
        assertEquals("Sortie 2", state2.title)
    }

    // ── No checklist for a sortie with no plan or no exercises ───────
    // Decision: 'A no checklist state still carries the eyebrow and title'.

    @Test
    fun noChecklist() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = java.time.LocalDate.of(2026, 1, 5),
            pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null
        ))

        // Case 1: LIFT sortie with no plan at all
        val liftNoPlanId = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.LIFT,
            focus = "Push", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        val liftNoPlanSortie = sortieDao.get(liftNoPlanId)!!
        val noPlanState = deriveInFlight(null, liftNoPlanSortie, WeightUnit.LB)
        assertTrue(noPlanState is InFlightState.NoChecklist)
        val noCheck = noPlanState as InFlightState.NoChecklist
        assertEquals("IN FLIGHT \u00b7 SORTIE 2", noCheck.eyebrow)
        assertEquals("Push", noCheck.title)

        // Case 2: RUN sortie with a plan that has zero exercises (segments only)
        val runSortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 1, type = SortieType.RUN,
            focus = "easy jog", focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        flightPlanDao.writePlan(runSortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Run Plan", estimatedMinutes = 30,
            warmup = null, notes = null, runKind = "steady", targetDistance = 5000.0,
            targetPace = "5:00", rawJson = "{}",
            exercises = emptyList(),
            segments = listOf(RunSegmentDraft(description = "Warmup", distance = 800.0, minutes = 4.0))
        ))
        val runSortie = sortieDao.get(runSortieId)!!
        val runPlan = flightPlanDao.getPlan(runSortieId)!!
        val runNoCheckState = deriveInFlight(runPlan, runSortie, WeightUnit.LB)
        assertTrue(runNoCheckState is InFlightState.NoChecklist)
        val runNoCheck = runNoCheckState as InFlightState.NoChecklist
        assertEquals("IN FLIGHT \u00b7 SORTIE 2", runNoCheck.eyebrow)
        // Plan title "Run Plan" is not blank, so it wins over focus
        assertEquals("Run Plan", runNoCheck.title)
    }
}
