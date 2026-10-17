# Tasks — Launchpad scrub dialog and draft controls cleanup

Four commits, in this order. Tasks 1 and 2 are independent of each other; task 4 builds on task 3.
Each task compiles on its own and gates green before it is committed.

Shared facts every task needs (the plan has the detail; these are the ones that bite):

- Fixture for a view-model test: in-memory Room with `allowMainThreadQueries()`,
  `PreferenceDataStoreFactory.create { File(context.cacheDir, "settings.preferences_pb") }` →
  `SettingsStore`, `MissionManager(db, settingsStore, clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"),
  ZoneOffset.UTC), zone = { ZoneOffset.UTC })`, and a `CoroutineScope(Dispatchers.Unconfined + job)` with a
  `CoroutineExceptionHandler` that records escapes (assert none recorded). `@After`: `job.cancel()` **before**
  `db.close()` — `stateIn(Lazily)` keeps the Room flow collecting for the scope's lifetime. Copied from
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadStatesTest.kt`.
- `stateIn` is `Lazily` and both `confirmScrub` and `addChip`/`removeChip` read `state.value`, so a test must
  collect the state first (`withTimeout(5_000) { vm.state.first { … } }`) or the code under test never runs.
- A scrub needs an ACTIVE mission: `setDefaultPattern(...)` → `onAppOpen()` → `manager.confirm(mission.id)`.
  `onAppOpen()` alone leaves it DRAFT and `MissionManager.scrub` refuses it.
- No test opens a Dialog, focuses or types into a text field. Never a package wildcard or the whole suite in a
  foreground shell call; run the gate detached exactly as the plan's "Risks" section spells it
  (`mkdir -p app/build` first, `--no-build-cache`, wait for the `EXIT=` line).
- CLAUDE.md: `area: lowercase summary` messages, stage named files only, LF endings, never "session" for a
  sortie. Do not touch `.rho/runs/launchpad-scrub-dialog-and-draft-0vdc/verify.sh`, `ScrubDialog.kt`,
  `InFlightScreen.kt` or `ui.theme`.
- Done for the run is `bash .rho/runs/launchpad-scrub-dialog-and-draft-0vdc/verify.sh` exiting 0; each task
  below is done when its own named tests appear as `pass` in
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` from a cold (`--no-build-cache`) gate that also left
  the existing suite green.

- [ ] Pin the view model's draft limit guards with tests
  Covers: a new `LaunchpadDraftGuardsTest.kt` under `app/src/test/java/com/liftoff/app/ui/launchpad/`,
  Robolectric + in-memory Room, no Compose. It proves the `>= 7` / `<= 1` guards in `LaunchpadViewModel.addChip`
  / `removeChip` (:49-73) that the brief says to keep: build the view model on a Draft left at 7 sorties
  (`runBlocking { settingsStore.setDefaultPattern("RLRLRLR") }` then `onAppOpen()`, both suspend), wait with a timeout until
  `state.value` really is `LaunchpadState.Draft` with `pattern.length == 7`, call `vm.addChip()`, and assert
  `missionDao.get(draft.missionId)!!.pattern` is still 7 long (`Draft.missionId` is a non-null `Long`);
  the mirror on a 1-sortie draft
  (`setDefaultPattern("R")`) calling `vm.removeChip()` and asserting the stored pattern is still 1 long.
  No main-source change — the guards already exist, so this task only writes the tests.
  Tests this task writes: `addChipAtSevenLeavesPatternUnchanged`, `removeChipAtOneLeavesPatternUnchanged`.
  Done when: the new file compiles, both names read `pass` in this run's XML reports, and the detached gate
  reports no failures anywhere. Commit `launchpad: test the draft pattern limit guards`.

- [ ] Show the themed scrub dialog on the Launchpad and trim the reason
  Covers: brief part 1, in one commit because the test needs the new `confirmScrub(reason)` signature.
  `LaunchpadViewModel.kt`: `confirmScrub()` becomes `confirmScrub(reason: String)` and stores
  `reason.trim().takeIf { it.isNotEmpty() }` (the rule `InFlightViewModel.normalizeNote` uses) instead of
  `_scrubReason.takeIf { it.isNotBlank() }`; delete `_scrubReason`, `scrubReason` and `setScrubReason`
  (:23-24, :117-119) and the `_scrubReason = ""` lines in `requestScrub()` / `cancelScrub()`; the `sortieId`
  `when` and `finally { scrubbing = false }` stay. `LaunchpadScreen.kt`: delete `private fun ScrubDialog`
  (:263-299, the package's only `OutlinedTextField`) and add `import com.liftoff.app.ui.inflight.ScrubDialog`
  in the same edit (both are named `ScrubDialog`), drop the now-unused `import androidx.compose.ui.window.Dialog`
  (:22), and call `ScrubDialog(sortieNumber = sortieIndex + 1, onConfirm = { viewModel.confirmScrub(it) },
  onDismiss = { viewModel.cancelScrub() })` at :95-101; drop the `scrubbing` / `scrubReason` parameters of
  `ActionButtons` (:171-199) and its `onCancelScrub` / `onConfirmScrub` parameters, which nothing inside it
  reads, and update the `bottomBar` call (:64-76) to match. `ScrubDialog.kt` is not edited — `internal` is
  already module-wide.
  Tests this task writes, in a new `LaunchpadScrubTest.kt` (Robolectric + in-memory Room, no Compose, nothing
  typed): `launchpadScrubTrimsTheReason` — ACTIVE mission, collect the state until it is
  `LaunchpadState.Planned` (that state carries `sortieId`), `vm.requestScrub()`,
  `vm.confirmScrub("  sore knee  ")`, poll `sortieDao.get(sortieId)` inside `withTimeout(5_000)` until the row's
  `state == SortieState.SCRUBBED`, assert its `scrubReason == "sore knee"` and `vm.scrubbing` is false;
  `launchpadScrubStoresBlankReasonAsNull` — `"   "` → `scrubReason == null`, sortie
  still SCRUBBED; `launchpadScrubKeepsInteriorLineBreaks` — `"  sore\nknee  "` → `"sore\nknee"`.
  Done when: all three names read `pass` in this run's XML reports;
  `grep -rn "OutlinedTextField" app/src/main/java/com/liftoff/app/ui/launchpad` and
  `grep -n "private fun ScrubDialog" app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`
  return nothing (`OutlinedButton` is still there at this point; the next task removes it); and the gate leaves
  `LandScrubDialogTest` and `InFlightLandScrubTest` green. Commit
  `launchpad: show the themed scrub dialog and trim the reason`.

- [ ] Draw the draft stepper once, and split out a testable `LaunchpadContent`
  Covers: brief part 2, first half. `LaunchpadScreen.kt`: delete the whole second `Row` of `DraftPatternChips`
  (:337-361, the two stock M3 `OutlinedButton`s with the ASCII `-`) and its `onAdd` / `onRemove` parameters,
  keeping the chip circles and `onToggle`, and update the call in `DraftContent` (:113-118). Split the screen
  body so a test can render the draft state without an `AppContainer` (which opens an on-disk `liftoff.db`):
  `LaunchpadScreen(container, onOpenInFlight)` keeps its exact signature and only builds the
  `LaunchpadViewModel`, delegating to a new `internal fun LaunchpadContent(viewModel: LaunchpadViewModel,
  onOpenInFlight: (Long) -> Unit)` that holds the `Scaffold`, the state `when`, the bottom bar and the
  scrub-dialog block moved verbatim. `LiftoffShell.kt:132` is untouched.
  Tests this task writes, in a new `LaunchpadDraftControlsTest.kt` (`createComposeRule()`,
  `@RunWith(AndroidJUnit4::class)`, `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, content wrapped in
  `LiftoffTheme { }`, rendering `LaunchpadContent(vm, onOpenInFlight = {})` on the shared in-memory fixture
  left in Draft by `onAppOpen()` only, default pattern `RLRLR`, no Dialog and no text field anywhere):
  `draftDrawsOneStepperPair` — wait with `composeRule.waitUntil(timeoutMillis = 5_000)` for the `"5 sorties"`
  text the Draft arrives through Room, then `onAllNodesWithText("+").assertCountEquals(1)`,
  `onAllNodesWithText("−").assertCountEquals(1)` (U+2212, the character the bottom bar uses) and
  `onAllNodesWithText("-").assertCountEquals(0)` (the ASCII hyphen the chip row used); counting `+` is what
  shows the duplicate pair is gone.
  Done when: that name reads `pass` in this run's XML reports,
  `grep -rn "OutlinedTextField\|OutlinedButton" app/src/main/java/com/liftoff/app/ui/launchpad` now returns
  nothing, and the gate leaves `LaunchpadStatesTest` and `LiftoffShellTest` (which composes the Launchpad
  through the shell) green. Commit `launchpad: draw the draft stepper once`.

- [ ] Disable the surviving draft stepper at 1 and at 7 sorties
  Covers: brief part 2, second half, on top of the previous task. `LaunchpadScreen.kt`: replace the two
  `InkButton`s in `DraftActions` (:201-234) with a private `DraftStepButton(text, enabled, onClick, modifier,
  height)` built exactly as the plan spells it out of pieces already public — `OffsetShadowBox` with shadow
  `Rule` when disabled else `RedPressed`/`Red`, `Muted` fill and `Sand` label when disabled, and
  `Modifier.clickable(interactionSource, indication = null, enabled = enabled, role = Role.Button)` so
  `assertIsNotEnabled()` works — keeping `"$count sorties"` and `PrimaryButton(text = "Confirm")` untouched.
  Call it as `DraftStepButton(text = "−", enabled = count > 1, onClick = onRemoveChip, modifier =
  Modifier.width(48.dp))` and `DraftStepButton(text = "+", enabled = count < 7, onClick = onAddChip, modifier =
  Modifier.width(48.dp))`: the width must come in through `modifier`, never a `fillMaxWidth()` inside, or the
  first button swallows the `SpaceBetween` row. New imports: `androidx.compose.ui.semantics.Role`,
  `androidx.compose.foundation.interaction.MutableInteractionSource`,
  `androidx.compose.foundation.interaction.collectIsPressedAsState`, `androidx.compose.ui.unit.Dp`.
  `ui.theme` is not edited; `InkButton` becoming unused in this file is fine.
  Tests this task adds to `LaunchpadDraftControlsTest.kt`: `draftMinusDisabledAtOneSortie` —
  `runBlocking { settingsStore.setDefaultPattern("R") }` before `onAppOpen()`, wait for `"1 sorties"`,
  `onNodeWithText("−").assertIsNotEnabled()` and
  `onNodeWithText("+").assertIsEnabled()`; `draftPlusDisabledAtSevenSorties` — the same with
  `setDefaultPattern("RLRLRLR")`, wait
  for `"7 sorties"`, `onNodeWithText("+").assertIsNotEnabled()` and `onNodeWithText("−").assertIsEnabled()`.
  Cancel the view model's job in `@After` before `db.close()`, since the compose rule tears down after
  `@After` and a still-collecting Room flow fails with "connection pool has been closed".
  `draftStepperClicksStopAtTheLimits` is deliberately not required by the gate; if it is written at all it is
  the flaky one and every wait needs a timeout.
  Done when: both names read `pass` in this run's XML reports, the bar still renders `Confirm` and the chips,
  and `bash .rho/runs/launchpad-scrub-dialog-and-draft-0vdc/verify.sh` exits 0 with all 33 named checks
  passing (`doneBodyweightSetStepperShowsBW` is a known flake — re-run the gate once before treating it as
  broken). Commit `launchpad: keep one draft stepper and disable it at the limits`.
