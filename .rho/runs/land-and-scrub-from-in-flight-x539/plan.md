# Land and scrub from In-Flight — plan

## Approach

Let the owner finish a sortie from the In-Flight footer. A 64 dp ink **Land** button (flag glyph, red offset shadow) replaces the Back text button, and an underlined **Scrub** action sits under it. Both drive `MissionManager` through `InFlightViewModel`, which already holds every other In-Flight write. Both end by calling the existing `onBack`, so the shell returns to the Launchpad. `ShellNav.back()` already does exactly that, so the shell needs no change.

Why this shape fits the codebase:

- **The lifecycle work is already done.**
  - `MissionManager.land(sortieId)` (data/MissionManager.kt:114) checks the sortie is IN_FLIGHT, sets LANDED with `clock.millis()`, and calls `flightPlanDao().markOpenSetsNotDone(sortieId)`.
  - It then calls `advance(...)`, which prepares the next sortie and closes the Mission after the last one. A run with generation off gets `simpleRunPlan`: title "Run", `source = SIMPLE_RUN`.
  - `scrub(sortieId, reason)` (:85) keeps the checked sets and advances.
  - Nothing new is needed in `data/` or `domain/` beyond the blank-reason fix the brief names.
- **"Recorded as not done" already exists.** `SetStatus` is `OPEN, DONE, SKIPPED` (data/Enums.kt:7), and `markOpenSetsNotDone` moves OPEN to SKIPPED. No new status and no Room schema change.
- **The open-set count is a one-line addition to the derived state.**
  - `deriveInFlight` already builds `allProgress: List<SetStatus>` (ui/inflight/InFlightState.kt:83, 205), so `openCount` is `allProgress.count { it == SetStatus.OPEN }`.
  - Skipping an exercise already marks its open sets SKIPPED (`skipOpenSets`, FlightPlanDao.kt:273), so skipped exercises add nothing.
  - So the count is exactly "sets that landing would record as not done".
- **The themed parts exist.**
  - `InkButton` (ui/theme/Buttons.kt:92) is already ink with a red offset shadow, 64 dp by default, with a cream label and icon.
  - That matches the mockup's footer (design/screens/in-flight.html:110-115): 3 px ink rule, cream footer, full-width 64 px ink button, 4 px red shadow, flag glyph, `LAND`.
  - `LiftoffIcons.flag()`, `UnderlinedTextButton`, and the Paper-card-in-`Dialog` style used by `StepperDialog` (InFlightScreen.kt:746) and `NoteDialog.kt` can all be reused as they are.
- **The scrub flow has a precedent to mirror.**
  - `LaunchpadViewModel` already holds `scrubbing` / `scrubReason` and calls `manager.scrub(..., reason.takeIf { it.isNotBlank() })` (ui/launchpad/LaunchpadViewModel.kt:112-143).
  - The In-Flight view-model gets the same shape. The new dialog is built so the Launchpad can adopt it later.
- **Tests stay plain Kotlin.**
  - They use Robolectric and in-memory Room against the view-model, the way `InFlightViewModelTest` and `MissionManagerTest` already work.
  - The brief makes Compose tests that open a Dialog optional. This repo also has a known hang when a text field sits inside a Compose `Dialog` under Robolectric; `NoteDialog.kt` was split into shell + content for that reason.
  - So the confirmation logic is exposed as a pure helper on the state instead.

## Files and parts involved

**Changed**

| File | What changes |
|---|---|
| `app/src/main/java/com/liftoff/app/domain/Sorties.kt` | In `scrub` (:49-53), change `reason.isNullOrEmpty()` to `reason.isNullOrBlank()`, so a whitespace-only reason is stored as null. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt` | `Ready` gains `val openCount: Int`, set by `deriveInFlight` from `allProgress`.<br>`Ready` and `NoChecklist` both gain `val sortieNumber: Int` (`sortie.index + 1`, already computed in `deriveInFlight`) so the dialogs can title themselves. Both classes are constructed only in `deriveInFlight`, so no other call site changes.<br>Add a top-level `fun needsLandConfirmation(state: InFlightState): Boolean` that returns `state is Ready && state.openCount > 0`. `Loading` and `NoChecklist` (a run) never confirm. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` | New trailing constructor param `missionManager: MissionManager = MissionManager(db, settings)`. The default keeps the existing test call sites compiling; the screen passes `container.missionManager`.<br>A plain `busy` flag set **synchronously** before `scope.launch` and reset in `finally`. While it is set, `land` / `scrub` return at once. Because it resets after a call finishes, a later call reaches the manager and is rejected there.<br>A `MutableStateFlow<String?>` message plus `clearMessage()`.<br>`land(onDone)` and `scrub(reason, onDone)`, each `scope.launch { try { manager… ; onDone() } catch (CancellationException) rethrow; catch (Exception) → message }`. `onDone` is called only on success.<br>Before the reason reaches the manager it is trimmed and blank becomes null, the same normalisation `normalizeNote` applies to notes. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | Only the view-model construction, the footer and the dialog hosts:<br>• The `InFlightViewModel(...)` at :49 also passes `container.missionManager`.<br>• `Footer` (:871-885) becomes Land + Scrub for `Ready` and `NoChecklist` (call sites :207, :268), with the rejection message rendered above Land.<br>• Two new `remember` dialog flags sit beside `editingSetId` / `noteTarget` (:52-54).<br>• `LoadingContent`'s footer (:167) keeps the old Back footer under a new name. |
| `app/src/test/java/com/liftoff/app/domain/SortiesTest.kt` | Add a blank/whitespace reason test. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightStatesTest.kt` | Add `openCount`, `sortieNumber` and `needsLandConfirmation` cases. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt` | Add a footer test, and retarget `noChecklistBackButtonTriggersCallback` (:332-382), which taps the Back button this brief removes (see the test table). |

**New**

- `app/src/main/java/com/liftoff/app/ui/inflight/LandConfirmDialog.kt`
  - A themed Paper card in a `Dialog`, with the same shell as `StepperDialog`/`NoteDialogContent`: Paper background, 2 dp Ink `CardShape` border, 16 dp padding, `LiftoffType.cardTitle()` title, and a `PrimaryButton` + `UnderlinedTextButton` row.
  - Takes `openCount` and `sortieNumber`, plus `onConfirm` / `onDismiss`. No view-model inside it.
- `app/src/main/java/com/liftoff/app/ui/inflight/ScrubDialog.kt`
  - The same shell, `internal`. Takes `sortieNumber`, `onConfirm(reason: String)` and `onDismiss`.
  - The reason text is held in the dialog's own `remember` state.
  - The reason field is `LabeledTextField` (ui/control/MissionControlScreen.kt:231). It is `internal` in the same module and already reused by `NoteDialog.kt`'s `LabeledMultiLineTextField`, so this is the Mission Control field, not a stock M3 field.
  - No view-model inside it, so the Launchpad can reuse it later.
  - Its name doesn't clash with `LaunchpadScreen.kt`'s `private ScrubDialog`, which lives in a different package.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` — Robolectric tests for land and scrub through the view-model against in-memory Room.

**Not touched:** `MissionManager`, any DAO or Room entity, `ShellNav` / `LiftoffShell`, `LaunchpadScreen` / `LaunchpadViewModel` (including its private `ScrubDialog`), `NoteDialog.kt`, `ui/theme`, `coach/`, ARCHITECTURE.md.

## Order of work

Each step ends with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` green and its own commit (`area: lowercase summary`, named files staged only).

1. **Domain blank-reason fix.** `Sorties.kt` `isNullOrBlank()` plus a `SortiesTest` case. This is the smallest step, it stands alone, and the rest of the plan relies on it.
2. **Open-set count, sortie number and the confirmation helper.** `Ready.openCount`, `sortieNumber` on both loaded states, `deriveInFlight`, `needsLandConfirmation`, and the `InFlightStatesTest` cases. Pure Kotlin, no UI.
3. **View-model land and scrub.**
   - Add the `missionManager` param, the `busy` guard, the message flow, `land(onDone)` and `scrub(reason, onDone)`.
   - Add `InFlightLandScrubTest`, covering every item in the brief's Done-when list.
   - `InFlightScreen` is not touched yet, so the screen keeps compiling against the defaulted param.
4. **The two dialogs**, each in its own file and styled from `StepperDialog` / `NoteDialogContent`. They take plain values and callbacks, so they compile and can be reused before they are wired.
5. **Footer wiring in `InFlightScreen.kt`.**
   - Pass `container.missionManager`.
   - Add the Land button and Scrub action, and the two dialog flags.
   - Land decides with `needsLandConfirmation`: if true, open the dialog; otherwise land straight away. Confirming closes the dialog, then calls `vm.land(onBack)`.
   - Add the message line and the screen tests.
   - This step removes the Back button, so the existing Back test is retargeted in the same commit.
6. **Final gate**, with proof the tests ran:
   - Run `bash gradlew.sh --stop`, then `rm -rf app/build/test-results/testDebugUnitTest`, then `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`.
   - Read the per-class `tests=/failures=/errors=` totals in `app/build/test-results/testDebugUnitTest/TEST-*.xml`. `BUILD SUCCESSFUL` alone can be a cached run of the previous tests.

## How the result will be tested

Robolectric + in-memory Room, no network, no Compose where the logic can be reached without it.

**Fixture for `InFlightLandScrubTest`.** Use a `MissionManager` with a fixed `Clock` and `zone = { ZoneOffset.UTC }`, and the default settings (`generateRunPlans = false`).
- `onAppOpen()` then `confirm()` an `RLRLR` Mission. Index 0 is a RUN and gets the simple Run plan.
- Launch and land sortie 0 through the manager.
- Sortie 1 (a LIFT) is now current. Write a seeded lift plan for it with `flightPlanDao().writePlan`, set it PLANNED with `markPlanned`, then `manager.launch` it.
- The view-model under test is built for sortie 1 and handed that manager.
- This is the brief's "Launch → log → Land for a seeded lift plan" path: sets are checked with `vm.check(...)`, then `vm.land(...)` is called.

For the closing test, `setPattern` to a two-sortie pattern before `confirm`, as `LaunchpadStatesTest` already does.

| Test | Asserts |
|---|---|
| `SortiesTest.scrubTreatsWhitespaceReasonAsNull` | `scrub(sortie, "   ")` gives `scrubReason == null`; a real reason is kept. |
| `InFlightStatesTest.openCountCountsOpenSets` | `Ready.openCount` on the existing fixture (7 sets) after checking some sets; skipped exercises add nothing. |
| `InFlightStatesTest.confirmationNeededOnlyWhenSetsAreOpen` | `needsLandConfirmation` is false at `openCount == 0` and true at 1 and above. |
| `InFlightStatesTest.noChecklistNeedsNoConfirmation` | `NoChecklist` (a run, and a plan with no exercises) never confirms. |
| `InFlightLandScrubTest.landWithEverySetChecked` | All sets DONE gives LANDED; the sets stay DONE, `onDone` runs once, and there is no message. |
| `InFlightLandScrubTest.landRecordsUncheckedSetsAsNotDone` | With mixed DONE/OPEN sets, the OPEN sets become SKIPPED and the DONE ones stay DONE. |
| `InFlightLandScrubTest.landStoresLandedTime` | The state is LANDED and `landedAt` equals the fixed `Clock`'s millis. |
| `InFlightLandScrubTest.landMakesNextRunSortieCurrentWithSimpleRunPlan` | Landing sortie 1 (LIFT) leaves sortie 2 (RUN) PLANNED, with a plan of `source = SIMPLE_RUN` and title "Run". |
| `InFlightLandScrubTest.landingARunNeedsNoChecklist` | A view-model on an in-flight RUN sortie (NoChecklist) lands it; it is LANDED and `onDone` runs. |
| `InFlightLandScrubTest.landingLastSortieClosesMission` | With a two-sortie pattern, landing the last sortie sets the Mission CLOSED. |
| `InFlightLandScrubTest.scrubFromInFlightKeepsCheckedSetsAndStoresReason` | The sortie is SCRUBBED, the checked sets are still DONE, `scrubReason == "felt sick"`, and the next sortie has advanced. |
| `InFlightLandScrubTest.scrubStoresWhitespaceReasonAsNull` | `vm.scrub("   ", …)` leaves `scrubReason` null. |
| `InFlightLandScrubTest.doubleTapLandsOnce` | Two `land()` calls with no wait between them: `onDone` runs once, the sortie is LANDED, the sortie after it is not skipped or advanced past, and nothing is thrown. |
| `InFlightLandScrubTest.secondLandIsRejectedWithoutCrash` | After the first `land()` completes, a second one leaves the sortie LANDED once with its original `landedAt`. It sets a short message, does not call `onDone`, and throws nothing out of the view-model. |
| `InFlightScreenTest.landFooterOffersLandAndScrubAndNoBack` | The footer shows `LAND` and `SCRUB` and no `BACK`, on both the checklist and the no-checklist state. Nodes are asserted, not clicked. |
| `InFlightScreenTest.noChecklistLandLandsAndCallsOnBack` (retargets `noChecklistBackButtonTriggersCallback`) | The same RUN-sortie setup, with the Mission set ACTIVE first (`land` advances, which requires ACTIVE). Tap `LAND`; no dialog opens because there is no checklist. `waitUntil(5_000)` for `onBack`, then the sortie is LANDED. |

Every wait has a timeout:
- In screen tests, `composeRule.waitUntil(5_000) { … }`, as the existing In-Flight screen tests already do.
- In the plain tests, `withTimeout(…)` / `flow.first { … }` inside `runBlocking`, or a `CompletableDeferred` completed by `onDone` and awaited under `withTimeout`.

No test clicks a control that opens a Dialog containing a text field.

## Risks and uncertainties

- **`noChecklistBackButtonTriggersCallback` (InFlightScreenTest:332-382) fails as soon as the footer loses Back.**
  - It is not one of the note tests the brief protects, so step 5 retargets it to tap Land rather than keep a Back button alive.
  - Its Mission comes from `onAppOpen()` and is DRAFT, so the retargeted test must make it ACTIVE first.
  - System back from In-Flight is already covered by `ShellNavTest.backFromInFlight*`.
- **`InFlightScreen.kt` is ~895 lines.** Edits stay in the view-model construction, `Footer` and its call sites, and the dialog-host block beside `editingSetId` / `noteTarget`. The checklist, cards and note wiring are not touched.
- **A Dialog with a text field hangs Compose under Robolectric** on this machine; that is why `NoteDialog` is split into shell + `NoteDialogContent`.
  - The scrub dialog's reason field is therefore not clicked in a screen test.
  - If it is Compose-tested at all, it is tested as a content composable outside the `Dialog`, the way `NoteDialogTest` does.
- **The view-model's scope is the screen's `rememberCoroutineScope()`.** `onDone` (navigation) runs only after the manager's transaction returns, so leaving the screen cannot cancel a write half-way.
- **After landing, the In-Flight flows keep emitting** until the shell navigates away.
  - `deriveInFlight` ignores `sortie.state`, so a LANDED sortie still derives a normal state.
  - The screen must not read that as a failure, so the message flow is only set from a caught exception, never from state.
- **`MissionManager` rejections are not all one type.**
  - `check(...)` throws `IllegalStateException`, `db.sortieDao().get(sortieId)!!` can throw a null-pointer, and `advance` can throw `IllegalTransitionException`.
  - The view-model catches `Exception` (rethrowing `CancellationException`) rather than guessing at one type.
- **`LabeledTextField` is a themed `OutlinedTextField`.** That is the Mission Control style the brief asks for, not the `BasicTextField` used by `LabeledMultiLineTextField`. If the owner wants a multi-line reason, that is a one-line swap.
- **The defaulted `missionManager` parameter** keeps the existing view-model tests compiling. A test that wants a fixed clock passes its own manager, and the screen passes `container.missionManager` so the app keeps one manager.
- **ARCHITECTURE.md's M4 row stays "Not started".** The brief's Done-when names no documentation change, so none is made here.
- **This checkout is shared with other terminals,** so a Gradle run may wait on a lock. Long gate runs are best run detached, with the log under `app/build/`.

## Decisions

**9. Is a reason with text in it trimmed?** Chosen: only blank or whitespace-only becomes null; a reason with text is stored exactly as typed. Alternative: trim it, as notes are trimmed. The brief names only the blank case for the domain fix, and the owner's reason text is theirs.

Changed by review: the plan chose to store a reason exactly as typed, while its own view-model row said the reason is trimmed. The review chose to trim the reason in the view-model, with blank becoming null. Two reasons:
- The same screen already trims notes that way (`normalizeNote`), so the two text inputs behave alike.
- A stray leading or trailing space from the keyboard is never something the owner meant to keep.

**1. Where the Scrub action sits in the bottom area.** The mockup's footer holds only Land.
- Chosen: an underlined `SCRUB` text action under the full-width Land button, left-aligned.
- Alternative: beside Land in a row, or up in the header.
- The Launchpad already puts Scrub as an underlined action under its primary button, so the same shape in the same place is where the owner would look.
- A 64 dp Land button is the mockup's and shouldn't be narrowed to make room.

**2. What the footer shows while the screen is still Loading.**
- Chosen: keep the current Back text button in the Loading footer; Land and Scrub appear once the sortie has been read.
- Alternative: show Land greyed out.
- Nothing can be landed before the plan is read, and a Land button that does nothing invites a tap. The state lasts one Room read.

**3. The confirmation dialog's wording, and singular vs plural.**
- Chosen: title `Land sortie N?`, then the body `3 sets unchecked.` and `They will be recorded as not done.` For one set, the singular `1 set unchecked.`
- Alternative: always plural, or list the open sets.
- The brief's own example is the plural line, and "1 sets unchecked" reads wrong. Naming each set is more text than a glance at the counter already gives.

**4. Does scrubbing ever skip the confirmation?**
- Chosen: the Scrub action always opens the confirmation dialog, even when nothing has been checked yet.
- Alternative: scrub straight away when no set is logged.
- The brief says the action opens a confirmation dialog. Scrubbing gives up the sortie's place in the week, so one deliberate step is what the owner would expect.

**5. Does the scrub dialog explain what happens to the sets already checked?**
- Chosen: no extra line. The dialog shows `Scrub sortie N?`, an optional reason, and Scrub and Cancel, matching the Launchpad's dialog.
- Alternative: add "Sets already checked stay logged."
- DESIGN §5.1 already settles it, and the Launchpad dialog doesn't say it. The two dialogs should read the same so the owner learns the flow once.

**6. Where a rejection appears and what it says.**
- Chosen: a short line in the footer above Land, `Couldn't land this sortie.` or `Couldn't scrub this sortie.`
- The owner stays on In-Flight, and the line clears on the next action or when a dialog closes.
- Alternative: a dialog or snackbar, or showing `MissionManager`'s own wording ("Sortie must be current").
- The operation is a single Room write, so a modal is heavier than the situation needs, and the manager's check messages read like a stack trace.

**7. What the buttons do while a land or scrub write is running.**
- Chosen: the view-model's guard ignores a second tap, and the buttons keep their normal look.
- Alternative: disable or grey them.
- The write is one transaction that finishes in milliseconds. Greying would need a new enabled state on the shared ink button, which every other screen would inherit.

**8. Is the Launchpad's existing scrub dialog replaced in this run?**
- Chosen: no. The new dialog is built in `ui.inflight` for the Launchpad to adopt in a later brief, and `LaunchpadScreen` is left alone.
- Alternative: refactor both now.
- The brief says the Launchpad can reuse it *later* and lists the Launchpad under "must not break". Swapping its dialog would change a screen this brief didn't ask to change.

The brief settles the rest of the behavior this plan touches:
- Land replaces the Back text button, and system back still leaves without landing.
- Landing goes through `MissionManager.land` and returns to the Launchpad, which then shows the next sortie or the closed state.
- A sortie with no checklist lands with no confirmation.
- Scrub keeps the sets already checked and returns to the Launchpad.
- A blank reason is stored as null.
- The reason field uses the Mission Control text-field style.
- The confirmation is asked only when sets are still open.
