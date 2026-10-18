# Tasks — landed-history-screen-3c5m

Seven tasks, in order. Each is one commit, in the plan's order of work, and each compiles
with its own tests passing before it is committed.

## Rules every task follows

- Read `.rho/runs/landed-history-screen-3c5m/brief.md`, `plan.md` and `map.md` first. Your own
  entry below is the only part of the plan you implement.
- Never touch `.rho/runs/landed-history-screen-3c5m/verify.sh`. It is the runner's.
- Commit message: `area: lowercase summary`. Stage named files only, one at a time — never `git add -A`.
  LF endings. CLAUDE.md §2 vocabulary: never "session" for a sortie. Comments: 1–2 lines, why not what.
- No Room schema change: no new entity, index, version, or `@Delete` on `MissionDao`/`SortieDao`
  (`noWipeForMissionsAndOnlyTheByIdDeleteForSorties` fails on a new delete,
  `exportedV1SchemaMatchesDatabase` on a schema change).
- Iterate with a compile check (`bash gradlew.sh :app:assembleDebug`), then run your own tests once,
  cold and detached, and read the log and the JUnit XML rather than the tail:
  `bash gradlew.sh --stop`; `mkdir -p app/build` (a fresh worktree has none, so no log appears);
  `nohup bash -c 'bash gradlew.sh :app:assembleDebug > app/build/gate.log 2>&1; bash gradlew.sh :app:testDebugUnitTest --tests "<class>" --no-build-cache >> app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' &`
  and `wait` on the `EXIT=` sentinel (the second `EXIT=` line is the test run's). Prove the test ran by
  its totals in `app/build/test-results/testDebugUnitTest/TEST-*.xml`, not by a green tail.
- Test fixture (the one `MissionStatesTest` already uses, so copy it):
  `@RunWith(RobolectricTestRunner::class)` or `AndroidJUnit4`, `@Config(sdk = [34])`,
  `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`, a
  `PreferenceDataStoreFactory`-backed `SettingsStore` deleted in `tearDown`, and a `MissionManager`
  with `Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` and `zone = { ZoneOffset.UTC }`.
  Insert each `Mission` once (`weekStart` is UNIQUE). Any test that lands or scrubs through
  `MissionManager` must make the Mission `ACTIVE` first — `onAppOpen()` gives a `DRAFT` and
  `advance()` rejects a draft.
- Every flow read is bounded: `withTimeout(5_000) { flow.first { … } }`. No test types into or
  focuses a field inside a `Dialog` — a `BasicTextField` in a `Dialog` never goes idle under Robolectric.
- The eight test method names below are fixed by `verify.sh`, which looks them up by method name
  across every report. Spell them exactly; class and package are yours.

---

- [x] `data: read plan titles for a list of sorties`
  Covers: one read-only query so a history row can show its plan title without a `getPlan` per row.
  `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt` gains
  `@Query("SELECT sortieId, title FROM flightPlan WHERE sortieId IN (:ids)")`
  `abstract suspend fun getTitlesForSortieIds(ids: List<Long>): List<SortiePlanTitle>`;
  `data/Relations.kt` gains `data class SortiePlanTitle(val sortieId: Long, val title: String)`
  (`FlightPlan.title` is non-null, `data/FlightPlan.kt:23`). Nothing else: no entity, index, version
  or delete. This is the plan's step 1 and unblocks the row title for every later task.
  Test you write (in the existing `app/src/test/java/com/liftoff/app/data/FlightPlanDaoTest.kt`,
  reusing its `createDb`/`makeSortie` helpers; `verify.sh` does not name it, so this name is yours):
  `titlesForSortieIdsOmitSortiesWithNoPlan` — two sorties, one with a written plan and one with none:
  the query returns the planned one's title and omits the other; an empty id list returns an empty list.
  Done when: that class passes cold, and `exportedV1SchemaMatchesDatabase` and
  `noWipeForMissionsAndOnlyTheByIdDeleteForSorties` still pass (`bash gradlew.sh :app:testDebugUnitTest
  --tests "com.liftoff.app.data.FlightPlanDaoTest" --tests "com.liftoff.app.data.SchemaExportTest"
  --tests "com.liftoff.app.data.MissionDaoTest"`).

- [ ] `ui: derive the landed history list`
  Covers: the plain-Kotlin history derivation the brief asks for, and nothing about rendering.
  New `app/src/main/java/com/liftoff/app/ui/landed/LandedState.kt` with the history half of the
  plan's "Derived state" — `LandedHistoryState` (`Loading` / `Empty` / `Weeks`), `HistoryWeek`
  (`head = "WEEK OF " + formatWeekDate(weekStart)`), `HistoryRow` (`indexLabel`, `type`, `typeLabel`,
  `title`, `dateLabel`, `stateLabel`, `scrubReason`) and `suspend deriveHistory(sorties)` — no Android
  imports. `formatWeekDate` and the other helpers in `ui/sortie/PlanFormat.kt` are `internal`, which is
  module-visible, so `ui.landed` can call them as `InFlightState.kt` already does.
  New `LandedStates.kt` with the plan's constructor
  `LandedStates(db, settingsStore, zone = { ZoneId.systemDefault() })` (the settings and zone are used
  by the next two tasks; create them now so the class is not reshaped later) and
  `observeHistory(): Flow<LandedHistoryState>` = `sortieDao().observeHistory().map { deriveHistory(it) }.flowOn(Dispatchers.IO)`.
  `deriveHistory` once per emission: `Empty` for no rows; **return before** `missionDao().getByIds(...)`
  when the id list is empty (Room emits `IN ()` and SQLite rejects it); then
  `flightPlanDao().getTitlesForSortieIds(ids)`; keep the DAO's order (`weekStart DESC, index DESC`) and
  group **consecutive** rows by the Mission's `weekStart`. Do not combine `flightPlanDao().observeChanges()`
  in — nothing can move a row back out of the history.
  Decisions this implements: 2 (date = `landedAt`, else `launchedAt`, else the Mission's `weekStart`,
  printed `OCT 12` — the rule `PromptInputLoader.dateOf` :111-121 already uses), 3 (bucket = the
  Mission's `weekStart`), 4 (order within a week = the DAO's `index DESC`), 11 (no title and no focus →
  the type word `Run` / `Lift`). Row title = plan title, else `sortie.focus`, else the type word.
  New test file `app/src/test/java/com/liftoff/app/ui/landed/LandedStatesTest.kt` (the next two tasks
  add to this same file; do not create a second one).
  Tests you write, exactly these names:
  `historyGroupsByWeekNewestFirstAcrossTwoWeeks` — Missions at 2026-01-05 and 2026-01-12, several
  `LANDED` and one `SCRUBBED` sortie in each: assert the week order, the heads `WEEK OF JAN 12` and
  `WEEK OF JAN 5`, the row order inside a week, each row's title (plan title where a plan exists, focus
  where it does not), its `LIFT` / `RUN` label, `LANDED` / `SCRUBBED`, its date label, and the scrub
  reason on the scrubbed row.
  `emptyHistoryShowsTheEmptyState` — no `LANDED`/`SCRUBBED` rows → `LandedHistoryState.Empty`, not
  `Weeks(NONE)` and not `Loading`.
  `historyUpdatesWhenASortieIsLanded` — start collecting `observeHistory()` **before** the landing
  (e.g. `launch { states.observeHistory().take(2).toList(received) }`), then `manager.land(...)` an
  `ACTIVE` mission's sortie, then await the second emission with a timeout and assert the sortie is in
  it. A test that re-subscribes after the write proves nothing about liveness.
  Done when: `bash gradlew.sh :app:assembleDebug` compiles and
  `--tests "com.liftoff.app.ui.landed.LandedStatesTest"` runs those three cold, with the XML showing
  three passing testcases under those names.

- [ ] `ui: derive the landed detail of a landed lift, and save its note`
  Covers: the detail half of `LandedState.kt` and the Room read that feeds it — the plan's
  `LandedDetail` (`Loading` / `Lift` / `Run` / `Scrubbed`), `SetMark`, `RecordedExercise`,
  `RecordedSet`, and the pure `deriveLandedDetail(plan: FlightPlanDetail?, sortie: Sortie,
  mission: Mission?, weightUnit, distanceUnit, zone)` modelled on `deriveInFlight` (no I/O, so a test
  can drive it on a fresh `getPlan` read). `LandedStates.observeDetail(sortieId)` mirrors
  `MissionStates.observeSortie` (`MissionState.kt:112`): `combine(sortieDao().observeById(id),
  settings.map { it.weightUnit }.distinctUntilChanged(), settings.map { it.distanceUnit }
  .distinctUntilChanged())` then `getPlan` + `missionDao().get` + `deriveLandedDetail`, `.flowOn(Dispatchers.IO)`;
  no `observeChanges()` — `observeById` already re-emits on a note write.
  `LandedStates.saveNote(id, text)` = `sortieDao().updateNotes(id, text.takeIf { it.isNotBlank() })`
  (Decision 7: a blank clears the note).
  Set marks (Decision 1, as revised by the review): `DONE` → `DONE`; `SKIPPED` **inside an exercise
  whose `plannedExercise.skipped` is set** → `SKIPPED`; every other `SKIPPED` set and every `OPEN` set
  on a landed sortie → `NOT_DONE`, because `MissionManager.land()` rewrites every still-OPEN set to
  `SKIPPED` (`MissionManager.kt:127`, `FlightPlanDao.markOpenSetsNotDone` :202) and the Land
  confirmation promises "recorded as not done". Deviation (Decision 8): a `DONE` set whose recorded
  value differs from its planned value, field by field → `weightDeviates` / `countDeviates`; a skipped
  or not-done set is never a deviation. Labels come from `ui/sortie/PlanFormat.kt` (`formatWeight`,
  `formatDistance`, `formatMinutes`, `formatRunKind` are `internal`, i.e. module-visible, as
  `InFlightState.kt` already uses them); weight label is `"${formatWeight(w)} ${weightUnit.name}"`,
  `"BW"` when the weight is null.
  The `Run` and `Scrubbed` variants are declared and dispatched here, but the next task is what proves
  them; keep this task's derivation honest for the lift path and do not claim the run/scrubbed paths
  are covered.
  Tests you write, exactly these names (added to `LandedStatesTest.kt`):
  `liftDetailShowsPlannedAndActualValuesAndFlagsDeviations` — a `DONE` set recorded with different reps
  **and** a different weight than planned → `actualLabel` ≠ `plannedLabel` and both
  `weightDeviates` and `countDeviates` true; a `DONE` set recorded exactly as planned → both false.
  `liftDetailMarksSkippedNotDoneAndAddedSets` — built through the real flow, not hand-written rows:
  launch an `ACTIVE` mission's sortie, check some sets, `skipExercise` on one exercise, `addSet` on
  another, leave at least one set unchecked, then `manager.land(...)`. Assert the skipped exercise has
  `exerciseSkipped` true with its sets `SKIPPED`, the set `land()` rewrote reads `NOT_DONE`, and the
  added set has `added = true`. Also cover `OPEN` → `NOT_DONE` with a pure `deriveLandedDetail` call on
  a plan that still has an `OPEN` set (a test that only ever sees `SKIPPED` rows would pass with the
  opposite rule).
  `editingTheNoteOfALandedSortieWritesItBack` — `saveNote(id, "knee felt fine")` then the same
  `observeDetail` flow emits it; `saveNote(id, "")` clears it to null and the detail stops carrying it.
  Drive it through `LandedStates`, not a view model.
  Done when: `--tests "com.liftoff.app.ui.landed.LandedStatesTest"` runs six passing testcases cold —
  the three from the previous task plus the two lift tests and the note test.

- [ ] `ui: cover the landed run and scrubbed detail`
  Covers: the two paths the previous task declared but did not prove, and whatever the derivation
  needs to make them right. `Run` carries the plan summary the user launched against — `kind`
  (`formatRunKind`), the target line (`formatDistance(targetDistance, unit)` · `targetPace`), the
  segments (`RunSegmentRow` from `ui.inflight` is already the shape of a segment line) — plus
  `recordedDistance` = `formatDistance(sortie.runDistance, distanceUnit)` and `recordedDuration` =
  `formatMinutes(sortie.runMinutes)`, the plan note and the sortie note. **Decision 9: do not reuse
  `FlightPlanSection`** — it prints `"%.2f km"` whatever the setting says (`ui/sortie/FlightPlanSection.kt:78,94`),
  so the Landed derivation formats these lines itself. A landed sortie with no plan at all (a run whose
  plan was never written) is a `Run` with a null summary and whatever recorded values it has.
  `Scrubbed` carries `reason` (`sortie.scrubReason`; Decision 10: none recorded → the detail says
  "No reason given", the wording `MissionScreen` already uses), only the exercises with at least one
  `DONE` set and **only** their `DONE` sets (DESIGN §5.1: a scrub "keeps the sets already checked as a
  partial record"), the `n of m sets checked` counts, and the sortie note. A scrubbed sortie with no
  plan is a `Scrubbed` with no exercises.
  Tests you write, exactly these names (added to `LandedStatesTest.kt`):
  `runDetailShowsRecordedDistanceAndDurationInConfiguredUnits` — `runDistance = 5.0`,
  `runMinutes = 32.0` → `5 mi` / `32 min` under `DistanceUnit.MI`, and the same `observeDetail` flow
  re-emits `5 km` after the setting is changed to `KM`. The re-emit is the proof that the value is
  formatted from the setting and not baked in, so assert both from one flow.
  `scrubbedDetailShowsReasonAndOnlyTheSetsCheckedBeforeTheScrub` — a plan with `DONE`, `SKIPPED` and
  `OPEN` sets on a scrubbed sortie: the detail's exercises contain only the `DONE` sets, none of the
  others, with the reason and the checked-of-total counts. Scrub through `MissionManager` on an
  `ACTIVE` mission, or write the rows directly — but the `SKIPPED`/`OPEN` mix must be present, since
  that is what the rule filters.
  Done when: `--tests "com.liftoff.app.ui.landed.LandedStatesTest"` runs eight passing testcases cold,
  and those eight names are exactly the eight `verify.sh` lists under "history list" and "detail view"
  (check them off against the script; a name spelled differently is a failed run).

- [ ] `ui: show the landed history list`
  Covers: the list half of the real screen, and the shell, in one commit — the placeholder cannot go
  without the shell test changing at the same time.
  New `app/src/main/java/com/liftoff/app/ui/landed/LandedViewModel.kt`:
  `LandedViewModel(db, settingsStore, scope)` with `history: StateFlow<LandedHistoryState>` built
  `stateIn(scope, SharingStarted.Lazily, LandedHistoryState.Loading)`, mirroring `MissionViewModel`.
  `LandedScreen` in `ui/landed/LandedScreen.kt` changes signature from `LandedScreen()` to
  `LandedScreen(container: AppContainer)` (like `MissionScreen(container)`) and drops
  `PlaceholderScreen`: `TitleBlock(eyebrow = "History", title = "Landed")` — `Eyebrow` uppercases, so
  the shell test's `onNodeWithText("HISTORY")` stays true — then per week a section head
  (`LiftoffType.sectionHead()`, Ink) reading `WEEK OF JAN 12`, then one
  `InkRuledListRow(index = "02", title = row.title, detail = "JAN 12 · LIFT · LANDED",
  modifier = Modifier.clickable { onSelect(row.sortieId) })` — the same way `MissionScreen` makes a row
  tappable without changing the theme composable; a scrubbed row passes its reason through the
  composable's `actions` slot in `LiftoffType.note()`/Muted. `Empty` renders the `TitleBlock` plus
  "No history yet." (the wording is the plan's; the screen and the shell test must use the same string);
  `Loading` renders the `TitleBlock` only, so the eyebrow is on screen before the
  first read and the empty state never flashes over real data. `ui/PlaceholderScreen.kt` stays where it
  is — this run does not ask for it to be deleted.
  `ui/LiftoffShell.kt:173` becomes `Tab.Landed -> LandedScreen(container)` (`container` is already in
  scope at :163). `ShellNav` and the shell's `BackHandler` (:81) stay untouched.
  `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt`, same commit: the three tests that read
  the Landed tab's copy now read the real screen's empty state — the shell's `@Before` only runs
  `onAppOpen()`, which leaves a draft Mission with no finished sorties. Replace
  `onNodeWithText("Landed sorties will appear here, newest first.")` at :66
  (`bottomBarReachesEveryTab`), :148 and :157 (`missionControlBackButtonReturnsToTabItWasOpenedFrom`)
  with the file's existing `waitFor(hasText("No history yet."))` — the history read is async, so a bare
  `assertIsDisplayed()` right after the click races it. `placeholderScreensShowTheirCopy` (:193-201)
  asserts only the Landed placeholder today, so rename it to what it now proves
  (e.g. `landedScreenShowsItsEmptyStateCopy`) and keep its `onNodeWithText("HISTORY")` assertion;
  `verify.sh` deliberately does not name it, but the gate runs it whatever it is called.
  Keep every other test name in that file exactly as it is: `verify.sh` re-names
  `appOpensOnLaunchpad`, `bottomBarReachesEveryTab`, `systemBackFromATabGoesToLaunchpad` and
  `missionControlBackButtonReturnsToTabItWasOpenedFrom`, and `ShellNavTest`
  `backFromMissionOrLandedGoesToLaunchpad`.
  Do not assert on the text `LANDED`: the bottom bar renders `Text(label.uppercase())`
  (`LiftoffShell.kt:247`), so `hasText("LANDED")` matches two nodes and `onNodeWithText` throws
  "expected exactly 1 node". Navigate by `onNodeWithContentDescription("Landed")`, as these tests already do.
  Done when: the app assembles, `--tests "com.liftoff.app.ui.LiftoffShellTest" --tests "com.liftoff.app.ui.ShellNavTest"`
  run cold with no failures and no test renamed away from the names above, and no file still contains
  the string "Landed sorties will appear here".
  Commit body records which shell assertions changed, since `verify.sh` cannot see the rename.

- [ ] `ui: add the landed detail view and its note editor`
  Covers: opening a row and everything the detail shows. `LandedScreen` keeps its own selection exactly
  like `MissionScreen`: `var selectedSortieId: Long? by rememberSaveable { mutableStateOf(null) }`,
  `BackHandler(enabled = selectedSortieId != null) { selectedSortieId = null }` composed **after** the
  shell's (:81) so the tab's wins, then the detail instead of the list while it is set, ending with
  `UnderlinedTextButton(text = "Back", onClick = onBack)`. `ShellNav` still untouched — the system back
  reaches the tab's handler because the tab registered later.
  Detail `TitleBlock` where the eyebrow is `SORTIE 2 · LIFT · LANDED` (the shape
  `MissionState.sortieEyebrow`, `MissionState.kt:199`, already uses for a finished sortie) and the title
  is the plan title, else the focus, else `Run` / `Lift` (`MissionState.kt:135` does the same); a muted
  `LANDED JAN 12` line when the sortie has a landed time, and no date line on a scrubbed detail — no
  scrub time was ever recorded.
  `FullCard` (`InFlightScreen.kt:553`), `CollapsedRow` (:504) and `ValueCell` (:690) are **private** to
  that file, and `sortieEyebrow` is private to `MissionState.kt:199`: none of them can be imported.
  `FullCard` takes In-Flight's `ExerciseCard` and `ValueCell` takes an `onValueClick`, and a landed
  sortie has neither, so build the Landed cards from the theme pieces in the same shape rather than
  widening those helpers.
  Lift: one paper card per exercise — `Paper` + `CardShape` + `BorderStroke(2.dp, Ink)`, an ink header
  bar with `"%02d"` in `LiftoffType.index()`/Mustard and the name uppercased in
  `LiftoffType.cardTitle()`/Cream (the `FullCard` shell), then a row per set: `SET n` in
  `barLabel()`/Muted, the actual value in `setValue()` rendered **Red with a muted `of <planned>` beside
  it** when `weightDeviates`/`countDeviates`, exactly as `ValueCell` does on In-Flight, and a mark word
  for `SKIPPED` / `NOT DONE` plus an `ADDED` tag; the exercise's own note under its sets in
  `note()`/Muted; a skipped exercise keeps its card with the name struck through and `SKIPPED` beside it,
  as `CollapsedRow` does. Run: the plan summary in a paper card, then the recorded distance and duration
  as the two big values on the screen (`setValue()`), labelled `DISTANCE` and `TIME` in `barLabel()`/Muted.
  Scrubbed: the reason in a paper card ("No reason given" when there is none), the
  `n of m sets checked before the scrub` line, then the cards with only their `DONE` sets.
  Note: the sortie note renders as a muted strip and `UnderlinedTextButton("Edit note")` opens
  `NoteDialog(title = "Sortie note", initial = note.orEmpty(), onSave = { viewModel.saveNote(id, it) },
  onDismiss = …)` from `ui.inflight` (`internal`, so module-visible). Offer the editor on a landed
  **and** a scrubbed detail (Decision 6). Nothing else on the screen is editable: DESIGN §5.1 leaves
  only the notes open on a finished sortie.
  Add `observeDetail(id)` and `saveNote(id, text)` to `LandedViewModel` as `StateFlow`s built with
  `SharingStarted.Lazily` — a test or caller that calls `saveNote` must start collecting the detail
  flow first, or `state.value` is still the initial value.
  No new test is required by `verify.sh` here (Compose UI tests are not required, and no Compose test
  may type into or focus a field inside a `Dialog`). If you add a Compose test for the list rendering,
  render it without tapping a row and without touching the dialog's field, and give every wait a timeout.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` (detached,
  read the log) is green with the Landed screen compiling and `LandedStatesTest`'s eight tests still
  passing; the owner reviews the rendering in the pull request — `verify.sh` says so in its header.

- [ ] `docs: mark landed history built under m5`
  Covers: the two `ARCHITECTURE.md` rows the brief's Done-when names, and one read of the whole suite.
  `ARCHITECTURE.md:37`, the `com.liftoff.app.ui` area row: drop "Landed as a placeholder" (the row must
  stay **one line**; "Mission Control stub" may stay — `verify.sh` only rejects `landed…placeholder` in
  it). `ARCHITECTURE.md:84`, the M5 row's status cell (field 4 when the line is split on `|`): it must
  stop reading exactly "Not started", must name the Landed history, and must say that part is built —
  e.g. `Partial: the Landed history screen is built; Re-fly and export/import not started`. M5 also
  covers Re-fly and export/import, which this run does not build, so a bare "Done" would be false.
  Then run the whole suite once, cold and detached, and read `app/build/test-results/testDebugUnitTest`
  rather than trusting a per-class gate: `bash gradlew.sh --stop`; `mkdir -p app/build`;
  `nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' &`.
  A per-class gate never runs the cross-cutting guards (`noWipeForMissionsAndOnlyTheByIdDeleteForSorties`,
  `exportedV1SchemaMatchesDatabase`, `neverSaysSession`, `historyIsNewestFirstByWeekThenIndex`,
  `formatWeekDateOCT5`, `formatDistanceWholeKilometre`, `openSetsOnAScrubbedSortieReadNotDone`), and the
  test they break may be named nowhere in the run's plan. Fix any regression you find in this commit or
  a follow-up one, staging named files. `doneBodyweightSetStepperShowsBW` in `InFlightScreenTest` is a
  known flake (a closed Room pool that passes on a re-run): re-run the gate on the same tree before
  treating it as broken.
  Done when: `awk '/^\| `com\.liftoff\.app\.ui` \|/' ARCHITECTURE.md` has no `landed…placeholder`,
  `awk -F"|" '/^\| M5 \|/ {print $4}' ARCHITECTURE.md` names the Landed history and a built word, and
  the detached full gate log ends `EXIT=0` with `LandedStatesTest` present in the XML with eight
  passing testcases.
