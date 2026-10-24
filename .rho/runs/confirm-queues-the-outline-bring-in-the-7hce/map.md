# Map

Branch `rho/confirm-queues-the-outline-bring-in-the-7hce`, HEAD `d5d446b` (`run: plan outline`) on top of
`1f8d5fc goal: round 18 check` (that commit adds only `.../roadmap.md`, no code). Merge-base with the source
branch is `2a22c82 goal: round 17 check`.

Source branch `rho/confirm-queues-the-outline-bring-in-the-gny6` (checked out in
`C:/Users/corpo/.rho/runner/worktrees/confirm-queues-the-outline-bring-in-the-gny6`, at `83f99d7`) holds the
five picks interleaved with `run:` ticks: `c748582`, `29b1821`, `99aceec`, `6a16f32`, `9dae7bb`. Its extra
commits below the picks (`c1b5b2e`…`f73a240`, `38b40fe`, `219e02b`, `5d60090`, `83f99d7`) are brief/plan/verify
and task ticks — none are picked, and no file under `.rho/runs/` comes over.

Reading picked content: `git show <sha>:<path>` works in this repo (branch:path does not). Reading another
worktree's files by absolute path needs approval, so use `git show`/`git show <sha> -- <path>`.

## What each pick does (from its own message and diff)

| sha | files | effect |
|---|---|---|
| `c748582` | MissionManager.kt + 10 test files | adds `continueWithoutOutline(missionId)` (Unit at this point) as a copy of today's confirm body; moves 25 fixture call sites to it; confirm unchanged |
| `29b1821` | MissionManager.kt, GenerationDao.kt, **domain/Coach.kt (new)**, GenerationDaoTest, MissionManagerTest | confirm → `ConfirmOutcome`; inserts one QUEUED OUTLINE row (no sorties, no advance), reuses the open row's id on a second Confirm; `CoachNotConfigured` writes nothing; `setPattern` refuses while an outline row is QUEUED/RUNNING; `continueWithoutOutline` now returns `List<Long>` of open outline ids; moves 3 more `MissionManagerTest` fixture sites |
| `99aceec` | MissionManager.kt, MissionManagerTest | `retryOutline(missionId): ConfirmOutcome` — same configured check and same QUEUED row; `IllegalStateException` unless the Mission is this week's DRAFT and its newest non-cancelled OUTLINE row is FAILED |
| `6a16f32` | LaunchpadScreen.kt, LaunchpadViewModel.kt + 4 Launchpad test files | view model gains `queueGeneration`/`cancelGeneration` params, `notConfiguredMessage`, `confirm(): Job?`, `continueWithoutOutline(): Job?`, `retry(): Job?`, private `queueOrReport`; `internal fun launchpadViewModel(container, scope)`; test construction sites get no-op stubs |
| `9dae7bb` | LaunchpadScreen.kt, LaunchpadDraftControlsTest | `DraftActions` draws `UnderlinedTextButton("Continue without outline")` under `PrimaryButton("Confirm")`; `DraftContent` draws `notConfiguredMessage`; 2 new UI tests |

## Main sources

- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` (286 lines at HEAD) — the only entry point the UI
  uses for week/pattern writes. Constructor takes `db, settingsStore, clock, zone` (tests pass
  `clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC), zone = { ZoneOffset.UTC }`).
  Holds `confirm`, `setPattern`, `storeOutline`, `launch`, `scrub`, `refly`, `advance`, `onAppOpen`. After the
  picks: `confirm`/`retryOutline` return `ConfirmOutcome` (`Queued(generationId: Long)` | `CoachNotConfigured`,
  declared in this file), plus `continueWithoutOutline(missionId): List<Long>`. All three draft actions
  `check(...)` on "this week's DRAFT" and run inside `db.withTransaction`.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` (77 lines) — worker row helpers: `insert`, `get`,
  `update`, `markRunning`, `markSucceeded(id, finishedAt)`, `markFailed(id, error, finishedAt)`,
  **`markCancelled(id, finishedAt)`**, `saveSessionId`, `saveLastEventId`, `bumpAttempt`, `setCoachOfflineMark`,
  `getNewestNonCancelledForSortie`, `getNewestNonCancelledOutlineForMission(missionId)`. Picks add
  `observeNewestNonCancelledOutlineForMission(missionId): Flow<Generation?>` and
  `getOpenOutlineForMission(missionId): List<Generation>` (QUEUED/RUNNING only). Ordering everywhere is
  `createdAt DESC, id DESC` — with a fixed clock, `id` decides "newest".
- `app/src/main/java/com/liftoff/app/data/Generation.kt` — `Generation(id=0, kind, missionId, sortieId=null,
  status, nabuSessionId, lastEventId, attempt=0, error, createdAt, finishedAt)`; `Generation.COACH_OFFLINE =
  "Mission Control offline"`; `isCoachOffline`. `Enums.kt`: `GenerationKind { OUTLINE, FLIGHT_PLAN }`,
  `GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`.
- `app/src/main/java/com/liftoff/app/domain/Coach.kt` (new in `29b1821`) —
  `fun coachIsConfigured(daemonHost: String, daemonToken: String): Boolean`, both non-empty after `trim()`.
  Pure Kotlin (invariant 2). `AppContainer` and `DaemonConnectionCheck` already apply the same rule.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` (155 lines at HEAD) — at HEAD
  `confirm()` returns Unit and calls `manager.confirm`. After the picks: five constructor params
  (`db, manager, scope, queueGeneration: (Long) -> Unit, cancelGeneration: suspend (Long) -> Unit`);
  `internal const val NOT_CONFIGURED_MESSAGE = "Mission Control isn't set up. Add the host and token in Mission
  Control, or continue without an outline."`; `var notConfiguredMessage by mutableStateOf<String?>(null)` with
  `private set` (so a test can only set it by driving `confirm()`/`retry()`); `confirm()` nulls the message
  inside its coroutine before the manager call; `continueWithoutOutline()` cancels each id the manager returned,
  then clears the message; `retry()` → `manager.retryOutline`; all three return `scope.launch { … }` as `Job?`,
  `null` when `state.value !is LaunchpadState.Draft`, and each keeps `catch (_: IllegalStateException) {
  runCatching { manager.onAppOpen() } }`. `state = LaunchpadStates(db, manager).observe().stateIn(scope,
  SharingStarted.Lazily, LaunchpadState.Loading)`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (355 lines) — `LaunchpadScreen(container,
  onOpenInFlight)` composes `remember(container) { launchpadViewModel(container, scope) }`; the factory passes
  `queueGeneration = { id -> container.generationEnqueuer().enqueue(id) }` and
  `cancelGeneration = { id -> container.generationCanceller().cancel(id) }` — lambdas, not bound `::enqueue`,
  because the container asks WorkManager for itself and a bound reference would build it during composition and
  break `LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`. `LaunchpadContent(viewModel, onOpenInFlight)` is
  the renderable body apart from the container. `DraftActions` = stepper + `PrimaryButton("Confirm")` +
  `UnderlinedTextButton("Continue without outline")`; theme controls uppercase their labels, so test nodes read
  `CONFIRM` / `CONTINUE WITHOUT OUTLINE`, and neither control has an `enabled` param.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` (218 lines) — `LaunchpadState`
  (`Loading`, `Draft(missionId, pattern, …)`, `Planned(sortieId, …)`, `Pending`, `InFlight`, `Closed`) and
  `LaunchpadStates(db, manager).observe()`: DRAFT → `Draft`, otherwise `currentSortie(sorties)` decides
  Planned/Pending/InFlight/Closed. This is the derivation that shows "sortie 1 is current".
- `app/src/main/java/com/liftoff/app/settings/SettingsStore.kt` — `setDaemonHost`, `setDaemonPort`,
  `setDaemonToken`, `setDefaultPattern`, … ; `Settings.daemonHost = ""` and `daemonToken = ""` by default, so an
  unconfigured draft is the default state.
- `app/src/main/java/com/liftoff/app/work/GenerationCanceller.kt` — `suspend fun cancel(generationId: Long)`:
  cancels the unique work, `markCancelled(generationId, clock.millis())` only while QUEUED/RUNNING, gives up the
  rho session last. `GenerationEnqueuer.kt` — one unique one-time request named `gen-<generationId>`, `KEEP`,
  connected-network constraint, 1-minute backoff. Both already have their own tests; the new file uses neither.
- `app/src/main/java/com/liftoff/app/AppContainer.kt` — `settingsStore`, `database`, `missionManager` (lazy),
  `generationEnqueuer()`, `generationCanceller()`, `daemonConnectionCheck()`, `daemonClient()`.

## Tests

- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftGuardsTest.kt` — **the shape to copy for a
  view-model-only test**: `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`, in-memory Room with
  `allowMainThreadQueries()`, `SettingsStore(PreferenceDataStoreFactory.create { File(context.cacheDir,
  "settings.preferences_pb") })`, `MissionManager` with the fixed clock, `scope = CoroutineScope(Dispatchers
  .Unconfined + job + CoroutineExceptionHandler { _, t -> escaped.add(t) })`, `tearDown` = `job.cancel()` →
  `db.close()` → delete the settings file. Its `draftAt(pattern)` sets `setDefaultPattern` **before**
  `manager.onAppOpen()`, then builds the view model, then
  `withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Draft } }` — `stateIn` is `Lazily`, so an action
  called before the first collection sees `Loading` and returns null.
- `LaunchpadDraftControlsTest.kt` (5 tests after `9dae7bb`) — the Compose harness: `createComposeRule()`,
  `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, `runBlocking { job.cancelAndJoin() }` before `db.close()`
  because the compose rule tears down after `@After` and the view model's Room flow is still collecting.
  `draftAlwaysOffersContinueWithoutOutline` taps the way out on a draft with nothing queued and proves the action
  ran by the state leaving Draft; `draftShowsTheNotConfiguredMessageWhenItIsSet` drives `confirm()` on blank
  settings to set the message (it has a `private set`) and joins the returned `Job`.
- `MissionManagerTest.kt` (24 tests after the picks) — `configureCoach(host = "coach.local", token = "t0ken")`,
  `seedOutline(missionId, status)`, `assertPatternRefused(...)`, `assertRetryRefused(...)`; the confirm queue
  path (`confirmQueuesOneOutlineGenerationAndLeavesTheDraftUntouched`,
  `confirmWithoutAHostOrTokenQueuesNothingAndSaysSo`,
  `confirmingTheSameDraftTwiceReusesTheOpenOutlineGeneration`,
  `theWeeklyPatternIsFrozenWhileAnOutlineGenerationIsOpenAndEditableAgainAfterItFails`),
  `continueWithoutOutlineHandsBackEveryOpenOutlineGeneration`,
  `retryOutlineRefusesUnlessTheDraftsNewestOutlineHasFailed` (which already asserts the FAILED row keeps its
  status and error text, and that a second Retry refuses).
- `GenerationDaoTest.kt` — `theLiveOutlineReadForAMissionDropsACancelledRow`.
- Fixture sites the picks move from `confirm` to `continueWithoutOutline` (the PR statement names these): 28 call
  sites across 10 test classes — `MissionManagerTest` (9: 6 in `c748582` at lines 169, 229, 342, 364, 380, 438, 3
  more in `29b1821`), `MissionManagerOutlineTest` 243, `MissionManagerPlanTest` 82, `LaunchpadStatesTest` (170,
  187, 232, 254, 282, 305, 344), `LaunchpadReFlyTest` (142, 160), `LaunchpadScrubTest` 94, `InFlightLandScrubTest`
  95, `InFlightLeaveTest` 98, `InFlightRunTest` 97, `GenerationWorkerTest` (175, 208, 939, 1408). Count call sites
  only: a whole-file range diff also counts KDoc/comment mentions of `confirm(id)`, so
  `git show <pick> | grep -c "^-.*\\.confirm("` per pick is the reliable number (25 in `c748582`, 3 in `29b1821`).
  13 test files are touched in all; the 3 with no fixture move are `GenerationDaoTest` (new live-read test),
  `LaunchpadDraftControlsTest` (2 new UI cases) and `LaunchpadDraftGuardsTest` (helper arguments only).
  `MissionManagerTest`'s three confirm-behaviour tests stay on `confirm` and are renamed in `29b1821`.
  `6a16f32` updates the `LaunchpadViewModel(` construction sites in 4 test files: `DraftControls`, `DraftGuards`,
  `ReFly`, `Scrub` (`LaunchpadStatesTest` builds no view model).
- `GenerationEnqueuerTest`, `GenerationCancellerTest`, `GenerationWorkerTest` (28 tests) — the "must not break"
  work layer. Also `LiftoffShellTest` (12), `ShellNavTest` (21), `MainActivityTest` (2).
- Nothing in the Launchpad package proves the view model's `catch (_: IllegalStateException)` swallows a manager
  refusal: `LaunchpadDraftGuardsTest`'s `escaped` assertions cover the view model's own `>= 7` / `<= 1` chip
  guards, not a refused write. Its `escaped` list plus `assertTrue(escaped.isEmpty())` is still the shape to copy
  for a view-model-only test.
- Whole-suite size after `9dae7bb`: 633 tests, 0 failures.

## Docs that set rules

- `CLAUDE.md` — gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only, never `git add -A`; §2 vocabulary (`Mission`, `Sortie`,
  `FlightPlan`, `Generation`, never "session" for a sortie); short comments, why not what; LF endings.
- `DESIGN.md` §4.2 (lines 119–143) — Confirm queues an outline generation; the Mission becomes ACTIVE when the
  outline is stored; a failed outline leaves the draft "with an error and a retry"; "Continue without outline"
  activates with no outline. §2 vocabulary at line 51; §13 testing at 658. Do not re-litigate: an amendment goes
  at the end of DESIGN.md.
- `ARCHITECTURE.md` — invariants (2: `domain/` pure Kotlin; 5: one `AppContainer`, no DI framework; 7: session
  labels never start with `run:`). M3's line still says "App wiring — the screens that queue a generation and
  cancel one — comes next"; the brief asks for no doc change, so this run leaves it and the PR body says what
  the picks did.

## Machine notes

Run the gate detached from the worktree root with `mkdir -p app/build` first, log + `EXIT=$?` sentinel in the
script (not after a pipe), scratch under `app/build/`. A hung gate dies at the 5-minute test-task timeout and
writes no `TEST-*.xml` — re-run rather than fix. Move a stale `app/build/test-results` with `mv`, never `rm`;
`gradlew --stop` is machine-wide and would kill other worktrees' daemons.
