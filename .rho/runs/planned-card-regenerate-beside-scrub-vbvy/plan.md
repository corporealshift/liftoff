# Plan

## Approach

Everything the card needs already exists: `LaunchpadState.Planned` carries `generationStatus` and `regenerateAvailable`, `LaunchpadViewModel.regenerate()` already accepts a Planned card, and `LaunchpadContent` already passes `onRegenerate` to `ActionButtons` for the PENDING arm. So this is one screen file: `PlannedActions` draws Regenerate and Scrub as two weighted `UnderlinedTextButton`s in a Row when `regenerateAvailable` is true and Scrub alone otherwise, and `PlannedContent` draws 'Coach is re-planning…' in `LiftoffType.note()`/Muted above the Flight Plan while `generationStatus` is `RePlanning`. The pair's visibility depends only on `regenerateAvailable`, so Regenerate stays on screen and tappable while a re-planning is open. A PLANNED card's status is only ever `RePlanning` or `Idle` (`derivePlannedSortieGenerationStatus`), so the line needs one arm, and the PENDING card's status line is the shape to mirror. Nothing in the domain, the derivation, the view model or the theme changes, and the PENDING card is left alone.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — `PlannedActions` takes the Planned state and `onRegenerate` and draws the pair or Scrub alone; `ActionButtons`' Planned arm passes them; `PlannedContent` draws the re-planning line above `FlightPlanSection`. What each of these holds today is in the map.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPlannedActionsTest.kt` — new. Three cases, on the harness `LaunchpadDraftStatusTest` defines and `LaunchpadPendingStatusTest` carries forward (see the map for its parts and for how to reach each card).

## Order

1. `PlannedActions` + `ActionButtons`: the pair under Launch, gated on `regenerateAvailable`, Scrub alone when it is false. `:app:assembleDebug` green; no test asserts it yet.
2. `PlannedContent`: the `RePlanning` line above the Flight Plan, plan and Launch untouched. `:app:assembleDebug` green.
3. `LaunchpadPlannedActionsTest`: the PLANNED-lift case (REGENERATE + SCRUB, no line) and the run-off case (SCRUB, no REGENERATE). Run the class cold.
4. Add the tap case: REGENERATE queues exactly one id, the line appears, LAUNCH and the plan's title stay. Run the class cold.
5. Prove each case bites with one named mutation of the screen (drop the `regenerateAvailable` gate; drop the status check on the line; leave `PlannedActions` ignoring `onRegenerate`), then restore the file and run the whole gate once.

## Testing

All three are Robolectric Compose in `LaunchpadPlannedActionsTest`, rendering `LaunchpadContent` in `LiftoffTheme` on a real view model, Room and SettingsStore — no state built by hand, no WorkManager, every wait bounded at 5 s.

- A PLANNED lift card shows `REGENERATE` and `SCRUB` both displayed and enabled, side by side, and no 'Coach is re-planning…'. Render-only.
- Tapping `REGENERATE` hands the recording `queueGeneration` exactly one id, read back as a fresh QUEUED FLIGHT_PLAN row on this sortie, and the card then shows 'Coach is re-planning…' with `LAUNCH` and the plan's title `Push + core` still on screen and the plan still in the table. Interaction through the real view model.
- The run-off PLANNED card (pattern `RLL`, `generateRunPlans` off) shows `SCRUB` and no `REGENERATE`, and no re-planning line. Render-only.
- Nothing else changes: the existing Launchpad Compose classes and `LiftoffShellTest` pass untouched, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.

## Risks

- `continueWithoutOutline` leaves a QUEUED FLIGHT_PLAN row on the lift sortie and `storePlan` does not close it, so a freshly-stored PLANNED card is `RePlanning`, not `Idle`. The lift fixture must close that row — `generationDao.markSucceeded(row.id, …)`, the move `LaunchpadSortieStatusTest` makes — before it asserts there is no re-planning line; otherwise case 1 fails on the very line it is meant to prove absent.
- `regenerate()` reads `state.value` and returns null on a card that has not been derived yet, which would make the tap case vacuous. Await `REGENERATE` on screen before clicking, and assert the queued id is read back as a real QUEUED row on this sortie rather than a number.
- With the activating row already SUCCEEDED, `regenerateFlightPlan` supersedes nothing, so `cancelGeneration` is handed nothing. Assert `cancelledIds` is empty rather than leaving it unobserved, so the case is not proved by a cancel that never happened.
- The new test never clicks `SCRUB`: that sets `viewModel.scrubbing` and opens `ScrubDialog`, and a Dialog-backed control is the shape that hangs under Robolectric here. Only `REGENERATE` is clicked.
- Two weighted buttons in one Row are the same height as today's single full-width Scrub, and the re-planning line goes in the scrollable body, so no existing class should lose sight of a control. If one does, give that class `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")` and say so in the PR.
- The gate can hang once for about 5 minutes: run it detached with its own log and `EXIT=` sentinel, and re-run the identical tree once before treating a hang as a failure.

## Decisions

**Order of the two buttons: Regenerate on the left, Scrub on the right.** The brief names them in that order and the mockup draws them in that order, so the plan keeps it. The alternative — Scrub first — would put the destructive action in the position users read first, and would not match `design/screens/launchpad.html`.

**Tapping Regenerate a second time while the coach is already re-planning is allowed, and is handled by the rule that already exists.** The brief says to keep Regenerate available during re-planning, so the plan does not dim or hide it and adds no new guard: the second request goes through `LaunchpadViewModel.regenerate()` and the existing supersede/cancel behaviour decides the outcome, exactly as it does today from the PENDING card. The alternative — disabling the button while `RePlanning` — would look like the app is busy and would stop a user changing their mind mid-generation, which is the case the supersede rule was built for.

**The re-planning line is driven only by `generationStatus`, not by `regenerateAvailable`.** If Mission Control's host or token is cleared while a re-planning generation is open, the card shows 'Coach is re-planning…' with Scrub alone and no Regenerate. The alternative — hiding the line whenever Regenerate is hidden — would claim nothing is happening while a generation is still running. The line is a fact about the generation; the button is a fact about whether a new one can be asked for.
