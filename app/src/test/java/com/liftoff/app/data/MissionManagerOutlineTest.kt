package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachFixtures
import com.liftoff.app.coach.CoachOutline
import com.liftoff.app.domain.allSortiesDone
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.domain.sortieTypesOf
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.UUID

/**
 * `MissionManager.storeOutline` fed from `outline-valid.txt` against the fixture's own context — pattern
 * `RLRLR`, 60-minute sorties — with run generation left off, which is the default.
 *
 * The outline is the thing that turns a DRAFT into a week (DESIGN.md §4.2), so these tests read the mission and
 * its five sorties back: the pattern's order, each sortie's focus and rationale, the notes on the mission, and
 * which sortie `advance` made current and prepared.
 *
 * Clock and zone are fixed the way [MissionManagerTest] fixes them, so the same week is current here as on the
 * CI runner in UTC. A refusal test reads the mission, the week's sorties and every plan row back and requires
 * them unchanged, so a guard that fired after a write would leave a hole here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerOutlineTest {

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

        // A file of its own, so a setting changed here cannot reach another test class.
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "outline-${UUID.randomUUID()}.preferences_pb")
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

    /** The fixture's outline, validated against the pattern the current week's draft holds. */
    private fun outlineReply() =
        CoachFixtures.validReply(CoachFixtures.OUTLINE, CoachFixtures.OUTLINE_CONTEXT)

    /** The week the fixture describes, stored through the operation. */
    private suspend fun storeOutline(missionId: Long) =
        manager.storeOutline(missionId, outlineReply())

    // ── the outline activates the Mission ──────────────────────────────────────

    @Test
    fun theStoredOutlineActivatesTheMissionWithItsSortiesFocusesAndNotes() = runBlocking {
        val draft = manager.onAppOpen()
        assertEquals(MissionStatus.DRAFT, draft.status)
        assertEquals("RLRLR", draft.pattern)
        assertNull("a draft holds no outline notes", draft.outlineNotes)

        val valid = outlineReply()
        val outline = valid.plan as CoachOutline

        assertEquals(StoreOutcome.Stored, manager.storeOutline(draft.id, valid))

        val mission = missionDao.get(draft.id)!!
        assertEquals(MissionStatus.ACTIVE, mission.status)
        assertEquals(outline.notes, mission.outlineNotes)
        assertEquals("Thursday's lift follows the tempo day, so hinge volume stays low.", mission.outlineNotes)

        val sorties = sortieDao.getForMission(draft.id)
        assertEquals("one sortie per pattern letter", 5, sorties.size)
        assertEquals(listOf(0, 1, 2, 3, 4), sorties.map { it.index })
        // The pattern's own order, which is the order the confirm-without-outline path already uses (§4.3).
        assertEquals(
            listOf(SortieType.RUN, SortieType.LIFT, SortieType.RUN, SortieType.LIFT, SortieType.RUN),
            sorties.map { it.type }
        )
        assertEquals(sortieTypesOf(mission.pattern), sorties.map { it.type })

        // Each sortie holds the outline entry at the same index, not the two hardcoded defaults.
        assertEquals(
            listOf("Easy 5k", "Push + core", "Tempo intervals", "Legs + pull", "Long run"),
            sorties.map { it.focus }
        )
        assertEquals(outline.sorties.map { it.focus }, sorties.map { it.focus })
        assertEquals(outline.sorties.map { it.rationale }, sorties.map { it.focusRationale })

        // Only the lift at index 1 has a rationale, and it is the fixture's own sentence.
        assertEquals(
            "Upper body only: it sits between the easy run and Thursday's tempo day.",
            sorties[1].focusRationale
        )
        assertTrue(
            "the outline gives a rationale for the lift alone",
            sorties.filter { it.focusRationale != null }.map { it.index } == listOf(1)
        )

        // Nothing else has happened to a sortie yet.
        assertTrue(sorties.all { it.launchedAt == null && it.landedAt == null && it.scrubReason == null })
        assertTrue(sorties.all { it.notes == null && it.runDistance == null && it.runMinutes == null })
    }

    @Test
    fun theStoredOutlineMakesSortieOneCurrentAndLeavesTheMissionOpen() = runBlocking {
        val draft = manager.onAppOpen()
        assertEquals(StoreOutcome.Stored, storeOutline(draft.id))

        val mission = missionDao.get(draft.id)!!
        assertEquals("advance closes a Mission only when every sortie is done", MissionStatus.ACTIVE, mission.status)

        val sorties = sortieDao.getForMission(draft.id)
        assertEquals(5, sorties.size)
        assertFalse(allSortiesDone(sorties))

        val current = currentSortie(sorties)
        assertTrue(current != null)
        assertEquals("sortie 1 is the first of the pattern's own order", 0, current!!.index)
        assertEquals(sorties[0].id, current.id)
        assertEquals(SortieType.RUN, current.type)

        // Run generation off: the first sortie is the only one that has moved on from PENDING, because advance
        // prepares the current sortie and waits for a generation on the rest.
        assertEquals(SortieState.PLANNED, sorties[0].state)
        assertEquals(
            listOf(SortieState.PLANNED) + List(4) { SortieState.PENDING },
            sorties.map { it.state }
        )
        for (sortie in sorties.drop(1)) {
            assertNull("sortie ${sortie.index} is not current, so it holds no plan", flightPlanDao.getPlan(sortie.id))
        }
    }

    @Test
    fun aRunSortieStillGetsTheSimpleRunPlanWhenRunGenerationIsOff() = runBlocking {
        assertFalse("run generation is off by default", settingsStore.settings.first().generateRunPlans)

        val draft = manager.onAppOpen()
        val outline = outlineReply().plan as CoachOutline
        assertEquals(StoreOutcome.Stored, storeOutline(draft.id))

        val first = sortieDao.getForMission(draft.id).first { it.index == 0 }
        assertEquals(SortieType.RUN, first.type)
        assertEquals(SortieState.PLANNED, first.state)

        val stored = flightPlanDao.getPlan(first.id)!!
        assertEquals(FlightPlanSource.SIMPLE_RUN, stored.plan.source)
        assertEquals("Run", stored.plan.title)
        // The outline's own run focus, not the confirm-without-outline default "easy".
        assertEquals("Easy 5k", stored.plan.notes)
        assertEquals(outline.sorties[0].focus, stored.plan.notes)

        // It is the simple plan the advance step already writes, not a coach plan: nothing was generated.
        assertEquals("{}", stored.plan.rawJson)
        assertTrue(stored.exercises.isEmpty())
        assertTrue(stored.segments.isEmpty())
        assertTrue(
            "no GENERATED plan is stored anywhere in the week",
            flightPlanDao.getAllPlans().none { it.source == FlightPlanSource.GENERATED }
        )
    }

    // ── refusals: an outline that arrives too late changes nothing ────────────────

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
        assertEquals("no sortie was added or moved", before.sorties, after.sorties)
        assertEquals("no plan was written or deleted", before.plans, after.plans)
        assertEquals("no planned exercise was written or deleted", before.exercises, after.exercises)
        assertEquals("no set was written or deleted", before.sets, after.sets)
        assertEquals("no segment was written or deleted", before.segments, after.segments)
    }

    @Test
    fun anOutlineStoredForAMissionThatIsNoLongerADraftChangesNothing() = runBlocking {
        val draft = manager.onAppOpen()

        // ACTIVE: the week was confirmed without an outline, so it already has its five sorties and a plan.
        manager.continueWithoutOutline(draft.id)
        val active = weekState(draft.id)
        assertEquals(MissionStatus.ACTIVE, active.mission.status)
        assertEquals(5, active.sorties.size)
        assertTrue(active.plans.isNotEmpty())
        assertNull(active.mission.outlineNotes)

        refusalReason(manager.storeOutline(draft.id, outlineReply()), "not a DRAFT")
        assertNothingChanged(draft.id, active)
        assertNull("no outline notes were written", missionDao.get(draft.id)!!.outlineNotes)
        assertEquals(
            "the week keeps the focuses it was confirmed with",
            listOf("easy", "full body", "easy", "full body", "easy"),
            sortieDao.getForMission(draft.id).map { it.focus }
        )

        // CLOSED: every sortie of this same Mission has been scrubbed, so advance closed it.
        for (sortie in sortieDao.getForMission(draft.id)) manager.scrub(sortie.id, "test: closing the week")
        val closed = weekState(draft.id)
        assertEquals(MissionStatus.CLOSED, closed.mission.status)
        assertTrue(closed.sorties.all { it.state == SortieState.SCRUBBED })

        refusalReason(manager.storeOutline(draft.id, outlineReply()), "not a DRAFT")
        assertNothingChanged(draft.id, closed)
        assertEquals(MissionStatus.CLOSED, missionDao.get(draft.id)!!.status)
        assertNull(missionDao.get(draft.id)!!.outlineNotes)
    }

    @Test
    fun anOutlineForAWeekThatHasEndedIsRefusedAndChangesNothing() = runBlocking {
        // Last week's draft, never confirmed: the app has not been opened since, so nothing rolled it over yet.
        val missionId = missionDao.insert(
            Mission(
                weekStart = LocalDate.of(2026, 1, 5),
                pattern = "RLRLR",
                status = MissionStatus.DRAFT,
                outlineNotes = null
            )
        )
        val before = weekState(missionId)
        assertTrue(before.sorties.isEmpty())

        refusalReason(manager.storeOutline(missionId, outlineReply()), "has ended")
        assertNothingChanged(missionId, before)
        assertEquals("the draft is still a draft", MissionStatus.DRAFT, missionDao.get(missionId)!!.status)
        assertTrue("no sortie was activated for a week that has passed", sortieDao.getAll().isEmpty())
    }

    @Test
    fun anOutlineForAPatternEditedAfterItWasValidatedIsRefusedAndChangesNothing() = runBlocking {
        val draft = manager.onAppOpen()
        val valid = outlineReply()

        // The owner edits the draft's pattern after the outline was validated against RLRLR. A draft's pattern
        // is editable until it is confirmed (§4.1).
        manager.setPattern(draft.id, "R")
        var before = weekState(draft.id)
        refusalReason(manager.storeOutline(draft.id, valid), "no longer matches")
        assertNothingChanged(draft.id, before)

        // Same length as the outline, but the letter at index 3 is a run where the outline planned a lift.
        manager.setPattern(draft.id, "RLRRR")
        before = weekState(draft.id)
        refusalReason(manager.storeOutline(draft.id, valid), "no longer matches")
        assertNothingChanged(draft.id, before)

        assertEquals(MissionStatus.DRAFT, missionDao.get(draft.id)!!.status)
        assertEquals("RLRRR", missionDao.get(draft.id)!!.pattern)
        assertNull("no notes were written for an outline that was refused", missionDao.get(draft.id)!!.outlineNotes)
        assertTrue("no sortie was activated", sortieDao.getForMission(draft.id).isEmpty())
    }
}
