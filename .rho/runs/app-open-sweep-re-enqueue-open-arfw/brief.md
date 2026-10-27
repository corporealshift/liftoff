# App-open sweep: re-enqueue open generations, cancel stale ones

Make coach generations self-healing. Each time the app comes to the foreground, after the week rollover has run, look at every open (QUEUED or RUNNING) `Generation` row. Hand the ones still wanted to WorkManager, and cancel the ones whose target has moved on. This covers an enqueue lost to a process kill between a commit and its enqueue, and it cleans up after the week-ended rollover, which scrubs open sorties. Read DESIGN.md §5.3, §7.2 and §4.4. They call the harness 'nabu'; it is now 'rho'.

Facts you need (all on this branch):
- `MainActivity.onCreate` runs `container.missionManager.onAppOpen()` inside `repeatOnLifecycle(Lifecycle.State.STARTED)`. `onAppOpen()` scrubs a past week's open sorties with 'week ended', closes that Mission, and makes the current week's DRAFT if none exists.
- `Generation` (`com.liftoff.app.data`): `kind` (OUTLINE or FLIGHT_PLAN), `missionId`, `sortieId` (null for an outline), `status` (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED). `GenerationDao` has `getAll()`, `getOpenOutlineForMission(missionId)`, `getOpenFlightPlanForSortie(sortieId)` and `markCancelled(id, finishedAt)`. Add a query for every open row if you need one. A query is not a schema change.
- `MissionDao`/`SortieDao` read Missions (`status` DRAFT/ACTIVE/..., `weekStart`) and sorties (`state` PENDING/PLANNED/IN_FLIGHT/LANDED/SCRUBBED). `MissionManager` knows the current week (`currentWeekStart`) and which sortie is current.
- `AppContainer.generationEnqueuer().enqueue(id)` uses unique work `gen-<id>` with KEEP, so enqueuing a row that already has work does nothing. `AppContainer.generationCanceller().cancel(id)` cancels the work, marks the row CANCELLED only if it is open, and tries to stop its rho session, swallowing a daemon that can't be reached. Both build WorkManager on each call.
- `MainActivityTest`, `LiftoffShellTest` and `ShellNavTest` run the real container with no WorkManager set up.

Rules:
- An open OUTLINE row is wanted only while its Mission is the current week's DRAFT.
- An open FLIGHT_PLAN row is wanted only while its Mission is ACTIVE and in the current week, and its sortie still exists, is the Mission's current sortie, and is PENDING or PLANNED.
- Wanted rows are enqueued. Unwanted rows are cancelled through the canceller.
- The sweep runs after `onAppOpen()` returns, off the main thread, and never blocks the UI. It calls WorkManager only when there is a row to act on. Any failure (except cancellation) is swallowed per row, so one bad row doesn't stop the rest and the app never crashes.

Put the decision logic where it can be tested against Room with recording enqueue and cancel lambdas, without WorkManager.

No Room schema change.

Tests (Robolectric `@Config(sdk = [34])`, in-memory Room, recording lambdas, no WorkManager, every wait bounded, CI ubuntu-latest UTC):
- a QUEUED Flight Plan row for the current PENDING sortie is enqueued and not cancelled
- a QUEUED outline row for the current draft is enqueued
- after the week-ended rollover, the scrubbed sortie's open row is cancelled, and so is a past draft's open outline row
- a row for a sortie that is no longer current (scrubbed or landed) is cancelled
- SUCCEEDED, FAILED and CANCELLED rows are left alone
- with no rows, neither lambda is called

Update the ARCHITECTURE.md `work` package row to name the sweep.

Must not break: the shell tests and `MainActivityTest`, the worker, enqueue and cancel tests, outline and Flight Plan queueing, In-Flight and reopening, the Launchpad tests, the domain tests and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
