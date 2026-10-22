# Tasks — GenerationWorker: repairs and the ways a generation fails

Six tasks, in order. Each ends in a commit. Tasks 1–3 change
`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` and add cases to the existing
`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, each building on what the task before it
left behind; task 4 is test-only; task 5 is the two ARCHITECTURE.md rows; task 6 is the whole-suite gate.

The ten test names below are exactly the names `verify.sh` looks for — write them with those spellings, in
`GenerationWorkerTest`, and read the comment above each name in `verify.sh` for what the test must show. Reuse
the class's existing `@Before`/`@After`, `runWorker`, `frameFor`, `methodsSent`, `assertNothingReachedTheDaemon`,
`sortieAt`, `insertFlightPlanGeneration`, `assertAwaitingGeneration`, `eventId` and `turnWait` (30 s); do not
build a second harness or a second `AppContainer`. While iterating, compile with
`bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`; gate with one cold run of this class,
`bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.work.GenerationWorkerTest" --no-build-cache`,
run detached into a log under `app/build/` with an `EXIT=$?` sentinel and no pipe. Commits follow CLAUDE.md:
`area: lowercase summary`, named files staged, never `git add -A`.

- [x] The FAILED-and-stop helper, and the permanent ends of a turn
  `GenerationWorker.kt`: one private helper carrying every permanent end — `markFailed(id, message,
  clock.millis())`, then `stopCoachSession` when a session exists for this generation (the last id
  `onSessionId` reported, or the row's stored one; no id ⇒ no stop), then `Result.failure()`; a stop that
  throws must not change the outcome already decided. Route through it: a turn ending `TurnEnd.Blocked`
  ('coach needed input'), `StoreOutcome.Refused` (the store's own reason), and a `DaemonException` whose
  `isPermanent` is true in `runGeneration`'s catch (the daemon's own message) — the catch splits permanent
  from retryable, and a non-permanent `DaemonException` still retries. Everything else in
  `storeFinishedTurn` keeps returning `Result.retry()`, and `finally { client.close() }` still covers every
  path. No Room schema change.
  Tests: `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput`,
  `aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession`,
  `aValidReplyForASortieThatHasMovedOnFailsTheGenerationWithTheStoresOwnReason`. The moved-on case seeds
  `onAppOpen()` + `confirm(missionId)`, `insertFlightPlanGeneration` for the current `PENDING` sortie (check it
  with `assertAwaitingGeneration`), then `MissionManager.scrub` that sortie before `runWorker`, so the store
  refuses with `"sortie is SCRUBBED"` — see the map. All three show the single `rho.session.stop` frame the
  helper sends for the session that exists.
  Done when those three pass and the class's seven existing tests still pass in one cold run of
  `GenerationWorkerTest`.

- [x] The protocol major-version mismatch
  `GenerationWorker.kt`: the `ProtocolMismatch` arm of the connection `when` (today every non-`Connected`
  outcome falls into `else -> Result.retry()`) goes through the FAILED-and-stop helper with its own message
  builder — 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x' built from the daemon's major, word for
  word the sentence a user reads in Mission Control's Test connection; when `ProtocolMismatch.daemonVersion`
  is empty or names no numeric major, the refusal's own `message` instead. No session is opened and nothing is
  stopped, because the check uses its own socket. Keep `ui/control/ConnectionViewModel.kt`'s file-private
  `mismatchRow` untouched and do not widen it. The other non-connected outcomes (`NotConfigured`,
  `Unreachable`, `AuthenticationRejected`) still retry.
  Tests: `aProtocolMismatchFromARefusedHelloFailsTheGenerationWithoutOpeningASession`,
  `aProtocolMismatchFromAnAcceptedHelloFailsTheGenerationWithoutOpeningASession`,
  `aProtocolMismatchThatNamesNoVersionFailsTheGenerationWithTheDaemonsOwnMessage`.
  Done when those three pass and `GenerationWorkerTest` is green in one cold run of the class.

- [x] The repair loop
  `GenerationWorker.kt`: on `CoachReplyResult.Invalid`, `bumpAttempt(id)` first, then another `runCoachTurn`
  wrapped in `savingProgressTo` on the session id and cursor the previous turn reported, with
  `CoachPromptBuilder.repairPrompt(errors)` and client_id `<generationId>-<attempt>` built from the attempt
  read *after* the bump (the fake dedupes by `client_id`, so reusing the old one plays no turn). The cap is
  the row's `attempt` — at most two repairs for the generation, so a row already at 2 asks no more. A repair
  turn's end is judged exactly like the first turn's: blocked fails, paused/error/timed out retry, a finished
  turn is validated again. Still invalid at the cap ⇒ the FAILED-and-stop helper with 'invalid output: ' then
  the last turn's validator error lines joined with '; ', each word for word, and nothing stored. Per the
  plan's Decisions, a finished turn with no assistant text is handed to the validator like any other reply
  rather than retried (no `verify.sh` name covers it; it is a reviewed decision). Do not persist the repair
  prompt.
  Tests: `anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds`,
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing`.
  Done when both pass and the class is green in one cold run of `GenerationWorkerTest`.

- [x] The outcomes that still retry
  Test-only: `State("paused")` and `State("error")` each, asserting `Result.retry()`, the row still `RUNNING`
  with the session id and cursor that turn reported still on it, `error` still null, `attempt` unchanged, and
  no `rho.session.stop` frame. Expect no production change; if one is needed it is only that the retry path
  leaves the row resumable, and it belongs in this commit.
  Tests: `aPausedTurnRetriesAndLeavesTheRowRunningWithItsSessionAndCursor`,
  `aTurnThatEndsInErrorRetriesAndLeavesTheRowRunningWithItsSessionAndCursor`.
  Done when both pass in one cold run of `GenerationWorkerTest`.

- [x] ARCHITECTURE.md's two rows
  The M3 milestone row (line 82): its Status cell stops reading "Not started — … the worker does not" and says
  the worker's core flow is built, naming resilience (repairs and retries), cancellation, supersede and app
  wiring/enqueueing as still to come, with a "still to come"-type marker. The `com.liftoff.app.work` area-map
  row (line 36): drop the now-false "the repairs and failure outcomes are not written yet"; "nothing enqueues
  it yet" stays. Keep any "not written/built/started" wording away from `GenerationWorker` adjacency in both
  cells.
  Done when the two checks in `verify.sh`'s section 4 pass — read the M3 row with
  `grep -m1 -E '^\|[[:space:]]*M3[[:space:]]*\|' ARCHITECTURE.md` and the work row with
  `grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work' ARCHITECTURE.md` and apply those greps' rejections to the
  cells they read (field 4).

- [x] The whole gate, once
  Run the CI gate detached on the finished tree: `timeout 1800 bash gradlew.sh :app:assembleDebug
  :app:testDebugUnitTest --no-build-cache` into a log under `app/build/` with an `EXIT=$?` sentinel and no
  pipe. A hang that writes no `TEST-*.xml` (the build kills a test task at five minutes) is re-run on the same
  tree, not fixed; the known flake is `doneBodyweightSetStepperShowsBW`. Read the verdict out of the reports,
  not out of `BUILD SUCCESSFUL`.
  Done when the reports show zero failures, errors and skipped, over the baseline the map measured on the
  untouched tree (593 tests) plus the ten new ones, `GenerationWorkerTest`, `CoachTurnRunnerTest`, `DaemonConnectionCheckTest`, `ConnectionViewModelTest`,
  `MissionManagerOutlineTest`, `MissionManagerPlanTest` and `CoachPromptGoldenFileTest` all ran green, and
  `git diff --quiet 8936b90 -- app/schemas` reports nothing.

## Blockers from the final review

- [ ] A refused stop after a successful store must not turn the SUCCEEDED row into FAILED
  GenerationWorker.kt:257-259 calls markSucceeded and then stopCoachSession without a guard. If the daemon refuses that stop with a permanent code, the DaemonException reaches the catch at lines 127-138. That catch calls failGeneration, and markFailed has no status guard, so it overwrites the SUCCEEDED row with FAILED and the daemon's message. The outline or plan has already been stored by then. failGeneration also sends a second stop frame. The case is likely: INVALID_TRANSITION and SESSION_NOT_FOUND are both in RpcCodes.PERMANENT, and storeFinishedTurn accepts a turn that ended `completed`, so the daemon could refuse to stop that session as an invalid transition. If the refusal is not permanent, the run returns Result.retry() for a generation that already succeeded. The plan's own Risks section says a stop that throws must not change the outcome already decided, and failGeneration follows that rule, but the success path doesn't. Fix: treat a failed stop on the success path the same way failGeneration does, and still return Result.success(). Add a GenerationWorkerTest test that sets refuseMethod["rho.session.stop"] to a permanent code on the success path. It should expect Result.success(), the row SUCCEEDED with error null, the reply stored, and exactly one stop frame.
