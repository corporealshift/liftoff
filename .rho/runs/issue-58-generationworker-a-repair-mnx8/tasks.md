# Tasks — issue 58: a repair prompt to a `completed` session is refused

Five tasks, in this order. Each is one session and ends in its own commit; task 2 needs task 1's prompt
shape, task 3 checks task 2's fix at the other end of the repair loop, task 4 writes the documents that
describe what tasks 1–2 shipped, task 5 is the whole gate.

`verify.sh` is fixed and is the definition of done. It names **3 tests that do not exist yet**; each is
written by exactly one task below, under that exact name (the names are long — copy them character for
character). It also names 4 existing tests that must keep passing, and requires 9 classes to have run with
everything in them passing; they pass today, so they are the must-not-disturb net. Baseline from this run's
own gate run on this branch's code (read from `app/build/test-results/testDebugUnitTest/TEST-*.xml`, which
that run left in place, so a work step need not re-measure it): **775 testcases in 93 `TEST-*.xml` reports,
0 failures / 0 errors / 0 skipped**, cold with `--no-build-cache`; the three new names appear in no report
and the four existing ones all pass. The gate on this machine is
`JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest --no-build-cache`
(`gradlew.sh` pins Windows paths); a single class is `--tests "com.liftoff.app.work.GenerationWorkerTest"`.
Scratch files live under `app/build/`; never put a scratch test class under `app/src/test`. Do not touch
`verify.sh`.

- [x] 1. The fresh-session repair prompt shape, in `coach/`
  `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt`: a second repair shape beside
  `repairPrompt` (48), built from the original prompt text, the refused reply and the validator's error
  list — a session with no history cannot be pointed at the two-line "Your reply was not valid:" prompt,
  because it has no reply to look at. Pure Kotlin, no Android imports (ARCHITECTURE.md invariant 2), same
  byte-identical-for-same-inputs rule the class KDoc states. `prompt()` and `repairPrompt()` are **not**
  edited or replaced: `CoachPromptGoldenFileTest` compares the three §7.4 prompts byte for byte against
  `app/src/test/resources/prompts`, and the original prompt's text is what the new shape carries. Nothing
  calls the new shape yet, so no production caller changes in this task.
  Test required by `verify.sh`, in `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt`
  (plain JVM test, inputs written by hand in that file):
  `aFreshSessionRepairPromptCarriesTheOriginalPromptTheRefusedReplyAndEveryError` — carries the original
  prompt's text, the refused reply's text, and every validator error line verbatim and in order (§7.6);
  asks for the corrected JSON object; two calls with the same inputs give identical text; is neither the
  two-line `repairPrompt` alone nor the original prompt alone. Read the errors out of a real
  `CoachReplyValidator.validate` run the way `repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson`
  (484) does, so the lines are the validator's wording and not hand-written.
  Done when: a cold `--tests` run of `CoachPromptBuilderTest` **and** `CoachPromptGoldenFileTest` is BUILD
  SUCCESSFUL, the new name is in `CoachPromptBuilderTest`'s report as passing, and
  `repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson` still passes. Commit.

- [x] 2. The Invalid arm picks the session from how the refused turn ended — and the test that proves it
  `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`, the `CoachReplyResult.Invalid` arm of
  `storeFinishedTurn` (299-331): when `refused.end == TurnEnd.Completed` the repair runs on a **new**
  session — `runCoachTurn` with a null `sessionId` (which is already what creates one, `CoachTurnRunner.kt`
  188 / 244) and the `CoachSessionOptions` this worker already holds (150-154), so the replacement carries
  the same workspace, labels and description — and with **no cursor carried over** from the refused turn
  (a brand-new session never had those events; the real daemon answers `CURSOR_UNKNOWN`). `TurnEnd.Idle`
  keeps today's same-session repair exactly as it is: same session id, same `refused.cursor`, same
  `CoachPromptBuilder.repairPrompt(errors)`. The original prompt text (built once at 160 via `promptFor`)
  has to be carried into `storeFinishedTurn` so a fresh-session repair can send it — carry it in rather
  than rebuilding it there with `promptFor`, which re-reads Room. The fresh-session repair uses task 1's
  shape. One more line: the repair turn's `onSessionId` must also update `reportedSessionId` (146), or a
  permanent refusal during a fresh-session repair would make the catch (172-186) give up the session rho
  already ended and leave the new one running. Update the class KDoc, `storeFinishedTurn`'s KDoc ("handed
  back to the same session", 247) and the "Held here because a repair turn runs on the same session"
  comment (149) to match what the code now does.
  Test required by `verify.sh`, in `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, in the
  style of `anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds` (771):
  `aRefusedTurnThatEndedCompletedRepairsOnANewSessionAndThenTheGenerationSucceeds` — first turn scripted
  `DaemonScript.Assistant(CoachFixtures.fixture(CoachFixtures.BAD_JSON))` then `DaemonScript.State("completed")`,
  second turn a valid `CoachFixtures.OUTLINE` reply; **two** `rho.session.create` frames with two different
  session ids (the fake hands out one id until a test hands it another — set `daemon.sessionId`, a
  `@Volatile var`, from a `DaemonScript.Act` at the end of the first turn); the first prompt to the first
  session, the repair prompt to the second under client_id `<generationId>-1`; the `rho.session.events_after`
  frame after that prompt carries no `last_event_id` from the refused turn; the repair prompt carries the
  original prompt's text, the refused reply and every error line from
  `CoachFixtures.errorsOf(CoachFixtures.BAD_JSON, CoachFixtures.OUTLINE_CONTEXT)`; `Result.Success`, attempt
  1, row SUCCEEDED with `finishedAt`, outline stored (mission ACTIVE with a current sortie), row's
  `nabuSessionId` the **second** session; the session given up at the end of the store is that second
  session; the second create carries the same workspace, labels and description as the first. Note
  `frameFor` (2103) fails unless exactly one such frame was sent — a two-session case must use
  `firstFrameFor` (2115) or index `daemon.framesFor("rho.session.create")`.
  Write the test first and watch it fail on the one-create-frame assertion before changing the worker.
  Done when: a cold `--tests "com.liftoff.app.work.GenerationWorkerTest"` run is green with the new name in
  its report, and `anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds`,
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing` and
  `aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession` (1102) still pass with
  their exactly-one-create assertions intact. Commit.

- [x] 3. A `completed` turn still gets the whole repair budget
  Tests only, in `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`, against task 2's arm —
  no production change expected, since the cap is already the row's `Generation.attempt` and `MAX_REPAIRS`
  is 2 (506). Name exactly:
  `aGenerationThatRunsOutOfRepairsOnCompletedTurnsFailsWithTheLastErrorsAndNotTheDaemonsRefusal` — every
  turn scripted to end `DaemonScript.State("completed")` on an invalid reply (`CoachFixtures.BAD_JSON` then
  `CoachFixtures.SCHEMA_VIOLATION`, so the last turn's errors are distinguishable from the first's; the fake
  replays its last scripted turn), each turn on its own session: one `rho.session.create` per turn with a
  distinct id, three prompts and no fourth, `Result.Failure`, attempt 2, row FAILED with `"invalid output: "`
  plus the **last** turn's validator error lines joined with `"; "` — never the daemon's `INVALID_TRANSITION`
  text, never "session has ended", never the first refused reply's errors — mission still DRAFT with no
  sorties, and the session that produced the last refused reply given up once. Make each session id distinct
  the way task 2's case does. If this test exposes a gap in task 2's arm (a `completed` turn that stops
  repairing after one round, or an RPC message landing on the row), fix the arm in this same commit.
  Done when: a cold `--tests "com.liftoff.app.work.GenerationWorkerTest"` run is green with that name in its
  report and every earlier case in the class still passing. Commit.

- [x] 4. DESIGN.md and ARCHITECTURE.md say what the code now does
  `DESIGN.md`: amend §7.3 step 5 (334-342) inline for the split — a refused turn that ended `idle` is
  repaired in the same session, one that ended `completed` is repaired on a new session carrying the
  original prompt, the refused reply and the error list — in the same "(amended …)" style §7.3 already
  uses for the `paused`/`error` split; §10's "Invalid output" row (611) and §10's comparison-table
  Validation row "with in-session repair (≤2)" (785), both of which currently say in-session outright; and
  one entry at the **top** of "Amendments after approval" (808, newest first) dated 2026-11-20 saying what
  changed, when and why (rho refuses `send_prompt` on a `completed` session with `INVALID_TRANSITION`, a
  permanent code, so the generation failed with the daemon's message instead of the validator's errors).
  `ARCHITECTURE.md`: invariant 8 (69) — "up to 2 in-session repairs" no longer holds for every end.
  Invariant 3 (nothing in rho changes, only existing protocol methods) and invariant 2 (`coach/` is pure)
  are what the change had to respect; say nothing new about them. No code, no Gradle run.
  Done when: the amendment is the first entry in that section, §7.3 step 5 and both §10 rows no longer claim
  "same session"/"in-session" for a `completed` end, invariant 8 reads to match, and `git show --stat` for
  this commit lists only `DESIGN.md` and `ARCHITECTURE.md`. Commit.

- [x] 5. The whole gate, and the sweep for anything that read the old rule
  Run `bash .rho/runs/issue-58-generationworker-a-repair-mnx8/verify.sh` (it is not executable) and read its
  output: the three new names, the four must-keep-passing names, the nine class checks, and the closing
  "Issue 58 done". Then sweep `app/src` for anything still assuming a repair always runs on the refused
  turn's session — other `storeFinishedTurn`/`runCoachTurn` call sites, tests that script a `completed` end
  and assert one create frame, `GenerationCanceller` and `LaunchpadState`, which read the row's
  `nabuSessionId` that a repair now moves. Fix whatever the gate or the sweep exposes, in this commit.
  `com.liftoff.app.ui.LiftoffShellTest` is the recorded full-suite flake (one `waitFor` expiring at random,
  passing on a rerun of the identical tree): rerun before believing a failure there, and do not fold it into
  this issue. Report the out-of-scope finding for the pull request rather than fixing it: the same refusal
  still exists on the **resume** path — a row whose stored `nabuSessionId` completed while the worker was
  down — and the copied protocol has no call that reports a session's state, which ARCHITECTURE.md invariant
  3 forbids adding.
  Done when: `bash .rho/runs/issue-58-generationworker-a-repair-mnx8/verify.sh` exits 0 and prints
  "Issue 58 done", with no `:app:testDebugUnitTest FROM-CACHE|UP-TO-DATE|NO-SOURCE` line in its log. Commit
  if this task changed any file; if the gate was green and the sweep found nothing, say so and commit
  nothing.
