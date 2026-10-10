package com.liftoff.app.ui.launchpad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liftoff.app.data.MissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Plain state holder for the Launchpad screen. */
class LaunchpadViewModel(
    private val db: com.liftoff.app.data.LiftoffDatabase,
    private val manager: MissionManager,
    private val scope: CoroutineScope,
) {
    /** Scrub confirmation dialog state. */
    var scrubbing by mutableStateOf(false)
        private set
    private var _scrubReason by mutableStateOf("")
    val scrubReason: String get() = _scrubReason

    val state: StateFlow<LaunchpadState> = LaunchpadStates(
        db,
        manager,
    ).observe()
        .stateIn(scope, SharingStarted.Lazily, LaunchpadState.Loading)

    // ── Pattern editing (Draft state) ────────────────────────────────

    fun toggleChip(index: Int) {
        val s = state.value
        if (s !is LaunchpadState.Draft) return
        scope.launch {
            try {
                val pattern = s.pattern.mapIndexed { i, ch ->
                    if (i == index) if (ch == 'R') 'L' else 'R' else ch
                }.joinToString("")
                manager.setPattern(s.missionId, pattern)
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    fun addChip() {
        val s = state.value
        if (s !is LaunchpadState.Draft || s.pattern.length >= 7) return
        scope.launch {
            try {
                val pattern = s.pattern + 'R' // append R
                manager.setPattern(s.missionId, pattern)
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    fun removeChip() {
        val s = state.value
        if (s !is LaunchpadState.Draft || s.pattern.length <= 1) return
        scope.launch {
            try {
                val pattern = s.pattern.dropLast(1)
                manager.setPattern(s.missionId, pattern)
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    fun confirm() {
        val s = state.value
        if (s !is LaunchpadState.Draft) return
        scope.launch {
            try {
                manager.confirm(s.missionId)
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    // ── Launch ───────────────────────────────────────────────────────

    fun launch(onLaunched: (Long) -> Unit) {
        val s = state.value
        val sortieId = when (s) {
            is LaunchpadState.Planned -> s.sortieId
            is LaunchpadState.InFlight -> s.sortieId
            else -> return
        }
        scope.launch {
            try {
                if (s is LaunchpadState.InFlight) {
                    onLaunched(sortieId)
                } else {
                    manager.launch(sortieId)
                    onLaunched(sortieId)
                }
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    // ── Scrub ────────────────────────────────────────────────────────

    fun requestScrub() {
        scrubbing = true
        _scrubReason = ""
    }

    fun setScrubReason(reason: String) {
        _scrubReason = reason
    }

    fun confirmScrub() {
        val s = state.value
        val sortieId = when (s) {
            is LaunchpadState.Planned -> s.sortieId
            is LaunchpadState.Pending -> s.sortieId
            is LaunchpadState.InFlight -> s.sortieId
            else -> return
        }
        scope.launch {
            try {
                manager.scrub(sortieId, _scrubReason.takeIf { it.isNotBlank() })
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            } finally {
                scrubbing = false
            }
        }
    }

    fun cancelScrub() {
        scrubbing = false
        _scrubReason = ""
    }
}
