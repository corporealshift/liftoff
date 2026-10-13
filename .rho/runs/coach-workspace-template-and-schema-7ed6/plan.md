# Plan — coach workspace template and schema files

Small brief: two docs at the repo root, three JSON files, one loading test, two rows of
ARCHITECTURE.md. No Kotlin logic beyond the test. Three commits, each independently mergeable.

## Approach, and why it fits this codebase

**`coach/` is documentation, not code.** DESIGN.md §7.1 says the coach workspace is its own git
repo on the PC and that this repo only carries the template. So `coach/README.md` and
`coach/COACH.md` are plain Markdown written straight from §7.1 and §7.1.1 — nothing to wire up,
nothing to import. They sit at the repo root, which is where §12's target layout already says they
go (`coach/README.md`, `coach/COACH.md`); neither exists in this worktree yet.

**The schemas go in `app/src/main/resources/schemas/`, which is already the documented location**
(ARCHITECTURE.md "Resource locations", line 45) and is also named in §12. That directory does not
exist yet, so it is created.

**No build change is needed to make them loadable from a unit test — verified, not assumed.**
AGP already puts `app/build/intermediates/java_res/debug/processDebugJavaRes/out` on
`testDebugUnitTest`'s classpath, and `:app:processDebugJavaRes` / `:app:processDebugUnitTestJavaRes`
both appear in `bash gradlew.sh :app:testDebugUnitTest --dry-run`. I confirmed the classpath entry
by registering a throwaway task that prints `testDebugUnitTest.classpath.files` (init script kept
outside the repo, `~/tmp-inspect-7ed6/init.gradle`); it listed that directory, and
`kotlinx-serialization-json-jvm-1.7.3.jar`. So `app/build.gradle.kts` is **not** touched, and the
test loads with `javaClass.classLoader.getResourceAsStream("schemas/outline.json")`. That keeps the
files the single source of truth for the later schema checker, which is the point of the brief.

**The test is plain JUnit 4, no Robolectric.** It touches only the classpath and a JSON parser, so
it runs on the JVM like `ExerciseNamesTest`. It lives in `com.liftoff.app.coach` (test source tree).
The existing Android-import guard, `ExerciseNamesTest.coachPackageHasNoAndroidImports`
(`app/src/test/java/com/liftoff/app/coach/ExerciseNamesTest.kt:59`), scans only
`src/main/java/com/liftoff/app/coach/*.kt`, so a test file in the same package cannot break it.

## Files involved

| File | What happens |
|---|---|
| `coach/README.md` (new) | the one-time PC setup from §7.1: copy out, `git init`, the verify-gate snippet, daemon host/port/token for Mission Control |
| `coach/COACH.md` (new) | the coach's standing instructions, every MUST point in §7.1.1 |
| `app/src/main/resources/schemas/outline.json` (new) | §7.5.1, verbatim |
| `app/src/main/resources/schemas/lift-plan.json` (new) | §7.5.2, verbatim |
| `app/src/main/resources/schemas/run-plan.json` (new) | §7.5.3, verbatim |
| `app/src/test/java/com/liftoff/app/coach/CoachSchemaFilesTest.kt` (new) | loads each file, parses with `kotlinx.serialization.Json`, asserts `type == "object"` and a non-empty `required` |
| `ARCHITECTURE.md` (edit) | the `com.liftoff.app.coach` row (line 35) gains the schema files; the M0 row (line 79) says the template and schemas exist and the validator, prompt builder and live measurement are still to come |

Nothing else. `app/build.gradle.kts`, `app/src/main/java/com/liftoff/app/coach/ExerciseNames.kt`,
the settings store and the UI are untouched. The "Resource locations" table already lists the
schemas path, so it needs no edit.

## Order

1. **`docs: add the coach workspace template`** — `coach/README.md`, `coach/COACH.md`. Docs only,
   no build impact.
2. **`coach: add the three output schema files and their loading test`** — the three JSON files,
   then the test, then `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.coach.*"`
   and read the XML for that class before committing.
3. **`docs: record the coach schemas and the M0 status in ARCHITECTURE.md`** — the two rows.
4. Run the full gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

Stage each file by name; never `git add -A`. LF endings throughout (`.gitattributes` already
normalises `* text=auto eol=lf`).

## How the result is tested

- **Gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. `BUILD SUCCESSFUL` proves
  nothing on its own — read `tests=`, `failures=`, `errors=` on the root of each
  `app/build/test-results/testDebugUnitTest/*.xml`, and confirm
  `TEST-com.liftoff.app.coach.CoachSchemaFilesTest.xml` exists with three passing testcases. The
  existing suites (Room DAOs, domain, Compose screen tests, `ExerciseNamesTest`) must stay green.
- **Verbatim check (one-off, not a permanent test):** extract each §7.5 code fence from DESIGN.md
  and `diff` it against the file, e.g.
  `awk '/^#### 7\.5\.1 Outline/{f=1} f&&/^```json$/{c=1;next} c&&/^```$/{exit} c{print}' DESIGN.md | diff - app/src/main/resources/schemas/outline.json`
  (same for `7.5.2` → `lift-plan.json`, `7.5.3` → `run-plan.json`). Hand-copying JSON is where a
  typo hides; a diff is the cheap proof the brief's "copied verbatim" holds.
- **Loading test shape:** three test methods, one per file, each naming its resource path in the
  failure message, so a missing file reads as `schemas/outline.json missing from the test classpath`
  rather than a null-pointer trace.

## Risks and uncertainties

- **Classpath loading is AGP behaviour, not something this repo controls.** It is verified on this
  machine with this AGP (8.7.3) today. If a future AGP stops putting `java_res/debug` on the test
  classpath, the test fails loudly with a named resource — it cannot pass silently. The fix then is
  a `sourceSets` line in `app/build.gradle.kts`, deliberately not added now.
- **These JSON files also land in the APK** (`processDebugJavaRes` packages them). Harmless — a few
  KB, no AGP resource processing, no `res/` involvement — but worth knowing they are in the app
  package as well as the test classpath.
- **`coach/README.md` documents nabu's config file, which is not in this repo.** The brief demands
  §7.1's exact snippet, so it is copied rather than re-derived; if nabu's real key differs from
  §7.1, the README inherits that error. Not checkable from this worktree.
- **`const` appears in `lift-plan.json` and `run-plan.json`** (§7.5.2/§7.5.3). This brief's test
  does not care; the next brief's checker must support it. Flagged so it is not a surprise later.
- **The round-9 attempt (`coach-workspace-template-schemas-and-zfvq`) failed at planning** and its
  branch holds only its brief — nothing to reuse, nothing to reconcile.

## Decisions

- **Does the README tell the owner to write down the coach workspace path as well as host, port and
  token?** Chosen: yes — the README lists host, port (default 8737), token **and** the workspace
  path, because Mission Control already has a "Coach workspace path" field
  (`MissionControlScreen.kt:151`, backed by `Settings.coachWorkspacePath`, `Settings.kt:10`) and
  §7.3 sends that path to `nabu.session.create`. Alternative: name only the three the brief lists.
  The owner setting up the app would otherwise fill in three of four fields and hit a generation
  failure for the missing one.
- **Which OS do the setup steps assume?** Chosen: Windows first — the workspace path example is
  `C:/Users/corpo/liftoff-coach` as in §7.1, and the config file is named as `~/.nabu/config.json`
  with its Windows location spelled out. Alternative: POSIX-style paths. The owner's daemon runs on
  this Windows PC, so a POSIX-only README would need translating before it could be followed.
- **How do later edits to `coach/` reach the copied workspace?** Chosen: the README says the copy is
  one-time and independent, and that later template changes must be re-copied by hand. Alternative:
  document a symlink or a sync command. §7.1 requires the copy to be its own repo and never part of
  this one, and a symlink would let the coach reach the app's source tree; inventing a sync tool is
  outside this brief.
- **Does COACH.md mark the programming principles as the owner's to edit?** Chosen: yes, a one-line
  note under that heading. §7.1.1 says "These are the owner's to edit", and the owner is the reader
  who would otherwise treat COACH.md as fixed app text. Alternative: a plain list with no note.
- **Does COACH.md restate the output schemas?** Chosen: no — it states the contract (exactly one
  JSON object, no prose, no code fence) and nothing more, because §7.1.1 puts the schemas in the
  prompt and says the app owns and versions them. Alternative: embed the three schemas in COACH.md.
  Embedding them would create a second copy that drifts from `app/src/main/resources/schemas/`,
  which this brief makes the single source of truth.
- **One test that loops the three files, or three tests?** Chosen: three named tests, one per file.
  Test names are what the runner's `verify.sh` and the JUnit XML report, so a failure should point
  at the file that broke. Alternative: a single test over a list, which reports one failure for any
  of the three.
