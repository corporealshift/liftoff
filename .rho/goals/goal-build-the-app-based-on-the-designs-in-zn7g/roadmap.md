# Goal

build the app based on the designs in the design directory

## Done when

- The `design/` directory (README.md, the two screen mockups and the icon SVG) is in the repository. The app uses the Space Age look it describes: a custom MaterialTheme with the cream/paper/sand/ink/red/mustard/teal tokens at the listed hex values, Big Shoulders Display and Work Sans bundled in res/font, 2 dp ink borders, solid offset shadows with no blur, the red/mustard/teal stripes, light theme only, and the dumbbell-satellite adaptive launcher icon.
- The app opens to a navigation shell with an ink bottom bar (Launchpad · Mission · Landed, active item in mustard) and a top bar holding the LIFTOFF wordmark and a sliders button. That button opens Mission Control, which edits and persists every setting in DESIGN.md §8, manages equipment (add, edit, deactivate), tests the rho connection and exports/imports the database as JSON.
- Opening the app in a week with no Mission shows a draft with editable R/L pattern chips that start from the default pattern, and a Confirm button. Sorties then run in rolling pattern order. Scrub works. At the week boundary, open sorties are scrubbed with 'week ended' and nothing carries over. Pure-Kotlin domain tests cover the §13 mission and sortie cases.
- The Launchpad's PLANNED state matches design/screens/launchpad.html: an eyebrow, the display title, the pattern track, the Flight Plan list with the coach note, a big red Launch button with an offset shadow, and Regenerate/Scrub text buttons. The PENDING, IN_FLIGHT (Resume) and draft states are built from the same parts.
- The In-Flight screen matches design/screens/in-flight.html and DESIGN.md §6. It has a teal header with a set counter and segmented progress, and exercise cards in done, active and upcoming states. One tap checks a set off, editing a set marks it done and shows the deviation in red, and sets and exercises can be skipped. Sets can be added, notes kept, and Land asks for confirmation when sets are unchecked. Every action is written to Room straight away, so the screen comes back unchanged after the process is killed, and no network is needed.
- Coach generation works as DESIGN.md §7 describes. The `coach/` workspace template exists, along with the pure-Kotlin prompt builder, the schemas and the validator, all tested with golden files and fixtures. A GenerationWorker talks to rho through the copied DaemonClient and is tested against a fake daemon for the §13 cases. It produces outlines and lift Flight Plans, with up to 2 in-session repairs, regenerate/supersede, and 'Mission Control offline' status on the Launchpad.
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

**Check:** not met. Done so far: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, and the Launchpad and Mission screens. Still missing: the In-Flight screen is a placeholder ('The In-Flight checklist comes later'), so there is no set logging, Land UI, run landing or resume-after-kill. Nothing exists for coach generation: no coach/ workspace template, schemas, validator, prompt builder, copied nabu DaemonClient or GenerationWorker. Lift sorties stay PENDING forever, and Regenerate, the 'Coach is planning'/'Mission Control offline' statuses and generated run plans don't exist. The Landed tab is a placeholder and there is no Re-fly. Mission Control has no Test connection and no export/import. ARCHITECTURE.md marks M0 and M3–M6 not started. There is also some smaller design drift on the Launchpad: the scrub dialog uses a stock M3 text field, and the draft has duplicate −/+ controls. This round builds In-Flight in two small runs, then Landed and Re-fly, then export/import. Those don't depend on the network, so they come first. Generation follows in four runs: validator and schemas, prompt builder, nabu client with Test connection, the worker. A last run wires generation into Missions and the Launchpad.

## Round 4

### 1. In-Flight checklist for lift sorties

Build the In-Flight checklist where the owner logs a lift workout at the gym. Read DESIGN.md §2, §5 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first. The HTML is a static mockup where 1 px = 1 dp. This brief covers the checklist itself. Notes, Land, Scrub from In-Flight and run sorties come in the next brief, so leave clear room for them: a notes affordance in each card footer, and a bottom area where the Land button will go.

Build on what's already in the repo:
- the placeholder `InFlightScreen(sortieId, container, onBack)` in `com.liftoff.app.ui.inflight`. Launch and Resume on the Launchpad already open it. Replace its content.
- `FlightPlanDao` in `com.liftoff.app.data`: `getPlan(sortieId)` (exercises with display names, sets, segments, all in stored order), `observeChanges()`, `updateSetActuals`, `addExtraSet` (marks the set as added) and `updateExercise` (skipped flag and user notes)
- the theme in `com.liftoff.app.ui.theme`: color tokens (Teal, TealLight, Mustard, Red, Sand, Paper, Ink, Cream and the rest), `DuoStripe`, the offset-shadow helpers, `LiftoffType`, `LiftoffIcons`

**Header and progress.**
- A teal header with the mustard eyebrow 'IN FLIGHT · SORTIE n', the plan title in cream, and a set counter like '05/14' over 'SETS'. Count done sets out of all sets. Skipped sets count as finished for progress but not as done.
- A progress bar with one segment per set: mustard when done, outlined teal_light when open.
- The mustard and red bands under the header.

**Exercise cards.**
- **Done**: every set is done or skipped. The card collapses to a sand row with the name struck through and 'n/n LANDED' in teal.
- **Active**: the first exercise that still has an open set. A paper card with an offset shadow and an ink header bar.
- **Upcoming**: the same card without the shadow.
- A skipped exercise looks finished and says it was skipped.
- Each active or upcoming card has a footer with '+ Add set · Note · Skip'. For now, Note can be left out or disabled until the next brief.

**Set rows (58 dp).**
- Each row shows 'SET n', the weight (or 'BW' when there is none) and the reps or seconds, with a 48 dp check box.
- One tap on the box marks the set done with actual values equal to the planned ones. Tapping a done set's box un-checks it: back to open, actuals cleared.
- Tapping the numbers, or a long press, opens a stepper for reps or seconds and for weight, in the theme's style (no stock M3 dialog look). Saving marks the set done with those values.
- A set done differently from the plan shows the actual value in red with a small 'of <planned>'.
- The current set's label (the first open set of the active exercise) is red.
- A single set can be skipped, and a whole exercise can be skipped. Skipping an exercise marks its open sets skipped.
- '+ Add set' adds a set to that exercise, pre-filled from its last set and marked as added.

**Persistence.** Every action is written to Room as it happens, and the screen is rebuilt from Room. If the process is killed, reopening the In-Flight route for that sortie shows exactly what was left. Nothing uses the network. Do not change the Room schema. If you find a change unavoidable, bump the version with a real migration, never a destructive one, because the phone holds the only copy.

Put the screen state and its derivation (card states, current set, counter, deviations) and the actions in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Lift plans can't be generated yet, so tests seed plans directly through `writePlan`.

Must not break: the Launchpad and Mission screens, MissionManager and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- For a seeded lift plan, the In-Flight screen shows the header, progress and cards in done, active and upcoming states as in the mockup.
- Tests cover: one-tap check and un-check; editing with a deviation (actual differs, planned kept); skipping a set and an exercise; adding a set; the counter and card states; and the state being rebuilt identically from a fresh read of the database after actions.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Notes, Land, In-Flight scrub and run sorties

Finish the In-Flight flow: notes, landing, scrubbing from In-Flight, run sorties, and reopening the app into an in-flight sortie. Read DESIGN.md §5, §6 and §9, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first.

Build on what's already in the repo:
- the In-Flight checklist in `com.liftoff.app.ui.inflight` and its state holder, built by the previous brief
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`. Its `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie, prepares its plan, and closes the Mission after the last one. Its `scrub(sortieId, reason)` keeps checked sets.
- `SortieDao` (the Sortie row has `notes`, `runDistance` and `runMinutes`) and `FlightPlanDao.updateExercise` (exercise `userNotes`)
- the Launchpad screen and view model in `com.liftoff.app.ui.launchpad`, and the shell in MainActivity
- the theme: `InkButton` (ink with red offset shadow), `UnderlinedTextButton`, `LiftoffIcons.flag`, and the text-field style used in Mission Control

**Notes.** Each exercise card's 'Note' opens an optional free-text note for that exercise. There is also one note for the whole sortie. Both are saved to Room as they are entered and come back after a process kill.

**Land.** A 64 dp ink Land button with the flag glyph and a red offset shadow, as in the mockup.
- If any sets are unchecked, ask for confirmation first, saying how many. Unchecked sets are recorded as not done.
- Landing goes through `MissionManager.land`, then returns to the Launchpad, which now shows the next sortie, or the closed state after the last one.

**Scrub from In-Flight.** An underlined Scrub action with confirmation and an optional reason. It keeps the sets already checked, then returns to the Launchpad.

**Run sorties.** A run's In-Flight screen shows its plan: title, focus or notes, run kind, target distance and pace, and segments when present. There are no set rows. Land offers optional distance and duration fields in the configured distance unit (settings), stored on the sortie. Both may be left blank.

**Reopening.** While a sortie is IN_FLIGHT, a cold start of the app opens straight into its In-Flight screen. Back from In-Flight goes to the Launchpad, which still offers Resume. If the week ends while a sortie is in flight, the existing rollover scrubs it with 'week ended' when the app comes back to the foreground. The In-Flight screen must then return to the Launchpad instead of acting on a sortie that is no longer in flight.

**Launchpad polish.**
- The existing Launchpad scrub dialog uses a stock M3 OutlinedTextField with a floating label. Use the same themed reason field as the In-Flight scrub.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and 7.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. No network anywhere in this flow. Do not change the Room schema; if it's unavoidable, add a real migration, never a destructive one.

Must not break: the checklist from the previous brief, the Launchpad and Mission screens, MissionManager and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Launch → log → Land works end to end for a seeded lift plan and for a simple run, and both can be scrubbed from In-Flight.
- Tests cover: exercise and sortie notes persisting; landing with and without unchecked sets (unchecked ones recorded not done); the next sortie becoming current and the Mission closing after the last sortie; run distance and duration saved, and saved as empty when left blank; scrub from In-Flight keeping checked sets; and the start destination being In-Flight when a sortie is IN_FLIGHT.
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Landed history and Re-fly

Replace the Landed tab's placeholder with the real history screen, and add Re-fly. Read DESIGN.md §5.1, §5.4, §9 and §13 ('Re-fly') and `design/README.md` first. Landed isn't mocked up; build it from the design's parts: eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder today) and the shell in MainActivity
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first), `MissionDao`, and `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags and notes
- `MissionManager` in `com.liftoff.app.data` (one transaction per operation) and the pure domain package `com.liftoff.app.domain` (no Android imports, ARCHITECTURE.md invariant 2)
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (LaunchpadState, LaunchpadViewModel, LaunchpadScreen)

**Landed tab.**
- History is newest first and grouped by week ('WEEK OF OCT 5'). Each row shows the date, the type (run/lift), the plan title or focus, and whether it landed or was scrubbed. A scrubbed row shows its reason.
- Tapping a row opens a detail view:
  - for a lift: each exercise with every set's planned and actual values (deviations in red, skipped and not-done sets marked), exercise notes and the sortie note
  - for a run: the plan summary and the recorded distance and duration
  - for a scrubbed sortie: the reason and any sets that were checked before the scrub
- A landed sortie can't be edited, except its sortie note, which can be edited from the detail view (§5.1).
- An empty history shows a short empty state. The list updates live from Room. Back from the detail returns to the list.

**Re-fly (§5.4).**
- A pure function in `domain` picks the source plan for a PENDING sortie: the most recent LANDED sortie of the same type that has a plan, preferring one whose focus matches the current sortie's focus and otherwise falling back to the same type only.
- A Room-backed `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It copies planned values, not actuals; no added sets; skipped flags and user notes cleared. The source becomes REFLY and the sortie PLANNED. It works with no network.
- On the Launchpad's PENDING state, show a Re-fly action next to Scrub when a source plan exists. When none exists, say so instead of showing a dead button. Coach generation doesn't exist yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema; if it's unavoidable, add a real migration, never a destructive one.

Must not break: In-Flight, the Launchpad and Mission screens, MissionManager and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- The Landed tab lists history newest first, grouped by week, with a working detail view for landed lift, landed run and scrubbed sorties.
- Pure unit tests cover the §13 Re-fly cases: it prefers the same type and focus, falls back to the same type only, picks the most recent, and returns nothing when there is no landed plan of that type.
- Robolectric tests show the copy has planned values and no actuals, has source REFLY, leaves the sortie PLANNED, and needs no network. They also cover history grouping and ordering.
- ARCHITECTURE.md marks Landed and Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) with its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation, equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.** Mission Control has an Export action. It opens the system file picker to create a `.json` file, defaulting to a name like `liftoff-2026-10-10.json`, and writes the whole database to it: every row of every table, ids included, plus a format version and the Room schema version. Field names in the file are snake_case. Settings and the daemon token are not part of the export. Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file and loads it into the database in one transaction, keeping ids so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty, and that draft is replaced.
- Anything else is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread and report success or failure in Mission Control in the design style: section head, ink-ruled rows, buttons from `ui.theme`, no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Must not break: the settings and equipment editing in Mission Control, the data layer and its tests, the other screens, and the build gate. Do not change the Room schema. Follow CLAUDE.md conventions.

Done when:
- Export → import into a new in-memory database gives an identical database: every row in every table compares equal. The test seeds Missions in each status, sorties in each state, lift and run plans with actuals and added sets, exercises, generations, and active and inactive equipment.
- Tests cover refusing a non-empty database, accepting one that holds only a fresh draft, and refusing a malformed or wrong-version file, each with the database unchanged.
- ARCHITECTURE.md's `data` row mentions export/import, and M1's note no longer lists export/import as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Coach workspace template, output schemas and validator

Create the coach workspace template and the phone-side validation of coach replies. Read DESIGN.md §7.1, §7.1.1, §7.5, §7.6, §12 and §13 ('Validator') first. Nothing in the UI changes in this brief. Later briefs add the prompt builder and the generation worker, which call this validator.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md`: the one-time PC setup from §7.1. Copy the folder out to its own path, run `git init` there (it is never part of this repo), turn off rho's verify gate for that path in `~/.rho/config.json` with the exact snippet from §7.1, and note the daemon host, port (default 8737) and token for Mission Control.
- `coach/COACH.md`: the coach's standing instructions, with every point §7.1.1 says it MUST contain:
  - role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schemas.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json` with the JSON Schemas from §7.5.1–§7.5.3 verbatim.

**Validator (`com.liftoff.app.coach`, pure Kotlin, no Android imports, ARCHITECTURE.md invariant 2).**
- A small in-house JSON Schema checker for exactly the subset §7.5 uses: type, const, enum, required, additionalProperties, minimum/maximum, minLength/maxLength, minItems/maxItems. Don't add a JSON Schema library (§15 decision 14). Parse with kotlinx.serialization.
- A pure validation function from (raw reply text, generation context) to either a parsed result in plain coach types or a list of human-readable errors. The context holds the generation kind, the Mission pattern, the type of the sortie being planned, the active equipment keys and the configured sortie length.
- It follows §7.6 exactly:
  - trim, and unwrap a single surrounding Markdown code fence
  - parse exactly one JSON object; prose before or after it is an error
  - check the schema
  - run the semantic checks: outline entries match the pattern one per index 0..n-1 with matching types; a lift set has exactly one of reps or seconds; every equipment id is in the active equipment list; estimated_minutes is at most 1.25 × the sortie length; exercise names are unique; the plan type matches the sortie
- Each error names its path and problem specifically, because errors are sent back verbatim in repair prompts. For example: `exercises[2].equipment: "barbell" is not in the equipment list`.
- The schemas are loaded from the resources above, so the files are the single source of truth.

**Fixtures.** Add recorded-style coach replies under `app/src/test/resources/fixtures/`:
- valid: an outline, a lift plan, a run plan, and a valid plan wrapped in one code fence
- invalid: bad JSON, prose around the JSON, a schema violation, unknown equipment, an inactive equipment key, an outline that doesn't match the pattern, both reps and seconds on one set, an over-length session, duplicate exercise names, and a plan type that doesn't match the sortie

Must not break: everything already built and its tests, and the build gate. `coach/` must have no Android imports; a test already guards this, so keep it passing. Follow CLAUDE.md conventions and use snake_case JSON fields.

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid class gives its specific message, with the path in it.
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- ARCHITECTURE.md's `coach` row lists the validator and schemas. M0 says the workspace template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. The generation worker in a later brief calls this.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data` (missions, sorties, Flight Plans with planned and actual sets, active equipment, exercises)

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'
2. Instructions, worded as in §7.4
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km)
4. Equipment: every active item as `key — name — notes`, and the line that exercises may use only these ids, with bodyweight needing none
5. This week: the pattern with each sortie's index, type and state; the outline focus of each sortie when it exists; and for a Flight Plan, which sortie is being planned
6. History: landed and scrubbed sorties within the history window counted back from a given date, newest first, one block per sortie in the §7.4 format (date, type, title, landed or scrubbed; per exercise the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, seconds as `3×45 s ✓`; notes). Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates
8. Output schema: the schema text for this kind, verbatim from the resource file

Also provide the repair prompt: 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', with the validator's errors listed.

**Gathering inputs.** Add a Room-and-settings-backed loader outside the pure package. It builds the builder's input types for a given Mission (and sortie, for a Flight Plan) and a 'today' date, leaving out inactive equipment. The window, date and zone must be injectable for tests.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Must not break: the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Golden-file tests cover an outline prompt and a lift Flight Plan prompt with an outline, and a run Flight Plan prompt; building twice gives identical text.
- Tests cover the history formatting (deviation, skipped, seconds, bodyweight, notes, a scrubbed sortie), the 28-day window edge (day 28 in, day 29 out), a custom window, newest-first order, the sorted and de-duplicated exercise names, and inactive equipment being left out.
- A Robolectric test shows the loader builds the expected inputs from an in-memory database.
- ARCHITECTURE.md's `coach` row lists the prompt builder.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, and ARCHITECTURE.md invariants 3 and 4.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs (`protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports, such as `RhoJson` and `PROTOCOL_VERSION`) into `app/src/main/java/com/liftoff/app/rho/`.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording the source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- A 'Test connection' action in Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message:
  - unreachable or timed out ('Mission Control offline — check the PC and Tailscale')
  - authentication rejected
  - empty host or token
  - protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, and gives up after a reasonable timeout.
- The connection is closed afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built; no DI framework.

Must not break: the settings and equipment editing in Mission Control, export/import, the other screens, and the build gate. Follow CLAUDE.md conventions.

Done when:
- The copied files compile under `com.liftoff.app.rho` with their source headers.
- Tests against a fake daemon (an OkHttp MockWebServer WebSocket scripting `rho.hello`, in the style of rho's own client tests) cover: success showing both versions, a major-version mismatch, a refused connection, and missing settings.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. GenerationWorker against a fake rho daemon

Build the WorkManager worker that runs one coach generation through rho. Read DESIGN.md §7.2, §7.3, §7.6, §10 and §13 ('Integration tests'), the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, resume, stop), and ARCHITECTURE.md invariants 3, 6, 7 and 8 first. A later brief wires generations into confirm, landing and the Launchpad. This brief builds the worker, enqueueing, storing results and tests.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao` (kind, missionId, sortieId, status QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED, nabuSessionId, lastEventId, attempt, error, createdAt, finishedAt)
- `FlightPlanDao.writePlan` with `FlightPlanDraft` (source GENERATED), and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3 step by step:
- connect and say hello. A major-version mismatch is a permanent failure with the §10 message.
- create the rho session only if the row has none yet:
  - workspace = the coach path from settings
  - permission_mode 'auto'
  - labels `["liftoff", "liftoff:outline"]` or `["liftoff", "liftoff:flight-plan"]`, never starting with `run:`
  - a one-line description
  - store the session id before anything else
- send the prompt with `client_id = "<generationId>-<attempt>"`
- subscribe and catch up from the stored `lastEventId`, saving the cursor as events arrive
- the turn ends at the first state_change after our message to idle, completed, blocked, paused or error, with a 20-minute limit
- idle or completed: validate the last assistant message
  - valid: store the result and mark SUCCEEDED
  - invalid: up to 2 repair prompts in the same session, incrementing attempt
  - still invalid after that: FAILED, 'invalid output', with the error list kept in `error`
- blocked: FAILED, 'coach needed input'
- answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, and keep waiting
- paused or error: retry, resuming a paused session first
- unreachable daemon: retry, and record on the row that the coach is offline so the UI can say 'Mission Control offline'. After 8 attempts: FAILED, 'coach unreachable'.
- stop the rho session on SUCCEEDED or a permanent failure
- the row is RUNNING while the worker runs

**Storing results**, in one Room transaction, and only if the generation is still current (not CANCELLED, and the newest non-cancelled generation for its sortie or Mission):
- OUTLINE: activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale, store the outline notes, and make sortie 1 current through the existing advance and prepare step. A run with run generation off still gets its simple 'Run' plan.
- FLIGHT_PLAN: write the lift or run plan (title, estimated minutes, warmup, notes, exercises with equipment ids, rest and sets; or run kind, target distance and pace, and segments) with source GENERATED and `rawJson` set to the validated JSON, and set the sortie PLANNED if it was PENDING.

**Enqueue and cancel helpers.**
- enqueue: unique work `gen-<generationId>`, policy KEEP, network-connected constraint, exponential backoff starting at 1 minute
- cancel: cancels the work, stops the rho session if there is one, and marks the row CANCELLED
- expose both through `AppContainer`

Don't change the Room schema unless it's unavoidable. If it is, add a real migration, never a destructive one.

Must not break: the coach validator and prompt builder tests, MissionManager and the domain tests, every screen, and the build gate. The worker lives in `work`, not in `coach`, which stays free of Android imports. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for each §13 case:
  - the happy path for an outline and for a lift Flight Plan
  - invalid, then repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - a major-version mismatch failing permanently
  - an unreachable daemon retrying and then failing as 'coach unreachable'
- A test shows that a result for a cancelled or superseded generation is not stored.
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Wire coach generation into Missions and the Launchpad

Turn coach generation on across the app: outlines on confirm, Flight Plans as each sortie becomes current, Regenerate, the generated run plans toggle, and the generation status on the Launchpad. Read DESIGN.md §4.2, §5.3, §5.4, §7.2, §7.3 ('Superseded results'), §9 ('Launchpad') and §10 first.

Build on what's already in the repo:
- the `GenerationWorker`, its enqueue and cancel helpers, and its result storing in `com.liftoff.app.work`, reached through `AppContainer`
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path, and `advance` has an `AwaitGeneration` branch in `domain/SortiePlanning.kt` that does nothing yet.
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`), which has Draft, Planned, Pending, InFlight and Closed states and Re-fly on PENDING
- the 'Generate run plans' setting

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- While it runs, the draft shows 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has recorded that the coach is unreachable.
- On failure it shows the reason, with Retry and 'Continue without outline'. 'Continue without outline' keeps today's behaviour and cancels any outline generation still running.
- If no daemon host or token is set, say so and offer 'Continue without outline' rather than queueing a generation that can't succeed.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, the next sortie after landing, and the next sortie after a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment; changing it never alters an existing plan.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

**Launchpad.**
- **PLANNED**: an underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`. The current plan stays visible and launchable until the new one replaces it, with a short 'Coach is re-planning…' line meanwhile.
- **PENDING** shows the generation status:
  - 'Coach is planning…'
  - 'Mission Control offline — retrying'
  - a failure with its reason and the validator's error list when there is one
  - Retry, Re-fly (now offered only when the generation failed, the coach is offline, or no connection is configured, per §5.4), and Scrub
  - 'View in rho', which shows the rho session id so it can be opened in rho's app
- Status updates live from Room.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration, never a destructive one.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed and Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation; with it off, it gets a simple 'Run' plan
  - Regenerate supersedes a running generation (its work is cancelled, its rho session stopped against the fake daemon, and its late result is ignored)
  - Re-fly and scrub cancel an open generation
  - each Launchpad status (planning, offline, failed with errors, re-planning) is derived correctly, with Re-fly shown only when §5.4 allows
- ARCHITECTURE.md marks M2, M3, M5 and M6 done. M0 says what exists and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged so far: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, and the Launchpad and Mission screens. The round-4 In-Flight checklist run stopped with its session paused. It only committed its brief, plan, verify.sh and tasks; no code was written, and InFlightScreen.kt is still the 'The In-Flight checklist comes later' placeholder. Because of that, nothing else in round 4 started. Still missing: set logging, notes, Land, scrub from In-Flight, run landing and resume-after-kill; the Landed history and Re-fly; export/import; Test connection; the whole of coach generation (coach/ template, schemas, validator, prompt builder, copied DaemonClient, GenerationWorker, wiring, Regenerate, the planning and offline statuses, generated run plans); and the ARCHITECTURE.md milestones M0 and M3–M6. Two small Launchpad drifts also remain: a stock M3 text field in the scrub dialog and duplicate −/+ controls on the draft. This round makes every run smaller. The In-Flight checklist is split into state and actions with no UI, then the screen. Land/notes/scrub and runs/reopen become separate runs. Landed and Re-fly are split, and so is the validator. The generation worker is split into its core flow and its resilience. The wiring is split into queueing and the Launchpad UI.

## Round 5

### 1. In-Flight checklist state and actions (no UI)

Build the logic behind the In-Flight checklist, where the owner logs a lift sortie at the gym. This brief has no UI work. The next brief builds the screen on top of it. Read DESIGN.md §2, §5.2 and §6, and the 'In-Flight' section of `design/README.md`, first.

An earlier run that tried to build this logic and the whole screen together stopped before writing any code. Build only what is listed here, and commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- `FlightPlanDao` in `com.liftoff.app.data`:
  - `getPlan(sortieId)`: exercises with display names, sets and segments, all in stored order
  - `observeChanges()`
  - `updateSetActuals(setId, actualReps, actualSeconds, actualWeight, status)`
  - `addExtraSet(plannedExerciseId, reps, seconds, weight)`, which appends the set and marks it added
  - `updateExercise(plannedExerciseId, skipped, userNotes)`
- `SortieDao` and `SettingsStore` (weight unit), reached through `AppContainer`
- `formatWeight` in `ui/sortie/PlanFormat.kt`. It may be made `internal` for reuse, with no change to its behaviour.
- the plain state-holder pattern that `LaunchpadViewModel` / `LaunchpadState` and `MissionViewModel` / `MissionState` use in `com.liftoff.app.ui`. This is not an AndroidX ViewModel.

**State (in `com.liftoff.app.ui.inflight`).** A flow of the In-Flight state for one sortie, rebuilt from Room on every change, and a pure derivation function that tests can call on a fresh `getPlan` read. It provides:
- the eyebrow 'IN FLIGHT · SORTIE n' (n is 1-based), and the title: the plan title, else the sortie focus, else 'Sortie n'
- a set counter of DONE sets over all sets, added sets included. Skipped sets count as finished for progress but not as done.
- one progress segment per set in display order: done, skipped or open
- one card per exercise with a status:
  - skipped, when the exercise is skipped
  - done, when every set is done or skipped
  - active, for the first exercise that still has an open set
  - upcoming, for the rest
  Each card also carries its done count and set count.
- per set: 'SET n', the planned and actual values, a label for weight ('35 LB' or '20 KG' in the configured unit, or 'BW' when there is no weight) and a label for count ('× 10' for reps, '45 s' for seconds), whether it was added, and whether the weight or count deviates from the plan (DONE sets only)
- the current set: the first open set of the active card
- a 'no checklist' state for a sortie with no plan or no exercises (run sorties)

**Warning.** `observeChanges()` returns a row count that doesn't change on UPDATE. Room still re-emits it on every write to those tables, and that re-emission is what makes a check-off visible. Don't put `distinctUntilChanged` on it. Deduplicating the derived state is fine.

**Actions.** Each one writes to Room immediately and caches nothing:
- check a set: DONE, with actuals equal to the planned values
- uncheck a set: OPEN, with actuals cleared
- save an edit: DONE with the given actuals. Planned values stay unchanged.
- skip a set, and reopen a skipped set
- skip an exercise: set the flag and mark its open sets skipped. Done sets keep their actuals. This is one transaction.
- unskip an exercise: clear the flag and reopen the sets that were skipped. This is one transaction.
- add a set: pre-filled from the exercise's last set, using its actuals if that set is done and its planned values otherwise

A plain state holder exposes the state as a `StateFlow` and runs the actions on a coroutine scope. The screen in the next brief will use it.

Don't change the Room schema. `SchemaExportTest` must keep passing. Lift plans can't be generated yet, so tests seed plans through `writePlan`.

Must not break: the placeholder `InFlightScreen` and `InFlightScreenTest`, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Robolectric tests against an in-memory database, seeded with a LIFT sortie of three exercises (weighted, bodyweight and mixed), cover:
  - the initial card states, counter, labels and current set
  - one-tap check and uncheck
  - an edit with a deviation, where the actual differs and the planned value is kept
  - finishing an exercise, so it becomes done and the next one becomes active
  - skipping and reopening a set
  - skipping and unskipping an exercise
  - adding a set, including that it turns a done card active again
  - the state from a fresh database read being identical to the state the flow emitted after a mix of actions
  - no plan giving the 'no checklist' state
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. In-Flight checklist screen

Replace the placeholder In-Flight screen with the real checklist UI. Read DESIGN.md §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first. The HTML is a static mockup where 1 px = 1 dp.

Build on what's already in the repo:
- the In-Flight state, its derivation, its actions and its plain state holder in `com.liftoff.app.ui.inflight`, added by the previous brief. Use them; don't re-derive state in the UI.
- `InFlightScreen(sortieId, container, onBack)`, which Launch and Resume on the Launchpad already open. Keep its signature.
- the theme in `com.liftoff.app.ui.theme`:
  - color tokens (Teal, TealLight, Mustard, Red, Sand, Paper, Ink, Cream, Muted, Rule, White)
  - `DuoStripe`, the offset-shadow helpers, `LiftoffType`, `LiftoffIcons` (including check), `UnderlinedTextButton` and `PrimaryButton`

**Header.**
- A teal header with the mustard eyebrow, the title in cream, and the counter on the right ('05' large in mustard, '/14' in cream, 'SETS' under it).
- Under it, a progress bar with one segment per set:
  - mustard when done
  - outlined teal_light when open
  - filled teal_light when skipped
- Then the mustard and red bands.

**Cards.**
- **Done:** a sand row with a 2 dp ink border, the name struck through, and 'n/n LANDED' in teal.
- **Skipped:** the same row, saying 'SKIPPED'.
- **Active:** a paper card with a 2 dp ink border, an ink header bar (a mustard index and the uppercase name in cream) and the ink offset shadow.
- **Upcoming:** the same card without the shadow.
- Tapping a collapsed done or skipped row expands it into a full card, so a mis-tap can be fixed. Tapping its header collapses it again.
- The footer of an active or upcoming card has '+ Add set · Note · Skip' (Unskip on an expanded skipped card). Note is shown disabled; the next brief makes it work.

**Set rows (58 dp).**
- Each row shows 'SET n' (red for the current set, muted otherwise), the weight, the reps or seconds, and a 48 dp check box with a 2 dp ink border and 4 dp corners.
- The box is white when open, red with a white check when done, and shows a muted dash when skipped.
- One tap on the box checks the set. Tapping a done box unchecks it, and tapping a skipped box reopens it.
- A deviating value shows the actual in red with a small muted 'of <planned>'.
- Tapping the numbers, or a long press on the row, opens a stepper in the theme's style. Use a plain Dialog with custom content, not AlertDialog or a bottom sheet. It has:
  - − and + controls for weight (±5 lb or ±2.5 kg; 0 means bodyweight) and for reps (±1) or seconds (±5)
  - Save, which marks the set done with those values
  - Skip set
  - Cancel

**Bottom area.** A 3 dp ink rule at the top of a cream footer that stays in view. It holds a Back text button for now. The Land button replaces it in the next brief, so don't show a Land button that does nothing.

A sortie with no checklist shows the header without the counter, with a short muted line.

The UI must not cache state. Every tap goes through the actions and the screen re-renders from Room, so after a process kill, reopening the route shows exactly what was left. Nothing uses the network.

Must not break: the state and action tests from the previous brief, the Launchpad and Mission screens, Mission Control, the shell, and the build gate. `InFlightScreenTest` must keep passing; update it only where the placeholder text is gone. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- For a seeded lift plan, the screen shows the header, progress and cards in done, active and upcoming states as in the mockup.
- A Robolectric Compose test on a seeded in-memory or test database shows that tapping a check box marks the set done in Room and updates the counter.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Notes, Land and In-Flight scrub

Finish the lift In-Flight flow: notes, landing and scrubbing from In-Flight. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first.

Build on what's already in the repo:
- the In-Flight checklist screen, state and actions in `com.liftoff.app.ui.inflight`. The card footer has a disabled 'Note' cell, and the bottom area holds a Back button where Land goes.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie, prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets
- `SortieDao` (the Sortie row has `notes`) and `FlightPlanDao.updateExercise` (exercise `userNotes`)
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons.flag`, and the text-field style used in Mission Control
- the shell's navigation state in `com.liftoff.app.ui` (`ShellNav`, `LiftoffShell`)

**Notes.**
- Each exercise card's 'Note' opens an optional free-text note for that exercise.
- There is also one note for the whole sortie, reachable from the In-Flight screen.
- Both are saved to Room as they are entered, and come back after a process kill.
- Use the Mission Control text-field style, not stock M3 visuals.

**Land.** A 64 dp ink Land button with the flag glyph and a red offset shadow, as in the mockup, replacing the Back button in the bottom area.
- If any sets are unchecked, ask for confirmation first, saying how many. Unchecked sets are recorded as not done.
- Landing goes through `MissionManager.land`, then returns to the Launchpad. The Launchpad then shows the next sortie, or the closed state after the last one.

**Scrub from In-Flight.**
- An underlined Scrub action with a confirmation and an optional reason, in the themed text-field style.
- It keeps the sets already checked, then returns to the Launchpad.
- Keep a way back to the Launchpad that doesn't land, such as system back. The Launchpad keeps offering Resume.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist and its tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight lift sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - exercise and sortie notes persisting
  - landing with and without unchecked sets, with the unchecked ones recorded as not done
  - the next sortie becoming current, and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Run sorties in flight, reopening, and Launchpad polish

Make In-Flight work for run sorties, reopen the app into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §5.3, §6 and §9 and `design/README.md` first.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, Land (through `MissionManager.land`) and Scrub. Today a run sortie shows a 'no checklist' state.
- the Sortie row's `runDistance` and `runMinutes`, and the settings distance unit (mi or km)
- the simple 'Run' Flight Plan (source SIMPLE_RUN) that `MissionManager` creates for runs
- the shell in `com.liftoff.app.ui` (`ShellNav`, `LiftoffShell`, MainActivity)
- `MissionManager.onAppOpen()`, which the app runs on open and on return to the foreground
- the Launchpad in `com.liftoff.app.ui.launchpad`

**Run sorties.**
- A run's In-Flight screen shows its plan in the design style: title, focus or notes, run kind, target distance and pace, and segments when present. There are no set rows.
- Land offers optional distance and duration fields, with distance in the configured unit, and stores them on the sortie. Both may be left blank, and are then stored as empty.
- There is no unchecked-sets confirmation for runs.
- Scrub works as it does for lifts.

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start of the app opens straight into its In-Flight screen.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the week ends while a sortie is in flight, the existing rollover scrubs it with 'week ended' when the app comes back to the foreground. The In-Flight screen must then return to the Launchpad instead of acting on a sortie that is no longer in flight.

**Launchpad polish.**
- The Launchpad scrub dialog uses a stock M3 OutlinedTextField with a floating label. Use the same themed reason field as the In-Flight scrub.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7.

Put the logic in plain Kotlin that a Robolectric test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight and its tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Launch → Land works for a simple run.
- Tests cover:
  - run distance and duration being saved, and being saved as empty when left blank
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is no longer IN_FLIGHT
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed isn't mocked up. Build it from the design's parts:
- eyebrow and display title
- section heads
- ink-ruled rows
- paper cards with 2 dp ink borders
- red deviations, as on In-Flight

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder today), and the shell
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first), `MissionDao`, and `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags and notes
- the plain state-holder pattern used by the Launchpad and Mission screens, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed text-field style used in Mission Control and In-Flight

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens it; back returns to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are in red, and skipped and not-done sets are marked. Show added sets, exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- The Landed tab lists history newest first, grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Re-fly

Add Re-fly (DESIGN.md §5.4): reusing a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`. It has no Android imports (ARCHITECTURE.md invariant 2).
- `MissionManager` in `com.liftoff.app.data`, with one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (LaunchpadState, LaunchpadViewModel, LaunchpadScreen)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to the same type only
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction:
- copy planned values only, never actuals
- don't copy added sets
- clear skipped flags and user notes
- copy run fields and segments for a run
- the copy's source is REFLY, and the sortie becomes PLANNED
- it works with no network
- it refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show a Re-fly action next to Scrub when a source plan exists. When none exists, say so instead of showing a dead button. Coach generation doesn't exist yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Pure unit tests cover the §13 Re-fly cases:
  - it prefers the same type and focus
  - it falls back to the same type only
  - it picks the most recent plan
  - it ignores scrubbed sorties
  - it returns nothing when there is no landed plan of that type
- Robolectric tests show that the copy has planned values and no actuals, no added sets, source REFLY, and leaves the sortie PLANNED; that it needs no network; and that the Launchpad PENDING state shows Re-fly only when a source exists.
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) with its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, defaulting to a name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- Settings and the daemon token are not part of the export, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file and loads it into the database in one transaction, keeping ids so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty, and that draft is replaced.
- Anything else is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread and report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: the settings and equipment editing in Mission Control, the data layer and its tests, the other screens, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Export, then import into a new in-memory database, gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover refusing a non-empty database, accepting one that holds only a fresh draft, and refusing a malformed or wrong-version file, each with the database left unchanged.
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Coach workspace template, schemas and schema checker

Create the coach workspace template, the output schemas, and a small JSON Schema checker. Read DESIGN.md §7.1, §7.1.1, §7.5, §11, §12 and §15 (decision 14) first. Nothing in the UI changes. The next brief adds the semantic validator and the fixtures on top of this.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md`: the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; it is never part of this repo
  - turn off rho's verify gate for that path in `~/.rho/config.json`, with the exact snippet from §7.1
  - note the daemon host, port (default 8737) and token for Mission Control
- `coach/COACH.md`: the coach's standing instructions, with every point §7.1.1 says it MUST contain:
  - its role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schemas.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, with the JSON Schemas from §7.5.1–§7.5.3 verbatim. Make sure they are on the unit-test classpath.

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2). A test already guards this.
- An in-house checker for exactly the subset §7.5 uses: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems. Don't add a JSON Schema library.
- Parse with kotlinx.serialization.
- Load the schemas from the resource files, so the files are the single source of truth.
- It returns a list of errors, and each one names its JSON path and its problem specifically, because errors are later sent back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: "jog" is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property "day"`.

Must not break: everything already built and its tests, and the build gate. Follow CLAUDE.md conventions and use snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas, with valid documents passing and with at least one violation of each keyword in the subset, each giving its path-specific message.
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- ARCHITECTURE.md's `coach` row lists the schemas and schema checker. M0 says the workspace template and schemas exist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Coach reply validator and fixtures

Build the phone-side validation of coach replies (DESIGN.md §7.6). Read DESIGN.md §7.5, §7.6 and §13 ('Validator') first. No UI changes. Later briefs add the prompt builder and the generation worker, which call this validator.

Build on what's already in the repo:
- the schema files in `app/src/main/resources/schemas/`
- the in-house JSON Schema checker in `com.liftoff.app.coach`
- the `coach/` workspace template

**Validator (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports.
- A pure function from (raw reply text, generation context) to either a parsed result in plain coach types (an outline, a lift plan or a run plan) or a list of human-readable errors.
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

Every error names its path and problem, for example `exercises[2].equipment: "barbell" is not in the equipment list`.

**Fixtures.** Add recorded-style coach replies under `app/src/test/resources/fixtures/`:
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

Must not break: the schema checker and its tests, everything already built, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid class gives its specific message, with the path in it.
- ARCHITECTURE.md's `coach` row lists the validator and fixtures. M0 says the template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. The generation worker in a later brief calls this.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data`: missions, sorties, Flight Plans with planned and actual sets, active equipment, and exercises

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'.
2. Instructions, worded as in §7.4.
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km).
4. Equipment: every active item as `key — name — notes`, plus the line saying exercises may use only these ids and bodyweight needs none.
5. This week:
   - the pattern, with each sortie's index, type and state
   - the outline focus of each sortie, when an outline exists
   - for a Flight Plan, which sortie is being planned
6. History: landed and scrubbed sorties within the history window counted back from a given date, newest first, one block per sortie in the §7.4 format:
   - the date, type, title, and whether it landed or was scrubbed
   - per exercise, the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, with seconds as `3×45 s ✓`
   - the notes
   Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates.
8. Output schema: the schema text for this kind, verbatim from the resource file.

Also provide the repair prompt, 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', with the validator's errors listed.

**Gathering inputs.** Add a loader, backed by Room and settings, outside the pure package. It builds the builder's input types for a given Mission (and sortie, for a Flight Plan) and a 'today' date, leaving out inactive equipment. The window, date and zone must be injectable for tests.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Must not break: the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Golden-file tests cover an outline prompt, a lift Flight Plan prompt with an outline, and a run Flight Plan prompt, and building twice gives identical text.
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

### 11. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, and ARCHITECTURE.md invariants 3 and 4.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording the source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles. Add a dependency only if the copied code needs one that isn't already declared, and match rho's version.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- A 'Test connection' action in Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: the settings and equipment editing in Mission Control, export/import, the other screens, and the build gate. Follow CLAUDE.md conventions.

Done when:
- The copied files compile under `com.liftoff.app.rho` with their source headers.
- Tests against a fake daemon (an OkHttp MockWebServer WebSocket scripting `rho.hello`, in the style of rho's own client tests) cover success showing both versions, a major-version mismatch, a refused connection, and missing settings.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through rho, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10, the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop), and ARCHITECTURE.md invariants 3, 6, 7 and 8 first.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, unreachable retries, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId, lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row has none yet, and store the session id before anything else. The session uses:
  - workspace: the coach path from settings
  - permission_mode 'auto'
  - labels `["liftoff", "liftoff:outline"]` or `["liftoff", "liftoff:flight-plan"]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = "<generationId>-<attempt>"`.
- Subscribe, saving `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error, with a 20-minute limit.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or a permanent failure.

**Storing results.** One Room transaction:
- **OUTLINE:**
  - activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale
  - store the outline notes
  - make sortie 1 current through the existing advance-and-prepare step; a run with run generation off still gets its simple 'Run' plan
- **FLIGHT_PLAN:**
  - write the lift plan (title, estimated minutes, warmup, notes, exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments)
  - source GENERATED, with `rawJson` set to the validated JSON
  - set the sortie PLANNED if it was PENDING

**Enqueue helper.** Unique work `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one. The worker lives in `work`, not in `coach`, which stays free of Android imports.

Must not break: the coach validator and prompt builder tests, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - invalid, then repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. Read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'), and the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop), first.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = "<generationId>-<attempt>"`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- `Generation` and `GenerationDao`, and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current: not CANCELLED, and the newest non-cancelled generation for its sortie (for a Flight Plan) or Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. Queue coach generations from Missions and sorties

Turn coach generation on in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. The Launchpad's status display and Regenerate come in the next brief; this brief changes the flow and keeps the UI working.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path, and `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. Report that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning, and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, the next sortie after landing, and the next sortie after a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation; with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and its late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm queues an outline; continue without outline; retry; regenerate; Re-fly; scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and nabuSessionId
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure: the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set: say so, and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** The generation status:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so it can be opened in rho's app

Status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show each Launchpad status is derived correctly from the database:
  - outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, the Launchpad and Mission screens, and now the In-Flight checklist logic and screen (check, uncheck, edit with deviation, skip and unskip sets and exercises, add sets). The round-5 'Notes, Land and In-Flight scrub' run ended blocked without committing anything, so nothing after it started. Still missing: the exercise 'Note' cell is disabled and there is no sortie note. The In-Flight footer only has Back: there is no Land button, no unchecked-sets confirmation and no scrub from In-Flight. Run sorties show 'No checklist' and can't record distance or duration. A cold start doesn't reopen an in-flight sortie. Landed is still a placeholder, and Re-fly, export/import and Test connection don't exist. All of coach generation is missing: the coach/ template, schemas, schema checker, validator, prompt builder, the copied DaemonClient, GenerationWorker, queueing, Regenerate, the planning and offline statuses, and generated run plans. ARCHITECTURE.md marks M0 and M3–M6 not started. Two Launchpad drifts remain: a stock M3 text field in the scrub dialog, and duplicate −/+ controls on the draft. This round splits the failed run in two (notes; then Land and scrub). It also splits run In-Flight from reopening and Launchpad polish, then carries on with Landed, Re-fly, export/import and the generation work. Every brief now says to decide and record unclear points instead of stopping to ask.

## Round 6

### 1. In-Flight exercise and sortie notes

Add notes to the In-Flight screen: an optional note per exercise and one note for the whole sortie (DESIGN.md §6, §8). Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` first. This brief is deliberately small. An earlier run that tried to build notes, Land and scrub together stopped before writing any code. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state and actions in `com.liftoff.app.ui.inflight`: `InFlightScreen`, `InFlightState`/`InFlightStates` (state derived from Room on every change) and `InFlightViewModel` (a plain state holder whose actions write straight to Room). Each card footer has '+ Add set · Note · Skip', and the Note cell is disabled today.
- `FlightPlanDao.updateExercise(plannedExerciseId, skipped, userNotes)` and the `userNotes` column on plannedExercise
- `SortieDao` and the Sortie row's `notes` column
- the themed dialog style the In-Flight stepper already uses (a plain Dialog with a paper card and a 2 dp ink border, not AlertDialog), and the text-field style in Mission Control (`LabeledMultiLineTextField` and friends in `com.liftoff.app.ui.control`, which are `internal`)

**Exercise note.**
- The card footer's Note cell becomes active. It opens a themed dialog with a multi-line field, pre-filled with the current note, and Save and Cancel.
- Save writes the note to Room straight away and keeps the exercise's skipped flag and its sets as they are. A blank note is stored as null. Cancel changes nothing.
- A card with a note shows it as a short muted line, so the owner can see it is there.

**Sortie note.**
- One note for the whole sortie, opened from an underlined 'Sortie note' text action on the In-Flight screen. Put it where it won't crowd the bottom area, because the next brief puts the Land button there. It works the same way: pre-filled, Save and Cancel, blank stored as null.
- It also works on a sortie with no checklist.

The In-Flight state carries each card's note and the sortie note, derived from Room like everything else, so after a process kill the notes come back. Nothing uses the network. Don't change the Room schema; `SchemaExportTest` must keep passing. A new DAO query that updates only the sortie's notes is fine.

Must not break: the checklist and its tests (`InFlightStatesTest`, `InFlightViewModelTest`, `InFlightScreenTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Robolectric tests against an in-memory database cover:
  - saving an exercise note, which then shows up in the state rebuilt from a fresh database read
  - a note on a skipped exercise keeping the skipped flag and the sets unchanged
  - clearing a note to blank, which stores null
  - saving the sortie note, including on a sortie with no plan
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight` (`InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`). The bottom area is a 3 dp ink rule over a cream footer that holds only a Back text button today.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper uses, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`): `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area, as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog, saying how many (for example '3 sets unchecked. They will be recorded as not done.'). With none open, land straight away.
- Landing goes through `MissionManager.land`, then returns to the Launchpad, which then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason, using the Mission Control text-field style rather than a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank the same way.

**Robustness.** A double tap must not land twice or crash. If `MissionManager` rejects an operation (for example the sortie is no longer in flight), the screen shows a short message instead of crashing. Expose the number of open sets from the state, so the confirmation logic can be tested without Compose.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count driving whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Run sorties in flight

Make the In-Flight screen work for run sorties. Read DESIGN.md §5.3 and §6 (the run paragraph), §8 (Sortie and RunSegment) and `design/README.md` first. Build only what is listed here. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, the Land button (through `MissionManager.land`), the unchecked-sets confirmation and Scrub. Today a run sortie shows a 'No checklist for this sortie.' state and lands with no extra input.
- the Flight Plan for a run: the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates, and the plan's run fields (run kind, target distance, target pace) and `RunSegment` rows, all read through `FlightPlanDao.getPlan`
- the Sortie row's `runDistance` and `runMinutes`, and the settings distance unit (mi or km)
- the formatting helpers in `com.liftoff.app.ui.sortie`, which the Launchpad already uses for its run summary

**Run In-Flight.**
- A run's In-Flight screen keeps the teal header and bands. Its body shows the plan in the design style: title, focus or plan notes, run kind, target distance and pace in the configured unit, and segments when present (ink-ruled rows). There are no set rows and no set counter. A run whose plan is only the simple 'Run' plan shows that simply, without empty fields.
- Land opens a themed dialog with two optional fields: distance in the configured unit and duration in minutes. Use the Mission Control text-field style. Store them on the sortie as entered, in the unit configured at the time, in the same transaction as landing. Either may be left blank and is then stored as null. A value that isn't a positive number shows an inline error, and nothing is landed.
- There is no unchecked-sets confirmation for runs.
- Scrub works as it does for lifts.
- A sortie with no plan at all still lands without the dialog.

Put the logic in plain Kotlin that a Robolectric test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight with its notes, Land and Scrub and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land works for a simple run.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, and from a simple 'Run' plan
  - distance and duration saved on landing
  - both left blank and saved as null
  - an invalid value being rejected with the sortie still IN_FLIGHT
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence'), §9 and `design/README.md` first. Build only what is listed here. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity) and `MissionManager.onAppOpen()`, which the app runs on open and on return to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with lift and run flows, notes, Land, Scrub and the themed scrub dialog with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start of the app (no saved navigation state) opens straight into its In-Flight screen. Otherwise it opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad rather than acting on it. This covers the week-ended rollover when the app comes back to the foreground, and landing or scrubbing it elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the same themed scrub dialog and reason field In-Flight uses. A blank reason is stored as null.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination and leave-when-not-in-flight logic in plain Kotlin that a test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is no longer IN_FLIGHT
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed isn't mocked up. Build it from the design's parts:
- eyebrow and display title
- section heads
- ink-ruled rows
- paper cards with 2 dp ink borders
- red deviations, as on In-Flight
If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first), `MissionDao`, and `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes. The Sortie row has notes, scrubReason, runDistance and runMinutes.
- the plain state-holder pattern used by the Launchpad, Mission and In-Flight screens, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed dialog and text-field style used in In-Flight and Mission Control

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens it, and back (the system back and an on-screen back) returns to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are in red, skipped and not-done sets are marked, and added sets are marked. Show exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first, grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Re-fly

Add Re-fly (DESIGN.md §5.4): reusing a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, with one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (LaunchpadState, LaunchpadViewModel, LaunchpadScreen)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to the same type only
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction:
- copy planned values only, never actuals
- don't copy added sets
- clear skipped flags and user notes
- copy run fields and segments for a run
- the copy's source is REFLY, and the sortie becomes PLANNED
- it works with no network
- it refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation doesn't exist yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases:
  - it prefers the same type and focus
  - it falls back to the same type only
  - it picks the most recent plan
  - it ignores scrubbed sorties
  - it returns nothing when there is no landed plan of that type
- Robolectric tests show that the copy has planned values and no actuals, no added sets, source REFLY, and leaves the sortie PLANNED; that it needs no network; that it refuses a non-current or non-PENDING sortie; and that the Launchpad PENDING state shows Re-fly only when a source exists.
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) with its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, defaulting to a name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- Settings and the daemon token are not part of the export, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file and loads it into the database in one transaction, keeping ids so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty, and that draft is replaced.
- Anything else is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread and report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: the settings and equipment editing in Mission Control, the data layer and its tests, the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Export, then import into a new in-memory database, gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover refusing a non-empty database, accepting one that holds only a fresh draft, and refusing a malformed or wrong-version file, each with the database left unchanged.
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Coach workspace template, schemas and schema checker

Create the coach workspace template, the output schemas, and a small JSON Schema checker. Read DESIGN.md §7.1, §7.1.1, §7.5, §11, §12 and §15 (decision 14) first. Nothing in the UI changes. The next brief adds the semantic validator and the fixtures on top of this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md`: the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; it is never part of this repo
  - turn off rho's verify gate for that path in `~/.rho/config.json`, with the exact snippet from §7.1
  - note the daemon host, port (default 8737) and token for Mission Control
- `coach/COACH.md`: the coach's standing instructions, with every point §7.1.1 says it MUST contain:
  - its role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schemas.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, with the JSON Schemas from §7.5.1–§7.5.3 verbatim. Make sure they are on the unit-test classpath.

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2). A test already guards this.
- An in-house checker for exactly the subset §7.5 uses: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems. Don't add a JSON Schema library.
- Parse with kotlinx.serialization.
- Load the schemas from the resource files, so the files are the single source of truth.
- It returns a list of errors, and each one names its JSON path and its problem specifically, because errors are later sent back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: \"jog\" is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property \"day\"`.

Must not break: everything already built and its tests, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only) and use snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas, with valid documents passing and with at least one violation of each keyword in the subset, each giving its path-specific message.
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- ARCHITECTURE.md's `coach` row lists the schemas and schema checker. M0 says the workspace template and schemas exist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Coach reply validator and fixtures

Build the phone-side validation of coach replies (DESIGN.md §7.6). Read DESIGN.md §7.5, §7.6 and §13 ('Validator') first. No UI changes. Later briefs add the prompt builder and the generation worker, which call this validator. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the schema files in `app/src/main/resources/schemas/`
- the in-house JSON Schema checker in `com.liftoff.app.coach`
- the `coach/` workspace template

**Validator (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports.
- A pure function from (raw reply text, generation context) to either a parsed result in plain coach types (an outline, a lift plan or a run plan) or a list of human-readable errors.
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

Every error names its path and problem, for example `exercises[2].equipment: barbell is not in the equipment list`.

**Fixtures.** Add recorded-style coach replies under `app/src/test/resources/fixtures/`:
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

Must not break: the schema checker and its tests, everything already built, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid class gives its specific message, with the path in it.
- ARCHITECTURE.md's `coach` row lists the validator and fixtures. M0 says the template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. The generation worker in a later brief calls this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data`: missions, sorties, Flight Plans with planned and actual sets, active equipment, and exercises

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'.
2. Instructions, worded as in §7.4.
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km).
4. Equipment: every active item as `key — name — notes`, plus the line saying exercises may use only these ids and bodyweight needs none.
5. This week:
   - the pattern, with each sortie's index, type and state
   - the outline focus of each sortie, when an outline exists
   - for a Flight Plan, which sortie is being planned
6. History: landed and scrubbed sorties within the history window counted back from a given date, newest first, one block per sortie in the §7.4 format:
   - the date, type, title, and whether it landed or was scrubbed
   - per exercise, the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, with seconds as `3×45 s ✓`
   - the notes
   Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates.
8. Output schema: the schema text for this kind, verbatim from the resource file.

Also provide the repair prompt, 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', with the validator's errors listed.

**Gathering inputs.** Add a loader, backed by Room and settings, outside the pure package. It builds the builder's input types for a given Mission (and sortie, for a Flight Plan) and a 'today' date, leaving out inactive equipment. The window, date and zone must be injectable for tests.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Must not break: the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Golden-file tests cover an outline prompt, a lift Flight Plan prompt with an outline, and a run Flight Plan prompt, and building twice gives identical text.
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

### 11. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, and ARCHITECTURE.md invariants 3 and 4. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, with one exception below.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording the source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles. Add a dependency only if the copied code needs one that isn't already declared, and match rho's version.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- A 'Test connection' action in Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: the settings and equipment editing in Mission Control, export/import, the other screens, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- The copied files compile under `com.liftoff.app.rho` with their source headers.
- Tests against a fake daemon (an OkHttp MockWebServer WebSocket scripting `rho.hello`, in the style of rho's own client tests) cover success showing both versions, a major-version mismatch, a refused connection, and missing settings.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through rho, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10, the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop), and ARCHITECTURE.md invariants 3, 6, 7 and 8 first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, unreachable retries, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId, lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row has none yet, and store the session id before anything else. The session uses:
  - workspace: the coach path from settings
  - permission_mode 'auto'
  - labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, saving `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error, with a 20-minute limit.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or a permanent failure.

**Storing results.** One Room transaction:
- **OUTLINE:**
  - activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale
  - store the outline notes
  - make sortie 1 current through the existing advance-and-prepare step; a run with run generation off still gets its simple 'Run' plan
- **FLIGHT_PLAN:**
  - write the lift plan (title, estimated minutes, warmup, notes, exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments)
  - source GENERATED, with `rawJson` set to the validated JSON
  - set the sortie PLANNED if it was PENDING

**Enqueue helper.** Unique work `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one, and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports.

Must not break: the coach validator and prompt builder tests, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - invalid, then repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. Read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'), and the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop), first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- `Generation` and `GenerationDao`, and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current: not CANCELLED, and the newest non-cancelled generation for its sortie (for a Flight Plan) or Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one, and update the exported schema.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. Queue coach generations from Missions and sorties

Turn coach generation on in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path, and `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, the next sortie after landing, and the next sortie after a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one, and update the exported schema.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation; with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and its late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm queues an outline; continue without outline; retry; regenerate; Re-fly; scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and nabuSessionId
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure: the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set: say so, and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** The generation status:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so it can be opened in rho's app

Status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show each Launchpad status is derived correctly from the database:
  - outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, the Launchpad and Mission screens, and the In-Flight checklist logic and screen (check, uncheck, edit with deviation, skip and unskip sets and exercises, add sets). The round-6 notes run didn't fail because of its brief. The nabu daemon restarted at 15:39:03 and stopped the session partway through. Its first part (DAO note updates, notes in the In-Flight state, view-model actions and tests) was finished on the local branch nabu/in-flight-exercise-and-sortie-notes-svpf but never merged. Still missing on the goal branch: In-Flight exercise and sortie notes; the Land button with the unchecked-sets confirmation; scrub from In-Flight; run sorties in flight (plan view, distance and duration); reopening into an in-flight sortie on a cold start; the Landed history and detail; Re-fly; export/import; Test connection; and all of coach generation (the coach/ template, schemas, schema checker, validator, prompt builder, copied DaemonClient, GenerationWorker, queueing, Regenerate, the planning and offline statuses, generated run plans). ARCHITECTURE.md marks M0 and M3–M6 not started. Two Launchpad drifts remain: a stock M3 text field in the scrub dialog, and duplicate −/+ controls on the draft. This round splits notes into a data/state run with no UI, which can reuse the interrupted branch's finished work, and a separate screen run. The remaining briefs carry on unchanged in scope.

## Round 7

### 1. In-Flight notes: data, state and actions (no UI)

Add the data and state side of In-Flight notes: an optional note per exercise and one note for the whole sortie (DESIGN.md §6, §8). No screen changes in this brief. The next brief adds the dialogs and the visible note lines. This brief is deliberately tiny. The last attempt was cut off by a daemon restart, not by a problem in the work. Build only what is listed here, and commit as soon as it compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

A previous attempt finished this exact part on the local git branch `nabu/in-flight-exercise-and-sortie-notes-svpf` (see its commits or working files under `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt`, `SortieDao.kt` and `ui/inflight/InFlightState.kt`, `InFlightStates.kt`, `InFlightViewModel.kt`, and `InFlightViewModelTest.kt`). If that branch exists, you may use it as a reference, but check everything against this brief and the current code. If it doesn't exist, build it fresh.

Build on what's already in the repo:
- `com.liftoff.app.ui.inflight`: `InFlightState`/`InFlightStates` (state derived from Room on every change, through the pure `deriveInFlight`) and `InFlightViewModel` (a plain state holder whose actions write straight to Room)
- `FlightPlanDao` and the `userNotes` column on plannedExercise
- `SortieDao` and the Sortie row's `notes` column

**What to add.**
- An action that saves an exercise's note. It changes only the note: the exercise's skipped flag and its sets stay as they are.
- An action that saves the sortie's note. It works on a sortie with no Flight Plan too.
- For both, a blank or whitespace-only note is stored as null, and other text is stored trimmed.
- The In-Flight state carries each exercise card's note and the sortie note, for both the checklist state and the no-checklist state. The state must also update when only the sortie row changes, for example when the sortie note is saved.
- Each save is written to Room straight away, so notes come back after a process kill. Nothing uses the network.

Don't change the Room schema. `SchemaExportTest` must keep passing. New DAO queries that update only a note are fine.

Must not break: the checklist and its tests (`InFlightStatesTest`, `InFlightViewModelTest`, `InFlightScreenTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Robolectric tests against an in-memory database cover:
  - saving an exercise note, which then shows up in the state rebuilt from a fresh database read
  - a note on a skipped exercise keeping the skipped flag and the sets unchanged
  - clearing a note to blank, which stores null
  - saving the sortie note on a sortie with a plan, and on one with no plan, each showing up in the emitted state
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. In-Flight notes: dialogs and note lines on screen

Show and edit In-Flight notes on the screen. Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `InFlightScreen` in `com.liftoff.app.ui.inflight`. Each card footer has '+ Add set · Note · Skip', and the Note cell is disabled today.
- the In-Flight state, which already carries each card's note and the sortie note, and `InFlightViewModel`'s actions to save an exercise note and the sortie note. Those actions already store a blank note as null.
- the themed dialog style the In-Flight set stepper uses (a plain Dialog with a paper card and a 2 dp ink border, not AlertDialog), and the Mission Control text-field style (`LabeledMultiLineTextField` and friends in `com.liftoff.app.ui.control`, which are `internal` and so usable here)

**Exercise note.**
- The Note cell on active and upcoming cards becomes active. It opens a themed dialog with a multi-line field, pre-filled with the current note, and Save and Cancel.
- Save goes through the view model. Cancel changes nothing.
- A card with a note shows it as a short muted line, so the owner can see it is there.

**Sortie note.**
- An underlined 'Sortie note' text action on the In-Flight screen, on both the checklist and the no-checklist states. Keep it out of the bottom area, because a later brief puts the full-width Land button there. Just under the header bands is a good place.
- It opens the same dialog, titled for the sortie, pre-filled, with Save and Cancel. A saved sortie note shows as a short muted line.

Use the design's parts and no stock M3 visuals (no AlertDialog, no OutlinedTextField with a floating label). If a Compose UI test proves hard to make reliable in Robolectric, keep the logic in the view model and cover it there instead. Don't let a hanging test hold up the run: every wait in a test needs a timeout.

Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes state and actions and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Note cell and the 'Sortie note' action open the themed dialog, Save stores the note in Room, and the muted note line appears.
- Tests cover saving an exercise note from the dialog and saving a sortie note on a sortie with no checklist, with Room holding the value afterwards.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight` (`InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`). The bottom area is a 3 dp ink rule over a cream footer that holds only a Back text button today.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper and note dialogs use, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`): `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area, as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog, saying how many (for example '3 sets unchecked. They will be recorded as not done.'). With none open, land straight away.
- Landing goes through `MissionManager.land`, then returns to the Launchpad, which then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason, using the Mission Control text-field style rather than a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank the same way.

**Robustness.** A double tap must not land twice or crash. If `MissionManager` rejects an operation (for example the sortie is no longer in flight), the screen shows a short message instead of crashing. Expose the number of open sets from the state, so the confirmation logic can be tested without Compose. Every wait in a test needs a timeout.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count driving whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Run sorties in flight

Make the In-Flight screen work for run sorties. Read DESIGN.md §5.3 and §6 (the run paragraph), §8 (Sortie and RunSegment) and `design/README.md` first. Build only what is listed here. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, the Land button (through `MissionManager.land`), the unchecked-sets confirmation and Scrub. Today a run sortie shows a 'No checklist for this sortie.' state and lands with no extra input.
- the Flight Plan for a run: the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates, and the plan's run fields (run kind, target distance, target pace) and `RunSegment` rows, all read through `FlightPlanDao.getPlan`
- the Sortie row's `runDistance` and `runMinutes`, and the settings distance unit (mi or km)
- the formatting helpers in `com.liftoff.app.ui.sortie`, which the Launchpad already uses for its run summary

**Run In-Flight.**
- A run's In-Flight screen keeps the teal header and bands. Its body shows the plan in the design style: title, focus or plan notes, run kind, target distance and pace in the configured unit, and segments when present (ink-ruled rows). There are no set rows and no set counter. A run whose plan is only the simple 'Run' plan shows that simply, without empty fields.
- Land opens a themed dialog with two optional fields: distance in the configured unit and duration in minutes. Use the Mission Control text-field style. Store them on the sortie as entered, in the unit configured at the time, in the same transaction as landing. Either may be left blank and is then stored as null. A value that isn't a positive number shows an inline error, and nothing is landed.
- There is no unchecked-sets confirmation for runs.
- Scrub and the sortie note work as they do for lifts.
- A sortie with no plan at all still lands without the dialog.

Put the logic in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight with its notes, Land and Scrub and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land works for a simple run.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, and from a simple 'Run' plan
  - distance and duration saved on landing
  - both left blank and saved as null
  - an invalid value being rejected with the sortie still IN_FLIGHT
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence'), §9 and `design/README.md` first. Build only what is listed here. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity) and `MissionManager.onAppOpen()`, which the app runs on open and on return to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with lift and run flows, notes, Land, Scrub and the themed scrub dialog with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start of the app (no saved navigation state) opens straight into its In-Flight screen. Otherwise it opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad rather than acting on it. This covers the week-ended rollover when the app comes back to the foreground, and landing or scrubbing it elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the same themed scrub dialog and reason field In-Flight uses. A blank reason is stored as null.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination and leave-when-not-in-flight logic in plain Kotlin that a test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is no longer IN_FLIGHT
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed isn't mocked up. Build it from the design's parts: eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first), `MissionDao`, and `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes. The Sortie row has notes, scrubReason, runDistance and runMinutes.
- the plain state-holder pattern used by the Launchpad, Mission and In-Flight screens, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed dialog and text-field style used in In-Flight and Mission Control

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens it, and back (the system back and an on-screen back) returns to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are in red, skipped and not-done sets are marked, and added sets are marked. Show exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first, grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Re-fly

Add Re-fly (DESIGN.md §5.4): reusing a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, with one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (LaunchpadState, LaunchpadViewModel, LaunchpadScreen)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to the same type only
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction:
- copy planned values only, never actuals
- don't copy added sets
- clear skipped flags and user notes
- copy run fields and segments for a run
- the copy's source is REFLY, and the sortie becomes PLANNED
- it works with no network
- it refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation doesn't exist yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases:
  - it prefers the same type and focus
  - it falls back to the same type only
  - it picks the most recent plan
  - it ignores scrubbed sorties
  - it returns nothing when there is no landed plan of that type
- Robolectric tests show that the copy has planned values and no actuals, no added sets, source REFLY, and leaves the sortie PLANNED; that it needs no network; that it refuses a non-current or non-PENDING sortie; and that the Launchpad PENDING state shows Re-fly only when a source exists.
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) with its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, defaulting to a name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- Settings and the daemon token are not part of the export, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file and loads it into the database in one transaction, keeping ids so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty, and that draft is replaced.
- Anything else is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread and report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: the settings and equipment editing in Mission Control, the data layer and its tests, the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Export, then import into a new in-memory database, gives an identical database: every row in every table compares equal. The test seeds Missions in each status, sorties in each state, lift and run plans with actuals and added sets, exercises and generations, and active and inactive equipment.
- Tests cover refusing a non-empty database, accepting one that holds only a fresh draft, and refusing a malformed or wrong-version file, each with the database left unchanged.
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Coach workspace template, schemas and schema checker

Create the coach workspace template, the output schemas, and a small JSON Schema checker. Read DESIGN.md §7.1, §7.1.1, §7.5, §11, §12 and §15 (decision 14) first. Nothing in the UI changes. The next brief adds the semantic validator and the fixtures on top of this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md`: the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; it is never part of this repo
  - turn off rho's verify gate for that path in `~/.rho/config.json`, with the exact snippet from §7.1
  - note the daemon host, port (default 8737) and token for Mission Control
- `coach/COACH.md`: the coach's standing instructions, with every point §7.1.1 says it MUST contain:
  - its role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schemas.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, with the JSON Schemas from §7.5.1–§7.5.3 verbatim. Make sure they are on the unit-test classpath.

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2). A test already guards this.
- An in-house checker for exactly the subset §7.5 uses: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems. Don't add a JSON Schema library.
- Parse with kotlinx.serialization.
- Load the schemas from the resource files, so the files are the single source of truth.
- It returns a list of errors, and each one names its JSON path and its problem specifically, because errors are later sent back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: 'jog' is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property 'day'`.

Must not break: everything already built and its tests, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only) and use snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas, with valid documents passing and with at least one violation of each keyword in the subset, each giving its path-specific message.
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- ARCHITECTURE.md's `coach` row lists the schemas and schema checker. M0 says the workspace template and schemas exist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Coach reply validator and fixtures

Build the phone-side validation of coach replies (DESIGN.md §7.6). Read DESIGN.md §7.5, §7.6 and §13 ('Validator') first. No UI changes. Later briefs add the prompt builder and the generation worker, which call this validator. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the schema files in `app/src/main/resources/schemas/`
- the in-house JSON Schema checker in `com.liftoff.app.coach`
- the `coach/` workspace template

**Validator (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports.
- A pure function from (raw reply text, generation context) to either a parsed result in plain coach types (an outline, a lift plan or a run plan) or a list of human-readable errors.
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

Every error names its path and problem, for example `exercises[2].equipment: barbell is not in the equipment list`.

**Fixtures.** Add recorded-style coach replies under `app/src/test/resources/fixtures/`:
- valid: an outline, a lift plan, a run plan, and a valid plan wrapped in one code fence
- invalid: bad JSON; prose around the JSON; a schema violation; unknown equipment; an inactive equipment key; an outline that doesn't match the pattern; both reps and seconds on one set; an over-length sortie; duplicate exercise names; a plan type that doesn't match the sortie

Must not break: the schema checker and its tests, everything already built, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid class gives its specific message, with the path in it.
- ARCHITECTURE.md's `coach` row lists the validator and fixtures. M0 says the template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 11. Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. The generation worker in a later brief calls this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data`: missions, sorties, Flight Plans with planned and actual sets, active equipment, and exercises

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'.
2. Instructions, worded as in §7.4.
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km).
4. Equipment: every active item as `key — name — notes`, plus the line saying exercises may use only these ids and bodyweight needs none.
5. This week: the pattern, with each sortie's index, type and state; the outline focus of each sortie, when an outline exists; and for a Flight Plan, which sortie is being planned.
6. History: landed and scrubbed sorties within the history window counted back from a given date, newest first, one block per sortie in the §7.4 format: the date, type, title, and whether it landed or was scrubbed; per exercise, the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, with seconds as `3×45 s ✓`; and the notes. Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates.
8. Output schema: the schema text for this kind, verbatim from the resource file.

Also provide the repair prompt, 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', with the validator's errors listed.

**Gathering inputs.** Add a loader, backed by Room and settings, outside the pure package. It builds the builder's input types for a given Mission (and sortie, for a Flight Plan) and a 'today' date, leaving out inactive equipment. The window, date and zone must be injectable for tests.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Must not break: the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Golden-file tests cover an outline prompt, a lift Flight Plan prompt with an outline, and a run Flight Plan prompt, and building twice gives identical text.
- Tests cover: the history formatting (a deviation, skipped sets, seconds, bodyweight, notes and a scrubbed sortie); the 28-day window edge (day 28 is in, day 29 is out); a custom window; newest-first order; the exercise names sorted and de-duplicated; and inactive equipment being left out.
- A Robolectric test shows the loader builds the expected inputs from an in-memory database.
- ARCHITECTURE.md's `coach` row lists the prompt builder.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, and ARCHITECTURE.md invariants 3 and 4. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, with one exception below.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording the source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles. Add a dependency only if the copied code needs one that isn't already declared, and match rho's version.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- A 'Test connection' action in Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: the settings and equipment editing in Mission Control, export/import, the other screens, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- The copied files compile under `com.liftoff.app.rho` with their source headers.
- Tests against a fake daemon (an OkHttp MockWebServer WebSocket scripting `rho.hello`, in the style of rho's own client tests) cover success showing both versions, a major-version mismatch, a refused connection, and missing settings.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through rho, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10, the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop), and ARCHITECTURE.md invariants 3, 6, 7 and 8 first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, unreachable retries, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId, lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row has none yet, and store the session id before anything else. The session uses: workspace = the coach path from settings; permission_mode 'auto'; labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`; and a one-line description.
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, saving `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error, with a 20-minute limit.
- **idle or completed:** validate the last assistant message. Valid: store the result and mark the row SUCCEEDED. Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time. Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or a permanent failure.

**Storing results.** One Room transaction:
- **OUTLINE:** activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale; store the outline notes; make sortie 1 current through the existing advance-and-prepare step (a run with run generation off still gets its simple 'Run' plan).
- **FLIGHT_PLAN:** write the lift plan (title, estimated minutes, warmup, notes, exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments); source GENERATED, with `rawJson` set to the validated JSON; set the sortie PLANNED if it was PENDING.

**Enqueue helper.** Unique work `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one, and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports. Every wait in a test needs a timeout.

Must not break: the coach validator and prompt builder tests, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for: the happy path for an outline and for a lift Flight Plan; invalid, then repair, then success; two failed repairs ending FAILED with the errors kept; blocked; a ui.ask being answered; a major-version mismatch failing permanently; and the session being stopped at the end.
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. Read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'), and the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop), first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- `Generation` and `GenerationDao`, and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out): return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'; clear that mark once a connection succeeds; after 8 attempts, mark the row FAILED with 'coach unreachable'.

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current: not CANCELLED, and the newest non-cancelled generation for its sortie (for a Flight Plan) or Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one, and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Robolectric fake-daemon tests cover: a paused session being resumed; a connection drop mid-turn, resuming from the stored cursor without creating a second session; send_prompt reusing the same client_id on retry; an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts; cancel stopping the rho session and marking the row CANCELLED; and a result for a cancelled or superseded generation not being stored.
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Queue coach generations from Missions and sorties

Turn coach generation on in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path, and `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, the next sortie after landing, and the next sortie after a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable; if it is, add a real migration, never a destructive one, and update the exported schema.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show: confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan; continue-without-outline cancels the outline generation; landing and scrubbing queue the next sortie's Flight Plan; with the toggle on, a run sortie queues a generation, and with it off, the run gets a simple 'Run' plan; regenerate supersedes an open generation, and its late result is ignored; Re-fly, scrub and rollover cancel an open generation; with no host or token set, no generation is queued.
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 16. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm queues an outline; continue without outline; retry; regenerate; Re-fly; scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and nabuSessionId
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure: the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set: say so, and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** The generation status:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so it can be opened in rho's app

Status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show each Launchpad status is derived correctly from the database: outline planning, offline and failed; no connection configured; PENDING planning, offline, and failed with errors; PLANNED re-planning; and Re-fly shown only when §5.4 allows.
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged so far: the Space Age design and theme; the navigation shell; Mission Control (settings and equipment); the Room data layer; the Mission/sortie domain logic with rollover; the Launchpad and Mission screens; the In-Flight checklist logic and screen (check, uncheck, edit with deviation, skip and unskip, add sets); and, from round 7, the In-Flight notes data, state and view-model actions (saveExerciseNote, saveSortieNote). The round-7 notes-UI run ended with a session error at its plan step and committed nothing, so no later run started. Still missing: (1) The exercise 'Note' footer cell is still disabled, and nothing on screen shows or edits the sortie note. (2) In-Flight has no Land button, no unchecked-sets confirmation and no scrub. (3) Runs in flight show no plan and can't record distance or duration. (4) A cold start doesn't reopen an in-flight sortie. (5) Landed is a placeholder, and Re-fly, export/import and Test connection don't exist. (6) All of coach generation is missing: the coach/ template, schemas, schema checker, validator, prompt builder, copied DaemonClient, GenerationWorker, queueing, Regenerate, the planning/offline statuses and generated run plans. (7) ARCHITECTURE.md marks M0 and M3–M6 not started. Two Launchpad drifts remain: a stock M3 text field in the scrub dialog and duplicate −/+ controls on the draft. This round retries the notes UI a different way: the note dialog goes in a new small file, the brief points at the exact composables in the large InFlightScreen.kt, and no fragile Compose text-input test is required. The remaining briefs carry on unchanged in scope.

## Round 8

### 1. In-Flight notes on screen, with the note dialog in its own file

Show and edit In-Flight notes on the screen: an optional note per exercise and one note for the whole sortie (DESIGN.md §6). Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. The data side is already done. This brief is UI only and deliberately small.

A previous attempt at this brief ended with a session error while it was still planning, before any code was written. To keep this run short and focused:
- Put the new note dialog in its own new file, `app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt`.
- Keep edits to `InFlightScreen.kt` to the few places listed below. That file is about 800 lines long. Don't read or restructure the rest of it.
- Commit as soon as each part compiles and its tests pass.
- If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `InFlightViewModel` in `com.liftoff.app.ui.inflight` has `saveExerciseNote(id, text)` and `saveSortieNote(text)`. Both already trim the text, store blank text as null and write straight to Room.
- The In-Flight state already carries each card's `note` (`ExerciseCard.note`) and the sortie note (`sortieNote` on both `InFlightState.Ready` and `InFlightState.NoChecklist`).
- In `InFlightScreen.kt`:
  - `CardFooter` (around line 582) has the 'Note' `FooterCell` with `enabled = false` (around line 603).
  - `NoChecklistContent` (around line 131) already shows the sortie note as a muted line.
  - `ReadyContent` and `ReadyHeader` (around lines 165–320) draw the checklist and header.
  - `StepperDialog` (around line 658) shows the themed dialog style: a plain Dialog with a paper card and a 2 dp ink border, not AlertDialog.
- `LabeledMultiLineTextField` in `com.liftoff.app.ui.control.MissionControlScreen.kt` is `internal`, so it can be used here. Also use the theme's `UnderlinedTextButton` and `PrimaryButton` / `InkButton` in `com.liftoff.app.ui.theme`.

**Note dialog (new file).** A themed dialog with:
- a title, such as the exercise name or 'Sortie note'
- one multi-line field, pre-filled with the current note
- Save and Cancel
Save hands the text back and closes. Cancel changes nothing. Don't use AlertDialog or an OutlinedTextField with a floating label.

**Exercise note.**
- The Note cell on active and upcoming cards becomes enabled and opens the dialog for that exercise. Save goes through `saveExerciseNote`.
- A card whose note isn't null shows it as a short muted line inside the card, so the owner can see it is there.

**Sortie note.**
- Add an underlined 'Sortie note' text action, just under the header bands, on both the checklist and the no-checklist states. Keep it out of the bottom footer, because a later brief puts the full-width Land button there.
- It opens the same dialog. Save goes through `saveSortieNote`.
- The checklist state shows a saved sortie note as a short muted line near the action. The no-checklist state already shows one.

Nothing uses the network. Don't change the Room schema or the view model's note rules.

**Tests.** `InFlightScreenTest` already drives the screen with Compose UI tests on Robolectric, using an in-memory database and a `Dispatchers.Unconfined` scope. Follow its style. Every wait needs a timeout. If typing into the field proves unreliable in Robolectric, test that the dialog opens pre-filled and that Save passes its text through, and leave the Room write to the existing view-model tests. Don't let a flaky test hold up the run.

Must not break: the checklist and its tests (`InFlightScreenTest`, `InFlightStatesTest`, `InFlightViewModelTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Note cell is enabled on active and upcoming cards and opens the themed dialog, pre-filled. Save stores the note, and the muted note line appears on the card.
- The 'Sortie note' action works on both a lift sortie and a sortie with no checklist.
- Tests cover opening the exercise note dialog from a card and saving it, and opening and saving the sortie note on a sortie with no checklist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight` (`InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`, and the themed note dialog in `NoteDialog.kt`). The bottom area is a 3 dp ink rule over a cream footer that holds only a Back text button today. `InFlightScreen.kt` is large, so change only what this brief needs, and put new dialogs in their own files.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper and note dialogs use, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`): `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area, as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog that says how many (for example '3 sets unchecked. They will be recorded as not done.'). With none open, land straight away.
- Landing goes through `MissionManager.land`, then returns to the Launchpad. The Launchpad then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason. The reason field uses the Mission Control text-field style, not a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank the same way.

**Robustness.**
- A double tap must not land twice or crash.
- If `MissionManager` rejects an operation (for example, the sortie is no longer in flight), the screen shows a short message instead of crashing.
- Expose the number of open sets from the state, so the confirmation logic can be tested without Compose.
- Every wait in a test needs a timeout.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count deciding whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Run sorties in flight

Make the In-Flight screen work for run sorties. Read DESIGN.md §5.3 and §6 (the run paragraph), §8 (Sortie and RunSegment) and `design/README.md` first. Build only what is listed here. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, the Land button (through `MissionManager.land`), the unchecked-sets confirmation and Scrub. Today a run sortie shows a 'No checklist for this sortie.' state and lands with no extra input. `InFlightScreen.kt` is large, so change only what this brief needs, and put new composables in their own files.
- the Flight Plan for a run, all read through `FlightPlanDao.getPlan`:
  - the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates
  - the plan's run fields: run kind, target distance and target pace
  - its `RunSegment` rows
- the Sortie row's `runDistance` and `runMinutes`, and the distance unit setting (mi or km)
- the formatting helpers in `com.liftoff.app.ui.sortie`, which the Launchpad already uses for its run summary

**Run In-Flight.**
- A run's In-Flight screen keeps the teal header and bands.
- Its body shows the plan in the design style:
  - the title, and the focus or plan notes
  - the run kind
  - the target distance and pace, in the configured unit
  - the segments, when present, as ink-ruled rows
- There are no set rows and no set counter.
- A run whose plan is only the simple 'Run' plan shows that simply, without empty fields.
- Land opens a themed dialog with two optional fields: distance in the configured unit, and duration in minutes. Use the Mission Control text-field style.
  - Store both on the sortie as entered, in the unit configured at the time, in the same transaction as landing.
  - A field left blank is stored as null.
  - A value that isn't a positive number shows an inline error, and nothing is landed.
- There is no unchecked-sets confirmation for runs.
- Scrub and the sortie note work as they do for lifts.
- A sortie with no plan at all still lands without the dialog.

Put the logic in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight with its notes, Land and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land works for a simple run.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, and from a simple 'Run' plan
  - distance and duration saved on landing
  - both left blank and saved as null
  - an invalid value being rejected, with the sortie still IN_FLIGHT
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence') and §9, and `design/README.md`, first. Build only what is listed here. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity) and `MissionManager.onAppOpen()`, which the app runs when it opens and when it returns to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with the lift and run flows, notes, Land, Scrub, and the themed scrub dialog with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start of the app (one with no saved navigation state) opens straight into its In-Flight screen. Otherwise the app opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad instead of acting on it. This covers the week-ended rollover when the app comes back to the foreground, and the sortie being landed or scrubbed elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the same themed scrub dialog and reason field that In-Flight uses. A blank reason is stored as null.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination logic, and the logic for leaving In-Flight when the sortie is no longer in flight, in plain Kotlin that a test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is otherwise no longer IN_FLIGHT
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed isn't mocked up. Build it from the design's parts: eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first) and `MissionDao`
- `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes
- the Sortie row's notes, scrubReason, runDistance and runMinutes
- `SortieDao.updateNotes`
- the plain state-holder pattern used by the Launchpad, Mission and In-Flight screens, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed note dialog in `com.liftoff.app.ui.inflight` and the Mission Control text-field style

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens it. Back, from both the system back and an on-screen back, returns to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are shown in red. Skipped, not-done and added sets are marked. Show the exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except for its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first and grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Re-fly

Add Re-fly (DESIGN.md §5.4): reusing a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, with one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to the same type only
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It:
- copies planned values only, never actuals
- doesn't copy added sets
- clears skipped flags and user notes
- copies the run fields and segments for a run
- sets the copy's source to REFLY and makes the sortie PLANNED
- works with no network
- refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation doesn't exist yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases:
  - it prefers the same type and focus
  - it falls back to the same type only
  - it picks the most recent plan
  - it ignores scrubbed sorties
  - it returns nothing when there is no landed plan of that type
- Robolectric tests show that:
  - the copy has planned values and no actuals, has no added sets, has source REFLY, and leaves the sortie PLANNED
  - it needs no network
  - it refuses a non-current or non-PENDING sortie
  - the Launchpad's PENDING state shows Re-fly only when a source exists
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) and its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, with a default name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- Settings and the daemon token are not part of the export, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file and loads it into the database in one transaction. Ids are kept, so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty, and that draft is replaced.
- Importing into any other database is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread. They report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: the settings and equipment editing in Mission Control, the data layer and its tests, the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Exporting, then importing into a new in-memory database, gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover each of these, with the database left unchanged after each refusal:
  - refusing a non-empty database
  - accepting one that holds only a fresh draft
  - refusing a malformed or wrong-version file
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Coach workspace template, schemas and schema checker

Create the coach workspace template, the output schemas, and a small JSON Schema checker. Read DESIGN.md §7.1, §7.1.1, §7.5, §11, §12 and §15 (decision 14) first. Nothing in the UI changes. The next brief adds the semantic validator and the fixtures on top of this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md`: the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; it is never part of this repo
  - turn off rho's verify gate for that path in `~/.rho/config.json`, with the exact snippet from §7.1
  - note the daemon host, port (default 8737) and token for Mission Control
- `coach/COACH.md`: the coach's standing instructions, with every point §7.1.1 says it MUST contain:
  - its role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schemas.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, with the JSON Schemas from §7.5.1–§7.5.3 copied verbatim. Make sure they are on the unit-test classpath.

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2). A test already guards this.
- An in-house checker for exactly the subset §7.5 uses: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems. Don't add a JSON Schema library.
- Parse with kotlinx.serialization.
- Load the schemas from the resource files, so the files are the single source of truth.
- It returns a list of errors. Each error names its JSON path and its exact problem, because errors are later sent back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: 'jog' is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property 'day'`.

Must not break: everything already built and its tests, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only), and use snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas. Valid documents pass, and at least one violation of each keyword in the subset gives its path-specific message.
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- ARCHITECTURE.md's `coach` row lists the schemas and schema checker. M0 says the workspace template and schemas exist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Coach reply validator and fixtures

Build the phone-side validation of coach replies (DESIGN.md §7.6). Read DESIGN.md §7.5, §7.6 and §13 ('Validator') first. No UI changes. Later briefs add the prompt builder and the generation worker, which call this validator. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the schema files in `app/src/main/resources/schemas/`
- the in-house JSON Schema checker in `com.liftoff.app.coach`
- the `coach/` workspace template

**Validator (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports.
- A pure function from (raw reply text, generation context) to either a parsed result in plain coach types (an outline, a lift plan or a run plan) or a list of human-readable errors.
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

Must not break: the schema checker and its tests, everything already built, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid case gives its specific message, with the path in it.
- ARCHITECTURE.md's `coach` row lists the validator and fixtures. M0 says the template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. The generation worker in a later brief calls this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data`: missions, sorties, Flight Plans with planned and actual sets, active equipment, and exercises

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'.
2. Instructions, worded as in §7.4.
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km).
4. Equipment: every active item as `key — name — notes`, plus the line saying exercises may use only these ids and bodyweight needs none.
5. This week: the pattern, with each sortie's index, type and state; the outline focus of each sortie, when an outline exists; and, for a Flight Plan, which sortie is being planned.
6. History: landed and scrubbed sorties within the history window, counted back from a given date, newest first, one block per sortie in the §7.4 format:
   - the date, type, title, and whether it landed or was scrubbed
   - per exercise, the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, with seconds as `3×45 s ✓`
   - the notes
   Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates.
8. Output schema: the schema text for this kind, verbatim from the resource file.

Also provide the repair prompt, 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', listing the validator's errors.

**Gathering inputs.** Add a loader, backed by Room and settings, outside the pure package. It builds the builder's input types for a given Mission (and sortie, for a Flight Plan) and a 'today' date, leaving out inactive equipment. The window, date and zone must be injectable for tests.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Must not break: the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Golden-file tests cover an outline prompt, a lift Flight Plan prompt with an outline, and a run Flight Plan prompt. Building twice gives identical text.
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

### 11. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, and ARCHITECTURE.md invariants 3 and 4. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, except in the one case below.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording the source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles.
- Add a dependency only if the copied code needs one that isn't already declared, and match rho's version.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- A 'Test connection' action in Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: the settings and equipment editing in Mission Control, export/import, the other screens, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- The copied files compile under `com.liftoff.app.rho` with their source headers.
- Tests against a fake daemon (an OkHttp MockWebServer WebSocket scripting `rho.hello`, in the style of rho's own client tests) cover: success showing both versions, a major-version mismatch, a refused connection, and missing settings.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through rho, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10 first. Also read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop) and ARCHITECTURE.md invariants 3, 6, 7 and 8. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, retries when the daemon is unreachable, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId, lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row has none yet, and store the session id before anything else. The session uses:
  - workspace = the coach path from settings
  - permission_mode 'auto'
  - labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, saving `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error, with a 20-minute limit.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or a permanent failure.

**Storing results.** One Room transaction:
- **OUTLINE:** activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale; store the outline notes; and make sortie 1 current through the existing advance-and-prepare step. A run with run generation off still gets its simple 'Run' plan.
- **FLIGHT_PLAN:** write the lift plan (title, estimated minutes, warmup, notes, and exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments), with source GENERATED and `rawJson` set to the validated JSON. Set the sortie PLANNED if it was PENDING.

**Enqueue helper.** Unique work `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports. Every wait in a test needs a timeout.

Must not break: the coach validator and prompt builder tests, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - an invalid reply, then a repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. Read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'), and the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop), first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- `Generation` and `GenerationDao`, and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current: it isn't CANCELLED, and it's the newest non-cancelled generation for its sortie (for a Flight Plan) or Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. Queue coach generations from Missions and sorties

Turn coach generation on in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path. `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, the next sortie after landing, and the next sortie after a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show that:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation, and with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and its late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm queues an outline; continue without outline; retry; regenerate; Re-fly; scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and nabuSessionId
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure: the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set: say so, and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** Show the generation status and its actions:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so it can be opened in rho's app

Status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show each Launchpad status is derived correctly from the database:
  - the outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist, and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged so far: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission and sortie domain logic with rollover, the Launchpad and Mission screens, the In-Flight checklist logic and screen, and the In-Flight notes data, state and view-model actions. The round-8 notes-UI run paused after its first task. It committed the themed NoteDialog.kt and NoteDialogTest.kt (commit 2cb0ed6) on the local branch nabu/in-flight-notes-on-screen-with-the-note-74a3, but that branch was never merged, so nothing after it started. Still missing on the goal branch: (1) The exercise 'Note' cell is still disabled. No sortie note is shown or editable anywhere: the no-checklist state does not show it either, despite the earlier brief saying it did. (2) In-Flight has no Land button, no unchecked-sets confirmation and no scrub. (3) A run in flight shows no plan and can't record distance or duration. (4) A cold start doesn't reopen an in-flight sortie. (5) Landed is a placeholder, and Re-fly, export/import and Test connection don't exist. (6) None of coach generation exists: the coach/ template, schemas, schema checker, validator, prompt builder, copied DaemonClient, GenerationWorker, queueing, Regenerate, the planning and offline statuses, and generated run plans. (7) ARCHITECTURE.md marks M0 and M3–M6 not started. Two Launchpad drifts remain: a stock M3 text field in the scrub dialog, and duplicate −/+ controls on the draft. Every failure since round 4 has been a session that paused, stopped, blocked or errored on the first UI run, and that has held up everything behind it. This round therefore runs the three pure-Kotlin coach briefs first, since they depend on nothing in the UI. The notes UI follows and reuses the dialog already written on the round-8 branch. The rest keep their scope.

## Round 9

### 1. Coach workspace template, schemas and schema checker

Create the coach workspace template, the output schemas, and a small JSON Schema checker. Read DESIGN.md §7.1, §7.1.1, §7.5, §11, §12 and §15 (decision 14) first. Nothing in the UI changes. A later brief adds the semantic validator and the fixtures on top of this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md`: the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; it is never part of this repo
  - turn off rho's verify gate for that path in `~/.rho/config.json`, with the exact snippet from §7.1
  - note the daemon host, port (default 8737) and token for Mission Control
- `coach/COACH.md`: the coach's standing instructions, with every point §7.1.1 says it MUST contain:
  - its role
  - never ask questions
  - the final message is exactly one JSON object, with no prose and no code fence
  - the programming principles
  - reuse exercise names
  - memory and the `liftoff-progress` note
  - no writes outside notes and memory

**Schemas.** Add `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, with the JSON Schemas from §7.5.1–§7.5.3 copied verbatim. Make sure they are on the unit-test classpath.

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2). A test already guards this.
- An in-house checker for exactly the subset §7.5 uses: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems. Don't add a JSON Schema library.
- Parse with kotlinx.serialization, which is already a dependency.
- Load the schemas from the resource files, so the files are the single source of truth.
- It returns a list of errors. Each error names its JSON path and its exact problem, because errors are later sent back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: 'jog' is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property 'day'`.

Must not break: everything already built and its tests (the exercise-name normalizer already lives in `com.liftoff.app.coach`), and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only), and use snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas. Valid documents pass, and at least one violation of each keyword in the subset gives its path-specific message.
- `coach/README.md` and `coach/COACH.md` exist with the §7.1 and §7.1.1 content.
- ARCHITECTURE.md's `coach` row lists the schemas and schema checker. M0 says the workspace template and schemas exist.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Coach reply validator and fixtures

Build the phone-side validation of coach replies (DESIGN.md §7.6). Read DESIGN.md §7.5, §7.6 and §13 ('Validator') first. No UI changes. Later briefs add the prompt builder and the generation worker, which call this validator. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the in-house JSON Schema checker in `com.liftoff.app.coach`, which returns path-specific errors
- the `coach/` workspace template

**Validator (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports.
- A pure function from (raw reply text, generation context) to either a parsed result in plain coach types (an outline, a lift plan or a run plan) or a list of human-readable errors.
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

Must not break: the schema checker and its tests, everything already built, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Tests run the validator against every fixture. Valid ones parse into the expected values. Each invalid case gives its specific message, with the path in it.
- ARCHITECTURE.md's `coach` row lists the validator and fixtures. M0 says the template, schemas and validator exist, and that the live reliability measurement has not been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Coach prompt builder with golden files

Build the prompts the coach receives. Read DESIGN.md §7.2, §7.4, §7.6 (repair prompts), §7.7 and §13 ('Prompt builder') first. No UI changes. A later brief's generation worker calls this. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- `com.liftoff.app.coach`: the exercise-name normalizer, the schema checker, the validator, and the schema files in `app/src/main/resources/schemas/` (outline.json, lift-plan.json, run-plan.json)
- the settings store in `com.liftoff.app.settings` (objectives, constraints, sortie length, units, history window)
- the Room DAOs in `com.liftoff.app.data`: missions, sorties (with their notes and scrub reason), Flight Plans with planned and actual sets and per-exercise user notes, active equipment, and exercises

**Pure prompt builder (`com.liftoff.app.coach`, no Android imports).** A pure function from plain input types to prompt text. The same inputs MUST give byte-identical text. There is one prompt for an OUTLINE and one for a FLIGHT_PLAN (lift or run). The sections are Markdown, in the §7.4 order:
1. Task: 'Write the outline for this week' or 'Write the Flight Plan for sortie N of this week'.
2. Instructions, worded as in §7.4.
3. Athlete profile: objectives, constraints, target sortie length in minutes, and units (lb or kg; mi or km).
4. Equipment: every active item as `key — name — notes`, plus the line saying exercises may use only these ids and bodyweight needs none.
5. This week: the pattern, with each sortie's index, type and state; the outline focus of each sortie, when an outline exists; and, for a Flight Plan, which sortie is being planned.
6. History: landed and scrubbed sorties within the history window, counted back from a given date, newest first, one block per sortie in the §7.4 format:
   - the date, type, title, and whether it landed or was scrubbed
   - per exercise, the sets as done, e.g. `3×8 @135 lb ✓, 1×6 @135 lb (planned 8)`, with seconds as `3×45 s ✓`
   - the notes
   Skipped and not-done sets must be recognisable.
7. Exercises used before: every exercise name in that history, sorted alphabetically, with no duplicates.
8. Output schema: the schema text for this kind, verbatim from the resource file.

Also provide the repair prompt, 'Your reply was not valid: <errors>. Reply again with only the corrected JSON object.', listing the validator's errors.

**Gathering inputs.** Add a loader, backed by Room and settings, outside the pure package (for example in `com.liftoff.app.data`). It builds the builder's input types for a given Mission (and sortie, for a Flight Plan) and a 'today' date, leaving out inactive equipment. The window, date and zone must be injectable for tests.

**Golden files.** Expected prompts live under `app/src/test/resources/` and are compared byte for byte. Keep LF endings.

Don't change the Room schema.

Must not break: the schema checker, the validator and its fixtures, the data layer, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only, §2 vocabulary).

Done when:
- Golden-file tests cover an outline prompt, a lift Flight Plan prompt with an outline, and a run Flight Plan prompt. Building twice gives identical text.
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

### 4. In-Flight notes on screen, reusing the finished note dialog

Show and edit In-Flight notes on screen: an optional note per exercise, and one note for the whole sortie (DESIGN.md §6). Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. The data side is already done. This brief is UI only and deliberately small. Earlier attempts were cut off by session interruptions, not by problems with the work. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Reuse the finished dialog.** A previous run already wrote and tested the note dialog. It is in commit `2cb0ed6` on the local branch `nabu/in-flight-notes-on-screen-with-the-note-74a3`:
- `app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt`, with `internal` `NoteDialog(title, initial, onSave, onDismiss)` wrapping `NoteDialogContent(...)`: a paper card with a 2 dp ink border, `LabeledMultiLineTextField`, Save and Cancel
- `app/src/test/java/com/liftoff/app/ui/inflight/NoteDialogTest.kt`

Bring both files in first, for example with `git show 2cb0ed6:<path>`, and commit them. If that commit can't be reached, write the same small dialog again: a themed Dialog with a paper card and a 2 dp ink border, a title, one multi-line field pre-filled with the current note, Save and Cancel. Don't use AlertDialog or an OutlinedTextField with a floating label.

Build on what's already in the repo:
- `InFlightViewModel` in `com.liftoff.app.ui.inflight` has `saveExerciseNote(id, text)` and `saveSortieNote(text)`. Both already trim the text, store blank text as null and write straight to Room.
- The In-Flight state already carries each card's `note` (`ExerciseCard.note`) and the sortie note (`sortieNote` on both `InFlightState.Ready` and `InFlightState.NoChecklist`).
- `InFlightScreen.kt` is about 800 lines long. Change only `CardFooter` (whose 'Note' `FooterCell` has `enabled = false`), `FullCard`, `ReadyContent`, `NoChecklistContent` and the top-level `InFlightScreen` composable, where `StepperDialog` is already shown. Don't restructure the rest.

**Exercise note.**
- The Note cell on the cards becomes enabled and opens the dialog for that exercise, pre-filled. Save goes through `saveExerciseNote`.
- A card whose note isn't null shows it inside the card as a short muted line (at most two lines, then ellipsis), so the owner can see a note is there.

**Sortie note.**
- Add an underlined 'Sortie note' text action just under the header bands. It is always visible, on both the checklist and the no-checklist states. Keep it out of the bottom footer, because a later brief puts the full-width Land button there.
- It opens the same dialog. Save goes through `saveSortieNote`.
- Both states show a saved sortie note as a short muted line near the action. Today the no-checklist state shows only 'No checklist for this sortie.' and no note, so add the line there too.

Nothing uses the network. Don't change the Room schema or the view model's note rules.

**Tests.** `InFlightScreenTest` already drives the screen with Compose UI tests on Robolectric, using an in-memory database and a `Dispatchers.Unconfined` scope; follow its style. Every wait needs a timeout. Labels from `FooterCell`, `PrimaryButton` and `UnderlinedTextButton` are upper-cased on screen. Keep the screen tests few:
- a seeded exercise note shows as the muted line on its card, with no dialog opened
- a seeded sortie note shows on the no-checklist state
- tapping the Note cell opens the dialog pre-filled, and Save stores the note
- tapping 'Sortie note' on a sortie with no checklist opens the dialog, and Save stores it
If typing into the field inside a Dialog is unreliable under Robolectric, save the pre-filled text unchanged and assert it reaches Room; NoteDialogTest already covers editing. If opening a Dialog from the screen hangs Robolectric even without typing, keep the line tests, assert that the actions are present and enabled, and record that in the plan's decisions. Don't let a flaky test hold up the run.

Must not break: the checklist and its tests (`InFlightScreenTest`, `InFlightStatesTest`, `InFlightViewModelTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Note cell is enabled and opens the themed dialog, pre-filled. Save stores the note, and the muted line appears on the card.
- The 'Sortie note' action works on a lift sortie and on a sortie with no checklist, and both states show a saved sortie note.
- The tests above pass, along with NoteDialogTest.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` (a static mockup where 1 px = 1 dp) first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight`: `InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`, and the themed note dialog in `NoteDialog.kt`. The bottom area is a 3 dp ink rule over a cream footer that holds only a Back text button today. `InFlightScreen.kt` is large, so change only what this brief needs, and put new dialogs in their own files.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper and note dialogs use, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`): `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area, as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog that says how many (for example '3 sets unchecked. They will be recorded as not done.'). With none open, land straight away.
- Landing goes through `MissionManager.land`, then returns to the Launchpad. The Launchpad then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason. The reason field uses the Mission Control text-field style, not a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank the same way.

**Robustness.**
- A double tap must not land twice or crash.
- If `MissionManager` rejects an operation (for example, the sortie is no longer in flight), the screen shows a short message instead of crashing.
- Expose the number of open sets from the state, so the confirmation logic can be tested without Compose.
- Every wait in a test needs a timeout.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose tests that open a Dialog are optional. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count deciding whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Run sorties in flight

Make the In-Flight screen work for run sorties. Read DESIGN.md §5.3, the run paragraph of §6, Sortie and RunSegment in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, the Land button (through `MissionManager.land`), the unchecked-sets confirmation and Scrub. Today a run sortie shows a 'No checklist for this sortie.' state and lands with no extra input. `InFlightScreen.kt` is large, so change only what this brief needs, and put new composables in their own files.
- the Flight Plan for a run, all read through `FlightPlanDao.getPlan`:
  - the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates
  - the plan's run fields: run kind, target distance and target pace
  - its `RunSegment` rows
- the Sortie row's `runDistance` and `runMinutes`, and the distance unit setting (mi or km)
- the formatting helpers in `com.liftoff.app.ui.sortie`, which the Launchpad already uses for its run summary

**Run In-Flight.**
- A run's In-Flight screen keeps the teal header and bands.
- Its body shows the plan in the design style:
  - the title, and the focus or plan notes
  - the run kind
  - the target distance and pace, in the configured unit
  - the segments, when present, as ink-ruled rows
- There are no set rows and no set counter.
- A run whose plan is only the simple 'Run' plan shows that simply, without empty fields.
- Land opens a themed dialog with two optional fields: distance in the configured unit, and duration in minutes. Use the Mission Control text-field style.
  - Store both on the sortie as entered, in the unit configured at the time, in the same transaction as landing.
  - A field left blank is stored as null.
  - A value that isn't a positive number shows an inline error, and nothing is landed.
- There is no unchecked-sets confirmation for runs.
- Scrub and the sortie note work as they do for lifts.
- A sortie with no plan at all still lands without the dialog.

Put the logic in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight with its notes, Land and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land works for a simple run.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, and from a simple 'Run' plan
  - distance and duration saved on landing
  - both left blank and saved as null
  - an invalid value being rejected, with the sortie still IN_FLIGHT
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence') and §9, and `design/README.md`, first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity), and `MissionManager.onAppOpen()`, which the app runs when it opens and when it returns to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with the lift and run flows, notes, Land, Scrub, and the themed scrub dialog with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start of the app (one with no saved navigation state) opens straight into its In-Flight screen. Otherwise the app opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad instead of acting on it. This covers the week-ended rollover when the app comes back to the foreground, and the sortie being landed or scrubbed elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the same themed scrub dialog and reason field that In-Flight uses. A blank reason is stored as null.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination logic, and the logic for leaving In-Flight when the sortie is no longer in flight, in plain Kotlin that a test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is otherwise no longer IN_FLIGHT
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed has no mockup, so build it from the design's parts: eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first) and `MissionDao`
- `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes
- the Sortie row's notes, scrubReason, runDistance and runMinutes
- `SortieDao.updateNotes`
- the plain state-holder pattern used by the Launchpad, Mission and In-Flight screens, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed note dialog in `com.liftoff.app.ui.inflight` (`NoteDialog`) and the Mission Control text-field style

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens it. Back, from both the system back and an on-screen back, returns to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are shown in red. Skipped, not-done and added sets are marked. Show the exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except for its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first and grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Re-fly

Add Re-fly (DESIGN.md §5.4): reusing a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, with one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to the same type only
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It:
- copies planned values only, never actuals
- doesn't copy added sets
- clears skipped flags and user notes
- copies the run fields and segments for a run
- sets the copy's source to REFLY and makes the sortie PLANNED
- works with no network
- refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation isn't wired into the app yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases:
  - it prefers the same type and focus
  - it falls back to the same type only
  - it picks the most recent plan
  - it ignores scrubbed sorties
  - it returns nothing when there is no landed plan of that type
- Robolectric tests show that:
  - the copy has planned values and no actuals, has no added sets, has source REFLY, and leaves the sortie PLANNED
  - it needs no network
  - it refuses a non-current or non-PENDING sortie
  - the Launchpad's PENDING state shows Re-fly only when a source exists
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) and its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, with a default name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- Settings and the daemon token are not part of the export, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file and loads it into the database in one transaction. Ids are kept, so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty, and that draft is replaced.
- Importing into any other database is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread. They report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: the settings and equipment editing in Mission Control, the data layer and its tests, the other screens, the coach package, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Exporting, then importing into a new in-memory database, gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover each of these, with the database left unchanged after each refusal:
  - refusing a non-empty database
  - accepting one that holds only a fresh draft
  - refusing a malformed or wrong-version file
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 11. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, and ARCHITECTURE.md invariants 3 and 4. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, except in the one case below.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording the source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles.
- Add a dependency only if the copied code needs one that isn't already declared, and match rho's version.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- A 'Test connection' action in Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: the settings and equipment editing in Mission Control, export/import, the coach package, the other screens, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- The copied files compile under `com.liftoff.app.rho` with their source headers.
- Tests against a fake daemon (an OkHttp MockWebServer WebSocket scripting `rho.hello`, in the style of rho's own client tests) cover: success showing both versions, a major-version mismatch, a refused connection, and missing settings. Every wait needs a timeout.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through rho, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10 first. Also read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop) and ARCHITECTURE.md invariants 3, 6, 7 and 8. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, retries when the daemon is unreachable, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId, lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row has none yet, and store the session id before anything else. The session uses:
  - workspace = the coach path from settings
  - permission_mode 'auto'
  - labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, saving `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error, with a 20-minute limit.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or a permanent failure.

**Storing results.** One Room transaction:
- **OUTLINE:** activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale; store the outline notes; and make sortie 1 current through the existing advance-and-prepare step. A run with run generation off still gets its simple 'Run' plan.
- **FLIGHT_PLAN:** write the lift plan (title, estimated minutes, warmup, notes, and exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments), with source GENERATED and `rawJson` set to the validated JSON. Set the sortie PLANNED if it was PENDING.

**Enqueue helper.** Unique work `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports. Every wait in a test needs a timeout.

Must not break: the coach validator and prompt builder tests, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - an invalid reply, then a repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. First read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'). Then read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop). Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- `Generation` and `GenerationDao`, and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current: it isn't CANCELLED, and it's the newest non-cancelled generation for its sortie (for a Flight Plan) or Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions (LF endings, `area: lowercase summary` commit messages, staging named files only).

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. Queue coach generations from Missions and sorties

Turn coach generation on in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path. `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, the next sortie after landing, and the next sortie after a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show that:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation, and with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and its late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm queues an outline; continue without outline; retry; regenerate; Re-fly; scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and nabuSessionId
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure: the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set: say so, and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** Show the generation status and its actions:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so it can be opened in rho's app

Status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show each Launchpad status is derived correctly from the database:
  - the outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist, and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. The following are built and merged: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, the Launchpad and Mission screens, the In-Flight checklist logic and screen, and the In-Flight notes data, state and view-model actions. The round-9 coach run failed at its plan step with a session error. Its branch (nabu/coach-workspace-template-schemas-and-zfvq) holds only its brief, so `coach/` and `app/src/main/resources/` still don't exist and nothing after that run started. Still missing: (1) The whole of coach generation: the coach/ template, schemas, schema checker, validator, prompt builder, copied DaemonClient, Test connection, GenerationWorker, queueing, Regenerate, the planning and offline statuses, and generated run plans. (2) On In-Flight, the exercise 'Note' cell is still disabled and no sortie note is shown or editable. There is no Land button, no unchecked-sets confirmation and no scrub. Runs show no plan and can't record distance or duration. A cold start doesn't reopen an in-flight sortie. (3) Landed is still a placeholder, and Re-fly and export/import don't exist. (4) ARCHITECTURE.md still marks M0 and M3–M6 not started. (5) Two Launchpad drifts remain: a stock M3 text field in the scrub dialog, and duplicate −/+ controls on the draft. Both plan-step failures so far (rounds 7 and 9) came from runs whose plan had to cover several separate parts. This round therefore splits the failed coach run in two. The first run is very small: the template documents and the three schema files, with no Kotlin logic beyond a test that the files load. The schema checker becomes its own run. Every later brief keeps its scope.

## Round 10

### 1. Coach workspace template and schema files

Add the coach workspace template and the three output schema files. This brief is deliberately small and contains no Kotlin logic beyond one loading test. A later brief adds the schema checker that uses these files. Read DESIGN.md §7.1, §7.1.1 and §7.5 first. Don't read the rest of DESIGN.md unless you need it. A previous attempt at a bigger version of this work failed during planning, so keep the plan short. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit each part as soon as it's written.

**Coach workspace template (`coach/` at the repo root).**
- `coach/README.md` explains the one-time PC setup from §7.1:
  - copy the folder out to its own path
  - run `git init` there; the copy is never part of this repo
  - turn off rho's verify gate for that path in `~/.rho/config.json`, using the exact snippet from §7.1
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

### 2. Coach JSON Schema checker

Build a small JSON Schema checker for the coach's output schemas. Read DESIGN.md §7.5 and §7.6 first. Nothing in the UI changes. A later brief builds the semantic validator on top of this checker. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- the schema files `app/src/main/resources/schemas/outline.json`, `lift-plan.json` and `run-plan.json`, with their loading test
- the `com.liftoff.app.coach` package, which has the exercise-name normalizer and a test that keeps the package free of Android imports
- kotlinx.serialization, which is already a dependency

**Schema checker (`com.liftoff.app.coach`).**
- Pure Kotlin with no Android imports (ARCHITECTURE.md invariant 2).
- An in-house checker for exactly the keywords §7.5 uses: type, const, enum, required, additionalProperties, minimum and maximum, minLength and maxLength, minItems and maxItems, plus nested properties and items. If the schema files use any other keyword, support it too and record that in the plan's decisions. Don't add a JSON Schema library.
- It loads the schemas from the resource files, so the files stay the single source of truth.
- It takes a parsed JSON value and a schema, and returns a list of errors. An empty list means the value is valid. Each error names its JSON path and its exact problem, because a later brief sends these errors back verbatim in repair prompts. Examples: `exercises[2].sets: must have at least 1 item`, `kind: 'jog' is not one of easy, tempo, intervals, long, recovery`, `sorties[0]: unexpected property 'day'`.
- Errors come out in a stable order, so the same input always gives the same list.

Must not break: the schema files and their loading test, the exercise-name normalizer, everything else already built, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only, snake_case JSON fields.

Done when:
- Unit tests run the checker on each of the three schemas. Valid documents pass. At least one violation of each supported keyword gives its path-specific message.
- ARCHITECTURE.md's `coach` row lists the schema checker.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Coach reply validator and fixtures

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

### 4. Coach prompt builder with golden files

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

### 5. In-Flight notes on screen, reusing the finished note dialog

Show and edit In-Flight notes on screen: an optional note per exercise, and one note for the whole sortie (DESIGN.md §6). Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` first. The mockup is static, and 1 px in it equals 1 dp. The data side is already done, so this brief is UI only and deliberately small. Earlier attempts were cut off by session interruptions, not by problems with the work. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Reuse the finished dialog.** A previous run already wrote and tested the note dialog. It is in commit `2cb0ed6` on the local branch `nabu/in-flight-notes-on-screen-with-the-note-74a3`, in two files:
- `app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt`, with the `internal` `NoteDialog(title, initial, onSave, onDismiss)` wrapping `NoteDialogContent(...)`. It draws a paper card with a 2 dp ink border, a `LabeledMultiLineTextField`, and Save and Cancel.
- `app/src/test/java/com/liftoff/app/ui/inflight/NoteDialogTest.kt`

Bring both files in first, for example with `git show 2cb0ed6:<path>`, and commit them. If that commit can't be reached, write the same small dialog again: a themed Dialog with a paper card and a 2 dp ink border, a title, one multi-line field pre-filled with the current note, and Save and Cancel. Don't use AlertDialog or an OutlinedTextField with a floating label.

Build on what's already in the repo:
- `InFlightViewModel` in `com.liftoff.app.ui.inflight` has `saveExerciseNote(id, text)` and `saveSortieNote(text)`. Both already trim the text, store blank text as null, and write straight to Room.
- The In-Flight state already carries each card's `note` (`ExerciseCard.note`) and the sortie note (`sortieNote` on both `InFlightState.Ready` and `InFlightState.NoChecklist`).
- `InFlightScreen.kt` is about 800 lines long. Change only these composables: `CardFooter` (its 'Note' `FooterCell` has `enabled = false`), `FullCard`, `ReadyContent`, `NoChecklistContent`, and the top-level `InFlightScreen`, which already shows `StepperDialog`. Don't restructure the rest.

**Exercise note.**
- The Note cell on each card becomes enabled. It opens the dialog for that exercise, pre-filled, and Save goes through `saveExerciseNote`.
- When a card's note isn't null, the card shows it as a short muted line (at most two lines, then an ellipsis), so the owner can see a note is there.

**Sortie note.**
- Add an underlined 'Sortie note' text action just under the header bands. It is always visible, on both the checklist and the no-checklist states. Keep it out of the bottom footer, because a later brief puts the full-width Land button there.
- It opens the same dialog, and Save goes through `saveSortieNote`.
- Both states show a saved sortie note as a short muted line near the action. The no-checklist state shows only 'No checklist for this sortie.' today, so add the line there too.

Nothing uses the network. Don't change the Room schema or the view model's note rules.

**Tests.** `InFlightScreenTest` already drives the screen with Compose UI tests on Robolectric, using an in-memory database and a `Dispatchers.Unconfined` scope; follow its style. Every wait needs a timeout. `FooterCell`, `PrimaryButton` and `UnderlinedTextButton` show their labels upper-cased. Keep the screen tests few:
- a seeded exercise note shows as the muted line on its card, with no dialog opened
- a seeded sortie note shows on the no-checklist state
- tapping the Note cell opens the dialog pre-filled, and Save stores the note
- tapping 'Sortie note' on a sortie with no checklist opens the dialog, and Save stores it
If typing into the field inside a Dialog is unreliable under Robolectric, save the pre-filled text unchanged and assert that it reaches Room; NoteDialogTest already covers editing. If opening a Dialog from the screen hangs Robolectric even without typing, keep the note-line tests, assert that the actions are present and enabled, and record that in the plan's decisions. Don't let a flaky test hold up the run.

Must not break: the checklist and its tests (`InFlightScreenTest`, `InFlightStatesTest`, `InFlightViewModelTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Note cell is enabled and opens the themed dialog, pre-filled. Save stores the note, and the muted line appears on the card.
- The 'Sortie note' action works on a lift sortie and on a sortie with no checklist, and both states show a saved sortie note.
- The tests above pass, along with NoteDialogTest.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first. The mockup is static, and 1 px in it equals 1 dp. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight`: `InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`, and the themed note dialog in `NoteDialog.kt`. The bottom area is a 3 dp ink rule over a cream footer, which today holds only a Back text button. `InFlightScreen.kt` is large, so change only what this brief needs, and put new dialogs in their own files.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper and note dialogs use, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`), where `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog that says how many (for example '3 sets unchecked. They will be recorded as not done.'). If none are open, land straight away.
- Landing goes through `MissionManager.land` and then returns to the Launchpad. The Launchpad then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason. The reason field uses the Mission Control text-field style, not a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank reasons the same way.

**Robustness.**
- A double tap must not land twice or crash.
- If `MissionManager` rejects an operation (for example, because the sortie is no longer in flight), the screen shows a short message instead of crashing.
- Expose the number of open sets from the state, so the confirmation logic can be tested without Compose.
- Every wait in a test needs a timeout.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose tests that open a Dialog are optional. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count deciding whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace-only reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Run sorties in flight

Make the In-Flight screen work for run sorties. Read DESIGN.md §5.3, the run paragraph of §6, Sortie and RunSegment in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, the Land button (through `MissionManager.land`), the unchecked-sets confirmation, and Scrub. Today a run sortie shows a 'No checklist for this sortie.' state and lands with no extra input. `InFlightScreen.kt` is large, so change only what this brief needs, and put new composables in their own files.
- a run's Flight Plan, all read through `FlightPlanDao.getPlan`:
  - the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates
  - the plan's run fields: run kind, target distance and target pace
  - its `RunSegment` rows
- the Sortie row's `runDistance` and `runMinutes`, and the distance unit setting (mi or km)
- the formatting helpers in `com.liftoff.app.ui.sortie`, which the Launchpad already uses for its run summary

**Run In-Flight.**
- A run's In-Flight screen keeps the teal header and bands.
- Its body shows the plan in the design style:
  - the title, and the focus or plan notes
  - the run kind
  - the target distance and pace, in the configured unit
  - the segments, when there are any, as ink-ruled rows
- There are no set rows and no set counter.
- A run whose plan is only the simple 'Run' plan shows that simply, without empty fields.
- Land opens a themed dialog with two optional fields: distance in the configured unit, and duration in minutes. Use the Mission Control text-field style.
  - Store both values on the sortie as entered, in the unit configured at the time, in the same transaction as landing.
  - A field left blank is stored as null.
  - A value that isn't a positive number shows an inline error, and nothing is landed.
- Runs get no unchecked-sets confirmation.
- Scrub and the sortie note work as they do for lifts.
- A sortie with no plan at all still lands without the dialog.

Put the logic in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight with its notes, Land and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land works for a simple run.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, and from a simple 'Run' plan
  - distance and duration saved on landing
  - both left blank and saved as null
  - an invalid value being rejected, with the sortie still IN_FLIGHT
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence') and §9, and `design/README.md`, first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity), and `MissionManager.onAppOpen()`, which the app runs when it opens and when it returns to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with the lift and run flows, notes, Land, Scrub, and the themed scrub dialog with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start (one with no saved navigation state) opens straight into its In-Flight screen. Otherwise the app opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad instead of acting on it. That covers the week-ended rollover when the app returns to the foreground, and the sortie being landed or scrubbed elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the themed scrub dialog and reason field that In-Flight uses. A blank reason is stored as null.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination logic, and the logic for leaving In-Flight once the sortie is no longer in flight, in plain Kotlin that a test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is otherwise no longer IN_FLIGHT
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed has no mockup, so build it from the design's parts: the eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first) and `MissionDao`
- `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes
- the Sortie row's notes, scrubReason, runDistance and runMinutes
- `SortieDao.updateNotes`
- the plain state-holder pattern that the Launchpad, Mission and In-Flight screens use, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed note dialog in `com.liftoff.app.ui.inflight` (`NoteDialog`) and the Mission Control text-field style

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens its detail view. Both the system back and an on-screen back return to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are shown in red. Skipped, not-done and added sets are marked. Show the exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except for its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first and grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Re-fly

Add Re-fly (DESIGN.md §5.4), which reuses a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, which uses one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie. It returns:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to any of the same type
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It:
- copies planned values only, never actuals
- doesn't copy added sets
- clears skipped flags and user notes
- copies a run's run fields and segments
- sets the copy's source to REFLY and makes the sortie PLANNED
- works with no network
- refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation isn't wired into the app yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases. The chooser:
  - prefers the same type and focus
  - falls back to the same type only
  - picks the most recent plan
  - ignores scrubbed sorties
  - returns nothing when there is no landed plan of that type
- Robolectric tests show that:
  - the copy has planned values and no actuals, has no added sets, has source REFLY, and leaves the sortie PLANNED
  - it needs no network
  - it refuses a non-current or non-PENDING sortie
  - the Launchpad's PENDING state shows Re-fly only when a source exists
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 11. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) and its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, with a default name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- The export leaves out settings and the daemon token, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file, then loads it into the database in one transaction. Ids are kept, so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty. That draft is replaced.
- Importing into any other database is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so that rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread. They report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: settings and equipment editing in Mission Control, the data layer and its tests, the other screens, the coach package, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Exporting and then importing into a new in-memory database gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover each of these, and check that the database is unchanged after each refusal:
  - refusing a non-empty database
  - accepting one that holds only a fresh draft
  - refusing a malformed or wrong-version file
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. Copied rho client and Test connection

Bring rho's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from rho') first, along with ARCHITECTURE.md invariants 3 and 4. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, except in the one case below.

**Copy the rho client.** rho's Android client is on this machine at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`. The protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever file defines what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording its source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles.
- Add a dependency only if the copied code needs one that isn't already declared, and match rho's version.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- Add a 'Test connection' action to Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: settings and equipment editing in Mission Control, export/import, the coach package, the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- The copied files compile under `com.liftoff.app.rho`, each with its source header.
- Tests against a fake daemon cover: success showing both versions, a major-version mismatch, a refused connection, and missing settings. The fake daemon is an OkHttp MockWebServer WebSocket that scripts `rho.hello`, in the style of rho's own client tests. Every wait needs a timeout.
- ARCHITECTURE.md's `com.liftoff.app.nabu` row is renamed to `com.liftoff.app.rho` and marked as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through rho, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10 first. Also read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop) and ARCHITECTURE.md invariants 3, 6, 7 and 8. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, retries when the daemon is unreachable, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId, lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row doesn't have one yet, and store the session id before doing anything else. The session uses:
  - workspace = the coach path from settings
  - permission_mode 'auto'
  - labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, and save `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error. The time limit is 20 minutes.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or on a permanent failure.

**Storing results.** Store each result in one Room transaction:
- **OUTLINE:** activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale. Store the outline notes. Make sortie 1 current through the existing advance-and-prepare step. A run still gets its simple 'Run' plan when run generation is off.
- **FLIGHT_PLAN:** write the lift plan (title, estimated minutes, warmup, notes, and exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments), with source GENERATED and `rawJson` set to the validated JSON. Set the sortie PLANNED if it was PENDING.

**Enqueue helper.** Unique work named `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports. Every wait in a test needs a timeout.

Must not break: the coach validator and prompt builder tests, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - an invalid reply, then a repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. First read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'). Then read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop). Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- `Generation` and `GenerationDao`, and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current. That means it isn't CANCELLED, and it's the newest non-cancelled generation for its sortie (for a Flight Plan) or its Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Queue coach generations from Missions and sorties

Turn on coach generation in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path. `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation that is still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, and the next sortie after landing or a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show that:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation, and with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and that generation's late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 16. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm (which queues an outline), continue without outline, retry, regenerate, Re-fly and scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and nabuSessionId
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- Show 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure, show the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set, say so and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** Show the generation status and its actions:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so the owner can open it in rho's app

The status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show that each Launchpad status is derived correctly from the database:
  - the outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist, and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged so far: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, the Launchpad and Mission screens, the In-Flight checklist logic and screen, the In-Flight notes data/state/actions, and (round 10) the coach/ workspace template plus the three schema files with a loading test. The round-10 schema-checker run failed at plan review with a tool crash ('claude: exit status 1'), not because of its brief, so nothing after it started. Still missing: (1) Coach generation: the schema checker, validator and fixtures, prompt builder with golden files, the copied client and Test connection, GenerationWorker (core and resilience), queueing from Missions and sorties, Regenerate, the planning and offline statuses on the Launchpad, and generated run plans. (2) In-Flight: the exercise Note cell is disabled and no sortie note is shown. There is no Land button, no unchecked-sets confirmation and no scrub. Runs show no plan and can't record distance or duration. A cold start doesn't reopen an in-flight sortie. (3) Landed is a placeholder, and Re-fly and export/import don't exist. (4) ARCHITECTURE.md marks M2–M6 incomplete. (5) Two Launchpad drifts remain: a stock M3 field in the scrub dialog and duplicate −/+ controls on the draft. One correction for this round: the upstream harness was renamed from nabu to rho. Its client is at C:/Users/corpo/Documents/projects/rho/clients/android/.../com/rho/client/ and its methods are rho.*, so the client, worker and wiring briefs now point there. Otherwise the briefs keep their round-10 scope and order: pure-Kotlin coach work first, then the UI.

## Round 11

### 1. Coach JSON Schema checker

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

### 2. Coach reply validator and fixtures

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

### 3. Coach prompt builder with golden files

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

### 4. In-Flight notes on screen, reusing the finished note dialog

Show and edit In-Flight notes on screen: an optional note per exercise, and one note for the whole sortie (DESIGN.md §6). Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` first. The mockup is static, and 1 px in it equals 1 dp. The data side is already done, so this brief is UI only and deliberately small. Earlier attempts were cut off by session interruptions, not by problems with the work. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Reuse the finished dialog.** A previous run already wrote and tested the note dialog. It is in commit `2cb0ed6` on the local branch `nabu/in-flight-notes-on-screen-with-the-note-74a3`, in two files:
- `app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt`, with the `internal` `NoteDialog(title, initial, onSave, onDismiss)` wrapping `NoteDialogContent(...)`. It draws a paper card with a 2 dp ink border, a `LabeledMultiLineTextField`, and Save and Cancel.
- `app/src/test/java/com/liftoff/app/ui/inflight/NoteDialogTest.kt`

Bring both files in first, for example with `git show 2cb0ed6:<path>`, and commit them. If that commit can't be reached, write the same small dialog again: a themed Dialog with a paper card and a 2 dp ink border, a title, one multi-line field pre-filled with the current note, and Save and Cancel. Don't use AlertDialog or an OutlinedTextField with a floating label.

Build on what's already in the repo:
- `InFlightViewModel` in `com.liftoff.app.ui.inflight` has `saveExerciseNote(id, text)` and `saveSortieNote(text)`. Both already trim the text, store blank text as null, and write straight to Room.
- The In-Flight state already carries each card's `note` (`ExerciseCard.note`) and the sortie note (`sortieNote` on both `InFlightState.Ready` and `InFlightState.NoChecklist`).
- `InFlightScreen.kt` is about 800 lines long. Change only these composables: `CardFooter` (its 'Note' `FooterCell` has `enabled = false`), `FullCard`, `ReadyContent`, `NoChecklistContent`, and the top-level `InFlightScreen`, which already shows `StepperDialog`. Don't restructure the rest.

**Exercise note.**
- The Note cell on each card becomes enabled. It opens the dialog for that exercise, pre-filled, and Save goes through `saveExerciseNote`.
- When a card's note isn't null, the card shows it as a short muted line (at most two lines, then an ellipsis), so the owner can see a note is there.

**Sortie note.**
- Add an underlined 'Sortie note' text action just under the header bands. It is always visible, on both the checklist and the no-checklist states. Keep it out of the bottom footer, because a later brief puts the full-width Land button there.
- It opens the same dialog, and Save goes through `saveSortieNote`.
- Both states show a saved sortie note as a short muted line near the action. The no-checklist state shows only 'No checklist for this sortie.' today, so add the line there too.

Nothing uses the network. Don't change the Room schema or the view model's note rules.

**Tests.** `InFlightScreenTest` already drives the screen with Compose UI tests on Robolectric, using an in-memory database and a `Dispatchers.Unconfined` scope; follow its style. Every wait needs a timeout. `FooterCell`, `PrimaryButton` and `UnderlinedTextButton` show their labels upper-cased. Keep the screen tests few:
- a seeded exercise note shows as the muted line on its card, with no dialog opened
- a seeded sortie note shows on the no-checklist state
- tapping the Note cell opens the dialog pre-filled, and Save stores the note
- tapping 'Sortie note' on a sortie with no checklist opens the dialog, and Save stores it
If typing into the field inside a Dialog is unreliable under Robolectric, save the pre-filled text unchanged and assert that it reaches Room; NoteDialogTest already covers editing. If opening a Dialog from the screen hangs Robolectric even without typing, keep the note-line tests, assert that the actions are present and enabled, and record that in the plan's decisions. Don't let a flaky test hold up the run.

Must not break: the checklist and its tests (`InFlightScreenTest`, `InFlightStatesTest`, `InFlightViewModelTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Note cell is enabled and opens the themed dialog, pre-filled. Save stores the note, and the muted line appears on the card.
- The 'Sortie note' action works on a lift sortie and on a sortie with no checklist, and both states show a saved sortie note.
- The tests above pass, along with NoteDialogTest.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first. The mockup is static, and 1 px in it equals 1 dp. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight`: `InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`, and the themed note dialog in `NoteDialog.kt`. The bottom area is a 3 dp ink rule over a cream footer, which today holds only a Back text button. `InFlightScreen.kt` is large, so change only what this brief needs, and put new dialogs in their own files.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper and note dialogs use, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`), where `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog that says how many (for example '3 sets unchecked. They will be recorded as not done.'). If none are open, land straight away.
- Landing goes through `MissionManager.land` and then returns to the Launchpad. The Launchpad then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason. The reason field uses the Mission Control text-field style, not a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank reasons the same way.

**Robustness.**
- A double tap must not land twice or crash.
- If `MissionManager` rejects an operation (for example, because the sortie is no longer in flight), the screen shows a short message instead of crashing.
- Expose the number of open sets from the state, so the confirmation logic can be tested without Compose.
- Every wait in a test needs a timeout.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose tests that open a Dialog are optional. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count deciding whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace-only reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Run sorties in flight

Make the In-Flight screen work for run sorties. Read DESIGN.md §5.3, the run paragraph of §6, Sortie and RunSegment in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with its lift checklist, notes, the Land button (through `MissionManager.land`), the unchecked-sets confirmation, and Scrub. Today a run sortie shows a 'No checklist for this sortie.' state and lands with no extra input. `InFlightScreen.kt` is large, so change only what this brief needs, and put new composables in their own files.
- a run's Flight Plan, all read through `FlightPlanDao.getPlan`:
  - the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates
  - the plan's run fields: run kind, target distance and target pace
  - its `RunSegment` rows
- the Sortie row's `runDistance` and `runMinutes`, and the distance unit setting (mi or km)
- the formatting helpers in `com.liftoff.app.ui.sortie`, which the Launchpad already uses for its run summary

**Run In-Flight.**
- A run's In-Flight screen keeps the teal header and bands.
- Its body shows the plan in the design style:
  - the title, and the focus or plan notes
  - the run kind
  - the target distance and pace, in the configured unit
  - the segments, when there are any, as ink-ruled rows
- There are no set rows and no set counter.
- A run whose plan is only the simple 'Run' plan shows that simply, without empty fields.
- Land opens a themed dialog with two optional fields: distance in the configured unit, and duration in minutes. Use the Mission Control text-field style.
  - Store both values on the sortie as entered, in the unit configured at the time, in the same transaction as landing.
  - A field left blank is stored as null.
  - A value that isn't a positive number shows an inline error, and nothing is landed.
- Runs get no unchecked-sets confirmation.
- Scrub and the sortie note work as they do for lifts.
- A sortie with no plan at all still lands without the dialog.

Put the logic in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema.

Must not break: lift In-Flight with its notes, Land and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land works for a simple run.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, and from a simple 'Run' plan
  - distance and duration saved on landing
  - both left blank and saved as null
  - an invalid value being rejected, with the sortie still IN_FLIGHT
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence') and §9, and `design/README.md`, first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity), and `MissionManager.onAppOpen()`, which the app runs when it opens and when it returns to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`, with the lift and run flows, notes, Land, Scrub, and the themed scrub dialog with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start (one with no saved navigation state) opens straight into its In-Flight screen. Otherwise the app opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad instead of acting on it. That covers the week-ended rollover when the app returns to the foreground, and the sortie being landed or scrubbed elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the themed scrub dialog and reason field that In-Flight uses. A blank reason is stored as null.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination logic, and the logic for leaving In-Flight once the sortie is no longer in flight, in plain Kotlin that a test can drive. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is otherwise no longer IN_FLIGHT
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed has no mockup, so build it from the design's parts: the eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first) and `MissionDao`
- `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes
- the Sortie row's notes, scrubReason, runDistance and runMinutes
- `SortieDao.updateNotes`
- the plain state-holder pattern that the Launchpad, Mission and In-Flight screens use, and the formatting helpers in `com.liftoff.app.ui.sortie`
- the themed note dialog in `com.liftoff.app.ui.inflight` (`NoteDialog`) and the Mission Control text-field style

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens its detail view. Both the system back and an on-screen back return to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are shown in red. Skipped, not-done and added sets are marked. Show the exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except for its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first and grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. Re-fly

Add Re-fly (DESIGN.md §5.4), which reuses a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, which uses one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie. It returns:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to any of the same type
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It:
- copies planned values only, never actuals
- doesn't copy added sets
- clears skipped flags and user notes
- copies a run's run fields and segments
- sets the copy's source to REFLY and makes the sortie PLANNED
- works with no network
- refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation isn't wired into the app yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases. The chooser:
  - prefers the same type and focus
  - falls back to the same type only
  - picks the most recent plan
  - ignores scrubbed sorties
  - returns nothing when there is no landed plan of that type
- Robolectric tests show that:
  - the copy has planned values and no actuals, has no added sets, has source REFLY, and leaves the sortie PLANNED
  - it needs no network
  - it refuses a non-current or non-PENDING sortie
  - the Launchpad's PENDING state shows Re-fly only when a source exists
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) and its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, with a default name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- The export leaves out settings and the daemon token, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file, then loads it into the database in one transaction. Ids are kept, so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty. That draft is replaced.
- Importing into any other database is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so that rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread. They report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: settings and equipment editing in Mission Control, the data layer and its tests, the other screens, the coach package, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Exporting and then importing into a new in-memory database gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover each of these, and check that the database is unchanged after each refusal:
  - refusing a non-empty database
  - accepting one that holds only a fresh draft
  - refusing a malformed or wrong-version file
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 11. Copied rho client and Test connection

Bring the harness's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from nabu') first, along with ARCHITECTURE.md invariants 3 and 4. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, except in the one case below.

**The harness was renamed.** DESIGN.md and ARCHITECTURE.md call the harness 'nabu'. It has since been renamed 'rho'. Its protocol methods are now `rho.hello`, `rho.session.create`, `rho.session.send_prompt` and so on, and the checkout is at `C:/Users/corpo/Documents/projects/rho`. Use the rho names and paths everywhere in new code. Add a short amendment at the end of DESIGN.md recording the rename, and update ARCHITECTURE.md's wording to match. Don't rename the existing Room column `nabuSessionId`, because that would change the schema.

**Copy the rho client.** rho's Android client is at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`. The protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever other files define what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording its source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles.
- Add a dependency only if the copied code needs one that isn't already declared, and match rho's version (see rho's `clients/android` Gradle files).
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- Add a 'Test connection' action to Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x' (use the major version the copied client expects)
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: settings and equipment editing in Mission Control, export/import, the coach package, the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- The copied files compile under `com.liftoff.app.rho`, each with its source header.
- Tests against a fake daemon cover: success showing both versions, a major-version mismatch, a refused connection, and missing settings. The fake daemon is an OkHttp MockWebServer WebSocket that scripts `rho.hello`, in the style of rho's own client tests. Every wait needs a timeout.
- ARCHITECTURE.md's package table lists `com.liftoff.app.rho` as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 12. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through the rho daemon, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10 first. DESIGN.md calls the harness 'nabu'; it was renamed 'rho', and its methods are `rho.*`. Also read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop) and ARCHITECTURE.md invariants 3, 6, 7 and 8. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, retries when the daemon is unreachable, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId (holds the rho session id), lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row doesn't have one yet, and store the session id before doing anything else. The session uses:
  - workspace = the coach path from settings
  - permission_mode 'auto'
  - labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, and save `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error. The time limit is 20 minutes.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or on a permanent failure.

**Storing results.** Store each result in one Room transaction:
- **OUTLINE:** activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale. Store the outline notes. Make sortie 1 current through the existing advance-and-prepare step. A run still gets its simple 'Run' plan when run generation is off.
- **FLIGHT_PLAN:** write the lift plan (title, estimated minutes, warmup, notes, and exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments), with source GENERATED and `rawJson` set to the validated JSON. Set the sortie PLANNED if it was PENDING.

**Enqueue helper.** Unique work named `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports. Every wait in a test needs a timeout.

Must not break: the coach validator and prompt builder tests, the rho client and Test connection, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - an invalid reply, then a repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 13. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. First read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'). DESIGN.md calls the harness 'nabu'; it was renamed 'rho', and its methods are `rho.*`. Then read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop). Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- the copied rho client in `com.liftoff.app.rho`
- `Generation` and `GenerationDao` (the session id lives in `nabuSessionId`), and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current. That means it isn't CANCELLED, and it's the newest non-cancelled generation for its sortie (for a Flight Plan) or its Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 14. Queue coach generations from Missions and sorties

Turn on coach generation in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. DESIGN.md calls the harness 'nabu'; it was renamed 'rho'. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path. `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation that is still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, and the next sortie after landing or a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show that:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation, and with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and that generation's late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 15. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. DESIGN.md calls the harness 'nabu'; it was renamed 'rho'. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm (which queues an outline), continue without outline, retry, regenerate, Re-fly and scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and the session id in `nabuSessionId`
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- Show 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure, show the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set, say so and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** Show the generation status and its actions:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so the owner can open it in rho's app

The status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show that each Launchpad status is derived correctly from the database:
  - the outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist, and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

**Check:** not met. Built and merged so far: the Space Age design and theme, the navigation shell, Mission Control (settings and equipment), the Room data layer, the Mission/sortie domain logic with rollover, the Launchpad and Mission screens, the In-Flight checklist logic and screen, In-Flight notes on screen, Land and scrub from In-Flight, and the pure-Kotlin coach work: the coach/ template, schemas, schema checker, reply validator with fixtures, and prompt builder with golden files. The round-11 'Run sorties in flight' run paused partway through its work step. Four of its six tasks are finished and committed on the unmerged local branch rho/run-sorties-in-flight-9qtx (f7522dc distance and run-kind formatting, 28e401a run-landing entry parser, e3f0af2 MissionManager.land storing run distance and minutes, cb9316a InFlightViewModel.landRun). The run state, the run screen, the landing dialog and their tests were never written. Still missing on the goal branch: (1) A run in flight shows 'No checklist' and can't record distance or duration. (2) A cold start doesn't reopen an in-flight sortie, and two Launchpad drifts remain: a stock M3 field in the scrub dialog and duplicate −/+ controls on the draft. (3) Landed is still a placeholder, and Re-fly and export/import don't exist. (4) The copied rho client and Test connection, GenerationWorker (core and resilience), queueing from Missions and sorties, Regenerate, the planning and offline statuses on the Launchpad, and generated run plans. (5) ARCHITECTURE.md marks M2–M6 incomplete. This round splits the failed run in two. The first run cherry-picks the four finished commits and adds only the run state and run screen, with Land unchanged. The second adds the landing dialog on top of the view-model action that already exists. The remaining briefs keep their scope.

## Round 12

### 1. Run In-Flight: bring in the finished work and show the run plan

Show a run sortie's Flight Plan on the In-Flight screen. Read DESIGN.md §5.3, the run paragraph of §6, Sortie and RunSegment in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Start from finished work.** An earlier run on this topic was interrupted, but its first four commits are finished and tested. They sit on the unmerged local branch `rho/run-sorties-in-flight-9qtx`, directly on top of this branch's base. Bring in exactly these four commits with `git cherry-pick`, in this order:
- `f7522dc` ui: add distance and run kind formatting (`formatDistance` and `formatRunKind` in `ui/sortie/PlanFormat.kt`, with tests)
- `28e401a` inflight: parse run landing entry (`ui/inflight/RunLanding.kt`: `RunEntry` and `parseRunEntry`, with `RunLandingTest`)
- `e3f0af2` data: store run distance and minutes on land (`MissionManager.land(sortieId, runDistance, runMinutes)`, written in the landing transaction, with tests)
- `cb9316a` inflight: land a run with distance and duration (`InFlightViewModel.landRun`, `runErrors` and `clearRunErrors`, with tests)

Don't bring in that branch's `.rho/runs/` files. Run the gate once right after cherry-picking, before changing anything else. If the branch is missing or a commit won't apply cleanly, rebuild the same pieces from that branch's plan (`git show rho/run-sorties-in-flight-9qtx:.rho/runs/run-sorties-in-flight-9qtx/plan.md`). Nothing in this brief uses `landRun` or `parseRunEntry` yet. The next brief adds the landing dialog that uses them.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`: `InFlightState` (Loading, NoChecklist, Ready), `deriveInFlight`, `InFlightStates`, `InFlightViewModel`, and `InFlightScreen` with its lift checklist, notes, Land, the unchecked-sets confirmation and Scrub. Today a run sortie shows the NoChecklist state. `InFlightScreen.kt` is large, so change only what this brief needs and put new composables in their own files.
- a run's Flight Plan, read through `FlightPlanDao.getPlan`: the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates, the plan's run kind, target distance and target pace, and its `RunSegment` rows
- the distance unit setting (mi or km) in `SettingsStore`
- the formatting helpers in `com.liftoff.app.ui.sortie` and the theme parts in `com.liftoff.app.ui.theme` (`InkRuledListRow`, stripes, headings)

**Run In-Flight.**
- A run sortie that has a Flight Plan gets its own In-Flight state and screen. A run with no plan at all keeps today's NoChecklist screen.
- The run screen keeps the teal header and the stripe bands. It has no set counter, no progress bar and no set rows.
- Its body shows the plan in the design style: the title; one line with the plan notes, or the focus when there are no notes; the run kind; the target distance in the configured unit; the target pace; and the segments, when there are any, as ink-ruled rows.
- A row appears only when it has a value. The simple 'Run' plan therefore shows its title and focus without empty fields.
- Changing the distance unit in Mission Control updates the screen live.
- Scrub and the sortie note work on the run screen exactly as on lifts. Watch for any `when` over the In-Flight state with an `else` branch, such as the one that picks the sortie number for the scrub dialog. A new state must not fall into it and leave Scrub or the note dead on runs.
- Land on a run keeps today's behaviour for now: it lands straight away, with no dialog and no unchecked-sets confirmation.

Put the derivation in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status rather than re-running it.

Must not break: the lift In-Flight flow with its notes, Land, the unchecked-sets confirmation and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. If an existing test asserted that a run with a plan shows NoChecklist, change it to assert the new run state, and say so in the PR. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The four commits above are on the branch and their tests pass.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, in km and in mi
  - the run state from the simple 'Run' plan, with no empty rows
  - a run with no plan still showing NoChecklist
  - Launch → Land of a simple run from the run state, leaving the sortie LANDED
  - Scrub from the run state storing its reason
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 2. Run landing dialog with distance and duration

Let the user record distance and duration when landing a run. Read DESIGN.md §5.3, the run paragraph of §6, Sortie in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the run In-Flight state and screen in `com.liftoff.app.ui.inflight`. Today Land on a run lands straight away.
- `InFlightViewModel.landRun(distanceText, minutesText, onDone)`, which parses both fields, publishes field errors to `runErrors` and returns false without landing on bad input, and otherwise lands through `MissionManager.land(sortieId, runDistance, runMinutes)` in one transaction. Also `clearRunErrors()`.
- `parseRunEntry` in `ui/inflight/RunLanding.kt`: blank becomes null, and only a positive finite number is accepted
- the themed dialogs `LandConfirmDialog`, `ScrubDialog` and `NoteDialog` in `ui.inflight`, and the Mission Control text-field style (`LabeledTextField` in `ui/control/MissionControlScreen.kt`)
- the distance unit setting (mi or km)

**The dialog.**
- Land on a run that has a plan opens a themed dialog in the style of the existing In-Flight dialogs. No stock M3 visuals.
- It has two optional fields in the Mission Control text-field style, with a decimal keyboard: distance, labelled with the configured unit, and duration in minutes. Both start blank each time the dialog opens.
- The dialog's Land calls `landRun`. A field that isn't a positive number shows its error inline under that field, the dialog stays open and nothing is landed. On success the dialog closes and the screen leaves the way the lift Land does.
- Cancel, Back and tapping outside close the dialog, clear any errors and land nothing.
- Runs get no unchecked-sets confirmation.
- A sortie with no plan still lands without the dialog. Lift landing is unchanged.
- A second tap while the landing is being written is ignored, as on lifts.

Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema. No Compose test may type into or focus a text field inside a Dialog: under Robolectric that never goes idle and the test hangs (see PR #18). Test the behaviour through the view model and the database, and if you add a Compose test, only check what is rendered. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: the run In-Flight screen, the lift In-Flight flow with its notes, Land, the unchecked-sets confirmation and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land of a simple run with a distance and a duration works.
- Robolectric tests cover:
  - distance and duration stored on the sortie when it lands
  - both fields left blank and stored as null
  - an invalid distance and an invalid duration, each rejected with the error on the right field and the sortie still IN_FLIGHT
  - a run with no plan landing without the dialog
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 3. Reopen into In-Flight, and Launchpad polish

Make the app reopen into an in-flight sortie, and fix two small Launchpad drifts. Read DESIGN.md §6 ('Persistence') and §9, and `design/README.md`, first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the shell in `com.liftoff.app.ui` (`ShellNav` with `inFlightSortieId`, `LiftoffShell`, MainActivity), and `MissionManager.onAppOpen()`, which the app runs when it opens and when it returns to the foreground
- the In-Flight screen in `com.liftoff.app.ui.inflight`: the lift checklist, the run plan view with its distance-and-duration landing dialog, notes, Land, Scrub, and the themed `ScrubDialog` with its reason field
- the Launchpad in `com.liftoff.app.ui.launchpad` (`LaunchpadScreen`, `LaunchpadState`, `LaunchpadViewModel`)

**Reopening.**
- While a sortie is IN_FLIGHT, a cold start (one with no saved navigation state) opens straight into its In-Flight screen. Otherwise the app opens to the Launchpad. When saved navigation state exists, it still wins, as it does today.
- Back from In-Flight goes to the Launchpad, which still offers Resume.
- If the sortie on screen stops being IN_FLIGHT while In-Flight is open, the screen returns to the Launchpad instead of acting on it. That covers the week-ended rollover when the app returns to the foreground, and the sortie being landed or scrubbed elsewhere.

**Launchpad polish.**
- The Launchpad's scrub dialog uses a stock M3 OutlinedTextField with a floating label. Replace it with the themed `ScrubDialog` that In-Flight uses. Trim the reason, and store a blank one as null, the same way In-Flight does.
- The draft state draws its −/+ pattern controls twice, once under the chips and once in the bottom bar. Keep one set, disabled at 1 and at 7 sorties.

Put the start-destination logic, and the logic for leaving In-Flight once the sortie is no longer in flight, in plain Kotlin that a test can drive. No Compose test may type into a text field inside a Dialog, because that hangs under Robolectric. Nothing uses the network. Don't change the Room schema.

Must not break: In-Flight (lift and run) and its tests, the Launchpad and Mission screens, the shell's back behaviour and `ShellNavTest`, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Tests cover:
  - the start destination being In-Flight when a sortie is IN_FLIGHT, and the Launchpad otherwise
  - In-Flight leaving when its sortie is scrubbed by rollover or is otherwise no longer IN_FLIGHT
  - the Launchpad scrub storing a trimmed reason, and a blank one as null
  - the draft's pattern controls stopping at 1 and 7
- ARCHITECTURE.md marks M4 done and describes `ui` with a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 4. Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed has no mockup, so build it from the design's parts: the eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first) and `MissionDao`
- `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes
- the Sortie row's notes, scrubReason, runDistance and runMinutes
- `SortieDao.updateNotes`
- the plain state-holder pattern that the Launchpad, Mission and In-Flight screens use, and the formatting helpers in `com.liftoff.app.ui.sortie` (including `formatDistance` and `formatRunKind`)
- the themed note dialog in `com.liftoff.app.ui.inflight` (`NoteDialog`) and the Mission Control text-field style

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens its detail view. Both the system back and an on-screen back return to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are shown in red. Skipped, not-done and added sets are marked. Show the exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except for its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required, and no Compose test may type into a text field inside a Dialog, because that hangs under Robolectric. Don't change the Room schema.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first and grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 5. Re-fly

Add Re-fly (DESIGN.md §5.4), which reuses a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, which uses one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie. It returns:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to any of the same type
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It:
- copies planned values only, never actuals
- doesn't copy added sets
- clears skipped flags and user notes
- copies a run's run fields and segments
- sets the copy's source to REFLY and makes the sortie PLANNED
- works with no network
- refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation isn't wired into the app yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Don't change the Room schema.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases. The chooser:
  - prefers the same type and focus
  - falls back to the same type only
  - picks the most recent plan
  - ignores scrubbed sorties
  - returns nothing when there is no landed plan of that type
- Robolectric tests show that:
  - the copy has planned values and no actuals, has no added sets, has source REFLY, and leaves the sortie PLANNED
  - it needs no network
  - it refuses a non-current or non-PENDING sortie
  - the Launchpad's PENDING state shows Re-fly only when a source exists
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 6. Database export and import in Mission Control

Add backup and restore. Read DESIGN.md §8 ('Export and import'), §10 ('Lost or reset phone') and §13 first. The phone holds the only copy of the training history, so this is the backup story. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LiftoffDatabase` in `com.liftoff.app.data` (version 1) and its tables: mission, sortie, flightPlan, plannedExercise, plannedSet, runSegment, exercise, generation and equipment
- the Mission Control screen and its view model in `com.liftoff.app.ui.control`
- `MissionManager.onAppOpen()`, which makes sure the current week has a Mission
- kotlinx.serialization, which is already a dependency

**Export.**
- Mission Control has an Export action. It opens the system file picker to create a `.json` file, with a default name like `liftoff-2026-10-10.json`.
- It writes the whole database: every row of every table, ids included, plus a format version and the Room schema version.
- Field names in the file are snake_case.
- The export leaves out settings and the daemon token, and Mission Control says so near the button.

**Import.** Mission Control has an Import action. It opens the system file picker to choose a file, then loads it into the database in one transaction. Ids are kept, so every reference stays intact.
- Import is allowed only into an empty database. A fresh install creates the current week's draft as soon as it opens, so a database whose only content is one DRAFT Mission with no sorties also counts as empty. That draft is replaced.
- Importing into any other database is refused with a clear message, and nothing is changed.
- A file that isn't a Liftoff export, has an unknown format version or is malformed is refused with a clear message, and nothing is changed.
- After a successful import, run `onAppOpen()` so that rollover and the current week are settled, and show how much was restored.

Both actions run off the main thread. They report success or failure in Mission Control in the design style: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Put the export/import logic in `com.liftoff.app.data`, separate from the UI, so a Robolectric test can drive it with in-memory databases and streams.

Don't change the Room schema.

Must not break: settings and equipment editing in Mission Control, the data layer and its tests, the other screens, the coach package, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Exporting and then importing into a new in-memory database gives an identical database: every row in every table compares equal. The test seeds:
  - Missions in each status
  - sorties in each state
  - lift and run plans with actuals and added sets
  - exercises and generations
  - active and inactive equipment
- Tests cover each of these, and check that the database is unchanged after each refusal:
  - refusing a non-empty database
  - accepting one that holds only a fresh draft
  - refusing a malformed or wrong-version file
- ARCHITECTURE.md's `data` row mentions export/import, M1's note no longer lists export/import as missing, and M5 is marked done.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 7. Copied rho client and Test connection

Bring the harness's client code into Liftoff and add 'Test connection' to Mission Control. Read DESIGN.md §3, §7.3 step 1, §9 ('Mission Control'), §10 and §11 ('Reused from nabu') first, along with ARCHITECTURE.md invariants 3 and 4. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, except in the one case below.

**The harness was renamed.** DESIGN.md and ARCHITECTURE.md call the harness 'nabu'. It has since been renamed 'rho'. Its protocol methods are now `rho.hello`, `rho.session.create`, `rho.session.send_prompt` and so on, and the checkout is at `C:/Users/corpo/Documents/projects/rho`. Use the rho names and paths everywhere in new code. Add a short amendment at the end of DESIGN.md recording the rename, and update ARCHITECTURE.md's wording to match. Don't rename the existing Room column `nabuSessionId`, because that would change the schema.

**Copy the rho client.** rho's Android client is at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`. The protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it needs into `app/src/main/java/com/liftoff/app/rho/`. That means `protocol/Events.kt`, `protocol/RpcCodes.kt`, and whichever other files define what DaemonClient imports.
- Change only the package and import lines. Don't edit the logic, so the copy stays comparable with upstream.
- Each copied file starts with a short header comment recording its source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Don't copy files Liftoff doesn't need. If a copied file pulls in something unneeded, copy the smallest set that compiles.
- Add a dependency only if the copied code needs one that isn't already declared, and match rho's version (see rho's `clients/android` Gradle files).
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.
- Nothing in rho changes.

**Test connection (Mission Control).**
- Add a 'Test connection' action to Mission Control's Connection section. It uses the host, port and token currently stored in settings, connects the way rho's own client does, and runs `rho.hello` with client `liftoff`.
- On success it shows the daemon version and the protocol version.
- On failure it shows a clear message for each case:
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - authentication rejected
  - empty host or token
  - a protocol major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x' (use the major version the copied client expects)
- It runs off the main thread, shows that it's working while it runs, gives up after a reasonable timeout, and closes the connection afterwards.
- Use the design's parts (ink-ruled rows, buttons from `ui.theme`), not stock M3 visuals.
- Put the logic in plain Kotlin, outside Compose, that a test can drive.

Also give `AppContainer` a way to build a connected client from the current settings, so the generation worker in a later brief can reuse it. Keep `AppContainer` hand-built, with no DI framework.

Must not break: settings and equipment editing in Mission Control, export/import, the coach package, the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- The copied files compile under `com.liftoff.app.rho`, each with its source header.
- Tests against a fake daemon cover: success showing both versions, a major-version mismatch, a refused connection, and missing settings. The fake daemon is an OkHttp MockWebServer WebSocket that scripts `rho.hello`, in the style of rho's own client tests. Every wait needs a timeout.
- ARCHITECTURE.md's package table lists `com.liftoff.app.rho` as existing, with the source commit, and M1's note no longer lists Test connection as missing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 8. GenerationWorker core flow

Build the WorkManager worker that runs one coach generation through the rho daemon, covering the main path. Read DESIGN.md §7.2, §7.3, §7.6 and §10 first. DESIGN.md calls the harness 'nabu'; it was renamed 'rho', and its methods are `rho.*`. Also read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (session create, send_prompt with client_id, subscribe and events_after, state_change, ui.ask, permission.request, stop) and ARCHITECTURE.md invariants 3, 6, 7 and 8. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

The next brief adds resilience: resuming paused sessions, catching up from the cursor after a drop, retries when the daemon is unreachable, and cancel and supersede. A later brief wires generations into the app. Build only what is listed here.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`, and `AppContainer`'s way to build a connected client from settings
- the pure prompt builder, repair prompt and validator in `com.liftoff.app.coach`, and the loader that gathers prompt inputs from Room and settings (`PromptInputLoader`)
- `Generation` and `GenerationDao`. A Generation has kind, missionId, sortieId, status (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), nabuSessionId (holds the rho session id), lastEventId, attempt, error, createdAt and finishedAt.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`, and `MissionManager` with the domain functions in `com.liftoff.app.domain`
- `androidx.work` and `work-testing`, which are already dependencies

**The worker (`com.liftoff.app.work`).** A `CoroutineWorker` that takes a generation id and follows §7.3:
- The row is RUNNING while the worker runs.
- Connect and send hello. A major-version mismatch is a permanent failure with the §10 message.
- Create the rho session only if the row doesn't have one yet, and store the session id before doing anything else. The session uses:
  - workspace = the coach path from settings
  - permission_mode 'auto'
  - labels `[liftoff, liftoff:outline]` or `[liftoff, liftoff:flight-plan]`, never starting with `run:`
  - a one-line description
- Send the prompt with `client_id = <generationId>-<attempt>`.
- Subscribe, and save `lastEventId` on the row as events arrive. The turn ends at the first state_change after our message whose `to` is idle, completed, blocked, paused or error. The time limit is 20 minutes.
- **idle or completed:** validate the last assistant message.
  - Valid: store the result and mark the row SUCCEEDED.
  - Invalid: send up to 2 repair prompts in the same session, incrementing attempt each time.
  - Still invalid after that: FAILED with 'invalid output', keeping the error list in `error`.
- **blocked:** FAILED with 'coach needed input'.
- Answer an incoming ui.ask with 'Decide yourself; there is nobody to ask.' and a permission.request with deny, then keep waiting.
- **paused, error, or a connection failure:** return a retry for now. The next brief refines this.
- Stop the rho session on SUCCEEDED or on a permanent failure.

**Storing results.** Store each result in one Room transaction:
- **OUTLINE:** activate the DRAFT Mission with one sortie per pattern letter, each with the outline's focus and rationale. Store the outline notes. Make sortie 1 current through the existing advance-and-prepare step. A run still gets its simple 'Run' plan when run generation is off.
- **FLIGHT_PLAN:** write the lift plan (title, estimated minutes, warmup, notes, and exercises with equipment ids, rest and sets) or the run plan (run kind, target distance and pace, and segments), with source GENERATED and `rawJson` set to the validated JSON. Set the sortie PLANNED if it was PENDING.

**Enqueue helper.** Unique work named `gen-<generationId>`, policy KEEP, a network-connected constraint, and exponential backoff starting at 1 minute. Expose it through `AppContainer`.

The schema loader reads schema files through the classloader. Make sure that works in the app at runtime, not only on the test classpath. Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. The worker lives in `work`, not in `coach`, which stays free of Android imports. Every wait in a test needs a timeout.

Must not break: the coach validator and prompt builder tests, the rho client and Test connection, `MissionManager` and the domain tests, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests run the worker against a fake rho daemon (an OkHttp MockWebServer WebSocket that scripts the protocol) for:
  - the happy path for an outline and for a lift Flight Plan
  - an invalid reply, then a repair, then success
  - two failed repairs ending FAILED with the errors kept
  - blocked
  - a ui.ask being answered
  - a major-version mismatch failing permanently
  - the session being stopped at the end
- ARCHITECTURE.md's `work` row is marked as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 9. GenerationWorker resilience, cancel and supersede

Make the generation worker resumable and safe to cancel. First read DESIGN.md §7.3 (steps 4–6, 'Unreachable daemon', 'Retries before giving up', 'Superseded results'), §10 and §13 ('Integration tests'). DESIGN.md calls the harness 'nabu'; it was renamed 'rho', and its methods are `rho.*`. Then read the rho protocol spec at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md` (events_after, resume, stop). Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker` and its enqueue helper in `com.liftoff.app.work`, with its fake-daemon tests. It already connects, creates the session once, sends prompts with `client_id = <generationId>-<attempt>`, saves `lastEventId`, validates and repairs, stores results and stops the session.
- the copied rho client in `com.liftoff.app.rho`
- `Generation` and `GenerationDao` (the session id lives in `nabuSessionId`), and `AppContainer`

**Resilience.**
- **paused:** call `rho.session.resume` before returning a retry.
- **error:** return a retry.
- **Reconnecting after a dropped connection or a killed worker:** reuse the stored session id, never create a second session, and catch up with `events_after` from the stored `lastEventId`.
- **A retried send:** reuse the same `client_id`, so the daemon doesn't take the prompt twice.
- **Unreachable daemon** (connection refused or timed out):
  - return a retry, and record on the row that the coach is offline, so a later brief can show 'Mission Control offline'
  - clear that mark once a connection succeeds
  - after 8 attempts, mark the row FAILED with 'coach unreachable'

**Cancel and supersede.**
- A cancel helper cancels the work, stops the rho session if there is one, and marks the row CANCELLED. Expose it through `AppContainer`.
- Results are stored only if the generation is still current. That means it isn't CANCELLED, and it's the newest non-cancelled generation for its sortie (for a Flight Plan) or its Mission (for an outline). A late result for a superseded or cancelled generation is dropped, and its session is stopped.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker's existing tests, the coach and domain tests, every screen, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- Robolectric fake-daemon tests cover:
  - a paused session being resumed
  - a connection drop mid-turn, resuming from the stored cursor without creating a second session
  - send_prompt reusing the same client_id on retry
  - an unreachable daemon being marked offline, retrying, and then failing as 'coach unreachable' after 8 attempts
  - cancel stopping the rho session and marking the row CANCELLED
  - a result for a cancelled or superseded generation not being stored
- ARCHITECTURE.md marks M3's worker as built, with app wiring still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 10. Queue coach generations from Missions and sorties

Turn on coach generation in the Mission and sortie flow: an outline on confirm, and a Flight Plan whenever a sortie becomes current and needs one. Read DESIGN.md §4.2, §4.3, §5.3, §7.2 and §7.3 ('Superseded results') first. DESIGN.md calls the harness 'nabu'; it was renamed 'rho'. The Launchpad's status display and Regenerate button come in the next brief. This brief changes the flow and keeps the UI working. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `GenerationWorker`, its enqueue and cancel helpers and its result storing in `com.liftoff.app.work`, reached through `AppContainer`. Storing an OUTLINE result activates the Mission and makes sortie 1 current.
- `MissionManager` in `com.liftoff.app.data`. `confirm` currently takes the 'Continue without outline' path. `advance` has an `AwaitGeneration` branch, decided in `domain/SortiePlanning.kt`, that does nothing yet.
- Re-fly, scrub, land and the week-ended rollover in `MissionManager`
- the 'Generate run plans' setting, and the daemon host and token settings

**Outline.**
- Confirm freezes the pattern and queues an OUTLINE generation. The Mission stays a DRAFT until the outline is stored.
- A 'continue without outline' operation keeps today's confirm behaviour and cancels any outline generation that is still open.
- 'Retry' queues a fresh outline generation after a failure.
- If no daemon host or token is set, confirm doesn't queue a generation that can't succeed. It reports that to the caller, so the Launchpad can offer 'Continue without outline'.
- Until the next brief adds the status display, the Launchpad's draft state must at least show that the coach is planning and offer 'Continue without outline', so the app is never stuck.

**Flight Plans.**
- Whenever a sortie becomes current and needs generation (a lift, or a run with the toggle on), create a FLIGHT_PLAN Generation row and enqueue it after the transaction commits. That covers sortie 1 after the outline, and the next sortie after landing or a scrub.
- With the toggle off, runs keep getting the simple 'Run' plan.
- The toggle is read at that moment. Changing it never alters an existing plan.
- Add a regenerate operation for a PLANNED or PENDING current sortie that queues a new FLIGHT_PLAN generation. The current plan stays until the new result replaces it.

**Supersede and cleanup.** Starting a new generation for a sortie cancels any queued or running one for that sortie, including its rho session. Re-fly, scrub, landing and the week-ended rollover also cancel a sortie's open generation, so a late result can never overwrite them.

Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. Every wait in a test needs a timeout.

Must not break: the worker and its fake-daemon tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database and WorkManager's test helpers show that:
  - confirm queues an outline, and its result activates the Mission and queues sortie 1's Flight Plan
  - continue-without-outline cancels the outline generation
  - landing and scrubbing queue the next sortie's Flight Plan
  - with the toggle on, a run sortie queues a generation, and with it off, the run gets a simple 'Run' plan
  - regenerate supersedes an open generation, and that generation's late result is ignored
  - Re-fly, scrub and rollover cancel an open generation
  - with no host or token set, no generation is queued
- ARCHITECTURE.md marks M6 done and M3's wiring as built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

### 11. Generation status, Regenerate and Re-fly rules on the Launchpad

Show coach generation on the Launchpad, as DESIGN.md §9 ('Launchpad') and §10 describe, and finish the milestone records. Read DESIGN.md §4.2, §5.4, §7.3, §9 and §10, `design/README.md` and `design/screens/launchpad.html` first. DESIGN.md calls the harness 'nabu'; it was renamed 'rho'. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the Launchpad (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen` in `com.liftoff.app.ui.launchpad`), with its Draft, Planned, Pending, InFlight and Closed states, and Re-fly on PENDING
- `MissionManager`'s generation operations: confirm (which queues an outline), continue without outline, retry, regenerate, Re-fly and scrub
- the Generation rows: status, error with the validator's error list, the offline mark the worker records, and the session id in `nabuSessionId`
- the theme composables (`UnderlinedTextButton`, `PrimaryButton`, ink-ruled rows)

**Draft while the outline runs.**
- Show 'Coach is planning the week…', or 'Mission Control offline — retrying' when the worker has marked the coach offline.
- On failure, show the reason, with Retry and 'Continue without outline'.
- With no daemon host or token set, say so and offer 'Continue without outline'.

**PLANNED.**
- An underlined Regenerate text button next to Scrub, as in `design/screens/launchpad.html`.
- While a regeneration runs, the current plan stays visible and launchable, with a short 'Coach is re-planning…' line.

**PENDING.** Show the generation status and its actions:
- 'Coach is planning…'
- 'Mission Control offline — retrying'
- a failure with its reason, and the validator's error list when there is one
- Retry, Re-fly and Scrub. Re-fly is now offered only when the generation failed, the coach is offline, or no connection is configured (§5.4).
- 'View in rho', which shows the rho session id so the owner can open it in rho's app

The status updates live from Room. Use the design's parts, with no stock M3 visuals. Put the state derivation in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout.

Must not break: the generation flow and worker tests, In-Flight, Landed, Re-fly, export/import, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests show that each Launchpad status is derived correctly from the database:
  - the outline planning, offline and failed
  - no connection configured
  - PENDING planning, offline, and failed with errors
  - PLANNED re-planning
  - Re-fly shown only when §5.4 allows
- ARCHITECTURE.md's package table and milestones reflect what is built. M2, M3, M5 and M6 are done. M0 says the template, schemas, validator and prompt builder exist, and that the live measurement (LiveCoachTest) hasn't been run.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).

