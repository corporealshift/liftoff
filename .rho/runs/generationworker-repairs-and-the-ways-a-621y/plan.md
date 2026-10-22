# Plan

## Approach

Everything the brief asks for is a decision the worker already makes, at the points where it already makes it: the
`when` on the connection outcome, the `Result.retry()` returns in `storeFinishedTurn`, and the
`catch (DaemonException)` in `runGeneration`. So this is outcome handling added to `GenerationWorker`, not new
machinery. The repair prompt is another `runCoachTurn` on the session id and cursor the previous turn reported, wrapped
in `savingProgressTo` like the first so its session and cursor reach the row too, with the bumped attempt in its
client_id. "The same rho session" is the resume path the runner already has (invariant 6), not a new one. A repair
turn's end is judged exactly like the first turn's: blocked fails, paused, error and timed out retry, and a finished
turn is validated again. One private helper carries every permanent end: mark the row FAILED with its message, stop the
session when one exists, return `Result.failure()`. That way the permanent outcomes cannot drift apart. The existing
`finally { client.close() }` still covers every path, including the new ones. The retry path stays what it is today:
leave the row RUNNING with its session id and cursor and no error text.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: the mismatch arm of the connection `when`; the repair
  loop; blocked, `StoreOutcome.Refused` and a permanent `DaemonException` become FAILED with their own messages; the
  stop-on-every-permanent-failure rule; the two message builders (invalid output, protocol mismatch). A permanent
  refusal arrives as a thrown call with no `TurnOutcome`, so the session to stop is the last id `onSessionId` reported
  (or the row's stored one). If there is none, there is no stop.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`: new tests in the existing class, reusing its
  `@Before`/`@After`, `runWorker`, `frameFor`, `methodsSent` and `assertNothingReachedTheDaemon`.
- `ARCHITECTURE.md`: the M3 row (line 82) must stop reading "Not started" and say the worker's core flow is built,
  naming resilience, cancel, supersede and app wiring as still to come. The `com.liftoff.app.work` cell (line 36) must
  drop its now-false claim that "the repairs and failure outcomes are not written yet". Keep the negation away from
  `GenerationWorker.kt`, which is what a doc check greps for.
- No new file, no Room schema change: `attempt`, `error`, `nabuSessionId` and `lastEventId` are already columns, and
  `bumpAttempt`/`markFailed` already exist.

What each of these already holds is in `map.md`.

## Order

1. The permanent outcomes that need no loop (blocked, `ProtocolMismatch`, a permanent `DaemonException`,
   `StoreOutcome.Refused`), routed through one FAILED-and-stop helper, plus their tests. Build and
   `GenerationWorkerTest` green.
2. The repair loop: `bumpAttempt`, then another `runCoachTurn` inside `savingProgressTo` on the previous turn's session
   and cursor, with `CoachPromptBuilder.repairPrompt(errors)` and client_id `<generationId>-<attempt>`. It is capped at
   two by the row's attempt and ends in the still-invalid FAILED. Plus its tests. Build and `GenerationWorkerTest`
   green.
3. The retry-path assertions (paused, error: row still RUNNING with its session and cursor and no error, no stop
   frame) and the stop-frame counts. Test-only.
4. `ARCHITECTURE.md`'s M3 row and work-package cell.
5. The whole gate once, detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

All tests are Robolectric in `GenerationWorkerTest`, against `FakeDaemon` on the application's own container. Each
prompt gets one scripted turn (the fake repeats its last turn when the list runs out), and every wait is bounded by the
class's existing `turnWait`.

- invalid reply, then a repair, then a valid one: exactly two `rho.session.send_prompt` frames and one
  `rho.session.create`, client_ids ending `-0` then `-1`, row `attempt` 1, row SUCCEEDED, the reply stored.
- two failed repairs: three prompt frames and no fourth, `attempt` 2, row FAILED with an error that starts
  'invalid output' and carries the last turn's error lines, and nothing stored (the mission is still DRAFT with no
  sortie for a plan to sit on).
- `State("blocked")`: FAILED 'coach needed input', `Result.failure()`, one stop frame.
- `ProtocolMismatch` both ways, `refuseProtocolMismatch("2.0")` and `answerHelloWithProtocol("2.0")`: FAILED with
  'Liftoff speaks rho protocol 1.x, the daemon speaks 2.x', `Result.failure()`, and no `rho.session.create` frame.
  Also a hello refused with `PROTOCOL_MISMATCH` and a message that names no version (set through `refuseMethod`): FAILED
  with that message word for word.
- `State("paused")` and `State("error")` each: `Result.retry()`, row still RUNNING with its session id and cursor kept
  and `error` null, and no stop frame.
- a permanent refusal at a call (`refuseMethod["rho.session.send_prompt"]` set to a code in `RpcCodes.PERMANENT`):
  FAILED with the daemon's own message, and exactly one stop frame for the session that exists.
- a valid reply whose target has moved on: move it between inserting the row and running the worker, either by
  confirming the mission out from under an outline row or by scrubbing the sortie a plan row names, so the store refuses
  it. Expect FAILED with the refusal's own reason, nothing stored, one stop frame.
- one stop frame on success and on a permanent failure, and none on a retry. The existing success-path test already
  asserts the first half.

## Risks

- **The gate can hang for about 5 minutes on this machine** and writes no XML when it does. Run it detached through
  `gradlew.sh` with a log and an `EXIT=` sentinel (no pipe, or the sentinel reports the pipe's status). Re-run the same
  tree on a test-task timeout, and read the totals out of `TEST-*.xml` rather than trusting `BUILD SUCCESSFUL`.
- **A repair that reuses the previous client_id plays no turn at all.** The fake dedupes by `client_id` and answers
  with the earlier event id, so the test would sit until `turnWait` rather than fail loudly. Build the client_id from
  the attempt read *after* `bumpAttempt`.
- **A retried run whose row already has `attempt` above 0 sends the original prompt under `<id>-<attempt>`.** The
  daemon dedupes it against the repair already sent, so the resume continues the repair. Don't persist the repair
  prompt to make this work.
- **Three prompts × `TURN_LIMIT` (20 min) can outlast WorkManager's own stop.** That stop is only an early retry that
  resumes the same session and cursor, so nothing is lost. No test may depend on it, and the fake answers at once.
- **`rho.session.stop` appends a `state_change` to `completed` in the fake**, so a stop is also an event. Cursor
  assertions stay against the turn's own last event, as the existing tests do, and a stop must not be read as a turn
  end.
- **A stop call that throws must not change the outcome already decided.** Otherwise a permanent failure turns into a
  retry, and a dead generation keeps being asked.
- **The mismatch sentence duplicates the Test-connection row's wording**, whose helper is file-private in
  `ConnectionViewModel.kt`. Keep the "1.x / N.x" sentence identical so a user reads the same thing in both places. Do
  not copy that helper's no-version fallback, though: when `daemonVersion` is empty or has no numeric major, the
  brief wants the mismatch's own `message`. Do not widen the UI helper.
- **`app/schemas` must stay byte-identical**: no entity change, no migration. If a schema file appears or changes, the
  step went wrong.
- Existing tests must survive: the worker's own (the blocked and invalid-reply paths change under it), the turn
  runner's, coach, data and domain tests, and Test connection.

## Decisions

**A finished turn with no assistant text is an invalid reply, not a retry.** Added by review. Today an idle or
completed turn with no assistant message returns `Result.retry()`. The retry resends the same client_id, which the
daemon dedupes and plays nothing for, so every run waits out `TURN_LIMIT` and retries again, forever. The review hands
the empty text to the validator like any other reply, so it gets the repairs and then the 'invalid output' FAILED.
The alternative was to leave it retrying, but a coach that said nothing has answered, just badly.

**How the "invalid output" failure reads.** The brief says to mark the row failed with 'invalid output' followed by the
last error list, but not how the list is rendered. The plan writes `invalid output: ` then the validator's own error
lines joined with `; `, each kept word for word. The alternative is the repair prompt's own layout, one error per line,
as plan decision 11 does. That reads well in a prompt but makes the row's single `error` field multi-line. The row's
error is all a user has to go on for why a generation died (nothing reports it yet). These are the same lines the coach
was handed, so the row reads like the coach's own complaint rather than a second, differently-worded one.

**A daemon error that is not permanent retries rather than failing.** The brief names permanent refusals as failures
and connection outcomes as retries, but says nothing about a non-permanent `DaemonException` raised part-way through a
prompt. The plan retries; the alternative is to fail the row. A dropped connection mid-generation is exactly the case
the retry path exists for. Failing would throw away a generation the user is waiting on when the daemon is fine a
moment later.

**The two repairs are the generation's, not one worker run's.** The cap reads the row's `attempt`, which only the
repair path ever bumps, so a generation gets at most two repairs across all its runs. The alternative is to give each
`doWork()` a fresh two. That would let a generation that keeps pausing part-way through a repair ask the coach again
every time it is retried. Capping on the row is what "at most 2 repairs" for a generation means, and it is what stops a
coach that keeps answering badly from being asked forever.

**No custom retry backoff.** The brief says `Result.retry()` and stops there, so the plan sets no backoff criteria and
takes WorkManager's default exponential backoff. The alternative is a fixed short delay. Nothing enqueues this worker
yet (scheduling is a later milestone), and a fixed delay would keep poking a daemon that is paused or down.

**A permanently failed row keeps the session id and cursor it had.** Only status and error text change; the session
fields are left as they were. The alternative is to clear them so a FAILED row points at nothing. The row is terminal
and its session is stopped, so the fields are history, and which session a failed generation used is the only
remaining trace of it.
