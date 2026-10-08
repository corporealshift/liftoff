# Mission Control — Settings Screen Plan

## Approach

Add a `MissionControlViewModel` (plain Kotlin, no Android imports) that wraps `SettingsStore` and exposes the full UI state with per-field validation. Build the screen composables on top of it, using only the existing theme parts (`TitleBlock`, `InkRuledListRow`, buttons, offset-shadow helpers). No new theme composables are needed — the brief's required pieces (eyebrow + display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders) already exist or are trivial to compose from them.

This fits the codebase because:
- `SettingsStore` is already wired through `AppContainer` and has one setter per field.
- The navigation shell in `MainActivity` already opens `MissionControlScreen` via the sliders button.
- The theme composables provide all visual primitives; the brief explicitly says "build it from the design's parts".
- The brief asks for a plain Kotlin state holder that JVM tests can drive — a ViewModel without Android dependencies satisfies this.

## Files and code involved

### New files

1. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlViewModel.kt`**
   - Plain Kotlin class (no Android imports). Constructor takes `SettingsStore`.
   - Holds the UI state as a data class (`MissionControlState`) with fields for every §8 setting plus per-field error messages and a token-visibility flag.
   - Exposes a `MutableStateFlow<MissionControlState>` that mirrors `settingsStore.settings` on init, then updates on each save.
   - Validation methods for each field type:
     - `setDaemonPort(raw: String)` — parses int, rejects outside 1–65535 or non-numeric; leaves stored value unchanged.
     - `setDefaultPattern(chips: List<Chip>)` — converts chips to R/L string; the store setter already enforces 1–7 R/L chars, but the ViewModel also prevents going below 1 or above 7 at the UI level.
     - `setSortieLengthMinutes(raw: String)` / `setHistoryWindowDays(raw: String)` — parses int, rejects ≤ 0 or non-numeric; leaves stored value unchanged.
     - Text fields (host, token, coach path, objectives, constraints) accept any string; no length validation beyond what the store needs.
   - `toggleTokenVisibility()` and `setGenerateRunPlans(value: Boolean)` for simple toggles.

2. **`app/src/test/java/com/liftoff/app/ui/control/MissionControlViewModelTest.kt`**
   - JVM tests (no Compose UI). Uses a test double of `SettingsStore` that records setter calls and returns a controllable `Flow<Settings>`.
   - Covers: loading stored values, saving each field type, rejecting invalid input with the stored value unchanged, and the pattern editor's 1–7 limits.

### Modified files

3. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`**
   - Replace `MissionControlContent()` placeholder with the full settings screen.
   - Accepts `MissionControlViewModel` (or a factory) as parameter; in the shell, create it from `AppContainer.settingsStore`.
   - Sections (in order):
     - **Connection:** daemon host (text field), port (numeric text field), token (masked text field with reveal button).
     - **Coach:** workspace path (text field).
     - **Mission:** pattern chips editor, sortie length (numeric text field), history window (numeric text field).
     - **Units:** weight lb/kg (segmented toggle), distance mi/km (segmented toggle).
     - **Runs:** "Generate run plans" toggle.
     - **Objectives and constraints:** two free-text fields.
     - Leave a visible spacer or section head after objectives/constraints to allow for the future Equipment section (no placeholder widgets that don't work).

4. **`app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`**
   - Wire `MissionControlViewModel` creation from `AppContainer.settingsStore`.

### Existing files read (not modified)

- `SettingsStore.kt`, `Settings.kt` — source of truth, setters with validation.
- `AppContainer.kt` — provides `settingsStore`.
- `LiftoffShell.kt` — hosts the navigation shell; needs ViewModel wiring.
- Theme files: `Buttons.kt`, `Headings.kt`, `InkRuledListRow.kt`, `OffsetShadow.kt`, `Color.kt`, `Shape.kt`, `Type.kt`.

## Order of work

1. **Write `MissionControlViewModel.kt`** — the state holder with validation. This is the core logic, pure Kotlin, testable without Android.
2. **Write `MissionControlViewModelTest.kt`** — JVM tests for the ViewModel. Run `:app:testDebugUnitTest` to verify they pass before building UI.
3. **Build the screen composables in `MissionControlScreen.kt`** — replace the placeholder with the full settings UI, using existing theme parts. Wire it through the shell.
4. **Wire ViewModel in `LiftoffShell.kt`** — create the ViewModel from `AppContainer.settingsStore`.
5. **Run the full gate** — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

### JVM tests (in `MissionControlViewModelTest.kt`)

- **Loading stored values:** verify that on init, all UI state fields match the initial `Settings` from the test store.
- **Saving each field type:** for every setter (host, port, token, workspace path, pattern, sortie length, history window, weight unit, distance unit, run-plans toggle, objectives, constraints), confirm the corresponding `SettingsStore` setter is called with the correct value.
- **Rejecting invalid input — stored value unchanged:**
  - Port: non-numeric string → error shown, store not called.
  - Port: number outside 1–65535 → error shown, store not called.
  - Sortie length: non-numeric or ≤ 0 → error shown, store not called.
  - History window: non-numeric or ≤ 0 → error shown, store not called.
  - Pattern: empty (below 1 chip) → rejected at UI level.
  - Pattern: 8+ chips → rejected at UI level.
- **Pattern editor limits:** adding a chip when already at 7 does nothing; removing a chip when at 1 does nothing; toggling an existing chip's type (R ↔ L) works.

### Build gate

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` must pass.

## Risks and uncertainties

- **Compose preview / inspection mode:** The existing screens use `LocalInspectionMode.current` guards in previews. Need to ensure the new screen composables don't break existing preview behavior.
- **DataStore threading:** `SettingsStore` uses a Kotlin `Flow`. The ViewModel needs to handle flow collection correctly — collect on init, update state on each emission. Since this is a plain class, no lifecycle concerns, but tests must use `runTest` for coroutine safety.
- **Text field focus/keyboard:** Numeric fields need `KeyboardOptions(keyboardType = KeyboardType.Number)` to avoid typing letters. Error messages should appear next to (below) the field without shifting layout unexpectedly.
- **Token masking:** The brief says "masked, with a way to reveal it." This means an `TextField` with `visualTransformation = PasswordVisualTransformation()` and a small eye/reveal button inside or beside the field.

## Decisions

### How does the token reveal work?

**Chosen:** A small eye icon button inside the end of the masked text field (standard Android pattern). Tapping toggles between `PasswordVisualTransformation` and plain text.

**Alternative:** A separate "Show" / "Hide" text button below the field.

**Why:** Users expect the reveal control to be attached to the field itself, not floating elsewhere. The eye-in-field pattern is universal on Android.

---

### How are pattern chips added and removed?

**Chosen:** A row of R/L circle chips (like `PatternTrack`). Below them, two square buttons: a "+" chip button to append an "R" sortie, and small "×" dismiss icons on each existing chip to remove it. Tapping a chip toggles its type between R and L.

**Alternative:** An inline "+" text button that adds chips one at a time with a type picker.

**Why:** The brief says chips are "edited as R/L chips: tap to toggle, add and remove." A dismiss icon on each chip is the most discoverable remove action; a dedicated "+" button keeps adding explicit. Keeping it all in one visual row matches the design's circle-chip language.

---

### How are unit choices (weight, distance) presented?

**Chosen:** Two segmented toggle buttons per unit — e.g. "lb | kg" as a single pill-shaped control with the active half filled red and outlined ink, inactive half paper-filled. Same pattern as M3 SegmentedButton but without the tonal surface look.

**Alternative:** Radio-style circle buttons or a dropdown / spinner.

**Why:** Two options is exactly what segmented controls are for. The brief says "Don't use stock M3 tonal surfaces" — so we build a custom two-state toggle, not a `RadioGroup` or `DropdownMenu`. Users expect to see both options side by side for binary choices.

---

### Do sections get paper cards or sit directly on cream?

**Chosen:** Each section (Connection, Coach, Mission, Units, Runs, Objectives and constraints) is a paper card with 2 dp ink border and 4 dp corners, separated by 16 dp vertical gaps. Section heads appear at the top-left of each card in Big Shoulders caps.

**Alternative:** All fields on cream with only horizontal rules separating sections.

**Why:** The brief explicitly calls out "paper cards with 2 dp ink borders" as a design part to build from. Cards give each section visual weight and make the future Equipment section easy to slot in (just add another card). Without cards, there's nothing to distinguish where Equipment would go.

---

### Where does the Equipment section go?

**Chosen:** After Objectives and Constraints, with a visible "Equipment" section head row on a paper card — but no fields populated yet. The card has a subtle ink rule separator above it and the brief says "leave room," not "add placeholders that don't work." So: an empty paper card with just the section head, ready for content.

**Alternative:** No visible placeholder at all; just a bottom spacer.

**Why:** The brief says "Leave room in the screen for an Equipment section" and "don't add placeholders for them that don't work." A bare section head on a card signals the section exists without pretending it's functional. It also matches the card-per-section pattern established above.
