# Plan

## Approach
An earlier run wrote and tested this data layer on `rho/database-export-and-import-in-mission-fqp3`, cut from the same commit this branch sits on, and stopped before its UI. Cherry-picking its four data commits brings the code and its tests over unchanged, which is what the brief asks for and is safe here: the merge-base is this branch's own base and this branch has changed no file under `app/` since, so there is nothing to reconcile (see the map's pre-flight). The only work this run does itself is three ARCHITECTURE.md cells and one cold whole-suite gate.

## Files
Arrive by cherry-pick, unchanged — the map lists each file's per-commit line counts:

- `app/src/main/java/com/liftoff/app/data/BackupDocument.kt`, `DatabaseExport.kt`, `DatabaseImport.kt` — new.
- `app/src/main/java/com/liftoff/app/data/MissionDao.kt`, `SortieDao.kt`, `ExerciseDao.kt`, `EquipmentDao.kt`, `GenerationDao.kt`, `FlightPlanDao.kt` — bulk read / bulk insert / count methods, and `MissionDao.deleteDraft`.
- `app/src/test/java/com/liftoff/app/data/DatabaseExportTest.kt`, `DatabaseImportTest.kt`, `BackupRoundTripTest.kt` — new.
- `app/src/test/java/com/liftoff/app/data/MissionDaoTest.kt` — its no-wipe guard rewritten to allow exactly `deleteDraft`.

Written by this run:

- `ARCHITECTURE.md` — the only file edited by hand. Line 32's "Exists yet?" cell names export and import; line 84's M5 status says the export/import data layer is built and its Mission Control actions come next; line 80's M1 status stops putting export/import with a not-built word (see Decisions).
- `.rho/runs/database-export-and-import-bring-in-the-dp10/plan.md` and `map.md` — this session only.

Nothing else. No UI file, no `AppContainer` change, no build file, no Room schema change.

## Order
1. Cherry-pick `c3e1dbe`, `2ff4bd9`, `f0d1720`, `c89dd1d` in that order, keeping each original message. No `run:` commit from the source branch, so no `.rho/runs/database-export-and-import-in-mission-fqp3/` file lands. After each pick, `git show --stat` shows only files under `app/`.
2. Run the gate once, detached, immediately after the picks and before any other change: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, read as the map describes. This is the run's one whole-suite measurement.
3. Fix only what that gate shows is broken — expected: nothing, since this branch changed no app file since the base. Re-run a failing class once before treating it as broken (the known In-Flight flake).
4. Update the three ARCHITECTURE.md cells. Stage `ARCHITECTURE.md` plus anything step 3 had to fix, and commit `docs: record export and import as built`, with the gate's `tests=` / `failures=` / `errors=` totals in the body — the gate's numbers are the run's evidence and only a commit carries them. Re-run the gate only if step 3 changed code.

## Testing
This run writes no test. The evidence is that the ten picked test methods and the edited guard pass, and that nothing else moved. Each behaviour below is already shown by a named test in a picked class (all Robolectric with an in-memory Room database); the map lists the method names.

- Export writes every row of every table, ids included, with both versions — `DatabaseExportTest`.
- Keys are snake_case and Settings and the daemon token are never in the file — `DatabaseExportTest`.
- A non-empty database is refused and nothing changes — `DatabaseImportTest`.
- A malformed file, a file that is not a Liftoff export, and an unknown or newer version are each refused with their own message, and nothing changes — `DatabaseImportTest`.
- A file with a dangling reference leaves nothing behind — `DatabaseImportTest`.
- A database holding only the fresh DRAFT Mission counts as empty and that draft is replaced — `DatabaseImportTest`.
- Import runs `MissionManager.onAppOpen()` so the current week is settled, and reports how many Missions and sorties it restored — `DatabaseImportTest`.
- Export then import into a fresh database gives an identical database, ids included — `BackupRoundTripTest`.
- `MissionDao`'s only delete is `deleteDraft`, and `SortieDao`'s only delete is still `deleteById` — `MissionDaoTest`, the guard the picks edit.
- Nothing else moved: the whole suite cold, once, after the picks. Per-class runs are not enough — the guards that live outside the picked classes (`MissionControlScreenTest`'s "no unbuilt placeholders" assertion, the shell tests, the `MissionManager` tests) name export and import nowhere, and only a whole-suite run shows they still hold.

## Risks
- **A cherry-pick conflicts.** Expected not to happen (map's pre-flight). If it does, resolve by hand keeping both sides' intent, or re-apply that one commit's own diff with `git diff HEAD...<branch> -- <path>`; never merge the branch, never rewrite the file from memory. Record it in Decisions.
- **A `run:` commit gets picked by mistake** and drags the other run's `tasks.md` / `verify.sh` / `map.md` into this branch. Pick the four named shas only, and check `git show --stat` after each.
- **Adding a Mission Control action "while here" would break `MissionControlScreenTest.showsNoUnbuiltPlaceholders`**, which asserts `Export` is absent from the screen. This run adds no UI, so that test is left alone and stays green.
- **The gate outlasts a shell command's limit.** Run it detached with the `EXIT=$?` sentinel under `app/build/` and `wait` on `EXIT=`; `BUILD SUCCESSFUL` alone proves nothing, so read the XML totals.
- **A hung Robolectric + Room run.** Every picked test already wraps its body in a timeout; add none without one. If a run hangs anyway, `jstack` the worker before touching code, and `bash gradlew.sh --stop` first.
- **`doneBodyweightSetStepperShowsBW` fails with a closed connection pool.** Re-run the same tree before treating it as broken.
- **The ARCHITECTURE.md cells may be read as text by the verify step** — the previous run's verify.sh read line 32's third cell and M1's status cell, and not M5 at all. Line 32's cell must literally name export and import; M5's cell must no longer read `export/import not started`. Wording that only paraphrases ("safety nets built") satisfies neither.
- **A Room schema change sneaking in.** The picks add no entity, column or migration; if the gate reports a schema mismatch, that is a pick error, not something to migrate around.

## Decisions
**ARCHITECTURE.md's M1 row is corrected too, not only the two cells the brief names.** The brief asks for the `com.liftoff.app.data` "Exists yet?" cell and M5's status. M1's status cell makes the same claim — "Done — test connection and export/import come later" — and would then contradict the two cells this run just changed, a few rows away in the same table. The alternative is to touch only the two cells the brief names, which leaves a reader of the milestone table with one claim stated two ways. Correcting the claim wherever it appears is what someone reading the document would expect, and it is what the previous run did: its verify.sh grepped M1's status cell and rejected a cell that still put export/import with a not-built word, so a gate written the same way here would fail on an untouched M1. The owner can drop this clause if they want the diff to match the brief exactly.

**Nothing in the app can export or import yet, and the data layer is not pre-wired.** After this run the export and import functions exist and are tested, but no screen calls them: `AppContainer` gains nothing and Mission Control is untouched. The alternative is to add an `AppContainer` accessor now so the next run starts closer to its UI. The brief says the Mission Control actions come next, and a holder nothing calls is code the gate cannot exercise; the wiring belongs with the file picker and the result line that will use it.

**If a cherry-pick conflicts, keep both sides' intent and record it here.** Pre-flight says a clean apply is what to expect: `git merge-base HEAD rho/database-export-and-import-in-mission-fqp3` is `e691908` (Re-fly #26), which is also this branch's own base, and this branch has changed no file under `app/` since — the four picks touch only `app/src/main/java/com/liftoff/app/data/` and `app/src/test/java/com/liftoff/app/data/`. If a pick does conflict, resolve it by hand in the conflicted file, keeping what each side's tests assert; do not merge the branch, and do not re-derive a file from scratch, because the picked code is already tested and rewriting it loses what its tests prove. Whatever happened gets written down in this entry.

**The behaviour the picked commits already implement is not re-decided by this run.** `week_start` as an ISO date, snake_case keys via `@SerialName`, the five refusal messages, `Restored(missions, sorties)` as the count, and `db.openHelper.readableDatabase.version` as the schema version are all already in the picked code and already covered by its tests. The brief says they must still hold, so this run changes none of them.

Everything else the brief touches is settled by the brief itself: the emptiness rule and its one draft exception, the four refusal cases, ids kept, `onAppOpen()` after import, the counts reported, no UI, and the gate.
