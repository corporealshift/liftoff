# Plan

## Approach
Two writes are wrong, and both are already modelled in this file. `failGeneration` is the only place the worker
ever writes FAILED — the blocked turn, the exhausted repairs, a permanent `DaemonException`, a Mission or Sortie
that has gone away and the 8th unreachable attempt all funnel through it — so guarding that one write and giving
it the outcome the guarded store already uses (leave the row, give up the session, end as done) fixes every
failure end at once. `markRunning` gets the same status filter, and the worker stops there when the write refuses
instead of opening a turn. `GenerationDao.setCoachOfflineMark` already carries `AND status IN ('QUEUED',
'RUNNING')`, so a status-filtered `@Query` is precedented in this DAO and, as the brief says, is not a schema
change; the check and the write stay in one statement so a cancel that lands between them cannot slip through.


## Files
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — `markRunning` and `markFailed` gain the status
  filter `setCoachOfflineMark` already uses, and each reports whether it changed the row, so a caller can tell a
  refusal from a write. What the DAO already holds: map.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — `failGeneration` ends as done, after giving up
  the session it was holding, when the row has already been cancelled; `doWork` ends quietly when the mark-running
  write is refused, before the Mission/Sortie reads and before any connection is opened.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — the three cases the brief names, on the
  existing harness and fake daemon.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — only if the cases need it: a script step that can run
  an action part-way through a turn, so a test can cancel the row before the fake sends the event that ends it.
  Test-only; nothing in `src/main` depends on it.
- `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt` — one case that shows the two guards refuse a row
  that has already decided its outcome. This is what proves the DAO change bites; see Testing.

No new files. No schema change, so `app/schemas` must stay byte-identical.


## Order
1. Guard `markRunning` and `markFailed` in the DAO and add the DAO case. Every existing caller moves a QUEUED or
   RUNNING row, so the rest of the suite is untouched; `:app:assembleDebug` and `GenerationDaoTest` pass.
2. `failGeneration`: when the guarded write is refused because the row has been cancelled, give up the session as
   it does today and return done instead of failure. Add the two mid-turn cases (blocked end, repairs exhausted).
3. `doWork`: end quietly when the mark-running write is refused, before the Mission/Sortie reads and before any
   daemon connection. Add the pre-cancelled case.
4. Whole gate, detached, once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, re-run the identical
   tree if it hangs. Confirm the worker, enqueue, cancel and turn-runner tests, Flight Plan and outline queueing,
   the Launchpad cases and `app/schemas` are unchanged and green.


## Testing
All in `GenerationWorkerTest` on the existing harness and fake daemon, every wait bounded by the class's own
timeout. The two mid-turn cases need the worker running while the row is cancelled, so they launch `doWork()` on
another dispatcher and `await()` it under the timeout, as
`aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone` does. That case polls until the prompt
frame and the row's session id appear and only then cancels; the two new failure-path cases cannot win that race
— the fake plays a script that ends a turn straight through, and once the row is FAILED the canceller will not
touch it — so the cancel is driven from a step inside the fake's script, placed before the event that ends the
turn, and it writes the row through the DAO rather than through `container.generationCanceller()`. That makes the
row provably CANCELLED before the worker can act on the turn's end, and keeps the single `rho.session.stop` frame
the daemon sees the worker's own.

- Cancelled during a turn that then ends blocked (the script from
  `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput`): the row is CANCELLED with its error still
  whatever it held before the cancel — not FAILED, not `coach needed input` — and `doWork` returns done with
  exactly one session stop.
- Cancelled during a turn that then exhausts its repairs (the script from
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing`): the row is CANCELLED with its
  error unchanged, `doWork` returns done, and nothing is stored on the Mission.
- Cancelled before the worker starts: the row is CANCELLED, `doWork` returns done, and the fake daemon saw no
  frames at all — no session, no prompt.
- In `GenerationDaoTest`: `markRunning` leaves a CANCELLED, SUCCEEDED or FAILED row exactly as it was, and
  `markFailed` writes no status, no error and no `finishedAt` to a CANCELLED row.

Mutation checks, with the red each one is expected to cause: dropping the status filter from `markFailed` reddens
both mid-turn cases; dropping it from `markRunning` reddens the DAO case; and the pre-cancelled worker case stays
green under both, because `doWork` already returns done for a finished row — which is why the DAO case is the one
that proves that guard.


## Risks
- The pre-cancelled worker case cannot reproduce the race the brief describes — a cancel landing between the row
  read and the mark-running write — without SQLite lock timing a test cannot control. The plan proves that guard
  in `GenerationDaoTest` and keeps the worker case as the guard that no turn is opened for a finished row.
- Guarding `markFailed` in the DAO reaches every caller. The existing seeding in `MissionManagerTest`, the three
  Launchpad tests and `GenerationDaoTest` all mark rows that are QUEUED or RUNNING, so they keep working; the
  whole gate confirms that rather than reworking them.
- The mid-turn race. The fake plays a script that ends a turn straight through once the prompt arrives, so a
  poll-then-cancel can lose: the worker reaches `failGeneration` first, the row is FAILED, and the canceller
  refuses a FAILED row. The work session drives the cancel from inside the script as Testing says; if that still
  proves flaky it holds the rest of the turn with a test-only step in `FakeDaemon`, rather than weakening the
  assertion.
- A mid-turn cancel through `container.generationCanceller()` sends its own session stop, which would let
  `assertSessionGivenUpOnce` pass on a worker that stopped nothing. The failure-path cases write the row through
  the DAO for that reason.
- A session stop the daemon refuses must not turn the new done outcome into a retry. The existing `giveUpSession`
  swallow covers it; the new path must not wait on the stop or act on its failure.
- The repair loop still bumps `attempt` on a cancelled row. The brief limits the fix to the FAILED write and its
  error text, so `bumpAttempt` is left alone and the tests assert only status and error.
- If the guarded `markFailed` is refused for a reason other than a cancel, the plan keeps today's failure
  outcome: the brief asks only about a cancelled row.
- `app/schemas` must stay byte-identical — a status filter in a `@Query` is not a schema change, so an exported
  schema that moves means the DAO edit is wrong.
- The full gate can hang about five minutes on this machine: run it detached with the log and an `EXIT=$?`
  sentinel appended without a pipe, and re-run the identical tree once if it hangs — no `gradlew --stop`
  (machine-wide), and move the results directory with `mv`, never `rm`.


## Decisions
**What the worker reports to WorkManager when it finds the row already cancelled.** The brief settles the
failure path (`Result.success()`), but not the mark-running path, where it only says the work "ends quietly".
The plan ends both paths as `Result.success()`. The alternative is `Result.failure()`, which asks WorkManager to
retry the same generation. A cancel is something the user asked for: retrying would re-enqueue a turn they asked
to stop, and would keep the row alive in WorkManager's list for another backoff cycle. Ending as done is also
what the already-guarded store path does today when it finds the row is no longer current, so the two refusal
paths agree.

**Which statuses the mark-running step accepts.** The plan lets it write only to a row that is still QUEUED or
RUNNING, so a row already CANCELLED, FAILED or SUCCEEDED is left alone and the worker stops there without
opening a turn. The alternative is to guard only against CANCELLED, which is the narrower reading of the brief's
title. Users expect the wider one: WorkManager can deliver a late or duplicate attempt for a generation that has
already reached an end state, and a row that has finished must not be dragged back to RUNNING by it.

**Whether a cancelled row's existing error text is cleared.** The plan writes nothing at all to a cancelled row,
so whatever it already held stays. The alternative is to blank the error, or to record why the abandoned turn
ended. The brief asks for the error unchanged, and the row is the record of the generation the user cancelled —
the reason its turn happened to end afterwards is not that generation's story.

The brief settles the rest of the behavior it touches: the failure path still stops the rho session when one was
started, no Room schema change, and no FAILED status or error text on a cancelled row.
