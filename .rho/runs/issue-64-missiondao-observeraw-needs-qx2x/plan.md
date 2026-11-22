# Plan

## Approach
One annotation and one test.

`MissionDao.observeRaw` (`MissionDao.kt:49-50`) gets `@Transaction`. Room then wraps the whole
emission — the mission SELECT and the batched `@Relation` sortie SELECT that follow it inside the
same generated callable — in one database transaction, so a `land`/`advance` write can no longer land
between the two reads and produce the stale combination the brief describes. The brief's sweep ("any
other `@Relation` query in the DAOs") comes up empty: `MissionWithSorties.sorties` (`Relations.kt:8`)
is the only `@Relation` in `app/src/main`, so `observeRaw` is the only method to annotate.
`observeWeek` (`MissionDao.kt:52-56`) looks like the target but carries no `@Query`, so Room ignores
it. The rest of the DAO layer needs nothing — its multi-statement methods already carry
`@Transaction` and the rest are single-statement (see map.md).

Two cheap confirmations before writing the test:

- `:app:kspDebugKotlin` prints Room's warning today as the build's only `w:` line (exact text and
  location in map.md). After the change it should print none.
- The generated `MissionDao_Impl` (Java; path in map.md) should show `beginTransaction` /
  `setTransactionSuccessful` / `endTransaction` around the callable `CoroutinesRoom.createFlow` runs.
  That is the direct proof the annotation took effect on a `Flow` query, and where to look if it did not.

## Files
- `app/src/main/java/com/liftoff/app/data/MissionDao.kt` — `@Transaction` on `observeRaw`, plus the
  `androidx.room.Transaction` import. The only production change in the run.
- `app/src/test/java/com/liftoff/app/data/MissionDaoTest.kt` — one new case. The five existing cases
  stay untouched; they are what proves the emission's *content* is unchanged by the annotation.
- No new files, and no changes to `Relations.kt`, `LiftoffDatabase.kt`, `LaunchpadState.kt`,
  `MissionState.kt`, or any other DAO.

## Order
1. Baseline: run the KSP task on the untouched tree and keep the log. It should hold exactly one
   `w:` line, the `MissionDao.kt:50` relation warning — the brief's "one warning the gate prints".
2. Add `@Transaction` to `observeRaw`. Re-run the KSP task: no `w:` lines, and the generated
   `MissionDao_Impl` now brackets the flow callable with `beginTransaction` /
   `setTransactionSuccessful` / `endTransaction`. Compiles clean.
3. Add the new case to `MissionDaoTest` and run that class alone. Green.
4. Prove it can fail: drop the annotation, re-run the class, keep the failure text, put the
   annotation back, re-run green. Earlier runs' plans do the same for a new assertion.
5. Full gate — `:app:assembleDebug` and `:app:testDebugUnitTest` with the toolchain command in
   map.md — and read the log: no `w:` lines, suite green, including `BackupImportRoomTest`, which
   collects `observeWeek` around an import. Commit.

Steps 1–3 and 5 leave the build passing; step 4 is a deliberate, reverted detour on one annotation.

## Testing
- **One emission reads mission and sortie inside a single transaction.** New Room/Robolectric case in
  `MissionDaoTest`: build the in-memory database with Room's query callback on a same-thread
  executor, collect one `observeWeek` emission, and assert both SELECTs — the mission row and the
  sortie relation row — were reported while the database was in a transaction. Without the
  annotation neither read is inside one, so the case fails. It seeds a mission with at least one
  sortie, because Room skips the relation SELECT when the parent query returns nothing (map.md). It
  builds its own callback-equipped database rather than changing the shared `createDb` the other five
  cases use.
- **The transaction bracket is what the test measures, not a race.** No test tries to interleave a
  `land`/`advance` with a collection: that is the bug, but it is timing-dependent and would be a
  flaky gate case. The bracket is the mechanism that makes the stale combination impossible, and it
  is deterministic.
- **The emission's content is unchanged.** The five existing `MissionDaoTest` cases stay untouched
  and green.
- **Consumers are unaffected.** `LaunchpadStatesTest` and the other `ui/launchpad`, `ui/inflight` and
  `ui/landed` suites run against real in-memory DAOs, and `BackupImportRoomTest` watches
  `observeWeek` and asserts it re-emits; all stay green untouched, and nothing in `LaunchpadState` or
  `MissionState` changes.
- **The build prints no Room warning.** Confirmed by reading the gate log for `w:` lines, not by a
  test (see Decisions).

Verify: `MissionDaoTest` alone (new case plus the five existing ones), then the full
`:app:testDebugUnitTest` suite through the gate.

## Risks
- **The query-callback hook is read off the 2.6.1 jars, not run.** map.md shows the callback fires on
  the executor's thread just before the delegated query, so `db.inTransaction()` read inside it should
  see Room's transaction; Robolectric's own SQLite layer is the unproven half. Work does: try it, and
  if the transaction state is not observable, fall back in order — count transaction commits with a
  `RoomDatabase.Callback` and assert one brackets the two SELECTs; only as a last resort assert the
  generated `MissionDao_Impl` brackets the callable. Room's annotations have `CLASS` retention, so
  runtime reflection on `MissionDao` cannot see `@Transaction` (map.md). Say in the commit message
  which shape landed and why.
- **The transaction begins at collection, on Room's query executor.** A case that asserts from the
  test thread sees nothing. Work does: collect with `first()` like the existing cases, and read the
  transaction state only inside the callback.
- **Room + Robolectric can hang** — `app/build.gradle.kts:42-51` already fails a test at 5 minutes for
  exactly this reason. Work does: no threads and no concurrency in the new case; if the class hangs,
  take the fallback rather than raising the timeout.
- **Nesting.** A flow read that begins a transaction could clash with a consumer already inside one.
  Room's generated wrapper is guarded by `if (!db.inTransaction())`, so it joins the outer transaction
  instead of nesting; `MissionManager` and `DatabaseImport` wrap their writes the same way, and
  `BackupImportRoomTest` collects `observeWeek` around an import inside the gate suite — the early
  signal if this is wrong.
- **The gate command in the brief does not run on this machine** (`gradlew.sh` is Windows-only). Work
  does: use the toolchain command in map.md, with `--no-build-cache`, and read the log rather than
  trust a green exit that could be a cache hit.

## Decisions
The brief settles every behavior this run touches: `@Transaction` on `observeRaw` — which turns out to
be the only `@Relation` query in the DAOs — with no change to what the Flow emits or to the Launchpad
and Mission screens that consume it.
