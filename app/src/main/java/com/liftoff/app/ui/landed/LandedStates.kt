package com.liftoff.app.ui.landed

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /**
     * Mirrors MissionStates.observeSortie. observeById re-emits on a note write — the only edit a
     * finished sortie can take — so flightPlanDao().observeChanges() is not combined in here either.
     */
    fun observeDetail(sortieId: Long): Flow<LandedDetail> =
        combine(
            db.sortieDao().observeById(sortieId),
            settingsStore.settings.map { it.weightUnit }.distinctUntilChanged(),
            settingsStore.settings.map { it.distanceUnit }.distinctUntilChanged(),
        ) { sortie, weightUnit, distanceUnit -> Triple(sortie, weightUnit, distanceUnit) }
            .map { (sortie, weightUnit, distanceUnit) ->
                if (sortie == null) LandedDetail.Loading
                else deriveLandedDetail(
                    db.flightPlanDao().getPlan(sortieId),
                    sortie,
                    db.missionDao().get(sortie.missionId),
                    weightUnit,
                    distanceUnit,
                    zone()
                )
            }
            .flowOn(Dispatchers.IO)

    /** Decision 7: a blank saves as null, which is what clears the note. */
    suspend fun saveNote(sortieId: Long, text: String) =
        db.sortieDao().updateNotes(sortieId, text.takeIf { it.isNotBlank() })
}
