package com.liftoff.app.ui.inflight

import com.liftoff.app.data.*
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Dispatchers

/** Derivation class: re-reads plan on every Room change and derives state. */
class InFlightStates(
    private val db: LiftoffDatabase,
    private val settings: SettingsStore,
) {
    fun observe(sortieId: Long): Flow<InFlightState> =
        db.flightPlanDao().observeChanges()
            .combine(settings.settings.map { it.weightUnit }.distinctUntilChanged()) { _, unit -> unit }
            .mapNotNull { unit ->
                val sortie = db.sortieDao().get(sortieId) ?: return@mapNotNull null
                deriveInFlight(db.flightPlanDao().getPlan(sortieId), sortie, unit)
            }
            .flowOn(Dispatchers.IO)
}
