# Map — issue 64: MissionDao.observeRaw needs @Transaction

Run dir: `.rho/runs/issue-64-missiondao-observeraw-needs-qx2x/`. HEAD `4fb6d8f` (this run's plan,
revised after the plan review); previous `b434e83` plan outline; the brief's review was done at
`9454f0f`.
Room 2.6.1, declared inline in `app/build.gradle.kts:73-75` (runtime, ktx, KSP compiler) plus
`room-testing` at `:83`; there is no `gradle/libs.versions.toml` in this repo.

## The warning, confirmed on this tree

`gradle :app:kspDebugKotlin` (Room's KSP processor) prints exactly one `w:` line:

```
w: file:///.../app/src/main/java/com/liftoff/app/data/MissionDao.kt:50:23 The return value
includes a POJO with a @Relation. It is usually desired to annotate this method with
@Transaction to avoid possibility of inconsistent results between the POJO and its relations.
```

Room's message key is `ROOM_RELATION_QUERY_WITHOUT_TRANSACTION` (string in
`room-compiler-2.6.1.jar`, `androidx/room/compiler/messages/RoomMessages.class`). A rerun with
`--rerun-tasks --no-build-cache` printed the same single warning, so it is not a cache artifact.
Logs from the plan session: gitignored `app/build/plan64/ksp.log` (not committed).

Three things a gate script has to know about this warning, measured on the untouched tree at
`4fb6d8f`:

- With `--console=plain` the line is `w: [ksp] /abs/path/MissionDao.kt:50: <message>` — `[ksp]`
  prefix, no `file:///`, no column. `grep '^w:'` catches both shapes.
- **A warm gate run prints no `w:` line at all**: `:app:kspDebugKotlin` is `UP-TO-DATE` there, so its
  warnings are never printed whether or not the annotation is present. "The gate log has no `w:`" is
  vacuous unless the task is forced; `:app:kspDebugKotlin --rerun-tasks --no-build-cache` is 14 tasks
  in ~3s warm and always prints it.
- `:app:compileDebugUnitTestKotlin`, when it executes (always in CI, not in a warm local run), prints
  four pre-existing `w:` lines of its own: `SchemaExportTest.kt:114` and `:115` (Java type mismatch),
  `MissionDaoTest.kt:89` (nullable receiver), `MissionDaoTest.kt:91` ×2 (condition always true). Those
  are not Room's warnings, so a whole-gate-log `w:` grep would fail on unrelated code.
- Full gate baseline: `:app:assembleDebug :app:testDebugUnitTest --no-build-cache` → BUILD SUCCESSFUL
  in 1m10s warm, 93 test classes, 775 test records, 0 failures, 0 skipped.

## Files involved

- `app/src/main/java/com/liftoff/app/data/MissionDao.kt` (57 lines) — the file to change.
  - `:49-50` `observeRaw` — `@Query("SELECT * FROM mission WHERE weekStart = :weekStart")` on a
    `protected abstract fun` returning `Flow<MissionWithSorties?>`. The warned method, and the one
    to annotate.
  - `:52-56` `observeWeek` — `open fun`, **no Room annotation**, so Room ignores it: it maps
    `observeRaw`'s emission with `result?.copy(sorties = result.sorties.sortedBy { it.index })`.
    Not the target; it carries no `@Query`.
  - The rest of the file (`:16-47`) is single-statement `@Query` methods, including the two deletes
    (`deleteDraft` guarded to one DRAFT row, `deleteWeek`). Nothing after `observeWeek`.
- `app/src/main/java/com/liftoff/app/data/Relations.kt` (73 lines) — `MissionWithSorties` at `:6-13`:
  `@Embedded val mission: Mission` plus `@Relation(parentColumn = "id", entityColumn = "missionId")`
  on `val sorties: List<Sortie>` (`:8-12`). **The only `@Relation` in `app/src/main`**
  (`git grep -n "@Relation" HEAD -- app/src/main` → that one site), so `observeRaw` is the only
  `@Relation` query in the DAOs and the brief's "any other" sweep comes up empty. The file's other
  data classes are plain query POJOs (`PlannedExerciseRow`, `FlightPlanDetail`, …), none with a
  relation.
- `app/src/main/java/com/liftoff/app/data/LiftoffDatabase.kt` — abstract `RoomDatabase`, seven
  entities, `version = 1`, `exportSchema = false`. No `RoomDatabase.Callback` and no query callback
  today, so a test that wants one builds its own database.
- `app/src/test/java/com/liftoff/app/data/MissionDaoTest.kt` (95 lines) — where the new test goes.
  `@RunWith(RobolectricTestRunner)`; `createDb` builds
  `Room.inMemoryDatabaseBuilder(..., LiftoffDatabase::class.java).allowMainThreadQueries().build()`
  and `closeDb` closes it; cases are `= runBlocking { … }` and take the Flow with `first()`.
  Five cases today: `observeWeekReturnsSortiesInIndexOrder` (`:40`),
  `observeWeekIsNullForWeekWithoutMission` (`:53`), `missionAndSortieUpdatesAreVisible` (`:59`),
  `duplicateWeekStartIsRejected` (`:75`), and `noWipeForMissionsAndOnlyTheByIdDeleteForSorties`
  (`:88`) — a reflection case over `MissionDao::class.java.methods` that asserts method *names*, the
  only reflection-shaped case in the file.
- Consumers of the Flow, unchanged by this run: `ui/launchpad/LaunchpadState.kt:101` and
  `ui/mission/MissionState.kt:112`, both `db.missionDao().observeWeek(ws)` inside a
  `flatMapLatest`/`map` chain that is `flowOn(Dispatchers.IO)`. Room's generated transaction begins
  on that IO thread, at collection.
- Tests that already watch this Flow: `ui/control/BackupImportRoomTest.kt:208`
  (`watch(target.missionDao().observeWeek(thisWeek))`) with `assertReEmitted("observeWeek", …)` at
  `:252` — it asserts the emission before and after an import, so it is the test most likely to
  notice a transaction change. `ui/launchpad/LaunchpadStatesTest.kt` is the suite that exercises
  `observeWeek` end to end (its comments at `:277`, `:348`, `:369` name the `@Relation` on sorties
  as the re-emit path); `ui/inflight` and `ui/landed` build real in-memory DAOs too.
- `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt` (347 lines) — the DAO that already does
  this correctly: `:125-126` `@Transaction open suspend fun getPlan(...)` assembles
  `FlightPlanDetail` from several protected `@Query` methods; `:62`, `:198`, `:280-335` annotate the
  write paths. Its protected queries return plain rows (no `@Relation`), so Room warns about none of
  them. `ExerciseDao.kt:26` `@Transaction open suspend fun resolve(...)` is the other annotated
  multi-statement method in the layer. `git grep -n "@Transaction" HEAD -- app/src/main` lists every
  annotated method: only `ExerciseDao` and `FlightPlanDao`.
- `SortieDao.kt`, `EquipmentDao.kt`, `GenerationDao.kt` and the rest of `MissionDao.kt` contain only
  single-statement methods, no `@Transaction` and no `@Relation`. Checked, so the work session does
  not re-check.

## Room facts verified from the 2.6.1 jars (so the work session need not re-derive them)

- `@Transaction` **is** supported on a `Flow`-returning `@Query`. Room's Flow binder
  `CoroutineFlowResultBinder` extends `BaseObservableQueryResultBinder`, whose
  `createRunQueryAndReturnStatements(..., isTransaction: Boolean, ...)` emits, when the flag is set,
  `if (!db.inTransaction()) db.beginTransaction()` … `db.setTransactionSuccessful()` …
  `finally { db.endTransaction() }` (strings in `androidx/room/solver/query/result/TransactionWrapperKt*`).
  The compiler's `@Transaction` errors are about *non-`@Query`* transaction methods ("…must not be
  private, final, or abstract. It can be abstract only if the method is also annotated with
  @Query") — `observeRaw` is `protected abstract` **and** `@Query`, so it satisfies that.
- Generated output is **Java**, not Kotlin (no `room.generateKotlin` ksp arg in
  `app/build.gradle.kts`): `app/build/generated/ksp/debug/java/com/liftoff/app/data/MissionDao_Impl.java`
  (811 lines today). Today's `observeRaw` is `:570-649`: it builds
  `CoroutinesRoom.createFlow(__db, false, new String[]{"sortie","mission"}, callable)` (`:582`); the
  callable runs `DBUtil.query(__db, _statement, true, null)` for `SELECT * FROM mission WHERE weekStart = ?`
  (`:586`), then calls `__fetchRelationshipsortieAscomLiftoffAppDataSortie(...)` (`:601`, defined
  `:694`), which runs one batched `SELECT … FROM sortie WHERE missionId IN (…)` through
  `DBUtil.query` (`:720`). Both reads are inside one callable — that callable is where
  `beginTransaction` / `setTransactionSuccessful` / `endTransaction` will appear once `observeRaw` is
  annotated. Reading this file is the cheapest proof the annotation took effect.
- The relation SELECT is **skipped when the parent query returns no rows** (`:697`
  `if (_map.isEmpty()) return;`), so a test that wants to see both reads needs a mission with at
  least one sortie.
- Both reads go through `androidx.room.util.DBUtil.query` → `RoomDatabase.query(...)` →
  `getOpenHelper().getWritableDatabase().query(...)`, which is what the query callback below
  intercepts.
- Nesting, corrected: the generated wrapper for `observeRaw` calls `__db.beginTransaction()`
  **unguarded** (measured in the annotated `MissionDao_Impl`), and `RoomDatabase.beginTransaction()`
  → `internalBeginTransaction()` is `assertNotMainThread()` then
  `getWritableDatabase().beginTransaction()` — no `inTransaction()` check in either. So the earlier
  note here ("guarded by `if (!db.inTransaction())`") was wrong: collecting this Flow from *inside*
  an already-open transaction on the same thread would hit SQLite's "cannot start a transaction
  within a transaction". Nothing in this repo does that today — `MissionManager` and `DatabaseImport`
  write inside `withTransaction` but do not collect `observeWeek` from inside it — and the whole unit
  suite passed with the annotation (see "Measured on a scratch copy"), so the risk is real but
  unexercised.
- Test hook: `RoomDatabase.Builder.setQueryCallback(QueryCallback, Executor)` (Room 2.6.1). Setting
  it makes the builder wrap the helper in `QueryInterceptorOpenHelper`, whose
  `getWritableDatabase()/getReadableDatabase()` return `QueryInterceptorDatabase`, and that wrapper
  calls `queryCallback.onQuery(sql, args)` on the given executor **before** delegating the query.
  Kotlin signature to override: `override fun onQuery(sqlQuery: String, bindArgs: List<Any?>)`.
  `DBUtil.query` reaches `RoomDatabase.query`, so DAO queries are seen. With a same-thread executor
  the callback runs on the querying thread, so `db.inTransaction()` read inside it reflects the
  transaction Room just began. **Verified at runtime under Robolectric** — see "Measured on a scratch
  copy" below. `RoomDatabase.inTransaction()` is
  `getOpenHelper().getWritableDatabase().inTransaction()` (javap -c), and with a query callback
  installed that helper is Room's own interceptor, so it reports the transaction Room began.
- Room annotations (`@Query`, `@Transaction`, `@Relation`) have Java `CLASS` retention, not
  `RUNTIME` (checked with `javap -v` on the jars and a small probe). Runtime reflection on
  `MissionDao` cannot see `@Transaction`, so a reflection fallback must read the generated
  `MissionDao_Impl` source or behaviour, not the annotation.

## Measured on a scratch copy (verify session, 2026-11-22)

To check that `verify.sh` can pass, this tree was copied to `/tmp/probe64` (repo copy, `app/build`
excluded; nothing in the repo was touched) and the change was made *there*:

- `@Transaction` on `observeRaw` **silences the warning**: `:app:kspDebugKotlin` then prints zero `w:`
  lines. It also compiles fine on a `Flow`-returning abstract `@Query`.
- The annotated `MissionDao_Impl` is 817 lines (811 before). `observeRaw` at `:570` returns
  `CoroutinesRoom.createFlow(__db, true, new String[]{"sortie","mission"}, callable)` — the second
  argument is `true` where it was `false`. The callable is bracketed: `__db.beginTransaction();` …
  `__db.setTransactionSuccessful(); return _result;` with `finally { _cursor.close(); }` and an outer
  `finally { __db.endTransaction(); }`. Inside it, `DBUtil.query(__db, _statement, true, null)` — the
  third argument (`enforceTransaction`) is now `true` — then `_cursor` is read into
  `_missionWithSorties`, then `__fetchRelationshipsortieAscomLiftoffAppDataSortie(_collectionSorties)`
  runs **inside** the same bracket. So both reads are inside one transaction, as the brief wants.
  The relation statement is
  `SELECT \`id\`, \`missionId\`, … FROM \`sortie\` WHERE \`missionId\` IN (?)`.
- The query-callback test hook **works under Robolectric** — the plan's main risk, and its fallback
  ladder is not needed. A case built as `Room.inMemoryDatabaseBuilder(...).setQueryCallback(cb,
  Executor { it.run() }).build()`, seeding one mission + two sorties, clearing the recorded list, then
  `observeWeek(...).first()`, recorded both SELECTs and `db.inTransaction()` was `true` for both with
  the annotation. With the annotation removed the same case fails on the first assertion, reporting
  the mission read outside a transaction. So the named case can fail, and does fail, for exactly the
  reason the brief describes.
- With the annotation in place the **whole** unit suite is green: `:app:assembleDebug
  :app:testDebugUnitTest` → 776 test records (775 + the new case), 0 failures, and
  `BackupImportRoomTest`, `LaunchpadStatesTest` and `MissionStatesTest` all pass untouched. The
  consumers did not break.
- API names the new case needs (the ones in this tree, which differ from what a guess would use):
  `MissionDao.insert(Mission(...))` (there is no `insertDraftForTesting`), `SortieDao.insert(Sortie(...))`
  (there is no `upsert`), `java.time.LocalDate.of(2026, 1, 5)` fully qualified (MissionDaoTest has no
  LocalDate import), `Mission(weekStart, pattern, status, outlineNotes)` — `pattern` is required,
  `id` defaults to 0 — and `Sortie(missionId, index, type, focus, focusRationale, state, launchedAt,
  landedAt, scrubReason, notes, runDistance, runMinutes)` with `SortieType.RUN` / `SortieState.PENDING`;
  `runDistance` and `runMinutes` have no defaults, so every argument must be named.
  The probe used `.allowMainThreadQueries()` on the builder; whether it is needed is untested.
- `verify.sh` itself was run in that copy: **exit 0**, 13 checks ok. On the untouched tree it exits 1
  with exactly two failures: the named case "did not run at all", and the KSP rerun still printing the
  `MissionDao.kt:50` warning.

## Gate / toolchain on this machine

- `gradlew.sh` is Windows-only — `:5-7` hardcode `JAVA_HOME`, `ANDROID_HOME` and the gradle binary
  under `C:/Users/corpo/android-toolchain/` — so the brief's `bash gradlew.sh …` does not run here.
  What works:
  `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk ~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain`.
- `:app:kspDebugKotlin` alone shows Room's warnings and is much faster than the full gate.
- `app/build.gradle.kts:42-51` sets `testOptions.unitTests` (`isIncludeAndroidResources`,
  `isReturnDefaultValues`) and a per-test `timeout` of 5 minutes, with the comment "A Robolectric +
  Room test can hang forever on the paused clock; fail fast instead."
- `MissionDaoTest` alone ran green on HEAD: 5 tests, 0 failures
  (`app/build/plan64/test-missiondao.log`, gitignored).
- `app/build/` is gitignored (`build/` in `.gitignore`); scratch logs go there.

## Project rules that bind this run

- `CLAUDE.md` "Build and test gate": the gate must pass before work is called done, and
  "Stage named files only. Never `git add -A`." Commit messages are `area: lowercase summary`.
- Earlier runs' plans include a "prove it can fail" step (drop the change, watch the new test fail,
  restore). That is the house convention this run follows, not a `CLAUDE.md` rule.
- `ARCHITECTURE.md:32` — `com.liftoff.app.data` is Room entities, DAOs, the database builder and
  `MissionManager.kt` (which applies the domain rules); nothing in this run moves a rule into the
  DAO. `DESIGN.md:635` fixes the test stack (JUnit 4, Robolectric, kotlinx-coroutines-test,
  room-testing), which is what the new case uses.
- `MissionManager.kt` writes through `db.withTransaction` (`:85`, `:112`, and elsewhere) — that is
  the land/advance write the brief names as the racing writer. `DatabaseImport.kt:49` wraps an import
  the same way, which is the nesting case `BackupImportRoomTest` exercises.

## verify.sh (this run's definition of done)

- Scratch lives in gitignored `app/build/verify64/` and is **not** trap-deleted, so a work step can
  read the failure text: `gate-1..3.log`, `ksp.log`, and the report index files (`raw`, `all`,
  `methods`, `qualified`, `classes`).
- Launcher order: `~/android-toolchain/gradle/bin/gradle` if it exists (with JAVA_HOME/ANDROID_HOME
  exported only when unset and the directories exist) → `./gradlew` → `bash gradlew.sh`; Git Bash
  (`uname` MINGW*/MSYS*/CYGWIN*) goes straight to `gradlew.sh`. Same order as issue-63's script;
  issue-68's used `bash gradlew.sh` unconditionally, which cannot run in a rho worktree.
  `LIFTOFF_TOOLCHAIN` overrides the toolchain root.
- Gate: `:app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain`, reports moved
  aside before each attempt, up to 3 attempts, retried only for a Gradle-level breakage or a failure
  in the recorded flaky classes `LiftoffShellTest`, `InFlightScreenTest`, `LaunchpadPendingTitleTest`.
  A failure anywhere else, including anything in `MissionDaoTest`, is final.
- Named cases it requires: `MissionDaoTest.observeWeekReadsMissionAndSortiesInsideOneTransaction`
  (the new one, the only check that cannot pass today besides the warning check), the five existing
  `MissionDaoTest` cases, and `BackupImportRoomTest.anImportThroughTheBackupActionReachesRoomsFlows`.
  Class checks: `MissionDaoTest`, `BackupImportRoomTest`, `LaunchpadStatesTest`, `MissionStatesTest`.
- Warning check: `:app:kspDebugKotlin --rerun-tasks --no-build-cache --console=plain` must print
  `> Task :app:kspDebugKotlin` (executed, not UP-TO-DATE/FROM-CACHE/NO-SOURCE) and no `w:` line. Plus
  a grep of the last gate log for `^w:.*@Relation`.
- Measured verdicts: untouched tree → 11 ok / 2 FAIL, exit 1, identical across two runs. Scratch copy
  with the annotation and a case of the named shape → 13 ok, exit 0.
