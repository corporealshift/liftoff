# Findings — task 5: the gate, and the sweep of every shared-title call site

No source changed in this task. It ran the run's gate and swept the call sites; the sweep turned up nothing the
plan had not already decided to leave. What it did turn up is that the gate itself is not reliable on this
machine, which is written up in `replan-request.md` next to this file.

## The gate

`bash .rho/runs/issue-62-pending-launchpad-card-puts-5t73/verify.sh` from the repository root, five times. The
script's own logs are `app/build/verify-issue62/gate-<n>.log`, overwritten per attempt; the whole stdout of each
invocation is kept at `app/build/verify-task5-run1.log` … `…-run5.log`, and runs 3–5 record the script's exit
code on their last line.

**Four of the five invocations returned `VERDICT: done` with exit 0** (runs 2, 3, 4, 5). Each one reads:

- `> Task :app:testDebugUnitTest` with no `FROM-CACHE`, `UP-TO-DATE` or `NO-SOURCE` suffix; `BUILD SUCCESSFUL
  in 1m 9s`, `46 actionable tasks: 1 executed, 45 up-to-date` — the one executed task is the test task.
- `… reports: 780 tests, 0 failures, 0 errors, 0 skipped`. Read straight off the reports as well: 94
  `TEST-*.xml`, 780 testcases, and zero `<failure>`, `<error>` or `<skipped>` elements in any of them.
- The five names verify.sh requires all read OK, and all five are `<testcase>`s in
  `TEST-com.liftoff.app.ui.launchpad.LaunchpadPendingTitleTest.xml` (`tests="5" failures="0" errors="0"
  skipped="0"`): `pendingTitleWithALongFocusIsAtMostTwoLinesTall`,
  `pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus`,
  `pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize`,
  `pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall`,
  `plannedCardTitleIsStillOneLineAtTheFullDisplaySize`.
- All six class checks read OK: `TypographyTest`, `LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`,
  `LaunchpadViewInRhoTest`, `LaunchpadCoachStatusTest`, `LaunchpadStatesTest`.

Run 4 needed three attempts to get there (two `LiftoffShellTest` flakes first). Run 5 passed on attempt 1.

**The fifth invocation (run 1) returned `VERDICT: not done — 1 check(s) failed`,** and that is the one failure
this task had to decide about. Across the five invocations there were 8 gate attempts and 4 failed them:

- 3 were `LiftoffShellTest` — `systemBackFromATabGoesToLaunchpad` (twice) and
  `systemBackFromMissionControlReturnsToTabItWasOpenedFrom`, each an expired 20 000 ms `waitFor`
  (`ComposeTimeoutException` at `LiftoffShellTest.kt:43`). verify.sh's `transient_failure` saw a failure
  confined to that class and ran the gate again, which is what it is for.
- 1 was `InFlightScreenTest > sortieNoteActionOnNoChecklistOpensDialogAndSaves` —
  `android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view
  hierarchy can touch its views. Expected: SDK 35 Main Thread @coroutine#3976 Calling:
  DefaultDispatcher-worker-6 @coroutine#4018`, with a knock-on
  `java.lang.ArrayIndexOutOfBoundsException at SlotTable.kt:4003`: a state change reaching the Compose node
  tree from a `DefaultDispatcher` worker while that tree was being torn down. That class is not
  `LiftoffShellTest`, so verify.sh did not retry it — "attempt 2 failed on a reported test result", 780 tests,
  1 failure.

**Why that one is a flake and not a finding.** Four separate pieces of evidence:

1. The class alone — `gradle :app:testDebugUnitTest --tests
   com.liftoff.app.ui.inflight.InFlightScreenTest --no-build-cache` — is 16 testcases, 0 failures, 0 errors, 0
   skipped, in 7s.
2. Nothing in this run's diff (`Headings.kt`, `LaunchpadScreen.kt`, `design/README.md`,
   `LaunchpadPendingTitleTest.kt`) touches `InFlightScreen`, its view-model, or any dispatcher, and the failing
   case never opens the dialog (its own comment at `InFlightScreenTest.kt:1074` says so).
3. It has bitten this machine before, in another run: `/home/kyle/.rho/runner/worktrees/
   issue-60-mission-tab-says-not-confirmed-9c27/app/build/verify-final-run3.log` lines 134 and 199 record the
   same test failing with the same two exceptions, in a run whose diff was the Mission tab's confirmation
   states, and issue 60's `verify.sh` refused to retry it for the same reason. Counting every gate log left in
   every worktree on this machine: 8 recorded `LiftoffShellTest` failures and 4 recorded `InFlightScreenTest`
   failures. Issue 61's `verify.sh` had already widened its retry to "the recorded flaky screen classes"
   (plural) and that run reached `verify: 778 unit tests ran and passed`.
4. The suspected cause is heap, and it responds: three full-suite runs with `maxHeapSize = "2g"` on the test
   task — forced by a gitignored init script (`app/build/scratch-task5/heap-init.gradle`, which prints
   `scratch-heap: :app:testDebugUnitTest maxHeapSize=2g`) rather than by editing the repository — are all green
   (`app/build/heap-test-1.log`, `-2.log`, `-3.log`: 780 tests / 0 failures / 0 errors / 0 skipped each, the
   test task executed, exit 0). Gradle's default Test heap is 512m for a ~780-test Robolectric+Compose suite,
   and `app/build.gradle.kts`'s `testOptions.unitTests.all { }` block sets only a 5-minute timeout and no
   `maxHeapSize`.

Four greens out of five invocations, on an unchanged tree, is the state of the gate. The red attempt is not a
regression from issue 62; the gap is that this run's `verify.sh` retries only `LiftoffShellTest`, and this
machine has a second flaky class. That gap is `replan-request.md`'s subject, because closing it means either
editing `verify.sh` (off-limits) or `app/build.gradle.kts` (not in the plan's Files list, and this task adds no
source).

## The sweep

`grep -rn "TitleBlock(\|DisplayTitle(" app/src/main/java | grep -v theme/Headings.kt` → 17 call sites
(14 `TitleBlock`, 3 direct `DisplayTitle`), the count the map records. Each was read with its full argument
list and asked whether it passes `maxLines`, `overflow` or `onTextLayout`. **One does**:
`LaunchpadScreen.kt:256`, inside `PendingTitleBlock` (253), which `PendingContent` reaches at 218 and nothing
else calls. The other 16 pass none of the three, so `Headings.kt` hands `Text` its own defaults
(`Int.MAX_VALUE`, `TextOverflow.Clip`, no callback) and they draw what they drew before those parameters
existed. The line numbers in the task entry (`139, 191, 261, 277`) predate task 2's insertion into
`LaunchpadScreen.kt`; the same four call sites are now at 141, 193, 301 and 317.

| call site | what it draws | why it is left uncapped |
| --- | --- | --- |
| `LaunchpadScreen.kt:141` (Draft) | `title = "New Mission"`, a literal | no long text can reach it |
| `LaunchpadScreen.kt:193` (Planned) | `state.title` = the plan's title (`LaunchpadState.kt:209`) | plan Decision "Only the PENDING title changes; PLANNED keeps its display type"; this is the layout PENDING is being matched to, and `plannedCardTitleIsStillOneLineAtTheFullDisplaySize` is the guard |
| `LaunchpadScreen.kt:301` (InFlight) | `state.title` = the plan's title, falling back to the focus only when an in-flight sortie has no plan (`LaunchpadState.kt:287`) | the plan's Files list names only `PendingContent`'s call site; the map records an in-flight sortie always has a plan |
| `LaunchpadScreen.kt:317` (Closed) | `title = "Mission complete"`, a literal | no long text can reach it |
| `MissionScreen.kt:111` | `title = "Mission"`, a literal | no long text can reach it |
| `MissionScreen.kt:165` (WithPlan) | `state.title` = the plan's title (`MissionState.kt:135,153`) | plan Risk: the Mission screen's titles are left alone |
| `MissionScreen.kt:176` (NoPlan) | `state.title` = `sortie.focus ?: typeLabel` (`MissionState.kt:144,162`) — this one **can** carry the reported long focus | plan Risk names these two and leaves them; the brief names only the Launchpad card, and the defaulted cap makes extending the rule there a one-call-site change later |
| `MissionScreen.kt:202`, `:242` | `title = "Landed"` / `"Scrubbed"`, literals | no long text can reach them |
| `LandedScreen.kt:100` | `title = "Landed"`, a literal | no long text can reach it |
| `LandedScreen.kt:178` (`DetailHead`) | `title = planTitle ?: focus ?: typeWord` (`LandedState.kt:99`) — the same focus-fallback family | neither the brief nor the plan names the Landed screen; left as the plan leaves Mission |
| `MissionControlScreen.kt:143` | `title = "Mission Control"`, a literal | no long text can reach it |
| `PlaceholderScreen.kt:29` | the placeholder's own `eyebrow`/`title` parameters | placeholder content is not a sortie title |
| `InFlightScreen.kt:313`, `:440`, `RunContent.kt:58` | direct `DisplayTitle` at `LiftoffType.headerTitle()` (44 sp) | already the smaller display size the map records as the precedent for a shrinking Launchpad title; uncapped today and untouched by this run |

Nothing here is a call site the plan did not already decide to leave, so no call site was changed. The evidence
that the 16 uncapped sites still render as before is the gate itself: `TypographyTest` (which compares whole
`TextStyle` objects, so it fails on any change to a role's size, weight, line-height or letter spacing) and the
five Launchpad screen classes are green, and none of them passes a cap.

`verify.sh` was not edited, and no scratch file under `app/build/` is tracked.
