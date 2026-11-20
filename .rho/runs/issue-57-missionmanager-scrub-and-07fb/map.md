# Map — issue 57 (scrub / launch cancel the sortie's open Flight Plan generation)

Split into 6 ordered tasks in `.rho/runs/issue-57-missionmanager-scrub-and-07fb/tasks.md`.

## The code the issue names

**`app/src/main/java/com/liftoff/app/data/MissionManager.kt`** (539 lines) — the only writer of the week's
state; every refusal is an `IllegalStateException` unless noted.

- `scrub(sortieId, reason: String? = null): Long?` — line 264. Requires ACTIVE mission + current sortie;
  writes the SCRUBBED row, then `advance(mission.id, settings)`. Its **current return value is not a cancel
  list**: it is `advance`'s id — the FLIGHT_PLAN generation queued for the sortie that *became* current (a row
  it inserted, or the id of a row it found already open, line 521). Callers enqueue it.
- `launch(sortieId: Long)` — line 279. Returns Unit. Requires ACTIVE mission, current sortie,
  `requireNoneInFlight(db.sortieDao().getInFlight())`; writes the IN_FLIGHT row.
- `refly(sortieId): List<Long>` — line 345. The pattern to copy: last statement is
  `db.generationDao().getOpenFlightPlanForSortie(sortieId).map { it.id }` (361).
- `regenerateFlightPlan(sortieId, onQueued: (Long) -> Unit = {}): List<Long>` — line 379. Reads the open ids
  **before** inserting its new row (comment at 402-404: newest-first ordering would otherwise hand the row it
  just queued back to the caller to cancel), then `queued?.let(onQueued)` after the transaction commits.
- `continueWithoutOutline(missionId, onQueued): List<Long>` — line 197, and `storeOutline(..., onQueued)` —
  line 228. Same shape: **return the ids to cancel, hand the newly queued id to `onQueued` post-commit.**
- `resetWeek(missionId): List<Long>` — line 429 (cancel list for a whole Mission).
- `storePlan(sortieId, valid): StoreOutcome` — line 451. Refuses with `StoreOutcome.Refused("sortie is ${sortie.state}")`
  when the sortie is neither PENDING nor PLANNED (479-481) — the "sortie is SCRUBBED" / "sortie is IN_FLIGHT"
  the brief quotes. Also refuses a non-ACTIVE mission and a non-current sortie.
- `private suspend fun advance(missionId, settings): Long?` — line 498. Closes the Mission when all sorties
  are done; for a PENDING current sortie either writes a simple plan (`markPlanned`) or queues/reuses a
  FLIGHT_PLAN generation (`getOpenFlightPlanForSortie(current.id)`, 520-530). Never touches the sortie that
  was just scrubbed/landed, so its id can never be one of that sortie's open ids.

**`app/src/main/java/com/liftoff/app/data/GenerationDao.kt`**
- `getOpenFlightPlanForSortie(sortieId)` — line 114: `kind = 'FLIGHT_PLAN' AND status IN ('QUEUED','RUNNING')`,
  newest first. Already the query `refly`, `regenerateFlightPlan` and `advance` use; nothing new is needed.
- `getOpenOutlineForMission` (106), `getOpenForMission` (119), `getOpen` (126, the app-open sweep),
  `markCancelled(id, finishedAt)` (51).

**`app/src/main/java/com/liftoff/app/domain/Sorties.kt`** — pure transitions.
- `SortieTransitions` (6-16): PENDING→PLANNED/SCRUBBED, PLANNED→IN_FLIGHT/SCRUBBED, IN_FLIGHT→LANDED/SCRUBBED/PLANNED.
  So **launch only works on a PLANNED sortie**; scrub works from PENDING, PLANNED or IN_FLIGHT.
- `launch(sortie, at)` (40), `scrub(sortie, reason)` (56), `requireNoneInFlight` (34), `markPlanned` (63).

**`app/src/main/java/com/liftoff/app/work/GenerationCanceller.kt`** — `suspend fun cancel(generationId)` (36):
`workManager.cancelUniqueWork`, then one transaction marking the row CANCELLED (only when QUEUED/RUNNING),
then `giveUpSession(row.nabuSessionId)`, swallowing a daemon that isn't there. This is "the same helper".

**`app/src/main/java/com/liftoff/app/AppContainer.kt`** — `fun generationCanceller()` (46), `generationEnqueuer()`.

## Call sites

**`app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`** (250 lines) — already takes
`queueGeneration: (Long) -> Unit` and `cancelGeneration: suspend (Long) -> Unit` (28-29).
- `launch(onLaunched)` — 151: PLANNED and InFlight states; the InFlight branch only navigates, the Planned
  branch calls `manager.launch(sortieId)` (163).
- `confirmScrub(reason)` — 180: `val queuedPlanId = manager.scrub(...)` then `queuedPlanId?.let(queueGeneration)` (190-191).
- `refly()` (211) and `regenerate()` (232) — the loop to copy: `for (id in manager.X(...)) cancelGeneration(id)`.
- `continueWithoutOutline()` (111) — the onQueued + cancel loop.
- Every action catches `IllegalStateException` and calls `runCatching { manager.onAppOpen() }`.

**`app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`** — line 60 passes
`cancelGeneration = { id -> container.generationCanceller().cancel(id) }`.

**`app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`** (207 lines) — ctor (23-33) takes
`db, settings, sortieId, scope, missionManager = MissionManager(db, settings), queueGeneration = {}`.
**It has no cancelGeneration yet.** `scrub(reason, onDone)` (152-170) does
`val queuedPlanId = missionManager.scrub(sortieId, normalizeNote(reason))` then `if (queuedPlanId != null) enqueueQuietly(queuedPlanId)`.
`enqueueQuietly` (196) swallows a failed enqueue: "a generation the enqueue could not schedule stays QUEUED in
Room; it is not a failed land or scrub". `land`/`unlaunch` are the neighbouring actions.

**`app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`** — builds the VM at 51-58 with
`queueGeneration = { id -> container.generationEnqueuer().enqueue(id) }` and has `container: AppContainer` in scope.

**`app/src/main/java/com/liftoff/app/ui/mission/MissionViewModel.kt`** (20) and `MissionScreen.kt` (42) — the
other place `cancelGeneration` is already threaded through, for reference.

## Wiring, checked line by line (2026-11-20)

- `scrub`'s only main-source call sites are `InFlightViewModel.kt:159` and `LaunchpadViewModel.kt:190`;
  `manager.launch`'s only main-source call site is `LaunchpadViewModel.kt:163`. Only three places in the tests
  bind `scrub`'s return value: `MissionManagerTest.kt:713`, `:842`, `:872`.
- `MissionManagerTest.kt:725` — `private suspend fun seedFlightPlan(missionId, sortieId, status:
  GenerationStatus, at): Generation`. The case at 854 mixes the two actions: its first half asserts on
  `manager.land(...)` (which stays `Long?`, out of scope) and only its last `scrub` changes shape.
- `LiftoffShell.kt`: `shellScope = rememberCoroutineScope()` (79); `InFlightScreen(sortieId, container, onBack)`
  (127); `LaunchpadScreen(container, onOpenInFlight = …)` (170); `MissionScreen(container, actionScope =
  shellScope)` (177).
- `LaunchpadScreen.kt:40` takes `(container, onOpenInFlight)` and builds the VM through
  `internal fun launchpadViewModel(container, scope)` (54). **`LiftoffShell` is the only caller of
  `LaunchpadScreen`**; the Compose card tests render `LaunchpadContent` on their own view model, so a required
  `actionScope` on `LaunchpadScreen`/`launchpadViewModel` breaks no test.
- `InFlightScreen.kt:43` takes `(sortieId, container, onBack)` and is called by **17 sites in
  `InFlightScreenTest.kt`** (95, 149, 227, 300, 373, 459, 543, 618, 718, 799, 871, 932, 985, 1052, 1107, 1157).
  A new `actionScope` there needs a default (falling back to the screen's own `rememberCoroutineScope`) or all
  17 break. That class never taps Scrub — `scrubReason = null` is only a rendered-state field — so no real
  canceller or enqueuer runs in it.
- `MissionScreen.kt:31` declares `actionScope: CoroutineScope` with **no default**, which is safe only because
  `LiftoffShell` is its sole caller.

**`app/src/main/java/com/liftoff/app/MainActivity.kt`** — 34-42: `onAppOpen()` then `AppOpenSweep` with
enqueue/cancel lambdas. The sweep is the thing that today cleans up the stale rows the brief describes.

## Tests that exist

- `app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt` — scrub/launch behaviour. Exactly three cases read
  scrub's return value and will need the new shape: `scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId`
  (705-719), `aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken` (838-852, `assertNull(reported)`),
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` (854-878). That last one is the
  bug in miniature: it seeds a QUEUED row on sortie index 1 and a RUNNING row on sortie index 2, scrubs sortie 1
  (current, PENDING, mid-generation), and asserts `scrub` returned **sortie 2's** row id — the one `advance` reused
  for the new current sortie. Sortie 1's own QUEUED row is left open today. After the change the return value is
  `[queuedRow.id]` and `runningRow.id` goes to `onQueued`.
  Helper `seedFlightPlan(missionId, sortieId, status, at)` seeds an open FLIGHT_PLAN row — the fixture a
  scrub/launch cancel test needs. `manager.launch(...)` is called as a statement in ~10 places, so a
  `List<Long>` return does not break those.
- `MissionManagerReFlyTest.kt` — asserts the ids `refly` returns; the model for the new manager tests.
- `MissionManagerPlanTest.kt` (94, 378), `MissionManagerOutlineTest.kt` (260) — scrub/launch as statements.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadScrubTest.kt` — VM built at 108-114 already records
  `queuedIds` and `cancelledIds`.
- `LaunchpadRegenerateTest.kt` — the model for a cancel assertion: its `cancelGeneration` records the id **and**
  marks the row cancelled "as GenerationCanceller marks it, so a cancel read back is real" (136-143);
  `joinAction` (146) fails if the action returned no Job.
- `LaunchpadPlannedActionsTest.kt` — Compose render/tap tests over the Planned card (Regenerate, Scrub); no test
  taps Launch today.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` — VM built at 146 and 174
  (`EnqueueRecording` helper, 182); scrub assertions at 361-426, enqueue assertions at 577 and 665.
  `InFlightLeaveTest.kt` (303) and `InFlightRunTest.kt` also call `manager.launch(...)` as a statement.
- `app/src/test/java/com/liftoff/app/work/GenerationCancellerTest.kt` — the canceller's own tests.

## Docs that set rules

- **DESIGN.md §7.3**, last bullet (361-364): "Superseded results: when a regeneration is requested while a
  generation for the same sortie is queued or running, the old one is cancelled: its work is cancelled and its
  nabu session stopped. Results are stored only if the generation is still the sortie's current one." §7.2 names
  "Regenerate" as a generation trigger. §7.3 step 6: sessions are stopped on SUCCEEDED/permanent FAILED.
- **ARCHITECTURE.md** — Invariants 2 (`domain/` and `coach/` pure Kotlin), 5 (no DI framework, one `AppContainer`),
  6 (generations resumable from the row). Milestones gate on `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- **CLAUDE.md** — gate command; commit messages `area: lowercase summary`; stage named files only, never
  `git add -A`; short comments; DESIGN.md is not re-litigated (amendments go at its end).

## Gate on this machine

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` is the brief's command, but **this worktree's
`gradlew.sh` is the committed Windows-only variant** (`toolchain="C:/Users/corpo/android-toolchain"`; the
uname-case version exists only as an uncommitted edit in `/home/kyle/Projects/liftoff`). On this Linux box the
toolchain is `~/android-toolchain/{jdk,sdk,gradle}`; verified working:
`JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk ~/android-toolchain/gradle/bin/gradle --version`
→ Gradle 8.11.1, JVM 17.

Ran the whole gate here: `:app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, 775 unit tests, 0
failures, ≈2m cold / ≈1s warm. `app/build` is gitignored, so running the gate leaves the tree clean.

## Verify script (.rho/runs/issue-57-.../verify.sh) — how it reads the gate

- Gradle resolution order: `$LIFTOFF_GATE_GRADLE` → `bash gradlew.sh` if it actually runs (Git Bash on Windows)
  → `$HOME/android-toolchain/gradle/bin/gradle` with JAVA_HOME/ANDROID_HOME exported → `./gradlew` (CI's POSIX
  wrapper; `~/.gradle/wrapper/dists` is empty here, so it would download Gradle 8.11.1 — kept as the last resort)
  → else fail.
- Deletes `app/build/test-results/testDebugUnitTest` before the gate so the suite is proved to have run now;
  Gradle restores it (FROM-CACHE) because its declared outputs are missing.
- Reads `app/build/test-results/testDebugUnitTest/TEST-<fully.qualified.ClassName>.xml` — one file per class,
  93 of them, 775 testcases today. Shape: `<testcase name="X" classname="com.liftoff…" time="0.1"/>`, or
  `<testcase …><failure message="…" type="…">trace</failure></testcase>`; `<error …>` and `<skipped/>` also
  appear. The script splits records on `<testcase` with awk, folds each name (drop backticks/underscores/
  spaces, lowercase) and requires every named test to be present and PASS.
- Both paths simulated: injecting the named testcases into copies of the XML → exit 0; a `<failure>` child on
  one of them → exit 1. Untouched code → exit 1, 8 named tests missing.

## Signatures today (checked)

- `MissionManager.kt`: `scrub(sortieId, reason: String? = null): Long?` (264), `launch(sortieId): Unit` (279),
  `unlaunch` (299), `land` (321), `refly: List<Long>` (345), `regenerateFlightPlan(sortieId, onQueued): List<Long>`
  (379), `resetWeek: List<Long>` (429), `storePlan` (451), `private advance(missionId, settings): Long?` (498).
- `LaunchpadViewModel.launch(onLaunched: (Long) -> Unit)` (151) returns Unit — a launch test cannot `joinAction`,
  it must await the sortie becoming IN_FLIGHT. `confirmScrub(reason)` (180). `InFlightViewModel.scrub(reason, onDone)` (152).
- There is **no `MissionShell.kt`**. The action scope is threaded as `LiftoffShell.kt:177`
  `MissionScreen(container, actionScope = shellScope)` → `MissionScreen.kt:34,43` →
  `MissionViewModel(..., private val actionScope: CoroutineScope = scope, ...)` (`MissionViewModel.kt:25`, used at
  :53 `actionScope.launch { … }`). That is where a scrub/launch cancel scope has to be added.

## Test names and fixtures that already exist (the verify script requires some of the names)

- `MissionManagerTest` (40 tests): `scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId`,
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported`,
  `aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken`, `scrubInFlightKeepsCheckedSets`,
  `scrubbingLastSortieClosesMission`, `launchRecordsLaunchedAt`, `launchWorksAgainAfterUnlaunch`,
  `landingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId` (land stays `Long?` — out of scope),
  `continueWithoutOutlineHandsBackEveryOpenOutlineGeneration`, `storeOutlineQueuesSortieOnesFlightPlanAndReportsItsId`.
- `LaunchpadScrubTest` (4): `scrubFromTheLaunchpadQueuesTheNextSortiesFlightPlanGenerationForEnqueuing`,
  `launchpadScrubStoresBlankReasonAsNull`, `launchpadScrubTrimsTheReason`, `launchpadScrubKeepsInteriorLineBreaks`.
  Records `queuedIds` / `cancelledIds` (CopyOnWriteArrayList) and `escaped` throwables; `scope` is
  `Dispatchers.Unconfined + job + CoroutineExceptionHandler`. Helpers: `plannedLaunchpad()` (102, a two-sortie
  R-L week with the current sortie PLANNED, returns `Pair<LaunchpadViewModel, LaunchpadState.Planned>`),
  `awaitScrubbed(vm, sortieId)` (122, polls the SortieDao until SCRUBBED and `vm.scrubbing` is clear),
  `queuedPlanFor(sortieId)` (145, the one QUEUED FLIGHT_PLAN row read back from Room).
- `LaunchpadRegenerateTest`: `activeWeek(pattern = "LRL")` (122), `newViewModel()` (134),
  `joinAction(launched: Job?, vm)` (147), `awaitPending` / `awaitPlanned` / `awaitClosed`,
  `openPlanRowFor(sortieId)` (189), `queuedRowEnqueued(missionId, sortieId)` (199), `assertManagerRefuses`,
  `snapshot()` / `assertWroteNothing` / `insertSortieOf`, and the same `queuedIds` / `cancelledIds` recording.
- `InFlightLandScrubTest` (18): `scrubFromInFlightQueuesTheNextSortiesQueuedFlightPlanIdOnce`,
  `scrubFromInFlightKeepsCheckedSetsAndStoresReason`, `scrubWithAFailingEnqueueStillScrubbsAndShowsNoMessage`,
  `scrubbingASortieThatIsNoLongerCurrentIsRejectedWithoutCrash`,
  `landFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce`. Fixtures: `seedMission()` (96),
  `landSortie0` (103), `seedSortie1LiftPlan` (110), `liftPlanDraft()` (118), `launchSortie1()` (141, drives
  PENDING→PLANNED→IN_FLIGHT and returns `Pair<InFlightViewModel, InFlightState.Ready>`),
  `launchSortie1WithEnqueue(...)` (159), `private class EnqueueRecording` (182), `awaitReady` / `awaitRun` /
  `awaitDone`. `heldScope` is a **local** val in two tests (454, 731) that keeps a scope alive past the
  view model's own — the nearest existing model for the action-scope split.
- `MissionViewModelResetTest`: `screenScope` and `actionScope` are separate `CoroutineScope(Dispatchers.Default
  + Job())` fields (42-43); the test cancels the screen scope and keeps the action scope alive, then cancels
  both in tearDown.

## The 11 tests verify.sh names (checked against the tree, 2026-11-20)

Already in `MissionManagerTest.kt` and must be rewritten in place onto `onQueued`, not replaced:
`scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId` (705),
`aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken` (838, `assertNull(reported)`),
`aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` (854).

Absent, so each has to be written, in exactly one place:
- `MissionManagerTest` — `scrubbingASortieWithAnOpenFlightPlanGenerationReturnsThatRowsId`,
  `scrubbingASortieReturnsItsOpenRunningFlightPlanRowToo`,
  `launchingAPlannedSortieWithAnOpenFlightPlanGenerationReturnsThatRowsId`,
  `scrubbingOrLaunchingASortieWithNoOpenGenerationReturnsNothingAndStillWritesTheSortie`
- `LaunchpadScrubTest` — `scrubFromTheLaunchpadCancelsTheScrubbedSortiesOpenFlightPlanGeneration`
- `LaunchpadLaunchTest` (**new class**) —
  `launchFromTheLaunchpadCancelsTheSortiesOpenFlightPlanGenerationAndStillNavigates`
- `InFlightLandScrubTest` —
  `scrubFromInFlightCancelsTheSortiesOpenFlightPlanGenerationAndStillEnqueuesTheQueuedPlan`,
  `scrubFromInFlightCancelsTheOpenGenerationAfterTheScreenScopeIsCancelled`

`verify.sh` also fails on any test in any class that ran and did not pass, so the whole suite is part of done.

**Baseline, run in this worktree 2026-11-20:** `bash .rho/runs/.../verify.sh` → gate BUILD SUCCESSFUL (it
resolves `~/android-toolchain/gradle/bin/gradle` here), 775 unit tests ran and passed, exit 1 with exactly the
8 absent tests above listed as missing and the 3 existing ones reported `ok`. So the run's whole job is those 8
plus keeping the other 775 green.

## Facts worth remembering

- `scrub`'s existing return value is a *queued* id, not a cancel list — the change has to keep that channel
  (the next sortie's plan would otherwise never be enqueued) while adding the cancel list.
- `launch` can only fire on a PLANNED sortie, so "launching while a Regenerate is in flight" is exactly:
  PLANNED sortie holding a plan + an open QUEUED/RUNNING FLIGHT_PLAN row from Regenerate.
- A PENDING sortie mid-generation is scrubbable from the Pending card; an IN_FLIGHT sortie is scrubbable from
  the In-Flight screen. Both are cases the new cancel list covers.
- Cancelling must happen **after** the manager's transaction returns (as `refly`/`regenerate` do):
  `GenerationCanceller` opens its own transaction.
