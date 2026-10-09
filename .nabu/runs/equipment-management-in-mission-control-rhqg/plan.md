# Plan: Equipment Management in Mission Control

## Approach

The brief asks for an **Equipment section** inside the existing Mission Control screen. The data layer is already built — `Equipment` entity, `EquipmentDao`, and its tests all exist. The UI layer has a working `MissionControlScreen` / `MissionControlViewModel` pair that manages settings. The pattern in this codebase is:

- **ViewModel holds state + business logic** (plain Kotlin, no Android imports).
- **UI reads from the ViewModel's StateFlow** and calls methods on it.
- **Room Flow drives live updates.**

So the plan extends the existing `MissionControlViewModel` to own equipment state and adds an Equipment section card to the screen. No new files are needed for the core logic — everything fits inside the existing view model and screen, with one new file for the equipment-specific UI composables (to keep the screen file manageable) and one test file.

This approach matches the existing architecture: `MissionControlViewModel` is already a plain-Kotlin state holder wrapping a store; extending it to also wrap an `EquipmentDao` follows the same pattern. The Compose UI uses the same theme components (`SectionCard`, `InkRuledListRow`, `Paper` cards, ink-bordered text fields) that are already in the codebase.

## Files involved

### Existing files (modified)

1. **`app/src/main/java/com/liftoff/app/AppContainer.kt`**
   - No change needed — `database.equipmentDao()` is already accessible from any code that holds a reference to `AppContainer`. The shell passes `settingsStore` and `scope` to the screen; we'll add `equipmentDao` as an extra parameter.

2. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`**
   - Add an `EquipmentSectionCard` composable that renders the equipment list (active items, and optionally deactivated ones).
   - Add a "Show deactivated" toggle inside the section.
   - Add an "Add equipment" button that opens an inline form (key, name, notes fields) with validation errors.
   - Each active row shows key — name — notes, with Edit and Deactivate actions.
   - Each deactivated row shows key — name — notes, with a Reactivate action.
   - Use theme composables: `InkRuledListRow` for list rows, paper cards with ink borders for the add form, custom text fields matching the existing style (no M3 pill shapes or tonal surfaces).

3. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlViewModel.kt`**
   - Add `EquipmentDao` as a constructor parameter.
   - Add equipment-specific state: `equipmentList`, `showDeactivated`, `editingId`, `editName`, `editNotes`, `addKey`, `addName`, `addNotes`, `addError`.
   - Expose flows for active and all equipment lists, merged into the UI state.
   - Methods: `onAddEquipment()`, `onEdit(id, name, notes)`, `onDeactivate(id)`, `onReactivate(id)`, `setShowDeactivated(bool)`, `startEdit(id)`, `cancelEdit()`, `startAdd()`, `cancelAdd()`, field setters for the add form and edit fields.
   - Add equipment validation: key must match `[a-z0-9_]+` (already enforced by DAO), key must not exist in active or inactive items, name is required.

4. **`app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`**
   - Pass `equipmentDao` to the Mission Control screen. The shell already accesses `container.settingsStore` — it will also access `container.database.equipmentDao()`.

5. **`ARCHITECTURE.md`**
   - Update the milestone table: mark M1 as done, with a note that test connection and export/import come later.

### New files (created)

6. **`app/src/main/java/com/liftoff/app/ui/control/EquipmentSection.kt`**
   - Composable functions for the equipment section UI: list rows, edit form inline in the row, add dialog/form, deactivated toggle. This keeps `MissionControlScreen.kt` at a manageable size and follows the pattern of keeping composable helpers in separate files (like `theme/Buttons.kt`).

7. **`app/src/test/java/com/liftoff/app/ui/control/EquipmentViewModelTest.kt`**
   - JVM tests (JVM only, no Robolectric needed for state logic — but Room DAO calls need a real DB, so use in-memory Room via Robolectric or the room-testing library).
   - Tests cover: key validation rules (invalid pattern, duplicate against active, duplicate against inactive), name requirement, editing, deactivate/reactivate moving items between lists without deleting.

## Order of work

1. **Extend `MissionControlViewModel`** to accept `EquipmentDao`, load equipment state from flows, and implement add/edit/deactivate/reactivate logic with validation. This is pure Kotlin + Room DAO — testable on JVM/Robolectric.
2. **Create `EquipmentSection.kt`** with the Compose composables for the equipment UI: list, add form, edit inline, deactivate/reactivate buttons, show-deactivated toggle. Use existing theme components.
3. **Wire it into `MissionControlScreen.kt`** — add the Equipment section card after the "Objectives and Constraints" section.
4. **Wire `equipmentDao` through `LiftoffShell.kt`** — pass `container.database.equipmentDao()` to the screen.
5. **Write tests** in `EquipmentViewModelTest.kt`.
6. **Update `ARCHITECTURE.md`** milestone table.
7. **Run the gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

### Unit tests (`EquipmentViewModelTest.kt`)

Using an in-memory Room database (same pattern as `EquipmentDaoTest.kt`):

- **Add — valid key, name required.**
  - Adding with empty name produces an error and nothing is saved.
  - Adding with a key and name succeeds; item appears in the active list.
- **Key validation — invalid pattern.**
  - Keys like `"Bar"`, `"pull up"`, `"kb-24"`, `""` produce inline errors.
- **Key validation — duplicate against active item.**
  - Adding a key already used by an active item produces an error; nothing is saved.
- **Key validation — duplicate against inactive item.**
  - Adding a key already used by a deactivated item also produces an error (the brief says "one already used by any item (active or inactive)").
- **Edit name and notes.**
  - Editing changes the name/notes; the key stays fixed.
  - Edit is reflected in the list immediately via Flow.
- **Deactivate.**
  - Item moves from active list to not-visible-in-active-list.
  - It is still present in `observeAll()`.
  - No delete method is called (verified by checking no `delete` DAO call).
- **Reactivate.**
  - Deactivated item reappears in the active list.
  - The item's key, name, and notes are preserved.
- **Show deactivated toggle.**
  - When off: only active items shown.
  - When on: both active and deactivated items shown with appropriate actions (Reactivate for deactivated).
- **Live updates from Room.**
  - After any operation, the UI state reflects the change without needing a refresh.

### Build gate

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` — must pass.

## Risky or uncertain areas

1. **ViewModel scope and lifecycle.** The existing `MissionControlViewModel` takes a `CoroutineScope` from the shell. Equipment DAO operations (flow collection, insert) need coroutine launches on this scope. Must ensure the scope is cancelled when the screen navigates away, to avoid leaks.
2. **Flow collection in ViewModel.** Collecting two flows (`observeActive()` and `observeAll()`) needs careful merging so that edits/deactivations update the UI state atomically. Will use a single `combine` or a shared state variable updated by a coroutine that collects both.
3. **Inline add form UX.** The brief says "enter key, name and optional notes" with inline errors. Need to decide whether this is an inline expandable section within the Equipment card (matching the existing SectionCard style) or a dialog. Plan: inline expandable section — it's lighter weight and matches the Mission Control aesthetic.
4. **`AppContainer` doesn't expose `database` publicly.** It does — `val database: LiftoffDatabase by lazy { ... }`. The shell already accesses `container.settingsStore`, so accessing `container.database.equipmentDao()` is a one-line change.

## Decisions

The brief leaves the following behaviors open. Each entry gives the choice, the alternative, and why.

**1. How is the add form presented?**
- **Choice:** Inline expandable section within the Equipment section card, below the active list. Tapping "Add" reveals key/name/notes fields and a Save button; tapping Cancel or saving hides it again.
- **Alternative:** A full-screen dialog or bottom sheet.
- **Why:** Mission Control uses inline sections throughout (no dialogs for settings). The brief says "Use the same design parts as the rest of Mission Control" — the existing LabeledTextField and SectionCard patterns are inline, not dialog-based.

**2. How is editing presented?**
- **Choice:** Each active row has an Edit button that replaces the row's display with inline editable fields (key shown read-only, name + notes editable), a Save button, and a Cancel button.
- **Alternative:** A separate edit screen or dialog.
- **Why:** Matches the inline editing pattern of Mission Control settings. The brief says "Edit: change an item's name and notes" — no need for a separate navigation target on a settings screen.

**3. Where is the deactivated items toggle?**
- **Choice:** A text button at the bottom of the Equipment section card, labelled "Show deactivated", that toggles visibility. Deactivated items appear below active items in the same list.
- **Alternative:** A separate tab or a second section card.
- **Why:** The brief says "A way to show deactivated items" — a simple toggle is the lightest-weight option and keeps everything in one place. Users expect to find deactivated equipment near where they deactivated it.

**4. What happens when an item is deactivated?**
- **Choice:** The item immediately disappears from the active list. It remains visible only after toggling "Show deactivated". No confirmation dialog is shown.
- **Alternative:** A confirmation dialog before deactivation.
- **Why:** Mission Control settings don't use confirmation dialogs (pattern chips, unit changes, etc. all apply immediately). Consistency with the rest of the screen.

**5. Can the equipment key be edited after creation?**
- **Choice:** No. The key is fixed once created and shown as read-only in edit mode.
- **Why:** The brief explicitly says "The key is fixed once created, because history and prompts refer to it." This is decided by the brief.

**6. How are validation errors displayed for the add form?**
- **Choice:** Inline error text below each field (matching the existing `LabeledTextField` error pattern), plus a summary error above the Save button for key-level issues (duplicate key).
- **Alternative:** A toast or snackbar.
- **Why:** The brief says "gets an inline error" — and the existing Mission Control uses inline errors below fields (portError, sortieLengthError, etc.).

The brief settles every other behavior the plan touches: add/edit/deactivate/reactivate mechanics, key validation rules, live updates from Room, theme usage, test requirements.
