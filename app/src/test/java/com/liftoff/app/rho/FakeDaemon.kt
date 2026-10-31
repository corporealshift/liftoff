package com.liftoff.app.rho

import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.net.RpcError
import com.liftoff.app.rho.protocol.EventType
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.rho.protocol.RhoJson
import com.liftoff.app.rho.protocol.RpcCodes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One thing the daemon does after a prompt reaches it. A turn is a list of these, in the order the
 * client should see them; [Ask] and [Permission] stop the turn until the client answers.
 */
sealed interface DaemonScript {
    /** An assistant message, carrying the text a worker is meant to validate. */
    data class Assistant(val content: String) : DaemonScript

    /** A state_change event. The turn ends at the one after the prompt. */
    data class State(val to: String, val reason: String = "") : DaemonScript

    /** rho.rpc.ui.ask, sent by the daemon and answered by the client. */
    data class Ask(val question: String, val choices: List<String> = emptyList()) : DaemonScript

    /** rho.rpc.permission.request, sent by the daemon and answered by the client. */
    data class Permission(val tool: String, val summary: String, val risk: String = "low") : DaemonScript

    /**
     * Runs [action] where it sits in the turn, so a test can act while the turn is still open. It is not an
     * event and the daemon never answers it; it must not throw.
     */
    class Act(val action: () -> Unit) : DaemonScript
}

/**
 * A stand-in daemon for [DaemonConnectionCheck]'s tests and for the generation worker's, modelled on
 * rho's own `clients/android/.../testing/FakeDaemon.kt`.
 *
 * It answers through a [Dispatcher] rather than an enqueued response, because MockWebServer's
 * default queue serves one enqueued response per connection and a second connection then waits
 * forever for a handshake nothing will answer.
 *
 * It reacts to the frames it receives instead of replaying a fixed script: each call gets the answer
 * the real daemon gives the same call, and [script] says what the daemon does next with the session.
 * Nothing here waits on a clock, so a test that never prompts and a test that prompts twice both get
 * an answer rather than a timeout.
 */
class FakeDaemon {
    val server = MockWebServer()

    /** Every frame the client sent, in the order it arrived. */
    val frames = ConcurrentLinkedQueue<String>()

    /** Every request this server saw, headers included. */
    val requests = ConcurrentLinkedQueue<RecordedRequest>()

    /** The daemon's end of every connection it accepted, so teardown can close them all. */
    val sockets = ConcurrentLinkedQueue<WebSocket>()

    /** The connections that called rho.session.subscribe, and so get every event pushed to them. */
    private val subscribers = CopyOnWriteArrayList<WebSocket>()

    /** What rho.session.create hands out as the session id. */
    @Volatile var sessionId: String = "session-1"

    /** What rho.hello answers, as the raw `result` object, in the shape daemon/api/transport.go uses. */
    @Volatile var helloResult: String =
        """{"daemon_version":"0.0.0","protocol_version":"$PROTOCOL_VERSION","capabilities":["delta","tasks","goal"]}"""

    /** Methods this daemon turns down, keyed by method name, with the code and message it answers with. */
    val refuseMethod = ConcurrentHashMap<String, Pair<Int, String>>()

    /** Refuse the upgrade itself, as the real daemon does for a bearer token it doesn't accept. */
    @Volatile var refuseUpgrade = false

    /** The session log, oldest first. Seed it before a prompt to stand for a turn that already happened. */
    val log = CopyOnWriteArrayList<JsonObject>()

    /** What the client answered each daemon-sent request with, keyed by that request's id. */
    val answers = ConcurrentHashMap<String, JsonElement>()

    /** The turns this daemon plays, one per prompt it accepts. */
    private val turns = CopyOnWriteArrayList<List<DaemonScript>>()

    /** event_id by client_id, so a prompt sent twice is one event (spec 7.4). */
    private val promptIds = ConcurrentHashMap<String, String>()

    private val eventSeq = AtomicInteger()
    private val requestSeq = AtomicInteger()
    private var turnIndex = 0

    /** A ui.ask or permission.request the daemon is still waiting to hear back about. */
    private class Held(val id: String, val frame: String, val rest: List<DaemonScript>)

    private var held: Held? = null

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

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                subscribers.remove(ws)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                subscribers.remove(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                frames.add(text)
                val msg = runCatching { RhoJson.decodeFromString(Rpc.serializer(), text) }.getOrNull() ?: return
                val id = msg.id ?: return
                val method = msg.method
                if (method == null) {
                    // The only frame with no method is the client answering a request this daemon sent.
                    clientAnswered(text(id) ?: return, msg.result)
                    return
                }
                val refusal = refuseMethod[method]
                if (refusal != null) refuse(ws, id, refusal.first, refusal.second)
                else handle(ws, id, method, msg.params as? JsonObject ?: JsonObject(emptyMap()))
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

    /** Script one turn: what this daemon does, in order, with the next prompt it accepts. */
    fun script(vararg steps: DaemonScript) {
        turns.add(steps.toList())
    }

    /**
     * Put an event in the log without a prompt or a connection, for a turn that happened before this
     * client arrived. Returns its id, which is what a cursor is given back. A seeded user message that
     * carries a `client_id` counts as a prompt this daemon already took, so a re-send of it is deduped
     * the way [handle]'s `rho.session.send_prompt` dedupes one it received.
     */
    fun logEvent(type: String, data: JsonObject): String {
        val id = append(type, data).eventId()
        val clientId = text(data["client_id"])
        if (type == EventType.MESSAGE && text(data["role"]) == "user" && clientId != null) {
            promptIds.putIfAbsent(clientId, id)
        }
        return id
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

    @Synchronized
    private fun handle(ws: WebSocket, id: JsonElement, method: String, params: JsonObject) {
        when (method) {
            "rho.hello" -> answer(ws, id, RhoJson.parseToJsonElement(helloResult))

            "rho.session.create" -> {
                val event = append(EventType.SESSION, buildJsonObject {
                    put("workspace", params["workspace"] ?: JsonPrimitive(""))
                    params["options"]?.let { put("options", it) }
                })
                answer(ws, id, buildJsonObject {
                    put("session_id", sessionId)
                    put("event", event)
                })
            }

            "rho.session.subscribe" -> {
                subscribers.addIfAbsent(ws)
                answer(ws, id, buildJsonObject { put("subscribed", true) })
                // A question the daemon is still holding gets asked again now there is someone to answer it.
                held?.let { runCatching { ws.send(it.frame) } }
            }

            "rho.session.send_prompt" -> {
                val clientId = text(params["client_id"])
                if (clientId != null) {
                    val seen = promptIds[clientId]
                    if (seen != null) {
                        answer(ws, id, buildJsonObject { put("event_id", seen) })
                        return
                    }
                }
                val event = append(EventType.MESSAGE, buildJsonObject {
                    put("role", "user")
                    put("content", text(params["content"]) ?: "")
                    if (clientId != null) put("client_id", clientId)
                })
                val eventId = event.eventId()
                if (clientId != null) promptIds[clientId] = eventId
                answer(ws, id, buildJsonObject { put("event_id", eventId) })
                play(nextTurn())
            }

            "rho.session.events_after" -> {
                val cursor = text(params["last_event_id"])
                if (cursor == null) {
                    answer(ws, id, eventsAfter(log.toList()))
                    return
                }
                val at = log.indexOfFirst { it.eventId() == cursor }
                if (at < 0) {
                    refuse(ws, id, RpcCodes.CURSOR_UNKNOWN, "rho_cursor_unknown: no event $cursor in session $sessionId")
                } else {
                    answer(ws, id, eventsAfter(log.drop(at + 1)))
                }
            }

            "rho.session.stop" -> {
                append(EventType.STATE_CHANGE, buildJsonObject {
                    put("to", "completed")
                    put("reason", "stopped")
                })
                answer(ws, id, JsonObject(emptyMap()))
            }

            "rho.session.resume" -> {
                // A paused session goes back to running. "running" is not one of the state_changes that end
                // a turn, so a client reading this event cannot mistake the resume for its turn having ended.
                val event = append(EventType.STATE_CHANGE, buildJsonObject {
                    put("to", "running")
                    put("reason", "resumed")
                })
                answer(ws, id, buildJsonObject {
                    put("session_id", sessionId)
                    put("event", event)
                })
            }

            // A method this fake has no script for still gets an answer, so a test that calls one
            // fails on what it asserts rather than on a wait that never ends.
            else -> answer(ws, id, buildJsonObject { put("ok", true); put("method", method) })
        }
    }

    /** Appends one event to the log and pushes it to every subscribed connection. */
    @Synchronized
    private fun append(type: String, data: JsonObject): JsonObject {
        val event = buildJsonObject {
            put("id", nextEventId())
            put("timestamp", eventTime(eventSeq.get()))
            put("type", type)
            put("data", data)
        }
        log.add(event)
        val push = RhoJson.encodeToString(
            Rpc.serializer(),
            Rpc(
                method = "rho.session.event",
                params = buildJsonObject {
                    put("session_id", sessionId)
                    put("event", event)
                },
            ),
        )
        for (ws in subscribers) runCatching { ws.send(push) }
        return event
    }

    /** Records what the client said to a request this daemon sent, and plays on with the rest of the turn. */
    @Synchronized
    private fun clientAnswered(key: String, result: JsonElement?) {
        answers[key] = result ?: JsonNull
        val waiting = held
        if (waiting != null && waiting.id == key) {
            held = null
            play(waiting.rest)
        }
    }

    /** Walks a turn until it runs out or the daemon has to ask the client something. */
    @Synchronized
    private fun play(steps: List<DaemonScript>) {
        for ((index, step) in steps.withIndex()) {
            when (step) {
                is DaemonScript.Assistant -> append(EventType.MESSAGE, buildJsonObject {
                    put("role", "assistant")
                    put("content", step.content)
                })

                is DaemonScript.State -> append(EventType.STATE_CHANGE, buildJsonObject {
                    put("to", step.to)
                    put("reason", step.reason)
                })

                is DaemonScript.Ask -> {
                    hold("srv-" + requestSeq.incrementAndGet(), "rho.rpc.ui.ask", buildJsonObject {
                        put("question", step.question)
                        put("choices", buildJsonArray { step.choices.forEach { add(JsonPrimitive(it)) } })
                    }, steps.drop(index + 1))
                    return
                }

                is DaemonScript.Permission -> {
                    hold("srv-" + requestSeq.incrementAndGet(), "rho.rpc.permission.request", buildJsonObject {
                        put("tool", step.tool)
                        put("summary", step.summary)
                        put("risk", step.risk)
                    }, steps.drop(index + 1))
                    return
                }

                // On this thread, in order, so the next step's event is pushed only after it has run.
                is DaemonScript.Act -> step.action()
            }
        }
    }

    /** Sends a daemon-originated request and keeps the rest of the turn back until it is answered. */
    private fun hold(id: String, method: String, params: JsonObject, rest: List<DaemonScript>) {
        val frame = RhoJson.encodeToString(
            Rpc.serializer(),
            Rpc(
                id = JsonPrimitive(id),
                method = method,
                params = buildJsonObject {
                    put("session_id", sessionId)
                    put("request_id", id)
                    params.forEach { (key, value) -> put(key, value) }
                },
            ),
        )
        held = Held(id, frame, rest)
        for (ws in subscribers) runCatching { ws.send(frame) }
    }

    @Synchronized
    private fun nextTurn(): List<DaemonScript> {
        if (turns.isEmpty()) return emptyList()
        // Running out repeats the last turn, so a worker that prompts again meets the same daemon.
        return turns[minOf(turnIndex++, turns.size - 1)]
    }

    private fun answer(ws: WebSocket, id: JsonElement, result: JsonElement) {
        runCatching { ws.send(RhoJson.encodeToString(Rpc.serializer(), Rpc(id = id, result = result))) }
    }

    private fun refuse(ws: WebSocket, id: JsonElement, code: Int, message: String) {
        runCatching {
            ws.send(RhoJson.encodeToString(Rpc.serializer(), Rpc(id = id, error = RpcError(code, message))))
        }
    }

    private fun eventsAfter(events: List<JsonObject>): JsonElement = buildJsonObject {
        put("events", buildJsonArray { events.forEach { add(it) } })
        put("synced", true)
    }

    /** ULID-shaped ids of one width, so the log reads in id order the way spec 2 requires. */
    private fun nextEventId(): String = "01E" + eventSeq.incrementAndGet().toString().padStart(23, '0')

    /** A fixed clock, one second per event: the id is what orders the log, but a log of one instant looks wrong. */
    private fun eventTime(n: Int): String = "2026-01-01T00:%02d:%02dZ".format(Locale.ROOT, n / 60 % 60, n % 60)

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.eventId(): String = text(this["id"]) ?: ""
}
