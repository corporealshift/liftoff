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

