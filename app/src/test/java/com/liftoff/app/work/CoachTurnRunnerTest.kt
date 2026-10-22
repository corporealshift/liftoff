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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
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

    /**
     * The limit for a turn scripted with no end to find: a fraction of a second, long enough for the
     * calls a turn makes over a localhost socket and far shorter than the wait for an end that never
     * arrives, so the limit is what ends the turn.
     */
    private val shortLimit = 500.milliseconds

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

    /**
     * "a turn scripted to end blocked … report that end". The end is the first state_change after the
     * prompt whose target is blocked, and it comes back as blocked — not as idle, and not as a timeout
     * that ran out looking for an end. The word the fake actually logged is read off its log, so the
     * mapping from the daemon's state to the runner's end reason is checked rather than assumed.
     */
    @Test
    fun aTurnThatEndsBlockedReportsBlocked(): Unit = runBlocking {
        val reply = "I need the equipment list before I can plan the next set"
        daemon.script(
            DaemonScript.Assistant(reply),
            DaemonScript.State("blocked", "waiting on the equipment list"),
        )

        val client = connectedClient()
        val cursors = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = null,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "what should I do next",
                    clientId = "turn-6",
                    cursor = null,
                    limit = turnLimit,
                    onCursor = { cursors.add(it) },
                )
            }
        } finally {
            client.close()
        }

        val end = daemon.log.last()
        assertEquals(EventType.STATE_CHANGE, end["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("blocked", end["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull)

        assertEquals(
            "the daemon's blocked is its own end reason, not idle and not a timeout: $end",
            TurnEnd.Blocked,
            outcome.end,
        )
        assertEquals("the reply read before the end is still reported", reply, outcome.lastAssistantText)
        assertEquals("the cursor this turn left is the blocked state_change", eventId(end), outcome.cursor)
        assertEquals("the cursor callback saw that same end", eventId(end), cursors.last())
    }

    /**
     * "… and one ending paused, report that end". Paused is its own end reason, not a timeout. This turn
     * is scripted with no assistant message at all, so the state_change is the only thing after the
     * prompt: the runner stops at it rather than waiting out its limit for a reply that never comes.
     */
    @Test
    fun aTurnThatEndsPausedReportsPaused(): Unit = runBlocking {
        daemon.script(DaemonScript.State("paused", "the session was paused by whoever runs it"))

        val client = connectedClient()
        val cursors = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = null,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "pause this for now",
                    clientId = "turn-7",
                    cursor = null,
                    limit = turnLimit,
                    onCursor = { cursors.add(it) },
                )
            }
        } finally {
            client.close()
        }

        val end = daemon.log.last()
        assertEquals(EventType.STATE_CHANGE, end["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("paused", end["data"]?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull)

        assertEquals(
            "paused is an end the daemon reported, not a limit that ran out: $end",
            TurnEnd.Paused,
            outcome.end,
        )
        assertNull("a paused turn that never produced a reply still ends paused", outcome.lastAssistantText)
        assertEquals("the cursor this turn left is the paused state_change", eventId(end), outcome.cursor)
        assertEquals("the cursor callback saw that same end", eventId(end), cursors.last())
    }

    /**
     * The turn ends at the *first* state_change after the prompt that ends it, even when one events_after
     * page holds two of them. Both ends are seeded with [FakeDaemon.logEvent] so they reach the runner
     * through the catch-up read and neither as a push: the pushed path already stopped at the first, so the
     * same log has to give the same end whichever way the events arrived. The prompt is re-sent under the
     * client_id it was first sent with, which is what puts the two seeded ends after it.
     */
    @Test
    fun twoEndingStateChangesAfterThePromptStopAtTheFirst(): Unit = runBlocking {
        val clientId = "turn-10"
        val client = connectedClient()
        withTimeout(answerWindow) {
            client.callOrThrow("rho.session.send_prompt", buildJsonObject {
                put("session_id", daemon.sessionId)
                put("content", "carry on")
                put("client_id", clientId)
            })
        }
        val promptId = promptEventId(clientId)

        val reply = "the reply the daemon gave before it blocked"
        daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", reply)
        })
        val blockedId = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "blocked")
            put("reason", "waiting on the equipment list")
        })
        val idleId = daemon.logEvent(EventType.STATE_CHANGE, buildJsonObject {
            put("to", "idle")
            put("reason", "the session was reset after the block")
        })
        val afterTheEnd = "a reply from after the turn had already ended"
        daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", afterTheEnd)
        })

        val cursors = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = daemon.sessionId,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "carry on",
                    clientId = clientId,
                    cursor = promptId,
                    limit = turnLimit,
                    onCursor = { cursors.add(it) },
                )
            }
        } finally {
            client.close()
        }

        assertEquals(
            "the prompt was logged once, so the re-send answered with the original event_id",
            1,
            daemon.log.count {
                it["type"]?.jsonPrimitive?.contentOrNull == EventType.MESSAGE &&
                    it["data"]?.jsonObject?.get("client_id")?.jsonPrimitive?.contentOrNull == clientId
            },
        )
        assertEquals(
            "the runner re-sent the prompt under the same client_id",
            2,
            daemon.framesFor("rho.session.send_prompt").size,
        )
        assertTrue("both seeded ends come after the prompt this turn re-sent: $promptId then $blockedId", blockedId > promptId)
        assertTrue("the blocked state_change is the earlier of the two ends: $blockedId then $idleId", blockedId < idleId)
        assertEquals(
            "the catch-up read asked for everything after the prompt this turn re-sent",
            promptId,
            frameFor("rho.session.events_after")["last_event_id"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "the page held on past the end this turn stopped at",
            afterTheEnd,
            daemon.log.last()["data"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull,
        )

        assertEquals(
            "the turn ends at the first state_change after the prompt, not at the last one on the page: $blockedId then $idleId",
            TurnEnd.Blocked,
            outcome.end,
        )
        assertEquals("the cursor this turn left is the blocked state_change", blockedId, outcome.cursor)
        assertEquals("the cursor callback saw that same end", blockedId, cursors.last())
        assertTrue("the idle after the end was never read: $cursors", !cursors.contains(idleId))
        assertEquals(
            "the reply read before the end is the one reported, not the one after it",
            reply,
            outcome.lastAssistantText,
        )
    }

    /**
     * "a turn with no end inside a short limit reports timed out". The limit is a parameter, so this test
     * hands in a fraction of a second, and the runner returns the timeout as a value instead of letting
     * the wait escape. The turn is scripted with an assistant message and no state_change, so there is a
     * furthest event actually read but no end to find, and the cursor that comes back is that event and
     * not the one handed in: a retry picks up where this attempt stopped.
     */
    @Test
    fun aTurnWithNoEndInsideItsLimitReportsTimedOut(): Unit = runBlocking {
        val seeded = daemon.logEvent(EventType.MESSAGE, buildJsonObject {
            put("role", "assistant")
            put("content", "the reply from the turn before this one")
        })
        val reply = "the daemon answered, but never said the turn was over"
        daemon.script(DaemonScript.Assistant(reply))

        val client = connectedClient()
        val cursors = mutableListOf<String>()
        val outcome = try {
            withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = daemon.sessionId,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "carry on",
                    clientId = "turn-8",
                    cursor = seeded,
                    limit = shortLimit,
                    onCursor = { cursors.add(it) },
                )
            }
        } finally {
            client.close()
        }

        assertEquals("a turn that never reached a state_change is its own end reason", TurnEnd.TimedOut, outcome.end)

        val furthest = daemon.log.last()
        assertEquals(EventType.MESSAGE, furthest["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(reply, furthest["data"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull)

        assertEquals(
            "the catch-up read asked for what came after the cursor handed in",
            seeded,
            frameFor("rho.session.events_after")["last_event_id"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "the cursor that comes back is the furthest event this attempt actually read",
            eventId(furthest),
            outcome.cursor,
        )
        assertTrue(
            "the cursor moved past the one handed in: $seeded -> ${outcome.cursor}",
            outcome.cursor != null && outcome.cursor!! > seeded,
        )
        assertEquals("the callback saw the same furthest event", eventId(furthest), cursors.last())
        assertEquals("the reply was read before the limit ran out", reply, outcome.lastAssistantText)
    }

    /**
     * "Also add a small helper that sends rho.session.stop {session_id}". One call, one frame, carrying
     * the id it was given — an id that is not the fake's own, so the frame shows the pass-through rather
     * than a default. The turn before it ran out of its limit and stopped nothing itself: stopping stays
     * the caller's call, which is why the helper exists at all.
     */
    @Test
    fun theStopHelperSendsOneSessionStopFrame(): Unit = runBlocking {
        daemon.script(DaemonScript.Assistant("the daemon answered and never ended the turn"))

        val client = connectedClient()
        try {
            val outcome = withTimeout(answerWindow) {
                runCoachTurn(
                    client = client,
                    sessionId = daemon.sessionId,
                    options = CoachSessionOptions(workspace = "/repo/coach"),
                    prompt = "carry on",
                    clientId = "turn-9",
                    cursor = null,
                    limit = shortLimit,
                )
            }
            assertEquals(TurnEnd.TimedOut, outcome.end)
            assertEquals(
                "the runner never stops a session, not even on its own timeout: ${daemon.framesFor("rho.session.stop")}",
                0,
                daemon.framesFor("rho.session.stop").size,
            )

            val stopped = "coach-session-the-caller-chose"
            withTimeout(answerWindow) { stopCoachSession(client, stopped) }

            val frame = frameFor("rho.session.stop")
            assertEquals(
                "the frame carries the session id the helper was given, not the one the fake hands out",
                stopped,
                frame["session_id"]?.jsonPrimitive?.contentOrNull,
            )
        } finally {
            client.close()
        }
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
