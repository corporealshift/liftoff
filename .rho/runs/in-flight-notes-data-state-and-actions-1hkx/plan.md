# Plan: In-Flight notes — data, state and actions (no UI)

## Brief summary

Give the In-Flight screen's data layer a note per exercise and a note per sortie (DESIGN.md §6, §8):

- an action that saves an exercise note, changing **only** the note — the skipped flag and the sets stay as they are;
- an action that saves the sortie note, working on a sortie with no Flight Plan too;
- blank or whitespace-only text is stored as null, other text is stored trimmed;
- the derived In-Flight state carries each card's note and the sortie note, in both the checklist and no-checklist states, and re-emits when only the `sortie` row changes;
- everything is written straight to Room, no network.

No screen changes. The next brief adds the dialogs and the visible note lines. The Room schema must not change, so `SchemaExportTest` stays green.

## What exists today (read before starting)

- `ui/inflight/InFlightState.kt` — `deriveInFlight(plan, sortie, unit)` is pure and builds either `InFlightState.NoChecklist(sortieId, eyebrow, title)` or `InFlightState.Ready(...)` with `ExerciseCard`s. Neither carries a note today. These types are constructed only inside `deriveInFlight`; no test builds them directly, so adding fields is safe.
- `ui/inflight/InFlightStates.kt` — `observe(sortieId)` combines `flightPlanDao().observeChanges()` with the weight unit, then re-reads the sortie and the plan. `observeChanges()` counts only `flightPlan`, `plannedExercise`, `plannedSet` and `runSegment`, so **it does not fire when the `sortie` row changes**. A sortie-note save would never reach the state without also observing that row. `SortieDao.observeById(sortieId)` already exists.
- `ui/inflight/InFlightViewModel.kt` — a plain holder; every action is `scope.launch { dao.x(...) }`. `sortieId` is currently a constructor parameter, not a property.
- `data/FlightPlanDao.kt` — `updateExercise(plannedExerciseId, skipped, userNotes)` already exists, as does the protected `getPlannedExercise(id)`. `skipExercise`, `unskipExercise` and `clearSkip` already preserve `userNotes`, so the column is the right home for a user note and is deliberately kept separate from the coach's `notes`. `updateExercise` touches only `plannedExercise`, never `plannedSet`.
- `data/SortieDao.kt` — an interface with a generic `update(sortie)`; no notes-only update yet.
- `data/PlannedExercise.kt` has `notes` (coach) and `userNotes`; `data/Sortie.kt` has `notes`. Both columns already exist, so no schema change is needed.

## Approach

Follow the pattern the In-Flight checklist already uses: Room is the only source of truth, `deriveInFlight` is a pure function over a fresh read, and the view model's actions are thin writes to a DAO. So the work is four small pieces:

1. a notes-only write on each DAO;
2. note fields on the state that `deriveInFlight` already builds;
3. observe the `sortie` row so a sortie-note save re-derives the state;
4. two view-model actions that normalise the text and write it.

No new tables, columns, entities or migrations. No UI.

## Reference branch

`nabu/in-flight-exercise-and-sortie-notes-svpf` exists and its commit `9c6174d` ("inflight: store exercise and sortie notes in state and actions") is exactly this brief's scope. The app code at that commit's parent is byte-identical to this branch's `HEAD` (`git diff 9c6174d^ HEAD -- app` is empty), so the reference still applies unchanged. It is a reference only: nothing is cherry-picked or merged, and every line below was re-checked against the current files.

Note for whoever runs this: on this machine `git show <branch>:<path>` is mangled by Git Bash (it rewrites `:` to `;` and `/` to `\`). Use `git diff HEAD...<branch> -- <path>` instead.

## Files and steps

### 1. `app/src/main/java/com/liftoff/app/data/SortieDao.kt`

Add a notes-only update next to the existing queries:

```kotlin
@Query("UPDATE sortie SET notes = :notes WHERE id = :sortieId")
suspend fun updateNotes(sortieId: Long, notes: String?)
```

This is what makes the sortie note work on a sortie with no Flight Plan: it never touches the plan tables.

### 2. `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt`

Add a transactional action beside `skipExercise` / `unskipExercise`, reusing the existing read and update:

```kotlin
@Transaction
open suspend fun saveExerciseNote(plannedExerciseId: Long, note: String?) {
    val ex = getPlannedExercise(plannedExerciseId) ?: return
    updateExercise(plannedExerciseId, ex.skipped, note)
}
```

Reading the row first and passing its own `skipped` back is what keeps the flag; `updateExercise` writes only `plannedExercise`, so the sets are untouched.

### 3. `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt`

- `ExerciseCard`: add `val note: String?`.
- `InFlightState.NoChecklist` and `InFlightState.Ready`: add `val sortieNote: String?`.
- In `deriveInFlight`, set `note = exerciseDetail.plannedExercise.userNotes` on the card, and `sortieNote = sortie.notes` on both states.

Both state types are built in exactly two places in this file, so those are the only call sites to update.

### 4. `app/src/main/java/com/liftoff/app/ui/inflight/InFlightStates.kt`

Observe the sortie row as well as the plan tables, using the top-level `combine` (already imported):

```kotlin
fun observe(sortieId: Long): Flow<InFlightState> =
    combine(
        db.flightPlanDao().observeChanges(),
        db.sortieDao().observeById(sortieId),
        settings.settings.map { it.weightUnit }.distinctUntilChanged(),
    ) { _, sortie, unit -> sortie to unit }
        .mapNotNull { (sortie, unit) ->
            sortie ?: return@mapNotNull null
            deriveInFlight(db.flightPlanDao().getPlan(sortieId), sortie, unit)
        }
        .flowOn(Dispatchers.IO)
```

Without this, a sortie-note save never re-derives the state, because the plan-table count does not change.

### 5. `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`

- Make `sortieId` a `private val` and hold `private val sortieDao = db.sortieDao()`.
- One private normaliser shared by both actions:
  `private fun normalizeNote(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }`
- Two actions in the existing style:
  - `fun saveExerciseNote(id: Long, text: String) { scope.launch { dao.saveExerciseNote(id, normalizeNote(text)) } }`
  - `fun saveSortieNote(text: String) { scope.launch { sortieDao.updateNotes(sortieId, normalizeNote(text)) } }`

Normalising in the view model keeps the exercise and sortie rules identical, and keeps the DAOs dumb writes.

### 6. `app/src/test/java/com/liftoff/app/ui/inflight/InFlightViewModelTest.kt`

Add five Robolectric tests to the existing class (in-memory DB, `Dispatchers.Unconfined` scope, `vm.state.first { ... }` under `withTimeout(5_000)` — the pattern every test in the file already uses):

1. `exerciseNoteSavesAndSurvivesFreshRead` — save a note on the Bench Press card, wait for `cards[0].note` in `vm.state`, then assert a fresh `deriveInFlight(flightPlanDao.getPlan(...), sortieDao.get(...), WeightUnit.LB)` carries the same note.
2. `noteOnSkippedExerciseKeepsSkipAndSets` — skip the first exercise, capture its sets from `getPlan`, save a note, then assert `plannedExercise.skipped` is still true, the card is still `CardStatus.SKIPPED`, and each set's status and actuals are unchanged.
3. `blankExerciseNoteStoresNull` — save text, then save `"   "`, and assert the card's note is null in the state and `plannedExercise.userNotes` is null in the DB.
4. `sortieNoteSavesOnPlannedSortie` — save a sortie note, wait for `Ready.sortieNote`, assert `sortieDao.get(...).notes`; then save blank and assert null in both.
5. `sortieNoteSavesOnSortieWithNoPlan` — insert a second sortie (RUN, `IN_FLIGHT`, no plan), build a view model for it, wait for `NoChecklist`, save a note, wait for `NoChecklist.sortieNote`, assert the DB row, and assert `deriveInFlight(null, sortie, unit)` carries it.

Test 5 is also the one that proves the state re-emits on a `sortie`-only change, since that sortie has no plan rows to change.

## Order

One commit, because the whole thing is one behaviour and each piece is inert without the others:

1. `SortieDao.updateNotes`
2. `FlightPlanDao.saveExerciseNote`
3. `InFlightState` fields + `deriveInFlight`
4. `InFlightStates` observes the sortie row
5. `InFlightViewModel` actions
6. the five tests

Then run the tests and commit the six files by name with `inflight: store exercise and sortie notes in state and actions` (the `area: lowercase summary` form CLAUDE.md asks for).

## Testing

- Targeted while working: `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.inflight.*"`
- Gate before committing: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`
- Must stay green: `InFlightStatesTest`, `InFlightViewModelTest`, `InFlightScreenTest`, `SchemaExportTest`, the `data` DAO tests, the domain/`MissionManager` tests, and the Launchpad, Mission and Mission Control tests.
- `SchemaExportTest` is safe by construction: only new `@Query` methods, no entity or column changes.

## Risks and uncertainties

| Risk | Mitigation |
|------|-----------|
| A sortie-note save never reaches the state, because `observeChanges()` ignores the `sortie` table | Step 4 combines `sortieDao.observeById(sortieId)`. Tests 4 and 5 wait on `vm.state`, so they fail if it is missing. |
| A note save clears the skipped flag or disturbs the sets | `saveExerciseNote` re-reads the row and passes its own `skipped` back; test 2 asserts the sets field by field. |
| Adding constructor parameters to `NoChecklist`/`Ready` breaks a caller | Only `deriveInFlight` constructs them; `InFlightScreen` reads them. `assembleDebug` catches any miss. |
| `observeById` emits `null` before the flow settles, dropping the first state | `mapNotNull` keeps the existing behaviour: no emission until the sortie row is read, same as today's `?: return@mapNotNull null`. |
| Two note saves racing on the same row | Out of scope: the UI that could cause it comes in the next brief, and each save is a single `@Transaction` write. |

## Decisions

The brief settles the visible behaviour it touches — trimming, blank-to-null, notes surviving a process kill, notes present in both checklist and no-checklist states — so those are not decisions. Three user-visible behaviours are left open:

1. **Does saving a note change what the exercise looks like?**
   **Choice: no.** A note is inert: the exercise keeps its skipped flag, its set statuses and its card status, and saving a note never un-skips or completes anything.
   *Alternative:* treat "has a note" as a sign the owner is working on that exercise and un-skip it, as checking a set already does.
   *Why:* the brief says the action changes only the note, and a user typing "left shoulder twinge" on a skipped exercise means "note this", not "do it after all". Un-skipping would silently add sets back to the checklist.

2. **Can a sortie with no Flight Plan carry a note?**
   **Choice: yes.** The no-checklist state — a run, or a lift whose plan is not ready — carries the sortie note just like the checklist state does.
   *Alternative:* allow the note only on a sortie that has a plan.
   *Why:* DESIGN.md §6 gives a note per sortie, and a run sortie is the case where a note is most useful ("felt strong, 5k at 5:00"). A user on the In-Flight screen for a run would expect the same note they get anywhere else.

Two smaller ones, recorded for completeness:

3. **Saving a note on an exercise that no longer exists.**
   **Choice: nothing happens** — the write is dropped rather than throwing.
   *Alternative:* fail loudly.
   *Why:* the plan can be regenerated under an open screen; a dropped note on a row that is gone is invisible to the user, whereas a crash is.

5. **How much of the text is kept.**
   **Choice: the whole note, with only the leading and trailing whitespace removed.** Line breaks inside it are preserved.
   *Alternative:* collapse newlines to spaces, or cap the length.
   *Why:* the brief says "stored trimmed", and a free-text note is often two lines ("tight lower back / eased after set 2"). Nothing in DESIGN.md sets a limit for a user note.
