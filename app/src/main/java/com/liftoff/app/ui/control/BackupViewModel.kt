package com.liftoff.app.ui.control

import com.liftoff.app.data.ImportResult
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.exportDatabase
import com.liftoff.app.data.importDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate

/** Which backup action is running. Its two strings are the rows the card draws for it. */
enum class BackupAction(val busyMessage: String, val failureMessage: String) {
    Export("Exporting…", "Export failed — the file could not be written."),
    Import("Importing…", "Import failed — the file could not be read.")
}

/** The BACKUP card's state. A result row stays until the next action, so nothing clears it. */
sealed interface BackupState {
    /** The row's text. Idle has none. */
    val message: String?

    data object Idle : BackupState {
        override val message: String? get() = null
    }

    data class Busy(val action: BackupAction) : BackupState {
        override val message: String get() = action.busyMessage
    }

    data class Succeeded(override val message: String) : BackupState

    data class Failed(override val message: String) : BackupState
}

/** Opens the file the picker chose. A null stream is a provider that would not hand one over. */
typealias BackupStreamOpener<S> = suspend () -> S?

/**
 * Plain Kotlin state holder for the Export and Import actions. No Android imports, no ViewModel
 * superclass: the data calls arrive as lambdas and the work runs on an injected dispatcher.
 */
class BackupViewModel(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val exportWork: suspend (OutputStream) -> Unit,
    private val importWork: suspend (InputStream) -> ImportResult
) {

    /** The app's own wiring: the real data layer, against the database the other tabs observe. */
    constructor(
        database: LiftoffDatabase,
        missionManager: MissionManager,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        scope = scope,
        dispatcher = dispatcher,
        exportWork = { out -> exportDatabase(database, out) },
        importWork = { input -> importDatabase(database, input, missionManager) }
    )

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state

    fun runExport(opener: BackupStreamOpener<OutputStream>?) {
        runBackup(BackupAction.Export, opener) { stream ->
            exportWork(stream)
            BackupState.Succeeded(EXPORTED_MESSAGE)
        }
    }

    fun runImport(opener: BackupStreamOpener<InputStream>?) {
        runBackup(BackupAction.Import, opener) { stream ->
            when (val result = importWork(stream)) {
                // The data layer's own sentence, unchanged.
                is ImportResult.Refused -> BackupState.Failed(result.message)
                is ImportResult.Restored -> BackupState.Succeeded(restoredRow(result.missions, result.sorties))
            }
        }
    }

    private fun <S : Closeable> runBackup(
        action: BackupAction,
        opener: BackupStreamOpener<S>?,
        work: suspend (S) -> BackupState
    ) {
        // A cancelled picker is not an action, so it does not touch the state at all.
        if (opener == null) return
        // One backup at a time. The card dims both buttons while Busy; this is the backstop.
        if (_state.value is BackupState.Busy) return

        _state.value = BackupState.Busy(action)
        scope.launch(dispatcher) {
            try {
                val stream = opener() ?: throw IOException("${action.name} produced no stream")
                stream.use { _state.value = work(stream) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BackupState.Failed(action.failureMessage)
            }
        }
    }

    companion object {
        /** The name Export suggests for the file the picker creates: today on the device's own zone. */
        fun defaultExportFileName(clock: Clock = Clock.systemDefaultZone()): String =
            "liftoff-${LocalDate.now(clock)}.json"
    }
}

private const val EXPORTED_MESSAGE = "Exported the whole database."

private fun restoredRow(missions: Int, sorties: Int): String =
    "Restored ${counted(missions, "mission")} and ${counted(sorties, "sortie")}."

private fun counted(count: Int, singular: String): String =
    if (count == 1) "1 $singular" else "$count ${singular}s"
