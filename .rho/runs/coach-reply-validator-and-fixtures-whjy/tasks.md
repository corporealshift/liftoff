# Tasks — coach reply validator and fixtures

Five tasks, in order. Each is one commit, and each ends with its own tests green.

`verify.sh` matches named tests **by method name** across every `TEST-com.liftoff.app.coach*.xml`
report, so how the methods are grouped into classes is the implementer's call — the names below are
fixed and must be spelled exactly as `verify.sh` writes them. Of its 37 named tests, 33 are new
(10 extraction, 9 semantic, 14 fixture) and are written here; 4 already exist
(`constViolationNamesTheRequiredValue`, `unexpectedPropertyIsReportedOnItsObject`,
`outlineSchemaLoadsFromTestClasspath`, `coachPackageHasNoAndroidImports`) and need no task — their
files stay untouched. `verify.sh` also checks the two ARCHITECTURE.md rows (task 5).
Never edit `.rho/runs/coach-reply-validator-and-fixtures-whjy/verify.sh`.

Read `brief.md` and `plan.md` first: the plan's **message table**, **extraction lines** and
**Decisions** are load-bearing — those strings go into repair prompts (§7.3 step 5) and `verify.sh`
asserts them. Do not reword a line.

Run one class with `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
"com.liftoff.app.coach.<Class>"`, then read that class's `TEST-*.xml` under
`app/build/test-results/testDebugUnitTest` for `tests=`, `failures="0"` and `errors="0"` —
`BUILD SUCCESSFUL` alone is not proof, and without `--no-build-cache` the reports can come back
FROM-CACHE with no test executed. If a build will not start, `bash gradlew.sh --stop` first.
Plain JVM JUnit, no Robolectric (nothing here needs a `Context`). Stage named files only; LF endings
(`git ls-files --eol` reads `i/lf w/lf`).

- [x] Write the coach types and the raw-text extraction
  `app/src/main/java/com/liftoff/app/coach/CoachModels.kt` (new): `GenerationContext(kind, pattern,
  sortieType?, equipmentKeys, sortieLengthMinutes)` exactly as the plan's API block — `sortieType`
  nullable, `require(kind != FLIGHT_PLAN || sortieType != null)` in an `init`; the parsed coach types
  `CoachOutline`/`CoachSortieFocus`, `CoachLiftPlan`/`CoachExercise`/`CoachSet`,
  `CoachRunPlan`/`CoachSegment` (plain Kotlin, not Room — do not reuse `data/FlightPlan.kt`); and
  `sealed interface CoachReplyResult { Valid(plan: CoachPlan) ; Invalid(errors: List<String>) }`.
  `GenerationKind` and `SortieType` come from `com.liftoff.app.data.Enums.kt`; the pattern→sortie
  mapping is `domain/Pattern.kt`'s `sortieTypesOf(pattern)`, not a new one.
  `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` (new): `object` of stateless
  functions like `ExerciseNames.kt`/`SchemaChecker.kt`, no Android or androidx imports
  (`ExerciseNamesTest.coachPackageHasNoAndroidImports` scans every `.kt` in that directory). This
  task is the extraction half only: `internal fun extract(raw)` doing trim → unwrap **one**
  surrounding fence (first line 3+ backticks with an optional info string, last line at least that
  many backticks; tildes not accepted; two separate fenced blocks are *not* unwrapped) → depth-count
  for exactly one top-level `{…}` respecting strings and escapes → hand the substring to
  `Json.parseToJsonElement`. `validate(raw, context)` is stubbed to run extraction and return its
  error, so the class compiles and the extraction tests drive it. `internal` is visible from the
  unit-test source set; if this project's Kotlin plugin disagrees, make `extract` public rather than
  weakening a test.
  Extraction failures are one-error `Invalid`s at path `$`, with the plan's exact lines:
  `the reply is empty`, `unexpected text before the JSON object`, `unexpected text after the JSON
  object`, `the JSON object is not closed`, `expected a JSON object, got an array` (the word comes
  from the first character: `[` array, `"` string, `t`/`f` boolean, digit or `-` number, else text),
  and `not valid JSON: <reason>` where `<reason>` is the **first line only** of kotlinx's message.
  `app/src/test/java/com/liftoff/app/coach/CoachReplyExtractionTest.kt` (new), driving `extract`
  directly.
  Tests: `fencedReplyIsUnwrappedToTheJsonObject`, `fenceWithAnInfoStringIsUnwrappedToTheJsonObject`,
  `proseBeforeTheJsonObjectIsAnError`, `proseAfterTheJsonObjectIsAnError`,
  `malformedJsonIsReportedAsNotValidJson`, `unclosedJsonObjectIsReportedAsNotClosed`,
  `emptyReplyIsAnError`, `nonObjectReplyNamesWhatItActuallyWas`,
  `braceInsideAStringDoesNotEndTheObject`, `escapedQuoteInsideAStringDoesNotEndTheObject`.
  Done when: `:app:compileDebugKotlin` succeeds and that class's XML reads `failures="0"
  errors="0"` with 10 testcases, the fence/brace-in-string/escaped-quote cases passing (not skipped)
  and each failure case asserting its one exact line; `CoachSchemaFilesTest`, `CoachSchemaLoadTest`,
  `ExerciseNamesTest`, `SchemaCheckerKeywordTest` and `SchemaCheckerValidDocumentTest` still pass;
  commit `coach: extract one JSON object from a coach reply`.

- [ ] Finish `validate`: schema pass, semantic checks, mapping to coach types
  Complete `CoachReplyValidator.validate` as the plan's pipeline: extract (stop on failure) → plan
  type check for `FLIGHT_PLAN` only, short-circuiting on a well-formed `lift`/`run` of the wrong
  kind → `CoachSchemas.load` for the schema picked from the **context** (`OUTLINE` →
  `schemas/outline.json`; `FLIGHT_PLAN` → the schema for the context's sortie type; the three loads
  memoized with `lazy`) → `SchemaChecker.check`, keeping its paths and wording verbatim → the
  semantic checks over only the nodes whose type matched → `Valid` with the document mapped into the
  coach types, or `Invalid` with schema errors first then semantic errors.
  Semantic order: plan-level `estimated_minutes` first, then exercises in index order (each one's
  sets in order, then its equipment ids in array order), then name duplicates at the later index.
  Names compared with `ExerciseNames.normalize`. The budget is an exact comparison against
  `1.25 × sortieLengthMinutes`, printing the fractional limit when there is one. Every line is the
  plan table's wording, unchanged — including `exercises[2].equipment: barbell is not in the
  equipment list` (unquoted id, per the plan's Decision) and
  `exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]`.
  `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt` (new): documents inline, each
  test asserting the exact `path: problem` list against the fixed context (pattern `RLRLR`, keys
  `barbell, dumbbell, bench, pullup-bar`, 60-minute sortie; lift docs LIFT, run docs RUN).
  Tests: `outlineEntryCountNamesThePatternAndBothCounts`, `outlineIndexMustMatchItsPosition`,
  `outlineTypeMustMatchThePatternAtItsPosition`, `setWithBothRepsAndSecondsIsRejected`,
  `setWithNeitherRepsNorSecondsIsRejected`, `equipmentIdNotInTheListNamesTheIdAndItsPath`,
  `estimatedMinutesAboveTheSortieBudgetIsRejected`,
  `duplicateExerciseNameNamesBothSpellingsAndBothIndexes`, `planTypeNotMatchingTheSortieIsRejected`.
  Two more cases the plan asks for, each its own method (not named by `verify.sh`, still required):
  one document holding both a schema violation and a semantic violation, asserting both lines in
  that order; and `"type": "swim"` for a lift sortie giving **only** the checker's
  `type: must be 'lift'` line, with no second semantic `type` error.
  Done when: that class's XML reads `failures="0" errors="0"` with a passing testcase per row of the
  plan's semantic table plus those two cases; the extraction class still passes; commit
  `coach: add the semantic checks to the reply validator`.

- [ ] Add the four valid fixtures and the fixture loader
  `app/src/test/resources/fixtures/coach/` (new directory): `outline-valid.txt`,
  `lift-plan-valid.txt`, `run-plan-valid.txt`, `lift-plan-fenced.txt` — each the raw reply text as
  the coach would have sent it (`.txt`, LF), matching the plan's fixture table (outline: five
  sorties matching `RLRLR`, one with `rationale`; lift: 4 exercises, reps sets plus one `seconds`
  set, a `weight`, `warmup`, `rest_seconds`, `notes`; run: `kind`, `target_distance`, `target_pace`,
  4 segments; fenced: the same lift plan inside one ` ```json ` fence). They must be documents the
  existing checker already accepts, so write them against the real schema files.
  `app/src/test/java/com/liftoff/app/coach/CoachReplyFixtureTest.kt` (new): the one fixture loader
  for the whole run — read `fixtures/coach/<file>.txt` off the unit-test classpath the way
  `CoachSchemaFilesTest` reads `schemas/outline.json`. `app/src/test/resources` is **not yet proven**
  reachable here; if the classpath read returns null, the fallback is the working-directory route
  `ExerciseNamesTest.coachPackageHasNoAndroidImports` uses (`src/test/resources/fixtures/coach/…`
  under `app/`, trying `../` first) — confine that to the loader so later fixture tests reuse it.
  Assert the parsed values field by field (counts, one exercise's sets, the run plan's segments),
  not merely "no errors".
  Tests: `outlineFixtureParsesIntoTheExpectedOutline`, `liftPlanFixtureParsesIntoTheExpectedLiftPlan`,
  `runPlanFixtureParsesIntoTheExpectedRunPlan`, `fencedFixtureParsesIntoTheExpectedPlan`, plus
  `everyFixtureFileIsCoveredByATest` — it lists the files in the fixture directory and fails on any
  file no test reads (the plan requires it; `verify.sh` does not name it).
  Done when: that class's XML reads `failures="0" errors="0"` with 5 testcases and the four fixtures
  shown to be read (a missing-file failure would read as a missing file, so a green run proves the
  loader works); commit `coach: add the valid coach reply fixtures`.

- [ ] Add the ten invalid fixtures and their expected messages
  Ten more files in `app/src/test/resources/fixtures/coach/`, per the plan's table:
  `bad-json.txt` (braces balance, trailing comma after the last exercise, so it reaches kotlinx),
  `prose-around-json.txt`, `schema-violation.txt`, `unknown-equipment.txt`,
  `inactive-equipment.txt`, `outline-pattern-mismatch.txt`, `reps-and-seconds.txt`,
  `over-length-sortie.txt`, `duplicate-exercise-names.txt`, `plan-type-mismatch.txt`.
  Tests in `CoachReplyFixtureTest.kt`, each asserting the **exact** list of lines so a fixture that
  produces a second, unexpected error fails:
  `badJsonFixtureGivesOneNotValidJsonLine` (the one exception: assert the single line starts
  `$: not valid JSON: ` with a non-empty reason — do not pin kotlinx's sentence);
  `proseAroundJsonFixtureGivesItsOwnError`;
  `schemaViolationFixtureGivesTheCheckersErrors` — derive the lines from `SchemaChecker.kt` rather
  than guessing: a quoted `estimated_minutes` is a type error (`estimated_minutes: expected an
  integer, got a string`) and an extra `day` key on an exercise is
  `exercises[<i>]: unexpected property 'day'`, in the checker's own order;
  `unknownEquipmentFixtureNamesTheIdAndItsPath` and
  `inactiveEquipmentFixtureIsRejectedAsNotInTheEquipmentList` (a deactivated key gets the same
  `… is not in the equipment list` line — Decision);
  `outlinePatternMismatchFixtureGivesItsErrors` — four sorties for `RLRLR` gives
  `sorties: expected 5 sorties for the pattern RLRLR, got 4` **and**
  `sorties[2].type: must be 'run' at position 2 of the pattern RLRLR`;
  `repsAndSecondsFixtureGivesItsError`; `overLengthSortieFixtureGivesItsError` (`estimated_minutes:
  80` against 60 → `estimated_minutes: 80 is more than 75 (1.25 × the 60-minute sortie length)`, and
  keep it under the schema's own `maximum: 180` so only the semantic line fires);
  `duplicateExerciseNamesFixtureGivesItsError` (`Bench Press` at `exercises[0]`, `bench  press` at
  `exercises[3]`); `planTypeMismatchFixtureGivesItsError` (a complete, valid **run** plan offered for
  the lift sortie → the one `type: 'run' does not match the lift sortie being planned` line, with no
  schema errors, per the plan's Decision).
  Done when: that class's XML reads `failures="0" errors="0"` with 15 testcases — the 14 fixture
  tests plus `everyFixtureFileIsCoveredByATest`, which now covers all 14 files; commit
  `coach: add the invalid coach reply fixtures and their messages`.

- [ ] Record it in ARCHITECTURE.md and run the full gate
  Edit only two lines of `ARCHITECTURE.md`: the `com.liftoff.app.coach` row (currently line 35) must
  name both the **validator** and the **fixtures** on its single row line — `verify.sh` greps the row
  for both words case-insensitively, and the row today has neither "fixture" nor a landed validator;
  and the M0 status cell (line 79), which `verify.sh` splits on `;` and the em dash and reads
  clause by clause: one clause must name the validator **and** say exist/landed/present/built/shipped/
  in place/complete/done/added/written/implemented, and a clause naming live/reliability/
  `LiveCoachTest` must say not run/not been/not yet/has not/no measurement/to come/pending/remain/
  still. Today's clause "the validator … are still to come" fails the first test; reword so the
  validator sits in the clause that says *exist* and the live measurement in the clause that says it
  **has not been run**. Wording is otherwise free; no other line, no milestone rewrite.
  Then the whole gate: `rm -rf app/build/test-results/testDebugUnitTest` (if it cannot be cleared, a
  stranded test executor is holding it — `bash gradlew.sh --stop`), then
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, and read **every**
  `TEST-*.xml` for `failures="0" errors="0"`: the five existing coach classes (`CoachSchemaFilesTest`
  3, `CoachSchemaLoadTest` 11, `ExerciseNamesTest` 8, `SchemaCheckerKeywordTest` 17,
  `SchemaCheckerValidDocumentTest` 5) plus `CoachReplyExtractionTest`, `CoachReplySemanticTest`,
  `CoachReplyFixtureTest`, and the rest of the suite. Confirm from the log that
  `:app:testDebugUnitTest` was executed and not `FROM-CACHE`/`UP-TO-DATE`.
  Done when: `git show --stat HEAD` lists `ARCHITECTURE.md` and nothing else; both `verify.sh`
  ARCHITECTURE checks pass (run its two `awk` snippets to see it, rather than eyeballing the rows);
  the gate is `BUILD SUCCESSFUL` **and** every report reads `failures="0" errors="0"`; commit
  `docs: list the coach validator and fixtures in the architecture map`. If the gate turns up a
  defect, fix it in its own commit with its own `area: lowercase summary` message — do not fold a
  code fix into the docs commit.

## Out of scope (a later brief owns it)

No prompt builder, no `GenerationWorker`, no Room mapping from `CoachPlan` to `FlightPlanDraft`, no
`rawJson` on the result, no run-plan length check, no UI, no device-side proof that the schemas are
readable at runtime. Untouched: `CoachSchema.kt`, `SchemaChecker.kt`, `ExerciseNames.kt`, the five
existing coach test classes, the three schema files, `app/build.gradle.kts` (no new dependency —
`kotlinx-serialization-json:1.7.3` is already an `implementation` dep), all UI, and
`.rho/runs/coach-reply-validator-and-fixtures-whjy/verify.sh`.
