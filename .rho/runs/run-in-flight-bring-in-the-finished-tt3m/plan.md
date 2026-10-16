# Run plan — In-Flight: bring in the finished work and show the run plan

Brief: `.rho/runs/run-in-flight-bring-in-the-finished-tt3m/brief.md`.

## Approach

**Bring the finished work in as it is.** The four commits the brief names (`f7522dc`, `28e401a`,
`e3f0af2`, `cb9316a`) sit on `rho/run-sorties-in-flight-9qtx`. Checked in this checkout: that branch's
merge-base with HEAD is `eb2bd31`, this branch has changed only `.rho/` files since that base, and each
of the four commits touches only app code — `PlanFormat.kt` + its test, `RunLanding.kt` +
`RunLandingTest.kt`, `MissionManager.kt` + `MissionManagerTest.kt`, `InFlightViewModel.kt` +
`InFlightViewModelTest.kt`. None touches `.rho/`, so `git cherry-pick` in that order should apply all
four with no conflict and bring no run records. The `run: …` commits interleaved between them on that
branch are simply not picked, which is what the brief asks for.

**Then build the missing half.** Those four commits are the formatting, parsing and storage layer. What
is missing is the state and the screen. `deriveInFlight` (`InFlightState.kt:74`) sends every run to
`NoChecklist`, because its `plan == null || plan.exercises.isEmpty()` check at :86 catches a run plan —
a run plan carries `segments`, not `exercises`. So the change is one new `InFlightState.Run` variant,
derived in that same one place, rendered by one new arm of the screen's `when`, in a new file.

This fits the codebase's shape: `InFlightState` is derived in exactly one pure function, published by
one `combine` (`InFlightStates.kt:19`), held by `InFlightViewModel.state`, and rendered by one
exhaustive `when (state)` (`InFlightScreen.kt:66`). Adding a variant means touching the derivation, the
`combine` and the `when` — nothing else. It is not new design work either: everything the run screen
shows already exists on `FlightPlanDetail` (`plan: FlightPlan` with `title`, `notes`, `runKind`,
`targetDistance`, `targetPace`, plus `segments: List<RunSegment>`), and
`FlightPlanSection.kt:60-103` already renders this same block for Launchpad and Mission. The run screen
formats what the plan already carries, using the `formatDistance` / `formatRunKind` helpers that step 1
brings in.

The state carries **already-formatted strings**, the way `Ready` already carries `SetRow` labels and
`NoChecklist` already carries its `eyebrow` and `title`. That is what makes the brief's "derivation in
plain Kotlin that a Robolectric test can drive" true: every assertion is a string comparison on the
state, with no Compose in the way.

`InFlightScreen.kt` is 991 lines, so the run layout goes in its own file (`RunContent.kt`) and the diff
to the screen itself is a few small arms.

## Files and parts involved

**Brought in by cherry-pick, unchanged:**

- `ui/sortie/PlanFormat.kt` — `internal fun formatDistance(value: Double, unit: DistanceUnit): String`
  (`5 mi`, `5.5 km`, trailing `.0` trimmed via the shared private `trimTrailingZero`) and
  `internal fun formatRunKind(kind: String): String` (`tempo` → `Tempo`). `internal` is module-wide in
  Kotlin, so `ui.inflight` can call both with no visibility change.
- `ui/inflight/RunLanding.kt` — `RunEntry.Valid(distance, minutes)` /
  `RunEntry.Invalid(distanceError, minutesError)`, `parseRunEntry(distanceText, minutesText)`. Nothing
  in this run calls it; the next brief's landing dialog does.
- `data/MissionManager.kt` — `land(sortieId, runDistance = null, runMinutes = null)`, written inside the
  existing `withTransaction` before `markOpenSetsNotDone` and `advance`. No schema change:
  `Sortie.runDistance` / `runMinutes` already exist (`Sortie.kt:33-34`).
- `ui/inflight/InFlightViewModel.kt` — `runErrors`, `clearRunErrors()`,
  `landRun(distanceText, minutesText, onDone): Boolean`. Left unused by this run, as the brief says.

**Written by this run:**

- `ui/sortie/PlanFormat.kt` — one addition next to `formatDistance`:
  `internal fun formatMinutes(value: Double): String = "${trimTrailingZero(value)} min"` (`10 min`,
  `2.5 min`). Needed because no existing helper produces `10 min` from `10.0`: `formatWeight` is about
  weight, and `FlightPlanSection`'s `"%.0f min"` rounds. Add two cases to `PlanFormatTest`
  (`10.0` → `10 min`, `2.5` → `2.5 min`).
- `ui/inflight/InFlightState.kt`
  - new `InFlightState.Run`: `sortieId`, `sortieNumber`, `eyebrow`, `title`, `notesLine: String?`,
    `kind: String?`, `targetDistance: String?`, `targetPace: String?`,
    `segments: List<RunSegmentRow>`, `sortieNote: String?`;
  - `data class RunSegmentRow(val description: String, val detail: String?)` — deliberately **not**
    called `RunSegment`, which is the Room entity (`data/RunSegment.kt`: `order`, `description`,
    `distance?`, `minutes?`);
  - `deriveInFlight(plan, sortie, unit, distanceUnit: DistanceUnit = DistanceUnit.MI)`, with the run
    branch **ahead of** the `plan == null || plan.exercises.isEmpty()` check at :86:
    `sortie.type == SortieType.RUN && plan != null` → `Run`. A run with no plan keeps `NoChecklist`; a
    LIFT with a segments-only plan keeps `NoChecklist`. The eyebrow (:76) and the title rule (:79-83 —
    plan title if not blank, else `sortie.focus`, else `"Sortie n"`) are reused unchanged, and
    `needsLandConfirmation` (:38) stays `Ready`-only, so a run lands with no confirmation.
  - Formatting happens here, not in the composable:
    - `notesLine` = `plan.plan.notes` if not blank, **else `sortie.focus` if not blank**, else null —
      the brief's "the plan notes, or the focus when there are no notes" — and then null if it equals
      `title` (see Decisions);
    - `kind` = `plan.plan.runKind?.takeIf { it.isNotBlank() }?.let(::formatRunKind)`;
    - `targetDistance` = `plan.plan.targetDistance?.let { formatDistance(it, distanceUnit) }`;
    - `targetPace` = `plan.plan.targetPace?.takeIf { it.isNotBlank() }`;
    - each segment: `description`, and a detail of `formatDistance(distance, distanceUnit)` and
      `formatMinutes(minutes)`, the present ones joined with `" · "`, null when neither is present.

    A null or blank value becomes a null field, which is what makes "a row appears only when it has a
    value" testable.
- `ui/inflight/InFlightStates.kt` — the `combine` (:19-23) gains
  `settings.settings.map { it.distanceUnit }.distinctUntilChanged()` as a fourth flow and passes it to
  `deriveInFlight`, next to the existing `weightUnit` flow. That is the whole "changing the unit updates
  the screen live" change.
- `ui/inflight/RunContent.kt` (new) — the run body, built from the pieces `NoChecklistContent`
  (`InFlightScreen.kt:235-272`) already uses: the teal header box (`Eyebrow` in Mustard + `DisplayTitle`
  uppercase), `DuoStripe`, `SortieNoteStrip`, then a scrollable body (`weight(1f)` + `verticalScroll`,
  since a generated run plan can carry 20 segments) holding the notes line and `InkRuledListRow`s — Kind
  / Target distance / Target pace, then one row per segment — each emitted only when its value is
  non-null, then `Footer(message, onLand, onScrub)`. No set counter, no progress bar, no set rows.
- `ui/inflight/InFlightScreen.kt` — only these changes:
  - a `is InFlightState.Run ->` arm in the main `when (state)` (:66). Required: that `when` is
    exhaustive over the sealed interface, so the build fails without it. Its Land is
    `vm.clearMessage(); vm.land(onBack)` — straight through, no dialog; `needsLandConfirmation` is
    already false for anything that is not `Ready`, so the `LandConfirmDialog` block at :157 needs no
    change. Its Scrub is `vm.clearMessage(); scrubbing = true` and its note is
    `noteTarget = NoteTarget.Sortie(run.sortieNote)`, exactly as the `NoChecklist` arm;
  - the scrub `sortieNumber` `when` (:175-179): add `is InFlightState.Run -> run.sortieNumber` **and
    replace `else -> null` with `InFlightState.Loading -> null`**, so the `when` is exhaustive and the
    compiler, not a reviewer, catches the next state that is missing here. This is the trap the brief
    warns about: with the `else`, a missing arm compiles and leaves Scrub silently dead on a run, and
    no view-model test can see it, because `vm.scrub` does not go through this `when`;
  - `private` → `internal` on `SortieNoteStrip` (:341) and `Footer` (:938) so `RunContent.kt` can call
    them. No lift code path changes.
- Read, not changed: `data/FlightPlan.kt`, `data/RunSegment.kt`, `data/Relations.kt`
  (`FlightPlanDetail(plan, exercises, segments)`), `data/FlightPlanDao.kt` (`getPlan`, `writePlan` —
  `writePlan` deletes the sortie's existing plan first, so it can replace the simple plan in a test),
  `domain/SortiePlanning.kt` (`simpleRunPlan` at :21: `title = "Run"`, `notes = sortie.focus`,
  `runKind` / `targetDistance` / `targetPace` null, no exercises, no segments), `domain/Missions.kt:56`
  (a run sortie's `focus` is `"easy"` on the default pattern), `settings/Settings.kt`
  (`DistanceUnit { MI, KM }`, default `MI`) and `SettingsStore.setDistanceUnit` (:79), `ui/theme/*`
  (`Eyebrow`, `DisplayTitle`, `DuoStripe`, `InkRuledListRow`, `UnderlinedTextButton`, `LiftoffType`,
  colours), `DESIGN.md` §5.3, §6, §7.5.3, §8, and `design/README.md`.
- `ui/sortie/FlightPlanSection.kt` and `ui/mission/MissionScreen.kt` are **not** touched. They hardcode
  `"%.2f km"` for run distances (`FlightPlanSection.kt:78, :94`, `MissionScreen.kt:180`) and ignore the
  distance setting; the brief is In-Flight only. Say so in the PR, change nothing.

## Order

Each step compiles and passes the suite on its own; commit after each with `area: lowercase summary`,
staging named files only.

1. **Cherry-pick** `f7522dc 28e401a e3f0af2 cb9316a`, in that order, then run the gate once before
   anything else changes, as the brief requires. If a pick conflicts, re-apply that commit's own diff
   from `git show <sha>` (or rebuild the piece from that branch's plan at
   `git show rho/run-sorties-in-flight-9qtx:.rho/runs/run-sorties-in-flight-9qtx/plan.md`) rather than
   merging the branch.
2. `inflight: derive and show the run in-flight state` — `formatMinutes` and its `PlanFormatTest`
   cases, `Run` + `RunSegmentRow`, the run branch in `deriveInFlight`, the defaulted `distanceUnit`
   parameter, the `combine` in `InFlightStates.kt`, `RunContent.kt`, and the `InFlightScreen.kt`
   changes. These are one commit because adding the variant breaks the screen's exhaustive `when`, and
   because two existing tests must change in the same step (below). A compile-only pass first
   (`bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`, ~35 s) finds every call
   site that needs the new parameter.
3. `inflight: test the run in-flight state` — the new test file and the new screen test (below), then
   the full gate.

## How the result is tested

**New file** `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt`, Robolectric
`@RunWith(RobolectricTestRunner::class)` `@Config(sdk = [34])`, reusing the fixture shape already in
`InFlightLandScrubTest.kt` (in-memory Room with `allowMainThreadQueries()`, a
`PreferenceDataStoreFactory`-backed `SettingsStore`, `MissionManager` with
`Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` and `zone = { ZoneOffset.UTC }`, a
`Dispatchers.Unconfined` scope with a `CoroutineExceptionHandler` that collects anything that escapes,
and `seedMission()` = `onAppOpen()` + `confirm()` on the default `RLRLR` pattern, which leaves sortie 0 a
PLANNED run with its SIMPLE_RUN plan and `focus = "easy"`). **Every wait is a `withTimeout(5_000)`.**

- `runStateFromSeededPlanWithSegments` — `seedMission()`, launch sortie 0, then `flightPlanDao.writePlan`
  over its simple plan with a generated one: `runKind = "tempo"`, `title = "Tempo Tuesday"`,
  `notes = "Hold it honest"`, `targetDistance = 5.0`, `targetPace = "9:30/mi"`, two segments
  (`Warm-up` 1.0 / 10.0, `Tempo` 3.1 / 25.0). Assert through the view model's state that it is `Run`,
  with eyebrow `IN FLIGHT · SORTIE 1`, `sortieNumber == 1`, title `Tempo Tuesday`, notes line
  `Hold it honest`, kind `Tempo`, distance `5 mi`, pace `9:30/mi`, and rows `Warm-up` /
  `1 mi · 10 min` and `Tempo` / `3.1 mi · 25 min`. Then assert
  `deriveInFlight(plan, sortie, WeightUnit.LB, DistanceUnit.KM)` on the same rows gives `5 km` and
  `1 km · 10 min`. There is no shared run-plan fixture in the repo, so the plan is seeded here through
  the DAO — the nearest existing model is the segments-only run plan in `InFlightStatesTest.kt:258-264`.
- `runStateFromSimpleRunPlan` — `seedMission()`, `manager.launch(sortie 0)`, wait for `Run`: title `Run`,
  `notesLine` = `easy` (`simpleRunPlan` copies `sortie.focus` into `notes`), `kind`,
  `targetDistance`, `targetPace` all null, `segments` empty. That is the "no empty rows" assertion: the
  only fields the state carries are the ones the screen will print. Also derive directly with the same
  plan but `notes = null` and assert `notesLine` still reads the focus `easy`.
- `runWithNoPlanStillShowsNoChecklist` — a RUN sortie inserted with no plan row stays `NoChecklist` (the
  case `InFlightStatesTest.kt:376` and `InFlightViewModelTest.kt:548` already rely on).
- `launchAndLandSimpleRunFromRunState` — `seedMission()`, `manager.launch(sortie 0)`, wait for `Run`, then
  `vm.land(onDone)`: `onDone` runs once, the sortie row is `LANDED` with `landedAt` equal to the fixed
  clock, `vm.message.value` is null, and `runDistance` / `runMinutes` stay null — this run's Land stores
  no actuals, because the brief keeps today's Land behaviour and nothing here uses `landRun` yet.
- `scrubFromRunStateStoresReason` — from the `Run` state, `vm.scrub("  felt sick  ", onDone)`: the sortie
  is `SCRUBBED`, `scrubReason` is `"felt sick"`, the Mission moved on, `message` is null.
- `runDistanceUnitChangeUpdatesStateLive` — while the `Run` state of the seeded tempo plan is showing,
  `settingsStore.setDistanceUnit(DistanceUnit.KM)` and wait for the distance string to move from `5 mi`
  to `5 km` on the state the view model publishes.

**New screen test** in `InFlightScreenTest.kt`, same shape as `sortieNoteActionOnNoChecklistOpensDialogAndSaves`
(:998): `runScreenShowsPlanAndOffersNoteLandAndScrub` — insert an IN_FLIGHT RUN sortie with
`focus = "easy"`, `writePlan` it the simple plan (`simpleRunPlan(sortie)`), set the content, wait up to
5 s for `RUN` (the uppercased title). Assert `easy` is shown, `No checklist for this sortie.` is not, and
`SORTIE NOTE`, `LAND` and `SCRUB` are displayed and enabled. Dialogs are not opened: a `BasicTextField`
inside a `Dialog` never lets Compose go idle under Robolectric (the reason given at :993). The test only
reads, so tearDown closing the database is safe.

**Two existing tests change, because this brief changes what they observe** (say so in the PR):

- `InFlightStatesTest.noChecklist()` Case 2 (:251-272) seeds a RUN sortie with a segments-only plan
  (`runKind = "steady"`, `targetDistance = 5000.0`, `targetPace = "5:00"`, one segment `Warmup`
  800.0 / 4.0) and asserts `NoChecklist`. It becomes a `Run` assertion in place: title `Run Plan`, kind
  `Steady`, distance `5000 mi` under the defaulted MI, one segment row `Warmup` / `800 mi · 4 min`.
  Case 1 (LIFT, no plan) is unchanged.
- `InFlightLandScrubTest.landingARunNeedsNoChecklist` (:257-275): `seedMission()` gives sortie 0 a
  SIMPLE_RUN plan, so after launch its state is `Run` and `awaitNoChecklist` (:152) would time out. Wait
  for `InFlightState.Run` instead (a new `awaitRun` helper) and assert `sortieNumber == 1`; the rest —
  land, `onDone` once, `LANDED`, no message — stays. `awaitNoChecklist` has no other user in that file,
  so it goes with the change.

Everything else stays green unchanged. `deriveInFlight` is written at 23 places (its definition,
`InFlightStates.kt`, 16 in `InFlightStatesTest.kt`, 5 in `InFlightViewModelTest.kt`); the defaulted
`distanceUnit` keeps all 22 existing calls compiling. `InFlightStatesTest.noChecklistNeedsNoConfirmation`
Case 1 (RUN, **no plan**) and Case 2 (LIFT, empty plan), `InFlightViewModelTest.sortieNoteSavesOnSortieWithNoPlan`
and `noPlan`, and the RUN sorties in `InFlightScreenTest.kt` (:77, :350, :447, :890, :1012) all have no
plan and stay `NoChecklist`. The four picked commits' own tests (`PlanFormatTest`, `RunLandingTest`, the
`MissionManagerTest` and `InFlightViewModelTest` additions) must pass unchanged.

Gate, run detached and waited on an `EXIT=` sentinel — a full run outlasts the shell's time limit here:

    bash gradlew.sh --stop
    rm -rf app/build/test-results/testDebugUnitTest
    nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > gate.log 2>&1; echo EXIT=$? >> gate.log' &

then wait for `EXIT=` and read the JUnit XML under `app/build/test-results/testDebugUnitTest/`: every
report `failures="0" errors="0"`, and `:app:testDebugUnitTest` executed rather than `FROM-CACHE`
(`--no-build-cache` is what proves the tests actually ran). Quote the totals in the PR. Do not stage
`gate.log`.

## Risks and uncertainties

- **The scrub `when`'s `else -> null`** (`InFlightScreen.kt:178`) is the named trap. A view-model scrub
  test cannot catch it, since `vm.scrub` never passes through that `when`, and Robolectric cannot open
  the scrub dialog. The fix is structural: the `else` is replaced by an explicit `Loading` arm, so a
  missing `Run` arm (or any future state) is a compile error.
- **More existing tests may assert the old fall-through** than the two found by reading. They surface
  when the gate runs; each is a test of behaviour this brief changes, so it is re-asserted as `Run`, not
  deleted.
- **Cherry-pick conflicts** are unlikely (verified above) but if one happens the fix is that commit's own
  diff applied by hand, not a merge.
- **`landRun`, `parseRunEntry` and `runErrors` arrive with no UI consumer** — the brief says nothing in
  this run uses them, and the next brief adds the landing dialog. They are exercised only by their own
  tests. Do not wire them into the screen.
- **`deriveInFlight`'s defaulted unit** keeps call sites compiling, but a test that expects `mi` output
  while its fixture is seeded in km would then be wrong silently — the km and mi assertions are written
  explicitly rather than relying on the default.
- **Compose screen tests that write through Room** must unmount the screen before `tearDown` closes the
  database, or the screen's live `observe()` fails with "connection pool has been closed".
- `doneBodyweightSetStepperShowsBW` (`InFlightScreenTest`) fails every few runs with that same closed
  pool and passes on a re-run of the same tree. It is not this run's work: re-run the gate before
  treating it as broken, and do not "fix" it.
- `runMinutes` is a `Double`, so fractional minutes are stored as given; no rounding.
- Machine hazards: Gradle daemons get reaped on this box and the checkout is shared with other
  terminals, so a build may wait on a lock. A hung Robolectric run has happened before here — `jstack`
  it and re-measure rather than concluding the suite is broken.
- No network, no Room schema change, no migration.

## Decisions

- **Added by review — how a segment's minutes are written.** As stored, trailing `.0` trimmed:
  `10 min`, `2.5 min`, via a new `formatMinutes` beside `formatDistance`. Alternative: round to whole
  minutes, as `FlightPlanSection`'s `"%.0f min"` does. The schema allows fractional minutes, and a
  `2.5 min` interval rounded to `3 min` (or `2 min`) is a different workout. Distances are already shown
  this way.
- **Is a distance converted when the unit changes, or shown as stored?** Shown as stored, in whichever
  unit is configured: a plan's `targetDistance = 5.0` reads `5 mi` in miles and `5 km` in km.
  Alternative: convert the number (5.0 → `8.05 km`). The helper the brief makes this run bring in
  (`formatDistance`, `f7522dc`) is written to show it as-is, the plan stores no unit of its own, and
  §7.5.3 says distance and pace are already "in the owner's configured unit" — converting would invent a
  distance the plan never said.
- **What a segment row reads.** `04  Warm-up  1 mi · 10 min`: the segment's `description` as the row
  title; its `distance` and `minutes` joined with `" · "` as the detail; a part that is missing is left
  out; and a segment with neither gets its description alone, with no detail. Alternative: a `0 min` for
  a missing duration, or two lines per segment. This is the row shape `FlightPlanSection.kt:92-102`
  already produces for the same segments on Launchpad and Mission, so one run reads the same way on both
  screens.
- **How the run kind is written.** Capitalised: `tempo` → `Tempo`, `easy` → `Easy`. Alternative: the raw
  lowercase word the schema stores. The schema's five kinds are labels, every other row on this screen is
  a label, and `formatRunKind` — which the brief makes this run bring in — already title-cases it.
- **Is a unit appended to the pace?** No: the plan's `targetPace` string is shown exactly as written
  (`9:30/mi`, `easy`). Alternative: append `/mi` or `/km`. `targetPace` is free text up to 20 characters
  (§7.5.3) that the coach wrote with its own unit already in it; appending one would contradict the plan
  and would be wrong for a pace that has no unit.
- **When the notes line would repeat the title, is it shown?** Hidden. If the plan has no title, the
  title falls back to the sortie's focus, and the simple 'Run' plan copies that same focus into
  `notes`, so the same words would print twice: once as the title and again just under it.
  Alternative: show it anyway. `FlightPlanSection` already hides a note that equals the
  title (`FlightPlanSection.kt:108-110`), so hiding it is what the owner already sees elsewhere in the
  app. A note equal to the focus is *not* hidden here: the brief asks for the focus on this screen.
- **Are kind, target distance and target pace separate rows, or one summary line?** Separate ink-ruled
  rows, numbered in sequence with the segments (`01 Kind`, `02 Target distance`, `03 Target pace`,
  `04 …`), each only when it has a value. Alternative: one line under the title — `Tempo · 5 mi · 9:30/mi`.
  The brief lists them as three separate things the body shows and asks for the segments "as ink-ruled
  rows"; rows are how every other plan in the app is shown, and a summary line has to be rebuilt anyway
  whenever a value is missing.
