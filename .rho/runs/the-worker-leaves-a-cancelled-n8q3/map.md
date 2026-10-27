# Map — the worker leaves a cancelled generation cancelled

Branch HEAD at the tasks step: `481d4f4` ("run: verify"); the code underneath is still `98d25d1` — the app-open
sweep (#44), and `9fa19e2` is the commit that added this run's brief. Run brief:
`.rho/runs/the-worker-leaves-a-cancelled-n8q3/brief.md`.

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
Callers of `markRunning` / `markFailed` today (grep of `app/src`, 2026-10-27): main — `GenerationWorker` 64 and 391
only; tests — `GenerationDaoTest` 118/135/156/233, `MissionManagerTest` 275/278, `LaunchpadCoachStatusTest` 232,
`LaunchpadConfirmTest` 322, `LaunchpadDraftStatusTest` 161. All of them move a QUEUED or RUNNING row, so a status
filter leaves them passing. No test reflects over DAO methods (`declaredMethods`, `declaredMembers`,
`getDeclaredMethods`, `javaClass.methods` — zero hits under `app/src/test`), so changing these two helpers' return
type trips no guard test.
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

All 29 existing `GenerationWorkerTest` case names (the report says 29; so a new name cannot collide and a later
session can see what must keep passing): anOutlineGenerationStoresTheReplyAndMarksTheRowSucceeded · aLiftPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan
· aRunPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan · aGenerationRowThatDoesNotExistReturnsFailureAndSendsNothingToTheDaemon
· aGenerationRowThatAlreadyFinishedIsLeftAsItIsAndReportedAsDone (a **SUCCEEDED** row only — not CANCELLED)
· aGenerationRowWithASessionIdAlreadyStoredIsResumedWithoutCreatingAnotherSession · aGenerationWhosePromptAlreadyLandedResumesFromTheStoredCursorAndStoresTheReply
· aCursorAtTheEndOfTheTurnStillFindsTheReplyTheWorkerAlreadyRead · aRetriedGenerationSendsItsPromptAgainUnderTheSameClientIdAndTheDaemonDeliversItOnce
· oneSessionStopFrameIsSentAfterASuccessfulStore · aRefusedStopAfterASuccessfulStoreStillEndsTheGenerationSucceeded · anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds
· afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing · aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput
· aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession · aValidReplyForASortieThatHasMovedOnFailsTheGenerationWithTheStoresOwnReason
· aProtocolMismatchFromARefusedHelloFailsTheGenerationWithoutOpeningASession · aProtocolMismatchFromAnAcceptedHelloFailsTheGenerationWithoutOpeningASession
· aProtocolMismatchThatNamesNoVersionFailsTheGenerationWithTheDaemonsOwnMessage · aPausedTurnSendsAResumeForTheSessionItStoppedIn
· aRefusedResumeStillRetriesAndLeavesTheRowRunningWithItsSessionAndCursor · aTurnThatEndsInErrorRetriesAndLeavesTheRowRunningWithItsSessionAndCursor
· anUnreachableDaemonMarksTheGenerationOfflineAndRetries · aLaterRunThatReachesTheDaemonClearsTheOfflineMark · theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable
· aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone · aWorkerThatStoresAnOutlineEnqueuesTheFlightPlanItQueuedForSortieOne
· aSupersededFlightPlanGenerationStoresNothingAndTheNewerGenerationStillStoresItsPlan · aSupersededOutlineGenerationStoresNothingAndTheNewerGenerationStillStoresItsOutline

`GenerationDaoTest`'s existing cases: insertGetUpdateDelete · markingAGenerationRunningSucceededFailedOrCancelledSetsItsStatusAndFinishedAt
· savingTheSessionIdAndLastEventIdAndBumpingTheAttemptUpdatesTheGenerationRow · isCoachOfflineIsTrueOnlyForARowStillWaitingOnTheCoach
· theNewestNonCancelledGenerationForASortieIgnoresCancelledRows · theNewestNonCancelledGenerationForAMissionIsItsOwnOutlineGeneration
· theLiveOutlineReadForAMissionDropsACancelledRow. Its `queueGeneration(...)` helper writes a QUEUED row with the fields named.

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

**Who uses FakeDaemon** (so a change to `play` has to be gated on all of them): `GenerationWorkerTest`,
`CoachTurnRunnerTest`, `GenerationCancellerTest`, `rho/DaemonConnectionCheckTest`, `AppContainerTest` 68/101.
`DaemonScript.Assistant/State/Ask/Permission` are matched only in `play`'s `when` (341–359) and constructed in
`CoachTurnRunnerTest` and `GenerationWorkerTest`.

**The two scripts the new mid-turn cases copy** (read 2026-10-27):
- 791 `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing` — **two** `daemon.script(...)`
  calls: `Assistant(BAD_JSON)` + `State("idle")`, then `Assistant(SCHEMA_VIOLATION)` + `State("idle")`; three
  prompts under client ids `"$generationId-$index"`; asserts `attempt == 2`, `FAILED`, error
  `"invalid output: " + lastErrors.joinToString("; ")`, mission still DRAFT with no sorties,
  `assertSessionGivenUpOnce`. A cancel step belongs in the **first** script, before its `State("idle")`.
- 845 `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput` — one script: two `Assistant` lines then
  `State("blocked", "the coach is waiting for input")`; the row is inserted inline (not via
  `insertOutlineGeneration`), FAILED with `coach needed input`, nothing stored, session given up once.
Both cases seed the Mission with `container.missionManager.onAppOpen()`; the repairs case's generation row comes
from `insertOutlineGeneration(mission.id)`.

## app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt

`queueGeneration(...)` helper (writes a QUEUED row with the fields named); 110
`markingAGenerationRunningSucceededFailedOrCancelledSetsItsStatusAndFinishedAt` asserts each mark writes
only its own columns.

## verify.sh — the run's definition of done (committed this session)

`.rho/runs/the-worker-leaves-a-cancelled-n8q3/verify.sh`. Shape: CI gate → four named tests read out of that gate's
own reports → 27 whole-class checks → the exported Room schema. `t <method>` matches a test name across every
`TEST-*.xml` this run wrote (so which file/package a test sits in is the implementer's call); `c <ClassName>`
requires the class ran and every test in it passed. Reports are indexed by one awk pass keeping the worst outcome.
Stale reports are `mv`'d to `app/build/verify-old-results-<stamp>`; `--no-build-cache`; a `FROM-CACHE`/`UP-TO-DATE`
on `:app:testDebugUnitTest` is itself a failure; the gate is retried up to 3× only for the known hang / build-fault
signature / `doneBodyweightSetStepperShowsBW` alone.

The four names it requires (all new to this branch — verified zero hits under `app/src` before the work):
- `aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError`
- `aGenerationCancelledMidTurnThatRunsOutOfRepairsStaysCancelledAndStoresNothing`
- `aGenerationCancelledBeforeTheWorkerStartsOpensNoTurnAndEndsAsDone`
- `markRunningAndMarkFailedRefuseAGenerationThatAlreadyDecidedItsOutcome`

Each name's comment in verify.sh is what the task-writer and the test-writing session read; the tasks are written from
that script. The two mid-turn comments say the cancel must land **before the event that ends the turn** and must write
the row through `GenerationDao.markCancelled`, not through `container.generationCanceller()` (whose own
`rho.session.stop` would make `assertSessionGivenUpOnce` pass on a worker that stopped nothing).

Checked deliberately: the whole gate (which is also the check that every *existing* FAILED-expecting worker case —
blocked, two refused repairs, permanent refusal, protocol mismatches, 8th unreachable attempt, store-found-gone —
still lands FAILED); the 27 classes the brief's "Must not break" list names; `app/schemas` byte-identical against the
commit that added this run's brief (`git log --diff-filter=A -- <run>/brief.md`, then `^`), committed *and* working
tree, plus the file *set* under `app/schemas` (an untracked new version file is invisible to `git diff`).
Not checked, and why, is written in the script's header: the other `failGeneration` call sites (same guarded write),
an early stop of the repair loop (the plan's Decision leaves it running), the read→write race for `markRunning`
(a test cannot control SQLite lock timing — the DAO case proves that guard), the FakeDaemon action step (test-only
scaffolding), and any doc row.

**Ran against the untouched branch** (`app/build/n8q3-verify.log`, launched detached from `app/build/n8q3-verify-run.sh`):
gate attempt 1 `BUILD SUCCESSFUL in 2m 21s`, `46 actionable tasks: 46 executed`, 690 tests / 0 failures / 0 errors /
0 skipped; gate ✓, all 27 class checks ✓, schema ✓, and the four names each reported "did not run" →
`Result: 29 passed, 4 failed` / `EXIT=1`. That is the intended failure mode.

## Tasks — written 2026-10-27 (`run: tasks`)

Five tasks in `.rho/runs/the-worker-leaves-a-cancelled-n8q3/tasks.md`, following plan.md §Order. Which task writes
which verify.sh name: 1 → `markRunningAndMarkFailedRefuseAGenerationThatAlreadyDecidedItsOutcome` (DAO guards, whole
gate); 2 → the FakeDaemon action step + the guarded `failGeneration` +
`aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError`; 3 →
`aGenerationCancelledMidTurnThatRunsOutOfRepairsStaysCancelledAndStoresNothing` (no new production code); 4 → the
`doWork` mark-running refusal path +
`aGenerationCancelledBeforeTheWorkerStartsOpensNoTurnAndEndsAsDone`; 5 → the whole gate once, no new code.
Expected counts as the tasks land: `GenerationDaoTest` 7 → 8, `GenerationWorkerTest` 29 → 30 → 31 → 32, whole suite
690 → 694. No task writes a doc row: verify.sh checks no doc wording.

## Project rules

- CLAUDE.md: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (toolchain not on PATH);
  commit style `area: lowercase summary`; stage named files, never `git add -A`.
- ARCHITECTURE.md invariant 6: a generation is resumable from its row (session id + cursor); the `work`
  package row describes the worker, canceller and app-open sweep.
- DESIGN.md §7.3 generation lifecycle, §7.5.3 the two repairs, §10 the rho majors.
- No Room schema change is wanted, so `app/schemas` must stay byte-identical.
