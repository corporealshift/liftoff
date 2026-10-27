# In-Flight enqueues the Flight Plan that land and scrub queued

When a sortie is landed or scrubbed from the In-Flight screen, the next sortie's Flight Plan generation must be handed to WorkManager, the same way the Launchpad already does it. Read DESIGN.md §5.3 and §7.2. They call the harness 'nabu'; it is now 'rho'.

Facts you need (all on this branch):
- `MissionManager.land(sortieId, runDistance, runMinutes): Long?` and `MissionManager.scrub(sortieId, reason): Long?` (`com.liftoff.app.data`) return the id of the QUEUED FLIGHT_PLAN `Generation` row they inserted for the sortie that became current, or null when they queued none. The row is committed when they return.
- `InFlightViewModel` (`app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`) calls `missionManager.land(sortieId)` in `land()`, `missionManager.land(sortieId, distance, minutes)` in `landRun(...)` and `missionManager.scrub(...)` in `scrub(...)`. Today it ignores the return values. Its constructor is `(db, settings, sortieId, scope, missionManager = MissionManager(db, settings))`.
- `InFlightScreen.kt` line ~50 builds the view model: `InFlightViewModel(container.database, container.settingsStore, sortieId, scope, container.missionManager)`.
- `AppContainer.generationEnqueuer().enqueue(generationId)` (`com.liftoff.app.work`) builds WorkManager on every call. It must never be called while composing, or the shell tests (`LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`), which compose the real container with no WorkManager, break. `LaunchpadScreen.kt`'s `internal fun launchpadViewModel(container, scope)` shows the pattern: pass a lambda that reaches the container only when it is called.

Change:
- Add an enqueue input to `InFlightViewModel` as a lambda parameter with a no-op default, placed after the existing defaulted `missionManager`. Every existing construction site in the tests must keep compiling unchanged. Do not edit them.
- After `land`, `landRun` and `scrub` return a non-null id, call the lambda once with it. Call it only after the manager call has returned, and not at all when the id is null.
- A failure inside the lambda (anything except cancellation) must not turn a sortie that did land or scrub into the 'Couldn't land/scrub this sortie.' message. Swallow it. The QUEUED row stays in Room as the source of truth, and a later run re-enqueues lost rows at app open.
- `InFlightScreen` passes a lambda that calls `container.generationEnqueuer().enqueue(id)` when invoked.

No Room schema change. Touch only `InFlightViewModel.kt`, `InFlightScreen.kt`, and the test files named below.

Tests (Robolectric `@Config(sdk = [34])`, in-memory Room, a recording lambda, no WorkManager, every wait bounded, CI ubuntu-latest UTC). Add them to the existing classes and reuse their harness. Set a daemon host and token in the `SettingsStore` before `onAppOpen()` so the next lift sortie gets a row:
- in `InFlightLandScrubTest`: landing a lift records exactly the id of the QUEUED FLIGHT_PLAN row for the next sortie, once; scrubbing does the same
- in `InFlightRunTest`: landing a run with distance and duration records the next sortie's row id once
- with no host or token set, landing records nothing

Must not break: every In-Flight test, the Launchpad tests, the worker tests, the shell tests, the domain tests and the build gate. Follow CLAUDE.md conventions. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
