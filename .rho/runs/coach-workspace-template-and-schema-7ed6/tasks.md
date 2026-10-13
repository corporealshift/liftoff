# Tasks — coach workspace template and schema files

Four tasks, each ending in its own commit. Read `.rho/runs/coach-workspace-template-and-schema-7ed6/brief.md`
and `.rho/runs/coach-workspace-template-and-schema-7ed6/plan.md` before your task; the plan's *Decisions*
section is already agreed, so follow it rather than re-deciding. Commit messages are `area: lowercase
summary` (CLAUDE.md), stage named files only, never `git add -A`. LF endings.

`coach/README.md`, `coach/COACH.md` and the two ARCHITECTURE.md rows are prose: `verify.sh` does not grep
them, the owner reviews them in the pull request. Write them from the DESIGN.md sections the brief names,
not from a checklist of phrases.

- [x] Write the coach workspace template: `coach/README.md` and `coach/COACH.md`
  Docs only, no build impact. Create `coach/` at the repo root (it does not exist yet) with exactly the two
  files §12's layout names.
  - `coach/README.md` — the one-time PC setup from DESIGN.md §7.1: copy `coach/` out to its own path
    (the plan's decision: Windows first, e.g. `C:/Users/corpo/liftoff-coach`), run `git init` there, and say
    plainly that the copy is its own repo and never part of this one, and that later template changes are
    re-copied by hand. Include §7.1's verify-gate snippet verbatim — `"modules": {"verify": {"commands":
    {"<workspace path>": ""}}}` in `~/.nabu/config.json`, with its Windows location spelled out — and the
    reason for it (a global verify command such as `go build ./...` would otherwise run whenever the coach
    touched a file). Note the daemon host, the port (default 8737) and the token for Mission Control, plus
    the workspace path, because Mission Control has a "Coach workspace path" field that generation needs.
  - `coach/COACH.md` — every point §7.1.1 says it MUST contain: role; never ask questions (state any
    assumption in `notes`); the final message is exactly one JSON object matching the schema in the prompt,
    no prose before or after and no code fence; the programming principles, copied from §7.1.1 with a
    one-line note that they are the owner's to edit; reuse exercise names from the "exercises used before"
    list and introduce new names only for new movements; memory and the `liftoff-progress` note, neither
    replacing the history the prompt carries; no writes outside notes and memory — no file edits, no commands.
    Do not restate the three output schemas here (plan decision: the app owns and versions them).
  - Done when: both files exist at the repo root and cover §7.1 / §7.1.1 as above. Nothing under `app/` is
    touched, so no build run is needed for this commit.
    Commit: `docs: add the coach workspace template`

- [x] Add the three output schema files, extracted verbatim from DESIGN.md §7.5
  Create `app/src/main/resources/schemas/` (it does not exist yet) with `outline.json`, `lift-plan.json`
  and `run-plan.json`. Do not hand-copy or reformat: extract each ```json fence body with awk and write the
  output straight to the file, so the odd inline spacing the design uses (`"type":  { "enum": ... }`) is
  preserved. From the repo root:

  ```sh
  awk '/^#### 7\.5\.1 Outline/{f=1} f&&/^```json$/{c=1;next} c&&/^```$/{exit} c{print}' DESIGN.md > app/src/main/resources/schemas/outline.json
  awk '/^#### 7\.5\.2 Lift Flight Plan/{f=1} f&&/^```json$/{c=1;next} c&&/^```$/{exit} c{print}' DESIGN.md > app/src/main/resources/schemas/lift-plan.json
  awk '/^#### 7\.5\.3 Run Flight Plan/{f=1} f&&/^```json$/{c=1;next} c&&/^```$/{exit} c{print}' DESIGN.md > app/src/main/resources/schemas/run-plan.json
  ```

  Expected sizes: 22, 38 and 26 lines. `app/build.gradle.kts` is **not** touched — AGP 8.7.3 already puts
  `app/build/intermediates/java_res/debug/processDebugJavaRes/out` on `testDebugUnitTest`'s classpath, which
  the plan verified on this machine.
  - Done when each file matches its §7.5 fence byte for byte, checked the way `verify.sh` does it:
    `tr -d '\r' < DESIGN.md | awk -v h="#### 7.5.1 Outline" 'index($0,h)==1{w=1} w&&$0=="```json"{i=1;next} i&&$0=="```"{exit} i{print}' | diff - app/src/main/resources/schemas/outline.json`
    (same for `#### 7.5.2 Lift Flight Plan` → `lift-plan.json`, `#### 7.5.3 Run Flight Plan` → `run-plan.json`)
    — each diff prints nothing. `git ls-files --eol` on the three files shows `i/lf`.
    Commit: `coach: add the three output schema files from DESIGN.md §7.5`

- [x] Write the loading test `com.liftoff.app.coach.CoachSchemaFilesTest`
  New file `app/src/test/java/com/liftoff/app/coach/CoachSchemaFilesTest.kt`, plain JUnit 4 with no
  Robolectric (it touches only the classpath and a JSON parser, like `ExerciseNamesTest` in the same folder).
  Three test methods, one per file, named exactly as `verify.sh` looks for them:
  `outlineSchemaLoadsFromTestClasspath`, `liftPlanSchemaLoadsFromTestClasspath`, `runPlanSchemaLoadsFromTestClasspath`.
  Each reads its own resource with `javaClass.classLoader.getResourceAsStream("schemas/<name>.json")`, fails
  with a message naming that resource path (e.g. `schemas/outline.json missing from the test classpath`)
  rather than a null trace, parses the text with `kotlinx.serialization.json.Json.parseToJsonElement`
  (kotlinx-serialization-json 1.7.3 is already an `implementation` dependency — add no dependencies), and
  asserts the top level is a JSON object whose `"type"` is `"object"` and whose `"required"` list is non-empty.
  Add no other test and no logic in `src/main`.
  - Done when `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.coach.*"` finishes and the
    XML — not `BUILD SUCCESSFUL` — proves it: `app/build/test-results/testDebugUnitTest/TEST-com.liftoff.app.coach.CoachSchemaFilesTest.xml`
    exists with `failures="0" errors="0"` and the three testcases above; `TEST-com.liftoff.app.coach.ExerciseNamesTest.xml`
    is still there with `equivalentSpellingsNormalizeToBenchPress` and `coachPackageHasNoAndroidImports` passing.
    If the resource is not found, do not add a `sourceSets` line to `app/build.gradle.kts` — report it instead,
    because the plan deliberately relies on AGP's own classpath entry.
    Commit: `coach: add the test that loads the three schema files from the classpath`

- [ ] Record the schemas and the M0 status in ARCHITECTURE.md, then run the full gate
  Two rows only, in the existing tables.
  - Line 35, the `com.liftoff.app.coach` row: its description gains the schema files now sitting in
    `app/src/main/resources/schemas/` (outline, lift plan, run plan); the prompt builder and validator stay
    as still to come, and the status column keeps `ExerciseNames.kt` as what has landed.
  - Line 79, the M0 row: say the coach workspace template and the three schemas exist, and that the validator,
    the prompt builder and live measurement (`LiveCoachTest`) are still to come. It is no longer "Not started".
  The "Resource locations" table already names the schemas path, so it needs no edit.
  - Done when both rows read as above and the full gate passes: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`,
    then read `tests=`, `failures=`, `errors=` on the root of every `app/build/test-results/testDebugUnitTest/*.xml`
    — `failures="0" errors="0"` throughout, the whole existing suite (Room DAOs, settings, domain, Compose screen
    tests, `ExerciseNamesTest`) still green, and `CoachSchemaFilesTest` reporting three testcases.
    `git status --porcelain` shows no build output.
    Commit: `docs: record the coach schemas and the M0 status in ARCHITECTURE.md`
