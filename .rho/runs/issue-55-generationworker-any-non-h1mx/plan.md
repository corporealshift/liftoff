# Plan

## Approach

The worker already has one block where everything from the settings read through the store runs, and one handler
for a generation that is over: `failGeneration`, which writes the row's reason, gives up the rho session and
reports failure. The fix puts the exceptions that currently travel out of `doWork` onto that existing end. A
catch of `Exception` goes on `runGeneration`'s existing `try` — after the `DaemonException` arm, so the daemon's
retryable ends keep retrying — rethrowing `CancellationException` and otherwise ending through `failGeneration`
with the exception's own message, at the point where the client and the session id the turn reported are still
in scope and the socket is still open. The same handler covers what `doWork` does after it claims the row — the
Mission and Sortie reads, the connection check — which have no client yet, exactly as the "Mission no longer
exists" arm already does. Nothing else moves: the ends, the resume
paths, the offline ladder and the sweep stay as they are, and DESIGN §7.3 step 6 already asks for the session
stop on a permanent FAILED that this path was missing. See the map for the current catch shape and where each
throw source sits.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the new `catch` on `runGeneration`'s existing
  `try`, and the same guard around the work `doWork` does after it claims the row. One handler, so both arms
  write the reason the same way and only differ in whether a client and session id are there to give up.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — one new case in the existing harness.
- No new fixture file: the reply is `CoachFixtures.fixture(CoachFixtures.LIFT_PLAN)` with one exercise name
  replaced by a punctuation-only string, so the reply stays the valid plan the rest of the suite already uses and
  only the field the case is about differs.
- No document changes: `ARCHITECTURE.md` already describes the worker as ending the row "FAILED with its reason",
  and DESIGN §10's failure table has no row for a store throw to correct.

## Order

1. Add the handler and its two arms in `GenerationWorker.kt`. Compiles; `GenerationWorkerTest`'s 34 cases stay
   green, because no daemon end changes.
2. Add the store-throw case. Green with the fix. Prove it bites by reverting the new `catch` in a scratch copy,
   running the class, and reading the one failing case (the row left RUNNING, `doWork` throwing) in its own log;
   restore the file and commit only the test.
3. One cold whole-suite run (`:app:assembleDebug :app:testDebugUnitTest --no-build-cache`) and read its log; the
   baseline is 738 tests in 89 reports, so this run should report 739 in the same 89 classes.

## Testing

- A reply that passes validation but whose store throws: the row ends FAILED carrying `ExerciseDao.resolve`'s own
  sentence word for word, `doWork` returns a failure, nothing is stored (no plan, no planned exercises or sets, the
  sortie still PENDING), the session is given up once, and the row keeps the session id its turn ran on. Worker
  test over the real container and the real store — the same seeding as the lift-plan happy path.
- The `CancellationException` rethrow gets no test of its own: cancelling the coroutine running `doWork` cancels
  the row write inside `failGeneration` too, so the row and the throw look the same with or without the rethrow,
  and no assertion could tell them apart. It follows the shape `giveUpSession` and `handSessionBack` already use,
  and review checks it.
- The daemon's ends are unchanged — a permanent refusal still ends FAILED with the daemon's message, a dropped
  connection still retries with the row left RUNNING, an offline ladder still ends "coach unreachable". Existing
  cases; no new test.
- A throw before the row is claimed is unchanged, and a FAILED row is no longer re-enqueued at the next app open
  because it is no longer open. Both already asserted by `aGenerationRowThatDoesNotExist…` and
  `AppOpenSweepTest`; no new test.

## Risks

- **The generic catch could swallow a daemon end that should retry.** It sits after the `DaemonException` arm,
  and step 1 lands with no new test on purpose: the existing retryable cases — a dropped connection retried with
  the row left RUNNING, the offline ladder, a turn that ran out its 20 minutes — are what prove the daemon's ends
  still reach their own arms.
- **The store-throw case is coupled to issue #56**, whose fix would make this reply invalid before the store is
  reached. If #56 lands first, the case keeps every assertion and changes only its throw: make the store throw by
  closing the database mid-turn through `FakeDaemon`'s `Act` step, which reaches the same `catch` and the same
  FAILED row.
- **Dropping the `CancellationException` rethrow reddens nothing, in the existing cases or in a new one**: a
  cancelled coroutine fails the row write inside `failGeneration` before it can write, so the row looks the same
  either way. What protects it is being written in the same commit as the catch it belongs to, in the shape
  `giveUpSession` and `handSessionBack` already use, where a reviewer can see it.
- **An exception with no message** ends the row FAILED with a blank reason. Nothing new is written for it:
  `SortieGenerationStatus` and `CoachStatus` already fall back to their "…generation failed" wording, and their
  tests already cover a FAILED row with no reason.
- **A throw inside `failGeneration` itself** — the row write failing — is outside this fix: the row stays RUNNING
  and the app-open sweep is still the last resort. The plan does not wrap it; a store that cannot write a FAILED
  row is not made able to by this change.
- **Whole-suite timing**: the two known flakes named in the map can redden step 3's run. Re-run the identical tree
  once before treating a red as this change's, and only then look for a cause.

## Decisions

**What the row carries as its reason.** The exception's own message, word for word, and an empty reason when the exception has none (`e.message.orEmpty()`, the same shape the permanent-refusal arm already uses). The alternative is a short generic sentence ("generation failed") or the exception's class name. Word for word is what every other end in this worker already does — the store's own "sortie is SCRUBBED", the daemon's own refusal, the validator's own error lines — and the reason is what the owner reads to decide what to fix. A blank reason is not a blank card: `SortieGenerationStatus` and `CoachStatus` already fall back to "flight plan generation failed" / "outline generation failed" for a FAILED row with no reason, so the owner still sees a failure with Retry offered, while a Kotlin class name on the card would be a string they cannot act on. A Room or SQLite sentence is long and full of SQL; it stays as it came.

**Whether a generation that died this way still gives up its rho session.** Yes, exactly as every other permanent end does, when the turn had already reported a session. The alternative is to leave the session open in case a later attempt resumes it. Nothing will: the row is FAILED, so no attempt comes back for it, and the owner would be left with a live session in Mission Control that Liftoff abandoned. DESIGN §7.3 step 6 asks for the stop on a permanent FAILED, and the existing FAILED ends already keep the session id on the row so the failure and the session it ran on point at each other.

**Whether WorkManager runs it again.** No: the row ends FAILED and the work reports failure, so nothing re-runs it. The alternative is `Result.retry()`. The throw came from Liftoff's own data path — the validator, the store, the prompt load — not from the coach, so a retry would run the same failing path again, which is the loop the issue is about (today the row stays RUNNING, so the app-open sweep re-enqueues it at every app open). The card shows the reason and offers Retry / Regenerate, which is how §10 already handles every other FAILED reason: the owner decides when to try again.

**Where the boundary sits: only after the row has been claimed.** A throw before `markRunning` — the row read
itself, or the claim — is left as it is: the row stays QUEUED, so it still reads as a generation that has not
started, which is a state the card, the sweep and the cancel helper all already handle, and the arms that answer
before the claim (a row that is gone, a claim that was refused, a connection that is not set up) keep the results
they return today. The alternative is to wrap the whole of `doWork`. A row nobody claimed is not stranded in the
way the issue describes: it still reads as an open generation everything else can see, and failing it would end a
generation the owner never saw start.
