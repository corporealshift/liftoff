# Goal

build the app based on the designs in the design directory

## Done when

- The `design/` directory (README.md, the two screen mockups and the icon SVG) is in the repository. The app uses the Space Age look it describes: a custom MaterialTheme with the cream/paper/sand/ink/red/mustard/teal tokens at the listed hex values, Big Shoulders Display and Work Sans bundled in res/font, 2 dp ink borders, solid offset shadows with no blur, the red/mustard/teal stripes, light theme only, and the dumbbell-satellite adaptive launcher icon.
- The app opens to a navigation shell with an ink bottom bar (Launchpad · Mission · Landed, active item in mustard) and a top bar holding the LIFTOFF wordmark and a sliders button. That button opens Mission Control, which edits and persists every setting in DESIGN.md §8, manages equipment (add, edit, deactivate), tests the nabu connection and exports/imports the database as JSON.
- Opening the app in a week with no Mission shows a draft with editable R/L pattern chips that start from the default pattern, and a Confirm button. Sorties then run in rolling pattern order. Scrub works. At the week boundary, open sorties are scrubbed with 'week ended' and nothing carries over. Pure-Kotlin domain tests cover the §13 mission and sortie cases.
- The Launchpad's PLANNED state matches design/screens/launchpad.html: an eyebrow, the display title, the pattern track, the Flight Plan list with the coach note, a big red Launch button with an offset shadow, and Regenerate/Scrub text buttons. The PENDING, IN_FLIGHT (Resume) and draft states are built from the same parts.
- The In-Flight screen matches design/screens/in-flight.html and DESIGN.md §6. It has a teal header with a set counter and segmented progress, and exercise cards in done, active and upcoming states. One tap checks a set off, editing a set marks it done and shows the deviation in red, and sets and exercises can be skipped. Sets can be added, notes kept, and Land asks for confirmation when sets are unchecked. Every action is written to Room straight away, so the screen comes back unchanged after the process is killed, and no network is needed.
- Coach generation works as DESIGN.md §7 describes. The `coach/` workspace template exists, along with the pure-Kotlin prompt builder, the schemas and the validator, all tested with golden files and fixtures. A GenerationWorker talks to nabu through the copied DaemonClient and is tested against a fake daemon for the §13 cases. It produces outlines and lift Flight Plans, with up to 2 in-session repairs, regenerate/supersede, and 'Mission Control offline' status on the Launchpad.
- The Landed screen shows history newest first, grouped by week, with a detail view per sortie. Re-fly copies the most recent landed plan of the same type, preferring the same focus, and works offline. The 'Generate run plans' toggle produces generated run Flight Plans when on and simple 'Run' plans when off.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes, and ARCHITECTURE.md's package table and milestone statuses reflect what has been built.

## Round 1

### 1. Bring in the Space Age design and build the theme

The owner chose a visual design for Liftoff, direction B 'Space Age'. It is on the pushed branch `origin/design/space-age-icon` and is not on this branch yet. That branch adds `design/` (README.md with color, type, shape and component specs; screens/launchpad.html and screens/in-flight.html, static HTML mockups where 1 px = 1 dp; icon/liftoff-icon.svg), a DESIGN.md amendment pointing §9 and §12 at `design/`, and the dumbbell-satellite adaptive launcher icon resources wired into AndroidManifest.xml. Merge that branch into this one with its content unchanged. If `design/README.md` already exists here, that step is already done.

Then build the look described in `design/README.md` as reusable Compose building blocks under `com.liftoff.app.ui.theme`, which later screens will use:
- A custom MaterialTheme. It must have a color scheme built from the README's tokens (cream, paper, sand, ink, red, red_pressed, mustard, teal, teal_light, muted, rule, white) with the token values also available by name, typography covering the README's roles, and shapes (4 dp corners on buttons and checkboxes, square cards). Light theme only.
- Fonts: Big Shoulders Display (700, 800, 900) and Work Sans (400, 500, 600), bundled in `res/font/` from Google Fonts. Both are under the Open Font License; include the license text in the repo.
- Small composables for what Material 3 doesn't provide:
  - the 3-band (red/mustard/teal, 6 dp) and 2-band (mustard/red, 5 dp) stripes
  - a solid offset shadow (4 dp right and down, no blur, color configurable)
  - the primary red button (2 dp ink border, ink offset shadow, red_pressed when pressed, display-font label, optional leading icon) and the ink variant with a red shadow (used later for Land)
  - the underlined text button
  - an eyebrow and display-title pair
  - the pattern track: one chip per R/L in landed, current or upcoming style, joined by a 2 dp ink line
  - an ink-ruled list row
  - the 24 dp stroke icons for rocket, ringed planet, flag and sliders, as vector drawables from the mockup paths or the closest Material Symbols
  Do not use stock M3 visuals (tonal surfaces, pill buttons, ripples on cream) where the design shows something else.
- MainActivity should render inside the new theme and show a simple themed placeholder: the stripes and the LIFTOFF wordmark on cream. The navigation shell comes in a later brief.

Must not break: the build gate, the existing build configuration and manifest attributes, and the CLAUDE.md conventions (LF endings, commit message style, staging named files only). Pure packages must not gain Android imports.

Done when:
- `design/` and the launcher icon are on this branch.
- The app builds, installs with the dumbbell-satellite icon and shows the themed placeholder.
- A unit test pins the color token values to the README's hex values.
- The gate `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Data layer, settings and AppContainer

Build Liftoff's storage as described in DESIGN.md §8 and §7.7. No screens change in this brief; later briefs build the UI on top of it. Read DESIGN.md and ARCHITECTURE.md first.

- **Room database (`com.liftoff.app.data`).**
  - Entities for Mission, Sortie, FlightPlan, PlannedExercise, PlannedSet, RunSegment, Exercise, Generation and Equipment, with the fields and enum states listed in §8. Use the §2 vocabulary in code; never call a sortie a 'session'.
  - Uniqueness: Mission.weekStart, FlightPlan.sortieId, Exercise.normalizedName and Equipment.key are unique.
  - DAOs for what the app will need next:
    - observing a given week's Mission with its sorties
    - reading a sortie's Flight Plan with its exercises (with display names), sets and run segments
    - writing a whole Flight Plan in one transaction
    - updating individual set and exercise fields
    - listing landed and scrubbed sorties newest first
    - CRUD for Generation rows
    - equipment listing, active only or all
- **Exercise identity (§7.7).** A pure function normalizes names: lower-case, punctuation removed except '-', whitespace collapsed. It lives in a package with no Android imports. Looking up an exercise by name returns the existing row when the normalized name matches and creates one otherwise.
- **Equipment.** The key must match `[a-z0-9_]+` and be unique. Removing an item marks it inactive and never deletes it.
- **Settings (`com.liftoff.app.settings`), backed by DataStore Preferences,** with an observable flow and update functions for:
  - daemon host, port (default 8737) and token
  - coach workspace path
  - default pattern (default RLRLR; must be 1–7 characters of R/L)
  - session length in minutes (default 60)
  - units: lb or kg, mi or km (default lb and mi)
  - history window in days (default 28)
  - the run-generation toggle (default off)
  - objectives and constraints (free text, default empty)
- **AppContainer.** A single hand-built container in the root package creates the database and settings once. An Application subclass registered in the manifest owns it. No dependency-injection framework (ARCHITECTURE.md invariant 5).

Must not break: the Space Age theme and placeholder screen from the previous brief, the launcher icon, and the build gate.

Done when:
- Robolectric/in-memory Room tests cover the DAOs, the uniqueness rules and the 'deactivate, don't delete' equipment rule.
- Unit tests show spelling variants of an exercise name map to one Exercise, and cover settings defaults, round-trips and pattern validation.
- ARCHITECTURE.md's package table is updated for the packages that now exist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Navigation shell and Mission Control

Give Liftoff its real navigation and settings screen, in the Space Age look from `design/README.md`. Build on the theme and shared composables in `com.liftoff.app.ui.theme` (stripes, offset shadow, buttons, eyebrow/title, ruled rows, stroke icons) and on the AppContainer, settings store and equipment DAO already in the repo.

**Shell.** Replace MainActivity's placeholder with the app shell described in DESIGN.md §9 and design/README.md:
- the three stripes
- a top bar with the LIFTOFF wordmark and a 44 dp outlined sliders button that opens Mission Control
- an ink bottom bar with Launchpad, Mission and Landed, each an icon over an uppercase label; the active item is mustard and the others are the 'rule' color
Launchpad (the start destination), Mission and Landed are placeholder screens for now: an eyebrow and display title in the design style with a short line of text. Later briefs fill them in. System back works as expected.

**Mission Control.** It isn't mocked up; build it from the same parts (eyebrow and display title, ink-ruled lists, paper cards with ink borders). It edits every setting in DESIGN.md §8:
- daemon host, port and token (masked, with a way to reveal it)
- coach workspace path
- default pattern, edited as R/L chips (1–7 sorties)
- session length in minutes, units (lb/kg and mi/km) and history window in days
- the 'Generate run plans' toggle
- objectives and constraints as free text
Changes persist and survive an app restart. Invalid input (a bad port, a pattern outside 1–7, a non-positive length) is rejected with a clear message rather than saved.

**Equipment.** Mission Control also manages the equipment list. It shows each item as key — name — notes and supports add, edit and deactivate. Deactivated items are hidden from the main list but can be viewed and reactivated. A key must be unique and match `[a-z0-9_]+`, and a duplicate or invalid key gets an inline error.

'Test connection' and export/import belong to later briefs; do not add non-working placeholders for them.

Must not break: the data layer and its tests, the launcher icon, the build gate, and the §2 vocabulary in UI strings.

Done when:
- The app opens to the shell, and all three tabs and Mission Control are reachable.
- Settings and equipment edits persist.
- Tests cover the Mission Control state/validation logic, including equipment key rules.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Mission and sortie domain logic

Implement the rules for weeks (Missions) and their sorties from DESIGN.md §4, §5.1 and §5.3. Build on the Room data layer and settings already in the repo. No new screens in this brief; the next brief builds the Launchpad on it.

**Pure Kotlin in `com.liftoff.app.domain`, with no Android imports:**
- the week start: Monday, in the phone's local time zone
- creating a DRAFT Mission for the current week with the default pattern
- overriding the pattern, only while the Mission is a draft (1–7 of R/L); the pattern is frozen once confirmed
- the Mission lifecycle DRAFT → ACTIVE → CLOSED
- the sortie state machine (PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED): every legal transition in §5.1 succeeds and every illegal one is rejected
- at most one sortie IN_FLIGHT across all Missions
- next-sortie selection: the lowest index that is neither landed nor scrubbed
- a Mission closes when every sortie has landed or been scrubbed
- rollover: once a Mission's week has ended, its open sorties become SCRUBBED with reason 'week ended' and the Mission closes. Nothing carries over, and no Mission is created ahead of its week.

**Confirming.** Coach generation does not exist yet, so confirming a draft takes the 'Continue without outline' path from §4.2: the Mission becomes ACTIVE, lift sorties get focus 'full body' and runs get focus 'easy'.

**Planning the current sortie.** Whenever a sortie becomes current (after confirm, or after the previous sortie lands or is scrubbed), the app prepares its plan the way §5.3 and §7.2 describe:
- With run generation off, a run sortie gets a simple Flight Plan titled 'Run' with source SIMPLE_RUN and its focus, and the sortie becomes PLANNED.
- Lift sorties, and runs with generation on, stay PENDING for now. Keep this decision in one clear place so the later coach-generation work can queue a generation there. Do not create Generation rows yet.

**A Room-backed layer the UI can call**, with each operation in a transaction:
- on app open, roll over past Missions and make sure the current week has a Mission, creating a draft if needed
- change a draft's pattern
- confirm
- scrub the current sortie (a scrubbed IN_FLIGHT sortie keeps the sets already checked)
- launch a PLANNED sortie
Expose it through AppContainer.

Must not break: the shell, Mission Control, the data layer and the build gate.

Done when:
- Unit tests cover the §13 'Missions' and 'Sorties' cases: draft created with the default pattern; override then freeze on confirm; rollover scrubs open sorties with 'week ended'; no carry-over; the Monday boundary across time zones; every legal and illegal transition; next-sortie selection skipping landed and scrubbed sorties; at most one in flight.
- Tests cover simple run plan creation and the Room-backed operations.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Launchpad and Mission screens

Replace the Launchpad and Mission placeholder tabs with the real screens. Build on:
- the Space Age theme and shared composables in `com.liftoff.app.ui.theme`
- the navigation shell
- the Mission/sortie domain logic and its Room-backed operations (ensure the current week, change the draft pattern, confirm, scrub, launch), reached through AppContainer
Screens update live from Room.

**Launchpad** (DESIGN.md §9). The PLANNED state must match `design/screens/launchpad.html` and the 'Launchpad, PLANNED' section of `design/README.md`:
- an eyebrow like 'WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT' and the plan title in 68 sp display type
- the pattern track, with landed, current and upcoming chips
- a 'FLIGHT PLAN' head with the estimated minutes and set count, over a ruled list with one row per exercise: red index, name, load such as '3×8 · 135'. For a run, show the run summary instead.
- the coach note, with 'Coach:' in teal
- the 72 dp red Launch button with the rocket glyph and offset shadow
- an underlined Scrub text button
Regenerate needs coach generation, which comes later; leave it out until then.

The other states are built from the same parts:
- **No confirmed Mission this week:** the draft, with editable R/L pattern chips (add, remove, toggle; 1–7 sorties) and a Confirm button.
- **PENDING:** a status line saying no Flight Plan is ready yet, with Scrub where Launch would be.
- **IN_FLIGHT:** a Resume button.
- **Mission closed:** a short completion state.

Scrub asks for confirmation first. Launch sets the sortie IN_FLIGHT and opens an In-Flight route; Resume opens the same route. That route can be a simple placeholder showing the sortie's title, because the next brief builds the real In-Flight screen.

**Mission tab** (§9). It shows:
- this week's pattern track
- the outline notes, if any
- each sortie with its index, type (run/lift), focus and state
Tapping a sortie shows its Flight Plan, or a short record for a landed or scrubbed sortie (including the scrub reason). It isn't mocked up; use the same design parts.

UI strings keep the §2 vocabulary (Launch, Flight Plan, Sortie, Scrub, Mission).

Must not break: Mission Control, the domain rules and their tests, and the build gate.

Done when:
- On a fresh install, the app opens to a draft for the current week with the default pattern.
- Confirming shows sortie 1 (a run under the default RLRLR pattern) as PLANNED with Launch available.
- Scrubbing advances to the next sortie.
- Tests cover how each Launchpad state is derived from the database.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. In-Flight checklist, Launch and Land

Build the In-Flight screen, where the owner logs a workout at the gym. It is described in DESIGN.md §6, `design/screens/in-flight.html` and the 'In-Flight' section of `design/README.md`. It replaces the placeholder In-Flight route that Launch and Resume on the Launchpad already open. Build on the Space Age theme composables (the ink button variant with a red shadow, the stripes, the offset shadow), the Room data layer, and the domain's Room-backed operations for launch, scrub and preparing the next sortie.

**Header and progress.**
- A teal header: the mustard eyebrow 'IN FLIGHT · SORTIE n', the title in cream, and a set counter like '05/14' over 'SETS'.
- A progress bar with one segment per set: mustard when done, outlined teal_light when open.
- The mustard and red bands under the header.

**Exercise cards.**
- **Done:** collapsed to a sand row with the name struck through and 'n/n LANDED' in teal.
- **Active:** a paper card with an offset shadow and an ink header bar.
- **Upcoming:** the same card without the shadow.
- Each active or upcoming card has a footer with '+ Add set · Note · Skip'.

**Set rows (58 dp).**
- Each row shows 'SET n', the weight and the reps or seconds, and a 48 dp check box.
- One tap on the box marks the set done with actual values equal to the planned ones.
- Tapping the numbers, or a long press, opens a stepper for reps or seconds and weight. Saving it marks the set done.
- A set done differently from the plan shows the actual value in red with a small 'of <planned>'.
- The current set's label is red.
- A set or a whole exercise can be skipped. Extra sets can be added and are marked as added.

**Notes.** An optional note per exercise and one per sortie.

**Land.** A 64 dp ink Land button with a flag glyph and a red offset shadow.
- If any sets are unchecked, ask for confirmation. Unchecked sets are recorded as not done.
- Landing sets the sortie LANDED with its landed time and returns to the Launchpad.
- The next sortie becomes current and its plan is prepared through the existing domain step. The Mission closes if this was the last sortie.
- Scrub stays available from In-Flight and keeps the sets already checked.

**Run sorties.** A run's In-Flight shows its plan (title, focus, any segments). Land offers optional distance and duration fields in the configured units.

**Persistence.** Every action is written to Room as it happens. If the process is killed, reopening the app (or tapping Resume) brings the screen back exactly as it was left. Nothing in this flow uses the network.

Lift Flight Plans can't be generated yet, so tests should seed plans directly into Room.

Must not break: the Launchpad and Mission screens, the domain rules, Mission Control, and the build gate.

Done when:
- Launch → log → Land works for a seeded lift plan and for a simple run.
- Tests cover:
  - checking a set, editing with deviation, skipping, adding a set and notes
  - landing with and without unchecked sets
  - the next sortie being advanced and the Mission closing after the last sortie
  - the In-Flight state being rebuilt exactly from Room
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Only the Space Age design and theme (round 1, brief 1) are built. The data-layer run stalled: its database file used the six data-access classes from com.liftoff.app.data.dao without importing them. The database code generator reported only 'LiftoffDatabase references a type that is not present', which hides that error, and the run retried the same build until the runner stopped it. Nothing that depends on the data layer exists yet: the navigation shell, Mission Control, the Mission/sortie domain logic, the Launchpad and Mission screens, In-Flight, coach generation, Landed/Re-fly, the run-plan toggle behaviour, export/import and test connection. ARCHITECTURE.md still marks every milestone not started. The theme also leaves several Material type roles on the system font. This round splits the data layer into two smaller runs (settings and container first, then the Room database, with a warning about the hidden error) and then carries on with the shell, the domain logic and the screens.

## Round 2

### 1. Settings store, exercise names and AppContainer

Add Liftoff's settings storage, the exercise-name normalizer and the app's single dependency container. Read DESIGN.md (§2, §7.7, §8, §11) and ARCHITECTURE.md first. No screens change, and this brief does not touch Room; the database comes in the next brief.

**Settings (`com.liftoff.app.settings`), backed by DataStore Preferences** (the dependency is already in app/build.gradle.kts). Provide an observable flow of all settings plus one update function per setting:
- daemon host (default empty), port (default 8737) and token (default empty)
- coach workspace path (default empty)
- default pattern (default RLRLR; must be 1–7 characters, each exactly 'R' or 'L')
- sortie length in minutes (default 60). DESIGN.md calls it 'session length', but code uses the §2 word 'sortie'.
- units: weight lb or kg (default lb), distance mi or km (default mi)
- history window in days (default 28)
- the 'Generate run plans' toggle (default off)
- objectives and constraints (free text, default empty)
Invalid values are rejected with an exception before anything is written, so the stored value is unchanged. This applies to a pattern that isn't 1–7 R/L characters, a port outside 1–65535, and a sortie length or history window that isn't positive. If a stored unit can't be read, use the default.

**Exercise names (§7.7).** Write a pure function that normalizes an exercise name. It lower-cases the name, removes every character that isn't a letter, a digit, whitespace or '-', collapses runs of whitespace to one space, and trims. Spaces are never turned into hyphens. Put it in `com.liftoff.app.coach`, which must have no Android imports (ARCHITECTURE.md invariant 2). The database brief that follows will use it to find or create exercises.

**AppContainer.** Add a single hand-built `AppContainer` class in the root package `com.liftoff.app`. It creates the settings store once, lazily, from the application context. Add an `Application` subclass that owns the one container and register it on the existing `<application>` element in AndroidManifest.xml. Do not change any other manifest attributes. Do not use a dependency-injection framework (invariant 5). Later briefs add the database and other services to this container.

Builds on the Space Age theme in `com.liftoff.app.ui.theme` and the themed placeholder that MainActivity shows. Must not break: those, the launcher icon, the existing tests, and the build gate. Follow CLAUDE.md: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- JVM unit tests cover every settings default, a set-then-read round trip of every field, and the rejected values above, with the previous value still stored after a rejection. Tests use their own DataStore on a temporary file.
- Unit tests show that 'Bench Press', 'bench  press', 'BENCH PRESS' and ' Bench Press. ' all normalize to 'bench press', that 'Bench-Press' keeps its hyphen, and that symbols such as '&', '(' and an apostrophe are removed.
- A test shows that the app's Application owns one container and that the container returns the same settings store on repeated access.
- ARCHITECTURE.md's package table marks `settings` and the root package's `AppContainer` as existing, and `coach` as started with the name normalizer.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Room database and DAOs

Build Liftoff's Room database as described in DESIGN.md §8 and §7.7. No screens change; later briefs build on it. Read DESIGN.md and ARCHITECTURE.md first. Room 2.6.1 with KSP, room-testing and Robolectric are already declared in app/build.gradle.kts, so no dependency changes should be needed.

**Warning from a previous attempt.** An earlier run failed on this work. Room's KSP processor runs before the Kotlin compiler, so an ordinary Kotlin error in a file Room reads shows up only as `[MissingType]: Element '...LiftoffDatabase' references a type that is not present`, with no detail. The run retried the same build until it was stopped. The cause was simple: the database class used DAO types from another package without importing them. If you see that error, look for unresolved references, such as missing imports, wrong package names or misspelled types, in the database, entity, DAO and converter files. Add a few entities at a time and check that the build still compiles after each step.

**Database (`com.liftoff.app.data`).**
- Entities for Mission, Sortie, FlightPlan, PlannedExercise, PlannedSet, RunSegment, Exercise, Generation and Equipment, with the fields and enum states listed in §8. Use the §2 vocabulary in code, and never call a sortie a 'session'. Remember that `index`, `order` and `key` are SQL keywords.
- Uniqueness: Mission.weekStart, FlightPlan.sortieId, Exercise.normalizedName and Equipment.key are unique.
- The database is version 1 and must never be set up to drop data on migration, because the phone holds the only copy.
- DAOs for what the app will need next:
  - observing a given week's Mission with its sorties in index order
  - reading a sortie's Flight Plan with its exercises (including display names), sets and run segments, all in their stored order
  - writing a whole Flight Plan in one transaction, replacing any plan the sortie already has
  - updating a set's actual values and status, adding an extra set (marked as added), and updating an exercise's skipped flag and notes
  - updating sorties and missions
  - listing landed and scrubbed sorties newest first
  - insert, read, update and delete for Generation rows
  - equipment: add, edit, list active only or all, deactivate and reactivate
- **Exercise identity (§7.7).** Looking up an exercise by name uses the existing normalizer in `com.liftoff.app.coach`. It returns the existing row when the normalized name matches and creates one otherwise. The first spelling seen stays the display name. Writing a Flight Plan resolves its exercise names the same way.
- **Equipment.** A key must match `[a-z0-9_]+` and be unique. An invalid key is rejected. Removing an item marks it inactive and never deletes it, and there is no delete operation at all.

**AppContainer.** Add the database to the existing `AppContainer` in `com.liftoff.app`. It is created once, lazily, next to the settings store that is already there.

Builds on the AppContainer, Application subclass, settings store and exercise-name normalizer from the previous brief, and the Space Age theme and placeholder screen. Must not break: those and their tests, the launcher icon, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Robolectric tests with in-memory Room cover each DAO behaviour above: ordering, plan replacement, set and exercise updates, newest-first history, and Generation CRUD.
- Tests cover the four uniqueness rules, equipment key validation and 'deactivate, don't delete'.
- Tests show that spelling variants of an exercise name map to one Exercise row.
- ARCHITECTURE.md's package table marks `data` as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Navigation shell and Mission Control

Give Liftoff its real navigation and settings screen in the Space Age look from `design/README.md`. Build on what's already in the repo:
- the theme and shared composables in `com.liftoff.app.ui.theme`: `LiftoffTheme`, `TriStripe`/`DuoStripe`, `offsetShadow`, `PrimaryButton`, `InkButton`, `UnderlinedTextButton`, `Eyebrow`/`DisplayTitle`/`TitleBlock`, `Wordmark`, `InkRuledListRow`, `PatternTrack` and `LiftoffIcons`
- the `AppContainer` with its settings store and the Room database's equipment DAO

**Shell.** Replace MainActivity's placeholder with the app shell described in DESIGN.md §9 and design/README.md:
- the three stripes
- a top bar with the LIFTOFF wordmark and a 44 dp outlined sliders button that opens Mission Control
- an ink bottom bar with Launchpad, Mission and Landed, each an icon over an uppercase label; the active item is mustard and the others are the 'rule' color
Launchpad (the start destination), Mission and Landed are placeholder screens for now: an eyebrow and display title in the design style with a short line of text. Later briefs fill them in. System back works as expected. Navigation is plain Compose state; add a navigation library only if one is already declared.

**Mission Control.** It isn't mocked up; build it from the same parts (eyebrow and display title, ink-ruled lists, paper cards with ink borders). It edits every setting in DESIGN.md §8:
- daemon host, port and token (masked, with a way to reveal it)
- coach workspace path
- default pattern, edited as R/L chips (1–7 sorties)
- sortie length in minutes, units (lb/kg and mi/km) and history window in days
- the 'Generate run plans' toggle
- objectives and constraints as free text
Changes persist and survive an app restart. Invalid input (a bad port, a pattern outside 1–7, a non-positive length) is rejected with a clear message rather than saved.

**Equipment.** Mission Control also manages the equipment list. It shows each item as key — name — notes and supports add, edit and deactivate. Deactivated items are hidden from the main list but can be viewed and reactivated. A key must be unique and match `[a-z0-9_]+`, and a duplicate or invalid key gets an inline error.

'Test connection' and export/import come in later briefs; do not add placeholders for them that don't work.

**Theme gaps.** Every Material 3 typography role must use the bundled fonts: Big Shoulders Display for display, headline and title roles, and Work Sans for body and label roles. At the moment some roles fall back to the system font, and text fields and dialogs would show it. Also remove or properly annotate the public '...Preview' composables in ui/theme. They have no @Preview annotation, so they are dead public API.

Must not break: the data layer and settings and their tests, the launcher icon, the build gate, and the §2 vocabulary in UI strings. Follow CLAUDE.md conventions.

Done when:
- The app opens to the shell, and all three tabs and Mission Control are reachable.
- Settings and equipment edits persist.
- Tests cover the Mission Control state and validation logic, including the equipment key rules, and check that no typography role uses the default font family.
- ARCHITECTURE.md's package table marks `ui` as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Mission and sortie domain logic

Implement the rules for weeks (Missions) and their sorties from DESIGN.md §4, §5.1 and §5.3. Build on the Room database, DAOs, settings store and AppContainer already in the repo. No new screens in this brief; the next brief builds the Launchpad on it.

**Pure Kotlin in `com.liftoff.app.domain`, with no Android imports:**
- the week start: Monday, in the phone's local time zone
- creating a DRAFT Mission for the current week with the default pattern
- overriding the pattern, only while the Mission is a draft (1–7 of R/L); the pattern is frozen once confirmed
- the Mission lifecycle DRAFT → ACTIVE → CLOSED
- the sortie state machine (PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED): every legal transition in §5.1 succeeds and every illegal one is rejected
- at most one sortie IN_FLIGHT across all Missions
- next-sortie selection: the lowest index that is neither landed nor scrubbed
- a Mission closes when every sortie has landed or been scrubbed
- rollover: once a Mission's week has ended, its open sorties become SCRUBBED with reason 'week ended' and the Mission closes. Nothing carries over, and no Mission is created ahead of its week.

**Confirming.** Coach generation does not exist yet, so confirming a draft takes the 'Continue without outline' path from §4.2. The Mission becomes ACTIVE, lift sorties get focus 'full body' and runs get focus 'easy'.

**Planning the current sortie.** Whenever a sortie becomes current (after confirm, or after the previous sortie lands or is scrubbed), the app prepares its plan the way §5.3 and §7.2 describe:
- With run generation off, a run sortie gets a simple Flight Plan titled 'Run' with source SIMPLE_RUN and its focus, and the sortie becomes PLANNED.
- Lift sorties, and runs with generation on, stay PENDING for now. Keep this decision in one clear place so the later coach-generation work can queue a generation there. Do not create Generation rows yet.

**A Room-backed layer the UI can call**, with each operation in a transaction:
- on app open, roll over past Missions and make sure the current week has a Mission, creating a draft if needed
- change a draft's pattern
- confirm
- scrub the current sortie, with an optional reason (a scrubbed IN_FLIGHT sortie keeps the sets already checked)
- launch a PLANNED sortie
- land the IN_FLIGHT sortie (set LANDED with its landed time, mark unchecked sets as not done, advance to the next sortie and prepare its plan, and close the Mission after the last one)
Expose it through AppContainer. The clock and time zone must be injectable so tests can control them.

Must not break: the shell, Mission Control, the data layer and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Unit tests cover the §13 'Missions' and 'Sorties' cases: draft created with the default pattern; override, then frozen on confirm; rollover scrubs open sorties with 'week ended'; no carry-over; the Monday boundary across time zones; every legal and illegal transition; next-sortie selection skipping landed and scrubbed sorties; at most one in flight.
- Tests cover simple run plan creation and the Room-backed operations, including land advancing to the next sortie and closing the Mission.
- ARCHITECTURE.md's package table marks `domain` as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Launchpad and Mission screens

Replace the Launchpad and Mission placeholder tabs with the real screens. Build on:
- the Space Age theme and shared composables in `com.liftoff.app.ui.theme` (`TitleBlock`, `PatternTrack`, `InkRuledListRow`, `PrimaryButton`, `UnderlinedTextButton`, `offsetShadow`, `LiftoffIcons`)
- the navigation shell in MainActivity
- the Mission/sortie domain logic and its Room-backed operations (ensure the current week, change the draft pattern, confirm, scrub, launch), reached through AppContainer
Screens update live from Room. Run 'ensure the current week' whenever the app opens or comes back to the foreground, so rollover happens at the week boundary.

**Launchpad** (DESIGN.md §9). The PLANNED state must match `design/screens/launchpad.html` and the 'Launchpad, PLANNED' section of `design/README.md`:
- an eyebrow like 'WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT' and the plan title in 68 sp display type
- the pattern track, with landed, current and upcoming chips
- a 'FLIGHT PLAN' head with the estimated minutes and set count, over a ruled list with one row per exercise: red index, name, and load such as '3×8 · 135'. For a run, show the run summary instead.
- the coach note, with 'Coach:' in teal
- the 72 dp red Launch button with the rocket glyph and offset shadow
- an underlined Scrub text button
Regenerate needs coach generation, which comes later; leave it out until then.

The other states are built from the same parts:
- **No confirmed Mission this week:** the draft, with editable R/L pattern chips (add, remove, toggle; 1–7 sorties) starting from the default pattern, and a Confirm button.
- **PENDING:** a status line saying no Flight Plan is ready yet, with Scrub where Launch would be.
- **IN_FLIGHT:** a Resume button.
- **Mission closed:** a short completion state.

Scrub asks for confirmation first. Launch sets the sortie IN_FLIGHT and opens an In-Flight route; Resume opens the same route. That route can be a simple placeholder showing the sortie's title, because the next brief builds the real In-Flight screen.

**Mission tab** (§9). It shows:
- this week's pattern track
- the outline notes, if any
- each sortie with its index, type (run/lift), focus and state
Tapping a sortie shows its Flight Plan, or a short record for a landed or scrubbed sortie (including the scrub reason). It isn't mocked up; use the same design parts.

UI strings keep the §2 vocabulary (Launch, Flight Plan, Sortie, Scrub, Mission).

Must not break: Mission Control, the domain rules and their tests, and the build gate. Follow CLAUDE.md conventions.

Done when:
- On a fresh install, the app opens to a draft for the current week with the default pattern.
- Confirming shows sortie 1 (a run under the default RLRLR pattern) as PLANNED with Launch available.
- Scrubbing advances to the next sortie.
- Tests cover how each Launchpad state is derived from the database.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. In-Flight checklist, Launch and Land

Build the In-Flight screen, where the owner logs a workout at the gym. It is described in DESIGN.md §6, `design/screens/in-flight.html` and the 'In-Flight' section of `design/README.md`. It replaces the placeholder In-Flight route that Launch and Resume on the Launchpad already open. Build on:
- the Space Age theme composables (`InkButton` with its red shadow, the stripes, `offsetShadow`, `LiftoffIcons`)
- the Room DAOs for set and exercise updates and for adding sets
- the domain's Room-backed operations for launch, scrub and land, reached through AppContainer

**Header and progress.**
- A teal header: the mustard eyebrow 'IN FLIGHT · SORTIE n', the title in cream, and a set counter like '05/14' over 'SETS'.
- A progress bar with one segment per set: mustard when done, outlined teal_light when open.
- The mustard and red bands under the header.

**Exercise cards.**
- **Done:** collapsed to a sand row with the name struck through and 'n/n LANDED' in teal.
- **Active:** a paper card with an offset shadow and an ink header bar.
- **Upcoming:** the same card without the shadow.
- Each active or upcoming card has a footer with '+ Add set · Note · Skip'.

**Set rows (58 dp).**
- Each row shows 'SET n', the weight and the reps or seconds, and a 48 dp check box.
- One tap on the box marks the set done with actual values equal to the planned ones. Tapping it again un-checks it.
- Tapping the numbers, or a long press, opens a stepper for reps or seconds and for weight. Saving it marks the set done.
- A set done differently from the plan shows the actual value in red with a small 'of <planned>'.
- The current set's label is red.
- A set or a whole exercise can be skipped. Extra sets can be added and are marked as added.

**Notes.** An optional note per exercise and one per sortie.

**Land.** A 64 dp ink Land button with a flag glyph and a red offset shadow.
- If any sets are unchecked, ask for confirmation. Unchecked sets are recorded as not done.
- Landing sets the sortie LANDED with its landed time and returns to the Launchpad.
- The next sortie becomes current and its plan is prepared through the existing domain step. The Mission closes if this was the last sortie.
- Scrub stays available from In-Flight and keeps the sets already checked.

**Run sorties.** A run's In-Flight screen shows its plan (title, focus, any segments). Land offers optional distance and duration fields in the configured units.

**Persistence.** Every action is written to Room as it happens. If the process is killed, reopening the app (or tapping Resume) brings the screen back exactly as it was left; while a sortie is IN_FLIGHT the app reopens to it. Nothing in this flow uses the network.

Lift Flight Plans can't be generated yet, so tests should seed plans directly into Room.

Must not break: the Launchpad and Mission screens, the domain rules, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Launch → log → Land works for a seeded lift plan and for a simple run.
- Tests cover:
  - checking a set, editing with a deviation, skipping, adding a set, and notes
  - landing with and without unchecked sets
  - the next sortie being advanced and the Mission closing after the last sortie
  - the In-Flight state being rebuilt exactly from Room
- ARCHITECTURE.md's milestone table updates M1, M2 and M4 to reflect what is built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built so far: the Space Age design and theme, the settings store, the exercise-name normalizer, AppContainer, and the Room database with its DAOs. The round 2 'Navigation shell and Mission Control' run was stopped before it finished. It held too much for one run: the shell, every settings editor, equipment management and the theme fixes. Because of that, the domain logic, Launchpad and Mission screens and In-Flight never started. MainActivity still shows the round-1 placeholder. Nothing exists yet for Mission/sortie rules, Launchpad, In-Flight, coach generation (coach/ workspace, prompt builder, schemas, validator, DaemonClient copy, GenerationWorker), Landed/Re-fly, run-plan toggle behaviour, test connection or export/import. Seven Material type roles still use the system font, the unannotated '...Preview' composables are still public, and every ARCHITECTURE.md milestone still says not started. This round splits the failed run into three small runs (shell and theme fixes, settings editor, equipment), then does the domain logic and the Launchpad/Mission screens. In-Flight, generation, Landed and export/import come in later rounds.

## Round 3

### 1. Navigation shell and theme fixes

Replace MainActivity's placeholder with Liftoff's real navigation shell, in the Space Age look from `design/README.md` and DESIGN.md §9. Fix two small gaps in the theme. This brief is deliberately small. An earlier run that tried to build the shell together with the whole settings screen was stopped before it finished. Build only what is listed here. The settings screen's content and equipment management come in the next briefs.

Build on what's already in the repo:
- the theme and shared composables in `com.liftoff.app.ui.theme`: `LiftoffTheme`, `TriStripe`, `Wordmark`, `Eyebrow`/`DisplayTitle`/`TitleBlock`, the offset-shadow helpers and `LiftoffIcons` (rocket, planet, flag, sliders)
- the color tokens (Ink, Mustard, Rule, Cream and the rest)
- the `AppContainer` owned by `LiftoffApplication`

**Shell.** MainActivity renders, inside `LiftoffTheme` on cream:
- the three-band stripe at the top
- a top bar with the LIFTOFF wordmark on the left and a 44 dp outlined sliders button on the right (2 dp ink border, sliders icon). The button opens Mission Control.
- an ink bottom bar with three items, Launchpad (rocket), Mission (planet) and Landed (flag). Each item is an icon over an uppercase display-font label. The active item is mustard and the others use the 'rule' color.
Launchpad is the start destination. Launchpad, Mission and Landed are placeholder screens for now: an eyebrow and display title in the design style with one short line of text. Later briefs fill them in.

Mission Control is a separate full screen reached from the sliders button. It has no bottom-bar item. For now it shows an eyebrow and the title 'MISSION CONTROL' with a back affordance. The next brief fills it in, so leave a clear place for that content.

Navigation is plain Compose state. No navigation library is declared, so don't add one. System back behaves as people expect: from Mission Control it returns to the tab you came from, from a non-start tab it returns to Launchpad, and from Launchpad it leaves the app. The selected tab survives rotation.

**Theme fixes.**
- Every Material 3 typography role must use a bundled font. Today `LiftoffTypography()` in `ui/theme/Type.kt` sets only 8 roles. displaySmall, headlineMedium, headlineSmall, titleMedium, titleSmall, bodySmall and labelMedium still fall back to the system font, so text fields and dialogs would show it. Display, headline and title roles use Big Shoulders Display. Body and label roles use Work Sans. Keep the 8 roles that are already set as they are.
- The public `...Preview` composables in `ui/theme` (PrimaryButtonPreview, TriStripePreview and the others) have no `@Preview` annotation, so they are dead public API. Either add `@Preview` and make them private, or remove them. Update any test that refers to them.

UI strings follow the DESIGN.md §2 vocabulary. Must not break: the data layer, settings store, AppContainer and their tests, the launcher icon, the existing theme tests, and the build gate. Follow CLAUDE.md: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- The app opens to the shell on the Launchpad tab. All three tabs and the Mission Control screen are reachable, and back behaves as described.
- A JVM test covers the navigation state logic: tab selection, opening Mission Control, and what back does from each place.
- A test checks that every Material 3 typography role's font family is Big Shoulders Display or Work Sans, and never the default family.
- ARCHITECTURE.md's package table marks `ui` as existing, and the root package's row no longer calls MainActivity a placeholder.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Mission Control settings

Fill in the Mission Control screen so it edits every setting in DESIGN.md §8. Read DESIGN.md §2, §8 and §9 and `design/README.md` first.

Build on what's already in the repo:
- the navigation shell in MainActivity, where the top bar's sliders button opens a Mission Control screen that currently shows only its title. Put the content there.
- `SettingsStore` in `com.liftoff.app.settings`, reached through `AppContainer`. It has an observable `settings` flow and one setter per field. The setters already reject a port outside 1–65535, a pattern that isn't 1–7 R/L characters, and a non-positive sortie length or history window.
- the theme composables in `com.liftoff.app.ui.theme`: `TitleBlock`, `InkRuledListRow`, `PatternTrack`, the buttons and the offset-shadow helpers

Mission Control isn't mocked up. Build it from the design's parts: eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and square or 4 dp corners. Don't use stock M3 tonal surfaces or pill shapes. It scrolls and edits:
- **Connection:** daemon host, port and token. The token is masked, with a way to reveal it.
- **Coach:** the coach workspace path.
- **Mission:**
  - the default pattern, edited as R/L chips: tap to toggle, add and remove, 1–7 sorties
  - sortie length in minutes
  - history window in days
- **Units:** weight lb/kg and distance mi/km.
- **Runs:** the 'Generate run plans' toggle.
- **Objectives and constraints:** two free-text fields.

Edits are saved to the settings store and survive an app restart. Invalid input is not saved, and a clear message is shown next to the field: a port that isn't a number in 1–65535, a non-positive or non-numeric length or window, or a pattern outside 1–7. The pattern editor must not let the pattern drop below 1 or go above 7 chips. When the screen opens it shows the stored values.

Put the validation and screen state in plain Kotlin that a JVM test can drive without Compose UI, for example a state holder or ViewModel built from the settings store. Compose UI tests are not required. Leave room in the screen for an Equipment section, which the next brief adds. Don't add 'Test connection' or export/import yet, and don't add placeholders for them that don't work.

UI strings follow the §2 vocabulary: say 'sortie length', never 'session'. Must not break: the shell and its back behaviour, the settings store and data layer and their tests, the theme tests, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Every §8 setting can be edited from Mission Control and keeps its new value after the app restarts.
- JVM tests cover loading the stored values, saving each kind of field, rejecting each invalid input with the stored value left unchanged, and the pattern editor's 1–7 limits.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Equipment management in Mission Control

Add equipment management to the Mission Control screen. The coach later uses the equipment list to plan lifts (DESIGN.md §7.1, §8). Read DESIGN.md §8 and `design/README.md` first.

Build on what's already in the repo:
- the Mission Control screen and its state logic, which already edits the settings. Add an Equipment section to it.
- `EquipmentDao` in `com.liftoff.app.data`, reached through `AppContainer`'s database. It has add (rejects a key that doesn't match `[a-z0-9_]+`), edit of name and notes, active-only and all lists as flows, deactivate and reactivate. The key column is unique, so inserting a duplicate key throws.
- the theme composables (`InkRuledListRow`, the buttons, paper cards with ink borders)

**Behaviour.**
- The section lists active equipment, one row per item, showing key — name — notes.
- Add: enter key, name and optional notes. An invalid key, or one already used by any item (active or inactive), gets an inline error and nothing is saved. Name is required.
- Edit: change an item's name and notes. The key is fixed once created, because history and prompts refer to it.
- Deactivate: removes the item from the main list. Nothing is ever deleted.
- A way to show deactivated items, each with a Reactivate action.
The list updates live from Room.

Use the same design parts as the rest of Mission Control. No stock M3 tonal surfaces or pill shapes. Dialogs and text fields should use the theme's fonts and ink borders.

Put the validation and state in plain Kotlin that a JVM or Robolectric test can drive against an in-memory database. Compose UI tests are not required.

Must not break: the settings editing in Mission Control, the shell, the data layer and its tests, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Equipment can be added, edited, deactivated and reactivated from Mission Control, and changes persist.
- Tests cover the key rules (invalid pattern, duplicate against an active item, duplicate against an inactive item), the name requirement, editing, and deactivate/reactivate moving an item between the two lists without deleting it.
- ARCHITECTURE.md's milestone table marks M1 (skeleton and data, theme, shell, Mission Control with equipment) as done, with a note that test connection and export/import come later.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Mission and sortie domain logic

Implement the rules for weeks (Missions) and their sorties from DESIGN.md §4, §5.1 and §5.3. Read DESIGN.md §2, §4, §5, §8 and §13 first. Build on the Room database and DAOs in `com.liftoff.app.data` (Mission, Sortie, FlightPlan and their DAOs, including writing a whole Flight Plan in one transaction), the settings store, and `AppContainer`. No new screens in this brief; the next brief builds the Launchpad on it.

**Pure Kotlin in `com.liftoff.app.domain`, with no Android imports** (ARCHITECTURE.md invariant 2):
- the week start: Monday, in the phone's local time zone
- creating a DRAFT Mission for the current week with the default pattern
- overriding the pattern, only while the Mission is a draft (1–7 of R/L); the pattern is frozen once confirmed
- the Mission lifecycle DRAFT → ACTIVE → CLOSED
- the sortie state machine (PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED): every legal transition in §5.1 succeeds and every illegal one is rejected
- at most one sortie IN_FLIGHT across all Missions
- next-sortie selection: the lowest index that is neither landed nor scrubbed
- a Mission closes when every sortie has landed or been scrubbed
- rollover: once a Mission's week has ended, its open sorties become SCRUBBED with reason 'week ended' and the Mission closes. Nothing carries over, and no Mission is created ahead of its week.

**Confirming.** Coach generation doesn't exist yet, so confirming a draft takes the 'Continue without outline' path from §4.2. The Mission becomes ACTIVE and gets one sortie per pattern letter (R = run, L = lift). Lift sorties get focus 'full body' and runs get focus 'easy'.

**Planning the current sortie.** Whenever a sortie becomes current (after confirm, or after the previous sortie lands or is scrubbed), the app prepares its plan as §5.3 and §7.2 describe:
- With run generation off, a run sortie gets a simple Flight Plan titled 'Run' with source SIMPLE_RUN and its focus, and the sortie becomes PLANNED.
- Lift sorties, and runs with generation on, stay PENDING for now. Keep this decision in one clear place so the later coach-generation work can queue a generation there. Don't create Generation rows yet.

**A Room-backed layer the UI can call**, with each operation in one transaction:
- on app open, roll over past Missions and make sure the current week has a Mission, creating a draft if needed
- change a draft's pattern
- confirm
- scrub the current sortie with an optional reason. A scrubbed IN_FLIGHT sortie keeps the sets already checked.
- launch a PLANNED sortie (records launchedAt)
- land the IN_FLIGHT sortie: set it LANDED with its landed time, mark unchecked sets as not done, advance to the next sortie and prepare its plan, and close the Mission after the last one
Expose it through `AppContainer`. The clock and time zone must be injectable so tests can control them.

Must not break: the shell, Mission Control and equipment management, the data layer and its tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary: never 'session' for a sortie.

Done when:
- Unit tests cover the §13 'Missions' and 'Sorties' cases: draft created with the default pattern; override, then frozen on confirm; rollover scrubs open sorties with 'week ended'; no carry-over; the Monday boundary across time zones; every legal and illegal transition; next-sortie selection skipping landed and scrubbed sorties; at most one in flight.
- Robolectric tests with in-memory Room cover simple run plan creation and each Room-backed operation, including land advancing to the next sortie and closing the Mission.
- ARCHITECTURE.md's package table marks `domain` as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Launchpad and Mission screens

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

