# Map — the worker leaves a cancelled generation cancelled

Branch HEAD at planning time: `f4fa951` ("run: plan outline"); the code underneath is `98d25d1` — the app-open
sweep (#44). Run brief: `.rho/runs/the-worker-leaves-a-cancelled-n8q3/brief.md`.

## app/src/main/java/com/liftoff/app/work/GenerationWorker.kt (477 lines)

Plain `CoroutineWorker` (no HiltWorker); `doWork()` at line 54 reads its `Generation` row off
`GENERATION_ID` in `inputData`.

- 59: `generations.get(generationId) ?: return Result.failure()` — a missing row is a failure.
- 62: `if (row.status in FINISHED_STATUSES) return Result.success()` — SUCCEEDED / FAILED / CANCELLED
  (`FINISHED_STATUSES`, companion, line 441) already short-circuit *before* the row is read again below.
- 64: `generations.markRunning(generationId)` — unguarded. A cancel that lands between the read at 59 and
  this write is what flips a cancelled row back to RUNNING; the early return at 62 cannot catch it.
- 69, 72: `failGeneration(..., client = null, sessionId = null)` when the Mission or the Sortie is gone.
- 75–99: `container.daemonConnectionCheck().check()` → Connected (clears the offline mark, runs the turn),
  ProtocolMismatch (`failGeneration`), Unreachable (`coachUnreachable`), else `Result.retry()`.
- 147–161: `catch (dropped: DaemonException)` — `dropped.isPermanent` → `failGeneration(..., client,
  reportedSessionId ?: row.nabuSessionId)`; anything else → `Result.retry()`.
- 173–179 `coachUnreachable`: below `GIVE_UP_AT_ATTEMPT` (= 7, i.e. the 8th attempt) leaves
  `Generation.COACH_OFFLINE` on the row and retries; at the cap it calls `failGeneration`.
- 239–329 the turn loop: `TurnEnd.Blocked` → `failGeneration(row.id, "coach needed input", client, sessionId)`
  (243); a non-idle/non-completed end → `handSessionBack` + `Result.retry()` (248); invalid reply under the
  repair cap → `bumpAttempt` + a repair turn (269–287); invalid reply at `MAX_REPAIRS` (= 2, line 456) →
  `failGeneration("invalid output: …")` (261).
- 294–315 the guarded store: `container.database.withTransaction { if (!isCurrentGeneration(...)) { if
  (status != CANCELLED) markCancelled(...); NotCurrent } else { store…; markSucceeded; Wrote } }`; a
  `StoreDecision.NotCurrent` then `giveUpSession(client, turn.sessionId)` and `return Result.success()`
  (312–315). This is the behaviour the brief says to copy.
- 349–352 `private sealed interface StoreDecision { data class Wrote(outcome, queuedPlanId); data object NotCurrent }`.
- **384–394 `failGeneration(generations, generationId, message, client, sessionId)`** — the *only* writer of
  FAILED in this file (all four call sites above go through it): `markFailed(...)` unguarded, then
  `if (client != null && sessionId != null) giveUpSession(...)`, then `Result.failure()`.
- 397–404 `giveUpSession` swallows every failure except `CancellationException`; 411–418 `handSessionBack`
  does the same for `rho.session.resume`.
- `TURN_LIMIT = 20.minutes` (453), `MAX_REPAIRS = 2` (456), `GIVE_UP_AT_ATTEMPT = 7` (459),
  `COACH_UNREACHABLE = "coach unreachable"` (462).

## app/src/main/java/com/liftoff/app/data/GenerationDao.kt

Row helpers each write only the columns that step owns (comment at 32). Unguarded by status:
`markRunning(id)` (36), `markSucceeded(id, finishedAt)` (39), `markFailed(id, error, finishedAt)` (43),
`markCancelled(id, finishedAt)` (46), `saveSessionId`, `saveLastEventId`, `bumpAttempt`.
Already guarded by status, the precedent to follow: `setCoachOfflineMark(id, error?)` (62) carries
`AND status IN ('QUEUED', 'RUNNING')`. Reads that filter on status: `getNewestNonCancelledForSortie`,
`getNewestNonCancelledOutlineForMission` (+ its `observe…` Flow), `getOpenOutlineForMission`,
`getOpenFlightPlanForSortie`, `getOpen`.

## app/src/main/java/com/liftoff/app/data/Generation.kt / GenerationStatus

Row fields: `status`, `nabuSessionId`, `lastEventId`, `attempt`, `error`, `createdAt`, `finishedAt`,
`kind`, `missionId`, `sortieId`; `Generation.COACH_OFFLINE`. Statuses: QUEUED, RUNNING, SUCCEEDED, FAILED,
CANCELLED.

## app/src/main/java/com/liftoff/app/work/GenerationCanceller.kt

`cancel(generationId)` (32): cancels the unique work `gen-<id>`, then one transaction reads the row and
`markCancelled` only when `row.status in CANCELLABLE_STATUSES` (41–42), then stops the row's session if it
carried one. **Note for tests:** cancelling through this helper sends its own `rho.session.stop`, so a stop
frame seen by the fake daemon is not proof the *worker* stopped the session.

## app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt (1803 lines)

Harness: `runWorker(generationId, runAttemptCount = 0)` (1592) builds the worker with
`TestListenableWorkerBuilder` against the application's own container; `turnWait` bounds each wait;
`initializeTestWorkManager` only where the case reads WorkManager or uses the canceller helper.
Assertions available: `assertNothingReachedTheDaemon()` (1677), `assertSessionGivenUpOnce(sessionId)` (1687),
`assertSessionLeftOpen()` (1697), `assertNoSessionWasOpened()` (1728), `frameFor`/`firstFrameFor` (1739/1751),
`promptsSent()` (1767), `methodsSent()` (1774), `userMessagesFor(clientId)` (1760).
Fixtures: `CoachFixtures.fixture(OUTLINE | RUN_PLAN | LIFT_PLAN | BAD_JSON | SCHEMA_VIOLATION)`;
`insertOutlineGeneration` (1626), `insertFlightPlanGeneration` (1609).

Cases to copy:
- 845 `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput` — scripts two assistant messages then
  `DaemonScript.State("blocked", …)`; row FAILED with `coach needed input`, `Result.failure()`.
- 793ish `…two failed repairs…` — BAD_JSON then SCHEMA_VIOLATION, `attempt == 2`, row FAILED with
  `invalid output: <last errors>`.
- 1340 `aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone` — the race pattern: launch
  `doWork()` in `async(Dispatchers.IO)`, poll (bounded, `delay(20)`) until the prompt frame *and* the row's
  `nabuSessionId` exist, then `container.generationCanceller().cancel(generationId)`, `await()` under
  `withTimeout(turnWait)`; asserts Success, row CANCELLED with `finishedAt`, nothing stored.

## app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt

`script(vararg DaemonScript)` — `Assistant(content)` (37), `State(to, reason)` (40), `Ask(question, choices)`
(43), `Permission(tool, summary, risk)` (46). `play(steps)` (338) pushes the whole script as soon as the prompt
arrives; **only `Ask` and `Permission` pause it** — `hold(id, method, params, rest)` (372) keeps the rest of the
script back until the client answers, and the worker's turn runner answers those on its own. `framesFor(method)`,
`logEvent`, `sessionId`, `log`, `stop()`. One scripted turn per new `client_id`; a repeated `client_id` is
deduped. `refuseMethod` makes a call throw a permanent `DaemonException`.

**Consequence for a mid-turn cancel:** nothing in a script holds a turn open long enough for a test to win a race
against it. The existing mid-turn case (below) can win only because a valid reply leaves the worker validating
and storing after the turn. A script that *ends* the turn — blocked, or a refused reply that hits the repair cap
— can reach `failGeneration` before a poll-then-cancel lands, and once the row is FAILED the canceller will not
touch it (`CANCELLABLE_STATUSES`). The failure-path cases need the cancel driven from inside the script, at a
step placed before the event that ends the turn.

## app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt

`queueGeneration(...)` helper (writes a QUEUED row with the fields named); 110
`markingAGenerationRunningSucceededFailedOrCancelledSetsItsStatusAndFinishedAt` asserts each mark writes
only its own columns.

## Project rules

- CLAUDE.md: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (toolchain not on PATH);
  commit style `area: lowercase summary`; stage named files, never `git add -A`.
- ARCHITECTURE.md invariant 6: a generation is resumable from its row (session id + cursor); the `work`
  package row describes the worker, canceller and app-open sweep.
- DESIGN.md §7.3 generation lifecycle, §7.5.3 the two repairs, §10 the rho majors.
- No Room schema change is wanted, so `app/schemas` must stay byte-identical.
