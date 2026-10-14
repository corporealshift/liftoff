# Coach reply validator and fixtures

Build the phone-side validation of coach replies (DESIGN.md §7.6). Read DESIGN.md §7.5, §7.6 and §13 ('Validator') first. No UI changes. Later briefs add the prompt builder and the generation worker, which call this validator. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the in-house JSON Schema checker in `com.liftoff.app.coach`, which returns errors that name their path
- the `coach/` workspace template

**Validator (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports.
- A pure function. It takes the raw reply text and a generation context. It returns either a parsed result in plain coach types (an outline, a lift plan or a run plan) or a list of human-readable errors.
- The context holds the generation kind, the Mission pattern, the type of the sortie being planned, the active equipment keys and the configured sortie length.

It follows §7.6 exactly:
- trim the text, and unwrap a single surrounding Markdown code fence
- parse exactly one JSON object; prose before or after it is an error
- check the schema with the existing checker
- run the semantic checks:
  - the outline has one entry per pattern position, with indexes 0..n-1 and each type matching the pattern
  - each lift set has exactly one of reps or seconds
  - every equipment id is in the active equipment list
  - estimated_minutes is at most 1.25 × the sortie length
  - exercise names are unique within the plan
  - the plan type matches the sortie

Every error names its path and its problem, for example `exercises[2].equipment: barbell is not in the equipment list`.

**Fixtures.** Add coach replies in the style of real recorded replies under `app/src/test/resources/fixtures/`:
- valid: an outline, a lift plan, a run plan, and a valid plan wrapped in one code fence
- invalid:
  - bad JSON
  - prose around the JSON
  - a schema violation
  - unknown equipment
  - an inactive equipment key
  - an outline that doesn't match the pattern
  - both reps and seconds on one set
  - an over-length sortie
  - duplicate exercise names
  - a plan type that doesn't match the sortie

Must not break: the schema checker and its tests, everything else already built, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid case gives its specific message, with the path in it.
- ARCHITECTURE.md's `coach` row lists the validator and fixtures. M0 says the template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
