# Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. A later brief's generation worker calls this builder. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the schema checker, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data`. They cover missions; sorties, with their notes and scrub reason; Flight Plans, with planned and actual sets and per-exercise user notes; active equipment; and exercises.

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'.
2. Instructions, worded as in §7.4.
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km).
4. Equipment: every active item as `key — name — notes`, then the line saying that exercises may use only these ids and that bodyweight needs none.
5. This week: the pattern, with each sortie's index, type and state; each sortie's outline focus, when an outline exists; and, for a Flight Plan, which sortie is being planned.
6. History: landed and scrubbed sorties within the history window, counted back from a given date, newest first, with one block per sortie in the §7.4 format:
   - the date, type, title, and whether it landed or was scrubbed
   - for each exercise, the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, with seconds as `3×45 s ✓`
   - the notes
   Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates.
8. Output schema: the schema text for this kind, verbatim from the resource file.

Also provide the repair prompt, 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', listing the validator's errors.

**Gathering inputs.** Add a loader, backed by Room and settings, outside the pure package (for example in `com.liftoff.app.data`). For a given Mission (plus the sortie, for a Flight Plan) and a 'today' date, it builds the builder's input types. It leaves out inactive equipment. Tests must be able to inject the window, the date and the zone.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Don't change the Room schema.

Must not break: the schema checker, the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only, and the §2 vocabulary.

Done when:
- Golden-file tests cover an outline prompt, a lift Flight Plan prompt with an outline, and a run Flight Plan prompt. Building the same prompt twice gives identical text.
- Tests cover:
  - the history formatting: a deviation, skipped sets, seconds, bodyweight, notes and a scrubbed sortie
  - the 28-day window edge: day 28 is in, day 29 is out
  - a custom window
  - newest-first order
  - the exercise names sorted and de-duplicated
  - inactive equipment being left out
- A Robolectric test shows the loader builds the expected inputs from an in-memory database.
- ARCHITECTURE.md's `coach` row lists the prompt builder.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
