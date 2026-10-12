package com.liftoff.app.ui.inflight

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Plain state holder for the In-Flight screen. */
class InFlightViewModel(
    db: LiftoffDatabase,
    settings: SettingsStore,
    private val sortieId: Long,
    private val scope: CoroutineScope,
) {
    private val dao = db.flightPlanDao()
    private val sortieDao = db.sortieDao()

    val state: StateFlow<InFlightState> = InFlightStates(db, settings).observe(sortieId)
        .stateIn(scope, SharingStarted.Lazily, InFlightState.Loading)

    fun check(setId: Long)            { scope.launch { dao.checkSet(setId) } }
    fun uncheck(setId: Long)          { scope.launch { dao.uncheckSet(setId) } }
    fun saveEdit(setId: Long, reps: Int?, seconds: Int?, weight: Double?) {
        scope.launch { dao.saveSetEdit(setId, reps, seconds, weight) }
    }
    fun skipSet(setId: Long)          { scope.launch { dao.skipSet(setId) } }
    fun reopenSet(setId: Long)        { scope.launch { dao.reopenSet(setId) } }
    fun skipExercise(id: Long)        { scope.launch { dao.skipExercise(id) } }
    fun unskipExercise(id: Long)      { scope.launch { dao.unskipExercise(id) } }
    fun addSet(id: Long)              { scope.launch { dao.addSet(id) } }
    fun saveExerciseNote(id: Long, text: String) {
        scope.launch { dao.saveExerciseNote(id, normalizeNote(text)) }
    }
    // Sortie-row-only write, so a sortie with no Flight Plan can carry a note too.
    fun saveSortieNote(text: String) {
        scope.launch { sortieDao.updateNotes(sortieId, normalizeNote(text)) }
    }

    // Blank text becomes null here so the exercise and sortie rules match and the DAOs stay dumb writes.
    private fun normalizeNote(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
}
