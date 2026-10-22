# Map — GenerationWorker repairs and failure outcomes

## Files the run touches

**`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`** — the worker. Plain `CoroutineWorker`,
`(context, params)` constructor; reaches everything through
`(applicationContext as LiftoffApplication).container`. Current shape of `doWork()`:
read row by `GENERATION_ID` ("generationId"; missing key → `NO_SUCH_GENERATION = -1L`) → `Result.failure()`
if no row → `Result.success()` if `row.status in FINISHED_STATUSES` (SUCCEEDED/FAILED/CANCELLED) →
`markRunning` → mission/sortie existence guards via `failRow(...)` →
`when (container.daemonConnectionCheck().check()) { Connected -> runGeneration(...); else -> Result.retry() }`.

- `runGeneration` builds `container.daemonClient()`, `client.connect()`, reads
  `container.settingsStore.settings.first()`, runs `savingProgressTo(generations, row.id) { onSessionId, onCursor -> runCoachTurn(...) }`
  with `clientId = "${row.id}-${row.attempt}"`, `cursor = row.lastEventId`, `sessionId = row.nabuSessionId`,
  `limit = TURN_LIMIT`, then `storeFinishedTurn(...)`. `catch (dropped: DaemonException) -> Result.retry()`
  (no `isPermanent` check yet). `finally { client.close() }`.
- `savingProgressTo` — turn callbacks are non-suspend, so each write goes on an unlimited `Channel<suspend () -> Unit>`
  drained by one coroutine, joined in a `finally`. Reuse it for the repair prompts.
- `storeFinishedTurn` — today: any `TurnEnd` other than Idle/Completed → `Result.retry()` (so **Blocked currently
  retries**); `lastAssistantText == null` → retry; `CoachReplyResult.Invalid -> return Result.retry()` (**no repair
  loop yet**); `StoreOutcome.Refused -> return Result.retry()` (**no FAILED yet**); then `markSucceeded` +
  `stopCoachSession(client, outcome.sessionId)` + `Result.success()`. Builds `GenerationContext` from the mission's
  pattern, the sortie's type, `equipmentDao().observeActive().first().map { it.key }` and
  `settings.sortieLengthMinutes`.
- `promptFor` uses `PromptInputLoader(database, settingsStore).outlineInput(missionId)` /
  `flightPlanInput(missionId, sortie.index)` then `CoachPromptBuilder.prompt(input)`.
- `failRow(generations, id, reason)` = `markFailed(id, reason, clock.millis())` + `Result.failure()`.
- companion: `labelsFor` (invariant 7 — no label starts with `run:`), `descriptionOf(row, sortie)` one-liner naming the row.
- file-private `private val TURN_LIMIT: Duration = 20.minutes` (the turn's own deadline, not WorkManager's).

**`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`** — the harness to reuse, not rebuild.
Robolectric `@Config(sdk = [34])`. `@Before seedContainer`: start `FakeDaemon`, write host/port/token
(`SECRET_TOKEN = "sekrit"`) and `COACH_WORKSPACE = "/repo/coach"` into `container.settingsStore`, and
`equipmentDao().insertAll(CoachFixtures.KEYS.map { Equipment(key = it, name = it) })` (`add()` rejects the hyphen in
`pullup-bar`). `@After tearDown`: `daemon.stop()`, `container.database.close()`, delete `liftoff.db`, delete the
`settings` DataStore file. Helpers: `runWorker(id)` = `runBlocking { withTimeout(turnWait = 30.seconds) {
TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(workDataOf(GenerationWorker.GENERATION_ID to id)).build().doWork() } }`;
`frameFor(method)` (exactly one frame, returns its `params` as `JsonObject`); `methodsSent()` (call order);
`assertNothingReachedTheDaemon()`; `sortieAt(missionId, index)`; `insertFlightPlanGeneration`;
`assertAwaitingGeneration(sortie)`; `assertPlannedWithAGeneratedPlan`; `eventId(event)`; `jsonObject(key)`.
Existing tests (7) cover the three happy paths, missing row, already-finished row, resume-with-a-stored-session-id,
and one stop frame after success. `daemon.sessionId` is the fake's single session id.

**`app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`** — `runCoachTurn(client, sessionId, options, prompt,
clientId, cursor, limit, onSessionId, onCursor): TurnOutcome`. `TurnEnd { Idle, Completed, Blocked, Paused, Error, TimedOut }`;
`TurnOutcome(sessionId, cursor, end, lastAssistantText)`; `CoachSessionOptions(workspace, labels, description)`.
No `sessionId` → `rho.session.create`; always subscribe + `send_prompt` + `events_after` + drain pushes.
`stopCoachSession(client, sessionId)` sends `rho.session.stop`; **the runner never calls it — stopping is the caller's**.
A `DaemonException` from any call goes out unchanged (except `CURSOR_UNKNOWN`, retried once from the start); a socket
that dies mid-turn throws a `DaemonException` with `code == null`.

**`app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt`** — `fun repairPrompt(errors: List<String>): String`
(line 48): `"Your reply was not valid:"` + each error on its own line + `"Reply again with only the corrected JSON object."`
(plan decision 11: validator lines stay verbatim, one per line).

## What already exists to build on

- `CoachReplyResult` (`coach/CoachModels.kt` 34-44): `Valid(plan, json)`, `Invalid(errors: List<String>)`.
  Validator error lines are already `path: problem` (`CoachReplyValidator`, shared with `SchemaChecker`).
- `GenerationDao` (`data/GenerationDao.kt`): `markRunning(id)`, `markSucceeded(id, finishedAt)`,
  **`markFailed(id, error, finishedAt)`** — writes status/error/finishedAt only, so a FAILED row keeps its
  `nabuSessionId`, `lastEventId` and `attempt`; `markCancelled`, `saveSessionId`, `saveLastEventId`,
  **`bumpAttempt(id)`** (`attempt = attempt + 1`; nothing else in the app bumps it, so `attempt` is the count of
  repairs so far), `get`, `getAll`, `countAll`, `insert`, `update`, `delete`.
- `ConnectionOutcome` (`rho/DaemonConnectionCheck.kt` 26-48): `Connected(daemonVersion, protocolVersion)`,
  `NotConfigured`, `Unreachable(reason)`, `AuthenticationRejected`,
  **`ProtocolMismatch(expectedVersion, daemonVersion, message)`** — a refusal keeps the daemon's own message, and
  `daemonVersion` is `""` when the daemon named no version (`daemonVersionIn` regex, line 214).
  `PROTOCOL_VERSION = "1.0"` (`rho/protocol/Events.kt:15`). The check uses its own OkHttp socket, not `DaemonClient`,
  so a mismatch never opens a session.
- The sentence the brief asks for already exists in `ui/control/ConnectionViewModel.kt` 115-119 as file-private
  `mismatchRow(daemonVersion)`: `"Liftoff speaks rho protocol 1.x, the daemon speaks $daemonMajor.x"`, and
  `"...the daemon did not say which version it speaks"` when no major parses. `liftoffProtocolMajor` (line 113) is
  `"1.x"`. File-private, so the worker cannot call it.
- `DaemonException(message, code: Int?)` (`rho/net/DaemonClient.kt` 86-92): `isPermanent = code != null && code in
  RpcCodes.PERMANENT`. `RpcCodes.PERMANENT` deliberately excludes `UNAUTHORIZED`, `PROTOCOL_MISMATCH` and
  `INTERNAL_ERROR` (`rho/protocol/RpcCodes.kt` 28-38).
- `StoreOutcome` (`data/MissionManager.kt` 37-40): `Stored`, `Refused(reason: String)`; reasons are short sentences
  like `"sortie $sortieId is not the current sortie"`, `"sortie is ${sortie.state}"`.
- `FakeDaemon` (`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`): `script(vararg DaemonScript)` scripts **one
  turn** and `nextTurn()` repeats the last turn when the list runs out; `DaemonScript.Assistant(content)`,
  `State(to, reason)`, `Ask`, `Permission`. `refuseProtocolMismatch(v)` refuses `rho.hello` with
  `PROTOCOL_MISMATCH` and the message `"rho_protocol_mismatch: protocol version 1.0 is incompatible with daemon $v"`
  (so `daemonVersionIn` reads `v`); `answerHelloWithProtocol(v, daemonVersion = "0.0.0")` accepts the hello but answers
  `protocol_version: v`. `refuseMethod[method] = code to message` refuses any method. `framesFor(method)`, `frames`,
  `log`, `logEvent(type, data)`, `sessionId` (a fixed `"session-1"` the fake answers *every* create with, so "one
  session" is proved by counting create frames, not by comparing ids), `host()`, `port()`, `stop()`. `send_prompt`
  dedupes by `client_id`
  (a repeat returns the earlier `event_id` and plays nothing), `events_after` refuses an unknown cursor with
  `CURSOR_UNKNOWN`, and `rho.session.stop` appends a `state_change` to `completed`.
- `CoachFixtures` (`app/src/test/java/com/liftoff/app/coach/CoachFixtures.kt`): `OUTLINE`, `LIFT_PLAN`, `RUN_PLAN`,
  `LIFT_PLAN_FENCED`, `BAD_JSON`, `PROSE_AROUND_JSON`, `SCHEMA_VIOLATION`, `UNKNOWN_EQUIPMENT`, `PLAN_TYPE_MISMATCH`, …
  read with `CoachFixtures.fixture(name)`.

## Docs that set rules

- `CLAUDE.md`: gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages `area: lowercase
  summary`; stage named files only, never `git add -A`; §2 vocabulary; snake_case JSON; LF; short comments (why, not what).
- `ARCHITECTURE.md` line 36 — the `com.liftoff.app.work` area-map row, whose 4th cell currently ends
  "…nothing enqueues it yet, and the repairs and failure outcomes are not written yet". That claim goes false with
  this run.
- `ARCHITECTURE.md` line 82 — the M3 row: `| M3 | Generation — rho client copy, GenerationWorker, outline + lift plan
  end to end | Not started — the copied rho client and its connection check exist; the worker does not |`.
  Both rows are grepped by a run's `verify.sh`, so wording must name each thing the brief demands, one clause each.
- Invariant 6 (a stored session id + cursor make a retry a continuation of the same turn) and invariant 7 (no label
  starts with `run:`) are cited in the worker's own comments.

## Facts the verify step checked against the tree (2026-10-22)

- **Milestone table has three columns** — `| Milestone | Title | Status |` (header at line 77). So in
  `awk -F'|'` the Status cell is field `$4` for every milestone row; the area-map table has the same shape, so the
  `com.liftoff.app.work` row's status cell ("Partly (…)") is also `$4`. That cell is where the
  "the repairs and failure outcomes are not written yet" claim sits.
- **Classes the brief's "must not break" list maps to** (all exist, so naming them cannot block a green run):
  `work/GenerationWorkerTest.kt`, `work/CoachTurnRunnerTest.kt`, `rho/DaemonConnectionCheckTest.kt`,
  `ui/control/ConnectionViewModelTest.kt`, `data/MissionManagerOutlineTest.kt`, `data/MissionManagerPlanTest.kt`,
  `coach/CoachPromptGoldenFileTest.kt`.
- **The known suite flake** is `doneBodyweightSetStepperShowsBW` in `ui/inflight/InFlightScreenTest.kt` (line 601).
- **The exported schema is one file**: `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json`. This run's
  `brief.md` landed in `d2d6b2e run: brief`, so the schema baseline to compare against is its parent
  `8936b90` (the merged GenerationWorker PR #34).
- **Baseline the verify step measured on the untouched tree** (one detached `verify.sh` run, 2026-10-22): gate
  `BUILD SUCCESSFUL in 2m 35s`, `> Task :app:testDebugUnitTest` executed (not FROM-CACHE), reports total
  **593 tests, 0 failures, 0 errors, 0 skipped**. verify.sh itself read **9 passed / 12 failed, EXIT=1**: the 12
  failures are exactly the ten named tests ("did not run") plus the two ARCHITECTURE.md rows. So the suite a green
  run of this brief must reach is 593 + the new tests, and a whole-suite gate on this tree costs about 2½ minutes.
- **The M3 Status-cell rejection was tried against candidate rows**, so a work session can reword that cell freely.
  It rejects only the three shapes the cell has today or that restate them: a cell starting `Not started`, a cell
  ending in a bare `does not`, and a claim that the worker `does not exist` / `is not written`. It accepts
  "Core flow built; the worker does not yet cancel or supersede", "Partial: the worker runs one turn …; repairs
  and retries, cancellation, supersede and app wiring are not built yet", and "In progress — GenerationWorker
  exists; …". The clause window stops at `.`, `;` and `,`, so a claim about something else that really is unwritten
  is not read as the old one.
