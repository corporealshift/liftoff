# Coach turn runner over the copied rho client

Build the piece that runs one coach turn in a rho session: create or reuse the session, send a prompt, watch events until the turn ends, and hand back what happened. It uses no Room and no WorkManager. A later run builds the GenerationWorker on top of it.

Why this is small: an earlier attempt at the whole worker stalled because it had to read too many files at once. Everything this run needs is in this brief and in the four files named below. Don't read DESIGN.md, the rho protocol spec outside the repo, or the data and coach packages. Write the new source file early, then build.

**Step 1: bring in the finished fake daemon.** Commit 6b1063e ('test: teach FakeDaemon to script a whole rho turn') on the local branch rho/generationworker-core-flow-j5ca changes only `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`. Cherry-pick it, keeping its message. Don't bring any `.rho/runs/` file from that branch. After the pick, `DaemonConnectionCheckTest` and `AppContainerTest` must still pass. The fake already scripts turns: `script(DaemonScript.Assistant(text), DaemonScript.State("idle"))`, plus `DaemonScript.Ask` and `DaemonScript.Permission`. It also offers `logEvent(type, data)` to seed earlier history, `frames` / `framesFor(method)` to see what the client sent, `answers` to see how the client answered daemon requests, `sessionId` (default "session-1"), and `host()`, `port()`, `start()` and `stop()`.

**Step 2: the turn runner**, a new file in `com.liftoff.app.work` with no Android imports. Its inputs are a `DaemonClient` the caller has already connected (`com.liftoff.app.rho.net.DaemonClient`), an existing session id or null, the session options for a new session (workspace path, labels, description), the prompt text, a `client_id` string, the stored event cursor or null, a turn time limit, and two callbacks: one when a session id is first known, one each time the cursor moves. It returns the session id, the final cursor, how the turn ended (idle, completed, blocked, paused, error, or timed out) and the text of the last assistant message after our prompt (or null).

Protocol facts, all checked against the copied client and the fake:
- `client.callOrThrow(method, params: JsonElement)` returns the result or throws `DaemonException` (its `code` is the daemon's code, and `isPermanent` covers `RpcCodes.PERMANENT`). `client.respond(id, result)` answers a request the daemon sent.
- `client.incoming` is a SharedFlow with no replay. Start collecting it before subscribing or prompting, because the fake pushes the turn's events while it is still handling `send_prompt`. Buffer them in a Channel and judge them only once you hold the prompt's `event_id`.
- `rho.session.create` takes `{workspace, options: {permission_mode: "auto", labels: [...], description}}` and returns `{session_id, event}`. Call it only when there is no session id, and report the id through the callback before anything else.
- `rho.session.subscribe` takes `{session_id}`.
- `rho.session.send_prompt` takes `{session_id, content, client_id}` and returns `{event_id}`. A repeated `client_id` returns the original id and is not delivered twice.
- `rho.session.events_after` takes `EventsAfterParams(sessionId, lastEventId)` and returns `EventsAfterResult(events, synced)`. An unknown cursor is refused with `RpcCodes.CURSOR_UNKNOWN`; then refetch from null. After the prompt, call it once to catch up, and pass those events through the same handling as pushed ones (skip any id already seen).
- Pushed events arrive as `Incoming.Event(SessionEvent(sessionId, event))`. `Event.id` strings sort in log order. `event.payload<MessageData>()` gives `role` and `content`; `event.payload<StateChangeData>()` gives `to`.
- The turn ends at the first `state_change` whose id is greater than our prompt's `event_id` and whose `to` is idle, completed, blocked, paused or error. A state_change from before our prompt never ends it.
- `Incoming.Ask`: respond with `{"answer": "Decide yourself; there is nobody to ask."}`. `Incoming.Permission`: respond with `{"verdict": "deny"}`. Keep waiting after both.
- Also add a small helper that sends `rho.session.stop` `{session_id}`; the worker calls it later.

Tests, plain JUnit or Robolectric, against the fake. Every wait has a timeout, and use `runBlocking` rather than virtual time, since the fake runs on real threads. They must also pass on the GitHub CI runner (ubuntu-latest, UTC). Cover:
- a fresh turn: one create frame carrying the workspace, permission_mode auto, the labels and the description; the prompt frame carrying the given client_id; the returned text equal to the scripted assistant message; ended idle; the callbacks receiving the session id and a final cursor
- an existing session id and cursor: no create frame
- a state_change to idle logged before the prompt (`logEvent`) does not end the turn
- a scripted Ask is answered with the sentence above, a scripted Permission is answered deny, and each turn still ends idle with the reply
- a turn scripted to end blocked, and one ending paused, report that end
- a turn with no end inside a short limit reports timed out
- the stop helper sends one `rho.session.stop` frame

Update ARCHITECTURE.md's `com.liftoff.app.work` row ("Exists yet?" column, currently `—`) to name the turn runner. Don't touch the M3 row.

Must not break: the copied files in `com.liftoff.app.rho` (unchanged, per ARCHITECTURE invariant 3), Test connection and its tests, and the build gate. Follow CLAUDE.md: `area: lowercase summary` commits, named files only, LF endings. The full gate can hang once for about 5 minutes on this machine. That is a known flake: run it detached and re-run it rather than investigate.

Done when: the fake-daemon commit is cherry-picked, the turn runner and its tests are in, the ARCHITECTURE.md row is updated, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
