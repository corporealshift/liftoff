# Map — issue #53 (a coach turn that ends `error` retries forever)

## The code the change touches

**`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`** (486 lines) — owns the row, the schedule, the storage.
- `doWork()` :54 — reads the row, `FINISHED_STATUSES` short-circuit :62, `markRunning` guard :66, then the connection check :77 (`Connected` → `runGeneration`, `ProtocolMismatch` → `failGeneration`, `Unreachable` → `coachUnreachable`, else `Result.retry()`).
- `runGeneration` :105 — `client.connect()` (a socket that never opened → `coachUnreachable`), `savingProgressTo { runCoachTurn(...) }` :132 with `clientId = "${row.id}-${row.attempt}"` :138 and `cursor = row.lastEventId` :139, then `storeFinishedTurn` :148. `catch (dropped: DaemonException)` :149 → `dropped.isPermanent` → `failGeneration` with the daemon's message, else `Result.retry()`.
- `storeFinishedTurn` :221 — the repair loop. **The branch to change is :249-252**: `if (turn.end != Idle && turn.end != Completed) { handSessionBack(...); return Result.retry() }`. `Blocked` already fails at :244 with `"coach needed input"`. The comment at :326 ("Only a generation that stored its answer gives up its session; every other end resumes it (invariant 6)") and the KDoc at :207-211 both state the rule being changed.
- `failGeneration` :389 — `markFailed(id, message, now)`, then `giveUpSession` when client+sessionId are non-null, then the CANCELLED exception (a row the user cancelled first keeps its own outcome → `Result.success()`), else `Result.failure()`.
- `giveUpSession` :406 and `handSessionBack` :420 both swallow every refusal (only `CancellationException` travels out). `handSessionBack` is the only `rho.session.resume` call.
- `coachUnreachable` :175 — `runAttemptCount < GIVE_UP_AT_ATTEMPT` (7 = the 8th attempt, WorkManager counts from 0). The attempt ceiling is only on this offline path: the retry arm at :249 has none, so a turn that keeps ending `TimedOut` loops until the app-open sweep cancels the row.
- File-level: `TURN_LIMIT = 20.minutes` :462 ("being stopped at the limit is just an early retry"), `MAX_REPAIRS = 2` :465, `COACH_UNREACHABLE = "coach unreachable"` :471.

**`app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`** (252 lines) — one turn, no Room/WorkManager.
- `enum TurnEnd { Idle, Completed, Blocked, Paused, Error, TimedOut }` :40; `TURN_ENDS` maps the daemon's `state_change.to` names :58.
- `data class TurnOutcome(sessionId, cursor, end, lastAssistantText)` :50 — **carries no reason**, so the `state_change`'s `reason` never reaches the worker today.
- `handle()` :131 — `EventType.STATE_CHANGE` :144 reads `StateChangeData.to`; the first end after `promptId` wins.
- `alreadyActedOn(target, id)` :125 — `id <= actedOnAtOrBefore && (target == Paused || target == Error)`: a `paused`/`error` the stored cursor had already passed does **not** end the turn. This is the second half of the bug: a resumed attempt whose `lastEventId` sits past the error event finds no end and waits out `limit` → `TimedOut` → retry.
- The re-read path :196-204 — runs when the handed cursor is past this turn's own prompt id; sets `actedOnAtOrBefore` for the pass.
- `stopCoachSession` :225 (`rho.session.stop`); `eventsAfter` :241 re-reads from the start on `RpcCodes.CURSOR_UNKNOWN`.

**`app/src/main/java/com/liftoff/app/rho/protocol/Events.kt`:100** — `StateChangeData(from, to, reason)`; `reason` is the session's own words, i.e. the error text the brief wants on the row.

**`app/src/main/java/com/liftoff/app/data/GenerationDao.kt`:49** — `markFailed(id, error: String, finishedAt): Int`; the Int is the rows-written count `failGeneration` branches on.

## Where a FAILED row surfaces (no UI change needed)

- `domain/CoachStatus.kt`:70-71 — a FAILED outline row → `CoachStatus.Failed(error)`, blank error → `FAILED_WITHOUT_REASON = "outline generation failed"`.
- `domain/SortieGenerationStatus.kt`:74, 108-119 — a FAILED FLIGHT_PLAN row → `Failed(text)` shown verbatim; blank → `FAILED_WITHOUT_REASON = "flight plan generation failed"`; a `"invalid output: "` prefix is reformatted into `INVALID_OUTPUT` + validator lines. `QUEUED`/`RUNNING` → `Planning` / `RePlanning` — the "Coach is planning…" line the issue is about.
- `data/MissionManager.kt`:157 `retryOutline` — Retry is refused unless the newest non-cancelled outline row is `FAILED` (:169), and it inserts a **new** QUEUED row with no `nabuSessionId`/`lastEventId` (:172). `regenerateFlightPlan` :344 likewise inserts a fresh QUEUED row. So a retry after a FAILED row always starts a new rho session.

**`app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt`** (118 lines) — at each app open, every open (QUEUED/RUNNING) row whose target is still current goes to `enqueue`, the rest to `cancel`. A row stuck RUNNING is therefore re-run, not failed; after the week rolls over its Mission's `weekStart != weekStart` makes it unwanted → CANCELLED.

## Tests that already exist

**`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`** (1959 lines, `@Config(sdk = [34])`) — worker tests run `doWork()` on the application's own container via `runWorker(generationId, runAttemptCount = 0)` :1748 (`TestListenableWorkerBuilder` + `workDataOf(GENERATION_ID to id)` + `withTimeout(turnWait = 30.seconds)`).
- Helpers: `frameFor(method)` :1895 (exactly one frame), `firstFrameFor` :1907, `assertSessionGivenUpOnce(sessionId)` :1843, `assertSessionLeftOpen()` :1853 (no `rho.session.stop` frame), `insertOutlineGeneration(missionId, createdAt)` :1782, `mismatchFailedRow` :1865, `pointSettingsAtNothing()`.
- **The test that asserts today's bug: `aTurnThatEndsInErrorRetriesAndLeavesTheRowRunningWithItsSessionAndCursor` :1307** — scripted `Assistant` then `State("error", "the coach's turn fell over")`, asserts `Retry`, RUNNING, `error` null, `attempt` 0, no `finishedAt`, session left open. It is the one test in the tree that scripts an `error` state.
- Shape to copy: `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput` :952 — `Result.Failure`, row FAILED with the reason, `finishedAt` stamped, `nabuSessionId` kept, nothing stored, `assertSessionGivenUpOnce`.
- Leave alone: `aPausedTurnSendsAResumeForTheSessionItStoppedIn` :1215, `aRefusedResumeStillRetriesAndLeavesTheRowRunningWithItsSessionAndCursor` :1264, and the two-run resume test :587 (a paused end, then the reply, `doWork()` twice).
- Other FAILED cases: `afterTwoFailedRepairs…` :838, `aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession` :1045, `aValidReplyForASortieThatHasMovedOn…` :1089, `theEighthAttemptThatCannotConnect…` :1447. Mid-turn cancels go through `DaemonScript.Act` (:997, :1496).

**`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`** (429 lines) — `DaemonScript.{Assistant, State(to, reason = ""), Ask, Permission, Act}` :35-46; a `State` step appends a `state_change` with `to` **and `reason`** :352-355. `script(vararg steps)` :187, `logEvent(type, data)` :193 seeds an event before the client arrives (a seeded user message carrying a `client_id` counts as the prompt already taken, so a re-send is deduped), `refuseMethod[method] = code to message` :91, `sessionId` :84, `framesFor(method)` :210. `rho.session.stop` appends a `completed` state_change :280; `rho.session.resume` appends `running` :288.

**`app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt`** (751 lines) — turn-level tests: fresh turn, handed session/cursor, a state_change before the prompt, ask/permission answers, `Blocked` :361, `Paused` :407, two ends stop at the first :451, `TimedOut` :549, a dropped connection throws :611, the stop helper :667. **No test scripts an `error` end**, and none exercises `alreadyActedOn` directly (the worker's paused-resume tests do, through `State("paused")`).

## Harness details for the new tests

**`CoachTurnRunnerTest`** — plain JUnit 4 over a real localhost socket, no Robolectric, no virtual time.
- Limits the class already holds: `turnLimit = 2.seconds` (a turn scripted to finish), `shortLimit = 500.milliseconds`
  (a turn scripted with no end, so the limit is what ends it), `answerWindow = 15.seconds` (the `withTimeout` wrapped
  around every call), `droppedTurnLimit = 1.hours` (only for the dropped-connection case). `@Before startDaemon` /
  `@After stopDaemon` on the class's own `FakeDaemon`; `connectedClient()` :72 builds a `DaemonClient` against
  `daemon.host()/port()` with token `"sekrit"` and connects.
- Private helpers at :705-745: `frameFor(method)`, `JsonObject.jsonObject(key)`, `answerTo(requestId)`,
  `eventId(event)`, `contentOf(id)`, `promptEventId(clientId)`.
- Shape of a turn call: `runCoachTurn(client, sessionId, options = CoachSessionOptions(workspace = "/repo/coach"),
  prompt, clientId, cursor, limit, onSessionId = {}, onCursor = {})`. Every test names its own `clientId`
  (`"turn-1"` … `"turn-11"`), so a new test needs a fresh one.
- `twoEndingStateChangesAfterThePromptStopAtTheFirst` :451 is the model for a turn whose events are all seeded rather
  than pushed: it calls `client.callOrThrow("rho.session.send_prompt", {session_id, content, client_id})` itself,
  reads `promptEventId(clientId)`, then `daemon.logEvent(EventType.MESSAGE, …)` and `daemon.logEvent(EventType.STATE_CHANGE, {to, reason})`,
  and finally runs the turn with the SAME `clientId` (the re-send is deduped) and the cursor it wants handed in.
  **That is the shape the stuck-row test needs**: seed the prompt under the client_id, seed the `error`
  state_change after it, then hand in a cursor past that error event.
- `aSessionAndCursorHandedInIsUsedWithoutCreatingAnother` :142 is the handed-in-session case to copy for asserting
  "no second `rho.session.create` frame".

**`GenerationWorkerTest`** — the mid-turn cancel shape to copy is `aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError`
:997: `insertOutlineGeneration(mission.id)`, then a script of `Assistant` lines, then `DaemonScript.Act { … }` that
re-reads the row, `update(row.copy(error = carriedError))` and `markCancelled(generationId, now)` while the turn is
open, then the end state. Cancel goes through the DAO, never `container.generationCanceller()` (it sends its own
`rho.session.stop` and would prove nothing). Asserts `Result.Success`, CANCELLED, `row.error == carriedError`,
`finishedAt` non-null, mission still DRAFT, no sorties, `assertSessionGivenUpOnce`.
- `eventId(event)` and `daemon.log.single { it["data"]?.jsonObject?.get("to")… }` are how a test reads the id of the
  end event the fake logged (see :1322).

## verify.sh — what this run's check names (re-read 2026-10-31, after the rewrite at `e51b66b`)

`.rho/runs/issue-53-generationworker-a-coach-turn-4x9r/verify.sh`, 218 lines. It was written at `aaaa5b0` and
**rewritten by claude review at `e51b66b`**: that rewrite cut the other two named tests and all ten class
checks, so the script now has the CI gate plus **one** named test. Read the script itself, not this note's
memory, if a later step needs its exact checks.

Gate (section 1): `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` — the whole
unit suite, up to 3 attempts retried only on the hang / build fault / `doneBodyweightSetStepperShowsBW` flake,
stale reports moved aside not deleted, a `FROM-CACHE`/`UP-TO-DATE` test task is a failure.

Named test (section 2), absent from `app/src/test` today, so the script exits 1 on the untouched tree:
1. `aTurnThatEndsInErrorFailsTheGenerationWithTheDaemonsReason` — worker; `Result.Failure`, FAILED +
   `finishedAt` + session id kept, `row.error` is the scripted reason string, nothing stored, one
   `rho.session.stop`, no `rho.session.resume`.

**No longer named by the script** (they were, before the review rewrite; they are still the plan's own tests
and tasks 1 and 3 write them, proved by the whole-suite gate):
`aGenerationCancelledMidTurnThatEndsInErrorStaysCancelledAndKeepsItsError` and
`aCursorHandedInPastAnErrorReportsThatErrorInsteadOfTimingOut`. There is no `c <ClassName>` helper any more, so
no class check exists: `GenerationWorkerTest`, `CoachTurnRunnerTest`, `GenerationDaoTest`,
`GenerationEnqueuerTest`, `GenerationCancellerTest`, `AppOpenSweepTest`, `MissionManagerTest`,
`MissionManagerOutlineTest`, `MissionManagerPlanTest`, `FlightPlanDaoTest` are covered only by the gate running
the whole suite. No UI test is named; no document row is grepped.

The script's header now states that the plan's Decisions get no test of their own and are reviewed in the PR:
stuck rows unstuck, a cancelled/superseded generation keeping its outcome, `TimedOut` staying on the retry
path, the fixed sentence for a reason-less `error`, Retry opening a fresh session, and the DESIGN.md §7.3
amendment.

**Ran end to end on the untouched tree (2026-10-31, at HEAD `ad8a4bc`, i.e. the pre-rewrite script)**: gate
attempt 1 passed — `BUILD SUCCESSFUL in 1m 56s`, `:app:testDebugUnitTest` executed (not FROM-CACHE), **736
tests / 0 failures / 0 errors / 0 skipped**. Scratch copy `app/build/verify-run.sh`, log
`app/build/verify.log` (the script's own `$WORK` dir is removed by its trap). Run it from a copy under
`app/build`, never in place: editing a script while a detached run of it is going gives a phantom "syntax
error near unexpected token".

Deliberately NOT checked (stated in the script header): the narrowed retry arm (existing paused-resume, refused-resume,
two-run-resume, `TimedOut` and dropped-connection tests cover it and already pass, so naming them cannot fail the
script); the Launchpad's rendering of a FAILED row; `TurnOutcome` carrying `reason` as a field (only that the
daemon's words land on the row); the daemon possibly refusing `rho.session.stop` on an errored session; DESIGN.md
and ARCHITECTURE.md wording (this run writes code, so no doc row is grepped); the Room schema (the brief asks for no
schema change and the change touches no entity).

## Extra facts the tasks step checked (2026-10-31)

- `TurnOutcome` is built in exactly one place in the whole tree — `CoachTurnRunner.kt:221` — and no test
  constructs one (`grep 'TurnOutcome|runCoachTurn' app/src` finds only that construction, the worker's reads at
  :230/:240-252/:276-288, and `CoachTurnRunnerTest`'s eleven `runCoachTurn` calls). So giving it a `reason`
  field is a one-file change; the worker only reads what comes back.
- `CoachTurnRunnerTest`'s client ids are `turn-1` … `turn-11` (:102, :159, :225, :278, :327, :377, :419, :452,
  :566, :625, :678), so the two new cases need fresh ones.
- `alreadyActedOn`'s body is `val at = actedOnAtOrBefore ?: return false; return id <= at && (target ==
  TurnEnd.Paused || target == TurnEnd.Error)` (:125-128). `actedOnAtOrBefore` is non-null only inside the
  re-read pass at :196-204, whose entry condition is `promptId.isNotEmpty() && handed > promptId && (end ==
  null || lastAssistantText == null)`. Dropping `TurnEnd.Error` from that comparison is the whole of the
  unstuck-rows half of the fix.
- The worker's existing `error` case (:1307-1339) scripts `Assistant("Starting with Monday's lift, then—")`
  then `State("error", "the coach's turn fell over")`, and reads the end event's id with
  `daemon.log.single { it["data"]?.jsonObject?.get("to")… == "error" }` + `eventId(errored)` (:1322). Its
  `runWorker(generationId)` hands in no cursor, so `alreadyActedOn` is not what that test reaches — which is
  why it stays green through the runner task and only flips in the worker task.
- `DESIGN.md` "Amendments after approval" (:783-786) states its own rule — "listed here, newest first, and
  marked inline in the section they affect: what changed, when, and why" — and its bullets are
  `**YYYY-MM-DD — §N what changed.** prose`. The 2026-10-20 rename bullet explicitly left the approved text
  under the old `nabu` names, and §7.3 still reads `nabu.session.resume` / `nabu.session.stop` (:332-333), so a
  rewritten step-5 line should keep those names rather than mixing in `rho.`.

## Docs that state the rule

- **`DESIGN.md` §7.3 (lines 285-344), step 5 at :331-332**: "**`paused` or `error`:** a retryable failure, so return `Result.retry()`. Before the retry, if the session is `paused`, call `nabu.session.resume`." The design document currently says `error` is retryable — the issue says rho makes it terminal. The line has to change with the code, or the spec contradicts the app.
- `ARCHITECTURE.md` :36 (the `com.liftoff.app.work` row) already reads "…or ending the row FAILED with its reason"; :82 (M3) reads "with resume, offline and supersede handled there". Neither states the `error` rule, so no row needs rewriting.
