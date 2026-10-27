# Plan

## Approach
Two writes are wrong, and both are already modelled in this file. `failGeneration` is the only place the worker
ever writes FAILED. Every failure end goes through it: the blocked turn, the exhausted repairs, a permanent
`DaemonException`, a Mission or Sortie that has gone away and the 8th unreachable attempt. Guarding that one
write, and giving it the outcome the guarded store already uses (leave the row, give up the session, end as
done), fixes every failure end at once. `markRunning` gets the same status filter, and the worker stops there
when the write refuses instead of opening a turn. `GenerationDao.setCoachOfflineMark` already carries
`AND status IN ('QUEUED', 'RUNNING')`, so a status-filtered `@Query` is precedented in this DAO and, as the brief
says, is not a schema change. The check and the write stay in one statement so a cancel that lands between them
cannot slip through.


## Files
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — `markRunning` and `markFailed` gain the status
  filter `setCoachOfflineMark` already uses. Each returns the number of rows it changed, so a caller can tell a
  refusal from a write. Existing callers that ignore the return value still compile.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — when the guarded write is refused and the row
  reads CANCELLED, `failGeneration` gives up the session it was holding and ends as done. `doWork` ends quietly
  when the mark-running write is refused, before the Mission/Sortie reads and before any connection is opened.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — needed. Today a scripted turn plays straight through
  with no way to act part-way. Add one `DaemonScript` step that runs a test-supplied action at that point in the
  turn, synchronously inside `play`, before the next step's event is appended. The `when` in `play` is the only
  exhaustive match on `DaemonScript`, so it gains one branch. Test-only; nothing in `src/main` depends on it.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — the three cases the brief names, on the
  existing harness and fake daemon.
- `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt` — one case that shows the two guards refuse a row
  that has already decided its outcome. This is what proves the DAO change bites; see Testing.

No new files. No schema change, so `app/schemas` must stay byte-identical.


## Order
1. Guard `markRunning` and `markFailed` in the DAO and add the DAO case. Every existing caller (`GenerationDaoTest`,
   `MissionManagerTest`, the three Launchpad tests) moves a QUEUED or RUNNING row, so the rest of the suite is
   untouched. `:app:assembleDebug` and `GenerationDaoTest` pass.
2. Add the FakeDaemon action step. Then change `failGeneration`: when the guarded write is refused and the row now
   reads CANCELLED, give up the session as it does today and return done instead of failure. Add the two mid-turn
   cases (blocked end, repairs exhausted).
3. `doWork`: end quietly when the mark-running write is refused, before the Mission/Sortie reads and before any
   daemon connection. Add the pre-cancelled case.
4. Whole gate, detached, once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, re-run the identical
   tree if it hangs. Confirm the worker, enqueue, cancel and turn-runner tests, Flight Plan and outline queueing,
   the Launchpad cases and `app/schemas` are unchanged and green.


## Testing
All in `GenerationWorkerTest` on the existing harness and fake daemon, with every wait bounded by the class's own
`turnWait` timeout. The existing `aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone` polls,
then cancels, and the two new failure-path cases cannot win that race. The fake plays a turn straight through once
the prompt arrives, and once the row is FAILED the canceller will not touch it. So each case puts the new action
step in its script, right before the event that ends the turn. That action marks the row CANCELLED through
`GenerationDao.markCancelled`, not through `container.generationCanceller()`. The row is therefore CANCELLED
before the worker can see the turn's end, and the single `rho.session.stop` the daemon sees is the worker's own.
These cases can use `runWorker` as is; they need no extra dispatcher and no poll.

- Cancelled during a turn that then ends blocked (the script from
  `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput`, with the cancel step before `State("blocked")`).
  The row is CANCELLED and its error is whatever it held before the cancel, not `coach needed input`. `doWork`
  returns done, and `assertSessionGivenUpOnce` passes.
- Cancelled during a turn that then exhausts its repairs (the two scripts from
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing`). Put the cancel step in the first
  turn, before its `State("idle")`. The fake repeats the last scripted turn, so a step in the second turn would
  run twice. The repairs then play out as they do today. The row is CANCELLED with its error unchanged, `doWork`
  returns done, nothing is stored on the Mission, and the session is given up once.
- Cancelled before the worker starts: the row is CANCELLED, `doWork` returns done, and the fake daemon saw no
  frames at all (`assertNothingReachedTheDaemon`).
- In `GenerationDaoTest`: `markRunning` leaves a CANCELLED, SUCCEEDED or FAILED row exactly as it was, and
  returns 0. `markFailed` writes no status, no error and no `finishedAt` to a CANCELLED row, and returns 0.

Mutation checks, and the red each should cause:
- Dropping the status filter from `markFailed` should fail both mid-turn cases.
- Dropping it from `markRunning` should fail the DAO case.
- The pre-cancelled worker case stays green under both, because `doWork` already returns done for a finished row.
  That is why the DAO case is the one that proves the `markRunning` guard.


## Risks
- The pre-cancelled worker case cannot reproduce the race the brief describes: a cancel landing between the row
  read and the mark-running write. Doing that would need SQLite lock timing a test cannot control. The plan proves
  that guard in `GenerationDaoTest` and keeps the worker case as the check that no turn is opened for a finished
  row.
- Guarding `markFailed` in the DAO reaches every caller. The existing seeding in `MissionManagerTest`, the three
  Launchpad tests and `GenerationDaoTest` all mark rows that are QUEUED or RUNNING, so they keep working. The
  whole gate confirms that; they are not reworked.
- The action step runs on OkHttp's listener thread, inside the fake's synchronized `play`. At that point the
  worker is waiting on events and holds no transaction, so a blocking DAO write there cannot deadlock. The step
  must not throw. Any assertion belongs in the test body, not in the action.
- A mid-turn cancel through `container.generationCanceller()` sends its own session stop. That would let
  `assertSessionGivenUpOnce` pass on a worker that stopped nothing, which is why the failure-path cases write the
  row through the DAO instead.
- A session stop the daemon refuses must not turn the new done outcome into a retry. The existing `giveUpSession`
  swallow covers it. The new path must not wait on the stop or act on its failure.
- The repair loop still bumps `attempt` and sends repair prompts on a cancelled row. The brief limits the fix to
  the FAILED write and its error text, so `bumpAttempt` is left alone and the tests assert only status and error.
- If the guarded `markFailed` is refused for a reason other than a cancel, the plan keeps today's failure
  outcome, because the brief asks only about a cancelled row.
- `app/schemas` must stay byte-identical. A status filter in a `@Query` is not a schema change, so if the
  exported schema moves, the DAO edit is wrong.
- The full gate can hang for about five minutes on this machine. Run it detached, with the log and an `EXIT=$?`
  sentinel appended without a pipe. If it hangs, re-run the identical tree once. Don't run `gradlew --stop`,
  which is machine-wide, and move the results directory with `mv`, never `rm`.


## Decisions
**Whether a cancel stops the repair loop.** Added by review. The plan leaves the repair loop alone: a row
cancelled mid-turn still has its remaining repair turns played, and only the final FAILED write is refused. The
alternative is to check the row before each repair prompt and stop early. The brief says "fix only that", and its
second test case describes a cancelled turn that "then exhausts its repairs", which assumes the loop runs on. The
store path and the failure path both already refuse to write over a cancel, so the extra turns cost coach time
but leave nothing behind. Stopping the loop early is a follow-up.

**What the worker reports to WorkManager when it finds the row already cancelled.** The brief settles the
failure path (`Result.success()`), but not the mark-running path, where it only says the work "ends quietly".
The plan ends both paths as `Result.success()`. The alternative is `Result.failure()`, which marks the work as
failed in WorkManager. A cancel is something the user asked for, so the work should not read as failed. Ending as
done is also what the already-guarded store path does today when it finds the row is no longer current, so the
two refusal paths agree.

**Which statuses the mark-running step accepts.** The plan lets it write only to a row that is still QUEUED or
RUNNING, so a row already CANCELLED, FAILED or SUCCEEDED is left alone and the worker stops there without
opening a turn. The alternative is to guard only against CANCELLED, which is the narrower reading of the brief's
title. Users expect the wider one: WorkManager can deliver a late or duplicate attempt for a generation that has
already reached an end state, and that attempt must not drag a finished row back to RUNNING.

**Whether a cancelled row's existing error text is cleared.** The plan writes nothing at all to a cancelled row,
so whatever it already held stays. The alternative is to blank the error, or to record why the abandoned turn
ended. The brief asks for the error unchanged, and the row is the record of the generation the user cancelled.
Why its turn happened to end afterwards is not part of that record.

The brief settles the rest of the behavior it touches: the failure path still stops the rho session when one was
started, there is no Room schema change, and a cancelled row gets no FAILED status or error text.
