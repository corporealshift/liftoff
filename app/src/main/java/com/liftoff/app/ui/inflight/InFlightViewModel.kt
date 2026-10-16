package com.liftoff.app.ui.inflight

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    // Defaulted so the screen and every existing call site keep compiling; a test that wants a
    // fixed clock passes its own manager.
    private val missionManager: MissionManager = MissionManager(db, settings),
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

    // ── Finishing the sortie ─────────────────────────────────────────
    // A second tap while a write is running is ignored, so a double tap cannot land twice.
    private var busy = false

    /** A rejection the screen shows as a short line; null when there is nothing to show. */
    val message = MutableStateFlow<String?>(null)

    fun clearMessage() { message.value = null }

    fun land(onDone: () -> Unit) {
        if (busy) return
        busy = true
        message.value = null
        scope.launch {
            try {
                missionManager.land(sortieId)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Couldn't land this sortie."
            } finally {
                busy = false
            }
        }
    }

    /** Scrub keeps the sets already logged; the reason is optional and normalised like a note. */
    fun scrub(reason: String?, onDone: () -> Unit) {
        if (busy) return
        busy = true
        message.value = null
        scope.launch {
            try {
                missionManager.scrub(sortieId, normalizeNote(reason))
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Couldn't scrub this sortie."
            } finally {
                busy = false
            }
        }
    }

    // Blank text becomes null here so the exercise and sortie rules match and the DAOs stay dumb writes.
    private fun normalizeNote(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
}
