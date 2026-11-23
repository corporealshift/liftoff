GenerationWorker: a repair prompt to a `completed` session is refused, losing both repairs

In `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`, `storeFinishedTurn` accepts `TurnEnd.Completed` as a normal end. On an invalid reply it then sends the repair prompt to the same session. rho refuses `send_prompt` on a `completed` session with INVALID_TRANSITION ("session has ended (completed); start a new session instead"). That code is in `RpcCodes.PERMANENT`, so the generation fails with an RPC error instead of the validator's errors.

When I ran the app, the coach sessions ended `idle` (the case that works), so this only bites if a session ever ends `completed`. Still worth closing.

**Fix:** when the turn ended `Completed`, run the repair on a new session (null session id). Its prompt is the original prompt, the invalid reply and the validator errors. Keep same-session repair for `Idle`.

**Test:** a GenerationWorkerTest case where the fake daemon ends the first turn `completed` with an invalid reply, asserting that a new session is created and the repair succeeds.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#58: https://github.com/corporealshift/liftoff/issues/58)
