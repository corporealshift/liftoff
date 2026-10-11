# Tasks for In-Flight Checklist Screen

- [x] Add state fields to InFlightState.kt
  - `Ready.weightUnit: WeightUnit` and `SetRow.plannedWeightLabel: String`, `plannedCountLabel: String`. Fill them in `deriveInFlight`. No existing test constructs `Ready` or `SetRow`, so this is additive-only. Existing tests (`InFlightStatesTest`, `InFlightViewModelTest`) must pass unchanged.
  - Commit message: "feat(inflight): add weightUnit and planned labels to InFlightState"

- [x] Rewrite InFlightScreen.kt scaffold, header, cards and footer
  - Replace the placeholder body with the full checklist UI per Steps 1–7 of the plan: holder wiring via `InFlightViewModel`, teal header with eyebrow/title/counter, progress bar, done/skipped collapsed rows, full cards (active/upcoming/expanded), set rows with checkboxes and deviation display, stepper dialog, and bottom Back button. Keep the `InFlightScreen` signature unchanged.
  - Commit message: "feat(inflight): implement checklist screen UI"

- [x] Write InFlightScreenTest tests and pass build gate
  - Write the two new Robolectric Compose tests required by verify.sh:
    - `seededLiftPlanShowsHeaderProgressAndCards` — asserts header counter, collapsed done row "3/3 LANDED", active card uppercase name, upcoming card uppercase name on a seeded fixture.
    - `checkingASetMarksItDoneInRoomAndUpdatesCounter` — taps check box for SET 1, waits for counter "00/07" → "01/07", asserts Room has the set as DONE with actuals equal to planned values.
  - If existing tests fail due to async Room flow, replace `waitForIdle()` with the permitted `waitUntil` on visible text (Step 8 of plan).
  - Verify with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.
  - Commit message: "test(inflight): add Compose tests and fix wait for async state"

## Blockers from the final review

- [x] Make the '+ Add set', 'Skip' and 'Unskip' buttons in the card footer work
  InFlightScreen.kt, CardFooter/FooterCell: the caller sets `.clickable { vm.addSet(...) }` (or the skip/unskip action) on the modifier. FooterCell then adds a second `.clickable {}` to it when `enabled` is true. When two clickables are chained on one node, the inner (later) one takes the tap and consumes it, so the real action never runs. In the app, tapping '+ Add set', 'Skip' or 'Unskip' does nothing. That also breaks the mis-tap fix the brief asks for: you can't undo a skip from an expanded skipped card. Give each footer cell a single clickable that runs the action. Add a Compose test in InFlightScreenTest: tap '+ Add set' on the active card and check that Room has one more set and the counter total goes up; tap 'Skip' and then 'Unskip' on a card and check the exercise's `skipped` flag in Room. Note that FooterCell upper-cases the labels, so the tests must look for the rendered text.
- [x] Make the Back button work on the no-checklist and loading screens
  InFlightScreen.kt: LoadingContent() and NoChecklistContent() both call `Footer(onBack = {})`, so they ignore the screen's `onBack`. On a sortie with no checklist (for example a RUN sortie opened from the Launchpad), Back does nothing. Pass `onBack` through to both footers. A test in InFlightScreenTest would show it: use a no-plan sortie, click 'Back' and assert the callback ran.
- [x] Style the header counter as the brief and mockup ask
  InFlightScreen.kt, ReadyHeader: the counter is one Text, `"%02d/%02d\nSETS"`, all in Cream in the headerTitle style. The brief asks for the done count ('05') large in Mustard, '/14' smaller in Cream, and 'SETS' in small Cream type under it. The plan gives the sizes: 44 sp, 22 sp and 13 sp. Done-when 1 needs the header 'as in the mockup', so this is not met. Build it from an AnnotatedString or separate Texts with those colours and sizes. Keep the tests able to find the counter text, and update their exact-text matchers if the node text changes.
- [ ] Fill the stepper from the logged values when editing a bodyweight done set
  InFlightScreen.kt, StepperDialog: the weight starts as `row.actualWeight ?: row.plannedWeight ?: 0.0`, and reps and seconds use the same fallback. For a DONE set, the stored null actual weight means bodyweight. Say a set planned at 35 LB was saved as BW. When it is reopened to fix the reps, the stepper shows 35 LB, and Save quietly writes 35 back into Room. For a DONE set, start from the actual values only (null weight shows as 0/BW), as the brief's 0-means-bodyweight rule and plan Step 6 say. Use the planned values only for OPEN and SKIPPED sets.
