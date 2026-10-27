# The worker leaves a cancelled generation cancelled

A coach generation that was cancelled while its turn was running must stay CANCELLED. Today `GenerationWorker` can overwrite it with FAILED. Fix only that.

Facts you need (all on this branch, `com.liftoff.app.work.GenerationWorker`):
- `failGeneration` calls `GenerationDao.markFailed(id, error, finishedAt)` with no status check. It is reached when a turn ends blocked, when the repair cap is hit, and on a permanent `DaemonException`.
- The step that marks the row RUNNING (`GenerationDao.markRunning(id)`) doesn't check the status either, so a row cancelled before the worker starts can be flipped back to RUNNING.
- The store path is already guarded: `storeFinishedTurn` checks inside one transaction that the generation is still current, marks a stale row CANCELLED, stops the session and returns `Result.success()`. Copy that behaviour.
- `GenerationWorkerTest` has a fake rho daemon (`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`) and a cancelled-mid-turn case to copy.

Change:
- The failure path leaves an already-CANCELLED row alone: no FAILED status and no error text. It still stops the rho session if one was started, and the work ends with `Result.success()`.
- The mark-running step does nothing to a row that is no longer QUEUED or RUNNING, and the work ends quietly without opening a turn.
- Doing the status check and the write in one statement or one transaction is fine. A guarded `@Query` is not a schema change.

No Room schema change.

Tests, in `GenerationWorkerTest`, using its existing harness and fake daemon, every wait bounded, CI ubuntu-latest UTC:
- a row cancelled during a turn that then ends blocked stays CANCELLED, with its error unchanged
- a row cancelled during a turn that then exhausts its repairs stays CANCELLED
- a row cancelled before the worker starts stays CANCELLED, and the fake daemon sees no prompt

Must not break: the existing worker, enqueue, cancel and turn-runner tests, Flight Plan and outline queueing, the Launchpad's draft status and the build gate. Follow CLAUDE.md conventions. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
