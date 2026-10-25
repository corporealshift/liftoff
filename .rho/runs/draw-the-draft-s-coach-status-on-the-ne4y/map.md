# Map — the draft's coach status on the Launchpad

HEAD when this plan was written: `0776758` (PR #40, "The draft's coach status, derived and live from Room
(no UI)"). The derivation is merged; only the drawing is missing.

## Docs that set rules

- `DESIGN.md` §4.2 (lines 119–142): draft → confirm queues an outline generation; a failed outline leaves the
  Mission a draft "with an error and a retry"; "Continue without outline" activates the week with no outline.
- `DESIGN.md` §9 (lines 553–577): the Launchpad's states, and the status wording "Coach is planning…",
  "Mission Control offline — retrying", "or a failure with its reason".
- `design/README.md`: color table (`red` = the primary action, deviations; `muted` = secondary text), type
  table (Text button = Work Sans 15/600 underlined; Secondary/coach note = Work Sans 13/500–600), and lines
  112–114: the Launchpad states that are not mocked up are "built from the same parts… PENDING replaces the
  Flight Plan list with the status line and puts Retry / Re-fly / Scrub where Launch is". No stock M3 visuals.
- `ARCHITECTURE.md` line 82: the M3 row, ending "App wiring — the screens that queue a generation and cancel
  one — comes next". This is the row the brief asks to update.
- `CLAUDE.md`: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only; §2 vocabulary; short comments.

## What already exists

- `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt` — the sealed status, already naming its own
  actions: `Editable` (confirm only), `Planning` / `Offline` (continue only), `Failed(reason)` (retry +
  continue), `NotConfigured` (continue only). `deriveCoachStatus(newestOutlineStatus, newestOutlineError,
  newestOutlineIsCoachOffline, coachConfigured)`: QUEUED/RUNNING → Offline when the row carries the offline
  mark, else Planning; FAILED → `Failed(error)`, falling back to `CoachStatus.FAILED_WITHOUT_REASON`
  ("outline generation failed") for a blank reason; SUCCEEDED / CANCELLED / no row → Editable or
  NotConfigured off the settings. A FAILED row outranks blank settings. `Planning`, `Offline` and
  `NotConfigured` share the same three flags, so a screen that must tell them apart branches on the variant.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` — `LaunchpadState.Draft(weekStart,
  pattern, missionId, coachStatus)` (line 22). The DRAFT arm (lines 99–115) combines `flightPlanDao()
  .observeChanges()`, `generationDao().observeNewestNonCancelledOutlineForMission(mission.id)` and
  `manager.coachConfigured`, then `distinctUntilChanged()`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` — `NOT_CONFIGURED_MESSAGE` (lines
  17–18: "Mission Control isn't set up. Add the host and token in Mission Control, or continue without an
  outline."); `notConfiguredMessage` (line 35, set by `queueOrReport` line 140, cleared at the start of
  `confirm()` line 95 and by `continueWithoutOutline()` line 116); `confirm()` 91, `continueWithoutOutline()`
  108, `retry()` 124 — each returns the `Job` and no-ops unless the state is a Draft; `toggleChip(index)` 46,
  `addChip()` 61, `removeChip()` 72 (guards at 7 and 1).
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — `ConfirmOutcome.Queued | CoachNotConfigured`
  (51–54); `coachConfigured: Flow<Boolean>` off the settings (66); `confirm()` (120) and `retryOutline()`
  (152) both return `CoachNotConfigured` when host/token are blank, before any check; `retryOutline` checks
  the newest non-cancelled outline is FAILED; `continueWithoutOutline()` (184) does not read the settings and
  returns the open outline ids for the view model to cancel.
- `app/src/main/java/com/liftoff/app/data/Generation.kt` — `error` column, `const val COACH_OFFLINE =
  "Mission Control offline"`, and `isCoachOffline` derived from `error == COACH_OFFLINE` while QUEUED/RUNNING.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (393 lines) —
  `internal fun LaunchpadContent(viewModel, onOpenInFlight)` (62) is the test seam; `DraftContent` (127) draws
  `TitleBlock(eyebrow = "WEEK OF … · DRAFT", title = "New Mission")`, `DraftPatternChips` (134), the
  `notConfiguredMessage` block (141–148, `LiftoffType.note()` in `Muted`); the bottom bar `ActionButtons`
  (201) → `DraftActions` (237): the −/+ row with the "N sorties" label (246–267), `PrimaryButton("Confirm")`
  (269), and the always-visible `UnderlinedTextButton("Continue without outline")` (273). `DraftStepButton`
  (280, file-private) is the repo's proven dimmed-and-untappable shape: `clickable(enabled = …, role =
  Role.Button, indication = null)` + `Rule`/`Muted` when disabled. `DraftPatternChips` (358, file-private) is
  the draft's own 44 dp circle row — `Red` fill for `R`, `Paper` for `L`, 2 dp `Ink` border, `clickable { }`
  with no enabled parameter.
- `app/src/main/java/com/liftoff/app/ui/theme/` — `PrimaryButton(text, onClick, modifier, icon, iconAtEnd,
  height = 72.dp, labelStyle = LiftoffType.launchLabel())` (Buttons.kt 28); `UnderlinedTextButton(text,
  onClick, modifier)` (155) upper-cases its label, `LiftoffType.textButton()` in `Ink`, 1 dp ink underline at
  baseline+4, `Red` when pressed, `heightIn(min = 44.dp)`; `LiftoffType.note()` / `textButton()` /
  `eyebrow()` (Type.kt); `Cream Paper Sand Ink Red RedPressed Mustard Teal TealLight Muted Rule White`
  (Color.kt). `PatternTrack.kt` holds `ChipState = Landed | Current | Upcoming | Scrubbed` and
  `PatternChip(letter, state)` — the read-only track the other Launchpad states use, with a 2 dp ink rule
  behind the chips.

## Tests that exist

- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftControlsTest.kt` — the only class that renders
  the draft through Compose. `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`. Harness: in-memory Room,
  `PreferenceDataStoreFactory.create { File(context.cacheDir, "settings.preferences_pb") }`, a
  `MissionManager` on a fixed clock (`2026-01-12T00:00:00Z`, UTC), `LaunchpadViewModel` with no-op
  `queueGeneration` / `cancelGeneration`; `draft(pattern)` sets only the default pattern and calls
  `onAppOpen()`; `tearDown` cancels the scope `Job` before `db.close()`. Each test waits with
  `waitUntil(5_000)` on a known string before asserting. **Its fixture leaves host and token blank, so every
  test in it now derives `NotConfigured`, not `Editable`.** `draftPlusDisabledAtSevenSorties` asserts
  `CONFIRM`; `draftAlwaysOffersContinueWithoutOutline` asserts `CONFIRM` + `CONTINUE WITHOUT OUTLINE`, clicks
  the latter and waits for `LAUNCH`; `draftShowsTheNotConfiguredMessageWhenItIsSet` drives
  `viewModel.confirm()` directly (not the button) and waits for `NOT_CONFIGURED_MESSAGE`.
- `LaunchpadCoachStatusTest.kt` — derivation only, no Compose. Its fixture recipe is what a render test can
  copy: `coachIsSetUp()` = `setDaemonHost("127.0.0.1")` + `setDaemonToken("coach-token")`; `outlineRow(kind =
  OUTLINE, missionId, status, createdAt, finishedAt)` inserted through `db.generationDao()`; `at(step)`
  staggers `createdAt` so "newest" is decided; a collector launched before the writes.
- `LaunchpadConfirmTest.kt` — view-model only, no WorkManager; asserts `NOT_CONFIGURED_MESSAGE` at line 184,
  so a wording change to the constant is safe there.
- `LaunchpadStatesTest.kt`, `LaunchpadDraftGuardsTest.kt`, `LaunchpadScrubTest.kt`, `LaunchpadReFlyTest.kt` —
  no Compose draft render (ReFly renders the PENDING screen through `LaunchpadContent`; its fixture is an
  ACTIVE mission). Nothing outside `ui/launchpad` asserts on the draft's buttons. No test in the suite taps a pattern
  chip, so chip tappability is unproven today.

## Facts a later step needs (added while writing verify.sh)

- Both `PrimaryButton` and `UnderlinedTextButton` draw `text.uppercase()`, so the labels a Compose test asserts are
  `CONFIRM`, `RETRY` and `CONTINUE WITHOUT OUTLINE`. `DraftStepButton` draws its `−` (U+2212) and `+` as-is, and the
  existing class asserts `"\u2212"`.
- `retry()` (LaunchpadViewModel 124) is the only one of the three draft actions that does **not** clear
  `notConfiguredMessage` first — `confirm()` does at 95 and `continueWithoutOutline()` at 116. That is the fix the
  plan names.
- `MissionManager.retryOutline` (152) returns `CoachNotConfigured` **before** its "newest outline must be FAILED"
  check, so a FAILED row plus blank host/token makes a RETRY tap report not-configured rather than throw. That is how
  the brief's "a Confirm tapped with nothing configured would otherwise show nothing" case actually arrives.
- `retryOutline` inserts a new QUEUED OUTLINE row at `clock.millis()`. Every newest-outline query orders by
  `createdAt DESC, id DESC` (GenerationDao 68–90), so that row outranks a seeded row even when the test's fixed clock
  gives them the same instant — no fixture ordering trap in the "Retry reaches Mission Control" case.
- `DraftPatternChips` is `clickable { }` with no `enabled` parameter, so a read-only chip has to be drawn the way
  `DraftStepButton` is (`clickable(enabled = false, role = Role.Button, indication = null)` + `Rule`/`Muted`), which
  really sets `Enabled = false` semantics. `clickable(enabled = true, role = Role.Button)` sets `Enabled = true`,
  which is what `draftMinusDisabledAtOneSortie` already relies on.
- `ActionButtons` (LaunchpadScreen 201) takes `onConfirm` / `onContinueWithoutOutline` and has no `onRetry`; the draft
  arm at 219 passes five callbacks to `DraftActions` (237), which draws the stepper row (246–267), `PrimaryButton`
  "Confirm" (269) and the always-visible `UnderlinedTextButton` (273).
- `retryOutline` refuses (IllegalStateException) unless the newest non-cancelled outline is FAILED, and the view model
  swallows that into `manager.onAppOpen()`. So the "report clears" case must leave a FAILED row behind for the second
  RETRY to answer — which is exactly what the first RETRY does, since it refuses without writing a row.

## verify.sh (rewritten after this map was first written)

`c315072 run: verify.sh rewritten by claude review` replaced the eight-name version. **It now names six tests**, all
new to this run and all rendered statuses: `editableDraftDrawsItsChipsStepperAndConfirmAndNoOtherAction`,
`planningDraftDrawsItsStatusLineAndContinueWithoutOutlineAndNoStepperOrConfirm`,
`planningDraftDrawsItsPatternChipsReadOnly`, `offlineDraftDrawsItsStatusLineAndTheSameActionsAsPlanning`,
`failedDraftDrawsItsReasonAndRetryAndContinueWithoutOutlineAndNoConfirm`,
`notConfiguredDraftDrawsItsSetupLineAndContinueWithoutOutlineAndNoConfirmOrRetry`. The two single-status-line names
(a RETRY replacing the failure reason with one not-configured line; the report clearing when the next RETRY reaches
Mission Control) are **gone from verify.sh**, though the plan's Testing section still describes them as cases and its
header says the one-line rule and the retry-clearing are decisions Kyle reviews in the PR, not checks. Plus the CI
gate and 26 whole-class "must not break" checks. No ARCHITECTURE.md grep, no source grep, no schema check — the
header says why.

Run against the untouched tree (`096bf6a`): gate attempt 1 passed in 2m38s with a warm daemon, 655 tests / 0 failures
/ 0 skipped, 32 checks passed, exactly those 8 names reported "did not run", **exit 1**. Log at
`app/build/verify-ne4y.log`, script copy at `app/build/verify-ne4y.sh` (run the copy, not the real file, so editing
the real one mid-run does not give a phantom syntax error). That measurement was of the eight-name script; the
six-name version has not been re-measured, but the six names are a subset of the eight and none of them existed at
`096bf6a`, so the untouched tree still fails.

## Facts a later step needs (added while writing tasks.md)

- HEAD for the work steps is `c315072`; nothing under `app/src` has changed since PR #40 (`0776758`).
- `LaunchpadDraftControlsTest`'s five methods are `draftDrawsOneStepperPair` (97), `draftMinusDisabledAtOneSortie`
  (122), `draftPlusDisabledAtSevenSorties` (142), `draftAlwaysOffersContinueWithoutOutline` (166),
  `draftShowsTheNotConfiguredMessageWhenItIsSet` (196). Only that class asserts on the draft's buttons in Compose
  (179–183, 209–217); nothing else in `app/src/test` mentions `CONTINUE WITHOUT OUTLINE` except as prose in
  `CoachStatusTest`'s comments and `CoachPromptBuilderTest`.
- `LaunchpadConfirmTest` reads `NOT_CONFIGURED_MESSAGE` by constant (184), so re-wording the constant is safe there.
  It asserts `assertNull(vm.notConfiguredMessage)` at 192, 211, 269 and 301; 301 is after a **successful** `retry()`
  with host and token set, where the field is already null, so adding the clear at the start of `retry()` keeps that
  class green. Its fixture uses `VALID_HOST = "127.0.0.1"` / `VALID_TOKEN = "token-under-test"` and
  `RETRIED_ERROR = "the coach's reply was not an outline"`, and it reaches a FAILED row through
  `generationDao.markFailed(id, error, clock.millis())` — the same helper a render test can use to seed a failed
  draft.
- `ARCHITECTURE.md` line 82 is the M3 row; its closing clause today is "App wiring — the screens that queue a
  generation and cancel one — comes next". That is the clause the brief's M3 update replaces.
- The per-class gate form used by earlier runs of this project works here:
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.<ClassName>"`.
- `LaunchpadContent` is called with named arguments `viewModel = …, onOpenInFlight = {}` at seven sites: the screen
  itself (LaunchpadScreen 43), the five `LaunchpadDraftControlsTest` cases, and `LaunchpadReFlyTest` 123. A new
  **required** parameter on that seam breaks `LaunchpadReFlyTest`, which `verify.sh` checks as a whole class, so the
  Retry wiring belongs inside `LaunchpadContent` / `ActionButtons`, not in the seam's signature.
