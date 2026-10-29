# Map — View in rho on the Pending card

Branch HEAD at plan time: `eebf26e` ("run: plan outline"), on top of `a3fa233` (#49, planned-card
Regenerate) and `838e3ad` (#48, pending-card status lines + Retry). At the tasks step HEAD is `7a988d0`
("run: verify"), tree clean.

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

## What the seeded PENDING card actually draws (confirmed by reading the derivation and the card)

- `LaunchpadState.kt` l.193: `regenerateAvailable = coachConfigured && (current.type != SortieType.RUN ||
  generateRunPlans)`; `reFlyAvailable = …offersReFly` (l.235, l.259) — false for a sortie that has no earlier plan.
  So the card the brief's seeding builds (host + token, pattern `LRL`, `continueWithoutOutline`) is a LIFT with
  `generationStatus == Planning`: status line `Coach is planning…` (`PENDING_PLANNING_LINE`, LaunchpadScreen.kt:232),
  no Retry (not a failure), and in `PendingActions` neither the Re-fly button nor the "No earlier plan to re-fly."
  line (l.431-441 — the `else if` skips Planning). **The bar holds only SCRUB today**, so the new button is the
  second control in that Column and the window (`h2000dp`) has room.
- `NOT_CONFIGURED_MESSAGE` (LaunchpadViewModel.kt:18, `internal`, same package so a test can name it) is
  "Mission Control isn't set up. Add the host and token in Mission Control." — the status line the unconfigured
  PENDING card draws (`pendingStatusLine`, LaunchpadScreen.kt:226).
- `LaunchpadPendingStatusTest.pendingCard(configured, pattern, runPlans)` (l.121-144) is exactly the brief's seeding;
  `openRow()` (l.147) is `getOpenFlightPlanForSortie(sortieId).single()` and asserts it is the id
  `continueWithoutOutline` reported through its `onQueued` callback. `pendingCardShowsThatMissionControlIsNotSetUpAndNoRetry`
  (l.223) is the precedent that unconfigured still yields a PENDING card with `countAll() == 0`.
- `LaunchpadSortieStatusTest` l.258-269 is the precedent for `generationDao().saveSessionId(row, ID)`; the brief's
  `update(row.copy(nabuSessionId = …))` is the other write and is what `LaunchpadPendingStatusTest` l.202 already uses
  for `error`.

## verify.sh — the three names the work steps must produce

Committed at this step. Gate first (`:app:assembleDebug :app:testDebugUnitTest`, what `.github/workflows/ci.yml` runs,
`--no-build-cache`, results moved aside, up to 3 attempts on the hang signature), then three named tests by method
name, then whole-class checks on the twelve Launchpad classes + `LiftoffShellTest`. The names:

1. `pendingCardWithASessionIdShowsViewInRhoAndTappingItRevealsTheSessionLine` — VIEW IN RHO shown and enabled; no
   node containing `sess-7f3a-liftoff` before the tap; after the tap the whole line `rho session sess-7f3a-liftoff`
   is displayed and `Coach is planning…` + SCRUB are still displayed; nothing was handed to the recording enqueuer and
   the row is still QUEUED with that id.
2. `pendingCardWithAGenerationRowThatHasNoSessionIdShowsNoViewInRho` — wait for `Coach is planning…` (or SCRUB) first,
   then no VIEW IN RHO and no text containing `rho session`.
3. `pendingCardWithNoGenerationRowShowsNoViewInRho` — no host/token, assert no generation row, wait for
   `NOT_CONFIGURED_MESSAGE`, then no VIEW IN RHO and no `rho session` text.

Not checked (written in the script's header): theme-part identity and colour, that the line is inline rather than in a
Dialog (a dialog's text is a displayed node too), the button's position relative to the id line across the two Scaffold
slots, the "stays until the sortie changes" rule (plan says the `sortieId` key carries it, no test), the one-way
reveal, and that `nabuSessionId` keeps its column name. Case 1 does assert the two positions the brief states, each
inside its own slot: VIEW IN RHO above SCRUB in the bar's Column, the id line under the status line in the content
Column.

## verify.sh ran on untouched code (this step): it fails on purpose

Two full detached runs of the script (`app/build/verify-t49t.log`, `app/build/verify-t49t-2.log`): gate attempt 1
passed both times (BUILD SUCCESSFUL 2m53s / 1m33s, `:app:testDebugUnitTest` executed, 733 tests, 0 failures, 0 errors,
0 skipped — the baseline holds), all 13 must-not-break classes green, and the three names above reported "did not run"
→ `Result: 14 passed, 3 failed`, `EXIT=1`. So the script's only failing checks on untouched code are the three new
tests, which is what makes it the definition of done.

## The task split (written at the tasks step)

Three tasks in `tasks.md`, each ending in a commit, in order: draw it → test it → run the gate. Only
`LaunchpadScreen.kt` changes in production; no task touches the derivation, the view model, the DAO or a
doc.

1. **Draw it.** The reveal state in `LaunchpadContent` (`rememberSaveable` keyed on the PENDING card's
   `sortieId`, null when the card is not PENDING), the `UnderlinedTextButton("View in rho")` in
   `PendingActions` between the Re-fly block and Scrub with the bar's 12 dp spacer, the
   `rho session <id>` note line at the end of `PendingContent`, and the two new arguments threaded through
   `ActionButtons` to the Pending arm only, defaulted. Gate: `:app:compileDebugKotlin`, then one cold
   class-scoped run of the five Compose-rendering Launchpad classes + `LiftoffShellTest` = 37 tests, 0
   failures.
2. **Test it.** `LaunchpadViewInRhoTest` with the three names above, then the plan's three one-token
   mutations of the screen file, one log each, restored from a byte-copy backup. Gate: that class alone, 3
   tests, 0 failures.
3. **Gate it.** `bash .rho/runs/view-in-rho-on-the-pending-card-t49t/verify.sh` detached. Expect
   `Result: 17 passed, 0 failed`, `BRIEF DONE.`, `EXIT=0`, and the report line at 736 tests (733 baseline +
   the 3 new).

**Which mutation reddens which name** — so a work step can tell a good result from a wrong one: dropping the
button's id condition reddens the two absence names; making the tap set nothing reddens
`pendingCardWithASessionIdShowsViewInRhoAndTappingItRevealsTheSessionLine` (the line never appears); dropping
the reveal condition on the id line reddens the same name (the line is there before the tap).

**Where verify.sh's 17 `ok`s come from**: 1 for the CI gate + 3 named tests + 13 whole-class checks (the 12
Launchpad classes + `LiftoffShellTest`). The build-cache check and the "reports exist" check only ever add a
`bad`, so a clean run is 17/0.

**Per-class counts from the last whole-suite run** (`app/build/verify-old-results-1793307599`):
`LaunchpadPendingStatusTest` 6, `LaunchpadPlannedActionsTest` 3, `LaunchpadReFlyTest` 4,
`LaunchpadDraftStatusTest` 8, `LaunchpadDraftControlsTest` 4, `LiftoffShellTest` 12. Those five Launchpad
classes are the only ones in the package that create a Compose rule; the other seven are view-model or
derivation tests, so a change to what the card draws cannot reach them — which is why task 1's gate names
five plus the shell and not all twelve.

**Hazards the work steps should not re-learn**: a scratch gate script must `mv` `app/build/test-results`
aside before it runs, or the next run reads this run's XML; never two Gradle runs in one shell invocation
(they share the results directory); `echo "EXIT=$?"` must not follow a pipe; a mutation run is *supposed* to
print `BUILD FAILED`, and what proves it bit is the failing test's name read out of the XML; a
`git checkout -- file` restore is denied by the approval gate in a runner session, so restore with `cp` from
a backup taken from the *edited* file; `:app:assembleDebug` is not needed until task 3's gate.

## Docs that set rules- `DESIGN.md` §9, l.561-568: the PENDING card lists Retry, Re-fly, Scrub "plus 'View in nabu', which
  shows the nabu session id so it can be opened in nabu's app" — the brief renames it rho; the doc
  stays as it is (no doc edits this run).
- `CLAUDE.md`: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only; §2 vocabulary (never "session" for a sortie);
  short comments.
- `ARCHITECTURE.md` l.37 and the M3 row (l.82) describe the Launchpad's current state — not to be
  changed in this run.
