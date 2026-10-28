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
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` (114) —
  `getNewestNonCancelledForSortie(sortieId)` (line 76): `WHERE sortieId = :sortieId AND status != 'CANCELLED'
  ORDER BY createdAt DESC, id DESC LIMIT 1` — **no `kind` filter**. `observeNewestNonCancelledOutlineForMission`
  (line 90) is the Flow precedent for the same query. `getOpenFlightPlanForSortie` (line 106) filters
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
