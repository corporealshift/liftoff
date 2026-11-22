# Plan

## Approach
One annotation and one test.

`MissionDao.observeRaw` (`MissionDao.kt:49-50`) gets `@Transaction`. Room then wraps the whole
emission in one database transaction: the mission SELECT and the batched `@Relation` sortie SELECT
that runs after it inside the same generated callable. A `land`/`advance` write can no longer land
between the two reads and produce the stale combination the brief describes. The brief's sweep ("any
other `@Relation` query in the DAOs") comes up empty: `MissionWithSorties.sorties` (`Relations.kt:8`)
is the only `@Relation` in `app/src/main`, so `observeRaw` is the only method to annotate.
`observeWeek` (`MissionDao.kt:52-56`) looks like the target but carries no `@Query`, so Room ignores
it. The rest of the DAO layer needs nothing. Its multi-statement methods already carry
`@Transaction` and the others are single-statement (see map.md).

Two cheap confirmations before writing the test:

- `:app:kspDebugKotlin` prints Room's warning today as the build's only `w:` line (exact text and
  location in map.md). After the change it should print none.
- The generated `MissionDao_Impl` (Java; path in map.md) should show `beginTransaction` /
  `setTransactionSuccessful` / `endTransaction` around the callable that `CoroutinesRoom.createFlow`
  runs. That is the direct proof the annotation took effect on a `Flow` query, and where to look if
  it did not.

## Files
- `app/src/main/java/com/liftoff/app/data/MissionDao.kt`: `@Transaction` on `observeRaw`. The file
  already imports `androidx.room.*`, so no new import. This is the only production change in the run.
- `app/src/test/java/com/liftoff/app/data/MissionDaoTest.kt`: one new case. The five existing cases
  stay untouched. They are what proves the annotation leaves the emission's *content* unchanged.
- No new files, and no changes to `Relations.kt`, `LiftoffDatabase.kt`, `LaunchpadState.kt`,
  `MissionState.kt`, or any other DAO.

## Order
1. Baseline: run the KSP task on the untouched tree and keep the log. It should hold exactly one
   `w:` line, the `MissionDao.kt:50` relation warning. That is the brief's "one warning the gate
   prints".
2. Add `@Transaction` to `observeRaw`. Re-run the KSP task. Expect no `w:` lines, and the generated
   `MissionDao_Impl` now brackets the flow callable with `beginTransaction` /
   `setTransactionSuccessful` / `endTransaction`. It compiles clean.
3. Add the new case to `MissionDaoTest` and run that class alone. Green.
4. Prove the case can fail: drop the annotation, re-run the class, keep the failure text, put the
   annotation back, re-run green.
5. Full gate (`:app:assembleDebug` and `:app:testDebugUnitTest` with the toolchain command in
   map.md), then read the log. Expect no `w:` lines and a green suite, including
   `BackupImportRoomTest`, which collects `observeWeek` around an import. Commit.

Steps 1–3 and 5 leave the build passing. Step 4 is a deliberate detour on one annotation, reverted
at once.

## Testing
- **One emission reads mission and sortie inside a single transaction.** New Room/Robolectric case in
  `MissionDaoTest`. It builds the in-memory database with Room's query callback on a same-thread
  executor and collects one `observeWeek` emission. It then asserts that both SELECTs (the mission
  row and the sortie relation row) were reported while the database was in a transaction. Without
  the annotation neither read is inside one, so the case fails. The callback records only the two
  SELECTs from this collection. Seed inserts also pass through the callback and run inside Room's
  own write transactions, so they must not count. The case seeds a mission with at least one sortie,
  because Room skips the relation SELECT when the parent query returns nothing (map.md). It builds its
  own callback-equipped database and leaves the shared `createDb` that the other five cases use alone.
- **The test measures the transaction bracket, not a race.** No test tries to interleave a
  `land`/`advance` with a collection. That race is the bug, but it depends on timing and would make a
  flaky gate case. The bracket is the mechanism that makes the stale combination impossible, and
  checking it is deterministic.
- **The emission's content is unchanged.** The five existing `MissionDaoTest` cases stay untouched
  and green.
- **Consumers are unaffected.** `LaunchpadStatesTest` and the other `ui/launchpad`, `ui/inflight` and
  `ui/landed` suites run against real in-memory DAOs. `BackupImportRoomTest` watches `observeWeek` and
  asserts it re-emits. All of them stay green untouched, and nothing in `LaunchpadState` or
  `MissionState` changes.
- **The build prints no Room warning.** Checked by reading the gate log for `w:` lines, not by a test
  (see Decisions).

Verify: `MissionDaoTest` alone (the new case plus the five existing ones), then the full
`:app:testDebugUnitTest` suite through the gate.

## Risks
- **The query-callback hook was read off the 2.6.1 jars, not run.** map.md shows the callback fires
  on the executor's thread just before the delegated query, so `db.inTransaction()` read inside it
  should see Room's transaction. Robolectric's own SQLite layer is the unproven half. Work does: try
  it. If the transaction state is not observable, fall back in this order. First, count transaction
  commits with a `RoomDatabase.Callback` and assert that one commit brackets the two SELECTs. Only as
  a last resort, assert that the generated `MissionDao_Impl` brackets the callable. Room's
  annotations have `CLASS` retention, so runtime reflection on `MissionDao` cannot see
  `@Transaction` (map.md). Say in the commit message which shape landed and why.
- **The transaction begins at collection, on Room's query executor.** A case that asserts from the
  test thread sees nothing. Work does: collect with `first()` like the existing cases, and read the
  transaction state only inside the callback.
- **Room + Robolectric can hang.** `app/build.gradle.kts:42-51` already fails a test at 5 minutes for
  exactly this reason. Work does: no threads and no concurrency in the new case. If the class hangs,
  take the fallback rather than raising the timeout.
- **Nesting.** A flow read that begins a transaction could clash with a consumer that is already
  inside one. Room's generated wrapper is guarded by `if (!db.inTransaction())`, so it joins the
  outer transaction instead of nesting. `MissionManager` and `DatabaseImport` wrap their writes the
  same way. `BackupImportRoomTest`, which collects `observeWeek` around an import inside the gate
  suite, is the early signal if this is wrong.
- **The gate command in the brief does not run on this machine.** `gradlew.sh` hardcodes Windows
  `C:/` toolchain paths. Work does: use the toolchain command in map.md with `--no-build-cache`, and
  read the log rather than trust a green exit that could be a cache hit.

## Decisions
- **Added by review: the "no Room warnings" check is a log read, not a test.** The brief asks to
  confirm that the gate prints no Room warnings. The plan checks this by grepping the KSP and gate
  logs for `w:` lines before and after the change. It does not add a `-Werror`-style build flag or a
  test that parses build output. Either of those would make every future, unrelated KSP warning fail
  the build, which goes beyond this issue.
- **Added by review: the gate runs through the map.md toolchain command, not `bash gradlew.sh`.** The
  brief and CLAUDE.md name `gradlew.sh`, but the script hardcodes `C:/Users/corpo/...` paths and
  cannot run here. The plan runs the same two tasks (`:app:assembleDebug :app:testDebugUnitTest`)
  with the equivalent local toolchain paths. `gradlew.sh` itself is left alone, because fixing it is
  outside this issue.
- **Added by review: the behaviour test asserts the transaction bracket, not the race itself.** The
  brief asks for "a test for the behaviour change". The plan tests that one emission's two reads run
  inside a transaction, with a fallback ladder in Risks. It does not reproduce the land/advance
  interleaving, because that depends on timing and would be flaky in the gate.
- The brief settles every other behavior this run touches. `@Transaction` goes on `observeRaw`, which
  turns out to be the only `@Relation` query in the DAOs. Nothing changes in what the Flow emits or in
  the Launchpad and Mission screens that consume it.
