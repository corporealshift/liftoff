package com.liftoff.app.ui.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liftoff.app.data.MissionManager
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
) {
    val state: StateFlow<MissionTabState> = MissionStates(
        db,
        manager,
    ).observe()
        .stateIn(scope, SharingStarted.Lazily, MissionTabState.Loading)

    /** Whether the Reset week confirmation is showing. */
    var resetting by mutableStateOf(false)
        private set

    fun requestReset() {
        if ((state.value as? MissionTabState.Week)?.canReset == true) resetting = true
    }

    fun cancelReset() {
        resetting = false
    }

    /**
     * Start the week over: the Mission and everything recorded in it go, and a new draft takes its place. The
     * week's open generations are cancelled after the delete, so none of them writes into the new draft.
     */
    fun confirmReset(): Job? {
        val s = state.value as? MissionTabState.Week ?: return null
        resetting = false
        return scope.launch {
            try {
                for (generationId in manager.resetWeek(s.missionId)) {
                    cancelGeneration(generationId)
                }
            } catch (_: IllegalStateException) {
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
