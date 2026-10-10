package com.liftoff.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.settings.SettingsStore

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
}
