# Map

Branch here is the merge of #52 (`55dfc6e`), so issue #53's `error`-arm work is **not** in this tree.
Read the files as they are at HEAD `174093d` (the run's own commits since then are `783be7e` plan, `97bf8f1`
verify, and the tasks commit — none of them touches app code).

## The code the brief names

`app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` (486 lines)
- `doWork`: row read → `FINISHED_STATUSES` short-circuit (:62) → `markRunning(generationId)` guard (:66,
  0 rows = a cancel or a late duplicate → `Result.success()`) → Mission/Sortie lookups, each missing one
  already going through `failGeneration(..., client = null, sessionId = null)` (:71, :74).
- The connection `when` (:77-101): `Connected` → clears the offline mark and runs the turn;
  `ProtocolMismatch` → `failGeneration` with `mismatchMessage(...)` (:86-92, `client = null`);
  `Unreachable` → `coachUnreachable` (:96); **`else -> Result.retry()` (:100)** — this arm is what
  `NotConfigured` and `AuthenticationRejected` fall into. It writes nothing to the row, so the row stays
  RUNNING with no reason and WorkManager retries forever.
- `coachUnreachable` (:175-181): `runAttemptCount < GIVE_UP_AT_ATTEMPT` → `setCoachOfflineMark(id,
  Generation.COACH_OFFLINE)` + `Result.retry()`; at 7 (the 8th attempt, WorkManager counts from 0) →
  `failGeneration(..., COACH_UNREACHABLE, client = null, sessionId = null)`.
- `failGeneration` (:389-403) is the one permanent end already used by every other permanent failure:
  `markFailed(id, message, clock.millis())`, then `giveUpSession` **only when both `client` and
  `sessionId` are non-null**, a row that reads CANCELLED → `Result.success()`, otherwise `Result.failure()`.
  A connection-check failure has no client and no session, so it is called exactly as the mismatch and
  missing-Mission arms call it: `client = null, sessionId = null`.
- File-level constants (:462-484): `TURN_LIMIT` 20 min, `MAX_REPAIRS` 2, `GIVE_UP_AT_ATTEMPT = 7`,
  `COACH_UNREACHABLE = "coach unreachable"`, `mismatchMessage(...)`. New reason strings would sit here.
- `runGeneration` (:105-167) is only reached from the `Connected` arm; a `client.connect()` refusal also
  goes to `coachUnreachable` (:119).

`app/src/main/java/com/liftoff/app/rho/DaemonConnectionCheck.kt`
- `ConnectionOutcome` sealed interface (:26-48): `Connected`, `NotConfigured` (data object),
  `Unreachable(reason)`, `AuthenticationRejected` (data object), `ProtocolMismatch(expected, daemon, message)`.
  Because it is sealed, the worker's `else` covers exactly `NotConfigured` + `AuthenticationRejected`; naming
  both arms makes the `when` exhaustive, so a future outcome is a compile error rather than another silent retry.
- `check()` returns `NotConfigured` as its first step when the trimmed host or token is empty (:97) — no
  socket is opened at all. `AuthenticationRejected` comes only from a 401 at the WebSocket upgrade, in
  `onFailure` (:184), before any JSON-RPC frame is sent.

## What a FAILED row already does (nothing to build here)

- `data/GenerationDao.kt`: `markRunning` (:37-38) is guarded to `QUEUED`/`RUNNING`, so a row already RUNNING
  from an earlier attempt still returns 1 and the worker proceeds. `markFailed` (:45-49) writes
  `status='FAILED'`, `error=<reason>`, `finishedAt`, guarded to rows reading `QUEUED` or `RUNNING` — so a row
  already RUNNING is a valid target, and a row the user cancelled keeps its own record. It does not clear
  `nabuSessionId` or `lastEventId`. `setCoachOfflineMark` (:67-68) writes the same `error` column, so the new
  reason replaces any "Mission Control offline" text the row carried.
- `data/Generation.kt`: `COACH_OFFLINE = "Mission Control offline"` (:24); `isCoachOffline` is
  `error == COACH_OFFLINE && status is QUEUED or RUNNING` (:28-30), so a FAILED row is never read as offline.
- `domain/CoachStatus.kt`: a FAILED outline row → `CoachStatus.Failed(reason)` with the reason kept word for
  word, `offersRetry = true`, `offersContinueWithoutOutline = true`. `CoachStatus.NotConfigured` only answers
  when no row is worth reporting, so a FAILED row wins over the settings.
- `domain/SortieGenerationStatus.kt`: FAILED → `failedFrom(error)` (:108); only the `"invalid output: "` lead is
  parsed out, so a plain sentence passes through unchanged. `offersReFly` (:134) needs
  `hasReFlySourcePlan` as well as a Failed/Offline/NotConfigured answer — unchanged by this work.
- Nothing in the existing suite blanks the daemon host or token for a worker run. The only blanks in a test are
  `LaunchpadRegenerateTest.kt:407-408`, and that harness records queue/cancel lambdas instead of running
  WorkManager, so changing the two arms breaks no current case.
- `ui/launchpad/LaunchpadScreen.kt`: :176 `DraftLine(report ?: status.reason, Red)`, :247
  `PendingLine(status.reason, Red, status.validatorErrors)` — the row's reason is the card's line.
- `ui/launchpad/LaunchpadViewModel.kt` :19 `NOT_CONFIGURED_MESSAGE = "Mission Control isn't set up. Add the
  host and token in Mission Control."`, used at :145 when a Retry/Confirm answers `CoachNotConfigured`. So a
  Retry tapped while the settings are still blank says which setting to fix rather than queueing anything.
- `MissionManager` refuses to queue at all when Mission Control isn't configured (`ConfirmOutcome.CoachNotConfigured`,
  :128, :160), which is why a `NotConfigured` row only exists when the settings were cleared after the row was
  queued, or came in through an Import.

## Adjacent wording that this touches

- `work/AppOpenSweep.kt` KDoc: "The sweep reads no settings: a row asked for while Mission Control was
  configured is still resumed under the worker's offline handling when it isn't." That sentence is what the
  fix changes — a blank host/token now ends the row instead of being resumed.
- `ui/control/ConnectionViewModel.kt` :108 `REFUSED_TOKEN_MESSAGE = "Mission Control refused this token — check
  it matches the PC"` and `missingSettingsRow(...)` (:100) — Mission Control's Test-connection wording, which is
  deliberately more specific than the row reason the brief names. Nothing there changes.

## Tests that already exist

`app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — `doWork()` over a real socket to a scripted
`FakeDaemon`, on the application's own container (`container` from `LiftoffApplication`); settings are written
into `container.settingsStore` in `@Before` (:82-90) and the container re-reads them per call.
- `runWorker(generationId, runAttemptCount = 0)` (:1748) builds the worker directly with
  `TestListenableWorkerBuilder` under a 30 s `withTimeout`.
- `pointSettingsAtNothing()` (:1732) — closed loopback port → `Unreachable`.
  `pointSettingsAtTheLiveFake()` (:1739).
- `insertOutlineGeneration` (:1782) / `insertFlightPlanGeneration` (:1765).
- Wire assertions: `assertNothingReachedTheDaemon()` (:1833, no frames at all), `assertSessionLeftOpen()`
  (:1853), `assertSessionGivenUpOnce(id)` (:1843), `assertNoSessionWasOpened()` (:1884 — the check's own hello
  was sent, no create/prompt/stop).
- `mismatchFailedRow(generationId, missionId, error)` (:1865) — the reusable "permanent end from the connection
  check" row assertion: FAILED, `error` exactly the reason, `finishedAt` stamped, `nabuSessionId` null, nothing
  stored. Used by the three mismatch cases (:1133, :1159, :1184, via `daemon.refuseProtocolMismatch`).
- The offline cases: `anUnreachableDaemonMarksTheGenerationOfflineAndRetries` (:1349),
  `aLaterRunThatReachesTheDaemonClearsTheOfflineMark` (:1402),
  `theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable` (:1447).

`app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`
- `refuseUpgrade` (:94) answers the WebSocket upgrade with 401 + `DISCONNECT_AT_END` (:161-168), which is the
  recipe `DaemonConnectionCheckTest.aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable` (:166-171)
  uses to get `AuthenticationRejected`. It refuses whatever token is set, so a worker case only has to set the
  flag before running the worker.
- `helloResult` (:87), `refuseMethod`, `framesFor(method)`, `requests`/`requestCount()`.

## Build / gate facts

- CI (`.github/workflows/ci.yml`) runs exactly `./gradlew :app:assembleDebug :app:testDebugUnitTest` on
  ubuntu-latest, Java 17, 20-minute job timeout. On this machine the wrapper is `bash gradlew.sh` (pins the JDK,
  SDK and Gradle under `~/android-toolchain`; none of it is on PATH).
- `app/build.gradle.kts` kills the test task at 5 minutes, so a hang writes no `TEST-*.xml` at all.
- Two known timing flakes in tests this run does not touch, both allowed to be retried by `verify.sh`:
  `doneBodyweightSetStepperShowsBW` (`ui/inflight/InFlightScreenTest.kt:601`, Room pool closed under a concurrent
  `observe()`) and `aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt`
  (`ui/launchpad/LaunchpadSortieStatusTest.kt:304` — its fix `awaitPlanned(from = before)` landed on
  `rho/issue-53-…` at `8a10469` and is **not** in this tree, so it can still bite a slow runner).
- Whole-suite baseline at this HEAD (`174093d`, the merge of #52): **736 tests in 89 reports**, 0 failures
  (measured by this run's own gate, `app/build/verify-run.log`, 2m46s). The 739-test figure belongs to the
  issue-53 branch, which adds tests this tree does not have.

## Harness details the two new cases need (read off the file)

- `GenerationWorkerTest` `@Before seedContainer` (:82-90): `FakeDaemon().start()`, then
  `container.settingsStore.setDaemonHost(daemon.host())` / `setDaemonPort(...)` / `setDaemonToken(SECRET_TOKEN)` /
  `setCoachWorkspacePath(...)`, and `equipmentDao().insertAll(CoachFixtures.KEYS.map { Equipment(key = it, name = it) })`.
  `@After` stops the fake, closes `container.database`, deletes the DB and the settings DataStore file.
- Blank settings for the `NotConfigured` case: `container.settingsStore.setDaemonHost("")` or `setDaemonToken("")`
  (`SettingsStore.kt:46` has `setDaemonHost`; the container re-reads `settings.first()` per call).
  `pointSettingsAtNothing()` (:1732) is the *Unreachable* recipe (closed loopback port), not this one.
- `runWorker(generationId, runAttemptCount = 0)` (:1748) — `TestListenableWorkerBuilder` under a 30 s `withTimeout`.
- `FakeDaemon.refuseUpgrade` (:94) → the dispatcher answers the upgrade with 401 + `DISCONNECT_AT_END` (:161-168).
  `DaemonConnectionCheckTest.aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable` (:166-171) sets it and
  asserts `ConnectionOutcome.AuthenticationRejected`.
- Existing cases the class check must keep green: `anUnreachableDaemonMarksTheGenerationOfflineAndRetries` (:1349),
  `aLaterRunThatReachesTheDaemonClearsTheOfflineMark` (:1402),
  `theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable` (:1447), and the three mismatch cases
  (:1133, :1159, :1184) which use `mismatchFailedRow(generationId, missionId, error)` (:1865) — FAILED, `error`
  exactly the reason, `finishedAt` stamped, `nabuSessionId` null, mission still DRAFT, no sorties.
- Test classes named by `verify.sh` as must-not-break: `GenerationWorkerTest`, `DaemonConnectionCheckTest`,
  `CoachStatusTest`, `SortieGenerationStatusTest`, `AppOpenSweepTest` (all exist under `app/src/test/java`).

## verify.sh (committed)

Two names, one per outcome, both new to this run and both absent today — that is what makes the script fail on
untouched code:
- `aBlankHostOrTokenFailsTheGenerationAsMissionControlIsntSetUp`
- `aRefusedTokenFailsTheGenerationAsMissionControlRefusedTheToken`

plus `c` class checks on `GenerationWorkerTest`, `DaemonConnectionCheckTest`, `CoachStatusTest`,
`SortieGenerationStatusTest`, `AppOpenSweepTest`, and the CI gate as one `bash gradlew.sh :app:assembleDebug
:app:testDebugUnitTest --no-build-cache` run (results dir cleared first, up to 3 attempts, retrying only the two
known flakes / a daemon death / a hung suite that wrote no reports).

Ran against the untouched tree: gate attempt 1 passed (736 tests, 0 failures, 89 reports), the two names reported
"did not run", `Result: 6 passed, 2 failed`, exit 1. The matcher was separately checked against the same reports
with two existing worker names, which both resolved to pass, so a test written with one of the two names above will
be found.

## Tasks (written at the tasks step)

`tasks.md` splits the plan's Order into five committed tasks: 1 the worker's two named arms + the two reason
constants; 2 `aBlankHostOrTokenFailsTheGenerationAsMissionControlIsntSetUp`; 3
`aRefusedTokenFailsTheGenerationAsMissionControlRefusedTheToken`; 4 the `AppOpenSweep.kt` KDoc sentence; 5 the cold
whole-suite run read against the five classes `verify.sh` names. Tasks 1 and 5 run the whole suite, tasks 2-4 a
class-scoped run. Expected counts: 736 tests at task 1 (no test added), 738 by task 5, 89 reports throughout.

Confirmed by reading, for the sessions that follow:

- `GenerationWorker.kt` :98-100 is the arm to replace, comment included:
  `// A host or a token that isn't set, or a token the daemon won't take, is a setting of Liftoff's own, not Mission
  Control being down, so the row carries no claim about the coach.` followed by `else -> Result.retry()`. The other
  three `Result.retry()`s in the file (:162, :178, :251) are the offline/turn paths and stay.
- `AppOpenSweep.kt` :29-30 verbatim: "The sweep reads no settings: a row asked for while Mission Control was
  configured is still resumed under the worker's offline handling when it isn't. And it never queues a generation
  the app did not already ask for." The old wording to reject is "is still resumed".
- Neither new test name exists anywhere in `app/src/test` yet. `CoachStatusTest.kt:66` has a similarly-named but
  different test (`aBlankHostOrTokenWithNoOutlineRowIsNotConfiguredAndOffersContinueWithoutOutlineButNotConfirm`),
  which is why `verify.sh`'s `t` matcher matches on the whole method name.
- Gradle's JUnit `testsuite` line attribute order here is
  `tests="N" skipped="N" failures="N" errors="N"`, so `grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"'`
  over `TEST-*.xml` is a reliable way to total a run.

## Docs

- `DESIGN.md` §7.3 (:285-343): step 1 calls a major mismatch a permanent failure; the bullets after step 6 say
  "**Unreachable daemon** (the connection is refused or times out): return `Result.retry()`… UI shows 'Mission
  Control offline'" and "after 8 attempts the generation is marked FAILED, reason 'coach unreachable'". Read
  literally, "the connection is refused" covers a refused token, which is what this change separates out.
  Amendments are listed newest-first under "## Amendments after approval" (:783+) and marked inline in the
  section they affect.
- `ARCHITECTURE.md` :36 already describes `GenerationWorker.kt` as "ending the row FAILED with its reason"; no
  row change needed.
- Convention check for whether `DESIGN.md` should be amended: the worker's own behavior PRs (#37 resume/reconnect/
  offline, #44 sweep, #45 cancelled rows) each updated an `ARCHITECTURE.md` row and left `DESIGN.md` alone;
  `DESIGN.md` was last touched by #30. So this run amends neither doc file.
