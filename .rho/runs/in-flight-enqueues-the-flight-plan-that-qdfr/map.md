# Map — In-Flight enqueues the Flight Plan that land and scrub queued

Branch state verified 2026-10-26. Line numbers are HEAD of this worktree.

## Docs that set rules

- `DESIGN.md:180-193` §5.3 Run sorties — the **Generate run plans** toggle is **off by default**: a run
  sortie gets a simple `SIMPLE_RUN` plan titled "Run" and **no generation is involved**; on, it gets a
  generated plan. "The toggle is read at generation time."
- `DESIGN.md:274-283` §7.2 Generation kinds — `FLIGHT_PLAN` is triggered by "previous sortie landed or
  scrubbed (next sortie)". "Generation is queued for the next sortie as soon as the previous one lands."
  "A run sortie with run generation off is never sent to the coach."
- `DESIGN.md` names the harness 'nabu'; it is now 'rho' (the brief says so; `Generation.nabuSessionId` is
  still the field name).
- `CLAUDE.md:34-43` — commit messages `area: lowercase summary`; stage named files only, never `git add -A`;
  §2 vocabulary; short comments (why, not what, 1–2 lines). Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.
- `ARCHITECTURE.md:82` (M3 row) already ends "In-Flight's enqueue, Regenerate and the Launchpad's display of
  the current sortie's status come next". **This brief forbids touching ARCHITECTURE.md**, so that clause is
  left for a later doc pass.

## Files this run changes

- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`
  - Ctor `(db, settings, sortieId, scope, missionManager = MissionManager(db, settings))` — 5 params, last defaulted.
  - `land(onDone)` :89, `landRun(distanceText, minutesText, onDone): Boolean` :117, `scrub(reason, onDone)` :148.
    Each: `left`/`busy` guards, `message.value = null`, `scope.launch { try { manager.land/scrub(...); leaveOnce(onDone) }
    catch (CancellationException) throw; catch (Exception) message = "Couldn't land/scrub this sortie."; finally busy = false }`.
    The manager's returned `Long?` is **discarded today**. `runErrors` :109, `message` :85, `left` AtomicBoolean :43,
    `watchLeave` :50, `leaveOnce` :45.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`
  - :48-51 `val scope = rememberCoroutineScope(); val vm = remember(sortieId) { InFlightViewModel(container.database,
    container.settingsStore, sortieId, scope, container.missionManager) }`; :54 `LaunchedEffect(vm) { vm.watchLeave { currentOnBack() } }`.
    The lambda is built inside `remember(sortieId)`, so it runs only when the key changes, not on every recomposition.

## The pattern the brief points at

- `LaunchpadScreen.kt:43` `remember(container) { launchpadViewModel(container, scope) }`;
  `:52-59` `internal fun launchpadViewModel(container, scope)` passes
  `queueGeneration = { id -> container.generationEnqueuer().enqueue(id) }` and
  `cancelGeneration = { id -> container.generationCanceller().cancel(id) }`. Its comment (:48-51) is the reason:
  a bound reference would ask the container for WorkManager every composition, including in the shell tests.
- `LaunchpadViewModel.kt:27` `private val queueGeneration: (Long) -> Unit` — the exact shape to copy.
  :191 `queuedPlanId?.let(queueGeneration)` right after `manager.scrub(...)` returns, inside the same `try`.
  **Note: the Launchpad does not swallow a failure from `queueGeneration`** — this brief asks In-Flight to.

## Where the id comes from

- `MissionManager.kt` (`com.liftoff.app.data`)
  - `land(sortieId, runDistance = null, runMinutes = null): Long?` :282-298 — requires the sortie IN_FLIGHT,
    updates it, `markOpenSetsNotDone`, then `return advance(sortie.missionId, settings)` inside `db.withTransaction`.
  - `scrub(sortieId, reason = null): Long?` :252-265 — requires mission ACTIVE and the sortie current; same shape.
  - `advance(missionId, settings): Long?` :382-419 — closes the mission when every sortie is done (returns null);
    otherwise, for a current **PENDING** sortie: `prepareCurrent(current, settings.generateRunPlans)`;
    `SimplePlan` → writes the plan, `markPlanned`, **returns null**; `AwaitGeneration` →
    `if (!coachIsConfigured(...)) return null` (:403), `getOpenFlightPlanForSortie(current.id)` and
    **`if (open.isNotEmpty()) return open.first().id`** (:405) — so the id can be an *already-open* QUEUED/RUNNING
    row, not a fresh insert — else insert one `Generation(kind = FLIGHT_PLAN, status = QUEUED, createdAt = clock.millis())`.
  - `continueWithoutOutline(missionId, onQueued: (Long) -> Unit = {})` :185-204 and `storeOutline(..., onQueued)` :216
    also call `advance` and hand the id to their defaulted callback **after** the transaction. `onAppOpen()` :70-93
    creates the draft only — it queues nothing.
- `domain/SortiePlanning.kt:13` `prepareCurrent(sortie, generateRunPlans)` → `SimplePlan` only when
  `type == RUN && !generateRunPlans`, else `AwaitGeneration`.
- `domain/Sorties.kt:8-13` — the only allowed transitions are PENDING→PLANNED, PLANNED→IN_FLIGHT / SCRUBBED,
  IN_FLIGHT→LANDED / SCRUBBED. `launch` (`:40`) requires PLANNED, so a run sortie left PENDING because the
  toggle is on **cannot be launched** — which is why the run-plan toggle must stay off while a fixture is
  seeding and launching sortie 0.
- `domain/Coach.kt:10` `coachIsConfigured(daemonHost, daemonToken)`.
- `settings/SettingsStore.kt` — `setDaemonHost` :46, `setDaemonToken` :55, `setGenerateRunPlans` :88
  (default `false`, :40), `setDefaultPattern` :63. Read fresh per manager call (`settingsStore.settings.first()`).

## WorkManager side

- `AppContainer.kt:39-40` `fun generationEnqueuer() = GenerationEnqueuer(WorkManager.getInstance(appContext))` —
  built per call on purpose (comment :34-38), so constructing a container touches nothing WorkManager-related.
  `WorkManager.getInstance` **throws IllegalStateException when WorkManager was never initialized**.
- `work/GenerationEnqueuer.kt:22` `fun enqueue(generationId: Long): OneTimeWorkRequest` — **not suspend**;
  unique one-time work `gen-<id>` (`uniqueName` :41) with `ExistingWorkPolicy.KEEP`, network-connected, 1-min backoff.
- Nothing today re-enqueues rows left QUEUED at app open: `GenerationStatus.QUEUED` appears only in
  `Generation.kt:30`, `MissionManager.kt:137/171/411`, `CoachStatus.kt:67`, `GenerationCanceller.kt:70`.
  `GenerationWorker.kt:363` re-enqueues its own generation on a retry.

## Tests that already exist

- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` — Robolectric `@Config(sdk = [34])`,
  in-memory Room, `SettingsStore` over a cacheDir file, `MissionManager(db, settingsStore, clock = fixed 2026-01-12T00:00:00Z,
  zone = UTC)`, scope `Unconfined + job + CoroutineExceptionHandler` recording into `escaped`.
  Fixture: `seedMission()` = `onAppOpen()` + `continueWithoutOutline(mission.id)` on the default RLRLR pattern;
  `landSortie0` (manager launch+land); `seedSortie1LiftPlan` (`writePlan` + `markPlanned`);
  `launchSortie1()` → `Pair<InFlightViewModel, InFlightState.Ready>`; `awaitReady`/`awaitRun`/`awaitDone` all
  `withTimeout(5_000)`. No `generationDao` field yet. No host/token set anywhere.
  **Landing the lift sortie 1 makes sortie 2 (a RUN) current** — asserted at :237-252 (`landMakesNextRunSortieCurrentWithSimpleRunPlan`),
  so with the toggle off `advance` returns **null** and nothing would be enqueued.
  With a host/token set the fixture's own `landSortie0` queues an open QUEUED row for sortie 1 (left PENDING, then
  planned by hand by `seedSortie1LiftPlan`), so a new test asserts against **sortie 2's** row, and the
  `setGenerateRunPlans(true)` it needs goes *after* `landSortie0` — with it on, sortie 0 is never PLANNED and
  `landSortie0`'s `launch` throws.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt` — same harness; `viewModel(sortieId)` helper :103;
  `landRun("5.2", "31") { ... }` tests at :306, :336, :361, :390, :420. Landing sortie 0 (a RUN) makes sortie 1
  (a LIFT) current → a QUEUED `FLIGHT_PLAN` row with a coach configured, no toggle needed.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt` — `container = AppContainer(context)` (:45),
  **no WorkManager initialization anywhere**; :387 taps `LAND` through the real screen in
  `noChecklistLandLandsAndCallsOnBack`, but that mission has one sortie and no host/token, so `advance` returns null.
- Construction sites for `InFlightViewModel(` (all positional, 4 or 5 args — a 6th defaulted param keeps them compiling):
  `InFlightLandScrubTest.kt:143,264,291,382,420,474`; `InFlightLeaveTest.kt:146,268`;
  `InFlightRunTest.kt:104,434`; `InFlightScreenTest.kt:213`; `InFlightViewModelTest.kt:51,546,673`.
- Recording-lambda precedent for the enqueue assertions: `LaunchpadConfirmTest` (queue/cancel lambdas recorded,
  no WorkManager).
