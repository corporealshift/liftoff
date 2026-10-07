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
class EquipmentDaoTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var equipmentDao: EquipmentDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        equipmentDao = db.equipmentDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun addAcceptsValidKey() = runBlocking {
        val id = equipmentDao.add(Equipment(key = "dumbbells_2", name = "Dumbbells 2kg", notes = "light"))
        assertTrue(id > 0)

        val item = equipmentDao.get(id)!!
        assertEquals("dumbbells_2", item.key)
        assertEquals("Dumbbells 2kg", item.name)
    }

    @Test
    fun invalidKeyIsRejectedBeforeWrite() = runBlocking {
        val badKeys = listOf("Bar", "pull up", "kb-24", "")
        for (key in badKeys) {
            try {
                equipmentDao.add(Equipment(key = key, name = "name"))
                assertTrue("add should throw for key '$key'", false)
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }

        // None of them should have been written
        val all = equipmentDao.observeAll().first()
        assertEquals(0, all.size)
    }

    @Test
    fun duplicateKeyIsRejected() = runBlocking {
        equipmentDao.add(Equipment(key = "barbell", name = "Barbell"))
        try {
            equipmentDao.add(Equipment(key = "barbell", name = "Barbell 2kg"))
            assertTrue("Should have thrown SQLiteConstraintException for duplicate key", false)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun editChangesNameAndNotesButNotKey() = runBlocking {
        val id = equipmentDao.add(Equipment(key = "barbell", name = "Old Name", notes = "old"))
        equipmentDao.edit(id, "New Name", "new notes")

        val item = equipmentDao.get(id)!!
        assertEquals("barbell", item.key)
        assertEquals("New Name", item.name)
        assertEquals("new notes", item.notes)
    }

    @Test
    fun deactivateKeepsItemOutOfActiveList() = runBlocking {
        val id = equipmentDao.add(Equipment(key = "barbell", name = "Barbell"))

        equipmentDao.deactivate(id)

        val active = equipmentDao.observeActive().first()
        assertEquals(0, active.size)

        val all = equipmentDao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("barbell", all[0].key)

        val item = equipmentDao.get(id)!!
        assertEquals("barbell", item.key)
    }

    @Test
    fun reactivateRestoresItem() = runBlocking {
        val id = equipmentDao.add(Equipment(key = "barbell", name = "Barbell"))
        equipmentDao.deactivate(id)

        equipmentDao.reactivate(id)

        val active = equipmentDao.observeActive().first()
        assertEquals(1, active.size)
        assertEquals("barbell", active[0].key)
    }

    @Test
    fun hasNoDeleteOperation() {
        val methods = EquipmentDao::class.java.methods.map { it.name }.toSet()
        assertTrue("EquipmentDao should not have a delete method", !methods.any { it.contains("delete", ignoreCase = true) })
    }
}
