# Tasks

Six tasks, in this order. 1 and 2 are the two production changes the brief asks for; 3, 4 and 5 write the seven
tests `verify.sh` names; 6 runs the whole gate. Each ends in one commit.

`verify.sh` names seven tests, all new to this branch, and they are split 3 / 2 / 2 across tasks 3, 4 and 5, spelled
below exactly as `verify.sh` spells them. No task writes any other test `verify.sh` names: every other check it makes
is the gate, a whole-class check, or the exported schema.

Shared by the test tasks (also recorded in map.md): the coach is configured through `SettingsStore.setDaemonHost` /
`setDaemonToken` **before** the class's `seedMission()` calls `onAppOpen()`; `setGenerateRunPlans(true)` only
**after** the fixture has landed its own first sortie and before the view model's land / scrub; nothing moves into
`setUp`, so every existing test keeps the behaviour it was written for; no WorkManager and no daemon in any of these
tests; every wait goes through the class's own `withTimeout(5_000)` helpers; a recorded id is read back through
`db.generationDao().get(id)` / `getNewestNonCancelledForSortie(sortieId)`.

- [x] 1. `InFlightViewModel`: carry the id its land / landRun / scrub queued out to an enqueue input
  Covers plan step 1. File: `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` — one more
  constructor parameter after the defaulted `missionManager`, a defaulted no-op `(Long) -> Unit`, the shape
  `LaunchpadViewModel.queueGeneration` uses. `land` (:89), `landRun` (:117) and `scrub` (:148) capture the `Long?`
  their manager call returns and call it once, after that call has returned, only when it is non-null, keyed on the
  id alone — never on `left` and never through `leaveOnce`, which the leave watcher can have already made a no-op.
  The call sits where a throw from it cannot reach the existing `catch (Exception)` that writes "Couldn't land/scrub
  this sortie."; a `CancellationException` still propagates. No other file, no schema change, no new double-tap
  guard — the existing `busy` / `left` guards are what absorb a second tap.
  Done when: `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` passes with the fourteen
  existing `InFlightViewModel(...)` call sites map.md lists left untouched, and a cold
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.inflight.InFlightLandScrubTest" --tests "com.liftoff.app.ui.inflight.InFlightRunTest" --tests "com.liftoff.app.ui.inflight.InFlightViewModelTest" --tests "com.liftoff.app.ui.inflight.InFlightLeaveTest" --tests "com.liftoff.app.ui.inflight.InFlightStatesTest"`
  reports 0 failures and 0 errors.

- [x] 2. `InFlightScreen`: pass the container's enqueuer as a lambda
  Covers plan step 2. File: `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` — at the existing
  construction site (:48-51), inside the same `remember(sortieId)`, pass a lambda that reaches
  `container.generationEnqueuer().enqueue(id)` only when it is invoked, the way `launchpadViewModel` does. Nothing
  may touch `generationEnqueuer()` while composing: it calls `WorkManager.getInstance`, which throws when
  WorkManager was never initialized, and `InFlightScreenTest` (:45) plus the shell classes compose the real container
  with no initialization. No test file changes in this task, and no screen test gets a host or a token.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.inflight.InFlightScreenTest" --tests "com.liftoff.app.ui.LiftoffShellTest" --tests "com.liftoff.app.ui.ShellNavTest" --tests "com.liftoff.app.MainActivityTest"`
  passes cold with every case in those four classes green.

- [x] 3. `InFlightLandScrubTest`: the ids landing and scrubbing hand to the enqueue
  Covers plan step 3. File: `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt`. The class's
  `launchSortie1()` (:138-146) builds the view model at :143 with no enqueue input, so add a coach-configured
  variant of that path that passes a recording `(Long) -> Unit` and hands back the ids it recorded; leave
  `launchSortie1()` and every existing test untouched. The class has no `generationDao` field — add one in this file
  or read through `db.generationDao()`. On this fixture the sortie landing the lift makes current is **sortie 2, a
  RUN**, so the run-plan toggle has to be on after `landSortie0` and before the view model's land / scrub or
  `advance` returns null and nothing is queued; and the fixture's own `landSortie0` already queued an open row for
  sortie 1, so assert against sortie 2's row, not against a row count or "a new row appeared".
  Tests it writes, spelled exactly as `verify.sh` uses them (`land` needs no set checked, and `scrub` takes an
  optional reason):
  `landFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce` (exactly one id handed over, and reading it back
  gives kind `FLIGHT_PLAN`, status `QUEUED`, on sortie 2; the sortie is LANDED; `onDone` ran once),
  `scrubFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce` (the same through `scrub`, the sortie SCRUBBED),
  `landWithMissionControlUnsetEnqueuesNothingAndStillLands` (no host and no token, the toggle on at the same point
  so it fails for the missing Mission Control and not for the toggle: the recording lambda was handed nothing at all,
  the sortie still lands, `onDone` ran, `message` stayed null).
  Done when: a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.inflight.InFlightLandScrubTest"`
  passes with every case in the class green and its `TEST-*.xml` naming all three.

- [x] 4. `InFlightLandScrubTest`: an enqueue that throws is not turned into a failure message
  Covers the brief's swallow rule, in the class task 3 prepared:
  `landWithAFailingEnqueueStillLandsAndShowsNoMessage` and `scrubWithAFailingEnqueueStillScrubbsAndShowsNoMessage` —
  a lambda that throws when it is called: the sortie is LANDED / SCRUBBED, `onDone` ran once, `message` stayed null,
  nothing reached the scope's `CoroutineExceptionHandler` (`escaped` empty), and the QUEUED row the manager wrote is
  still in the database. Reuse task 3's coach-configured fixture variant. Prove each test bites with a one-token
  mutation that puts the enqueue call back inside the reach of the existing `catch (Exception)` (or drops its
  shield), read the assertion it makes fail, then restore the implementation and commit only the tests.
  Done when: a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.inflight.InFlightLandScrubTest"`
  passes with every case in the class green — task 3's three included — and its `TEST-*.xml` names all five.

- [ ] 5. `InFlightRunTest`: `landRun` hands over the next sortie's id, and a throwing enqueue stays silent
  Covers plan step 4. File: `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt`. Fixture shape, as
  `runLandWithDistanceAndDurationStoresBoth` (:306) has it: `seedMission()` → `sortie0` (a PLANNED run) →
  `manager.launch` → `awaitRun` → `vm.landRun("5.2", "31") { … }`. The class's `viewModel(sortieId)` helper (:103)
  has no enqueue input, so build the view model in these tests with a recording `(Long) -> Unit`. Host and token
  before `onAppOpen()`; **no** run-plan toggle — landing a run makes a LIFT current, which is always left awaiting
  generation.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `landRunFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce` (exactly one id handed over, and reading it back
  gives kind `FLIGHT_PLAN`, status `QUEUED`, on sortie 1; the run is LANDED with 5.2 and 31 stored on it; `onDone`
  ran once), `landRunWithAFailingEnqueueStillLandsTheRunAndShowsNoMessage` (a throwing lambda: the run landed with
  its distance and duration, `runErrors` clear, `message` null, `escaped` empty).
  Done when: a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.inflight.InFlightRunTest"`
  passes with every case in the class green and its `TEST-*.xml` naming both.

- [ ] 6. The whole gate, run once, and any fallout it turns up
  Covers plan step 5 and the brief's Done-when. Run
  `.rho/runs/in-flight-enqueues-the-flight-plan-that-qdfr/verify.sh` detached through a copy under `app/build/`
  (`mkdir -p app/build` first; run the copy, never the real file, so editing it mid-run cannot give a phantom syntax
  error), logging to `app/build/` with an `EXIT=$?` sentinel on its own line and no pipe. It moves
  `app/build/test-results/testDebugUnitTest` aside with `mv` and runs
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` itself, then looks up the seven names
  and the whole-class checks it lists. One Gradle invocation in this worktree at a time; never `rm` the results dir (a
  stranded test executor holds `binary/output.bin`); never `gradlew --stop` (machine-wide). Read the log: the gate's
  own output, the report totals it prints, and its check-by-check list. A hang that writes no `TEST-*.xml` is the
  hang the brief warns about — the script re-runs the gate itself; if it hangs a third time, find the worker JVM and
  `jstack` it rather than re-running again.
  Done when: the log ends with `EXIT=0` and `BRIEF DONE`, with the seven new names ✓, the whole-suite gate ✓, every
  class check ✓ and the schema check ✓, and the reports show 0 failures and 0 errors. Fix and commit anything the
  gate turns up; if it turns up nothing, record the log path and the report totals in a commit message so the run's
  evidence is in the history.
