# Tasks — regenerate the current sortie's Flight Plan (no UI)

Six tasks, in the order plan.md §Order sets them. Each ends in a commit. Test names below are spelled exactly as
`.rho/runs/regenerate-the-current-sortie-s-flight-1xa7/verify.sh` spells them, and each is written by exactly one
task. verify.sh names eight and all eight are written here; two more names are written that verify.sh does not check
— one plan.md §Files/§Testing requires, one that makes a guard the plan names provable.

| test | task |
| --- | --- |
| `regenerateOnAPendingSortieCancelsItsOpenRowAndEnqueuesTheNewQueuedRow` | 3 |
| `regenerateAfterAFailedFlightPlanQueuesAFreshRowAndLeavesTheFailedRowAlone` | 3 |
| `regenerateOnAPlannedSortieKeepsItsPlanAndStaysPlanned` | 3 |
| `regenerateWithoutAHostOrTokenIsRefusedAndWritesNothing` | 4 |
| `regenerateOnAnInFlightSortieIsRefusedAndWritesNothing` | 4 |
| `regenerateOnASortieThatIsNotCurrentIsRefusedAndWritesNothing` | 4 |
| `regenerateOnASortieOfAWeekThatIsNotCurrentIsRefusedAndWritesNothing` | 4 |
| `regenerateOnARunSortieFollowsTheGenerateRunPlansToggle` (plan-required, not verify-named) | 4 |
| `regenerateOnAMissionThatIsNotActiveIsRefusedAndWritesNothing` (not verify-named) | 4 |
| `regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState` | 5 |

Gate conventions (CLAUDE.md, and the run's map): `bash gradlew.sh …`, never bare gradle; scratch scripts and logs
under `app/build/`; a gate run is launched detached with the log and an `EXIT=$?` sentinel appended without a pipe;
move `app/build/test-results/testDebugUnitTest` aside with `mv`, never `rm`; never `gradlew --stop`; every class a
check names is run in ONE invocation; re-run the identical tree once if the build kills the test task at five
minutes without writing any `TEST-*.xml`. Baseline for the whole suite is 694 tests in 83 classes. Do not touch
verify.sh. plan.md §Order step 4 (mutation checks) is not its own task: tasks 3-5 each prove their own cases bite,
by a one-token edit to the **implementation** file (`MissionManager.kt` or `LaunchpadViewModel.kt`) kept as a byte
copy under `app/build/` for restoring. Tasks 3, 4 and 5 all add to the same new test class, so each reads what the
previous one left there before writing.

- [x] 1. The manager operation: ask for a fresh Flight Plan for a sortie
  Covers plan step 1. `app/src/main/java/com/liftoff/app/data/MissionManager.kt`: one new operation for a sortie id,
  guarding the way `refly` (306) guards a sortie — Mission ACTIVE, this sortie is `currentSortie`, sortie PENDING or
  PLANNED — plus the two guards nothing else in the file has: `mission.weekStart == currentWeekOf(clock, zone)`
  (the comparison `continueWithoutOutline` 191 makes) and `coachIsConfigured` read from `settingsStore.settings`
  before the transaction the way `confirm` does, and the run-toggle rule `prepareCurrent` applies (refuse a RUN
  current sortie while `generateRunPlans` is off; allow it when on). Every refusal is `IllegalStateException`,
  including the unconfigured one — unlike `confirm`/`retryOutline`, which return `ConfirmOutcome.CoachNotConfigured`.
  On success it inserts one QUEUED `FLIGHT_PLAN` row built exactly the way `advance` builds its row (406-414) and
  hands back that new id plus the ids of the sortie's other open `FLIGHT_PLAN` rows read **before** the insert
  (`getOpenFlightPlanForSortie` is newest first, so reading after would hand the row it just queued to the caller to
  cancel). Hand the new id out the way `continueWithoutOutline` does, only after the transaction commits. No entity
  change, no new DAO query, so `app/schemas` stays byte-identical. No test written by this task.
  Done when: `bash gradlew.sh :app:compileDebugKotlin` reports `BUILD SUCCESSFUL` — nothing calls the operation yet,
  so no existing file changes — and `git status --short` shows nothing under `app/schemas`. Write the guards and the
  compile command into the commit body.

- [ ] 2. The view-model action: enqueue the new row, cancel the rest
  Covers plan step 2. `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`: one new action
  returning `Job?`, reading `state.value` and returning null unless the derived state is `LaunchpadState.Pending` or
  `Planned` (the early return `refly` 211 uses), else `scope.launch { … }` that calls the manager operation with
  that state's `sortieId`, then enqueues the new id and cancels the others the way `continueWithoutOutline()` (111)
  enqueues-then-cancels. A refusal is caught with the file's own `catch (_: IllegalStateException)` re-sync, so the
  Launchpad state is left exactly as it was and nothing escapes into the scope. No message, no status change, no new
  state, no `AppContainer`/`MainActivity` change (its two lambdas are already passed at 39-40). No test written by
  this task.
  Done when: `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` is green, and one cold
  detached run of `:app:testDebugUnitTest --no-build-cache` naming `LaunchpadConfirmTest`, `LaunchpadReFlyTest`,
  `LaunchpadScrubTest`, `LaunchpadStatesTest`, `LaunchpadCoachStatusTest`, `LaunchpadDraftStatusTest`,
  `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `MainActivityTest` and `LiftoffShellTest` in one
  invocation reports `BUILD SUCCESSFUL` with 0 failures — the action exists but nothing calls it yet, so every
  existing test must still pass unchanged.

- [ ] 3. The new test class and the three behaviours that ask successfully
  Covers plan step 3's first half. New file
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadRegenerateTest.kt`: Robolectric `@Config(sdk = [34])`, no
  Compose, no WorkManager, no daemon, with the harness copied from `LaunchpadConfirmTest` — fixed
  `Clock.fixed(2026-01-12T00:00:00Z, UTC)` and `zone = { ZoneOffset.UTC }`, in-memory Room with
  `allowMainThreadQueries()`, a real `SettingsStore` over a DataStore file in `cacheDir` (host and token set **before**
  `onAppOpen()`), `CoroutineScope(Dispatchers.Unconfined + Job() + CoroutineExceptionHandler → escaped)`, recording
  `queuedIds`/`cancelledIds` where the cancel lambda also calls `generationDao.markCancelled(it, clock.millis())` so
  a read-back is real, a `joinAction` that fails on a null `Job`, `withTimeout(5_000)` on every wait, and
  `job.cancel()` before `db.close()` in `tearDown`. Fixture recipe for an ACTIVE week with a PENDING current lift
  sortie: `setDefaultPattern` → `onAppOpen()` → `continueWithoutOutline(mission.id)`, which already leaves one open
  `FLIGHT_PLAN` row on that sortie; stagger `createdAt` wherever a case seeds two rows so "older" is more than id
  order. Write these three tests:
  **`regenerateOnAPendingSortieCancelsItsOpenRowAndEnqueuesTheNewQueuedRow`** — exactly one id enqueued and exactly
  one cancelled, different ids; the enqueued id reads back QUEUED `FLIGHT_PLAN` on this Mission and this sortie; the
  cancelled id is the older row and reads back CANCELLED; no other row of this Mission changed.
  **`regenerateAfterAFailedFlightPlanQueuesAFreshRowAndLeavesTheFailedRowAlone`** — the newest row is FAILED with
  its own error and `finishedAt`, seeded straight through `generationDao` (no worker, no daemon); a new QUEUED
  `FLIGHT_PLAN` row appears and is the id enqueued; the FAILED row keeps `FAILED`, its error and its `finishedAt`,
  and is neither cancelled nor enqueued; the cancel lambda recorded nothing; the derived state is what it was.
  **`regenerateOnAPlannedSortieKeepsItsPlanAndStaysPlanned`** — on a PLANNED current **LIFT** sortie (move it with
  `storePlan` fed by `CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)`, as
  `MissionManagerPlanTest` does — not a run sortie's simple plan, which task 4's toggle refusal refuses): one id
  enqueued and a new QUEUED `FLIGHT_PLAN` row on that sortie; the sortie's `FlightPlan` row is the plan it had (same
  title, same exercises) and the sortie is still PLANNED; the derived state is still `Planned` carrying that same
  plan.
  Done when: one cold detached `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*LaunchpadRegenerateTest'`
  is `BUILD SUCCESSFUL` and `TEST-com.liftoff.app.ui.launchpad.LaunchpadRegenerateTest.xml` reports 3 tests, 0
  failures. Then prove the supersede case bites with each of plan §Testing's one-token mutations to the
  implementation in turn — in `LaunchpadViewModel.kt`, enqueue the older id instead of the new one, or skip the
  cancels; in `MissionManager.kt`, return the open rows read *after* the insert — each making that case red, then
  restoring that file from the copy under `app/build/` and re-running the class. Write the mutations, the gate
  command, EXIT and the XML totals into the commit body.

- [ ] 4. The refusals, each of which writes nothing
  Covers plan step 3's second half, in the class task 3 created (read what it left there before adding to it). Write
  these six tests. In every refusal case "writes nothing" means: the generation table holds exactly the rows it held
  before with the same statuses, no id reached the enqueue or cancel lambda, the Mission and its sorties are unchanged,
  and nothing escaped into the scope's exception handler:
  **`regenerateWithoutAHostOrTokenIsRefusedAndWritesNothing`** — through the view model on a card the action is
  available on: the action returns a `Job`, the `Job` finishes, the manager's `IllegalStateException` was caught
  inside it rather than escaping, and the derived state is exactly what it was.
  **`regenerateOnAnInFlightSortieIsRefusedAndWritesNothing`** — manager-direct (the view model returns null on an
  InFlight card): throws `IllegalStateException`, the sortie is still IN_FLIGHT with its plan and exercises as they
  were.
  **`regenerateOnASortieThatIsNotCurrentIsRefusedAndWritesNothing`** — manager-direct with the id of a PENDING
  sortie of the current ACTIVE Mission that is not `currentSortie`: throws and writes nothing, for that sortie or
  for the current one.
  **`regenerateOnASortieOfAWeekThatIsNotCurrentIsRefusedAndWritesNothing`** — manager-direct on an ACTIVE Mission
  with a past `weekStart` and a PENDING current sortie, seeded the way `MissionManagerReFlyTest` seeds its ACTIVE
  mission, and **without** calling `onAppOpen()` first, because the sweep would close it.
  **`regenerateOnARunSortieFollowsTheGenerateRunPlansToggle`** — plan §Files and §Testing require it (verify.sh does
  not name it): seed a RUN current sortie with the toggle left off, which leaves it PLANNED with the simple
  `SIMPLE_RUN` plan `advance` writes; refused with `IllegalStateException` while `generateRunPlans` is off, then
  accepted once `SettingsStore.setGenerateRunPlans(true)` — queueing one new QUEUED `FLIGHT_PLAN` row, enqueued, while
  the `SIMPLE_RUN` plan and the PLANNED sortie are still what they were. `MissionManager` re-reads settings per call, so
  one test can flip the toggle between the two halves.
  **`regenerateOnAMissionThatIsNotActiveIsRefusedAndWritesNothing`** — not named by verify.sh and not in plan §Testing's
  refusal list, but written here because plan §Files names the ACTIVE check as a guard and §Testing asks that dropping
  each guard make its refusal case fail: manager-direct on a **DRAFT** Mission with a PENDING current sortie of the
  current week, refused and writing nothing. Without this case the ACTIVE check is the one guard no test refuses.
  Done when: one cold detached `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*LaunchpadRegenerateTest'`
  is `BUILD SUCCESSFUL` with that class's `TEST-*.xml` reporting 9 tests, 0 failures, and the six new names present.
  Then drop each guard in `MissionManager.kt` in turn and confirm the case it is the only one refusing goes red — the
  ACTIVE check → the not-ACTIVE case; the current-sortie check → the not-current case; the PENDING/PLANNED check → the
  IN_FLIGHT case; the coach-configured check → the no-host-or-token case; the `weekStart` check → the stale-week case;
  the run-toggle check → the toggle case — restoring the file from the copy under `app/build/` and re-running the class
  after each. Write the mutations, the gate command, EXIT and the XML totals into the commit body.

- [ ] 5. Where the action is available
  Covers plan step 3's last case, in the class tasks 3-4 built. Write
  **`regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState`**: the action returns a `Job` on a PENDING
  current sortie and on a PLANNED one, and null on a Draft week, on an IN_FLIGHT current sortie, on a CLOSED Mission
  and on an ACTIVE Mission with no current sortie (both derive `Closed`), and before the week has settled
  (`LaunchpadState.Loading`). The variants are exactly `Loading`, `Draft`, `Planned`, `Pending`, `InFlight`,
  `Closed`; the null half is what stops the other cases passing on an action that did nothing, so assert the nulls as
  firmly as the `Job`s and join every non-null `Job` before reading anything back.
  Done when: one cold detached `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*LaunchpadRegenerateTest'`
  is `BUILD SUCCESSFUL` with that class's `TEST-*.xml` reporting 10 tests, 0 failures, and that name present. Then
  prove it bites by a one-token edit to `LaunchpadViewModel.kt` that lets one of the null states through — watching
  this case go red — restoring the file from the copy under `app/build/`, and re-running the class. Write the mutation,
  the gate command, EXIT and the XML totals into the commit body.

- [ ] 6. Record it in ARCHITECTURE.md, then run the whole gate
  Covers plan step 5. `ARCHITECTURE.md` line 82, M3's row: move **Regenerate** out of the closing "In-Flight's
  enqueue, Regenerate and the Launchpad's display of the current sortie's status come next" list and record in the
  row what the operation does — the manager queues one QUEUED `FLIGHT_PLAN` generation for the current PENDING or
  PLANNED sortie of the current week's ACTIVE Mission and hands the sortie's other open Flight Plan generations to
  the canceller, leaving the plan the sortie already has until the new result replaces it; the Launchpad action is
  offered on the Pending and Planned cards. Leave the rest of that sentence for Kyle's review in the pull request;
  no other doc is touched and no doc row is grepped by verify.sh.
  Done when: one cold detached whole gate — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
  --no-build-cache` — with `BUILD SUCCESSFUL`, `:app:testDebugUnitTest` executed (not FROM-CACHE/UP-TO-DATE), 0
  failures, and all eight verify.sh-named names read **pass** in the reports under
  `app/build/test-results/testDebugUnitTest` (the toggle case too); the 33 classes verify.sh §3 names are all
  present in those reports; `git status --short` shows no change under `app/schemas`. Write the gate command, EXIT,
  the report totals and the class count into the commit body.
