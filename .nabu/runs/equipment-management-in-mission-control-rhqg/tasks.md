# Tasks: Equipment Management in Mission Control

- [ ] Create EquipmentViewModel.kt and EquipmentViewModelTest.kt
  A plain-Kotlin state holder (no ViewModel superclass) with all add/edit/deactivate/reactivate logic, validation, and a combined Flow of active + deactivated lists. Tests cover invalid key pattern, duplicate against active and inactive items, name required on add and edit, successful add, edit, deactivate, reactivate, error clears on typing, and whitespace trimming — using Robolectric with an in-memory LiftoffDatabase.
  Pass when `EquipmentViewModelTest` runs green under `bash gradlew.sh :app:testDebugUnitTest`.

- [ ] Add the actions slot to InkRuledListRow.kt
  Add an optional `actions: (@Composable RowScope.() -> Unit)? = null` parameter that renders a right-aligned row between the text content and the 1 dp ink rule when present, and does nothing when absent. Existing callers and previews must be unchanged.
  Pass when the build compiles and existing theme/preview tests still pass (`:app:testDebugUnitTest`).

- [ ] Make SectionCard, LabeledTextField and LabeledMultiLineTextField internal; create EquipmentSection.kt
  Change `SectionCard`, `LabeledTextField` and `LabeledMultiLineTextField` from `private` to `internal` in MissionControlScreen.kt. Create `EquipmentSection.kt` with the active-item rows (with Edit/Deactivate buttons), inline add form, inline edit form, Show/Hide deactivated toggle, and empty-state messages — using only theme composables (no stock M3).
  Pass when the build compiles (`:app:assembleDebug`).

- [ ] Wire equipment into MissionControlScreen and LiftoffShell; update MissionControlScreenTest
  Add `equipmentDao: EquipmentDao` to `MissionControlScreen`, create `EquipmentViewModel(equipmentDao, scope)` with `remember`, collect its state, and add an EQUIPMENT SectionCard after "OBJECTIVES AND CONSTRAINTS". In `LiftoffShell.kt`, read the container once and pass `equipmentDao = container.database.equipmentDao()`. Update `MissionControlScreenTest`: build an in-memory LiftoffDatabase in `launchScreen` and close it in `tearDown`; rename `showsNoEquipmentOrUnbuiltPlaceholders` to `showsNoUnbuiltPlaceholders` and remove the "Equipment" assertion; add "EQUIPMENT" to the head list in `showsEverySectionHeadInOrder`; add equipment labels to the text list in `neverSaysSession`.
  Pass when all tests pass (`:app:testDebugUnitTest`).

- [ ] Update ARCHITECTURE.md and run the build gate
  Set the M1 milestone row's status to Done with note "test connection and export/import come later". Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` end-to-end and confirm it passes, including all existing tests (EquipmentDaoTest, MissionControlViewModelTest, LiftoffShellTest, theme tests).
  Pass when the gate command exits with status 0.
