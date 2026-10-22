# Tasks — GenerationWorker: one successful generation end to end

Six tasks, in order. Each ends in a commit. Task 1 is the worker alone; tasks 2–5 build one test class,
`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, adding to what the task before it left behind;
task 6 is the doc row and the whole-suite gate. The seven test names below are exactly the names `verify.sh`
looks for — write them with those spellings, and read the comment above each name in `verify.sh` for what the
test must show.

- [x] Write `GenerationWorker.kt`
  New file `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: a `CoroutineWorker` with the plain
  `(context, params)` constructor so `TestListenableWorkerBuilder` can build it without a `WorkerFactory`. It
  covers the whole brief's worker: read the `Generation` row by id (missing row ⇒ `Result.failure()`, nothing
  else); leave `SUCCEEDED`/`FAILED`/`CANCELLED` unchanged and return `Result.success()`; `markRunning`; the
  `daemonConnectionCheck()` gate (`Connected` only, anything else ⇒ `Result.retry()`); `runCoachTurn` with the
  workspace, the two labels, the one-line description, `<generationId>-<attempt>` as client id, the row's stored
  session id and cursor, the 20-minute limit, and the session id/cursor written back to the row through an
  ordered drain that is joined before the row is read or marked; validate → `storeOutline`/`storePlan` →
  `markSucceeded` → `stopCoachSession`; `client.close()` on every path; `DaemonException` caught ⇒
  `Result.retry()`. No test exists yet and none is written here.
  Done when `bash gradlew.sh :app:compileDebugKotlin` passes and the file is committed.

- [x] `GenerationWorkerTest`: the harness and the outline happy path
  New file `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, Robolectric `@Config(sdk = [34])`,
  built the way `verify.sh`'s section 2 header spells it out: the application's own container
  (`ApplicationProvider` + `LiftoffApplication.container`, never a second `AppContainer`), one `FakeDaemon` per
  case scripted before `doWork()`, the fake's `host()`/`port()`, a non-blank token and a coach workspace written
  into `settingsStore`, `equipmentDao().insertAll(CoachFixtures.KEYS)`, `container.missionManager` on the real
  clock, `TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(...)`, `doWork()` inside
  `runBlocking { withTimeout(...) }`, and teardown that stops the fake, then closes the database, then deletes
  `liftoff.db` and the settings file. This task establishes all of that with one case: seed `onAppOpen()` only,
  a `QUEUED` row `kind = OUTLINE`, `sortieId = null` (`Generation.createdAt` has no default, so a seeded row sets
  it), reply `CoachFixtures.fixture(OUTLINE)`. Assert the row `SUCCEEDED` with `nabuSessionId` and `lastEventId`
  both set, the Mission `ACTIVE` with its first sortie (index 0 — the brief's "sortie 1") current, and the create
  frame carrying the labels `["liftoff", "liftoff:outline"]` with no label starting `run:` plus a one-line
  description, and the prompt frame's client id `<id>-0`.
  Test: `anOutlineGenerationStoresTheReplyAndMarksTheRowSucceeded`.
  Done when that test passes in a run of this class. If `equipmentDao().observeActive().first()` hangs under
  Robolectric, the plan's fallback is a one-shot `suspend` active-only query beside `observeActive` in
  `EquipmentDao` — no schema change, and the worker's shape stays the same.

- [ ] The lift and run happy paths
  Add to `GenerationWorkerTest`. Lift: `onAppOpen()`, `confirm(id)`, then `scrub` the index-0 sortie (by its id),
  which leaves index 1 (a `LIFT` under the default `RLRLR`) current and `PENDING`; row `kind = FLIGHT_PLAN` with that
  sortie's id, reply `fixture(LIFT_PLAN)`. Run: `settingsStore.setGenerateRunPlans(true)` **before**
  `onAppOpen()`, then `confirm(id)`, leaving index 0 a current `PENDING` `RUN`; reply `fixture(RUN_PLAN)`. Both
  assert the sortie is `PLANNED`, its `FlightPlan` stored with source `GENERATED`, the row `SUCCEEDED`, and the
  create frame carrying the labels `["liftoff", "liftoff:flight-plan"]`.
  Tests: `aLiftPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan`,
  `aRunPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan`.
  Done when both pass in a run of this class.

- [ ] The two row guards
  Add to `GenerationWorkerTest`: a `doWork()` for an id that has no row, and a `doWork()` for a row already
  `SUCCEEDED` with its `finishedAt`, `error`, `nabuSessionId` and `lastEventId` filled in. Both cases must show
  the fake received no session frame at all; the finished row must still read exactly as it was seeded.
  Tests: `aGenerationRowThatDoesNotExistReturnsFailureAndSendsNothingToTheDaemon`,
  `aGenerationRowThatAlreadyFinishedIsLeftAsItIsAndReportedAsDone`.
  Done when both pass in a run of this class.

- [ ] Resume, and the single stop frame
  Add to `GenerationWorkerTest`: a row whose `nabuSessionId` is seeded with the fake's own `sessionId`
  (`"session-1"`) so the pushed events match the session the runner filters on — no `rho.session.create` frame
  at all, the turn still reaches its end and the row still ends `SUCCEEDED` on the stored session. And a
  successful case asserting `framesFor("rho.session.stop")` holds exactly one frame and it comes after the
  prompt frame.
  Tests: `aGenerationRowWithASessionIdAlreadyStoredIsResumedWithoutCreatingAnotherSession`,
  `oneSessionStopFrameIsSentAfterASuccessfulStore`.
  Done when both pass in a run of this class.

- [ ] The `work` row in ARCHITECTURE.md, and the whole-suite gate
  Rewrite the "Exists yet?" cell of the `com.liftoff.app.work` row (line 36) so it names `GenerationWorker` and
  no longer says it is not written; keep an honest note that the repairs and failure outcomes are still to come.
  Leave the M3 milestone row (line 82) alone. Then run the full gate detached — `bash gradlew.sh --stop`, then
  `timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` into a log under
  `app/build/` with an `EXIT=$?` sentinel — and read the totals out of the `TEST-*.xml` reports, not from
  `BUILD SUCCESSFUL`. `CoachTurnRunnerTest` and `ConnectionViewModelTest` must still be there and green; the
  known flake is `doneBodyweightSetStepperShowsBW`, and a hang is re-run rather than fixed.
  Done when the row is rewritten, the gate's XML shows zero failures, and `app/schemas` is untouched.
