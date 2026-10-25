# The draft's coach status, derived and live from Room (no UI)

Give the Launchpad draft a live coach status, as state only. A later run draws it. Read DESIGN.md §4.2 and §9 ('Launchpad').

Build on (all on this branch already):
- `LaunchpadState.Draft(weekStart, pattern, missionId)` and `LaunchpadStates` (`com.liftoff.app.ui.launchpad/LaunchpadState.kt`). `LaunchpadStates.observe()` maps `manager.currentWeekStart` to `missionDao().observeWeek(ws)`, combined with `flightPlanDao().observeChanges()`, and derives one state per emission.
- `GenerationDao.observeNewestNonCancelledOutlineForMission(missionId): Flow<Generation?>`.
- `Generation` (`com.liftoff.app.data`): `status` (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED), `error: String?`, and `isCoachOffline`. `isCoachOffline` is true while `error == Generation.COACH_OFFLINE` and the row is QUEUED or RUNNING.
- `com.liftoff.app.domain.coachIsConfigured(host, token)` and `SettingsStore.settings` (`daemonHost`, `daemonToken`).
- `LaunchpadViewModel`'s `confirm()`, `continueWithoutOutline()` and `retry()`.

Add:
- A plain-Kotlin derivation in `com.liftoff.app.domain`, with no Android or Room types in its signature. It takes the newest non-cancelled outline row's status, error and offline flag (or none), plus whether the coach is configured. It returns one of:
  - **editable**: no row, or the newest row SUCCEEDED. The pattern can be edited and Confirm is offered.
  - **planning**: the row is QUEUED or RUNNING and not offline.
  - **offline**: the row is open and marked offline.
  - **failed**: carries the reason written on the row, word for word, and offers Retry.
  - **not configured**: the host or token is blank and no row is open.
  Planning, offline, failed and not configured all offer 'Continue without outline'.
- `Draft` carries the derived status. `LaunchpadStates` joins the outline Flow and the settings only on the DRAFT branch, keeps today's behaviour for every other state, and doesn't re-emit for unrelated changes.
- Update `LaunchpadScreen` only as far as it needs to compile. Don't change what it draws.

No Room schema change.

Tests:
- Plain JUnit (no Robolectric) for each derivation case, including a FAILED row's reason passed through word for word.
- Robolectric (`@Config(sdk = [34])`, in-memory Room, every wait bounded, CI ubuntu-latest UTC; no WorkManager). With one subscription to `LaunchpadStates.observe()` for a current-week draft: writing a QUEUED OUTLINE row emits planning, `GenerationDao.setCoachOfflineMark(id, Generation.COACH_OFFLINE)` emits offline, and `markFailed(id, "reason", t)` emits failed with that reason, all without re-subscribing.

Must not break: the Launchpad tests (draft controls, guards, scrub, Re-fly, states, Confirm), the worker tests, In-Flight, Landed, Mission Control, the domain tests, and the build gate. Follow CLAUDE.md conventions. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
