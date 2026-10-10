# Launchpad and Mission screens

Replace the Launchpad and Mission placeholder tabs with the real screens. Read DESIGN.md §2, §4, §5 and §9, `design/README.md` and `design/screens/launchpad.html` first. The HTML is a static mockup where 1 px = 1 dp. Build on:
- the Space Age theme composables in `com.liftoff.app.ui.theme` (`TitleBlock`, `PatternTrack`, `InkRuledListRow`, `PrimaryButton`, `UnderlinedTextButton`, the offset-shadow helpers, `LiftoffIcons`)
- the navigation shell in MainActivity
- the Mission/sortie domain logic in `com.liftoff.app.domain` and its Room-backed operations (ensure the current week, change the draft pattern, confirm, scrub, launch), reached through `AppContainer`
Screens update live from Room. Run 'ensure the current week' when the app opens and whenever it comes back to the foreground, so rollover happens at the week boundary.

**Launchpad.** The PLANNED state must match `design/screens/launchpad.html` and the 'Launchpad, PLANNED' section of `design/README.md`:
- an eyebrow like 'WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT' and the plan title in 68 sp display type
- the pattern track, with landed, current and upcoming chips
- a 'FLIGHT PLAN' head with the estimated minutes and set count, over a ruled list with one row per exercise: red index, name, and load such as '3×8 · 135'. For a run, show the run summary (title, focus, any target distance or segments) instead.
- the coach note, with 'Coach:' in teal, when the plan has notes
- the 72 dp red Launch button with the rocket glyph and offset shadow
- an underlined Scrub text button
Regenerate needs coach generation, which comes later; leave it out until then.

The other states use the same parts:
- **No confirmed Mission this week:** the draft, with editable R/L pattern chips (tap to toggle, add, remove; 1–7 sorties) that start from the default pattern, and a Confirm button
- **PENDING:** a status line saying no Flight Plan is ready yet, with Scrub where Launch would be
- **IN_FLIGHT:** a Resume button
- **Mission closed:** a short completion state

Scrub asks for confirmation first. Launch sets the sortie IN_FLIGHT and opens an In-Flight route; Resume opens the same route. That route can be a simple placeholder that shows the sortie's title and offers back, because a later brief builds the real In-Flight screen.

**Mission tab** (§9). It shows:
- this week's pattern track
- the outline notes, if any
- each sortie with its index, type (run/lift), focus and state
Tapping a sortie shows its Flight Plan, or a short record for a landed or scrubbed sortie (including the scrub reason). It isn't mocked up; use the same design parts.

Put the derivation of each screen's state from the database in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. UI strings keep the §2 vocabulary (Launch, Flight Plan, Sortie, Scrub, Mission).

Must not break: the shell, Mission Control, the domain rules and their tests, and the build gate. Follow CLAUDE.md conventions.

Done when:
- On a fresh install, the app opens to a draft for the current week with the default pattern.
- Confirming shows sortie 1 (a run under the default RLRLR pattern) as PLANNED with Launch available.
- Scrubbing advances to the next sortie.
- Tests cover how each Launchpad state (draft, PLANNED lift with seeded exercises, PLANNED run, PENDING, IN_FLIGHT, closed) and the Mission tab are derived from the database.
- ARCHITECTURE.md's milestone table marks M2 as done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
