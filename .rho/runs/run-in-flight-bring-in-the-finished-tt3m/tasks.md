# Tasks — Run In-Flight: bring in the finished work and show the run plan

Six tasks, in the plan's order. Each is one commit (task 1 is the four cherry-picks themselves),
`area: lowercase summary`, staging named files only (CLAUDE.md). Task 1 must land first: task 2 edits
`PlanFormat.kt`, which task 1 brings in. Tasks 4 and 5 both extend `InFlightRunTest.kt`, so 5 needs 4.

**Named tests.** `.rho/runs/run-in-flight-bring-in-the-finished-tt3m/verify.sh` names 59 tests; 44 already
exist in the tree and no task writes them — they only have to keep passing. The 15 that do not exist yet are
assigned once each: the 8 that arrive with the four picks → task 1; the 7 this run writes → tasks 4 and 5.
No other task writes a test verify.sh names. `runScreenShowsPlanAndOffersNoteLandAndScrub` is the plan's own
screen test, **not** named by verify.sh; it belongs to task 4.

**Gate for every task** (run it detached and wait on an `EXIT=` line — a full run exceeds the shell's time
limit on this machine, and `--no-build-cache` is what proves the tests actually executed):

    bash gradlew.sh --stop
    rm -rf app/build/test-results/testDebugUnitTest
    nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' &

then wait for `EXIT=` in `app/build/gate.log`, confirm `:app:testDebugUnitTest` is neither `FROM-CACHE` nor
`UP-TO-DATE`, and read `tests=`/`failures=`/`errors=` on every `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
Never stage `gate.log`. Never edit or rename `verify.sh`. If the gate trips on
`doneBodyweightSetStepperShowsBW` alone, re-run once before treating it as broken — verify.sh records it as
flaky and outside this brief.

- [x] 1. Bring in the four finished commits
  `git cherry-pick f7522dc 28e401a e3f0af2 cb9316a`, in that order, from `rho/run-sorties-in-flight-9qtx`
  (merge-base with this branch is `eb2bd31`, and this branch has changed only `.rho/` files since, so all four
  should apply cleanly and none touches `.rho/`). Do not pick that branch's `run: …` commits and do not bring in
  any `.rho/runs/` file. Keep each pick's original message (`ui: add distance and run kind formatting`,
  `inflight: parse run landing entry`, `data: store run distance and minutes on land`,
  `inflight: land a run with distance and duration`). If a pick will not apply, re-apply that commit's own diff
  from `git show <sha>` (or rebuild the piece from
  `git diff HEAD...rho/run-sorties-in-flight-9qtx -- <path>`, since `git show branch:path` fails on Git Bash
  here) rather than merging the branch. Then run the gate once, before anything else changes, as the brief
  requires.
  These eight verify.sh names arrive with the picks and must pass unchanged, under these exact names:
  `formatDistanceWholeMile`, `formatRunKindTempo` (f7522dc: `PlanFormatTest`);
  `decimalDistanceIsAccepted`, `bothFieldsBadReportBothErrors` (28e401a: `RunLandingTest`);
  `landStoresRunDistanceAndRunMinutes`, `landWithoutRunActualsLeavesThemNull` (e3f0af2: `MissionManagerTest`);
  `landRunRejectsInvalidDistanceWithoutTouchingTheManager`,
  `landRunRejectsInvalidDurationWithoutTouchingTheManager` (cb9316a: `InFlightViewModelTest`).
  Write nothing else, and use none of the seven names tasks 4–5 own.
  Done when: `git log --oneline` shows the four commits with their original messages on this branch,
  `git diff --stat eb2bd31..HEAD` lists no `.rho/` path beyond this run's own records, and the gate is green
  with those eight names present and passing in this run's XML. The four picks are this task's commits; if the
  session wants a repo record of the gate, add one `run: cherry-picks gate green` commit whose body carries the
  EXIT code and the per-class XML totals, and nothing else.

- [ ] 2. Format a run's minutes
  `app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt`: add
  `internal fun formatMinutes(value: Double): String = "${trimTrailingZero(value)} min"` beside
  `formatDistance` — `10.0` → `10 min`, `2.5` → `2.5 min`, reusing the private `trimTrailingZero` that
  `formatDistance` already uses (plan Decision: minutes are stored as written, not rounded, because a
  `2.5 min` interval rounded to `3 min` is a different workout). No existing helper produces this:
  `formatWeight` is about weight and `FlightPlanSection`'s `"%.0f min"` rounds. Extend
  `app/src/test/java/com/liftoff/app/ui/sortie/PlanFormatTest.kt` with the two cases (`10.0` → `10 min`,
  `2.5` → `2.5 min`). Touch no other file; nothing calls `formatMinutes` yet.
  Commit: `ui: format a run's minutes`.
  Done when: the gate is green and the new `PlanFormatTest` cases appear as passing in this run's XML, with
  task 1's `formatDistanceWholeMile` / `formatRunKindTempo` still passing. verify.sh names no new test for
  this task.

- [ ] 3. Derive and render the run's In-Flight state
  Needs task 1 (`formatDistance` / `formatRunKind`) and task 2 (`formatMinutes`). One commit, because adding a
  variant to the sealed `InFlightState` breaks the screen's exhaustive `when`, and because two existing tests
  change what they observe in this same step.
  - `ui/inflight/InFlightState.kt`: `data class Run(sortieId, sortieNumber, eyebrow, title, notesLine: String?,
    kind: String?, targetDistance: String?, targetPace: String?, segments: List<RunSegmentRow>, sortieNote)`
    and `data class RunSegmentRow(val description: String, val detail: String?)` — deliberately **not** named
    `RunSegment`, which is the Room entity (`data/RunSegment.kt`). The state carries already-formatted strings,
    the way `Ready` carries `SetRow` labels.
    `deriveInFlight(plan, sortie, unit, distanceUnit: DistanceUnit = DistanceUnit.MI)` — the default keeps all
    22 existing call sites compiling — with the run branch **ahead of** the
    `plan == null || plan.exercises.isEmpty()` check at :86, so `sortie.type == SortieType.RUN && plan != null`
    → `Run`, a run with no plan keeps `NoChecklist`, and a LIFT with a segments-only plan keeps `NoChecklist`.
    Reuse the eyebrow (:76) and the title rule (:79-83) unchanged; `needsLandConfirmation` (:38) stays
    `Ready`-only, so a run lands with no confirmation. Formatting happens here, not in the composable:
    `notesLine` = plan notes if not blank, else `sortie.focus` if not blank, else null, and null when it equals
    `title`; `kind` = `runKind?.takeIf { it.isNotBlank() }?.let(::formatRunKind)`; `targetDistance` =
    `targetDistance?.let { formatDistance(it, distanceUnit) }`; `targetPace` = the string as written,
    `takeIf { it.isNotBlank() }`; each segment's detail = the present ones of `formatDistance(distance, unit)`
    and `formatMinutes(minutes)` joined with `" · "`, null when neither is present. A null or blank value
    becomes a null field — that is what makes "a row appears only when it has a value" testable.
  - `ui/inflight/InFlightStates.kt`: add `settings.settings.map { it.distanceUnit }.distinctUntilChanged()` to
    the `combine` (:19-23) as a fourth flow and pass it to `deriveInFlight`, next to `weightUnit`. That is the
    whole "changing the distance unit updates the screen live" change.
  - New `ui/inflight/RunContent.kt`, written whole in one call: the same teal header box `NoChecklistContent`
    (:235-272) uses (`Eyebrow` in Mustard + `DisplayTitle` uppercase), `DuoStripe`, `SortieNoteStrip`, then a
    body (`weight(1f)` + `verticalScroll`, a generated plan can carry 20 segments) holding the notes line and
    `InkRuledListRow`s — Kind / Target distance / Target pace, then one row per segment — each emitted only when
    its value is non-null, then `Footer(message, onLand, onScrub)`. No set counter, no progress bar, no set rows.
  - `ui/inflight/InFlightScreen.kt` — only these three changes, each anchored on a line copied from a fresh
    read: a `is InFlightState.Run ->` arm in the main `when (state)` (:66) whose Land is
    `vm.clearMessage(); vm.land(onBack)` straight through, whose Scrub is `vm.clearMessage(); scrubbing = true`
    and whose note is `noteTarget = NoteTarget.Sortie(run.sortieNote)`, exactly as the `NoChecklist` arm; the
    scrub `sortieNumber` `when` (:175-179) gains `is InFlightState.Run -> run.sortieNumber` **and its
    `else -> null` is replaced by `InFlightState.Loading -> null`**, so the compiler — not a reviewer — catches a
    state missing from that `when` (this is the trap the brief warns about: with the `else`, Scrub is silently
    dead on runs and no view-model test can see it); and `private` → `internal` on `SortieNoteStrip` (:341) and
    `Footer` (:938) so `RunContent.kt` can call them. No lift code path changes. Do not wire `landRun`,
    `parseRunEntry` or `runErrors` into the screen — the next brief's dialog does that.
  - Same commit, the two existing tests this brief changes what they observe (say so in the commit body so the
    PR can repeat it): `InFlightStatesTest.noChecklist()` Case 2 (:251-272) — a RUN sortie with a
    segments-only plan (`runKind = "steady"`, `targetDistance = 5000.0`, `targetPace = "5:00"`, one segment
    `Warmup` 800.0 / 4.0) — becomes a `Run` assertion in place: title `Run Plan`, kind `Steady`, distance
    `5000 mi` under the defaulted MI, one segment row `Warmup` / `800 mi · 4 min`; Case 1 (LIFT, no plan) is
    unchanged. `InFlightLandScrubTest.landingARunNeedsNoChecklist` (:257-275) waits for `InFlightState.Run`
    through a new `awaitRun` helper and asserts `sortieNumber == 1`; the rest — land, `onDone` once, `LANDED`,
    no message — stays, and `awaitNoChecklist` goes with the change if nothing else uses it.
  - Do not touch `FlightPlanSection.kt` or `MissionScreen.kt` (their hardcoded `"%.2f km"` is out of scope) —
    record that in the commit body. No Room schema change, no migration.
  Commit: `inflight: derive and show the run in-flight state`.
  Done when: `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` passes first (it finds
  every call site needing the new parameter), then the gate is green with `noChecklist` and
  `landingARunNeedsNoChecklist` still passing under those names, and the must-not-break In-Flight names
  (`noChecklistNeedsNoConfirmation`, `noChecklistLandLandsAndCallsOnBack`,
  `seededSortieNoteShowsOnNoChecklistState`, `sortieNoteActionOnNoChecklistOpensDialogAndSaves`,
  `scrubFromInFlightKeepsCheckedSetsAndStoresReason`, `doubleTapLandsOnce`, `exportedV1SchemaMatchesDatabase`)
  still pass. Write none of the seven names tasks 4–5 own.

- [ ] 4. Test the run state derived from a Flight Plan
  Needs task 3. New `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt`, Robolectric
  `@RunWith(RobolectricTestRunner::class)` `@Config(sdk = [34])`, reusing the fixture shape already in
  `InFlightLandScrubTest.kt`: in-memory Room with `allowMainThreadQueries()`, a
  `PreferenceDataStoreFactory`-backed `SettingsStore`, `MissionManager` with
  `Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` and `zone = { ZoneOffset.UTC }`, a
  `Dispatchers.Unconfined` scope with a `CoroutineExceptionHandler` that collects anything that escapes, and
  `seedMission()` = `onAppOpen()` + `confirm()` on the default `RLRLR` pattern, which leaves sortie 0 a PLANNED
  run with its SIMPLE_RUN plan and `focus = "easy"`. **Every wait is a `withTimeout(5_000)`.** Write these three
  tests, named exactly as verify.sh spells them:
  - `runStateFromSeededPlanWithSegments` — `seedMission()`, launch sortie 0, then `flightPlanDao.writePlan` over
    its simple plan with a generated one (`writePlan` deletes the existing plan first): `runKind = "tempo"`,
    `title = "Tempo Tuesday"`, `notes = "Hold it honest"`, `targetDistance = 5.0`, `targetPace = "9:30/mi"`, two
    segments (`Warm-up` 1.0 / 10.0, `Tempo` 3.1 / 25.0). Assert through `vm.state` that it is `Run`, with eyebrow
    `IN FLIGHT · SORTIE 1`, `sortieNumber == 1`, title `Tempo Tuesday`, notes line `Hold it honest`, kind
    `Tempo`, distance `5 mi`, pace `9:30/mi`, and rows `Warm-up` / `1 mi · 10 min` and `Tempo` / `3.1 mi · 25 min`;
    then `deriveInFlight(plan, sortie, WeightUnit.LB, DistanceUnit.KM)` on the same plan gives `5 km` and
    `1 km · 10 min`. Assert the unit explicitly rather than relying on the default. There is no shared run-plan
    fixture in the repo, so the plan is seeded here through the DAO.
  - `runStateFromSimpleRunPlan` — `seedMission()`, `manager.launch(sortie 0)`, wait for `Run`: title `Run`,
    `notesLine` = `easy` (`simpleRunPlan` copies `sortie.focus` into `notes`), `kind`, `targetDistance` and
    `targetPace` all null, `segments` empty — the "no empty rows" assertion. Also derive directly with the same
    plan but `notes = null` and assert `notesLine` still reads the focus `easy`.
  - `runWithNoPlanStillShowsNoChecklist` — a RUN sortie inserted with no plan row stays `NoChecklist` (the case
    `InFlightStatesTest.kt:376` and `InFlightViewModelTest.kt:548` already rely on).
  Also add the plan's own screen test to `InFlightScreenTest.kt`, same shape as
  `sortieNoteActionOnNoChecklistOpensDialogAndSaves` (:998): `runScreenShowsPlanAndOffersNoteLandAndScrub` —
  insert an IN_FLIGHT RUN sortie with `focus = "easy"`, `writePlan` it `simpleRunPlan(sortie)`, set the content,
  `waitUntil(5_000)` for `RUN` (the uppercased title), assert `easy` is shown, `No checklist for this sortie.`
  is not, and `SORTIE NOTE`, `LAND` and `SCRUB` are displayed and enabled. Open no dialog and type in no field:
  a `BasicTextField` inside a `Dialog` never lets Compose go idle under Robolectric. The test only reads, so
  `tearDown` closing the database is safe. verify.sh does not name this test: if Robolectric cannot reach idle,
  record that in the commit body and drop the test rather than fight it — the gate does not depend on it.
  Commit: `inflight: test the run state derived from a plan`.
  Done when: the gate is green and those three names appear as passing in this run's XML (prove it by grepping
  the method names out of `app/build/test-results/testDebugUnitTest/TEST-*.xml`). Write none of task 5's four
  names here.

- [ ] 5. Test landing, scrubbing, noting and the live unit on a run
  Needs tasks 3 and 4; extend `InFlightRunTest.kt` with its existing fixture. Write these four tests, named
  exactly as verify.sh spells them:
  - `runDistanceUnitChangeUpdatesStateLive` — while the `Run` state of the seeded tempo plan is showing,
    `settingsStore.setDistanceUnit(DistanceUnit.KM)` and wait for the distance string to move from `5 mi` to
    `5 km` on the state the view model publishes, so it is the screen's own state flow that moves, not a helper
    called a second time.
  - `launchAndLandSimpleRunFromRunState` — `seedMission()`, `manager.launch(sortie 0)`, wait for `Run`, then
    `vm.land(onDone)`: `onDone` runs exactly once, the sortie row is `LANDED` with `landedAt` equal to the fixed
    clock, `vm.message.value` is null, and `runDistance` / `runMinutes` stay null — this run's Land stores no
    actuals, because the brief keeps today's Land and nothing here uses `landRun` yet.
  - `scrubFromRunStateStoresReason` — from the `Run` state, `vm.scrub("  felt sick  ", onDone)`: the sortie is
    `SCRUBBED`, `scrubReason` is `"felt sick"`, the Mission moved on, `message` is null.
  - `sortieNoteSavesOnRunWithAPlan` — the sortie note on a run that has a Flight Plan, through the same note
    rule the lifts use: `vm.saveSortieNote` stores the text trimmed on the sortie row and it survives a fresh
    DAO read.
  Commit: `inflight: test landing, scrubbing and noting a run`.
  Done when: the gate is green and those four names appear as passing in this run's XML, and task 4's three
  names still pass.

- [ ] 6. Full gate, and the totals as a repo record
  Needs task 5. Run the whole gate above once, cold, and read every report under
  `app/build/test-results/testDebugUnitTest/`: `:app:testDebugUnitTest` executed rather than `FROM-CACHE` or
  `UP-TO-DATE`, every report `failures="0" errors="0"`, and all fifteen of this run's names present —
  `formatDistanceWholeMile`, `formatRunKindTempo`, `decimalDistanceIsAccepted`, `bothFieldsBadReportBothErrors`,
  `landStoresRunDistanceAndRunMinutes`, `landWithoutRunActualsLeavesThemNull`,
  `landRunRejectsInvalidDistanceWithoutTouchingTheManager`,
  `landRunRejectsInvalidDurationWithoutTouchingTheManager`, `runStateFromSeededPlanWithSegments`,
  `runStateFromSimpleRunPlan`, `runWithNoPlanStillShowsNoChecklist`, `runDistanceUnitChangeUpdatesStateLive`,
  `launchAndLandSimpleRunFromRunState`, `scrubFromRunStateStoresReason`, `sortieNoteSavesOnRunWithAPlan` — plus
  the must-not-break block intact. The plan warns that more existing tests may assert the old fall-through than
  the two task 3 found; each one that surfaces here is a test of behaviour this brief changes, so re-assert it
  as `Run` rather than deleting or gutting it, and commit that fix as `inflight: …` staging only the test file.
  If the gate trips on `doneBodyweightSetStepperShowsBW` alone, re-run once before treating it as broken, and do
  not "fix" it.
  Done when: the gate is green and the EXIT code, the executed-not-FROM-CACHE line and the per-class
  `tests=`/`failures=`/`errors=` totals are recorded in a commit body — the totals are the evidence, and a note
  file is not a repo artifact, so if this task changed no code, record them with
  `git commit --allow-empty --cleanup=verbatim -F <msg-file-in-the-worktree-root>` under
  `run: full gate totals` and say in the body that it carries no code.
