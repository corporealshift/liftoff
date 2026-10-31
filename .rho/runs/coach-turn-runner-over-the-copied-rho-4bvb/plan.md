# Plan

## Approach

The work has two steps, in the brief's order. First, bring in the finished scripted fake. Second, write one Android-free file in a new package and test it against that fake. Nothing new has to be learned about the protocol. The copied `DaemonClient` already exposes everything the runner needs (`callOrThrow`, `respond`, `incoming`, `EventsAfterParams` / `EventsAfterResult`), and the picked fake already answers those methods and plays a scripted turn.

The runner is a suspend function. It works in this order:
- It starts collecting `incoming` into a channel **before** it sends anything. The flow has `replay = 0`, so an event pushed while nobody is collecting is lost.
- If it was not handed a session, it creates one and reports the id.
- It subscribes, then sends the prompt.
- It judges the pushed events and the caught-up events against the prompt's `event_id`. Along the way it keeps the last assistant message, moves the cursor and answers any Ask or Permission.
- It stops when a `state_change` ends the turn or the limit runs out.

The runner only looks at pushed events whose `sessionId` is its own session. The cursor only ever moves forward, by string comparison of ids, and the cursor callback fires only on a forward move. "Last assistant message" means the assistant `message` with the highest id after the prompt's `event_id`. It does not mean the last one handled, so it does not matter whether catch-up events or pushed events are handled first.

There is no Room, no WorkManager and no Android import, so the tests are plain JUnit against a real socket on localhost. See the map for the fake's API and the client's shapes.

## Files

- `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` (new). Creates the `com.liftoff.app.work` package. It holds:
  - the turn runner;
  - the value it returns: session id, final cursor, how the turn ended, and the last assistant text or null;
  - the end-reason values;
  - the `rho.session.stop` helper.

  No Android imports, and nothing in it touches Room or WorkManager.
- `app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt` (new). The tests below, in plain JUnit 4 against `FakeDaemon`. `FakeDaemon` is public in the same test source set, so it does not need to move.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`. Changed only by the cherry-pick of `6b1063e`, with no hand edits.
- `ARCHITECTURE.md` line 36. Only the "Exists yet?" cell of the `com.liftoff.app.work` row changes: it names the turn runner and says it uses neither Room nor WorkManager. The Responsibility cell and the M3 milestone row stay as they are.

## Order

1. Cherry-pick `6b1063e`, keeping its message. Check that `git show --stat HEAD` names only `FakeDaemon.kt` and no `.rho/runs/` file. Then run `DaemonConnectionCheckTest` and `AppContainerTest`. The build passes.
2. Write `CoachTurnRunner.kt`. `bash gradlew.sh :app:compileDebugKotlin` passes.
3. Write `CoachTurnRunnerTest.kt` and run only that class, with no build cache (`:app:testDebugUnitTest --tests 'com.liftoff.app.work.CoachTurnRunnerTest' --no-build-cache`). Read `tests=` / `failures=` / `errors=` from the JUnit XML, not from stdout.
4. Update the ARCHITECTURE.md row.
5. Run the whole gate detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`. Write a log under `app/build/` and an `EXIT=$?` sentinel that is not behind a pipe. Then list every result XML in one pass to show the whole suite ran. If it hits the known ~5-minute hang, re-run it rather than investigate.

Commits follow CLAUDE.md: `area: lowercase summary`, only named files staged, LF endings.

## Testing

Everything is in `CoachTurnRunnerTest`: plain JUnit 4 with `runBlocking` and `withTimeout`, against `FakeDaemon` on a real localhost socket. No Robolectric and no virtual time, because the fake runs on real threads. Each line is one test:

- **Fresh turn.** Exactly one `rho.session.create` frame, carrying the workspace, `permission_mode: "auto"`, the labels and the description. One `send_prompt` frame carrying the given `client_id`. The returned text is the scripted assistant message, and the turn ended `idle`. The callbacks saw the session id and a final cursor.
- **Session id and cursor handed in.** No `rho.session.create` frame at all. The cursor comes from `logEvent`, so the fake knows it.
- **Old idle state.** A `state_change` to `idle` seeded with `logEvent` before the prompt does not end the turn. The turn still ends on the scripted one.
- **Ask and Permission.** A scripted Ask is answered with the sentence the brief gives, and a scripted Permission is answered `deny`. Both answers are read from the fake's `answers`. Each turn still ends `idle` with its reply.
- **Blocked and paused.** A turn scripted to end `blocked`, and one scripted to end `paused`, each report that end.
- **Timeout.** A turn with no end inside a short limit reports `timed out`, with the cursor it reached.
- **Stop helper.** The stop helper sends exactly one `rho.session.stop` frame.
- **Carried over from step 1.** `DaemonConnectionCheckTest` (7) and `AppContainerTest` (8) still pass unchanged after the cherry-pick.

## Risks

- **A wait that never ends eats the build.** `app/build.gradle.kts:48` kills the test task at 5 minutes and writes no `TEST-*.xml`, which reads as a missing test rather than a hang. Every wait in the tests and in the runner is bounded by a `withTimeout` far below that. The runner's own limit is a parameter, and the tests set it to a fraction of a second. The runner returns `timed out` through `withTimeoutOrNull` rather than letting the cancellation escape.
- **Events pushed before a collector exists are lost**, because `incoming` has `replay = 0`. The collector starts before `subscribe` and before the prompt, and is cancelled in a `finally`.
- **The fake answers `send_prompt` first and only then plays the turn**, so the turn's pushes arrive almost on top of the response. The runner buffers them in a channel and judges them only once it holds the `event_id`, as the brief says. The catch-up `events_after` goes through the same handling and skips ids already seen.
- **Catch-up events and pushed events can be handled in either order.** If the cursor or the "last" message followed handling order, the cursor could move backwards or the reply could be an older message. Both follow the highest id instead.
- **`CURSOR_UNKNOWN` makes the runner refetch from null, which returns the whole log**, including events from before our prompt. The end check compares against the prompt's own `event_id`, not against "the next event", so an old `state_change` cannot end the turn. The `logEvent` test proves this too.
- **Id comparison must stay a string comparison.** The fake's ids are fixed-width (`01E` plus a 23-digit sequence), so log order is id order. Parsing timestamps instead would make the runner depend on a clock and could split on CI.
- **Once its script list runs out, the fake repeats its last scripted turn**, so a test that prompts twice gets the same turn again. When the replies must differ, script once per prompt.
- **Invariant 3 and the caller's client.** The runner sends only existing protocol methods, edits nothing under `com.liftoff.app.rho`, and never closes the client it was handed. Closing it stays the caller's job.
- **Invariant 7 (labels never start with `run:`) belongs to the caller, not the runner.** The runner passes on the labels it is given unchanged. The worker that supplies them is where that rule belongs.
- **The full gate can hang for about 5 minutes on this machine.** Run it detached with a log and re-run it. If a stranded test executor is holding `app/build/test-results`, run `bash gradlew.sh --stop` and delete that directory before the re-run.
- **The whole-suite run at the end can fail on a flake that is not ours.** One In-Flight screen test fails at random with a closed Room pool and passes when the same tree is re-run, and this brief names it nowhere. Re-run the gate before treating it as broken, and change no test to make it pass.

## Decisions

- **A call that fails: report `ended = error`, or let `DaemonException` out?** The brief's return shape (session id, cursor, one of six end reasons, last text) has no room for an error code. The plan reads that as meaning a daemon that answers and refuses should show up as `error`, so a refusal (`code != null`) ends the turn as `error`, with the cursor it reached. The plan does *not* swallow a call that never got an answer at all (`code == null`: no connection, dropped socket). That is not a turn ending but the connection failing, and the copied client already says how to handle it: give up the socket and let the reconnect catch up from the stored cursor. The alternative is to report both as `error`. That would let the worker treat a dead daemon as a finished turn and spend its repair budget on it. The cost: a refusal the daemon judged permanent arrives as `error` with no code attached, so the worker has to decide whether to try again instead of being told.
  Changed by review: the plan chose to report a refused call as `ended = error`; the review chose to let every `DaemonException` out unchanged (code and `isPermanent` intact), except `CURSOR_UNKNOWN` from `events_after`, which the runner handles by refetching from null. Reasons:
  - The six end reasons are the daemon's `state_change` targets plus our own timeout, so `error` should mean the session itself went to error.
  - A refused `rho.session.create` has no session id to put in the returned value.
  - A stale stored session id (`SESSION_NOT_FOUND`) or `WORKSPACE_UNTRUSTED` must reach the worker with its code, so the worker can start a new session or give up, rather than read it as a failed turn.
  - Nothing is lost by throwing: the session id and cursor were already reported through the callbacks as they became known.

- **What the time limit covers: the whole turn, or only the wait after the prompt?** The plan measures the limit from the moment the runner is called, so creating the session, subscribing and prompting all spend from the same budget. The alternative is to limit only the wait for the turn to end. That would let a turn that never reaches the prompt run for as long as the daemon likes, which is exactly the case a limit exists to catch. The worker's own deadline is a whole-task budget, so a whole-turn limit lines up with it.

- **On a timeout, which cursor comes back: the furthest event actually read, or the one that was passed in?** The plan returns the furthest id the runner has actually read. The alternative is to return the stored cursor unchanged, on the grounds that nothing was confirmed. Continuing from the last event read is what a caller would expect, and ARCHITECTURE invariant 6 exists for it: the cursor is the only thing the worker keeps between attempts, so a retry should pick up where this attempt stopped instead of re-reading the same events. A dropped connection is the exception: nothing comes back, and the caller resumes from what it already stored.

- **Does the session-id callback fire when the caller already supplied the id?** The plan calls it once, as soon as the runner knows the id, whether the runner created the session or was handed one. The alternative is to fire it only on `rho.session.create`. That would leave the worker holding the id it passed in rather than the id the runner is actually using, and would make the callback a special case instead of the one place the id is reported.

- **The runner never stops the session itself, not even on a timeout.** The brief asks for a separate `rho.session.stop` helper that "the worker calls later", so the runner's job ends at reporting how the turn ended, and stopping stays the caller's decision. The alternative is to stop the session automatically on a timeout. That would end a session the caller may want to keep and prompt into again, and would make the helper redundant in exactly the case it was asked for.

- **Answer a question that is not from our turn?** `rho.session.subscribe` re-sends any question the daemon is still holding from an earlier prompt, so the runner can be handed an Ask or Permission request that belongs to an earlier turn. The plan answers it the same way as one from our own turn, with the fixed sentence or `deny`, and keeps waiting. The alternative is to ignore anything not caused by our prompt. That would leave the daemon holding a request forever and, in the fake, would leave the rest of our own scripted turn unplayed. A runner with nobody watching that reaches a question meant for a person should decide it, not stall on it.
