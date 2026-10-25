# Tasks

Seven tasks. Tasks 1–5 are the five cherry-picks, one per task, in the brief's order; task 5 closes the picks
with the whole-suite gate the plan's step 6 asks for. Tasks 6–7 write the one new test file, `LaunchpadConfirmTest`,
and close the run with the final whole-suite gate. No task writes production code: everything production is picked.

Rules that apply to every pick task: pick by sha only (`git cherry-pick <sha>`), never `git checkout` the source
branch `rho/confirm-queues-the-outline-bring-in-the-gny6` — it is checked out in another worktree. Keep each
commit's original message. After each pick, `git show --stat HEAD` must list exactly that pick's files and nothing
under `.rho/runs/`; if a `.rho/runs/` path rode along, drop it before committing. Never resolve a conflict by
dropping the pick's test changes. Gate with a compile check while iterating and one cold class-scoped test run;
never run the whole suite in a foreground call.

The ten test names under tasks 1–5 arrive inside the picks; they are listed under the pick that writes them, spelled
as `verify.sh` spells them. The three names the picks retire (`confirmPlansFirstRunWhenGenerationOff`,
`confirmLeavesLiftPending`, `confirmLeavesRunPendingWhenGenerationOn`) are renamed by `29b1821` and are deliberately
named by nobody. Suite totals: 624 tests today, 633 after the five picks, 639 after `LaunchpadConfirmTest`.

- [x] 1. Cherry-pick `c748582` — the fixture move
  Adds `MissionManager.continueWithoutOutline(missionId)` (returns `Unit` here) as a copy of today's `confirm` body,
  and moves 25 fixture call sites from `confirm(...)` to `continueWithoutOutline(...)` across ten test files. No
  behaviour change and no new test method; this is the move the PR statement is about.
  Files (11): `data/MissionManager.kt`; tests `MissionManagerTest`, `MissionManagerOutlineTest`,
  `MissionManagerPlanTest`, `InFlightLandScrubTest`, `InFlightLeaveTest`, `InFlightRunTest`, `LaunchpadReFlyTest`,
  `LaunchpadScrubTest`, `LaunchpadStatesTest`, `GenerationWorkerTest`.
  Tests: none of its own — the existing classes must still pass with the moved fixtures.
  Done: `git show --stat HEAD` shows this pick's message, 11 files, 62 insertions / 41 deletions, no `.rho/runs/`
  path; `bash gradlew.sh :app:compileDebugUnitTestKotlin` clean; a cold
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*MissionManagerTest*'` reports 18 tests, 0 failures.

- [x] 2. Cherry-pick `29b1821` — confirm queues the outline, and the pattern freeze
  The behaviour change: `confirm` returns `ConfirmOutcome` (`Queued(generationId)` / `CoachNotConfigured`), inserting
  or reusing exactly one QUEUED OUTLINE `Generation` row (`sortieId = null`) and leaving the Mission DRAFT;
  `continueWithoutOutline` now returns `List<Long>` of the mission's open outline ids; `setPattern` refuses while an
  outline row is QUEUED or RUNNING; new `domain/Coach.kt` `coachIsConfigured`; two new `GenerationDao` reads
  (`observeNewestNonCancelledOutlineForMission`, `getOpenOutlineForMission`). Also renames the three
  `MissionManagerTest` tests that recorded what `confirm` did when it activated the week.
  Files (5): `data/MissionManager.kt`, `data/GenerationDao.kt`, `domain/Coach.kt` (new), `GenerationDaoTest`,
  `MissionManagerTest`.
  Tests this pick brings, spelled as `verify.sh` spells them:
  `confirmQueuesOneOutlineGenerationAndLeavesTheDraftUntouched`,
  `confirmWithoutAHostOrTokenQueuesNothingAndSaysSo`,
  `confirmingTheSameDraftTwiceReusesTheOpenOutlineGeneration`,
  `theWeeklyPatternIsFrozenWhileAnOutlineGenerationIsOpenAndEditableAgainAfterItFails`,
  `continueWithoutOutlineHandsBackEveryOpenOutlineGeneration`,
  `continueWithoutOutlinePlansFirstRunWhenGenerationOff` (the rename of `confirmPlansFirstRunWhenGenerationOff`),
  `theLiveOutlineReadForAMissionDropsACancelledRow`.
  Done: `git show --stat HEAD` shows 5 files, 201 insertions / 21 deletions, no `.rho/runs/` path; a cold
  `--tests '*MissionManagerTest*'` reports 23 tests, 0 failures, and a cold `--tests '*GenerationDaoTest*'` reports
  7 tests, 0 failures, with the seven names above present in those reports.

- [x] 3. Cherry-pick `99aceec` — `MissionManager.retryOutline`
  Adds `retryOutline(missionId): ConfirmOutcome`: the same configured check and the same QUEUED OUTLINE row shape as
  `confirm`, refusing with `IllegalStateException` unless the Mission is the current week's DRAFT and its newest
  non-cancelled OUTLINE row is FAILED; blank host or token gives the not-configured outcome rather than a throw.
  Files (2): `data/MissionManager.kt`, `MissionManagerTest` (plus its new helpers `seedOutline`, `assertRetryRefused`).
  Test this pick brings: `retryOutlineRefusesUnlessTheDraftsNewestOutlineHasFailed`.
  Done: `git show --stat HEAD` shows 2 files, 121 insertions, no deletions, no `.rho/runs/` path; a cold
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*MissionManagerTest*'` reports 24 tests,
  0 failures, with that name in the report.

- [x] 4. Cherry-pick `6a16f32` — the view model's wiring
  `LaunchpadViewModel` gains `queueGeneration: (Long) -> Unit` and `cancelGeneration: suspend (Long) -> Unit`,
  `notConfiguredMessage` (private set), `NOT_CONFIGURED_MESSAGE`, `confirm()` / `continueWithoutOutline()` /
  `retry()` each returning `Job?` (`null` unless `state.value` is a `LaunchpadState.Draft`), each enqueueing or
  cancelling only after the manager call has returned, each catching `IllegalStateException`. `LaunchpadScreen.kt`
  gains `internal fun launchpadViewModel(container, scope)` passing lambdas, not bound `::enqueue` references —
  keep it that way, or `LiftoffShellTest`, `ShellNavTest` and `MainActivityTest` fail. Adds no test method; it
  updates the four Launchpad test classes' construction sites with no-op stubs, so the constructor change never
  leaves a broken build.
  Files (6): `ui/launchpad/LaunchpadScreen.kt`, `ui/launchpad/LaunchpadViewModel.kt`, and tests
  `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LaunchpadReFlyTest`, `LaunchpadScrubTest`.
  Tests: none of its own.
  Done: `git show --stat HEAD` shows 6 files, 124 insertions / 13 deletions, no `.rho/runs/` path;
  `bash gradlew.sh :app:compileDebugUnitTestKotlin` clean (it names any construction site the pick missed); a cold
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*LaunchpadDraftControlsTest*' --tests '*LaunchpadDraftGuardsTest*' --tests '*LaunchpadReFlyTest*' --tests '*LaunchpadScrubTest*'`
  reports 11 tests, 0 failures.

- [x] 5. Cherry-pick `9dae7bb`, then run the whole suite once on the five picks
  Draws the draft's way out (`UnderlinedTextButton("Continue without outline")` under `PrimaryButton("Confirm")`) and
  the not-configured line in `DraftContent`. Then the plan's step 6: one cold whole-suite gate,
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, run detached from the worktree root
  with `mkdir -p app/build` first, a log and an `EXIT=$?` sentinel inside the script (not after a pipe), a stale
  `app/build/test-results/testDebugUnitTest` moved aside with `mv` (never `rm`), and no `gradlew --stop` anywhere.
  If it is red, fix only what it names, in a separate test-only commit after the pick: fix which entry point a
  fixture calls, never weaken or delete an assertion and never move a fixture back to `confirm`. A suite that hangs
  and writes no `TEST-*.xml` is re-run on the same tree, not fixed.
  Files: `ui/launchpad/LaunchpadScreen.kt`, `ui/launchpad/LaunchpadDraftControlsTest`.
  Tests this pick brings: `draftAlwaysOffersContinueWithoutOutline`,
  `draftShowsTheNotConfiguredMessageWhenItIsSet`.
  Done: `git show --stat HEAD` shows 2 files, 90 insertions / 4 deletions, no `.rho/runs/` path, and
  `LaunchpadDraftControlsTest` now holds 5 tests; the gate log ends `BUILD SUCCESSFUL` with XML totals of 633 tests,
  0 failures, 0 errors, 0 skipped, and `git log --oneline 1f8d5fc..HEAD` shows exactly five code commits with the
  picked messages plus this run's `run:` commits.

- [x] 6. Write `LaunchpadConfirmTest` — the confirm cases
  New file `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadConfirmTest.kt`, package
  `com.liftoff.app.ui.launchpad` so the view model's `internal` `NOT_CONFIGURED_MESSAGE` is visible. View model +
  DAO only: no
  Compose, no compose rule, no WorkManager, no fake daemon. Copy the harness from `LaunchpadDraftGuardsTest`:
  Robolectric, `@Config(sdk = [34])`, in-memory Room with `allowMainThreadQueries()`, a real `SettingsStore` on a
  cache-dir file, a `MissionManager` on the fixed clock, a `Dispatchers.Unconfined` scope with a `Job` and a
  recording `CoroutineExceptionHandler`, and recording `queueGeneration` / `cancelGeneration` lambdas — the cancel
  lambda records its ids *and* calls `generationDao().markCancelled(id, finishedAt)` the way `GenerationCanceller`
  does. Write host, token and a default pattern starting with `L` (e.g. `"LRL"`) before `onAppOpen()`; wait for
  `state` to reach `Draft` under a timeout; assert the returned `Job` is non-null and join it under a timeout;
  assert the exception handler caught nothing. This task writes the file and the four Confirm cases; task 7 adds the
  other two cases.
  Tests this task writes, spelled as `verify.sh` spells them:
  `confirmWithMissionControlConfiguredQueuesOneOutlineGenerationForEnqueuingAndLeavesTheWeekADraft` — exactly one
  OUTLINE/QUEUED row for the draft with `sortieId` null, the queue lambda recorded exactly that id once, the Mission
  still DRAFT at the current week's start with no sorties;
  `confirmWithABlankHostQueuesNothingAndReportsTheNotConfiguredMessage`,
  `confirmWithAWhitespaceOnlyHostQueuesNothingAndReportsTheNotConfiguredMessage`,
  `confirmWithABlankTokenQueuesNothingAndReportsTheNotConfiguredMessage` — one test per setting, each on the fresh
  draft and database `@Before` builds: no generation row at all, neither lambda recording anything, the Mission still
  DRAFT, `notConfiguredMessage` set (it has a `private set`, so only driving `confirm()` on blank settings sets it);
  then each sets a valid host and token and Confirms again on the same draft, and that Confirm clears the line and
  queues exactly one row.
  Done: a cold `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*LaunchpadConfirmTest*'` reports 4
  tests, 0 failures, with all four names in `TEST-*.xml`; the file is committed alone with a `test: …` message.

- [ ] 7. Add the way out and the retry to `LaunchpadConfirmTest`, then the final whole-suite gate
  The remaining two brief cases, on the harness task 6 built. Continue-without-outline after a Confirm: Confirm and
  join, wait for `Draft` again, call `continueWithoutOutline()` and join — the Mission reads ACTIVE at the current
  week's start, sortie 1 exists and is the current one, and because the pattern starts with `L` that sortie stays
  PENDING so `state` reaches a `LaunchpadState.Pending` whose `sortieIndex` is that first sortie's (waiting for
  `Planned` would time out); the cancel lambda recorded exactly the outline id once and that row now reads
  CANCELLED. Retry after a FAILED outline: Confirm and
  join, mark that row with `generationDao().markFailed(id, "<error text>", finishedAt)`, wait for `Draft`, call
  `retry()` and join — `getNewestNonCancelledOutlineForMission` now returns a new QUEUED OUTLINE row with a
  different id, the FAILED row keeps its status and its exact error text, and the queue lambda recorded
  `[failedId, newId]`. The fixed clock makes every `createdAt` identical, so "newest" is the DAO's `id DESC`
  tie-break and the first Confirm's row always has the lower id. A refused retry is swallowed by the view model, so
  "a new row exists" is what catches it. Then one cold whole-suite gate exactly as task 5 ran it.
  Files: `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadConfirmTest.kt` only.
  Tests this task writes, spelled as `verify.sh` spells them:
  `continueWithoutOutlineAfterAConfirmActivatesTheWeekMakesSortieOneCurrentAndCancelsTheOutlineGeneration`,
  `retryAfterAFailedOutlineQueuesANewOutlineGenerationAsTheNewestAndRecordsItForEnqueuing`.
  Done: the gate log ends `BUILD SUCCESSFUL` with XML totals of 639 tests, 0 failures, 0 errors, 0 skipped, and
  `LaunchpadConfirmTest` reports 6 tests, 0 failures in that run; the commit touches only that file and its body
  records those totals.
