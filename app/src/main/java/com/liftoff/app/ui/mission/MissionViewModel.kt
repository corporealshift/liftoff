package com.liftoff.app.ui.mission

import com.liftoff.app.data.MissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Plain state holder for the Mission tab. */
class MissionViewModel(
    private val db: com.liftoff.app.data.LiftoffDatabase,
    private val manager: MissionManager,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<MissionTabState> = MissionStates(
        db,
        manager,
    ).observe()
        .stateIn(scope, SharingStarted.Lazily, MissionTabState.Loading)

    /** Observe detail for a single sortie. */
    fun observeSortie(sortieId: Long): StateFlow<SortieDetailState> {
        return MissionStates(
            db,
            manager,
        ).observeSortie(sortieId)
            .stateIn(scope, SharingStarted.Lazily, SortieDetailState.Loading)
    }
}
