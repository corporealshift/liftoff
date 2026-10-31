GenerationWorker: a coach turn that ends in `error` retries forever

In `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` (`storeFinishedTurn`, the `if (turn.end != TurnEnd.Idle && turn.end != TurnEnd.Completed)` branch), `TurnEnd.Error` is handed back with `handSessionBack` (resume) and `Result.retry()`.

In rho, `error` is terminal. `Manager.Resume` only accepts a paused session (`daemon/agent/manager.go`), so the resume is refused and the refusal is swallowed. On the next attempt `send_prompt` is deduped by client_id, the stored cursor is already past the error event, and the turn runner waits for a turn end that never comes. The row stays RUNNING, and the Launchpad says "Coach is planning…" forever.

**Fix:** treat `TurnEnd.Error` as permanent by calling `failGeneration` with the session's error text. Only `Paused` (and a dropped connection) should be handed back and retried.

**Test:** in GenerationWorkerTest, have the fake daemon end the turn in `error` and assert that the row ends FAILED and isn't retried.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#53: https://github.com/corporealshift/liftoff/issues/53)
