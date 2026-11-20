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

## Gate

`gradlew.sh` pins `C:/Users/corpo/android-toolchain/...` — Windows paths, so it does not run on this machine.
Locally: `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest` (~1m08s warm;
`--no-build-cache` plus `rm -rf app/build/test-results/testDebugUnitTest` to force a real run).
Suite today: 783 unit tests. `LiftoffShellTest` is the known flake in a full-suite run — rerun before believing it.
