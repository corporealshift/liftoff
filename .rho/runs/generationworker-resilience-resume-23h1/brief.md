# GenerationWorker resilience: resume, reconnect and offline

Make `GenerationWorker` (`com.liftoff.app.work`) pick up where it left off, and say when the coach is offline. It already runs a turn through the turn runner, repairs invalid replies, maps every outcome to SUCCEEDED, FAILED or retry, and stops the rho session at the end. Its Robolectric tests run it against `FakeDaemon` using the application's container. Reuse that setup. Read the worker, its test, the turn runner and `FakeDaemon`. You don't need DESIGN.md or the external rho spec: the rules are below.

Add:
- **paused**: send `rho.session.resume` `{session_id}`, then return `Result.retry()`.
- **Resume after a drop or a killed worker**: a row that already has `nabuSessionId` and `lastEventId` reuses that session, never creates another, and catches up with `events_after` from the stored cursor. If the cursor is past our own prompt, still find the turn's reply. A re-sent prompt reuses the same client_id (`<generationId>-<attempt>`, with the attempt the row holds), so the daemon delivers it once.
- **Unreachable daemon** (`ConnectionOutcome.Unreachable`, or a connect that fails): return a retry, and mark the row offline by setting `error` to exactly 'Mission Control offline' while it stays QUEUED or RUNNING. Clear the mark (back to null) as soon as a connection succeeds. Add a small public helper, e.g. `Generation.isCoachOffline`, that a later Launchpad run can call instead of comparing strings. Once WorkManager's `runAttemptCount` shows the 8th attempt has also failed to connect, mark the row FAILED with 'coach unreachable' and return failure.

No Room schema change.

Tests against the fake. Every wait has a timeout and nothing depends on timing (CI is ubuntu-latest, UTC).
- paused: a `rho.session.resume` frame, then retry
- a row with a session id and cursor, where the fake's log already holds our prompt and the reply: no create frame, the reply is stored, and `events_after` starts from the stored cursor
- a retried send reuses the same client_id, and the fake records one message
- an unreachable daemon (a fake that is stopped, or a closed port) marks the row offline and retries; a later successful run clears the mark
- an unreachable daemon on the 8th attempt, built with `TestListenableWorkerBuilder.setRunAttemptCount`, becomes FAILED 'coach unreachable'

Must not break: the worker's existing tests, the enqueue helper's test, the coach, data and domain tests, and the build gate. Follow CLAUDE.md conventions. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
