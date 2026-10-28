# Map — sortie generation status on the Launchpad

Branch base: `34bedce` (Regenerate, PR #46). Docs: `CLAUDE.md` (gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, commit style `area: lowercase summary`, stage named files only), `ARCHITECTURE.md` (invariants + milestones), `DESIGN.md` §5.4 (line 195), §9 (line 553), §10 (line 580).

## Design rules the brief points at

- **§5.4 Re-fly** (DESIGN.md:195): "When a sortie is PENDING and its generation has failed, or the coach
  can't be reached, the Launchpad offers Re-fly." Copies the most recent landed plan of the same type,
  prefers same focus, planned sets, `source = REFLY`, works offline.
- **§9 Launchpad** (DESIGN.md:561): PENDING shows generation status ("Coach is planning…",
  "Mission Control offline — retrying", or a failure with its reason); Retry, Re-fly, Scrub, plus
  "View in nabu" showing the nabu session id. PLANNED shows the plan summary with Launch, Regenerate, Scrub.
- **§10** (DESIGN.md:586): invalid output → "FAILED with the last error list visible, plus Retry and Re-fly".

## Main code

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` (236 lines) — `sealed interface LaunchpadState`
  with `Loading`, `Draft(weekStart, pattern, missionId, coachStatus)`, `Planned(eyebrow, title, chips, plan,
  sortieId, sortieIndex, sortieType, sortieFocus)`, `Pending(eyebrow, title, chips, sortieId, sortieIndex,
  reFlyAvailable)`, `InFlight(...)`, `Closed(...)`. `LaunchpadStates(db, manager).observe()` =
  `manager.currentWeekStart.flatMapLatest { missionDao().observeWeek(ws) }.flatMapLatest { derive(it) }`.
  - `derive()` (line 93): DRAFT arm is the only one that joins its own flows —
    `combine(planChanges, generationDao().observeNewestNonCancelledOutlineForMission(mission.id), manager.coachConfigured)`
    then `.distinctUntilChanged()`. CLOSED and ACTIVE arms are `planChanges.map { ... }`; ACTIVE's map calls the
    suspend `deriveActive(...)` which does its own DB reads (`flightPlanDao().getPlan`, `reflyCandidates`).
  - `deriveActive` PENDING arm (line 183): `reFlyAvailable = chooseReflySource(current, reflyCandidates(db.sortieDao(), db.flightPlanDao(), current)) != null` —
    today a source plan alone is enough. PLANNED arm (line 154) sets `reFlyAvailable = false` on the Pending fallback
    (a PLANNED sortie with no plan row); the `else` arm (LANDED/SCRUBBED never returned by `currentSortie`) also false.
- `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt` (76) — the shape to copy for sorties:
  `sealed interface CoachStatus { offersConfirm; offersRetry; offersContinueWithoutOutline }` with
  `Editable / Planning / Offline / Failed(reason) / NotConfigured` and `FAILED_WITHOUT_REASON`.
  `deriveCoachStatus(newestOutlineStatus: GenerationStatus?, newestOutlineError: String?,
  newestOutlineIsCoachOffline: Boolean, coachConfigured: Boolean): CoachStatus` — plain values, no Room types
  except the `GenerationStatus` enum. QUEUED/RUNNING → Offline if the mark, else Planning; FAILED → Failed(error or fallback);
  SUCCEEDED/CANCELLED/null → Editable if configured else NotConfigured. Ordering (doc comment at 55-59): a FAILED or open row
  answers on its own, and `coachConfigured` is consulted only when no row is worth reporting — so a FAILED row reads as a
  failure even when the host/token are now blank.
- `app/src/main/java/com/liftoff/app/domain/Coach.kt` — `coachIsConfigured(host, token)`: both non-empty after trim.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — `coachConfigured: Flow<Boolean>` (line 66) =
  `settingsStore.settings.map { coachIsConfigured(...) }.distinctUntilChanged()`. Also
  `regenerateFlightPlan(sortieId, queueGeneration): List<Long>` (PR #46), `refly(sortieId): List<Long>`,
  `scrub(...): Long?`, `currentWeekStart: MutableStateFlow<LocalDate?>`.
- `app/src/main/java/com/liftoff/app/data/Generation.kt` — row fields incl. `nabuSessionId`, `error`, `attempt`.
  `COACH_OFFLINE = "Mission Control offline"`. `isCoachOffline` = `error == COACH_OFFLINE && status in (QUEUED, RUNNING)`
  — so a FAILED row is never "offline" by this property.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` (122) —
  `getNewestNonCancelledForSortie(sortieId)` (line 76): `WHERE sortieId = :sortieId AND status != 'CANCELLED'
  ORDER BY createdAt DESC, id DESC LIMIT 1` — **no `kind` filter**. `observeNewestNonCancelledForSortie(sortieId):
  Flow<Generation?>` (line 84, added by task 1) is its Flow twin: the identical `SELECT`/`WHERE`/`ORDER BY`/`LIMIT`,
  added because a Flow cannot share a `@Query` string with the suspend read and the two must not be able to disagree.
  `observeNewestNonCancelledOutlineForMission` (line 98) is the Flow precedent for the same shape.
  `getOpenFlightPlanForSortie` (line 114) filters
  `kind = 'FLIGHT_PLAN' AND status IN ('QUEUED','RUNNING')`. `markFailed(id, error, finishedAt)` guards on
  `status IN ('QUEUED','RUNNING')`; `setCoachOfflineMark(id, error?)` writes `error` only while open.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the failure texts it writes:
  - `isCurrentGeneration` (line 345) uses `getNewestNonCancelledForSortie(row.sortieId)` for a FLIGHT_PLAN row, i.e. the
    worker's currency check is the same read the new Flow would mirror.
  - invalid output (line 266): `"invalid output: " + reply.errors.joinToString("; ")` — **one line, `"; "`-joined,
    not one per line** (the brief's wording is inaccurate; the lead is glued to the first error).
  - `"coach needed input"` (line 245, Blocked turn), `COACH_UNREACHABLE = "coach unreachable"` (line 471, after
    retries), `"Mission N no longer exists"` / `"Sortie N no longer exists"` (71/74), protocol mismatch (86).
  - `failGeneration` (line 389) → `markFailed(generationId, message, clock.millis())`.
  - Validator error strings (`coach/CoachReplyValidator.kt`): `"sorties: expected 3 sorties for the pattern RLR, got 2"`,
    `"exercises[0].sets[1]: reps and seconds cannot both be set"`, `"exercises[0].equipment: X is not in the equipment list"` —
    no `"; "` inside any of them.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` (250) — `state` =
  `LaunchpadStates(db, manager).observe().stateIn(scope, SharingStarted.Lazily, Loading)`. Actions: `refly()`
  (Pending only, cancels the ids `manager.refly` returns), `regenerate()` (Pending + Planned), `confirmScrub`,
  `retry()` (Draft only), `NOT_CONFIGURED_MESSAGE` (line 18).
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (442) — `PendingActions` (line 365) shows
  `UnderlinedTextButton("Re-fly")` only `if (pending.reFlyAvailable)`; `PlannedActions` (353) has Launch/Scrub;
  draft status line `draftStatusLine` (160) maps `CoachStatus` → line text. Nothing else reads Pending/Planned fields.
- `app/src/main/java/com/liftoff/app/domain/ReFly.kt` — `ReflyCandidate(sortie, hasPlan)`,
  `chooseReflySource(current, candidates)` (LANDED + same type + hasPlan, prefers matching focus, newest by `landedAt`).
  `reflyCandidates(dao, dao, sortie)` lives in `data/` (MissionManager's own helper — used by `refly` and by LaunchpadStates).

## CI and the gate

- `.github/workflows/ci.yml` runs exactly `./gradlew :app:assembleDebug :app:testDebugUnitTest` on temurin JDK 17, ubuntu-latest. No lint/detekt/ktlint step. The live coach test self-skips when `LIFTOFF_LIVE_*` env vars are absent, so it is not a check.
- `app/build.gradle.kts` kills a test task that goes 5 minutes without finishing (`timeoutDuration`-style test-task timeout recorded in earlier runs); a hang writes no `TEST-*.xml` at all.
- `app/src/test/java/com/liftoff/app/data/SchemaExportTest.kt` → `exportedV1SchemaMatchesDatabase()` reads `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json` (the only file under `app/schemas`) and asserts it mentions each entity table. Room re-exports that file at build time, so an entity change rewrites it or adds `2.json`.
- 85 test classes exist under `app/src/test` on this branch. `verify.sh` names 43 of them with `c <ClassName>` (all present) plus `t exportedV1SchemaMatchesDatabase`.

## Tests that already exist

- `app/src/test/java/com/liftoff/app/domain/CoachStatusTest.kt` — plain JUnit over `deriveCoachStatus`; line 53
  uses a multi-error reason `"invalid output: sorties: expected 3 sorties…; …"`.
- `app/src/test/java/com/liftoff/app/ui/launchpad/`: `LaunchpadStatesTest` (Robolectric, `@Config(sdk=[34])`,
  in-memory Room + PreferenceDataStore, fixed `Clock` 2026-01-12 UTC, `zone = ZoneOffset.UTC`; asserts
  `reFlyAvailable` at lines 234/244/257/273), `LaunchpadReFlyTest` (`@Config(sdk=[34], qualifiers="w411dp-h2000dp")`,
  compose rule, `LaunchpadContent` rendered on a real view model), `LaunchpadCoachStatusTest`, `LaunchpadDraftStatusTest`,
  `LaunchpadConfirmTest`, `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LaunchpadRegenerateTest`, `LaunchpadScrubTest`.
- **Which Launchpad tests configure the coach** (host + token): `LaunchpadCoachStatusTest` (156, 325),
  `LaunchpadConfirmTest` (106, 204), `LaunchpadDraftControlsTest` (85), `LaunchpadDraftStatusTest` (106, 422),
  `LaunchpadRegenerateTest` (122, 465, 518, 540; `VALID_HOST`/`VALID_TOKEN` at 616). `LaunchpadStatesTest` and
  `LaunchpadReFlyTest` never set them, so `coachConfigured` is false there (`SettingsStore` defaults both to `""`;
  `LaunchpadCoachStatusTest:323` asserts the blank host). `LaunchpadReFlyTest` seeds no generation rows at all.
- `MissionManager`: `advance` returns null and queues no `FLIGHT_PLAN` row when the coach is not configured (line 456);
  `regenerateFlightPlan` `check(coachIsConfigured(...))` (line 339). So in `LaunchpadStatesTest` / `LaunchpadReFlyTest`
  no open row is ever queued, and under §5.4 (`not configured` → Re-fly allowed) their `reFlyAvailable` assertions at
  `LaunchpadStatesTest` 234/244/257/273 should keep passing unchanged.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt:867` asserts the exact stored error text
  `"invalid output: " + lastErrors.joinToString("; ")` — so the worker's wording must not change.

## Facts added while splitting the plan into tasks

- `MissionManager.advance` (435-472) is the only place a lifecycle call queues a `FLIGHT_PLAN` generation row: the
  `AwaitGeneration` arm returns null at 456 when `coachIsConfigured` is false, else inserts one QUEUED row (459).
  `continueWithoutOutline` (185) calls it at 199, `land` (282) at 296, `scrub` (252) likewise. So a fixture that sets
  host and token **already has** one open row on its current PENDING sortie (that is where a live test's first
  `planning` emission comes from), and a fixture that does not — every existing Launchpad re-fly assertion — has no
  row at all, which is why §5.4 is not expected to flip any of them.
- `refly` (306) checks the sortie is PENDING (313) and `check(source != null)` (316), so a PLANNED sortie cannot re-fly.
- **No test constructs `LaunchpadState.Pending` or `Planned`**; tests only cast (`as LaunchpadState.Pending`), and the
  only construction sites are in `LaunchpadState.kt` — 156 (Planned), 171, 191, 224 (Pending). Adding a field to those
  two data classes is compile-safe once those four sites are updated. `LaunchpadScreen` reads `pending.reFlyAvailable`
  at 371 inside `PendingActions` (365-382, the `else` arm 374-380 is the "No earlier plan to re-fly." line);
  `LaunchpadViewModel` reads only `sortieId` from Pending/Planned.
- `LaunchpadStatesTest`'s re-fly cases: `pendingOffersReFlyWhenALandedPlanOfTheSameTypeExists` (assertFalse 234 before
  anything lands, assertTrue 244 after a LIFT lands with a plan) and `pendingOffersNoReFlyWithoutAMatchingLandedPlan`
  (257, 273). With the coach unconfigured the derivation answers *not configured*, so 244 survives (a source exists)
  and 234/257/273 survive (no source). `LaunchpadReFlyTest`'s three cases are at 188 (Re-fly shown), 226 (the line,
  243), 257 (tap); 215 asserts the line is absent.
- `LaunchpadReFlyTest` can seed what a §5.4 case needs: `settingsStore` (86-89, its own
  `settings.preferences_pb` in `cacheDir`, deleted at 104) and `db.generationDao()` are both in scope; fixtures are
  `landedSourceThenPending()` (139, pattern "LL", lands sortie 0 so sortie 1 is the current PENDING one with a source)
  and `pendingWithNothingLanded()` (157); `awaitOnScreen` (129) waits on an action-row control.
- Recipe for a **PLANNED current sortie** with the coach configured:
  `manager.storePlan(sortie.id, CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))`
  (`LaunchpadRegenerateTest.kt:359`) moves the PENDING sortie to PLANNED with a plan; `activeWeek()` (121) sets host
  and token **before** `onAppOpen()`/`continueWithoutOutline()`.
- Gate baseline on this branch: **704 tests across 84 `TEST-*.xml` report files** (85 test class files under
  `app/src/test`), from the run's own `verify.sh` gate run.
- **Open decision the tasks must settle:** plan.md does not say whether the derivation is one function that takes the
  card kind (PENDING vs PLANNED) or two entry points. `verify.sh`'s names require the derivation itself to answer the
  §5.4 Re-fly offer and to answer PENDING and PLANNED differently; how is the implementer's call, recorded in the commit.

## The live-subscription harness to copy (LaunchpadCoachStatusTest, 350 lines)

`app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadCoachStatusTest.kt` — `@Config(sdk = [34])`, no WorkManager, no daemon, no screen.
- `setUp`: `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`; `settingsFile = File(context.cacheDir, "coach-status-${UUID.randomUUID()}.preferences_pb")` + `SettingsStore(PreferenceDataStoreFactory.create { settingsFile })` (a file of its own per class); `MissionManager(db, settingsStore, clock = clock, zone = { ZoneOffset.UTC })` with `clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` and `weekStart = LocalDate.parse("2026-01-12")`; `states = LaunchpadStates(db, manager)`. `tearDown`: `db.close()` then `settingsFile.delete()`.
- `private val emissions = CopyOnWriteArrayList<LaunchpadState>()`; `CoroutineScope.openSubscription() = launch { states.observe().collect { emissions.add(it) } }` — one collector per case, opened before the writes and left open.
- `awaitDraft(where)` = `withTimeout(AWAIT_MS)` polling `delay(POLL_MS)`; `assertNothingNewEmitted(before, newestStatus, what)` = `withTimeoutOrNull(SETTLE_MS)` for a new emission, then `assertNull` + `assertEquals(before, emissions.size)` + the newest status unchanged.
- `outlineRow(missionId, status, createdAt, finishedAt = null) = Generation(kind = OUTLINE, missionId, sortieId = null, status, createdAt, finishedAt)` — `Generation.createdAt` has no default and `GenerationDao.insert` returns the id; `at(step) = seededAt + step * 1_000L` gives each row its own later instant (rows are ordered by `createdAt`).
- `coachIsSetUp()` = `settingsStore.setDaemonHost("127.0.0.1")` + `setDaemonToken("coach-token")`; the blank-host case reads `settingsStore.settings.first().daemonHost == ""`; `setWeightUnit(WeightUnit.KG)` is the settings write that must not re-emit.

## The check as it stands now (read it, do not trust an older log)

`verify.sh` was rewritten by `972980a` "run: verify.sh rewritten by claude review", **after** the gate run whose log is at `app/build/verify-run.log`. That log shows a section "the PENDING action row under the new rule" and a 19th new name `neitherReFlyNorTheNoEarlierPlanLineIsShownWhileTheCoachIsPlanning`; **neither is in the current `verify.sh`** — do not write that test. What the current script requires is 14 plain-JUnit derivation names, 4 live one-subscription names, 43 `c <ClassName>` class checks, `t exportedV1SchemaMatchesDatabase` (already exists), and the `app/schemas` comparison against the commit that added this run's `brief.md`.
