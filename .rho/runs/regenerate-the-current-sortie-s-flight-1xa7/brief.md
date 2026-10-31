# Regenerate the current sortie's Flight Plan (no UI)

Let the owner ask the coach for a fresh Flight Plan for the current sortie, as a manager operation and a view-model action. A later run adds the button. Read DESIGN.md §5.3, §5.4 and §7.2. They call the harness 'nabu'; it is now 'rho'.

Facts you need (all on this branch):
- `MissionManager` (`com.liftoff.app.data`). Its private `advance` inserts a QUEUED FLIGHT_PLAN `Generation(kind = GenerationKind.FLIGHT_PLAN, missionId, sortieId, status = GenerationStatus.QUEUED, createdAt = clock.millis())` when a sortie becomes current and needs a plan. Copy how it does that. `storePlan(sortieId, valid)` accepts a result for the current sortie while it is PENDING or PLANNED and overwrites the existing plan.
- `GenerationDao.getOpenFlightPlanForSortie(sortieId)` returns its QUEUED and RUNNING FLIGHT_PLAN rows. `com.liftoff.app.domain.coachIsConfigured(host, token)`; `SettingsStore.settings`.
- The worker's store check treats only the newest non-cancelled row for a sortie as current, so an older row's result is dropped and that row is marked CANCELLED.
- `LaunchpadViewModel(db, manager, scope, queueGeneration: (Long) -> Unit, cancelGeneration: suspend (Long) -> Unit)` (`com.liftoff.app.ui.launchpad`). Its actions return `Job?` and call the lambdas only after the manager call has returned. See `continueWithoutOutline()` and `refly()`. Every action calls `manager.onAppOpen()` first.
- `LaunchpadState.Pending(..., sortieId, ...)` and `LaunchpadState.Planned(..., sortieId, ...)`.

Add:
- A `MissionManager` regenerate operation for a sortie. It refuses with `IllegalStateException` unless the sortie is the current sortie of the current week's ACTIVE Mission, is PENDING or PLANNED, and the coach is configured. On success it inserts one new QUEUED FLIGHT_PLAN row and returns that id plus the ids of the sortie's other open FLIGHT_PLAN rows. It never touches the sortie's existing plan, so a PLANNED sortie stays PLANNED and launchable until the new result replaces it.
- `LaunchpadViewModel.regenerate(): Job?`, available on Pending and Planned (null otherwise). After the manager call it enqueues the new id and cancels the others. A refusal is caught and leaves the state unchanged. It also serves as Retry after a failed Flight Plan.

No Room schema change. No UI change.

Tests (Robolectric `@Config(sdk = [34])`, in-memory Room, recording lambdas, no WorkManager, every wait bounded, CI ubuntu-latest UTC). Copy the harness from `LaunchpadConfirmTest` (a host and token set before `onAppOpen()`):
- regenerate on a PENDING sortie with an open row returns that row for cancelling and a new QUEUED row; the view model cancels the one and enqueues the other
- regenerate on a PENDING sortie whose last row FAILED queues a new row and leaves the FAILED row's status and error alone
- regenerate on a PLANNED sortie keeps its plan and its PLANNED state
- regenerate with no host or token, on an IN_FLIGHT sortie, or on a sortie that isn't current is refused and writes nothing

Must not break: the Launchpad tests, Re-fly, scrub, Flight Plan and outline queueing, the worker tests, In-Flight, the shell tests, the domain tests and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
