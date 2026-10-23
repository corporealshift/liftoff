# Map — cancel and supersede coach generations

Facts a later step would otherwise have to look up again. Line numbers are from HEAD `f71a1a1`.

## Docs that set rules

- `DESIGN.md` §7.3, lines 336-343 — the generation outcomes this run builds on: unreachable daemon →
  `Result.retry()` + "Mission Control offline" (§9); 8 attempts → FAILED "coach unreachable";
  **Superseded results** (340-343): "when a regeneration is requested while a generation for the same sortie
  is queued or running, the old one is cancelled: its work is cancelled and its nabu session stopped.
  Results are stored only if the generation is still the sortie's current one." So the cancel helper is
  DESIGN's mechanism, and the store guard is its second half. `DESIGN.md:704` lists "a regeneration supersedes
  the running generation" under M3; `DESIGN.md:739` puts "Regenerate and supersede" with generation.
- `ARCHITECTURE.md:82` — the M3 row the brief asks to update. It currently ends "cancellation, supersede and
  app wiring still to come".
- `ARCHITECTURE.md:36` — the `com.liftoff.app.work` package-layout row, which records what that package holds
  (the last run updated this row together with M3). The plan does **not** touch it: the brief names only the M3
  row, and the plan stays inside the brief.
- `ARCHITECTURE.md:60-69` — invariants. 6: generations resumable from the row's stored cursor. 7: labels never
  start with `run:`. 8: every reply validated, up to 2 in-session repairs.
- `CLAUDE.md` — gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only; §2 vocabulary; short comments.

## `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`

The three row helpers the brief names already exist and are tested (`GenerationDaoTest.kt:141,246,261-308`):

- `markCancelled(id, finishedAt)` (44-45) — `status = 'CANCELLED'`, stamps `finishedAt`.
- `getNewestNonCancelledForSortie(sortieId)` (65-69) — `status != 'CANCELLED'`, `ORDER BY createdAt DESC, id DESC LIMIT 1`.
- `getNewestNonCancelledOutlineForMission(missionId)` (72-76) — same, plus `kind = 'OUTLINE'`.
- Also there: `get(id)`, `markRunning`, `markSucceeded`, `markFailed`, `setCoachOfflineMark` (60-61, writes the
  mark only while the row is QUEUED or RUNNING), `saveSessionId`, `saveLastEventId`, `bumpAttempt`.
- No `@Transaction` on the DAO; transactions are taken through `db.withTransaction` (see MissionManager).

## `app/src/main/java/com/liftoff/app/data/Generation.kt`, `Enums.kt`

- Row fields: `id, kind, missionId, sortieId?, status, nabuSessionId?, lastEventId?, attempt, error?, createdAt, finishedAt?`.
- `GenerationStatus` (`Enums.kt:9`): `QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED` — CANCELLED already exists,
  so no schema change is needed anywhere in this run. `GenerationKind` (`Enums.kt:8`): `OUTLINE, FLIGHT_PLAN`.
- `Generation.COACH_OFFLINE = "Mission Control offline"`; `isCoachOffline` is true only while QUEUED/RUNNING.

## `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` (411 lines)

- `doWork()` (53-99): reads the row by `GENERATION_ID`; **returns early for a row already in
  `FINISHED_STATUSES` = {SUCCEEDED, FAILED, CANCELLED}** (61) — so a row cancelled *before* `doWork` never
  reaches a turn; `markRunning`; Mission/Sortie lookups that can fail the row; then the
  `daemonConnectionCheck().check()` branch (`Connected` clears the offline mark, `Unreachable` →
  `coachUnreachable`, else retry).
- `runGeneration` (102-164): `container.daemonClient()` + `connect()` (a refused socket → `coachUnreachable`);
  `savingProgressTo` (186-202) drains the turn's session-id/cursor writes through a channel; a dropped
  `DaemonException` is permanent → `failGeneration`, otherwise `Result.retry()`; `finally client.close()`.
- `storeFinishedTurn` (214-302) is where the brief's guard goes. Today it: builds `GenerationContext` —
  including `equipmentDao().observeActive().first()` (229), a **Flow read** — then loops: `Blocked` →
  fail; any end that is not Idle/Completed → `handSessionBack` + retry; validate, repairing with
  `bumpAttempt` and client_id `<row.id>-<attempt>` up to `MAX_REPAIRS = 2`; then store (286-289) through
  `container.missionManager.storeOutline(row.missionId, valid)` / `storePlan(requireNotNull(row.sortieId), valid)`;
  `StoreOutcome.Refused` → `failGeneration`; then `markSucceeded` + `giveUpSession` + `Result.success()` (296-300).
- `failGeneration` (318-328) marks FAILED then stops the session. `giveUpSession` (331-338) and `handSessionBack`
  (345-352) both swallow every exception except `CancellationException` — the pattern a new session stop follows.
- `TURN_LIMIT = 20.minutes`, `GIVE_UP_AT_ATTEMPT = 7` (WorkManager counts from 0).
- The worker reaches everything through `(applicationContext as LiftoffApplication).container`, so
  `container.database.withTransaction { }` is available to it.

## `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt` (252 lines)

- `suspend fun stopCoachSession(client, sessionId)` (225) — the stop helper the brief names: one
  `rho.session.stop` call, `callOrThrow`. The runner itself never calls it.
- `TURN_ENDS` (58-64): `idle → Idle`, `completed → Completed`, `blocked → Blocked`, `paused → Paused`, `error → Error`.
- `runCoachTurn` (78-222): keeps `lastAssistantText` from an assistant message whose id is after the prompt's
  (135-141); the **first** state_change after the prompt is the turn's end (144-152). So a `completed` pushed
  mid-turn ends the turn with the reply already in hand.
- `NOBODY_TO_ASK = "Decide yourself; there is nobody to ask."` (67) — the runner answers the fake's `Ask` itself.

## `app/src/main/java/com/liftoff/app/data/MissionManager.kt`

- `StoreOutcome` (37-39): `Stored` / `Refused(reason)`.
- `storeOutline(missionId, valid)` (109-133) and `storePlan(sortieId, valid)` (218-256) **each open their own
  `db.withTransaction`** and refuse a target that has moved on (`storePlan` refuses a sortie that is not the
  current one, 251). Any new transaction the worker opens around the store therefore nests with these.

## `app/src/main/java/com/liftoff/app/AppContainer.kt` (76 lines)

- `fun generationEnqueuer(): GenerationEnqueuer` (38-39) — `GenerationEnqueuer(WorkManager.getInstance(appContext))`,
  built per call so constructing a container touches nothing WorkManager-related. The cancel helper's accessor
  sits beside it.
- `suspend fun daemonClient(): DaemonClient` (68-75) — an unconnected client in rho's URL form, host/port/token
  trimmed and read at the moment of the call; `suspend fun daemonConnectionCheck()` (55-62). The container holds
  no connection: whoever opens one closes it.
- `database`, `missionManager`, `settingsStore` are the other members.

## `app/src/main/java/com/liftoff/app/work/GenerationEnqueuer.kt` (37 lines)

- `enqueue(generationId)` builds the `OneTimeWorkRequest` (network constraint, 1-minute exponential backoff) and
  calls `enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, request)`.
- `private fun uniqueName(generationId) = "gen-$generationId"` (36) — private today; the cancel helper needs the
  same spelling, so the name has to be shared rather than repeated.

## Tests that already exist

- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` (1539 lines) — the harness the brief says to
  reuse: `@Config(sdk = [34])`, the **application's own container** (`(context as LiftoffApplication).container`),
  a `FakeDaemon` started in `@Before` with host/port/token/workspace written into `container.settingsStore`,
  `equipmentDao().insertAll(CoachFixtures.KEYS…)`; `@After` stops the daemon, closes the database, deletes the
  db and settings files.
  - `runWorker(id, runAttemptCount = 0)` (1346) — `TestListenableWorkerBuilder` + `withTimeout(30.seconds)`.
  - `frameFor(method)` / `daemon.framesFor(method)`; `assertNothingReachedTheDaemon()`;
    `assertNoStopFrame()` (1437) for a retry.
  - `pointSettingsAtNothing()` (1330) — a `ServerSocket(0, 0, 127.0.0.1)` opened and closed, settings pointed at
    that port: the unreachable path. `pointSettingsAtTheLiveFake()` puts them back.
  - `insertFlightPlanGeneration(missionId, sortieId)` / `insertOutlineGeneration(missionId)` (1359, 1372) seed
    QUEUED rows with `createdAt = System.currentTimeMillis()`; `assertAwaitingGeneration(sortie)` proves the
    sortie had no plan before the run; `SEEDED_CREATED_AT` for rows seeded as if from an earlier run.
  - Existing tests mutate rows **before** `runWorker` (a deleted row 221-249, a SUCCEEDED row 257-289); none
    mutates a row while the worker is mid-turn — that is what the cancelled-mid-turn test has to add.
- `app/src/test/java/com/liftoff/app/work/GenerationEnqueuerTest.kt` (159 lines) — WorkManager in Robolectric:
  `WorkManagerTestInitHelper.initializeTestWorkManager(context)` in `@Before`, `closeWorkDatabase()` in `@After`;
  `WorkManager.getInstance(context)`; `itemsUnder(name)` = `getWorkInfosForUniqueWork(name).await()` inside
  `withTimeout(5_000)`; names spelled out literally (`"gen-42"`), and the last two tests reach the helper through
  `container.generationEnqueuer()`.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — one fixed `sessionId` for every session this daemon
  sees. `script(vararg steps)` appends one turn; `nextTurn()` (390-394) hands out the next turn **per new prompt**
  and repeats the last one when it runs out, so two generations on one daemon get two scripted turns.
  `logEvent(type, data)` (191) appends an event **and pushes it to every subscriber** without a prompt.
  `refuseMethod[method] = code to message` refuses a call; `answers[key]` records what the client answered a held
  daemon request with; `Ask`/`Permission` hold the rest of the turn until that answer arrives (351-387).
  `rho.session.stop` (274-280) appends a `state_change` `to = "completed"`, `reason = "stopped"` **and pushes it**,
  then answers `{}` — so a stop is also an end to any turn still subscribed, and a second stop is not refused.
  `rho.session.resume` (282-293) appends `to = "running"`, which is not one of `TURN_ENDS`.
  `framesFor(method)` (207) counts frames; `stop()` closes sockets before `server.shutdown()`.
- `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt` — already exercises `markCancelled` and both
  `getNewestNonCancelled…` reads, including the tie-break and "all cancelled → null".

## `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — the push a mid-turn cancel rides on

`append(type, data)` (303-323) appends the event to `log` **and pushes it as a `rho.session.event` to every
subscribed connection**. `rho.session.stop` (274-280) goes through `append`, so the stop the cancel helper sends is
itself the `completed` state_change that ends a turn still being read by the worker. A scripted turn with an
Assistant step and no `DaemonScript.State` therefore never ends on its own — `play` (338) just runs out — which is
what makes the cancelled-mid-turn test forced rather than racy. `rho.session.send_prompt` calls `play(nextTurn())`,
so each new prompt takes the next scripted turn.

## `verify.sh` for this run (written 2026-10-23)

`.rho/runs/cancel-and-supersede-coach-generations-xega/verify.sh` — copied whole from
`.rho/runs/generationworker-resilience-resume-23h1/verify.sh` (gate machinery, `index_reports`, `t`, `c`), with the
header and the test list rewritten. Six named tests, all new to this run (checked: none of the six strings occurs
under `app/src/test` today):

- `cancellingAGenerationStopsItsSessionAndMarksTheRowCancelled` — row CANCELLED + `finishedAt`; exactly one
  `rho.session.stop` naming the row's session; the work the enqueuer scheduled reads `WorkInfo.State.CANCELLED`
  under the same unique name; nothing stored.
- `cancellingAGenerationWithTheDaemonUnreachableStillCancelsTheRowAndItsWork` — closed loopback port; no throw;
  row CANCELLED; work still CANCELLED. (Decision: local-first.)
- `cancellingAGenerationThatAlreadyFinishedLeavesTheRowAndItsStoredResultAlone` — SUCCEEDED and FAILED rows read
  exactly as seeded, stored result intact, no stop frame at all. Whether leftover work is also cancelled for a
  finished id is deliberately not pinned.
- `aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone` — turn scripted with no end state, worker
  started off the test thread, cancel after the prompt frame arrives; nothing stored, row CANCELLED,
  `Result.Success`. No stop-frame count asserted.
- `aSupersededFlightPlanGenerationStoresNothingAndTheNewerGenerationStillStoresItsPlan` — older row stores nothing,
  reads CANCELLED, returns success; then the newer row stores its plan and reads SUCCEEDED.
- `aSupersededOutlineGenerationStoresNothingAndTheNewerGenerationStillStoresItsOutline` — same for two OUTLINE rows
  on one Mission.

Whole-class checks (must not break): `GenerationWorkerTest` (25 existing tests), `GenerationEnqueuerTest`,
`GenerationDaoTest`, `CoachTurnRunnerTest`, `MissionManagerOutlineTest`, `MissionManagerPlanTest`,
`AppContainerTest`. `GenerationCancellerTest` is **not** class-checked: naming a new class would dictate the file
the cancel tests live in, and the six method names are matched across every report.

No doc-row check (ARCHITECTURE.md's M3 row is reviewed by Kyle in the PR). Schema check compares `app/schemas`
against `28a7763^` (the commit that added this run's brief.md) both committed and in the working tree after the
gate. Gate: `timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, results dir
cleared first, retried up to 3× only on the known `doneBodyweightSetStepperShowsBW` flake, a daemon/memory death,
or a hung suite that wrote no reports.

Measured against untouched code (one detached run of a copy under `app/build/`, 2026-10-23): gate attempt 1 passed
in 2m13s, `BUILD SUCCESSFUL`, reports **618 tests, 0 failures, 0 errors, 0 skipped**, `:app:testDebugUnitTest`
executed (not FROM-CACHE); the six new names all reported "did not run"; the seven class checks and the schema
check passed; **Result: 9 passed, 6 failed → EXIT=1**. That is the script doing its job.

## Build facts on this machine

- `gradlew.sh` sits at the repository root; scratch scripts go under `app/build/` after `mkdir -p app/build`,
  and the run's verdict is the log plus an `EXIT=$?` sentinel with no pipe after the build.
- Room 2.6.1 (`room-runtime`, `room-ktx`), WorkManager `work-runtime-ktx` 2.9.1, `work-testing` 2.9.1
  (`app/build.gradle.kts:73-86`).
- Known hang from the android-dev skill: a Room `Flow` read with `.first()` inside `runBlocking` under
  Robolectric deadlocks. `storeFinishedTurn` already reads `observeActive().first()`; keep such reads out of any
  new transaction block.
