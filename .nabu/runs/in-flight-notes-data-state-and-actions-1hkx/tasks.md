# Tasks: In-Flight notes — data, state and actions (no UI)

Four tasks, in order. Each one is a commit that compiles and passes the gate on its own.
Read `.nabu/runs/in-flight-notes-data-state-and-actions-1hkx/brief.md` and `plan.md` before
starting, then only your own entry.

Rules that apply to every task:
- Gate before committing: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`
  (targeted while working: `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.inflight.*"`).
- Never touch `.nabu/runs/in-flight-notes-data-state-and-actions-1hkx/verify.sh`.
- Stage the named files only, never `git add -A`. Commit message form is `inflight: lowercase summary`
  (CLAUDE.md). LF endings. §2 vocabulary — never "session" for a sortie.
- No Room schema change: new `@Query` methods only, no entity or column edits. `SchemaExportTest`
  must stay green throughout.
- The tests named below are the contract `verify.sh` checks; write them with exactly those names in
  `app/src/test/java/com/liftoff/app/ui/inflight/InFlightViewModelTest.kt`, following that file's
  existing pattern (in-memory Room DB, `Dispatchers.Unconfined` scope,
  `withTimeout(5_000) { vm.state.first { ... } }`, and a fresh `deriveInFlight(getPlan(...), get(...), WeightUnit.LB)`
  comparison for anything that must survive a process kill).

- [ ] Add the two notes-only DAO writes
  `data/SortieDao.kt`: `@Query("UPDATE sortie SET notes = :notes WHERE id = :sortieId")`
  `suspend fun updateNotes(sortieId: Long, notes: String?)`. `data/FlightPlanDao.kt`: an
  `@Transaction open suspend fun saveExerciseNote(plannedExerciseId: Long, note: String?)` beside
  `skipExercise`/`unskipExercise`, which reads the row with the existing protected
  `getPlannedExercise`, returns if it is gone, and calls the existing `updateExercise(id, ex.skipped, note)`
  so the skipped flag is passed back unchanged and `plannedSet` is never touched. Plan §1–2.
  Done when: the gate passes; no new `verify.sh` test belongs to this task; the existing
  `com.liftoff.app.data.SchemaExportTest.exportedV1SchemaMatchesDatabase`,
  `FlightPlanDaoTest.updateExerciseSetsSkippedAndUserNotes` and
  `FlightPlanDaoTest.skipExerciseKeepsDoneSetActualsAndUserNotes` still pass; nothing outside these
  two files changed. Commit `inflight: add notes-only dao writes`.

- [ ] Carry the notes in the derived state and re-derive on a sortie-row change
  `ui/inflight/InFlightState.kt`: `val note: String?` on `ExerciseCard`, `val sortieNote: String?` on
  both `InFlightState.NoChecklist` and `InFlightState.Ready`; `deriveInFlight` sets
  `note = exerciseDetail.plannedExercise.userNotes` on each card and `sortieNote = sortie.notes` on both
  states (its two construction sites are the only call sites — `InFlightScreen` only reads them).
  `ui/inflight/InFlightStates.kt`: `observe(sortieId)` must combine
  `db.sortieDao().observeById(sortieId)` with `observeChanges()` and the weight unit, and keep the
  existing "no emission until the sortie row is read" behaviour (`mapNotNull`, `flowOn(Dispatchers.IO)`).
  This is required because `observeChanges()` counts only `flightPlan`, `plannedExercise`, `plannedSet`
  and `runSegment`, so a `sortie`-only write would never reach the state. Plan §3–4.
  Done when: the gate passes, `assembleDebug` compiles `InFlightScreen` unchanged, and the existing
  `com.liftoff.app.ui.inflight.InFlightStatesTest.initialState`,
  `com.liftoff.app.ui.inflight.InFlightViewModelTest.freshReadEqualsFlow` and
  `com.liftoff.app.ui.inflight.InFlightScreenTest.checkingASetMarksItDoneInRoomAndUpdatesCounter`
  all still pass (the last two compare the emitted state with a fresh `deriveInFlight`, so they fail if
  the note fields are set in one place and not the other). Commit
  `inflight: carry exercise and sortie notes in derived state`.

- [ ] Save an exercise note, with its tests
  `ui/inflight/InFlightViewModel.kt`: make `sortieId` a `private val`, hold
  `private val sortieDao = db.sortieDao()`, add the shared
  `private fun normalizeNote(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }`, and
  `fun saveExerciseNote(id: Long, text: String) { scope.launch { dao.saveExerciseNote(id, normalizeNote(text)) } }`
  in the existing action style. Normalising in the view model keeps the exercise and sortie rules
  identical and the DAOs dumb writes. Plan §5.
  Write these tests: `exerciseNoteSavesAndSurvivesFreshRead`,
  `noteOnSkippedExerciseKeepsSkipAndSets`, `blankExerciseNoteStoresNull`.
  - `exerciseNoteSavesAndSurvivesFreshRead` — save a note on the Bench Press card, wait for
    `cards[0].note` in `vm.state`, then assert the same note on
    `deriveInFlight(flightPlanDao.getPlan(...), sortieDao.get(...), WeightUnit.LB)`.
  - `noteOnSkippedExerciseKeepsSkipAndSets` — skip the first exercise, capture its sets from
    `getPlan`, save a note, then assert `plannedExercise.skipped` is still true, the card is still
    `CardStatus.SKIPPED`, and every set's status and actuals are identical to before the note.
  - `blankExerciseNoteStoresNull` — save text, then save `"   "`, and assert the card's note is null in
    the emitted state and `plannedExercise.userNotes` is null in the DB (not `"   "`).
  Done when: the gate passes and those three tests ran and passed. Commit
  `inflight: save exercise notes through the view model`.

- [ ] Save the sortie note, including on a sortie with no Flight Plan, with its tests
  `ui/inflight/InFlightViewModel.kt`: `fun saveSortieNote(text: String) { scope.launch { sortieDao.updateNotes(sortieId, normalizeNote(text)) } }`,
  reusing task 3's `normalizeNote`. It never touches the plan tables, which is what makes it work with
  no plan. Plan §5.
  Write these tests: `sortieNoteSavesOnPlannedSortie`, `sortieNoteSavesOnSortieWithNoPlan`,
  `notesAreStoredTrimmed`.
  - `sortieNoteSavesOnPlannedSortie` — save a sortie note on the fixture sortie, wait for
    `Ready.sortieNote` in `vm.state` (the plan tables did not change, so this also proves the
    sortie-row-only re-derivation from task 2), assert `sortieDao.get(...).notes`; then save blank and
    assert null in both the state and the row.
  - `sortieNoteSavesOnSortieWithNoPlan` — insert a second sortie (RUN, `IN_FLIGHT`, no plan), build a
    view model for it, wait for `NoChecklist` and assert its `sortieNote` starts null, save a note, wait
    for `NoChecklist.sortieNote`, assert the `sortie` row, and assert
    `deriveInFlight(null, sortie, unit)` carries it.
  - `notesAreStoredTrimmed` — padding around an exercise note and around a sortie note comes back
    trimmed, and a line break inside a note survives the save (plan Decision 5: the whole note, trimmed
    at the ends only).
  Done when: the gate passes and those three tests ran and passed, together with the three from task 3,
  and `SchemaExportTest.exportedV1SchemaMatchesDatabase`, `InFlightStatesTest.initialState`,
  `InFlightViewModelTest.checkAndUncheck` and `freshReadEqualsFlow`, and
  `InFlightScreenTest.checkingASetMarksItDoneInRoomAndUpdatesCounter` are all still green. Commit
  `inflight: save sortie notes including on sorties with no plan`.
