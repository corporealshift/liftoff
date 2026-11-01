# Tasks

Five tasks, in the plan's Order. Each is one session and ends in a commit.

Shared facts every session needs (they are in `map.md`):

- Gate wrapper on this machine is `bash gradlew.sh` (bare `gradlew`/`adb`/`java` are not on PATH). Clear
  `app/build/test-results/testDebugUnitTest` before each gate so the XML you read is from that run, and always pass
  `--no-build-cache`. Prefer `mv` over `rm` for the results dir — a stranded test executor can be holding
  `binary/output.bin` — and give the moved-aside directory a name unique to your task. Scratch files and logs go
  under `app/build/`; never under `app/src/test`.
- Baseline at this HEAD (`97bf8f1`, tree of the merge of #52 at `174093d`): **736 tests in 89 reports, 0 failures**.
  Tasks 2 and 3 each add one test, so the whole suite is 738 tests by task 5.
- Two known timing flakes this run does not touch and that may need a re-run of the same tree:
  `doneBodyweightSetStepperShowsBW` and `aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt`.
- Do not edit `.rho/runs/issue-54-generationworker-notconfigured-sga5/verify.sh`. Do not touch `DESIGN.md` or
  `ARCHITECTURE.md` — the plan's Files says neither needs a change.
- A mutation proof needs a backup of the file taken from the file *as it is now*, restored with `cp` (not
  `git checkout --`, which the approval gate denies here), and its log kept separate from the green run's log. In a
  mutation run, read the failing test's name out of the JUnit XML — `BUILD FAILED` / `EXIT=1` is the expected result
  there, not a problem.

- [x] 1. End the generation on the two settings faults
  Covers the plan's Order step 1, in `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` only: the
  `else -> Result.retry()` arm at :100 becomes named arms for `ConnectionOutcome.NotConfigured` and
  `ConnectionOutcome.AuthenticationRejected`, each ending through the existing `failGeneration` (map: :389-403)
  exactly as the `ProtocolMismatch` arm above it does — `client = null, sessionId = null`, because a connection-check
  failure has opened no session. The two sentences the brief names, "Mission Control isn't set up" and "Mission
  Control refused the token", join the file-level constants next to `COACH_UNREACHABLE` (:462-484). No `else` is
  kept as a safety net: naming both arms is what makes the `when` over the sealed `ConnectionOutcome` exhaustive, so
  a future outcome is a compile error. `Unreachable` and its 8-attempt `coachUnreachable` path (:96, :175-181) stay
  exactly as they are. No test is written by this task.
  Done-when: the whole suite, cold, is green at the baseline count — 736 tests, 0 failures, 89 reports — and the
  commit exists. The compile itself proves the `when` is exhaustive without an `else`.
  Check: `mv app/build/test-results/testDebugUnitTest app/build/t1-old 2>/dev/null; bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/t1.log 2>&1; grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*.xml | awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print t, "tests", f+e, "failed"; exit (t>=736 && f+e==0 ? 0 : 1)}'`

- [x] 2. The blank host or token case
  Covers the first of the plan's two worker cases, in
  `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, beside the existing offline and mismatch cases.
  Test to write, under this exact name (it is one of the two names `verify.sh` looks for):
  **`aBlankHostOrTokenFailsTheGenerationAsMissionControlIsntSetUp`**.
  Shape (plan's Testing, first bullet; helpers listed in map's "Harness details"): seed the row the way the
  unreachable path leaves it — RUNNING with `Generation.COACH_OFFLINE` in `error` — so the case shows the new reason
  replacing that text rather than sitting under it; blank the host or the token in `container.settingsStore` (the
  container re-reads settings per call; `pointSettingsAtNothing()` is the *Unreachable* recipe, not this one);
  `runWorker(id)` at `runAttemptCount = 0`; assert `Result.Failure`, then the row FAILED with `error` exactly
  "Mission Control isn't set up", `finishedAt` stamped, `isCoachOffline` false, `nabuSessionId` null, the row's own
  `attempt` still 0, nothing stored, and nothing at all reaching the fake daemon — not even the check's hello. Reuse
  the class's existing permanent-end row assertion `mismatchFailedRow` (:1865) rather than restating it.
  Prove it bites: back up `GenerationWorker.kt` from the current file, revert only the `NotConfigured` arm to
  `Result.retry()`, run the class gate, and read the failing test's name out of the XML — it must be this one; then
  `cp` the file back and re-run green.
  Done-when: the class report contains this test and it passed, the class's other cases are unchanged in count and
  all pass, and the commit exists.
  Check: `mv app/build/test-results/testDebugUnitTest app/build/t2-old 2>/dev/null; bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.work.GenerationWorkerTest" > app/build/t2.log 2>&1; grep -c 'name="aBlankHostOrTokenFailsTheGenerationAsMissionControlIsntSetUp"' app/build/test-results/testDebugUnitTest/TEST-com.liftoff.app.work.GenerationWorkerTest.xml`

- [x] 3. The refused token case
  Covers the second of the plan's two worker cases, same file, same neighbourhood.
  Test to write, under this exact name (the other name `verify.sh` looks for):
  **`aRefusedTokenFailsTheGenerationAsMissionControlRefusedTheToken`**.
  Shape (plan's Testing, second bullet): make the fake refuse the WebSocket upgrade the way
  `DaemonConnectionCheckTest.aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable` does — set
  `FakeDaemon.refuseUpgrade` (:94, which answers 401 + `DISCONNECT_AT_END` at :161-168) before running the worker,
  and leave the mechanism alone so the server shutdown does not hang; run the worker over a row that was queued with
  a token that used to work, at `runAttemptCount = 0`; assert `Result.Failure`, then the row FAILED with `error`
  exactly "Mission Control refused the token", `finishedAt` stamped, `isCoachOffline` false, the row's `attempt`
  still 0, nothing stored on the mission or the sortie, and no `rho.session.stop` frame reaching the daemon — the
  upgrade never completed, so there was no session to give up (plan's Decision "The rho session the row may still
  carry is left alone"). Reuse `mismatchFailedRow` and the class's existing frame assertions rather than restating
  them.
  Prove it bites: back up `GenerationWorker.kt` from the current file, revert only the `AuthenticationRejected` arm
  to `Result.retry()`, run the class gate, read the failing test's name out of the XML — it must be this one; then
  `cp` the file back and re-run green.
  Done-when: the class report contains this test and it passed, every other case in the class still passes, and the
  commit exists.
  Check: `mv app/build/test-results/testDebugUnitTest app/build/t3-old 2>/dev/null; bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.work.GenerationWorkerTest" > app/build/t3.log 2>&1; grep -c 'name="aRefusedTokenFailsTheGenerationAsMissionControlRefusedTheToken"' app/build/test-results/testDebugUnitTest/TEST-com.liftoff.app.work.GenerationWorkerTest.xml`

- [ ] 4. Correct the sweep's KDoc
  Covers the plan's Order step 3, in `app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt` — the KDoc at :29-30
  ("a row asked for while Mission Control was configured is still resumed under the worker's offline handling when
  it isn't") is now wrong for a blank host or a refused token, since those rows are ended rather than resumed. One
  sentence corrected; no behaviour change in this file, and no test written or changed. The sweep itself still reads
  no settings — that part of the sentence stays true.
  Done-when: the sentence no longer contains the old wording "is still resumed", `AppOpenSweepTest` ran and every
  test in it passed, and the commit exists.
  Check: `mv app/build/test-results/testDebugUnitTest app/build/t4-old 2>/dev/null; bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.work.AppOpenSweepTest" > app/build/t4.log 2>&1; grep -c 'is still resumed' app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt; echo 'expect the grep to print 0, and no FAILED test in t4.log'`

- [ ] 5. The whole suite, cold, and read its log
  Covers the plan's Order step 4: one cold whole-suite run of the finished tree, then read the log and the reports
  rather than trusting the class gates. Confirm the classes `verify.sh` requires all ran with nothing failing —
  `GenerationWorkerTest`, `DaemonConnectionCheckTest`, `CoachStatusTest`, `SortieGenerationStatusTest`,
  `AppOpenSweepTest` — and that the cases this change could disturb are in the passing set: the three offline cases
  (`anUnreachableDaemonMarksTheGenerationOfflineAndRetries`, `aLaterRunThatReachesTheDaemonClearsTheOfflineMark`,
  `theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable`) and the three mismatch cases that use
  `mismatchFailedRow`. Expect 738 tests in 89 reports, 0 failures. If anything is red, fix it in this task's commit
  and re-run; if only one of the two known timing flakes is red, re-run the same tree before treating it as real.
  Fix nothing that is not red — in particular do not "help" by queueing a generation when the settings are blank,
  and do not harmonise the row's reason with Mission Control's longer Test-connection wording.
  Done-when: the run's log and XML totals are in the commit body (738 tests, 0 failures, 89 reports), and the tree
  is clean.
  Check: `mv app/build/test-results/testDebugUnitTest app/build/t5-old 2>/dev/null; bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/t5.log 2>&1; grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*.xml | awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print t, "tests", f+e, "failed"; exit (t==738 && f+e==0 ? 0 : 1)}'`
