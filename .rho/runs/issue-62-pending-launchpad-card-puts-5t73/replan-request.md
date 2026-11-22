# Replan request — this run's gate cannot be relied on to run clean, and I cannot fix it

## First, what is not in question

The feature work is finished and the gate does pass. On the committed tree, `bash
.rho/runs/issue-62-pending-launchpad-card-puts-5t73/verify.sh` returned `VERDICT: done` with exit 0 in **four
of five invocations** (`app/build/verify-task5-run2.log`, `-run3.log`, `-run4.log`, `-run5.log`), each with
`> Task :app:testDebugUnitTest` carrying no `FROM-CACHE` / `UP-TO-DATE` / `NO-SOURCE` suffix, 780 tests, 0
failures / 0 errors / 0 skipped across 94 reports, the five named tests `ok` and all six class checks `ok`.
The fifth invocation reported a test failure and stopped. Nothing here says the Launchpad title change is
wrong, unfinished, or unmeasured — see `findings.md` for the gate numbers and the call-site sweep.

## What I tried

1. Ran the gate five times and read the output each time, not just the exit code. Across those five
   invocations there were 8 gate attempts; 4 failed, in two different classes:
   - `LiftoffShellTest` — 3 attempts (`systemBackFromATabGoesToLaunchpad`,
     `systemBackFromMissionControlReturnsToTabItWasOpenedFrom`), each an expired 20 000 ms `waitFor`
     (`ComposeTimeoutException`). verify.sh's `transient_failure` recognised these as confined to that class
     and re-ran the gate; run 4 needed three attempts and still returned `done`, exit 0.
   - `InFlightScreenTest > sortieNoteActionOnNoChecklistOpensDialogAndSaves` — 1 attempt,
     `android.view.ViewRootImpl$CalledFromWrongThreadException` (`Expected: SDK 35 Main Thread @coroutine#3976
     Calling: DefaultDispatcher-worker-6 @coroutine#4018`) with a knock-on
     `java.lang.ArrayIndexOutOfBoundsException at SlotTable.kt:4003`. That class is not `LiftoffShellTest`, so
     `transient_failure` called it a real failure: "attempt 2 failed on a reported test result; not running the
     gate again", `VERDICT: not done — 1 check(s) failed`.
2. Ran that class alone: `gradle :app:testDebugUnitTest --tests
   com.liftoff.app.ui.inflight.InFlightScreenTest --no-build-cache` → 16 testcases, 0 failures, 0 errors, 0
   skipped, in 7s.
3. Looked for the same failure in the gate logs left by earlier runs on this machine
   (`/home/kyle/.rho/runner/worktrees/*/app/build/`). It is there: **issue 60's** gate log
   `app/build/verify-final-run3.log` lines 134 and 199 record the *same test* failing with the *same two*
   exceptions, in a run whose diff was the Mission tab's confirmation states, and issue 60's `verify.sh`
   refused to retry it for the same reason. Counting every gate log in every worktree: 8 recorded
   `LiftoffShellTest` failures and 4 recorded `InFlightScreenTest` failures. Issue 61's `verify.sh` had already
   widened its retry — its log says "failed on the build itself or on the recorded **flaky screen classes**"
   (plural) — and that run reached `verify: 778 unit tests ran and passed`.
4. Tested the suspected cause. Three full-suite runs with `maxHeapSize = "2g"` on the test task, forced by a
   gitignored init script (`app/build/scratch-task5/heap-init.gradle`, which prints
   `scratch-heap: :app:testDebugUnitTest maxHeapSize=2g`) rather than by editing the repository: all three
   green — `app/build/heap-test-1.log`, `-2.log`, `-3.log`, each 780 tests / 0 failures / 0 errors / 0 skipped,
   `> Task :app:testDebugUnitTest` executed, `BUILD SUCCESSFUL in 1m 9s`, exit 0. With Gradle's default 512m
   test heap the same suite failed 4 of 8 attempts.

## What makes the task impossible as written

"Run the gate; done when it passes" is only satisfiable here by luck. `verify.sh` retries a failure **only
when it is confined to `com.liftoff.app.ui.LiftoffShellTest`**, and this machine has a second full-suite flake,
in `com.liftoff.app.ui.inflight.InFlightScreenTest`, that its rule classifies as a genuine failure. So the
gate can return `not done` on a tree that has nothing wrong with it, and it has — twice now, in two different
runs, on the same test.

I cannot close that gap from inside this task:

- **`verify.sh` is off-limits.** The one change that would make this run's gate reliable is widening
  `transient_failure`'s flaky-class list to cover `InFlightScreenTest` as well, the way issue 61's script did.
  The runner discards any session that touches the file, so I did not touch it.
- **The likely root cause is outside the plan.** Gradle's default Test heap is 512m for a ~780-test
  Robolectric+Compose suite; `app/build.gradle.kts`'s `testOptions.unitTests.all { }` block sets only a
  5-minute task timeout and no `maxHeapSize`. The fix is one line there. That file is not in this plan's Files
  list, and this task says "No new source" — changing build configuration in the gate task would be a change
  the plan never authorised and the sweep never asked for.

## What is true instead

- The suite is green on this tree: 4 of 5 gate invocations `done` / exit 0, and 3 of 3 full-suite runs at a 2g
  test heap green. The failures are a harness race — a state change reaching the Compose node tree from a
  `DefaultDispatcher` worker while the tree is being torn down — not a regression from anything in issue 62.
- The flake is pre-existing and cross-run: the same test, the same exceptions, in issue 60's gate log.
- The gate's reliability is a property of the *script's* retry rule and of the test heap, both of which live
  outside a work task's reach.

## What I am asking the reviewer to choose

1. **Accept the four green invocations as this run's gate** (the tree has not changed since the red one), or
2. **Re-plan the gate step** so its retry rule covers both known flaky screen classes, and/or land the
   `maxHeapSize` bump in `app/build.gradle.kts` as its own issue first so a later run's gate does not need to
   gamble on 512m, or
3. **Tell me exactly what to re-run and record** — e.g. "run the gate until it returns `done` and cite only
   that invocation" — and I will do it.
