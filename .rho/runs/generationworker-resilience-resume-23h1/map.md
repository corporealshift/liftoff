# Map — GenerationWorker resilience: resume, reconnect, offline

Base for the worker/test/FakeDaemon picture: `.rho/runs/generationworker-repairs-and-the-ways-a-621y/map.md`
(that run's facts are still true except where this file updates them). Everything below was read off HEAD
`7dbc9c3` (which contains merged PRs #34 repairs-flow, #35 outcomes, #36 enqueuer).

## Files this run touches

**`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`** (352 lines) — plain `CoroutineWorker`,
`(context, params)`; everything through `(applicationContext as LiftoffApplication).container`.
`doWork()` (51-87): row by `GENERATION_ID` (`NO_SUCH_GENERATION = -1L`) → failure if no row → success if
`row.status in FINISHED_STATUSES` → `markRunning` → mission/sortie existence guards through
`failGeneration(..., client = null, sessionId = null)` (66, 69) → the connection `when` at **72-86**:
`Connected -> runGeneration(...)` (73), `ProtocolMismatch -> failGeneration` with `mismatchMessage(...)` (77-83),
**`else -> Result.retry()` (85)** — that arm is what catches `Unreachable`, `NotConfigured` and
`AuthenticationRejected` today, with no mark on the row.

- `runGeneration` (90-145): `container.daemonClient()`, then **`client.connect()` at 102 inside the same `try`
  as the turn**, so a connect that fails and a drop mid-turn both land in `catch (dropped: DaemonException)`
  (127-141): `isPermanent -> failGeneration(...)`, otherwise `Result.retry()` (140). `finally { client.close() }`.
  `reportedSessionId` (100) is held so the catch can give the session up.
- `runCoachTurn` call (110-125): `sessionId = row.nabuSessionId`, `clientId = "${row.id}-${row.attempt}"` (116),
  `cursor = row.lastEventId` (117), `limit = TURN_LIMIT`, callbacks wrapped so `reportedSessionId` is recorded.
- `savingProgressTo` (153-...): turn callbacks are non-suspend, so each row write goes on an unlimited
  `Channel<suspend () -> Unit>` drained in order by one coroutine, joined in a `finally` even when the turn threw.
- `storeFinishedTurn` (180-263): loops. `Blocked -> failGeneration("coach needed input")` (203-205);
  **`turn.end != Idle && != Completed -> Result.retry()` (206) — this is the arm `Paused` reaches today, and no
  `rho.session.resume` is sent**; `Invalid` → `bumpAttempt` (225) then a repair turn on the same session with
  `clientId = "${row.id}-$attempt"` where `attempt` is read back off the row (216, 228), capped by `MAX_REPAIRS`;
  `StoreOutcome.Refused -> failGeneration(stored.reason)` (253-255); success → `markSucceeded` +
  `giveUpSession(client, turn.sessionId)` (257-261).
- `failGeneration` (279-288) = `markFailed(id, reason, clock.millis())` + optional session stop + `Result.failure()`.
- `giveUpSession` (292-...) swallows everything, so a refused stop never changes a decided outcome.
- companion: `labelsFor` (309, invariant 7), `descriptionOf` (315). File-private `TURN_LIMIT = 20.minutes` (334),
  `mismatchMessage` (347), `majorOf` (352). `MAX_REPAIRS` is in the companion.
- `runAttemptCount` is available directly on the worker (inherited from `ListenableWorker`/`WorkerParameters`);
  nothing in this file reads it today.

**`app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`** (222 lines) —
`runCoachTurn(client, sessionId, options, prompt, clientId, cursor, limit, onSessionId, onCursor): TurnOutcome`.
`TurnEnd { Idle, Completed, Blocked, Paused, Error, TimedOut }` (40; `"paused"` is already mapped, 62).
`TurnOutcome(sessionId, cursor, end, lastAssistantText)` (50). Flow (153-183): create only when `sessionId == null`
(155) → `onSessionId` → `subscribe` → `send_prompt` with `client_id` → `promptId` from the answer (165) →
**one catch-up read `eventsAfter(client, session, lastCursor)` (171)** → drain buffered pushes → `receive()` until
an end. `handle` (120-140) accepts an assistant message or a state_change only when `event.id > promptId`;
`moveCursorTo` (111) only moves forward. `stopCoachSession` (195) sends `rho.session.stop`; the runner never calls it.
`eventsAfter` (211-221) retries once from the start on `CURSOR_UNKNOWN` only.

**The gap this run has to close:** when the row's stored cursor is already past the reply and the end, the single
catch-up read returns nothing useful, `end` stays null, and the runner sits in `pushed.receive()` until
`TURN_LIMIT` (20 minutes). The test's 30 s `withTimeout` kills it first. A second read from the prompt's own id is
what finds a reply the cursor has already walked past.

**`app/src/main/java/com/liftoff/app/data/GenerationDao.kt`** (72 lines) — `insert`, `get`, `getAll`, `insertAll`,
`countAll`, `update`, `delete`, `markRunning`, `markSucceeded`, `markFailed(id, error, finishedAt)`, `markCancelled`,
`saveSessionId`, `saveLastEventId`, `bumpAttempt(id)` (`attempt = attempt + 1`), `getNewestNonCancelledForSortie`,
`getNewestNonCancelledOutlineForMission`. **No query sets or clears `error` on its own** — `markFailed` is the only
writer of `error` and it also writes status + finishedAt. A new `@Query` is needed; that is not a schema change.

**`app/src/main/java/com/liftoff/app/data/Generation.kt`** (20 lines) — `@Entity(tableName = "generation")`:
`id`, `kind`, `missionId`, `sortieId?`, `status`, `nabuSessionId?`, `lastEventId?`, `attempt = 0`, `error?`,
`createdAt`, `finishedAt?`. `GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }` and
`GenerationKind` are in `data/Enums.kt` (line 9).

**`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`** (398 lines) — `MockWebServer` WebSocket fake.
- `send_prompt` (231-249) dedupes by `client_id` through `private val promptIds` (100): a repeat answers with the
  earlier `event_id` and **plays no new turn**.
- `logEvent(type, data)` (189) → `append(...)` only: it does **not** register a `client_id` in `promptIds`, so a
  seeded user message is not deduped and a re-send of the same client_id would append a second message.
- `events_after` (251-263): no cursor → whole log; unknown cursor → `CURSOR_UNKNOWN`; otherwise `log.drop(at + 1)`.
- **No `rho.session.resume` handler**: it falls to the catch-all at 275 (`{ok:true, method:...}`), which is still
  recorded in `frames`, so a resume frame is observable today but the fake does nothing for it.
- `append` (281-301) logs and pushes to every subscriber. `play`/`hold`/`nextTurn` (316-372) script **one turn**;
  `nextTurn()` repeats the last scripted turn when the list runs out.
- `refuseMethod` is a public `ConcurrentHashMap<String, Pair<Int, String>>` (85) read for any method (146).
  `refuseProtocolMismatch` (171). `sessionId` is a fixed `"session-1"` (78) answered for every create, so "one
  session" is proved by counting create frames. `framesFor(method)` (198) is a substring filter on raw frames.
  `stop()` (201) shuts the server down. `host()`/`port()` (191/193). `nextEventId()` = `"01E" + 23-digit counter`
  (390) — fixed width, so `>` compares in log order.
- No script step drops a connection; a drop has to be arranged another way (e.g. `stop()` before `runWorker`, or a
  new `DaemonScript` step).

**`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`** (1049 lines) — the harness to reuse.
Robolectric `@Config(sdk = [34])`; `@Before seedContainer` starts `FakeDaemon` and writes host/port/token
(`SECRET_TOKEN`) + `COACH_WORKSPACE` into `container.settingsStore`, plus `equipmentDao().insertAll(...)`;
`@After tearDown` stops the daemon, closes the DB, deletes `liftoff.db` and the settings file. `turnWait = 30.seconds`
(69). `runWorker(id)` (876) = `runBlocking { withTimeout(turnWait) { TestListenableWorkerBuilder<GenerationWorker>(
context).setInputData(workDataOf(GenerationWorker.GENERATION_ID to id)).build().doWork() } }` — **no
`setRunAttemptCount` anywhere yet**; the builder does have `setRunAttemptCount(int)` (confirmed in
`work-testing-2.9.1`).
Helpers: `sortieAt` (885), `insertFlightPlanGeneration` (888), `insertOutlineGeneration` (901),
`assertAwaitingGeneration` (918), `assertPlannedWithAGeneratedPlan` (927), `assertNothingReachedTheDaemon` (947),
`assertSessionGivenUpOnce` (957), `assertSessionLeftOpen` (967), `mismatchFailedRow` (979),
`assertNoSessionWasOpened` (998), **`frameFor(method)` (1009 — asserts exactly one frame and returns its params)**,
`promptsSent()` (1018), `methodsSent()` (1025), `jsonObject` (1028), `eventId` (1034).
Existing cases (18) include `aGenerationRowWithASessionIdAlreadyStoredIsResumedWithoutCreatingAnotherSession` (296,
seeds a cursor with `daemon.logEvent(EventType.MESSAGE, ...)` and inserts a row carrying `nabuSessionId` +
`lastEventId`), `aPausedTurnRetriesAndLeavesTheRowRunningWithItsSessionAndCursor` (798 — asserts Retry, the create
frame, `client_id == "$generationId-0"`, RUNNING, session + cursor kept, `error` null, `attempt` 0, session left
open; asserts **no** resume frame today), and `aTurnThatEndsInErrorRetries...` (842).

**`app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt`** (751 lines) — `twoEndingStateChangesAfterThePrompt
StopAtTheFirst` (451) is the shape the brief's re-send case copies: it sends a prompt under `clientId`, seeds the
reply and two ends with `logEvent`, calls `runCoachTurn` again with `cursor = promptId`, then asserts the log holds
**one** message for that client_id and that two `send_prompt` frames went out. `promptEventId(clientId)` (746) finds
the prompt event by its client_id. No existing test anywhere seeds a message that carries a `client_id` through
`logEvent`, so making the fake register one changes no current case.

**`app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt`** (237 lines) — in-memory Room, `queueGeneration(...)`
helper (83), and cases for the mark helpers, `saveSessionId` / `saveLastEventId` / `bumpAttempt`, and the two
newest-non-cancelled reads. This is where the new `error` query and `isCoachOffline` belong.

## What already exists to build on

- `ConnectionOutcome` (`rho/DaemonConnectionCheck.kt` 26-48): `Connected`, `NotConfigured`, `Unreachable(reason)`,
  `AuthenticationRejected`, `ProtocolMismatch`. The check uses its own OkHttp client, **connectTimeout 5 s,
  readTimeout 10 s** (`TIMEOUT_MS`, comment at 71), so a closed port fails fast — well inside `turnWait`.
- `DaemonException(message, code: Int?)` (`rho/net/DaemonClient.kt`): `isPermanent = code != null && code in
  RpcCodes.PERMANENT`. `RpcCodes.PERMANENT` excludes `UNAUTHORIZED`, `PROTOCOL_MISMATCH`, `INTERNAL_ERROR`,
  `CURSOR_UNKNOWN`, `PERMISSION_DENIED`, `ALREADY_RESOLVED`. A socket that dies throws with `code == null`.
- `AppContainer`: `fun generationEnqueuer() = GenerationEnqueuer(WorkManager.getInstance(appContext))` is asked for
  per call, so building a container touches nothing WorkManager-related. `daemonConnectionCheck()` and
  `daemonClient()` read host/token at call time.
- `GenerationEnqueuer` (37 lines): one unique one-time request per generation, name `gen-<generationId>`, `KEEP`,
  network constraint, 1-minute exponential backoff.

## Facts worth not re-deriving

- `doWork` calls `markRunning` (61) **before** the connection check, so a row the worker marks offline is always
  RUNNING by then, never QUEUED. QUEUED only matters for `isCoachOffline`, which a Launchpad row read can hold.
- **WorkManager's `runAttemptCount` is 0-based**: the first attempt is 0 and each `Result.retry()` adds one
  (`WorkerParameters.getRunAttemptCount()` is `@IntRange(from = 0)`). So the brief's "8th attempt" is
  `runAttemptCount == 7`, and the test builds the worker with `setRunAttemptCount(7)`.
- The row's `attempt` is **not** WorkManager's attempt count: only `bumpAttempt` (repairs) changes it, so
  `client_id = "<id>-<row.attempt>"` stays put across WorkManager retries. The two counters must not be merged.
- `ARCHITECTURE.md` line 36 (the `com.liftoff.app.work` area-map row) and line 82 (the M3 row) both currently end
  with a claim that resilience beyond the current outcomes is still to come. Both rows are grepped by a run's
  `verify.sh`; wording must name each thing the brief demands, one clause each.
- `CLAUDE.md`: gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only; §2 vocabulary (`Mission`, `Sortie`, `FlightPlan`,
  `Generation` — never "session" for a sortie); snake_case JSON; LF; short comments (why, not what).
- Known suite flake: `InFlightScreenTest` body-weight stepper case; the gate can also hang ~5 min on this machine —
  run it detached (`mkdir -p app/build` first) and re-run rather than "fix" it.
