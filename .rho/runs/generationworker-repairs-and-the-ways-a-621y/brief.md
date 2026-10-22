# GenerationWorker: repairs and the ways a generation fails

Finish the outcome handling in `GenerationWorker` (`com.liftoff.app.work`). It already runs one turn through the turn runner and stores a valid reply. Its Robolectric tests run it against `FakeDaemon` using the application's own container. Reuse that test class's setup; don't build a new one. Read only the worker, its test, the turn runner and `CoachPromptBuilder.repairPrompt`. Everything else you need is below.

Add:
- **Invalid reply** (`CoachReplyResult.Invalid(errors)`): call `generationDao().bumpAttempt(id)`, then send `CoachPromptBuilder.repairPrompt(errors)` in the same rho session with client_id `<generationId>-<attempt>`, using the bumped attempt. Allow at most 2 repairs. If the reply is still invalid, call `markFailed` with 'invalid output' followed by the last error list, return `Result.failure()`, and store nothing.
- **blocked**: FAILED with 'coach needed input', and `Result.failure()`.
- **Protocol major-version mismatch** (`ConnectionOutcome.ProtocolMismatch`): FAILED with 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x', where N is the daemon's major. If the daemon named no version, use the refusal's own message. Return `Result.failure()`; no session is created.
- **A permanent refusal** from a call (`DaemonException.isPermanent`): FAILED with the daemon's message.
- **A valid reply whose target has moved on** (`StoreOutcome.Refused(reason)`): FAILED with the reason, because retrying can never succeed.
- **paused, error, timed out, a dropped connection, or any non-connected outcome other than a mismatch**: `Result.retry()`. Leave the row RUNNING with its session id and cursor, and no error text.
- Stop the rho session (`rho.session.stop`) on SUCCEEDED and on every permanent failure after a session exists. Never stop it on a retry. Close the client on every path.

Tests, Robolectric against the fake, with invalid replies from the fixtures in `CoachFixtures` (`BAD_JSON`, `SCHEMA_VIOLATION`, `PROSE_AROUND_JSON`). Script one turn per prompt; the fake repeats the last turn when it runs out.
- invalid reply, then a repair, then success: two prompt frames in one session, client_ids ending `-0` then `-1`, attempt 1, row SUCCEEDED
- two failed repairs: three prompt frames and no fourth, attempt 2, row FAILED with 'invalid output' and the last errors, nothing stored
- blocked becomes FAILED 'coach needed input'
- a mismatch, both through `refuseProtocolMismatch("2.0")` and `answerHelloWithProtocol("2.0")`: FAILED with the message above, and no create frame
- paused and error each return retry with the row RUNNING and no stop frame
- one stop frame on success and on a permanent failure

Every wait has a timeout, and nothing depends on timing (CI is ubuntu-latest, UTC). Don't change the Room schema.

Update ARCHITECTURE.md's M3 row to say the worker's core flow is built, with resilience, cancel, supersede and app wiring still to come.

Must not break: the worker's and the turn runner's existing tests, the coach, data and domain tests, Test connection, and the build gate. Follow CLAUDE.md conventions. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: these tests pass, the M3 row is updated, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
