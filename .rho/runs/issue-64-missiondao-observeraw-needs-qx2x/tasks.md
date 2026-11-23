# Tasks — issue 64: `MissionDao.observeRaw` needs `@Transaction`

Four tasks, in plan.md §Order's order. Each is one session and ends in its own commit. The one test
`verify.sh` names that does not exist yet is written by exactly one task:

| test | task |
| --- | --- |
| `MissionDaoTest.observeWeekReadsMissionAndSortiesInsideOneTransaction` | 2 |

Everything else `verify.sh` requires already runs green on this tree and is the must-not-disturb net:
the five `MissionDaoTest` cases (`MissionDaoTest.kt:40, :53, :59, :75, :89`),
`BackupImportRoomTest.anImportThroughTheBackupActionReachesRoomsFlows` (`:186`), and the four classes
`MissionDaoTest`, `BackupImportRoomTest`, `LaunchpadStatesTest`, `MissionStatesTest`. Baseline from this
run's own gate run (map.md): **775 test records in 93 classes, 0 failures, 0 skipped**, and
`MissionDaoTest` at **5** tests. After task 2 the expectation is **776 records in the same 93 classes**,
`MissionDaoTest` at **6**. `verify.sh` passing is **13 checks ok**.

Conventions (CLAUDE.md + map.md), for every task:
- `bash gradlew.sh` does **not** run on this machine (`gradlew.sh:5-7` hardcode `C:/Users/corpo/...`).
  Use `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
  ~/android-toolchain/gradle/bin/gradle <tasks> --no-build-cache --console=plain`.
- Scratch logs, byte-copies and one-off scripts go under gitignored `app/build/` (e.g.
  `app/build/task1-64/`); never a scratch test class under `app/src/test`. Move
  `app/build/test-results/testDebugUnitTest` aside with `mv`, never `rm`, before each run so the totals
  read are that run's; never `gradlew --stop`. A targeted run is
  `:app:testDebugUnitTest --tests <fully.qualified.Class>`.
- Restore a deliberately mutated file with `cp` from a byte-copy, never `git checkout` — the approval
  gate here refuses the destructive forms.
- Never edit `verify.sh`. Do not push and do not open a PR; the runner does both.
- Commit style `area: lowercase summary`; stage named files only, never `git add -A`. HEAD is
  `9f04e65`; line numbers below are from HEAD and shift once a task lands, so re-read the file.

- [x] 1. Baseline the Room warning, then annotate `observeRaw`
  Covers plan steps 1–2. Production change only in
  `app/src/main/java/com/liftoff/app/data/MissionDao.kt`; nothing else in the DAO layer.
  First, on the untouched tree, run the forced KSP task (`:app:kspDebugKotlin --rerun-tasks
  --no-build-cache --console=plain`) and keep its log under `app/build/`: it must show
  `> Task :app:kspDebugKotlin` executed (not UP-TO-DATE/FROM-CACHE/NO-SOURCE) and exactly one `w:` line
  — `MissionDao.kt:50`, "The return value includes a POJO with a @Relation…". That is the brief's "the
  one warning the gate prints", and the baseline task 4 compares against.
  Then put `@Transaction` on `observeRaw` (`:49-50`), on its own line above the declaration the way
  `ExerciseDao.kt:26` and `FlightPlanDao.kt:125` do; `androidx.room.*` is already imported. `observeWeek`
  (`:52-56`) is **not** the target — it carries no `@Query`. `Relations.kt:8` is the repo's only
  `@Relation` in `app/src/main` (map.md), so no other DAO method needs the annotation and the brief's
  "any other @Relation query" sweep stays empty; the forced KSP run is what proves that.
  Re-run the forced KSP task: the task executes and prints no `w:` line. Then read
  `app/build/generated/ksp/debug/java/com/liftoff/app/data/MissionDao_Impl.java` and confirm `observeRaw`
  now passes `true` as `CoroutinesRoom.createFlow`'s second argument and its callable is bracketed with
  `beginTransaction` / `setTransactionSuccessful` / `endTransaction`, with the batched relation fetch
  inside that bracket (map.md records today's shape: `false` at `:582`, no bracket).
  Done when: the forced KSP log shows the task executed with zero `w:` lines; the generated file shows
  the bracket around both reads; and a cold `:app:testDebugUnitTest --tests
  com.liftoff.app.data.MissionDaoTest` run is BUILD SUCCESSFUL with that class at **5 tests, 0 failures,
  0 skipped** — the annotation changed nothing the Flow emits. Commit `MissionDao.kt` alone, with the
  baseline warning line, the KSP command and the generated-code proof in the body.

- [x] 2. The behaviour test, and the proof that it bites
  Test only: `app/src/test/java/com/liftoff/app/data/MissionDaoTest.kt`, one new case named exactly
  **`observeWeekReadsMissionAndSortiesInsideOneTransaction`** — the name `verify.sh` §2 looks for, and
  the only test this run has to write. Covers plan steps 3–4. `verify.sh` §2's comment is the spec;
  plan.md §Testing and §Risks give the shape and the fallback ladder (the ladder is not needed — the
  first rung was measured to work, map.md).
  What the case does: builds **its own** in-memory `LiftoffDatabase` with
  `RoomDatabase.Builder.setQueryCallback(callback, java.util.concurrent.Executor { it.run() })` and keeps
  `.allowMainThreadQueries()` — `RoomDatabase.beginTransaction()` calls `assertNotMainThread()`, which
  only returns early when that flag is set (map.md), and the annotation makes Room begin a transaction
  in this path. Leave the shared `createDb`/`closeDb` and the five existing cases untouched. In
  `override fun onQuery(sqlQuery: String, bindArgs: List<Any?>)` record the SQL and `db.inTransaction()`
  read **inside** the callback — the transaction exists only while Room's callable runs. Seed a mission
  with at least one sortie first (Room skips the relation SELECT when the parent returns no rows), start
  recording after the seeds, then take one emission with `first()` the way the existing cases do. Assert:
  the parent read (`SELECT * FROM mission WHERE weekStart = ?`) recorded, the relation read (Room's
  batched `SELECT` over `sortie` with `missionId IN (?)`) recorded, the parent recorded first, and both
  while `inTransaction()` was true. No threads and no concurrency: `app/build.gradle.kts:42-51` fails a
  unit test at 5 minutes because a Room + Robolectric test can hang. No reflection on `MissionDao`
  either — Room's annotations have Java CLASS retention, so a reflection test cannot see `@Transaction`
  (verify.sh §2 says so); the existing reflection case at `:89` stays as it is. Append the new case
  after `:94` so the four pre-existing test-compile `w:` lines keep the line numbers map.md records.
  Then prove it bites: keep a byte-copy of the annotated `MissionDao.kt` under `app/build/`, delete the
  `@Transaction` line, re-run the class, and read the one red case in that run's own log — the mission
  read reported outside a transaction, which is exactly the brief's bug. `cp` the copy back, confirm
  `git diff` shows no change to `MissionDao.kt`, re-run the class green. Keep the mutation run's log and
  XML under their own names so the green run's reports are what the totals are read from.
  Done when: a cold `:app:testDebugUnitTest --tests com.liftoff.app.data.MissionDaoTest` run is BUILD
  SUCCESSFUL with that class at **6 tests, 0 failures, 0 skipped** and the new name read pass, and the
  mutation log shows the same name failing with the annotation removed. Commit `MissionDaoTest.kt` alone,
  with the mutation, its failure line and both totals in the body.

- [ ] 3. The consumers of the Flow, and the nesting risk
  No new behaviour and no new test. Covers plan.md §Testing "Consumers are unaffected" and §Risks
  "Nesting". Files: none expected; `findings.md` is this task's deliverable.
  Run cold, in **one** `:app:testDebugUnitTest` invocation carrying six `--tests` filters —
  `com.liftoff.app.ui.control.BackupImportRoomTest` (whose
  `anImportThroughTheBackupActionReachesRoomsFlows` is at `:186`),
  `com.liftoff.app.ui.launchpad.LaunchpadStatesTest`, `com.liftoff.app.ui.mission.MissionStatesTest`,
  `com.liftoff.app.ui.mission.MissionTabStateTest`, `com.liftoff.app.ui.inflight.InFlightStatesTest`,
  `com.liftoff.app.ui.landed.LandedStatesTest` — the three classes `verify.sh` §4 names plus the other
  state-level suites that build a real in-memory database around this Flow. All must pass untouched —
  `BackupImportRoomTest` is the early signal for the nesting problem, because it watches `observeWeek`
  across an import that `DatabaseImport.kt:49` wraps in a transaction.
  Check the nesting question in the code as well as in the tests: the generated wrapper for `observeRaw`
  calls `__db.beginTransaction()` with **no** `inTransaction()` guard (map.md), and SQLite refuses a
  transaction started inside one, so confirm that nothing collects `observeWeek`/`observeRaw` from inside
  an already-open transaction on the same thread — `MissionManager.kt` opens them at `:85, :112, :137,
  :169, :200, :235` and `DatabaseImport.kt` at `:49`, none of them reading this Flow inside one; the two
  consumers (`ui/launchpad/LaunchpadState.kt:101`, `ui/mission/MissionState.kt:112`) read it under
  `flowOn(Dispatchers.IO)`. Write down what was checked and what was found.
  Done when: that invocation is BUILD SUCCESSFUL with 0 failures and each named class green in its own
  report, and the nesting check's result is written up. Commit
  `.rho/runs/issue-64-missiondao-observeraw-needs-qx2x/findings.md` (created by this task) carrying both
  results; if a consumer broke, commit the fix in the file this run owns plus the findings line that
  explains it.

- [ ] 4. The whole gate, once, and the Room warning confirmation
  Covers plan step 5. No new behaviour, no new test, no source change expected. Files: `findings.md`.
  Run `bash .rho/runs/issue-64-missiondao-observeraw-needs-qx2x/verify.sh` **once** on the tree the
  earlier tasks committed — the `findings.md` written afterwards cannot change a gate result — because it
  is the run's definition of done and it does the gate, the named cases and the warning check in
  one pass — and read its output and the logs it leaves under `app/build/verify64/`. Expect `BRIEF DONE`
  with **13 checks ok**, the gate green with `> Task :app:testDebugUnitTest` executed rather than
  FROM-CACHE/UP-TO-DATE/NO-SOURCE, and the count the script prints — **776 test records** in 93 classes
  (775 today; `verify.sh` prints the count rather than asserting one, so read it and say what it was) —
  with 0 failures / 0 errors / 0 skipped, `MissionDaoTest` at 6 including the new name, and the forced
  `:app:kspDebugKotlin` rerun executed with no `w:` line. A warm gate prints no `w:` line at all, so that
  forced rerun — not the gate log — is what carries the brief; the gate-log grep is scoped to
  `^w:.*@Relation`, and the four `w:` lines `:app:compileDebugUnitTestKotlin` prints (map.md) are not
  Room's and not this issue's business.
  If a check fails, fix only what it names, re-run once, and stop; a second identical green run on an
  unchanged tree adds nothing. Do not edit `verify.sh`, and do not chase a flake in
  `LiftoffShellTest`, `InFlightScreenTest` or `LaunchpadPendingTitleTest` beyond the one re-run the
  script already does.
  Done when: `verify.sh` has returned `BRIEF DONE` once on the tree as committed, and `findings.md`
  records the verdicts — the baseline warning vs the clean forced KSP rerun, the gate totals, the named
  cases, the consumer/nesting results from task 3, and which test shape landed (query callback +
  `db.inTransaction()`, per plan §Risks) and why. Commit `findings.md`: a task that changes no source
  still has to leave a commit, or the runner reads it as a task that never ran.
