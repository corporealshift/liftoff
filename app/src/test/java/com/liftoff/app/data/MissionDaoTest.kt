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

    // Nothing wipes missions or sortie history wholesale. MissionDao has two by-id deletes: import's, guarded to
    // one DRAFT row, and Reset week's, which the owner confirms; SortieDao's one by-id delete is the In-Flight
    // "no longer exists" case. Each must be a @Query so Room's invalidation tracker re-emits.
    @Test
    fun noWipeForMissionsAndOnlyTheByIdDeleteForSorties() {
        val missionDeletes = MissionDao::class.java.methods.map { it.name }.filter { it.contains("delete", ignoreCase = true) }.toSet()
        val sortieDeletes = SortieDao::class.java.methods.map { it.name }.filter { it.contains("delete", ignoreCase = true) }.toSet()
        assertEquals("MissionDao's deletes are import's draft delete and Reset week's", setOf("deleteDraft", "deleteWeek"), missionDeletes)
        assertEquals("SortieDao's only delete is the by-id one", setOf("deleteById"), sortieDeletes)
    }

    // Room reads the mission row and its @Relation sorties in two SELECTs, so a land/advance write that
    // lands between them emits a sortie still PLANNED next to a plan row already replaced. @Transaction on
    // observeRaw is what brackets both reads in one transaction, so this case asks Room itself: its query
    // callback sees every statement the DAO runs, and db.inTransaction() is read inside the callback because
    // the transaction exists only while Room's callable runs. Room dispatches DAO work onto its own executor
    // threads, so the recording list is copy-on-write; the case starts no threads of its own, and a Room +
    // Robolectric test can hang (app/build.gradle.kts:42-51).
    @Test
    fun observeWeekReadsMissionAndSortiesInsideOneTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reads = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        var watched: LiftoffDatabase? = null
        val callback = object : androidx.room.RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val database = watched
                if (database != null) reads.add(sqlQuery to database.inTransaction())
            }
        }
        // This case's own database, so the callback is only ever installed here. allowMainThreadQueries
        // stays: RoomDatabase.beginTransaction() calls assertNotMainThread(), which returns early only
        // when that flag is set, and the annotation makes Room begin a transaction in this path.
        val watchedDb = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(callback, java.util.concurrent.Executor { it.run() })
            .build()
        watched = watchedDb
        db.close()
        db = watchedDb
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()

        // Room skips the relation SELECT when the parent query returns no rows, so the seeds come first.
        val weekStart = java.time.LocalDate.of(2026, 1, 5)
        val missionId = missionDao.insert(Mission(weekStart = weekStart, pattern = "push", status = MissionStatus.DRAFT, outlineNotes = null))
        sortieDao.insert(Sortie(missionId = missionId, index = 0, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))
        sortieDao.insert(Sortie(missionId = missionId, index = 1, type = SortieType.RUN, focus = null, focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null, scrubReason = null, notes = null, runDistance = null, runMinutes = null))

        // The seeds reach this same callback, inside Room's own write transactions, and Room's invalidation
        // tracker reaches it too: only the statements of this one collection are what the assertions read.
        reads.clear()

        val emission = missionDao.observeWeek(weekStart).first()
        assertTrue("observeWeek emitted nothing", emission != null)
        assertEquals(2, emission!!.sorties.size)

        val parent = reads.indexOfFirst { it.first == "SELECT * FROM mission WHERE weekStart = ?" }
        val relation = reads.indexOfFirst { it.first.startsWith("SELECT") && it.first.contains("FROM `sortie`") && it.first.contains("`missionId` IN (") }
        assertTrue("the mission SELECT was not recorded: $reads", parent >= 0)
        assertTrue("the @Relation sortie SELECT was not recorded: $reads", relation >= 0)
        assertTrue("the mission SELECT did not come before the @Relation sortie SELECT: $reads", parent < relation)
        assertTrue("the mission SELECT ran outside a transaction: $reads", reads[parent].second)
        assertTrue("the @Relation sortie SELECT ran outside a transaction: $reads", reads[relation].second)
    }
}
