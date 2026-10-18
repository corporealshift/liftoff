package com.liftoff.app.ui.landed

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/** The Room reads behind the Landed screen. */
class LandedStates(
    private val db: LiftoffDatabase,
    private val settingsStore: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    /**
     * The sortie table is the only thing that can put a row in or take it out of the history, so
     * flightPlanDao().observeChanges() is deliberately not combined in: it would re-derive the whole
     * list on every set ticked on the In-Flight tab.
     */
    fun observeHistory(): Flow<LandedHistoryState> =
        db.sortieDao().observeHistory()
            .map { deriveHistory(it, db, zone()) }
            .flowOn(Dispatchers.IO)
}
