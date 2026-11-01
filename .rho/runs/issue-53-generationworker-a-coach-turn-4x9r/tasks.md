# Tasks — issue #53 (a coach turn that ends `error` retries forever)

Four tasks, in this order. 1 gives a turn back the reason it ended for and stops the runner treating an
`error` behind the stored cursor as already dealt with; 2 uses both in the worker, which is the brief's fix;
3 proves the new rule stays narrow enough not to sweep up a generation the user cancelled; 4 puts the design
document in line with the code and runs the brief's gate over the whole suite.

Each task ends in its own commit. Scratch scripts and logs go under `app/build/`, never under `app/src/test`.

**What this run's check is.** `.rho/runs/issue-53-generationworker-a-coach-turn-4x9r/verify.sh` (rewritten at
`e51b66b`, 218 lines) runs the CI gate — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
--no-build-cache`, whole suite, up to 3 attempts retried only on the hang / build fault /
`doneBodyweightSetStepperShowsBW` — and then names exactly **one** test that does not exist yet:
`aTurnThatEndsInErrorFailsTheGenerationWithTheDaemonsReason`. Task 2 writes it under that exact name. The
rewrite dropped the other two test names and all ten class checks, so those tests are the plan's own: they
are written by tasks 1 and 3 and are proved by the whole-suite gate, not by a name in the script. No UI test
is named and no document row is grepped.

- [x] 1. The turn runner reports the reason a turn ended for, and an `error` behind the cursor is an end
  - Covers `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` only: `TurnOutcome` (:50) carries the
    ending `state_change`'s `reason` next to the end it already reports (the `STATE_CHANGE` arm of `handle`
    :144-155 is where the reason is read, :221 is the one place a `TurnOutcome` is built); and
    `alreadyActedOn` (:125) keeps only `Paused`, with its KDoc saying why — an `error` is never resumed any
    more, so no earlier attempt can have acted on one, and the check has to come back if `error` ever becomes
    resumable. The `paused` half of that check stays exactly as it is.
  - Writes two new cases in `app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt`, each with its own
    fresh `clientId` (the class has used `turn-1` … `turn-11`):
    - a turn whose `state_change` is `error` reports `TurnEnd.Error` carrying the daemon's own reason string.
    - `aCursorHandedInPastAnErrorReportsThatErrorInsteadOfTimingOut` — the stuck-row case the issue is about.
      Copy `twoEndingStateChangesAfterThePromptStopAtTheFirst` (:451): call `rho.session.send_prompt` yourself
      under the client_id, read `promptEventId(clientId)`, `daemon.logEvent` an assistant line and then an
      `error` `state_change` with a reason the test sets, and run the turn with the SAME client_id (the re-send
      is deduped) and a cursor past that `error` event. Assert `TurnEnd.Error` carrying that reason, not
      `TimedOut`. Hand in `shortLimit` (500 ms) so a regression fails this test fast instead of hanging it.
  - Done when a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
    "com.liftoff.app.work.CoachTurnRunnerTest" --tests "com.liftoff.app.work.GenerationWorkerTest"` prints
    `BUILD SUCCESSFUL`, `CoachTurnRunnerTest`'s report shows both new names with 0 failures, and
    `GenerationWorkerTest`'s report shows 0 failures — its paused-resume and refused-resume cases are what
    prove the `paused` half of `alreadyActedOn` still works. Commit.

- [x] 2. The worker ends a generation for good when the turn ends `error`
  - Covers `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: in `storeFinishedTurn` (:221) the
    `TurnEnd.Error` end goes to `failGeneration` with the reason the turn reported; when the `state_change`
    named no reason, a fixed sentence naming the coach's failure, in the style of the file's other written
    sentences (`"coach needed input"` at :245, `COACH_UNREACHABLE` at :471). The hand-back-and-retry arm at
    :249-252 narrows to the ends that can still get somewhere: `Paused` and the worker's own `TimedOut` — the
    plan's Decision that a merely slow coach must not become a failed generation, since `TURN_LIMIT` is
    Liftoff's clock and not a verdict from the daemon. The KDoc at :207-211 and the comment at :326 both state
    the rule being changed, so they are rewritten with it. `failGeneration`, `giveUpSession`,
    `handSessionBack`, the row schema and the queue are not touched.
  - Writes the one test this run's check names, `aTurnThatEndsInErrorFailsTheGenerationWithTheDaemonsReason`,
    as the rewrite of `aTurnThatEndsInErrorRetriesAndLeavesTheRowRunningWithItsSessionAndCursor` (:1307) in
    `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — same scripted turn (an `Assistant` line,
    then `DaemonScript.State("error", "the coach's turn fell over")`), flipped expectation. Assert
    `Result.Failure`; the row FAILED with `finishedAt` stamped and its `nabuSessionId` kept; `row.error` is the
    scripted reason string, not null and not an invented sentence; nothing stored (mission still DRAFT, no
    sorties); `assertSessionGivenUpOnce`; and no `rho.session.resume` frame at all, which is the half that
    stops the endless retry. Copy the shape of `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput`
    (:952). Leave the paused-resume (:1215), refused-resume (:1264) and two-run-resume (:587) cases alone.
  - Done when a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
    "com.liftoff.app.work.GenerationWorkerTest" --tests "com.liftoff.app.work.CoachTurnRunnerTest"` prints
    `BUILD SUCCESSFUL`, `GenerationWorkerTest`'s report contains
    `aTurnThatEndsInErrorFailsTheGenerationWithTheDaemonsReason`, the old `…RetriesAndLeavesTheRowRunning…`
    name is gone from every report, and both reports show 0 failures. Commit.

- [ ] 3. A generation the user cancelled mid-turn keeps its own outcome when the turn then ends `error`
  - Test-only, no main-source change: this is the plan's Decision that only a genuine `error` turn end marks a
    row FAILED, and that a cancelled or superseded generation keeps the outcome it already has.
    `failGeneration` already refuses to write over a CANCELLED row; this proves the new arm reaches that rule
    instead of sweeping the row up.
  - Writes `aGenerationCancelledMidTurnThatEndsInErrorStaysCancelledAndKeepsItsError` in
    `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, copying
    `aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError` (:997): `insertOutlineGeneration`,
    an `Assistant` line, then a `DaemonScript.Act { … }` that re-reads the row, `update(row.copy(error =
    carriedError))` and `markCancelled(generationId, now)` while the turn is still open, then
    `DaemonScript.State("error", …)` with a reason that is NOT the carried one. Cancel through the DAO, never
    `container.generationCanceller()`. Assert `Result.Success`, the row still CANCELLED, `row.error` still the
    carried error, `finishedAt` non-null, mission still DRAFT with no sorties, `assertSessionGivenUpOnce`.
  - Done when a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
    "com.liftoff.app.work.GenerationWorkerTest"` prints `BUILD SUCCESSFUL` with that name in the report and 0
    failures — and the case is shown to bite: one token in task 2's new arm (`TurnEnd.Error` changed to
    `TurnEnd.Idle`, so an `error` end falls through to the hand-back arm and the worker retries) must turn this
    test red. Keep a byte-copy of `GenerationWorker.kt` under `app/build/` before the mutation and the red run's
    log under `app/build/`, then `cp` the file back and commit only the test. Commit.

- [ ] 4. The design document says what the code now does, then the brief's gate
  - Covers `DESIGN.md` only: §7.3 step 5 (:331-332) splits the single "**`paused` or `error`:** a retryable
    failure" line into `paused` (retry, resuming the session first) and `error` (the generation FAILED with the
    session's own reason), marked inline in the section the way that section's own convention requires; and a
    dated bullet — "**2026-10-31 — …**", what changed, when, why — goes at the top of the newest-first list
    under "Amendments after approval" (:783-786), naming that rho treats `error` as terminal, so its resume is
    refused and the retry looped forever. Keep §7.3's existing `nabu.` method names as the 2026-10-20 rename
    amendment left them, so the rewritten line reads consistently with the lines beside it. No
    `ARCHITECTURE.md` change: its `com.liftoff.app.work` row already reads "ending the row FAILED with its
    reason".
  - Done when a cold `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` — the gate
    the brief names — prints `BUILD SUCCESSFUL` with `:app:testDebugUnitTest` executed rather than
    FROM-CACHE/UP-TO-DATE, and the reports under `app/build/test-results/testDebugUnitTest` total 0 failures,
    0 errors, 0 skipped over the whole suite (736 tests at `ad8a4bc`, plus the cases tasks 1-3 added). Commit.
