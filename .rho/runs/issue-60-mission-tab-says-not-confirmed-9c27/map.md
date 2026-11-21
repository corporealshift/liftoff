# Map of the code

Run: issue 60 — Mission tab says "Not confirmed yet" while the outline is generating.

## The two tabs' rule (DESIGN.md)

- `DESIGN.md` §9 (line 593): the **Mission** tab is "This week's pattern, the outline and its notes, and each
  sortie with its state… A confirmed week offers **Reset week**". It lists no Confirm/Retry/Continue actions on
  this tab; the Launchpad (lines 582-589) is where the draft's Confirm and the generation's Retry live.
- `DESIGN.md` §4.2 + `MissionManager` docs: Confirm only *asks* for the outline — the Mission stays DRAFT until
  the coach's outline arrives.
- `ARCHITECTURE.md` invariants 2 (`domain/` and `coach/` are pure Kotlin) and 3 (no new rho protocol methods).
- Gate: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (on this machine the committed `gradlew.sh` is
  Windows-only; see memory `liftoff-gradle-gate-on-linux`).

## Mission tab (the screen being fixed)

- `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt` (257 lines)
  - `sealed interface MissionTabState` — `Loading`, and `Week(missionId, weekStart, status, chips, outlineNotes,
    rows)` with `canReset = status != MissionStatus.DRAFT` (line 28).
  - `class MissionStates(db, manager)`: `observe()` = `manager.currentWeekStart.flatMapLatest { ws ->
    db.missionDao().observeWeek(ws).map { derive(it) } }` (108-116). `derive` is a plain `suspend fun` switching
    on `mission.status` (214-255). **The DRAFT arm reads no generation row and no settings**: `chips =
    deriveChips(mission.pattern, emptyList())`, `outlineNotes = null`, `rows = emptyList()`.
  - Also holds `SortieDetailState` + `observeSortie(sortieId)` (56-100, 119-124) — untouched by this brief.
- `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` (316 lines)
  - `WeekContent` (104-152): TitleBlock `"WEEK OF …" / "Mission"` → `PatternTrack` → outline notes → **the draft
    message at 128-134**: `if (state.status == MissionStatus.DRAFT)` → `Text("Not confirmed yet. Confirm this
    Mission on the Launchpad.", LiftoffType.note().copy(color = Muted))` → sortie rows → `Reset week` when
    `state.canReset`.
  - `MissionScreen` (30-80) builds `MissionViewModel(container.database, container.missionManager, scope,
    cancelGeneration = { container.generationCanceller().cancel(it) }, actionScope)`.
  - `MissionTabPreview` (~264) constructs `MissionTabState.Week(status = ACTIVE, …)` — a call site if `Week` gains
    a field without a default.
- `app/src/main/java/com/liftoff/app/ui/mission/MissionViewModel.kt` (72 lines) — plain holder: `state` from
  `MissionStates.observe()`, plus the Reset-week flow only. **No coach/generation actions.**
- `app/src/main/java/com/liftoff/app/ui/mission/ResetWeekDialog.kt` — unrelated.

## The coach status the Launchpad draft already derives (reuse this)

- `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt` (76 lines) — pure Kotlin.
  - `sealed interface CoachStatus { offersConfirm; offersRetry; offersContinueWithoutOutline }` with `Editable`,
    `Planning`, `Offline`, `Failed(reason)`, `NotConfigured`, and `const val FAILED_WITHOUT_REASON`.
  - `fun deriveCoachStatus(newestOutlineStatus, newestOutlineError, newestOutlineIsCoachOffline, coachConfigured)`:
    QUEUED/RUNNING → `Offline` if the row carries the offline mark else `Planning`; FAILED → `Failed(error ?:
    FAILED_WITHOUT_REASON)`; SUCCEEDED/CANCELLED/null → `Editable` if configured else `NotConfigured`. Line 73's
    comment: *a SUCCEEDED outline activated the mission in the same transaction, so a draft carries no outline to
    show* — so a DRAFT row's newest non-cancelled outline is only ever open, failed, cancelled, or absent.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` (326 lines) — `LaunchpadState.Draft(…,
  coachStatus)`. Its DRAFT arm (120-136) is the shape the Mission tab needs: `combine(planChanges,
  db.generationDao().observeNewestNonCancelledOutlineForMission(mission.id), manager.coachConfigured) { …
  deriveCoachStatus(row?.status, row?.error, row?.isCoachOffline ?: false, configured) }.distinctUntilChanged()`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (544 lines)
  - `draftStatusLine(status, report)` (169-178): `Editable → null`; `Planning → PLANNING_LINE/Muted`;
    `Offline → OFFLINE_LINE/Muted`; `Failed → (report ?: reason)/Red`; `NotConfigured → NOT_CONFIGURED_MESSAGE/Muted`.
  - The strings are **private to this file** (184-187, 256): `PLANNING_LINE = "Coach is planning the week…"`,
    `OFFLINE_LINE = "Mission Control offline — retrying"`, `RE_PLANNING_LINE`, `PENDING_PLANNING_LINE`.
    The only shared coach string in the codebase is `internal const val NOT_CONFIGURED_MESSAGE` in
    `LaunchpadViewModel.kt:18`.
  - `DraftActions` (333-382): the pattern stepper when `patternEditable`; `PrimaryButton("Confirm")` when
    `offersConfirm`; `PrimaryButton("Retry")` when `offersRetry` (takes Confirm's slot); `UnderlinedTextButton(
    "Continue without outline")` when `offersContinueWithoutOutline`. `patternEditable` = not Planning and not
    Offline (181-182).
- `LaunchpadViewModel.kt` — `retry()`, `confirm()`, `continueWithoutOutline()`; `notConfiguredMessage` is a
  view-model-local report a Confirm/Retry left when nothing is configured (145).

## Data side
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — `observeNewestNonCancelledOutlineForMission(missionId):
  Flow<Generation?>` (98) and its suspend twin (91): `kind = 'OUTLINE' AND status != 'CANCELLED'`, newest by
  `createdAt DESC, id DESC`. `getOpenOutlineForMission` (106) is the QUEUED/RUNNING list.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — `confirm(missionId)` (132) inserts one QUEUED
  OUTLINE row and activates nothing; `retry` (~157) refuses unless the newest outline FAILED;
  `continueWithoutOutline` (197) activates the draft and returns the open generation ids; `storeOutline` (~218)
  activates a DRAFT with its notes and sorties; `coachConfigured: Flow<Boolean>` (68).
- `app/src/main/java/com/liftoff/app/data/Enums.kt` — `MissionStatus { DRAFT, ACTIVE, CLOSED }`,
  `GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`.

## Tests that already exist

- `app/src/test/java/com/liftoff/app/ui/mission/MissionStatesTest.kt` — Robolectric + in-memory Room,
  `MissionManager` with a fixed clock (`2026-01-12T00:00:00Z`, UTC); reads the tab state with
  `states.observe().first { it is MissionTabState.Week }`. 12 tests (chips, outline notes, sortie rows, detail).
  **This is where the brief's test goes.**
- `app/src/test/java/com/liftoff/app/ui/mission/MissionTabStateTest.kt` — plain unit test of `Week.canReset`
  (helper `week(status)` at line 10). Another call site if `Week` gains a field.
- `app/src/test/java/com/liftoff/app/ui/mission/MissionViewModelResetTest.kt`, `ResetWeekDialogTest.kt`.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadCoachStatusTest.kt` — the model to copy for the new
  test: Room rows + `awaitDraft { it.coachStatus is CoachStatus.Planning }`, asserting the derived `CoachStatus`
  and its `offers*` flags. Tests: `theDraftReportsPlanningWhenAQueuedOutlineRowIsWrittenToRoom`,
  `theDraftReportsOfflineWhenItsOutlineRowTakesTheCoachOfflineMark`,
  `theDraftReportsFailedWithTheExactReasonItsOutlineRowWasMarkedFailedWith`,
  `theDraftDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow`,
  `theDraftFollowsTheSettingsFromNotConfiguredToEditableWithoutReSubscribing`.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftStatusTest.kt` — Compose-rule screen test that
  asserts the literal strings ("Coach is planning the week…", `NOT_CONFIGURED_MESSAGE`). Only place asserting the
  draft's wording today.
- **Nothing anywhere asserts "Not confirmed yet"** — `grep -rn "Not confirmed yet" app/src` hits only
  `MissionScreen.kt:130`. There is no Compose screen test for the Mission tab.
- `app/src/test/java/com/liftoff/app/domain/CoachStatusTest.kt` — 11 tests over `deriveCoachStatus`.

## Two facts worth having to hand

- **UI strings are private to the file that draws them**, everywhere in `ui/` — `grep "const val .*MESSAGE\|_LINE"`
  shows private consts in `ConnectionViewModel`, `RunLanding`, `LaunchpadScreen` (184-187, 256-257). The one shared
  coach string is `internal const val NOT_CONFIGURED_MESSAGE` in `LaunchpadViewModel.kt:18`, read by
  `LaunchpadScreen.kt` and by tests. So a Mission tab that wants the Launchpad's wording either widens those consts
  or restates them.
- **A DRAFT can legitimately carry a CANCELLED outline row**: `work/AppOpenSweep.kt` cancels an open row whose
  "Mission or sortie has moved on" at each app open, and a mission left DRAFT stays DRAFT. That is the case the plan
  keeps on the "Not confirmed yet" line, so it is worth a test rather than an assumption.
