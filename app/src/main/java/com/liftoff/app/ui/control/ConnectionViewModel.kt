package com.liftoff.app.ui.control

import com.liftoff.app.rho.ConnectionOutcome
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The handshake the action runs, handed in rather than built here: DaemonConnectionCheck is concrete. */
typealias DaemonCheck = suspend () -> ConnectionOutcome

/**
 * The CONNECTION card's state for one "Test connection". A result row stays until the next test,
 * so nothing clears it. [note] is the client's own reason, drawn muted under the row.
 */
sealed interface ConnectionState {
    /** The row's text. Idle has none. */
    val message: String?
    val note: String?

    data object Idle : ConnectionState {
        override val message: String? get() = null
        override val note: String? get() = null
    }

    data object Testing : ConnectionState {
        override val message: String get() = TESTING_MESSAGE
        override val note: String? get() = null
    }

    data class Result(override val message: String, override val note: String? = null) : ConnectionState
}

/**
 * Plain Kotlin state holder for the "Test connection" action. No Android imports, no ViewModel
 * superclass: the handshake arrives as a suspend call and the work runs on an injected dispatcher.
 */
class ConnectionViewModel(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val checkDaemon: DaemonCheck,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    /** The screen's draft, exactly as typed. The fields already save as they are typed, but those
     * writes are async, so this writes them itself and waits before the check aims at them. */
    fun testConnection(host: String, portText: String, portError: String?, token: String) {
        // One test at a time. The card dims the button while Testing; this is the backstop.
        if (_state.value is ConnectionState.Testing) return

        // An invalid port is deliberately never stored, so the check would aim at the last good one.
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            _state.value = ConnectionState.Result(portError ?: PORT_INVALID_MESSAGE)
            return
        }

        val hostDraft = host.trim()
        val tokenDraft = token.trim()
        if (hostDraft.isEmpty() || tokenDraft.isEmpty()) {
            _state.value = ConnectionState.Result(missingSettingsRow(hostDraft, tokenDraft))
            return
        }

        _state.value = ConnectionState.Testing
        scope.launch(dispatcher) {
            try {
                store.setDaemonHost(hostDraft)
                store.setDaemonPort(port)
                store.setDaemonToken(tokenDraft)
                _state.value = rowFor(checkDaemon(), hostDraft, tokenDraft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Result(OFFLINE_MESSAGE, e.message)
            }
        }
    }

    private fun rowFor(outcome: ConnectionOutcome, host: String, token: String): ConnectionState.Result =
        when (outcome) {
            is ConnectionOutcome.Connected -> ConnectionState.Result(
                "Mission Control answered — daemon ${versionOrUnknown(outcome.daemonVersion)}, " +
                    "protocol ${versionOrUnknown(outcome.protocolVersion)}"
            )

            is ConnectionOutcome.ProtocolMismatch -> ConnectionState.Result(mismatchRow(outcome.daemonVersion))

            ConnectionOutcome.AuthenticationRejected -> ConnectionState.Result(REFUSED_TOKEN_MESSAGE)

            // NotConfigured names no field, so the draft says which one was blanked.
            ConnectionOutcome.NotConfigured -> ConnectionState.Result(missingSettingsRow(host, token))

            is ConnectionOutcome.Unreachable -> ConnectionState.Result(OFFLINE_MESSAGE, outcome.reason)
        }
}

private const val TESTING_MESSAGE = "Testing the daemon…"
private const val OFFLINE_MESSAGE = "Mission Control offline — check the PC and Tailscale"
private const val REFUSED_TOKEN_MESSAGE = "Mission Control refused this token — check it matches the PC"
private const val PORT_INVALID_MESSAGE = "Port must be a number from 1 to 65535"
private const val UNKNOWN_VERSION = "unknown"

/** DESIGN 10 compares the major only, so the mismatch row shows majors: the client's 1.0 reads "1.x". */
private val liftoffProtocolMajor: String = majorOf(PROTOCOL_VERSION)?.let { "$it.x" } ?: PROTOCOL_VERSION

private fun mismatchRow(daemonVersion: String): String {
    val daemonMajor = majorOf(daemonVersion)
        ?: return "Liftoff speaks rho protocol $liftoffProtocolMajor, the daemon did not say which version it speaks"
    return "Liftoff speaks rho protocol $liftoffProtocolMajor, the daemon speaks $daemonMajor.x"
}

private fun missingSettingsRow(host: String, token: String): String = when {
    host.isEmpty() && token.isEmpty() -> "No daemon host and no daemon token"
    host.isEmpty() -> "No daemon host — set the PC's address"
    else -> "No daemon token"
}

/** A version nobody named reads as "unknown", never as a blank or a guessed number. */
private fun versionOrUnknown(version: String): String = version.trim().ifEmpty { UNKNOWN_VERSION }

private fun majorOf(version: String): Int? = version.trim().substringBefore('.').toIntOrNull()
