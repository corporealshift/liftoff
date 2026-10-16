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
        combine(
            db.flightPlanDao().observeChanges(),
            db.sortieDao().observeById(sortieId),
            settings.settings.map { it.weightUnit }.distinctUntilChanged(),
            settings.settings.map { it.distanceUnit }.distinctUntilChanged(),
        ) { _, sortie, unit, distanceUnit -> Triple(sortie, unit, distanceUnit) }
            .mapNotNull { (sortie, unit, distanceUnit) ->
                sortie ?: return@mapNotNull null
                deriveInFlight(db.flightPlanDao().getPlan(sortieId), sortie, unit, distanceUnit)
            }
            .flowOn(Dispatchers.IO)
}
