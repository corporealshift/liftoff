# Plan

## Approach

`error` joins the permanent ends in the worker's end-of-turn decision. `CoachTurnRunner` already reads the daemon's `state_change` and knows its `reason`, but `TurnOutcome` drops it, so the runner carries it and `storeFinishedTurn` hands it to `failGeneration` — which already writes the row FAILED with a reason, gives the session up, and leaves a row the user cancelled holding its own outcome. The arm that retries narrows to the ends it can actually get anywhere with — `paused`, and the worker's own timeout. Nothing else moves: the row schema, the queue, the Launchpad and the Retry path are as they are, because a FAILED row is already what the card draws as a failure with Retry, and Retry already inserts a fresh row that opens a new session.

## Files

- `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` — `TurnOutcome` carries the ending `state_change`'s `reason`, which the runner already reads and currently drops.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — `TurnEnd.Error` goes to `failGeneration` with that reason; the arm that hands the session back and retries is narrowed to the ends that resume. The KDoc at :207-211 and the comment at :326 restate the rule they now describe wrongly.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — rewrite `aTurnThatEndsInErrorRetries…` (:1307) into the FAILED case; add the cancelled-mid-turn-then-error case.
- `app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt` — one case: an `error` end is reported as `TurnEnd.Error` carrying the daemon's reason.
- `DESIGN.md` §7.3 step 5 (:331-332) — the line that calls `error` a retryable failure is split into `paused` (retry) and `error` (FAILED with the reason).
- No change to `ARCHITECTURE.md`: its `com.liftoff.app.work` row already reads "ending the row FAILED with its reason" (see map).

## Order

1. `CoachTurnRunner`: `TurnOutcome` carries the ending `state_change`'s reason, with the one new `CoachTurnRunnerTest` case that proves it. Build and the two work-package test classes green.
2. `GenerationWorker`: `TurnEnd.Error` goes to `failGeneration` with that reason, and the retry arm is narrowed to the ends that resume. Rewrite the existing error test into the FAILED case and add the cancelled-mid-turn case. Green.
3. `DESIGN.md` §7.3 step 5, then the brief's gate once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

- A turn that ends `error` ends the generation for good: `Result.Failure`, the row FAILED carrying the daemon's own `reason` (the scripted string, not an invented one), `finishedAt` stamped, the session id kept, nothing stored, exactly one `rho.session.stop` and no `rho.session.resume`. (worker test — the rewrite of :1307)
- A row the user cancelled mid-turn that then meets an `error` keeps CANCELLED and the error it carried, and the worker ends as done. (worker test, the shape already used for `blocked` at :997)
- A turn that ends `error` is reported as `TurnEnd.Error` carrying the daemon's reason, so the worker has the session's own words to write. (turn test)
- Unchanged and still green: a `paused` end hands the session back once and retries with the row RUNNING at that cursor (:1215, :1264, and the two-run resume at :587); a turn with no end inside its limit still reports `TimedOut` (:549); `blocked` still fails with "coach needed input" (:952).

## Risks

- The brief's "only `Paused` (and a dropped connection)" would sweep `TimedOut` — Liftoff's own 20-minute clock — into the permanent arm, turning a slow coach into a failed generation. The work keeps `TimedOut` on the resume-and-retry arm and names it in the PR. It cannot be tested at the worker level (`TURN_LIMIT` is not injectable and the class's own wait is 30 seconds), so the guard is the runner test at :549 plus the explicit arm.
- rho may refuse `rho.session.stop` on a session already in `error`. `giveUpSession` already swallows every refusal and the row's outcome is decided before the stop is attempted, so the test proves the frame was sent, not that the daemon accepted it.
- Step 2 rewrites an existing assertion rather than adding one, so the gate's diff shows a test whose expectation flipped. The PR has to say that plainly, or it reads like a test was weakened.
- This fixes turns that end `error` from now on. A row that is already stuck — its stored cursor past an `error` event the runner then ignored — keeps meeting that ignored event, so it still waits out its limit and retries. See Decisions.

## Decisions

**What the user sees when a coach turn ends in `error`: fail now, or keep trying.**
The plan ends the row FAILED on the first `error`, storing the session's own reason, so the Launchpad shows the failure state it already draws — the daemon's words as the status line, plus Retry. The alternative is a bounded retry, reusing the existing attempt ceiling so a transient daemon hiccup can self-heal. Failing immediately is what a user would expect because rho treats `error` as terminal: no later attempt can succeed, and each one costs a 20-minute worker window during which the card keeps saying "Coach is planning…". Today the card says that forever, and Retry is refused because `MissionManager.retryOutline` only accepts a FAILED row.

**What Retry does after an `error` failure: reuse the dead session or start a fresh one.**
The plan keeps the retry path as it is — a new QUEUED row with no session id and no cursor, so the next attempt opens a new rho session — and does not carry the dead session forward. Resuming is what the retry arm is for elsewhere, and it is exactly what rho refuses here: `Manager.Resume` takes only a paused session, so reusing the id reproduces the hang. A user tapping Retry expects a new attempt, not the same dead conversation replayed.

**A cancelled or superseded generation must not be swept up by the new rule.**
Only a genuine `error` turn end marks a row FAILED. A generation the user cancelled, or one a newer generation replaced, keeps the outcome and the message it already decided — `failGeneration` already refuses to write over a CANCELLED row, and the plan does not change that. The alternative is to treat every end that is neither Idle nor Completed the same way, which is the current bug's shape. Nobody should see "generation failed" for something they asked to stop.

**A turn that runs out of Liftoff's own 20-minute clock stays on the retry path.**
The brief says only `Paused` and a dropped connection should be handed back, but `TimedOut` — the worker's own limit, not a verdict from the daemon — stays there too, with the session and cursor kept so the next attempt continues the same turn. Treating it like `error` would fail a coach that is merely slow, and the limit is already documented as "just an early retry". A slow coach should still end with a plan, not with a failure the user has to ask for again.

**Rows already stuck from the old rule are not unstuck by this plan.**
A row whose stored cursor already sits past an `error` event the runner ignored keeps meeting it: `CoachTurnRunner.alreadyActedOn` (:125) treats an `error` at or behind the handed cursor as one the earlier attempt acted on, so the attempt finds no end and waits out its 20-minute limit again, and WorkManager retries it. Unsticking those rows means dropping `Error` from that check, which is a second change in a second file and goes past what the brief asks for. It is left out; the owner can ask for it in review if the cards already stuck on a real phone matter more than the size of this change.

**What the row says when the daemon's error names no reason.**
The row carries the daemon's words when it has any; when the `state_change` names none, the plan writes a fixed sentence naming the coach's failure rather than leaving the row blank and letting the card's existing "…generation failed" fallback speak. The row is the record of what happened, and the file already writes fixed sentences for the ends nobody named ("coach unreachable", "coach needed input").

**`DESIGN.md` §7.3 currently says `error` is a retryable failure, and the plan changes that line.**
The alternative is to leave the design document stating the opposite of the code. The issue is precisely that the design's assumption about rho is wrong, so the spec is where the correction belongs; leaving it would mean the next session reads the same wrong rule off the same page.
