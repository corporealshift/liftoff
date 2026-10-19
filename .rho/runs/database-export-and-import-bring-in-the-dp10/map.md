# Map — bring in the export/import data layer

Run `database-export-and-import-bring-in-the-dp10`. Branch `rho/database-export-and-import-bring-in-the-dp10`,
2 ahead of merge-base `e691908` (Re-fly #26): `52f363d run: brief`, `a047079 run: plan outline`. Tree clean.
This run adds no UI: it cherry-picks finished code and edits two ARCHITECTURE.md cells.

## The source branch and the four picks (checked 2026-10-19)

- Source branch `rho/database-export-and-import-in-mission-fqp3`. `git merge-base HEAD <that branch>` =
  `e691908`, which is also this branch's own base, so the finished commits sit directly on this branch's base.
- `git diff --stat e691908..HEAD -- app/` is **empty**: this branch has changed no app file since the base.
  That is what predicts a clean cherry-pick.
- The four picks, in the brief's order, and the files each touches (all under `app/`):
  - `c3e1dbe` export → `BackupDocument.kt` (+153), `DatabaseExport.kt` (+152), `EquipmentDao` (+4),
    `FlightPlanDao` (+13), `GenerationDao` (+4), `MissionDao` (+4), `SortieDao` (+4), `DatabaseExportTest` (+306)
  - `2ff4bd9` import → `DatabaseImport.kt` (+209), `EquipmentDao` (+8), `ExerciseDao` (+8), `FlightPlanDao` (+25),
    `GenerationDao` (+7), `MissionDao` (+12), `SortieDao` (+3), `DatabaseImportTest` (+270), `MissionDaoTest` (+7 −3)
  - `f0d1720` refusals → `DatabaseImport.kt` (+88 −32), `DatabaseImportTest` (+219 −32)
  - `c89dd1d` round trip → `BackupRoundTripTest.kt` (+413)
- Between them sit `run: task N done` commits (`55dcabb`, `a90b69a`, `3f23326`) and `run: tasks` / `run: verify` /
  `run: plan …` — all of them touch **only** `.rho/runs/database-export-and-import-in-mission-fqp3/`
  (`tasks.md`, `map.md`, `verify.sh`). Skipping every `run:` commit is what keeps that run's records out, as the
  brief requires.
- `git show <branch>:<path>` fails in Git Bash on this machine. To read a file from the other branch use
  `git diff HEAD...rho/database-export-and-import-in-mission-fqp3 -- <path>`, or `git grep -n <pat> <branch> -- <path>`.

## Project docs that set rules

- `ARCHITECTURE.md` line 32, `com.liftoff.app.data` row: Responsibility already reads "…export/import helpers";
  the "Exists yet?" cell (field 4 of the `|`-split line) reads `✅ (Room entities, DAOs, LiftoffDatabase)` and is
  the cell the brief wants to name export and import.
- `ARCHITECTURE.md` line 80, M1 status cell: `Done — test connection and export/import come later`.
- `ARCHITECTURE.md` line 84, M5 status cell ends `export/import not started`.
- `CLAUDE.md`: gate via `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (nothing is on PATH);
  `area: lowercase summary` commit messages; stage named files only; LF endings; short comments (why, not what).
  Precedent for a docs-only commit: `docs: mark re-fly built under M5`, `docs: list the coach prompt builder in
  the architecture map`.
- Invariants (`ARCHITECTURE.md` 58-69): #5 no DI framework, one `AppContainer` builds everything; #2 `domain/` and
  `coach/` are pure Kotlin (`data/` is not held to that).
- The source branch's own `verify.sh` (read-only here; never copy it) shows the wording a gate written the same way
  would accept: it greps the `com.liftoff.app.data` row and requires field 4 of the `|`-split line to name export
  and import; it greps `| M1 |` field 4 and rejects a cell that puts export/import in the same clause as a
  not-built word, splitting the cell on `;` `,` `.` `—` `–` `-` — so `Done — test connection comes later; export
  and import built` passes and a bare `Done` fails. It reads **M5 not at all**; M5 is the brief's own requirement.

## What the picked code is (API surface, from the source branch)

- `data/BackupDocument.kt`: `@Serializable` `BackupDocument(header, missions, sorties, flight_plans,
  planned_exercises, planned_sets, run_segments, exercises, generations, equipment)` plus one `Backup*` row type
  per table. Every key is `@SerialName` snake_case, because three tables store camelCase columns.
  `BackupMission.weekStart` is a `String` — an ISO date, not Room's epoch-day number.
- `data/DatabaseExport.kt`: `suspend fun exportDatabase(db: LiftoffDatabase, out: OutputStream,
  clock: Clock = Clock.systemUTC())` — one `db.withTransaction`, header `format_version` =
  `BACKUP_FORMAT_VERSION`, `schema_version` = `roomSchemaVersion(db)`, `exported_at` = `clock.millis()`.
  `internal fun roomSchemaVersion(db) = db.openHelper.readableDatabase.version` — the `@Database` annotation is
  **not** readable by reflection here (`getAnnotation` returns null), so SQLite's `user_version` is what the app
  is running. Private `toBackup()` per entity.
- `data/DatabaseImport.kt`: `sealed interface ImportResult { data class Refused(message); data class
  Restored(missions, sorties) }` and `suspend fun importDatabase(db, input: InputStream,
  manager: MissionManager): ImportResult`. `Json { ignoreUnknownKeys = true }`; five private message constants
  (`NOT_EMPTY_MESSAGE`, `NOT_A_LIFTOFF_FILE_MESSAGE`, `NEWER_LIFTOFF_MESSAGE`, `UNKNOWN_FORMAT_MESSAGE`,
  `INCOMPLETE_FILE_MESSAGE`); `KNOWN_FORMAT_VERSIONS = setOf(BACKUP_FORMAT_VERSION)`; a private
  `Emptiness` (`Empty` / `FreshDraft(mission)` / `Occupied`) decides whether the database may be written into.
- DAO methods the picks add (all `suspend`, `ORDER BY id` so two exports of the same data read alike):
  `MissionDao.getAll/insertAll/countAll` + `deleteDraft(id)` — `@Query("DELETE FROM mission WHERE id = :id AND
  status = 'DRAFT'")`, the status guard being what stops the cascade wiping a week's history;
  `SortieDao.getAll/countAll`; `EquipmentDao.getAll/insertAll/countAll` (its `insertRaw` is `protected`, so import
  needs its own `@Insert`); `ExerciseDao.insertAll/countAll`; `GenerationDao.getAll/insertAll/countAll`;
  `FlightPlanDao.getAllPlans/getAllPlannedExercises/getAllPlannedSets/getAllRunSegments` + matching
  `insertAll*` and `count*`. `MissionDao`, `ExerciseDao`, `EquipmentDao`, `FlightPlanDao` are `abstract class`
  (new methods `abstract suspend fun`); `SortieDao` and `GenerationDao` are interfaces.
- `MissionDaoTest.noWipeForMissionsAndOnlyTheByIdDeleteForSorties` (line ~85) reflects over `MissionDao::class`
  and `SortieDao::class` method names. Before the picks it asserts MissionDao has **no** delete at all; `2ff4bd9`
  rewrites that half to `setOf("deleteDraft")` and leaves the SortieDao half asserting `setOf("deleteById")`.
  Nothing else in the suite reflects over DAO method names.

## Files this run changes

- `ARCHITECTURE.md` lines 32, 80, 84 (see the Decisions entry for why M1 is included).
- Everything else arrives by cherry-pick; no app file is written by hand in this run.

## Tests that must pass, and the shape they use

- Picked classes in `app/src/test/java/com/liftoff/app/data/`: `DatabaseExportTest`
  (`exportWritesEveryRowOfEveryTableWithIdsAndBothVersions`,
  `exportFileUsesSnakeCaseKeysAndCarriesNoSettingsOrToken`); `DatabaseImportTest`
  (`importRefusesANonEmptyDatabaseAndChangesNothing`,
  `importRefusesAFileThatIsNotALiftoffExportAndChangesNothing`,
  `importRefusesAVersionItCannotReadAndChangesNothing`,
  `importWritesNothingWhenAFileHasADanglingReference`,
  `importAcceptsADatabaseHoldingOnlyAFreshDraftAndReplacesIt`,
  `importRunsOnAppOpenSoTheCurrentWeekIsSettled`, `importReportsHowMuchWasRestored`); `BackupRoundTripTest`
  (`exportThenImportIntoAFreshDatabaseGivesAnIdenticalDatabase`). Ten test methods between them.
- `MissionDaoTest` — the guard test the picks edit.
- Shape those tests use: `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`,
  `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`, `TemporaryFolder` for the `SettingsStore` file
  (`MissionManager` needs one), `Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)`-style pins,
  and every test body wrapped in `runBlocking<Unit> { withTimeout(…) { … } }` — Robolectric + Room can hang here.
- `SchemaExportTest` is about Room's *schema* JSON at `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json`
  (from the `room.schemaLocation` KSP arg) — a different thing from this brief's export file. No name collision.
- Settings and the daemon token live in DataStore (`settings/SettingsStore.kt`), not Room, so export excludes them
  by never reading DataStore.
- `LiftoffDatabase.kt`: `@Database(version = 1, exportSchema = true)`, 9 entities (Exercise, Equipment,
  Generation, Mission, Sortie, FlightPlan, PlannedExercise, PlannedSet, RunSegment), 6 DAOs exposed —
  plannedExercise / plannedSet / runSegment are reached through `FlightPlanDao`. Comment at line 23: never set up
  destructive migration. All ids are `@PrimaryKey(autoGenerate = true) val id: Long = 0`, so an explicit non-zero
  id survives an insert — that is how import keeps ids.
- `AppContainer.kt`: `settingsStore` (DataStore "settings"), `database` (Room "liftoff.db"), `missionManager`.
  `LiftoffApplication.container` is the only wiring. `MissionManager(db, settingsStore, clock, zone)` with
  `onAppOpen()` rolling past missions closed and drafting the current week.
- Nothing in `ui/control/` mentions export, import or backup today — confirms there is no UI to leave alone.
  `MissionControlScreenTest.showsNoUnbuiltPlaceholders` (line 219) asserts `assertDoesNotExist("Export",
  ignoreCase = false)` at line 228, so adding a Mission Control Export action in this run would fail it. With no
  UI added, that test stays green untouched.
- Known flake: `doneBodyweightSetStepperShowsBW` (In-Flight) sometimes fails with a closed Room connection pool
  and passes on a re-run of the same tree.

## This run's verify.sh (written 2026-10-19)

- Gate call, copied from `.github/workflows/ci.yml` (the `gate` job runs `./gradlew :app:assembleDebug` then
  `:app:testDebugUnitTest`): `timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
  --no-build-cache`, after `--stop`, `mkdir -p app/build` and deleting `app/build/test-results/testDebugUnitTest`
  so a restored FROM-CACHE report cannot stand in for tests that never ran. `BUILD SUCCESSFUL` carries no counts,
  so every named test is looked up in this run's own JUnit XML.
- One `index_reports` awk pass indexes every `TEST-*.xml` into `app/build/verify-reports.tsv` as
  `name<TAB>status<TAB>report` plus `C:<classname>` rows, ranking pass < failure < error < skipped, so a method
  that fails in one report still fails the run even if another report has it passing. `t`/`c` then look a name or
  a class up there and fail on "did not run at all".
- The 10 new test methods above are named individually; `MissionDaoTest` and the three picked classes are also
  required to have run at all. `MissionDaoTest.noWipeForMissionsAndOnlyTheByIdDeleteForSorties` is named because
  `2ff4bd9` edits it.
- Existing tests named as "must not break": `exportedV1SchemaMatchesDatabase` + `hasNoDeleteOperation` +
  `hasNoCascadeDeleteOfMissionHistory` (Room schema and the no-wipe guard), the four `onAppOpen*` tests,
  `neverSaysSession`, `outlinePromptMatchesGoldenFile`, `generationRunWritesCoachReplyToTheSortie`,
  `showsNoUnbuiltPlaceholders` (this run adds no UI, so Mission Control must still offer no Export action), and
  `landedMissionHistoryIsVisibleFromMissionControlAndSurvivesReopenAndColdStart`.
- Doc cells are read clause by clause (`sed 's/[;,.—–-]/\n/g'`). A clause counts as not-built when it names
  export+import with a flat negation, or with a deferral (`later`, `to come`) that the same clause does not also
  call built; a separate clause deferring something else is not held against it — so
  `…data layer is built; its Mission Control export and import actions come next` reads as built. A deferral with
  no built word anywhere (`export and import come next`) still fails, because the built half is required too.
  All three cells must read as built, and the data row must name export and import.
- `OTHER_RUN=".rho/runs/database-export-and-import-in-mission-fqp3"`: that directory must not exist in the tree,
  and no commit reachable only through this run's picks may touch it — that is the brief's "its run records stay
  out of this repository".

## Running the gate on this machine

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` from the repo root (`gradlew.sh` pins
JAVA_HOME/ANDROID_HOME under `~/android-toolchain`). A whole-suite run can outlast a shell command's limit:
`bash gradlew.sh --stop`; `mkdir -p app/build`; `rm -rf app/build/test-results/testDebugUnitTest`; then a detached
run with `EXIT=$?` appended to a log under `app/build/`, and `wait` on `EXIT=`. Read `tests=`/`failures=`/`errors=`
on each `app/build/test-results/testDebugUnitTest/TEST-*.xml` — `BUILD SUCCESSFUL` carries no counts. Scratch files
go under `app/build/`. While iterating, compile-check with `bash gradlew.sh :app:compileDebugKotlin
:app:compileDebugUnitTestKotlin` and run one test class at a time with `--no-build-cache --tests '*ClassName*'`;
never a package wildcard or the whole suite in a foreground call.
