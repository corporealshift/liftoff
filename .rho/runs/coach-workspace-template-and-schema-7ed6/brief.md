# Coach workspace template and schema files

Add the coach workspace template and the three output schema files. This brief is deliberately small and contains no Kotlin logic beyond one loading test. A later brief adds the schema checker that uses these files. Read DESIGN.md §7.1, §7.1.1 and §7.5 first. Don't read the rest of DESIGN.md unless you need it. A previous attempt at a bigger version of this work failed during planning, so keep the plan short. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit each part as soon as it's written.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md` explains the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; the copy is never part of this repo
  - turn off nabu's verify gate for that path in `~/.nabu/config.json`, using the exact snippet from §7.1
  - note the daemon host, the port (default 8737) and the token, for Mission Control
- `coach/COACH.md` holds the coach's standing instructions. It must include every point §7.1.1 says it MUST contain:
  - its role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schema files.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`. Each holds the JSON Schema from §7.5.1, §7.5.2 or §7.5.3, copied verbatim. These files become the single source of truth for later code. Make sure they are on the unit-test classpath.

**One test.** Add a plain JUnit test in `com.liftoff.app.coach`. It loads each of the three files from the classpath and parses it with kotlinx.serialization's JSON parser, which is already a dependency. It checks that each file parses to an object with a `type` of `object` and a non-empty `required` list. Add no other dependencies.

ARCHITECTURE.md: add the schema files to the `coach` row's description. Change M0 to say that the workspace template and the schemas exist and that the validator, prompt builder and live measurement are still to come.

Must not break: everything already built and its tests, including the exercise-name normalizer in `com.liftoff.app.coach` and the test that keeps that package free of Android imports. The build gate must still pass. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- The three schema files exist under `app/src/main/resources/schemas/`, match §7.5 verbatim, and the loading test passes.
- ARCHITECTURE.md is updated as described.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
