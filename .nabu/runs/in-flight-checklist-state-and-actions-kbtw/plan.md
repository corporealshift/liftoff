# Plan — In-Flight checklist state and actions (no UI)

## Approach

Build a pure derivation function plus a flow-backed state holder in `com.liftoff.app.ui.inflight`, following the same pattern as `LaunchpadStates` / `LaunchpadViewModel`. The derivation takes a `FlightPlanDetail` (from `getPlan`) and derives the full checklist state; the holder wires `observeChanges()` → re-derive → `StateFlow`. Actions write to Room via the DAO, then let the flow pick up the change.

This fits because:
- The brief explicitly says "follow the plain state-holder pattern that `LaunchpadViewModel` / `LaunchpadState` and `MissionViewModel` / `MissionState` use."
- `observeChanges()` already emits on every write (INSERT/UPDATE/DELETE) to the four tables, so no schema changes are needed.
- All DAO methods the brief requires already exist (`updateSetActuals`, `addExtraSet`, `updateExercise`). We only need transactional wrappers for skip/unskip.

## Files and code involved

### New files (all in `com.liftoff.app.ui.inflight`)

1. **InFlightState.kt** — sealed interface + data classes
2. **InFlightStates.kt** — derivation class (`observeChanges()` → fresh `getPlan` → derive)
3. **InFlightActions.kt** — plain state holder (exposes `StateFlow<InFlightState>` + coroutine scope for actions)

### Modified files

4. **app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt** — make `formatWeight` `internal` instead of `private` so the new code can format weight labels like "35 LB" / "20 KG".
5. **app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt** — add two transactional action methods: `skipExercise(sortieId)` and `unskipExercise(exerciseId)`.
6. **app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt** — update to use the new state holder (placeholder text replaced with real UI scaffolding that compiles but doesn't yet render the full screen; the next brief builds the actual UI).

### Existing files read but not changed

- `SortieDao` — `observeById(sortieId)` used for sortie index/focus
- `SettingsStore.weightUnit` — used to format weight labels
- `PlanFormat.kt` — existing `formatExerciseLoad`, `formatSetUnit` (already private)
- `Relations.kt` — `FlightPlanDetail`, `PlannedExerciseDetail`, `PlannedSet` (read-only)
- `Enums.kt` — `SetStatus`, `SortieType`, `SortieState`

### Test files

7. **app/src/test/java/com/liftoff/app/ui/inflight/InFlightStatesTest.kt** — Robolectric tests against in-memory DB, seeded with a LIFT sortie of three exercises (weighted, bodyweight, mixed). Covers all scenarios listed in the brief.
8. **app/src/test/java/com/liftoff/app/ui/inflight/InFlightActionsTest.kt** — Robolectric tests exercising each action and verifying Room writes + state recovery.

## Order of work

### Step 1: State model and derivation function

Create `InFlightState.kt` with the sealed hierarchy and all data classes, then implement the pure derive function in `InFlightStates.kt`. This step is testable once we have a way to seed data.

The derive function takes `(FlightPlanDetail, Sortie, WeightUnit) → InFlightState.Ready`. It computes:
- eyebrow (`IN FLIGHT · SORTIE n`, 1-based from sortie.index)
- title (plan.title, else sortie.focus, else "Sortie n")
- set counter (DONE sets only, added included; skipped ≠ done)
- progress segments (one per set in order: DONE, SKIPPED, OPEN)
- exercise cards (status: skipped/done/active/upcoming + count)
- current set (first open set of active card, null if no active card)
- "no checklist" for run sorties or empty plan

### Step 2: Action DAO methods and state holder

Add `skipExercise(sortieId)` and `unskipExercise(exerciseId)` to `FlightPlanDao` as `@Transaction` suspend functions. Build `InFlightActions` that exposes the `StateFlow` and coroutine-scoped action methods, each writing immediately to Room.

### Step 3: Make `formatWeight` internal

Change `formatWeight` in `PlanFormat.kt` from `private` to `internal`. No behavioral change — it's used by existing tests and code that already compile.

### Step 4: Update placeholder InFlightScreen

Wire the placeholder screen to instantiate `InFlightActions` and read its state flow. Replace the static text with a compilation stub that shows the eyebrow/title from the state holder but keeps the "The In-Flight checklist comes later." body text. This ensures the new code is used by the shell without breaking the existing test.

### Step 5: Write tests

Write `InFlightStatesTest.kt` and `InFlightActionsTest.kt` covering every scenario listed in the brief. Then run the gate.

## How the result will be tested

All tests are Robolectric (`@Config(sdk = [34])`, `Room.inMemoryDatabaseBuilder`) seeded with a LIFT sortie of three exercises:
1. **Weighted** — e.g. Bench Press, 3 sets with weight
2. **Bodyweight** — e.g. Push-ups, sets with no weight (null)
3. **Mixed** — e.g. Squat, some sets weighted, some bodyweight

### InFlightStatesTest.kt (state derivation)

1. Initial card states, counter, labels, current set — verify eyebrow, title, 7 sets total, 0 done, first set is current, cards: [active, upcoming, upcoming]
2. No checklist state — sortie with no plan or no exercises → `InFlightState.NoChecklist`

### InFlightActionsTest.kt (actions + recovery)

3. One-tap check a set → DONE with planned actuals → counter becomes 1, set is checked
4. Uncheck a set → OPEN, actuals cleared → counter decrements
5. Edit with deviation → DONE with different actuals, planned values unchanged, deviation flag on the set
6. Finishing an exercise → card becomes done, next exercise becomes active
7. Skip and reopen a set → SKIPPED status toggled correctly
8. Skip and unskip an exercise → all open sets skipped in one transaction, then reopened
9. Add a set → pre-filled from last set's actuals (if done) or planned values; turns a done card active again
10. State recovery — perform a mix of actions, then read fresh from DB and assert derived state matches what the flow emitted

### Gate

```bash
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
```

Must pass with zero failures.

## Risky or uncertain

1. **`observeChanges()` deduplication.** The brief warns not to put `distinctUntilChanged` on the raw flow because Room re-emits on every write even when row count doesn't change. We deduplicate *after* derivation (the `StateFlow` builder does this naturally). Risk: if an action updates a set but the derived state is identical, the UI won't update. This is acceptable — the brief says "Deduplicating the derived state is fine."

2. **Transaction scope for skip/unskip.** `skipExercise(sortieId)` must mark the exercise skipped AND all its open sets SKIPPED in one transaction. The current DAO has `markOpenSetsNotDone(sortieId)` which does a subquery across all exercises. For `skipExercise` we need to limit to the specific sortie's exercises. We'll add a new query scoped to `sortieId`.

3. **Adding a set pre-fill logic.** "Pre-filled from the exercise's last set, using its actuals if that set is done and its planned values otherwise." The DAO method `addExtraSet` takes explicit reps/seconds/weight — the state holder must compute which values to pass based on the last set's status.

4. **Weight unit formatting.** `formatWeight` currently returns just the number (e.g. "135"). The brief requires weight labels like "35 LB" or "20 KG". We need a new internal helper that appends the unit string, using `SettingsStore.weightUnit`.

## Decisions

The brief settles every behavior the plan touches. No open decisions remain.
