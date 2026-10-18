package com.liftoff.app.ui.landed

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.*
import com.liftoff.app.settings.SettingsStore
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
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LandedStatesTest {

    private lateinit var context: Context
    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var flightPlanDao: FlightPlanDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private lateinit var states: LandedStates

    private fun settingsFile() = File(context.cacheDir, "landed_states.preferences_pb")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        flightPlanDao = db.flightPlanDao()

        settingsStore = SettingsStore(PreferenceDataStoreFactory.create { settingsFile() })
        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
        states = LandedStates(db, settingsStore, zone = { ZoneOffset.UTC })
    }

    @After
    fun tearDown() {
        db.close()
        settingsFile().delete()
    }

    // ── The list: weeks newest first, rows newest first inside a week ─────────

    @Test
    fun historyGroupsByWeekNewestFirstAcrossTwoWeeks() = runBlocking {
        val jan5 = insertWeek(LocalDate.of(2026, 1, 5))
        val jan12 = insertWeek(LocalDate.of(2026, 1, 12))

        val squatJan5 = insertSortie(jan5, 2, SortieType.LIFT, SortieState.SCRUBBED,
            focus = "Squat", scrubReason = "sick")
        val runJan8 = insertSortie(jan5, 1, SortieType.RUN, SortieState.LANDED,
            focus = "Easy 5k", landedAt = Instant.parse("2026-01-08T07:00:00Z"))
        val liftJan6 = insertSortie(jan5, 0, SortieType.LIFT, SortieState.LANDED,
            focus = "Full body", landedAt = Instant.parse("2026-01-06T10:00:00Z"))

        val scrubJan12 = insertSortie(jan12, 2, SortieType.LIFT, SortieState.SCRUBBED,
            focus = null, launchedAt = Instant.parse("2026-01-15T05:00:00Z"), scrubReason = "travel")
        val pullJan14 = insertSortie(jan12, 1, SortieType.LIFT, SortieState.LANDED,
            focus = "Pull", landedAt = Instant.parse("2026-01-14T18:00:00Z"))
        val runJan13 = insertSortie(jan12, 0, SortieType.RUN, SortieState.LANDED,
            focus = "Tempo", landedAt = Instant.parse("2026-01-13T06:00:00Z"))

        // Only two of the six have a Flight Plan, so the other four must fall back to focus or type.
        writePlan(liftJan6, "Push Day")
        writePlan(pullJan14, "Pull Day")

        val state = withTimeout(5_000) {
            states.observeHistory().first { it is LandedHistoryState.Weeks }
        } as LandedHistoryState.Weeks

        assertEquals("the newer week first", listOf(LocalDate.of(2026, 1, 12), LocalDate.of(2026, 1, 5)),
            state.weeks.map { it.weekStart })
        assertEquals(listOf("WEEK OF JAN 12", "WEEK OF JAN 5"), state.weeks.map { it.head })
        assertEquals(listOf(3, 3), state.weeks.map { it.rows.size })

        val newest = state.weeks[0]
        assertEquals(listOf(scrubJan12, pullJan14, runJan13), newest.rows.map { it.sortieId })
        assertEquals(listOf("03", "02", "01"), newest.rows.map { it.indexLabel })
        assertEquals(listOf("Lift", "Pull Day", "Tempo"), newest.rows.map { it.title })
        assertEquals(listOf(SortieType.LIFT, SortieType.LIFT, SortieType.RUN), newest.rows.map { it.type })
        assertEquals(listOf("LIFT", "LIFT", "RUN"), newest.rows.map { it.typeLabel })
        assertEquals(listOf("SCRUBBED", "LANDED", "LANDED"), newest.rows.map { it.stateLabel })
        assertEquals(listOf("JAN 15", "JAN 14", "JAN 13"), newest.rows.map { it.dateLabel })
        assertEquals(listOf("travel", null, null), newest.rows.map { it.scrubReason })

        val older = state.weeks[1]
        assertEquals(listOf(squatJan5, runJan8, liftJan6), older.rows.map { it.sortieId })
        assertEquals(listOf("03", "02", "01"), older.rows.map { it.indexLabel })
        assertEquals(listOf("Squat", "Easy 5k", "Push Day"), older.rows.map { it.title })
        assertEquals(listOf(SortieType.LIFT, SortieType.RUN, SortieType.LIFT), older.rows.map { it.type })
        assertEquals(listOf("LIFT", "RUN", "LIFT"), older.rows.map { it.typeLabel })
        assertEquals(listOf("SCRUBBED", "LANDED", "LANDED"), older.rows.map { it.stateLabel })
        // A scrub with no timestamp at all is dated by its week; a landed row by its landedAt.
        assertEquals(listOf("JAN 5", "JAN 8", "JAN 6"), older.rows.map { it.dateLabel })
        assertEquals(listOf("sick", null, null), older.rows.map { it.scrubReason })
    }

    // ── No finished sortie reads as the empty state ───────────────────────────

    @Test
    fun emptyHistoryShowsTheEmptyState() = runBlocking {
        val jan12 = insertWeek(LocalDate.of(2026, 1, 12))
        insertSortie(jan12, 0, SortieType.RUN, SortieState.PLANNED, focus = "Easy")
        insertSortie(jan12, 1, SortieType.LIFT, SortieState.PENDING, focus = "Bench")

        val state = withTimeout(5_000) { states.observeHistory().first() }

        assertSame("a history with nothing landed or scrubbed is the empty state, not a list of no weeks",
            LandedHistoryState.Empty, state)
        assertTrue("the read has run, so it is not still loading", state !is LandedHistoryState.Loading)
    }

    // ── The list is a live Room read ──────────────────────────────────────────

    @Test
    fun historyUpdatesWhenASortieIsLanded() = runBlocking {
        val jan12 = insertWeek(LocalDate.of(2026, 1, 12))
        val sortieId = insertSortie(jan12, 0, SortieType.LIFT, SortieState.PLANNED, focus = "Bench")
        manager.launch(sortieId)

        // One subscription, opened before the landing and left open through it.
        val received = mutableListOf<LandedHistoryState>()
        val collecting = launch { states.observeHistory().collect { received += it } }

        withTimeout(5_000) { while (received.isEmpty()) delay(25) }
        assertSame("the read starts while nothing is landed", LandedHistoryState.Empty, received.first())

        manager.land(sortieId)

        withTimeout(5_000) { while (received.none { it is LandedHistoryState.Weeks }) delay(25) }
        collecting.cancel()

        val week = (received.last() as LandedHistoryState.Weeks).weeks.single()
        assertEquals("WEEK OF JAN 12", week.head)
        val row = week.rows.single()
        assertEquals(sortieId, row.sortieId)
        assertEquals("LANDED", row.stateLabel)
        assertEquals("JAN 12", row.dateLabel)
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private suspend fun insertWeek(weekStart: LocalDate): Long = missionDao.insert(
        Mission(
            weekStart = weekStart,
            pattern = "LRL",
            status = MissionStatus.ACTIVE,
            outlineNotes = null
        )
    )

    private suspend fun insertSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        state: SortieState,
        focus: String?,
        launchedAt: Instant? = null,
        landedAt: Instant? = null,
        scrubReason: String? = null,
    ): Long = sortieDao.insert(
        Sortie(
            missionId = missionId,
            index = index,
            type = type,
            focus = focus,
            focusRationale = null,
            state = state,
            launchedAt = launchedAt?.toEpochMilli(),
            landedAt = landedAt?.toEpochMilli(),
            scrubReason = scrubReason,
            notes = null,
            runDistance = null,
            runMinutes = null
        )
    )

    private suspend fun writePlan(sortieId: Long, title: String) {
        flightPlanDao.writePlan(
            sortieId,
            FlightPlanDraft(
                source = FlightPlanSource.GENERATED,
                title = title,
                estimatedMinutes = 45,
                warmup = null,
                notes = null,
                runKind = null,
                targetDistance = null,
                targetPace = null,
                rawJson = "{}",
                exercises = emptyList(),
                segments = emptyList()
            )
        )
    }
}
