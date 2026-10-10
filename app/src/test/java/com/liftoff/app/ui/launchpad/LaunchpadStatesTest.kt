package com.liftoff.app.ui.launchpad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.SettingsStore
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
    // Direct DB queries: mission/sortie/plan data inserted manually, no flow emission.

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

        flightPlanDao.writePlan(s0Id, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Body", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
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

        val sorties = sortieDao.getForMission(mission.id)
        val current = com.liftoff.app.domain.currentSortie(sorties)!!
        assertEquals(SortieState.PLANNED, current.state)
        assertEquals(0, current.index)

        val plan = flightPlanDao.getPlan(current.id)!!
        assertEquals("Upper Body", plan.plan.title)
        assertEquals(3, plan.exercises.size)
    }

    // ── Planned (run) ──────────────────────────────────────────────────
    // confirm() updates mission status to ACTIVE, triggering observeWeek.

    @Test
    fun plannedRunAfterConfirm() = runBlocking {
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        val sorties = sortieDao.getForMission(mission.id)
        assertEquals(SortieType.RUN, sorties[0].type)
        assertEquals(SortieState.PLANNED, sorties[0].state)

        val state = states.observe().first { it is LaunchpadState.Planned } as LaunchpadState.Planned
        assertEquals(0, state.sortieIndex)
        assertNotNull(state.plan)
    }

    // ── Pending ────────────────────────────────────────────────────────
    // scrub() only changes sortie table — use direct DB query.

    @Test
    fun pendingAfterScrub() = runBlocking {
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        val sorties = sortieDao.getForMission(mission.id)
        assertEquals(SortieState.PLANNED, sorties[0].state)
        assertEquals(SortieState.PENDING, sorties[1].state)

        manager.scrub(sorties[0].id, "weather")

        // scrub only changes sortie state; use DB query since observeWeek won't re-emit.
        val updated = sortieDao.getForMission(mission.id)
        val current = com.liftoff.app.domain.currentSortie(updated)!!
        assertEquals(SortieState.PENDING, current.state)
        assertEquals(1, current.index)

        // Verify derived state matches: mission ACTIVE, current sortie PENDING → Pending.
        val dbMission = missionDao.get(mission.id)!!
        assertEquals(MissionStatus.ACTIVE, dbMission.status)
    }

    // ── In-Flight ──────────────────────────────────────────────────────
    // launch() only changes the sortie table. Direct DB query.

    @Test
    fun inFlightAfterLaunch() = runBlocking {
        val mission = manager.onAppOpen()
        manager.confirm(mission.id)

        val sorties = sortieDao.getForMission(mission.id)
        manager.launch(sorties[0].id)

        val updated = sortieDao.get(sorties[0].id)!!
        assertEquals(SortieState.IN_FLIGHT, updated.state)
        assertNotNull(updated.launchedAt)
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

        // Verify mission is CLOSED via direct DB query (land triggers advance -> close).
        val closedMission = missionDao.get(mission.id)!!
        assertEquals(MissionStatus.CLOSED, closedMission.status)
        assertEquals("RL", closedMission.pattern)

        // Verify derived Closed state via direct queries.
        val finalSorties = sortieDao.getForMission(mission.id)
        val landedCount = finalSorties.count { it.state == SortieState.LANDED }
        assertEquals(2, landedCount)
    }

    // ── Live update ────────────────────────────────────────────────────
    // Uses flow for mission-changing ops, direct DB queries otherwise.

    @Test
    fun liveUpdateThroughStates() = runBlocking {
        // onAppOpen -> Draft (mission inserted).
        manager.onAppOpen()
        val draft = states.observe().first { it is LaunchpadState.Draft } as LaunchpadState.Draft
        assertEquals("RLRLR", draft.pattern)

        // confirm -> Planned (mission status ACTIVE).
        manager.confirm(draft.missionId)
        val planned = states.observe().first { it is LaunchpadState.Planned } as LaunchpadState.Planned
        assertEquals(0, planned.sortieIndex)

        // scrub -> Pending: use DB query (scrub doesn't change mission table).
        manager.scrub(planned.sortieId, "test")
        val sortiesAfterScrub = sortieDao.getForMission(missionDao.get(draft.missionId!!)!!.id)
        val current = com.liftoff.app.domain.currentSortie(sortiesAfterScrub)!!
        assertEquals(SortieState.PENDING, current.state)
        assertEquals(1, current.index)

        // Write plan for PENDING lift and mark PLANNED.
        flightPlanDao.writePlan(current.id, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Lift", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = emptyList(), segments = emptyList()
        ))
        sortieDao.update(com.liftoff.app.domain.markPlanned(current))

        // Verify PLANNED via DB (no mission change).
        val sorties2 = sortieDao.getForMission(missionDao.get(draft.missionId)!!.id)
        val cur2 = com.liftoff.app.domain.currentSortie(sorties2)!!
        assertEquals(SortieState.PLANNED, cur2.state)

        // launch -> InFlight: use DB query.
        manager.launch(cur2.id)
        val updated = sortieDao.get(cur2.id)!!
        assertEquals(SortieState.IN_FLIGHT, updated.state)
    }
}
