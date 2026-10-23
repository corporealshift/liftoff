# Cancel and supersede coach generations

Make sure a cancelled or outdated coach generation can never write a result. Build on `GenerationWorker` and its enqueue helper in `com.liftoff.app.work` (unique work `gen-<generationId>`), the stop helper next to the turn runner, `GenerationDao` (`markCancelled(id, finishedAt)`, `getNewestNonCancelledForSortie(sortieId)`, `getNewestNonCancelledOutlineForMission(missionId)`), and `AppContainer`. Reuse the worker test's setup: the application's container and `FakeDaemon`.

Add:
- **A cancel helper**, exposed from `AppContainer`. For a generation id it cancels the unique work, marks the row CANCELLED, and stops its rho session if the row has one and the daemon can be reached. If the daemon is unreachable, cancelling still succeeds locally. It does nothing to a row that is already SUCCEEDED, FAILED or CANCELLED.
- **The worker stores only a current result.** Just before storing, the row must not be CANCELLED, and it must be the newest non-cancelled generation for its sortie (Flight Plan) or its Mission's outline (outline). Otherwise store nothing, leave the row's status alone (or mark it CANCELLED if it isn't already), stop its rho session, and return success. Do the check and the store in one Room transaction, so a cancel can't slip in between.

No Room schema change.

Tests (Robolectric; every wait has a timeout; CI is ubuntu-latest, UTC):
- cancel on a row with a session: the row CANCELLED, and one `rho.session.stop` frame
- cancel with the daemon unreachable: the row still CANCELLED
- a worker whose row was cancelled before the turn ended stores nothing
- a worker whose generation was superseded by a newer row for the same sortie stores nothing, and the newer row's result is still stored when it runs
- the same for an outline superseded by a newer outline for the same Mission

Update ARCHITECTURE.md's M3 row to say the worker, including resilience, cancel and supersede, is built, and that app wiring comes next.

Must not break: the worker's and the enqueue helper's tests, the coach, data and domain tests, and the build gate. Follow CLAUDE.md conventions. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: these tests pass, the M3 row is updated, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
