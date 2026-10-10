# Plan — In-Flight checklist state and actions (no UI)

## Approach

Build a pure derivation function plus a flow-backed state holder in `com.liftoff.app.ui.inflight`, following the same pattern as `LaunchpadStates` / `LaunchpadViewModel`. The derivation takes a `FlightPlanDetail` (from `getPlan`) and derives the full checklist state; the holder wires `observeChanges()` → re-derive → `StateFlow`. Actions write to Room via the DAO, then let the flow pick up the change.

This fits because:
- The brief explicitly says "follow the plain state-holder pattern that `LaunchpadViewModel` / `LaunchpadState` and `MissionViewModel` / `MissionState` use." (These live in `ui/launchpad/LaunchpadState.kt` and `ui/mission/MissionState.kt`.)
- `observeChanges()` already emits on every write (INSERT/UPDATE/DELETE) to the four tables, so no schema changes are needed.
- All DAO methods the brief requires already exist (`updateSetActuals`, `addExtraSet`, `updateExercise`). We only need transactional wrappers for skip/unskip per exercise.

## Files and code involved

### New files (all in `com.liftoff.app.ui.inflight`)

1. **InFlightState.kt** — sealed interface + data classes
2. **InFlightStates.kt** — derivation class (`observeChanges()` → fresh `getPlan` → derive)
3. **InFlightActions.kt** — plain state holder (exposes `StateFlow<InFlightState>` + coroutine scope for actions)

### Modified files

4. **app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt** — make `formatWeight` `internal` instead of `private` so the new code can format weight labels like "35 LB" / "20 KG".
5. **app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt** — add two transactional action methods: `skipExercise(plannedExerciseId)` and `unskipExercise(plannedExerciseId)`.

### Existing files read but not changed

- `SortieDao` (data/SortieDao.kt) — reached via `database.sortieDao()` through `AppContainer`
- `SettingsStore.weightUnit` (settings/SettingsStore.kt, WeightUnit enum in settings/Settings.kt) — used to format weight labels
- `PlanFormat.kt` — existing `formatExerciseLoad` (public), `formatSetUnit` (private)
- `Relations.kt` — `FlightPlanDetail`, `PlannedExerciseDetail`, `PlannedSet` (read-only)
- `Enums.kt` — `SetStatus`, `SortieType`, `SortieState`

### Test files

6. **app/src/test/java/com/liftoff/app/ui/inflight/InFlightStatesTest.kt** — Robolectric tests against in-memory DB, seeded with a LIFT sortie of three exercises (weighted, bodyweight, mixed). Covers all scenarios listed in the brief.
7. **app/src/test/java/com/liftoff/app/ui/inflight/InFlightActionsTest.kt** — Robolectric tests exercising each action and verifying Room writes + state recovery.

## Order of work

### Step 1: Make `formatWeight` internal, add state model, derivation function, and tests

Change `formatWeight` in `PlanFormat.kt` from `private` to `internal`. No behavioral change — it's used by existing code that already compiles.

Create `InFlightState.kt` with the sealed hierarchy and all data classes, then implement the pure derive function in `InFlightStates.kt`. Write `InFlightStatesTest.kt` covering initial card states, counter, labels, current set, and no-checklist state. Commit as soon as this compiles and tests pass.

The derive function takes `(FlightPlanDetail, Sortie, WeightUnit) → InFlightState.Ready`. It computes:
- eyebrow (`IN FLIGHT · SORTIE n`, 1-based from sortie.index)
- title (plan.title, else sortie.focus, else "Sortie n")
- set counter (DONE sets only, added included; skipped ≠ done)
- progress segments (one per set in order: DONE, SKIPPED, OPEN)
- exercise cards (status: skipped/done/active/upcoming + count)
- current set (first open set of active card, null if no active card)
- "no checklist" for run sorties or empty plan

### Step 2: Add DAO skip/unskip methods and state holder with tests

Add `skipExercise(plannedExerciseId)` and `unskipExercise(plannedExerciseId)` to `FlightPlanDao` as `@Transaction` suspend functions. Build `InFlightActions` that exposes the `StateFlow` and coroutine-scoped action methods, each writing immediately to Room. Write `InFlightActionsTest.kt` covering check/uncheck, edit with deviation, finish exercise, skip/reopen set, skip/unskip exercise, add set, and state recovery. Commit as soon as this compiles and tests pass.

### Gate (after Step 2)

```bash
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
```

Must pass with zero failures.

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

## Risky or uncertain

1. **`observeChanges()` deduplication.** The brief warns not to put `distinctUntilChanged` on the raw flow because Room re-emits on every write even when row count doesn't change. We deduplicate *after* derivation (the `StateFlow` builder does this naturally). Risk: if an action updates a set but the derived state is identical, the UI won't update. This is acceptable — the brief says "Deduplicating the derived state is fine."

2. **Transaction scope for skip/unskip.** `skipExercise(plannedExerciseId)` must mark the exercise skipped AND all its open sets SKIPPED in one transaction. The current DAO has `markOpenSetsNotDone(sortieId)` which does a subquery across all exercises. For `skipExercise` we need to limit to the specific exercise's sets. We'll add a new query scoped to `plannedExerciseId`.

3. **Adding a set pre-fill logic.** "Pre-filled from the exercise's last set, using its actuals if that set is done and its planned values otherwise." The DAO method `addExtraSet` takes explicit reps/seconds/weight — the state holder must compute which values to pass based on the last set's status.

4. **Weight unit formatting.** `formatWeight` currently returns just the number (e.g. "135") or empty string for null (bodyweight). The brief requires weight labels like "35 LB" or "20 KG". We'll add a small helper in `ui/inflight` that builds `"${formatWeight(w)} ${unit.name}"` or `"BW"`, leaving `formatWeight` itself unchanged.

## Decisions

These are behaviors a user would notice that the brief leaves open:

**Unskip an exercise:** with no schema change, a set skipped on its own before the exercise skip can't be told apart from sets skipped as part of the exercise skip. **Choose:** reopen every SKIPPED set in that exercise. **Alternative:** reopen none of the individually skipped sets, which needs a schema change and so isn't possible.

**Weight and count labels on a DONE set:** **Choose:** show the actuals (what was lifted), and planned values on open or skipped sets. **Alternative:** always show planned values.

**Check, reopen or add a set inside a skipped exercise:** **Choose:** also clear the exercise's skip flag, so the card doesn't sit as "skipped" with an open set. **Alternative:** leave the flag alone.

**Deviation when the planned weight is set and the actual is null (or the other way round):** **Choose:** count it as a deviation. **Alternative:** treat null as "no change".

**What "current set" is when every card is done or skipped:** **Choose:** null. **Alternative:** point at the last set.
