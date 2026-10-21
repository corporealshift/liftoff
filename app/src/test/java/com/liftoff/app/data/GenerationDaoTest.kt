package com.liftoff.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationDaoTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var generationDao: GenerationDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        generationDao = db.generationDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertGetUpdateDelete() = runBlocking {
        val createdAt = System.currentTimeMillis()
        val id = generationDao.insert(Generation(
            kind = GenerationKind.OUTLINE,
            missionId = 1,
            sortieId = null,
            status = GenerationStatus.QUEUED,
            nabuSessionId = "session-1",
            lastEventId = null,
            attempt = 0,
            error = null,
            createdAt = createdAt,
            finishedAt = null
        ))
        assertTrue(id > 0)

        val gen = generationDao.get(id)!!
        assertEquals(GenerationKind.OUTLINE, gen.kind)
        assertEquals(1, gen.missionId)
        assertEquals(GenerationStatus.QUEUED, gen.status)
        assertEquals("session-1", gen.nabuSessionId)
        assertNull(gen.lastEventId)
        assertEquals(0, gen.attempt)

        val updated = gen.copy(
            status = GenerationStatus.RUNNING,
            lastEventId = "evt-42",
            attempt = 1,
            error = "timeout",
            finishedAt = System.currentTimeMillis()
        )
        generationDao.update(updated)

        val afterUpdate = generationDao.get(id)!!
        assertEquals(GenerationStatus.RUNNING, afterUpdate.status)
        assertEquals("evt-42", afterUpdate.lastEventId)
        assertEquals(1, afterUpdate.attempt)
        assertEquals("timeout", afterUpdate.error)
        assertNotNull(afterUpdate.finishedAt)

        generationDao.delete(afterUpdate)
        assertNull(generationDao.get(id))
    }

    private suspend fun queueGeneration(
        kind: GenerationKind,
        missionId: Long,
        sortieId: Long?,
        createdAt: Long,
        status: GenerationStatus = GenerationStatus.QUEUED,
        nabuSessionId: String? = null,
        lastEventId: String? = null,
        attempt: Int = 0,
    ): Generation {
        val id = generationDao.insert(Generation(
            kind = kind,
            missionId = missionId,
            sortieId = sortieId,
            status = status,
            nabuSessionId = nabuSessionId,
            lastEventId = lastEventId,
            attempt = attempt,
            error = null,
            createdAt = createdAt,
            finishedAt = null
        ))
        return generationDao.get(id)!!
    }

    @Test
    fun markingAGenerationRunningSucceededFailedOrCancelledSetsItsStatusAndFinishedAt() = runBlocking {
        val untouched = queueGeneration(GenerationKind.OUTLINE, missionId = 1, sortieId = null, createdAt = 1_000)
        val running = queueGeneration(
            GenerationKind.FLIGHT_PLAN, missionId = 1, sortieId = 10, createdAt = 2_000,
            nabuSessionId = "session-1", lastEventId = "evt-1", attempt = 1,
        )

        generationDao.markRunning(running.id)
        val isRunning = generationDao.get(running.id)!!
        assertEquals(GenerationStatus.RUNNING, isRunning.status)
        // Marking a row RUNNING is a step in the same generation, not a fresh start: it stamps no finishedAt
        // and keeps the session, cursor and attempt the row already had.
        assertNull(isRunning.finishedAt)
        assertEquals("session-1", isRunning.nabuSessionId)
        assertEquals("evt-1", isRunning.lastEventId)
        assertEquals(1, isRunning.attempt)

        val succeeded = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 11, createdAt = 3_000)
        generationDao.markSucceeded(succeeded.id, finishedAt = 4_000)
        val isSucceeded = generationDao.get(succeeded.id)!!
        assertEquals(GenerationStatus.SUCCEEDED, isSucceeded.status)
        assertEquals(4_000L, isSucceeded.finishedAt!!)

        val failed = queueGeneration(GenerationKind.OUTLINE, 1, null, createdAt = 5_000)
        generationDao.markFailed(failed.id, error = "coach unreachable", finishedAt = 6_000)
        val isFailed = generationDao.get(failed.id)!!
        assertEquals(GenerationStatus.FAILED, isFailed.status)
        assertEquals("coach unreachable", isFailed.error)
        assertEquals(6_000L, isFailed.finishedAt!!)

        val cancelled = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 12, createdAt = 7_000)
        generationDao.markCancelled(cancelled.id, finishedAt = 8_000)
        val isCancelled = generationDao.get(cancelled.id)!!
        assertEquals(GenerationStatus.CANCELLED, isCancelled.status)
        assertEquals(8_000L, isCancelled.finishedAt!!)

        val stillQueued = generationDao.get(untouched.id)!!
        assertEquals(GenerationStatus.QUEUED, stillQueued.status)
        assertNull(stillQueued.finishedAt)
        assertNull(stillQueued.error)
    }

    @Test
    fun savingTheSessionIdAndLastEventIdAndBumpingTheAttemptUpdatesTheGenerationRow() = runBlocking {
        val queued = queueGeneration(GenerationKind.FLIGHT_PLAN, missionId = 1, sortieId = 10, createdAt = 1_000)
        generationDao.markRunning(queued.id)
        generationDao.saveSessionId(queued.id, nabuSessionId = "session-77")
        generationDao.saveLastEventId(queued.id, lastEventId = "evt-3")

        val resumed = generationDao.get(queued.id)!!
        assertEquals(GenerationStatus.RUNNING, resumed.status)
        assertEquals("session-77", resumed.nabuSessionId)
        assertEquals("evt-3", resumed.lastEventId)
        assertEquals(0, resumed.attempt)

        // The cursor moves forward as events arrive; the session id the worker stored first is not rewritten.
        generationDao.saveLastEventId(queued.id, lastEventId = "evt-9")
        // A worker killed and restarted counts its own attempts, so attempt climbs from 0 by itself.
        generationDao.bumpAttempt(queued.id)
        generationDao.bumpAttempt(queued.id)
        generationDao.bumpAttempt(queued.id)

        val after = generationDao.get(queued.id)!!
        assertEquals(3, after.attempt)
        assertEquals("evt-9", after.lastEventId)
        assertEquals("session-77", after.nabuSessionId)
        assertEquals(GenerationStatus.RUNNING, after.status)
        assertNull(after.finishedAt)
    }

    @Test
    fun theNewestNonCancelledGenerationForASortieIgnoresCancelledRows() = runBlocking {
        val queued = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 10, createdAt = 1_000)
        val running = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 10, createdAt = 2_000, status = GenerationStatus.RUNNING)
        queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 10, createdAt = 3_000, status = GenerationStatus.CANCELLED)
        val otherSortie = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 11, createdAt = 4_000, status = GenerationStatus.SUCCEEDED)

        // The newest row for the sortie is cancelled, so the lookup takes the live row under it rather than
        // ranking the cancelled one first.
        val newest = generationDao.getNewestNonCancelledForSortie(10)!!
        assertEquals(running.id, newest.id)
        assertEquals(2_000L, newest.createdAt)

        // Two rows created at the same instant are told apart by id, so the one inserted later wins.
        val tiedEarlier = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 10, createdAt = 5_000)
        val tiedLater = queueGeneration(GenerationKind.FLIGHT_PLAN, 1, 10, createdAt = 5_000)
        val tied = generationDao.getNewestNonCancelledForSortie(10)!!
        assertEquals(tiedLater.id, tied.id)
        assertTrue(tied.id > tiedEarlier.id)

        // Another sortie's generation is never this sortie's, however new it is.
        assertEquals(otherSortie.id, generationDao.getNewestNonCancelledForSortie(11)?.id)

        generationDao.markCancelled(running.id, finishedAt = 6_000)
        generationDao.markCancelled(tiedEarlier.id, finishedAt = 6_000)
        generationDao.markCancelled(tiedLater.id, finishedAt = 6_000)
        generationDao.markCancelled(queued.id, finishedAt = 6_000)

        assertNull(generationDao.getNewestNonCancelledForSortie(10))
        assertEquals(otherSortie.id, generationDao.getNewestNonCancelledForSortie(11)?.id)
    }

    @Test
    fun theNewestNonCancelledGenerationForAMissionIsItsOwnOutlineGeneration() = runBlocking {
        val outline = queueGeneration(GenerationKind.OUTLINE, missionId = 5, sortieId = null, createdAt = 1_000, status = GenerationStatus.RUNNING)
        queueGeneration(GenerationKind.FLIGHT_PLAN, 5, 20, createdAt = 2_000, status = GenerationStatus.SUCCEEDED)
        val newestPlan = queueGeneration(GenerationKind.FLIGHT_PLAN, 5, 21, createdAt = 3_000)
        queueGeneration(GenerationKind.FLIGHT_PLAN, 5, 21, createdAt = 4_000, status = GenerationStatus.CANCELLED)
        val otherMission = queueGeneration(GenerationKind.OUTLINE, 6, null, createdAt = 5_000, status = GenerationStatus.SUCCEEDED)

        // The Mission lookup reads only the Mission's own OUTLINE row, so a sortie's newer plan generation
        // cannot stand in for the outline the draft screen is asking about.
        val found = generationDao.getNewestNonCancelledOutlineForMission(5)!!
        assertEquals(outline.id, found.id)
        assertEquals(GenerationKind.OUTLINE, found.kind)
        assertNull(found.sortieId)
        assertEquals(1_000L, found.createdAt)

        // The sortie lookups still see those FLIGHT_PLAN rows, cancelled ones left out as always.
        assertEquals(newestPlan.id, generationDao.getNewestNonCancelledForSortie(21)?.id)

        // With its outline generation cancelled, the Mission has none — even with live plan rows under it.
        generationDao.markCancelled(outline.id, finishedAt = 6_000)
        assertNull(generationDao.getNewestNonCancelledOutlineForMission(5))

        // And another Mission's outline generation is not this one's.
        assertEquals(otherMission.id, generationDao.getNewestNonCancelledOutlineForMission(6)?.id)
    }
}
