package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortieDaoTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun historyIsNewestFirstByWeekThenIndex() = runBlocking {
        val week1 = java.time.LocalDate.of(2026, 1, 5)
        val week2 = java.time.LocalDate.of(2026, 1, 12)

        val m1Id = missionDao.insert(Mission(weekStart = week1, pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        val m2Id = missionDao.insert(Mission(weekStart = week2, pattern = "pull", status = MissionStatus.DRAFT, outlineNotes = null))

        sortieDao.insert(Sortie(missionId = m1Id, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.LANDED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = m1Id, index = 1, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.LANDED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = m2Id, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.LANDED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = m2Id, index = 1, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null, scrubReason = "weather", notes = null, runDistance = null, runMinutes = null))

        val history = sortieDao.observeHistory().first()
        assertEquals(4, history.size)
        // week2 index 1 first (newest week, highest index)
        assertEquals(1, history[0].index)
        assertEquals(m2Id, history[0].missionId)
        // week2 index 0
        assertEquals(0, history[1].index)
        assertEquals(m2Id, history[1].missionId)
        // week1 index 1
        assertEquals(1, history[2].index)
        assertEquals(m1Id, history[2].missionId)
        // week1 index 0
        assertEquals(0, history[3].index)
        assertEquals(m1Id, history[3].missionId)
    }

    @Test
    fun historyHasOnlyLandedAndScrubbed() = runBlocking {
        val week1 = java.time.LocalDate.of(2026, 1, 5)
        val mId = missionDao.insert(Mission(weekStart = week1, pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))

        sortieDao.insert(Sortie(missionId = mId, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.LANDED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mId, index = 1, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null, scrubReason = "bad", notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mId, index = 2, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mId, index = 3, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PLANNED, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = mId, index = 4, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.IN_FLIGHT, launchedAt = System.currentTimeMillis(), landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val history = sortieDao.observeHistory().first()
        assertEquals(2, history.size)
        // History orders by weekStart DESC, then index DESC — so index 1 (SCRUBBED) comes first
        assertEquals(SortieState.SCRUBBED, history[0].state)
        assertEquals(SortieState.LANDED, history[1].state)
    }
}
