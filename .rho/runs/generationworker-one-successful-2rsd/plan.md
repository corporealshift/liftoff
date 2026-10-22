# Plan

## Approach

`GenerationWorker` is the scheduling-and-persistence half of a coach turn; everything about talking to the daemon already exists. It reads the `Generation` row, guards the statuses the brief says are terminal, marks the row `RUNNING`, asks `container.daemonConnectionCheck()` before it opens anything, and then hands the connected client to `runCoachTurn` with the prompt from `PromptInputLoader` + `CoachPromptBuilder` and the row's stored session id and cursor. The turn's session id and cursor go back onto the row through the runner's callbacks, so a worker that dies mid-turn leaves a resumable row. The runner's `onSessionId`/`onCursor` are plain (non-suspend) callbacks, while `saveSessionId`/`saveLastEventId` are suspend DAO calls. So the callbacks put each write on an unlimited channel, and one coroutine in the worker's own scope drains it in order. That channel is closed and the drain joined once the turn returns, before anything reads or marks the row. This means no write is lost or reordered and nothing blocks the runner's event loop. When the turn ends `Idle`/`Completed`, the reply goes through `CoachReplyValidator` with a `GenerationContext` built from the Mission's pattern, the Sortie's type, the active equipment keys and `sortieLengthMinutes`. A valid reply goes to `MissionManager.storeOutline`/`storePlan`, the same storage path the app already uses for a coach reply. Then come `markSucceeded`, `stopCoachSession`, and `client.close()` on every path. Nothing new is invented: the worker is the caller the turn runner was written for, and the storage half is the already-merged `storeOutline`/`storePlan`.

This run adds no scheduling: nothing enqueues the worker, and no UI reaches it. It is the class, its tests, and the `ARCHITECTURE.md` row that names it.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: new. The `CoroutineWorker` with the row guards, `markRunning`, the connection gate, the turn call with the labels/description/client id/20-minute limit, and the ordered callback writes. Then validate → store → `markSucceeded` → `stopCoachSession`, with `close()` on every path.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`: new. Robolectric `@Config(sdk = [34])`, `TestListenableWorkerBuilder<GenerationWorker>`, the application's own `container`, `FakeDaemon`, `CoachFixtures`.
- `ARCHITECTURE.md`: the `com.liftoff.app.work` row (line 36) names `GenerationWorker` and says what it does, dropping "not written yet". The M3 milestone row (line 82) is left alone. The brief names only the `work` row, and M3 also covers scheduling and the live end-to-end run, which this run does not add.
- No dependency, schema or `AppContainer` change. `work-runtime-ktx` and `work-testing` (2.9.1) are already declared in `app/build.gradle.kts`.

## Order

1. Write `GenerationWorker.kt` whole (guards, connection gate, turn, callback writes, validate/store, success, close) and get `bash gradlew.sh :app:compileDebugKotlin` green before any test exists.
2. `GenerationWorkerTest`: the outline happy path only (seed, fake script, `doWork()` under a bounded `runBlocking`, the row and Mission assertions). Run that one class.
3. Add the lift and run happy paths, with the seeding each needs (see Testing).
4. Add the remaining brief cases: a row that already has a session id, the missing row and the already-`SUCCEEDED` row, and the single `rho.session.stop` frame after success.
5. `ARCHITECTURE.md` work row.
6. One detached full gate (`:app:assembleDebug :app:testDebugUnitTest`) with the log and an `EXIT=$?` sentinel. Read the results from the `TEST-*.xml` files, not from `BUILD SUCCESSFUL`.

## Testing

Everything goes in `GenerationWorkerTest`, Robolectric `@Config(sdk = [34])`. Each case gets one `FakeDaemon` and one `TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(...)`, with `doWork()` called inside `runBlocking { withTimeout(...) }`. Every case seeds the same way:
- the application's own `container`, never a second `AppContainer`;
- `settingsStore` written with the fake's `host()`/`port()`, a non-blank token and a coach workspace path;
- `equipmentDao().insertAll` with `CoachFixtures.KEYS`;
- `container.missionManager` on the real clock.

The cases:
- **Outline happy path:** `Result.success()`, the row `SUCCEEDED` with `nabuSessionId` and `lastEventId` both set, and the Mission `ACTIVE` with its first sortie (index 0) current. Indexes are 0-based, so "sortie 1" in the brief is index 0. Seed: `onAppOpen()` only, row `kind = OUTLINE`, `sortieId = null`, reply `CoachFixtures.fixture(OUTLINE)`.
- **Lift plan happy path:** the sortie is `PLANNED` and its plan is stored with source `GENERATED`. Seed: `onAppOpen()`, `confirm(id)`, then `scrub` the index-0 sortie. Index 1 (a `LIFT` under the default `RLRLR`) is then current and `PENDING`. Reply `fixture(LIFT_PLAN)`.
- **Run plan happy path:** the same for a `RUN` sortie. Seed: `setGenerateRunPlans(true)` **before** `onAppOpen()`, then `confirm(id)`, which leaves index 0 a current `PENDING` run. Reply `fixture(RUN_PLAN)`.
- **A row that already has a session id:** seed the row's `nabuSessionId` with the fake's own `sessionId` (`"session-1"`), so the pushed events match the session the runner filters on. `framesFor("rho.session.create")` is empty and the turn still completes. This is the resume case the stored cursor exists for.
- **A missing row:** `Result.failure()`, and the fake received no session frames at all.
- **A row already `SUCCEEDED`:** `Result.success()`, the row unchanged (status, `finishedAt`, `error`, cursor), and no frames sent.
- **One `rho.session.stop` frame after success:** `framesFor("rho.session.stop")` holds exactly one frame, and it comes after the prompt frame.
- Teardown: `daemon.stop()`, then close the container's database, then delete `liftoff.db` and the settings file, as the existing screen tests do.
- The non-success outcomes are implemented but not tested here. They are: `retry()` on a connection outcome that is not `Connected`; on a `Blocked`/`Paused`/`Error`/`TimedOut` end; on an invalid reply or a refused store; on a `DaemonException`; and the `FAILED` mark for a vanished Mission or Sortie. The brief's list is the success path, and the failure run owns those cases.

## Risks

- **`observeActive().first()` under Robolectric has hung this suite before.** The android-dev skill names it as one of two deadlocks that have already happened here. The read happens inside `doWork`, which is not the main looper, and every wait is bounded. If it still hangs, the fix is a one-shot `suspend` active-only query beside `observeActive` in `EquipmentDao`. That needs no schema change, and the worker's shape stays the same.
- **The callback writes must not be fire-and-forget.** A write launched and never joined can land after `markSucceeded`, or not at all before the test reads the row. The single ordered drain, joined before the row is read or marked, is what the outline test's `lastEventId` assertion depends on. Calling `runBlocking` inside the callback is the alternative to avoid, because it stalls the runner's event loop for each event.
- **`FakeDaemon` dedupes prompts by `client_id`** and plays no new turn for a client id it has already seen. Every case must `script(...)` before `doWork()`, and a case that prompts twice needs a second script.
- **The 20-minute limit must never be waited out in a test.** The fake is scripted to end at once and the test's own `withTimeout` is a few seconds. A stuck turn then fails the class rather than hitting the build's five-minute test-task timeout.
- **In the fake, `rho.session.stop` itself appends a `state_change` to `completed`.** The worker stops only after it has read the reply. The stop-frame test counts frames rather than reading the fake's log for the turn's end.
- **The container's `liftoff.db` and settings file are on disk and shared across the class.** Teardown deletes both and each test seeds what it needs. Otherwise `Mission.onAppOpen()` collides on `weekStart` if a row survives from an earlier test.
- **The store's refusals are the easy way to write a test that proves nothing.** `storeOutline` refuses a Mission that is not `DRAFT` or whose week has ended, so seed through the container's real clock, never a fixed past date. `storePlan` refuses a sortie that is not the current one, so check the default `RLRLR` pattern before picking an index. A `Refused` would otherwise look like a stored plan in the assertions above.
- **A `DaemonException` must not escape `doWork`.** A bad host, a refused session, or a connection that drops mid-turn all throw one. The worker catches it and returns `retry()`. An uncaught throw would leave the row `RUNNING` with nothing recorded about why.
- **The full gate can hang for about five minutes once on this machine.** Run it detached after `bash gradlew.sh --stop`, with the log and an `EXIT=$?` sentinel, and read the counts from the `TEST-*.xml`. If it hangs, re-run the same tree instead of changing code.

## Decisions

**A row whose Mission or Sortie has gone away is a failure.** The brief settles what a missing `Generation` row does, but a row can outlive the thing it was generating for. The worker needs the Mission's pattern and the Sortie's type and index before it can build a prompt at all. The plan returns `Result.failure()` for that, without opening a connection. The alternative is `retry()`, which is what a `Refused` store says. Retrying cannot bring back a Mission that is gone, and a row that retries forever reads to the user as a generation that never finishes.
Changed by review: the plan chose to return `Result.failure()` and leave the row as it stood, which is already `RUNNING` by then. The review chose `markFailed(id, <what is gone>, now)` and then `Result.failure()`. A row left `RUNNING` with no worker behind it reads to the user exactly like the never-finishing generation this decision means to avoid. `markFailed` already exists, and the failure run can reword the error text without changing the transition.

**A turn that ends cleanly but whose reply is no good returns `retry()`, not `failure()`.** The brief defers "repairs and the failure outcomes", but the worker still has to answer something in two cases: the turn ends `idle`/`completed` and the reply fails validation, or `storeOutline`/`storePlan` comes back `Refused`. The plan leaves the row `RUNNING` and returns `Result.retry()`. The alternative is `markFailed` + `Result.failure()`. Retrying is what the rest of the brief already does for every other non-success end. It also keeps the row resumable: the session id and cursor are stored, so the next attempt continues the same turn instead of starting over. The failure run is what will write the error text and the `FAILED` transition, and anything invented here would have to be replaced.

**`attempt` is read, never written.** The client id is `<generationId>-<attempt>`, and the worker uses the value already on the row. The alternative is to bump it at the start of each run with `GenerationDao.bumpAttempt`, so every attempt gets its own client id. The brief lists the calls the worker makes and `bumpAttempt` is not among them. The attempt counter belongs to the repair run, which decides when a genuinely new ask is needed. The visible consequence is that a retry re-sends the same client id. Both `FakeDaemon` and rho (spec 7.4) treat that as the same prompt rather than a second one. That is right for the retries this run actually takes: at the connection gate nothing has been prompted yet, and on the ends that are not a finished turn the attempt should follow the turn it already started. The case that needs a new prompt is the invalid reply, and that belongs to the failure run.

**The rho session is stopped only after a successful store.** On any other end the session is left open and only the client is closed. The alternative is to stop on every path. The brief stores the session id and cursor on the row precisely so a later attempt can resume that turn. Stopping a turn that did not finish would throw away the work and force a fresh session, and a `stop` frame after a failure would leave the stored cursor pointing at a dead session.

**A row already `RUNNING` is re-run.** Only `SUCCEEDED`, `FAILED` and `CANCELLED` are left untouched, as the brief says. The alternative is to treat `RUNNING` as "someone else is on it" and back off. But a `RUNNING` row with no worker behind it, left by a killed or crashed attempt, is exactly the case a re-run should recover, and the stored cursor makes that re-run a continuation rather than a duplicate. The visible cost is a second rho session for the same generation if two workers ever run at once. Nothing in this run enqueues the worker twice.

**The one-line description is built from the row, not from the model.** The brief asks for a one-line description of the session but does not say what it contains. The plan uses a fixed line naming the kind and what it belongs to (outline: the mission; flight plan: the sortie), with no prompt text and no user data in it. The alternative is a line quoted from the prompt or the reply. This string is what Mission Control's session list shows, so it should identify which generation the session belongs to and should not carry anything the user typed.

**A daemon that throws mid-turn is a retry, not a crash.** `runCoachTurn` throws a `DaemonException` when the connection drops, when the daemon refuses a session, or when it refuses a call for any reason other than a forgotten cursor. The brief's "other ends" rule does not cover an exception. The plan catches it and returns `Result.retry()`, leaving the row `RUNNING` with the session id and cursor it already saved. The alternatives are to let it escape `doWork`, which WorkManager reports as a failed run with nothing recorded, or to mark the row `FAILED`. A dropped connection is the common case and exactly what a retry is for; nobody would expect a generation to be lost because the phone lost the socket.
