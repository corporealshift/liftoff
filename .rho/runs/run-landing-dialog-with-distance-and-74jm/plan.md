# Run plan — Run landing dialog with distance and duration

Brief: `.rho/runs/run-landing-dialog-with-distance-and-74jm/brief.md` — let the owner record a
distance and a duration when landing a run, through a themed In-Flight dialog.

## Approach

The parsing, the storage and the view-model half of this already exist and are tested. What is
missing is the screen: today `InFlightScreen`'s `Run` branch calls `vm.land(onBack)` straight away,
so `landRun` has no caller. So this is a UI change with one small state addition, not a new feature
stack.

- `InFlightViewModel.landRun(distanceText, minutesText, onDone)` (`InFlightViewModel.kt:85`) already
  parses both fields through `parseRunEntry`, publishes a `RunEntry.Invalid` to `runErrors`, returns
  `false` without touching `MissionManager` on bad input, returns `false` while `busy` (the second
  tap), and otherwise lands through `MissionManager.land(sortieId, runDistance, runMinutes)` in one
  transaction. `clearRunErrors()` is at `:79`.
- `RunLanding.kt` already rejects anything that is not a finite number above 0, maps blank to null,
  and reports both bad fields together.
- `Sortie.runDistance` / `runMinutes` already exist (`DESIGN.md` §8 line 527), so **no schema
  change** — the brief says the same.
- The dialog is a fourth member of the `ui.inflight` dialog family (`LandConfirmDialog`,
  `ScrubDialog`, `NoteDialog`): the same Paper card with a 2 dp Ink border, `PrimaryButton` +
  `UnderlinedTextButton`, and the fields in `LabeledTextField` from `ui/control/MissionControlScreen`
  — which is `internal`, already takes `keyboardType` and `error`, and is what `ScrubDialog.kt`
  already imports. That is the "no stock M3 visuals" the brief asks for.

One addition the dialog needs that the state does not carry: `InFlightState.Run` holds the target
distance as an already-formatted string (`"5 mi"`) and nothing else about the unit, so the dialog
cannot label its distance field. `deriveInFlight` already receives `distanceUnit`
(`InFlightState.kt:102`) and `InFlightStates.observe` already combines the setting
(`InFlightStates.kt:23`), so the unit goes onto the `Run` state and the screen never has to read the
settings store itself. `InFlightState.Run(` is constructed in exactly one place (`InFlightState.kt:122`),
and no test builds a `Run` by hand — they go through `deriveInFlight` or `vm.state` — so adding a
field is a two-line change.

## Files

| File | Change |
| --- | --- |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt` | `Run` gains `val distanceUnit: DistanceUnit` (the enum is already imported at line 4); `deriveInFlight`'s run branch fills it from the parameter it already has |
| `app/src/main/java/com/liftoff/app/ui/inflight/RunLandDialog.kt` | **new** — `RunLandDialog` (the `Dialog` shell) and `RunLandDialogContent` (the card, so the fields can be tested outside a Dialog) |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | `var landingRun by remember { mutableStateOf(false) }` beside `confirmingLand`/`scrubbing` (line 58–59); the `is InFlightState.Run ->` branch's `onLand` (line 92–96) opens it instead of landing; a `RunLandDialog` block beside the `confirmingLand` block (line 173–188) |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt` | the four Robolectric behaviours the brief lists, on the fixture this class already has (`seedMission`, `sortie0`, `viewModel`, `awaitRun`, `withTimeout(5_000)`) |
| `app/src/test/java/com/liftoff/app/ui/inflight/LandScrubDialogTest.kt` | render-only tests of `RunLandDialogContent`, in the same file and style as the two dialogs it already covers |

`RunContent.kt`, `InFlightViewModel.kt`, `RunLanding.kt`, `MissionManager.kt`, the schema, the
`NoChecklist` branch (line 68–85) and the `Ready` branch (line 103–124) are not touched. Lift
landing, the unchecked-sets confirmation and Scrub stay exactly as they are.

`RunLandDialog` shape:

```
RunLandDialog(sortieNumber, distanceUnit, errors: RunEntry.Invalid?, onLand: (distance, minutes) -> Boolean, onDismiss)
  Dialog(onDismissRequest = onDismiss) { RunLandDialogContent(...) }
```

`RunLandDialogContent` keeps the two field values in `remember { mutableStateOf("") }`, so both are
blank every time the card is composed — which is every time the dialog opens. Its Land is
`if (onLand(distance, minutes)) { /* the caller closes */ }`; the caller closes only when
`landRun` returned true, which is the brief's "on success the dialog closes", and it stays open with
the error under the field otherwise. `errors.distanceError` and `errors.minutesError` go straight into
the two `LabeledTextField`s' `error =` parameter, which already draws them in `Red` under the field.

`InFlightScreen` wiring:

- `onLand` for the `Run` branch: `vm.clearMessage(); vm.clearRunErrors(); landingRun = true`.
- dialog `onLand = { d, m -> vm.landRun(d, m, onBack) }` — the screen's `onBack` is what leaves the
  screen, the same way `LandConfirmDialog`'s confirm calls `vm.land(onBack)`.
- dialog `onDismiss` (Cancel, Back, tap outside — all three are `onDismissRequest`/`onDismiss`):
  `landingRun = false; vm.clearRunErrors(); vm.clearMessage()`. Nothing is landed.

## Order

Each step compiles and its tests pass on its own.

1. **`distanceUnit` on the `Run` state.** `InFlightState.kt` field + `deriveInFlight` fills it.
   `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`, then
   `InFlightStatesTest` / `InFlightRunTest` — no existing assertion changes, because nothing reads
   the new field yet. Commit.
2. **`RunLandDialog.kt`** — the shell and the content card, nothing wired to it yet. Compile check.
   Commit.
3. **Wire it into `InFlightScreen.kt`** — the `landingRun` state, the `Run` branch's `onLand`, the
   dialog block. Compile check. Commit.
4. **Tests** — the four in `InFlightRunTest.kt`, the render-only ones in `LandScrubDialogTest.kt`,
   and the rewording of the now-false comment at `InFlightRunTest.kt:246-248` ("nothing on this
   screen calls landRun yet"). Run `:app:testDebugUnitTest`. Commit.
5. **Gate** — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, run detached (see Risks).

## Testing

Behaviour through the view model and Room, in `InFlightRunTest.kt`, on its existing fixture
(Robolectric `@Config(sdk = [34])`, in-memory Room with `allowMainThreadQueries()`, a real
`SettingsStore`, a `MissionManager` with a fixed clock, `Dispatchers.Unconfined` scope, an `escaped`
queue asserted empty at the end of each test). Every wait is `withTimeout(5_000)`, as in the rest of
the file.

1. **Distance and duration stored when the run lands.** Launch the seeded run, `awaitRun`, then
   `landRun("5.2", "31", onDone)` returns true, `onDone` runs once, and `sortieDao.get` is `LANDED`
   with `runDistance == 5.2` and `runMinutes == 31.0` and `landedAt == fixedInstant.toEpochMilli()`.
2. **Both fields blank store null.** `landRun("", "", onDone)` returns true, the sortie is `LANDED`,
   both run fields are null. This is the brief's "Launch → Land of a simple run with a distance and a
   duration works" minus the numbers, and it is what a user who just wants to finish the run does.
3. **Invalid distance rejected on the distance field.** For each of `"abc"`, `"-3"`, `"0"`:
   `landRun(typed, "", onDone)` returns false, `runErrors.value` is non-null with `distanceError` set
   and `minutesError` null, `onDone` never ran, and `sortieDao.get` is still `IN_FLIGHT` with both run
   fields null.
4. **Invalid duration rejected on the duration field.** The mirror image with `"5.2"` in the distance
   field: `minutesError` set, `distanceError` null, sortie still `IN_FLIGHT`.
5. **A run with no plan lands without the dialog.** Built on `runWithNoPlanStillShowsNoChecklist`
   (line 193), which already asserts the state is `NoChecklist`: extend it (or add a case beside it)
   to call `vm.land(onBack)` and assert `LANDED` with both run fields null — that is the path the
   screen takes for that state, and it is unchanged by this work.
6. **The unit reaches the state**, so the dialog can label its field: assert `run.distanceUnit` is
   `DistanceUnit.MI` on the seeded run, and that after `settingsStore.setDistanceUnit(DistanceUnit.KM)`
   the state the view model publishes next carries `DistanceUnit.KM`. `runDistanceUnitChangeUpdatesStateLive`
   (line 219) already proves the setting flows into the `Run` state live; this only adds the field.

Dialog rendering, in `LandScrubDialogTest.kt`, which already states the rule ("a text field inside a
Dialog never lets Compose go idle under Robolectric, so the Dialog shell itself is not opened here"):
render `RunLandDialogContent` directly in the main window and assert only what is on screen — the
title, `"Distance (mi)"` for `DistanceUnit.MI` and `"Distance (km)"` for `DistanceUnit.KM`,
`"Duration (min)"`, and that passing a `RunEntry.Invalid` puts the distance error text under the
distance field and not under the duration. No `performClick` on Land, no `performTextReplacement`, no
focus, no `waitUntil` on a dialog node. The buttons' callbacks are already proven for this family by
`landConfirmButtonsCallConfirmOrDismiss`; if a button test is added it asserts only that the callback
fires, never that a landing happened.

Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (CLAUDE.md's command; `gradlew.sh`,
not `./gradlew`, on this machine). Run it detached and wait for its exit status, because it can
outlast a shell command's time limit:

```
bash gradlew.sh --stop
rm -rf app/build/test-results/testDebugUnitTest
nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest > app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' &
```
then `wait` on `EXIT=` in `app/build/gate.log`, and read the totals out of
`app/build/test-results/testDebugUnitTest/TEST-*.xml` (`tests=` / `failures=` / `errors=` / `skipped=`,
all zero but `tests`). Expect the suite to grow from 463 by the new tests, with 0 failures, 0 errors,
0 skipped.

## Risks

- **The Robolectric dialog hang.** A `BasicTextField` inside a `Dialog` window never lets Compose go
  idle under Robolectric — it is what killed `noteCellOnActiveCardOpensDialogPrefilledAndSaves` in
  PR #18, and the brief forbids it outright. So: no screen test clicks Land on a run, and no dialog
  test opens the `Dialog` shell. `runScreenShowsPlanAndOffersNoteLandAndScrub`
  (`InFlightScreenTest.kt:1052`) stays as it is — it asserts `LAND` is present and enabled and never
  taps it. The cost is that the one line wiring `RunLandDialog` to `vm.landRun` is not covered at
  screen level; the view-model tests and the content-card tests cover both sides of it. Say so in the
  PR.
- **`busy` makes `landRun` return false for two different reasons** — bad input, and a landing
  already being written. The dialog cannot tell them apart, and does not need to: in both cases it
  stays open and nothing is landed, and `onBack` navigates away once the write finishes. A double tap
  is therefore ignored, as on lifts, without extra code.
- **A write failure closes the dialog.** `landRun` returns true before the transaction runs, so the
  failure surfaces as the screen's existing footer line `"Couldn't land this sortie."` after the
  dialog has closed. That is what a lift Land failure looks like today; see Decision 3.
- **Adding a field to the `Run` data class.** `deriveInFlight` is called at 23 places, but only
  `InFlightState.kt:122` constructs a `Run`, and `sortieNoteSavesOnRunWithAPlan` compares a state
  against a fresh `deriveInFlight(...)` — both keep working, since the same unit flows into both. If
  a call site does turn out to need the unit, `deriveInFlight`'s `distanceUnit` already has a default
  (`DistanceUnit.MI`) and `Run`'s new field can take one too.
- **`InFlightRunTest.kt:246-248`** carries a comment that this brief makes false ("nothing on this
  screen calls landRun yet"). Reword it in the test commit rather than leaving it.
- **`doneBodyweightSetStepperShowsBW`** (`InFlightScreenTest`) fails every few runs with Room's
  `connection pool has been closed` and passes on a re-run of the same tree. It is unrelated to this
  brief. Re-run the gate before treating it as broken; do not "fix" it.
- **`LandConfirmDialog`'s comment** at `InFlightScreen.kt:173` ("Only a checklist sortie can have
  open sets, so the dialog needs Ready") stays true and stays worded as it is; the new dialog is a
  separate block, not an edit to that one.

## Decisions

- **Which Land taps open the dialog.** Chosen: a `RUN` sortie whose Flight Plan exists — the simple
  `"Run"` plan or a generated one — opens it; a run with no plan keeps landing straight away.
  Alternative: ask every run, or only runs with a generated plan. The brief states both halves
  ("Land on a run that has a plan opens a themed dialog"; "A sortie with no plan still lands without
  the dialog"), and DESIGN.md §5.3 gives the simple plan the distance-and-duration entry too, so the
  plan's presence is the line. A user with the run-generation toggle off — which is the default —
  would otherwise get no way to record their run at all.
- **Field labels.** Chosen: `Distance (mi)` / `Distance (km)`, following the configured unit, and
  `Duration (min)`. Alternative: bare `Distance` and `Duration`, or `Minutes`. The brief requires the
  distance label to carry the configured unit, and the app already spells distances `5 mi` / `5 km`
  (`formatDistance`) and durations `10 min` (`formatMinutes`), so the unit in the label is what the
  rest of the screen reads like. A bare label would leave the owner guessing which unit the number is
  stored in — the plan shows `5 mi` and `9:30/mi` side by side, and the stored number is never
  converted.
- **A failed write.** Chosen: the dialog closes and the screen's existing footer line
  `"Couldn't land this sortie."` appears, because `landRun` returns true before the transaction runs.
  Alternative: keep the dialog open with the values still typed so the owner can retry. Closing is
  what a lift Land failure already looks like (`LandConfirmDialog` → `vm.land` → footer line), and the
  In-Flight screen itself is still there with the sortie still `IN_FLIGHT`, so retrying is one tap on
  Land away. Keeping the dialog open would make a run land failure look different from a lift's for
  no reason the owner can see.
- **How long a field error stays on screen.** Chosen: the error stays under its field until the next
  Land or until the dialog is dismissed, not cleared as the owner types. Alternative: clear the error
  on every keystroke. `landRun` re-validates on every Land and reports both bad fields at once, so a
  stale error is the only signal that the value typed is still the one that was rejected; clearing on
  edit would make a field that is still wrong look fixed. Mission Control's fields
  (`MissionControlViewModel` writes the error and clears it only when the value becomes valid) behave
  the same way.
- **The dialog's title.** Chosen: `Land sortie 1?` — the same wording `LandConfirmDialog` and
  `ScrubDialog` use, with the sortie number. Alternative: `Land run?`, or a title naming the plan
  ("Tempo Tuesday"). The two dialogs the owner already sees in this footer are "Land sortie n?" and
  "Scrub sortie n?", and the run's title is already the heading of the screen behind the dialog, so
  repeating it in the card adds nothing. Keeping the question form also means the dialog reads as the
  same kind of thing as the one it replaces.
- **Land with both fields blank.** Chosen: the Land button is always enabled and lands with both
  values null. Alternative: disable Land until one field has a value. DESIGN.md §5.3 says "both are
  optional", and the brief's own done-when list asks for blank fields stored as null, so a user who
  just wants to finish the run — the flow that works today — must not be blocked by a button that
  looks dead.
- **Both errors at once.** Chosen: a distance and a duration that are both bad show both errors
  together. Alternative: report the first problem only. `parseRunEntry` already returns both
  (`RunLanding.kt:11`), and fixing one field at a time means a second Land for no reason.

The rest of what this plan touches is settled by the brief: the decimal keyboard, both fields starting
blank, no unchecked-sets confirmation for runs, Cancel/Back/tap-outside closing without landing, the
ignored second tap, no schema change, no network, and lift landing unchanged.
