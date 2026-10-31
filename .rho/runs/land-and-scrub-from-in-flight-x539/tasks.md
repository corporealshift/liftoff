# Tasks: Land and scrub from In-Flight

Six code tasks, in order. Each ends with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`
green and its own commit (`area: lowercase summary`, named files staged only, LF endings, CLAUDE.md §2
vocabulary). Test names below are exactly the names `.rho/runs/land-and-scrub-from-in-flight-x539/verify.sh`
looks for in the JUnit XML, so write them with those spellings; the class and package are yours to choose.
Before naming a new test, check the name is not already in the suite.

- [x] 1. Store a whitespace-only scrub reason as null
  Covers: `domain/Sorties.kt` — in `scrub`, change `reason.isNullOrEmpty()` to `reason.isNullOrBlank()`
  so a reason of only spaces is stored as null while a reason with text is kept exactly as passed.
  Nothing else changes; the rest of the run builds on this one-line fix.
  New test in `app/src/test/java/com/liftoff/app/domain/SortiesTest.kt`:
  `scrubTreatsWhitespaceReasonAsNull` — `scrub(sortie, "   ")` gives `scrubReason == null`, and a real
  reason is still stored on the scrubbed sortie.
  Done when: that test exists and passes, and the existing `SortiesTest.scrubStoresBlankReasonAsNull`
  (which verify.sh lists under "must not break") still passes.

- [x] 2. Open-set count, sortie number, and when landing needs confirmation
  Covers: `ui/inflight/InFlightState.kt` only — `Ready` gains `openCount`, derived in `deriveInFlight`
  as `allProgress.count { it == SetStatus.OPEN }` (a skipped exercise's sets are already SKIPPED, so they
  add nothing); `Ready` and `NoChecklist` both gain `sortieNumber` (`sortie.index + 1`, already computed);
  add a top-level `fun needsLandConfirmation(state: InFlightState): Boolean` that is true only for a
  `Ready` with `openCount > 0`, so `Loading` and `NoChecklist` never confirm. Plain Kotlin, no Compose,
  no view-model or screen change. Both classes are constructed only in `deriveInFlight`, so no other call
  site moves.
  New tests in `app/src/test/java/com/liftoff/app/ui/inflight/InFlightStatesTest.kt`:
  `openCountCountsOpenSets` (full count before anything is checked, drops as sets are checked, sets of a
  skipped exercise not counted), `confirmationNeededOnlyWhenSetsAreOpen` (false at `openCount == 0`, true
  at 1 and above), `noChecklistNeedsNoConfirmation` (a RUN sortie, and a plan with no exercises, and
  `Loading`).
  Done when: those three tests exist and pass, and the existing derivation/check/note tests in
  `InFlightStatesTest` and `InFlightScreenTest` still pass.

- [x] 3. Land a sortie through the view model
  Covers: `ui/inflight/InFlightViewModel.kt` — a trailing constructor param
  `missionManager: MissionManager = MissionManager(db, settings)` (the default keeps every existing call
  site and test compiling; `InFlightScreen.kt` is **not** touched in this task), the synchronous `busy`
  guard set before `scope.launch` and reset in `finally`, a `MutableStateFlow<String?>` message plus
  `clearMessage()`, and `land(onDone)`: `scope.launch { try { manager.land(sortieId); onDone() } catch
  CancellationException rethrow; catch Exception -> message }`, `onDone` called only on success.
  New file `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` with the plan's
  fixture: Robolectric + in-memory Room, a `MissionManager` with a fixed `Clock` and
  `zone = { ZoneOffset.UTC }`, default settings (`generateRunPlans = false`), `onAppOpen()` + `confirm()`
  on an `RLRLR` Mission, land sortie 0 through the manager, seed sortie 1's lift plan with
  `flightPlanDao().writePlan` + `markPlanned`, `manager.launch` it, then build the view model for sortie 1
  against that manager and log sets with `vm.check(...)`. Every wait has a timeout
  (`withTimeout` / `CompletableDeferred` awaited under it).
  New tests in that file: `landWithEverySetChecked` (LANDED, sets stay DONE, `onDone` runs once, no
  message), `landRecordsUncheckedSetsAsNotDone` (OPEN sets become SKIPPED, DONE ones stay DONE),
  `landStoresLandedTime` (state LANDED, `landedAt` equals the fixed clock's millis),
  `landMakesNextRunSortieCurrentWithSimpleRunPlan` (sortie 2 is the current PLANNED RUN with a plan of
  `source = SIMPLE_RUN` and title "Run"), `landingARunNeedsNoChecklist` (a view model on an in-flight RUN
  sortie lands it and `onDone` runs), `landingLastSortieFromInFlightClosesMission` (two-sortie pattern set
  before `confirm`, as `LaunchpadStatesTest` does; landing the last sortie sets the Mission CLOSED — note
  `MissionManagerTest` already has `landingLastSortieClosesMission`, so this name must be new and reached
  from In-Flight).
  Done when: those six tests exist and pass, and `MissionManagerTest`'s lifecycle tests
  (`landAdvancesToNextSortie`, `landMarksUncheckedSetsNotDone`, `landingLastSortieClosesMission`) still
  pass unchanged.

- [x] 4. Double tap and rejection are safe
  Covers: the robustness half of task 3's land path — the `busy` guard ignoring a second call while a
  write is running, and a `MissionManager` rejection (sortie no longer in flight) becoming a short message
  instead of an exception escaping the view model. `MissionManager` rejects with mixed types
  (`IllegalStateException`, a null-pointer from `sortieDao().get(id)!!`, `IllegalTransitionException`), so
  the catch stays `Exception` with `CancellationException` rethrown. Adjust the view model only if these
  tests show the guard or the message flow is wrong; no screen change.
  New tests in `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt`:
  `doubleTapLandsOnce` (two `land()` calls with no wait between them: `onDone` ran once, the sortie is
  LANDED, the next sortie was not advanced past, nothing thrown),
  `secondLandIsRejectedWithoutCrash` (after the first land completes, a second leaves the sortie LANDED
  once with its original `landedAt`, sets a short message, does not call `onDone`, throws nothing out of
  the view model).
  Done when: both tests exist and pass, and the suite is still green.

- [x] 5. Scrub a sortie through the view model
  Covers: `InFlightViewModel.scrub(reason, onDone)` in the same shape as `land`, with the reason trimmed
  and blank-becomes-null before it reaches the manager (the same normalisation `normalizeNote` applies to
  notes), and the message flow reused for a rejected scrub. No screen change; the Launchpad's own
  `LaunchpadViewModel` and its private `ScrubDialog` are left alone.
  New tests in `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt`:
  `scrubFromInFlightKeepsCheckedSetsAndStoresReason` (sortie SCRUBBED, the sets already checked are still
  DONE, `scrubReason == "felt sick"`, the Mission advanced to the next sortie, `onDone` ran),
  `scrubStoresWhitespaceReasonAsNull` (`vm.scrub("   ", …)` leaves `scrubReason` null on the sortie).
  Done when: both tests exist and pass, and `MissionManagerTest.scrubInFlightKeepsCheckedSets`,
  `scrubbingLastSortieClosesMission` and `SortiesTest.scrubStoresBlankReasonAsNull` still pass.

- [x] 6. The two themed dialogs, in their own files
  Covers: two new files, styled from `StepperDialog` / `NoteDialogContent` (Paper card in a `Dialog`, 2 dp
  Ink `CardShape` border, 16 dp padding, `LiftoffType.cardTitle()` title, `PrimaryButton` +
  `UnderlinedTextButton` row), each taking plain values and callbacks and holding no view model, so the
  Launchpad can reuse them later:
  `ui/inflight/LandConfirmDialog.kt` — takes `openCount`, `sortieNumber`, `onConfirm`, `onDismiss`; title
  `Land sortie N?`, body `3 sets unchecked.` / `They will be recorded as not done.`, singular `1 set
  unchecked.` for one set.
  `ui/inflight/ScrubDialog.kt` — `internal`; takes `sortieNumber`, `onConfirm(reason: String)`,
  `onDismiss`; `Scrub sortie N?`, an optional reason held in the dialog's own `remember`, Scrub and
  Cancel; the reason field is `LabeledTextField` from `ui/control/MissionControlScreen.kt` (`internal`,
  same module — the Mission Control field, not a stock M3 field). Its name does not clash with
  `LaunchpadScreen.kt`'s private `ScrubDialog` (different package).
  No test is named for this task: verify.sh names no dialog test, and the brief makes Compose tests that
  open a `Dialog` optional. If a test is written at all, it tests the content composable outside the
  `Dialog`, the way `NoteDialogTest` does — never a text field inside a `Dialog`, which hangs Compose
  under Robolectric on this machine.
  Done when: both files exist, `:app:assembleDebug` succeeds, the suite is green, and neither is wired
  into `InFlightScreen.kt` yet.

- [x] 7. Wire Land and Scrub into the In-Flight footer, and retarget the Back test
  Covers: `ui/inflight/InFlightScreen.kt` only, in three places — the `InFlightViewModel(...)` call also
  passes `container.missionManager`; `Footer` becomes a full-width 64 dp `InkButton` Land (flag glyph from
  `LiftoffIcons`, red offset shadow) with the underlined `SCRUB` action under it and the rejection message
  line above Land, for `Ready` and `NoChecklist` (call sites), while `LoadingContent`'s footer keeps the
  old Back button under a new name; two `remember` dialog flags beside `editingSetId` / `noteTarget`, with
  Land deciding through `needsLandConfirmation` (dialog first when true, land straight away otherwise;
  confirming closes the dialog then calls `vm.land(onBack)`; Scrub always opens its dialog; the message
  clears on the next action or when a dialog closes). The checklist, cards and note wiring are not touched.
  New tests in `app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt`:
  `landFooterOffersLandAndScrubAndNoBack` (footer nodes show `LAND` and `SCRUB` and no `BACK`, on both the
  checklist and the no-checklist state; nodes asserted, not clicked), and
  `noChecklistLandLandsAndCallsOnBack`, which **replaces** the existing
  `noChecklistBackButtonTriggersCallback` (it taps the Back button this task removes): same RUN-sortie
  setup, Mission made ACTIVE first because `land` advances, tap `LAND`, no dialog opens because there is
  no checklist, `composeRule.waitUntil(5_000)` for `onBack`, then the sortie is LANDED. No test clicks a
  control that opens a Dialog containing a text field.
  Done when: both names run and pass, `noChecklistBackButtonTriggersCallback` is gone, and the final gate
  is re-measured clean — `bash gradlew.sh --stop`, then `rm -rf app/build/test-results/testDebugUnitTest`,
  then `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` (run detached with the
  log under `app/build/`, deleted before finishing) — with `BUILD SUCCESSFUL`, no
  `:app:testDebugUnitTest FROM-CACHE|UP-TO-DATE` line, and the per-class `tests=/failures=/errors=` totals
  from `app/build/test-results/testDebugUnitTest/TEST-*.xml` recorded in the commit body as evidence the
  tests actually ran. The "must not break" names verify.sh lists (checklist, notes, `exportedV1SchemaMatchesDatabase`,
  MissionManager/domain, Launchpad, Mission screens, shell `backFromInFlightClearsTheSortieId`,
  `neverSaysSession`) all still pass.
