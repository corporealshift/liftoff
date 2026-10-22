# Plan

## Approach

Everything the brief asks for is a decision the worker already makes, at the points where it already makes it — the
`when` on the connection outcome, the `Result.retry()` returns in `storeFinishedTurn`, and the
`catch (DaemonException)` in `runGeneration` — so this is outcome handling added to `GenerationWorker`, not new
machinery. The repair prompt is a second `runCoachTurn` on the session id and cursor the first turn reported, with the
bumped attempt in its client_id: "the same rho session" is the resume path the runner already has (invariant 6), not a
new one. One private helper carries every permanent end — mark the row FAILED with its message, stop the session when
one exists, return `Result.failure()` — so the permanent outcomes cannot drift apart, and the existing
`finally { client.close() }` still covers every path including the new ones. The retry path stays what it is today:
leave the row RUNNING with its session id and cursor and no error text.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the mismatch arm of the connection `when`; the repair
  loop; blocked, `StoreOutcome.Refused` and a permanent `DaemonException` become FAILED with their own messages; the
  stop-on-every-permanent-failure rule; the two message builders (invalid output, protocol mismatch).
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — new tests in the existing class, reusing its
  `@Before`/`@After`, `runWorker`, `frameFor`, `methodsSent` and `assertNothingReachedTheDaemon`.
- `ARCHITECTURE.md` — the M3 row (line 82) must stop reading "Not started" and say the worker's core flow is built,
  naming resilience, cancel, supersede and app wiring as still to come; the `com.liftoff.app.work` cell (line 36) must
  drop its now-false claim that "the repairs and failure outcomes are not written yet" — keep the negation away from
  `GenerationWorker.kt`, which is what a doc check greps for.
- No new file, no Room schema change: `attempt`, `error`, `nabuSessionId` and `lastEventId` are already columns and
  `bumpAttempt`/`markFailed` already exist.

What each of these already holds is in `map.md`.

## Order

1. The permanent outcomes that need no loop — blocked, `ProtocolMismatch`, a permanent `DaemonException`,
   `StoreOutcome.Refused` — routed through one FAILED-and-stop helper, plus their tests. Build and
   `GenerationWorkerTest` green.
2. The repair loop: `bumpAttempt`, a second `runCoachTurn` on the reported session and cursor with
   `CoachPromptBuilder.repairPrompt(errors)` and client_id `<generationId>-<attempt>`, capped at two by the row's
   attempt, then the still-invalid FAILED. Plus its tests. Build and `GenerationWorkerTest` green.
3. The retry-path assertions (paused, error: row still RUNNING with its session and cursor and no error, no stop
   frame) and the stop-frame counts. Test-only.
4. `ARCHITECTURE.md`'s M3 row and work-package cell.
5. The whole gate once, detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

All Robolectric in `GenerationWorkerTest`, against `FakeDaemon` on the application's own container, one scripted turn
per prompt (the fake repeats its last turn when the list runs out), every wait bounded by the class's existing
`turnWait`.

- invalid reply, then a repair, then a valid one: exactly two `rho.session.send_prompt` frames and one
  `rho.session.create`, client_ids ending `-0` then `-1`, row `attempt` 1, row SUCCEEDED, the reply stored.
- two failed repairs: three prompt frames and no fourth, `attempt` 2, row FAILED with an error that starts
  'invalid output' and carries the last turn's error lines, and nothing stored — the mission is still DRAFT with no
  sortie for a plan to sit on.
- `State("blocked")`: FAILED 'coach needed input', `Result.failure()`, one stop frame.
- `ProtocolMismatch` both ways — `refuseProtocolMismatch("2.0")` and `answerHelloWithProtocol("2.0")`: FAILED with
  'Liftoff speaks rho protocol 1.x, the daemon speaks 2.x', `Result.failure()`, and no `rho.session.create` frame.
- `State("paused")` and `State("error")` each: `Result.retry()`, row still RUNNING with its session id and cursor kept
  and `error` null, and no stop frame.
- a permanent refusal at a call (`refuseMethod["rho.session.send_prompt"]` set to a code in `RpcCodes.PERMANENT`):
  FAILED with the daemon's own message, and exactly one stop frame for the session that exists.
- a valid reply whose target has moved on: move it between inserting the row and running the worker — confirm the
  mission out from under an outline row, or scrub the sortie a plan row names — so the store refuses it; FAILED with
  the refusal's own reason, nothing stored, one stop frame.
- one stop frame on success and on a permanent failure, and none on a retry; the existing success-path test already
  asserts the first half.

## Risks

- **The gate can hang for about 5 minutes on this machine** and writes no XML when it does: run it detached through
  `gradlew.sh` with a log and an `EXIT=` sentinel (no pipe, or the sentinel reports the pipe's status), re-run the same
  tree on a test-task timeout, and read the totals out of `TEST-*.xml` rather than trusting `BUILD SUCCESSFUL`.
- **A repair that reuses the previous client_id plays no turn at all** — the fake dedupes by `client_id` and answers
  with the earlier event id, so the test would sit until `turnWait` rather than fail loudly. The client_id is built
  from the attempt read *after* `bumpAttempt`.
- **Three prompts × `TURN_LIMIT` (20 min) can outlast WorkManager's own stop.** That stop is only an early retry that
  resumes the same session and cursor, so nothing is lost; no test may depend on it, and the fake answers at once.
- **`rho.session.stop` appends a `state_change` to `completed` in the fake**, so a stop is also an event: cursor
  assertions stay against the turn's own last event, as the existing tests do, and a stop must not be read as a turn end.
- **A stop call that throws must not change the outcome already decided**, or a permanent failure turns into a retry
  and a dead generation keeps being asked.
- **The mismatch sentence duplicates the Test-connection row's wording**, whose helper is file-private in
  `ConnectionViewModel.kt`. Keep the two sentences identical so a user reads the same thing in both places; do not widen
  the UI helper, and do not read it as part of this run.
- **`app/schemas` must stay byte-identical** — no entity change, no migration. If a schema file appears or changes, the
  step went wrong.
- Existing tests must survive: the worker's own (the blocked and invalid-reply paths change under it), the turn
  runner's, coach, data and domain tests, and Test connection.

## Decisions

**How the "invalid output" failure reads.** The brief marks the row failed with 'invalid output' followed by the last
error list, but not how the list is rendered. The plan writes `invalid output: ` then the validator's own error lines
joined with `; `, each kept word for word. The alternative is the repair prompt's own layout — one error per line, as
plan decision 11 does — which reads well in a prompt but makes the row's single `error` field multi-line. The row's
error is what a user has to go on for why a generation died (nothing reports it yet), and these are the same lines the
coach was handed, so the row reads like the coach's own complaint rather than a second, differently-worded one.

**A daemon error that is not permanent retries rather than failing.** The brief names
permanent refusals as failures and connection outcomes as retries, but says nothing about a
non-permanent `DaemonException` raised part-way through a prompt. The plan retries. The
alternative is to fail the row. A dropped connection mid-generation is exactly the case the
retry path exists for; failing would throw away a generation the user is waiting on when the
daemon is fine a moment later.

**The two repairs are the generation's, not one worker run's.** The cap reads the row's `attempt`, which only the
repair path ever bumps, so a generation gets at most two repairs across all its runs. The alternative is to give each
`doWork()` a fresh two, which would let a generation that keeps pausing part-way through a repair ask the coach again
every time it is retried. Capping on the row is what "at most 2 repairs" for a generation means, and it is what stops a
coach that keeps answering badly from being asked forever.

**No custom retry backoff.** The brief says `Result.retry()` and stops there, so the plan sets
no backoff criteria and takes WorkManager's default exponential backoff. The alternative is a
fixed short delay. Nothing enqueues this worker yet — scheduling is a later milestone — and a
fixed delay would keep poking a daemon that is paused or down.

**A permanently failed row keeps the session id and cursor it had.** Only status and error
text change; the session fields are left as they were. The alternative is to clear them so a
FAILED row points at nothing. The row is terminal and its session is stopped, so the fields
are history, and which session a failed generation used is the only remaining trace of it.
