# In-Flight notes: data, state and actions (no UI)

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
