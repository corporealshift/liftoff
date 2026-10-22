package com.liftoff.app.work

import com.liftoff.app.rho.net.CreateSessionResult
import com.liftoff.app.rho.net.DaemonClient
import com.liftoff.app.rho.net.DaemonException
import com.liftoff.app.rho.net.EventsAfterParams
import com.liftoff.app.rho.net.EventsAfterResult
import com.liftoff.app.rho.net.Incoming
import com.liftoff.app.rho.protocol.Event
import com.liftoff.app.rho.protocol.EventType
import com.liftoff.app.rho.protocol.MessageData
import com.liftoff.app.rho.protocol.RhoJson
import com.liftoff.app.rho.protocol.RpcCodes
import com.liftoff.app.rho.protocol.StateChangeData
import com.liftoff.app.rho.protocol.payload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * One coach turn, from a caller that already has a connected [DaemonClient].
 *
 * Nothing here is Android, Room or WorkManager: the worker that calls this owns the schedule, and
 * this owns one session's worth of prompting and reading. A [DaemonException] from any call goes
 * out unchanged — the caller has the code to decide what to do about it — except a cursor the
 * daemon no longer recognises, which is worth one read from the start.
 */

/** How a turn ended: the daemon's five state_change targets, plus the limit this call ran out of. */
enum class TurnEnd { Idle, Completed, Blocked, Paused, Error, TimedOut }

/** What a session is started with. An existing session keeps whatever it already has. */
data class CoachSessionOptions(
    val workspace: String,
    val labels: List<String> = emptyList(),
    val description: String = "",
)

/** What one turn left behind, including the cursor a caller needs to resume from (invariant 6). */
data class TurnOutcome(
    val sessionId: String,
    val cursor: String?,
    val end: TurnEnd,
    val lastAssistantText: String?,
)

/** The daemon's `to` values that end a turn. Any other state is one the turn carries on through. */
private val TURN_ENDS = mapOf(
    "idle" to TurnEnd.Idle,
    "completed" to TurnEnd.Completed,
    "blocked" to TurnEnd.Blocked,
    "paused" to TurnEnd.Paused,
    "error" to TurnEnd.Error,
)

// A runner with nobody watching decides its own questions and denies any tool it cannot vouch for.
private const val NOBODY_TO_ASK = "Decide yourself; there is nobody to ask."
private const val PERMISSION_MODE_AUTO = "auto"

/**
 * Runs one turn on [sessionId], or starts a session for [options] when handed none.
 *
 * [limit] covers the whole turn from this call, so a session that never reaches the prompt is
 * caught too. The client is the caller's: this never closes it.
 */
suspend fun runCoachTurn(
    client: DaemonClient,
    sessionId: String?,
    options: CoachSessionOptions,
    prompt: String,
    clientId: String,
    cursor: String?,
    limit: Duration,
    onSessionId: (String) -> Unit = {},
    onCursor: (String) -> Unit = {},
): TurnOutcome = coroutineScope {
    val pushed = Channel<Incoming>(Channel.UNLIMITED)
    // incoming has replay = 0, so an event pushed before a collector exists is gone for good, and
    // the daemon plays its whole turn inside the frame that answers the prompt.
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        client.incoming.collect { pushed.send(it) }
    }

    var session = sessionId ?: ""
    var lastCursor = cursor
    var promptId = ""
    var lastAssistantId = ""
    var lastAssistantText: String? = null
    var end: TurnEnd? = null
    val seen = HashSet<String>()

    fun moveCursorTo(id: String) {
        // Ids are ULID-shaped and fixed width, so log order is id order and this needs no clock.
        val from = lastCursor
        if (from == null || id > from) {
            lastCursor = id
            onCursor(id)
        }
    }

    fun handle(event: Event) {
        if (!seen.add(event.id)) return
        moveCursorTo(event.id)
        when (event.type) {
            EventType.MESSAGE -> {
                val message = event.payload<MessageData>()
                // "Last" is by id, not by handling order, so catch-up and pushes can arrive either way.
                if (message?.role == "assistant" && event.id > promptId && event.id > lastAssistantId) {
                    lastAssistantId = event.id
                    lastAssistantText = message.content
                }
            }

            EventType.STATE_CHANGE -> {
                val target = event.payload<StateChangeData>()?.to?.let { TURN_ENDS[it] }
                // Compared against the prompt's own id, so a state_change from before it never ends this turn.
                // Only the first one after the prompt is the end; a later one is a state the turn already left.
                if (target != null && end == null && event.id > promptId) end = target
            }
        }
    }

    suspend fun handleIncoming(item: Incoming) {
        when (item) {
            is Incoming.Event -> if (item.value.sessionId == session) handle(item.value.event)
            // A question the daemon is still holding from somebody else's turn gets the same answer:
            // ignoring it leaves the daemon waiting and our own turn unplayed.
            is Incoming.Ask -> client.respond(item.value.id, buildJsonObject { put("answer", NOBODY_TO_ASK) })
            is Incoming.Permission -> client.respond(item.value.id, buildJsonObject { put("verdict", "deny") })
            is Incoming.Other -> Unit
        }
    }

    try {
        withTimeoutOrNull(limit) {
            if (sessionId == null) session = createSession(client, options)
            // Reported either way, so the caller always holds the id this turn actually used.
            onSessionId(session)

            client.callOrThrow("rho.session.subscribe", buildJsonObject { put("session_id", session) })
            val prompted = client.callOrThrow("rho.session.send_prompt", buildJsonObject {
                put("session_id", session)
                put("content", prompt)
                put("client_id", clientId)
            })
            promptId = prompted.jsonObject["event_id"]?.jsonPrimitive?.contentOrNull ?: ""

            // The catch-up read and the buffered pushes go through one handling; ids already seen
            // are skipped, so the two overlapping is expected rather than a double count.
            // The batch stops at the end it finds, the way the pushed loop below does, so a page holding
            // two ends reports the first.
            for (event in eventsAfter(client, session, lastCursor)) {
                if (end != null) break
                handle(event)
            }

            while (end == null) {
                val buffered = pushed.tryReceive().getOrNull() ?: break
                handleIncoming(buffered)
            }
            while (end == null) handleIncoming(pushed.receive())
        }
    } finally {
        collector.cancel()
    }

    // No end found inside the limit is the timeout, and the cursor that comes back is the furthest
    // event this attempt actually read, so a retry picks up here.
    TurnOutcome(session, lastCursor, end ?: TurnEnd.TimedOut, lastAssistantText)
}

/** Ends a session. The runner never calls this, not even on a timeout: stopping is the caller's. */
suspend fun stopCoachSession(client: DaemonClient, sessionId: String) {
    client.callOrThrow("rho.session.stop", buildJsonObject { put("session_id", sessionId) })
}

private suspend fun createSession(client: DaemonClient, options: CoachSessionOptions): String {
    val created = client.callOrThrow("rho.session.create", buildJsonObject {
        put("workspace", options.workspace)
        put("options", buildJsonObject {
            put("permission_mode", PERMISSION_MODE_AUTO)
            put("labels", buildJsonArray { options.labels.forEach { add(JsonPrimitive(it)) } })
            put("description", options.description)
        })
    })
    return RhoJson.decodeFromJsonElement(CreateSessionResult.serializer(), created).sessionId
}

private suspend fun eventsAfter(client: DaemonClient, sessionId: String, cursor: String?): List<Event> {
    val params = EventsAfterParams.serializer()
    val answer = try {
        client.callOrThrow("rho.session.events_after", RhoJson.encodeToJsonElement(params, EventsAfterParams(sessionId, cursor)))
    } catch (refused: DaemonException) {
        // A cursor the daemon has forgotten is worth one read from the start. Every other refusal
        // goes out with its code, because it is a judgement about the request, not about the cursor.
        if (refused.code != RpcCodes.CURSOR_UNKNOWN) throw refused
        client.callOrThrow("rho.session.events_after", RhoJson.encodeToJsonElement(params, EventsAfterParams(sessionId)))
    }
    return RhoJson.decodeFromJsonElement(EventsAfterResult.serializer(), answer).events
}
