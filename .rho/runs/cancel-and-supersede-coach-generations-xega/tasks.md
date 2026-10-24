# Tasks — cancel and supersede coach generations

Five tasks, in this order; each ends in its own commit. The test names below are spelled exactly as
`.rho/runs/cancel-and-supersede-coach-generations-xega/verify.sh` spells them, and each is written by exactly one
task. All six are new to this run. No task changes the Room schema: verify.sh compares `app/schemas` against
`28a7763^`, so a new version file or a rewritten one fails the run.

Rules that apply to every task: scratch scripts and logs go under `app/build/` after `mkdir -p app/build`, with the
build's verdict read from the log plus an `EXIT=$?` sentinel and no pipe after the build; commit messages are
`area: lowercase summary`; stage only the named files; never touch `verify.sh`.

- [x] 1. The cancel helper, exposed from the container
  Shares the unique work name and builds the cancel side of the rule. `uniqueName` in `GenerationEnqueuer.kt`
  (line 36) stops being private to that file, so the enqueuer and the canceller cannot spell `gen-<generationId>`
  differently. New `app/src/main/java/com/liftoff/app/work/GenerationCanceller.kt`: for a generation id it cancels
  that unique work, then in one `database.withTransaction` re-reads the row and marks it CANCELLED with
  `finishedAt` stamped only when the row is QUEUED or RUNNING; after that transaction commits, if it cancelled the
  row and the row carries a session id, it opens a client (the container's `daemonClient()` is suspend, so the
  canceller is given a way to ask for one at stop time rather than a client built up front), calls the existing
  `stopCoachSession`, and closes the client — swallowing a failed connect or a refused stop but rethrowing a
  `CancellationException`, the way the worker's `giveUpSession` does. Cancelling an id with no row cancels the work
  and returns without throwing. `AppContainer.kt`: a cancel accessor beside `generationEnqueuer()` (line 38), built
  per call from the container's own WorkManager and database, so constructing a container still touches nothing
  WorkManager-related.
  Files: `work/GenerationEnqueuer.kt`, `work/GenerationCanceller.kt` (new), `AppContainer.kt`,
  `app/src/test/java/com/liftoff/app/work/GenerationCancellerTest.kt` (new).
  The new test file is the harness the later cancel tests reuse: the application's own container
  (`(context as LiftoffApplication).container`, never a second `AppContainer`), a scripted `FakeDaemon` whose
  host/port/token/workspace are written into `container.settingsStore`, and WorkManager initialised the way
  `GenerationEnqueuerTest` does (`WorkManagerTestInitHelper.initializeTestWorkManager` in `@Before`,
  `closeWorkDatabase()` in `@After`) because the helper cancels work as well as marking the row. Schedule the work
  through `container.generationEnqueuer()` first, so the unique work genuinely exists under the enqueuer's own name
  before the cancel, and read it back from WorkManager.
  Tests it writes: `cancellingAGenerationStopsItsSessionAndMarksTheRowCancelled` (row CANCELLED with `finishedAt`;
  exactly one `rho.session.stop` frame naming the session the row carried; the work under that same unique name
  reads `WorkInfo.State.CANCELLED`, not ENQUEUED or RUNNING; nothing stored for that generation), and
  `cancellingAGenerationWithTheDaemonUnreachableStillCancelsTheRowAndItsWork` (settings pointed at a loopback port
  the test opened and closed, as `pointSettingsAtNothing` does: the call does not throw, the row still reads
  CANCELLED with `finishedAt`, and the work still reads CANCELLED).
  Done when: a cold run (`--no-build-cache`) of the new class shows both names passing, and
  `GenerationEnqueuerTest` and `AppContainerTest` ran with everything in them green.

- [ ] 2. A cancel does not touch a generation that already finished
  Proves the brief's "does nothing to a row that is already SUCCEEDED, FAILED or CANCELLED" clause against the
  helper and harness task 1 built, in `GenerationCancellerTest.kt`. Seed one SUCCEEDED row whose result was
  genuinely stored (its outline or Flight Plan is on the mission) and one FAILED row carrying an error, both with
  `nabuSessionId`, `finishedAt` and `error` filled in, and cancel each id. If task 1's helper already marks only
  QUEUED or RUNNING rows this is a tests-only task; if it marks unconditionally, the status guard belongs here,
  inside the same transaction as the read.
  Files: `app/src/test/java/com/liftoff/app/work/GenerationCancellerTest.kt`; `work/GenerationCanceller.kt` only if
  the guard is missing.
  Test it writes: `cancellingAGenerationThatAlreadyFinishedLeavesTheRowAndItsStoredResultAlone` — each row reads
  exactly as it was seeded (status, `finishedAt`, `error`, `nabuSessionId`), the SUCCEEDED row not turned
  CANCELLED, the stored result still there, and no `rho.session.stop` frame reached the fake at all. Whether
  leftover work is also cancelled for a finished id is deliberately not asserted.
  Done when: the name passes in a cold run, and it has been shown to bite — one mutation that removes or widens the
  status guard, named in the log by the failing method, then the implementation restored by copy before committing.

- [ ] 3. The worker stores only a current result
  The guard at the moment of writing, in `storeFinishedTurn` (`work/GenerationWorker.kt`, 214-302). Inside one
  `database.withTransaction`, together with the store and `markSucceeded`, re-read the row and the newest
  non-cancelled generation for its sortie (`getNewestNonCancelledForSortie`) or for its Mission's outline
  (`getNewestNonCancelledOutlineForMission`). The not-current path stores nothing, marks the row CANCELLED if it is
  not already, stops `turn.sessionId` outside the transaction through `giveUpSession`, and returns success so
  WorkManager does not retry. Keep the guard after validation and after the repair loop, so the repair cap, the
  resume path, the offline mark and the 8th-attempt give-up do not move. Keep every Room `Flow` read — including
  the existing `equipmentDao().observeActive().first()` at 229 — outside the new transaction block: a Room Flow
  `.first()` under Robolectric has deadlocked this suite. The new transaction nests with `MissionManager`'s own
  `storeOutline`/`storePlan` transactions; if that nesting fails, move the currency check inside
  `MissionManager`'s store transaction rather than pulling the store out of it.
  Files: `work/GenerationWorker.kt`, `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`.
  Test it writes: `aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone`. Script an assistant reply
  with **no end state** — the fake's turn ends only when something pushes a state_change, and `rho.session.stop`
  pushes a `completed` one — and start the worker on its own coroutine, off the test thread, under the class's own
  bounded wait. Wait (bounded) for this row's prompt frame to reach the fake, then cancel through the container's
  helper; that stop is what ends the turn, so the worker is forced to reach its store with a valid reply in hand and
  a row already marked CANCELLED. Assert nothing was stored (the sortie still has no Flight Plan, or the mission
  still has no outline), the row reads CANCELLED, and `doWork()` returned `Result.Success`. Do not assert a single
  stop frame here: the helper's stop and the worker's own are both there. This test needs WorkManager initialised,
  because the helper cancels the work too; add it without disturbing the 25 existing tests in the class.
  Done when: the name passes in a cold run of `GenerationWorkerTest`, and the whole class ran with its 25 existing
  tests green — the guard's negative control is the happy-path tests, which store and must stay green.

- [ ] 4. A superseded generation stores nothing, and the current one still stores
  The currency half of the guard, proven in the harness task 3 extended. Tests-only: the guard is already in
  `storeFinishedTurn`. Seed rows with `createdAt` values spaced out rather than leaning on the `id DESC` tie-break,
  and script two turns — one reply per prompt, since `nextTurn()` hands out the next turn per new prompt.
  Files: `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`.
  Tests it writes: `aSupersededFlightPlanGenerationStoresNothingAndTheNewerGenerationStillStoresItsPlan` (two
  FLIGHT_PLAN rows for one sortie; run the older worker first — nothing stored, the sortie still PENDING with no
  plan the way `assertAwaitingGeneration` shows, its row CANCELLED, `Result.Success` rather than a retry — then run
  the newer worker on the same sortie: its plan stored, the sortie PLANNED with a generated plan, its row
  SUCCEEDED), and `aSupersededOutlineGenerationStoresNothingAndTheNewerGenerationStillStoresItsOutline` (the same
  for two OUTLINE rows on one Mission, no sortie: the older stores nothing and reads CANCELLED, the newer stores
  its outline and reads SUCCEEDED).
  Done when: both names pass in a cold run, each has been shown to bite by one mutation that drops the currency
  check (named in the log by the failing method, implementation restored by copy before committing), and a cold run
  of `GenerationWorkerTest` and `GenerationEnqueuerTest` shows every test in both classes green — the plan's step 3
  check that the guard did not move the happy, repair or retry paths.

- [ ] 5. The M3 row, then the whole gate
  Update `ARCHITECTURE.md`'s M3 row (line 82) so it says the worker — including resilience, cancel and supersede —
  is built and that app wiring comes next. It is the only document this run changes; leave the
  `com.liftoff.app.work` package-layout row (line 36) alone. Then run the whole gate once, detached:
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` with the results dir cleared first,
  in a scratch script under `app/build/` with a log and an `EXIT=$?` sentinel. If the build dies at its 5-minute
  test-task timeout with no `TEST-*.xml` written, re-run it rather than treating it as a failure of the work.
  Files: `ARCHITECTURE.md`.
  Tests it writes: none.
  Done when: the M3 row is updated in the commit, and the commit body records the gate's own report totals (tests,
  failures, errors, skipped) and that `:app:assembleDebug` and `:app:testDebugUnitTest` both passed. That run is
  also the run's only check on `GenerationDaoTest`, `CoachTurnRunnerTest`, `MissionManagerOutlineTest` and
  `MissionManagerPlanTest`, which no earlier task gates: every test in each of them has to have passed in it.
