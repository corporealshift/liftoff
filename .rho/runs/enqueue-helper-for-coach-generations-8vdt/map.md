# Map — enqueue helper for coach generations

## Docs that set the rules

- `DESIGN.md` §7.3 "Lifecycle of one generation" (lines 285–344) is the source of the brief's four
  scheduling rules: unique work `gen-<generationId>`, `ExistingWorkPolicy.KEEP`, network-connected
  constraint, exponential backoff starting at 1 minute. Same section also decides (later work, not
  this run): 8 attempts then FAILED "coach unreachable", supersede cancels queued work for the same
  sortie, `nabu.session.stop` only on a finished generation.
- `DESIGN.md` §11 (line 604) lists WorkManager in the stack; §11 line 608 and ARCHITECTURE invariant 5:
  no DI framework, `AppContainer` builds everything by hand.
- `CLAUDE.md`: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only; short comments (why, not what); §2 vocabulary.
- `ARCHITECTURE.md:36` — the `com.liftoff.app.work` row. It ends **"nothing enqueues it yet"**, which
  this run must replace with the helper's name. `ARCHITECTURE.md:82` (M3 row) already lists "WorkManager's
  retry policy and backoff" as still to come; the brief's done_when only names the work row.

## Files involved

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the worker the helper schedules.
  `CoroutineWorker` with a plain `(context, params)` constructor (no `WorkerFactory`), reaching its
  dependencies through `(applicationContext as LiftoffApplication).container`.
  Input key: `GenerationWorker.GENERATION_ID = "generationId"` (line 303), read as
  `inputData.getLong(GENERATION_ID, NO_SUCH_GENERATION)` where `NO_SUCH_GENERATION = -1L` (line 54).
  A missing row → `Result.failure()`; a row already in `FINISHED_STATUSES` (SUCCEEDED/FAILED/CANCELLED)
  → `Result.success()` without a coach turn. Nothing here enqueues anything; no attempt cap yet.
- `app/src/main/java/com/liftoff/app/AppContainer.kt` (66 lines) — `class AppContainer(context: Context)`
  holding `private val appContext`. Members today: lazy `settingsStore`, `database`, `missionManager`;
  suspend `daemonSettings()`, `daemonConnectionCheck()`, `daemonClient()`. No WorkManager anywhere.
  The helper is exposed here.
- `app/src/main/java/com/liftoff/app/LiftoffApplication.kt` (12 lines) — `lateinit var container`
  built in `onCreate`. `app/src/main/AndroidManifest.xml` already declares `INTERNET` and
  `ACCESS_NETWORK_STATE` (the latter is what a network constraint needs), names `.LiftoffApplication`,
  and does **not** remove the default `WorkManagerInitializer` provider, so on a device WorkManager
  initializes itself; no `Configuration.Provider` is implemented anywhere in `app/src`.
- `app/build.gradle.kts:77,86` — `implementation("androidx.work:work-runtime-ktx:2.9.1")`,
  `testImplementation("androidx.work:work-testing:2.9.1")`. Both already there; no dependency change.

## Existing tests

- `app/src/test/java/com/liftoff/app/AppContainerTest.kt` (206 lines) — Robolectric,
  `@Config(sdk = [34])`, container reached as `ApplicationProvider.getApplicationContext<Context>() as
  LiftoffApplication`, waits wrapped in `withTimeout`. Must keep passing with no WorkManager setup.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — 18 tests; builds the worker with
  `TestListenableWorkerBuilder` and calls `doWork()` directly, never through `WorkManager`, so it is
  unaffected by this change. Uses the application's own container (a second `AppContainer` would open a
  second DataStore/Room on the same files).
- Screen tests (`ui/inflight`, `ui/launchpad`, `ui/control`, …) also build screens off the application
  container; none touches WorkManager.

## WorkManager 2.9.1 facts (read off the jars in the Gradle cache, not from memory)

- `WorkManager.enqueueUniqueWork(String, ExistingWorkPolicy, OneTimeWorkRequest)` — single-request
  overload exists (non-abstract). `WorkManagerImpl` also has the `List` overload.
- `WorkRequest.Builder.setBackoffCriteria(BackoffPolicy, java.time.Duration)` and the
  `(BackoffPolicy, long, TimeUnit)` overload both exist. **No clamping**: the duration is written
  straight into `WorkSpec.backoffDelayDuration`. `WorkRequest` constants are
  `DEFAULT_BACKOFF_DELAY_MILLIS = 30_000`, `MIN_BACKOFF_MILLIS = 10_000`, `MAX_BACKOFF_MILLIS = 18_000_000`,
  so 1 minute is legal and stays 1 minute.
- `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()`; readable back with
  `Constraints.getRequiredNetworkType()`.
- `WorkInfo` carries state, id, tags, runAttemptCount, output — **not** constraints or backoff.
  **The easy route is the built request itself**: `WorkRequest.workSpec` is `public final`
  (`@RestrictTo`, which is a lint rule, not a compile error, so Kotlin unit tests compile it), and
  `WorkSpec`'s public fields include `id`, `input` (`Data`), `constraints`, `backoffPolicy`,
  `backoffDelayDuration` (millis), `initialDelay`, `expedited`, `state`, `runAttemptCount`. So a test can
  assert constraints, backoff policy/delay and input data on the request the helper built, with no
  WorkManager and no database at all.
- The harder route, only needed to see what WorkManager actually stored:
  `WorkManagerImpl.getWorkDatabase()` is public in 2.9.1
  (`@RestrictTo`, but Kotlin compiles it), `WorkDatabase.workSpecDao()`, and
  `WorkSpecDao.getWorkSpec(String id)` / `getWorkSpecIdAndStatesForName(String name)`
  (`SELECT id, state FROM workspec WHERE id IN (SELECT work_spec_id FROM workname WHERE name=?)`).
  For unique work the `WorkSpec.id` **is** the unique work name, so `getWorkSpec("gen-<id>")` works.
- `ExistingWorkPolicy` is **not** stored anywhere readable, and `WorkSpec` has no `uniqueName` field
  readable through that DAO route — `KEEP` can only be proven by its effect (one item under the name,
  keeping the first request's id).
- `TestDriver` in 2.9.1 is only `setAllConstraintsMet(UUID)`, `setInitialDelayMet(UUID)`,
  `setPeriodDelayMet(UUID)` — no WorkSpec access. `WorkManagerTestInitHelper` has
  `initializeTestWorkManager(Context)`, `getTestDriver(Context)`, `closeWorkDatabase()`.
- `initializeTestWorkManager` builds the WorkDatabase **in-memory** with
  `Room…inMemoryDatabaseBuilder(…).allowMainThreadQueries()` and installs it with
  `WorkManagerImpl.setDelegate(...)` (a plain static swap, no throw when repeated). So under Robolectric
  a test may read the WorkSpec synchronously on the main thread, and each call to
  `initializeTestWorkManager` gives a fresh in-memory DB.
- `WorkManagerImpl.initialize` throws `IllegalStateException` ("WorkManager is already initialized…")
  only when both the delegated and default instances are set; `getInstance()` returns the delegate if
  one is set, otherwise the default (null under Robolectric without init → `WorkManager.getInstance`
  throws "not initialized properly"). That is why the helper must call `getInstance` only when called.
- work-runtime-ktx suspend helpers available on the test classpath: `androidx.work.await` on a
  `ListenableFuture` and `Operation.await()` (throws on failure). Guava futures also accept
  `.get(n, TimeUnit)`.
