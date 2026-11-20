# Tasks — issue 57 (scrub / launch cancel the sortie's open Flight Plan generation)

Read `brief.md`, `.rho/runs/issue-57-missionmanager-scrub-and-07fb/plan.md` and
`.rho/runs/issue-57-missionmanager-scrub-and-07fb/map.md` before your task. The plan's Approach, Decisions and
Risks sections are the rules; the map has the current signatures, call sites and fixtures. Each task below ends
with the CI gate green and one commit (`area: lowercase summary`, stage only the files you touched).

Gate on this machine (the committed `gradlew.sh` is Windows-only — do not edit it):
`JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk ~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest`

Order matters: 1 → 2 → 3 → 4 → 5 → 6. Task 3 is the only one that breaks a call site, so it carries its own
fixes. Never edit `.rho/runs/issue-57-missionmanager-scrub-and-07fb/verify.sh`; running it read-only is fine.

- [x] 1. `MissionManager.launch` hands back the launched sortie's open Flight Plan ids
  Covers: `launch` returns the launched PLANNED sortie's open FLIGHT_PLAN generation ids instead of Unit, read
  inside the transaction before the IN_FLIGHT write, doc comment in `refly`'s wording. No caller reads the value
  yet, so nothing else moves. Read DESIGN.md §7.3's superseded-results rule before writing it.
  Files: `app/src/main/java/com/liftoff/app/data/MissionManager.kt`; `app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt`.
  Tests you write: `MissionManagerTest.launchingAPlannedSortieWithAnOpenFlightPlanGenerationReturnsThatRowsId`
  (seed the open row with the class's `seedFlightPlan`; a QUEUED row and a RUNNING row on the launched sortie are
  both in the list, another sortie's open row is not).
  Done when: that test ran and passed, and the gate is green — `manager.launch(...)` is a statement in ~10 other
  tests and in `LaunchpadViewModel`, all of which keep compiling unchanged.

- [x] 2. Launchpad: the shell's action scope, and Launch cancels what `launch` handed back
  Covers: `LaunchpadViewModel` gains `actionScope` defaulting to its own `scope`, documented the way
  `MissionViewModel`'s is; `LaunchpadScreen` and `launchpadViewModel` take it and pass it on; `LiftoffShell`
  passes `shellScope` to `LaunchpadScreen` as it already does for `MissionScreen`. The Planned branch of
  `launch` starts one `actionScope.launch { cancelGeneration(id) }` per id returned by `manager.launch` and does
  not wait for them, so `onLaunched` navigates without a daemon round trip. Cancels start only after the
  manager's transaction has returned.
  Files: `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`,
  `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`,
  `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`; new test class
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadLaunchTest.kt` (plain view-model test,
  `LaunchpadScrubTest`/`LaunchpadRegenerateTest` style — recording `cancelGeneration` that marks the row
  CANCELLED in the fake as `GenerationCanceller` marks it, never the real canceller).
  Tests you write: `LaunchpadLaunchTest.launchFromTheLaunchpadCancelsTheSortiesOpenFlightPlanGenerationAndStillNavigates`
  (PLANNED sortie holding an open FLIGHT_PLAN row: the id is cancelled, the row reads back CANCELLED from Room,
  `onLaunched` still fires). `LaunchpadViewModel.launch(onLaunched)` returns Unit, so await the sortie becoming
  IN_FLIGHT the way `LaunchpadScrubTest.awaitScrubbed` polls the dao — you cannot `joinAction` it.
  `LaunchpadScreen`/`launchpadViewModel` may take `actionScope` as a required parameter: `LiftoffShell` is the
  only caller of `LaunchpadScreen`, and the Compose card tests render `LaunchpadContent` instead.
  Done when: that test ran and passed and the gate is green, with `LaunchpadPlannedActionsTest` and the other
  Launchpad tests untouched.

- [x] 3. `MissionManager.scrub` gains `onQueued` and hands back the scrubbed sortie's open ids
  Covers: `scrub` takes `onQueued: (Long) -> Unit = {}` and returns the scrubbed sortie's open FLIGHT_PLAN ids
  instead of the id `advance` queued. Read the open rows before the SCRUBBED write (`regenerateFlightPlan`'s
  ordering comment is the warning); hand `advance`'s id to `onQueued` only after the transaction commits. This
  is the only step that breaks call sites, so it carries their fixes: `LaunchpadViewModel.confirmScrub` passes
  `queueGeneration` as `onQueued` (no cancel loop yet — task 4 adds it) and `InFlightViewModel.scrub` passes
  `{ enqueueQuietly(it) }` as `onQueued`, leaving the returned list unread until task 5. Rewrite in place, with
  their assertions intact, the three `MissionManagerTest` cases that read the old `Long?`:
  `scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId`,
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` (assert both channels: the
  scrubbed sortie's own row comes back to cancel, `advance`'s reused id goes to `onQueued`), and
  `aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken`. If an assertion cannot be expressed through
  `onQueued`, stop and re-check the shape rather than loosening the test. `land` keeps its `Long?` return —
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` asserts on `manager.land(...)`
  in its first half and only its closing `scrub` changes shape.
  Files: `app/src/main/java/com/liftoff/app/data/MissionManager.kt`,
  `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`,
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`,
  `app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt`.
  Tests you write: `MissionManagerTest.scrubbingASortieWithAnOpenFlightPlanGenerationReturnsThatRowsId`,
  `MissionManagerTest.scrubbingASortieReturnsItsOpenRunningFlightPlanRowToo` (scrub is legal from PENDING,
  PLANNED and IN_FLIGHT), `MissionManagerTest.scrubbingOrLaunchingASortieWithNoOpenGenerationReturnsNothingAndStillWritesTheSortie`
  (no guard added: both still write the sortie and return an empty list; a row already SUCCEEDED, FAILED or
  CANCELLED is not returned).
  Done when: those three ran and passed, the three rewritten cases ran and passed with their original
  assertions, and the gate is green.

- [x] 4. Launchpad scrub cancels every id `scrub` handed back
  Covers: `LaunchpadViewModel.confirmScrub` starts one `actionScope.launch { cancelGeneration(id) }` per id
  `scrub` returned, after the manager's transaction returned, and keeps enqueuing the id `scrub` queued through
  the `onQueued` it now passes. A failed cancel must not turn a committed scrub into a failure.
  Files: `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`,
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadScrubTest.kt`.
  Tests you write: `LaunchpadScrubTest.scrubFromTheLaunchpadCancelsTheScrubbedSortiesOpenFlightPlanGeneration`
  (seed an open row on the sortie being scrubbed; assert the recording `cancelledIds` holds that id, the row
  reads back CANCELLED from Room, and `queuedIds` still holds the id queued for the next sortie).
  Done when: that test ran and passed and the gate is green, with the class's four existing scrub tests and its
  fixtures unchanged.

- [x] 5. In-Flight scrub cancels on the shell's action scope
  Covers: `InFlightViewModel` gains `cancelGeneration: suspend (Long) -> Unit = {}` and `actionScope` defaulting
  to its own `scope`; `InFlightScreen` passes `cancelGeneration = { id -> container.generationCanceller().cancel(id) }`
  the way `LaunchpadScreen` does, plus the `actionScope` it is now given; `LiftoffShell` passes `shellScope` to
  `InFlightScreen`. In-Flight `scrub` cancels the ids on `actionScope` — outside the `try` that reports
  "Couldn't scrub this sortie." — before `leaveOnce`, and still enqueues the id it queued via `enqueueQuietly`.
  Files: `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`,
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`,
  `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`,
  `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` (its fixture gains a recording
  `cancelGeneration` and a separate action scope; `MissionViewModelResetTest`'s screen/action split is the model).
  Note: `InFlightScreen` is called by 17 sites in `InFlightScreenTest.kt`, so its new `actionScope` needs a
  default that falls back to the screen's own scope (unlike `MissionScreen`, whose only caller is `LiftoffShell`);
  `InFlightScreenTest` never taps Scrub, so no real canceller runs there.
  Tests you write: `InFlightLandScrubTest.scrubFromInFlightCancelsTheSortiesOpenFlightPlanGenerationAndStillEnqueuesTheQueuedPlan`,
  `InFlightLandScrubTest.scrubFromInFlightCancelsTheOpenGenerationAfterTheScreenScopeIsCancelled` (cancel the
  screen scope the moment `onDone` fires, keep the action scope alive: the row still reaches CANCELLED — do not
  put the cancels on the screen scope).
  Done when: both ran and passed and the gate is green, with the class's existing land/scrub/enqueue tests and
  `InFlightLeaveTest`/`InFlightRunTest` untouched.

- [x] 6. Read the change against the docs and run the whole gate
  Covers: re-read `MissionManager.launch`/`scrub`, `LaunchpadViewModel` and `InFlightViewModel` once against
  DESIGN.md §7.3 and ARCHITECTURE.md invariants 2, 5 and 6; confirm nothing else moved — the
  `refly`/`regenerateFlightPlan`/`continueWithoutOutline` cancel lists, the app-open sweep in `MainActivity`,
  Reset week, `land`/`unlaunch` (still out of scope), and the existing Launchpad and In-Flight card tests. Fix
  any call site or test the new signatures left behind anywhere in `app/src`, and no DESIGN.md amendment (§7.3
  already states the rule). Commit as `data: scrub and launch cancel the sortie's open generation`.
  Done when: `bash .rho/runs/issue-57-missionmanager-scrub-and-07fb/verify.sh` exits 0 and `git status` is
  clean; commit whatever the sweep changed.
