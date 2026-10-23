# Plan

## Approach

The row already carries `nabuSessionId` and `lastEventId`, and the worker already hands both to the turn runner. So resuming is not a new path. The runner's catch-up read has to find a reply and an end that the stored cursor has already walked past (today it waits out the 20-minute turn limit or judges an empty reply instead), and a turn that ends paused gets one `rho.session.resume` call. The offline behaviour is a change of arm. Today the connection check's `Unreachable` outcome and a `client.connect()` that throws are both silent retries. They become the place where the row is marked offline, and WorkManager's own `runAttemptCount`, read straight off the worker, decides when "offline" becomes "coach unreachable". `FakeDaemon` already dedupes a prompt by `client_id` and already records every frame, so the new cases run against the existing Robolectric harness with three small additions to the fake. No Room schema change: the offline mark is a `@Query` that writes `error` on its own.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`:
  - **Paused.** A paused end sends `rho.session.resume` for the session it stopped in, then returns `retry` and leaves the session open. A resume the daemon refuses, permanently or not, is swallowed the way `giveUpSession` swallows a refused stop, so it can never reach the catch that fails the generation.
  - **Offline.** The `Unreachable` outcome and a `client.connect()` that throws (a `DaemonException` with no code) become their own path, so a failed connect is not mistaken for a turn that dropped mid-way. On that path, if `runAttemptCount >= 7` (the 8th attempt or later, counted from 0), the row is marked FAILED `coach unreachable` with `failure`. Otherwise the offline mark is stamped and the worker returns `retry`.
  - **Clearing the mark.** It is cleared as soon as the connection check answers `Connected`.
  - **Not offline.** `NotConfigured` and `AuthenticationRejected` keep today's plain retry, with no mark.
- `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`:
  - **Second read.** When the stored cursor is past the prompt's own id and the read from the cursor did not yield both an end and an assistant reply after the prompt, it reads again from the prompt's id. The reply and the end are then decided as if the cursor had never passed them: the first end after the prompt, and the last assistant message before it.
  - **Ends already acted on.** In that second read, a `paused` or `error` end at or before the stored cursor was already acted on by the attempt that read it. It is skipped, and the turn carries on to a later end or the pushes.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`: one query that sets or clears `error` only on a QUEUED or RUNNING row, leaving status, session id, cursor and attempt alone.
- `app/src/main/java/com/liftoff/app/data/Generation.kt`: `isCoachOffline` and the `Mission Control offline` constant it compares against. The worker writes the same constant.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`:
  - a `rho.session.resume` handler;
  - a user message seeded through `logEvent` registers its `client_id` for dedup, the way a prompt sent through `send_prompt` already does.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`:
  - the new cases, reusing `runWorker`, `insertOutlineGeneration` / `insertFlightPlanGeneration` and the existing assertions;
  - `runWorker` gains an optional run-attempt count passed to `setRunAttemptCount`;
  - a helper points the settings at a closed port (taken from a socket opened and closed in the test), and back at the live fake;
  - the paused case gains the resume-frame assertion.
- `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt`: the new query and `isCoachOffline`.
- `ARCHITECTURE.md`: the `com.liftoff.app.work` row (line 36) and the M3 row (line 82) drop the claim that resilience beyond the current outcomes is still to come.

## Order

1. `Generation.isCoachOffline`, its constant, and the DAO query, with their cases in `GenerationDaoTest`. Nothing reads them yet, so the build stays green.
2. `FakeDaemon`: the `rho.session.resume` handler and `client_id` registration for a seeded user message. Its own tests (`CoachTurnRunnerTest`, `rho` client tests) still pass unchanged.
3. The runner's second catch-up read and its skip of ends already acted on, proved by the resume cases in Testing.
4. Paused: `rho.session.resume` then `retry`, added to the existing paused case, plus the refused-resume case.
5. The offline mark and its clearing on one row: closed port, then the live fake.
6. The 8th attempt: `runAttemptCount` 7 turns the offline row into FAILED `coach unreachable` and `failure`.
7. The `ARCHITECTURE.md` rows, then the whole gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, run detached and re-run if it hangs.

## Testing

All worker cases run in `GenerationWorkerTest` against `FakeDaemon` through `runWorker`. Each wait is bounded by `turnWait`, and nothing depends on timing.

- **paused:**
  - A `rho.session.resume` frame names the session the turn stopped in, then `Result.Retry`.
  - The row stays RUNNING with its session id and cursor, `error` null, and there is no `rho.session.stop`.
  - With `refuseMethod["rho.session.resume"]` set to a permanent code, the result is still `Retry` and the row is not FAILED.
- **resume from a stored session and cursor:**
  - The row is seeded with `nabuSessionId` and a `lastEventId` before the prompt.
  - The fake's log already holds our prompt (client_id `<id>-0`), the reply and an idle end.
  - Expect no `rho.session.create` frame, the first `events_after` frame carrying the stored cursor, the reply stored on the mission, and no second user message in the log.
- **cursor past our own prompt:** the same row in three variants, each SUCCEEDED with the reply stored:
  - the cursor at the reply, with the end after it;
  - the cursor at the end;
  - a log of prompt, `paused`, a running state, reply, idle, with the cursor at the reply, so the paused end the cursor passed is skipped.
- **a re-sent prompt is delivered once:** two runs of the same row (the first ending paused or error) send the same `<generationId>-<attempt>` client_id, and the fake's log holds exactly one user message for it.
- **offline, then back:**
  - With the settings pointed at a closed port: `Result.Retry`, the row RUNNING and not FAILED, `error` exactly `Mission Control offline`, `isCoachOffline` true, and `finishedAt` null.
  - With the settings pointed back at the live fake, the same row runs to SUCCEEDED with `error` null.
- **the 8th attempt:** the closed-port setup with `setRunAttemptCount(7)` gives `Result.Failure`, the row FAILED with `coach unreachable`, `finishedAt` stamped and `isCoachOffline` false.
- **`isCoachOffline`**, in `GenerationDaoTest`:
  - true for a QUEUED or RUNNING row carrying exactly that mark;
  - false for a FAILED row and for a running row with any other error text;
  - the query leaves a FAILED row's error alone.
- **Regression:** the 18 existing `GenerationWorkerTest` cases, `GenerationEnqueuerTest`, `CoachTurnRunnerTest`, the coach, data and domain tests, and the whole gate stay green.

## Risks

- **`frameFor` asserts exactly one frame.** A resumed turn can send two `events_after` frames, and a case that runs the worker twice sends two `send_prompt` frames. Those cases assert on `daemon.framesFor(...)` or `daemon.log`. The helper is not loosened, because the existing cases depend on it meaning "exactly one".
- **A stopped `FakeDaemon` cannot be restarted.** MockWebServer does not start again after shutdown, so "offline, then back" uses a closed port rather than stopping the shared fake.
- **The attempt count is 0-based.** WorkManager's first run has `runAttemptCount == 0`, so the 8th is 7. If the case leaves the row RUNNING, check the threshold, not the assertion.
- **A drop must not be a race.** Unreachability is set up before `runWorker`, never by closing a socket mid-turn. A closed port is refused at once, well inside `turnWait`.
- **The re-read must not replay an end already handled.** Without the skip of `paused`/`error` ends at or before the cursor, a paused row would find its old paused end on every retry and loop through resume and retry instead of waiting for the turn.
- **The second read must not move the cursor backwards.** The runner only moves the cursor forward and skips ids it has seen. The re-read keeps both, or a resumed row would re-store events it already saved.
- **The offline mark must never overwrite a decided failure.** The query only touches QUEUED or RUNNING rows, and `markFailed` keeps its own reason.
- **`FakeDaemon` changes are shared.** No existing case seeds a message carrying a `client_id`, so none should change, but the runner and rho client test classes are run after that step rather than assumed.
- **The gate can hang for about 5 minutes on this machine.** Run it detached, and re-run the same tree before treating a hang as a failure.

## Decisions
**Does a resumed run send the prompt again, or only read what already happened?** It sends, under the client_id the row already holds. The daemon dedupes by client_id and answers with the original prompt's id, so a prompt that already landed plays no second turn. It then reads from the stored cursor, and reads once more from the prompt's own id when that first read finds no end and the cursor has already walked past the prompt. The alternative is to read before sending anything, which costs an extra read from the start of the log just to find the prompt, for a result the user cannot tell apart. Either way the coach gets one prompt and one reply per generation.
Changed by review: the plan chose to re-read from the prompt only when the first read finds no end. The review chose to re-read when the cursor is past the prompt and the first read lacks either the end or the reply, and to skip in that re-read any `paused` or `error` end the stored cursor already passed. The reason: a cursor that stopped between the reply and the end would otherwise find the end with no reply, judge an empty reply, and spend a repair on a coach that answered correctly. And a re-read that counted an old paused end would send a paused generation round resume and retry forever instead of waiting for its turn to finish.

**While a generation is paused, what does the row look like?** The row keeps the status it already had: QUEUED or RUNNING, with `error` null because this attempt reached the daemon. The worker returns `retry`, so the generation still reads as in progress and WorkManager comes back to it. The alternative is to write a "paused" status or an error message so the user can see the wait. The brief forbids a schema change, so there is no paused status to write. A paused session is a temporary state the worker expects to clear on the next attempt, and showing it as a problem would be a message the user cannot act on.

**Can the offline mark overwrite an error that is already a real failure?** No. `Mission Control offline` is written only when this attempt failed to connect. A row that already carries a decided failure (a reply that could not be repaired, a refused turn) keeps that message. The alternative, always stamping the offline text, would make a generation that genuinely failed read as "the coach is offline". That is wrong, and misleading for the Launchpad run that reads `isCoachOffline`.

**What does `Generation.isCoachOffline` mean?** It is true only for the offline mark: the row is still QUEUED or RUNNING and `error` is exactly `Mission Control offline`. A row that has failed with `coach unreachable` or any other message is not offline. The alternative is "any error that came from a connection", which would need the failure text too. The point of the helper is that Launchpad can say *offline, will keep trying* separately from *this one failed*, so the two states must not overlap.

**Which attempt makes the row fail for good?** The 8th attempt, counted the way WorkManager counts attempts (`runAttemptCount`), and only when that attempt also could not connect. The row becomes FAILED with `coach unreachable` and the worker returns `failure`. The alternative is to leave the row offline forever and let WorkManager exhaust its own retry budget, which ends with no message on the row and a generation that looks like it is still waiting. Eight is the brief's number. The visible result is that a coach that never comes back produces a clear failure rather than an endless spinner.

**Is the offline mark cleared by any successful connection?** Yes. The first attempt that reaches the daemon sets `error` back to null, even if that attempt then fails for another reason (a bad reply, a paused session). The alternative is to clear it only when the whole generation succeeds, which would leave "offline" on screen for a coach that is plainly reachable.

**Added by review: what happens when the daemon refuses `rho.session.resume`?** The refusal is swallowed and the worker still returns `retry`, with the row and session left as they were. Otherwise a permanent code on the resume call would reach the catch that fails the generation, and a session that was, say, already running again would turn a healthy generation into a FAILED one. The next attempt reads the session's real state anyway.

**Added by review: are a missing host or token, or a rejected token, "offline"?** No. `NotConfigured` and `AuthenticationRejected` keep today's plain retry. They carry no `Mission Control offline` mark and never count toward the 8th-attempt `coach unreachable` failure. The brief names only `Unreachable` and a failed connect. Telling the user Mission Control is down when the real problem is a setting they have not filled in, or a token the daemon rejects, would send them looking in the wrong place. Mission Control's own Test connection already reports those two.
