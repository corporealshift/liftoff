# Land and scrub from In-Flight — plan

## Approach

Finish a sortie from the In-Flight footer: a 64 dp ink **Land** button (flag glyph, red
offset shadow) replacing the Back text button, and an underlined **Scrub** action under it.
Both drive `MissionManager` through `InFlightViewModel`, which is where every other In-Flight
write already lives, and both end by calling the existing `onBack` so the shell returns to the
Launchpad (`ShellNav.back()` already does exactly that — no shell change needed).

Why this shape fits the codebase:

- **The lifecycle work is already done.** `MissionManager.land(sortieId)` (data/MissionManager.kt:114)
  sets LANDED with `clock.millis()`, calls `flightPlanDao().markOpenSetsNotDone(sortieId)`,
  `advance(...)` prepares the next sortie (a run with generation off gets `simpleRunPlan`,
  title "Run", `source = SIMPLE_RUN`) and closes the Mission after the last one.
  `scrub(sortieId, reason)` (:85) keeps the checked sets and advances. Nothing new is needed
  in `data/` or `domain/` beyond the blank-reason fix the brief names.
- **"Recorded as not done" already exists.** `SetStatus` is `OPEN, DONE, SKIPPED`
  (data/Enums.kt:7) and `markOpenSetsNotDone` moves OPEN → SKIPPED. No new status, no Room
  schema change.
- **The open-set count is a one-line addition to the derived state.** `deriveInFlight` already
  builds `allProgress: List<SetStatus>` (ui/inflight/InFlightState.kt:83, 205), so `openCount`
  is `allProgress.count { it == SetStatus.OPEN }`. Skipping an exercise already marks its open
  sets SKIPPED (`skipOpenSets`, FlightPlanDao.kt:273), so skipped exercises add nothing and the
  count is exactly "sets that landing would record as not done".
- **The themed parts exist.** `InkButton` (ui/theme/Buttons.kt:92) is already ink with a red
  offset shadow, 64 dp by default, cream label and icon — the mockup's footer
  (design/screens/in-flight.html:110-115: 3 px ink rule, cream footer, full-width 64 px ink
  button, 4 px red shadow, flag glyph, `LAND`). `LiftoffIcons.flag()`, `UnderlinedTextButton`,
  and the Paper-card-in-`Dialog` style used by `StepperDialog` (InFlightScreen.kt:746) and
  `NoteDialog.kt` are all reusable as they are.
- **The scrub flow has a precedent to mirror.** `LaunchpadViewModel` already holds
  `scrubbing` / `scrubReason` and calls `manager.scrub(..., reason.takeIf { it.isNotBlank() })`
  (ui/launchpad/LaunchpadViewModel.kt:112-143). The In-Flight view-model gets the same shape,
  and the new dialog is built so the Launchpad can adopt it later.
- **Tests stay plain Kotlin.** Robolectric + in-memory Room against the view-model, the way
  `InFlightViewModelTest` and `MissionManagerTest` already work. The brief makes Compose tests
  that open a Dialog optional, and this repo has a known hang when a text field sits inside a
  Compose `Dialog` under Robolectric (`NoteDialog.kt` was split into shell + content for that
  reason), so the confirmation logic is exposed as a pure helper on the state instead.

## Files and parts involved

**Changed**

| File | What changes |
|---|---|
| `app/src/main/java/com/liftoff/app/domain/Sorties.kt` | `scrub` (:49-53): `reason.isNullOrEmpty()` → `reason.isNullOrBlank()`, so a whitespace-only reason is stored as null. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt` | `Ready` gains `val openCount: Int`; `deriveInFlight` sets it from `allProgress`; add a top-level `fun needsLandConfirmation(state: InFlightState): Boolean` (`state is Ready && state.openCount > 0`) — `Loading` and `NoChecklist` (a run) never confirm. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` | New trailing constructor param `missionManager: MissionManager = MissionManager(db, settings)` (defaulted so the four existing `InFlightViewModel(...)` call sites keep compiling); `busy` guard; `MutableStateFlow<String?>` message; `land(onDone)` and `scrub(reason, onDone)`, each `scope.launch { try { manager… ; onDone() } catch (CancellationException) rethrow; catch (Exception) → message }`. Reason is trimmed here and blank → null before it reaches the manager. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | Only the footer and the dialog hosts: `Footer` (:871-885) becomes Land + Scrub for `Ready` and `NoChecklist` (call sites :167, :207, :268); two new `remember` dialog flags beside `editingSetId` / `noteTarget` (:52-54); the rejection message rendered above Land. `LoadingContent`'s footer is left as it is. |
| `app/src/test/java/com/liftoff/app/domain/SortiesTest.kt` | blank/whitespace reason test. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightStatesTest.kt` | `openCount` and `needsLandConfirmation` cases. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt` | footer test; retarget `noChecklistBackButtonTriggersCallback` (:332-382), which taps the Back button this brief removes. |

**New**

- `app/src/main/java/com/liftoff/app/ui/inflight/LandConfirmDialog.kt` — themed Paper card in a
  `Dialog`, same shell as `StepperDialog`/`NoteDialogContent` (Paper background, 2 dp Ink
  `CardShape` border, 16 dp padding, `LiftoffType.cardTitle()` title, `PrimaryButton` +
  `UnderlinedTextButton` row with `Modifier.weight(1f)`). Takes `openCount` and `sortieIndex`,
  `onConfirm` / `onDismiss`. No view-model inside it.
- `app/src/main/java/com/liftoff/app/ui/inflight/ScrubDialog.kt` — same shell, `internal`,
  reason field via `LabeledTextField` (ui/control/MissionControlScreen.kt:231 — `internal` in
  the same module, already reused by `NoteDialog.kt`'s `LabeledMultiLineTextField`), so it is
  the Mission Control field and not a stock M3 field. `onConfirm(reason: String)` / `onDismiss`,
  no view-model inside it, so the Launchpad can reuse it later.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` — Robolectric tests
  for land/scrub through the view-model against in-memory Room.

**Not touched:** `MissionManager`, any DAO or Room entity, `ShellNav` / `LiftoffShell`,
`LaunchpadScreen` / `LaunchpadViewModel` (including its private `ScrubDialog`), `NoteDialog.kt`,
`ui/theme`, `coach/`, ARCHITECTURE.md.

## Order of work

Each step ends with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` green and its
own commit (`area: lowercase summary`, named files staged only).

1. **Domain blank-reason fix.** `Sorties.kt` `isNullOrBlank()` + `SortiesTest` case. Smallest,
   independent, and the rest of the plan relies on it.
2. **Open-set count and the confirmation helper.** `Ready.openCount`, `deriveInFlight`,
   `needsLandConfirmation`, `InFlightStatesTest` cases. Pure Kotlin, no UI.
3. **View-model land and scrub.** `missionManager` param, `busy` guard, message flow,
   `land(onDone)`, `scrub(reason, onDone)`; `InFlightLandScrubTest` covering every item in the
   brief's Done-when list. `InFlightScreen` is not touched yet, so the screen keeps compiling
   against the defaulted param.
4. **The two dialogs**, in their own files, styled from `StepperDialog` / `NoteDialogContent`.
   They take plain values and callbacks so they compile and can be reused before they are wired.
5. **Footer wiring in `InFlightScreen.kt`.** Land button + Scrub action, the two dialog flags,
   the Land decision (`needsLandConfirmation` → dialog, else land straight away), the message
   line, and the screen tests. This is the step that removes the Back button, so the existing
   Back test is retargeted in the same commit.
6. **Final gate** with the reports proven to have run:
   `bash gradlew.sh --stop`, `rm -rf app/build/test-results/testDebugUnitTest`,
   `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, then read the
   per-class `tests=/failures=/errors=` totals in `app/build/test-results/testDebugUnitTest/TEST-*.xml`
   (`BUILD SUCCESSFUL` alone can be a cached run of the previous tests).

## How the result will be tested

Robolectric + in-memory Room, no network, no Compose where the logic can be reached without it.

| Test | Asserts |
|---|---|
| `SortiesTest.scrubTreatsWhitespaceReasonAsNull` | `scrub(sortie, "   ")` → `scrubReason == null`; a real reason is kept. |
| `InFlightStatesTest.openCountCountsOpenSets` | `Ready.openCount` on the existing fixture (7 sets) after checking some sets; skipped exercises add nothing. |
| `InFlightStatesTest.confirmationNeededOnlyWhenSetsAreOpen` | `needsLandConfirmation` false at `openCount == 0`, true at 1 and above. |
| `InFlightStatesTest.noChecklistNeedsNoConfirmation` | `NoChecklist` (a run, and a plan with no exercises) never confirms. |
| `InFlightLandScrubTest.landWithEverySetChecked` | all sets DONE → LANDED, sets stay DONE, no message. |
| `InFlightLandScrubTest.landRecordsUncheckedSetsAsNotDone` | mixed DONE/OPEN → the OPEN sets are SKIPPED, the DONE ones stay DONE. |
| `InFlightLandScrubTest.landStoresLandedTime` | state LANDED and `landedAt` equals the fixed `Clock` the test injects into the `MissionManager` it hands the view-model. |
| `InFlightLandScrubTest.landMakesNextRunSortieCurrentWithSimpleRunPlan` | a seeded `RLRLR` Mission with sorties 0 and 1 LANDED and sortie 2 IN_FLIGHT: landing it leaves sortie 3 PLANNED with `source = SIMPLE_RUN`, title "Run". |
| `InFlightLandScrubTest.landingLastSortieClosesMission` | landing the last sortie sets the Mission CLOSED. |
| `InFlightLandScrubTest.scrubFromInFlightKeepsCheckedSetsAndStoresReason` | SCRUBBED, the checked sets still DONE, `scrubReason == "felt sick"`, next sortie advanced. |
| `InFlightLandScrubTest.scrubStoresWhitespaceReasonAsNull` | `"   "` reaches the manager and `scrubReason` is null. |
| `InFlightLandScrubTest.secondLandIsRejectedWithoutCrash` | a second `land()` leaves the sortie LANDED once with its original `landedAt`, sets a short message, and throws nothing out of the view-model. |
| `InFlightScreenTest.landFooterOffersLandAndScrubAndNoBack` | the footer shows `LAND` and `SCRUB` and no `BACK`, on both the checklist and the no-checklist state. Nodes are asserted, not clicked — clicking would open a Dialog. |

Every wait has a timeout: `composeRule.waitUntil(5_000) { … }` as the existing In-Flight screen
tests already do, and `withTimeout(…)` / `flow.first { … }` inside `runBlocking` in the plain
tests. No test clicks a control that opens a Dialog containing a text field.

## Risks and uncertainties

- **`noChecklistBackButtonTriggersCallback` (InFlightScreenTest:332-382) fails as soon as the
  footer loses Back.** It is not one of the note tests the brief protects, so it is retargeted
  in step 5 rather than kept alive by leaving a Back button in. System back is already covered
  by `ShellNavTest` / `LiftoffShellTest`.
- **`InFlightScreen.kt` is ~895 lines.** Edits stay in `Footer`, its three call sites, and the
  dialog-host block beside `editingSetId` / `noteTarget`. The checklist, cards and note wiring
  are not touched.
- **A Dialog with a text field hangs Compose under Robolectric** on this machine (the reason
  `NoteDialog` is split into shell + `NoteDialogContent`). The scrub dialog's reason field is
  therefore not clicked in a screen test; if it is Compose-tested at all it is tested as a
  content composable outside the `Dialog`, the way `NoteDialogTest` does.
- **After landing, the In-Flight flows keep emitting** until the shell navigates away.
  `deriveInFlight` ignores `sortie.state`, so a LANDED sortie still derives a normal state and
  the screen must not read that as a failure — the message flow is only set from a caught
  exception, never from state.
- **`MissionManager` rejections are not all one type:** `check(...)` throws
  `IllegalStateException`, `db.sortieDao().get(sortieId)!!` can throw a null-pointer, and
  `advance` can throw `IllegalTransitionException`. The view-model catches `Exception`
  (rethrowing `CancellationException`) rather than guessing at one type.
- **`LabeledTextField` is a themed `OutlinedTextField`**, which is the Mission Control style the
  brief asks for; it is not the `BasicTextField` used by `LabeledMultiLineTextField`. If the
  owner wants a multi-line reason, that is a one-line swap.
- **The defaulted `missionManager` parameter** keeps the existing view-model tests compiling but
  means a test that wants a fixed clock must pass its own manager. Passing it explicitly at all
  four call sites is the alternative; it touches three test files.
- **ARCHITECTURE.md's M4 row stays "Not started"** — the brief's Done-when names no documentation
  change, so none is made here.
- This checkout is shared with other terminals, so a Gradle run may wait on a lock; long gate
  runs are best run detached with the log under `app/build/`.

## Decisions

**1. Where the Scrub action sits in the bottom area.** The mockup's footer holds only Land.
Chosen: an underlined `SCRUB` text action full-width under the Land button, left-aligned.
Alternative: beside Land in a row, or up in the header. The Launchpad already puts Scrub as an
underlined action under its primary button, so the same shape in the same place is what the
owner would look for; a 64 dp Land button is the mockup's and shouldn't be narrowed to make
room.

**2. What the footer shows while the screen is still Loading.** Chosen: keep the current Back
text button in the Loading footer; Land and Scrub appear once the sortie has been read.
Alternative: show Land greyed out. Nothing can be landed before the plan is read, and a Land
button that does nothing invites a tap; the state lasts one Room read.

**3. The confirmation dialog's wording, and singular vs plural.** Chosen: title
`Land sortie N?`, body `3 sets unchecked.` then `They will be recorded as not done.`, with the
singular `1 set unchecked.` for one. Alternative: always plural, or list the open sets. The
brief's own example is the plural line; "1 sets unchecked" reads wrong, and naming each set is
more text than a glance at the counter already gives.

**4. Does scrubbing ever skip the confirmation?** Chosen: the Scrub action always opens the
confirmation dialog, even when nothing has been checked yet. Alternative: scrub straight away
when no set is logged. The brief says the action opens a confirmation dialog, and scrubbing
gives up the sortie's place in the week, so one deliberate step is what the owner would expect.

**5. Does the scrub dialog explain what happens to the sets already checked?** Chosen: no extra
line — `Scrub sortie N?`, an optional reason, Scrub and Cancel, matching the Launchpad's dialog.
Alternative: add "Sets already checked stay logged." DESIGN §5.1 already settles it, the
Launchpad dialog doesn't say it, and the two dialogs should read the same so the owner learns
the flow once.

**6. Where a rejection appears and what it says.** Chosen: a short line in the footer above
Land — `Couldn't land this sortie.` / `Couldn't scrub this sortie.` — the owner stays on
In-Flight, and the line clears on the next action or when a dialog closes. Alternative: a
dialog or snackbar, or showing `MissionManager`'s own wording ("Sortie must be current"). The
operation is a single Room write, so a modal is heavier than the situation, and the manager's
check messages read like a stack trace.

**7. What the buttons do while a land or scrub write is running.** Chosen: a second tap is
ignored by a guard in the view-model, and the buttons keep their normal look. Alternative:
disable/grey them. The write is one transaction that finishes in milliseconds, and greying
would need a new enabled state on the shared ink button that every other screen inherits.

**8. Is the Launchpad's existing scrub dialog replaced in this run?** Chosen: no — the new
dialog is built in `ui.inflight` for the Launchpad to adopt in a later brief, and
`LaunchpadScreen` is left alone. Alternative: refactor both now. The brief says the Launchpad
can reuse it *later* and lists the Launchpad under "must not break"; swapping its dialog would
change a screen this brief didn't ask to change.

**9. Is a reason with text in it trimmed?** Chosen: only blank or whitespace-only becomes null;
a reason with text is stored exactly as typed. Alternative: trim it, as notes are trimmed. The
brief names only the blank case for the domain fix, and the owner's reason text is theirs.

The brief settles the rest of the behavior this plan touches: Land replaces the Back text
button and system back still leaves without landing; landing goes through `MissionManager.land`
and returns to the Launchpad, which then shows the next sortie or the closed state; a sortie
with no checklist lands with no confirmation; scrub keeps the sets already checked and returns
to the Launchpad; a blank reason is stored as null; the reason field uses the Mission Control
text-field style; and the confirmation is asked only when sets are still open.
