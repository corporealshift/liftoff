# Plan

## Approach

The row already carries `nabuSessionId` and `lastEventId` and the worker already hands both to the turn runner, so
resuming is not a new path — it is the runner's catch-up read being able to find a reply the stored cursor has
already walked past (today it waits out the 20-minute turn limit instead), plus one `rho.session.resume` call when a
turn ends paused. The offline behaviour is a change of arm: the connection check's `Unreachable` outcome and a
`client.connect()` that throws are today both silent retries, and they become where the row is marked offline,
with WorkManager's own `runAttemptCount` — readable straight off the worker — deciding when "offline" becomes
"coach unreachable". `FakeDaemon` already dedupes a prompt by `client_id` and already records every frame, so the
new cases run against the existing Robolectric harness with three small additions to the fake. No Room schema
change: the offline mark is a `@Query` that writes `error` on its own.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the paused end sends `rho.session.resume` for the
  session it stopped in before returning `retry`, and still leaves that session open; the `Unreachable` outcome and a
  `client.connect()` that throws become their own path that stamps the offline mark and retries, so a connect that
  fails is not mistaken for a turn that dropped mid-way; `NotConfigured` and `AuthenticationRejected` keep today's
  plain retry. The mark is cleared by the first attempt that reaches the daemon; `runAttemptCount` decides when an offline row becomes FAILED `coach unreachable`
  and `failure`. What the file looks like today: see the map.
- `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` — the catch-up read continues from the prompt's own id
  when the stored cursor is past it, so a reply and an end the cursor has already passed are still found instead of
  the turn being waited out.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — one query that sets or clears `error` on a row that has
  not finished, leaving status, session id, cursor and attempt alone.
- `app/src/main/java/com/liftoff/app/data/Generation.kt` — `isCoachOffline`, the public helper a later Launchpad run
  calls instead of comparing strings.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — a `rho.session.resume` handler; a user message seeded
  through `logEvent` registers its `client_id` for dedup the way a prompt sent through `send_prompt` already does;
  a way to make the daemon unreachable from a test.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — the new cases, reusing `runWorker`,
  `insertOutlineGeneration` / `insertFlightPlanGeneration` and the existing assertions; the paused case gains the
  resume-frame assertion.
- `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt` — the new query and `isCoachOffline`.
- `ARCHITECTURE.md` — the `com.liftoff.app.work` row (line 36) and the M3 row (line 82) drop the claim that
  resilience beyond the current outcomes is still to come.

## Order

1. `Generation.isCoachOffline` and the DAO query that writes `error` on its own, with their cases in
   `GenerationDaoTest`. Nothing reads them yet, so the build stays green.
2. `FakeDaemon`: the `rho.session.resume` handler, `client_id` registration for a seeded user message, and the way to
   make it unreachable. Its own tests (`CoachTurnRunnerTest`, `rho` client tests) still pass unchanged.
3. The runner's second catch-up read, proved by the resume case: a row with a session id and a cursor whose log
   already holds the prompt and the reply — no create frame, the reply stored, `events_after` starting from the stored
   cursor — plus the variant where the cursor is past the prompt.
4. Paused: `rho.session.resume` then `retry`, added to the existing paused case.
5. The offline mark and its clearing: an unreachable daemon marks the row and retries; a later run that connects
   clears it.
6. The 8th attempt: `runAttemptCount` at 7 turns the offline row into FAILED `coach unreachable` and `failure`.
7. `ARCHITECTURE.md` rows, then the whole gate — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` run
   detached, re-run if it hangs.

## Testing

All worker cases run in `GenerationWorkerTest` against `FakeDaemon` through the existing `runWorker` harness, each
wait bounded by `turnWait`, nothing dependent on timing.

- **paused** — a `rho.session.resume` frame naming the session the turn stopped in, then `Result.Retry`; the row stays
  RUNNING with its session id and cursor, `error` null, no `rho.session.stop`. Added to the existing paused case.
- **resume from a stored session and cursor** — a row seeded with `nabuSessionId` and `lastEventId`, the fake's log
  already holding our prompt, the reply and the end: no `rho.session.create` frame, the first `events_after` frame
  carrying the stored cursor, and the reply stored on the mission.
- **cursor past our own prompt** — the same row with the cursor at the reply or later: the reply is still found and
  stored, proved by the second read starting from the prompt's id rather than by a wait.
- **a re-sent prompt is delivered once** — two runs of the same row send the same `<generationId>-<attempt>`
  client_id and the fake's log holds exactly one user message for it. `CoachTurnRunnerTest`'s
  `twoEndingStateChangesAfterThePromptStopAtTheFirst` already shows this shape at the runner level.
- **unreachable daemon** — a fake that is stopped before the run: `Result.Retry`, the row still RUNNING (the worker marks it running before it
  connects) and not FAILED, `error` exactly `Mission Control offline`, `finishedAt` still null, and no frame at the
  daemon.
- **the mark is cleared** — a row carrying that mark, run again against a live fake, ends SUCCEEDED with `error` back
  to null.
- **the 8th attempt** — the same unreachable setup with `setRunAttemptCount(7)`: `Result.Failure`, the row FAILED with
  `coach unreachable` and `finishedAt` stamped.
- **`isCoachOffline`** — in `GenerationDaoTest`: true for a QUEUED or RUNNING row carrying exactly that mark, false for
  a FAILED row and for a running row with any other error text.
- The 18 existing `GenerationWorkerTest` cases, `GenerationEnqueuerTest`, `CoachTurnRunnerTest`, the coach, data and
  domain tests, and the whole gate must stay green.

## Risks

- **`frameFor` asserts exactly one frame.** A resumed turn can send two `events_after` frames, and a case that runs the worker twice on one row sends two
  `send_prompt` frames, so those cases assert on `daemon.framesFor(...)` and read the one they mean, or on `daemon.log`. The helper itself is not loosened, because
  the existing cases depend on it meaning "exactly one".
- **The attempt count is off by one if WorkManager counts from 1.** The plan takes it as 0-based, so the 8th attempt is
  `runAttemptCount == 7`. If the case built with `setRunAttemptCount(7)` leaves the row RUNNING, the threshold is wrong,
  not the test — check the neighbouring count rather than weakening the assertion.
- **A drop must not be a race.** Making the daemon unreachable is done by stopping the fake (or pointing the settings at
  a closed port) before `runWorker`, not by closing a socket part-way through a turn. The connection check's own
  timeouts are 5 s connect / 10 s read, so a closed port fails well inside `turnWait`.
- **The offline mark must never overwrite a decided failure.** Only the connect-failure path writes it; `markFailed`
  keeps its own reason, and a row already FAILED is not re-marked.
- **`NotConfigured` and `AuthenticationRejected` are not the coach being offline.** The brief names only `Unreachable`,
  so those two keep a plain retry with no mark: a host or token the user has not filled in is not reported as Mission
  Control being down.
- **The second read must not move the cursor backwards.** The runner already only moves the cursor forward and skips ids
  it has seen; a re-read from the prompt's id has to keep both, or a resumed row would re-store events it already saved.
- **`FakeDaemon` changes are shared.** Registering a seeded message's `client_id` for dedup touches a fake used by the
  runner and rho client tests; no existing case seeds a message carrying a `client_id`, so none should change, but
  those classes are run after this step rather than assumed.
- **The gate can hang for about 5 minutes on this machine.** Run it detached and re-run the same tree before treating a
  hang as a failure.

## Decisions
**While a generation is paused, what does the row look like?** The row keeps the status it already had (QUEUED or RUNNING; `error` null, because this attempt reached the daemon) and the worker returns `retry`, so the generation still reads as in progress and WorkManager comes back to it. The alternative is to write a "paused" status or an error message so the user can see the wait. The brief forbids a schema change, so there is no paused status to write, and a paused session is a temporary state the worker expects to clear on the next attempt — showing it as a problem would be a message the user cannot act on.

**Does a resumed run send the prompt again, or only read what already happened?** It sends, under the client_id the row already holds: the daemon dedupes by client_id and answers with the original prompt's id, so a prompt that already landed plays no second turn. It then reads from the stored cursor, and reads once more from the prompt's own id when that first read finds no end and the cursor has already walked past the prompt. The alternative is to read before sending anything, which costs an extra read from the start of the log just to find the prompt, for a result the user cannot tell apart. Either way the coach gets one prompt and one reply per generation.

**Can the offline mark overwrite an error that is already a real failure?** No. `Mission Control offline` is written only when this attempt failed to connect, and a row that already carries a decided failure (a reply that could not be repaired, a refused turn) keeps that message. The alternative — always stamping the offline text — would make a generation that genuinely failed read as "the coach is offline", which is both wrong and misleading for the Launchpad run that reads `isCoachOffline`.

**What does `Generation.isCoachOffline` mean?** It is true only for the offline mark: the row is still QUEUED or RUNNING and `error` is exactly `Mission Control offline`. A row that has failed with `coach unreachable` or any other message is not offline. The alternative is "any error that came from a connection", which would need the failure text too. The point of the helper is that Launchpad can say *offline, will keep trying* separately from *this one failed*, so the two states must not overlap.

**Which attempt makes the row fail for good?** The 8th attempt, counted the way WorkManager counts attempts (`runAttemptCount`), and only when that attempt also could not connect: the row becomes FAILED with `coach unreachable` and the worker returns `failure`. The alternative is to leave the row offline forever and let WorkManager exhaust its own retry budget, which ends with no message on the row and a generation that looks like it is still waiting. Eight is the brief's number; the visible result is that a coach that never comes back produces a clear failure rather than an endless spinner.

**Is the offline mark cleared by any successful connection?** Yes — the first attempt that reaches the daemon sets `error` back to null, even if that attempt then fails for another reason (a bad reply, a paused session). The alternative is to clear it only when the whole generation succeeds, which would leave "offline" on screen for a coach that is plainly reachable.

