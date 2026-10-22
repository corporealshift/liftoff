# Map — GenerationWorker (one successful generation)

## What this run touches

| File | What it holds / why it matters |
|---|---|
| `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` | **to write.** A `CoroutineWorker` in `com.liftoff.app.work`, standard `(context, params)` constructor so `TestListenableWorkerBuilder` can build it without a `WorkerFactory`. |
| `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` | **to write.** Robolectric `@Config(sdk = [34])`, uses the application's own container + `FakeDaemon`. |
| `ARCHITECTURE.md` | line 36, the `com.liftoff.app.work` row: status cell currently reads `Partly (CoachTurnRunner.kt: …; GenerationWorker not written yet)`. Line 82 is the M3 milestone row (`Not started — … the worker does not`); the brief names only the `work` row. |

## The turn runner (already merged, PR #33)

`app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` — plain JUnit-tested in
`app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt` (11 tests, no Robolectric).

- `enum class TurnEnd { Idle, Completed, Blocked, Paused, Error, TimedOut }`
- `data class CoachSessionOptions(workspace: String, labels: List<String>, description: String)`
- `data class TurnOutcome(sessionId: String, cursor: String?, end: TurnEnd, lastAssistantText: String?)`
- `suspend fun runCoachTurn(client, sessionId: String?, options, prompt, clientId, cursor, limit: Duration, onSessionId: (String) -> Unit, onCursor: (String) -> Unit): TurnOutcome`
  - creates a session (`rho.session.create`, `permission_mode = "auto"`, labels, description) **only when `sessionId == null`**; otherwise reuses it
  - `rho.session.subscribe` → `rho.session.send_prompt` (carries `client_id`) → `rho.session.events_after` catch-up → then pushes
  - answers `rho.rpc.ui.ask` with `{"answer": "Decide yourself; there is nobody to ask."}` and `rho.rpc.permission.request` with `{"verdict": "deny"}`
  - `withTimeoutOrNull(limit)`; no end found ⇒ `TurnEnd.TimedOut`; cursor handed back is the furthest event this attempt read
  - a connection that dies mid-turn throws `DaemonException` (no code, `isPermanent == false`)
  - a cursor the daemon forgot (`RpcCodes.CURSOR_UNKNOWN`) is retried once from the start; every other refusal goes out
  - **never closes the client**; the callbacks are non-suspend `(String) -> Unit`
- `suspend fun stopCoachSession(client, sessionId)` — `rho.session.stop`. The runner never calls it; stopping is the caller's.

## The copied client

`app/src/main/java/com/liftoff/app/rho/net/DaemonClient.kt`
- `DaemonClient(baseUrl, token, clientName = DaemonConnectionCheck.CLIENT_NAME)`; `baseUrl` is rho's `http://host:port/` form and `connect()` swaps `http`→`ws`
- `suspend fun connect()`, `suspend fun callOrThrow(method, params): JsonElement`, `suspend fun respond(id, result)`, `fun close()` (**not** suspend), `suspend fun awaitClosed(): String`, `val incoming: SharedFlow<Incoming>` (replay 0)
- `sealed interface Incoming { Event, Ask, Permission, Other }`; `class DaemonException(message, code: Int? = null)`, `isPermanent`
- `app/src/main/java/com/liftoff/app/rho/DaemonConnectionCheck.kt`: `suspend fun check(): ConnectionOutcome` — `Connected(daemonVersion, protocolVersion)` | `NotConfigured` | `Unreachable(reason)` | `AuthenticationRejected` | `ProtocolMismatch(...)`; `TIMEOUT_MS = 10_000`, `CLIENT_NAME = "liftoff"`.

## The container

`app/src/main/java/com/liftoff/app/AppContainer.kt` (held by `LiftoffApplication.container`, which `onCreate` builds; no WorkManager configuration anywhere in the app)
- `settingsStore` (DataStore file `settings`), `database` (`Room.databaseBuilder(..., "liftoff.db")` — **on disk**, not in-memory), `missionManager` (real clock)
- `suspend daemonConnectionCheck()` and `suspend daemonClient()` both re-read the settings and trim host/token; `daemonClient()` is **unconnected** — the caller connects and closes it.

## The row

`app/src/main/java/com/liftoff/app/data/Generation.kt` — `id` (auto), `kind`, `missionId`, `sortieId?`, `status`, `nabuSessionId?`, `lastEventId?`, `attempt = 0`, `error?`, `createdAt`, `finishedAt?`
`GenerationDao.kt` — `get(id)`, `markRunning(id)`, `saveSessionId(id, s)`, `saveLastEventId(id, e)`, `markSucceeded(id, finishedAt)`, `markFailed(id, error, finishedAt)`, `markCancelled(...)`, **`bumpAttempt(id)`** (exists, but is not in the brief's list of calls; `GenerationDaoTest` proves it climbs 0→3), plus the export/import and "newest non-cancelled" reads.
`Enums.kt` — `GenerationKind { OUTLINE, FLIGHT_PLAN }`, `GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`, `SortieType { RUN, LIFT }`, `SortieState { PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED }`, `FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }`.

## What the worker reads to build the prompt and the context

- `SortieDao.get(sortieId)` → `Sortie.index` (for `flightPlanInput(missionId, sortie.index)`) and `Sortie.type` (for `GenerationContext.sortieType`)
- `MissionDao.get(missionId)` → `Mission.pattern`
- `EquipmentDao.observeActive(): Flow<List<Equipment>>` — **no one-shot "active only" suspend query exists**; `getAll()` is one-shot but returns inactive rows too. `insertAll(list)` is the bulk insert.
- `PromptInputLoader(db, settingsStore, clock = Clock.systemUTC(), zone, historyWindowDays = null)`: `suspend outlineInput(missionId)`, `suspend flightPlanInput(missionId, sortieIndex)` → `CoachPromptInput`
- `CoachPromptBuilder.prompt(input): String` (an `object`)
- `CoachReplyValidator.validate(raw: String, context: GenerationContext): CoachReplyResult` — `Valid(plan, json)` | `Invalid(errors: List<String>)`; `GenerationContext(kind, pattern, sortieType? = null, equipmentKeys, sortieLengthMinutes)` **requires** `sortieType` when `kind == FLIGHT_PLAN`
- `MissionManager.storeOutline(missionId, valid)` / `storePlan(sortieId, valid)` → `StoreOutcome.Stored` | `StoreOutcome.Refused(reason)`
  - outline refuses: mission gone, mission not `DRAFT`, week ended, outline/pattern mismatch. On success it activates the Mission, inserts one sortie per pattern letter and `advance`s, so sortie 1 becomes current.
  - plan refuses: sortie gone, no mission, mission not `ACTIVE`, sortie not `PENDING`/`PLANNED`, sortie not the current one. On success it writes the plan (source `GENERATED`) and marks a `PENDING` sortie `PLANNED`.

## The fake daemon (test source set, `com.liftoff.app.rho`)

`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`
- `script(vararg DaemonScript)` where `DaemonScript.Assistant(text)`, `.State(to, reason)`, `.Ask`, `.Permission`; `nextTurn()` **repeats the last script** when the list runs out
- `sessionId` defaults to `"session-1"`; `host()`, `port()`, `frames`, `framesFor(method)`, `requests`, `requestCount()`, `log`, `logEvent(type, data)`, `answers`, `refuseMethod[method] = code to message`, `refuseUpgrade`, `stop()` (closes both ends)
- **`rho.session.send_prompt` dedupes by `client_id`** (spec 7.4): a second prompt with a client id it has seen returns the same `event_id` and plays **no new turn**
- `rho.session.stop` appends a `state_change` to `"completed"`; `rho.session.events_after` refuses an unknown cursor with `RpcCodes.CURSOR_UNKNOWN`
- event ids are `"01E" + 23-digit counter`, so log order == id order

## Test-shape facts already in this repo

- `CoachTurnRunnerTest`: `DaemonClient(baseUrl = "http://${daemon.host()}:${daemon.port()}", token = "sekrit")` then `client.connect()`; its own `answerWindow = 15.seconds` wraps every call, turn limits 500 ms / 2 s / 1 h.
- `AppContainerTest` (Robolectric, sdk 34) is the one test that uses `app.container` directly: `app.container.settingsStore.setDaemonHost/setDaemonPort/setDaemonToken`, `app.container.daemonConnectionCheck()`, `app.container.daemonClient()`, `client.close()` then `daemon.stop()`.
- Screen/DAO tests build their **own** in-memory Room + their own named DataStore file; only the container tests touch the on-disk `liftoff.db` and `settings.preferences_pb`.
- `CoachFixtures` (`app/src/test/java/com/liftoff/app/coach/`): `fixture(OUTLINE | LIFT_PLAN | RUN_PLAN | LIFT_PLAN_FENCED | OUTLINE_PATTERN_MISMATCH …)`, `KEYS = [barbell, dumbbell, bench, pullup-bar]`, `OUTLINE_CONTEXT` etc.
- `MissionManagerTest` seeds with a fixed clock; the worker test must use `container.missionManager` (real clock) and `onAppOpen()` so the DRAFT is the current week. `setGenerateRunPlans(true)` before `onAppOpen()` is what makes a run sortie appear (default pattern `RLRLR`, `SettingsStore.kt:35`).
- The `android-dev` skill names two deadlocks that have already hung this kind of suite: a Room `Flow` read with `.first()` inside `runBlocking` under Robolectric (main looper paused ⇒ hang; fix is a `suspend` one-shot query beside the `Flow`), and `MockWebServer.enqueue` once against two connections (fix is a `Dispatcher`). Relevant to the worker's `equipmentDao().observeActive().first()`.
- `app/build.gradle.kts` already has `androidx.work:work-runtime-ktx:2.9.1` (implementation) **and** `androidx.work:work-testing:2.9.1`, robolectric 4.14.1, androidx.test:core 1.6.1, mockwebserver 4.12.0 — no dependency change needed.

## Rules that bind this run

- `CLAUDE.md`: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages `area: lowercase summary`; stage named files only; §2 vocabulary (`Mission`/`Sortie`/`FlightPlan`/`Generation`, never "session" for a sortie); short comments, why not what; LF endings.
- `ARCHITECTURE.md` invariant 6 (resume from the stored cursor) and invariant 5 (one `AppContainer`, no DI framework).
- The brief: no Room schema change; `coach` stays free of Android imports; every wait bounded so CI (ubuntu-latest) passes.
