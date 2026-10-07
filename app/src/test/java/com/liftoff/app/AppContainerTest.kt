package com.liftoff.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {

    private val app: LiftoffApplication
        get() = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication

    @Test
    fun applicationIsLiftoffApplication() {
        assertEquals(LiftoffApplication::class.java, app.javaClass)
    }

    @Test
    fun applicationOwnsOneContainer() {
        assertSame(app.container, app.container)
    }

    @Test
    fun containerReturnsSameSettingsStore() {
        assertSame(app.container.settingsStore, app.container.settingsStore)
    }

    @Test
    fun settingsArePersistedToSettingsPreferencesFile() {
        val store = app.container.settingsStore
        runBlocking {
            store.setDaemonHost("192.168.1.1")
            assertEquals("192.168.1.1", store.settings.first().daemonHost)
        }
    }

    @Test
    fun containerReturnsSameDatabase() {
        assertSame(app.container.database, app.container.database)
    }

    @Test
    fun databaseRefusesVersionMismatchInsteadOfWiping() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tempFile = java.io.File(context.cacheDir, "version_test.db")
            if (tempFile.exists()) tempFile.delete()

            // Create DB with Room to get full schema, insert data, then close
            val memDb = Room.inMemoryDatabaseBuilder(context, com.liftoff.app.data.LiftoffDatabase::class.java).build()
            val equipmentSql = memDb.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='equipment'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            memDb.close()

            // Use raw SQLiteDatabase to create the DB file with schema and data
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(tempFile, null)
            equipmentSql?.let { db.execSQL(it) }
            db.execSQL("INSERT INTO equipment (key, name, notes, active) VALUES ('test_key', 'Test Equipment', '', 1)")

            val countBefore = db.query("equipment", arrayOf("COUNT(*)"), null, null, null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertTrue(countBefore > 0)
            db.close()

            // Bump user_version to 2 so Room's v1 schema thinks it's incompatible
            val rawDb = android.database.sqlite.SQLiteDatabase.openDatabase(tempFile.absolutePath, null, 0)
            rawDb.execSQL("PRAGMA user_version = 2")
            rawDb.close()

            // Open the file in-place via databaseBuilder using the absolute path.
            // Room expects version 1; user_version=2 triggers a migration check.
            // Without fallbackToDestructiveMigration this must throw and must not wipe the file.
            var roomThrew = false
            try {
                val badDb = Room.databaseBuilder(
                    context.applicationContext,
                    com.liftoff.app.data.LiftoffDatabase::class.java,
                    tempFile.absolutePath
                ).build()
                // Trigger lazy open of the underlying SQLiteOpenHelper
                badDb.openHelper.writableDatabase
                badDb.close()
            } catch (_: Exception) {
                roomThrew = true
            }
            assertTrue("Room should throw on version mismatch instead of wiping", roomThrew)

            // Verify the database file still exists and data is intact after Room fails to open
            assertTrue("DB file should exist", tempFile.exists())
            val reopened = android.database.sqlite.SQLiteDatabase.openDatabase(tempFile.absolutePath, null, 0)
            val cursor = reopened.query("equipment", arrayOf("COUNT(*)"), null, null, null, null, null)
            assertTrue(cursor.moveToFirst())
            assertEquals(countBefore, cursor.getInt(0))
            cursor.close()
            reopened.close()

            tempFile.delete()
        }
    }
}
