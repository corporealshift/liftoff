# Findings — issue-64 run (`MissionDao.observeRaw` needs `@Transaction`)

## Task 3 — the consumers of the Flow, and the nesting risk

Tree this task worked on: `HEAD = d574f9b` ("run: task 2 done"), on top of `08f11f2` ("test: MissionDaoTest
shows observeWeek's two reads share one transaction") and `bf0924a` ("data: @Transaction on
MissionDao.observeRaw"). `MissionDao.kt` carries `@Transaction` at `:51`, `@Query` at `:52`,
`fun observeRaw` at `:53`, and `observeWeek` at `:55`. This task changed no file under `app/src` —
`git status --short` and `git diff --stat HEAD` were both empty before and after the runs below. The only
file it writes is this one.

Artifacts (all under gitignored `app/build/`, so they live on this machine, not in the commit):
`app/build/task3-64/consumers.log`, `app/build/task3-64/consumers-results/TEST-*.xml` (the six reports),
`app/build/task3-64/probe2.log`, `app/build/task3-64/probe-report.xml`,
`app/build/task3-64/probe/NestingProbeTest.kt`, `app/build/task3-64/jars/` (the javap dumps).

---

## 1. The consumers: one cold invocation, six `--tests` filters, all green

One invocation, nothing else in it:

```
~/android-toolchain/gradle/bin/gradle :app:testDebugUnitTest \
  --tests com.liftoff.app.ui.control.BackupImportRoomTest \
  --tests com.liftoff.app.ui.launchpad.LaunchpadStatesTest \
  --tests com.liftoff.app.ui.mission.MissionStatesTest \
  --tests com.liftoff.app.ui.mission.MissionTabStateTest \
  --tests com.liftoff.app.ui.inflight.InFlightStatesTest \
  --tests com.liftoff.app.ui.landed.LandedStatesTest \
  --rerun-tasks --no-build-cache --console=plain
```

`consumers.log`: `> Task :app:testDebugUnitTest` executed (line 48 — not UP-TO-DATE, not FROM-CACHE),
`BUILD SUCCESSFUL in 19s` (line 50), `29 actionable tasks: 29 executed` (line 51), no `FAILED` line, and no
failure/error/skip element in any report. The eight `w:` lines it prints are pre-existing and none is
Room's: four `Divider` deprecations in `InFlightScreen.kt` (`:622, :799, :1010, :1044`) and four
test-compile warnings (`SchemaExportTest.kt:17`, `:21`, `InFlightViewModelTest.kt:678`,
`OffsetShadowTest.kt:30`). Zero `@Relation` lines.

Each named class, from its own report XML (`testsuite` attributes):

| class | tests | skipped | failures | errors |
|---|---|---|---|---|
| `com.liftoff.app.ui.control.BackupImportRoomTest` | 1 | 0 | 0 | 0 |
| `com.liftoff.app.ui.inflight.InFlightStatesTest` | 9 | 0 | 0 | 0 |
| `com.liftoff.app.ui.landed.LandedStatesTest` | 8 | 0 | 0 | 0 |
| `com.liftoff.app.ui.launchpad.LaunchpadStatesTest` | 10 | 0 | 0 | 0 |
| `com.liftoff.app.ui.mission.MissionStatesTest` | 12 | 0 | 0 | 0 |
| `com.liftoff.app.ui.mission.MissionTabStateTest` | 1 | 0 | 0 | 0 |

41 test records, 0 failures, 0 errors, 0 skipped. `BackupImportRoomTest`'s single record is
`anImportThroughTheBackupActionReachesRoomsFlows` (0.411s) — the case `verify.sh` §4 names. It watches
`observeWeek` in a `Dispatchers.Unconfined` collector (`BackupImportRoomTest.kt:120-127`, read at `:208`)
while `DatabaseImport.importBackup` runs its writes inside `db.withTransaction` (`DatabaseImport.kt:49`),
then asserts the Flow re-emitted. It passed untouched, as did every other case in the six classes.

Of the six, `BackupImportRoomTest` and `MissionDaoTest` are the only tests that call `observeWeek`
directly; `LaunchpadStatesTest` (`:41`, `:57`), `MissionStatesTest` (`:38`, `:54`) and
`LandedStatesTest` (`:47`, `:59`) build a real in-memory `LiftoffDatabase` and drive the two consumers
that read the Flow, `InFlightStatesTest` (`:30`) builds the same database and drives `deriveInFlight` over
DAO reads, and `MissionTabStateTest` is the state type itself with no database.

No consumer broke, so there is no fix to commit: plan.md §Testing "Consumers are unaffected" holds.

---

## 2. The nesting question: what was checked

**The generated wrapper.** `app/build/generated/ksp/debug/java/com/liftoff/app/data/MissionDao_Impl.java`:
`observeRaw` is generated at `:578`, `CoroutinesRoom.createFlow(__db, true, …)` at `:580` (the flag is
`true` because of the annotation), its callable opens `__db.beginTransaction();` at `:585`, closes with
`__db.setTransactionSuccessful();` at `:641` and `} finally { __db.endTransaction(); }` at `:647`.
`grep -n "\.inTransaction()"` over the whole generated file returns **nothing** — no guard, exactly as
map.md says and *not* as plan.md §Risks says. The same file has five other unguarded `beginTransaction()`
sites (`:132, :151, :169, :191, :216`): `insert`, `insertAll`, `update`, `deleteDraft`, `deleteWeek`. Room
already emits an unguarded begin for every suspend DAO method, so the unguarded begin is not something
this change introduced.

**Room's own layers** (javap on the jars Gradle resolves — room-runtime 2.6.1, room-ktx 2.6.1; dumps in
`app/build/task3-64/jars/`):
- `RoomDatabase.beginTransaction()` → `assertNotMainThread()` → `internalBeginTransaction()` →
  `QueryInterceptorDatabase.beginTransaction()`, which records the virtual statement
  `BEGIN EXCLUSIVE TRANSACTION` for the query callback and then delegates to
  `SupportSQLiteDatabase.beginTransaction()`. No `inTransaction()` check anywhere on that path.
- `CoroutinesRoom.execute` (the suspend-DAO path) *does* read `RoomDatabase.inTransaction()`, but only to
  decide whether to hop to `db.transactionDispatcher`; the callable it runs still contains its own
  `beginTransaction()`.
- `CoroutinesRoom.createFlow` and its flow classes contain **no** `inTransaction()` reference at all. The
  `inTransaction` flag is used only to pick a dispatcher: `createFlow$1$1.invokeSuspend` looks for a
  `TransactionElement` in the collecting context, and otherwise uses `db.transactionDispatcher` when the
  flag is true and `db.queryDispatcher` when it is false. So the annotation changes *where* the read runs,
  not *whether* a begin is skipped.
- `RoomDatabaseKt.withTransaction` is the same shape: it reuses the dispatcher when a `TransactionElement`
  is already in the coroutine context, and its block calls `beginTransaction()` unconditionally.
- `RoomDatabase.internalEndTransaction()` is the one place Room does consult `inTransaction()`: after
  `endTransaction()` it refreshes the invalidation-tracker versions only when no transaction is open any
  more, so a re-query provoked by a nested commit waits for the outermost transaction.

**Every reader of the Flow in `app/src/main`.** `grep -rn "observeWeek\|observeRaw" app/src/main/java`
returns the DAO declaration (`MissionDao.kt:52, :53, :55`) plus exactly two consumers:
`ui/launchpad/LaunchpadState.kt:101` and `ui/mission/MissionState.kt:112`. Both are the first link of a
`flatMapLatest` chain whose `.flowOn` is `LaunchpadState.kt:110` and `MissionState.kt:128`
(`Dispatchers.IO`); neither chain opens a transaction, and the `derive(...)` they call reaches only suspend
DAO methods (`getPlan`, `getForMission`, `getById`, `getOpenOutlineForMission`).

**Every transaction site in `app/src/main`.** `grep -rn "withTransaction {" app/src/main/java` gives 19
call sites, not the 6 map.md names: `MissionManager.kt:85, :112, :137, :169, :200, :235, :266, :280, :300,
:327, :345, :385, :429, :452` (14), plus `DatabaseImport.kt:49`, `DatabaseExport.kt:21`,
`AppOpenSweep.kt:46`, `GenerationCanceller.kt:37`, `GenerationWorker.kt:336`. Inside those blocks the DAO
calls are all suspend methods (`get`, `getById`, `getForMission`, `getPlan`, `getOpenOutlineForMission`,
`insert`, `insertAll`, `update`, …). `grep -rn "\.observe[A-Za-z]*(" app/src/main/java` shows the only
Flow-returning DAO reads in the app are `PromptInputLoader.kt:63` (`observeActive().first()`) and
`GenerationWorker.kt:270` (`observeActive().first()`, inside `storeFinishedTurn` but before its
`withTransaction` at `:336`); `MissionManager.kt` never calls an `observe*` method at all. So nothing in
the main sources collects any Flow DAO query from inside an open transaction — `observeWeek` included.

**The tests.** `grep -rn "observeWeek(" app/src/test/java` gives five read sites: `MissionDaoTest.kt:46,
:54, :68, :137` and `BackupImportRoomTest.kt:208`. `grep -rn "withTransaction\|inTransaction\|beginTransaction"
app/src/test/java` returns three lines, all in `MissionDaoTest.kt` (`:99`, `:111`, `:115`) and all of them
the task-2 query callback *reading* `database.inTransaction()` — no test opens a transaction of its own.
The only transaction any test meets is the one `DatabaseImport` opens internally, which is the
BackupImportRoomTest case above.

---

## 3. The nesting question: what was found

Because the plan's premise about the guard is wrong, the consequence was measured rather than argued. A
scratch probe (`app/build/task3-64/probe/NestingProbeTest.kt`; gitignored, six cases, never a deliverable
test — `verify.sh` selects tests by class name, so it can never see it) ran green: 6 tests, 0 failures, 0
errors, 0 skipped. Each case prints `PROBE-n`, and every statement is recorded through
`Room.inMemoryDatabaseBuilder(…).setQueryCallback(…)`, printing the thread, `db.inTransaction()` at the
moment the callback fires, and the SQL. Outcomes, from `probe-report.xml`:

| case | result |
|---|---|
| `probe1_plainFlowRead` | `observeWeek` → `mission=1 sorties=1`; bracket `BEGIN EXCLUSIVE TRANSACTION → SELECT * FROM mission WHERE weekStart = ? → SELECT … FROM \`sortie\` WHERE \`missionId\` IN (?) → TRANSACTION SUCCESSFUL → END TRANSACTION`, all on one coroutine on one `arch_disk_io_*` thread |
| `probe2_flowReadInsideOpenTransaction` | `db.withTransaction { missionDao.observeWeek(week).first() }` → **`ok mission=1`**, no exception. The Flow's whole bracket ran on the *same* thread as the open transaction (`arch_disk_io_0`, outer `@coroutine#6`, Flow `@coroutine#7`), and its `beginTransaction` was recorded while `inTx=true` — a genuine nesting event that did not fail |
| `probe3_suspendReadInsideOpenTransaction` | `db.withTransaction { missionDao.get(id) }` → `ok mission=1` (control) |
| `probe4_nestedWithTransaction` | `withTransaction` inside `withTransaction` → `ok`. This is what the app already does: `GenerationWorker.kt:336` calls `missionManager.storeOutline`, whose own `withTransaction` is `MissionManager.kt:235` |
| `probe5_flowCollectorAgainstOpenTransaction` | a live `observeWeek` collector on `Dispatchers.IO` while a `withTransaction` block updates the mission and inserts a sortie → `ok`, no exception, no stall; the writes' own nested `BEGIN`/`END` pairs were recorded inside the outer bracket |
| `probe6_flowReadInsideTransactionSeesAndCommits` | inside an open transaction: insert `outlineNotes = "uncommitted"`, then read through the Flow → the read **saw the uncommitted row** (`PROBE-6a … outlineNotes=uncommitted`), i.e. the Flow reads run on the same connection as the open transaction; then `error("rollback on purpose")` → `PROBE-6b … rows=0 outlineNotes=[]`, so the Flow's own `BEGIN`/`END` did **not** commit the outer transaction and its atomicity survived |

The mechanism is in the framework, not in Room. `android.database.sqlite.SQLiteSession` (API 34, from the
Robolectric `android-all-instrumented` jar) keeps a **transaction stack**: `beginTransactionUnchecked`
skips `acquireConnection` when `mTransactionStack != null` and issues `BEGIN IMMEDIATE;` /
`BEGIN EXCLUSIVE;` / `BEGIN;` only when the stack is empty; `endTransactionUnchecked` pops
`mTransactionStack = mTransactionStack.mParent` and issues `COMMIT;`/`ROLLBACK;` and `releaseConnection()`
only when the stack becomes empty — and when a popped transaction was not marked successful it sets
`mChildFailed = true` on the parent, so a failed inner transaction poisons the outer one. That is why
probe2 and probe4 succeed, why probe6's inner `END` did not commit (its outer `END TRANSACTION` was
recorded with no preceding `TRANSACTION SUCCESSFUL`, i.e. a rollback), and why the recorded inner `BEGIN`
is Room's callback bookkeeping rather than a second `BEGIN` reaching SQLite.

So the sentence in the task — "SQLite refuses a transaction started inside one" — is not what the Android
stack does on this call path: the nesting is absorbed one level below Room, by reference counting, on the
same connection. The real consequence of reading this Flow from inside a transaction would be *semantic*
(it would read that transaction's own uncommitted rows, as probe6 shows) rather than an exception.

**For this run the answer is unchanged:** no consumer reads `observeWeek`/`observeRaw` inside a
transaction, in main or in test, so the annotation changes only which dispatcher the read runs on
(`db.transactionDispatcher` instead of `db.queryDispatcher`) and makes the two reads share one bracket —
which is what `MissionDaoTest.observeWeekReadsMissionAndSortiesInsideOneTransaction` (task 2) asserts. The
six consumer suites are green untouched. Nesting is not a reason to add an `inTransaction()` guard, and
such a guard would not match Room's behaviour for any other DAO method.

---

## 4. Two corrections for later sessions of this run (written by task 3)

1. **plan.md §Risks "Nesting" is wrong as written.** It says "Room's generated wrapper is guarded by
   `if (!db.inTransaction())`, so a flow read reached from inside an already-open transaction joins that
   transaction instead of starting a nested one." There is no such guard in the generated wrapper, in
   `RoomDatabase.beginTransaction`, or in `withTransaction` — `verify.sh` §4's own comment already states
   the measured version. The join does happen, but in `android.database.sqlite.SQLiteSession`'s
   transaction stack, not in Room.
2. **map.md's transaction-site list is short.** It names six `MissionManager.kt` sites (`:85, :112, :137,
   :169, :200, :235`); the file has fourteen (`:85, :112, :137, :169, :200, :235, :266, :280, :300, :327,
   :345, :385, :429, :452`), and `DatabaseExport.kt:21`, `AppOpenSweep.kt:46`, `GenerationCanceller.kt:37`
   and `GenerationWorker.kt:336` are not listed either. The nesting sweep here ran against all 19
   `withTransaction` call sites in `app/src/main`, not the six.

---

## Task 4 — the whole gate, once, and the Room warning confirmation

Tree this task ran on: `HEAD = 4b48191` ("run: task 3 done"), i.e. `01f732d` (task 3's findings) on top
of `d574f9b`/`08f11f2` (task 2's test) on top of `e8e8b06`/`bf0924a` (task 1's annotation). `git status
--short` was empty before the run and after it, and it is still empty apart from this file: **this task
changed no file under `app/`** and wrote no test. The only file it writes is this one.

One command, run once:

```
bash .rho/runs/issue-64-missiondao-observeraw-needs-qx2x/verify.sh
```

Its stdout is saved at gitignored `app/build/task4-64/verify-run.log`; the logs it leaves are under
`app/build/verify64/` (`gate-1.log`, `ksp.log`, and the report index `raw`/`all`/`methods`/`qualified`/
`classes`). `verify.sh` was not edited.

**Verdict: `BRIEF DONE` — `=== Result: 13 passed, 0 failed ===`, exit 0, one gate attempt, no retry,
1m16s wall.**

## 5. The Room warning: the baseline against the clean forced rerun

| | command | task line | `w:` lines | build |
|---|---|---|---|---|
| Baseline, untouched tree (task 1, `app/build/task1-64/ksp-baseline.log`) | `:app:kspDebugKotlin --rerun-tasks --no-build-cache --console=plain` | `> Task :app:kspDebugKotlin` — executed; `14 actionable tasks: 14 executed` | **1** | `BUILD SUCCESSFUL in 6s` |
| This run, committed tree (`app/build/verify64/ksp.log`) | the same command, run by `verify.sh` §5 | `> Task :app:kspDebugKotlin` — executed; `14 actionable tasks: 14 executed` | **0** | `BUILD SUCCESSFUL in 4s` |

The baseline line, in full, is the brief's "the one warning the gate prints":

```
w: [ksp] <worktree>/app/src/main/java/com/liftoff/app/data/MissionDao.kt:50: The return value includes a
POJO with a @Relation. It is usually desired to annotate this method with @Transaction to avoid
possibility of inconsistent results between the POJO and its relations. …
```

The forced rerun on the committed tree printed no `w:` line at all, and it *executed* — the log's task
line carries no `UP-TO-DATE`, `FROM-CACHE` or `NO-SOURCE` suffix, which is what `verify.sh` §5 checks
before it looks at warnings. Because Room prints `ROOM_RELATION_QUERY_WITHOUT_TRANSACTION` per method,
that empty warning surface is also the brief's "any other `@Relation` query in the DAOs" sweep: no DAO
method anywhere in the main sources returns a POJO with an un-annotated `@Relation`.

**Why the gate log cannot carry this check.** In this warm gate `> Task :app:kspDebugKotlin UP-TO-DATE`
(`gate-1.log`), so Room's processor printed nothing there whether or not the annotation is present —
map.md's "a warm gate run prints no `w:` line at all". The forced rerun is the check that carries the
brief, and it is the one that flipped from 1 warning to 0.

**The gate-log grep.** `grep -n '^w:.*@Relation' app/build/verify64/gate-1.log` matched nothing, so the
second warning check passed. In fact `gate-1.log` holds **zero** `w:` lines: `:app:compileDebugUnitTestKotlin`
executed but printed none of the four pre-existing lines map.md records (`SchemaExportTest.kt:114`,
`:115`, `MissionDaoTest.kt:89`, `MissionDaoTest.kt:91` ×2), because the incremental compile only
recompiled the files that changed since the previous run — in CI, on a cold tree, that task compiles
everything and prints them. Either way those four are not Room's warnings and not this issue's business,
which is why the check is scoped to `@Relation` rather than to `^w:` over the gate log.

## 6. The gate: green on the first attempt, and what it ran

From `app/build/verify64/gate-1.log` (`:app:assembleDebug :app:testDebugUnitTest --no-build-cache
--console=plain`, the two tasks `ci.yml` runs, through `~/android-toolchain/gradle/bin/gradle`):

- `> Task :app:testDebugUnitTest` — **executed**, no `FROM-CACHE`/`UP-TO-DATE`/`NO-SOURCE` suffix. The
  script's own cache guard is what would have caught a served run; it did not fire.
- `> Task :app:assembleDebug` — executed. The tasks that actually ran were `dexBuilderDebug`,
  `mergeProjectDexDebug`, `packageDebug`, `assembleDebug`, `kspDebugUnitTestKotlin`,
  `compileDebugUnitTestKotlin`, `testDebugUnitTest`; `46 actionable tasks: 6 executed, 40 up-to-date`;
  `BUILD SUCCESSFUL in 1m 11s`.
- **Totals: 776 test records in 93 classes** — the script's own line is `… 776 test records, 93 classes
  indexed`. That is map.md's baseline of 775 records in the same 93 classes plus task 2's new case, which
  is what tasks.md predicts. `verify.sh` prints the count rather than asserting one, so it is recorded
  here as measured: **776**.
- **0 failures / 0 errors / 0 skipped.** The 93 report XMLs under `app/build/test-results/testDebugUnitTest/`
  sum to `tests="776" skipped="0" failures="0" errors="0"`, and every one of the 776 records in the
  script's index reads `pass`. The per-class `time` attributes sum to 65.6s, which is most of the gate's 1m11s, so the
  suite really ran rather than being read out of a cache.
- One attempt. `transient_failure` never ran, so no retry was used and the recorded flaky classes
  (`LiftoffShellTest`, `InFlightScreenTest`, `LaunchpadPendingTitleTest`) never failed and needed no
  chasing.
- `verify.sh` moved the previous results directory to `app/build/verify-old-results-1` before the run —
  that directory held task 3's scratch probe report, not a gate run — so the 93 reports now under
  `app/build/test-results/testDebugUnitTest/` are this gate's, and every named case below is read from
  them.

## 7. The named cases, all 13

| check | verdict |
|---|---|
| gate: `:app:assembleDebug :app:testDebugUnitTest` | ok |
| `MissionDaoTest.observeWeekReadsMissionAndSortiesInsideOneTransaction` | ok |
| `MissionDaoTest.observeWeekReturnsSortiesInIndexOrder` | ok |
| `MissionDaoTest.observeWeekIsNullForWeekWithoutMission` | ok |
| `MissionDaoTest.missionAndSortieUpdatesAreVisible` | ok |
| `MissionDaoTest.duplicateWeekStartIsRejected` | ok |
| `MissionDaoTest.noWipeForMissionsAndOnlyTheByIdDeleteForSorties` | ok |
| class `MissionDaoTest` — every test in it passed | ok |
| `BackupImportRoomTest.anImportThroughTheBackupActionReachesRoomsFlows` | ok |
| class `BackupImportRoomTest` — every test in it passed | ok |
| class `LaunchpadStatesTest` — every test in it passed | ok |
| class `MissionStatesTest` — every test in it passed | ok |
| the gate prints no Room warnings: `:app:kspDebugKotlin` executed and printed no warning | ok |

The class counts behind those four class checks, from this gate's own reports: `MissionDaoTest` **6**
tests (`0 failures, 0 errors, 0 skipped`; the new name recorded as a passing testcase at 0.025s),
`BackupImportRoomTest` **1**, `LaunchpadStatesTest` **10**, `MissionStatesTest` **12**. `MissionDaoTest`
is at 6 because of task 2's case; the five pre-existing cases still run and pass, which is what proves
the annotation left the emission's content unchanged.

## 8. Consumers and nesting, as they landed in the gate

Task 3 ran the six consumer suites cold in one invocation and all 41 records passed untouched (§1), and
its sweep found no reader of `observeWeek`/`observeRaw` inside an open transaction in `app/src/main` or
in `app/src/test` (§2), with the nesting behaviour itself measured by a scratch probe rather than argued
(§3): `android.database.sqlite.SQLiteSession`'s transaction stack absorbs one nesting level on the same
connection, so the consequence would be semantic, not an exception. This gate run is the same verdict at
full scale — the Flow's consumers are green inside the whole-suite run, `BackupImportRoomTest`'s
`anImportThroughTheBackupActionReachesRoomsFlows` (the case that watches `observeWeek` across an import
`DatabaseImport.kt` wraps in `withTransaction`) among them, and nothing else in the 776 records moved.
No consumer broke in any task of this run, so no fix commit exists.

## 9. Which test shape landed, and why

**The first rung of plan.md §Risks' ladder: Room's query callback with `db.inTransaction()` read inside
it.** `MissionDaoTest.observeWeekReadsMissionAndSortiesInsideOneTransaction` (`MissionDaoTest.kt:103-148`)
builds its own in-memory `LiftoffDatabase` with
`Room.inMemoryDatabaseBuilder(…).allowMainThreadQueries().setQueryCallback(callback, Executor { it.run() })`,
seeds a mission with two sorties, starts recording after the seeds, takes one emission with `first()`, and
asserts the parent read (`SELECT * FROM mission WHERE weekStart = ?`) was recorded, Room's batched
`@Relation` read over `sortie` with `` `missionId` IN (`` was recorded, the parent came first, and both
were recorded while `database.inTransaction()` was true.

Why this shape and not a later rung:

- It was measured to work. Task 2's mutation proof: with the `@Transaction` line deleted from
  `MissionDao.kt` the same case failed at `MissionDaoTest.kt:146` with
  `java.lang.AssertionError: the mission SELECT ran outside a transaction` — Room's parent read outside
  any transaction, which is the brief's bug — and it went green again when the byte-copy was `cp`'d back.
  A case that can be turned red by exactly the annotation under test is what makes the later rungs
  unnecessary: no `RoomDatabase.Callback` commit count was needed, and the generated `MissionDao_Impl`
  bracket was read as task 1's confirmation, not used as the test.
- The callback is the only place on this path where the transaction state is observable at all. Room
  begins the transaction on its own `arch_disk_io` executor while the generated callable runs
  (`MissionDao_Impl.java:585` `beginTransaction()`, `:587` the mission SELECT, `:603` the batched relation
  fetch, `:641` `setTransactionSuccessful()`, `:647` `endTransaction()`), so an assertion made from the
  test thread after `first()` returns sees a transaction that has already closed.
- `allowMainThreadQueries()` stays on this case's builder because `RoomDatabase.beginTransaction()` calls
  `assertNotMainThread()`, which returns early only when that flag is set, and the annotation makes Room
  begin a transaction in this path.
- Reflection was ruled out before it was tried: Room's annotations have Java `CLASS` retention, so a
  reflection test on `MissionDao` cannot see `@Transaction` at all. The file's one reflection-shaped case
  (`noWipeForMissionsAndOnlyTheByIdDeleteForSorties`) is about the delete surface and is untouched.
- No threads and no concurrency in the case, per plan §Risks' hang warning
  (`app/build.gradle.kts:42-51` fails a unit test at 5 minutes for exactly this reason); the recording
  list is a `CopyOnWriteArrayList` because Room dispatches DAO work onto its own executor threads.

## 10. State left behind

Nothing under `app/` changed and nothing was pushed. `app/build/verify64/` holds this run's gate and KSP
logs and its report index; `app/build/verify-old-results-1/` holds the results directory `verify.sh` moved
aside (task 3's scratch probe report); `app/build/task4-64/verify-run.log` holds the script's stdout. The
scratch probe under `app/build/task3-64/` is gitignored and only compiles when
`-I app/build/task3-64/init.gradle` is passed, so a gate run never sees it — which this run's gate confirms:
93 classes, no probe class among them.
