# Coach JSON Schema checker

Build a small JSON Schema checker for the coach's output schemas. Read DESIGN.md §7.5 and §7.6 first; you don't need the rest of DESIGN.md. Nothing in the UI changes. A later brief builds the semantic validator on top of this checker. The last attempt at this brief failed because the tool crashed, not because of the work, so keep the plan short and to the point. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- the schema files `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, and their loading test `CoachSchemaFilesTest`
- the `com.liftoff.app.coach` package, which has the exercise-name normalizer and a test that keeps the package free of Android imports
- kotlinx.serialization, which is already a dependency

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2).
- An in-house checker for exactly the keywords the three schema files use: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems, plus nested properties and items. Open the files to confirm the list. If they use any other keyword, support it too and record that in the plan's decisions. Don't add a JSON Schema library.
- It loads the schemas from the resource files, so the files stay the single source of truth.
- It takes a parsed JSON value and a schema, and returns a list of errors. An empty list means the value is valid. Each error names its JSON path and its exact problem, because a later brief sends these errors back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: 'jog' is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property 'day'`.
- Errors come out in a stable order, so the same input always gives the same list.

Must not break: the schema files and their loading test, the exercise-name normalizer, everything else already built, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only, snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas. Valid documents pass. At least one violation of each supported keyword gives its path-specific message.
- ARCHITECTURE.md's `coach` row lists the schema checker.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
