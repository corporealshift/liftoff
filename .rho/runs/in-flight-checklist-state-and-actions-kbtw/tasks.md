- [x] Add the checklist state model and derivation function
  Make `formatWeight` internal in PlanFormat.kt. Write InFlightState.kt with all sealed types, data classes, and the pure deriveInFlight() function including weight/count label helpers. Write InFlightStatesTest.kt with four tests: initialState, kgUnitLabel, titleFallbacks, noChecklist — each exercising deriveInFlight on fresh getPlan reads against the fixture (3-exercise LIFT sortie).
  Done when the file compiles and all four test methods pass.

- [x] Add in-flight action methods to FlightPlanDao
  Add protected reads: getSet, getPlannedExercise, getLastSet. Add skipOpenSets and reopenSkippedSets helpers. Add clearSkip private helper. Add public @Transaction suspend methods: checkSet, uncheckSet, saveSetEdit, skipSet, reopenSet, skipExercise, unskipExercise, addSet — each following the plan's write table and the "do nothing if not found" rule. Write FlightPlanDaoTest tests for checkSet, skipExercise, unskipExercise, and addSet pre-fill behaviour.
  Done when all new DAO methods compile and their tests pass.

- [x] Add the flow, state holder, and ViewModel tests
  Write InFlightStates.kt (observe method combining Room changes with settings) and InFlightViewModel.kt (plain StateFlow holder). Write InFlightViewModelTest.kt with eight tests: checkAndUncheck, editWithDeviation, finishExercise, skipAndReopenSet, skipAndUnskipExercise, addSet, freshReadEqualsFlow, noPlan — using a cancellable CoroutineScope, DataStore-backed SettingsStore, and withTimeout waiting.
  Done when all eight test methods pass.
