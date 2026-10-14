# Plan — coach reply validator and fixtures

Two new main files and one new test file in `com.liftoff.app.coach`, fourteen fixture files under
`app/src/test/resources/fixtures/coach/`, three new test classes, two rows of ARCHITECTURE.md.
No UI, no build change, no new dependency, no change to `CoachSchema.kt` / `SchemaChecker.kt` /
`ExerciseNames.kt` or their tests.

## Approach, and why it fits this codebase

**The validator is the layer §7.6 describes, sitting on top of the checker that already exists.**
`SchemaChecker.check(JsonElement, CoachSchema): List<SchemaError>` already does §7.6 step 2 and
already produces `path: problem` lines. So the new code adds exactly the two things that were
deliberately left out of that run: §7.6 step 1 (raw text → one JSON object) and the semantic
checks. It reuses the checker's error list rather than inventing a second error style — the strings
are sent back verbatim in repair prompts (§7.3 step 5), so one vocabulary is the whole point.

**Same shape as everything else in `coach/`.** `ExerciseNames.kt` and `SchemaChecker.kt` are both
`object`s of plain stateless functions with no Android imports; `CoachReplyValidator` follows it.
`coach/` stays pure Kotlin (ARCHITECTURE invariant 2), so the tests are plain JVM JUnit like
`CoachSchemaFilesTest` — no Robolectric. `kotlinx-serialization-json:1.7.3` is already an
`implementation` dep (`app/build.gradle.kts:63`), so nothing is added.

**The context reuses the types the app already has.** The generation context is a small data class
carrying `GenerationKind`, the Mission `pattern` string, the `SortieType` of the sortie being
planned, the active equipment keys and the configured sortie length. `GenerationKind` and
`SortieType` are plain enums in `com.liftoff.app.data.Enums.kt` (that file has no Android imports),
and `domain/Pattern.kt` already maps a pattern string to `List<SortieType>` with `sortieTypesOf`.
Reusing both is what keeps §2 vocabulary in one place; `domain/` already imports those enums, so
`coach/` importing them is the same direction, and `coach/` importing `sortieTypesOf` keeps one
definition of what `R` and `L` mean.

**The parsed result is plain coach types, not Room rows.** §7.6 says the result is "a parsed result
in plain coach types". `data/FlightPlan.kt` and its `FlightPlanDraft` are Room-shaped (ids,
`sortieId`, `rawJson`) and belong to the worker brief that stores the result, not to the validator.
So `coach/CoachModels.kt` holds `CoachOutline` / `CoachLiftPlan` / `CoachRunPlan` and their nested
types, and the later worker maps them into `FlightPlanDraft`.

**Raw-text extraction is a small explicit scanner, not a parser trick.** `Json.parseToJsonElement`
rejects trailing content, but the message it throws (`Unexpected JSON token at offset 41 …`) is a
kotlinx message, and it cannot tell "prose after the JSON" apart from "the JSON is broken". The
brief needs those two fixtures to give *their own* messages, so extraction is done in three
explicit steps: trim → unwrap one fence → locate exactly one top-level `{…}` by depth-counting
while respecting strings and escapes → hand that substring to kotlinx for the syntax error. Each
failure mode then has its own line.

**Fixtures are the tests.** §13 says "Validator: run against `fixtures/`". Each fixture is the raw
reply text as the coach would have sent it (`.txt`, because some carry prose or a fence), read off
the test classpath, run through `validate`, and asserted against the exact expected parsed value or
the exact error line. One test class per group (valid / invalid), plus one test that fails if a
fixture file exists that no test reads — a fixture nobody runs is invisible coverage.

## Files involved

| File | What happens |
|---|---|
| `app/src/main/java/com/liftoff/app/coach/CoachModels.kt` (new) | the generation context and the parsed coach types: `GenerationContext`, `CoachOutline`/`CoachSortieFocus`, `CoachLiftPlan`/`CoachExercise`/`CoachSet`, `CoachRunPlan`/`CoachSegment`, and the result `sealed interface CoachReplyResult { Valid(plan: CoachPlan) ; Invalid(errors: List<String>) }` |
| `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` (new) | `object CoachReplyValidator`, `validate(raw: String, context: GenerationContext): CoachReplyResult`; private `extract` (trim, fence, one object), private semantic checks per kind; the three schemas memoized with `lazy` so one generation does not re-read the classpath |
| `app/src/test/java/com/liftoff/app/coach/CoachReplyExtractionTest.kt` (new) | §7.6 step 1 on its own: trim, one fence unwrapped, fence with an info string, two fences, prose before, prose after, bad JSON, unclosed object, empty reply, a non-object reply |
| `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt` (new) | one test per semantic rule with the document inline, each asserting the exact `path: problem` line |
| `app/src/test/java/com/liftoff/app/coach/CoachReplyFixtureTest.kt` (new) | runs `validate` over every fixture file; valid fixtures assert the parsed values field by field; invalid fixtures assert the exact error line; plus `everyFixtureFileIsCoveredByATest` |
| `app/src/test/resources/fixtures/coach/*.txt` (14 new) | the recorded-style replies listed below |
| `ARCHITECTURE.md` (edit) | the `com.liftoff.app.coach` row names the validator and the fixtures; the M0 row says the template, schemas and validator exist and the live measurement has not been run |

Untouched: `CoachSchema.kt`, `SchemaChecker.kt`, `ExerciseNames.kt`, the five existing coach test
classes, the three schema files, `app/build.gradle.kts`, all UI, and
`.rho/runs/coach-reply-validator-and-fixtures-whjy/verify.sh` (owned by the runner).

### The shape of the API

```kotlin
data class GenerationContext(
    val kind: GenerationKind,
    val pattern: String,              // the Mission pattern, e.g. "RLRLR"
    val sortieType: SortieType? = null,   // null for OUTLINE, which plans no single sortie
    val equipmentKeys: List<String>,  // active keys only
    val sortieLengthMinutes: Int,
)

object CoachReplyValidator {
    fun validate(raw: String, context: GenerationContext): CoachReplyResult
}
```

`Valid` carries a `CoachPlan` (`CoachOutline`, `CoachLiftPlan` or `CoachRunPlan`); `Invalid` carries
`List<String>` of `path: problem` lines, ready to splice into a repair prompt unchanged.

### The pipeline

1. **Extract.** Trim. Unwrap one surrounding fence. Find exactly one top-level JSON object. Any
   failure here returns a one-error `Invalid` and stops — there is no document to run the other
   checks against.
2. **Schema.** `CoachSchemas.load` for the kind's schema, then `SchemaChecker.check`. Errors keep
   the checker's paths and wording exactly.
3. **Semantic.** Only over the parts that exist and matched their declared type, so a document that
   failed the schema at a node is not re-checked inside that node.

Schema choice: `OUTLINE` → `outline.json`; `FLIGHT_PLAN` → the schema for the **sortie type in the
context** (a lift sortie gets `lift-plan.json`). See Decisions for what that means for a reply that
is the wrong kind of plan.

### Semantic checks and the exact lines

Order: plan-level checks in the brief's order, then exercises in index order (each exercise's sets,
then its equipment), then name duplicates at the later index. Every line is `path: problem`.

| Check | Path | Message |
|---|---|---|
| plan type vs the sortie | `type` | `'run' does not match the lift sortie being planned` |
| outline size vs pattern | `sorties` | `expected 5 sorties for the pattern RLRLR, got 4` |
| outline index | `sorties[3].index` | `must be 3` |
| outline type vs pattern | `sorties[2].type` | `must be 'run' at position 2 of the pattern RLRLR` |
| set with both | `exercises[0].sets[1]` | `reps and seconds cannot both be set` |
| set with neither | `exercises[0].sets[1]` | `set must have either reps or seconds` |
| equipment id | `exercises[2].equipment` | `barbell is not in the equipment list` |
| over-length sortie | `estimated_minutes` | `80 is more than 75 (1.25 × the 60-minute sortie length)` |
| duplicate name | `exercises[3].name` | `'Bench Press' duplicates exercises[0]` |

Extraction lines, all at path `$`: `unexpected text before the JSON object`, `unexpected text after
the JSON object`, `not valid JSON: <kotlinx's reason>`, `the JSON object is not closed`, `expected a
JSON object, got an array` (word from the first character: `[` array, `"` string, `t`/`f` boolean,
digit or `-` number, anything else text), `the reply is empty`.

### The fixtures

`app/src/test/resources/fixtures/coach/`, each the raw reply text, each with a fixed
`GenerationContext` stated in the test (pattern `RLRLR`, sortie 2 = LIFT, active keys
`barbell, dumbbell, bench, pullup-bar`, sortie length 60):

| File | Kind | Shows |
|---|---|---|
| `outline-valid.txt` | OUTLINE | five sorties matching `RLRLR`, one with `rationale` |
| `lift-plan-valid.txt` | FLIGHT_PLAN | 4 exercises, reps sets and one seconds set, a weight, `warmup`, `rest_seconds`, `notes` |
| `run-plan-valid.txt` | FLIGHT_PLAN (sortie 0 = RUN) | `kind`, `target_distance`, `target_pace`, 4 segments |
| `lift-plan-fenced.txt` | FLIGHT_PLAN | the same valid plan wrapped in one ` ```json ` fence |
| `bad-json.txt` | FLIGHT_PLAN | a trailing comma and an unterminated string |
| `prose-around-json.txt` | FLIGHT_PLAN | "Here's the plan:" before the object, a sentence after it |
| `schema-violation.txt` | FLIGHT_PLAN | `estimated_minutes` as a string, an exercise with an extra `day` key |
| `unknown-equipment.txt` | FLIGHT_PLAN | `exercises[2].equipment` uses `kettlebell`, which is not in the list |
| `inactive-equipment.txt` | FLIGHT_PLAN | `exercises[1].equipment` uses `rowing-machine`, a key the context does not list because it is deactivated |
| `outline-pattern-mismatch.txt` | OUTLINE | four sorties for `RLRLR`, and the third one is `run` where the pattern says `lift` |
| `reps-and-seconds.txt` | FLIGHT_PLAN | `exercises[0].sets[1]` has both |
| `over-length-sortie.txt` | FLIGHT_PLAN | `estimated_minutes: 80` against a 60-minute sortie |
| `duplicate-exercise-names.txt` | FLIGHT_PLAN | `Bench Press` at `exercises[0]` and `bench  press` at `exercises[3]` |
| `plan-type-mismatch.txt` | FLIGHT_PLAN (sortie 2 = LIFT) | a complete, valid **run** plan offered for the lift day |

Valid fixtures are asserted field by field (counts, one exercise's sets, the run plan's segments),
not just "no errors". Invalid fixtures assert the exact list of lines, so a fixture that produces a
second unexpected error fails.

## Order

Each step compiles and its tests pass on its own; commit after each.

**Step 1 — models and extraction.** `CoachModels.kt`, `CoachReplyValidator.kt` (the `extract` half
plus a `validate` that runs extraction only), `CoachReplyExtractionTest.kt`.
Proves it: `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.coach.CoachReplyExtractionTest"`
→ `BUILD SUCCESSFUL` **and** that class's `TEST-*.xml` read `failures="0" errors="0"` with the
fence, prose, bad-JSON, unclosed and empty cases present.

**Step 2 — schema pass and semantic checks.** finish `validate`, the per-kind semantic checks,
`CoachReplySemanticTest.kt`.
Proves it: the same command for `CoachReplySemanticTest`; `failures="0" errors="0"` with one passing
testcase per row of the semantic table, plus a case showing a schema violation and a semantic
violation in one document both appear, in order.

**Step 3 — fixtures.** the 14 `.txt` files, `CoachReplyFixtureTest.kt`.
Proves it: the same command for `CoachReplyFixtureTest`; `failures="0" errors="0"`, with 14 fixture
testcases plus `everyFixtureFileIsCoveredByATest`. Fixture loading is proven by the tests passing,
not assumed.

**Step 4 — record it in ARCHITECTURE.md.** the `coach` row and the M0 row only.
Proves it: `git show --stat HEAD` lists `ARCHITECTURE.md` and nothing else.

**Step 5 — the full gate.** no changes.
Proves it: `rm -rf app/build/test-results/testDebugUnitTest` then
`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` → `BUILD SUCCESSFUL`
with `:app:testDebugUnitTest` executed rather than `FROM-CACHE`, and every `TEST-*.xml` read with
`failures="0" errors="0"` — the five existing coach classes (`CoachSchemaFilesTest` 3,
`CoachSchemaLoadTest` 11, `ExerciseNamesTest` 8, `SchemaCheckerKeywordTest` 17,
`SchemaCheckerValidDocumentTest` 5) included. `BUILD SUCCESSFUL` alone is not read as proof.

Commit messages: `coach: extract one JSON object from a coach reply`,
`coach: add the semantic checks to the reply validator`,
`coach: add coach reply fixtures and run the validator over them`,
`docs: list the coach validator and fixtures in the architecture map`.
Stage named files only; LF endings (`git ls-files --eol` should read `i/lf w/lf`).

## Risks and uncertainties

- **`app/src/test/resources` is not yet proven reachable in this repo.** No test today reads a test
  resource. AGP's `processDebugUnitTestJavaRes` is in the task graph (`:app:processDebugUnitTestJavaRes`
  appears in `:app:testDebugUnitTest --dry-run`), which is the same mechanism that already makes
  `app/src/main/resources/schemas/` readable from `CoachSchemaFilesTest`, so the classpath read is
  the first choice. If step 3 shows it is not on the classpath, the fallback is the route
  `ExerciseNamesTest.coachPackageHasNoAndroidImports` already uses — a path relative to the test's
  working directory (`src/test/resources/fixtures/coach/…` under `app/`) — and that change is
  confined to the fixture test's loader.
- **The message strings are load-bearing.** They are the repair-prompt text (§7.3 step 5) and the
  verify step will assert them. The brief's own example line and DESIGN.md §7.6's differ in one
  detail (see Decisions), and the plan follows the brief. Every line is listed above so a reviewer
  can change one row instead of guessing at the implementation.
- **The hand-rolled object scanner is the risky code.** It must respect strings and escapes while
  depth-counting, or `{"note": "}"}` closes early. Step 1's tests cover a brace inside a string, an
  escaped quote, and an unclosed object; the scanner is only ever asked to find *one* top-level
  object, so it never needs to understand the document.
- **kotlinx's syntax message is somebody else's wording.** `not valid JSON: <reason>` embeds
  whatever 1.7.3 says. The test asserts the prefix and that the reason is non-empty, not the exact
  sentence, so a kotlinx upgrade cannot silently break a fixture assertion — but the reason a coach
  reads is then partly kotlinx's, not ours.
- **A wrong-kind plan that is also malformed gets only the type error** (see Decisions). That is a
  deliberate trade: the coach fixes the decisive mistake first and has two repairs. If the M0 live
  measurement shows that costs repairs, the fix is to run the schema pass too, which is a change to
  one branch of `validate` and its tests.
- **`estimated_minutes` has two ceilings** — the schema's `maximum: 180` and 1.25 × the sortie
  length — and both can fire on the same document. The fixture keeps them apart so each message is
  asserted on its own.
- **Nothing here proves the app can read the schemas on a device.** `CoachSchemas.load` is a
  classpath read, proven for unit tests only. The generation worker brief owns that question.

## Not doing

No prompt builder, no `GenerationWorker`, no Room mapping from `CoachPlan` to `FlightPlanDraft`, no
`rawJson` on the result (the worker can re-serialize the extracted element when it needs the column),
no run-plan length check (segments carry no minutes ceiling in §7.5.3), no UI, no ARCHITECTURE.md
milestone rewrite beyond the two rows the brief names.

## Decisions

- **What does the validator do when the reply is a well-formed plan of the wrong kind — a run plan
  offered for a lift day?** Chosen: the schema is chosen from the context (the sortie being planned),
  the plan-type-vs-sortie check runs first, and when it fires the validator returns that one error —
  `type: 'run' does not match the lift sortie being planned` — without running the schema pass.
  Alternative: validate against the lift-plan schema and report everything it says, which for that
  document is five errors (`type: must be 'lift'`, missing `estimated_minutes`, missing `exercises`,
  unexpected `kind`, unexpected `segments`). The coach reading a repair prompt needs the one mistake
  it actually made; five derivative errors bury it and spend the 2-repair budget on one problem.
- **Is an exercise name compared raw or normalized for uniqueness?** Chosen: normalized, with
  `ExerciseNames.normalize`, so `Bench Press` and `bench  press` collide and the error names both
  indexes. Alternative: exact string comparison. §7.7 stores each exercise once keyed by its
  normalized name, so two spellings of one movement are one exercise and would collide at storage —
  catching it here is what stops the plan failing to save later.
- **How is an equipment id written in its error?** Chosen: the brief's own line, unquoted —
  `exercises[2].equipment: barbell is not in the equipment list`. Alternative: DESIGN.md §7.6's
  quoted form (`"barbell" is not in the equipment list`), or the checker's single-quote style
  (`'barbell'`). The brief is the definition of done for this run and its example is the line the
  owner will read; the two documents disagree and the plan follows the brief. Changing it is one row
  of the message table.
- **Does a deactivated equipment key get its own message?** Chosen: no — the same
  `… is not in the equipment list` line, because the context carries only the active keys and the
  validator cannot tell an off-list key from an off-list-but-known one. Alternative: a second
  message, `… is deactivated`, which would need the context to carry inactive keys too. The coach
  only needs to know the id is unusable; whether it exists is the phone's business, and §7.4 sends
  only active equipment in the prompt anyway.
- **Is a set with neither `reps` nor `seconds` an error?** Chosen: yes — `set must have either reps or
  seconds`. Alternative: accept it as a bodyweight or rest-only set. §7.6 says "exactly one", and a
  set with no target is something the In-Flight checklist cannot show or tick, so a plan containing
  one is not a usable plan.
- **How is the 1.25 × budget computed when the sortie length is not a multiple of 4?** Chosen: exact
  comparison against `1.25 × length` as a number, so a 50-minute sortie rejects `estimated_minutes: 63`
  (limit 62.5) and the message prints the fractional limit. Alternative: round the limit to a whole
  minute. Rounding up would accept a plan 0.5 minutes over the owner's configured length, which is
  the exact thing the check exists to prevent.
- **Which code fences are unwrapped?** Chosen: one surrounding fence — the text's first meaningful
  line is three or more backticks with an optional info string (` ```json `), its last meaningful
  line is at least that many backticks, and only the outermost pair is removed. Tildes
  (`~~~`) are not accepted, and a reply containing two separate fenced blocks is not unwrapped, so it
  fails as `unexpected text after the JSON object`. Alternative: strip every fence in the text.
  Models write ` ```json ` almost always, and unwrapping only a single surrounding fence is what
  "unwrap a single surrounding Markdown code fence" means; a reply with two JSON objects is a real
  mistake the coach should be told about.
- **When extraction fails, are the schema and semantic checks still run?** Chosen: no — one error
  and stop. Alternative: report the extraction failure alongside guesses at the rest. There is no
  document to check; every further error would be about a value the validator could not locate.
- **When the schema pass finds errors, are the semantic checks still run?** Chosen: yes, over the
  parts that exist and matched their declared type — a document with both a schema violation and a
  bad equipment id gets both lines, schema errors first. Alternative: stop after the schema pass.
  §7.3 allows two repairs, so a reply that breaks two unrelated rules needs both named at once or
  the budget is spent one error at a time. Skipping a node whose type already failed is what keeps
  the list free of errors about values that are not there.
- **Does the OUTLINE generation context need a sortie type?** Chosen: no — `sortieType` is nullable
  and only `FLIGHT_PLAN` requires it; an outline generation plans the whole week, not one sortie.
  Alternative: require it and ignore it for outlines. A required field the caller has no honest value
  for is a field that gets filled with a lie by the worker brief.
- **Is the outline's sortie count compared to the pattern length, or is a longer outline accepted?**
  Chosen: exactly one entry per pattern position — a size mismatch is an error naming both numbers,
  and each entry's `index` and `type` are checked against its position. Alternative: accept extra
  sorties and ignore them. §7.6 says "exactly one entry per pattern position", and the week's sorties
  are already fixed by the pattern, so an outline with a sixth day is the coach proposing a week the
  app cannot store.
