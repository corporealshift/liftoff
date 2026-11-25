# Findings — issue #60: the Mission tab said "Not confirmed yet" while the outline was generating

Task 6 of 6: gate, sweep, findings. Nothing in the sweep turned up something to fix, so this file is
the record of what was checked and what was deliberately left out of the run.

## Gate

`bash .rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/verify.sh`, run from the repo root. It
resolved its own toolchain to `$HOME/android-toolchain/{jdk,sdk,gradle}` (the committed
`gradlew.sh` is Windows-only and was not touched), and ran
`:app:assembleDebug :app:testDebugUnitTest --no-build-cache`.

Two invocations, both ending in the script's own verdict line:

| invocation | attempts | result |
| --- | --- | --- |
| 1st (`app/build/task6/verify-run1.log`) | 1: `LiftoffShellTest > bottomBarReachesEveryTab` FAILED · 2: same · 3: green | `verify: 780 unit tests ran and passed … Issue 60 done.` |
| 2nd (`app/build/task6/verify-run2.log`) | 1: green in 1m 7s | same line |

Numbers on the passing attempts: **780 test records over 93 reports, 0 failures, 0 errors, 0 skipped**
— the baseline 775 recorded in `tasks.md` plus the five new names. `:app:testDebugUnitTest` carried no
`FROM-CACHE`/`UP-TO-DATE`/`NO-SOURCE` on either passing attempt, so the suite really executed. All five
names `verify.sh` asks for are `MissionStatesTest` cases and all five are `ok`.

Per-class, from the passing run's reports: `MissionStatesTest` 17 (the 12 existing cases plus the 5 new
ones), `MissionTabStateTest` 1, `MissionViewModelResetTest` 1, `LaunchpadCoachStatusTest` 5,
`LaunchpadDraftStatusTest` 8, `LaunchpadPendingStatusTest` 6, `LaunchpadPlannedActionsTest` 3,
`LiftoffShellTest` 12 — all with 0 failures.

The two failing attempts are the recorded `LiftoffShellTest` flake, not this change: each had exactly
one failure out of 780, `bottomBarReachesEveryTab` expiring its 20 000 ms `waitFor`
(`ComposeTimeoutException` at `LiftoffShellTest.kt:43`). Run alone on this tree
(`--tests com.liftoff.app.ui.LiftoffShellTest --rerun-tasks --no-build-cache`) the class is 12/12 green
in 34 s. `verify.sh` retried it, which is what it is for; no feature code was changed to chase it. See
"Out of scope" for the heap.

## What the change is

Five commits, five files, `+327 −45` in `app/src`:

- `ui/mission/MissionState.kt` — the DRAFT arm joins `observeNewestNonCancelledOutlineForMission(mission.id)`
  and `manager.coachConfigured` and derives through `domain/deriveCoachStatus` unchanged; `observe()` took
  the `flatMapLatest` + flow-returning-`derive` shape `LaunchpadStates` already uses, with the join and
  `distinctUntilChanged()` inside the DRAFT arm and ACTIVE/CLOSED as `flowOf(...)` of what they built
  before. `MissionTabState.Week` gained `coachStatus: CoachStatus = CoachStatus.Editable`.
- `ui/mission/MissionScreen.kt` — `DraftCoachLine` picks the line from that status; the failed case shows
  the row's own reason in Red plus one muted pointer.
- `ui/launchpad/LaunchpadViewModel.kt` / `LaunchpadScreen.kt` — the wording lift below.
- `test/.../mission/MissionStatesTest.kt` — the five cases and the one-subscription harness.

No new domain rule, no new DAO query, no rho call, no new button on the tab.

## Wording decision (task 3)

**The lift was taken; the restatement fallback was not.** `PLANNING_LINE` ("Coach is planning the week…")
and `OFFLINE_LINE` ("Mission Control offline — retrying") moved out of `LaunchpadScreen.kt`'s
file-private consts into `LaunchpadViewModel.kt` as `internal const val`s, beside
`NOT_CONFIGURED_MESSAGE` — the codebase's only other coach string shared between a screen and its tests,
which is why that file is the place. Three readers now share the two declarations: the Launchpad's draft
card (`LaunchpadScreen.kt:172-173`), its PENDING card (`:245-246`, the use the plan warned must keep
working — it does), and the Mission tab's `DraftCoachLine` (`MissionScreen.kt:162-163`). The sentences
themselves did not change by one character, so `LaunchpadDraftStatusTest`'s inline literals and
`LaunchpadPendingStatusTest`'s own copies (347-348) still match what is drawn; `LaunchpadViewInRhoTest`
(256) keeps its own copy too. `MissionScreen.kt` keeps only its two tab-specific sentences,
`UNCONFIRMED_LINE` and the new `LAUNCHPAD_POINTER_LINE` ("Retry or continue on the Launchpad.").

## Sweep

- **Other readers of `MissionTabState.Week`.** `MissionScreen.kt` 75 (the Reset-week dialog reads
  `week.rows` and `canReset`), 101/108 (`WeekContent`), 300 (`MissionTabPreview`);
  `MissionViewModel.kt` 31/38/51 (`canReset` gates `requestReset`, `confirmReset` reads the state);
  `MissionState.kt` 237/264 (the only constructors); tests `MissionTabStateTest.kt:10`'s `week(status)`
  helper, `MissionStatesTest`, `MissionViewModelResetTest.kt:91`. None needed a change: the new field has
  a default, so a confirmed week and every hand-built `Week` carry `Editable`, which is the status the
  tab's own sentence is drawn from. `canReset` and the Reset flow are untouched.
- **Other places that draw the Mission tab's draft line.** One: `DraftCoachLine`, reached from the single
  `state.status == MissionStatus.DRAFT` gate at `MissionScreen.kt:131`. `UNCONFIRMED_LINE` survives as
  the only copy of the old sentence in main code, used for `Editable` and `NotConfigured`. The preview at
  300 builds an `ACTIVE` week, so it never draws a draft line and was left alone. No doc states the old
  rule: `grep` for "not confirmed" over `DESIGN.md`, `ARCHITECTURE.md`, `CLAUDE.md`, `README.md`,
  `coach/` and `design/` returns nothing.
- **Scratch under `app/src/test`.** None. `git status --porcelain --ignored app/src` is empty, and the
  only test file this run touched is `MissionStatesTest.kt`. Every scratch artifact lives under gitignored
  `app/build/` (`scratch-task1/`, `scratch-task2/`, `task4/`, `task5/`, `task6/`, `verify-issue60/`,
  `verify-old-results-*`), so no probe class was compiled or counted by the gate.

## Out of scope, left for the owner or a later issue

- **The `LiftoffShellTest` flake is a heap problem, not this change's.** Gradle's default 512m test heap
  under a ~780-test Robolectric+Compose suite is the recorded cause; three forced full reruns with
  `maxHeapSize = "2g"` injected from outside were green. The fix is one line in `app/build.gradle.kts`'s
  `testOptions.unitTests.all { }` and belongs in its own issue, not folded into this PR.
- **Nothing asserts the drawn line.** The brief asks for a `MissionState` test and `verify.sh` names no
  Compose test, so the Mission tab's rendered draft line is unpinned. `LaunchpadDraftStatusTest` is the
  model if the reviewer wants it pinned.
- **The muted pointer under a failed draft is copy the owner reviews.** "Retry or continue on the
  Launchpad." was added by plan review, not by the brief; the buttons themselves stay on the Launchpad per
  DESIGN.md §9, and the Mission tab's `canReset` stays false for a DRAFT week.
- **A failed draft's line is always the row's own reason.** The Launchpad replaces a failed draft's reason
  with its own report when a Retry found nothing configured, and that report lives in
  `LaunchpadViewModel`. The Mission tab runs no Retry, so it has no report of its own to put in that slot.
- **Re-emission for unrelated generation writes is not asserted.** The DRAFT arm's join is keyed by
  `mission.id` and `distinctUntilChanged()` sits inside the arm, so another mission's row or a cancelled
  row does not change the emitted value; `verify.sh` does not check it (it is a plan Risk, not a brief
  behavior). The live-follow case does prove the arm follows its own row on one subscription: replacing
  the join with a one-shot read of the outline row at derive time made exactly that case fail with a
  timeout while the four status cases kept passing, because those re-subscribe.
