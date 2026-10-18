package com.liftoff.app.ui.landed

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Plain state holder for the Landed tab. */
class LandedViewModel(
    db: LiftoffDatabase,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
) {
    val history: StateFlow<LandedHistoryState> = LandedStates(db, settingsStore)
        .observeHistory()
        .stateIn(scope, SharingStarted.Lazily, LandedHistoryState.Loading)
}
