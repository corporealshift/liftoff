# Map — pending-card-coach-status-lines-and-j5et

HEAD at plan time: `43aa129` ("run: plan outline"), on top of `f9d1276` (PR #47, sortie generation status, no UI).

## Docs that set rules

- `DESIGN.md` §9 — the Launchpad card states. PENDING: generation status ("Coach is planning…", "Mission Control offline — retrying", or a failure with its reason); Retry, Re-fly and Scrub available.
- `design/README.md` — PENDING puts the status line where the Flight Plan list would be, and Retry / Re-fly / Scrub where Launch is.
- `CLAUDE.md` §2 — the project's vocabulary. Commit messages `area: lowercase summary`, stage named files only.
- ARCHITECTURE.md is not touched in this run (brief).

## app/src/main/java/com/liftoff/app/domain/SortieGenerationStatus.kt

The whole answer already exists here; the UI only draws it.

- `sealed interface SortieGenerationStatus`: `Planning`, `Offline`, `Failed(reason: String, validatorErrors: List<String> = emptyList())`, `NotConfigured`, `RePlanning`, `Idle`.
- `derivePendingSortieGenerationStatus(newestGenerationStatus, newestGenerationError, newestGenerationIsCoachOffline, newestGenerationSessionId, coachConfigured, hasReFlySourcePlan): SortieGenerationAnswer` — a PENDING card's status. It can return **only** Planning / Offline / Failed / NotConfigured: `RePlanning` and `Idle` are unreachable on a PENDING card, and `noRowAnswer` gives `Failed(NO_GENERATION_QUEUED)` when configured, `NotConfigured` when not.
- `derivePlannedSortieGenerationStatus` is the only source of `RePlanning` (QUEUED/RUNNING row on a PLANNED sortie) and `Idle`.
- `failedFrom(error)` splits the worker's one-line error: prefix `invalid output: ` → `reason = "invalid output"`, `validatorErrors =` the rest split at `"; "`. A blank/absent error → `Failed(FAILED_WITHOUT_REASON)`. So the UI must print `reason` then each `validatorErrors` entry — never re-split the raw string.
- `offersReFly(status, hasReFlySourcePlan)` — §5.4: true for Offline / Failed / NotConfigured when a source plan exists, false while Planning.
- `SortieGenerationAnswer(status, rhoSessionId, offersReFly)`.

## app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt

- `LaunchpadState.Pending(eyebrow, title, chips, sortieId, sortieIndex, generationStatus, rhoSessionId, reFlyAvailable)` — the card this run draws. `Planned` carries `generationStatus` too.
- `LaunchpadStates.derive()` joins `planChanges`, `generationDao().observeNewestNonCancelledForSortie(current.id)` and `manager.coachConfigured` for a PENDING/PLANNED current sortie, and calls `deriveSortieCard`.
- `deriveSortieCard` is the only place that builds a real `Pending` (two arms: a PLANNED sortie whose plan row is gone, with `hasReFlySourcePlan = false`; and the plain PENDING arm that reads refly candidates). `deriveActive`'s `else` arm is the Idle fallback (`generationStatus = Idle`, `reFlyAvailable = false`) for a state `currentSortie` never returns.
- Cherry-pick `e2199c5` adds `regenerateAvailable: Boolean` to `Planned` (after `rhoSessionId`) and `Pending` (after `reFlyAvailable`), joins `manager.generateRunPlans` into the `combine`, and sets `coachConfigured && (current.type != SortieType.RUN || generateRunPlans)` in `deriveSortieCard`, `false` in the `deriveActive` fallback. All four construction sites are in this one file, and no test builds either state directly.

## app/src/main/java/com/liftoff/app/data/MissionManager.kt

- `coachConfigured: Flow<Boolean>` (line ~66); `e2199c5` adds `generateRunPlans: Flow<Boolean>` beside it.
- `regenerateFlightPlan(sortieId, onQueued: (Long) -> Unit = {}): List<Long>` (line 337) — refuses when unconfigured, when the mission isn't ACTIVE or isn't the current week, when the sortie isn't current or isn't PENDING/PLANNED, and when `sortie.type == RUN && !settings.generateRunPlans`. Otherwise inserts one new QUEUED FLIGHT_PLAN row at `createdAt = clock.millis()` and returns the sortie's other open ids for the caller to cancel. With a no-op cancel the failed row stays FAILED and the new QUEUED row wins `id DESC` → the card reads Planning; in the app the caller does cancel the old ids, so they drop out of the newest-row query instead.
- `continueWithoutOutline(missionId, onQueued: (Long) -> Unit = {}): List<Long>` (line 185) — starts the week with no outline; `advance` queues the FLIGHT_PLAN generation for the sortie it makes current.

## app/src/main/java/com/liftoff/app/data/GenerationDao.kt / Generation.kt

- `observeNewestNonCancelledForSortie(sortieId): Flow<Generation?>` — `ORDER BY createdAt DESC, id DESC LIMIT 1`, CANCELLED excluded.
- `getOpenFlightPlanForSortie(sortieId): List<Generation>` (QUEUED/RUNNING, newest first), `update(Generation)`, `markFailed(id, error, finishedAt)`.
- `Generation.COACH_OFFLINE = "Mission Control offline"`; `Generation.isCoachOffline` is `error == COACH_OFFLINE && status in (QUEUED, RUNNING)`.

## app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt

The only main-source file this run edits after the cherry-pick.

- `LaunchpadContent(viewModel, onOpenInFlight)` — the body a test renders apart from `AppContainer` (which would open an on-disk liftoff.db). Content column is `verticalScroll`ed; the actions live in the Scaffold `bottomBar`, so they are not scrolled away.
- `PendingContent` (line 189) — TitleBlock, `PatternTrack`, `Spacer(20.dp)`, then the fixed `Text("Flight Plan not ready yet.", LiftoffType.textButton() … Muted)`. This is the slot the status replaces.
- `PendingActions(pending, onRequestScrub, onRefly)` (line 366) — Re-fly `UnderlinedTextButton` when `reFlyAvailable`, else a Muted "No earlier plan to re-fly." line unless the status is Planning, then `UnderlinedTextButton("Scrub")`. `ActionButtons` dispatches to it from the bottom bar.
- `DraftContent` + `draftStatusLine(status, viewModel.notConfiguredMessage): DraftLine?` (line 161) — the shape to copy: a private helper returning text + colour, drawn as `Text(line.text, LiftoffType.note().copy(color = line.color))`. Its constants `PLANNING_LINE` / `OFFLINE_LINE` (line 176) are the draft's wording; the offline wording is the same sentence the brief asks for here.
- Theme pieces already imported via `com.liftoff.app.ui.theme.*`: `Cream`, `Muted`, `Red`, `Ink`, `Rule`, `LiftoffType.note()/.textButton()`, `PrimaryButton` and `UnderlinedTextButton` (`ui/theme/Buttons.kt`). Both call `text.uppercase()` on the label, so tests look for `RETRY`. `PrimaryButton` is `fillMaxWidth()`, `height = 72.dp` by default, `labelStyle = LiftoffType.launchLabel()`, and has **no `enabled` parameter** — an unavailable Retry can only be absent, not dimmed.

## app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt

- `internal const val NOT_CONFIGURED_MESSAGE` (line 18) — "Mission Control isn't set up. Add the host and token in Mission Control."
- `fun regenerate(): Job?` (line 232) — already the Pending/Planned Retry: calls `manager.regenerateFlightPlan(sortieId, queueGeneration)` then cancels each superseded id. No view-model change is needed.
- `notConfiguredMessage` is the draft's Retry report; the Pending card's NotConfigured line comes from the derivation, not from here.

## Tests

- Compose renderers among the Launchpad classes: `LaunchpadDraftControlsTest`, `LaunchpadDraftStatusTest`, `LaunchpadReFlyTest` — all three already carry `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`. `LaunchpadScrubTest`, `LaunchpadRegenerateTest`, `LaunchpadSortieStatusTest`, `LaunchpadStatesTest`, `LaunchpadCoachStatusTest`, `LaunchpadConfirmTest`, `LaunchpadDraftGuardsTest` are `@Config(sdk = [34])` and drive the view model or `LaunchpadStates` directly, no `setContent`.
- `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt` — `createAndroidComposeRule<MainActivity>`, no `@Config`/qualifiers. Its Launchpad has no host/token, so it shows the Draft (wait by title "NEW MISSION"), not a Pending card.
- Harness to copy — `LaunchpadDraftStatusTest.kt` lines 52–178: in-memory Room with `allowMainThreadQueries()`, a real `SettingsStore` over a `PreferenceDataStoreFactory` file in `cacheDir`, `MissionManager` on `Clock.fixed(2026-01-12T00:00:00Z, UTC)` with `zone = { ZoneOffset.UTC }`, `CoroutineScope(Dispatchers.Unconfined + Job())`, `LaunchpadContent` inside `LiftoffTheme`, `queueGeneration = { }` / `cancelGeneration = { }`, `awaitText` via `composeRule.waitUntil(timeoutMillis = 5_000)`, and a tearDown that `job.cancelAndJoin()`s **before** `db.close()` (the screen's Room flow is still collecting).
- Seeding shape for a PENDING card with an open FLIGHT_PLAN row — `LaunchpadSortieStatusTest.activeWeek()` (line ~179): host + token + `setDefaultPattern("LRL")`, `manager.onAppOpen()`, `manager.continueWithoutOutline(mission.id) { queued = it }`. Pattern starts with L so the current sortie (index 0) is a LIFT, which keeps `regenerateAvailable` true without touching the run-plans toggle. Sortie index 2 is the week's other LIFT.
- No test anywhere asserts the text "Flight Plan not ready yet.", so replacing it is safe.
- With `cancelGeneration = { }` the superseded FAILED row is still FAILED in the database after a Retry. The card still moves to Planning because the row `regenerateFlightPlan` inserts carries `createdAt = clock.millis()` — the same fixed instant as every seeded row — and `id DESC` breaks the tie in its favour. A seeded row must therefore not be given a `createdAt` later than the clock's.

## CI and the gate

- `.github/workflows/ci.yml` — one job `gate`, ubuntu-latest, Java 17 (temurin), 20-minute job timeout, and its only
  build step is `./gradlew :app:assembleDebug :app:testDebugUnitTest`. That is the pair this run's verify.sh runs.
- `gradlew.sh` (repo root) pins JDK, SDK and Gradle under `~/android-toolchain`; none of them are on PATH, so a bare
  `gradle` or `java` finds nothing. Run it as `bash gradlew.sh <tasks>` from the repo root.
- Test results are the XML under `app/build/test-results/testDebugUnitTest/TEST-*.xml`; `BUILD SUCCESSFUL` carries no
  counts. Gradle exits 0 when a `--tests` name matches nothing, so a named test has to be looked for by name in those
  reports.

## MissionManager facts the new tests lean on

- `advance(missionId, settings)` (line 435) is what prepares the current PENDING sortie inside
  `continueWithoutOutline`. `prepareCurrent(current, settings.generateRunPlans)` gives `SimplePlan` for a RUN sortie
  with the toggle off — it writes a plan and marks the sortie PLANNED, so it queues nothing and its card is not
  PENDING at all — and `AwaitGeneration` otherwise. `AwaitGeneration` returns null when the host or token is blank, so
  the not-configured PENDING card has no generation row; otherwise it returns the newest open row's id if one exists,
  else inserts a QUEUED FLIGHT_PLAN at `createdAt = clock.millis()`.
- `regenerateFlightPlan` refuses, as an `IllegalStateException` that writes nothing, when unconfigured, when the
  mission is not ACTIVE or not the current week, when the sortie is not current or not PENDING/PLANNED, and when
  `sortie.type == SortieType.RUN && !settings.generateRunPlans`. That last refusal is the only thing
  `regenerateAvailable` exists to keep the card from offering.
- `SettingsStore.setGenerateRunPlans(Boolean)` exists and is what Mission Control's checkbox calls;
  `InFlightLandScrubTest` seeds it directly. `LaunchpadStates.derive`'s PENDING/PLANNED arm is a 3-flow `combine`
  today (`planChanges`, the newest row, `manager.coachConfigured`); `e2199c5` makes it 4 by adding
  `manager.generateRunPlans`, so a toggle change re-emits the card without the screen being rebuilt.
- `GenerationDao.markFailed(id: Long, error: String, finishedAt: Long): Int`.
- `failedFrom` (SortieGenerationStatus.kt:108) needs the `invalid output: ` lead to split; without it the whole error
  string is the reason and `validatorErrors` is empty.

## The toggle case verify.sh names (the sixth test)

Reachable, and this is the only observable proof that Step 1's `regenerateAvailable` reaches the card:
`setGenerateRunPlans(true)` **before** `continueWithoutOutline` on a pattern whose first sortie is a RUN (e.g. `RLL`),
so `prepareCurrent` gives `AwaitGeneration` instead of `SimplePlan` — the sortie stays PENDING with one QUEUED
FLIGHT_PLAN row, which `markFailed` turns into a failure with `regenerateAvailable` true and RETRY drawn. Then
`setGenerateRunPlans(false)` mid-render: `e2199c5`'s `generateRunPlans` flow (`MissionManager.kt`, beside
`coachConfigured`, `settingsStore.settings.map { it.generateRunPlans }.distinctUntilChanged()`) re-emits the 4-flow
`combine`, `deriveSortieCard` recomputes `regenerateAvailable = coachConfigured && (type != RUN || generateRunPlans)`
to false, and RETRY disappears while the reason stays. Nothing re-runs `prepareCurrent`, so the card stays PENDING.
Assert RETRY present **before** the toggle is flipped, or the absence proves nothing.

## What the existing PENDING-card tests will see

- `LaunchpadReFlyTest` renders a PENDING card in three cases and none of them has a FAILED row:
  `landedSourceThenPending(coachSetUp = false)` leaves the card NotConfigured with a source plan (RE-FLY drawn),
  `pendingWithNothingLanded()` is NotConfigured with no source (the "No earlier plan to re-fly." line), and
  `landedSourceThenPending(coachSetUp = true)` has the open QUEUED row `advance` queued, so Planning. So no new
  RETRY appears in any of them, and its bounds assertion is only "Re-fly above Scrub", which a Retry drawn above
  Re-fly does not break.
- `LaunchpadSortieStatusTest` and `LaunchpadStatesTest` assert on the derived `LaunchpadState`, not on the screen, so
  a field added to `Pending` cannot fail them — no test builds either state directly.
- `LaunchpadDraftStatusTest` looks for the draft's `PLANNING_LINE` = "Coach is planning the week…", which is a
  different sentence from the pending card's "Coach is planning…". Keep both constants.
- No existing Compose test renders a PENDING card with a FAILED row (`LaunchpadCoachStatusTest`,
  `LaunchpadSortieStatusTest`, `LaunchpadConfirmTest` and `LaunchpadRegenerateTest` all seed FAILED rows but drive the
  view model or `LaunchpadStates` directly, never `setContent`), so no existing test can see a new RETRY.
  `LaunchpadDraftStatusTest` and `LaunchpadDraftControlsTest` count pattern chips with
  `onAllNodesWithText("R").assertCountEquals(3)` / `("L")` on exact text, which a `RETRY` label and prose status lines
  cannot disturb.

## The source branch, in full

`rho/draw-generation-status-regenerate-and-n766` is HEAD + 6 commits: its brief/plan/tasks/verify, then `e2199c5`
(the derivation), then `e53b0d6` ("run: task 1 done"). Its UI work was never written — `git diff --stat
HEAD...that-branch` is those docs plus `MissionManager.kt` (+8) and `LaunchpadState.kt` (+16 −2) and nothing else. So
its verify.sh is the closest precedent for this run's, and its PENDING-card test names are the ones this run's
script reuses in shortened form.

## Commit to bring in

`e2199c5` on `rho/draw-generation-status-regenerate-and-n766` (parent `2070af2`, whose merge-base with HEAD is `f9d1276`) — reachable, touches only `MissionManager.kt` (+8) and `LaunchpadState.kt` (+16 −2), no `.rho/runs/` files. That branch's newer commit `e53b0d6` ("run: task 1 done") is its own run's bookkeeping and is not brought in.

## verify.sh — the definition of done (committed at the verify step)

`.rho/runs/pending-card-coach-status-lines-and-j5et/verify.sh` runs the CI pair once (moving
`app/build/test-results/testDebugUnitTest` aside with `mv` first, `--no-build-cache`, re-run up to twice on the hang or
on a build-task failure with no failing test — never `gradlew --stop`), indexes every report's `<testcase>` names in one
awk pass into `app/build/verify-work/methods` and `/classes`, then requires these six method names to have run and
passed (a name matched across any report, so the class they sit in is the implementer's call), then twelve whole-class
checks: the brief's six Launchpad classes + `LiftoffShellTest`, the other four Launchpad classes, and
`MissionManagerTest`. The other classes the brought-in commit could touch (`GenerationDaoTest`,
`SortieGenerationStatusTest`, `GenerationWorkerTest`, …) are deliberately not named: the gate already runs the whole
suite, and a class name only earns its place by catching a rename or a deletion.

Not checked, and said so in the script's header: ARCHITECTURE.md (brief: don't touch it), which theme parts draw each
line and the Red/Muted split (Compose semantics carry no component identity or colour), the fallback line for `Idle` /
`RePlanning` (unreachable on a PENDING card), and the plan's Decisions.

**Run against untouched code** (`app/build/verify-run2.log`, 2026-10-29): gate attempt 1 passed in 1 m 33 s,
`> Task :app:testDebugUnitTest` with no cache marker, 724 tests / 0 failures / 0 errors / 0 skipped; the six new names
all read "did not run"; all twelve class checks passed; `Result: 13 passed, 6 failed`, exit 1. That is the script doing
its job. Scratch copies of the script and its logs live under `app/build/` and are never staged.

- `pendingCardShowsThePlanningLineAndNoRetry`
- `pendingCardShowsTheOfflineLineAndNoRetry`
- `pendingCardShowsThatMissionControlIsNotSetUpAndNoRetry`
- `pendingCardShowsAFailedGenerationsReasonAndEachValidatorErrorOnItsOwnLineAndRetry`
- `tappingRetryOnAFailedPendingCardQueuesAFreshGenerationAndShowsThePlanningLine`
- `retryIsAbsentOnAFailedRunSortieWhileGenerateRunPlansIsOffAndDrawnWhileItIsOn` — the sixth is the brief's
  `regenerateAvailable` half, which the plan expected to prove with a mutation check instead. It needs a RUN current
  sortie (`setGenerateRunPlans(true)` before `continueWithoutOutline` on a pattern starting with R) whose row is then
  marked FAILED, and the toggle flipped off mid-render.
