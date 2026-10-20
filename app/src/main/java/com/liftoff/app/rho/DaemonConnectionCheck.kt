package com.liftoff.app.rho

import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.net.RpcError
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.rho.protocol.RhoJson
import com.liftoff.app.rho.protocol.RpcCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** What asking the daemon "are you there, and can we speak to you?" answers. */
sealed interface ConnectionOutcome {
    /** The handshake went through, with both versions exactly as the daemon answered them. */
    data class Connected(val daemonVersion: String, val protocolVersion: String) : ConnectionOutcome

    /** No host or no token, so no socket was opened at all. */
    data object NotConfigured : ConnectionOutcome

    /** Nothing is listening, or nothing answered in time. */
    data class Unreachable(val reason: String) : ConnectionOutcome

    /** The daemon refused the upgrade over the token. */
    data object AuthenticationRejected : ConnectionOutcome

    /**
     * A protocol major version Liftoff does not speak (DESIGN 10). Both versions are named; a
     * refusal carries the daemon's only in its [message], which is kept as it was sent.
     */
    data class ProtocolMismatch(
        val expectedVersion: String,
        val daemonVersion: String,
        val message: String,
    ) : ConnectionOutcome
}

/**
 * One handshake with the daemon, then the socket is hung up.
 *
 * The handshake is done here rather than through [com.liftoff.app.rho.net.DaemonClient].connect(),
 * because that throws the hello's reply away and the versions in that reply are the whole answer.
 * The envelopes and codes come from the copied [Rpc], [RhoJson], [PROTOCOL_VERSION] and [RpcCodes].
 */
class DaemonConnectionCheck(
    daemonHost: String,
    port: Int,
    daemonToken: String,
    private val clientVersion: String = CLIENT_VERSION,
    private val http: OkHttpClient = checkHttp(),
) {
    companion object {
        /** What the daemon logs as the client's name (spec 1.1). */
        const val CLIENT_NAME = "liftoff"

        /** Mirrors versionName in app/build.gradle.kts. */
        const val CLIENT_VERSION = "0.1"

        /** An "is the coach there?" question answers inside 10 seconds, or not at all. */
        const val TIMEOUT_MS = 10_000L

        private const val HELLO_ID = "1"

        /** The check's own client: no pings for a one-shot call, and a connect shorter than the copied client's 15 s. */
        fun checkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val host = daemonHost.trim()
    private val token = daemonToken.trim()

    /** rho's URL form, so the check and the copied client aim at the same daemon. */
    val baseUrl: String = "http://$host:$port/"

    suspend fun check(): ConnectionOutcome {
        // A blank token would still be let through by a daemon on the same machine, so it means
        // setup is unfinished rather than "try without a token".
        if (host.isEmpty() || token.isEmpty()) return ConnectionOutcome.NotConfigured

        val done = CompletableDeferred<ConnectionOutcome>()
        val request = Request.Builder()
            .url(baseUrl.replace(Regex("^http"), "ws"))
            .header("Authorization", "Bearer $token")
            .build()
        val socket = http.newWebSocket(request, Listener(done))

        var timedOut = false
        return try {
            withTimeout(TIMEOUT_MS) { done.await() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
            ConnectionOutcome.Unreachable("no answer within ${TIMEOUT_MS / 1000} seconds")
        } finally {
            // The answer is the point of the call; nothing here keeps the connection open.
            if (timedOut) socket.cancel() else socket.close(1000, null)
        }
    }

    private inner class Listener(private val done: CompletableDeferred<ConnectionOutcome>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = Rpc(
                id = JsonPrimitive(HELLO_ID),
                method = "rho.hello",
                params = buildJsonObject {
                    put("client", CLIENT_NAME)
                    put("client_version", clientVersion)
                    put("protocol_version", PROTOCOL_VERSION)
                },
            )
            if (!webSocket.send(RhoJson.encodeToString(Rpc.serializer(), hello))) {
                done.complete(ConnectionOutcome.Unreachable("the socket would not take the hello"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { RhoJson.decodeFromString(Rpc.serializer(), text) }.getOrNull() ?: return
            if (msg.method != null || msg.id?.jsonPrimitive?.contentOrNull != HELLO_ID) return
            val error = msg.error
            if (error != null) {
                done.complete(refused(error))
                return
            }
            val result = msg.result?.jsonObject ?: JsonObject(emptyMap())
            val daemonProtocol = textField(result, "protocol_version")
            val daemonVersion = textField(result, "daemon_version")
            done.complete(
                if (majorOf(daemonProtocol) != null && majorOf(daemonProtocol) == majorOf(PROTOCOL_VERSION)) {
                    ConnectionOutcome.Connected(daemonVersion, daemonProtocol)
                } else {
                    ConnectionOutcome.ProtocolMismatch(
                        expectedVersion = PROTOCOL_VERSION,
                        daemonVersion = daemonProtocol,
                        message = "the daemon answered protocol_version \"$daemonProtocol\"",
                    )
                }
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // The daemon answers a bad token with 401 at the upgrade, before any JSON-RPC, and
            // OkHttp hands that here rather than as an RPC error.
            if (response?.code == 401) done.complete(ConnectionOutcome.AuthenticationRejected)
            else done.complete(ConnectionOutcome.Unreachable(t.message ?: "the connection failed"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(reason)
        }

        private fun dropped(reason: String) {
            done.complete(ConnectionOutcome.Unreachable(
                if (reason.isEmpty()) "the daemon closed the connection" else reason,
            ))
        }

        private fun refused(error: RpcError): ConnectionOutcome = when (error.code) {
            RpcCodes.PROTOCOL_MISMATCH -> ConnectionOutcome.ProtocolMismatch(
                expectedVersion = PROTOCOL_VERSION,
                daemonVersion = daemonVersionIn(error.message),
                message = error.message,
            )

            RpcCodes.UNAUTHORIZED -> ConnectionOutcome.AuthenticationRejected

            // Any other refusal at the hello is neither a mismatch nor an auth failure, so it
            // goes with the daemon's own words rather than being dressed up as one of them.
            else -> ConnectionOutcome.Unreachable("the daemon refused the hello: ${error.message}")
        }
    }

    /** The daemon's refusal names its version only in the message text. */
    private fun daemonVersionIn(message: String): String =
        Regex("""daemon\s+([0-9]+(?:\.[0-9]+)*)""").find(message)?.groupValues?.get(1) ?: ""

    /** DESIGN 10: only the MAJOR has to match; a differing MINOR is fine. */
    private fun majorOf(version: String): Int? = version.trim().substringBefore('.').toIntOrNull()

    private fun textField(o: JsonObject, key: String): String =
        (o[key] as? JsonPrimitive)?.contentOrNull ?: ""
}
