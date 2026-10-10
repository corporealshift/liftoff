package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.ui.sortie.formatExerciseLoad
import com.liftoff.app.ui.sortie.formatPlanHead
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchpadStatesTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private lateinit var states: LaunchpadStates

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
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
        states = LaunchpadStates(db, manager)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.cacheDir, "settings.preferences_pb").delete()
    }

    // ── Loading ────────────────────────────────────────────────────────

    @Test
    fun loadingBeforeOnAppOpen() = runBlocking {
        val state = states.observe().first { it is LaunchpadState.Loading }
        assertTrue(state is LaunchpadState.Loading)
    }

    // ── Draft ──────────────────────────────────────────────────────────
    // onAppOpen inserts the mission, triggering observeWeek.

    @Test
    fun draftAfterOnAppOpen() = runBlocking {
        manager.onAppOpen()
        val state = states.observe().first { it is LaunchpadState.Draft } as LaunchpadState.Draft
        assertEquals("RLRLR", state.pattern)
        assertNotNull(state.missionId)
    }

    // ── Planned (lift) ─────────────────────────────────────────────────
    // Drive through observe(): mission/sortie plan data inserted, then read the derived
    // LaunchpadState.Planned and assert on eyebrow, title, rows, load strings, head, and coach note.

    @Test
    fun plannedLiftShowsPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RL"))

        val s0Id = sortieDao.insert(Sortie(
            missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench Press",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        sortieDao.insert(Sortie(
            missionId = mission.id, index = 1, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))

        val coachNote = "Focus on strict form; avoid arching the back."
        flightPlanDao.writePlan(s0Id, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Body", estimatedMinutes = 45,
            warmup = null, notes = coachNote, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = 135.0),
                        com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = 135.0),
                        com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = 135.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Push-up", equipmentIds = emptyList(), restSeconds = 60, notes = null,
                    sets = listOf(
                        com.liftoff.app.data.SetDraft(reps = 15, seconds = null, weight = null),
                        com.liftoff.app.data.SetDraft(reps = 12, seconds = null, weight = null)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Plank", equipmentIds = emptyList(), restSeconds = null, notes = null,
                    sets = listOf(
                        com.liftoff.app.data.SetDraft(reps = 1, seconds = 45, weight = null)
                    )
                )
            ),
            segments = emptyList()
        ))

        // Read through the derivation flow.
        val state = states.observe().first { it is LaunchpadState.Planned } as LaunchpadState.Planned

        // Eyebrow: WEEK OF JAN 12 · SORTIE 1 OF 2 · LIFT
        assertEquals("WEEK OF JAN 12 · SORTIE 1 OF 2 · LIFT", state.eyebrow)
        // Title from plan.
        assertEquals("Upper Body", state.title)
        // Sortie index and type.
        assertEquals(0, state.sortieIndex)
        assertEquals(SortieType.LIFT, state.sortieType)
        assertNotNull(state.chips)
        assertEquals(2, state.chips.size)

        // Plan fields.
        val plan = state.plan
        assertEquals("Upper Body", plan.plan.title)
        assertEquals(45, plan.plan.estimatedMinutes!!)
        assertEquals(3, plan.exercises.size)

        // Load strings for each exercise.
        assertEquals("3×8 · 135", formatExerciseLoad(plan.exercises[0]))
        assertEquals("2×15/12 · BW", formatExerciseLoad(plan.exercises[1]))
        assertEquals("1×45 s · BW", formatExerciseLoad(plan.exercises[2]))

        // Flight Plan head.
        assertEquals("≈45 min · 6 sets", formatPlanHead(plan))
    }

    // ── Planned (run) ──────────────────────────────────────────────────
    // confirm() updates mission status to ACTIVE and inserts sorties, triggering observeWeek.

    @Test
    fun plannedRunAfterConfirm() = runBlocking {
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        val state = states.observe().first { it is LaunchpadState.Planned } as LaunchpadState.Planned
        assertEquals(0, state.sortieIndex)
        assertNotNull(state.plan)
    }

    // ── Pending ────────────────────────────────────────────────────────
    // scrub() changes the sortie table; MissionDao_Impl observes sorties via @Relation,
    // so observeWeek re-emits. advance() auto-generates a SIMPLE_RUN plan for RUN sorties
    // (marking them PLANNED), but for LIFT it returns AwaitGeneration — leaving PENDING.
    // Use a 2-sortie pattern R-L where index 1 is LIFT to capture Pending.

    @Test
    fun pendingAfterScrub() = runBlocking {
        settingsStore.setDefaultPattern("RL")
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        // Wait for Planned emission (index 0, RUN).
        states.observe().first { it is LaunchpadState.Planned }

        val sortiesBefore = sortieDao.getForMission(mission.id)
        assertEquals(SortieType.RUN, sortiesBefore[0].type)
        assertEquals(SortieType.LIFT, sortiesBefore[1].type)

        manager.scrub(sortiesBefore[0].id, "weather")

        // advance() returns AwaitGeneration for LIFT index 1 — it stays PENDING.
        val state = states.observe().first { it is LaunchpadState.Pending } as LaunchpadState.Pending
        assertEquals(1, state.sortieIndex)
    }

    // ── In-Flight ──────────────────────────────────────────────────────
    // launch() changes the sortie table; observeWeek re-emits via the @Relation on sorties.

    @Test
    fun inFlightAfterLaunch() = runBlocking {
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        // Wait for Planned emission first.
        states.observe().first { it is LaunchpadState.Planned }

        val sortiesBefore = sortieDao.getForMission(mission.id)
        manager.launch(sortiesBefore[0].id)

        // Read the derived InFlight state through observe().
        val state = states.observe().first { it is LaunchpadState.InFlight } as LaunchpadState.InFlight
        assertEquals(0, state.sortieIndex)
        assertEquals(SortieType.RUN, state.sortieType)
    }

    // ── Closed ─────────────────────────────────────────────────────────
    // Closing the mission updates its status, triggering observeWeek.

    @Test
    fun closedAfterAllSortiesLanded() = runBlocking {
        // Default pattern is "RLRLR" (5 sorties). Set a 2-sortie pattern so two landings close it.
        settingsStore.setDefaultPattern("RL")

        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        var sorties = sortieDao.getForMission(mission.id)
        manager.launch(sorties[0].id)
        manager.land(sorties[0].id)

        // Sortie 1 (LIFT) is PENDING — write a plan and mark PLANNED before launching.
        sorties = sortieDao.getForMission(mission.id)
        flightPlanDao.writePlan(sorties[1].id, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Lift", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = emptyList(), segments = emptyList()
        ))
        sortieDao.update(com.liftoff.app.domain.markPlanned(sorties[1]))

        manager.launch(sorties[1].id)
        manager.land(sorties[1].id)

        // Read the derived Closed state through observe().
        val state = states.observe().first { it is LaunchpadState.Closed } as LaunchpadState.Closed
        assertEquals(2, state.landedCount)
        assertEquals(2, state.total)
    }

    // ── Live update ────────────────────────────────────────────────────
    // Collect one subscription across confirm, scrub and launch.

    @Test
    fun liveUpdateThroughStates() = runBlocking {
        manager.onAppOpen()

        val statesFlow = states.observe()

        // onAppOpen -> Draft (mission inserted).
        val draft = statesFlow.first { it is LaunchpadState.Draft } as LaunchpadState.Draft
        assertEquals("RLRLR", draft.pattern)

        // confirm -> Planned (mission status ACTIVE, sorties inserted).
        manager.confirm(draft.missionId)
        val planned = statesFlow.first { it is LaunchpadState.Planned } as LaunchpadState.Planned
        assertEquals(0, planned.sortieIndex)

        // scrub -> Pending: sortie table changes; observeWeek re-emits via @Relation on sorties.
        // For a LIFT current sortie (set up below), advance() returns AwaitGeneration so PENDING holds.
        manager.scrub(planned.sortieId, "test")

        // Write plan for the LIFT at index 1 and mark PLANNED to get Pending → Planned transition.
        val sortiesAfterScrub = sortieDao.getForMission(missionDao.get(draft.missionId!!)!!.id)
        val current = com.liftoff.app.domain.currentSortie(sortiesAfterScrub)!!
        assertEquals(1, current.index)

        flightPlanDao.writePlan(current.id, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Lift", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = emptyList(), segments = emptyList()
        ))
        sortieDao.update(com.liftoff.app.domain.markPlanned(current))

        // Observe PLANNED for index 1.
        val plannedAgain = statesFlow.first { it is LaunchpadState.Planned } as LaunchpadState.Planned
        assertEquals(1, plannedAgain.sortieIndex)

        // launch -> InFlight: sortie table changes; observeWeek re-emits via @Relation on sorties.
        manager.launch(plannedAgain.sortieId)
        val inFlight = statesFlow.first { it is LaunchpadState.InFlight } as LaunchpadState.InFlight
        assertEquals(1, inFlight.sortieIndex)
    }
}
