# Tasks — enqueue helper for coach generations

Four tasks, each ending in a commit. Commit messages follow CLAUDE.md (`area: lowercase
summary`, stage named files only, short comments). Read `map.md` before starting: it records
`GenerationWorker`'s input key, the WorkManager 2.9.1 facts (what is readable on `WorkRequest.workSpec`
vs `WorkInfo`), and what `AppContainer` holds today.

All six tests `.rho/runs/enqueue-helper-for-coach-generations-8vdt/verify.sh` §2 names live in **one**
new class, `app/src/test/java/com/liftoff/app/work/GenerationEnqueuerTest.kt` (Robolectric
`@Config(sdk = [34])`, WorkManager set up with `WorkManagerTestInitHelper.initializeTestWorkManager`,
every wait given a timeout or run inside `withTimeout`). Task 1 creates the class; tasks 2 and 3 add
tests to it. The names below are spelled exactly as verify.sh spells them.

- [x] 1. The enqueue helper, and the shape of the request it builds
  Covers: `app/src/main/java/com/liftoff/app/work/GenerationEnqueuer.kt` (new) — builds the one-time
  request for `GenerationWorker` carrying the generation id under `GenerationWorker.GENERATION_ID`, with
  the connected-network constraint and exponential backoff whose first delay is 60 000 ms, enqueues it as
  unique work named `gen-<generationId>` with `ExistingWorkPolicy.KEEP`, and hands the built request back
  to the caller. It writes no rows, and it must be reachable both from a test and from the container
  member task 3 adds. Also creates `GenerationEnqueuerTest.kt` with the two tests that read the shape off
  the built request's `workSpec` (`constraints`, `backoffPolicy`, `backoffDelayDuration`, `input`) — none
  of these are on `WorkInfo`. Set WorkManager up per test in `@Before` so each test gets a fresh in-memory
  WorkDatabase, and close it in `@After`.
  Tests: `theScheduledWorkRequiresAConnectedNetworkAndExponentialBackoffFromOneMinute`,
  `theScheduledWorkCarriesTheGenerationIdInTheWorkersInputData`.
  Done when: `bash gradlew.sh :app:compileDebugKotlin` passes; a cold run of the class
  (`:app:testDebugUnitTest --tests 'com.liftoff.app.work.GenerationEnqueuerTest' --no-build-cache`)
  reports `tests=2 failures=0` in `app/build/test-results/testDebugUnitTest/TEST-*.xml`; and each
  assertion is shown to bite — one mutation at a time (drop the constraint, change the backoff policy,
  change the delay to WorkManager's 30 000 default, drop the input) each makes the run fail, then the
  helper is restored byte-for-byte from a copy kept under `app/build/` before committing.

- [x] 2. Unique work under `gen-<generationId>`, and `KEEP` by its effect
  Covers: two more tests in `GenerationEnqueuerTest.kt` that ask WorkManager what it actually stored,
  through `getWorkInfosForUniqueWork("gen-N")`: calling the helper twice for one generation leaves exactly
  one item under that name, still pending, and it is the item the first call scheduled (the same work id —
  `REPLACE` would also leave one item, so the retained id is the only proof of `KEEP`); and two
  generations give one item each under their own names, with different ids. The network constraint is left
  unmet on purpose — no `TestDriver`, so no worker runs. The helper is only touched if a mutation check
  shows it is wrong.
  Tests: `enqueueingOneGenerationTwiceLeavesASingleWorkItemUnderItsName`,
  `twoGenerationsAreScheduledAsTwoWorkItemsUnderTheirOwnNames`.
  Done when: a cold run of the class reports `tests=4 failures=0`; and both tests are shown to bite —
  change the policy to `REPLACE` (expect the first test to fail) and drop the generation id from the unique
  name so two generations share one name (expect the second test to fail), then restore the helper from the
  copy under `app/build/`.

- [x] 3. Expose the helper from `AppContainer`, and prove it only schedules
  Covers: `app/src/main/java/com/liftoff/app/AppContainer.kt` gains the member that exposes the helper,
  calling `WorkManager.getInstance(appContext)` **inside the call** — never in a field or a `lazy`, so
  building a container touches nothing WorkManager-related (that is what keeps `AppContainerTest` and the
  screen tests working with no WorkManager setup). Two more tests in `GenerationEnqueuerTest.kt`, both on
  the application's own container (`(ApplicationProvider.getApplicationContext<Context>() as
  LiftoffApplication).container`, never a second `AppContainer`, which would open a second DataStore and
  Room database on the same files), with WorkManager initialized before the member is called: the member
  schedules a pending item under `gen-N`; and calling it for a generation id with no `Generation` row
  leaves the generation table exactly as it was (`database.generationDao()` count unchanged, no row with
  that id) while the work item for that id is still scheduled.
  Tests: `theAppContainerExposesTheEnqueueHelper`,
  `theEnqueueHelperSchedulesWorkAndCreatesNoGenerationRows`.
  Done when: a cold run of both classes reports `tests=6 failures=0` for `GenerationEnqueuerTest`
  (`AppContainerTest`'s own tests are counted separately in the same run) with `AppContainerTest` passing
  with no WorkManager setup; and the "creates no rows" test is shown to bite — make the helper insert the
  row it was handed, watch that test fail, then restore the helper from the copy under `app/build/`.

- [ ] 4. ARCHITECTURE.md's two rows, then the whole gate
  Covers: `ARCHITECTURE.md:36`, the `com.liftoff.app.work` row — replace its closing claim "nothing
  enqueues it yet" with the helper named as an artifact the doc does not already use (a backticked name
  such as `GenerationEnqueuer.kt`); edit the row in place, one clause, leaving the rest of its wording
  alone. `ARCHITECTURE.md:82`, the M3 row — add the enqueue helper to the built list and take WorkManager's
  backoff out of the "still to come" clause, leaving "app wiring" there because nothing calls the helper
  yet; change nothing else in the row. Then run the whole gate detached (`bash gradlew.sh
  :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, log under `app/build/`, results dir
  deleted first), re-running the same tree if it hangs for about 5 minutes and writes no `TEST-*.xml`.
  Done when: `grep -n 'com\.liftoff\.app\.work' ARCHITECTURE.md` shows a row that no longer says nothing
  enqueues the worker and names a new backticked artifact; the M3 row no longer lists backoff as still to
  come; and the gate log ends `BUILD SUCCESSFUL` with reports showing zero failures and zero errors and
  `GenerationEnqueuerTest`, `GenerationWorkerTest`, `AppContainerTest`, `MainActivityTest`,
  `LiftoffShellTest`, `MissionControlScreenTest`, `InFlightScreenTest`, `InFlightStatesTest`,
  `LaunchpadStatesTest`, `MissionStatesTest`, `LandedStatesTest` all present. If the whole-suite run
  exposes a real break (something the per-class runs could not see), fix it in this task's commit.
