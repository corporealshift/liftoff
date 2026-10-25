package com.liftoff.app.ui.launchpad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liftoff.app.data.ConfirmOutcome
import com.liftoff.app.data.MissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Confirm and Retry report when Mission Control has no host and token to ask. */
internal const val NOT_CONFIGURED_MESSAGE =
    "Mission Control isn't set up. Add the host and token in Mission Control, or continue without an outline."

/** Plain state holder for the Launchpad screen. */
class LaunchpadViewModel(
    private val db: com.liftoff.app.data.LiftoffDatabase,
    private val manager: MissionManager,
    private val scope: CoroutineScope,
    /** Hands one generation id to the enqueue helper. Asked for at action time, never at construction. */
    private val queueGeneration: (Long) -> Unit,
    /** Hands one generation id to the cancel helper: its work, its row, and its session. */
    private val cancelGeneration: suspend (Long) -> Unit,
) {
    /** Scrub confirmation dialog state. The reason lives in the dialog, not here. */
    var scrubbing by mutableStateOf(false)
        private set

    /** Set by a Confirm or Retry that found Mission Control unconfigured, cleared by the next attempt. */
    var notConfiguredMessage by mutableStateOf<String?>(null)
        private set

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

    /**
     * Ask Mission Control for the week's outline. The id reaches the enqueuer only after manager.confirm
     * has returned, so the work names a row that has already committed.
     */
    fun confirm(): Job? {
        val s = state.value
        if (s !is LaunchpadState.Draft) return null
        return scope.launch {
            notConfiguredMessage = null
            try {
                queueOrReport(manager.confirm(s.missionId))
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    /**
     * Start the week from the pattern the draft holds rather than wait on an outline. Every outline row the
     * manager found still open is cancelled, so a queued generation cannot land on a week already started.
     */
    fun continueWithoutOutline(): Job? {
        val s = state.value
        if (s !is LaunchpadState.Draft) return null
        return scope.launch {
            try {
                for (generationId in manager.continueWithoutOutline(s.missionId)) {
                    cancelGeneration(generationId)
                }
                notConfiguredMessage = null
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    /** Ask Mission Control for the outline again, after the newest one failed. */
    fun retry(): Job? {
        val s = state.value
        if (s !is LaunchpadState.Draft) return null
        return scope.launch {
            try {
                queueOrReport(manager.retryOutline(s.missionId))
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    /** Queued names the row to schedule; CoachNotConfigured is the draft being told nothing was queued. */
    private fun queueOrReport(outcome: ConfirmOutcome) {
        when (outcome) {
            is ConfirmOutcome.Queued -> queueGeneration(outcome.generationId)
            is ConfirmOutcome.CoachNotConfigured -> notConfiguredMessage = NOT_CONFIGURED_MESSAGE
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
    }

    /** The reason arrives from the dialog's Confirm; blank or whitespace-only stores null. */
    fun confirmScrub(reason: String) {
        val s = state.value
        val sortieId = when (s) {
            is LaunchpadState.Planned -> s.sortieId
            is LaunchpadState.Pending -> s.sortieId
            is LaunchpadState.InFlight -> s.sortieId
            else -> return
        }
        scope.launch {
            try {
                manager.scrub(sortieId, reason.trim().takeIf { it.isNotEmpty() })
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            } finally {
                scrubbing = false
            }
        }
    }

    fun cancelScrub() {
        scrubbing = false
    }

    // ── Re-fly ─────────────────────────────────────────────────────────

    /**
     * One tap, no confirmation: the source plan stays in history and this sortie had no plan of its own,
     * so there is nothing to ask about. manager.refly refuses anything but the current PENDING sortie.
     */
    fun refly() {
        val s = state.value
        if (s !is LaunchpadState.Pending) return
        scope.launch {
            try {
                manager.refly(s.sortieId)
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }
}
