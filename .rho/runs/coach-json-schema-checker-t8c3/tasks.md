# Tasks — coach JSON Schema checker

Four tasks, in order. Each is one commit, and each ends with its own tests green.
`verify.sh` matches named tests **by method name** across every `TEST-com.liftoff.app.coach*.xml`
report, so how the methods are grouped into classes is the implementer's call — the names below are
fixed, the classes are not. Never edit `.rho/runs/coach-json-schema-checker-t8c3/verify.sh`.
Run a class with `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.coach.<Class>"`,
then read that class's XML for `failures="0" errors="0"` — `BUILD SUCCESSFUL` alone is not proof.
Stage named files only, LF endings.

- [x] Write the schema model and its loader
  `app/src/main/java/com/liftoff/app/coach/CoachSchema.kt` (new): the parsed model of a schema
  document for exactly the 13 keywords the three files use — `type const enum required
  additionalProperties minimum maximum minLength maxLength minItems maxItems properties items`
  (confirmed against the files; no other keyword appears — every other key is a property name under
  `properties`) — plus `parse(name, text)` and `load(resource)`. `load` reads the resource off the
  classpath the way `CoachSchemaFilesTest.kt:42` already does
  (`javaClass.classLoader.getResourceAsStream("schemas/outline.json")`), so `app/build.gradle.kts`
  is not touched, and passes the text to `parse` with the resource path as the name. `parse` throws
  naming `name` and the keyword on any keyword it does not implement, on a non-boolean
  `additionalProperties`, on a `type` written as a list, and on a malformed schema; a missing
  resource throws naming the resource. Also the three resource paths as constants. Pure Kotlin, no
  Android imports.
  `app/src/test/java/com/liftoff/app/coach/CoachSchemaLoadTest.kt` (new), plain JVM (no
  Robolectric): all three real files load; a walk over all three asserting every keyword they use
  is one the model supports; an unsupported keyword rejected through `parse` with inline schema
  text (no test resource file); a missing resource rejected through `load`. Both failure cases
  name the file.
  Tests: none of `verify.sh`'s named tests live here — the three
  `*SchemaLoadsFromTestClasspath` checks it names already exist in `CoachSchemaFilesTest`, which
  stays untouched.
  Done when: the new class compiles and its XML reads `failures="0" errors="0"` with testcases for
  the three files, the keyword-coverage walk, the unsupported-keyword rejection and the missing
  resource; `CoachSchemaFilesTest` and `ExerciseNamesTest` still pass; commit
  `coach: add the coach schema model and its loader`.

- [x] Write the checker and one test per keyword
  `app/src/main/java/com/liftoff/app/coach/SchemaChecker.kt` (new):
  `check(value: JsonElement, schema: CoachSchema): List<SchemaError>` as an `object` of plain
  functions with no state, like `ExerciseNames.kt`; `SchemaError(path, problem)` with
  `toString() = "$path: $problem"`. Paths: root is `$`, a child of the root is bare (`notes`,
  `kind`), deeper paths append `.name` and `[index]`. Traversal order at every node — own keywords
  (`type`, then `const`/`enum`, then the bounds that apply to a value of that type), then `required`
  in schema order, then unexpected properties in document order, then declared `properties` in
  schema order, then array `items` by index. Every violation in the document is reported in one
  pass. Bounds are checked only when the type matched. Omitting `additionalProperties` means extra
  properties are allowed. Messages and the per-keyword documents/expected lines are the plan's two
  tables; the offending value in `const`/`enum` messages is a string in single quotes (`'jog'`) and
  anything else as its compact JSON text unquoted (`5 is not one of lift, run`). Length and
  item-count wording is singular when the limit is 1, plural otherwise.
  Gotcha to code against, not just know: `JsonPrimitive.isInt/isLong/isDouble/isBoolean` are all
  false for a *quoted* number while `content.toIntOrNull()` still succeeds — decide the type from
  `isString`/`isNull` first, or `"45"` slips through `type: integer`. A quoted number is a type
  error. `integer` accepts a whole number written with a decimal (`45.0` passes, `45.5` fails).
  Bounds are rendered from the schema's own text so `0.5` prints as `0.5`.
  `app/src/test/java/com/liftoff/app/coach/SchemaCheckerKeywordTest.kt` (new), plain JVM, each
  against a **real** schema (not a hand-written mini-schema), each asserting the error list is
  exactly the one line named in `verify.sh`'s comment above it.
  Tests: `typeViolationNamesPathAndExpectedAndActualType` (outline, `"notes": 42` →
  `notes: expected a string, got a number`); `constViolationNamesTheRequiredValue` (lift-plan,
  `"type": "run"` → `type: must be 'lift'`); `enumViolationNamesTheValueAndTheAllowedValues`
  (run-plan, `"kind": "jog"` → `kind: 'jog' is not one of easy, tempo, intervals, long, recovery`);
  `missingRequiredPropertyIsReportedOnItsObject` (outline, `sorties[0]` with `index` and `type` but
  no `focus` → `sorties[0]: missing required property 'focus'`);
  `unexpectedPropertyIsReportedOnItsObject` (outline, `sorties[0]` with `"day": 1` →
  `sorties[0]: unexpected property 'day'`); `numberBelowMinimumIsRejected` (lift-plan,
  `"estimated_minutes": 5` → `estimated_minutes: must be at least 10`); `numberAboveMaximumIsRejected`
  (lift-plan, `"estimated_minutes": 200` → `estimated_minutes: must be at most 180`);
  `stringBelowMinLengthIsRejected` (outline, `sorties[0].focus: ""` →
  `sorties[0].focus: must be at least 1 character`); `stringAboveMaxLengthIsRejected` (outline,
  1001-char `notes` → `notes: must be at most 1000 characters`); `arrayBelowMinItemsIsRejected`
  (lift-plan, 2 exercises → `exercises: must have at least 3 items`); `arrayAboveMaxItemsIsRejected`
  (lift-plan, 9 sets on `exercises[0]` → `exercises[0].sets: must have at most 8 items`);
  `nestedPropertyViolationNamesTheFullPath` (lift-plan, `exercises[0].sets[0].reps: 0` →
  `exercises[0].sets[0].reps: must be at least 1`); `arrayItemViolationNamesItsIndex` (outline,
  `"sorties": ["lift"]` → `sorties[0]: expected an object, got a string`).
  The plan also asks for a quoted-number type case and a non-string `enum` case (outline
  `sorties[0].type: 5` → `sorties[0].type: 5 is not one of lift, run`); give each its **own**
  method — `verify.sh`'s named `type` and `enum` tests must each stay the single-error document
  described above.
  Done when: that class's XML reads `failures="0" errors="0"` with at least 13 testcases covering
  all 13 keywords; commit `coach: add the schema checker with a test per keyword`.

- [ ] Cover valid documents, error order and stability
  `app/src/test/java/com/liftoff/app/coach/SchemaCheckerValidDocumentTest.kt` (new), plain JVM.
  Documents inline in the test, not under `app/src/test/resources/fixtures/` (that directory is
  reserved for recorded coach replies). Each valid document is checked against its real schema
  loaded from the resource file, and must give an empty error list. The lift-plan document covers
  the optional shapes too: a set with `seconds` instead of `reps`, a `weight`, and
  `warmup`/`rest_seconds`/`notes` absent or present.
  Tests: `validOutlineDocumentPasses`; `validLiftPlanDocumentPasses`; `validRunPlanDocumentPasses`;
  `allViolationsAreReportedInDocumentOrder` — the lift-plan document
  `{"type":"run","title":"","estimated_minutes":5,"exercises":[{"name":"Bench Press","equipment":["barbell"],"sets":[{"reps":0}]}],"notes":"ok","extra":true}`
  gives exactly this list in exactly this order:
  ```
  $: unexpected property 'extra'
  type: must be 'lift'
  title: must be at least 1 character
  estimated_minutes: must be at least 10
  exercises: must have at least 3 items
  exercises[0].sets[0].reps: must be at least 1
  ```
  `sameInputGivesTheSameErrorList` — the same broken document checked twice, each time from its own
  parse of the text, gives the same list in the same order.
  Done when: that class's XML reads `failures="0" errors="0"` with a passing valid case per schema
  plus the ordering and repeat-input cases; commit
  `coach: cover valid plans, error order and stability`.

- [ ] Record the checker in ARCHITECTURE.md and run the full gate
  Edit only the `com.liftoff.app.coach` row of `ARCHITECTURE.md` (line 35) so it names the schema
  checker next to the normalizer — the row must contain the word "checker", which is what
  `verify.sh` greps for. No other line of the file, no milestone rewrite.
  Then run the whole gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, and read
  every `TEST-*.xml` under `app/build/test-results/testDebugUnitTest` for `failures="0"
  errors="0"` — `CoachSchemaFilesTest` (3) and `ExerciseNamesTest` (8) included, plus the new
  classes. `ExerciseNamesTest.coachPackageHasNoAndroidImports` scans every `.kt` in
  `src/main/java/com/liftoff/app/coach`, so it is the guard on the two new main files; if it fails,
  fix the imports in the new files rather than the test.
  Done when: `git show --stat HEAD` lists `ARCHITECTURE.md` and nothing else; the coach row contains
  "checker"; the gate is `BUILD SUCCESSFUL` **and** every report reads `failures="0" errors="0"`;
  commit `docs: list the schema checker in the coach package row`.

## Out of scope (a later brief owns it)

Raw-text handling (§7.6 step 1 — code fences, trailing prose, "exactly one JSON object") and every
semantic check (equipment list, `reps`-xor-`seconds`, pattern index match, name uniqueness, session
length). No prompt builder, no worker, no UI, no fixture files, no new dependency, no JSON Schema
library (DESIGN.md §15 decision 14). Untouched: the three schema files, `CoachSchemaFilesTest.kt`,
`ExerciseNames.kt`, `ExerciseNamesTest.kt`, `app/build.gradle.kts`, all UI.
