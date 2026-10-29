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
- `regenerateFlightPlan(sortieId, onQueued: (Long) -> Unit = {}): List<Long>` (line 337) — refuses when unconfigured, when the mission isn't ACTIVE or isn't the current week, when the sortie isn't current or isn't PENDING/PLANNED, and when `sortie.type == RUN && !settings.generateRunPlans`. Otherwise inserts one new QUEUED FLIGHT_PLAN row at `createdAt = clock.millis()` and returns the sortie's other open ids for the caller to cancel. So after a Retry the failed row is CANCELLED and a QUEUED row is newest → the card reads Planning.
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

## Commit to bring in

`e2199c5` on `rho/draw-generation-status-regenerate-and-n766` (parent `2070af2`, whose merge-base with HEAD is `f9d1276`) — reachable, touches only `MissionManager.kt` (+8) and `LaunchpadState.kt` (+16 −2), no `.rho/runs/` files. That branch's newer commit `e53b0d6` ("run: task 1 done") is its own run's bookkeeping and is not brought in.
