# Plan

## Approach

`GenerationWorker` is the scheduling-and-persistence half of a coach turn; everything about talking to the daemon already exists. It reads the `Generation` row, guards the statuses the brief says are terminal, marks the row `RUNNING`, asks `container.daemonConnectionCheck()` before it opens anything, and then hands the connected client to `runCoachTurn` with the prompt from `PromptInputLoader` + `CoachPromptBuilder` and the row's stored session id and cursor. The turn's session id and cursor go back onto the row through the runner's callbacks, so a worker that dies mid-turn leaves a resumable row. When the turn ends `Idle`/`Completed`, the reply goes through `CoachReplyValidator` with a `GenerationContext` built from the Mission's pattern, the Sortie's type, the active equipment keys and `sortieLengthMinutes`, and a valid one goes to `MissionManager.storeOutline`/`storePlan`, which is the same path the app already uses for a coach reply. Then `markSucceeded`, `stopCoachSession`, and `client.close()` on every path. Nothing new is invented: the worker is the caller the turn runner was written for, and the storage half is the already-merged `storeOutline`/`storePlan`.

This run adds no scheduling: nothing enqueues the worker, and no UI reaches it. It is the class, its tests, and the `ARCHITECTURE.md` row that names it.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — new. The `CoroutineWorker`: row guards, `markRunning`, the connection gate, the turn call with the labels/description/client id/20-minute limit, the callback writes, validate → store → `markSucceeded` → `stopCoachSession`, `close()` on every path.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — new. Robolectric `@Config(sdk = [34])`, `TestListenableWorkerBuilder<GenerationWorker>`, the application's own `container`, `FakeDaemon`, `CoachFixtures`.
- `ARCHITECTURE.md` — the `com.liftoff.app.work` row (line 36) names `GenerationWorker` and says what it does. The M3 milestone row (line 82) is left alone: the brief names only the `work` row, and M3 also covers scheduling and the live end-to-end run this run does not add.
- No dependency, schema or `AppContainer` change — see the map for what is already on the test classpath.

## Order

1. Write `GenerationWorker.kt` whole — guards, connection gate, turn, validate/store, success, close — and get `bash gradlew.sh :app:compileDebugKotlin` green before any test exists.
2. `GenerationWorkerTest`: the outline happy path only (seed, fake script, `doWork()` under a bounded `runBlocking`, the row and Mission assertions). Run that one class.
3. Add the lift and run happy paths, with the seeding each needs (see Testing).
4. Add the remaining brief cases: a row that already has a session id, the missing row and the already-`SUCCEEDED` row, and the single `rho.session.stop` frame after success.
5. `ARCHITECTURE.md` work row.
6. One detached full gate (`:app:assembleDebug :app:testDebugUnitTest`) with the log and an `EXIT=$?` sentinel, results read from the `TEST-*.xml`, not from `BUILD SUCCESSFUL`.

## Testing

All in `GenerationWorkerTest`, Robolectric `@Config(sdk = [34])`, one `FakeDaemon` and one `TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(...)` per case, `doWork()` called inside `runBlocking { withTimeout(...) }`. Seeding for every case: the application's own `container` (never a second `AppContainer`), `settingsStore` written with the fake's `host()`/`port()`, a non-blank token and a coach workspace path, `equipmentDao().insertAll` with `CoachFixtures.KEYS`, and `container.missionManager` on the real clock.

- **Outline happy path** — `Result.success()`, the row `SUCCEEDED` with `nabuSessionId` and `lastEventId` both set, and the Mission `ACTIVE` with sortie index 1 current. Seed: `onAppOpen()` only, row `kind = OUTLINE`, `sortieId = null`, reply `CoachFixtures.fixture(OUTLINE)`.
- **Lift plan happy path** — the sortie `PLANNED` and its plan stored with source `GENERATED`. Seed: `onAppOpen()`, `confirm(id)`, `scrub` the index-0 sortie, so index 1 (a `LIFT` under the default `RLRLR`) is current and `PENDING`; reply `fixture(LIFT_PLAN)`.
- **Run plan happy path** — the same for a `RUN` sortie. Seed: `setGenerateRunPlans(true)` **before** `onAppOpen()`, then `confirm(id)`, leaving index 0 a current `PENDING` run; reply `fixture(RUN_PLAN)`.
- **A row that already has a session id** — `framesFor("rho.session.create")` is empty while the turn still completes. This is the resume case the stored cursor exists for.
- **A missing row** — `Result.failure()`, and the fake received no session frames at all.
- **A row already `SUCCEEDED`** — `Result.success()`, the row unchanged (status, `finishedAt`, `error`, cursor), and no frames sent.
- **One `rho.session.stop` frame after success** — counted in `framesFor("rho.session.stop")`, exactly one, and it comes after the prompt frame.
- Teardown: `daemon.stop()`, then close the container's database, then delete `liftoff.db` and the settings file, as the brief says the existing screen tests do.
- The non-success outcomes (`retry()` on a connection outcome that is not `Connected`, on a `Blocked`/`Paused`/`Error`/`TimedOut` end, on an invalid reply or a refused store, and on a `DaemonException`) are implemented but not tested here: the brief's list is the success path, and the failure run owns those cases.

## Risks

- **`observeActive().first()` under Robolectric has hung this suite before** (the android-dev skill names it as one of two deadlocks that already happened here). The read happens inside `doWork`, which is not the main looper, and every wait is bounded; if it still hangs, the fix is a one-shot `suspend` active-only query beside `observeActive` in `EquipmentDao` — no schema change, and the worker's shape stays the same.
- **`FakeDaemon` dedupes prompts by `client_id`** and plays no new turn for a client id it has already seen. Every case must `script(...)` before `doWork()`, and a case that prompts twice needs a second script.
- **The 20-minute limit must never be waited out in a test.** The fake is scripted to end at once and the test's own `withTimeout` is a few seconds, so a stuck turn fails the class rather than the build's five-minute test-task timeout.
- **In the fake, `rho.session.stop` itself appends a `state_change` to `completed`.** The worker stops only after it has read the reply, and the stop-frame test counts frames rather than reading the fake's log for the turn's end.
- **The container's `liftoff.db` and settings file are on disk and shared across the class.** Teardown deletes both, each test seeds what it needs, and `Mission.onAppOpen()` collides on `weekStart` if a row survives from an earlier test.
- **The store's refusals are the easy way to write a test that proves nothing.** `storeOutline` refuses a Mission that is not `DRAFT` or a week that has ended (so seed through the container's real clock, never a fixed past date); `storePlan` refuses a sortie that is not the current one (so check the default `RLRLR` pattern before picking an index). A `Refused` would otherwise look like a stored plan in the assertions above.
- **A `DaemonException` must not escape `doWork`** — a bad host, a refused session or a connection that drops mid-turn all throw one. The worker catches it and returns `retry()`; an uncaught throw would leave the row `RUNNING` with nothing recorded about why.
- **The full gate can hang for about five minutes once on this machine.** Run it detached after `bash gradlew.sh --stop`, with the log and an `EXIT=$?` sentinel, read the counts from the `TEST-*.xml`, and re-run the same tree if it hangs instead of changing code.

## Decisions

**A turn that ends cleanly but whose reply is no good returns `retry()`, not `failure()`.** The brief defers "repairs and the failure outcomes", but the worker still has to answer something when the turn ends `idle`/`completed` and the reply fails validation, or `storeOutline`/`storePlan` comes back `Refused`. The plan leaves the row `RUNNING` and returns `Result.retry()`. The alternative is `markFailed` + `Result.failure()`. Retrying is what the rest of the brief already does for every other non-success end, and it keeps the row resumable — the session id and cursor are stored, so the next attempt continues the same turn instead of starting over. The failure run is what will write the error text and the `FAILED` transition; inventing one here would have to be replaced.

**`attempt` is read, never written.** The client id is `<generationId>-<attempt>` and the worker uses the value already on the row. The alternative is to bump it at the start of each run — `GenerationDao` already has `bumpAttempt` for that — so every attempt gets its own client id. The brief lists the calls the worker makes and `bumpAttempt` is not among them, and the attempt counter belongs to the repair run that decides when a genuinely new ask is needed. The visible consequence is that a retry re-sends the same client id, which both `FakeDaemon` and rho (spec 7.4) treat as the same prompt rather than a second one: for the retries this run actually takes — the connection gate, where nothing has been prompted yet, and the ends that are not a finished turn — that is the right behaviour, because the attempt follows the turn it already started instead of starting a second one. The case that needs a new prompt is the invalid reply, which is the failure run's to take.

**The rho session is stopped only after a successful store.** On any other end the session is left open and only the client is closed. The alternative is to stop on every path. The brief stores the session id and cursor on the row precisely so a later attempt can resume that turn; stopping a turn that did not finish would throw away the work and force a fresh session, and a `stop` frame after a failure would make the stored cursor point at a dead session.

**A row already `RUNNING` is re-run.** Only `SUCCEEDED`, `FAILED` and `CANCELLED` are left untouched, as the brief says. The alternative is to treat `RUNNING` as "someone else is on it" and back off. A `RUNNING` row with no worker behind it — a killed or crashed attempt — is exactly the case a re-run should recover, and the stored cursor makes that re-run a continuation rather than a duplicate. The visible cost is a second rho session for the same generation if two workers ever do run at once; nothing in this run enqueues the worker twice.

**The one-line description is built from the row, not from the model.** The brief asks for a one-line description of the session but does not say what it says. The plan uses a fixed line naming the kind and the row it belongs to (outline: the mission; flight plan: the sortie), with no prompt text and no user data in it. The alternative is a line quoted from the prompt or the reply. This string is what Mission Control's session list shows, so it should identify which generation the session belongs to and should not carry anything the user typed.

**A daemon that throws mid-turn is a retry, not a crash.** `runCoachTurn` hands back a `DaemonException` for a connection that drops, a session the daemon refuses, or a call it refuses for any reason other than a forgotten cursor, and the brief's "other ends" rule does not cover an exception. The plan catches it and returns `Result.retry()`, leaving the row `RUNNING` with the session id and cursor it already saved. The alternative is to let it escape `doWork`, which WorkManager reports as a failed run with nothing recorded, or to mark the row `FAILED`. A dropped connection is the common case and is exactly what a retry is for; burning the generation because the phone lost the socket is not what anyone would expect.

**A row whose Mission or Sortie has gone away is a failure.** The brief settles what a missing `Generation` row does, but a row can outlive the thing it was generating for, and the worker needs the Mission's pattern and the Sortie's type and index before it can build a prompt at all. The plan returns `Result.failure()` for that, without opening a connection. The alternative is `retry()`, which is what a `Refused` store says. Retrying cannot bring back a Mission that is gone, and a row that retries forever reads to the user as a generation that never finishes.
