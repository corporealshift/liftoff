# Plan

## Approach

Everything the card needs already exists: `LaunchpadState.Planned` carries `generationStatus` and `regenerateAvailable`, `LaunchpadViewModel.regenerate()` already accepts a Planned card, and `LaunchpadContent` already passes `onRegenerate` to `ActionButtons` for the PENDING arm. So this is one screen file. When `regenerateAvailable` is true, `PlannedActions` draws Regenerate and Scrub as two `UnderlinedTextButton`s in a Row, each with weight 1 and 24dp between them as the mockup draws. When it is false, it draws Scrub alone. `PlannedContent` draws 'Coach is re-planning…' in `LiftoffType.note()`/Muted above the Flight Plan while `generationStatus` is `RePlanning`. Only `regenerateAvailable` decides whether the pair shows, so Regenerate stays on screen and tappable while a re-planning is open. A PLANNED card's status is only ever `RePlanning` or `Idle` (`derivePlannedSortieGenerationStatus`), so the line needs one arm. The PENDING card's status line is the shape to copy. Nothing changes in the domain, the derivation, the view model or the theme, and the PENDING card is left alone.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`: `PlannedActions` takes the Planned state and `onRegenerate` and draws either the pair or Scrub alone. `ActionButtons`' Planned arm passes them. `PlannedContent` draws the re-planning line above `FlightPlanSection`. The map says what each of these holds today.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPlannedActionsTest.kt`: new. Three cases, on the harness that `LaunchpadDraftStatusTest` defines and `LaunchpadPendingStatusTest` reuses. The map lists its parts and how to reach each card.

## Order

1. `PlannedActions` + `ActionButtons`: the pair under Launch, shown only when `regenerateAvailable` is true, and Scrub alone when it is false. `:app:assembleDebug` green; no test checks it yet.
2. `PlannedContent`: the `RePlanning` line above the Flight Plan, with the plan and Launch untouched. `:app:assembleDebug` green.
3. `LaunchpadPlannedActionsTest`: the PLANNED-lift case (REGENERATE + SCRUB, no line) and the run-off case (SCRUB, no REGENERATE). Run the class cold.
4. Add the tap case: REGENERATE queues exactly one id, the line appears, and LAUNCH and the plan's title stay. Run the class cold.
5. Check that each case fails when the screen is wrong, with one named break each: remove the `regenerateAvailable` check; remove the status check on the line; leave `PlannedActions` ignoring `onRegenerate`. Then restore the file and run the whole gate once.

## Testing

All three cases are Robolectric Compose tests in `LaunchpadPlannedActionsTest`. They render `LaunchpadContent` in `LiftoffTheme` on a real view model, Room and SettingsStore. No state is built by hand, WorkManager is never touched, and every wait is capped at 5 s.

- A PLANNED lift card shows `REGENERATE` and `SCRUB`, both displayed and enabled, side by side, and no 'Coach is re-planning…'. Render only.
- Tapping `REGENERATE` hands the recording `queueGeneration` exactly one id. The test reads that id back as a fresh QUEUED FLIGHT_PLAN row on this sortie. The card then shows 'Coach is re-planning…', with `LAUNCH` and the plan's title `Push + core` still on screen and the plan still in the table. This goes through the real view model.
- The run-off PLANNED card (pattern `RLL`, `generateRunPlans` off) shows `SCRUB`, no `REGENERATE` and no re-planning line. Render only.
- Nothing else changes: the existing Launchpad Compose classes and `LiftoffShellTest` pass untouched, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.

## Risks

- `continueWithoutOutline` leaves a QUEUED FLIGHT_PLAN row on the lift sortie, and `storePlan` does not close it. So a card straight after `storePlan` is `RePlanning`, not `Idle`. The lift fixture must close that row with `generationDao.markSucceeded(row.id, …)`, as `LaunchpadSortieStatusTest` does, before it asserts there is no re-planning line. Otherwise case 1 fails on the very line it is meant to show is absent.
- `regenerate()` reads `state.value` and returns null if the card has not been built yet, which would make the tap case pass without testing anything. Wait for `REGENERATE` on screen before clicking, and check that the queued id matches a real QUEUED row on this sortie, not just that some number was recorded.
- Once that first row is SUCCEEDED, `regenerateFlightPlan` has no open row to replace, so `cancelGeneration` is called with nothing. Assert that `cancelledIds` is empty, so the case cannot pass because of a cancel.
- The new test never clicks `SCRUB`. That sets `viewModel.scrubbing` and opens `ScrubDialog`, and controls that open a Dialog are what hang under Robolectric here. Only `REGENERATE` is clicked.
- Two weighted buttons in one Row are the same height as today's single full-width Scrub, and the re-planning line goes in the scrollable body, so no existing class should lose sight of a control. If one does, give that class `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")` and say so in the PR.
- The gate can hang once for about 5 minutes. Run it detached with its own log and an `EXIT=` marker line, and re-run the same tree once before treating a hang as a failure.

## Decisions

**Added by review: 24dp between Regenerate and Scrub, and 12dp between Launch and the Row.** The brief asks for equal weights but leaves the spacing open. `design/screens/launchpad.html` puts `gap: 24px` between the two buttons, so the Row uses `Arrangement.spacedBy(24.dp)`. The space under Launch stays at today's 12dp, so the Scrub-alone card does not move.

**Order of the two buttons: Regenerate on the left, Scrub on the right.** The brief and the mockup both name them in that order, so the plan keeps it. Putting Scrub first would place the destructive action where users read first, and it would not match `design/screens/launchpad.html`.

**Tapping Regenerate again while the coach is already re-planning is allowed, and the existing rule handles it.** The brief says to keep Regenerate available during re-planning, so the plan does not dim or hide it and adds no new guard. The second request goes through `LaunchpadViewModel.regenerate()`, and the existing replace-and-cancel rule decides the outcome, exactly as it does today from the PENDING card. Disabling the button during `RePlanning` would make the app look busy and would stop a user changing their mind mid-generation, which is the case that rule was built for.

**The re-planning line depends only on `generationStatus`, not on `regenerateAvailable`.** If Mission Control's host or token is cleared while a re-planning generation is open, the card shows 'Coach is re-planning…' with Scrub alone and no Regenerate. Hiding the line whenever Regenerate is hidden would claim nothing is happening while a generation is still running. The line reports the generation; the button reports whether a new one can be asked for.
