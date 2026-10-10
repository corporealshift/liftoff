# Plan — In-Flight checklist state and actions (no UI)

## Approach

Build the checklist logic in `com.liftoff.app.ui.inflight` the same way Launchpad is built. It has three parts:
- a state model plus a **pure** derivation function: `FlightPlanDetail?` + `Sortie` + `WeightUnit` → `InFlightState`;
- a derivation class, `InFlightStates`, whose `observe(sortieId)` re-runs a fresh `getPlan` read and the derivation on every Room change;
- a plain state holder, `InFlightViewModel`. Like `LaunchpadViewModel`, it is not an AndroidX ViewModel. It turns the flow into a `StateFlow` with `stateIn` and runs each action with `scope.launch`.

Each action is one `@Transaction` method on `FlightPlanDao`, built from the existing `updateSetActuals`, `addExtraSet` and `updateExercise`. Every write goes straight to Room. Nothing is cached in memory: actions read whatever they need, such as planned values or the last set, from Room inside their transaction, and the screen only passes IDs. The Room schema does not change. The new methods are `@Query`/`@Transaction` methods only, with no new entities or columns, so `SchemaExportTest` is unaffected.

## Files

New, under `app/src/main/java/com/liftoff/app/ui/inflight/`:
1. `InFlightState.kt`: the sealed state, its data classes, and the pure `deriveInFlight(...)` function with its label helpers.
2. `InFlightStates.kt`: the derivation class with `observe(sortieId): Flow<InFlightState>`.
3. `InFlightViewModel.kt`: the plain state holder.

Modified:
4. `app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt`: change `formatWeight` from `private` to `internal`. Its body stays as it is.
5. `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt`: add the action methods (Step 2).

Tests, under `app/src/test/java/com/liftoff/app/ui/inflight/`:
6. `InFlightStatesTest.kt`: derivation tests on fresh `getPlan` reads.
7. `InFlightViewModelTest.kt`: tests of each action through the holder's `StateFlow`, plus the fresh-read equality test.

Plus additions to `app/src/test/java/com/liftoff/app/data/FlightPlanDaoTest.kt` for the new DAO methods.

Do not touch `InFlightScreen.kt`, `InFlightScreenTest.kt`, `AppContainer`, or any other screen.

## State model (`InFlightState.kt`)

```kotlin
sealed interface InFlightState {
    object Loading : InFlightState
    data class NoChecklist(val sortieId: Long, val eyebrow: String, val title: String) : InFlightState
    data class Ready(
        val sortieId: Long,
        val eyebrow: String,          // "IN FLIGHT · SORTIE n", n = sortie.index + 1
        val title: String,            // plan.title (if not blank), else sortie.focus (if not blank), else "Sortie n"
        val doneCount: Int,           // DONE sets, added sets included
        val setCount: Int,            // all sets, added sets included
        val progress: List<SetStatus>,// one per set in display order (exercise order, then set order)
        val cards: List<ExerciseCard>,
        val currentSetId: Long?,      // first OPEN set of the ACTIVE card; null if there is no active card
    ) : InFlightState
}

enum class CardStatus { SKIPPED, DONE, ACTIVE, UPCOMING }

data class ExerciseCard(
    val plannedExerciseId: Long,
    val number: Int,                  // 1-based position
    val name: String,                 // displayName
    val status: CardStatus,
    val doneCount: Int,               // DONE sets in this exercise
    val setCount: Int,
    val sets: List<SetRow>,
)

data class SetRow(
    val setId: Long,
    val label: String,                // "SET n", 1-based within the exercise
    val status: SetStatus,
    val plannedReps: Int?, val plannedSeconds: Int?, val plannedWeight: Double?,
    val actualReps: Int?, val actualSeconds: Int?, val actualWeight: Double?,
    val weightLabel: String,          // "35 LB" / "20 KG" / "BW"
    val countLabel: String,           // "× 10" / "45 s"
    val added: Boolean,
    val weightDeviates: Boolean,      // DONE only: actualWeight != plannedWeight
    val countDeviates: Boolean,       // DONE only: actualReps != reps || actualSeconds != seconds
)
```

All of these are data classes or enums, so the fresh-read equality test can use `assertEquals`.

`fun deriveInFlight(plan: FlightPlanDetail?, sortie: Sortie, unit: WeightUnit): InFlightState` works as follows:
- If `plan == null` or `plan.exercises.isEmpty()`, return `NoChecklist` with the same eyebrow and title rules. This covers run sorties.
- Card status, checked in this order:
  1. `plannedExercise.skipped` → SKIPPED.
  2. No set is OPEN (every set is DONE or SKIPPED, which includes an exercise with zero sets) → DONE.
  3. The first remaining exercise that has an OPEN set → ACTIVE.
  4. Any other exercise → UPCOMING.
  A skipped exercise is never ACTIVE.
- Labels use the shown values: actuals for a DONE set, planned values otherwise.
  - Weight label: `"${formatWeight(w)} ${unit.name}"`, or `"BW"` when the weight is null.
  - Count label: `"$seconds s"` when seconds is non-null (the same precedence as `formatSetUnit`), else `"× $reps"` when reps is non-null, else `""`.
- Both deviation flags are false unless the set is DONE.

## Flow (`InFlightStates.kt`)

```kotlin
class InFlightStates(private val db: LiftoffDatabase, private val settings: SettingsStore) {
    fun observe(sortieId: Long): Flow<InFlightState> =
        db.flightPlanDao().observeChanges()
            .combine(settings.settings.map { it.weightUnit }.distinctUntilChanged()) { _, unit -> unit }
            .mapNotNull { unit ->
                val sortie = db.sortieDao().get(sortieId) ?: return@mapNotNull null
                deriveInFlight(db.flightPlanDao().getPlan(sortieId), sortie, unit)
            }
            .flowOn(Dispatchers.IO)
}
```

- **Never** put `distinctUntilChanged` on `observeChanges()`. Its count doesn't change on UPDATE; Room's re-emission on every write is the signal that a set changed. Deduplicating the weight unit and the derived state (which `stateIn` does) is fine.
- If the sortie row is missing, nothing is emitted, so the holder stays `Loading`.

## State holder (`InFlightViewModel.kt`)

```kotlin
/** Plain state holder for the In-Flight screen. */
class InFlightViewModel(db: LiftoffDatabase, settings: SettingsStore, sortieId: Long, private val scope: CoroutineScope) {
    private val dao = db.flightPlanDao()
    val state: StateFlow<InFlightState> = InFlightStates(db, settings).observe(sortieId)
        .stateIn(scope, SharingStarted.Lazily, InFlightState.Loading)

    fun check(setId: Long)            { scope.launch { dao.checkSet(setId) } }
    fun uncheck(setId: Long)          { scope.launch { dao.uncheckSet(setId) } }
    fun saveEdit(setId: Long, reps: Int?, seconds: Int?, weight: Double?) { scope.launch { dao.saveSetEdit(setId, reps, seconds, weight) } }
    fun skipSet(setId: Long)          { scope.launch { dao.skipSet(setId) } }
    fun reopenSet(setId: Long)        { scope.launch { dao.reopenSet(setId) } }
    fun skipExercise(id: Long)        { scope.launch { dao.skipExercise(id) } }
    fun unskipExercise(id: Long)      { scope.launch { dao.unskipExercise(id) } }
    fun addSet(id: Long)              { scope.launch { dao.addSet(id) } }
}
```

## DAO additions (`FlightPlanDao.kt`)

Add these protected reads, following the pattern of the existing non-suspend `getPlanBySortieId`:
- `getSet(setId): PlannedSet?`
- `getPlannedExercise(id): PlannedExercise?`
- `getLastSet(plannedExerciseId): PlannedSet?`, using `ORDER BY \`order\` DESC LIMIT 1`

Add these updates, using the same `'OPEN'`/`'SKIPPED'` literals as `markOpenSetsNotDone`:
- `skipOpenSets(plannedExerciseId)`: `UPDATE plannedSet SET status = 'SKIPPED' WHERE status = 'OPEN' AND plannedExerciseId = :id`
- `reopenSkippedSets(plannedExerciseId)`: `UPDATE plannedSet SET status = 'OPEN', actualReps = NULL, actualSeconds = NULL, actualWeight = NULL WHERE status = 'SKIPPED' AND plannedExerciseId = :id`

Add one private helper, `clearSkip(plannedExerciseId)`. If the exercise row is skipped, it calls `updateExercise(id, false, row.userNotes)`. `updateExercise` also overwrites `userNotes`, so the existing note must always be passed through.

Every public method below is `@Transaction open suspend`:

| Method | Writes |
|---|---|
| `checkSet(setId)` | read the set; `updateSetActuals(id, reps, seconds, weight, DONE)`; `clearSkip` |
| `uncheckSet(setId)` | `updateSetActuals(id, null, null, null, OPEN)`; `clearSkip` |
| `saveSetEdit(setId, reps, seconds, weight)` | `updateSetActuals(id, reps, seconds, weight, DONE)`; `clearSkip`. Planned columns are untouched. |
| `skipSet(setId)` | `updateSetActuals(id, null, null, null, SKIPPED)` |
| `reopenSet(setId)` | `updateSetActuals(id, null, null, null, OPEN)`; `clearSkip` |
| `skipExercise(id)` | `updateExercise(id, true, row.userNotes)`; `skipOpenSets(id)`. DONE sets keep their actuals. |
| `unskipExercise(id)` | `updateExercise(id, false, row.userNotes)`; `reopenSkippedSets(id)` |
| `addSet(id)` | `last = getLastSet(id)`. Pre-fill from `last`'s actuals if `last.status == DONE`, from its planned values otherwise, and with all nulls if there is no set. Then `addExtraSet(id, …)`, then `clearSkip`. |

If a set or exercise ID isn't found, the method does nothing.

## Order of work

Commit after each step, once it compiles and its tests pass. Stage the named files only, and use `area: lowercase summary` messages.

### Step 1: state model and derivation

1. Make `formatWeight` `internal`.
2. Write `InFlightState.kt`.
3. Write `InFlightStatesTest.kt`, which calls `deriveInFlight` on fresh `getPlan` reads (see the fixture below):
   - **Initial state.** Eyebrow is `IN FLIGHT · SORTIE 2` for index 1. Title is the plan title. The counter is 0/7. Progress is 7 OPEN. Cards are [ACTIVE, UPCOMING, UPCOMING] with done/set counts 0/3, 0/2, 0/2. Labels include `35 LB`, `× 10`, `BW`, `45 s`. `currentSetId` is the first bench set. `added` and both deviation flags are false.
   - **KG.** With `WeightUnit.KG`, the label is `20 KG`.
   - **Title fallbacks.** A blank plan title falls back to the focus. A blank title with a null focus gives `Sortie 2`.
   - **No checklist.** A sortie with no plan gives `NoChecklist`. A RUN sortie whose plan has no exercises (segments only) also gives `NoChecklist`. Both carry the eyebrow and title.

Commit: `inflight: add checklist state and derivation`.

### Step 2: DAO actions

1. Add the methods above to `FlightPlanDao`.
2. Add tests to `FlightPlanDaoTest`:
   - `checkSet` copies the planned values into the actuals.
   - `skipExercise` keeps a DONE set's actuals and the exercise's `userNotes`.
   - `unskipExercise` reopens the SKIPPED sets.
   - `addSet` pre-fills from the last set's actuals when it is DONE, and from its planned values when it is not.

Commit: `data: add in-flight set and exercise actions to flight plan dao`.

### Step 3: flow and state holder

1. Write `InFlightStates.kt` and `InFlightViewModel.kt`.
2. Write `InFlightViewModelTest.kt`.
   - Setup: build the scope as `CoroutineScope(Dispatchers.Unconfined + job)` and cancel it in `@After`, as `EquipmentViewModelTest` does. Do not pass `runBlocking`'s scope, because `stateIn` would keep it alive forever.
   - Settings: create `SettingsStore` from a DataStore file in `cacheDir`, as `LaunchpadStatesTest` does, and delete the file in `@After`.
   - Waiting: wait for each expected state with `withTimeout(5_000) { vm.state.first(predicate) }`.

   It covers:
   1. **Check and uncheck.** One tap on check gives DONE with actuals equal to planned, a counter of 1/7 and a DONE segment. Uncheck gives OPEN, null actuals and a counter of 0/7.
   2. **Edit with deviation.** `saveEdit(bench set 1, 8, null, 40.0)` gives DONE with actuals 8 and 40.0. Planned stays 10 and 35.0. `weightDeviates` and `countDeviates` are true, and the labels show the actuals.
   3. **Finish an exercise.** Check all bench sets. Bench becomes DONE (3/3), push-up becomes ACTIVE, and `currentSetId` is push-up set 1.
   4. **Skip and reopen a set.** The set becomes SKIPPED: the counter is unchanged and the segment is SKIPPED. Reopening makes it OPEN again.
   5. **Skip and unskip an exercise.** Check bench set 1, then skip bench. The card is SKIPPED, sets 2–3 are SKIPPED, set 1 stays DONE with its actuals, and push-up is ACTIVE. Unskip: sets 2–3 are OPEN, set 1 is still DONE, and bench is ACTIVE again.
   6. **Add a set.** Finish bench, with set 3 edited to 8 reps, then add a set to bench. The new set is OPEN, `added`, pre-filled with 8 reps and 35.0, and labelled `SET 4`. The bench card is ACTIVE again and push-up is UPCOMING. The counter becomes x/8. Also add a set after the last set has been reopened (so it is OPEN), and check it is pre-filled with that set's planned values.
   7. **Fresh read equals the flow.** After a mix of check, edit, skip set, skip exercise and add set, wait for the final state. Then assert that it equals `deriveInFlight(flightPlanDao.getPlan(id), sortieDao.get(id)!!, WeightUnit.LB)`.
   8. **No plan.** The holder emits `NoChecklist`.

Commit: `inflight: add checklist state holder with room-backed actions`.

### Gate

```bash
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
```

It must pass with zero failures. That includes `SchemaExportTest`, `InFlightScreenTest`, the Launchpad, Mission, MissionManager and domain tests, Mission Control and the shell.

## Test fixture

Seed a mission and a LIFT sortie with `index = 1`, `state = IN_FLIGHT` and `focus = "Push"`. Write its plan through `writePlan`, with the title "Upper Push" and three exercises:
1. **Weighted:** Bench Press, 3 × (reps 10, weight 35.0).
2. **Bodyweight:** Push-up, 2 × (reps 12, weight null).
3. **Mixed:** Kettlebell Hold, (reps 8, weight 20.0) and (seconds 45, weight null).

That is 7 sets in total.

## Risks

1. **`observeChanges()` deduplication.** See the Flow section. Equal derived states are collapsed by `StateFlow`, which the brief allows.
2. **`updateExercise` overwrites `userNotes`.** Every skip-flag write must pass through the note it reads from the row in the same transaction.
3. **Holder scope in tests.** A `runBlocking` scope given to `stateIn` hangs the test. Use a separate cancellable scope.

## Decisions

These are behaviors a user would notice that the brief leaves open.

**Check, uncheck, edit, reopen or add a set inside a skipped exercise.**
- **Choose:** also clear the exercise's skip flag, so the card never sits as "skipped" while its sets change underneath it.
- **Alternative:** leave the flag alone.
- Changed by review: the plan chose to clear the flag on check, reopen and add only. The review chose to clear it on uncheck and save-edit as well. Otherwise an unchecked set in a skipped exercise becomes an OPEN set that no card shows as active, and `unskipExercise` would not account for it.

**A 'no checklist' state still carries the eyebrow and title.** (Added by review)
- **Choose:** `NoChecklist` holds `eyebrow` and `title`, so the next brief's screen can keep the header for a run sortie.
- **Alternative:** a bare object.

**Skipping a DONE set.** (Added by review)
- **Choose:** the set becomes SKIPPED and its actuals are cleared, because skipped means not done.
- **Alternative:** keep the actuals.

**Unskip an exercise.**
- With no schema change, a set skipped on its own before the exercise skip can't be told apart from the sets skipped by the exercise skip.
- **Choose:** reopen every SKIPPED set in that exercise.
- **Alternative:** reopen none of the individually skipped sets, which needs a schema change and so isn't possible.

**Weight and count labels on a DONE set.**
- **Choose:** show the actuals (what was lifted) on DONE sets, and the planned values on open or skipped sets.
- **Alternative:** always show planned values.

**Deviation when the planned weight is set and the actual is null (or the other way round).**
- **Choose:** count it as a deviation.
- **Alternative:** treat null as "no change".

**What "current set" is when every card is done or skipped.**
- **Choose:** null.
- **Alternative:** point at the last set.
