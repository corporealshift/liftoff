package com.liftoff.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.rho.DaemonConnectionCheck
import com.liftoff.app.rho.net.DaemonClient
import com.liftoff.app.settings.Settings
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.first

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore: SettingsStore by lazy {
        SettingsStore(PreferenceDataStoreFactory.create {
            appContext.preferencesDataStoreFile("settings")
        })
    }

    val database: LiftoffDatabase by lazy {
        Room.databaseBuilder(appContext, LiftoffDatabase::class.java, "liftoff.db").build()
    }

    val missionManager: com.liftoff.app.data.MissionManager by lazy {
        com.liftoff.app.data.MissionManager(database, settingsStore)
    }

    /**
     * The daemon's settings, host and token trimmed, read at the moment a caller asks so a settings
     * change takes effect next time. Trimmed here rather than per caller, so the check and the
     * client cannot end up aiming at different daemons.
     */
    private suspend fun daemonSettings(): Settings {
        val settings = settingsStore.settings.first()
        return settings.copy(
            daemonHost = settings.daemonHost.trim(),
            daemonToken = settings.daemonToken.trim(),
        )
    }

    /** "Is the coach there, and does it speak our protocol?" Built fresh, and nothing is left connected. */
    suspend fun daemonConnectionCheck(): DaemonConnectionCheck {
        val settings = daemonSettings()
        return DaemonConnectionCheck(
            daemonHost = settings.daemonHost,
            port = settings.daemonPort,
            daemonToken = settings.daemonToken,
        )
    }

    /**
     * An unconnected copy of rho's client, in rho's URL form, for the generation worker to connect
     * and drive. The container holds no connection, so whoever opens one owns closing it.
     */
    suspend fun daemonClient(): DaemonClient {
        val settings = daemonSettings()
        return DaemonClient(
            baseUrl = "http://${settings.daemonHost}:${settings.daemonPort}/",
            token = settings.daemonToken,
            clientName = DaemonConnectionCheck.CLIENT_NAME,
        )
    }
}
