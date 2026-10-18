package com.liftoff.app.ui.inflight

import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Plain signal: the sortie this screen is showing is gone or no longer in flight. */
fun sortieIsNoLongerInFlight(sortie: Sortie?): Boolean =
    sortie == null || sortie.state != SortieState.IN_FLIGHT

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

    // ── Leaving the screen ───────────────────────────────────────────
    // The state flow cannot report a gone sortie — mapNotNull drops a null one and deriveInFlight never
    // reads sortie.state — so the screen watches the sortie row itself.
    // left is atomic because the watcher emits on Room's query thread and a Land resumes on its
    // transaction thread.
    private val left = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun leaveOnce(onLeave: () -> Unit) {
        if (left.compareAndSet(false, true)) onLeave()
    }

    /** Watch this sortie; the first moment it is gone or no longer IN_FLIGHT, leave — once. */
    fun watchLeave(onLeave: () -> Unit) {
        scope.launch {
            sortieDao.observeById(sortieId)
                .map(::sortieIsNoLongerInFlight)
                .collect { gone -> if (gone) leaveOnce(onLeave) }
        }
    }

    // Once this screen has left its sortie, no action starts a write to it.
    fun check(setId: Long)            { if (left.get()) return; scope.launch { dao.checkSet(setId) } }
    fun uncheck(setId: Long)          { if (left.get()) return; scope.launch { dao.uncheckSet(setId) } }
    fun saveEdit(setId: Long, reps: Int?, seconds: Int?, weight: Double?) {
        if (left.get()) return
        scope.launch { dao.saveSetEdit(setId, reps, seconds, weight) }
    }
    fun skipSet(setId: Long)          { if (left.get()) return; scope.launch { dao.skipSet(setId) } }
    fun reopenSet(setId: Long)        { if (left.get()) return; scope.launch { dao.reopenSet(setId) } }
    fun skipExercise(id: Long)        { if (left.get()) return; scope.launch { dao.skipExercise(id) } }
    fun unskipExercise(id: Long)      { if (left.get()) return; scope.launch { dao.unskipExercise(id) } }
    fun addSet(id: Long)              { if (left.get()) return; scope.launch { dao.addSet(id) } }
    fun saveExerciseNote(id: Long, text: String) {
        if (left.get()) return
        scope.launch { dao.saveExerciseNote(id, normalizeNote(text)) }
    }
    // Sortie-row-only write, so a sortie with no Flight Plan can carry a note too.
    fun saveSortieNote(text: String) {
        if (left.get()) return
        scope.launch { sortieDao.updateNotes(sortieId, normalizeNote(text)) }
    }

    // ── Finishing the sortie ─────────────────────────────────────────
    // A second tap while a write is running is ignored, so a double tap cannot land twice.
    private var busy = false

    /** A rejection the screen shows as a short line; null when there is nothing to show. */
    val message = MutableStateFlow<String?>(null)

    fun clearMessage() { message.value = null }

    fun land(onDone: () -> Unit) {
        if (left.get()) return
        if (busy) return
        busy = true
        message.value = null
        scope.launch {
            try {
                missionManager.land(sortieId)
                leaveOnce(onDone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Couldn't land this sortie."
            } finally {
                busy = false
            }
        }
    }

    /** What the Land dialog must show on a field; null when nothing was rejected. */
    val runErrors = MutableStateFlow<RunEntry.Invalid?>(null)

    fun clearRunErrors() { runErrors.value = null }

    /**
     * A run's Land carries its own entry, so it is parsed before anything is written: a rejected
     * field lands nothing. The return tells the dialog whether to close (Decision 16).
     */
    fun landRun(distanceText: String?, minutesText: String?, onDone: () -> Unit): Boolean {
        if (left.get()) return false
        if (busy) return false
        return when (val entry = parseRunEntry(distanceText, minutesText)) {
            is RunEntry.Invalid -> {
                runErrors.value = entry
                false
            }

            is RunEntry.Valid -> {
                runErrors.value = null
                busy = true
                message.value = null
                scope.launch {
                    try {
                        missionManager.land(sortieId, entry.distance, entry.minutes)
                        leaveOnce(onDone)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message.value = "Couldn't land this sortie."
                    } finally {
                        busy = false
                    }
                }
                true
            }
        }
    }

    /** Scrub keeps the sets already logged; the reason is optional and normalised like a note. */
    fun scrub(reason: String?, onDone: () -> Unit) {
        if (left.get()) return
        if (busy) return
        busy = true
        message.value = null
        scope.launch {
            try {
                missionManager.scrub(sortieId, normalizeNote(reason))
                leaveOnce(onDone)
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
