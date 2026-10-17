# Plan — Launchpad scrub dialog and draft controls cleanup

Two independent cleanups, both inside `com.liftoff.app.ui.launchpad`. Part 1 deletes the
Launchpad's private scrub dialog and adopts the themed one already used by In-Flight. Part 2
removes the duplicate −/+ pair under the draft chips and gives the surviving bottom-bar pair real
disabled states at 1 and 7 sorties.

## Why this approach fits the codebase

`app/src/main/java/com/liftoff/app/ui/inflight/ScrubDialog.kt` already exists for exactly this
hand-off: its header says it takes "plain values and callbacks and no view model, so the Launchpad
can adopt the same dialog later". It is `internal`, and `internal` in Kotlin is module-wide, so
`com.liftoff.app.ui.launchpad` (same `app` module) can already call it. **No visibility change to
`ScrubDialog.kt` is needed**, and none is made — the brief allows one, the code does not require it.

The Launchpad's own dialog is a near-copy of the same Paper card, so the swap is a deletion plus one
call site, not a redesign.

For the stepper: `InkButton` (`ui/theme/Buttons.kt:92`) has **no `enabled` parameter**, and
`ui.theme` is outside the files this brief allows. The disabled state therefore has to be built in
`LaunchpadScreen.kt` out of pieces that are already public: `Modifier.offsetShadow`
(`ui/theme/OffsetShadow.kt:25`), `ButtonShape`, `Ink`, `Red`, `Muted`, `Cream`, `LiftoffType`.
`Modifier.clickable(enabled = false, role = Role.Button)` applies the `disabled()` semantics itself,
so `assertIsNotEnabled()` works in a Compose test without touching `ui.theme`.

## Files and parts involved

**`app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`**
- `confirmScrub()` (:121-138): today `manager.scrub(sortieId, _scrubReason.takeIf { it.isNotBlank() })`
  — never trims, so `'  sore knee  '` is stored with its spaces. Change to
  `reason.trim().takeIf { it.isNotEmpty() }`, the rule `InFlightViewModel.normalizeNote`
  (`InFlightViewModel.kt:134`) uses. `trim()` only touches the ends, so interior line breaks survive.
- After the dialog swap: delete `_scrubReason`, `scrubReason` and `setScrubReason` (:23-24, :117-119),
  because `ScrubDialogContent` keeps the draft text in its own `remember` (`ScrubDialog.kt:59`).
  `requestScrub()` (:112-115) and `cancelScrub()` (:140-143) keep clearing `scrubbing`, so a reopened
  dialog still starts empty.
- `confirmScrub` becomes `confirmScrub(reason: String)`. Its `sortieId` `when` over
  Planned/Pending/InFlight and its `finally { scrubbing = false }` stay as they are, so the dialog
  still closes once the write has finished.
- `addChip` / `removeChip` keep their `>= 7` / `<= 1` guards, as the brief requires.

**`app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`**
- Delete `private fun ScrubDialog` (:263-299) — the only `OutlinedTextField` in the package.
- Add `import com.liftoff.app.ui.inflight.ScrubDialog`, and drop `import androidx.compose.ui.window.Dialog`
  (:22), which nothing else in the file uses. Do the deletion and the import in the same edit: with
  both present the names clash.
- Call site (:95-101) becomes
  `ScrubDialog(sortieNumber = sortieIndex + 1, onConfirm = { viewModel.confirmScrub(it) }, onDismiss = { viewModel.cancelScrub() })`.
  `sortieIndex + 1` is the number the Launchpad's dialog shows today ("Scrub sortie ${sortieIndex + 1}?"),
  and the shared dialog renders `sortieNumber` directly. The `showScrubDialog` / `sortieIndex` `when`
  blocks (:82-94) are unchanged.
- `ActionButtons` (:171-199): drop the `scrubbing` and `scrubReason` parameters — the `when` inside
  never reads them, and nothing else passes them. Update the call in the `bottomBar` (:64-76).
- `DraftPatternChips` (:303-363): delete the whole second `Row` (:337-361, the two stock M3
  `OutlinedButton`s) and its `onAdd` / `onRemove` parameters. The chip circles and `onToggle` stay.
  Update the call in `DraftContent` (:113-118).
- `DraftActions` (:201-234): replace the two `InkButton`s with a private `DraftStepButton`, keeping
  the `"$count sorties"` text and `PrimaryButton(text = "Confirm")` untouched:

  ```kotlin
  // InkButton has no enabled parameter, and ui.theme is out of scope for this brief.
  @Composable
  private fun DraftStepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
      val interactionSource = remember { MutableInteractionSource() }
      val pressed = interactionSource.collectIsPressedAsState()
      val fill = if (!enabled) Muted else if (pressed.value) RedPressed else Ink
      Box(
          modifier = Modifier
              .fillMaxWidth()
              .offsetShadow(color = if (enabled) Red else Rule, shape = ButtonShape, offset = 4.dp)
              .background(fill, ButtonShape)
              .border(BorderStroke(2.dp, Ink), ButtonShape)
              .clickable(
                  interactionSource = interactionSource,
                  indication = null,
                  enabled = enabled,
                  role = Role.Button,
                  onClick = onClick,
              ),
          contentAlignment = Alignment.Center,
      ) {
          Text(text, style = LiftoffType.landLabel().copy(color = if (enabled) Cream else Sand))
      }
  }
  ```

  `−` gets `enabled = count > 1`, `+` gets `enabled = count < 7`. Both keep
  `Modifier.width(48.dp)` and `height = 40.dp`, so the bar's layout does not move.
  `FooterCell` (`InFlightScreen.kt:831`) is the precedent for a dimmed, non-clickable control.
  `BorderStroke`, `background`, `border`, `clickable` and `Modifier.offsetShadow` are already imported
  in this file; `Role`, `MutableInteractionSource` and `collectIsPressedAsState` need new imports.
- Split the screen body so a test can render the whole draft state without an `AppContainer`
  (`AppContainer` opens an on-disk `liftoff.db`, which a Robolectric test should not do):
  `LaunchpadScreen(container, onOpenInFlight)` keeps its exact signature — it only builds the
  `LaunchpadViewModel` from the container — and delegates to a new
  `internal fun LaunchpadContent(viewModel: LaunchpadViewModel, onOpenInFlight: (Long) -> Unit)`
  holding the `Scaffold`, the state `when`, the bottom bar and the scrub-dialog block.
  `ui/LiftoffShell.kt:132` is untouched.

**Tests** — new files under `app/src/test/java/com/liftoff/app/ui/launchpad/`, which today holds only
`LaunchpadStatesTest.kt`.

## Order

Each step compiles and passes on its own, and is committed as soon as it does. Commit messages are
`area: lowercase summary` per CLAUDE.md, staging named files only.

1. **Reason normalization** — `LaunchpadViewModel.confirmScrub` trims and maps blank to null; add
   `LaunchpadScrubTest.kt`. Gate, then commit `launchpad: trim the scrub reason and store blank as null`.
2. **Dialog swap** — delete the private `ScrubDialog`, import the In-Flight one, rewrite the call
   site, move the reason into the dialog (`confirmScrub(reason: String)`), strip `scrubReason` from
   the view model and `ActionButtons`. Gate, then commit `launchpad: show the themed scrub dialog`.
3. **Draft controls** — delete the chip-row pair, add `DraftStepButton` with `enabled`, split out
   `LaunchpadContent`, add `LaunchpadDraftControlsTest.kt`. Gate, then commit
   `launchpad: keep one draft stepper and disable it at the limits`.

Gate each step with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, run detached (see
Risks). While iterating, `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` is
the fast check.

## How the result is tested

**`LaunchpadScrubTest.kt`** — Robolectric (`@Config(sdk = [34])`), fixture copied from
`LaunchpadStatesTest`: in-memory Room with `allowMainThreadQueries()`,
`PreferenceDataStoreFactory.create { File(context.cacheDir, "settings.preferences_pb") }` →
`SettingsStore`, `MissionManager(db, settingsStore, clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC), zone = { ZoneOffset.UTC })`,
and a `CoroutineScope(Dispatchers.Unconfined + Job())` with an exception handler that records escapes.
Setup: `settingsStore.setDefaultPattern("RL")` → `manager.onAppOpen()` → `manager.confirm(mission.id)`
(a mission must be ACTIVE or `MissionManager.scrub` rejects it). Build the view model, then
`withTimeout(5_000) { vm.state.first { it is LaunchpadState.Planned } }` — `stateIn` is `Lazily`, and
`confirmScrub` reads `state.value`, so the state must be collected before the scrub. Then
`vm.requestScrub()` and `vm.confirmScrub("  sore knee  ")`, then poll `sortieDao.get(sortieId)` inside
`withTimeout(5_000)` until `state == SortieState.SCRUBBED`.
- `launchpadScrubTrimsTheReason` → `scrubReason == "sore knee"`.
- `launchpadScrubStoresBlankReasonAsNull` → `"   "` → `scrubReason == null`, sortie still SCRUBBED.
- `launchpadScrubKeepsInteriorLineBreaks` → `"  sore\nknee  "` → `"sore\nknee"`.

Nothing renders the dialog and nothing types into a field: the brief forbids it, and a text field
inside a `Dialog` never lets Compose go idle under Robolectric (the reason `LandScrubDialogTest.kt`
renders `ScrubDialogContent` in the main window instead).

**`LaunchpadDraftControlsTest.kt`** — `createComposeRule()`, `@RunWith(AndroidJUnit4::class)`,
`@Config(qualifiers = "w411dp-h2000dp")`, content wrapped in `LiftoffTheme { }`, rendering
`LaunchpadContent(vm, onOpenInFlight = {})` with a view model built on the same in-memory fixture as
above, left in Draft (`onAppOpen()` only). No Dialog, no text field anywhere.
- `draftDrawsOneStepperPair` — default pattern `RLRLR`: `onAllNodesWithText("+").assertCountEquals(1)`
  and `onAllNodesWithText("−").assertCountEquals(1)` (U+2212, the character the bottom bar uses), plus
  `onAllNodesWithText("-").assertCountEquals(0)` (ASCII hyphen, the character the chip row used).
  Counting `+` is what proves the duplicate pair is gone; counting only `−` would pass before the fix.
- `draftMinusDisabledAtOneSortie` — `settingsStore.setDefaultPattern("R")` before `onAppOpen()`:
  `onNodeWithText("−").assertIsNotEnabled()`, `onNodeWithText("+").assertIsEnabled()`.
- `draftPlusDisabledAtSevenSorties` — `setDefaultPattern("RLRLRLR")`: `onNodeWithText("+").assertIsNotEnabled()`.
- `draftStepperClicksStopAtTheLimits` — from `RLRLR`, `performClick()` on `+` and
  `composeRule.waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("7 sorties").fetchSemanticsNodes().isNotEmpty() }`,
  twice, then `assertIsNotEnabled()` on `+`; then `−` back down to `1 sorties` and the same on `−`.
- `addChipAtSevenLeavesPatternUnchanged` and `removeChipAtOneLeavesPatternUnchanged` — view-model
  only (no Compose): call `vm.addChip()` / `vm.removeChip()` at the limit and assert the stored
  `mission.pattern` length is still 7 / 1, which is the guard the brief says to keep.

**Untouched and still green:** `LandScrubDialogTest`, `InFlightLandScrubTest`, `InFlightScreenTest`,
`LaunchpadStatesTest`, the Mission screen tests, `MissionManager` and the domain tests.
`ScrubDialog.kt` and `InFlightScreen.kt:212-232` are not edited at all.

Checks that show the work is done:
`grep -rn "OutlinedTextField\|OutlinedButton" app/src/main/java/com/liftoff/app/ui/launchpad` → no
matches; `grep -n "private fun ScrubDialog" app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`
→ no match.

## Risks and uncertainties

- **Compose test + real Room writes.** `LaunchpadContent` drives a live `observe()` flow, so the
  click test is the flaky one: every wait needs a timeout, and the screen must be unmounted before
  `tearDown()` closes the database, or the still-collecting flow fails the test with
  "connection pool has been closed". If a run hangs with no XML and climbing CPU, `jstack` the test
  executor (Room's `InvalidationTracker` spins on Robolectric's paused clock), `bash gradlew.sh --stop`,
  delete `app/build/test-results/testDebugUnitTest`, and re-run once before treating it as broken —
  one hung run is not a broken suite.
- **The gate outlasts a shell command.** Run it detached: `mkdir -p app/build` first (a fresh
  worktree has no `app/build`, so a redirect into it silently produces no log), then
  `nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' >/dev/null 2>&1 &`
  and wait for the `EXIT=` line rather than re-running. `--no-build-cache` so the reports are real.
- **Title style moves.** The Launchpad dialog's title goes from `LiftoffType.sectionHead()` to
  `cardTitle()`, and the field becomes `LabeledTextField` instead of a floating-label
  `OutlinedTextField`. That is what "show the In-Flight ScrubDialog" means, but it is a visible change
  on the Launchpad.
- **A stale comment stays stale.** `ScrubDialog.kt:31-36` says the name "does not clash with
  LaunchpadScreen's private ScrubDialog", which stops being true. That file is outside the brief's
  allowed edits (only its visibility was offered, and it does not need changing), so the comment is
  left alone and flagged here.
- **`material3.*` stays imported** (`Text`, `Scaffold`, …), so removing `OutlinedTextField` and
  `OutlinedButton` needs the grep above, not a compiler warning, to confirm they are gone.
- **`LaunchpadContent` is a new `internal` seam.** `LaunchpadScreen`'s public signature is unchanged,
  so `LiftoffShell` and `LiftoffShellTest` are unaffected; the risk is only that the state `when` and
  the dialog block must move verbatim.
- The runner's `verify.sh` does not exist in this worktree yet, so test names are chosen to read like
  the brief's Done-when bullets rather than to match a known list.

## Decisions

1. **What do Back or a tap outside the Launchpad scrub dialog do?**
   - Choice: they cancel the scrub and close the dialog, the way the In-Flight dialog already behaves.
   - Alternative: keep today's behaviour, where `onDismissRequest = { /* no dismiss outside buttons */ }`
     makes the dialog impossible to dismiss except through its two buttons.
   - Why: Back closing a dialog is what Android users expect, and after this change both screens show
     the same dialog, so the same gesture should mean the same thing. Keeping today's behaviour is not
     even available without editing `ScrubDialog` itself, because its Cancel button and its
     outside-dismiss share one `onDismiss`.

2. **How do the −/+ buttons look at 1 and at 7 sorties?**
   - Choice: dimmed (`Muted` fill, `Rule` shadow) and not tappable.
   - Alternative: they keep their normal Ink look and silently do nothing, relying on the view
     model's `>= 7` / `<= 1` guards.
   - Why: a control that cannot act should look unavailable, and the pair being deleted already
     dimmed at exactly those limits (`enabled = pattern.length > 1` / `< 7`), so users keep a signal
     they already have. Hiding the buttons instead would make the bar jump.

3. **What does the reason field show when the dialog is reopened?**
   - Choice: empty, because the text now lives inside the dialog and `requestScrub()` still clears
     `scrubbing` on each open.
   - Alternative: keep the last typed reason in the view model and re-show it.
   - Why: this is what the Launchpad does today (`requestScrub` sets `_scrubReason = ""`) and what the
     In-Flight dialog does, so nothing visible changes.

4. **At one sortie, does the bar read "1 sorties"?**
   - Choice: yes — `"$count sorties"` is kept exactly as it is today.
   - Alternative: singular "1 sortie" at 1.
   - Why: the brief says to keep the bottom-bar set "with its 'N sorties' label", so rewording it is a
     visible change nobody asked for, and the gate may assert the current text. Flagged so the owner
     can flip it in review if they want the grammar fixed.

The brief settles everything else this plan touches: the sortie number in the title, the trim-and-null
rule for the reason, which stepper set survives, the 1 and 7 limits, the view model keeping its own
guards, and Confirm and the chips behaving as they do now.
