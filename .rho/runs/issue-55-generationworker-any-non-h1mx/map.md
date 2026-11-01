# Map — issue #55 (any non-daemon exception after `markRunning` strands the row)

Branch `rho/issue-55-generationworker-any-non-h1mx`, HEAD `e96f763` at plan time. Baseline suite:
**738 tests in 89 reports** (issue #54's run raised it from 736; `GenerationWorkerTest` is at 34 cases).

## The code the change touches

**`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`** (486 lines) — one generation end to end.
- `doWork()` (54): reads the row, returns success for a finished row, `markRunning` claims it (66), then the
  Mission / Sortie reads (70–75, each ending through `failGeneration` with `client = null`), then the
  `ConnectionOutcome` `when` (77–101). Everything after line 66 is "the work after `markRunning`".
- `runGeneration` (105): `client.connect()` handled on its own (115–120), then **one `try` (124–148)** covering
  the settings read, `CoachSessionOptions`, `savingProgressTo { runCoachTurn(...) }` and `storeFinishedTurn`,
  with `catch (dropped: DaemonException)` (149) splitting permanent (`failGeneration` with
  `dropped.message.orEmpty()`) from retryable (`Result.retry()`), and `finally { client.close() }` (164).
  The catch runs **before** the `finally`, which is why it can still call `stop` on the live client.
  `reportedSessionId` (123) is held in the method precisely so the catch can give up a session even when the
  turn threw before reporting an outcome.
- `storeFinishedTurn` (221): the repair loop, `CoachReplyValidator.validate`, then
  `container.database.withTransaction` (296) holding the currency check + `storeOutline` / `storePlan` +
  `markSucceeded`. `StoreOutcome.Refused` → `failGeneration` with `stored.reason` (323).
- `failGeneration` (389): `markFailed(id, message, now)`, `giveUpSession` when both client and session id are
  there, `Result.success()` if the row already reads CANCELLED, otherwise `Result.failure()`.
- `giveUpSession` (406) and `handSessionBack` (420) are the house pattern for a swallow that must not eat a
  cancellation: `catch (stopped: CancellationException) { throw stopped } catch (_: Exception) {}`.
  `enqueueQueuedPlan` (363) uses the same shape.
- File-level: `TURN_LIMIT = 20.minutes`, `MAX_REPAIRS = 2`, `GIVE_UP_AT_ATTEMPT = 7` (WorkManager counts from 0),
  `COACH_UNREACHABLE`, `FINISHED_STATUSES = {SUCCEEDED, FAILED, CANCELLED}`.

**`app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`** — the turn only. Its own limit is
`withTimeoutOrNull(limit)` (172), so a 20-minute turn ends as `TurnEnd.TimedOut`, **not** as a thrown
`TimeoutCancellationException`; `storeFinishedTurn` sends a TimedOut end to `handSessionBack` + `Result.retry()`.
So the new catch cannot be handed a turn-limit exception.

**`app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt`** — re-enqueues every **open** row (QUEUED or RUNNING)
whose target is still current, cancels the rest. A FAILED row is not open, so once the row ends FAILED the sweep
leaves it alone; that is what stops the "re-runs the same failing path" half of the issue. Its `run()` already
uses the rethrow-then-swallow shape.

## The real trigger (issue #56's gap, used as the test's throw)

- **`CoachReplyValidator`** (`app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt`): `validate` (28)
  runs `SchemaChecker` then `semanticErrors`; the only name check is the duplicate-by-normalized-name one (167–172).
  `JsonObject.text(name)` (356) is an unchecked `as JsonPrimitive` cast.
- **`app/src/main/resources/schemas/lift-plan.json`**: an exercise `name` is checked only as
  `minLength: 1, maxLength: 60` — so `"!!!"` or `"   "` passes the schema.
- **`CoachReplyValidator` → `MissionManager.storePlan`** (`MissionManager.kt` 396) →
  **`FlightPlanDao.writePlan`** (`FlightPlanDao.kt` 63, `@Transaction`) →
  **`ExerciseDao.resolve`** (`ExerciseDao.kt` 27): `ExerciseNames.normalize(name).trim()` and
  `if (normalized.isBlank()) throw IllegalArgumentException("Exercise name cannot be blank or symbols-only: '$name'")`.
  `ExerciseNames.normalize` (`coach/ExerciseNames.kt`) keeps only letters, digits, space and `-`, so a
  punctuation-only or whitespace-only name normalizes to empty. DESIGN §7.7 (511–516) is the rule it enforces.
  The throw rolls the whole store transaction back and travels out of `storeFinishedTurn` today.
- `storePlan` also `require`s the sortie type matches (405, 411) — unreachable, the validator already rejected
  the mismatch — and everything else it decides comes back as `StoreOutcome.Refused`, never a throw. So
  `resolve` is the store's only reachable throw.

## Tests that already exist

**`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`** (1959 lines, 34 cases) — `doWork()` over a
real socket to a scripted `FakeDaemon`, on the application's own container (`container` from
`LiftoffApplication`); settings written into `container.settingsStore`; `@Config(sdk = [34])`.
- Helpers: `runWorker(generationId, runAttemptCount = 0)` (1748, `TestListenableWorkerBuilder` +
  `withTimeout(turnWait = 30.seconds)`), `insertFlightPlanGeneration` (1765), `insertOutlineGeneration` (1782),
  `sortieAt` (1762), `assertAwaitingGeneration` (1799), `assertPlannedWithAGeneratedPlan`,
  `assertSessionGivenUpOnce`, `pointSettingsAtNothing` (1732, closed port), `workInfosUnder` (1759).
- Lift-sortie seeding shape to copy (171 `aLiftPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan`):
  `onAppOpen()` → `continueWithoutOutline` → scrub the index-0 run → index 1 is a current PENDING LIFT.
- Closest existing case: 1089 `aValidReplyForASortieThatHasMovedOnFailsTheGenerationWithTheStoresOwnReason`
  (FAILED row, `assertEquals("sortie is SCRUBBED", row.error)`, `row.nabuSessionId` kept, nothing stored,
  session given up once).
- 1496 `aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone` shows the mid-turn pattern:
  `async(Dispatchers.IO) { worker.doWork() }`, poll for the prompt frame, act, `running.await()` — the shape a
  cancellation test needs (that case is the only one besides 1553 that calls `initializeTestWorkManager`).
- `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing` (838) and
  `aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput` (952) are the other FAILED-row cases.
- **No test can prove a `CancellationException` rethrow.** A coroutine in the cancelling state throws at its next
  suspension point, so `failGeneration`'s `markFailed` never runs whether the rethrow is there or not: the row
  stays RUNNING and `doWork` throws either way. The three mid-turn cancel cases (894, 997, 1496) cancel through
  `GenerationCanceller`, and a worker built with `TestListenableWorkerBuilder` is not the work that helper stops,
  so their own coroutines are never cancelled at all.

**`app/src/test/java/com/liftoff/app/coach/CoachFixtures.kt`** — `fixture(name)` reads
`app/src/test/resources/fixtures/coach/`; `LIFT_PLAN = "lift-plan-valid.txt"` (exercises "Bench Press",
"Pendlay Row", "Front Squat", …), `KEYS = barbell, dumbbell, bench, pullup-bar`, `validReply(fixture, context)`.
No fixture today has a blank or punctuation-only name.

**`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`** — `DaemonScript.Assistant/State/Ask/Permission/Act`
(`Act` (52) runs an arbitrary block mid-turn, used for mid-turn cancels), `script(vararg steps)` (187),
`framesFor(method)` (213), `refuseProtocolMismatch`, `sessionId`, `host()`, `port()`.

**`app/src/test/java/com/liftoff/app/work/AppOpenSweepTest.kt`** (9 cases) — covers the sweep's open-row rules;
no change needed here.

## The card wording (what the fix makes visible)

- `app/src/main/java/com/liftoff/app/domain/SortieGenerationStatus.kt`: RUNNING/QUEUED → `Planning` (72);
  FAILED → `failedFrom(error)` (108) which falls back to `FAILED_WITHOUT_REASON = "flight plan generation failed"`
  when the row's error is null or blank; `INVALID_OUTPUT_LEAD` splits the validator's lines out.
- `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt`: same for the outline,
  `FAILED_WITHOUT_REASON = "outline generation failed"` (51). Its comment at 36 claims
  "markFailed always writes a reason" — true except for the existing `.orEmpty()` arm.

## Docs that set the rules

- `DESIGN.md` §7.3 (285–344): the lifecycle, §7.3 step 6 "On SUCCEEDED or permanent FAILED, call
  `nabu.session.stop`", the 8-attempt give-up, supersede. §10 (580–594) is the failure table — it has no row for
  "the store throws"; this run changes no doc. §7.6 (490–509) validation, §7.7 (511–516) exercise identity.
- `ARCHITECTURE.md` line 36 (`com.liftoff.app.work` row) and line 53 describe the worker as ending the row
  "FAILED with its reason" — already what this fix makes true.
- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. Known timing flakes:
  `doneBodyweightSetStepperShowsBW`, `aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt`.

## Related issues

- #55 this one. #56 `CoachReplyValidator accepts an exercise name that normalizes to empty, then the store
  throws` — the trigger; its fix would make such a reply Invalid before the store, so a test written on that
  reply is coupled to it. #54 (already merged) added the two named connection arms to this same worker.
