# Map

Run: planned-card-regenerate-beside-scrub-vbvy. Branch base `462a5a9` (after PR #48, the PENDING card's status lines + Retry).

## Docs that set rules

- `DESIGN.md` §9 — "PLANNED: the plan summary, with Launch, Regenerate and Scrub." §7.2 run-generation toggle, §7.3 supersede rule (quoted in `MissionManager.regenerateFlightPlan`'s own doc).
- `CLAUDE.md` — theme-parts-only rule (no stock M3 visuals), labels upper-cased by the theme.
- `design/screens/launchpad.html` lines 63-71 — the PLANNED card's bottom block: LAUNCH (72px), then a flex row `gap: 24px` holding Regenerate then Scrub, each 44px tall, underlined, `padding: 0 8px`. No re-planning line is drawn in the mockup.

## Files involved

### `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (479 lines) — the only file to change

- `LaunchpadContent` (l.63-128): the composable tests render, apart from `AppContainer`. Body `when (s)` at l.80-87 → `PlannedContent(s as LaunchpadState.Planned, viewModel)` at l.83. `bottomBar = ActionButtons(...)` at l.91-103 — **`onRegenerate = { viewModel.regenerate() }` is already threaded** (l.102) and already passed to `PendingActions` (l.283); only the `PlannedActions` arm (l.282) ignores it.
- `PlannedContent` (l.180-187): TitleBlock → 16dp → `PatternTrack` → 20dp → `FlightPlanSection(plan, sortieType, sortieFocus)`. The new line goes between the 20dp spacer and `FlightPlanSection`.
- `PendingContent` (l.189-208) + `pendingStatusLine` (l.213-221) + `PendingLine` (l.210): the shape to mirror for a status line — `Text(text, style = LiftoffType.note().copy(color = Muted))`. `Idle` and `RePlanning` both fall to the PENDING card's old "Flight Plan not ready yet." line; the PLANNED card has no line at all today.
- `ActionButtons` (l.252-289): private, takes `state` + nine lambdas. `PlannedActions(onLaunch, onRequestScrub)` at l.282.
- `PlannedActions` (l.380-390): `PrimaryButton("Launch", icon = LiftoffIcons.rocket(), iconAtEnd = true)` → 12dp → `UnderlinedTextButton("Scrub", onRequestScrub)`.
- `PendingActions` (l.392-419): the existing reader of `regenerateAvailable` — Retry only when `generationStatus is Failed && regenerateAvailable`; "an unavailable Retry is absent", because `PrimaryButton` has no `enabled` param. Also the "no control → a line of text" precedent (Decision 5, l.405).
- File-private helpers live in this file; `LaunchpadContent`/`launchpadViewModel` are `internal`.

### `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt`

- `LaunchpadState.Planned` (l.33-48) already carries `generationStatus: SortieGenerationStatus` (l.43), `rhoSessionId` (l.45) and `regenerateAvailable: Boolean` (l.47). `Pending` carries the same pair (l.58, 64). No change needed here.
- Derivation: `deriveSortieCard` (l.175-…) computes `regenerateAvailable = coachConfigured && (current.type != SortieType.RUN || generateRunPlans)` (l.193) — one value shared by both cards. The PLANNED arm (l.195-215) reads the plan with `db.flightPlanDao().getPlan(current.id)`; `title = plan.plan.title`. If the plan row is gone the card becomes PENDING (l.216-226).
- The row the card answers from is `db.generationDao().observeNewestNonCancelledForSortie(current.id)` (l.144), combined with the settings flow (l.147) — so a settings change re-emits the card without re-preparing the sortie.

### `app/src/main/java/com/liftoff/app/domain/SortieGenerationStatus.kt`

- `derivePlannedSortieGenerationStatus` (l.86-102): a PLANNED card's status is **only ever `RePlanning` (row QUEUED or RUNNING) or `Idle`** (FAILED / SUCCEEDED / CANCELLED / null). So the new line needs one arm, not a table. `offersReFly = false` for PLANNED.

### `app/src/main/java/com/liftoff/app/data/MissionManager.kt`

- `regenerateFlightPlan(sortieId, onQueued)` (l.345-384): refuses (IllegalStateException, writes nothing) when unconfigured, Mission not ACTIVE, not the current week, not the current sortie, not PENDING/PLANNED, or a RUN sortie with `generateRunPlans` off. Inserts a fresh QUEUED FLIGHT_PLAN row and returns the ids of the **open** FLIGHT_PLAN rows read *before* the insert, for the caller to cancel. Never touches the plan row.
- `storePlan(sortieId, valid)` (l.396-435): writes the plan, moves PENDING → PLANNED, **leaves the generation row alone**.

### `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`

- `regenerate(): Job?` (l.232-249): reads `state.value`, takes `sortieId` from Pending **or Planned** (else returns null), launches on `scope`, calls `manager.regenerateFlightPlan(sortieId, queueGeneration)` then `cancelGeneration` for each superseded id; an `IllegalStateException` is caught inside the Job and answered with `runCatching { manager.onAppOpen() }`. No change needed.

### `app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt`

- `UnderlinedTextButton(text, onClick, modifier = Modifier)` (l.155-…): `modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp).clickable(role = Role.Button)`, label `text.uppercase()`, underline drawn behind the text. **It takes a `modifier`, so `Modifier.weight(1f)` from the enclosing `Row` works** — no theme change needed. `PrimaryButton` has no `enabled` param.

## State of this branch as of the tasks step

- HEAD is `9e0553d` ("run: verify"); the newest code commit is `838e3ad` (PR #48, the PENDING card). No commit on
  this branch touches `LaunchpadScreen.kt` yet, and `LaunchpadPlannedActionsTest.kt` does not exist: the launchpad
  test dir holds 11 classes (`CoachStatus`, `Confirm`, `DraftControls`, `DraftGuards`, `DraftStatus`,
  `PendingStatus`, `ReFly`, `Regenerate`, `Scrub`, `SortieStatus`, `States`).
- Confirmed by reading the file: `PlannedActions` (l.380-390) today takes only `(onLaunch, onRequestScrub)` and
  draws `PrimaryButton("Launch", icon = LiftoffIcons.rocket(), iconAtEnd = true)` → 12dp → one full-width
  `UnderlinedTextButton("Scrub")`. `ActionButtons`' Planned arm (l.282) is the only arm that does not pass
  `onRegenerate`. `PlannedContent` (l.180-187) draws no status line.

## Tests that already exist

- `LaunchpadPendingStatusTest.kt` (351 lines) — the harness to copy: `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, in-memory Room with `allowMainThreadQueries()`, `PreferenceDataStoreFactory` + real `SettingsStore` on `context.cacheDir/settings.preferences_pb`, `MissionManager(clock = Clock.fixed(2026-01-12T00:00:00Z, UTC), zone = { UTC })`, `CoroutineScope(Dispatchers.Unconfined + Job())`, recording `queuedIds`/`cancelledIds`, `pendingCard(configured, pattern, runPlans)` fixture, `awaitText` via `composeRule.waitUntil(5_000)`, `render { LiftoffTheme { LaunchpadContent(viewModel, onOpenInFlight = {}) } }`, and `tearDown` = `runBlocking { job.cancelAndJoin() }` **then** `db.close()` (the compose rule tears down after `@After`), and it also deletes the preferences file.
  - Its fixture `pendingCard(configured = true, pattern = "LRL", runPlans = false)` (l.121-144) is the recipe: set
    the pattern, `setDaemonHost("127.0.0.1")` + `setDaemonToken("t")`, `setGenerateRunPlans(true)` only when
    `runPlans`, then `missionId = manager.onAppOpen().id`, then `manager.continueWithoutOutline(missionId) {
    queuedRow = it }` — the trailing `onQueued` callback hands back the id of the QUEUED FLIGHT_PLAN row that call
    wrote, which is the row the new lift fixture must `markSucceeded` — then `sortieId =
    db.sortieDao().getForMission(missionId).first { it.index == 0 }.id`, then build `LaunchpadViewModel(db,
    manager, scope, queueGeneration = { queuedIds.add(it) }, cancelGeneration = { cancelledIds.add(it) })`.
    `openRow()` (l.147) reads that row back through `getOpenFlightPlanForSortie`; `awaitText` (l.161) is
    `waitUntil(5_000)` over `onAllNodesWithText(...).fetchSemanticsNodes()`; `render` (l.167) is the
    `LiftoffTheme { LaunchpadContent(viewModel = ..., onOpenInFlight = {}) }` call.
- `LaunchpadRegenerateTest.kt` (628 lines, `@Config(sdk = [34])`, no Compose) — reaches a PLANNED lift card: `activeWeek("LRL")` → `openPlanRowFor` → `generationDao.update(older.copy(createdAt = clock.millis() - 60_000))` (l.365) → `manager.storePlan(..., CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))` → `StoreOutcome.Stored` (l.367-371). Its `cancelGeneration` does `generationDao.markCancelled(it, clock.millis())` (l.139-143). The run-off half is `regenerateOnARunSortieFollowsTheGenerateRunPlansToggle` (l.495-519): pattern `"RLL"`, `generateRunPlans` left off, sortie already PLANNED with `FlightPlanSource.SIMPLE_RUN`.
- `LaunchpadSortieStatusTest.kt` — `aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt` (l.304-…): after `storePlan` it calls `generationDao.markSucceeded(week.queuedRow, at(1))` to get `Idle`, then queues a row to get `RePlanning`. **This is the step the new Compose test needs too**: `continueWithoutOutline` leaves a QUEUED FLIGHT_PLAN row on the lift sortie and `storePlan` does not close it, so a freshly-stored PLANNED card is `RePlanning`, not `Idle`, until that row is closed.
- Compose-rendering Launchpad tests: `LaunchpadDraftControlsTest`, `LaunchpadDraftStatusTest`, `LaunchpadPendingStatusTest`, `LaunchpadReFlyTest` (already `w411dp-h2000dp`, asserts `"SCRUB"` on a **PENDING** card). `LaunchpadScrubTest`, `LaunchpadConfirmTest`, `LaunchpadCoachStatusTest`, `LaunchpadStatesTest`, `LaunchpadRegenerateTest`, `LaunchpadDraftGuardsTest` are view-model/DAO only. `LiftoffShellTest` renders the shell with no host/token, so it never draws a PLANNED card.
- `CoachFixtures.LIFT_PLAN = "lift-plan-valid.txt"` → `app/src/test/resources/fixtures/coach/lift-plan-valid.txt`, whose plan title is **`Push + core`** (also the card's TitleBlock title).

## Facts the work needs

- `GenerationDao`: `markSucceeded(id, finishedAt)` and `markCancelled(id, finishedAt)` return `Unit`; `markFailed(id, error, finishedAt)` returns `Int`. `getOpenFlightPlanForSortie` selects only `status IN ('QUEUED','RUNNING')`, so a SUCCEEDED row is never handed back to cancel.
- A PLANNED lift card is `RePlanning` straight after `storePlan`, because `continueWithoutOutline` queued a QUEUED row and `storePlan` leaves it open. Close it with `markSucceeded` to get `Idle`.
- A PLANNED run card with `generateRunPlans` off has **no** generation row at all — `advance` queued none for it — so its status is `Idle` and it draws no re-planning line. Its plan's source is `FlightPlanSource.SIMPLE_RUN`.
- `PrimaryButton` uppercases its label too (`Buttons.kt` l.~74), so the assertions are `LAUNCH`, `REGENERATE`, `SCRUB`.
- The line's text is `Coach is re-planning…`, and the ellipsis is the single character U+2026, exactly as in the
  consts `PLANNING_LINE` (l.177) and `PENDING_PLANNING_LINE` (l.223). Write the same character in the const and in
  every assertion, or the assertion will not match the text the card draws.
- The run-off fixture needs no `storePlan`: `LaunchpadRegenerateTest.activeWeek(pattern = "RLL")` (l.122-128 =
  settings, `onAppOpen`, `continueWithoutOutline`) leaves that sortie `SortieState.PLANNED` with a
  `FlightPlanSource.SIMPLE_RUN` plan already, asserted at l.498-502 with `generateRunPlans` still off.
- Contrast worth knowing before writing the tap case: `regenerateOnAPlannedSortieKeepsItsPlanAndStaysPlanned`
  (l.362-389) does **not** close the row `continueWithoutOutline` queued, so there `cancelledIds == listOf(older.id)`
  and the row ends CANCELLED. The new Compose fixture closes it with `markSucceeded` first, so nothing is open and
  `cancelledIds` is empty. `getOpenFlightPlanForSortie` selects only QUEUED/RUNNING, which is why closing it matters.

## The gate this run is checked by

- `.github/workflows/ci.yml` job `gate` runs exactly `./gradlew :app:assembleDebug :app:testDebugUnitTest` (Java 17, 20-minute job timeout). `gradlew.sh` at the repo root pins `JAVA_HOME`/`ANDROID_HOME`/the Gradle binary under `~/android-toolchain`; run it as `bash gradlew.sh <tasks>` from the repo root.
- `verify.sh` runs that gate once per attempt (up to 3 attempts, only retried on the hang / build-error / lone-flaky-name signature), with `--no-build-cache` and the old `app/build/test-results/testDebugUnitTest` moved aside first, then reads every named test out of that one run's `TEST-*.xml`.
- The one name recorded as failing at random on this machine is `doneBodyweightSetStepperShowsBW` (`app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt` l.601) — a closed Room pool that passes on a re-run of the same tree.

## The three test names verify.sh requires (tasks.md is written from these)

All three are new to this branch and live in the class the brief names, `LaunchpadPlannedActionsTest`:
1. `plannedLiftCardShowsRegenerateBesideScrubAndNoRePlanningLine` — REGENERATE and SCRUB both displayed and enabled, bounds overlapping vertically, neither spanning the row alone, REGENERATE left of SCRUB, and no 'Coach is re-planning…'. Render only.
2. `tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine` — exactly one id handed to `queueGeneration` and that id is a real QUEUED FLIGHT_PLAN row on this sortie; then 'Coach is re-planning…' with `LAUNCH`, `Push + core` and REGENERATE still on screen and the plan still in the table; `cancelledIds` empty. Wait for REGENERATE on screen before the tap.
3. `plannedRunCardWithRunGenerationOffShowsScrubAndNoRegenerate` — SCRUB displayed and enabled, no REGENERATE, no re-planning line. Render only.

verify.sh also requires these classes to have run with everything in them passing: `LaunchpadPendingStatusTest`, `LaunchpadReFlyTest`, `LaunchpadScrubTest`, `LaunchpadRegenerateTest`, `LaunchpadSortieStatusTest`, `LaunchpadStatesTest`, `LaunchpadCoachStatusTest`, `LaunchpadConfirmTest`, `LaunchpadDraftStatusTest`, `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LiftoffShellTest`. It checks no method name in any other class, and does not check ARCHITECTURE.md, the class the new tests sit in, button spacing, equal weights, or text colour.
