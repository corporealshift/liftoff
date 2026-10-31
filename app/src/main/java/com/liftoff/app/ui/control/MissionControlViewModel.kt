package com.liftoff.app.ui.control

import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.settings.WeightUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI state for the Mission Control screen. */
data class MissionControlState(
    val loaded: Boolean = false,
    val host: String = "",
    val portText: String = "",
    val token: String = "",
    val workspacePath: String = "",
    val pattern: String = "RLRLR",
    val sortieLengthText: String = "",
    val historyWindowText: String = "",
    val weightUnit: WeightUnit = WeightUnit.LB,
    val distanceUnit: DistanceUnit = DistanceUnit.MI,
    val generateRunPlans: Boolean = false,
    val objectives: String = "",
    val constraints: String = "",
    val tokenVisible: Boolean = false,
    val portError: String? = null,
    val sortieLengthError: String? = null,
    val historyWindowError: String? = null,
    val patternError: String? = null
) {
    val canAddChip: Boolean get() = pattern.length < 7
    val canRemoveChip: Boolean get() = pattern.length > 1
}

/**
 * Plain Kotlin state holder for Mission Control settings.
 * No Android imports, no ViewModel superclass. Wraps SettingsStore,
 * holds a draft of every field, validates input and writes valid values to the store.
 */
class MissionControlViewModel(
    private val store: SettingsStore,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(MissionControlState())
    val state: StateFlow<MissionControlState> = _state

    init {
        scope.launch {
            try {
                val settings = store.settings.first()
                _state.value = MissionControlState(
                    loaded = true,
                    host = settings.daemonHost,
                    portText = settings.daemonPort.toString(),
                    token = settings.daemonToken,
                    workspacePath = settings.coachWorkspacePath,
                    pattern = settings.defaultPattern,
                    sortieLengthText = settings.sortieLengthMinutes.toString(),
                    historyWindowText = settings.historyWindowDays.toString(),
                    weightUnit = settings.weightUnit,
                    distanceUnit = settings.distanceUnit,
                    generateRunPlans = settings.generateRunPlans,
                    objectives = settings.objectives,
                    constraints = settings.constraints
                )
            } catch (e: java.io.IOException) {
                // Disk I/O failure during init — still mark loaded so the UI can render.
                _state.value = _state.value.copy(loaded = true)
            }
        }
    }

    // --- Free text fields (stored verbatim) ---

    fun setHost(value: String) {
        _state.value = _state.value.copy(host = value)
        scope.launch { store.setDaemonHost(value) }
    }

    fun setToken(value: String) {
        _state.value = _state.value.copy(token = value)
        scope.launch { store.setDaemonToken(value) }
    }

    fun setWorkspacePath(value: String) {
        _state.value = _state.value.copy(workspacePath = value)
        scope.launch { store.setCoachWorkspacePath(value) }
    }

    fun setObjectives(value: String) {
        _state.value = _state.value.copy(objectives = value)
        scope.launch { store.setObjectives(value) }
    }

    fun setConstraints(value: String) {
        _state.value = _state.value.copy(constraints = value)
        scope.launch { store.setConstraints(value) }
    }

    // --- Port ---

    fun setPortText(raw: String) {
        val trimmed = raw.trim()
        val intValue = trimmed.toIntOrNull()
        if (intValue != null && intValue in 1..65535) {
            _state.value = _state.value.copy(portText = trimmed, portError = null)
            scope.launch { store.setDaemonPort(intValue) }
        } else {
            _state.value = _state.value.copy(
                portText = trimmed,
                portError = "Port must be a number from 1 to 65535"
            )
        }
    }

    // --- Sortie length ---

    fun setSortieLengthText(raw: String) {
        val trimmed = raw.trim()
        val intValue = trimmed.toIntOrNull()
        if (intValue != null && intValue > 0) {
            _state.value = _state.value.copy(sortieLengthText = trimmed, sortieLengthError = null)
            scope.launch { store.setSortieLengthMinutes(intValue) }
        } else {
            _state.value = _state.value.copy(
                sortieLengthText = trimmed,
                sortieLengthError = "Sortie length must be a whole number of minutes, above 0"
            )
        }
    }

    // --- History window ---

    fun setHistoryWindowText(raw: String) {
        val trimmed = raw.trim()
        val intValue = trimmed.toIntOrNull()
        if (intValue != null && intValue > 0) {
            _state.value = _state.value.copy(historyWindowText = trimmed, historyWindowError = null)
            scope.launch { store.setHistoryWindowDays(intValue) }
        } else {
            _state.value = _state.value.copy(
                historyWindowText = trimmed,
                historyWindowError = "History window must be a whole number of days, above 0"
            )
        }
    }

    // --- Pattern chips ---

    fun togglePatternChip(index: Int) {
        val p = _state.value.pattern
        if (index !in p.indices) return
        val chars = p.toCharArray()
        chars[index] = if (chars[index] == 'R') 'L' else 'R'
        val newPattern = chars.concatToString()
        _state.value = _state.value.copy(pattern = newPattern, patternError = null)
        scope.launch { store.setDefaultPattern(newPattern) }
    }

    fun addPatternChip() {
        if (_state.value.pattern.length >= 7) {
            _state.value = _state.value.copy(
                patternError = "A pattern has 1 to 7 sorties"
            )
            return
        }
        val newPattern = _state.value.pattern + 'R'
        _state.value = _state.value.copy(pattern = newPattern, patternError = null)
        scope.launch { store.setDefaultPattern(newPattern) }
    }

    fun removePatternChip() {
        if (_state.value.pattern.length <= 1) {
            _state.value = _state.value.copy(
                patternError = "A pattern has 1 to 7 sorties"
            )
            return
        }
        val newPattern = _state.value.pattern.dropLast(1)
        _state.value = _state.value.copy(pattern = newPattern, patternError = null)
        scope.launch { store.setDefaultPattern(newPattern) }
    }

    // --- Enums ---

    fun setWeightUnit(value: WeightUnit) {
        _state.value = _state.value.copy(weightUnit = value)
        scope.launch { store.setWeightUnit(value) }
    }

    fun setDistanceUnit(value: DistanceUnit) {
        _state.value = _state.value.copy(distanceUnit = value)
        scope.launch { store.setDistanceUnit(value) }
    }

    // --- Toggle ---

    fun setGenerateRunPlans(value: Boolean) {
        _state.value = _state.value.copy(generateRunPlans = value)
        scope.launch { store.setGenerateRunPlans(value) }
    }

    // --- Token visibility ---

    fun toggleTokenVisibility() {
        _state.value = _state.value.copy(tokenVisible = !_state.value.tokenVisible)
    }
}
