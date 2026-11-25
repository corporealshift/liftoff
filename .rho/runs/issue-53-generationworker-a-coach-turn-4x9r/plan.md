# Plan

## Approach

`error` joins the permanent ends in the worker's end-of-turn decision. `CoachTurnRunner` already decodes the daemon's `state_change` (which has a `reason` field) but `TurnOutcome` drops the reason. So the runner carries it, and `storeFinishedTurn` hands it to `failGeneration`. `failGeneration` already writes the row FAILED with a reason, gives up the session, and leaves a row the user cancelled holding its own outcome. The retry arm narrows to the ends that can still get somewhere: `paused`, and the worker's own timeout. Since an `error` is now never resumed, the runner also stops treating an `error` at or behind the stored cursor as "already acted on". That one condition is what unsticks rows the old rule left waiting. The row schema, the queue, the Launchpad and the Retry path stay as they are: the card already draws a FAILED row as a failure with Retry, and Retry already inserts a fresh row that opens a new session.

## Files

- `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`: `TurnOutcome` carries the ending `state_change`'s `reason`. `alreadyActedOn` (:125) keeps only `Paused`, and its KDoc says why.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: `TurnEnd.Error` goes to `failGeneration` with that reason. The arm that hands the session back and retries is narrowed to the ends that resume. The KDoc at :207-211 and the comment at :326 now state the rule wrongly, so update them.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`: rewrite `aTurnThatEndsInErrorRetries…` (:1307) into the FAILED case, and add the cancelled-mid-turn-then-error case.
- `app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt`: two cases. An `error` end is reported as `TurnEnd.Error` carrying the daemon's reason. A turn handed a cursor already past an `error` reports that `error` instead of waiting out its limit.
- `DESIGN.md`: §7.3 step 5 (:331-332) gets an inline mark, and the line that calls `error` retryable is split into `paused` (retry) and `error` (FAILED with the reason). A dated entry goes at the top of "Amendments after approval", following that section's own convention and CLAUDE.md's rule that changes are made as amendments.
- No change to `ARCHITECTURE.md`. Its `com.liftoff.app.work` row already reads "ending the row FAILED with its reason".

## Order

1. `CoachTurnRunner`: `TurnOutcome` carries the reason, and `alreadyActedOn` drops `Error`. Add the two new `CoachTurnRunnerTest` cases. Build, and both work-package test classes green.
2. `GenerationWorker`: `TurnEnd.Error` goes to `failGeneration` with that reason, and the retry arm is narrowed to the ends that resume. Rewrite the existing error test into the FAILED case and add the cancelled-mid-turn case. Green.
3. `DESIGN.md` §7.3 step 5 and its amendment entry. Then run the brief's gate once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

- A turn that ends `error` ends the generation for good. Assert: `Result.Failure`; the row FAILED carrying the daemon's own `reason` (the scripted string, not an invented one); `finishedAt` stamped; the session id kept; nothing stored; exactly one `rho.session.stop`; no `rho.session.resume`. (Worker test, the rewrite of :1307.)
- A row the user cancelled mid-turn that then meets an `error` stays CANCELLED with the error it already had, and the worker ends as done. (Worker test, following the `blocked` case at :997.)
- A turn that ends `error` is reported as `TurnEnd.Error` carrying the daemon's reason. (Runner test.)
- A turn handed a cursor past its own prompt and past an `error` reports `TurnEnd.Error` with that reason, not `TimedOut`. This is the stuck-row case. (Runner test, using a short limit so a regression fails fast instead of hanging.)
- Unchanged and still green:
  - a `paused` end hands the session back once and retries, with the row RUNNING at that cursor (:1215, :1264, and the two-run resume at :587);
  - a `paused` at or behind the handed cursor is still treated as already acted on;
  - a turn with no end inside its limit still reports `TimedOut` (:549);
  - `blocked` still fails with "coach needed input" (:952).

## Risks

- The brief says only `Paused` and a dropped connection should be retried, which would move `TimedOut` (Liftoff's own 20-minute clock) to the permanent arm and turn a slow coach into a failed generation. The work keeps `TimedOut` on the resume-and-retry arm and says so in the PR. It can't be tested at the worker level (`TURN_LIMIT` is not injectable), so the guards are the runner test at :549 and the explicit arm.
- rho may refuse `rho.session.stop` on a session already in `error`. `giveUpSession` already swallows every refusal, and the row's outcome is decided before the stop is attempted. So the test proves the frame was sent, not that the daemon accepted it.
- Step 2 rewrites an existing assertion rather than adding one, so the diff shows a test whose expectation flipped. The PR has to say that plainly, or it looks like a test was weakened.
- Dropping `Error` from `alreadyActedOn` is only correct because `error` is never resumed any more. If a later change makes `error` resumable again, that check has to come back. The KDoc on `alreadyActedOn` should say so.

## Decisions

**Rows already stuck under the old rule are unstuck by this change.**
A row whose stored cursor already sits past an `error` event now meets that event on its next attempt and ends FAILED with the daemon's reason. It no longer waits out another 20-minute limit and retries forever. The fix is dropping `Error` from `CoachTurnRunner.alreadyActedOn`, in a file this change already edits.
Changed by review: the plan chose to leave stuck rows stuck, as a second change beyond the brief; the review chose to unstick them, because the brief's complaint is cards that say "Coach is planning…" forever, so the owner's phones already showing that card would expect it to clear. `CoachTurnRunner.kt` is already being edited, and once `error` is never resumed, "an earlier attempt acted on this error" is no longer true. Keeping the check would also hang a row whose worker died between saving the cursor and marking it FAILED.

**`DESIGN.md` §7.3 currently says `error` is a retryable failure, and the plan changes that line.**
The alternative is to leave the design document stating the opposite of the code. The design's assumption about rho is exactly what turned out wrong, so the spec is where the correction belongs. Leaving it would mean the next session reads the same wrong rule off the same page.
Changed by review: the plan chose to rewrite §7.3 step 5 in place; the review chose to also mark it inline and add a dated entry at the top of "Amendments after approval", because CLAUDE.md says design changes are made as amendments and that section's own rule is "listed here, newest first, and marked inline in the section they affect".

**What the user sees when a coach turn ends in `error`: fail now, or keep trying.**
The plan ends the row FAILED on the first `error`, storing the session's own reason. The Launchpad then shows the failure state it already draws: the daemon's words as the status line, plus Retry. The alternative is a bounded retry, reusing the existing attempt ceiling so a transient daemon hiccup can recover on its own. Failing immediately is what a user would expect because rho treats `error` as terminal: no later attempt can succeed, and each one costs a 20-minute worker window while the card keeps saying "Coach is planning…". Today the card says that forever, and Retry is refused because `MissionManager.retryOutline` only accepts a FAILED row.

**What Retry does after an `error` failure: reuse the dead session or start a fresh one.**
The plan keeps the retry path as it is: a new QUEUED row with no session id and no cursor, so the next attempt opens a new rho session. It does not carry the dead session forward. Resuming is what rho refuses here, because `Manager.Resume` takes only a paused session, so reusing the id would reproduce the hang. A user tapping Retry expects a new attempt, not a replay of the same dead conversation.

**A cancelled or superseded generation must not be swept up by the new rule.**
Only a genuine `error` turn end marks a row FAILED. A generation the user cancelled, or one a newer generation replaced, keeps the outcome and message it already has. `failGeneration` already refuses to write over a CANCELLED row, and the plan does not change that. The alternative is to treat every end that is neither Idle nor Completed the same way, which is the shape of the current bug. Nobody should see "generation failed" for something they asked to stop.

**A turn that runs out of Liftoff's own 20-minute clock stays on the retry path.**
The brief says only `Paused` and a dropped connection should be handed back. `TimedOut` stays there too, because it is the worker's own limit, not a verdict from the daemon. The session and cursor are kept so the next attempt continues the same turn. Treating it like `error` would fail a coach that is merely slow, and the code already documents the limit as "just an early retry". A slow coach should still end with a plan, not with a failure the user has to ask for again.

**What the row says when the daemon's error names no reason.**
The row carries the daemon's words when it has any. When the `state_change` names no reason, the plan writes a fixed sentence naming the coach's failure, rather than leaving the row blank and letting the card's "…generation failed" fallback speak. The row is the record of what happened, and the file already writes fixed sentences for ends with no named reason ("coach unreachable", "coach needed input").
