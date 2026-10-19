# Tasks — bring in the export/import data layer

Five tasks. Tasks 1–4 are the brief's four cherry-picks, in the brief's order, each ending in its own commit
with its original message; task 5 is the run's one whole-suite measurement and the ARCHITECTURE.md record that
carries its numbers. No task writes an app file by hand and no task writes a test: every test named below arrives
with the pick that owns it. Read `brief.md`, `plan.md` and `map.md` before starting; the map's "The source branch
and the four picks" and "Running the gate on this machine" sections hold the facts a session would otherwise
re-derive.

- [ ] Cherry-pick the export commit `c3e1dbe`
  Brings `data/BackupDocument.kt`, `data/DatabaseExport.kt`, the bulk read / count methods on `MissionDao`,
  `SortieDao`, `EquipmentDao`, `GenerationDao` and `FlightPlanDao`, and `DatabaseExportTest` — 8 files, all under
  `app/`. `git cherry-pick c3e1dbe`, keeping the original message; no edits, no other commit from
  `rho/database-export-and-import-in-mission-fqp3`.
  Tests this task brings (verify.sh names them; do not write them):
  `exportWritesEveryRowOfEveryTableWithIdsAndBothVersions`,
  `exportFileUsesSnakeCaseKeysAndCarriesNoSettingsOrToken`.
  Done when: `git show --stat HEAD` lists exactly those 8 files and nothing under `.rho/runs/`;
  `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` passes; a cold
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*DatabaseExportTest*'` reports 2 tests,
  0 failures, 0 errors in `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

- [ ] Cherry-pick the import commit `2ff4bd9`
  Brings `data/DatabaseImport.kt`, the bulk insert / count methods and `MissionDao.deleteDraft` on the six DAOs,
  `DatabaseImportTest`, and the edit to `MissionDaoTest`'s no-wipe guard (its MissionDao half becomes
  `setOf("deleteDraft")`; its SortieDao half is unchanged) — 9 files, all under `app/`.
  Tests this task brings: `importRefusesANonEmptyDatabaseAndChangesNothing`,
  `importAcceptsADatabaseHoldingOnlyAFreshDraftAndReplacesIt`, `importRunsOnAppOpenSoTheCurrentWeekIsSettled`,
  `importReportsHowMuchWasRestored`; and it is the task that changes
  `noWipeForMissionsAndOnlyTheByIdDeleteForSorties`.
  Done when: `git show --stat HEAD` lists exactly those 9 files and nothing under `.rho/runs/`; a cold run with
  `--tests '*DatabaseImportTest*' --tests '*MissionDaoTest*'` reports 4 tests and 5 tests, 0 failures, 0 errors.

- [ ] Cherry-pick the refusals commit `f0d1720`
  Brings the refusal half of `data/DatabaseImport.kt` and the three new `DatabaseImportTest` methods, and rewrites
  the four methods the previous pick added to `runBlocking<Unit> { withTimeout(…) { … } }` — 2 files. This is the
  pick that makes `DatabaseImportTest` complete at 7 tests.
  Tests this task brings: `importRefusesAFileThatIsNotALiftoffExportAndChangesNothing`,
  `importRefusesAVersionItCannotReadAndChangesNothing`, `importWritesNothingWhenAFileHasADanglingReference`.
  Done when: `git show --stat HEAD` lists exactly those 2 files; a cold `--tests '*DatabaseImportTest*'` run
  reports 7 tests, 0 failures, 0 errors.

- [ ] Cherry-pick the round-trip commit `c89dd1d`
  Brings `app/src/test/java/com/liftoff/app/data/BackupRoundTripTest.kt` and nothing else — 1 file, no main-code
  change. If it does not apply cleanly, resolve by hand keeping both sides' intent and record what happened in
  `plan.md`'s Decisions (plan Risk 1); never merge the source branch and never rewrite a picked file from memory.
  Test this task brings: `exportThenImportIntoAFreshDatabaseGivesAnIdenticalDatabase`.
  Done when: `git show --stat HEAD` lists only that file; a cold `--tests '*BackupRoundTripTest*'` run reports
  1 test, 0 failures, 0 errors.

- [ ] Measure the whole suite once, then record export and import as built
  Plan steps 2–4, in that order, and the run's only whole-suite run. First the cold detached gate:
  `bash gradlew.sh --stop`; `mkdir -p app/build`; `rm -rf app/build/test-results/testDebugUnitTest`; then
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` detached with an `EXIT=$?`
  sentinel appended to a log under `app/build/`, waiting on `EXIT=` (a foreground call can outlast the shell
  limit). Read the `tests=`/`failures=`/`errors=` totals out of every `TEST-*.xml` — `BUILD SUCCESSFUL` alone
  proves nothing. Fix only what the totals show is broken, and re-run the same tree once before treating
  `doneBodyweightSetStepperShowsBW` (closed Room connection pool) as broken; a real fix gets its own commit,
  `area: lowercase summary`, before the docs commit. Then edit the three ARCHITECTURE.md cells the plan names —
  line 32's `com.liftoff.app.data` "Exists yet?" cell, line 84's M5 status cell (drop `export/import not
  started`, say the data layer is built and its Mission Control actions come next), and line 80's M1 status cell
  (plan Decision 1: it makes the same claim, so `export/import come later` must go even though this run's
  verify.sh does not read M1). Wording has to survive verify.sh's clause-by-clause rule, which the map records.
  Commit `docs: record export and import as built`, staging `ARCHITECTURE.md` only (plus anything the fix
  touched, if it was folded in), with the gate's totals in the body — the commit body is the only place the
  gate's numbers survive for the pull request.
  Done when: the gate log ends `EXIT=0` and every report reads 0 failures, 0 errors; the four Done-when classes
  (`MissionDaoTest`, `DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`) each appear in this
  suite run's reports; and the docs commit's body carries the totals. No UI, no `AppContainer` change, no Room
  schema change: `MissionControlScreenTest.showsNoUnbuiltPlaceholders` asserts `Export` is absent from Mission
  Control, so adding an Export action here fails the run.
