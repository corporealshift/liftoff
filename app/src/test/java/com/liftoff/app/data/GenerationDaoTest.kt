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
}
