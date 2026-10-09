# Plan: Equipment Management in Mission Control

## Approach

The brief asks for an **Equipment section** inside the existing Mission Control screen. The data layer is already built: the `Equipment` entity, `EquipmentDao` and its tests all exist. This change touches only the UI layer and its state logic.

**What the codebase gives us:**

- `MissionControlViewModel` is a plain-Kotlin state holder. It is built as `MissionControlViewModel(store, scope)`, and `MissionControlViewModelTest` constructs it that way about 30 times.
- The `scope` passed to it is the shell's `rememberCoroutineScope()`. That scope lives as long as `LiftoffShell` does. It is **not** cancelled when Mission Control closes.
- `MissionControlScreen` creates the view model with `remember(settingsStore)`, so every time Mission Control opens it gets a new one.

**Consequences for the design:**

1. **Equipment gets its own state holder.** Create a new class, `EquipmentViewModel(dao: EquipmentDao, scope: CoroutineScope)`. It is plain Kotlin with no ViewModel superclass, the same style as `MissionControlViewModel`. Do not add a constructor parameter to `MissionControlViewModel`: that would break every existing settings test and mix two unrelated concerns.
2. **Equipment state is a cold `Flow`, not a collector launched on the shell scope.** If the view model launched `dao.observeAll().collect {}` on the shell scope, every opening of Mission Control would leak one more collector that never stops. Instead:
   - Expose `val state: Flow<EquipmentState>` built as `combine(dao.observeAll(), _form) { … }`.
   - The screen collects it with `collectAsState(initial = EquipmentState())`, so collection stops when Mission Control leaves composition.
   - Writes (add, edit, deactivate, reactivate) are launched on the injected scope. They finish even if the user taps Back straight away, the same as settings writes.
3. **One Room flow drives both lists.** `observeAll()` is partitioned into `active` and `deactivated` lists inside `combine`. Both lists come from one query emission, so a deactivate or reactivate moves an item atomically. Live updates come from Room's invalidation.

## Files involved

### Existing files (modified)

1. **`app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`**
   - Add the parameter `equipmentDao: EquipmentDao` to `MissionControlScreen`.
   - Create the state holder with `remember(equipmentDao) { EquipmentViewModel(equipmentDao, scope) }`.
   - Collect its state with `collectAsState(initial = EquipmentState())`.
   - Add an `EquipmentSection(...)` call after the "OBJECTIVES AND CONSTRAINTS" card, inside a `SectionCard(title = "EQUIPMENT")`.
   - Change `SectionCard`, `LabeledTextField` and `LabeledMultiLineTextField` from `private` to `internal`, so `EquipmentSection.kt` reuses exactly the same fields (theme fonts, ink borders, `ButtonShape`, `Paper` container) instead of copying them.
   - Leave the settings composables and `MissionControlViewModel` unchanged.

2. **`app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`**
   - Read the container once: `val container = (LocalContext.current.applicationContext as LiftoffApplication).container`.
   - Pass `settingsStore = container.settingsStore` and `equipmentDao = container.database.equipmentDao()`.
   - `AppContainer` is not changed: `database` is already a public lazy val.

3. **`app/src/main/java/com/liftoff/app/ui/theme/InkRuledListRow.kt`**
   - Add an optional `actions: (@Composable RowScope.() -> Unit)? = null` parameter.
   - When present, render it as a right-aligned row between the text row and the 1 dp ink rule, so each item's buttons sit inside its ruled row.
   - The default is `null`, so existing callers and previews are unchanged.

4. **`app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt`**
   - `launchScreen` must pass an `EquipmentDao`:
     - Build an in-memory `LiftoffDatabase` with `Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), …)`.
     - Close it in `tearDown`.
   - **`showsNoEquipmentOrUnbuiltPlaceholders` currently asserts that "Equipment" is *not* on screen, so it will fail once this work lands.**
     - Rename the test to `showsNoUnbuiltPlaceholders`.
     - Remove the `"Equipment"` assertion.
     - Keep the "Test connection" and "Export" assertions.
   - Add `"EQUIPMENT"` to the head list in `showsEverySectionHeadInOrder`.
   - Add the equipment labels ("Add equipment", "Show deactivated") to the text list in `neverSaysSession`.

5. **`ARCHITECTURE.md`**
   - In the milestone table, set the M1 row's status to `Done` and add the note: "test connection and export/import come later".

### New files (created)

6. **`app/src/main/java/com/liftoff/app/ui/control/EquipmentViewModel.kt`**
   - `data class EquipmentState(...)` with these fields:
     - lists: `active: List<Equipment>`, `deactivated: List<Equipment>`, `showDeactivated: Boolean`
     - add form: `adding: Boolean`, `addKey`, `addName`, `addNotes`, `addKeyError: String?`, `addNameError: String?`
     - edit form: `editingId: Long?`, `editName`, `editNotes`, `editNameError: String?`
   - `class EquipmentViewModel(dao: EquipmentDao, scope: CoroutineScope)`:
     - `private val _form = MutableStateFlow(FormState())`
     - `val state: Flow<EquipmentState> = combine(dao.observeAll(), _form) { … }`, which partitions the list by `active`.
     - Add-form methods: `startAdd()`, `cancelAdd()`, `setAddKey()`, `setAddName()`, `setAddNotes()`, `submitAdd()`.
     - Edit-form methods: `startEdit(item)`, `cancelEdit()`, `setEditName()`, `setEditNotes()`, `submitEdit()`.
     - Other actions: `deactivate(id)`, `reactivate(id)`, `setShowDeactivated(Boolean)`.
     - Typing into a field clears that field's error.
   - **`submitAdd()` validation.** Run every check before any write; if any fails, nothing is saved.
     1. Trim the key. If it doesn't match `[a-z0-9_]+`, set `addKeyError = "Key must use only a–z, 0–9 and _"`.
     2. Trim the name. If it is blank, set `addNameError = "Name is required"`.
     3. If both pass, check for a duplicate with `dao.observeAll().first().any { it.key == key }`. This covers active and inactive items. On a match, set `addKeyError = "Key \"$key\" is already used"`. When the matching item is inactive, add " by a deactivated item" to the message, so the user knows where to find it.
     4. Otherwise call `dao.add(Equipment(key, name, notes.trim()))`, then clear and close the form.
     5. As a backstop, if a race still makes the insert hit the unique index, catch `android.database.sqlite.SQLiteConstraintException` and set the same duplicate error.
   - **`submitEdit()` validation.** Trim the name; if it is blank, set `editNameError = "Name is required"` and save nothing. Otherwise call `dao.edit(id, name, notes.trim())` and close the editor. The key is never passed in, so it cannot change.

7. **`app/src/main/java/com/liftoff/app/ui/control/EquipmentSection.kt`**
   - `@Composable internal fun EquipmentSection(state: EquipmentState, vm: EquipmentViewModel)`.
   - **Active rows:**
     - One `InkRuledListRow` per item.
     - `index` is the 1-based position.
     - `title` is `listOf(key, name, notes).filter { it.isNotBlank() }.joinToString(" — ")`.
     - `actions` holds `UnderlinedTextButton("Edit")` and `UnderlinedTextButton("Deactivate")`.
   - **Editing row:**
     - Replaces the item's row.
     - Shows the key read-only in `LiftoffType.index()` style.
     - Shows `LabeledTextField("Name", error = editNameError)` and `LabeledMultiLineTextField("Notes")`.
     - Ends with `InkButton("SAVE")` and `UnderlinedTextButton("Cancel")`.
   - **Empty state:** when there are no active items, show a `LiftoffType.note()` line: "No equipment yet."
   - **Add:**
     - An `UnderlinedTextButton("Add equipment")` reveals an inline form.
     - The form has `LabeledTextField("Key", error = addKeyError)`, `LabeledTextField("Name", error = addNameError)`, `LabeledMultiLineTextField("Notes (optional)")`, `InkButton("ADD")` and `UnderlinedTextButton("Cancel")`.
   - **Show deactivated:**
     - An `UnderlinedTextButton` whose label switches between "Show deactivated" and "Hide deactivated".
     - When on, show a `LiftoffType.sectionHead()`-style "DEACTIVATED" sub-head, then one `InkRuledListRow` per deactivated item with a single `UnderlinedTextButton("Reactivate")` action.
     - When on and there are no deactivated items, show "No deactivated equipment."
   - **Styling:**
     - Use only theme parts: `Paper`, `Ink`, `ButtonShape`, `CardShape`, `LiftoffType`, `InkRuledListRow`, `InkButton`, `UnderlinedTextButton`.
     - No stock M3 tonal surfaces, no `RoundedCornerShape(50)` pills, no `AlertDialog`.

8. **`app/src/test/java/com/liftoff/app/ui/control/EquipmentViewModelTest.kt`**
   - Use `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [34])`.
   - Build an in-memory `LiftoffDatabase` with `allowMainThreadQueries()`, the same setup as `EquipmentDaoTest`.
   - The scope is `CoroutineScope(Dispatchers.Unconfined + job)`; cancel `job` and close the DB in `@After`.
   - Await state with `withTimeout(5_000) { vm.state.first { predicate } }`, the same pattern as `MissionControlViewModelTest.awaitStored`.

## Order of work

1. Create `EquipmentViewModel.kt` (state, validation, actions).
2. Write `EquipmentViewModelTest.kt` and make it pass.
3. Add the optional `actions` slot to `InkRuledListRow`.
4. Make `SectionCard`, `LabeledTextField` and `LabeledMultiLineTextField` `internal`, and create `EquipmentSection.kt`.
5. Add the `equipmentDao` parameter to `MissionControlScreen`, add the EQUIPMENT card, and wire it from `LiftoffShell.kt`.
6. Update `MissionControlScreenTest.kt`: in-memory DAO in `launchScreen`, the placeholder test, the section-head list, and the session-word list.
7. Update `ARCHITECTURE.md`'s milestone table.
8. Run the gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

### `EquipmentViewModelTest.kt`

- **Invalid key pattern.**
  - For `"Bar"`, `"pull up"`, `"kb-24"` and `""`, `submitAdd()` sets `addKeyError`.
  - `observeAll().first()` stays empty.
- **Duplicate against an active item.**
  - Add `barbell`, then try to add `barbell` again.
  - `addKeyError` is set and exactly one row exists.
- **Duplicate against an inactive item.**
  - Add `barbell`, then deactivate it, then try to add `barbell` again.
  - `addKeyError` is set and exactly one row exists, still inactive.
- **Name required on add.**
  - A blank or whitespace-only name sets `addNameError`; nothing is saved.
- **Successful add.**
  - The item appears in `state.active`.
  - The form is cleared and closed.
  - Surrounding whitespace on the key and name is trimmed.
- **Edit.**
  - `startEdit` → change name and notes → `submitEdit()`.
  - `dao.get(id)` has the new name and notes and the original key.
  - `state.active` reflects the change.
- **Name required on edit.**
  - A blank name sets `editNameError`; the stored name is unchanged.
- **Deactivate.**
  - The item leaves `state.active` and appears in `state.deactivated`.
  - `observeAll()` still contains it, with key, name and notes intact.
- **Reactivate.**
  - The item leaves `state.deactivated` and returns to `state.active`, unchanged.
- **Error clears on typing.**
  - After a key error, `setAddKey(...)` clears `addKeyError`.

### `MissionControlScreenTest.kt` (existing Robolectric UI test, updated so it keeps passing)

- `launchScreen` supplies an in-memory `EquipmentDao`.
- The EQUIPMENT head is displayed.
- "Test connection" and "Export" are still absent.
- Every existing settings test passes unchanged.

### Build gate

Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. It must pass, including the existing `EquipmentDaoTest`, `MissionControlViewModelTest`, `LiftoffShellTest` and the theme tests.

## Risky or uncertain areas

1. **Room flow emissions under Robolectric.**
   - Room invalidation runs on its own executor, so tests must wait with `first { predicate }` under a timeout rather than read `state` straight after a write.
   - If emissions are flaky, set `setQueryExecutor`/`setTransactionExecutor` to a direct executor on the in-memory builder.
2. **`LiftoffShellTest` opens Mission Control through the real `LiftoffApplication` container.**
   - That now touches `container.database`, which creates the file-backed Room database under Robolectric.
   - `AppContainerTest` already does this, so it should work. Check that test if the gate fails there.
3. **Changing `InkRuledListRow`.**
   - The slot must default to `null` and render nothing extra when absent, so the theme and preview tests keep passing.
4. **Writes on the shell scope.**
   - Writes intentionally outlive the screen; only the read flow is tied to composition.
   - Do not launch `collect` on the injected scope.

## Decisions

The brief leaves the following behaviors open. Each entry gives the choice, the alternative, and why.

**A. Should the key be auto-normalised (for example, lowercased) as the user types?** *(Added by review)*
- **Choice:** No. Surrounding whitespace is trimmed and nothing else. "Bar" gets the inline error "Key must use only a–z, 0–9 and _".
- **Alternative:** Lowercase the key and replace spaces with `_` silently.
- **Why:**
  - The key is permanent and appears in prompts, so the user should see exactly what is saved.
  - The brief's tests expect an invalid pattern to produce an error, not to be quietly fixed.

**B. Is the name required when editing as well as adding?** *(Added by review)*
- **Choice:** Yes. A blank name on edit shows "Name is required" inline and nothing is saved.
- **Why:**
  - The brief makes name required on add.
  - Allowing an edit to blank it would undo that rule and send an empty name to the coach.

**C. Is surrounding whitespace trimmed from name and notes?** *(Added by review)*
- **Choice:** Yes, on both add and edit.
- **Why:**
  - Whitespace-only names should count as blank.
  - Stray spaces would otherwise reach the prompt line `key — name — notes`.

**D. How is a duplicate against a deactivated item reported?** *(Added by review)*
- **Choice:** The same inline key error, with " by a deactivated item" added. The user then knows to use Show deactivated → Reactivate instead of creating a new item.
- **Why:** The brief rejects duplicates against inactive items. Without the hint, the user can't see why a key that isn't in the list is "taken".

**E. Can a deactivated item be edited?** *(Added by review)*
- **Choice:** No. Deactivated rows have only Reactivate; after reactivating, the item can be edited.
- **Why:** The brief gives deactivated items only a Reactivate action. This keeps the hidden list simple.

**F. What happens to the add form after a successful add, and to unsaved drafts?** *(Added by review)*
- **Choice:**
  - A successful add clears and closes the form.
  - Cancel discards the draft.
  - Only one item is edited at a time; starting Edit on another row discards the first row's unsaved changes.
  - Drafts are not kept after leaving Mission Control.
- **Why:** This is the simplest behavior with no ambiguous half-saved state, and it matches how the settings fields behave.

**G. Does the "Show deactivated" toggle persist?** *(Added by review)*
- **Choice:** No. It starts off each time Mission Control opens.
- **Why:** It is a view toggle, not a setting, and nothing in §8 calls for storing it.

**1. How is the add form presented?**
- **Choice:** An inline expandable section within the Equipment section card, below the active list. Tapping "Add equipment" reveals the key, name and notes fields and an Add button; Cancel or a successful add hides it again.
- **Alternative:** A full-screen dialog or bottom sheet.
- **Why:**
  - Mission Control uses inline sections throughout and no dialogs for settings.
  - The brief says "Use the same design parts as the rest of Mission Control", and the existing `LabeledTextField` and `SectionCard` patterns are inline, not dialog-based.

**2. How is editing presented?**
- **Choice:** Each active row has an Edit button. It replaces the row's display with inline fields: the key read-only, name and notes editable, plus Save and Cancel buttons.
- **Alternative:** A separate edit screen or dialog.
- **Why:**
  - This matches the inline editing pattern of Mission Control settings.
  - The brief says "Edit: change an item's name and notes"; a settings screen doesn't need a separate navigation target for that.

**3. Where is the deactivated items toggle?**
- **Choice:** A text button at the bottom of the Equipment section card, labelled "Show deactivated", that toggles visibility. Deactivated items appear below the active items.
- **Alternative:** A separate tab or a second section card.
- **Why:**
  - The brief says "A way to show deactivated items", and a simple toggle is the lightest option that keeps everything in one place.
  - Users expect to find deactivated equipment near where they deactivated it.

**4. What happens when an item is deactivated?**
- **Choice:** The item disappears from the active list at once. It is visible again only after turning on "Show deactivated". No confirmation dialog is shown.
- **Alternative:** A confirmation dialog before deactivation.
- **Why:**
  - Mission Control settings don't use confirmation dialogs: pattern chips, unit changes and the rest all apply immediately.
  - Deactivation is fully reversible.

**5. Can the equipment key be edited after creation?**
- **Choice:** No. The key is fixed once created and shown read-only in edit mode.
- **Why:** The brief explicitly says "The key is fixed once created, because history and prompts refer to it." This is decided by the brief.

**6. How are validation errors displayed for the add form?**
- **Choice:** Inline error text below each field, matching the existing `LabeledTextField` error pattern, plus a summary error above the Save button for key-level issues (duplicate key).
- **Alternative:** A toast or snackbar.
- **Why:**
  - The brief says the key "gets an inline error".
  - The existing Mission Control already shows errors inline below fields (`portError`, `sortieLengthError` and so on).

The brief settles every other behavior the plan touches: add/edit/deactivate/reactivate mechanics, key validation rules, live updates from Room, theme usage, and test requirements.
