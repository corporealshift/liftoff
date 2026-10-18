package com.liftoff.app.ui.landed

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Plain state holder for the Landed tab. */
class LandedViewModel(
    db: LiftoffDatabase,
    settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val states = LandedStates(db, settingsStore)

    val history: StateFlow<LandedHistoryState> = states
        .observeHistory()
        .stateIn(scope, SharingStarted.Lazily, LandedHistoryState.Loading)

    /** Observe the detail of one finished sortie. */
    fun observeDetail(sortieId: Long): StateFlow<LandedDetail> =
        states.observeDetail(sortieId)
            .stateIn(scope, SharingStarted.Lazily, LandedDetail.Loading)

    fun saveNote(sortieId: Long, text: String) {
        scope.launch { states.saveNote(sortieId, text) }
    }
}
