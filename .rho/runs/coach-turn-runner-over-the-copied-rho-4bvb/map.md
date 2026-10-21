# Map — coach turn runner

What this run touches, and what is already there. Read the code you change; this is only what would otherwise have to be found again.

## Existing files

**`app/src/main/java/com/liftoff/app/rho/net/DaemonClient.kt`** (366 lines, copied from rho `ffce07d…`, invariant 3: do not edit)
- `callOrThrow(method, params: JsonElement? = null): JsonElement` — throws `DaemonException(message, code: Int?)`; `code` is null when the call never got an answer (not connected, dropped socket), and `isPermanent` is `code in RpcCodes.PERMANENT`. A failed `call` also completes every waiting call exceptionally (`fail`, line 269).
- `respond(id: JsonElement, result: JsonElement)` — answers a daemon-sent request. Single writer via a `Mutex`.
- `incoming: SharedFlow<Incoming>` — `replay = 0`, `extraBufferCapacity = 4096`, `onBufferOverflow = SUSPEND`, but `emit` uses `tryEmit` and **cancels the socket** if it fails (line 262). With no subscriber an emission is simply gone, so a collector must exist before anything is pushed.
- `Incoming` (line 73): `Event(SessionEvent(sessionId, event))`, `Permission(PermissionRequest(id, requestId, tool, summary, risk))`, `Ask(AskRequest(id, requestId, sessionId, question, choices))`, `Other(Rpc)`. `id` is the `JsonElement` to answer with. Classified from `rho.session.event` / `rho.rpc.permission.request` / `rho.rpc.ui.ask`; `rho.session.delta` and `rho.session.thinking` are dropped by the client and never reach `incoming`.
- `connect()` opens the ws (`http` → `ws`) and does `rho.hello`. `close()` closes the socket. The runner must not close a client it was handed.
- Params/result types live here, not in Events.kt: `EventsAfterParams(@SerialName("session_id") sessionId, @SerialName("last_event_id") lastEventId: String? = null)`, `EventsAfterResult(events: List<Event>, synced: Boolean)`, `CreateSessionResult(session_id)` — it ignores the `event` field the create result also carries.

**`app/src/main/java/com/liftoff/app/rho/protocol/Events.kt`** (copied)
- `Event(id, parentId, timestamp, type, data: JsonElement)`; `inline fun <reified T> Event.payload(): T?` decodes leniently and returns null rather than throwing; `Event.field(name)` for raw strings.
- `MessageData(role, content, clientId, usage, interrupted)`, `StateChangeData(from, to, reason)`, `Options(permissionMode, labels, description, …)`, `EventType.{MESSAGE, STATE_CHANGE, SESSION}`. `RhoJson` ignores unknown keys.
- `PROTOCOL_VERSION = "1.0"`.

**`app/src/main/java/com/liftoff/app/rho/protocol/RpcCodes.kt`** (copied) — `CURSOR_UNKNOWN = -32005`, `SESSION_NOT_FOUND`, `INVALID_PARAMS`, … and `PERMANENT` (which deliberately excludes `CURSOR_UNKNOWN`, `INTERNAL_ERROR`, `UNAUTHORIZED`, `PROTOCOL_MISMATCH`).

**`app/src/main/java/com/liftoff/app/rho/DaemonConnectionCheck.kt`** — Liftoff's own use of the client; `baseUrl = "http://$host:$port/"`. Shows the shape a test uses to point at the fake.

**`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`** (129 lines today) — MockWebServer-backed fake, `@Before start()` / `@After stop()`. `host()`, `port()`, `frames`, `framesFor(method)` (a substring match on `"method":"<name>"`), `requests`, `requestCount()`, `refuseMethod`, `refuseUpgrade`, `refuseProtocolMismatch`, `answerHelloWithProtocol`. Answers `rho.hello` and everything else with `{"ok":true,…}` today.

**`ARCHITECTURE.md`** — line 36 is the `com.liftoff.app.work` row, third column `—`. Line 31 is the rho row. Invariants (line 58+): 3 "Nothing in rho changes… only existing protocol methods", 4 copied-with-header, 6 "Generations are resumable from the `Generation` row (stored cursor)", 7 "Session labels never start with `run:`", 8 "up to 2 in-session repairs". `com.liftoff.app.work` does not exist yet in `app/src/main/java/com/liftoff/app/` (packages today: coach, data, domain, rho, settings, ui).

## Bringing in the scripted fake (step 1)

`6b1063e` "test: teach FakeDaemon to script a whole rho turn" — one file, `FakeDaemon.kt`, +279/-10. Verified clean: merge-base of HEAD and that commit is `2d21d87` and `FakeDaemon.kt` is byte-identical between merge-base and HEAD, so the pick cannot conflict. The branch also holds `0a2811c run: task 1 done`, a `.rho/runs/` commit the brief says not to bring.

What the picked fake gains (confirmed from the diff, so a later session need not re-derive it):
- `sealed interface DaemonScript` with `Assistant(content)`, `State(to, reason = "")`, `Ask(question, choices = emptyList())`, `Permission(tool, summary, risk = "low")`; `script(vararg DaemonScript)` adds **one turn per prompt the fake accepts**.
- `logEvent(type, data: JsonObject): String` seeds an event before any connection and returns its id.
- `sessionId` (`@Volatile`, default `"session-1"`), `log` (the event log), `answers` (`ConcurrentHashMap<String, JsonElement>` keyed by the fake's own request id, `srv-1`, …).
- `rho.session.create` → `{session_id, event}` and logs a `session` event carrying `workspace` and `options` as sent. `rho.session.subscribe` → `{subscribed:true}` and re-sends a held Ask. `rho.session.send_prompt` → `{event_id}`, and a repeated `client_id` returns the original id and appends nothing. `rho.session.events_after` → `{events, synced:true}` from the cursor, `CURSOR_UNKNOWN` for an id not in the log, whole log for a null/absent `last_event_id`. `rho.session.stop` → `{}` plus a `state_change` to `completed` / `stopped`. An unscripted method still gets `{ok:true,…}`.
- Ordering that matters: `send_prompt` **answers first, then plays the turn** in the same `onMessage`, so the pushed events arrive just after the response frame. An Ask/Permission `hold`s the rest of the turn until the client answers, then plays on.
- Event ids are `"01E" + sequence.padStart(23, '0')` — fixed width, so id order is log order and comparing ids is a string comparison. Timestamps are a fixed clock (`2026-01-01T00:mm:ssZ`), one second per event, so nothing reads a real clock and CI's UTC is irrelevant.
- `nextTurn()` **repeats the last scripted turn** once `turns` runs out; script once per prompt for different replies.

## How a test drives it

Plain JUnit 4 + `runBlocking`, as in `app/src/test/java/com/liftoff/app/rho/DaemonConnectionCheckTest.kt` (7 tests, no Robolectric — the runner has no Android imports, so neither do its tests): `FakeDaemon()` + `start()` in `@Before`, `stop()` in `@After`, `DaemonClient("http://${daemon.host()}:${daemon.port()}/", "sekrit")` then `connect()`. `app/src/test/java/com/liftoff/app/AppContainerTest.kt` (8 tests, Robolectric) is the other class the cherry-pick must leave green. Every wait wrapped in `withTimeout` well under the build's 5-minute test-task timeout (`app/build.gradle.kts:48`), because a wait that never ends kills the test task and writes no `TEST-*.xml`.

## verify.sh (written by the verify step)

`.rho/runs/coach-turn-runner-over-the-copied-rho-4bvb/verify.sh` is the run's definition of done. It runs the CI gate
(`timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, retried up to 3 times for
the known flake `doneBodyweightSetStepperShowsBW`, a daemon/memory kill, or a hung suite that wrote no report), then
reads every `TEST-*.xml` in one awk pass into `app/build/verify-work/methods` and `…/classes`, then looks each named
test up by **method name** (so the class and package stay the implementer's choice). A name that never ran is a
failure — that is what makes the script fail on untouched code.

The nine names, covering the brief's seven "Cover:" bullets (Ask and Permission split, blocked and paused split, and the
stop helper the brief asks for in the same step):

- `aFreshTurnCreatesOneSessionAndReturnsItsReplyAndCursor`
- `aSessionAndCursorHandedInIsUsedWithoutCreatingAnother`
- `aStateChangeLoggedBeforeThePromptDoesNotEndTheTurn`
- `aDaemonQuestionIsAnsweredWithTheFixedSentenceAndTheTurnStillEndsIdle`
- `aPermissionRequestIsAnsweredDenyAndTheTurnStillEndsIdle`
- `aTurnThatEndsBlockedReportsBlocked`
- `aTurnThatEndsPausedReportsPaused`
- `aTurnWithNoEndInsideItsLimitReportsTimedOut`
- `theStopHelperSendsOneSessionStopFrame`

Plus the two classes the brief says the pick must leave green, checked as whole classes (every test in them must have
run and passed): `DaemonConnectionCheckTest` (`com.liftoff.app.rho`, 7 tests) and `AppContainerTest`
(`com.liftoff.app`, 8 tests). No other class is named: the gate already runs the whole suite, so a class the brief
does not name (e.g. `ConnectionViewModelTest`, which is Test connection's own tests today) would only add a demand to
keep one particular class name.

Doc checks, both written as a rejection of the old wording:
- the `com.liftoff.app.work` table row (`grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work'`), third cell
  (`awk -F'\|' '{print $4}'`, whitespace stripped): fails while that cell is exactly `—` or empty, and fails unless
  the cell matches `turn` **and** `runner` case-insensitively. Verified against six candidate cells: `—`, an empty
  cell and `Not started` fail; `✅ (CoachTurnRunner.kt)`, `✅ (CoachTurnRunner.kt — the coach turn runner;
  GenerationWorker not started)` and `Partial: the turn runner exists; the worker is not built` all pass.
- the M3 row must be byte-identical to the same row in the tree this run started from. That tree is found as
  `git rev-list -1 HEAD -- <run dir>/brief.md` then `^` → `1976682` today, read with
  `git cat-file blob "$base:ARCHITECTURE.md"` (`git show branch:path` fails in Git Bash here; `cat-file` works).

Bring-in check: `git log --oneline -- .rho/runs/generationworker-core-flow-j5ca` must be empty and nothing under that
path may be in the tree. Both need `2>/dev/null || true`, because git errors on a path that never existed.

Ran once on the untouched tree (`app/build/verify-probe.log`): gate attempt 1 passed in 2m24s, and the script exited
1 with **5 passed / 10 failed** — the nine named tests "did not run" plus the `com.liftoff.app.work` row. The gate,
the two classes, the M3 row and the bring-in check passed. The report parser was checked separately against the 74
reports that run wrote (575 methods, 74 classes) and against a synthetic report holding a `<failure>`, an `<error>`, a
`<skipped/>` and a `name="x[0]"` testcase spread over several lines.

## Gate

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (CLAUDE.md), run from the repo root where `gradlew.sh` sits. On this machine run it detached with `mkdir -p app/build` first and a log + `EXIT=$?` sentinel — not behind a pipe, since `cmd | tail; echo $?` reports `tail`'s status. Counts come from the XML (`tests=`, `failures=`, `errors=` on each `app/build/test-results/testDebugUnitTest/TEST-*.xml`), not from `BUILD SUCCESSFUL`. The full suite can hang for ~5 minutes and that is a known flake to re-run, not investigate; `app/build.gradle.kts:48` is what kills it, and a stranded test executor holding `app/build/test-results` is cleared with `bash gradlew.sh --stop` plus deleting that directory. One In-Flight screen test also fails at random with a closed Room pool and passes on a re-run of the same tree.
