package com.liftoff.app.ui.inflight

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.settings.WeightUnit
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * The run state read through the In-Flight view model against in-memory Room, on a Mission the app
 * itself created: confirm an RLRLR week, so sortie 0 is a PLANNED run carrying the simple 'Run' plan
 * the manager wrote. The plan is then replaced through the DAO, which deletes the old one first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightRunTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private val job = Job()
    // Anything that escapes a launched coroutine is recorded here, so a test can assert that nothing
    // leaked out of the view model rather than relying on the default handler cancelling the job.
    private val escaped = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, e -> escaped += e }
    )

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
            clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC),
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

    private suspend fun sortie0(missionId: Long) = sortieDao.getForMission(missionId)[0]

    private fun viewModel(sortieId: Long) =
        InFlightViewModel(db, settingsStore, sortieId, scope, manager)

    /** The first state the view model publishes that is a run state matching [pred]. */
    private suspend fun awaitRun(vm: InFlightViewModel, pred: (InFlightState.Run) -> Boolean = { true }) =
        withTimeout(5_000) { vm.state.first { it is InFlightState.Run && pred(it) } } as InFlightState.Run

    /** A generated run plan: kind, target distance/pace and two segments. No exercises. */
    private fun tempoPlanDraft() = FlightPlanDraft(
        source = FlightPlanSource.GENERATED, title = "Tempo Tuesday", estimatedMinutes = null,
        warmup = null, notes = "Hold it honest", runKind = "tempo", targetDistance = 5.0,
        targetPace = "9:30/mi", rawJson = "{}", exercises = emptyList(),
        segments = listOf(
            RunSegmentDraft(description = "Warm-up", distance = 1.0, minutes = 10.0),
            RunSegmentDraft(description = "Tempo", distance = 3.1, minutes = 25.0)
        )
    )

    // ── A launched run whose plan was replaced with a generated one ──
    // The state is the run state, carrying the sortie number, the plan's title, its notes line, the
    // run kind, the target distance and pace, and one row per segment. Read in the other distance unit
    // the same plan gives the same numbers under that unit's label.

    @Test
    fun runStateFromSeededPlanWithSegments() = runBlocking {
        val mission = seedMission()
        val sortie = sortie0(mission.id)
        manager.launch(sortie.id)
        flightPlanDao.writePlan(sortie.id, tempoPlanDraft())

        // The unit is pinned rather than assumed: the view model publishes whatever the setting holds.
        assertEquals(DistanceUnit.MI, withTimeout(5_000) { settingsStore.settings.first() }.distanceUnit)

        val run = awaitRun(viewModel(sortie.id)) { it.title == "Tempo Tuesday" }
        assertEquals("IN FLIGHT \u00b7 SORTIE 1", run.eyebrow)
        assertEquals(1, run.sortieNumber)
        assertEquals("Tempo Tuesday", run.title)
        assertEquals("Hold it honest", run.notesLine)
        assertEquals("Tempo", run.kind)
        assertEquals("5 mi", run.targetDistance)
        assertEquals("9:30/mi", run.targetPace)
        assertEquals(
            listOf(
                RunSegmentRow("Warm-up", "1 mi \u00b7 10 min"),
                RunSegmentRow("Tempo", "3.1 mi \u00b7 25 min")
            ),
            run.segments
        )

        val kmPlan = flightPlanDao.getPlan(sortie.id)!!
        val km = deriveInFlight(kmPlan, sortieDao.get(sortie.id)!!, WeightUnit.LB, DistanceUnit.KM)
            as InFlightState.Run
        assertEquals("5 km", km.targetDistance)
        assertEquals("1 km \u00b7 10 min", km.segments[0].detail)
        assertEquals("3.1 km \u00b7 25 min", km.segments[1].detail)

        assertTrue("something escaped the view model: $escaped", escaped.isEmpty())
    }

    // ── The simple 'Run' plan the manager writes ─────────────────────
    // It carries only what that plan actually has: no kind, no target distance, no target pace, no
    // segment rows — so the screen has nothing to print as an empty row. With the plan's notes cleared
    // the notes line still reads the sortie's focus.

    @Test
    fun runStateFromSimpleRunPlan() = runBlocking {
        val mission = seedMission()
        val sortie = sortie0(mission.id)
        assertEquals(SortieType.RUN, sortie.type)
        assertEquals("easy", sortie.focus)
        manager.launch(sortie.id)

        val run = awaitRun(viewModel(sortie.id))
        assertEquals("Run", run.title)
        assertEquals("easy", run.notesLine)
        assertNull(run.kind)
        assertNull(run.targetDistance)
        assertNull(run.targetPace)
        assertTrue("no empty rows: ${run.segments}", run.segments.isEmpty())

        val plan = flightPlanDao.getPlan(sortie.id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.plan.source)
        val noNotes = plan.copy(plan = plan.plan.copy(notes = null))
        val derived = deriveInFlight(noNotes, sortieDao.get(sortie.id)!!, WeightUnit.LB, DistanceUnit.MI)
            as InFlightState.Run
        assertEquals("Run", derived.title)
        assertEquals("easy", derived.notesLine)

        assertTrue("something escaped the view model: $escaped", escaped.isEmpty())
    }

    // ── A run with no Flight Plan at all ─────────────────────────────
    // It keeps today's NoChecklist state: the run branch needs a plan to read.

    @Test
    fun runWithNoPlanStillShowsNoChecklist() = runBlocking {
        val missionId = missionDao.insert(Mission(
            weekStart = LocalDate.of(2026, 2, 2), pattern = "R",
            status = MissionStatus.DRAFT, outlineNotes = null
        ))
        val sortieId = sortieDao.insert(Sortie(
            missionId = missionId, index = 0, type = SortieType.RUN, focus = "easy",
            focusRationale = null, state = SortieState.IN_FLIGHT,
            launchedAt = System.currentTimeMillis(), landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null
        ))
        assertNull(flightPlanDao.getPlan(sortieId))

        // Waiting for any loaded state means a wrong Run fails on the assert, not on a timeout.
        val state = withTimeout(5_000) { viewModel(sortieId).state.first { it !is InFlightState.Loading } }
        assertTrue("was $state", state is InFlightState.NoChecklist)
        assertEquals(1, (state as InFlightState.NoChecklist).sortieNumber)
        assertFalse(needsLandConfirmation(state))
    }
}
