# Map — View in rho on the Pending card

Branch HEAD at plan time: `eebf26e` ("run: plan outline"), on top of `a3fa233` (#49, planned-card
Regenerate) and `838e3ad` (#48, pending-card status lines + Retry). Working tree clean apart from
this run's plan/map.

## Files the change touches

### `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (504 lines) — the only file to change
- `LaunchpadContent(viewModel, onOpenInFlight)` — l.64, `internal`, "the screen body, apart from
  AppContainer so a test can render it on its own view model". Composes `Scaffold` whose `content`
  Column picks one card composable per `LaunchpadState` arm (l.80-87) and whose `bottomBar` is
  `ActionButtons(...)` (l.90-104, ten lambdas wired straight to the view model). Also holds the Scrub
  confirmation `ScrubDialog` (l.107-127). So the card body and the bottom bar are two separate
  composables under one parent — the reveal state has to be held in `LaunchpadContent` and passed to
  both, which is exactly where the brief's "line under the status lines, button above Scrub" lives.
- `PendingContent(state, viewModel)` — l.199-217: TitleBlock, PatternTrack, then
  `pendingStatusLine(state.generationStatus)` drawn as `LiftoffType.note()`, then one line per
  `line.validatorErrors`. The id line goes after these.
- `pendingStatusLine(SortieGenerationStatus)` → `private class PendingLine(text, color, validatorErrors)`
  — l.219-233; `PENDING_PLANNING_LINE = "Coach is planning…"`, `NOT_READY_LINE`.
- `ActionButtons(state, …)` — l.262-298: a Column that dispatches per state; the Pending arm is
  `PendingActions(state, onRequestScrub, onRefly, onRegenerate)` (l.292). A new argument for the
  Pending card has to be threaded through here.
- `PendingActions(pending, onRequestScrub, onRefly, onRegenerate)` — l.418-444: Retry
  (`PrimaryButton`, drawn only when `generationStatus is Failed && regenerateAvailable`), then Re-fly
  or the text line "No earlier plan to re-fly.", then `UnderlinedTextButton("Scrub")` last. "Above
  Scrub" = between the Re-fly block and l.443.
- `PlannedActions` — l.390-415 (Launch, then Regenerate+Scrub in a Row when
  `planned.regenerateAvailable`, else Scrub alone). `RE_PLANNING_LINE` at l.180. Not to be touched.
- All these helpers are file-private; nothing new needs widening because everything stays in this file.

### `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` (318 lines) — read only
- `LaunchpadState.Pending` (l.51-65) already carries `sortieId`, `sortieIndex`,
  `generationStatus`, `rhoSessionId: String?`, `reFlyAvailable`, `regenerateAvailable`. `Planned`
  carries the same `rhoSessionId` (l.45). No change needed here.
- `deriveSortieCard` (l.178-262) fills `rhoSessionId` from the joined
  `generationDao().observeNewestNonCancelledForSortie(current.id)` row; the PLANNED-without-a-plan
  fallback also returns a `Pending` with an id (l.219-237); `deriveActive`'s no-current-sortie
  fallback sets `rhoSessionId = null` (l.311).
- `LaunchpadStates.observe()` is the live Flow; the ACTIVE Pending/Planned arm is the only one that
  joins the generation row + settings, and it is `distinctUntilChanged()`.

### `app/src/main/java/com/liftoff/app/domain/SortieGenerationStatus.kt` — read only
- `rhoSessionId` is handed out unconditionally from `newestGenerationSessionId` (l.47, l.99): any
  status — Planning, Offline, Failed, Idle — carries the row's id. So a QUEUED row with an id gives
  a PENDING card with an id, which is what the test seeds.

### `app/src/main/java/com/liftoff/app/data/Generation.kt` / `GenerationDao.kt` — read only
- `Generation.nabuSessionId` is `@ColumnInfo(name = "nabuSessionId")`, default null (Generation.kt:14).
  Column and property names stay as they are; only the UI label says rho.
- DAO: `update(generation)` l.27, `saveSessionId(id, nabuSessionId)` l.54-55 (the worker's own
  write), `observeNewestNonCancelledForSortie` l.84, `getOpenFlightPlanForSortie(sortieId):
  List<Generation>` l.114, plus `markFailed`, `setCoachOfflineMark`, `get(id)`.

### `app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt` / `Type.kt` — read only
- `UnderlinedTextButton(text, onClick, modifier = Modifier)` l.155: `Box` with
  `clickable(role = Role.Button)`, `Text(text.uppercase(), LiftoffType.textButton() …)`. So the test
  looks for `VIEW IN RHO`. No `enabled` parameter.
- `LiftoffType.note()` (Type.kt:97) is the quiet 13sp style the card's other lines use; the card's
  quiet lines are `Muted`, its fault lines `Red`.

## Tests that already exist (launchpad package)

- `LaunchpadPendingStatusTest.kt` (351 lines) — the closest harness to copy. `@Config(sdk = [34],
  qualifiers = "w411dp-h2000dp")`; in-memory Room + `PreferenceDataStoreFactory` SettingsStore +
  `MissionManager(clock = Clock.fixed(2026-01-12T00:00:00Z, UTC), zone = { UTC })`; `pendingCard(
  configured = true, pattern = "LRL", runPlans = false)` (l.121-144) sets pattern/host/token,
  `manager.onAppOpen()`, then `manager.continueWithoutOutline(missionId) { queuedRow = it }`, then
  reads sortieId for index 0, and returns a `LaunchpadViewModel(db, manager, scope, queueGeneration =
  {…}, cancelGeneration = {…})` — recording lambdas, no WorkManager. `openRow()` (l.147) is
  `generationDao().getOpenFlightPlanForSortie(sortieId).single()`; `failOpenRow` uses `markFailed`.
  `awaitText` is a 5 s `waitUntil`; `render` is `LiftoffTheme { LaunchpadContent(viewModel,
  onOpenInFlight = {}) }`. `tearDown` cancels the scope Job *before* `db.close()` (l.103-110) — the
  screen's live `observe()` must stop first.
- `LaunchpadDraftStatusTest.kt` (433 lines) — the harness the brief names; the one above says so in
  its own docstring.
- `LaunchpadSortieStatusTest.kt` (433 lines) — precedent for putting an id on the row:
  `generationDao().saveSessionId(week.queuedRow, RHO_SESSION_ID)` then await the state carrying it
  (l.258-269). Proves the derivation, not the UI.
- `LaunchpadPlannedActionsTest.kt` (301 lines), `LaunchpadRegenerateTest.kt` (628),
  `LaunchpadReFlyTest.kt` (334), `LaunchpadScrubTest.kt` (234), `LaunchpadConfirmTest.kt` (350),
  `LaunchpadStatesTest.kt` (374), `LaunchpadCoachStatusTest.kt` (341), `LaunchpadDraftControlsTest.kt`
  (211), `LaunchpadDraftGuardsTest.kt` (160), and `ui/LiftoffShellTest.kt` — all must stay green.
  LiftoffShellTest's Launchpad has no Mission Control host/token, so it queues no generation row and
  never sees the new button.
- Whole-suite baseline on this branch: 733 tests in 88 reports (~1m22s).

## Docs that set rules
- `DESIGN.md` §9, l.561-568: the PENDING card lists Retry, Re-fly, Scrub "plus 'View in nabu', which
  shows the nabu session id so it can be opened in nabu's app" — the brief renames it rho; the doc
  stays as it is (no doc edits this run).
- `CLAUDE.md`: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only; §2 vocabulary (never "session" for a sortie);
  short comments.
- `ARCHITECTURE.md` l.37 and the M3 row (l.82) describe the Launchpad's current state — not to be
  changed in this run.
