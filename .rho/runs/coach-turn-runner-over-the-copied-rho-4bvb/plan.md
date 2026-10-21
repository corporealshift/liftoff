# Plan

## Approach

Two moves, in the brief's order: bring in the finished scripted fake, then write one Android-free file in a new package and test it against that fake. Nothing new has to be learned about the protocol, because the copied `DaemonClient` already exposes everything the runner needs (`callOrThrow`, `respond`, `incoming`, `EventsAfterParams` / `EventsAfterResult`) and the picked fake already answers those methods and plays a scripted turn. The runner is a suspend function that starts collecting `incoming` into a channel **before** it sends anything (the flow has `replay = 0`, so an event pushed to nobody is gone), creates the session when none was handed to it and reports the id, subscribes, sends the prompt, and then judges pushed and caught-up events against the prompt's `event_id` — keeping the last assistant message, moving the cursor, answering any Ask or Permission — until a `state_change` ends the turn or the limit runs out. No Room, no WorkManager, no Android imports, so the tests are plain JUnit against a real socket on localhost. See the map for the fake's API and the client's shapes.

## Files

- `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` (new) — creates the `com.liftoff.app.work` package. Holds the turn runner, the value it hands back (session id, final cursor, how the turn ended, last assistant text or null), the end-reason values, and the `rho.session.stop` helper. No Android imports; nothing in it touches Room or WorkManager.
- `app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt` (new) — the tests below, plain JUnit 4 against `FakeDaemon` (which is public in the same test source set, so no relocation needed).
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — changed only by the cherry-pick of `6b1063e`; no hand edits.
- `ARCHITECTURE.md` line 36 — the `com.liftoff.app.work` row's "Exists yet?" cell only, naming the turn runner and saying it uses neither Room nor WorkManager. The Responsibility cell and the M3 milestone row are left alone.

## Order

1. Cherry-pick `6b1063e` keeping its message. Check `git show --stat HEAD` names only `FakeDaemon.kt` (no `.rho/runs/` file), then run `DaemonConnectionCheckTest` and `AppContainerTest`. Build passes.
2. Write `CoachTurnRunner.kt`. `bash gradlew.sh :app:compileDebugKotlin` passes.
3. Write `CoachTurnRunnerTest.kt` and run just that class cold (`:app:testDebugUnitTest --tests 'com.liftoff.app.work.CoachTurnRunnerTest' --no-build-cache`), reading `tests=` / `failures=` / `errors=` from the JUnit XML rather than from stdout.
4. Update the ARCHITECTURE.md row.
5. Run the whole gate detached — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` with a log under `app/build/` and an `EXIT=$?` sentinel that is not behind a pipe — and index every result XML in one pass to show the suite ran. Re-run on the known ~5-minute hang rather than investigate it.

Commits follow CLAUDE.md: `area: lowercase summary`, named files staged only, LF endings.

## Testing

All in `CoachTurnRunnerTest`, plain JUnit 4 + `runBlocking` + `withTimeout` against `FakeDaemon` on a real localhost socket — no Robolectric, no virtual time, since the fake runs on real threads. Each line is one test:

- A fresh turn: exactly one `rho.session.create` frame, carrying the workspace, `permission_mode: "auto"`, the labels and the description; one `send_prompt` frame carrying the given `client_id`; the returned text is the scripted assistant message; the turn ended `idle`; the callbacks saw the session id and a final cursor.
- A session id and cursor handed in: no `rho.session.create` frame at all.
- A `state_change` to `idle` seeded with `logEvent` before the prompt does not end the turn; the turn still ends on the scripted one.
- A scripted Ask is answered with the sentence the brief gives, a scripted Permission is answered `deny` — read out of the fake's `answers` — and each turn still ends `idle` with its reply.
- A turn scripted to end `blocked`, and one to end `paused`, report that end.
- A turn with no end inside a short limit reports `timed out`, with the cursor reached.
- The stop helper sends exactly one `rho.session.stop` frame.
- Carried over from step 1: `DaemonConnectionCheckTest` (7) and `AppContainerTest` (8) still pass unchanged after the cherry-pick.

## Risks

- **A wait that never ends eats the build.** `app/build.gradle.kts:48` kills the test task at 5 minutes and writes no `TEST-*.xml`, which reads as a missing test rather than a hang. Every wait in the tests and in the runner is bounded by a `withTimeout` far below that, and the runner's own limit is a parameter the tests set to a fraction of a second.
- **Events pushed before a collector exists are lost** (`incoming` has `replay = 0`). The collector is started before `subscribe` and before the prompt, and is cancelled in a `finally`.
- **The fake answers `send_prompt` and only then plays the turn**, so the turn's pushes arrive almost on top of the response. The runner buffers them in a channel and judges them only once it holds the `event_id`, as the brief says; the catch-up `events_after` goes through the same handling, skipping ids already seen.
- **`CURSOR_UNKNOWN` sends the refetch from null, which returns the whole log** including events from before our prompt. The end test is against the prompt's own `event_id`, not against "the next event", so an old `state_change` cannot end the turn — which is also what the `logEvent` test proves.
- **Id comparison must stay a string comparison.** The fake's ids are fixed-width (`01E` + a 23-digit sequence), so log order is id order; parsing timestamps instead would make the runner depend on a clock and could split on CI.
- **The fake repeats its last scripted turn once its script list runs out**, so a test that prompts twice gets the same turn again. Script once per prompt when the replies must differ.
- **Invariant 3 and the caller's client.** The runner sends only existing protocol methods, edits nothing under `com.liftoff.app.rho`, and never closes the client it was handed — that stays the caller's.
- **Invariant 7 (labels never start with `run:`) is the caller's, not the runner's.** The runner passes the labels it is given through unchanged; the worker that supplies them is where that rule belongs.
- **The full gate can hang for about 5 minutes on this machine.** Run it detached with a log and re-run it; if a stranded test executor is holding `app/build/test-results`, `bash gradlew.sh --stop` and delete that directory before the re-run.
- **The whole-suite run at the end can fail on a flake that is not ours.** One In-Flight screen test fails at random with a closed Room pool and passes on a re-run of the same tree, and it is named nowhere in this brief. Re-run the gate before treating it as broken, and change no test to make it pass.

## Decisions

- **A call that fails: report `ended = error`, or let `DaemonException` out?** The brief's return shape — session id, cursor, one of six end reasons, last text — has no room for an error code, which means a daemon that answers and refuses is meant to show up as `error`. The plan does that: a refusal (`code != null`) ends the turn as `error`, with the cursor reached. What the plan does *not* swallow is a call that never got an answer at all (`code == null`: no connection, dropped socket). That is not a turn ending, it is the connection failing, and the copied client already says how that is handled — give up the socket and let the reconnect catch up from the stored cursor. The alternative is to report both as `error`, which would let the worker treat a dead daemon as a finished turn and burn its repair budget on it. The cost is honest: a refusal the daemon judged permanent arrives as `error` with no code attached, so the worker decides whether to try again rather than being told.

- **What the time limit covers: the whole turn, or only the wait after the prompt?** The plan measures the limit from the moment the runner is called, so creating the session, subscribing and prompting all spend from the same budget. The alternative — limiting only the wait for the turn to end — would let a turn that never gets as far as the prompt run for as long as the daemon likes, which is the case a limit exists to catch. The worker's own deadline is a whole-task budget, so a whole-turn limit is the one that lines up with it.

- **On a timeout, which cursor comes back: the furthest event actually read, or the one that was passed in?** The plan returns the furthest id the runner has genuinely read. The alternative is to return the stored cursor unchanged, on the grounds that nothing was confirmed. Continuing from the last event read is what a caller would expect, and it is what ARCHITECTURE invariant 6 is for: the cursor is the only thing the worker persists between attempts, so a retry should pick up where this one stopped instead of re-reading the same events. (A dropped connection is the exception that proves the rule — nothing comes back, and the caller resumes from what it already stored.)

- **Does the session-id callback fire when the caller already supplied the id?** The plan calls it once, as soon as the runner knows the id, whether it created the session or was handed one. The alternative is to fire only on `rho.session.create`, which would leave the worker holding the id it passed in rather than the id the runner is actually using, and would make the callback a special case instead of the one place the id is reported.

- **The runner never stops the session itself, not even on a timeout.** The brief asks for a separate `rho.session.stop` helper that "the worker calls later", so the runner's job ends at reporting how the turn ended, and stopping stays the caller's decision. The alternative — auto-stop on timeout — would end a session the caller may want to keep and prompt into again, and would make the helper redundant for exactly the case it was asked for.

- **Answer a question that is not from our turn?** `rho.session.subscribe` re-sends a question the daemon is still holding from an earlier prompt, so the runner can be handed an Ask or Permission request that belongs to somebody else's turn. The plan answers it the same way as one from our own turn — the fixed sentence, or `deny` — and keeps waiting. The alternative is to ignore anything not caused by our prompt, which would leave the daemon holding a request forever and, in the fake, leave the rest of our own scripted turn unplayed. A headless runner that reaches a human's question should decide it, not stall on it.
