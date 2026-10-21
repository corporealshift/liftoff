package com.liftoff.app.work

import com.liftoff.app.rho.DaemonScript
import com.liftoff.app.rho.FakeDaemon
import com.liftoff.app.rho.net.DaemonClient
import com.liftoff.app.rho.net.Rpc
import com.liftoff.app.rho.protocol.EventType
import com.liftoff.app.rho.protocol.RhoJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * [runCoachTurn] against [FakeDaemon] over a real localhost socket: plain JUnit 4, no Robolectric,
 * no virtual time, because the fake plays its turn on real threads.
 *
 * The runner's own limit is a couple of seconds and every wait around it is bounded well below the
 * build's five-minute test-task timeout, so a turn that never ends fails this test rather than the
 * build.
 */
class CoachTurnRunnerTest {
    private lateinit var daemon: FakeDaemon

    /** The runner's limit for a turn the fake is scripted to finish at once. */
    private val turnLimit = 2.seconds

    /** The runner's limit plus room for the fake to answer, so a hang fails here, not the build. */
    private val answerWindow = 15.seconds

    @Before
    fun startDaemon() {
        daemon = FakeDaemon()
        daemon.start()
    }

    @After
    fun stopDaemon() {
        daemon.stop()
    }

    private fun connectedClient(): DaemonClient {
        val client = DaemonClient(baseUrl = "http://${daemon.host()}:${daemon.port()}", token = "sekrit")
        runBlocking { client.connect() }
        return client
    }

    /**
     * "a fresh turn: one create frame carrying the workspace, permission_mode auto, the labels and the
     * description; the prompt frame carrying the given client_id; the returned text equal to the
     * scripted assistant message; ended idle; the callbacks receiving the session id and a final cursor".
     */
    @Test
    fun aFreshTurnCreatesOneSessionAndReturnsItsReplyAndCursor(): Unit = runBlocking {
        val reply = "three sets of five, then walk back"
        daemon.script(DaemonScript.Assistant(reply), DaemonScript.State("idle"))

        val client = connectedClient()
        val sessionIds = mutableListOf<String>()
        val cursors = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = null,
                    options = CoachSessionOptions(
                        workspace = "/repo/coach",
                        labels = listOf("coach", "plan"),
                        description = "one turn of the plan",
                    ),
                    prompt = "give me a set",
                    clientId = "turn-1",
                    cursor = null,
                    limit = turnLimit,
                    onSessionId = { sessionIds.add(it) },
                    onCursor = { cursors.add(it) },
                )
            }
        } finally {
            client.close()
        }

        val created = frameFor("rho.session.create").jsonObject("options")
        assertEquals("auto", created["permission_mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("coach", "plan"), created["labels"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("one turn of the plan", created["description"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/repo/coach", frameFor("rho.session.create")["workspace"]?.jsonPrimitive?.contentOrNull)

        val prompted = frameFor("rho.session.send_prompt")
        assertEquals("turn-1", prompted["client_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("give me a set", prompted["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(daemon.sessionId, prompted["session_id"]?.jsonPrimitive?.contentOrNull)

        assertEquals(TurnEnd.Idle, outcome.end)
        assertEquals(reply, outcome.lastAssistantText)
        assertEquals(daemon.sessionId, outcome.sessionId)

        assertEquals("the session id is reported once, as soon as it is known", listOf(daemon.sessionId), sessionIds)
        val finalCursor = daemon.log.last()["id"]?.jsonPrimitive?.contentOrNull
        assertEquals("the cursor callback saw the final cursor", finalCursor, cursors.last())
        assertEquals("the value handed back carries the same final cursor", finalCursor, outcome.cursor)
        assertEquals("the cursor only ever moved forward: $cursors", cursors.sorted(), cursors)
        assertEquals("one callback per event, none repeated: $cursors", cursors.size, cursors.distinct().size)
    }

    /**
     * "an existing session id and cursor: no create frame". The runner prompts on the session it was
     * handed and asks only for what came after the cursor it was handed — that cursor comes from the
     * fake's own log, so the fake knows it.
     */
    @Test
    fun aSessionAndCursorHandedInIsUsedWithoutCreatingAnother(): Unit = runBlocking {
        val seeded = daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", "a reply from the turn before this one")
        })
        val reply = "the reply this turn asked for"
        daemon.script(DaemonScript.Assistant(reply), DaemonScript.State("idle"))

        val client = connectedClient()
        val sessionIds = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = daemon.sessionId,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "carry on",
                    clientId = "turn-2",
                    cursor = seeded,
                    limit = turnLimit,
                    onSessionId = { sessionIds.add(it) },
                )
            }
        } finally {
            client.close()
        }

        assertEquals(
            "a session was handed in, so nothing should have been created: ${daemon.framesFor("rho.session.create")}",
            0,
            daemon.framesFor("rho.session.create").size,
        )

        val prompted = frameFor("rho.session.send_prompt")
        assertEquals(daemon.sessionId, prompted["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("turn-2", prompted["client_id"]?.jsonPrimitive?.contentOrNull)

        val caughtUp = frameFor("rho.session.events_after")
        assertEquals(
            "the catch-up read asked for what came after the cursor it was handed",
            seeded,
            caughtUp["last_event_id"]?.jsonPrimitive?.contentOrNull,
        )

        assertEquals(TurnEnd.Idle, outcome.end)
        assertEquals(reply, outcome.lastAssistantText)
        assertNotEquals("the reply from before the cursor is not this turn's", "a reply from the turn before this one", outcome.lastAssistantText)
        assertEquals(daemon.sessionId, outcome.sessionId)
        assertEquals("a handed-in id is reported too", listOf(daemon.sessionId), sessionIds)
        assertTrue(
            "the cursor moved past the one handed in: $seeded -> ${outcome.cursor}",
            outcome.cursor != null && outcome.cursor!! > seeded,
        )
    }

    /**
     * "a state_change to idle logged before the prompt (logEvent) does not end the turn". The end check
     * compares against the prompt's own event_id, not against "the next event", so an idle from before this
     * prompt is one more event to read rather than the end of the turn: the runner still reaches the
     * scripted end and reports its reply.
     *
     * [FakeDaemon.logEvent] pushes nothing — only a socket that called rho.session.subscribe gets pushes —
     * so this old idle reaches the runner through the catch-up events_after, which is asked for from the
     * start because the runner was handed no cursor.
     */
    @Test
    fun aStateChangeLoggedBeforeThePromptDoesNotEndTheTurn(): Unit = runBlocking {
        val oldIdle = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "idle")
            put("reason", "the turn before this one")
        })
        val reply = "the reply this turn asked for"
        daemon.script(DaemonScript.Assistant(reply), DaemonScript.State("idle", "this turn finished"))

        val client = connectedClient()
        val cursors = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = null,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "what should I do next",
                    clientId = "turn-3",
                    cursor = null,
                    limit = turnLimit,
                    onCursor = { cursors.add(it) },
                )
            }
        } finally {
            client.close()
        }

        val promptId = promptEventId("turn-3")
        assertTrue("the seeded state_change predates this turn's prompt: $oldIdle vs $promptId", oldIdle < promptId)
        assertTrue(
            "the runner was handed no cursor, so the catch-up read asked for the whole log",
            frameFor("rho.session.events_after")["last_event_id"] == null,
        )
        assertTrue(
            "the old idle arrived through the catch-up read, since logEvent pushed nothing: $cursors",
            cursors.contains(oldIdle),
        )

        assertEquals(TurnEnd.Idle, outcome.end)
        assertEquals("the turn ran on past the older idle to the scripted reply", reply, outcome.lastAssistantText)
        assertEquals(
            "the cursor this turn left is the scripted end, not the older idle: $cursors",
            eventId(daemon.log.last()),
            outcome.cursor,
        )
        assertTrue("the cursor moved forward past the seeded idle: $cursors", cursors.last() > oldIdle)
    }

    /**
     * "a scripted Ask is answered with the sentence above" — read out of the fake's answers, keyed by the
     * fake's own request id — "and each turn still ends idle with the reply". The fake holds the rest of the
     * turn back until the client answers, so a runner that ignores the question has a turn that never ends.
     */
    @Test
    fun aDaemonQuestionIsAnsweredWithTheFixedSentenceAndTheTurnStillEndsIdle(): Unit = runBlocking {
        val reply = "the daemon answered its own question and carried on"
        daemon.script(
            DaemonScript.Ask("which of these two?", listOf("left", "right")),
            DaemonScript.Assistant(reply),
            DaemonScript.State("idle"),
        )

        val client = connectedClient()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = null,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "which one?",
                    clientId = "turn-4",
                    cursor = null,
                    limit = turnLimit,
                )
            }
        } finally {
            client.close()
        }

        val answer = answerTo("srv-1")
        assertEquals(
            "a runner with nobody watching decides its own questions: $answer",
            "Decide yourself; there is nobody to ask.",
            answer["answer"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("the runner answered one daemon request, and only that one: ${daemon.answers}", 1, daemon.answers.size)
        assertEquals(
            "one answer frame, so the question was not answered twice",
            1,
            daemon.frames.count { !it.contains("\"method\":") },
        )

        assertEquals(TurnEnd.Idle, outcome.end)
        assertEquals("the runner kept waiting and got the reply the question was holding back", reply, outcome.lastAssistantText)
        assertEquals("the end arrived only after the answer", eventId(daemon.log.last()), outcome.cursor)
    }

    /**
     * "a scripted Permission is answered deny", read out of the fake's answers, "and the turn still ends idle
     * with its reply". Denying is what a runner with nobody watching does to a tool it cannot vouch for, and
     * the held-back rest of the turn is what proves it kept waiting after answering.
     */
    @Test
    fun aPermissionRequestIsAnsweredDenyAndTheTurnStillEndsIdle(): Unit = runBlocking {
        val reply = "the reply that came after the tool was refused"
        daemon.script(
            DaemonScript.Permission("write_file", "write /repo/coach/plan.md", "high"),
            DaemonScript.Assistant(reply),
            DaemonScript.State("idle"),
        )

        val client = connectedClient()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = null,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "write the plan file",
                    clientId = "turn-5",
                    cursor = null,
                    limit = turnLimit,
                )
            }
        } finally {
            client.close()
        }

        val answer = answerTo("srv-1")
        assertEquals(
            "a runner with nobody watching denies a tool it cannot vouch for: $answer",
            "deny",
            answer["verdict"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("the runner answered one daemon request, and only that one: ${daemon.answers}", 1, daemon.answers.size)
        assertEquals(
            "one answer frame, so the request was not answered twice",
            1,
            daemon.frames.count { !it.contains("\"method\":") },
        )

        assertEquals(TurnEnd.Idle, outcome.end)
        assertEquals("the runner kept waiting and got the reply the permission request was holding back", reply, outcome.lastAssistantText)
        assertEquals("the end arrived only after the answer", eventId(daemon.log.last()), outcome.cursor)
    }

    /** The params of the one frame this client sent calling [method], failing if there was not exactly one. */
    private fun frameFor(method: String): JsonObject {
        val frames = daemon.framesFor(method)
        assertEquals("expected exactly one $method frame, got $frames", 1, frames.size)
        val params = RhoJson.decodeFromString(Rpc.serializer(), frames.first()).params?.jsonObject
        assertTrue("the $method frame carried no params: ${frames.first()}", params != null)
        return params!!
    }

    private fun JsonObject.jsonObject(key: String): JsonObject {
        val value = this[key]?.jsonObject
        assertTrue("the frame has no \"$key\" object: $this", value != null)
        return value!!
    }

    /**
     * What the client answered the fake's own request [requestId] with. The fake keys its answers by that
     * request id ("srv-1", …), which is also the id the client had to answer with.
     */
    private fun answerTo(requestId: String): JsonObject {
        val answer = daemon.answers[requestId]
        assertTrue("the runner never answered request $requestId; the fake recorded ${daemon.answers}", answer != null)
        val body = answer!! as? JsonObject
        assertTrue("the answer to $requestId is not an object: $answer", body != null)
        return body!!
    }

    /** The id of one entry of the fake's log, which is what a cursor is compared against. */
    private fun eventId(event: JsonObject): String {
        val id = event["id"]?.jsonPrimitive?.contentOrNull
        assertTrue("this event has no id: $event", id != null)
        return id!!
    }

    /** The id the fake gave the prompt sent under [clientId], read back out of its log. */
    private fun promptEventId(clientId: String): String {
        val event = daemon.log.firstOrNull {
            it["type"]?.jsonPrimitive?.contentOrNull == EventType.MESSAGE &&
                it["data"]?.jsonObject?.get("client_id")?.jsonPrimitive?.contentOrNull == clientId
        }
        assertTrue("no prompt event for client_id $clientId in ${daemon.log.map { it["id"] }}", event != null)
        return eventId(event!!)
    }
}
