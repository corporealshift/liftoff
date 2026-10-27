# Tasks — the worker leaves a cancelled generation cancelled

Five tasks, in the order plan.md §Order sets them. Each ends in a commit. Test names below are spelled exactly as
`.rho/runs/the-worker-leaves-a-cancelled-n8q3/verify.sh` spells them, and each is written by exactly one task:

| test | task |
| --- | --- |
| `markRunningAndMarkFailedRefuseAGenerationThatAlreadyDecidedItsOutcome` | 1 |
| `aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError` | 2 |
| `aGenerationCancelledMidTurnThatRunsOutOfRepairsStaysCancelledAndStoresNothing` | 3 |
| `aGenerationCancelledBeforeTheWorkerStartsOpensNoTurnAndEndsAsDone` | 4 |

Gate conventions (CLAUDE.md, and the run's map): `bash gradlew.sh …`, never bare gradle; scratch scripts and logs
under `app/build/`; a gate run is launched detached with the log and an `EXIT=$?` sentinel appended without a pipe;
move `app/build/test-results/testDebugUnitTest` aside with `mv`, never `rm`; never `gradlew --stop`; every class a
check names is run in ONE invocation; re-run the identical tree once if the build kills the test task at five
minutes without writing any `TEST-*.xml`. Baseline for the whole suite is 690 tests in 83 classes.

- [x] 1. Guard the two row writes in the DAO, and prove the guard at the row itself
  Covers plan step 1. `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`: `markRunning` and `markFailed`
  gain the status filter `setCoachOfflineMark` already carries (`AND status IN ('QUEUED', 'RUNNING')`), and each
  returns how many rows it changed so a caller can tell a refusal from a write. Existing callers that ignore the
  return value — the worker, and the seeding in `GenerationDaoTest`, `MissionManagerTest` and the three Launchpad
  tests — keep compiling and passing as they stand. No entity changes, so `app/schemas` stays byte-identical.
  Test it writes, in `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt`:
  **`markRunningAndMarkFailedRefuseAGenerationThatAlreadyDecidedItsOutcome`** — a row that has already decided its
  outcome is left exactly as it was (status, error, `finishedAt` all unchanged) and each write reports 0:
  `markRunning` refuses a CANCELLED, a SUCCEEDED and a FAILED row; `markFailed` writes no status, no error and no
  `finishedAt` to a CANCELLED row. A QUEUED and a RUNNING row are still marked, so the guard refuses a decided row
  rather than every row.
  Done when: one cold detached whole gate — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
  --no-build-cache` — with `BUILD SUCCESSFUL`, `:app:testDebugUnitTest` executed (not FROM-CACHE/UP-TO-DATE), 0
  failures, and the new name read pass in `TEST-com.liftoff.app.data.GenerationDaoTest.xml` (8 tests, up from 7);
  `git status --short` shows no change under `app/schemas`. Prove both filters bite by dropping each in turn —
  `markRunning`'s, then `markFailed`'s — watching this case go red each time, restoring it, and re-running the
  class; write the mutations, the gate command, EXIT and the per-class totals into the commit body.

- [x] 2. Let a test act part-way through a scripted turn, and stop the failure write over a cancelled row
  Covers plan step 2's first half. `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`: add one `DaemonScript`
  step that runs a test-supplied action synchronously inside `play`, before the next step's event is appended —
  today nothing in a script holds a turn open long enough for a poll-then-cancel to win a race against a turn that
  ends. `play`'s `when` is the only exhaustive match on `DaemonScript`, so it gains one branch. Test-only; nothing
  in `src/main` depends on it, the action must not throw, and any assertion belongs in the test body.
  `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: `failGeneration` (line 384, the only writer of
  FAILED in the file, reached from the blocked turn, the exhausted repairs, a permanent `DaemonException`, a gone
  Mission or Sortie, and the 8th unreachable attempt) checks the guarded write's result; when it is refused and the
  row now reads CANCELLED it still gives up the session it was holding through the existing `giveUpSession` and
  returns done instead of failure. A refusal for any other reason keeps today's failure outcome.
  Test it writes, in `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`:
  **`aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError`** — the script from
  `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput` with the new action step placed before
  `State("blocked")`; the action marks the row CANCELLED through `GenerationDao.markCancelled`, never through
  `container.generationCanceller()` (whose own `rho.session.stop` would make `assertSessionGivenUpOnce` pass on a
  worker that stopped nothing). Asserts `doWork()` returns success, the row is CANCELLED and never FAILED with the
  error it held before the cancel rather than `coach needed input` — seed the row with an error before the run so
  "keeps its error" is a real assertion and not a null staying null — nothing stored on the Mission or its sorties,
  and `assertSessionGivenUpOnce` for the session the turn opened. Uses `runWorker` as is, under the class's own
  `turnWait`.
  Done when: one cold detached run of `:app:testDebugUnitTest --no-build-cache` naming `GenerationWorkerTest`,
  `CoachTurnRunnerTest`, `GenerationCancellerTest`, `DaemonConnectionCheckTest` and `AppContainerTest` in one
  invocation — 0 failures, `GenerationWorkerTest` at 30 tests with the new name read pass — plus
  `:app:compileDebugUnitTestKotlin` clean, which is what shows the new `when` branch is complete and every other
  FakeDaemon user still compiles. Prove it bites with one mutation on this task's own change — make
  `failGeneration` report failure whatever the guarded write returned, as it does today — and watch this case go
  red; restore and re-run the class. Write the mutation, the gate command, EXIT and the per-class totals into the
  commit body.

- [x] 3. The same rule for a cancelled turn that runs out of repairs
  Covers plan step 2's second half. No new scaffolding and no new production behaviour: task 2's FakeDaemon step and
  the guarded `failGeneration` are already in place, and the repair loop is deliberately left running (plan
  §Decisions) — only the final FAILED write is refused.
  Test it writes, in `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`:
  **`aGenerationCancelledMidTurnThatRunsOutOfRepairsStaysCancelledAndStoresNothing`** — the two scripts from
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing` (a refused reply and its
  `State("idle")`, then a second), with the cancel action step inside the **first** turn, right before that turn's
  `State("idle")`: the fake repeats the last scripted turn, so a step in the second turn would run twice. The
  repairs then play out as they do today. Asserts
  `doWork()` returns success, the row is CANCELLED with its error unchanged rather than `invalid output: …`, nothing
  stored on the Mission or its sorties, and the session given up exactly once.
  Done when: one cold detached `:app:testDebugUnitTest --no-build-cache` naming `GenerationWorkerTest` and
  `CoachTurnRunnerTest` — 0 failures, `GenerationWorkerTest` at 31 tests with the new name read pass. Prove it
  bites by making `failGeneration` report failure again whatever the guarded write returned and watching this case
  go red on the `doWork()` outcome alone — the row would still stay CANCELLED through task 1's DAO filter — then
  restoring it and re-running the class.

- [x] 4. The mark-running step refuses a decided row, and the worker ends quietly there
  Covers plan step 3. `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` `doWork`: when the guarded
  `markRunning` write (line 64) reports it changed no row, the work ends quietly as done — before the Mission and
  Sortie reads and before `container.daemonConnectionCheck()`, so no session is created and no prompt is sent for a
  row that has already decided its outcome. The early return for a row that already reads SUCCEEDED/FAILED/
  CANCELLED stays where it is; this is the guard for a cancel or a late duplicate attempt landing after that read.
  Test it writes, in `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`:
  **`aGenerationCancelledBeforeTheWorkerStartsOpensNoTurnAndEndsAsDone`** — the row is already CANCELLED when
  `doWork()` is asked for it: `doWork()` returns success, the row still reads CANCELLED with nothing written over
  it, and `assertNothingReachedTheDaemon` proves no frame at all reached the fake daemon. Distinct from the
  existing `aGenerationRowThatAlreadyFinishedIsLeftAsItIsAndReportedAsDone`, which is a SUCCEEDED row only.
  Done when: one cold detached `:app:testDebugUnitTest --no-build-cache` naming `GenerationWorkerTest`,
  `GenerationCancellerTest` and `AppOpenSweepTest` — 0 failures, `GenerationWorkerTest` at 32 tests with the new
  name read pass. Mutation check: this case cannot be reddened by touching the new refusal branch on its own,
  because the early return for a row that already reads SUCCEEDED/FAILED/CANCELLED short-circuits before the write
  ever runs — that is the limit plan §Risks states, and the reason task 1's DAO case is the proof that the
  `markRunning` guard bites. Say that in the commit body, along with the totals.

- [ ] 5. The whole gate, once, and the classes the brief says must not break
  Covers plan step 4. No new behaviour and no new test: this task runs the gate and fixes only what it names, in
  its own commit if anything needs fixing. One cold detached run of `bash gradlew.sh :app:assembleDebug
  :app:testDebugUnitTest --no-build-cache`, launched from a scratch script under `app/build/` with the log and an
  `EXIT=$?` sentinel appended without a pipe; re-run the identical tree once if it hangs. Read the log for `FAILED`
  as well as counting the totals, and sum the `TEST-*.xml` totals.
  What it confirms: the suite is 694 tests (the 690 baseline plus the four new cases) with 0 failures; every class
  `verify.sh` §3 names ran green — `GenerationWorkerTest` (32), `CoachTurnRunnerTest`, `GenerationEnqueuerTest`,
  `GenerationCancellerTest`, `GenerationDaoTest` (8), `AppOpenSweepTest`, the four `MissionManager*Test`,
  `LaunchpadConfirmTest`, `InFlightLandScrubTest`, `InFlightRunTest`, `FlightPlanDaoTest`, `SortiePlanningTest`,
  `ReFlyTest`, the seven Launchpad draft/status classes, `AppContainerTest`, `LiftoffShellTest`, `MainActivityTest`,
  `ShellNavTest`; the four new names read pass in that run's own reports; every existing FAILED-expecting worker
  case (blocked turn, two refused repairs, permanent refusal, protocol mismatches, 8th unreachable attempt,
  store-found-gone) still lands FAILED; and `app/schemas` is byte-identical to the tree this run started from,
  including the set of files under it.
  Done when: that run's log shows `BUILD SUCCESSFUL` with `EXIT=0`, `:app:testDebugUnitTest` executed rather than
  FROM-CACHE/UP-TO-DATE, 694 tests / 0 failures / 0 errors, and the four new names present in the reports;
  `git diff --quiet 9fa19e2^ -- app/schemas` is silent. If the gate turns up nothing to fix, commit with the gate
  command, EXIT, the executed-vs-cached line and the per-class totals in the body. Do not edit or run
  `verify.sh` — the runner owns it. No doc row is planned: `verify.sh` checks no doc wording, and doc rows are
  reviewed by Kyle in the pull request.
