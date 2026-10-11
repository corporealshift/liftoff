# Tasks for In-Flight Checklist Screen

- [x] Add state fields to InFlightState.kt
  - `Ready.weightUnit: WeightUnit` and `SetRow.plannedWeightLabel: String`, `plannedCountLabel: String`. Fill them in `deriveInFlight`. No existing test constructs `Ready` or `SetRow`, so this is additive-only. Existing tests (`InFlightStatesTest`, `InFlightViewModelTest`) must pass unchanged.
  - Commit message: "feat(inflight): add weightUnit and planned labels to InFlightState"

- [x] Rewrite InFlightScreen.kt scaffold, header, cards and footer
  - Replace the placeholder body with the full checklist UI per Steps 1–7 of the plan: holder wiring via `InFlightViewModel`, teal header with eyebrow/title/counter, progress bar, done/skipped collapsed rows, full cards (active/upcoming/expanded), set rows with checkboxes and deviation display, stepper dialog, and bottom Back button. Keep the `InFlightScreen` signature unchanged.
  - Commit message: "feat(inflight): implement checklist screen UI"

- [ ] Write InFlightScreenTest tests and pass build gate
  - Write the two new Robolectric Compose tests required by verify.sh:
    - `seededLiftPlanShowsHeaderProgressAndCards` — asserts header counter, collapsed done row "3/3 LANDED", active card uppercase name, upcoming card uppercase name on a seeded fixture.
    - `checkingASetMarksItDoneInRoomAndUpdatesCounter` — taps check box for SET 1, waits for counter "00/07" → "01/07", asserts Room has the set as DONE with actuals equal to planned values.
  - If existing tests fail due to async Room flow, replace `waitForIdle()` with the permitted `waitUntil` on visible text (Step 8 of plan).
  - Verify with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.
  - Commit message: "test(inflight): add Compose tests and fix wait for async state"
