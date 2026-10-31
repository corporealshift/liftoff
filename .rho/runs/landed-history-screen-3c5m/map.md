# Map — landed-history-screen-3c5m

Notes on the code this run touches. Not the plan. Facts I read myself are marked ✓;
facts from the review pass are marked (R) and were then checked.

HEAD at plan time: `808f9e3 run: brief` (over `74dffb0 Reopen into an in-flight sortie (#23)`).

## Docs that set rules

- `CLAUDE.md` ✓ — gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit
  messages `area: lowercase summary`; stage named files only; §2 vocabulary (never "session"
  for a sortie); LF endings; short comments (why, not what, 1–2 lines).
- `DESIGN.md` §5.1 (line 157) ✓ — LANDED "stores the landed time and the actual sets. It
  cannot be edited after landing, apart from its notes." Scrubbing an in-flight sortie
  "keeps the sets already checked as a partial record".
- `DESIGN.md` §8 (line 520) ✓ — entity table. `Sortie`: `missionId, index, type, focus?,
  focusRationale?, state, launchedAt?, landedAt?, scrubReason?, notes?, runDistance?,
  runMinutes?`. `PlannedExercise`: `… notes?, skipped, userNotes?`. `PlannedSet`:
  `reps?, seconds?, weight?, actualReps?, actualSeconds?, actualWeight?, status, added`.
  `RunSegment`: `flightPlanId, order, description, distance?, minutes?`.
- `DESIGN.md` §9 (line 553) ✓ — "**Landed.** History, newest first, grouped by week, with a
  detail view per sortie." Mission tab: "Tapping a sortie shows its plan, or for a landed
  sortie its record."
- `design/README.md` ✓ — direction B "Space Age". Tokens: cream `#F2EADB` screen bg, paper
  `#FBF6EC` cards, sand `#E8DFCD` collapsed row, ink `#1D1B19` text + every border, red
  `#C23F14` "exercise numbers, **deviations**", mustard `#E3A72F` active bottom bar / numbers
  on ink, teal `#1F5F6B` "**LANDED** status", muted `#5E5850` "secondary text, eyebrows, set
  labels", rule `#CFC6B6` hairlines. Borders 2 dp ink on buttons and cards; corners 4 dp on
  buttons/checkboxes, **square on cards**; offset shadow only on the primary button and the
  active exercise card. Type roles: Section head = Big Shoulders 20/800 ls 0.1em; Exercise
  card title = BS 22/800 uppercase ls 0.04em; Weight/reps in a set row = BS 24/800; Load in
  the plan list = BS 20/800; Index "01" = BS 16/800 red on cream, mustard on ink; Eyebrow =
  Work Sans 12/600 uppercase ls 0.16em muted; Exercise name = Work Sans 16/500; Text button =
  Work Sans 15/600 underlined offset 4; Secondary/coach note = Work Sans 13/500–600.
  "Mission, Landed and Mission Control are not mocked up. Use the same parts: the eyebrow and
  display title, ink-ruled lists, `paper` cards with ink borders, the bottom bar."
- `ARCHITECTURE.md` ✓ — line 37 `com.liftoff.app.ui` row says "Landed as a placeholder";
  line 84 M5 row "Landed and safety nets — history screens, Re-fly, export/import | Not
  started". Both need updating. What **this run's** verify.sh actually greps is written under
  "What verify.sh gates" below; it is not the older `;`-splitting form that memory
  `architecture-md-rows-are-grepped-by-verify-sh` describes.

## Shell and navigation

- `ui/ShellNav.kt` ✓ — `enum class Tab { Launchpad, Mission, Landed }`;
  `ShellNav(tab, missionControlOpen, inFlightSortieId)` with `select/openMissionControl/
  openInFlight/back/encode/decode/startNav`. **Leave untouched** — the brief lists the shell
  and its back behaviour under "must not break", and `ShellNavTest` round-trips encode/decode.
- `ui/LiftoffShell.kt` ✓ — line 173 `Tab.Landed -> LandedScreen()` is the only call site.
  The other tabs get `container` from
  `(LocalContext.current.applicationContext as LiftoffApplication).container` (line 163).
  Line 81 `BackHandler(enabled = nav.back() != null)`. Detail selection lives inside the tab
  composable, exactly as `MissionScreen` does (see below), so the shell only changes at 173.
- `ui/mission/MissionScreen.kt` ✓ — the exemplar for a tab with a detail view:
  `var selectedSortieId: Long? by rememberSaveable { mutableStateOf(null) }` +
  `BackHandler(enabled = selectedSortieId != null) { selectedSortieId = null }`, then
  `if (selectedSortieId != null) SortieDetailView(state, onBack = { selectedSortieId = null })`
  else the list. `InkRuledListRow(..., modifier = Modifier.clickable { onSelect(row.id) })` is
  how a row is made tappable **without** changing the theme composable. Detail view ends with
  `UnderlinedTextButton(text = "Back", onClick = onBack)`.
  Its `LandedDetailView` (154) / `ScrubbedDetailView` (198) are thin: run values are
  hardcoded `"%.2f km"` / `"%.0f min"` and ignore `DistanceUnit` (known, out of scope here).

## Data layer (no schema change allowed)

- `data/SortieDao.kt` ✓ — `observeHistory(): Flow<List<Sortie>>` = LANDED + SCRUBBED,
  `ORDER BY m.weekStart DESC, s.\`index\` DESC`; `getFinishedNewestFirst()` one-shot twin;
  `observeById(sortieId): Flow<Sortie?>`; `get(id)`; `updateNotes(sortieId, notes: String?)`
  (notes-only, "so a sortie with no Flight Plan can carry a note").
- `data/MissionDao.kt` ✓ — `getByIds(ids: List<Long>): List<Mission>` already exists with the
  comment "One read for a whole list of history sorties' missions, so none of them costs a
  query each." Also `get(id)`, `getByWeekStart(LocalDate)`, `observeWeek(LocalDate):
  Flow<MissionWithSorties?>`. `MissionDaoTest` has the delete guard test
  `noWipeForMissionsAndOnlyTheByIdDeleteForSorties` (MissionDao → `emptySet()`, SortieDao →
  `setOf("deleteById")`) — **adding any new delete to either DAO fails it.**
- `data/FlightPlanDao.kt` ✓ — `getPlan(sortieId): FlightPlanDetail?` (@Transaction, returns
  null when the sortie has no plan); `observeChanges(): Flow<Int>` counts flightPlan +
  plannedExercise + plannedSet + runSegment rows, i.e. re-emits on any plan edit;
  `markOpenSetsNotDone(sortieId)` at :202; `updateSetActuals(..., SetStatus.SKIPPED)` at :262.
- `data/Relations.kt` ✓ — `FlightPlanDetail(plan, exercises: List<PlannedExerciseDetail>,
  segments: List<RunSegment>)`; `PlannedExerciseDetail(plannedExercise, displayName,
  sets: List<PlannedSet>)`.
- `data/PlannedSet.kt` ✓ — `id, plannedExerciseId, order, reps?, seconds?, weight?,
  actualReps?, actualSeconds?, actualWeight?, status: SetStatus = OPEN, added: Boolean = false`.
- `data/Enums.kt` ✓ — `SetStatus { OPEN, DONE, SKIPPED }`, `SortieState { PENDING, PLANNED,
  IN_FLIGHT, LANDED, SCRUBBED }`, `FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }`.
- `data/Sortie.kt` ✓ — fields as in DESIGN §8; `runDistance`/`runMinutes` are `Double?`.
- `data/MissionManager.kt` ✓ — `land()` at :114 calls `markOpenSetsNotDone` at :127, so
  **landing turns every still-OPEN set into SKIPPED**. (R) `domain/Sorties.kt` `scrub()` sets
  no timestamp; only `launch()` sets `launchedAt` and `land()` sets `landedAt`.
- `settings/Settings.kt` ✓ — `DistanceUnit { MI, KM }`, plus `weightUnit`; read through
  `SettingsStore.settings: Flow<Settings>`.

## Formatting helpers already in `ui/sortie`

- `ui/sortie/PlanFormat.kt` ✓ — `formatWeekDate(LocalDate)` → `OCT 5` (so a week head is
  `"WEEK OF " + formatWeekDate(weekStart)`); `formatEyebrow(weekStart, sortieIndex,
  patternLength, type)` → `WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT`; `formatWeight(Double?)`
  (internal, `""` for BW); `formatDistance(Double, DistanceUnit)` (internal) → `5 mi` /
  `5.5 km`, never converted; `formatMinutes(Double)` (internal) → `10 min`;
  `formatRunKind(String)` (internal) → `Tempo`; `formatExerciseLoad`, `formatPlanHead` →
  `≈55 min · 14 sets`; `deriveChips`. `internal` is fine: same module.
- `coach/CoachPromptBuilder.kt:189-201` ✓ — the app's existing wording for a set that is not
  done: `if (set.status == SetStatus.SKIPPED) " skipped" else " not done"`.

## Theme parts

- `ui/theme/Headings.kt` ✓ — `Eyebrow(text, color = Muted)`, `DisplayTitle(text, style =
  LiftoffType.screenTitle(), color = Ink)`, `TitleBlock(eyebrow, title, eyebrowColor,
  titleColor, titleStyle)`.
- `ui/theme/InkRuledListRow.kt` ✓ — `InkRuledListRow(index, title, detail = null, modifier,
  actions = null)`; 1 dp ink rule under each row; **no onClick param** — tap is added by the
  caller with `Modifier.clickable` (as MissionScreen does).
- `ui/theme/Color.kt` ✓ — Cream, Paper, Sand, Ink, Red, Mustard, Teal, Muted, Rule, White.
- `ui/theme/Type.kt` ✓ — `object LiftoffType` with `wordmark/screenTitle/sectionHead/
  exerciseName/load/index/note/textButton/...`.
- `ui/theme/Shape.kt` ✓ — `CardShape` (square), `ButtonShape`.
- `ui/theme/Buttons.kt` ✓ — `UnderlinedTextButton(text, onClick)`.
- `ui/inflight/NoteDialog.kt` ✓ — `internal fun NoteDialog(title, initial, onSave,
  onDismiss)` wrapping `internal fun NoteDialogContent(...)` (the split exists so the field can
  be tested outside a `Dialog`); uses `com.liftoff.app.ui.control.LabeledMultiLineTextField`.
  `internal` → reachable from `ui.landed` in the same module.
- `ui/sortie/FlightPlanSection.kt` ✓ — `FlightPlanSection(plan, sortieType, sortieFocus,
  modifier)`: FLIGHT PLAN head + `formatPlanHead`, exercise rows, coach note. Shared by
  Launchpad and Mission. Hardcodes `"%.2f km"` at :78 and :94 (out of scope).

## Test patterns that already exist

- `test/…/ui/mission/MissionStatesTest.kt` ✓ — Robolectric `@Config(sdk = [34])` (or
  `AndroidJUnit4`), `Room.inMemoryDatabaseBuilder`, `PreferenceDataStoreFactory` for the
  SettingsStore, `MissionManager(db, store, Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"),
  ZoneOffset.UTC))`. Insert the Mission fixture once (`weekStart` is UNIQUE).
- `test/…/ui/inflight/NoteDialogTest.kt` ✓ — `@RunWith(AndroidJUnit4::class)`,
  `@Config(qualifiers = "w411dp-h2000dp")`, renders `NoteDialogContent` in the main window
  rather than in a `Dialog` (a `BasicTextField` inside a `Dialog` never goes idle under
  Robolectric).
- `test/…/ui/LiftoffShellTest.kt` ✓ — `createAndroidComposeRule<MainActivity>()`;
  **four assertions across three tests match the placeholder line "Landed sorties will appear here,
  newest first."** (lines 66, 148, 157, 200): `bottomBarReachesEveryTab`,
  `missionControlBackButtonReturnsToTabItWasOpenedFrom` (twice) and `placeholderScreensShowTheirCopy`.
  Replacing the placeholder breaks them unless the new screen renders something they can match.
- `test/…/ui/sortie/PlanFormatTest.kt`, `FlightPlanSectionTest.kt` ✓ — plain + Compose tests
  of the shared helpers.

## Read while splitting the plan into tasks (2026-10-18, `run: tasks`)

- `tasks.md` ✓ — seven tasks, in the plan's order of work, one commit each: 1 the DAO title query,
  2 the history derivation (+ its 3 tests), 3 the detail derivation for a landed lift and `saveNote`
  (+ 2 lift tests and the note test), 4 the run and scrubbed paths (+ their 2 tests), 5 the list screen
  with the shell call site and the `LiftoffShellTest` updates, 6 the detail view and note editor,
  7 the two `ARCHITECTURE.md` rows plus one read of the whole detached suite. The eight names
  `verify.sh` requires are each written by exactly one task (2, 3, 4).
- `ui/inflight/InFlightScreen.kt` ✓ — `CollapsedRow` (:504), `FullCard` (:553), `ValueCell` (:690) and
  `CheckBox` are **`private` to that file**, so `ui.landed` cannot import them. `FullCard` takes
  In-Flight's `ExerciseCard` and `ValueCell` takes `onValueClick: () -> Unit` (a landed sortie has
  neither), so the Landed cards are built from the theme pieces in the same shape rather than by
  widening them. `ValueCell`'s deviation rendering, which the plan says to copy: `LiftoffType.setValue()`
  in `Red` with `Modifier.weight(1f)`, then `"of $plannedLabel"` in `LiftoffType.barLabel()` Muted 11sp W600.
- `ui/mission/MissionState.kt` ✓ — `class MissionStates(db, manager)` at :96 (no settings store);
  `sortieEyebrow` at :199 is **private**, so the Landed detail writes its own eyebrow string. Its shape:
  `"SORTIE ${index + 1} · ${type} · ${state}"`, each `name.replace("_", " ").uppercase()`.
- `ui/mission/MissionViewModel.kt` ✓ — `MissionViewModel(db, manager, scope)`; each flow is
  `…stateIn(scope, SharingStarted.Lazily, …Loading)`; a detail flow is a `fun observeSortie(id): StateFlow<…>`
  that builds a fresh `MissionStates` per call. `ui/inflight/InFlightStates.kt` ✓ — `InFlightStates(db, settings)`.
- `ui/theme/Type.kt` ✓ — `LiftoffType` has `sectionHead`, `cardTitle`, `setValue`, `load`, `index`,
  `barLabel`, `eyebrow`, `exerciseName`, `textButton`, `note` (no-arg `@Composable` forms at :83-98).
- `ui/theme/Headings.kt` ✓ — `Eyebrow` and `DisplayTitle` both call `text.uppercase()`, so
  `TitleBlock(eyebrow = "History", …)` already satisfies the shell test's `onNodeWithText("HISTORY")`.
- `ui/LiftoffShell.kt` ✓ — `NavigationBarItem` renders `Text(label.uppercase())` **and**
  `Icon(contentDescription = label)` (:241-251), so `hasText("LANDED")` matches the bottom-bar label as
  well as a screen title: assert on the empty-state line and the eyebrow, never on `LANDED`.
- `ui/PlaceholderScreen.kt` ✓ — the only other user of it is `LandedScreen` (:8); the placeholder line is
  the string `"Landed sorties will appear here, newest first."`.
- `ui/LiftoffShellTest.kt` ✓ — `placeholderScreensShowTheirCopy` (:193-201) asserts **only** the Landed
  tab (`"HISTORY"` + the placeholder line), so it becomes a test of the real screen's copy and is renamed
  by task 5. The file's `waitFor(matcher)` helper (`waitUntil(5_000)`) is what the updated Landed
  assertions should use: the history read is async. `appOpensOnLaunchpad` :53,
  `bottomBarReachesEveryTab` :59 (assertion :66), `systemBackFromATabGoesToLaunchpad` :74,
  `missionControlBackButtonReturnsToTabItWasOpenedFrom` :145 (assertions :148, :157);
  `backFromMissionOrLandedGoesToLaunchpad` is in `ui/ShellNavTest.kt:42`.
- `data/FlightPlanDaoTest.kt` ✓ — exists with `createDb`/`closeDb`/`makeSortie()` helpers and 17 cases;
  the new title-query case reuses them. `data/FlightPlan.kt` ✓ — `title: String` non-null,
  `runKind: String?`, `targetDistance: Double?`, `targetPace: String?`, `estimatedMinutes: Int?`, `notes: String?`.
- `ui/inflight/InFlightState.kt` ✓ — `data class RunSegmentRow(description, detail)` at :93 is public,
  so the Landed run summary can reuse it.
- `ARCHITECTURE.md` ✓ — the exact current text: :37 row ends
  `… Launchpad, Mission and In-Flight as real screens, Landed as a placeholder, Mission Control stub, theme and shared composables.`;
  :84 is `| M5 | Landed and safety nets — history screens, Re-fly, export/import | Not started |`.
- `AppContainer.kt` ✓ — `settingsStore` (:13) and `database` (:19) are the fields a screen's
  `container` argument exposes; `missionManager` at :24.

## What verify.sh gates (written 2026-10-18, `run: verify`)

`.rho/runs/landed-history-screen-3c5m/verify.sh` is the run's definition of done. It runs
`rm -rf app/build/test-results/testDebugUnitTest`, then
`timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`
(what `.github/workflows/ci.yml` runs, ✓), fails if the test task comes back
`FROM-CACHE`/`UP-TO-DATE` or if no `TEST-*.xml` appeared, then looks every name below up in
one index built from all reports (`index_reports` → `$WORK/index`, deduped per method at its
worst outcome). A name that never ran is a failure; class and package are the implementer's
choice, **the method names are not** — spell them exactly.

The eight tests this run must write (comments in the script say what each must show):

- `historyGroupsByWeekNewestFirstAcrossTwoWeeks`
- `emptyHistoryShowsTheEmptyState`
- `historyUpdatesWhenASortieIsLanded`
- `liftDetailShowsPlannedAndActualValuesAndFlagsDeviations`
- `liftDetailMarksSkippedNotDoneAndAddedSets`
- `runDetailShowsRecordedDistanceAndDurationInConfiguredUnits`
- `scrubbedDetailShowsReasonAndOnlyTheSetsCheckedBeforeTheScrub`
- `editingTheNoteOfALandedSortieWritesItBack`

None is a Compose test: the brief says Compose UI tests are not required and asks for the
derivation in plain Kotlin driven against in-memory Room.

It also re-names 27 existing tests under "must not break" (shell/LiftoffShell, In-Flight,
Mission, MissionManager + domain, Mission Control, schema + DAO guard, PlanFormat, coach). All
27 were checked ✓ to exist once in `app/src/test` at HEAD, and all 27 read `pass` against the
most recent full-suite reports in `../reopen-into-an-in-flight-sortie-9c1v`, so the script's
indexing is confirmed against real Gradle XML. Renaming any of them fails the run.
`LiftoffShellTest.placeholderScreensShowTheirCopy` (its Landed assertions at :195-201) is
**deliberately not named**, because its name is about the placeholder this run removes and
renaming it is the right thing to do; the gate still runs it. The other two tests that read
the Landed tab's copy — `bottomBarReachesEveryTab` (:59, assertion :66) and
`missionControlBackButtonReturnsToTabItWasOpenedFrom` (:145, assertions :148 and :157) — are
named and keep their names; their Landed assertions must change in the same commit that
replaces the placeholder.

ARCHITECTURE.md, as this script checks it ✓:
- area row: `awk '/^\| `com\.liftoff\.app\.ui` \|/'` takes the **whole single line**, and the
  only thing rejected is `landed[^,;|]*placeholder` (case-insensitive) — so "Landed as a
  placeholder" must go, "Mission Control stub" may stay, and no positive wording is required.
- M5 row: `awk -F'|' '{print $4}'` of `^\| M5 \|` is the status cell. Rejected only if the cell
  is exactly `Not started`; otherwise it must match `landed|histor` **and** one of
  `done|built|exists|exist|shipped|partial|complete|progress|real|✅`. No delimiter is
  required, so `;`-separated clauses or plain prose both pass. Verified ✓ against the untouched
  tree (both fail) and against two plausible rewrites (both pass).
