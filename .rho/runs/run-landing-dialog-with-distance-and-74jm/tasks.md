# Tasks — Run landing dialog with distance and duration

Read `.rho/runs/run-landing-dialog-with-distance-and-74jm/brief.md` and
`.rho/runs/run-landing-dialog-with-distance-and-74jm/plan.md` before your task. The plan's
"Files", "Order", "Testing", "Risks" and "Decisions" sections are the authority; the plan's line
numbers are from the commit this run started at, so re-read the file before editing it.

Rules that bind every task: `bash gradlew.sh …` (never `./gradlew`); commit message
`area: lowercase summary`, staging named files only; LF endings; the §2 vocabulary (a sortie is
never a 'session'); no Room schema change; no network; every wait in a test carries a timeout;
**no Compose test may type into, focus or click a text field, and none opens the `Dialog` shell**
(a `BasicTextField` inside a `Dialog` never lets Compose go idle under Robolectric — PR #18).
Do not touch `.rho/runs/run-landing-dialog-with-distance-and-74jm/verify.sh`. Do not push.

Baseline: the suite is 463 tests and green at the start of this run. Iterate with
`bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` (~35 s) and a single
test class; the full cold gate takes ~4 minutes and must be run detached (Task 5).

- [x] Put the distance unit on the `Run` state
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt`: `InFlightState.Run` gains
  `val distanceUnit: DistanceUnit` (`DistanceUnit` is already imported in that file), and
  `deriveInFlight`'s run branch fills it from the `distanceUnit` parameter it already has. The
  `Run` data class is constructed in exactly one place (the run branch of `deriveInFlight`), and no
  test builds a `Run` by hand, so no call site and no existing assertion changes. Extend
  `InFlightRunTest.runDistanceUnitChangeUpdatesStateLive` (or add a case beside it) to assert the
  field: `distanceUnit` is `DistanceUnit.MI` on the seeded run, and after
  `settingsStore.setDistanceUnit(DistanceUnit.KM)` the state published next carries
  `DistanceUnit.KM`. No new test name is required by verify.sh here.
  Done when `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` succeeds and
  `InFlightStatesTest` and `InFlightRunTest` pass with 0 failures / 0 errors / 0 skipped in
  `app/build/test-results/testDebugUnitTest/TEST-*.xml`, and the names verify.sh lists under "must
  not break" for this file — `runStateFromSeededPlanWithSegments`, `runStateFromSimpleRunPlan`,
  `runDistanceUnitChangeUpdatesStateLive`, `runWithNoPlanStillShowsNoChecklist`,
  `sortieNoteSavesOnRunWithAPlan`, `scrubFromRunStateStoresReason`, `launchAndLandSimpleRunFromRunState`,
  `landingARunNeedsNoChecklist`, `confirmationNeededOnlyWhenSetsAreOpen`, `noChecklistNeedsNoConfirmation`,
  `openCountCountsOpenSets` — all still pass. Commit `in-flight: carry the distance unit on the run state`.

- [x] Write `RunLandDialog.kt`, wired to nothing
  New file `app/src/main/java/com/liftoff/app/ui/inflight/RunLandDialog.kt`, the fourth member of
  the `ui.inflight` dialog family. Read `ScrubDialog.kt` first and copy its shape: a thin
  `RunLandDialog(sortieNumber, distanceUnit, errors: RunEntry.Invalid?, onLand: (String, String) -> Unit, onDismiss)`
  that is only `Dialog(onDismissRequest = onDismiss) { RunLandDialogContent(…) }`, plus
  `RunLandDialogContent` holding the card so the fields can be tested outside a `Dialog`. Both are
  `internal`. Card: the Paper background with the 2 dp Ink border and `CardShape`, the title
  `Land sortie n?` (the plan's "The dialog's title"), two `LabeledTextField`s from
  `ui/control/MissionControlScreen.kt`
  (already `internal`, already takes `keyboardType` and `error`) with
  `keyboardType = KeyboardType.Decimal`, each field's value in its own
  `remember { mutableStateOf("") }` so both start blank on every composition, labels
  `Distance (mi)` / `Distance (km)` from the unit and `Duration (min)` (the plan's "Field labels" — use the
  lowercase unit word `formatDistance` prints, not `DistanceUnit.name`), `errors?.distanceError`
  and `errors?.minutesError` passed straight into the two fields' `error =` (both at once,
  the plan's "Both errors at once"), and `PrimaryButton` + `UnderlinedTextButton` each with `Modifier.weight(1f)` as in
  `ScrubDialogContent`: Land calls `onLand(distance, minutes)` and is always enabled (the plan's "Land with both fields blank"),
  Cancel calls `onDismiss`. The card knows nothing about whether anything landed. If a helper you
  want turns out to be file-private in `InFlightScreen.kt`, widen it to `internal` in this same
  commit rather than duplicating it.
  Done when the file exists and `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
  succeeds; nothing calls it yet, so no test changes. Commit `in-flight: the run landing dialog card`.

- [ ] Open the dialog from the run footer's Land
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` only: collect
  `val runErrors by vm.runErrors.collectAsState()` beside `message`; add
  `var landingRun by remember { mutableStateOf(false) }` beside `confirmingLand`/`scrubbing`; in the
  `is InFlightState.Run ->` branch replace `vm.land(onBack)` with
  `vm.clearMessage(); vm.clearRunErrors(); landingRun = true` and reword the comment there (it
  currently claims a run lands without asking, which this task makes false); add a
  `if (landingRun && state is InFlightState.Run)` block beside the `confirmingLand` block that
  builds `RunLandDialog` with `sortieNumber` and `distanceUnit` read from the `Run` state and
  `errors = runErrors`, `onLand = { d, m -> if (vm.landRun(d, m, onBack)) landingRun = false }`
  (closing on the `true` is the plan's "A failed write": a failed write shows the footer's
  `"Couldn't land this sortie."` after the dialog closes — do not close it inside `onDone`), and
  `onDismiss = { landingRun = false; vm.clearRunErrors(); vm.clearMessage() }`, which is what Back
  and a tap outside route through. Leave the `NoChecklist` and `Ready` branches, the
  `LandConfirmDialog` block and its comment, and the `ScrubDialog` block untouched.
  Done when the file compiles and `InFlightScreenTest` passes with 0 failures / 0 errors / 0 skipped,
  including `runScreenShowsPlanAndOffersNoteLandAndScrub` (Land present and enabled, never tapped)
  and `noChecklistLandLandsAndCallsOnBack` (a plan-less run still lands on the first tap with no
  dialog), and the lift note tests that share the file — `seededExerciseNoteShowsMutedLineOnCard`,
  `sortieNoteActionAndLineShowOnChecklistState`, `seededSortieNoteShowsOnNoChecklistState`,
  `sortieNoteActionOnNoChecklistOpensDialogAndSaves`, `noteCellOnActiveCardOpensDialogAndSaves`.
  No test in this task clicks Land on a run. Commit `in-flight: land a run through the dialog`.

- [ ] Test landing a run through `landRun` against Room
  `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt`, on the fixture that file
  already has (`seedMission` → `confirm` gives an ACTIVE Mission, `sortie0`, `viewModel`,
  `awaitRun`, `withTimeout(5_000)`, the `escaped` queue asserted empty at the end of each test).
  Launch the seeded run (`manager.launch(sortie.id)`) first, then write these five tests, each
  named exactly as verify.sh spells it:
  - `runLandWithDistanceAndDurationStoresBoth` — `landRun("5.2", "31", onDone)` returns true,
    `onDone` ran exactly once (a `CompletableDeferred` awaited under `withTimeout`),
    `sortieDao.get` is `LANDED` with `runDistance == 5.2`, `runMinutes == 31.0` and
    `landedAt == fixedInstant.toEpochMilli()`, and `vm.message.value` is null.
  - `runLandWithBlankFieldsStoresNulls` — `landRun("", "", onDone)` returns true, `onDone` ran, the
    sortie is `LANDED`, both run fields are null.
  - `runLandRejectsBadDistanceOnItsField` — for each of `"abc"`, `"-3"`, `"0"`: returns false,
    `vm.runErrors.value` is non-null with `distanceError` set and `minutesError` null, `onDone` never
    ran, no message raised, sortie still `IN_FLIGHT` with both run fields null.
  - `runLandRejectsBadDurationOnItsField` — the mirror with `"5.2"` beside the bad duration.
  - `runLandDoubleTapLandsOnce` — hold the worker thread so the second call *must* hit the `busy`
    guard instead of queueing behind the first: copy the shape of `doubleTapLandsOnce` in
    `InFlightLandScrubTest.kt` (a single-thread `Executor` blocked on a `CountDownLatch`, a scope
    built from that dispatcher, `landRun` twice, assert no `onDone` while held, then `countDown()`
    and await). Assert `onDone` ran once, the row is `LANDED` once, `vm.message` stays null
    (`assertNull(withTimeoutOrNull(1_000) { vm.message.first { it != null } })`) and `escaped` is
    empty; release the latch and cancel the job in a `finally`.
  The brief's fourth Done-when bullet — a run with no plan landing without the dialog — needs no new
  name: `runWithNoPlanStillShowsNoChecklist` already asserts that sortie keeps the `NoChecklist`
  state, and `noChecklistLandLandsAndCallsOnBack` taps Land on it at screen level. Extend
  `runWithNoPlanStillShowsNoChecklist` (or add a case beside it) to call `vm.land(onBack)` and
  assert the row is `LANDED` with both run fields null, which is the path the screen takes for that
  state. That test's fixture inserts a DRAFT Mission by hand, and `land` runs `advance()`, which
  only works on an ACTIVE Mission — set it ACTIVE (`missionDao.update(mission.copy(status =
  MissionStatus.ACTIVE))`) before landing, or the transaction rolls back and the view model reports
  "Couldn't land this sortie."
  Also reword the now-false comment above `launchAndLandSimpleRunFromRunState` ("nothing on this
  screen calls landRun yet"). Do not change `InFlightViewModel.kt`, `RunLanding.kt` or
  `MissionManager.kt` — they already do all of this.
  Done when all five names appear as passing `<testcase>` entries in this run's
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` and `InFlightRunTest` reports 0 failures /
  0 errors / 0 skipped. Commit `in-flight: tests for landing a run with distance and duration`.

- [ ] Test what the dialog card renders, then run the gate
  `app/src/test/java/com/liftoff/app/ui/inflight/LandScrubDialogTest.kt`, in that file's existing
  style (`createComposeRule`, `LiftoffTheme`, `@Config(qualifiers = "w411dp-h2000dp")`, cards
  rendered directly in the main window — never the `Dialog` shell). Two tests, named exactly as
  verify.sh spells them:
  - `runLandDialogLabelsDistanceWithTheConfiguredUnit` — render `RunLandDialogContent` twice in one
    `Column`, once per `DistanceUnit`: assert the title, `"Distance (mi)"` for `MI`,
    `"Distance (km)"` for `KM`, `"Duration (min)"`, and that both fields are blank.
  - `runLandDialogShowsEachFieldErrorUnderItsOwnField` — pass the error text `landRun` raises
    (`RunLanding.kt`'s own strings, not invented ones): a bad distance shows the distance error and
    `assertDoesNotExist()` on the duration error; the mirror for a bad duration; both bad shows
    both. No typing, no focus, no `performClick` on Land, no `waitUntil` on a dialog node.
  Then run the whole gate once, detached, and read its exit status:
  `bash gradlew.sh --stop`; `mkdir -p app/build` (it does not exist on a fresh worktree, and a
  detached redirect into it silently produces no log);
  `nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' &`
  and wait for the `EXIT=` line. Expect `EXIT=0`, no `FROM-CACHE`/`UP-TO-DATE` on
  `:app:testDebugUnitTest`, reports present under `app/build/test-results/testDebugUnitTest`, and
  totals of 0 failures / 0 errors / 0 skipped with the count grown from 463 by the new tests. The
  rest of verify.sh's "must not break" names ride on this whole-suite run and must still pass: the
  lift land/scrub set in `InFlightLandScrubTest.kt` (`landWithEverySetChecked`,
  `landRecordsUncheckedSetsAsNotDone`, `landStoresLandedTime`,
  `landMakesNextRunSortieCurrentWithSimpleRunPlan`, `landingLastSortieFromInFlightClosesMission`,
  `doubleTapLandsOnce`, `secondLandIsRejectedWithoutCrash`), the finished pieces this dialog calls
  (`landRunRejects…WithoutTouchingTheManager`, `landStoresRunDistanceAndRunMinutes`,
  `landWithoutRunActualsLeavesThemNull`, `decimalDistanceIsAccepted`, `bothFieldsBadReportBothErrors`,
  `notesAreStoredTrimmed`, `blankExerciseNoteStoresNull`), the `MissionManager`/domain set,
  `exportedV1SchemaMatchesDatabase` and `neverSaysSession`.
  `doneBodyweightSetStepperShowsBW` fails every few runs with Room's `connection pool has been
  closed` and passes on a re-run of the same tree: re-run the gate once before treating it as
  broken, and do not "fix" it. Put the totals and the `EXIT=` value in the commit body.
  Done when both names pass in this run's XML and the detached gate exited 0. Commit
  `in-flight: render tests for the run landing dialog`.
