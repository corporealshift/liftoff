package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionManagerStartNextWeekTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var settingsStore: SettingsStore

    // 2026-01-18 is a Sunday: the calendar week started Monday 2026-01-12, and next week starts 2026-01-19.
    private val thisWeek = LocalDate.of(2026, 1, 12)
    private val nextWeek = LocalDate.of(2026, 1, 19)
    private val sunday = Instant.parse("2026-01-18T12:00:00Z")
    private val monday = Instant.parse("2026-01-19T12:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "settings.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)
        runBlocking { settingsStore.setDefaultPattern("RLL") }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun managerAt(instant: Instant) = MissionManager(
        db, settingsStore,
        clock = Clock.fixed(instant, ZoneOffset.UTC),
        zone = { ZoneOffset.UTC }
    )

    private fun insertMission(status: MissionStatus, week: LocalDate = thisWeek): Long = runBlocking {
        db.missionDao().insert(Mission(weekStart = week, pattern = "RL", status = status, outlineNotes = null))
    }

    private fun assertRefused(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected an IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `start is refused while this week is still active or a draft`() {
        for (status in listOf(MissionStatus.ACTIVE, MissionStatus.DRAFT)) {
            val id = insertMission(status)
            assertRefused { managerAt(sunday).startNextWeek() }
            assertNull("$status week let next week start", runBlocking { db.missionDao().getByWeekStart(nextWeek) })
            runBlocking { db.missionDao().deleteWeek(id) }
        }
    }

    @Test
    fun `start makes next week's draft with the default pattern and makes it the current week`() = runBlocking {
        val closed = insertMission(MissionStatus.CLOSED)
        val manager = managerAt(sunday)

        val started = manager.startNextWeek()

        assertEquals(nextWeek, started.weekStart)
        assertEquals(MissionStatus.DRAFT, started.status)
        assertEquals("RLL", started.pattern)
        assertEquals(nextWeek, manager.currentWeekStart.value)
        assertEquals("the calendar week is still this one", thisWeek, manager.calendarWeekStart())
        // Sunday's cold start lands on the early week, and leaves the closed week as it was.
        assertEquals(started.id, manager.onAppOpen().id)
        assertEquals(nextWeek, manager.currentWeekStart.value)
        assertEquals(MissionStatus.CLOSED, db.missionDao().get(closed)!!.status)
        assertEquals(MissionStatus.DRAFT, db.missionDao().get(started.id)!!.status)
    }

    @Test
    fun `a second start returns the same draft and never runs two weeks ahead`() = runBlocking {
        insertMission(MissionStatus.CLOSED)
        val manager = managerAt(sunday)
        val first = manager.startNextWeek()

        assertEquals(first.id, manager.startNextWeek().id)

        // Even with next week itself closed, a start still answers with next week rather than the one after.
        db.missionDao().update(first.copy(status = MissionStatus.CLOSED))
        assertEquals(first.id, manager.startNextWeek().id)
        assertEquals(2L, db.missionDao().countAll())
    }

    @Test
    fun `the early week is fully live on Sunday`() = runBlocking {
        insertMission(MissionStatus.CLOSED)
        val manager = managerAt(sunday)
        val draft = manager.startNextWeek()

        manager.setPattern(draft.id, "RL")
        manager.continueWithoutOutline(draft.id)

        assertEquals(MissionStatus.ACTIVE, db.missionDao().get(draft.id)!!.status)
        val run = currentSortie(db.sortieDao().getForMission(draft.id))!!
        assertEquals(SortieType.RUN, run.type)
        manager.launch(run.id)
        assertEquals(SortieState.IN_FLIGHT, db.sortieDao().get(run.id)!!.state)
        // Reset week answers to the early week as the current one.
        manager.resetWeek(draft.id)
        assertEquals(MissionStatus.DRAFT, db.missionDao().getByWeekStart(nextWeek)!!.status)
    }

    @Test
    fun `Monday's cold start keeps the week that was started early`() = runBlocking {
        val closed = insertMission(MissionStatus.CLOSED)
        val started = managerAt(sunday).startNextWeek()

        val opened = managerAt(monday).onAppOpen()

        assertEquals(started.id, opened.id)
        assertEquals(2L, db.missionDao().countAll())
        assertEquals(MissionStatus.CLOSED, db.missionDao().get(closed)!!.status)
    }
}
