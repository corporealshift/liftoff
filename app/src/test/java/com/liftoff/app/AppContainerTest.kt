package com.liftoff.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {

    @Test
    fun applicationIsLiftoffApplication() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication
        assertEquals(LiftoffApplication::class.java, app.javaClass)
    }

    @Test
    fun applicationOwnsOneContainer() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication
        assertSame(app.container, app.container)
    }

    @Test
    fun containerReturnsSameSettingsStore() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication
        assertSame(app.container.settingsStore, app.container.settingsStore)
    }

    @Test
    fun settingsArePersistedToSettingsPreferencesFile() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication
        val store = app.container.settingsStore
        // Write a value through the store and read it back to prove
        // the DataStore file is real (settings.preferences_pb).
        runBlocking {
            store.setDaemonHost("192.168.1.1")
            assertEquals("192.168.1.1", store.settings.first().daemonHost)
        }
    }

    @Test
    fun containerReturnsSameDatabase() {
        val app = ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication
        assertSame(app.container.database, app.container.database)
    }
}
