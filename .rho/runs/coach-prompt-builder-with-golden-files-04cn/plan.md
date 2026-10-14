# Plan — coach prompt builder with golden files

Two new pure files in `com.liftoff.app.coach` (input types + builder), one small addition to
`CoachSchemas`, one new Room-backed loader in `com.liftoff.app.data`, one new `@Query` on each of
`SortieDao` and `MissionDao`, three golden files under `app/src/test/resources/prompts/`, three new
test classes, and two rows of `ARCHITECTURE.md`.

No UI changes. No `app/build.gradle.kts` change, no new dependency, no Room entity or version
change, no change to `CoachSchema.kt`'s parsing or to `SchemaChecker.kt` / `CoachReplyValidator.kt`
/ `ExerciseNames.kt` behaviour.

## Approach, and why it fits this codebase

**The builder is the §7.4 layer sitting next to the validator that already landed.** `coach/`
today holds the normalizer, the schema model + checker, and the reply validator. The prompt builder
is the other half of the same contract: the validator checks the reply against a schema, the
builder puts that same schema into the prompt. Both live in `coach/`, which ARCHITECTURE invariant 2
keeps free of Android imports (`ExerciseNamesTest.coachPackageHasNoAndroidImports` scans every `.kt`
in the directory, so a new file there is checked by a test that already exists).

**Same shape as the rest of `coach/`.** `ExerciseNames.kt`, `SchemaChecker.kt` and
`CoachReplyValidator.kt` are all `object`s of plain stateless functions taking plain data classes.
The builder follows them: plain input data classes in, one `String` out, no clock, no random, no
locale-sensitive formatting, so the same inputs give byte-identical text (§7.4).

**The input types mirror `GenerationContext`.** `CoachModels.kt` already defines the request side of
a generation as a plain data class carrying `GenerationKind`, the pattern string, the optional
`SortieType`, the active equipment keys and the sortie length, with an `init` block that rejects a
`FLIGHT_PLAN` with no sortie type. `CoachPromptInput` is the same idea on the prompt side: a
`kind`, a profile, equipment lines, this week's pattern/sorties, the history blocks, and an optional
`sortieIndex` that is required for a `FLIGHT_PLAN`. Reusing `GenerationKind`, `SortieType`,
`SortieState` and `SetStatus` from `data/Enums.kt` (that file has no Android imports) and
`WeightUnit` / `DistanceUnit` from `settings/Settings.kt` (also none) keeps §2 vocabulary in one
place, exactly as `CoachReplyValidator` already reuses `domain/Pattern.kt`'s `sortieTypesOf`.

**The schema section is read from the resource file, not passed in.** §7.4 item 8 says the schema
text goes in "verbatim". `CoachSchemas` already owns the classpath read and the three path
constants, and `CoachSchemaFilesTest` proves `app/src/main/resources/schemas/` is on the unit-test
classpath. So `CoachSchemas` gains one function, `text(resource: String): String`, that returns the
file's bytes as UTF-8 (memoized per path, the way the validator memoizes its three parsed schemas),
and the builder calls it. Reading it inside the builder rather than taking it as an input is what
guarantees the prompt carries the same schema text the validator will check the reply against; a
caller-supplied string could drift from it. `CoachSchema.kt`'s `parse`/`load` and the checker are
untouched.

**The loader is a `data/` class, because it is Room + settings.** §12 keeps `coach/` pure, so the
thing that reads Room cannot live there. `MissionManager` is the existing precedent: a class in
`com.liftoff.app.data` taking `db`, `settingsStore`, `clock: Clock = Clock.systemUTC()` and
`zone: () -> ZoneId = { ZoneId.systemDefault() }`, with `MissionManagerTest` injecting a fixed
clock. `PromptInputLoader` takes the same clock and zone, plus an optional
`historyWindowDays: Int?` that overrides the configured window. That is how a test injects the
date, the zone and the window without touching the builder.

**History is windowed and ordered by the loader, formatted by the builder.** Deriving a sortie's
date needs `landedAt` / `launchedAt` (epoch millis, so a `ZoneId`) and the sortie's Mission
`weekStart` as the fallback for a scrub with no timestamp — all Room facts. The builder receives an
already-windowed, already-newest-first list, which keeps it a pure formatter and keeps the window
tests next to the database they need.

**Golden files are the fixtures pattern, applied to output instead of input.**
`CoachReplyFixtureTest` already reads `.txt` files out of `app/src/test/resources/fixtures/coach/`
with a classpath read and a working-directory fallback. The prompt golden files reuse that loader
shape under `app/src/test/resources/prompts/`, and compare **byte arrays**
(`assertArrayEquals(expected.toByteArray(UTF_8), prompt.toByteArray(UTF_8))`) so a stray CR fails as
loudly as a wrong word.

## Files involved

| File | What happens |
|---|---|
| `app/src/main/java/com/liftoff/app/coach/PromptInputs.kt` (new) | the plain input types: `CoachPromptInput(kind, profile, equipment, week, history, sortieIndex?)`; `PromptProfile(objectives, constraints, sortieLengthMinutes, weightUnit, distanceUnit)`; `PromptEquipment(key, name, notes)`; `PromptWeek(pattern, sorties: List<PromptSortie>)`, `PromptSortie(index, type, state, focus?)`; `PromptHistorySortie(date: LocalDate, type, title, state, scrubReason?, exercises, notes?, runDistance?, runMinutes?)`, `PromptHistoryExercise(name, sets, note?)`, `PromptHistorySet(reps, seconds, weight, status, plannedReps, plannedSeconds, plannedWeight)` |
| `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` (new) | `object CoachPromptBuilder`: `prompt(input): String` dispatching on `kind` to the outline and Flight Plan builders, and `repairPrompt(errors: List<String>): String`. Private section builders for Task / Instructions / Athlete profile / Equipment / This week / History / Exercises used before / Output schema; private set-grouping and number formatting (`Locale.ROOT`, whole numbers written whole) |
| `app/src/main/java/com/liftoff/app/coach/CoachSchema.kt` (edit) | add `CoachSchemas.text(resource: String): String` — the same `getResourceAsStream` read `load` already does, returned as UTF-8 text and memoized per path. `parse`, `load`, `CoachSchema` and `SchemaType` are unchanged |
| `app/src/main/java/com/liftoff/app/data/PromptInputLoader.kt` (new) | `class PromptInputLoader(db, settingsStore, clock = Clock.systemUTC(), zone = { ZoneId.systemDefault() }, historyWindowDays: Int? = null)` with `suspend fun outlineInput(missionId: Long)` and `suspend fun flightPlanInput(missionId: Long, sortieIndex: Int)`. Reads settings for the profile and the default window, `EquipmentDao.observeActive()` (active items only), `SortieDao.getForMission` for this week, the new finished-sorties query for history, and `FlightPlanDao.getPlan` for each history sortie's exercises and sets |
| `app/src/main/java/com/liftoff/app/data/SortieDao.kt` (edit) | add `@Query` `suspend fun getFinishedNewestFirst(): List<Sortie>` — the same `WHERE s.state IN ('LANDED','SCRUBBED') ORDER BY m.weekStart DESC, s.\`index\` DESC` join `observeHistory` already uses, as a one-shot suspend read. `observeHistory` stays as it is |
| `app/src/main/java/com/liftoff/app/data/MissionDao.kt` (edit) | add `@Query("SELECT * FROM mission WHERE id IN (:ids)") suspend fun getByIds(ids: List<Long>): List<Mission>`, so the loader can resolve each history sortie's `weekStart` in one read |
| `app/src/test/resources/prompts/outline.txt` (new) | the expected outline prompt, LF endings |
| `app/src/test/resources/prompts/lift-flight-plan-with-outline.txt` (new) | the expected lift Flight Plan prompt, with an outline present |
| `app/src/test/resources/prompts/run-flight-plan.txt` (new) | the expected run Flight Plan prompt |
| `app/src/test/java/com/liftoff/app/coach/CoachPromptGoldenFileTest.kt` (new) | the three golden-file comparisons, the build-twice equality test, and the "schema section is the resource file verbatim" test; owns the fixed `CoachPromptInput` values the golden files were generated from |
| `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt` (new) | the formatting rules asserted line by line: deviation, skipped and not-done sets, seconds, bodyweight, notes, a scrubbed sortie, equipment lines, empty sections, sorted and de-duplicated exercise names, the repair prompt |
| `app/src/test/java/com/liftoff/app/data/PromptInputLoaderTest.kt` (new) | Robolectric against `Room.inMemoryDatabaseBuilder`, following `SortieDaoTest` (`allowMainThreadQueries`, `runBlocking`): the window edge, a custom window, newest-first order, inactive equipment left out, the scrubbed-sortie date fallback, and the inputs the builder is then handed |
| `ARCHITECTURE.md` (edit) | the `com.liftoff.app.coach` row names the prompt builder instead of saying it is still to come; the M0 status cell says the same |

Untouched: `SchemaChecker.kt`, `CoachReplyValidator.kt`, `CoachModels.kt`, `ExerciseNames.kt`, the
three schema files, every existing test, every screen, `AppContainer.kt`, `app/build.gradle.kts`,
the Room entities and `LiftoffDatabase` version, and
`.rho/runs/coach-prompt-builder-with-golden-files-04cn/verify.sh` (owned by the runner).

### The prompt shape the golden files fix

Markdown section headings at `##`, one blank line between sections, no title line above them, and
the eight §7.4 sections in order. A lift Flight Plan prompt, in full:

```
## Task

Write the Flight Plan for sortie 1 of this week

## Instructions

Follow COACH.md in this workspace. Your final message must be only a JSON object matching the schema below.

## Athlete profile

objectives: Get stronger at the basics
constraints: 45 minutes at home, no pull-up bar
sortie length: 45 minutes
units: lb, mi

## Equipment

bb — barbell — 20 lb bar, plates to 45 lb
db — dumbbells — pairs 5–50 lb in 5 lb steps
kb — kettlebell

Exercises may use only these ids. Bodyweight needs no id.

## This week

pattern: RLRLR
index 0 — run — landed — focus: Easy 5k
index 1 — lift — pending — focus: Push + core
index 2 — run — pending — focus: Tempo intervals
index 3 — lift — pending
index 4 — run — pending
planning: index 1 — lift

## History

2026-10-05 LIFT "Legs + pull" — landed
  Back squat: 3×5 @185 lb ✓
  Barbell row: 3×8 @135 lb ✓, 1×6 @135 lb (planned 8) — note: "lower back tired"
  Plank: 3×45 s ✓
  Push-up: 3×12 bodyweight ✓
  Kettlebell swing: 2×15 @32 kg skipped
  notes: "left shoulder twinge on last set"

2026-10-03 RUN "Easy 5k" — scrubbed ("week ended")
  run: 3.1 mi in 28 min
  notes: "knee sore"

2026-09-28 LIFT "Full body" — landed
  Bench press: 3×8 @135 lb ✓, 1×8 @135 lb not done

## Exercises used before

Back squat
Barbell row
Bench press
Kettlebell swing
Plank
Push-up

## Output schema

{
  "type": "object",
  ... the lift-plan.json file, byte for byte ...
}
```

The outline prompt differs only in Task ("Write the outline for this week"), the absence of the
`planning:` line, and the outline schema at the end.

### Set-line grammar

A run of consecutive sets that agree in status, actual values **and** planned values collapses to
`N×…`. Each group carries one marker:

| Case | Line |
|---|---|
| done as planned | `3×8 @135 lb ✓` |
| done, reps differ from plan | `1×6 @135 lb (planned 8)` |
| done, weight differs | `1×6 @145 lb (planned @135 lb)` |
| done, both differ | `1×6 @145 lb (planned 8 @135 lb)` |
| done, seconds differ | `1×40 s (planned 45 s)` |
| timed set, no weight | `3×45 s ✓` |
| reps, no weight (bodyweight) | `3×12 bodyweight ✓` |
| `SetStatus.SKIPPED` | `2×15 @32 kg skipped` |
| `SetStatus.OPEN` (a sortie scrubbed mid-flight) | `1×8 @135 lb not done` |

Weight is written whole when it is whole (`135`, not `135.0`), and keeps its fraction otherwise
(`22.5`), in the configured unit. Distance and minutes follow the same rule.

## Order of the work

Each step builds and passes its own tests, and is committed on its own with an
`area: lowercase summary` message.

1. **`coach: prompt input types and the §7.4 prompt builder`** — `PromptInputs.kt`,
   `CoachPromptBuilder.kt`, `CoachSchemas.text`, and `CoachPromptBuilderTest` written against the
   grammar above (no golden files yet). This is the step that proves the builder is pure: the
   existing `coachPackageHasNoAndroidImports` test picks up the two new files with no change.
2. **`coach: golden files for the outline, lift and run prompts`** — the three
   `app/src/test/resources/prompts/*.txt` files, generated by running the builder once on the fixed
   inputs and then read back byte for byte; `CoachPromptGoldenFileTest` with the three comparisons,
   `buildingTheSamePromptTwiceGivesIdenticalText`, and the verbatim-schema test. Golden files are
   written with LF and checked with `od -c` before committing (`.gitattributes` already forces
   `* text=auto eol=lf`).
3. **`coach: repair prompt built from the validator's errors`** — `repairPrompt` and its test,
   asserted against real `CoachReplyValidator` error lines rather than invented ones.
4. **`data: prompt input loader over Room and settings`** — the two new `@Query` methods,
   `PromptInputLoader.kt`, and `PromptInputLoaderTest` (Robolectric, in-memory database, fixed
   clock and zone).
5. **`docs: list the coach prompt builder in the architecture map`** — the `coach` row and the M0
   status cell.
6. **Gate:** `rm -rf app/build/test-results/testDebugUnitTest` then
   `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, then read every
   `TEST-*.xml` header for `failures="0" errors="0"` and confirm `:app:testDebugUnitTest` executed
   rather than `FROM-CACHE`.

## How the result will be tested

Every "Done when" bullet maps to a named test.

| Requirement | Test |
|---|---|
| outline prompt golden file | `CoachPromptGoldenFileTest.outlinePromptMatchesGoldenFile` |
| lift Flight Plan prompt with an outline | `CoachPromptGoldenFileTest.liftFlightPlanPromptWithOutlineMatchesGoldenFile` |
| run Flight Plan prompt | `CoachPromptGoldenFileTest.runFlightPlanPromptMatchesGoldenFile` |
| same inputs → identical text | `CoachPromptGoldenFileTest.buildingTheSamePromptTwiceGivesIdenticalText` |
| schema section verbatim from the resource | `CoachPromptGoldenFileTest.outputSchemaSectionIsTheResourceFileVerbatim` |
| a deviation | `CoachPromptBuilderTest.historyLineMarksASetThatDifferedFromItsPlan` |
| skipped sets | `CoachPromptBuilderTest.skippedSetsAreMarkedSkipped` |
| not-done sets | `CoachPromptBuilderTest.openSetsOnAScrubbedSortieReadNotDone` |
| seconds | `CoachPromptBuilderTest.timedSetsAreWrittenAsSeconds` |
| bodyweight | `CoachPromptBuilderTest.aRepSetWithNoWeightIsMarkedBodyweight` |
| notes | `CoachPromptBuilderTest.exerciseAndSortieNotesAppearInTheirBlocks` |
| a scrubbed sortie | `CoachPromptBuilderTest.scrubbedSortieShowsItsReason` |
| 28-day window edge | `PromptInputLoaderTest.day28IsInsideTheWindowAndDay29IsOutside` — fixed `today = 2026-10-29`, window 28, so a sortie dated 2026-10-01 is in and one dated 2026-09-30 is out |
| a custom window | `PromptInputLoaderTest.customWindowOverridesTheConfiguredHistoryWindow` |
| newest-first order | `PromptInputLoaderTest.historyIsNewestFirst` |
| exercise names sorted and de-duplicated | `CoachPromptBuilderTest.exercisesUsedBeforeAreSortedAndDeDuplicated` (two spellings of one name collapse) |
| inactive equipment left out | `PromptInputLoaderTest.inactiveEquipmentIsLeftOutOfTheEquipmentList` |
| loader builds the expected inputs from an in-memory DB | `PromptInputLoaderTest.outlineInputReadsProfileEquipmentAndWeekFromTheDatabase`, `flightPlanInputNamesTheSortieBeingPlanned`, `landedSortieDateComesFromLandedAtInTheInjectedZone`, `scrubbedSortieWithoutATimestampFallsBackToItsWeekStart`, `historyCarriesActualSetsAndExerciseNotes`, `runSortieCarriesDistanceAndMinutes` |
| ARCHITECTURE.md `coach` row | read by the runner's own check; step 5 writes it |
| the gate | step 6 |

Must-not-break coverage is the gate itself: `CoachSchemaLoadTest`, `SchemaCheckerKeywordTest`,
`SchemaCheckerValidDocumentTest`, `CoachReplyExtractionTest`, `CoachReplySemanticTest`,
`CoachReplyFixtureTest`, `CoachSchemaFilesTest`, the data-layer tests, every screen test and
`SchemaExportTest` (which compares the exported Room schema JSON, and so is the test that proves no
Room schema change slipped in).

## Risks and uncertain points

- **Golden files and line endings on Windows.** The comparison is byte-for-byte, so a CRLF in a
  golden file fails the test rather than passing quietly. `.gitattributes` maps `* text=auto
  eol=lf`, but the files still have to be *written* with LF; step 2 checks them with `od -c`.
- **Non-ASCII in prompts.** `—`, `×`, `✓` and the en dash in equipment notes are already used in
  this repo's Kotlin sources, so compilation is not the risk; reading golden files as UTF-8 bytes
  is. The golden loader never uses platform-default decoding.
- **Whether `app/src/test/resources` is on the unit-test classpath.** `CoachReplyFixtureTest` reads
  fixtures from the classpath *with a working-directory fallback* because that has not been proven
  here. The golden loader does the same two-route read, so a classpath surprise is not a failure.
- **`CoachSchemas.text` is an edit to a file the checker owns.** It is additive and the checker's
  22 tests are the guard. If it turns out to disturb `CoachSchemaLoadTest`, the fallback is a
  separate `coach/PromptSchemaText.kt` that does its own `getResourceAsStream` read.
- **A scrubbed sortie has no timestamp.** `domain/Sorties.kt`'s `scrub()` sets no time, and
  `rollover` scrubs with no time either, so the fallback date is the Mission's `weekStart`. That is
  a decision, not a bug to fix here — fixing it would mean a Room change.
- **`Sortie.focus` does not prove an outline existed.** "Continue without outline" fills
  `full body` / `easy` focuses (§4.2). The prompt renders whatever focus the row carries; the plan
  does not try to tell an outline focus from a default one.
- **A deliberate skip and an unticked set are the same row after landing.**
  `MissionManager.land` calls `FlightPlanDao.markOpenSetsNotDone`, which rewrites `OPEN` to
  `SKIPPED`. `OPEN` survives only on a sortie scrubbed while in flight. The prompt can report what
  is stored, not what was intended.
- **A set added mid-flight is indistinguishable from a planned one** once it is checked off
  (`added` is a flag, and its planned values equal its actuals). Deliberately not marked.
- **The coach's own earlier notes are not echoed back.** `FlightPlan.notes` and
  `PlannedExercise.notes` are the coach's fields; the history block carries the owner's
  (`Sortie.notes`, `PlannedExercise.userNotes`). Writing to / reading from the wrong one of those
  pairs is the silent bug to watch for in step 4.
- **History size.** The loader reads every landed and scrubbed sortie and windows in Kotlin. At a
  few dozen rows that is fine; if it ever stops being, the window moves into the SQL.
- **`ARCHITECTURE.md` rows are grepped, not read.** The `coach` row must stay one line, and the M0
  status cell must stay `;`-separated clauses with no em dash inside a clause, because the runner's
  check splits it on `;` and on the em dash.

## Decisions

The brief settles the section order and wording (§7.4), the 28-day window's inclusivity ("day 28 is
in, day 29 is out"), the newest-first direction, the byte-for-byte golden comparison, LF endings,
and that inactive equipment is left out. What it leaves open is below.

1. **Which date a scrubbed Sortie is filed under.**
   *Choice:* `landedAt`, else `launchedAt`, else the Mission's `weekStart`, each read in the
   injected zone. *Alternative:* leave undated scrubs out of history. *Why:* a scrub is exactly the
   information §7.1.1 tells the coach to plan around ("if sets were missed, hold or reduce"), and a
   week-ended scrub has no timestamp at all — `weekStart` is the only date the row has, and it is
   the date the owner would name if asked when that sortie was.

2. **How sorties are numbered in the prompt.**
   *Choice:* the stored 0-based `index`, in the Task line, the week list and the `planning:` line.
   *Alternative:* 1-based ordinals, as the Launchpad's "SORTIE 2 OF 5" shows them. *Why:* the coach
   has to write `index` values `0..n-1` in an outline reply, and the validator's messages are
   already 0-based (`sorties[2].index: must be 2`). One numbering system means the coach copies the
   numbers straight from the prompt; two systems costs a repair round, which is the thing the owner
   would notice as a slow or failed generation.

3. **Identical consecutive sets.**
   *Choice:* collapse into `3×8 @135 lb ✓`. *Alternative:* one entry per set. *Why:* §7.4's own
   example writes it that way, and it is how the owner already reads a plan in the app.

4. **A set that differed from its plan.**
   *Choice:* `(planned 8)` / `(planned @135 lb)` / `(planned 8 @135 lb)` / `(planned 45 s)`, shown
   only when the actual differs. *Alternative:* always show the planned value. *Why:* §7.4's example
   shows the marker only on the deviating set, and the coach's progression rule needs the delta, not
   a repeat of what it already proposed.

5. **Skipped and not-done sets, and a whole skipped exercise.**
   *Choice:* `skipped` for `SetStatus.SKIPPED`, `not done` for `SetStatus.OPEN`, and no special line
   for an exercise marked skipped — its sets carry the markers, so it reads
   `3×8 @135 lb skipped`. *Alternative:* label both states `not done`, or write
   `Bench press: skipped` for a skipped exercise. *Why:* `skipped` is the app's own word for that
   state (§5.1, the In-Flight screen), and a skipped exercise can still hold sets that were done, so
   a per-exercise line would need two formats. The two states genuinely differ in the data —
   `OPEN` only survives on a sortie scrubbed mid-flight — so both get a word.

6. **Bodyweight.**
   *Choice:* a reps set with no weight reads `3×12 bodyweight ✓`; a timed set with no weight reads
   `3×45 s ✓`, with no load word. *Alternative:* leave the weight blank for reps too, as §7.4's
   Plank line does for a hold. *Why:* §5.2 defines a missing weight as bodyweight, and for a rep
   exercise the coach has to know the load was the athlete's own body in order to progress it; a
   timed hold's load is bodyweight by definition, and §7.4's example already writes it without a
   word.

7. **Whose notes appear in a history block.**
   *Choice:* the owner's notes only — `Sortie.notes` on a `notes: "…"` line at the end of the block,
   and `PlannedExercise.userNotes` inline on its exercise line as `— note: "…"`; the coach's own
   `FlightPlan.notes` / `PlannedExercise.notes` are not echoed. *Alternative:* each note on its own
   indented line, and the coach's notes included too. *Why:* §6 records both owner notes, and the
   brief's block lists "the notes" after the exercises; the coach's own words from a previous reply
   are not information about what the athlete did, and repeating them invites the coach to reuse
   them instead of reading the sets.

8. **A run sortie in history.**
   *Choice:* one line `run: 3.1 mi in 28 min` when the owner entered them, omitted when neither was.
   *Alternative:* also list the plan's segments. *Why:* §5.3 makes distance and duration the only
   actuals a run has, and §7.4's history is "the sets as done" — segments are planned values.

9. **The title of a sortie that had no Flight Plan.**
   *Choice:* `Sortie.focus`, else `untitled`. *Alternative:* omit the title. *Why:* the block header
   keeps one shape either way, and a scrubbed-before-planning sortie still needs to be identifiable.

10. **Sections with nothing in them.**
    *Choice:* keep the heading and write `none` (equipment, history, exercises used before), and
    `none` for an empty objectives or constraints field. *Alternative:* omit the section. *Why:* the
    coach should see the same eight sections every prompt, and a first-week prompt with no history
    should read as "no history yet", not as a malformed prompt.

11. **The repair prompt's layout.**
    *Choice:* `Your reply was not valid:` on its own line, then each validator error line on its own
    line unchanged, then `Reply again with only the corrected JSON object.` *Alternative:* keep
    §7.3's single sentence with the errors joined by `; `. *Why:* each error is already a
    `path: problem` line containing its own colon and commas, so joining them makes one unreadable
    run-on; listing them keeps every error word for word, which is what §7.6 requires.

12. **Sorting and de-duplicating "exercises used before".**
    *Choice:* sort by display name case-insensitively, and de-duplicate by
    `ExerciseNames.normalize`, keeping the first spelling in sort order. *Alternative:* de-duplicate
    on the raw display string. *Why:* §7.7 stores each exercise once by normalized name, so
    `Bench Press` and `bench press` are one exercise; showing both lines would invite the coach to
    invent a third spelling, which is the drift §7.1.1 exists to prevent.

13. **Ties in newest-first order.**
    *Choice:* date descending, then Mission `weekStart` descending, then `index` descending.
    *Alternative:* whatever order the query returned. *Why:* byte-identical prompts require a total
    order, and this one matches the ordering `SortieDao.observeHistory` already uses, so two sorties
    on one day read in the order the owner did them.

14. **An equipment line with no notes.**
    *Choice:* `kb — kettlebell`, with no trailing dash. *Alternative:* always three fields, so
    `kb — kettlebell —`. *Why:* a dangling dash reads as a value that went missing, and §7.4's
    example is about the three-field case, not about padding.
