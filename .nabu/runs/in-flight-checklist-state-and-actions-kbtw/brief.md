# In-Flight checklist state and actions (no UI)

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
