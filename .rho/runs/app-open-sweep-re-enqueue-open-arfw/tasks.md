# Tasks

Six tasks, in this order. Task 1 is the sweep and the query it reads, plus the test class the rest grow in.
Tasks 2, 3 and 4 add the rest of `AppOpenSweepTest` in three halves, in the file task 1 created. Task 5 wires
`MainActivity`. Task 6 is the doc row and the whole gate. Each ends in one commit.

`verify.sh` (HEAD `78ce856`, rewritten by review) names eight tests, all new to this branch — none of the eight
method names appears anywhere under `app/src` today — split 1 / 3 / 3 / 1 across tasks 1–4 and spelled below exactly
as `verify.sh` spells them. Tasks 5 and 6 name no new test; every other check `verify.sh` makes is the gate, a
whole-class check, or the exported Room schema. It checks no ARCHITECTURE.md wording, and it does not check the
`MainActivity` wiring itself — that is read off the diff in the pull request.

Shared by the four test tasks (also recorded in map.md): one file
`app/src/test/java/com/liftoff/app/work/AppOpenSweepTest.kt`, `@RunWith(RobolectricTestRunner::class)` with
`@Config(sdk = [34])`, in-memory Room with `allowMainThreadQueries()`, `PreferenceDataStoreFactory.create` on
`settings.preferences_pb`, `MissionManager` on `Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)`
with `zone = { ZoneOffset.UTC }` (so the fixture's week starts 2026-01-12), `MissionManagerTest`'s
`insertMission` / `insertSortie` / `seedOutline` / `advanceClock` helper shapes, rows seeded through
`db.generationDao().insert(Generation(...))` with `createdAt` staggered, recording enqueue and cancel lambdas held in
lists, `db.close()` in `@After`, every wait bounded. **No case sets a host or a token and no case touches
WorkManager or a daemon**: the sweep consults no settings, so a wanted row must still reach the enqueue lambda with
Mission Control unset. The week each case hands the sweep is the `weekStart` of the Mission `onAppOpen()` returned,
not one the sweep works out. Every assertion is on the exact ids each lambda was handed, and the row each id names is
read back through `db.generationDao().get(id)` to check it is the row the case meant.

- [x] 1. `GenerationDao`: the whole-table open query, and `AppOpenSweep` that decides every open row
  Covers plan step 1. Files:
  `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — one added `@Query` selecting every row whose status is
  QUEUED or RUNNING. Both existing open queries are keyed to one missionId or one sortieId, so neither can find the
  rows the sweep has to look at. No entity change and no schema change: `app/schemas` must stay byte-identical.
  `app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt` (new) — the shape is the plan's Approach; what it has to
  honour is: the open rows and their Missions and sorties read in one Room read transaction, the lambdas called
  outside it; wanted = an OUTLINE row whose Mission is the handed-over week's DRAFT, or a FLIGHT_PLAN row whose
  Mission is ACTIVE and in that week and whose sortie still exists, is that Mission's `currentSortie`, and is PENDING
  or PLANNED; everything else goes to cancel — a row whose Mission or sortie has gone away, a FLIGHT_PLAN row with a
  null `sortieId`, a row whose sortie is IN_FLIGHT / LANDED / SCRUBBED or is no longer the current one; each lambda
  call wrapped so nothing but `CancellationException` escapes, and the whole read-and-decide wrapped the same way so
  a database that is gone ends the sweep quietly; no settings read, and never a generation queued that has no row.
  Also creates `AppOpenSweepTest` with the fixture above and its first case.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `anEmptyGenerationTableCallsNeitherLambda` (no Generation rows at all: neither lambda is called — not with an empty
  list, not with a zero or a null. This is what keeps WorkManager out of the shell tests.)
  Done when: `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` passes, then one cold detached
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.work.AppOpenSweepTest" --tests
  "com.liftoff.app.data.GenerationDaoTest"` (log under `app/build/`, `EXIT=$?` sentinel on its own line, no pipe)
  reports 0 failures and 0 errors in both classes and its `TEST-*.xml` names
  `anEmptyGenerationTableCallsNeitherLambda`; the per-class `tests=`/`failures=`/`errors=` totals go in the commit
  body.

- [x] 2. `AppOpenSweepTest`: the rows the sweep wants
  Covers the first half of plan step 2, in the class task 1 created — read what it left there first and reuse its
  seeding helpers; do not rewrite task 1's case.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `queuedFlightPlanForTheCurrentPendingSortieIsEnqueuedNotCancelled` (the current week's Mission ACTIVE, its current
  sortie PENDING, a QUEUED FLIGHT_PLAN row on it: its id handed to enqueue and to nothing else),
  `queuedOutlineForTheCurrentDraftMissionIsEnqueuedNotCancelled` (the current week's Mission DRAFT, a QUEUED OUTLINE
  row whose missionId is that Mission and whose sortieId is null: its id to enqueue, nothing to cancel),
  `runningFlightPlanForTheCurrentPlannedSortieIsEnqueuedNotCancelled` (a RUNNING FLIGHT_PLAN row on the current
  week's ACTIVE Mission's current sortie that is already PLANNED — the state a re-fly whose cancel was lost leaves —
  handed to enqueue, not to cancel; a sweep that accepted only PENDING sorties, or looked only at QUEUED rows, fails
  here). Make the Mission ACTIVE the way `MissionManagerTest` does, by inserting it with that status.
  Prove the new cases bite with one-token mutations in the sweep that still compile (drop `PLANNED` from the sortie
  states the sweep accepts; make it read only QUEUED rows), read each named case go red, restore the implementation
  with `cp` before committing, and record the mutation and the case it reddened in the commit body. Commit only the
  test file.
  Done when: one cold `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.work.AppOpenSweepTest"` reports 0 failures and 0 errors and its `TEST-*.xml` names all four cases
  in the class so far.

- [ ] 3. `AppOpenSweepTest`: the rows whose target has moved on
  Covers the second half of plan step 2, in the same class.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `weekEndedRolloverCancelsTheScrubbedSortiesRowAndThePastDraftsOutlineRow` (a past week's ACTIVE Mission carrying an
  open FLIGHT_PLAN row on a sortie that is not LANDED, plus a past week's DRAFT Mission carrying an open OUTLINE row;
  rebuild the manager on a clock one week later the way `MissionManagerTest.advanceClock` does and call
  `onAppOpen()`, which scrubs that Mission's not-done sorties with 'week ended' and closes it; hand the sweep the
  `weekStart` of the Mission that call returned: both open rows' ids handed to cancel, nothing handed to enqueue),
  `rowsForSortiesThatAreNoLongerCurrentAreCancelled` (the current week's ACTIVE Mission with a later sortie now
  current: an open FLIGHT_PLAN row on a LANDED sortie and one on a SCRUBBED sortie; both ids to cancel, nothing to
  enqueue),
  `decidedGenerationsAreLeftAlone` (a SUCCEEDED, a FAILED and a CANCELLED row sitting in the same table beside a
  wanted row: neither lambda is handed any of their ids, and the wanted row's id still goes to enqueue).
  Seed the not-done sorties directly rather than by landing and scrubbing through `MissionManager`, whose `land`
  rewrites OPEN sets to SKIPPED and can queue a row of its own.
  Done when: one cold `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.work.AppOpenSweepTest"` reports 0 failures and 0 errors and its `TEST-*.xml` names all seven cases
  in the class so far.

- [ ] 4. `AppOpenSweepTest`: a row that fails does not stop the sweep, and a cancelled sweep stays cancelled
  Covers the plan's Risks on the per-row wrap, in the same class.
  Tests it writes, spelled exactly as `verify.sh` uses it:
  `aRowThatFailsDoesNotStopTheSweepOrTheOtherRows` — a lambda that throws for the first id it is handed is still
  handed the next row's id, the sweep returns normally, and nothing escapes to the test scope or its
  `CoroutineExceptionHandler`; the case may use the enqueue lambda, the cancel lambda, or both. The arm `verify.sh`
  says this test also covers — the one failure that is not swallowed — is a lambda that throws
  `CancellationException`: it propagates, so a cancelled sweep is still cancelled rather than carrying on. That arm
  may be a second case in the class; `verify.sh` matches by method name.
  Prove the swallow case bites with the one-token mutation that widens the per-row catch to
  `CancellationException`, read the case go red, restore the implementation with `cp`, and record the mutation and
  the reddened case in the commit body. Commit only the test file.
  Done when: one cold `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.work.AppOpenSweepTest"` reports 0 failures and 0 errors and its `TEST-*.xml` names all eight of
  the names `verify.sh` lists.

- [ ] 5. `MainActivity`: run the sweep after `onAppOpen()` returns, off the main thread
  Covers plan step 3. File: `app/src/main/java/com/liftoff/app/MainActivity.kt` — capture the `Mission` the existing
  `repeatOnLifecycle(STARTED)` block's `onAppOpen()` returns (:26-30), then launch the sweep on `lifecycleScope` with
  a background dispatcher **outside** that block, per the plan's Decision that a repair which has started finishes
  even if the user switches away, handing it the returned Mission's `weekStart` and two lambdas that build
  `container.generationEnqueuer().enqueue(id)` and `container.generationCanceller().cancel(id)` **inside the lambda
  bodies**, the way `LaunchpadScreen` (:57-58) and `InFlightScreen` (:57) do. Both helpers call
  `WorkManager.getInstance` per call, and `LiftoffShellTest` composes the real container with WorkManager never
  initialized, so anything reaching either helper at composition or on an app open over an empty generation table
  fails those classes rather than passing quietly. Nothing awaits the sweep and nothing in `onCreate` blocks on it.
  No test file changes in this task, and no shell test gets a host, a token or WorkManager.
  The sweep now reads the shell tests' own Robolectric database from a background thread: if a shell or screen test
  starts failing with "connection pool has been closed", the sweep is racing that test's teardown — fix it in the
  sweep's own wrap, never by weakening a test or shortening a wait.
  Done when: one cold detached `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.LiftoffShellTest" --tests "com.liftoff.app.ui.ShellNavTest" --tests
  "com.liftoff.app.MainActivityTest" --tests "com.liftoff.app.AppContainerTest" --tests
  "com.liftoff.app.work.AppOpenSweepTest"` reports 0 failures and 0 errors in all five classes, with the eight sweep
  names still present in its reports.

- [ ] 6. Name the sweep in ARCHITECTURE.md's `work` row, then run the whole gate once
  Covers plan steps 4 and 5 and the brief's Done-when, in one commit. File: `ARCHITECTURE.md` line 36, the
  `com.liftoff.app.work` row only — its third column names each file in the package today (`CoachTurnRunner.kt`,
  `GenerationWorker.kt`, `GenerationEnqueuer.kt`); add `AppOpenSweep.kt` and a clause on what it does: at every app
  open, after the week rollover, every open Generation row is re-enqueued while its target is still current and
  cancelled once it has moved on. No other row, no other file. `verify.sh` does not grep this row; Kyle reviews the
  wording in the pull request, so write it plainly rather than to a phrase.
  Then run `.rho/runs/app-open-sweep-re-enqueue-open-arfw/verify.sh` detached through a copy under `app/build/`
  (`mkdir -p app/build` first; run the copy, never the real file, so editing it mid-run cannot give a phantom syntax
  error), logging to `app/build/` with an `EXIT=$?` sentinel on its own line and no pipe. It moves
  `app/build/test-results/testDebugUnitTest` aside with `mv` and runs
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` itself — up to three attempts when the
  suite hangs — then looks up the eight names, the 44 whole-class checks and the exported schema. One Gradle
  invocation in this worktree at a time; never `rm` the results dir (a stranded test executor holds
  `binary/output.bin`); never `gradlew --stop` (machine-wide). The gate can hang about five minutes writing no
  `TEST-*.xml`; the script re-runs it, and if it hangs a third time find the worker JVM and `jstack` it instead of
  re-running again.
  Done when: the log ends with `EXIT=0` and `BRIEF DONE`, with the eight names ✓, the gate ✓, every class check ✓ and
  the schema check ✓, and the reports show 0 failures and 0 errors. Fix and commit anything the gate turns up; if it
  turns up nothing, record the log path and the report totals in the commit message so the run's evidence is in the
  history.
