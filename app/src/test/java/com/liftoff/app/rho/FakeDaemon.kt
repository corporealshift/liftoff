package com.liftoff.app.rho

import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.rho.protocol.RhoJson
import com.liftoff.app.rho.protocol.RpcCodes
import kotlinx.serialization.json.JsonElement
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A stand-in daemon for [DaemonConnectionCheck]'s tests, modelled on rho's own
 * `clients/android/.../testing/FakeDaemon.kt`.
 *
 * It answers through a [Dispatcher] rather than an enqueued response, because MockWebServer's
 * default queue serves one enqueued response per connection and a second connection then waits
 * forever for a handshake nothing will answer.
 */
class FakeDaemon {
    val server = MockWebServer()

    /** Every frame the client sent, in the order it arrived. */
    val frames = ConcurrentLinkedQueue<String>()

    /** Every request this server saw, headers included. */
    val requests = ConcurrentLinkedQueue<RecordedRequest>()

    /** The daemon's end of every connection it accepted, so teardown can close them all. */
    val sockets = ConcurrentLinkedQueue<WebSocket>()

    /** What rho.hello answers, as the raw `result` object, in the shape daemon/api/transport.go uses. */
    @Volatile var helloResult: String =
        """{"daemon_version":"0.0.0","protocol_version":"$PROTOCOL_VERSION","capabilities":["delta","tasks","goal"]}"""

    /** Methods this daemon turns down, keyed by method name, with the code and message it answers with. */
    val refuseMethod = ConcurrentHashMap<String, Pair<Int, String>>()

    /** Refuse the upgrade itself, as the real daemon does for a bearer token it doesn't accept. */
    @Volatile var refuseUpgrade = false

    private var stopped = false

    fun start() {
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sockets.add(ws)
            }

            /**
             * A close is answered with a close, as a real daemon does. OkHttp's server side does not
             * echo one on its own, so a connection whose close goes unanswered keeps its socket open
             * and MockWebServer then gives up shutting down.
             */
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                frames.add(text)
                val msg = runCatching { RhoJson.decodeFromString(Rpc.serializer(), text) }.getOrNull() ?: return
                val id = msg.id ?: return
                val method = msg.method ?: return
                val refusal = refuseMethod[method]
                if (refusal != null) ws.send(errorFor(id, refusal.first, refusal.second))
                else ws.send(resultFor(id, method))
            }
        }

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                if (refuseUpgrade) {
                    // Disconnecting at the end is what keeps the refused upgrade out of OkHttp's
                    // connection pool, where an idle pooled socket holds the server's shutdown.
                    return MockResponse()
                        .setResponseCode(401)
                        .setBody("Unauthorized\n")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                }
                return MockResponse().withWebSocketUpgrade(listener)
            }
        }
        server.start()
    }

    /** The refusal rho's daemon sends for a major it doesn't speak (daemon/api/transport.go). */
    fun refuseProtocolMismatch(daemonProtocolVersion: String) {
        refuseMethod["rho.hello"] = RpcCodes.PROTOCOL_MISMATCH to
            "rho_protocol_mismatch: protocol version $PROTOCOL_VERSION is incompatible with daemon $daemonProtocolVersion"
    }

    /** Accept the hello, but answer a protocol version this client doesn't speak. */
    fun answerHelloWithProtocol(daemonProtocolVersion: String, daemonVersion: String = "0.0.0") {
        helloResult = """{"daemon_version":"$daemonVersion","protocol_version":"$daemonProtocolVersion","capabilities":["delta"]}"""
    }

    fun host(): String = server.hostName

    fun port(): Int = server.port

    fun requestCount(): Int = requests.size

    /** The frames carrying a call to [method]. */
    fun framesFor(method: String): List<String> = frames.filter { it.contains("\"method\":\"$method\"") }

    /** Closes both ends: MockWebServer gives up shutting down while a socket is still open. */
    fun stop() {
        if (stopped) return
        stopped = true
        for (ws in sockets) runCatching { ws.close(1000, null) }
        server.shutdown()
    }

    private fun resultFor(id: JsonElement, method: String): String {
        val result = if (method == "rho.hello") helloResult else """{"ok":true,"method":"$method"}"""
        return """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    }

    private fun errorFor(id: JsonElement, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}"""
}
