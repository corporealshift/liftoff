# Tasks — GenerationWorker resilience: resume, reconnect, offline

Seven tasks, in this order. Each ends in one commit (`area: lowercase summary`, named files staged).
The eight tests `verify.sh` names are each written by exactly one task, under the exact name it uses:

| name | task |
|---|---|
| `isCoachOfflineIsTrueOnlyForARowStillWaitingOnTheCoach` | 1 |
| `aGenerationWhosePromptAlreadyLandedResumesFromTheStoredCursorAndStoresTheReply` | 2 |
| `aRetriedGenerationSendsItsPromptAgainUnderTheSameClientIdAndTheDaemonDeliversItOnce` | 2 |
| `aCursorAtTheEndOfTheTurnStillFindsTheReplyTheWorkerAlreadyRead` | 3 |
| `aPausedTurnSendsAResumeForTheSessionItStoppedIn` | 4 |
| `anUnreachableDaemonMarksTheGenerationOfflineAndRetries` | 5 |
| `aLaterRunThatReachesTheDaemonClearsTheOfflineMark` | 5 |
| `theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable` | 6 |

Order matters: 2 needs nothing before it; 3 and 4 need 2's fake changes; 5 needs 1's helper and query; 6 needs 5's
closed-port setup. Read `map.md` before each task — it records the worker's line numbers, the fake's dedup, and the
`GenerationWorkerTest` helpers to reuse.

Two constraints every resumed-turn case runs into, from the plan's Risks: a resumed turn can send more than one
`events_after` frame and a row run twice sends two prompts, so those cases read `daemon.framesFor(...)` or
`daemon.log` directly — the class's `frameFor` helper stays meaning "exactly one", because existing cases depend on
it. And a row that already has a session id and a cursor is inserted directly (`Generation(nabuSessionId =
daemon.sessionId, lastEventId = …)`), as the existing resumed case does; `insertOutlineGeneration` and
`insertFlightPlanGeneration` seed neither.

- [x] 1. The offline mark on the row: the query and the helper
  `GenerationDao` gains one `@Query` that sets or clears `error` on a QUEUED or RUNNING row only, leaving status,
  session id, cursor, attempt and `finishedAt` alone; `Generation` gains `isCoachOffline` plus the
  `Mission Control offline` constant it compares against, which the worker will write in task 5. No schema change:
  no entity field, no migration, no new `app/schemas` version. Nothing reads them yet, so the rest of the tree is
  untouched.
  Files: `data/GenerationDao.kt`, `data/Generation.kt`, `data/GenerationDaoTest.kt`.
  Test written here: `isCoachOfflineIsTrueOnlyForARowStillWaitingOnTheCoach` — true for a QUEUED row and for a
  RUNNING row carrying exactly that mark; false for either status with no error text or with any other error text;
  false for a FAILED row carrying `coach unreachable`. Also prove the query leaves a FAILED row's error alone.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --tests "com.liftoff.app.data.GenerationDaoTest"`
  passes with that name in `app/build/test-results/testDebugUnitTest/TEST-*.xml`, and `git diff` shows no change
  under `app/schemas`.

- [x] 2. `FakeDaemon`: a resume handler, and a seeded prompt that counts as already sent
  The fake gets a `rho.session.resume` handler (today the method falls to the catch-all `{ok:true}` at 275, so a
  resume frame is visible but the fake does nothing for it), and a user message seeded through `logEvent` registers
  its `client_id` in `promptIds` the way `send_prompt` already does — otherwise a re-send under that client_id
  appends a second message and plays a new turn. Then two worker cases prove the dedup through the existing
  `runWorker` harness.
  Files: `rho/FakeDaemon.kt`, `work/GenerationWorkerTest.kt`.
  Tests written here:
  - `aGenerationWhosePromptAlreadyLandedResumesFromTheStoredCursorAndStoresTheReply` — seed the fake's log with this
    row's own prompt as a user message carrying `client_id "<generationId>-0"`, then the assistant reply, then an
    idle end; the row is QUEUED with `nabuSessionId` and a `lastEventId` at or before that prompt. Assert no
    `rho.session.create` frame, the first `events_after` frame carrying the stored cursor, the reply stored on the
    mission, the row SUCCEEDED with its cursor moved forward, and exactly one user message in `daemon.log` for that
    client_id.
  - `aRetriedGenerationSendsItsPromptAgainUnderTheSameClientIdAndTheDaemonDeliversItOnce` — the same row through
    `doWork()` twice. Seed the log as prompt → `paused` → reply → idle so the first run stops at the paused end and
    the second finds the reply and the idle end from the cursor it stored: a deduped prompt plays no new turn, so a
    case that lets the first run send the prompt would leave the second waiting for a turn the daemon never plays.
    Assert both `send_prompt` frames carry the same `"<generationId>-<attempt>"` built from the row's attempt, one
    user message for it, and no second create frame.
  Done when: those two names pass in `GenerationWorkerTest`, and `CoachTurnRunnerTest` plus the `com.liftoff.app.rho`
  test classes still pass unchanged (the plan's Risks says to run them, not assume it).

- [x] 3. The runner finds a reply its cursor already walked past
  `CoachTurnRunner`'s single catch-up read is the gap: when the stored cursor is past the prompt, the read from the
  cursor yields nothing, `end` stays null, and the turn sits in `pushed.receive()` until the 20-minute limit while
  the test's own 30-second wait ends it. Add the second read — from the prompt's own id — for when the cursor is
  past the prompt and the first read lacks the end or the reply, and decide the turn as if the cursor had never
  passed them: the first end after the prompt, the last assistant message before it. In that second read, skip a
  `paused` or `error` end at or before the stored cursor: the attempt that read it already acted on it. Keep the
  cursor moving forward and ids deduped, so a re-read cannot re-store events the row already saved.
  Files: `work/CoachTurnRunner.kt`, `work/GenerationWorkerTest.kt`.
  Test written here: `aCursorAtTheEndOfTheTurnStillFindsTheReplyTheWorkerAlreadyRead` — the same seeded log as task
  2, but the row's `lastEventId` at the idle end, so the read from the cursor finds nothing after it. Assert
  `Result.Success`, the row SUCCEEDED with the reply stored on the mission, exactly one prompt frame for the row,
  attempt 0 and `error` null, and the cursor not moved back behind the reply.
  Done when: that name passes, and `CoachTurnRunnerTest` (including
  `twoEndingStateChangesAfterThePromptStopAtTheFirst`) and all of `GenerationWorkerTest` are green.

- [x] 4. A paused turn hands its session back
  In `storeFinishedTurn`, the arm that today returns `retry` for any end that is not Idle/Completed sends
  `rho.session.resume` for the session the turn stopped in first, then returns `retry` and leaves the session open.
  A resume the daemon refuses — permanently or not — is swallowed the way `giveUpSession` swallows a refused stop, so
  it can never reach the catch that fails the generation.
  Files: `work/GenerationWorker.kt`, `work/GenerationWorkerTest.kt`.
  Test written here: `aPausedTurnSendsAResumeForTheSessionItStoppedIn` — exactly one `rho.session.resume` frame whose
  `session_id` is the session the row still holds afterwards; `Result.Retry`; the row still RUNNING with that session
  id and the cursor the paused turn reported, `error` null, attempt unchanged, `finishedAt` null, nothing stored on
  the mission; no `rho.session.stop`. Either extend and rename the existing
  `aPausedTurnRetriesAndLeavesTheRowRunningWithItsSessionAndCursor` or add this case beside it — the new name must
  exist and the class must stay green; neither existing retry case lists every frame the worker sent, so a resume
  frame does not break it. The plan's Testing also asks for the refused-resume shape
  (`refuseMethod["rho.session.resume"]` with a permanent code → still `Retry`, row not FAILED); `verify.sh` does not
  name it, but write it.
  Done when: that name passes and every case in `GenerationWorkerTest` passes, including the paused and
  ends-in-error cases written against the old behaviour.

- [x] 5. An unreachable daemon marks the row offline, and a reached daemon clears it
  Split the `else -> Result.retry()` arm in `doWork()`: `Unreachable` becomes its own path that stamps the offline
  mark (the task 1 query, with the task 1 constant) on the row and returns `retry`; `NotConfigured` and
  `AuthenticationRejected` keep today's plain retry with no mark. A `client.connect()` that throws with no code takes
  the same offline path, so a failed connect is not mistaken for a turn that dropped mid-way. Clear the mark as soon
  as the connection check answers `Connected`, even if that attempt then fails for another reason. Add the test
  helper that points the container's settings at a host and port nothing listens on — a port the test opened and
  closed — and back at the live fake; the connection check's own 5-second connect timeout makes the refusal arrive
  well inside `turnWait`.
  Files: `work/GenerationWorker.kt`, `work/GenerationWorkerTest.kt`.
  Tests written here:
  - `anUnreachableDaemonMarksTheGenerationOfflineAndRetries` — `Result.Retry`; the row still RUNNING and not FAILED
    with `finishedAt` null and its session id and cursor as they were; `error` exactly `Mission Control offline`;
    `Generation.isCoachOffline` true; nothing reached the scripted daemon.
  - `aLaterRunThatReachesTheDaemonClearsTheOfflineMark` — that same row, settings pointed back at the live fake:
    `error` null and `isCoachOffline` false afterwards, the row not FAILED and carrying no `coach unreachable` text,
    and the generation running on to SUCCEEDED with its reply stored.
  Done when: both names pass and every existing `GenerationWorkerTest` case still passes — the ones that reach the
  daemon and the ones that do not.

- [x] 6. The 8th attempt that cannot connect fails for good
  On the offline path, `runAttemptCount >= 7` (WorkManager counts from 0, so 7 is the 8th) marks the row FAILED with
  `coach unreachable` and returns `failure` instead of stamping the offline mark; the row's own `attempt` is a
  different counter and stays out of it. `runWorker` gains an optional run-attempt count passed to
  `TestListenableWorkerBuilder.setRunAttemptCount`.
  Files: `work/GenerationWorker.kt`, `work/GenerationWorkerTest.kt`.
  Test written here: `theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable` — the closed-port setup
  with `setRunAttemptCount(7)`: `Result.Failure`; the row FAILED with `error` exactly `coach unreachable` and
  `finishedAt` stamped; `isCoachOffline` false; the row not left RUNNING with the offline mark. If it lands RUNNING
  instead, check the threshold before touching the assertions.
  Done when: that name passes, task 5's two names still pass, and the whole `GenerationWorkerTest` class is green.

- [x] 7. The two architecture rows, then the whole gate
  `ARCHITECTURE.md`: the `com.liftoff.app.work` area-map row (line 36) and the M3 row (line 82) drop the claim that
  resilience beyond the current outcomes is still to come — say what the worker does now (resume, resume-from-a-stored
  session and cursor, the offline mark and the 8th-attempt failure). Only that claim: the M3 row's "still to come"
  also lists cancellation, supersede and app wiring, none of which this run builds, so it stays. Wording is reviewed
  by Kyle in the pull request; nothing in `verify.sh` reads these rows, so do not tune them against a grep.
  Files: `ARCHITECTURE.md`.
  Then run the full gate once, detached, on the finished tree: `mkdir -p app/build` first, then
  `timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` into a log under
  `app/build/`. The suite can hang and die at the build's own 5-minute test-task timeout writing no reports — re-run
  the same tree before treating that as a failure, and re-run once for the known `InFlightScreenTest` body-weight
  flake. Read the log and the XML totals; a piped `tail` makes `echo $?` useless.
  Done when: the gate reports BUILD SUCCESSFUL with zero failures and errors, every test class `verify.sh` checks by
  name has run, and `git diff` shows no change under `app/schemas`.
