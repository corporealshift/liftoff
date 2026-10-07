package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionDaoTest {

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
    fun observeWeekReturnsSortiesInIndexOrder() = runBlocking {
        val missionId = missionDao.insert(Mission(weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        sortieDao.insert(Sortie(missionId = missionId, index = 2, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = missionId, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = missionId, index = 1, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val result = missionDao.observeWeek(java.time.LocalDate.of(2026, 1, 5)).first()
        assertTrue(result != null)
        val sorties = result!!.sorties
        assertEquals(listOf(0, 1, 2), sorties.map { it.index })
    }

    @Test
    fun observeWeekIsNullForWeekWithoutMission() = runBlocking {
        val result = missionDao.observeWeek(java.time.LocalDate.of(2026, 2, 2)).first()
        assertTrue(result == null)
    }

    @Test
    fun missionAndSortieUpdatesAreVisible() = runBlocking {
        val missionId = missionDao.insert(Mission(weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        sortieDao.insert(Sortie(missionId = missionId, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        val updatedMission = Mission(id = missionId, weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "pull", status = MissionStatus.ACTIVE, outlineNotes = "notes")
        missionDao.update(updatedMission)

        sortieDao.update(Sortie(id = 1L, missionId = missionId, index = 0, type = SortieType.LIFT, focus = "focus!", focusRationale = null, state = SortieState.LANDED, launchedAt = null, landedAt = null, scrubReason = null, notes = "snotes", runDistance = null, runMinutes = null))

        val result = missionDao.observeWeek(java.time.LocalDate.of(2026, 1, 5)).first()!!
        assertEquals(MissionStatus.ACTIVE, result.mission.status)
        assertEquals("pull", result.mission.pattern)
        assertEquals(SortieType.LIFT, result.sorties[0].type)
    }

    @Test
    fun duplicateWeekStartIsRejected() = runBlocking {
        missionDao.insert(Mission(weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        try {
            missionDao.insert(Mission(weekStart = java.time.LocalDate.of(2026, 1, 5), pattern = "pull", status = MissionStatus.DRAFT, outlineNotes = null))
            assertTrue("Should have thrown SQLiteConstraintException", false)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun noDeleteForMissionsOrSorties() {
        val missionMethods = MissionDao::class.java.methods.map { it.name }.toSet()
        val sortieMethods = SortieDao::class.java.methods.map { it.name }.toSet()
        assertTrue("MissionDao should not have a delete method", !missionMethods.any { it.contains("delete", ignoreCase = true) })
        assertTrue("SortieDao should not have a delete method", !sortieMethods.any { it.contains("delete", ignoreCase = true) })
    }
}
