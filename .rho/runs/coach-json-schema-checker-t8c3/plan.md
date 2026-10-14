# Plan — coach JSON Schema checker

Two new files in `com.liftoff.app.coach`, three new test classes, one row of ARCHITECTURE.md.
No UI, no build change, no new dependency, no JSON Schema library (DESIGN.md §15 decision 14).

## Approach, and why it fits this codebase

**A pure function over kotlinx.serialization's already-parsed tree.** §7.6 defines validation as
"a pure function from (raw text, generation context) to … a list of human-readable errors", and
ARCHITECTURE.md invariant 2 keeps `coach/` free of Android imports. So the checker is
`check(value: JsonElement, schema: CoachSchema): List<SchemaError>` — the same shape as
`ExerciseNames.normalize` (`app/src/main/java/com/liftoff/app/coach/ExerciseNames.kt`): an `object`
of plain functions, no state. `kotlinx-serialization-json:1.7.3` is already an `implementation`
dep (`app/build.gradle.kts:63`), so these tests run on the plain JVM like `CoachSchemaFilesTest` —
no Robolectric.

**The schema files stay the single source of truth.** Nothing hard-codes the three schemas. The
loader reads them off the classpath exactly the way `CoachSchemaFilesTest` already does
(`app/src/test/java/com/liftoff/app/coach/CoachSchemaFilesTest.kt:42`,
`javaClass.classLoader.getResourceAsStream("schemas/outline.json")`). That path is proven reachable
from `testDebugUnitTest` by that test passing today, so `app/build.gradle.kts` is not touched.

**Two files, because the two jobs are separate.**
- `CoachSchema.kt` — the schema side: a small parsed model of a schema document (the 13 keywords
  below), plus two entry points:
  - `parse(name: String, text: String)` — builds the model from schema text and **throws on any
    keyword it does not implement**, naming `name` and the keyword. Rejecting at load is what keeps
    the checker honest as the schemas grow.
  - `load(resource: String)` — reads the resource off the classpath (throwing, naming the resource,
    if it is missing) and hands the text to `parse` with the resource path as the name.
  `parse` exists so the unsupported-keyword test can state its bad schema inline, without a test
  resource file.
- `SchemaChecker.kt` — the document side: `check(JsonElement, CoachSchema): List<SchemaError>`,
  `SchemaError(path, problem)` with `toString() = "$path: $problem"`, so the brief's three example
  lines are exactly what the checker emits. The structured pair is there because the later semantic
  validator splices these strings into repair prompts (§7.6).

**The keyword list is the brief's list — checked, not assumed.** I extracted every key from the
three files: the union is `additionalProperties const enum items maxItems maxLength maximum
minItems minLength minimum properties required type`, and every other key in those files is a
*property name* under `properties` (`focus`, `sets`, `kind`, `segments`, …). So the brief's "if they
use any other keyword, support it too" clause does not fire — no extra keyword. All 13 appear in at
least one of the three files, so every violation test can use a real schema instead of a
hand-written mini-schema. That is what the done-when asks for ("run the checker on each of the three
schemas").

Distribution, for picking which schema each keyword test uses: `enum` only in outline + run-plan,
`const` only in lift-plan + run-plan, `maximum`/`minItems`/`maxItems` only in lift-plan + run-plan,
the rest in all three.

**Tests hold their documents inline, not under `app/src/test/resources/fixtures/`.** ARCHITECTURE.md
reserves `fixtures/` for recorded coach replies (the later validator brief); a checker test that
states its document and its expected message side by side cannot drift from a fixture someone edits.

**The Android-import guard covers the new files for free.**
`ExerciseNamesTest.coachPackageHasNoAndroidImports` scans every `.kt` in
`src/main/java/com/liftoff/app/coach`, so both new main files are already guarded by a test that
exists. `kotlinx.serialization` imports do not trip it.

## Files involved

| File | What happens |
|---|---|
| `app/src/main/java/com/liftoff/app/coach/CoachSchema.kt` (new) | parsed schema model; `parse(name, text)`; `load(resource)` off the classpath; the three resource paths as constants; unsupported keyword or malformed schema → error naming the resource and the keyword |
| `app/src/main/java/com/liftoff/app/coach/SchemaChecker.kt` (new) | `check(JsonElement, CoachSchema): List<SchemaError>`; the 13 keywords; path building; fixed traversal order |
| `app/src/test/java/com/liftoff/app/coach/CoachSchemaLoadTest.kt` (new) | all three files load; a walk asserting every keyword the three files use is one the checker supports; an unsupported keyword (inline schema text through `parse`) and a missing resource (through `load`) both fail loudly, naming the file |
| `app/src/test/java/com/liftoff/app/coach/SchemaCheckerKeywordTest.kt` (new) | one test per keyword, each against a real schema, asserting the exact path and message |
| `app/src/test/java/com/liftoff/app/coach/SchemaCheckerValidDocumentTest.kt` (new) | a valid document per schema gives an empty list; a multi-error document gives the exact list in the exact order; the same input twice gives the same list |
| `ARCHITECTURE.md` (edit) | the `com.liftoff.app.coach` row (line 35) names the schema checker next to the normalizer |

Untouched: the three schema files, `CoachSchemaFilesTest.kt`, `ExerciseNames.kt`,
`ExerciseNamesTest.kt`, `app/build.gradle.kts`, all UI, and
`.rho/runs/coach-json-schema-checker-t8c3/verify.sh` (owned by the runner).

## Order

Each step compiles and passes on its own. Commit after each.

**Step 1 — schema model and loader.** `CoachSchema.kt`, `CoachSchemaLoadTest.kt`.
Proves it: `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.coach.CoachSchemaLoadTest"`
→ `BUILD SUCCESSFUL` and that class's XML reads `failures="0" errors="0"`, with testcases for the
three real files, the keyword-coverage walk, the unsupported-keyword rejection and the missing
resource.

**Step 2 — the checker, one test per keyword.** `SchemaChecker.kt`, `SchemaCheckerKeywordTest.kt`.
Proves it: the same command for `SchemaCheckerKeywordTest`; its XML reads `failures="0" errors="0"`
with at least 13 testcases — `type`, `const`, `enum`, `required`, `additionalProperties`, `minimum`,
`maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `properties`, `items`.

**Step 3 — valid documents, error order, stability.** `SchemaCheckerValidDocumentTest.kt`.
Proves it: same command for that class; `failures="0" errors="0"`, with a passing valid case for
each of the three schemas plus the ordering and repeat-input cases.

**Step 4 — record it in ARCHITECTURE.md.** The `coach` row only.
Proves it: `git show --stat HEAD` lists `ARCHITECTURE.md` and nothing else; the coach tests still
pass.

**Step 5 — the full gate.** No changes.
Proves it: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL`, and
every `TEST-*.xml` under `app/build/test-results/testDebugUnitTest` reads `failures="0" errors="0"`
— `CoachSchemaFilesTest` (3) and `ExerciseNamesTest` (8) included. `BUILD SUCCESSFUL` alone is not
read as proof.

Commit messages: `coach: add the coach schema model and its loader`,
`coach: add the schema checker with a test per keyword`,
`coach: cover valid plans, error order and stability`,
`docs: list the schema checker in the coach package row`. Stage named files only; LF endings.

## What the checker does, precisely

Traversal order at every node — this is what makes the list stable:
1. the value's own keywords (`type`, then `const`/`enum`, then the bounds that apply to a value of
   that type);
2. `required`, in the order the schema lists it;
3. unexpected properties, in the order the document lists them;
4. declared `properties`, in the order the schema lists them;
5. array `items`, by index.

Paths: the document root is `$`; a child of the root is written bare (`notes`, `kind`); deeper paths
append `.name` and `[index]` (`exercises[2].sets[0].reps`, `sorties[0]`).

Messages the tests assert:

| Keyword | Message |
|---|---|
| `type` | `expected a string, got a number` / `…got a string` / `…got a boolean` / `…got null` / `expected an object, got an array` / `expected an array, got an object` / `expected an integer, got a number` |
| `const` | `must be 'lift'` |
| `enum` | `'jog' is not one of easy, tempo, intervals, long, recovery` |
| `required` | `missing required property 'focus'` (on the containing object) |
| `additionalProperties: false` | `unexpected property 'day'` (on the containing object) |
| `minimum` / `maximum` | `must be at least 10` / `must be at most 180` — the bound rendered from the schema's own text, so `0.5` prints as `0.5` |
| `minLength` / `maxLength` | `must be at least 1 character` / `must be at most 1000 characters` |
| `minItems` / `maxItems` | `must have at least 1 item` / `must have at most 8 items` |

The offending value in `const`/`enum` messages: a string in single quotes (`'jog'`); any other value
as its compact JSON text without quotes (`5 is not one of lift, run`, `null is not one of …`,
`{"a":1} is not one of …`). The keyword test for `enum` adds one non-string case
(outline, `sorties[0].type: 5` → `sorties[0].type: 5 is not one of lift, run`).

The violations the tests construct, each against a real schema:

| Keyword | Schema | Broken document | Expected line |
|---|---|---|---|
| `type` | outline | `notes: 42` | `notes: expected a string, got a number` |
| `const` | lift-plan | `"type": "run"` | `type: must be 'lift'` |
| `enum` | run-plan | `"kind": "jog"` | `kind: 'jog' is not one of easy, tempo, intervals, long, recovery` |
| `required` | outline | a sortie with no `focus` | `sorties[0]: missing required property 'focus'` |
| `additionalProperties` | outline | a sortie with `"day": 1` | `sorties[0]: unexpected property 'day'` |
| `minimum` | lift-plan | `estimated_minutes: 5` | `estimated_minutes: must be at least 10` |
| `maximum` | lift-plan | `estimated_minutes: 200` | `estimated_minutes: must be at most 180` |
| `minLength` | outline | `sorties[0].focus: ""` | `sorties[0].focus: must be at least 1 character` |
| `maxLength` | outline | `notes` of 1001 chars | `notes: must be at most 1000 characters` |
| `minItems` | lift-plan | 2 exercises | `exercises: must have at least 3 items` |
| `maxItems` | lift-plan | 9 sets on `exercises[0]` | `exercises[0].sets: must have at most 8 items` |
| `properties` (nested) | lift-plan | `exercises[0].sets[0].reps: 0` | `exercises[0].sets[0].reps: must be at least 1` |
| `items` | outline | `sorties: ["lift"]` | `sorties[0]: expected an object, got a string` |

`integer` is a number with no fractional part (`45.0` passes, `45.5` fails). Bounds are checked only
when the type matched. A non-boolean `additionalProperties`, a `type` written as a list of types, or
any other unsupported construct fails at load.

## Not doing

No raw-text handling (§7.6 step 1 — code fences, trailing prose, "exactly one JSON object") and no
semantic checks (equipment list, `reps`-xor-`seconds`, pattern index match, name uniqueness, session
length): the brief says a later brief builds the semantic validator on top of this checker. No
prompt builder, no worker, no UI, no fixture files, no ARCHITECTURE.md milestone rewrite beyond the
`coach` row the brief names.

## Risks and uncertainties

- **Classpath reachability from the *app* is not proven by anything in this repo.** The three files
  are proven reachable from `testDebugUnitTest`. Java resources under `src/main/resources` are
  packaged into the APK, but reading them at runtime on a device is a different mechanism and nothing
  here tests it. This brief only needs the test classpath, so the loader uses the proven path. If the
  later generation brief finds the app cannot read them, the fix is moving them to
  `src/main/assets` — deliberately not done now, because it moves files this brief's done-when names.
- **`JsonPrimitive.isInt`/`isDouble`/`isBoolean` are all false for a *quoted* number, while
  `content.toIntOrNull()` succeeds.** Type must be decided from `isString`/`isNull` first, or `"45"`
  slips through `type: integer`. The `type` tests cover both directions.
- **Error order depends on `JsonObject` preserving document order.** It is LinkedHashMap-backed, but
  step 3 asserts one specific multi-error list rather than trusting that, so a future kotlinx change
  fails loudly instead of silently rewriting repair prompts.
- **These strings become the repair-prompt text** (§7.6: "sent back verbatim"). The later brief owns
  the prompt around them; if it wants different wording it edits these strings and their tests
  together. Pinning the wording here is what stops the two briefs drifting silently.
- **A schema edited later to add a keyword (`anyOf`, say) makes the loader throw.** That is the
  intent, but it would surface at generation time; step 1's keyword-coverage walk over all three
  files means the gate catches it first.

## Decisions

- **Added by review: how is a non-string offending value written in `const`/`enum` messages?**
  Chosen: strings in single quotes, as in the brief's `'jog'`; anything else as its compact JSON
  text, unquoted — `5 is not one of lift, run`. Alternative: quote everything (`'5' is not one of
  …`). Quoting a number would tell the model it sent the string `"5"`, which is the wrong fix to ask
  for in a repair prompt.
- **What path does an error about the document itself carry?** Chosen: `$` for the document, and no
  prefix on a top-level property (`notes: …`, `kind: …`). Alternative: JSONPath everywhere
  (`$.notes`, `$.sorties[0]`). The brief's three example lines are the text the owner will read in a
  repair prompt, and a `$` on every line would make those examples wrong; `$` is used only where
  there is no property to name.
- **Is a missing required property reported on the parent object or on the missing child?** Chosen:
  on the parent — `sorties[0]: missing required property 'focus'`. Alternative:
  `sorties[0].focus: missing`. A missing key has no value at that path, so naming the object that
  lacks it is what a reader can act on, and it matches the brief's own
  `sorties[0]: unexpected property 'day'`.
- **Is a quoted number accepted by a numeric type?** Chosen: no — `"estimated_minutes": "45"` is a
  type error. Alternative: accept numeric strings, since models often emit them. The schema says
  `integer`; silently accepting a string hides a real contract break from the repair prompt and
  pushes coercion into the later semantic validator, which is not where that call belongs.
- **Does `type: integer` accept a whole number written with a decimal (`45.0`)?** Chosen: yes;
  `45.5` fails. Alternative: reject anything written with a fraction bar. JSON has one number type
  and a model writing `45.0` means 45 — rejecting it would fail an otherwise-correct plan and spend
  one of the two repairs on nothing.
- **When a value is the wrong type, are its bounds reported too?** Chosen: no — one error per value,
  the type error. Alternative: report `expected a string, got a number` *and* `must be at most 1000
  characters`. The bounds of a value that is not a string say nothing the first error did not, and a
  repair prompt reads best with each problem named once.
- **In what order do the errors come out?** Chosen: the order the document and schema put them in —
  the value's own keywords, then `required` in schema order, then unexpected properties in document
  order, then declared properties in schema order, then array items by index. Alternative: sort the
  finished list by path. Both are stable, but the brief's `exercises[2].sets: …` example implies the
  plan's own order, and a prompt that walks the plan top to bottom is what the model can fix;
  alphabetical sorting would put `sets` before `name` for no reason a reader could see.
- **What happens when a schema file uses a keyword the checker does not implement?** Chosen: the
  loader throws, naming the file and the keyword. Alternative: ignore unknown keywords. Silently
  ignoring one means the phone accepts a document the schema forbids — the failure §7.6 exists to
  prevent — and it would fail silently rather than in the gate.
- **Does one violation stop the checker from reporting the others?** Chosen: no — every violation in
  the document is reported, in one pass. Alternative: stop at the first error. §7.6 allows two
  repairs, so a reply that breaks four rules needs all four named in the first prompt or the budget
  is spent one error at a time.
- **Are the length and item-count messages singular or plural?** Chosen: singular when the limit is
  1, plural otherwise — `must have at least 1 item` (the brief's own example), `must have at most 8
  items`, `must be at least 1 character`, `must be at most 1000 characters`. Alternative: always
  plural, which would contradict the brief's example line.
- **What if a schema object omits `additionalProperties`?** Chosen: extra properties are allowed, as
  in JSON Schema. Alternative: treat omission as `false`, i.e. reject anything undeclared. All three
  files currently say `false` explicitly, so this only bites a future schema edit — and a checker that
  invented a stricter rule than the schema it is reading would reject a plan the schema permits.
