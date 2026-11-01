# Tasks — GenerationWorker: any non-daemon exception after `markRunning` strands the row

Four tasks, in the order plan.md §Order sets them. Each ends in a commit. The one test `verify.sh` names that does
not exist yet is written by exactly one task:

| test | task |
| --- | --- |
| `aStoreThatThrowsFailsTheGenerationWithItsOwnMessageAndStoresNothing` | 3 |

Baseline on this branch, measured by the run's map (a cold whole gate): **736 tests in 89 reports**, and
`GenerationWorkerTest` has **32** `@Test` cases. So after task 3 the expectation is **737 tests in the same 89
classes**, `GenerationWorkerTest` at **33**. plan.md's "738 / 34" is issue #54's numbers and does not hold on this
branch (newest merge here is #52, `55dfc6e`); use the map's numbers.

Gate conventions (CLAUDE.md and the run's map): `bash gradlew.sh …`, never bare gradle; scratch scripts and logs
under `app/build/`; a gate run launched detached with the log and an `EXIT=$?` sentinel appended without a pipe;
move `app/build/test-results/testDebugUnitTest` aside with `mv`, never `rm`; never `gradlew --stop` (machine-wide);
every class a check names in ONE invocation; re-run the identical tree once if the build kills the test task at five
minutes without writing any `TEST-*.xml` — the map records that this branch's first gate attempt did exactly that.
Restore a mutated file with `cp` from a byte-copy of the fixed version, never `git checkout` (the approval gate
denies it here). Do not edit or run `verify.sh`; the runner owns it.

Only the files each task names change: tasks 1 and 2 both edit `GenerationWorker.kt` and nothing else, task 3 edits
only `GenerationWorkerTest.kt`, task 4 changes no source. Line numbers quoted below are from HEAD `13d34e2`; they
shift once task 1 lands, so re-read the file rather than trusting them.

- [x] 1. Catch everything that is not a `DaemonException` in the turn and the store
  Covers plan step 1's first half — the half the brief's Test names.
  `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`, `runGeneration`: one more arm on the existing
  `try` (124–148), **after** the existing `catch (dropped: DaemonException)` (149) and before the
  `finally { client.close() }` (164), so the client is still open to give up the session. It rethrows
  `CancellationException` first, in the shape `giveUpSession` (406) and `handSessionBack` (420) already use, and
  otherwise ends through `failGeneration` with `e.message.orEmpty()` — the same `.orEmpty()` the permanent-refusal
  arm uses — and `reportedSessionId ?: row.nabuSessionId`, the session the turn ran on.
  No new test in this task, on plan §Order's purpose: the existing retryable cases are what prove the daemon's ends
  still reach their own arms (plan §Risks). `CoachTurnRunner`'s own limit is `withTimeoutOrNull`, so a 20-minute
  turn arrives as `TurnEnd.TimedOut`, never as a thrown `TimeoutCancellationException` the new arm could mistake for
  a stray throw.
  Done when: one cold detached `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  com.liftoff.app.work.GenerationWorkerTest` shows `BUILD SUCCESSFUL` with `EXIT=0`, the test task executed rather
  than FROM-CACHE/UP-TO-DATE, and `TEST-com.liftoff.app.work.GenerationWorkerTest.xml` at **32 tests, 0 failures,
  0 errors, 0 skipped** — the retryable ends (dropped connection retried with the row left RUNNING, the offline
  ladder, the paused/resume cases, the mid-turn cancels) and every permanent end still landing its own reason.
  Commit the worker file alone, and say in the body that no test lands here on purpose and why.

- [x] 2. The same handler for the work `doWork` does after it claims the row
  Covers plan step 1's second half. Same file, `doWork`: everything after `markRunning` (66) — the Mission read
  (70), the Sortie read (72) and the `ConnectionOutcome` `when` (77–101) — runs under the same handler, so a throw
  from Room or from the connection check ends through `failGeneration` with `client = null, sessionId = null`, the
  shape the "Mission … no longer exists" arm (71) already uses, rethrowing `CancellationException`. One handler, so
  both arms write the reason the same way and differ only in whether a client and a session id are there to give up
  (plan §Files).
  What must not change: everything before the claim keeps today's outcome — the missing row's `Result.failure()`,
  the already-finished row's `Result.success()`, the refused claim's `Result.success()` (plan §Decisions: a row
  nobody claimed stays QUEUED, which the card, the sweep and the cancel helper all handle); and the `when`'s
  `Result.retry()` arms (the offline ladder, `NotConfigured`, `AuthenticationRejected`) keep retrying, because a
  retry is a returned result, not an exception, and must never reach the new arm.
  No new test: `verify.sh`'s "What is NOT checked" records that there is no reachable trigger for a throw in this
  half today, and the arms that answer it are existing cases. `DaemonConnectionCheck.check()` already turns its own
  throws (a host written as a URL, a token a header can't carry, a dropped socket, a timeout) into outcomes, which
  is why nothing here can be reddened.
  Done when: one cold detached `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` naming
  `--tests com.liftoff.app.work.GenerationWorkerTest --tests com.liftoff.app.work.AppOpenSweepTest` in one
  invocation — `BUILD SUCCESSFUL`, `EXIT=0`, 0 failures, with `GenerationWorkerTest` still 32 and `AppOpenSweepTest`
  9; the gone-Mission, gone-Sortie, each protocol mismatch, not-configured and 8th-unreachable cases still land
  exactly where they land today. `AppOpenSweepTest` is named because the brief's second sentence — the sweep
  re-running the same failing path at every app open — is about this half, and `verify.sh` names it. Commit the
  worker file alone, with the per-class totals and the reason this half carries no test in the body.

- [x] 3. The store-throw case, and the proof that it bites
  Covers plan step 2. Test only — `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, one new case
  named exactly **`aStoreThatThrowsFailsTheGenerationWithItsOwnMessageAndStoresNothing`** (the name `verify.sh` §2
  looks for).
  Route: the lift-plan happy path's seeding (171 `aLiftPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan`
  — `onAppOpen()` → `continueWithoutOutline` → scrub the index-0 run, leaving index 1 a current PENDING LIFT →
  `insertFlightPlanGeneration`), with the scripted reply being `CoachFixtures.fixture(CoachFixtures.LIFT_PLAN)` with
  one exercise name — the fixture's four are `Bench Press`, `Pendlay Row`, `Front Squat`, `Plank` — replaced by a
  punctuation-only string. The schema accepts it (`minLength: 1`) and the validator's only name check (duplicates)
  accepts it, so the throw comes from `ExerciseDao.resolve` inside `FlightPlanDao.writePlan` inside the store
  transaction. No new fixture file.
  Asserts: `doWork()` returns a Failure rather than throwing and rather than retrying; the row FAILED with the
  exception's own sentence word for word — `Exercise name cannot be blank or symbols-only: '<the string you put in
  the reply>'`, since `writePlan` hands `resolve` the draft's raw name — with `finishedAt` set; nothing stored
  (`flightPlanDao().getPlan(sortieId)` null, no planned exercises or sets, the sortie still PENDING not PLANNED,
  because the whole transaction including `writePlan`'s own `deleteForSortie` rolls back);
  `assertSessionGivenUpOnce(daemon.sessionId)`; and the row still carrying `nabuSessionId == daemon.sessionId`.
  Done when: one cold detached `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  com.liftoff.app.work.GenerationWorkerTest` shows `BUILD SUCCESSFUL`, `EXIT=0`, and that run's own
  `TEST-com.liftoff.app.work.GenerationWorkerTest.xml` at **33 tests, 0 failures**, the new name read pass. Then
  prove it bites: keep a byte-copy of the fixed worker file under `app/build/`, change the new arm's caught type to
  one the store throw is not — a one-token edit that still compiles — re-run the class, and read the one red case in
  its own log: `doWork()` throwing the `IllegalArgumentException` out of the worker and the row left RUNNING with no
  `finishedAt`, which is the stranded row the issue is about; `cp` the copy back, re-run the class green, and commit
  the test file alone with the
  mutation, the gate command, `EXIT` and the totals in the body. Keep the mutation run's log and XML under their own
  names in `app/build/` so the green run's reports are the ones read for the totals, and confirm `git diff` shows no
  change to `GenerationWorker.kt` before committing.

- [x] 4. The whole gate, once, and the classes the change reaches through
  Covers plan step 3. No new behaviour and no new test; this task runs the gate and fixes only what it names, in its
  own commit if anything needs fixing. One cold detached `bash gradlew.sh :app:assembleDebug
  :app:testDebugUnitTest --no-build-cache` from a scratch script under `app/build/`, log plus `EXIT=$?` sentinel
  appended without a pipe; re-run the identical tree once if the test task is killed at five minutes with no
  `TEST-*.xml`. Read the log for `FAILED` as well as counting the totals, and sum the `TEST-*.xml` totals.
  What it confirms: the suite is **737 tests in 89 reports** with 0 failures, 0 errors, 0 skipped, and
  `:app:testDebugUnitTest` executed rather than FROM-CACHE/UP-TO-DATE; `aStoreThatThrows…` present and pass in that
  run's own reports; every class `verify.sh` §3 names ran green in that run — `GenerationWorkerTest` (33),
  `CoachTurnRunnerTest`, `GenerationDaoTest` (9), `GenerationEnqueuerTest`, `GenerationCancellerTest`,
  `AppOpenSweepTest` (9, including `decidedGenerationsAreLeftAlone`), `PromptInputLoaderTest`,
  `CoachReplyFixtureTest`, `CoachReplySemanticTest`, `SchemaCheckerValidDocumentTest`, `MissionManagerPlanTest`,
  `MissionManagerOutlineTest`, `FlightPlanDaoTest`, `ExerciseResolverTest`, `SortieGenerationStatusTest`,
  `CoachStatusTest`; every existing FAILED-expecting worker case (blocked turn, two refused repairs, permanent
  refusal, each protocol mismatch, the 8th unreachable attempt, a store that refused its target) still lands FAILED
  with its own reason, and every retryable end still retries with the row left RUNNING; `app/schemas` byte-identical
  to the tree this run started from (no entity changes).
  Done when: that run's log shows `BUILD SUCCESSFUL` with `EXIT=0` and those totals, and the new name is in the
  reports. If the gate turns up nothing to fix, this task changes no file, so commit it with
  `git commit --allow-empty --cleanup=verbatim -F app/build/issue55-task4-msg.txt` — the repo's established shape
  for a gate-only task — carrying the gate command, `EXIT`, the executed-vs-cached line and the per-class totals in
  the body. No doc row: plan §Files changes no document, and `verify.sh` checks no doc
  wording — `ARCHITECTURE.md` already describes the worker as ending the row "FAILED with its reason".
