# Map

Branch here is the merge of #52 (`55dfc6e`), so issue #53's `error`-arm work is **not** in this tree.
Read the files as they are at HEAD `174093d`.

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

## Docs

- `DESIGN.md` §7.3 (:285-343): step 1 calls a major mismatch a permanent failure; the bullets after step 6 say
  "**Unreachable daemon** (the connection is refused or times out): return `Result.retry()`… UI shows 'Mission
  Control offline'" and "after 8 attempts the generation is marked FAILED, reason 'coach unreachable'". Read
  literally, "the connection is refused" covers a refused token, which is what this change separates out.
  Amendments are listed newest-first under "## Amendments after approval" (:783+) and marked inline in the
  section they affect.
- `ARCHITECTURE.md` :36 already describes `GenerationWorker.kt` as "ending the row FAILED with its reason"; no
  row change needed.
