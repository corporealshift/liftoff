package com.liftoff.app.ui.mission

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.domain.CoachStatus
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionStatesTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var generationDao: GenerationDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var settingsFile: File
    private lateinit var manager: MissionManager
    private lateinit var states: MissionStates

    /** Everything the one subscription of a live-follow case has emitted, in the order it arrived. */
    private val emissions = CopyOnWriteArrayList<MissionTabState>()

    /** The instant the fixture's clock reads; a generation row's createdAt is written later than this. */
    private val seededAt = Instant.parse("2026-01-12T00:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()
        generationDao = db.generationDao()

        // A file of its own per test, so a host or token written here reaches no other case or class.
        settingsFile = File(context.cacheDir, "mission-states-${UUID.randomUUID()}.preferences_pb")
        settingsStore = SettingsStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { settingsFile })

        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
        states = MissionStates(db, manager)
    }

    @After
    fun tearDown() {
        db.close()
        settingsFile.delete()
    }

    // ── The draft's coach status, as the DRAFT arm derives it ──────────

    /** Mission Control has a host and a token: the state every case below but one is read against. */
    private suspend fun coachIsSetUp() {
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("coach-token")
    }

    /** The tab's current week, read once the fixture has finished writing. */
    private suspend fun draftWeek(): MissionTabState.Week =
        states.observe().first { it is MissionTabState.Week } as MissionTabState.Week

    /** A generation row of the kind Confirm writes: an OUTLINE row for [missionId]. */
    private fun outlineRow(
        missionId: Long,
        status: GenerationStatus,
        createdAt: Long,
        finishedAt: Long? = null,
    ) = Generation(
        kind = GenerationKind.OUTLINE,
        missionId = missionId,
        sortieId = null,
        status = status,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )

    /** Rows are ordered by createdAt, so each write in a case gets its own later instant. */
    private fun at(step: Int): Long = seededAt + step * 1_000L

    // ── The one subscription a live-follow case reads from ─────────────
    // draftWeek() above calls observe() again, so it reads whatever Room holds at the moment of the read: enough
    // for a status assertion, but it proves nothing about a subscription staying live across a write. A follow
    // case opens observe() once, before its writes, and takes every emission out of [emissions].

    /** Opens observe() once, into [emissions], and returns the job that keeps it open. */
    private fun CoroutineScope.openSubscription(): Job =
        launch { states.observe().collect { emissions.add(it) } }

    private fun weeks(): List<MissionTabState.Week> = emissions.filterIsInstance<MissionTabState.Week>()

    /** A Week this same collection has already emitted; bounded so a status that never arrives fails. */
    private suspend fun awaitWeek(where: (MissionTabState.Week) -> Boolean): MissionTabState.Week =
        withTimeout(AWAIT_MS) {
            var found: MissionTabState.Week? = null
            while (found == null) {
                found = weeks().firstOrNull(where)
                if (found == null) delay(POLL_MS)
            }
            found!!
        }

    // ── Pattern chips from mixed sortie states ────────────────────────

    @Test
    fun patternChipsFromMixedSortieStates() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RLRLR"))

        sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "sick", notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 2, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 3, type = SortieType.LIFT, focus = "Squat",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 4, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals(5, week.chips.size)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Landed, week.chips[0].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Scrubbed, week.chips[1].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Current, week.chips[2].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Upcoming, week.chips[3].state)
        assertEquals(com.liftoff.app.ui.theme.ChipState.Upcoming, week.chips[4].state)
    }

    // ── Outline notes present ────────────────────────────────────────

    @Test
    fun outlineNotesPresent() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RL",
            outlineNotes = "Focus on form this week."))

        sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals("Focus on form this week.", week.outlineNotes)
    }

    // ── Outline notes absent ─────────────────────────────────────────

    @Test
    fun outlineNotesAbsent() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RL",
            outlineNotes = null))

        sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertTrue(week.outlineNotes.isNullOrEmpty())
    }

    // ── Sortie rows show index, type, focus, state ───────────────────

    @Test
    fun sortieRowsShowIndexTypeFocusState() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "RLR"))

        val id0 = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = "Easy",
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 2L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        val id1 = sortieDao.insert(Sortie(missionId = mission.id, index = 1, type = SortieType.LIFT, focus = "Full body",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mission.id, index = 2, type = SortieType.RUN, focus = "Tempo",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals(3, week.rows.size)

        assertEquals(id0, week.rows[0].id)
        assertEquals(0, week.rows[0].index)
        assertEquals(SortieType.RUN, week.rows[0].type)
        assertEquals("Easy", week.rows[0].focus)
        assertEquals("LANDED", week.rows[0].stateLabel)
        assertEquals("Run · Easy", week.rows[0].title)

        assertEquals(id1, week.rows[1].id)
        assertEquals(1, week.rows[1].index)
        assertEquals(SortieType.LIFT, week.rows[1].type)
        assertEquals("Full body", week.rows[1].focus)
        assertEquals("PLANNED", week.rows[1].stateLabel)
        assertEquals("Lift · Full body", week.rows[1].title)

        assertEquals(2, week.rows[2].index)
        assertEquals(SortieType.RUN, week.rows[2].type)
        assertEquals("Tempo", week.rows[2].focus)
        assertEquals("PENDING", week.rows[2].stateLabel)
        assertEquals("Run · Tempo", week.rows[2].title)
    }

    // ── Draft mission has no rows ────────────────────────────────────

    @Test
    fun draftMissionHasNoRows() = runBlocking {
        manager.onAppOpen()

        val week = states.observe().first { it is MissionTabState.Week } as MissionTabState.Week
        assertEquals(MissionStatus.DRAFT, week.status)
        assertTrue(week.rows.isEmpty())
    }

    // ── Planned sortie detail shows flight plan ──────────────────────

    @Test
    fun plannedSortieDetailShowsFlightPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Body", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(com.liftoff.app.data.SetDraft(reps = 8, seconds = null, weight = 135.0))
                )
            ),
            segments = emptyList()
        ))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.WithPlan)
        val withPlan = detail as SortieDetailState.WithPlan
        assertEquals("Upper Body", withPlan.title)
        assertEquals(SortieType.LIFT, withPlan.sortieType)
        assertEquals(1, withPlan.plan.exercises.size)
    }

    // ── Pending sortie without plan shows no plan ────────────────────

    @Test
    fun pendingSortieWithoutPlanShowsNoPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.NoPlan)
        val noPlan = detail as SortieDetailState.NoPlan
        assertEquals("Bench", noPlan.title)
    }

    // ── Pending sortie with plan shows flight plan ───────────────────

    @Test
    fun pendingSortieWithPlanShowsFlightPlan() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Bench Day", estimatedMinutes = 30,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = emptyList(), segments = emptyList()
        ))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.WithPlan)
        val withPlan = detail as SortieDetailState.WithPlan
        assertEquals("Bench Day", withPlan.title)
    }

    // ── Landed sortie detail shows record ────────────────────────────

    @Test
    fun landedSortieDetailShowsRecord() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1L, landedAt = 1705000000000L,
            scrubReason = null, notes = "Good pace", runDistance = 3.2, runMinutes = 18.5))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Landed)
        val landed = detail as SortieDetailState.Landed
        assertEquals(SortieType.RUN, landed.sortieType)
        landed.runDistance?.let { assertEquals(3.2, it, 0.01) }
        landed.runMinutes?.let { assertEquals(18.5, it, 0.01) }
        assertEquals("Good pace", landed.notes)
    }

    // ── Scrubbed sortie detail shows reason ──────────────────────────

    @Test
    fun scrubbedSortieDetailShowsReason() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "sick", notes = null, runDistance = null, runMinutes = null))

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Scrubbed)
        val scrubbed = detail as SortieDetailState.Scrubbed
        assertEquals("sick", scrubbed.reason)
        assertEquals(SortieType.LIFT, scrubbed.sortieType)
    }

    // ── Landed sortie with multi-set plan shows correct set counts ───

    @Test
    fun landedSortieWithMultiSetPlanShowsCorrectCount() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        // 3 exercises × 3 sets each = 9 total sets; mark 7 as DONE.
        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Full body",
            focusRationale = null, state = SortieState.LANDED, launchedAt = System.currentTimeMillis(),
            landedAt = 1705000000000L, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Full Body", estimatedMinutes = 55,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Squat", equipmentIds = emptyList(), restSeconds = 120, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Row", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 10, seconds = null, weight = 95.0),
                        SetDraft(reps = 10, seconds = null, weight = 95.0),
                        SetDraft(reps = 8, seconds = null, weight = 115.0)
                    )
                )
            ),
            segments = emptyList()
        ))

        // Mark 7 of the 9 sets as DONE.
        for (exercise in flightPlanDao.getPlan(sortieId)!!.exercises) {
            val sets = exercise.sets
            // First two exercises: all 3 sets DONE; third exercise: first set DONE, last 2 OPEN.
            if (exercise.plannedExercise.order < 2) {
                sets.forEach { s -> db.flightPlanDao().updateSetActuals(s.id, s.reps, s.seconds, s.weight, SetStatus.DONE) }
            } else {
                db.flightPlanDao().updateSetActuals(sets[0].id, sets[0].reps, sets[0].seconds, sets[0].weight, SetStatus.DONE)
            }
        }

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Landed)
        val landed = detail as SortieDetailState.Landed
        // planExerciseCount must be total sets (9), not exercise count (3).
        assertEquals(9, landed.planExerciseCount)
        assertEquals(7, landed.planLandedCount)
    }

    // ── Scrubbed sortie with multi-set plan shows correct set counts ─

    @Test
    fun scrubbedSortieWithMultiSetPlanShowsCorrectCount() = runBlocking {
        val mission = manager.onAppOpen()
        missionDao.update(mission.copy(status = MissionStatus.ACTIVE, pattern = "R"))

        // 2 exercises × 4 sets each = 8 total sets; mark 3 as DONE.
        val sortieId = sortieDao.insert(Sortie(missionId = mission.id, index = 0, type = SortieType.LIFT, focus = "Bench",
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "wrist pain", notes = null, runDistance = null, runMinutes = null))

        flightPlanDao.writePlan(sortieId, FlightPlanDraft(
            source = FlightPlanSource.GENERATED, title = "Upper Body", estimatedMinutes = 45,
            warmup = null, notes = null, runKind = null, targetDistance = null,
            targetPace = null, rawJson = "{}",
            exercises = listOf(
                com.liftoff.app.data.ExerciseDraft(
                    name = "Bench Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 8, seconds = null, weight = 135.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0),
                        SetDraft(reps = 6, seconds = null, weight = 155.0)
                    )
                ),
                com.liftoff.app.data.ExerciseDraft(
                    name = "Overhead Press", equipmentIds = emptyList(), restSeconds = 90, notes = null,
                    sets = listOf(
                        SetDraft(reps = 8, seconds = null, weight = 95.0),
                        SetDraft(reps = 8, seconds = null, weight = 95.0),
                        SetDraft(reps = 6, seconds = null, weight = 105.0),
                        SetDraft(reps = 6, seconds = null, weight = 105.0)
                    )
                )
            ),
            segments = emptyList()
        ))

        // Mark 3 of the 8 sets as DONE (first exercise: first 2 sets; second exercise: first set).
        val plan = flightPlanDao.getPlan(sortieId)!!
        db.flightPlanDao().updateSetActuals(plan.exercises[0].sets[0].id, null, null, null, SetStatus.DONE)
        db.flightPlanDao().updateSetActuals(plan.exercises[0].sets[1].id, null, null, null, SetStatus.DONE)
        db.flightPlanDao().updateSetActuals(plan.exercises[1].sets[0].id, null, null, null, SetStatus.DONE)

        val detail = states.observeSortie(sortieId).first { it !is SortieDetailState.Loading }
        assertTrue(detail is SortieDetailState.Scrubbed)
        val scrubbed = detail as SortieDetailState.Scrubbed
        // planExerciseCount must be total sets (8), not exercise count (2).
        assertEquals(8, scrubbed.planExerciseCount)
        assertEquals(3, scrubbed.planLandedCount)
    }

    // ── A draft with an open outline generation reports planning ───────
    // The brief's case: the draft was confirmed, so its QUEUED OUTLINE row is in Room and the coach is set up.
    // The week says the coach is planning, the status the Launchpad's draft says, not the one the tab's
    // "Not confirmed yet" line is drawn from.

    @Test
    fun theMissionTabReportsPlanningWhileItsDraftHasAnOpenOutlineGeneration() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()

        val row = generationDao.insert(outlineRow(mission.id, GenerationStatus.QUEUED, at(1)))

        val week = draftWeek()
        assertEquals(MissionStatus.DRAFT, week.status)
        assertEquals(mission.id, week.missionId)
        assertEquals(CoachStatus.Planning, week.coachStatus)
        assertFalse("the ask is already out, so Confirm is not offered", week.coachStatus.offersConfirm)
        assertFalse("nothing has failed to retry", week.coachStatus.offersRetry)
        assertTrue(week.coachStatus.offersContinueWithoutOutline)
        assertFalse("the tab gains no button while the coach works", week.canReset)

        // The same open generation one step further along: a RUNNING row is still planning, not a new status.
        assertEquals(1, generationDao.markRunning(row))
        assertEquals(GenerationStatus.RUNNING, generationDao.get(row)!!.status)
        assertEquals(CoachStatus.Planning, draftWeek().coachStatus)
    }

    // ── The open row carries the coach-offline mark ────────────────────
    // The mark travels on error alone while the row is still QUEUED, so this is an open generation waiting on a
    // Mission Control it cannot reach — not a failure wearing an offline label.

    @Test
    fun theMissionTabReportsOfflineWhenItsOpenOutlineRowCarriesTheCoachOfflineMark() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()

        val row = generationDao.insert(outlineRow(mission.id, GenerationStatus.QUEUED, at(1)))
        generationDao.setCoachOfflineMark(row, Generation.COACH_OFFLINE)

        val stored = generationDao.get(row)!!
        assertEquals(GenerationStatus.QUEUED, stored.status)
        assertEquals(Generation.COACH_OFFLINE, stored.error)

        val week = draftWeek()
        assertEquals(MissionStatus.DRAFT, week.status)
        assertEquals(CoachStatus.Offline, week.coachStatus)
        assertFalse(week.coachStatus.offersConfirm)
        assertFalse(week.coachStatus.offersRetry)
        assertTrue(week.coachStatus.offersContinueWithoutOutline)
        assertFalse(week.canReset)
    }

    // ── The row failed: the week carries its reason word for word ──────
    // A FAILED row is not open, but it is the newest outline the draft has, so the tab reports it rather than
    // falling back on "Not confirmed yet". Retry and Continue belong to the status, not to this tab: DESIGN.md §9
    // gives the Mission tab no action beyond Reset week, and a draft has no week to reset.

    @Test
    fun theMissionTabReportsFailedWithTheReasonItsOutlineRowCarriesAndOffersRetryAndContinue() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()

        val row = generationDao.insert(outlineRow(mission.id, GenerationStatus.QUEUED, at(1)))
        // A validator's reason: punctuation and inner spacing, so a rewrite or a trim is caught.
        val reason = "reply rejected: 3 of 5 sets name a barbell the owner does not own"
        generationDao.markFailed(row, reason, at(2))
        assertEquals(reason, generationDao.get(row)!!.error)

        val week = draftWeek()
        assertEquals(MissionStatus.DRAFT, week.status)
        assertTrue(week.coachStatus is CoachStatus.Failed)
        assertEquals(reason, (week.coachStatus as CoachStatus.Failed).reason)
        assertTrue(week.coachStatus.offersRetry)
        assertTrue(week.coachStatus.offersContinueWithoutOutline)
        assertFalse(week.coachStatus.offersConfirm)
        assertFalse("the tab gains no button for the actions it offers", week.canReset)
    }

    // ── Nothing in flight keeps the tab's own sentence ─────────────────
    // The status "Not confirmed yet. Confirm this Mission on the Launchpad." is drawn from, for a draft nobody
    // has confirmed and for one whose only outline row was cancelled behind it (work/AppOpenSweep cancels such a
    // row and leaves the mission DRAFT). A cancelled row is no row.

    @Test
    fun theMissionTabKeepsTheUnconfirmedDraftStatusWhenNoOutlineGenerationIsOpen() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()

        val nothingInFlight = draftWeek()
        assertEquals(MissionStatus.DRAFT, nothingInFlight.status)
        assertEquals(CoachStatus.Editable, nothingInFlight.coachStatus)
        assertTrue("Confirm is still the way on", nothingInFlight.coachStatus.offersConfirm)
        assertFalse(nothingInFlight.coachStatus.offersRetry)
        assertFalse(nothingInFlight.coachStatus.offersContinueWithoutOutline)
        assertFalse(nothingInFlight.canReset)

        val cancelled = generationDao.insert(
            outlineRow(mission.id, GenerationStatus.CANCELLED, at(1), finishedAt = at(1))
        )
        assertEquals(GenerationStatus.CANCELLED, generationDao.get(cancelled)!!.status)

        val after = draftWeek()
        assertEquals(MissionStatus.DRAFT, after.status)
        assertEquals(CoachStatus.Editable, after.coachStatus)
        assertTrue(after.coachStatus.offersConfirm)
        assertFalse(after.coachStatus.offersRetry)
        assertFalse(after.coachStatus.offersContinueWithoutOutline)
        assertFalse(after.canReset)
    }

    // ── The tab follows its own row on the subscription it opened first ─
    // One observe(), opened before the draft has any outline row, read through the two writes that a Confirm and
    // a rejected reply make: unconfirmed, then planning, then failed carrying the row's own reason. Every status
    // below is a value this one collection received, so a status only a fresh subscription could see fails here.

    @Test
    fun theMissionTabFollowsItsOwnOutlineRowFromQueuedToFailedWithoutReSubscribing() = runBlocking {
        coachIsSetUp()
        val mission = manager.onAppOpen()

        val collecting = openSubscription()
        try {
            val unconfirmed = awaitWeek { it.coachStatus is CoachStatus.Editable }
            assertEquals(MissionStatus.DRAFT, unconfirmed.status)
            assertEquals(mission.id, unconfirmed.missionId)
            assertTrue("nothing is open yet, so Confirm is the way on", unconfirmed.coachStatus.offersConfirm)

            val beforeRow = emissions.size
            val row = generationDao.insert(outlineRow(unconfirmed.missionId, GenerationStatus.QUEUED, at(1)))

            val planning = awaitWeek { it.coachStatus is CoachStatus.Planning }
            assertTrue("one new emission out of the one collection", emissions.size > beforeRow)
            assertEquals("the same draft week, not a re-subscribed one", unconfirmed.missionId, planning.missionId)
            assertEquals(MissionStatus.DRAFT, planning.status)
            assertFalse("the ask is already out, so Confirm is gone", planning.coachStatus.offersConfirm)
            assertFalse("the tab gains no button while the coach works", planning.canReset)
            assertEquals(GenerationStatus.QUEUED, generationDao.get(row)!!.status)

            // A validator's reason: punctuation and inner spacing, so a rewrite or a trim is caught.
            val reason = "reply rejected: 3 of 5 sets name a barbell the owner does not own"
            generationDao.markFailed(row, reason, at(2))

            val beforeFailure = emissions.size
            val failed = awaitWeek { it.coachStatus is CoachStatus.Failed }
            assertTrue("the same subscription carried this one too", emissions.size > beforeFailure)
            assertTrue("failed arrived after planning in the same collection",
                emissions.indexOf(planning) < emissions.indexOf(failed))
            assertEquals(unconfirmed.missionId, failed.missionId)
            assertEquals(MissionStatus.DRAFT, failed.status)
            assertEquals(reason, (failed.coachStatus as CoachStatus.Failed).reason)
            assertTrue(failed.coachStatus.offersRetry)
            assertTrue(failed.coachStatus.offersContinueWithoutOutline)
            assertFalse(failed.coachStatus.offersConfirm)
            assertFalse("the tab gains no button as its row changes", failed.canReset)
            assertEquals(reason, generationDao.get(row)!!.error)
        } finally {
            collecting.cancel()
        }
    }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val POLL_MS = 25L
    }
}
