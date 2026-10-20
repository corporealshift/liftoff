package com.liftoff.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.rho.ConnectionOutcome
import com.liftoff.app.rho.DaemonConnectionCheck
import com.liftoff.app.rho.FakeDaemon
import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.protocol.RhoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {

    private val app: LiftoffApplication
        get() = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication

    /** The check's own cap plus room for the fake to answer, so a hang fails this test, not the build. */
    private val answerWindow = DaemonConnectionCheck.TIMEOUT_MS + 5_000

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

    /**
     * Persist a host, port and token pointing at the fake daemon: the check the container builds from
     * those stored settings reports connected, with the versions the fake answered.
     *
     * The versions are ones only the fake could have given, and the stored host and token carry
     * padding, so the check can only pass by reading back what was persisted.
     */
    @Test
    fun theContainerBuildsTheConnectionCheckFromTheStoredSettings(): Unit = runBlocking {
        val daemon = FakeDaemon()
        daemon.start()
        try {
            daemon.answerHelloWithProtocol(daemonProtocolVersion = "1.1", daemonVersion = "9.9.9")

            val store = app.container.settingsStore
            store.setDaemonHost("  ${daemon.host()}  ")
            store.setDaemonPort(daemon.port())
            store.setDaemonToken("  sekrit  ")

            val check = app.container.daemonConnectionCheck()
            assertEquals("http://${daemon.host()}:${daemon.port()}/", check.baseUrl)

            val outcome = withTimeout(answerWindow) { check.check() }

            assertTrue("expected Connected, got $outcome", outcome is ConnectionOutcome.Connected)
            val connected = outcome as ConnectionOutcome.Connected
            assertEquals("9.9.9", connected.daemonVersion)
            assertEquals("1.1", connected.protocolVersion)
            assertTrue("the check opened no connection to the fake", daemon.requestCount() > 0)
        } finally {
            daemon.stop()
        }
    }

    /**
     * The client the container builds is rho's own copy, unconnected until the worker connects it,
     * and it greets the daemon as liftoff with the stored token as the bearer header. The stored
     * host and token carry padding, which the copied client would not survive: it sends the token
     * as it is given and puts the host straight into the URL.
     */
    @Test
    fun theContainerBuildsACopiedClientNamedLiftoffCarryingTheStoredToken(): Unit = runBlocking {
        val daemon = FakeDaemon()
        daemon.start()
        try {
            val store = app.container.settingsStore
            store.setDaemonHost("  ${daemon.host()}  ")
            store.setDaemonPort(daemon.port())
            store.setDaemonToken("  sekrit  ")

            val client = app.container.daemonClient()
            try {
                withTimeout(answerWindow) { client.connect() }

                val hello = RhoJson.decodeFromString(Rpc.serializer(), helloFrame(daemon))
                assertEquals("rho.hello", hello.method)
                val params = hello.params?.jsonObject
                assertEquals(
                    DaemonConnectionCheck.CLIENT_NAME,
                    params?.get("client_name")?.jsonPrimitive?.contentOrNull,
                )

                val request = daemon.requests.firstOrNull()
                assertEquals("Bearer sekrit", request?.getHeader("Authorization"))
                assertEquals("/", request?.path)
            } finally {
                // Both ends, or MockWebServer gives up shutting down.
                client.close()
            }
        } finally {
            daemon.stop()
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

    /** The rho.hello frame the fake daemon received. */
    private fun helloFrame(daemon: FakeDaemon): String {
        val frames = daemon.framesFor("rho.hello")
        assertTrue("the fake received no rho.hello; it received ${daemon.frames}", frames.isNotEmpty())
        return frames.first()
    }
}
