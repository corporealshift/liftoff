# Map — issue 58 (repair prompt to a `completed` session)

## Where the bug lives

`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` (536 lines)
- `storeFinishedTurn` (255-373): the validate/store loop. The `CoachReplyResult.Invalid` arm (299-331) checks the cap
  (`generations.get(row.id).attempt >= MAX_REPAIRS`, 302), `bumpAttempt`, then calls `runCoachTurn` with
  `sessionId = refused.sessionId`, `prompt = CoachPromptBuilder.repairPrompt(reply.errors)`,
  `cursor = refused.cursor`, `clientId = "${row.id}-$attempt"`. **This is the line the brief is about**: when
  `refused.end == TurnEnd.Completed` the session has ended, so `rho.session.send_prompt` is refused.
- Ends it already handles: `Blocked` (278), `Error` (283), `Paused`/`TimedOut` → `handSessionBack` + `Result.retry()` (289).
  `Idle` and `Completed` both fall through to validation (296) — the brief splits only the repair that follows.
- `runGeneration` (128-201): connects, builds `CoachSessionOptions` (150-154: workspace, labels, description — held
  precisely because "a repair turn runs on the same session, with the same labels and description"), runs the first
  turn under `savingProgressTo` (155-170), then calls `storeFinishedTurn`. Its `catch (dropped: DaemonException)`
  (172-186) is where today's refusal lands: `dropped.isPermanent` → `failGeneration(..., dropped.message, ...)`, so
  the row's error is the daemon's RPC text, not the validator's errors.
- The original prompt text is built once, at 160 via `promptFor` (413-420, `PromptInputLoader` + `CoachPromptBuilder.prompt`),
  and is **not** passed to `storeFinishedTurn`. A fresh-session repair needs it, so it has to be carried in (or rebuilt there).
- `savingProgressTo` (223-239): writes every session id and cursor move to the row through a channel, in order.
- `giveUpSession` (447) / `handSessionBack` (461): both swallow any refusal, so a stop of an already-ended session is harmless.
- `MAX_REPAIRS = 2` (506), `TURN_LIMIT = 20.minutes` (503), `GIVE_UP_AT_ATTEMPT = 7` (509).

`app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` (267 lines)
- `runCoachTurn` (83-237): `sessionId == null` → `createSession(client, options)` (188, 244-254: `rho.session.create`
  with `workspace`, `permission_mode: auto`, `labels`, `description`). So "new session" is already what a null id means.
- `TurnEnd { Idle, Completed, Blocked, Paused, Error, TimedOut }` (40); `TurnOutcome(sessionId, cursor, end, endReason,
  lastAssistantText)` (50-60). `stopCoachSession` (240) is the caller's, never the runner's.
- Guards that matter for a fresh session: `handle()` only counts a state_change or an assistant message with
  `event.id > promptId` (151, 162), so events belonging to an older session can neither end this turn nor become its
  reply; `alreadyActedOn` (138) applies only to `Paused`. A `cursor` handed to a session that never had those events is
  the daemon's `CURSOR_UNKNOWN`, which the runner answers with one read from the start (260-264).

## Supporting pieces

- `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` — `prompt(input)` (31-41, the eight §7.4 sections)
  and `repairPrompt(errors)` (48-50: "Your reply was not valid:" + errors + "Reply again with only the corrected JSON
  object."). Pure, no Android imports (invariant 2). Golden files cover `prompt` only.
- `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` — `CoachReplyResult.Valid` / `.Invalid(errors)`.
- `app/src/main/java/com/liftoff/app/rho/protocol/RpcCodes.kt` — `INVALID_TRANSITION = -32002`, and it is in
  `RpcCodes.PERMANENT`; `DaemonException.isPermanent` is `code in PERMANENT` (`rho/net/DaemonClient.kt` 86-92).
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — `saveSessionId` (54) overwrites `nabuSessionId` with no
  status guard, so a repair that moves to a new session leaves the row pointing at the new one; `bumpAttempt` (62);
  `markFailed` (45-49) is guarded to QUEUED/RUNNING.
- Protocol methods the copied client knows (grep of `app/src/main`): `rho.hello`, `session.create`, `subscribe`,
  `send_prompt`, `events_after`, `resume`, `stop`, `event`, `thinking`, plus `rpc.ui.ask` / `rpc.permission.request`.
  **There is no "what state is this session in" call**, so a worker cannot pre-check a stored session id.

## Tests that already exist

`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` (2167 lines, Robolectric, real socket to a scripted fake)
- Harness: `runWorker(generationId, runAttemptCount)` (1956), `promptsSent()` (2131), `methodsSent()` (2138),
  `frameFor` (2103, **fails unless there is exactly one** such frame), `firstFrameFor` (2115),
  `assertSessionGivenUpOnce(id)` (2051), `assertSessionLeftOpen()` (2061), `userMessagesFor(clientId)` (2124).
- The two repair cases the new one sits beside: `anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds`
  (771 — asserts exactly one create, both prompts in one session, `CoachPromptBuilder.repairPrompt(errors)` as the
  second prompt's content, attempt 1, SUCCEEDED) and `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing`
  (838 — three prompts, attempt 2, `FAILED` with `"invalid output: " + lastErrors.joinToString("; ")`).
  Both script `State("idle")`; both would break if the Idle path changed.
- `aGenerationCancelledMidTurnThatRunsOutOfRepairsStaysCancelledAndStoresNothing` (894) also runs the repair loop,
  using `DaemonScript.Act` to cancel the row mid-turn.
- Fixtures: `CoachFixtures.fixture(CoachFixtures.BAD_JSON / SCHEMA_VIOLATION / OUTLINE)`,
  `CoachFixtures.errorsOf(name, CoachFixtures.OUTLINE_CONTEXT)`.

`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` (429 lines) — the fake's limits, which shape the new test:
- One session id: `@Volatile var sessionId = "session-1"` (84) is what every `rho.session.create` answers with (228-237),
  and every pushed event carries it (317-328). A test that wants a *second, distinct* session id changes this field.
- One shared `log` for every session (97); `events_after` reads it by cursor only, ignoring session id (266-278), and
  answers `CURSOR_UNKNOWN` for a cursor it does not hold.
- `rho.session.send_prompt` (246-264) never refuses on session state; it dedupes by `client_id` and plays the next
  scripted turn. `refuseMethod[method]` (91, 152-153) refuses a whole method, not one call.
- `rho.session.stop` (280-286) appends `state_change to=completed reason=stopped`; `nextTurn()` (399-403) repeats the
  last script once the list runs out; `DaemonScript.Act` (52) runs an action inline in a turn.

## Who reads the row's session id

- `ui/launchpad/LaunchpadState.kt` (203, 229, 253): `newestGenerationSessionId = row?.nabuSessionId` — the card's link
  into rho. So moving the row to the repair session is visible to the user.
- `work/GenerationCanceller.kt` (46): a user cancel stops `cancelled?.nabuSessionId`. After a repair moves the row, the
  cancel stops the live session, not the ended one.
- `storeFinishedTurn` ends by `giveUpSession(client, turn.sessionId)` (368) — the session that produced the accepted
  reply, which for a fresh-session repair is the new one.

## Docs that state the rule being changed

- `DESIGN.md` §7.3 step 5 (334-342): "`idle` or `completed` … Invalid, with fewer than 2 repairs used: … send a repair
  prompt in the **same session**". This is the sentence the fix contradicts. §7.3 already carries inline
  "(amended 2026-10-31)" marks for the `paused`/`error` split.
- `DESIGN.md` §10 (611): "Invalid output | Up to 2 in-session repairs, then FAILED with the last error list visible".
- `DESIGN.md` "Amendments after approval" (808): newest first, "what changed, when, and why".
- `ARCHITECTURE.md` invariant 8 (69): "The phone validates every coach reply, with up to 2 in-session repairs."
  Invariant 3 (64): nothing in rho changes, only existing protocol methods. Invariant 2 (63): `coach/` is pure Kotlin.
- `DESIGN.md` §7.6 (514): "The error list is sent back verbatim in repair prompts."

## Confirmed at the tasks step (line numbers re-read, not inferred)

- `CoachTurnRunner.kt`: `TURN_ENDS` (62-68) maps `"completed"` → `TurnEnd.Completed`, so
  `DaemonScript.State("completed")` is what makes a turn end `Completed`. `runCoachTurn` calls
  `onSessionId(session)` (190) on **both** the create and the resume path, so a fresh session's id already
  reaches `savingProgressTo` and the row; only `reportedSessionId` in the worker does not.
  `eventsAfter` (256-267) sends `rho.session.events_after` through `EventsAfterParams(sessionId, cursor)` —
  a null cursor is what makes the frame carry none, and `CURSOR_UNKNOWN` gets one retry with no cursor.
- `FakeDaemon.kt`: the cursor key in an `events_after` frame is **`last_event_id`** (266-278), so "the new
  session was read with no cursor carried over" is an assertion on that key being absent.
  `EventsAfterParams` (`rho/net/DaemonClient.kt` 319-322) has `last_event_id` defaulting to null, and
  `RhoJson` (`rho/protocol/Events.kt` 22-27) sets `explicitNulls = false`, so a null cursor really is an
  **absent key** in the frame rather than `"last_event_id": null`.
  `rho.session.create` (228-237) answers `session_id` from `sessionId` at the moment of the call, so a
  `DaemonScript.Act` that sets `daemon.sessionId` mid-run gives the next create a distinct id.
  `rho.session.stop` (280-286) appends `state_change to=completed reason=stopped` — a stop is not a turn end
  the worker chose. `nextTurn()` (399-403) replays the last scripted turn once the list runs out.
- `GenerationWorkerTest.kt` helpers, exact lines: `runWorker` 1956, `insertOutlineGeneration` 1990,
  `assertSessionGivenUpOnce` 2051, `assertSessionLeftOpen` 2061, `frameFor` 2103 (needs exactly one),
  `firstFrameFor` 2115, `userMessagesFor` 2124, `promptsSent` 2131, `methodsSent` 2138.
  `aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession` is at 1102.
- `CoachFixtures.kt`: `BAD_JSON` 24, `SCHEMA_VIOLATION` 26, `OUTLINE_CONTEXT` 38, `fixture` 60,
  `errorsOf` 93. Fixtures live under `app/src/test/resources/fixtures`, golden prompts under
  `app/src/test/resources/prompts`.
- `CoachPromptBuilderTest.kt` is a plain JVM test (no Robolectric) with the inputs written by hand at the top
  of the file (`profile` 23, `equipment` 30, `week` 37); `repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson`
  is at 484 and reads its error lines out of a real `CoachReplyValidator.validate` run rather than writing
  them — the new prompt test should do the same.
- `GenerationWorker.kt`: `MAX_REPAIRS = 2` at 506; `promptFor` 413-420; `failGeneration` 430-444;
  `giveUpSession` 447; `handSessionBack` 461.
- Docs lines confirmed: `DESIGN.md` §7.3 step 5's "Invalid … in the **same session**" sentence is 338-340;
  §10's "Invalid output" row is 611; §10's comparison-table row 10 "Validation | On the phone, with
  in-session repair (≤2)" is 785; "Amendments after approval" heading 808, newest entry (2026-11-02) 812.
  `ARCHITECTURE.md` invariants table 60-68: invariant 2 (63), 3 (64), 6 (67), 8 (69).

## Gate

`gradlew.sh` pins `C:/Users/corpo/android-toolchain/...` — Windows paths, so it does not run on this machine:
`bash gradlew.sh --version` fails here, and `./gradlew` fails too unless `JAVA_HOME` is set. Locally the same
toolchain is used directly: `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest` (~1m08s warm, ~1m08s with the
daemon already up; 46 actionable tasks). `LiftoffShellTest` is the known flake in a full-suite run — rerun before
believing it.

## The gate script (`.rho/runs/issue-58-generationworker-a-repair-mnx8/verify.sh`)

Run it with `bash` (it is not executable). Measured on the untouched tree: exit 1 in ~1m08s, with
`--- reports: 775 tests, 0 failures, 0 errors, 0 skipped` and exactly three `MISSING` lines for the three new
test names. So the suite today is **775 testcases across 93 `TEST-*.xml` reports** (re-counted from
`app/build/test-results/testDebugUnitTest` at the tasks step: 93 files, every one with `tests>0`, summing to
775; the 39 figure an earlier note of this map carried was a mis-count, and the 783 figure elsewhere is an
older tree), and every class check below passes before the fix.

- Gradle ladder: `$LIFTOFF_GATE_GRADLE` → `bash gradlew.sh` (only if `bash gradlew.sh --version` succeeds, i.e. Git
  Bash on Windows) → `$HOME/android-toolchain/gradle/bin/gradle` with `JAVA_HOME`/`ANDROID_HOME` defaulted to that
  toolchain → `./gradlew`.
- The gate is `:app:assembleDebug :app:testDebugUnitTest --no-build-cache`, with the previous
  `app/build/test-results/testDebugUnitTest` moved aside first (`mv`, not `rm -rf`: a stranded executor can hold
  `binary/output.bin`). It then refuses a green run whose log says
  `:app:testDebugUnitTest FROM-CACHE|UP-TO-DATE|NO-SOURCE`, because `org.gradle.caching=true` would otherwise let a
  deleted results directory come back FROM-CACHE in about a second.
- Up to 3 gate attempts, retried only when the failure is not a reported test result: no `BUILD SUCCESSFUL`/
  `BUILD FAILED` line in the log (killed by the `timeout 1800`), the infra greps (daemon disappeared, OOM, Test
  Executor, task timeout), no results directory without a Kotlin compile error, or every failing testcase in
  `com.liftoff.app.ui.LiftoffShellTest`. A failure in any other class — including the new cases — is not retried.
- Test names are matched across all reports with backticks/underscores/spaces removed and case folded, and a
  `[param]` suffix stripped, so a backticked or parameterised name still counts. A `skipped` child counts as not
  passing; no test in this suite skips today (no `@Ignore`, `assumeTrue`, `LIFTOFF_LIVE*` anywhere in `app/src`).
- Named tests it requires (the comments in the script say what each must assert):
  new — `aRefusedTurnThatEndedCompletedRepairsOnANewSessionAndThenTheGenerationSucceeds`,
  `aGenerationThatRunsOutOfRepairsOnCompletedTurnsFailsWithTheLastErrorsAndNotTheDaemonsRefusal` (both
  GenerationWorkerTest), `aFreshSessionRepairPromptCarriesTheOriginalPromptTheRefusedReplyAndEveryError`
  (CoachPromptBuilderTest);
  existing, must keep passing — `anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds`,
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing`,
  `repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson`,
  `aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession`.
- Class checks (all their tests must have run and passed): GenerationWorkerTest (36), CoachTurnRunnerTest (13),
  CoachPromptBuilderTest (15), CoachPromptGoldenFileTest (5), CoachReplySemanticTest (15),
  CoachReplyExtractionTest (10), GenerationDaoTest (9), PromptInputLoaderTest (11), GenerationCancellerTest (3).
  No UI class is named: the brief names a worker test and the plan names no screen test.
- Not checked, by design: the cursor a fresh session starts with (the fake's shared log answers a carried cursor
  happily; only the `events_after` frame is visible, and the script asks for it inside the first new test), whether
  the worker stops the session it is leaving, a permanent refusal inside a fresh-session repair (the plan says no
  new test beyond the existing permanent-refusal case), the resume path, the DESIGN.md/ARCHITECTURE.md amendments,
  and any UI test.

## Harness details the new GenerationWorkerTest cases need

- `insertOutlineGeneration(mission.id)` seeds the QUEUED row the repair cases run on; `runWorker(generationId)`
  returns the `ListenableWorker.Result`. `frameFor(method)` **fails unless exactly one** such frame was sent, so a
  two-session case must use `firstFrameFor` or `daemon.framesFor("rho.session.create")` and index the two.
- `promptsSent()` returns the `rho.session.send_prompt` frames; the existing repair cases read
  `["session_id"]`, `["client_id"]` (`"${row.id}-$attempt"`) and `["content"]` off them, and
  `CoachFixtures.errorsOf(CoachFixtures.BAD_JSON, CoachFixtures.OUTLINE_CONTEXT)` is how the expected error lines
  are recomputed in a test.
- A second, distinct session id comes from `daemon.sessionId` (a `@Volatile var`) set inside a `DaemonScript.Act` at
  the end of the first turn; `rho.session.create` answers with whatever the field holds at the call.
- `DaemonScript.State("completed")` is what makes the first turn end `completed`: the fake pushes
  `state_change to=completed`, and `CoachTurnRunner.handle()` maps it to `TurnEnd.Completed`. `rho.session.stop`
  also appends `state_change to=completed reason=stopped`, so a `stop` frame is not itself a turn end.
- `send_prompt` carries no cursor: the cursor goes in the `rho.session.events_after` frame, which is where a test can
  see whether the refused turn's cursor was carried into the new session.
- `MAX_REPAIRS = 2` (GenerationWorker.kt 506) is why the out-of-repairs case sees exactly three prompts.

