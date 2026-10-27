# Map — regenerate the current sortie's Flight Plan

Branch `rho/regenerate-the-current-sortie-s-flight-1xa7`, HEAD `e56802c` (plan outline) on top of `a33dea0` (#45, the worker leaves a cancelled generation cancelled).

## Docs that set the rules

- `DESIGN.md` §5.1 — "PLANNED: … The owner can **regenerate** it from here, which replaces the plan." The replacement is `storePlan`'s `writePlan` overwrite, not the act of asking.
- §5.3 Run sorties — the `generateRunPlans` toggle decides whether a run sortie is ever sent to the coach; read at generation time.
- §5.4 Re-fly — offered when a sortie's generation failed or the coach can't be reached.
- §7.2 Generation kinds — `FLIGHT_PLAN` triggers include **"Regenerate"**, alongside outline-stored and land/scrub.
- §7.3 last bullet — **Superseded results**: "when a regeneration is requested while a generation for the same sortie is queued or running, the old one is cancelled: its work is cancelled and its nabu session stopped. Results are stored only if the generation is still the sortie's current one." This is the rule this run implements on the asking side.
- §2 Vocabulary — `Mission`, `Sortie`, `FlightPlan`, `Generation`; never "session" for a sortie. `Mission Control` = the settings screen.
- `CLAUDE.md` — gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages `area: lowercase summary`; stage named files only, never `git add -A`; short comments (why, not what).
- `ARCHITECTURE.md` — M3's row ends "In-Flight's enqueue, **Regenerate** and the Launchpad's display of the current sortie's status come next", so this run is a step inside M3 and that row is what records it. Invariant 5: no DI framework, one `AppContainer`.

## Data layer

`app/src/main/java/com/liftoff/app/data/MissionManager.kt` (423 lines) — every manager operation, each one `db.withTransaction { … }` with `check(...)` refusals that throw `IllegalStateException`.

- `coachConfigured: Flow<Boolean>` (66) = `settingsStore.settings` → `coachIsConfigured(host, token)`.
- `confirm` (120) / `retryOutline` (152) — the outline path. Both read settings **before** the transaction and return `ConfirmOutcome.CoachNotConfigured` instead of throwing when unconfigured. `retryOutline` refuses unless the newest non-cancelled outline is FAILED, so a second Retry refuses while one is open — the opposite of what a Flight Plan regenerate does.
- `continueWithoutOutline(missionId, onQueued: (Long) -> Unit = {})` (185) — the shape to copy for "hand ids out of a transaction": read the open rows inside, return them for the caller to cancel, and call `onQueued` with the newly queued id **after** the transaction commits.
- `scrub` (252) / `launch` (267) / `refly` (306) — the guard shape for a sortie operation: `check(mission.status == ACTIVE)`, then `currentSortie(sorties)` must equal this `sortieId`, then the sortie-state check. **None of them re-checks the week**, so the new operation must add that guard itself: `continueWithoutOutline` (191) compares `mission.weekStart == currentWeekOf(clock, zone)`, and `storeOutline` (233) uses `weekHasEnded(mission.weekStart, clock.instant().atZone(zone()).toLocalDate())`. The brief requires "the current week's ACTIVE Mission", so use the `weekStart` comparison.
- `refly` (306-323) is the closest model: it ends with `db.generationDao().getOpenFlightPlanForSortie(sortieId).map { it.id }` as its return value, i.e. "here are this sortie's open rows, cancel them".
- `storePlan(sortieId, valid)` (335-374) — refuses (`StoreOutcome.Refused`, not an exception) when the mission isn't ACTIVE, the sortie isn't PENDING/PLANNED, or it isn't the current sortie. `writePlan` overwrites whatever plan the sortie holds; the sortie moves to PLANNED only if it was PENDING, so a plan landing on a PLANNED sortie leaves it PLANNED. This is what a regenerate's result eventually lands on.
- `advance(missionId, settings)` (382-419, private) — the QUEUED FLIGHT_PLAN insert to copy (406-414): `Generation(kind = FLIGHT_PLAN, missionId, sortieId = current.id, status = QUEUED, createdAt = clock.millis())`. It returns an existing open row's id instead of inserting a second one (404-405), and returns null when the coach isn't configured (403) or the prep is a `SimplePlan` (400: run with the toggle off → `writePlan` a simple run plan and `markPlanned` the sortie, no generation).
- `continueWithoutOutline` (199) and `storeOutline` (244) both call `advance` inside their transaction, so **the fixture that activates a week already leaves one open `FLIGHT_PLAN` row on the new current sortie** — when host+token are set. With no token, activation happens but no row is queued, so an unconfigured refusal case needs no open row to exist.
- `settings.generateRunPlans` is read **only** in `advance` → `domain/SortiePlanning.kt:13 prepareCurrent(sortie, generateRunPlans)` (`SortieType.RUN && !generateRunPlans` → `Preparation.SimplePlan`, else `AwaitGeneration`). Neither `GenerationWorker` nor the Launchpad consults it, so a queued `FLIGHT_PLAN` for a run sortie is sent to the coach whatever the toggle says. Default is false (`Settings.kt:16`).
- `currentWeekOf(clock, zone)` (421, private) and `currentWeekStart: MutableStateFlow<LocalDate?>` (63).

`GenerationDao.kt` (114 lines):
- `getOpenFlightPlanForSortie(sortieId)` (102-106) — `kind = 'FLIGHT_PLAN' AND status IN ('QUEUED','RUNNING')`, **newest first** (`createdAt DESC, id DESC`).
- `getNewestNonCancelledForSortie(sortieId)` (72-76) — the currency read the worker stores against; `status != 'CANCELLED'`, `createdAt DESC, id DESC LIMIT 1`. The `id DESC` tie-break is what makes a row inserted in the same millisecond as an older one the current one.
- `insert` (10), `get` (13), `markCancelled(id, finishedAt)` (52, no status guard), `getOpen()` (111, whole-table sweep read).

`Generation.kt` — `id, kind, missionId, sortieId?, status, nabuSessionId, lastEventId, attempt, error, createdAt, finishedAt`; `COACH_OFFLINE = "Mission Control offline"` and `isCoachOffline` (only while QUEUED/RUNNING). No new column is needed for this run.

## Work layer

- `GenerationEnqueuer.enqueue(generationId)` — unique work `gen-<id>` (`uniqueName`, file-level `internal fun`), `ExistingWorkPolicy.KEEP`, network-connected constraint, 1-minute exponential backoff. Creates no row.
- `GenerationCanceller.cancel(generationId)` — cancels the unique work, then in one transaction marks the row CANCELLED **only while it is QUEUED or RUNNING** (a row that already decided its outcome is left alone), then gives up the rho session last, swallowing a daemon that isn't there.
- `AppOpenSweep.run(weekStart)` — at each app open splits every open row into re-enqueue (target still current: mission is the current week's and ACTIVE, sortie is `currentSortie` and PENDING/PLANNED) or cancel. It judges each row by its own target, **not** by newest, so two open FLIGHT_PLAN rows on the same current sortie are both wanted.
- `GenerationWorker` — `GENERATION_ID = "generationId"` input key (431); `isCurrentGeneration` (340-345) uses `getNewestNonCancelledForSortie` for a FLIGHT_PLAN row, and the store + currency check + mark share one transaction: a superseded row marks itself CANCELLED (297-299) and ends as done, storing nothing. `doWork` also returns early for a row that already decided its outcome, and `markRunning`/`markFailed` carry `status IN ('QUEUED','RUNNING')` guards.

## View model and state

`ui/launchpad/LaunchpadViewModel.kt` (224 lines) — `LaunchpadViewModel(db, manager, scope, queueGeneration: (Long) -> Unit, cancelGeneration: suspend (Long) -> Unit)`.
- Every action reads `state.value`, returns `null` when the state isn't the one it applies to, else `scope.launch { … }`.
- Every action's catch is `catch (_: IllegalStateException) { runCatching { manager.onAppOpen() } }` — a re-sync, not a message. **The brief's line "Every action calls `manager.onAppOpen()` first" is not what the code does**: `MainActivity` (31) calls it on cold start, and the view model calls it only in that catch.
- `continueWithoutOutline()` (111) is the enqueue-then-cancel model; `refly()` (211) is the cancel-loop model (`for (id in manager.refly(...)) cancelGeneration(id)`).
- `notConfiguredMessage` (36) is set only by `queueOrReport` (142) from `ConfirmOutcome.CoachNotConfigured`, and `LaunchpadScreen` (145) draws it only in the **Draft** status line. Nothing on a Pending or Planned screen reads it.
- `AppContainer.generationEnqueuer()` (39) / `generationCanceller()` (46); `MainActivity` (39-40) already passes them as the view model's two lambdas, so no wiring change is needed.

`ui/launchpad/LaunchpadState.kt` (236 lines) — `LaunchpadState.Pending(eyebrow, title, chips, sortieId, sortieIndex, reFlyAvailable)` (42) and `Planned(eyebrow, title, chips, plan, sortieId, sortieIndex, sortieType, sortieFocus)` (30). Neither carries any generation or coach status. `LaunchpadStates.derive` (93) joins live flows only in the **DRAFT** arm (99-115, `deriveCoachStatus` from the newest outline row + `manager.coachConfigured`); the ACTIVE arm maps over `flightPlanDao().observeChanges()` only. A PLANNED sortie with no plan row falls back to `Pending` (166-180). `LaunchpadScreen`: `PlannedActions` = Launch + Scrub (353), `PendingActions` = Re-fly + Scrub (367).

## Tests that already exist (harnesses to copy, and things that must stay green)

- `ui/launchpad/LaunchpadConfirmTest.kt` (341) — the harness the brief names: fixed `Clock.fixed(2026-01-12T00:00:00Z, UTC)`, `zone = { ZoneOffset.UTC }`, in-memory Room with `allowMainThreadQueries()`, a real `SettingsStore` over a `PreferenceDataStoreFactory` file in `cacheDir` (deleted in `tearDown`), `CoroutineScope(Dispatchers.Unconfined + job + CoroutineExceptionHandler → escaped)`, recording `queuedIds`/`cancelledIds` where the cancel lambda **also** calls `generationDao.markCancelled(it, clock.millis())` so a read-back is real, `joinAction(launched, vm)` that throws `AssertionError` on a null `Job`, `withTimeout(5_000)` on every wait, `job.cancel()` before `db.close()` in `tearDown` (the `stateIn(Lazily)` flow is still collecting). `primedDraft` sets host, token and `defaultPattern("LRL")` **before** `manager.onAppOpen()`.
- `ui/launchpad/LaunchpadReFlyTest.kt` (291) — the fixture recipe for an ACTIVE week with a PENDING current sortie: `setDefaultPattern("LL")` → `manager.onAppOpen()` (gives a DRAFT) → `manager.continueWithoutOutline(mission.id)` (activates it). It is a Compose test (`@Config(sdk=[34], qualifiers="w411dp-h2000dp")`); the new tests do not need Compose.
- Fixture variants the new cases need: a **PLANNED** current sortie is `setDefaultPattern("RL")` with the toggle left off — `advance` writes the simple "Run" plan (`source = SIMPLE_RUN`) and `markPlanned`, and queues no generation; a **not-current** PENDING sortie is any pattern with a second, still-PENDING sortie after the current one; **unconfigured** is host or token left unset, which also means no open row exists to cancel.
- `data/MissionManagerPlanTest.kt` — `storePlan`'s refusals ("not the current sortie", IN_FLIGHT, LANDED, SCRUBBED) and its use of `coach/CoachFixtures.validReply(fixture, context)` to build a `CoachReplyResult.Valid` from the recorded reply fixtures.
- Also green today: `MissionManagerTest`, `MissionManagerOutlineTest`, `MissionManagerReFlyTest`, `LaunchpadScrubTest`, `LaunchpadStatesTest`, `LaunchpadDraft*`, `LaunchpadCoachStatusTest`, `GenerationWorkerTest`, `GenerationCancellerTest`, `GenerationEnqueuerTest`, `GenerationDaoTest`, `AppOpenSweepTest`, `FlightPlanDaoTest`, `SortiePlanningTest`, `ReFlyTest`, `LiftoffShellTest`, `MainActivityTest`, `ShellNavTest`, `AppContainerTest`. Baseline whole gate: ~694 tests across 83 classes.

## Domain helpers

- `domain/Coach.kt` — `coachIsConfigured(daemonHost, daemonToken)`: both non-empty after `trim()`.
- `domain/Sorties.kt` — `currentSortie(sorties)`: lowest `index` that is not LANDED or SCRUBBED.
- `domain/Rollover.kt` — at app open, a past mission whose week ended is CLOSED and its unfinished sorties SCRUBBED (`WEEK_ENDED`), so an ACTIVE mission with a live current sortie is the current week's in practice; that is why `scrub`/`launch`/`refly`/`storePlan` check only `status == ACTIVE` + current sortie.
