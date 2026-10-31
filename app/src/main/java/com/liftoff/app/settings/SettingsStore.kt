package com.liftoff.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object SettingsKeys {
    val DAEMON_HOST = stringPreferencesKey("daemon_host")
    val DAEMON_PORT = intPreferencesKey("daemon_port")
    val DAEMON_TOKEN = stringPreferencesKey("daemon_token")
    val COACH_WORKSPACE_PATH = stringPreferencesKey("coach_workspace_path")
    val DEFAULT_PATTERN = stringPreferencesKey("default_pattern")
    val SORTIE_LENGTH_MINUTES = intPreferencesKey("sortie_length_minutes")
    val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
    val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    val HISTORY_WINDOW_DAYS = intPreferencesKey("history_window_days")
    val GENERATE_RUN_PLANS = booleanPreferencesKey("generate_run_plans")
    val OBJECTIVES = stringPreferencesKey("objectives")
    val CONSTRAINTS = stringPreferencesKey("constraints")
}

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> = dataStore.data.map { prefs ->
        Settings(
            daemonHost = prefs[SettingsKeys.DAEMON_HOST] ?: "",
            daemonPort = prefs[SettingsKeys.DAEMON_PORT] ?: 8737,
            daemonToken = prefs[SettingsKeys.DAEMON_TOKEN] ?: "",
            coachWorkspacePath = prefs[SettingsKeys.COACH_WORKSPACE_PATH] ?: "",
            defaultPattern = prefs[SettingsKeys.DEFAULT_PATTERN] ?: "RLRLR",
            sortieLengthMinutes = prefs[SettingsKeys.SORTIE_LENGTH_MINUTES] ?: 60,
            weightUnit = WeightUnit.entries.firstOrNull { it.name == prefs[SettingsKeys.WEIGHT_UNIT] } ?: WeightUnit.LB,
            distanceUnit = DistanceUnit.entries.firstOrNull { it.name == prefs[SettingsKeys.DISTANCE_UNIT] } ?: DistanceUnit.MI,
            historyWindowDays = prefs[SettingsKeys.HISTORY_WINDOW_DAYS] ?: 28,
            generateRunPlans = prefs[SettingsKeys.GENERATE_RUN_PLANS] ?: false,
            objectives = prefs[SettingsKeys.OBJECTIVES] ?: "",
            constraints = prefs[SettingsKeys.CONSTRAINTS] ?: ""
        )
    }

    suspend fun setDaemonHost(value: String) {
        dataStore.edit { it[SettingsKeys.DAEMON_HOST] = value }
    }

    suspend fun setDaemonPort(value: Int) {
        require(value in 1..65535) { "daemon port must be 1–65535, got $value" }
        dataStore.edit { it[SettingsKeys.DAEMON_PORT] = value }
    }

    suspend fun setDaemonToken(value: String) {
        dataStore.edit { it[SettingsKeys.DAEMON_TOKEN] = value }
    }

    suspend fun setCoachWorkspacePath(value: String) {
        dataStore.edit { it[SettingsKeys.COACH_WORKSPACE_PATH] = value }
    }

    suspend fun setDefaultPattern(value: String) {
        require(value.length in 1..7 && value.all { it == 'R' || it == 'L' }) {
            "default pattern must be 1–7 characters of R and L, got '$value'"
        }
        dataStore.edit { it[SettingsKeys.DEFAULT_PATTERN] = value }
    }

    suspend fun setSortieLengthMinutes(value: Int) {
        require(value > 0) { "sortie length must be positive, got $value" }
        dataStore.edit { it[SettingsKeys.SORTIE_LENGTH_MINUTES] = value }
    }

    suspend fun setWeightUnit(value: WeightUnit) {
        dataStore.edit { it[SettingsKeys.WEIGHT_UNIT] = value.name }
    }

    suspend fun setDistanceUnit(value: DistanceUnit) {
        dataStore.edit { it[SettingsKeys.DISTANCE_UNIT] = value.name }
    }

    suspend fun setHistoryWindowDays(value: Int) {
        require(value > 0) { "history window must be positive, got $value" }
        dataStore.edit { it[SettingsKeys.HISTORY_WINDOW_DAYS] = value }
    }

    suspend fun setGenerateRunPlans(value: Boolean) {
        dataStore.edit { it[SettingsKeys.GENERATE_RUN_PLANS] = value }
    }

    suspend fun setObjectives(value: String) {
        dataStore.edit { it[SettingsKeys.OBJECTIVES] = value }
    }

    suspend fun setConstraints(value: String) {
        dataStore.edit { it[SettingsKeys.CONSTRAINTS] = value }
    }
}
