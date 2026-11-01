# Map

Run branched from `55dfc6e` (merge of #52); HEAD here is `fc8bf9c run: plan outline`.

## The code the fix touches

- `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` — §7.6's validator. `validate(raw, context)` → `CoachReplyResult.Valid(plan, json)` or `.Invalid(errors: List<String>)`. Order: `extract` → `planTypeError` (short-circuits for a well-formed lift/run of the wrong kind) → `SchemaChecker.check(...) + semanticErrors(...)`. `semanticErrors` runs semantic checks only for LIFT flight plans (`liftErrors`) and for outlines; a run plan gets nothing beyond its type matching.
  - `liftErrors` (line ~153): `budgetError`, then `exercises.forEachIndexed` calling `setErrors`, `equipmentErrors`, then the duplicate pass. The name is read as `(node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content`; the duplicate map is `firstByName.putIfAbsent(ExerciseNames.normalize(name), position to name)` (line ~170), and `duplicates` is appended to `errors` **after** the loop, so inline errors come before duplicate lines.
  - Error-line style throughout: `path: problem`, e.g. `exercises[2].equipment: kettlebell is not in the equipment list`, `exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]`, `exercises[0].sets[1]: reps and seconds cannot both be set`. Raw values are quoted in single quotes.
  - Existing precedent for naming a rejected value once: `outlineErrors` only compares `type` with the pattern when the word is one the enum accepts (`declared in TYPE_WORDS`); `aTypeThatIsNeitherLiftNorRunIsReportedOnceByTheChecker` asserts that.
- `app/src/main/java/com/liftoff/app/coach/ExerciseNames.kt` — `normalize(name)`: lowercase, whitespace → space, keep only `isLetterOrDigit() || ' ' || '-'`, collapse runs of spaces, trim. Unicode-aware `isLetterOrDigit`, so accented letters count. `"!!!"` → `""`, `"   "` → `""`, `"!!! squat !!!"` → `"squat"`.
- `app/src/main/java/com/liftoff/app/data/ExerciseDao.kt` — `open suspend fun resolve(name)` (line 27): normalizes, and `if (normalized.isBlank()) throw IllegalArgumentException("Exercise name cannot be blank or symbols-only: '$name'")`; otherwise `findByNormalizedName` or insert a new exercise (`displayName = name.trim()`). This throw is the one the issue is about; it stays as a backstop. Its only main-source caller is `FlightPlanDao.writePlan` (line 80), which `MissionManager` calls from the coach store paths and from `refly` (line 324, copying a stored plan).
- `app/src/main/resources/schemas/lift-plan.json` — exercise `name` is `{ "type": "string", "minLength": 1, "maxLength": 60 }`, so `"!!!"` passes the schema. Outlines have `focus`, run plans have `description`; neither has an exercise name, so the new check belongs in `liftErrors` only.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` (~line 259) — the repair path the fix routes into: `CoachReplyResult.Invalid` → if `row.attempt >= MAX_REPAIRS` then `failGeneration(..., "invalid output: " + errors.joinToString("; "))`, else `bumpAttempt` and `runCoachTurn(..., prompt = CoachPromptBuilder.repairPrompt(reply.errors), clientId = "${row.id}-$attempt", cursor = refused.cursor)` in the same session. Nothing catches the DAO's `IllegalArgumentException`, which is why the symbols-only name currently kills the generation.

## Tests that already exist

- `app/src/test/java/com/liftoff/app/coach/CoachReplyFixtureTest.kt` — one test per invalid fixture asserting its **exact list of error lines** via `CoachFixtures.errorsOf(fixture, context)`. Also `everyFixtureFileIsCoveredByATest`, which diffs the `.txt` files in the fixture directory against the class's private `COVERED` set — **a new fixture file must be added to `COVERED` in the same commit or that test fails.**
- `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt` — inline semantic cases built from a private `liftPlan(exercises, typeField, minutesField)` helper plus `BENCH` / `ROW` / `CURL` exercise JSON constants, and its own `errorsOf(raw, context)`. `duplicateExerciseNameNamesBothSpellingsAndBothIndexes` is the nearest neighbour to the new case.
- `app/src/test/java/com/liftoff/app/coach/CoachReplyExtractionTest.kt` — extraction only; not touched.
- `app/src/test/java/com/liftoff/app/coach/CoachFixtures.kt` — fixture name constants (`const val DUPLICATE_EXERCISE_NAMES = "duplicate-exercise-names.txt"`, …), `KEYS = barbell, dumbbell, bench, pullup-bar`, `OUTLINE_CONTEXT` / `LIFT_CONTEXT` / `RUN_CONTEXT` (pattern `RLRLR`, 60-minute sortie, lift at position 1, run at position 0), `fixture(name)`, `fixtureDirectory()`, `errorsOf(...)`. Fixtures load from the classpath `fixtures/coach/` with a working-directory fallback.
- `app/src/test/java/com/liftoff/app/data/ExerciseResolverTest.kt` — `blankNameIsRejected` already asserts `resolve("!!!")` and `resolve("   ")` throw `IllegalArgumentException` and store nothing. That test stays green: the DAO guard is not being removed.
- Fixture directory `app/src/test/resources/fixtures/coach/` holds 14 `.txt` replies; `duplicate-exercise-names.txt` is the shape to copy (a full lift-plan reply, four exercises, the bad name at index 3).

## Docs

- `ARCHITECTURE.md` row for `com.liftoff.app.coach` (line 35) already lists the validator and the reply fixtures; invariant 8 states "the phone validates every coach reply, with up to 2 in-session repairs". No doc row changes for this fix — no new main-source file, and the fixture directory is already named.

## Gate

- `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (brief). Class-scoped runs for the coach classes: `CoachReplyValidator` tests live in `CoachReplyFixtureTest`, `CoachReplySemanticTest`, `CoachReplyExtractionTest`; the data-side neighbour is `ExerciseResolverTest`.
- Suite size recalled from the issue-55 run branched from this same merge (`55dfc6e`): 737 tests in 89 reports. Re-measure on this branch before writing an expected total anywhere — the count is branch-relative.
