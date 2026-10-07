package com.liftoff.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.liftoff.app.settings.SettingsStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore: SettingsStore by lazy {
        SettingsStore(PreferenceDataStoreFactory.create {
            appContext.preferencesDataStoreFile("settings")
        })
    }
}
