# Tasks — Queue Flight Plans: bring in the four finished commits

Six tasks: one per cherry-pick, in the brief's order, then the whole-suite gate, then the M3 row.
Nothing here writes new behaviour: the code and its tests arrive inside the four commits.

Rules every task follows (from `plan.md` and `map.md`):

- The source branch `rho/queue-a-flight-plan-when-a-sortie-vmn4` is checked out in another worktree.
  Cherry-pick by sha only — never `git checkout` it, never merge it. Keep each commit's own message.
  If a pick will not apply, re-apply that commit's own diff from `git show <sha>`; never rewrite a
  picked file from memory. Check HEAD and the tree before each pick, and check the pick's file list
  against `map.md` before committing. Stage named files only, never `git add -A`.
- No task brings a `run:` commit, a file under `.rho/runs/`, or any In-Flight change onto this branch.
  No task edits `verify.sh`. Only `map.md` may be written under the run folder.
- Gradle: scratch scripts and logs under `app/build/` (`mkdir -p app/build` first), run detached, one
  Gradle invocation at a time, `mv app/build/test-results/testDebugUnitTest` aside before each run
  (never `rm`, never `gradlew --stop`). Every run clears the reports, so gate a task with one cold
  invocation naming every class it cares about, and read the per-class `tests=`/`failures=` totals out
  of the XML into the commit body. A run that hangs, or a test task that dies without writing
  `TEST-*.xml`, is re-run on the same tree; a reported assertion failure is a real result wherever it
  sits and is fixed at its cause — never by dropping or loosening a count or an assertion.
- Where a whole-table count breaks because `advance` now leaves a QUEUED `FLIGHT_PLAN` row beside the
  rows a fixture seeded, the fix scopes the count to the kind the test means, or asserts the
  `FLIGHT_PLAN` row explicitly, in its own `test:` commit.

- [x] 1. Cherry-pick `bcf6bae` — the data layer queues a Flight Plan and reports its id
  Covers: `data/GenerationDao.kt` (+8, the new `getOpenFlightPlanForSortie(sortieId)` query),
  `data/MissionManager.kt` (+105/-35: private `advance` now returns `Long?` — the id it inserted, or
  the id of the open row it found instead of a second one, or null; `land`/`scrub` return that `Long?`;
  `storeOutline`/`continueWithoutOutline` take a defaulted `onQueued: (Long) -> Unit` called after
  their transaction commits; `refly` returns `List<Long>`), and `MissionManagerTest.kt` (+93).
  No other file, no entity, no screen.
  Tests this task brings (write none of them yourself; they come in the pick):
  `storingAnOutlineQueuesSortieOnesFlightPlanAndReportsItsId`,
  `continuingWithoutAnOutlineQueuesSortieOnesFlightPlanAndStillHandsBackTheOpenOutlineIdsToCancel`,
  `landingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId`,
  `scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId`.
  Done when: `git show --stat HEAD` shows exactly those three files and nothing under `.rho/runs/`,
  with the message `data: queue a flight plan when advance reaches a pending sortie`;
  `bash gradlew.sh :app:compileDebugUnitTestKotlin` passes, which proves every existing call site of
  `land`, `scrub`, `storeOutline`, `continueWithoutOutline` and `refly` still compiles; and one cold
  detached `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  '*MissionManagerTest*' --tests '*GenerationDaoTest*'` reports 0 failures with those four names
  present in the reports. The whole suite is not run here; classes this pick only *affects* are
  task 5's job.

- [x] 2. Cherry-pick `a74b099` — the guard cases around queuing
  Covers: `MissionManagerTest.kt` (+157) only. No implementation change: these cases assert the
  behaviour `bcf6bae` wrote — the run-plans toggle both ways, an unconfigured Mission Control, and
  never queuing a sortie twice.
  Tests this task brings:
  `aCurrentRunSortieWithRunGenerationOnQueuesAFlightPlanAndWritesNoSimplePlan`,
  `aCurrentRunSortieWithRunGenerationOffGetsTheSimpleRunPlanAndQueuesNothing`,
  `turningRunGenerationOnAfterwardsLeavesTheSimpleRunPlanThatSortieAlreadyHas`,
  `aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken`,
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported`.
  Done when: `git show --stat HEAD` shows `MissionManagerTest.kt` alone, with the message
  `test: cover the guard cases around queuing a flight plan`, and one cold detached
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*MissionManagerTest*'` reports
  0 failures with all nine `MissionManagerTest` names from tasks 1–2 present.

- [x] 3. Cherry-pick `1afea86` — the worker enqueues the plan its own store queued
  Covers: `work/GenerationWorker.kt` (+26/-3: `StoreDecision.Wrote` gains `queuedPlanId: Long?`, the
  worker captures the id through `storeOutline`'s `onQueued` and calls `enqueueQueuedPlan` after its
  transaction, on the success path only, rethrowing `CancellationException` and swallowing anything
  else so a process with no WorkManager still ends a stored reply as success) and
  `GenerationWorkerTest.kt` (+65).
  Tests this task brings: `aWorkerThatStoresAnOutlineEnqueuesTheFlightPlanItQueuedForSortieOne` — the
  only new name, and the only one of the twelve that reads scheduled work back off WorkManager
  (`ENQUEUED` under `gen-<planId>`, under a bounded wait).
  Done when: `git show --stat HEAD` shows exactly those two files with the message
  `work: enqueue the flight plan the worker's own store queued, after its transaction commits`, and
  one cold detached `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
  '*GenerationWorkerTest*' --tests '*GenerationCancellerTest*' --tests '*GenerationEnqueuerTest*'`
  reports 0 failures. `GenerationCancellerTest` is not edited by any pick but configures Mission
  Control and stores an outline, so it is the first place an auto-queued row can break an existing
  count: if it reports a failure, fix the cause in a second `test:` commit in this task, scoped to the
  kind of generation the count means.

- [x] 4. Cherry-pick `a27d999` — the Launchpad enqueues and cancels what its actions did
  Covers: `ui/launchpad/LaunchpadViewModel.kt` (+20/-6: `continueWithoutOutline()` passes
  `queueGeneration` as the manager's `onQueued` — note `cancelGeneration` is `suspend` and is not
  assignable to that parameter; `confirmScrub` does `queuedPlanId?.let(queueGeneration)`; `refly()`
  hands each id `manager.refly` reports to `cancelGeneration`) plus `LaunchpadConfirmTest.kt` (+42) and
  `LaunchpadScrubTest.kt` (+62). No `LaunchpadScreen` or `AppContainer` change: the screen already
  builds the enqueuer at action time.
  Tests this task brings:
  `continueWithoutOutlineQueuesSortieOnesFlightPlanGenerationForEnqueuing` and
  `scrubFromTheLaunchpadQueuesTheNextSortiesFlightPlanGenerationForEnqueuing`.
  Done when: `git show --stat HEAD` shows exactly those three files with the message
  `launchpad: enqueue the flight plan its actions queued, cancel re-fly's open plans`, and one cold
  detached `bash gradlew.sh :app:testDebugUnitTest --no-build-cache` naming `--tests
  '*LaunchpadConfirmTest*' --tests '*LaunchpadScrubTest*' --tests '*LaunchpadReFlyTest*' --tests
  '*LaunchpadStatesTest*' --tests '*LaunchpadDraftControlsTest*' --tests '*LaunchpadDraftGuardsTest*'
  --tests '*LaunchpadDraftStatusTest*' --tests '*LaunchpadCoachStatusTest*' --tests
  '*LiftoffShellTest*' --tests '*ShellNavTest*' --tests '*MainActivityTest*'` reports 0 failures with
  both names present. The three shell classes are in this run because they mount the real container
  with no WorkManager, which is what shows the new enqueue call is reached only when an action runs.

- [ ] 5. Run the whole gate once and fix only what it names
  Covers: the one run that covers every class the picks touched or affected — the brief's "must not
  break" list (worker, enqueue and cancel, Confirm's outline queueing and the draft status, In-Flight
  and reopening, Landed, Re-fly, export/import, Mission Control, the shell tests, the domain tests)
  and the exported schema. Nothing else is added.
  Run detached: copy `verify.sh` to a scratch name under `app/build/` (byte-identical, `bash -n` and
  `cmp` it, and never edit the real one while it runs) and run it, waiting on its `EXIT=` line. Read
  the attempt's own log and the per-suite `TEST-*.xml` totals before touching any code. Retry only on
  a hang or a build-killed signature; treat every reported assertion failure as real and fix its
  cause — the likely one is a whole-table generation count in a class no pick edits, most probably
  `GenerationCancellerTest`.
  Done when: a detached `verify.sh` run ends `BRIEF DONE.` / `EXIT=0`, and the task ends with a
  commit: either one `test:` commit per failure it fixed (body quoting the failing report line and the
  run's totals), or, if nothing was red, one commit whose body carries the whole-suite totals
  (`N tests, 0 failures, 0 errors, 0 skipped`) and the verify run's `Result:` line — an empty commit is
  fine here, because the evidence has to be in the history for the later steps to see it. Expect 674
  tests: the untouched branch ran 662, and the twelve names are twelve new methods, unless a fix added
  one.

- [ ] 6. Rewrite the ARCHITECTURE.md M3 row and confirm nothing else moved
  Covers: `ARCHITECTURE.md` line 82 only. Replace exactly the closing clause
  `Queueing Flight Plans and the Launchpad's display of the current sortie's status come next` with
  what the code now does — Flight Plans queued as sorties become current, the run-plans toggle
  honoured, nothing queued without a host and token, the worker and the Launchpad enqueue after their
  own transactions commit, Re-fly cancels the sortie's open Flight Plan generation — ending with
  In-Flight's enqueue, Regenerate and the Launchpad's sortie status coming next. Keep it one line in
  the same row format. Leave the M5 and M6 rows alone; the PR body says M6's toggle half now exists in
  `advance`.
  Done when: `git show --stat HEAD` shows `ARCHITECTURE.md` alone with a `docs:` message; the old
  clause is gone from the file and the row is still a single line of the same table;
  `git diff d2fb423^ HEAD -- app/schemas` is empty (`d2fb423` is the commit that added this run's
  `brief.md`, the baseline `verify.sh` compares against) and
  `app/schemas/com.liftoff.app.data.LiftoffDatabase/` still holds only `1.json`; `git status` is clean
  apart from the run folder; and `git log` shows the four picks with their original messages and no
  `run:` commit or `.rho/runs/` file among them.
