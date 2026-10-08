# Mission Control: Settings Screen Plan

## Approach

Add a `MissionControlViewModel` in `com.liftoff.app.ui.control`. It is a plain Kotlin state holder: no Android imports and no `androidx.lifecycle.ViewModel` superclass. It wraps the real `SettingsStore`, loads the stored values once, holds a draft of every field, validates input and writes valid values to the store. Build the screen composables on top of it. Use the existing theme parts (`TitleBlock`, `LiftoffType.sectionHead()`, colours, `ButtonShape`, `CardShape`) and add small private composables in `ui/control` for the parts the theme lacks: the section card, the R/L chip editor, the unit segments and the check box.

How this fits the codebase:
- `SettingsStore` is built by `AppContainer`. `LiftoffApplication.container` holds the `AppContainer`. The store has one suspend setter per field. Its setters throw `IllegalArgumentException` on a bad port, pattern, length or window, so the view model validates first and never calls a setter with a bad value.
- `LiftoffShell` (hosted by `MainActivity`) already shows `MissionControlScreen(onBack)` when `nav.missionControlOpen` is true. The shell has no reference to `AppContainer` yet, so it gets one from `LocalContext.current.applicationContext as LiftoffApplication`. It reads the context only inside the `missionControlOpen` branch, so `LiftoffShellPreview`, which renders with Mission Control closed, never does the cast.
- `SettingsStore` is a final class over `DataStore<Preferences>` with no interface, and the project has no mocking library. The JVM tests therefore use a real `SettingsStore` on a temp-file DataStore, the same way `SettingsStoreTest` does. This also lets the tests check that the stored value really is unchanged after a rejection.

## Files and code involved

### New files

1. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlViewModel.kt`**
   - `class MissionControlViewModel(private val store: SettingsStore, private val scope: CoroutineScope)`. It has no Android imports.
   - `data class MissionControlState` holds:
     - `loaded: Boolean`
     - text drafts: `host`, `portText`, `token`, `workspacePath`, `sortieLengthText`, `historyWindowText`, `objectives`, `constraints`
     - `pattern: String`, `weightUnit`, `distanceUnit`, `generateRunPlans`, `tokenVisible`
     - nullable errors: `portError`, `sortieLengthError`, `historyWindowError`, `patternError`
   - Exposes `val state: StateFlow<MissionControlState>`, backed by a private `MutableStateFlow`.
   - **Loading:** on init it runs `scope.launch { store.settings.first() }` and copies every field into the state, then sets `loaded = true`. After that it does **not** copy later emissions back into the drafts. If it did, an asynchronous save echoing back an older value would overwrite text the user is still typing, and an invalid draft would vanish as soon as another field saved.
   - **Writes:** every setter first updates the state synchronously, so the Compose text fields don't lose characters or jump the cursor. Then, if the value is valid, it runs `scope.launch { store.setX(value) }`.
   - Methods:
     - `setHost(String)`, `setToken(String)`, `setWorkspacePath(String)`, `setObjectives(String)`, `setConstraints(String)` store the text verbatim, with no validation. This matches `freeTextFieldsAreStoredVerbatim`.
     - `setPortText(raw)` trims the input, then calls `toIntOrNull()`. A value in 1..65535 is saved and the error is cleared. Anything else, including an empty field, sets `portError = "Port must be a number from 1 to 65535"` and does not write.
     - `setSortieLengthText(raw)` and `setHistoryWindowText(raw)` work the same way: trim, `toIntOrNull()`, save if > 0. Otherwise they don't write and set `"Sortie length must be a whole number of minutes, above 0"` or `"History window must be a whole number of days, above 0"`.
     - `togglePatternChip(index)` flips R↔L at `index` and saves.
     - `addPatternChip()` appends `R` and saves. At 7 chips it leaves the pattern unchanged, does not write, and sets `patternError = "A pattern has 1 to 7 sorties"`.
     - `removePatternChip()` removes the last chip and saves. At 1 chip it leaves the pattern unchanged, does not write, and sets the same error. A successful add or remove clears the error.
     - `setWeightUnit(WeightUnit)`, `setDistanceUnit(DistanceUnit)` and `setGenerateRunPlans(Boolean)` save directly.
     - `toggleTokenVisibility()` changes the UI state only.
   - The view model also exposes `canAddChip` and `canRemoveChip`, which the screen uses to disable the + and − buttons.

2. **`app/src/test/java/com/liftoff/app/ui/control/MissionControlViewModelTest.kt`**
   - JVM test with no Compose and no Robolectric.
   - Builds a real `SettingsStore` on `PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined + job)) { File(tmp.root, "settings.preferences_pb") }` with a `TemporaryFolder` rule.
   - Gives the view model a `CoroutineScope(Dispatchers.Unconfined + job)`.
   - Uses `runBlocking(Dispatchers.Unconfined + Job())` like `SettingsStoreTest`, and cancels the jobs in `@After`.
   - Coverage is listed under Testing below.

### Modified files

3. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`**
   - Signature becomes `MissionControlScreen(settingsStore: SettingsStore, scope: CoroutineScope, onBack: () -> Unit)`.
   - It builds the view model with `remember(settingsStore) { MissionControlViewModel(settingsStore, scope) }`. A fresh one is made on every open, so the screen always opens on the stored values. It collects `state` with `collectAsState()`.
   - Keep the existing back button and `TitleBlock(eyebrow = "Settings", title = "Mission Control")` row.
   - Replace the `MissionControlContent()` placeholder with a stateless `MissionControlContent(state, actions…)`. The `@Preview` renders this with a sample `MissionControlState`, so the preview needs no store. Keep the `LocalInspectionMode` guard.
   - Section cards always render their section heads. Fields stay disabled until `state.loaded`, so the user can't type before the stored values arrive.
   - Section cards, in order. Each card uses a `Paper` fill, a 2 dp `Ink` border and `CardShape` (square corners, per `design/README.md`), with 16 dp gaps between cards. Each card starts with a `LiftoffType.sectionHead()` head, and 1 dp ink rules separate the fields inside it:
     - **CONNECTION:**
       - "Daemon host" text field.
       - "Port" text field with `KeyboardType.Number`.
       - "Token" text field. It uses `PasswordVisualTransformation()` unless `tokenVisible` is true. A trailing 44 dp icon button shows `Icons.Filled.Visibility` or `VisibilityOff`, with the content description "Show token" or "Hide token".
     - **COACH:** "Coach workspace path".
     - **MISSION:**
       - "Default pattern": the chip editor described in Decisions. The `patternError` shows under the chips.
       - "Sortie length (minutes)" with a number keyboard.
       - "History window (days)" with a number keyboard.
     - **UNITS:** "Weight" with LB | KG segments, and "Distance" with MI | KM segments.
     - **RUNS:** "Generate run plans" with a 44 dp square check box.
     - **OBJECTIVES AND CONSTRAINTS:** "Objectives" and "Constraints", both multi-line text fields.
   - Show each field's error in `Red`, directly under that field, in the note style.
   - Text fields use `OutlinedTextField` with `shape = ButtonShape`, `Ink` border and label colours, and a transparent container. Do not use the filled `TextField`, which has a tonal surface.
   - All UI strings use §2 vocabulary: "Sortie length", never "session".
   - Leave room for Equipment: keep the cards in one `Column` of section composables. The next brief adds an `EquipmentSection()` after Objectives and constraints. Don't render an Equipment card or head now. Don't add 'Test connection' or export/import, and don't add stubs for them.
   - New composables stay private in `ui/control`. Do **not** add files or `@Preview`s to `ui/theme`: `ThemePackageTest` expects exactly 11 files there and `PreviewsTest` expects exactly 9 previews.

4. **`app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`**
   - Add `val shellScope = rememberCoroutineScope()` at the top of `LiftoffShell`. This scope outlives the Mission Control screen, so a write started just before Back isn't cancelled.
   - In the `missionControlOpen` branch, call `val store = (LocalContext.current.applicationContext as LiftoffApplication).container.settingsStore`, then `MissionControlScreen(settingsStore = store, scope = shellScope, onBack = { nav = nav.back() ?: nav })`.
   - Leave the `BackHandler`, the nav-bar contrast `SideEffect` and the tab logic unchanged.

5. **`app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt`**
   - These Robolectric tests run in the gate. Six of them assert `"Settings will appear here."`, which this work removes:
     - `missionControlSurvivesRecreation`
     - `slidersButtonOpensMissionControlWithoutBottomBar`
     - `missionControlBackButtonReturnsToTabItWasOpenedFrom`
     - `systemBackFromMissionControlReturnsToTabItWasOpenedFrom`
     - `navBarIconsAreLightOnTabsAndDarkOnMissionControl`
     - `missionControlSurvivesRecreation`, a second assertion after recreate
   - In each one, replace that assertion with one on the first section head, `onNodeWithText("CONNECTION")`. It renders before the settings load. Keep every back and navigation assertion exactly as it is. Don't delete any test.

### Existing files read (not modified)

- `settings/SettingsStore.kt`, `settings/Settings.kt`, `AppContainer.kt`, `LiftoffApplication.kt`, `MainActivity.kt`
- Theme: `Headings.kt`, `Type.kt` (`sectionHead`, `note`, `eyebrow`), `Shape.kt` (`ButtonShape`, `CardShape`), `Color.kt`, `PatternTrack.kt` (visual reference for the chip look; it is not clickable, so it isn't reused directly), `InkRuledListRow.kt`, `Buttons.kt`, `OffsetShadow.kt`.

## Order of work

1. Write `MissionControlViewModel.kt`.
2. Write `MissionControlViewModelTest.kt`, then run `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.control.*"`.
3. Replace the placeholder in `MissionControlScreen.kt` with the full screen.
4. Wire the store and scope in `LiftoffShell.kt`.
5. Update the placeholder-text assertions in `LiftoffShellTest.kt`.
6. Run the full gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

### JVM tests (`MissionControlViewModelTest.kt`, real `SettingsStore` on a temp file)

- **Loading stored values:** write non-default values to the store, build the view model, and assert that every state field and draft matches them and that `loaded` is true.
- **Saving each kind of field:** for host, token, workspace path, objectives, constraints, port, sortie length, history window, pattern (toggle, add, remove), weight unit, distance unit and the run-plans toggle, call the view model method, then assert `store.settings.first()` holds the new value.
- **Survives restart:** save values through one view model, cancel that DataStore's scope, open a new DataStore and `SettingsStore` on the same file, build a new view model, and assert it loads the saved values.
- **Rejecting invalid input, stored value unchanged:** for each case, assert the error is non-null and `store.settings.first()` still holds the previous value. Then a valid entry clears the error.
  - Port: `"abc"`, `""`, `"0"`, `"65536"`, `"-1"`, `"99999999999"`.
  - Sortie length: `"abc"`, `""`, `"0"`, `"-5"`, `"7.5"`.
  - History window: `"abc"`, `""`, `"0"`, `"-1"`.
- **Draft not clobbered:** type an invalid port, then save another field. The port draft and its error stay as they were.
- **Pattern editor limits:**
  - From a 7-chip pattern, `addPatternChip()` leaves the pattern at 7, sets `patternError` and doesn't change the store.
  - From `"R"`, `removePatternChip()` leaves `"R"` and sets the error.
  - `togglePatternChip` flips R↔L and saves.
  - `canAddChip` and `canRemoveChip` are false at 7 and at 1.

### Robolectric shell tests

`LiftoffShellTest` is updated as described above and must still pass, back behaviour included.

### Build gate

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` must pass.

## Risks and uncertainties

- **DataStore under Robolectric:** opening Mission Control in `LiftoffShellTest` now creates the app's real DataStore. Robolectric gives each test a fresh Application and files dir, so this should work. If tests fail with "multiple DataStores active for the same file", investigate it rather than weakening the tests.
- **Load timing:** the store loads on `Dispatchers.IO` in the app, so the first frame shows section heads with disabled fields. The shell tests assert only the static heads.
- **Rotation:** recreation rebuilds the view model from the stored values. Valid edits are already saved; an invalid draft is dropped, which is acceptable.
- **Chip row width:** 7 × 44 dp chips plus gaps must fit inside the card on a 390 dp-wide screen. Use 8 dp gaps and keep the + and − buttons on their own row under the chips.

## Decisions

### How are pattern chips added and removed?

Changed by review: the plan chose a small "×" on each chip plus a "+" button; the review chose square 44 dp "+" (append R) and "−" (remove last) buttons under the chip row, because a small × on a 44 dp chip falls below the design's 44 dp touch-target minimum and doesn't fit when there are 7 chips in a row. Removing from the middle can still be done by toggling chips and removing the last.

**Chosen (original):** A row of R/L circle chips (like `PatternTrack`). Below them, two square buttons: a "+" chip button to append an "R" sortie, and small "×" dismiss icons on each existing chip to remove it. Tapping a chip toggles its type between R and L.

**Alternative:** An inline "+" text button that adds chips one at a time with a type picker.

**Why:** The brief says chips are "edited as R/L chips: tap to toggle, add and remove." A dismiss icon on each chip is the most discoverable remove action; a dedicated "+" button keeps adding explicit. Keeping it all in one visual row matches the design's circle-chip language.

---

### How are unit choices (weight, distance) presented?

Changed by review: the plan chose a pill-shaped segmented control; the review chose two adjacent square segments with 4 dp outer corners (`ButtonShape`), a 2 dp ink border and at least 44 dp height, because the brief forbids pill shapes. The selected segment is filled and the other is paper.

**Chosen (original):** Two segmented toggle buttons per unit — e.g. "lb | kg" as a single pill-shaped control with the active half filled red and outlined ink, inactive half paper-filled. Same pattern as M3 SegmentedButton but without the tonal surface look.

**Alternative:** Radio-style circle buttons or a dropdown / spinner.

**Why:** Two options is exactly what segmented controls are for. The brief says "Don't use stock M3 tonal surfaces" — so we build a custom two-state toggle, not a `RadioGroup` or `DropdownMenu`. Users expect to see both options side by side for binary choices.

---

### Do sections get paper cards or sit directly on cream?

Changed by review: the plan chose 4 dp card corners; the review chose square corners (`CardShape`), because `design/README.md` says "4 dp on buttons and checkboxes, square on cards". The rest of the decision stands. Section heads use `LiftoffType.sectionHead()`.

**Chosen (original):** Each section (Connection, Coach, Mission, Units, Runs, Objectives and constraints) is a paper card with 2 dp ink border and 4 dp corners, separated by 16 dp vertical gaps. Section heads appear at the top-left of each card in Big Shoulders caps.

**Alternative:** All fields on cream with only horizontal rules separating sections.

**Why:** The brief explicitly calls out "paper cards with 2 dp ink borders" as a design part to build from. Cards give each section visual weight and make the future Equipment section easy to slot in (just add another card). Without cards, there's nothing to distinguish where Equipment would go.

---

### Where does the Equipment section go?

Changed by review: the plan chose an empty Equipment card with only a section head; the review chose no visible Equipment element, with room left only in the code (the next brief adds one section composable after Objectives and constraints), because an empty "EQUIPMENT" card does nothing and reads as broken, which is the kind of non-working placeholder the brief rules out.

**Chosen (original):** After Objectives and Constraints, with a visible "Equipment" section head row on a paper card — but no fields populated yet. The card has a subtle ink rule separator above it and the brief says "leave room," not "add placeholders that don't work." So: an empty paper card with just the section head, ready for content.

**Alternative:** No visible placeholder at all; just a bottom spacer.

**Why:** The brief says "Leave room in the screen for an Equipment section" and "don't add placeholders for them that don't work." A bare section head on a card signals the section exists without pretending it's functional. It also matches the card-per-section pattern established above.

---

### When are edits saved?

Added by review. **Chosen:** Saving is automatic, with no Save button. Each keystroke or tap that produces a valid value is written to the store immediately. An invalid value stays in the field with its error shown, and the stored value is left as it was. If the user leaves with an invalid draft, the next open shows the stored value.

**Alternative:** An explicit Save button, or saving when a field loses focus.

**Why:** Settings screens on Android save automatically, and a Save button is easy to miss before pressing Back. Saving each valid keystroke means nothing is lost on Back or rotation.

---

### How is numeric input parsed?

Added by review. **Chosen:** Surrounding whitespace is trimmed. Only whole numbers are accepted, so "7.5", an empty field, and numbers too big for an `Int` are rejected with the field's message.

**Why:** A stray space from the keyboard shouldn't count as an error, and the store holds `Int`s.

---

### What does the pattern editor show at its limits?

Added by review. **Chosen:** The + button is disabled at 7 chips and the − button at 1. If an add or remove is attempted at a limit anyway, the pattern doesn't change and "A pattern has 1 to 7 sorties" shows under the chips.

**Why:** The brief requires the editor never to go below 1 or above 7, and wants a clear message next to the field for a pattern outside 1–7.

---

### What control is 'Generate run plans'?

Added by review. **Chosen:** A 44 dp square check box with 4 dp corners and a 2 dp ink border: red with a white check when on, white when off. The label "Generate run plans" sits beside it, and the whole row is the touch target.

**Alternative:** The M3 `Switch`.

**Why:** The M3 switch has a pill-shaped track, which the brief forbids. The design already defines a square check box.

---

### How does the token reveal work?

**Chosen:** A small eye icon button inside the end of the masked text field (standard Android pattern). Tapping toggles between `PasswordVisualTransformation` and plain text.

**Alternative:** A separate "Show" / "Hide" text button below the field.

**Why:** Users expect the reveal control to be attached to the field itself, not floating elsewhere. The eye-in-field pattern is universal on Android.
