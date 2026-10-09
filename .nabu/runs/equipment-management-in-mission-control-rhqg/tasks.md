# Tasks: Equipment Management in Mission Control

- [x] Create EquipmentViewModel.kt and EquipmentViewModelTest.kt
  A plain-Kotlin state holder (no ViewModel superclass) with all add/edit/deactivate/reactivate logic, validation, and a combined Flow of active + deactivated lists. Tests cover invalid key pattern, duplicate against active and inactive items, name required on add and edit, successful add, edit, deactivate, reactivate, error clears on typing, and whitespace trimming — using Robolectric with an in-memory LiftoffDatabase.
  Pass when `EquipmentViewModelTest` runs green under `bash gradlew.sh :app:testDebugUnitTest`.

- [x] Add the actions slot to InkRuledListRow.kt
  Add an optional `actions: (@Composable RowScope.() -> Unit)? = null` parameter that renders a right-aligned row between the text content and the 1 dp ink rule when present, and does nothing when absent. Existing callers and previews must be unchanged.
  Pass when the build compiles and existing theme/preview tests still pass (`:app:testDebugUnitTest`).

- [x] Make SectionCard, LabeledTextField and LabeledMultiLineTextField internal; create EquipmentSection.kt
  Change `SectionCard`, `LabeledTextField` and `LabeledMultiLineTextField` from `private` to `internal` in MissionControlScreen.kt. Create `EquipmentSection.kt` with the active-item rows (with Edit/Deactivate buttons), inline add form, inline edit form, Show/Hide deactivated toggle, and empty-state messages — using only theme composables (no stock M3).
  Pass when the build compiles (`:app:assembleDebug`).

- [x] Wire equipment into MissionControlScreen and LiftoffShell; update MissionControlScreenTest
  Add `equipmentDao: EquipmentDao` to `MissionControlScreen`, create `EquipmentViewModel(equipmentDao, scope)` with `remember`, collect its state, and add an EQUIPMENT SectionCard after "OBJECTIVES AND CONSTRAINTS". In `LiftoffShell.kt`, read the container once and pass `equipmentDao = container.database.equipmentDao()`. Update `MissionControlScreenTest`: build an in-memory LiftoffDatabase in `launchScreen` and close it in `tearDown`; rename `showsNoEquipmentOrUnbuiltPlaceholders` to `showsNoUnbuiltPlaceholders` and remove the "Equipment" assertion; add "EQUIPMENT" to the head list in `showsEverySectionHeadInOrder`; add equipment labels to the text list in `neverSaysSession`.
  Pass when all tests pass (`:app:testDebugUnitTest`).

- [x] Update ARCHITECTURE.md and run the build gate
  Set the M1 milestone row's status to Done with note "test connection and export/import come later". Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` end-to-end and confirm it passes, including all existing tests (EquipmentDaoTest, MissionControlViewModelTest, LiftoffShellTest, theme tests).
  Pass when the gate command exits with status 0.

## Blockers from the final review

- [ ] Make the equipment list update live from Room, and add a test that proves it
  The brief says "The list updates live from Room." `EquipmentViewModel` (app/src/main/java/com/liftoff/app/ui/control/EquipmentViewModel.kt) does not do this. It holds a `MutableStateFlow` and reads `dao.observeAll().first()` once: in `init` (lines 39-48) and again in `reloadState()` after each of its own writes. Nothing ever collects the Room flow, so a change from anywhere other than this view model never reaches the screen. That includes another view model, the export/import that comes later, or any direct DAO write. If the initial load in `init` throws, the exception is swallowed and the section shows "No equipment yet." for good. The plan's design (`combine(dao.observeAll(), _form)` collected with `collectAsState`) was dropped. verify.sh cannot catch this: every test triggers its writes through the view model, which then reloads by hand. Add a test that writes through `dao` directly, for example `dao.add(...)` or `dao.deactivate(...)`, and waits for `vm.state` to show the change. Name it in verify.sh.
- [ ] Clear the add form's fields after a successful add
  In `submitAdd()` (EquipmentViewModel.kt lines 105-109), a successful insert only sets `adding = false`. `addKey`, `addName` and `addNotes` keep their old values. So the next tap on "Add equipment" opens the form pre-filled with the item just saved, and pressing ADD gives a duplicate-key error. Plan Decision F, and the verify.sh comment on `successfulAdd`, say a successful add "clears and closes the form". The `successfulAdd` test only checks `!adding`, so verify.sh passes without proving this. Reset the three fields and both errors on success, and assert that in `successfulAdd`.
