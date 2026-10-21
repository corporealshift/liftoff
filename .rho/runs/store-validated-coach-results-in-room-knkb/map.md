# Map

Run: store validated coach results in Room. Read this before the code.

## Docs that set the rules

- `CLAUDE.md` — gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit style
  `area: lowercase summary`; **stage named files only, never `git add -A`**; §2 vocabulary
  (`Mission`, `Sortie`, `FlightPlan`, `Generation`); LF endings.
- `ARCHITECTURE.md` — invariant 2: `domain/` and `coach/` are pure Kotlin, no Android imports.
  Invariant 5: no DI framework, one `AppContainer`. Milestones M3 (generation) and M6 (run plans)
  are the ones this work sits under. **The brief names no doc update**, so none is planned.
- `DESIGN.md` §4.2 — DRAFT → ACTIVE happens *when the outline is stored*; "Continue without
  outline" gives focus `full body` (lifts) / `easy` (runs). §4.3 — sorties run in pattern order
  regardless of weekday; the Launchpad offers the lowest-index unfinished sortie.
  §5.1 — `PENDING --generation stored--> PLANNED`; "The owner can regenerate it from here, which
  **replaces** the plan". §5.3 — run generation off: a simple Flight Plan titled `Run`,
  `source = SIMPLE_RUN`, holding the outline's run focus, created "at the moment a Flight Plan
  generation would otherwise be queued for it". The toggle is read at generation time.
  §7.2 — `OUTLINE` triggered by confirm, `FLIGHT_PLAN` triggered by "outline stored (sortie 1)",
  previous sortie landed/scrubbed, or Regenerate. A run sortie with run generation off is never
  sent to the coach. §7.3 step 5 — valid reply: "store the result in one transaction and mark the
  generation `SUCCEEDED`"; **superseded results**: "Results are stored only if the generation is
  still the sortie's current one." §7.5 — the three schemas. §7.6 — the semantic checks.
  §8 — `rawJson` is "the validated output, for debugging"; `FlightPlan.sortieId` is unique.

## Existing code

### `data/MissionManager.kt`
`confirm`, `scrub`, `launch`, `land`, `refly`, private `advance(missionId, settings)`, private
`currentWeekOf(clock, zone)`. Constructor takes `db`, `settingsStore`, `clock` (default
`Clock.systemUTC()`), `zone` (default `ZoneId.systemDefault()`), and holds
`currentWeekStart: MutableStateFlow<LocalDate?>`.
- Guard shape: `check(...) { "…" }` → `IllegalStateException`, inside one `db.withTransaction { }`.
  Messages today: `"Can only confirm the current week's draft"`, `"Mission must be ACTIVE"`,
  `"Sortie must be current"`, `"Sortie must be PENDING"`, `"No landed Flight Plan to re-fly"`.
- `confirm` = guard (DRAFT **and** `mission.weekStart == currentWeekOf(...)`) →
  `confirmWithoutOutline` → `sortieDao().insertAll` (missionId stamped in Kotlin) → `advance`.
- `advance` (private): closes the Mission when `allSortiesDone`; else takes `currentSortie`, and
  if it is PENDING calls `prepareCurrent(current, settings.generateRunPlans)`:
  `SimplePlan` → `writePlan` + `markPlanned`; `AwaitGeneration` → **empty block** (this is the
  branch the brief points at; nothing queues a generation yet).
- `refly` uses `markPlanned`, not `advance`, because `advance` would prepare the *next* sortie.

### `domain/Missions.kt`
`MissionLifecycle` (legal transitions DRAFT→ACTIVE, ACTIVE→CLOSED, DRAFT→CLOSED), `newDraft`,
`overridePattern`, `confirmWithoutOutline(mission): Pair<Mission, List<Sortie>>` — maps
`mission.pattern.mapIndexed` to sorties, focus `"easy"`/`"full body"`, everything else null,
`missionId = 0` (set by the caller). `allSortiesDone`.

### `domain/Sorties.kt`
`currentSortie` = lowest `index` that is not LANDED/SCRUBBED. `launch/land/scrub/markPlanned` are
pure `copy` transitions guarded by `SortieTransitions.requireTransition`. `markPlanned` requires a
legal PENDING→PLANNED (or PLANNED→PLANNED) transition.

### `domain/SortiePlanning.kt`
`Preparation.SimplePlan(draft)` / `Preparation.AwaitGeneration`; `prepareCurrent(sortie,
generateRunPlans)`; `simpleRunPlan(sortie)` = `FlightPlanDraft(source = SIMPLE_RUN, title = "Run",
notes = sortie.focus, rawJson = "{}", estimatedMinutes/warmup/runKind/targetDistance/targetPace =
null, exercises/segments = emptyList())`.

### `data/FlightPlanDao.kt` (abstract class, takes `db`)
`writePlan(sortieId, draft): Long` is `@Transaction` and **already deletes the sortie's existing
plan first** (`deleteForSortie`), then inserts the plan, then exercises in draft order
(`db.exerciseDao().resolve(name)`, `order = i`, `skipped = false`, `userNotes = null`), then sets
(`order = j`, `status = OPEN`, `added = false`, null actuals), then segments (`order = i`).
Reads: `getPlan(sortieId): FlightPlanDetail?`, `getTitlesForSortieIds`, `observeChanges():
Flow<Int>` (counts of the four plan tables), `countPlans/countPlannedExercises/countPlannedSets/
countRunSegments`, `markOpenSetsNotDone`, and the set/exercise action methods.

### `data/Relations.kt`
Write-input types: `FlightPlanDraft(source, title, estimatedMinutes?, warmup?, notes?, runKind?,
targetDistance?, targetPace?, rawJson, exercises: List<ExerciseDraft>, segments:
List<RunSegmentDraft>)`, `ExerciseDraft(name, equipmentIds: List<String>, restSeconds?, notes?,
sets: List<SetDraft>)`, `SetDraft(reps?, seconds?, weight?)`, `RunSegmentDraft(description,
distance?, minutes?)`. Also the read models `FlightPlanDetail`, `PlannedExerciseDetail`.

### Entities
- `FlightPlan`: `sortieId` has a **unique index** (one plan per sortie), FK to sortie CASCADE.
- `PlannedExercise`: FK to `FlightPlan` CASCADE and to `Exercise`; `equipmentIds` is a
  `List<String>` (TypeConverter in `data/Converters.kt`), **no FK to equipment**.
- `PlannedSet`: `reps/seconds/weight` planned, `actualReps/actualSeconds/actualWeight`,
  `status`, `added`.
- `RunSegment`: `flightPlanId`, `order`, `description`, `distance?`, `minutes?`.
- `Generation`: `id`, `kind`, `missionId`, `sortieId?`, `status`, `nabuSessionId?`,
  `lastEventId?`, `attempt = 0`, `error?`, `createdAt`, `finishedAt?`. **No `startedAt` column** —
  recency is `createdAt` (tie-break `id`).
- `Mission`: `weekStart` unique, `pattern`, `status`, `outlineNotes?`.
- `Sortie`: `missionId`, `index`, `type`, `focus?`, `focusRationale?`, `state`, `launchedAt?`,
  `landedAt?`, `scrubReason?`, `notes?`, `runDistance?`, `runMinutes?`.
- `data/Enums.kt`: `MissionStatus{DRAFT,ACTIVE,CLOSED}`, `SortieType{RUN,LIFT}`,
  `SortieState{PENDING,PLANNED,IN_FLIGHT,LANDED,SCRUBBED}`,
  `FlightPlanSource{GENERATED,REFLY,SIMPLE_RUN}`, `SetStatus{OPEN,DONE,SKIPPED}`,
  `GenerationKind{OUTLINE,FLIGHT_PLAN}`, `GenerationStatus{QUEUED,RUNNING,SUCCEEDED,FAILED,CANCELLED}`.

### `data/GenerationDao.kt` (interface)
Only `insert`, `get(id)`, `getAll()` (export), `insertAll`, `countAll`, `update`, `delete`
(import). **No status/session/attempt helpers and no per-sortie or per-mission lookup yet.**
Adding `@Query` methods does not change the Room schema; `data/SchemaExportTest.kt`
(`exportedV1SchemaMatchesDatabase`) is the guard.

### `data/SortieDao.kt` / `data/MissionDao.kt`
`SortieDao`: `insert`, `insertAll`, `update`, `get`, `getForMission` (ordered by `index`),
`getInFlight`, `getAll`, `countAll`, `getFinishedNewestFirst`, `deleteById` (a `@Query` delete —
a raw one would not re-emit `observeById`), `updateNotes`.
`MissionDao`: `insert`, `update`, `get`, `getByWeekStart`, `getUnclosedBefore`, `getByIds`,
`getAll`, `insertAll`, `countAll`, `deleteDraft`.

### `data/ExerciseDao.kt`
`resolve(name)`: normalizes through `coach/ExerciseNames.normalize`, returns the existing row or
inserts `Exercise(normalizedName, displayName = name.trim())`; throws on a blank/symbols-only name.

### `coach/CoachModels.kt`
`CoachPlan` (sealed) ← `CoachOutline(sorties: List<CoachSortieFocus>, notes)`,
`CoachLiftPlan(title, estimatedMinutes, warmup?, exercises: List<CoachExercise>, notes)`,
`CoachRunPlan(title, kind, targetDistance?, targetPace?, segments: List<CoachSegment>, notes)`.
`CoachSortieFocus(index, sortieType, focus, rationale?)`,
`CoachExercise(name, equipment: List<String>, sets: List<CoachSet>, restSeconds?, notes?)`,
`CoachSet(reps?, seconds?, weight?)`, `CoachSegment(description, distance?, minutes?)`.
`CoachReplyResult.Valid(plan) | Invalid(errors)`. `GenerationContext(kind, pattern, sortieType?,
equipmentKeys, sortieLengthMinutes)` — `init` requires `sortieType` for a FLIGHT_PLAN.

### `coach/CoachReplyValidator.kt`
`object CoachReplyValidator { fun validate(raw, context): CoachReplyResult }`.
`internal fun extract(raw): Extracted` where `Extracted.Ok(document: JsonObject)` /
`Extracted.Failed(error)` — trim, unwrap **one** Markdown fence, depth-count exactly one top-level
object. `Extracted` is `internal`, and `coach` and `data` are the **same Gradle module**, so `data`
can reach the accepted document.
Outline semantic checks (lines ~125-155) already enforce: `sorties.size == pattern.size`,
`sorties[i].index == i`, and `type` matching `pattern[i]` — so a `Valid` outline is
index-aligned with the pattern by construction. Lift checks: one of reps/seconds per set,
equipment ids in the passed list, `estimated_minutes <= 1.25 × sortieLengthMinutes`, unique
normalized exercise names. Run: only the type match.

**`Valid` carries no JSON** — only the typed plan. The validated text has to come from the
caller; `extract(raw).document.toString()` is the source available inside the module.
`title` is **required** by both `lift-plan.json` and `run-plan.json` (max 60 chars), and
DESIGN §9 (line 562) says a PLANNED card shows "the plan summary (title, the exercise list or
run summary, estimated time)" — so a plan's title is user-visible.
Fixture files in `app/src/test/resources/fixtures/coach/`: `outline-valid`, `lift-plan-valid`,
`run-plan-valid`, `lift-plan-fenced`, plus 10 invalid ones (`bad-json`, `prose-around-json`,
`schema-violation`, `reps-and-seconds`, `duplicate-exercise-names`, `inactive-equipment`,
`unknown-equipment`, `over-length-sortie`, `plan-type-mismatch`, `outline-pattern-mismatch`).
`outline-valid` is pattern `RLRLR`, five sorties, focus per sortie, rationale on the lift at
index 1, notes "Thursday's lift follows the tempo day…". `run-plan-valid` is `kind: tempo`,
`target_distance 6.5`, `target_pace "8:15/mi"`, four segments.

### `settings/Settings.kt`
`generateRunPlans: Boolean = false` (also `defaultPattern`, session length, units, history window,
daemon host/port/token, coach workspace path). `SettingsStore` is DataStore-backed;
`MissionManager` reads it with `settingsStore.settings.first()`.

## Existing tests

- `data/MissionManagerTest.kt` — Robolectric `@Config(sdk = [34])`, in-memory
  `LiftoffDatabase` with `allowMainThreadQueries()`, a real `PreferenceDataStoreFactory` store in
  `context.cacheDir`, and a **fixed clock** `2026-01-12T00:00:00Z` with `zone = { ZoneOffset.UTC }`
  (so the current week is Monday 2026-01-12). `advanceClock(days)` rebuilds the manager with a
  later fixed clock. Helpers `insertMission(weekStart, pattern, status)` and
  `insertSortie(missionId, index, type, focus, state)`.
- `data/MissionManagerReFlyTest.kt` — same shape; asserts refusal cases and `rawJson == "{}"`.
- `data/GenerationDaoTest.kt` — one test, `insertGetUpdateDelete`, exercising `update` with every
  field. No helper tests yet.
- `data/FlightPlanDaoTest.kt` — `writePlan` cases, including that a second `writePlan` replaces
  the first plan for a sortie.
- `coach/CoachReplyFixtureTest.kt` — owns the fixture loader every later fixture test reuses:
  `fixture(name)` (classpath `fixtures/coach/<name>`, then `src/test/resources/fixtures/coach`
  fallback), `validPlan(fixture, context)`, `errorsOf(fixture, context)`, and a `COVERED` set that
  `everyFixtureFileIsCoveredByATest` checks against the directory. Constants `KEYS =
  ["barbell","dumbbell","bench","pullup-bar"]`, `OUTLINE_CONTEXT`/`LIFT_CONTEXT`/`RUN_CONTEXT` all
  on pattern `"RLRLR"`, `sortieLengthMinutes = 60`. Convention: **lift fixtures use the sortie at
  pattern position 1, run fixtures position 0.**
- `data/SchemaExportTest.kt` — exported `app/schemas` JSON vs the Room database.
- Known flake: `doneBodyweightSetStepperShowsBW` (In-Flight screen test, Room "closed pool") fails
  at random and passes on a re-run of the same tree.

## CI

`.github/workflows/ci.yml`, job `gate`, `ubuntu-latest`, Java 17 (temurin), 20-minute job timeout, runs on every
pull request and on pushes to `main`. Its only build step is
`./gradlew :app:assembleDebug :app:testDebugUnitTest`. `LIFTOFF_LIVE_*` env vars are never set, so the live coach
test skips itself there.

## Existing test classes (exact class names, for verify.sh's class checks)

`app/src/test/java/com/liftoff/app/`: `AppContainerTest`, `MainActivityTest`.
`coach/`: `CoachPromptBuilderTest`, `CoachPromptGoldenFileTest`, `CoachReplyExtractionTest`,
`CoachReplyFixtureTest`, `CoachReplySemanticTest`, `CoachSchemaFilesTest`, `CoachSchemaLoadTest`,
`ExerciseNamesTest`, `SchemaCheckerKeywordTest`, `SchemaCheckerValidDocumentTest`.
`data/`: `BackupRoundTripTest`, `DatabaseExportTest`, `DatabaseImportTest`, `EquipmentDaoTest`,
`ExerciseResolverTest`, `FlightPlanDaoTest`, `GenerationDaoTest`, `MissionDaoTest`, `MissionManagerReFlyTest`,
`MissionManagerTest`, `PromptInputLoaderTest`, `SchemaExportTest`, `SortieDaoTest`.
`domain/`: `MissionsTest`, **`ReFlyTest`** (file `ReFlyTest.kt`, capital F), `RolloverTest`, `SortiePlanningTest`,
`SortiesTest`, `WeekTest`. `rho/`: `DaemonConnectionCheckTest` (+ `FakeDaemon.kt`, not a test class).
`settings/`: `SettingsStoreTest`. `ui/`: `LiftoffShellTest`, `ShellNavTest`; `ui/control/`: `BackupImportRoomTest`,
`BackupViewModelTest`, `ConnectionViewModelTest`, `EquipmentViewModelTest`, `MissionControlScreenTest`,
`MissionControlViewModelTest`; `ui/inflight/`: `InFlightLandScrubTest`, `InFlightLeaveTest`, `InFlightRunTest`,
`InFlightScreenTest`, `InFlightStatesTest`, `InFlightViewModelTest`, `LandScrubDialogTest`, `NoteDialogTest`,
`RunLandingTest`; `ui/landed/`: `LandedStatesTest`; `ui/launchpad/`: `LaunchpadDraftControlsTest`,
`LaunchpadDraftGuardsTest`, `LaunchpadReFlyTest`, `LaunchpadScrubTest`, `LaunchpadStatesTest`;
`ui/mission/`: `MissionStatesTest`; `ui/sortie/`: `FlightPlanSectionTest`, `PlanFormatTest`; `ui/theme/`: 12
classes (`ButtonsTest`, `ColorSchemeTest`, `ColorTokensTest`, `FontLicenseTest`, `FontResourcesTest`, `IconsTest`,
`OffsetShadowTest`, `PatternTrackTest`, `PreviewsTest`, `ShapesTest`, `StripesTest`, `ThemePackageTest`,
`TypographyTest`).

**Invariant 2 is already guarded for `coach` only**: `ExerciseNamesTest.coachPackageHasNoAndroidImports` reads
every `.kt` in `src/main/java/com/liftoff/app/coach` and asserts no `import android.` / `import androidx.`.
There is no equivalent test for `domain`.

## The verify script

`.rho/runs/store-validated-coach-results-in-room-knkb/verify.sh` — run it detached
(`mkdir -p app/build` first, `nohup bash .rho/runs/.../verify.sh > app/build/verify.log 2>&1`). It:
1. runs the CI gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, `timeout 2400`,
   results dir cleared first, up to 3 attempts only for the known flake / a build-level failure / a hung suite that
   wrote no reports), and fails if `:app:testDebugUnitTest` came `FROM-CACHE` or `UP-TO-DATE`;
2. indexes every `TEST-*.xml` once with awk into `app/build/verify-work/methods` and `/classes` (worst outcome
   kept per name; class keys are prefixed `C:`), then requires **15 named test methods** by exact method name
   (any class, any package) and 55 named test **classes** to have run with nothing failing or skipped;
3. exits 1 with `NOT DONE: 15 check(s) failed.` on the untouched tree — verified against `c70f8ad`+plan commits:
   gate attempt 1 passed, 72 classes / 558 tests / 0 skipped / 0 failures / 0 errors, 56 checks ok.

Baseline on untouched HEAD, for comparison after the work lands: **72 classes, 558 tests, 0 failures**.

## Machine notes

- Build with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (exports `ANDROID_HOME`).
  `mkdir -p app/build` before a detached run; scratch files go under `app/build/`.
- A unit-test task that hangs dies at the build's 5-minute test-task timeout and writes no
  `TEST-*.xml`; a gate can outlast a shell command's limit — run it detached and wait for its
  exit status. Prove tests ran with `--no-build-cache` plus the XML totals.
