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
  ACTIVE mission). Nothing outside `ui/launchpad` asserts on the draft's buttons.
