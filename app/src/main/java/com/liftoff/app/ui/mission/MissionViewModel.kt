package com.liftoff.app.ui.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.SortieType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Plain state holder for the Mission tab. */
class MissionViewModel(
    private val db: com.liftoff.app.data.LiftoffDatabase,
    private val manager: MissionManager,
    private val scope: CoroutineScope,
    /** Cancels one generation: its work, its row and its rho session. */
    private val cancelGeneration: suspend (Long) -> Unit = {},
    /** Enqueues one generation the manager has already written a row for. */
    private val queueGeneration: suspend (Long) -> Unit = {},
    /**
     * Where Reset week runs. The shell's scope, not this screen's: the owner leaves the tab right after a reset to
     * Confirm the new draft, and that must not stop the cancels half-way and leave the old week's turns running.
     */
    private val actionScope: CoroutineScope = scope,
) {
    val state: StateFlow<MissionTabState> = MissionStates(
        db,
        manager,
    ).observe()
        .stateIn(scope, SharingStarted.Lazily, MissionTabState.Loading)

    /** Whether the Reset week confirmation is showing. */
    var resetting by mutableStateOf(false)
        private set

    /** Whether the Add sortie dialog is showing. */
    var adding by mutableStateOf(false)
        private set

    fun requestReset() {
        if ((state.value as? MissionTabState.Week)?.canReset == true) resetting = true
    }

    fun cancelReset() {
        resetting = false
    }

    /**
     * Start the week over: the Mission and everything recorded in it go, and a new draft takes its place. The
     * week's open generations are cancelled after the delete, so none of them writes into the new draft. Each
     * cancel runs on its own, because stopping a rho session can wait on the daemon and the others shouldn't.
     */
    fun confirmReset(): Job? {
        val s = state.value as? MissionTabState.Week ?: return null
        resetting = false
        return actionScope.launch {
            try {
                for (generationId in manager.resetWeek(s.missionId)) {
                    launch { cancelGeneration(generationId) }
                }
            } catch (_: IllegalStateException) {
                runCatching { manager.onAppOpen() }
            }
        }
    }

    fun requestAdd() {
        if ((state.value as? MissionTabState.Week)?.canAdd == true) adding = true
    }

    fun cancelAdd() {
        adding = false
    }

    /**
     * Add a sortie the week didn't plan (§4.1, amended 2026-11-26): the type's letter on the end of the pattern,
     * and a new day after the week's last one. On the shell's scope like Reset week, because the owner leaves
     * this tab to Launch what was just added and the generation that prepares it must outlive the tab. The
     * generation the manager queued is enqueued only after its transaction has committed.
     */
    fun confirmAdd(type: SortieType): Job? {
        val s = state.value as? MissionTabState.Week ?: return null
        adding = false
        return actionScope.launch {
            try {
                manager.addSortie(s.missionId, type) { generationId -> launch { queueGeneration(generationId) } }
            } catch (_: IllegalStateException) {
                // The week moved under the tap — full, or reset — so re-read it rather than report a failure the
                // owner can't act on.
                runCatching { manager.onAppOpen() }
            }
        }
    }

    /** Observe detail for a single sortie. */
    fun observeSortie(sortieId: Long): StateFlow<SortieDetailState> {
        return MissionStates(
            db,
            manager,
        ).observeSortie(sortieId)
            .stateIn(scope, SharingStarted.Lazily, SortieDetailState.Loading)
    }
}
