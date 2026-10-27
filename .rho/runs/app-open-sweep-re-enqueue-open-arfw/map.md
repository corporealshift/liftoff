# Map — app-open sweep

Branch HEAD at the tasks step: `78ce856` ("verify.sh rewritten by claude review"); at planning it was `62d43c8`
("run: plan outline"), on top of `bda0e26` (#43 In-Flight enqueues…).
Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (CLAUDE.md); counts come from
`app/build/test-results/testDebugUnitTest/TEST-*.xml`, not stdout.

## Docs that set rules

- `CLAUDE.md` — gate command; `area: lowercase summary` commits; stage named files only; §2 vocabulary
  (`Mission`, `Sortie`, `FlightPlan`, `Generation`, never "session" for a sortie); short comments, why not what.
- `DESIGN.md` headings (read by range, do not grep "run"): §4 Missions 109-152, §5 Sorties 155-201,
  §7 Generation 223-344, §8 data model 520, §13 testing 658.
  - **§4.4 does not exist.** §4 stops at §4.3. The brief's "§4.4" is a slip for §4.2 Lifecycle (119-142:
    draft/confirm/closed, "unfinished sorties do not carry over") and §4.3 Rolling order (144-151: the next
    sortie becomes current and its Flight Plan generation is queued if it isn't already planned).
  - §5.3 (180-193): run plans only when the Generate-run-plans toggle is on; a run with it off gets a
    `SIMPLE_RUN` plan and **no generation row at all**.
  - §7.2 (274-283): FLIGHT_PLAN is triggered by outline stored (sortie 1) or previous sortie landed/scrubbed.
  - §7.3 (285-343): unique work `gen-<id>` + `KEEP`, network constraint, 1-min exponential backoff; the worker
    is resumable because all progress lives on the row, "the nabu session keeps running on the PC even if the
    phone's worker dies"; superseded generations are cancelled (work + session stop).
  - DESIGN still says "nabu"; the code and ARCHITECTURE say rho (`rho.session.*`). The DB column is still
    `nabuSessionId` — renaming it would be a schema change, which the brief forbids.
- `ARCHITECTURE.md` — the `com.liftoff.app.work` row is line 36 (Responsibility "Android-side workers
  (`GenerationWorker`). Uses WorkManager and Android APIs."; third column lists each file in the package).
  The M3 milestone row is line 82. The brief asks only for the work row.

## Main code

- `app/src/main/java/com/liftoff/app/MainActivity.kt` (37 lines) — `onCreate` launches
  `repeatOnLifecycle(STARTED) { container.missionManager.onAppOpen() }` on `lifecycleScope` (Main dispatcher),
  then `setContent { LiftoffShell() }`. Nothing else runs at open. This is where the sweep gets called.
- `LiftoffApplication.kt` — holds `container: AppContainer`; no WorkManager setup anywhere in main code.
- `AppContainer.kt` — `generationEnqueuer(): GenerationEnqueuer` (line 39) and `generationCanceller()`
  (line 46) both call `WorkManager.getInstance(appContext)` **per call**, so building a container touches
  nothing WorkManager-related; `generationCanceller()` also passes `openClient = { daemonClient() }` (suspend).
- `work/GenerationEnqueuer.kt` — `enqueue(generationId): OneTimeWorkRequest`, unique name
  `gen-<generationId>` via the file-level `internal fun uniqueName(generationId)` (line 36, shared with the
  canceller), `ExistingWorkPolicy.KEEP`, network-connected constraint, 1-minute exponential backoff.
  KEEP means an enqueue while that work is already pending/running creates nothing new.
- `work/GenerationCanceller.kt` — `suspend fun cancel(generationId)`: `workManager.cancelUniqueWork` first,
  then one Room transaction that marks the row CANCELLED **only while it is QUEUED or RUNNING**
  (`CANCELLABLE_STATUSES`), then `giveUpSession(nabuSessionId)` which swallows every exception except
  `CancellationException`. A row that already decided its outcome is left alone.
- `work/GenerationWorker.kt` — one generation end to end; on a retry it re-enqueues itself through
  `container.generationEnqueuer().enqueue(generationId)` (line 363); stores only while its generation is
  still the current one (currency guard + store in one transaction).
- `data/Generation.kt` — `id, kind, missionId, sortieId (null for an outline), status, nabuSessionId,
  lastEventId, attempt, error, createdAt, finishedAt`; `Generation.COACH_OFFLINE` and `isCoachOffline`.
- `data/GenerationDao.kt` — has `get`, `getAll`, `insertAll`, `countAll`, `markRunning/Succeeded/Failed/Cancelled`,
  `saveSessionId`, `saveLastEventId`, `bumpAttempt`, `setCoachOfflineMark`,
  `getNewestNonCancelledForSortie`, `getNewestNonCancelledOutlineForMission` + its `observe…` Flow,
  `getOpenOutlineForMission(missionId)` and `getOpenFlightPlanForSortie(sortieId)` — both filtered
  `status IN ('QUEUED','RUNNING')`, both keyed by one id. **There is no whole-table open query**: the sweep
  needs one (`status IN ('QUEUED','RUNNING')` over every row). A `@Query` is not a schema change.
- `data/Enums.kt` — `MissionStatus { DRAFT, ACTIVE, CLOSED }`, `SortieState { PENDING, PLANNED, IN_FLIGHT,
  LANDED, SCRUBBED }`, `GenerationKind { OUTLINE, FLIGHT_PLAN }`, `GenerationStatus { QUEUED, RUNNING,
  SUCCEEDED, FAILED, CANCELLED }`.
- `data/MissionManager.kt` (423 lines) — ctor `(db, settingsStore, clock = systemUTC, zone = systemDefault)`;
  `currentWeekStart: MutableStateFlow<LocalDate?>` set at the end of `onAppOpen()` (line 91) and null until
  then. `onAppOpen()` (70-93): one transaction that runs `rollover` over `getUnclosedBefore(weekStart)`,
  then makes or reads the current week's Mission; returns it. `advance(missionId, settings)` (382-419, private)
  is the only place a `FLIGHT_PLAN` row is inserted: for the **current PENDING** sortie, only when
  `prepareCurrent` says `AwaitGeneration`, only when the coach is configured, reusing the sortie's open row
  instead of inserting a second one, returning the id for the caller to enqueue after commit.
  `storePlan` (335-374) refuses a plan unless the mission is ACTIVE, the sortie is PENDING or PLANNED, and it
  is `currentSortie(...)` — the same three conditions the sweep uses. `refly` returns the sortie's open
  FLIGHT_PLAN ids for the caller to cancel. `land`/`scrub` return the id `advance` queued.
- `data/MissionDao.kt` — `get`, `getByWeekStart`, `getUnclosedBefore(weekStart)` (`status != 'CLOSED'`),
  `getByIds(List<Long>)`, `getAll`, `insertAll`, `deleteDraft` (only a DRAFT can be deleted), `observeWeek`.
- `data/SortieDao.kt` — `get(id)`, `getForMission(missionId)` (ordered by index), `getInFlight()`, `getAll`,
  `deleteById`, `observeById`, `observeHistory`, `getFinishedNewestFirst`.
- `domain/Sorties.kt` — `currentSortie(sorties)`: lowest index that is neither LANDED nor SCRUBBED;
  `SortieTransitions`, `launch/land/scrub/markPlanned`.
- `domain/Week.kt` — `currentWeekStart(clock, zone)`: the week an instant falls in (MissionManager's private
  `currentWeekOf` is the same thing).
- Other callers of `onAppOpen()` besides `MainActivity`: `LaunchpadViewModel` calls it before almost every action
  (lines 57, 70, 83, 100, 122, 136, 167, 193, 220 — each `runCatching`), and `DatabaseImport` calls it after a
  successful restore (line 69). `LaunchpadState`/`MissionState`/`LiftoffShell` read
  `manager.currentWeekStart` (a `MutableStateFlow`, null until the first `onAppOpen`) to know the week.
- `domain/Rollover.kt` — `rollover(mission, sorties, today)`: skips CLOSED missions and weeks that haven't
  ended; scrubs every sortie that isn't LANDED/SCRUBBED with `WEEK_ENDED = "week ended"`; closes the Mission.
- `domain/Missions.kt` — `MissionLifecycle` transitions, `newDraft`, `allSortiesDone`.
- UI lambda shape the sweep should mirror: `LaunchpadViewModel` takes `queueGeneration: (Long) -> Unit` and
  `cancelGeneration: suspend (Long) -> Unit` (lines 27, 29); `LaunchpadScreen` (57-58) and `InFlightScreen`
  (57) pass `container.generationEnqueuer().enqueue(id)` / `container.generationCanceller().cancel(id)`.
  `MissionManager` hands ids out of its transactions through a defaulted `onQueued: (Long) -> Unit = {}`
  or a returned `Long?`, and calls it after the transaction returns.

## Tests that already exist and what they do

- `app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt` — the fixture to copy: in-memory Room with
  `allowMainThreadQueries()`, `PreferenceDataStoreFactory.create { File(context.cacheDir,
  "settings.preferences_pb") }`, `MissionManager(db, settingsStore, clock = Clock.fixed(...), zone =
  { ZoneOffset.UTC })`, and an `advanceClock(days)` helper that rebuilds the manager on a later instant
  (that is how a test gets the week-ended rollover to run). `@After db.close()`.
  Sibling classes: `MissionManagerOutlineTest`, `MissionManagerPlanTest`, `MissionManagerReFlyTest`.
- `app/src/test/java/com/liftoff/app/work/` — `GenerationEnqueuerTest`, `GenerationCancellerTest`,
  `GenerationWorkerTest` are the only classes that call `WorkManagerTestInitHelper.initializeTestWorkManager`
  (Canceller 67, Enqueuer 41, Worker 1343/1400); they build the helpers through a real `AppContainer`.
- `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt` — `createAndroidComposeRule<MainActivity>()`,
  so `MainActivity.onCreate` really runs; `@Before` calls `onAppOpen()` itself on the container. Its DB is a
  fresh Robolectric database with a DRAFT Mission and **no Generation rows**, and no Mission Control host/token.
  Waits use `WAIT_MS` headroom and it waits on the draft title `NEW MISSION`, never on a status-dependent node.
- `app/src/test/java/com/liftoff/app/MainActivityTest.kt` — placeholder assertions, launches nothing.
- `app/src/test/java/com/liftoff/app/ui/ShellNavTest.kt` — pure `ShellNav` unit test; **does not** touch the
  container or MainActivity (the brief's fact about it is out of date).
- `GenerationDaoTest`, `MissionDaoTest`, `SortieDaoTest` — DAO-level in-memory Room tests.
- `MainActivityTest` is two placeholder assertions on literals (no Robolectric, launches nothing); `ShellNavTest` is a
  pure `ShellNav` unit test. Only `LiftoffShellTest` really runs `MainActivity.onCreate`.
- `MissionManager.refly` (line 303) marks the current PENDING sortie PLANNED and returns its open FLIGHT_PLAN ids for
  the caller to cancel. So the state the sweep's PLANNED arm meets is real: a row left QUEUED on a PLANNED current
  sortie is a re-fly whose cancel was lost. Per the brief's rule that row is **wanted** and is re-enqueued, not
  cancelled — the coach's plan then replaces the copied one, which `storePlan` allows on a PLANNED sortie.
- 822 test methods across 60 `*Test.kt` classes today; 681 `@Test` annotations.

## Fixture and wiring facts (read at the tasks step)

- `MainActivity.kt` :26-30 is the whole app-open hook: `lifecycleScope.launch { repeatOnLifecycle(STARTED) { …
  onAppOpen() } }`, and the `Mission` it returns is discarded today. `onAppOpen(): Mission` is **non-null**
  (`MissionManager.kt`:70-93), so the wiring task can capture the returned Mission's `weekStart` there; `setContent`
  follows at :31.
- `MissionManagerTest.kt` is the fixture `AppOpenSweepTest` copies: clock `Clock.fixed(Instant.parse(
  "2026-01-12T00:00:00Z"), ZoneOffset.UTC)` with `zone = { ZoneOffset.UTC }`, so the fixture's week starts
  **2026-01-12**; helpers `insertMission(weekStart, pattern, status): Long` (:72), `insertSortie(missionId, index,
  type, focus, state): Long` (:78, every nullable Sortie column passed null), `configureCoach(host, token)` (:87),
  `seedOutline(missionId, status): Generation` (:104 — `createdAt = 0L`, `finishedAt = 1L` only for SUCCEEDED/FAILED,
  `error` only for FAILED), and `advanceClock(days)` (:62) which **rebuilds the manager** on `2026-01-12 + days`;
  that rebuild is how a test makes the week-ended rollover run. There is no `seedFlightPlan` helper — a FLIGHT_PLAN
  row is seeded with the same `db.generationDao().insert(Generation(kind = FLIGHT_PLAN, missionId = …, sortieId = …,
  status = …))` shape.
- For the sweep's cancel cases, seed the not-done sorties directly through `insertSortie`: `MissionManager.land`
  rewrites the pattern's OPEN sets to SKIPPED and can queue a `FLIGHT_PLAN` row of its own through `advance`, so a
  case that lands its way to a LANDED sortie also gains a row it did not ask for.
- Packages for `--tests` flags: `com.liftoff.app.MainActivityTest`, `com.liftoff.app.AppContainerTest`,
  `com.liftoff.app.ui.LiftoffShellTest`, `com.liftoff.app.ui.ShellNavTest`, `com.liftoff.app.data.GenerationDaoTest`
  / `MissionDaoTest` / `SortieDaoTest` / `MissionManagerTest`. The new sweep class and its test are
  `com.liftoff.app.work.AppOpenSweep` / `AppOpenSweepTest`.
- `ARCHITECTURE.md`: the `com.liftoff.app.work` **package** row (line 36) names `CoachTurnRunner.kt`,
  `GenerationWorker.kt` and `GenerationEnqueuer.kt` in its third column and does **not** name
  `GenerationCanceller.kt`; the canceller is described in the M3 **milestone** row (line 82). The brief asks only for
  the package row, so the doc task adds the sweep there without contradicting either row's existing wording.

## tasks.md (this run)

Six tasks. 1 = the `GenerationDao` whole-table open query + `AppOpenSweep` + the new `AppOpenSweepTest` holding the
fixture and `anEmptyGenerationTableCallsNeitherLambda`. 2 = the three wanted-row tests. 3 = the three cancel tests.
4 = `aRowThatFailsDoesNotStopTheSweepOrTheOtherRows` plus its CancellationException arm. 5 = the `MainActivity`
wiring, gated on `LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`, `AppContainerTest` and `AppOpenSweepTest`.
6 = the ARCHITECTURE.md `work` row and the whole gate, run through a copy of `verify.sh` under `app/build/`. Each
task's Done-when is one cold class-scoped Gradle run naming every class it touches; only task 6 runs the whole suite.

## verify.sh (this run)

HEAD's `verify.sh` is `78ce856` "verify.sh rewritten by claude review" (on top of `2d7b584` "run: verify"); the
review rewrite shortened comments and **renamed one test**. Read the file at HEAD, not an earlier note.
Eight named tests, all new to this branch (re-confirmed at the tasks step: none of the eight names appears anywhere
under `app/src` today), all matched by method name across every report the gate run writes:
`queuedFlightPlanForTheCurrentPendingSortieIsEnqueuedNotCancelled` (a QUEUED FLIGHT_PLAN row on the current week's
ACTIVE Mission's current PENDING sortie), `queuedOutlineForTheCurrentDraftMissionIsEnqueuedNotCancelled`,
`runningFlightPlanForTheCurrentPlannedSortieIsEnqueuedNotCancelled` (the PLANNED arm *and* the RUNNING half of "every
open row": a RUNNING row on a current PLANNED sortie),
`weekEndedRolloverCancelsTheScrubbedSortiesRowAndThePastDraftsOutlineRow`,
`rowsForSortiesThatAreNoLongerCurrentAreCancelled`, `decidedGenerationsAreLeftAlone`,
`anEmptyGenerationTableCallsNeitherLambda`, `aRowThatFailsDoesNotStopTheSweepOrTheOtherRows` (also the
CancellationException arm).
Then 40 class checks, then the exported schema under `app/schemas` compared byte-for-byte against `a40dff8` (the
commit that added this run's brief) — a new `@Query` leaves it identical. No ARCHITECTURE.md wording check.
The MainActivity wiring itself is not checked: proving it needs a Robolectric test that launches MainActivity with
WorkManager initialized and rows seeded in the app's own database, which the brief's test list ("no WorkManager") and
the plan's Testing section both rule out. What is checked instead is that the shell classes stay green with
WorkManager uninitialized, plus the empty-table test.
