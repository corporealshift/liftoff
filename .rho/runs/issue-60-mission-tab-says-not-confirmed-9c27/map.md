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
    `Planning`, `Offline`, `Failed(reason)`, `NotConfigured`, and `const val FAILED_WITHOUT_REASON`. The flags,
    since an assertion will read them: Editable `true,false,false`; Planning `false,false,true`; Offline
    `false,false,true`; Failed `false,true,true`; NotConfigured `false,false,true` (in that order:
    offersConfirm, offersRetry, offersContinueWithoutOutline).
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

## The gate this run's verify.sh runs (measured on the untouched tree)

- `bash .rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/verify.sh` on the base commit `baa0eb8`: the
  gate itself is **green** — `BUILD SUCCESSFUL in 1m8s`, `46 actionable tasks: 1 executed, 45 up-to-date`,
  **775 test records over 93 reports, 0 failures, 0 skips** — and the script then exits 1 on the five named
  tests, which is the point. Baseline for comparing later: 775 / 93; the five new names should take it to 780.
- Gradle resolution in the script: `bash gradlew.sh` when `C:/Users/corpo/android-toolchain/gradle/bin/gradle`
  exists (Windows Git Bash), otherwise `$HOME/android-toolchain/gradle/bin/gradle` with
  `JAVA_HOME=$HOME/android-toolchain/jdk` and `ANDROID_HOME=$HOME/android-toolchain/sdk`. `gradlew.sh` is
  never edited. Tasks: `:app:assembleDebug :app:testDebugUnitTest --no-build-cache`, in the CI's order.
- Report format, because the script reads it: Gradle writes one `TEST-<FQCN>.xml` per class under
  `app/build/test-results/testDebugUnitTest/`; the `<testsuite name>` and the file name hold the FQCN, but
  `<testcase name="…">` holds the **bare method name** and `<testcase classname="…">` the FQCN. So a name
  check must use the testcase attribute and take the class from the file name — matching a FQCN prefix
  against the testcase name matches nothing. Backtick names with spaces survive as written
  (e.g. `Reset week is offered only for an ACTIVE mission`); `MissionTabStateTest` uses that style.
- The script moves `app/build/test-results/testDebugUnitTest` aside before each gate attempt, so results left
  by an earlier run cannot satisfy a name, and it fails if the log says
  `:app:testDebugUnitTest FROM-CACHE|UP-TO-DATE|NO-SOURCE`.

## The five names verify.sh requires (the work sessions write these)

HEAD's `verify.sh` is `b13a14c run: verify.sh rewritten by claude review`, which **dropped the sixth
name** the earlier draft had (`theMissionTabDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow`):
its "What is NOT checked" block says re-emitting for unrelated generation writes is an efficiency
concern from the plan's Risks, not a behavior the brief asks for. Copying `LaunchpadCoachStatusTest`'s
`distinctUntilChanged` wiring is still the plan's answer to it; it is just not gated.

All five are new; none exists on the base tree. They are listed with their "must show" comments in
`verify.sh` under "the brief's two rules, as named tests" (lines 186-212):

1. `theMissionTabReportsPlanningWhileItsDraftHasAnOpenOutlineGeneration` — the brief's case (QUEUED or RUNNING).
2. `theMissionTabReportsOfflineWhenItsOpenOutlineRowCarriesTheCoachOfflineMark`
3. `theMissionTabReportsFailedWithTheReasonItsOutlineRowCarriesAndOffersRetryAndContinue` — the row's
   reason verbatim, Retry/Continue offered as the Launchpad's status offers them, and a DRAFT week's
   `canReset` stays false (the tab gains no button).
4. `theMissionTabKeepsTheUnconfirmedDraftStatusWhenNoOutlineGenerationIsOpen` — no row, and a draft whose
   only OUTLINE row is CANCELLED.
5. `theMissionTabFollowsItsOwnOutlineRowFromQueuedToFailedWithoutReSubscribing` — one open subscription:
   unconfirmed → planning on a QUEUED row → failed with the reason on that row's `markFailed`.

verify.sh also requires every test in these classes to pass: `MissionStatesTest` (12 today),
`MissionTabStateTest` (1), `MissionViewModelResetTest` (1), `ResetWeekDialogTest` (3), `CoachStatusTest` (11),
`LaunchpadCoachStatusTest` (5), `LaunchpadDraftStatusTest` (8), `LaunchpadStatesTest` (10),
`LaunchpadDraftControlsTest` (4), `LaunchpadDraftGuardsTest` (2), `LaunchpadConfirmTest` (7).

## Two facts worth having to hand

- **UI strings are private to the file that draws them**, everywhere in `ui/` — `grep "const val .*MESSAGE\|_LINE"`
  shows private consts in `ConnectionViewModel`, `RunLanding`, `LaunchpadScreen` (184-187, 256-257). The one shared
  coach string is `internal const val NOT_CONFIGURED_MESSAGE` in `LaunchpadViewModel.kt:18`, read by
  `LaunchpadScreen.kt` and by tests. So a Mission tab that wants the Launchpad's wording either widens those consts
  or restates them.
- **A DRAFT can legitimately carry a CANCELLED outline row**: `work/AppOpenSweep.kt` cancels an open row whose
  "Mission or sortie has moved on" at each app open, and a mission left DRAFT stays DRAFT. That is the case the plan
  keeps on the "Not confirmed yet" line, so it is worth a test rather than an assumption.

## Added while splitting the plan into tasks

- **`MissionTabState.Week(...)` is constructed in exactly two places outside `MissionState.kt`**:
  `MissionScreen.kt:270` (`MissionTabPreview`) and `MissionTabStateTest.kt:10` (the `week(status)` helper).
  A new field with a default means neither needs an edit; without a default, both do.
- **The wording is pinned by literals, not by the constants.** `LaunchpadDraftStatusTest` writes the strings out
  inline — `"Coach is planning the week\u2026"` (241, 344, 429) and `"Mission Control offline \u2014 retrying"`
  (296), plus substring probes at 213-214, 366-367, 388-389. `LaunchpadPendingStatusTest.kt:347-348` and
  `LaunchpadViewInRhoTest.kt:256` declare **their own local copies** of the same two strings. So lifting
  `PLANNING_LINE`/`OFFLINE_LINE` out of `LaunchpadScreen.kt` breaks no test, but changing one character of either
  string breaks three classes.
- `LaunchpadPendingStatusTest` (pins the PENDING sortie line, which is what reads `OFFLINE_LINE` at
  `LaunchpadScreen.kt:249`) and `LaunchpadPlannedActionsTest` (`RE_PLANNING_LINE`) are not named by `verify.sh`
  but run in the gate, so the wording task has to keep them green too.
- **`MissionStatesTest`'s harness today runs with the coach unconfigured**: `setUp` (44-47) builds a
  `SettingsStore` over a fixed file `settings.preferences_pb`, deleted in `tearDown` (60); no case sets a host or
  a token. A case that wants `coachConfigured == true` must call `settingsStore.setDaemonHost(...)` +
  `setDaemonToken(...)` — `LaunchpadCoachStatusTest.coachIsSetUp` (155-158) is that call pair, and its per-test
  file `coach-status-<uuid>.preferences_pb` (84) is the pattern that keeps a setting change out of another class.
  Its `draftMissionHasNoRows` (177-183) is the only existing DRAFT-arm case.
- **The one-subscription harness to copy for the live-follow test**: `LaunchpadCoachStatusTest` 99-136 —
  `openSubscription()` (one `launch { states.observe().collect { emissions.add(it) } }` job, cancelled in
  `finally`), `awaitDraft { … }` (bounded poll, `AWAIT_MS 5_000` / `POLL_MS 25`), `assertNothingNewEmitted(before,
  newest, what)` (`withTimeoutOrNull(SETTLE_MS 1_500)`). Rows are written with their own later `createdAt`
  (`at(step)`, 153) because the DAO orders by it.
- **DAO calls a state test uses**: `generationDao.insert(Generation(kind = OUTLINE, missionId = …, sortieId = null,
  status = QUEUED, createdAt = …))`, `get(id)`, `setCoachOfflineMark(id, Generation.COACH_OFFLINE)` (68),
  `markFailed(id, error, finishedAt)` (49), `markCancelled(id, finishedAt)` (52).
- **The shape `MissionStates.observe()` has to take on**: today `observe()` (108-116) maps one flow into a
  `suspend fun derive` (214-255); `LaunchpadStates.observe`/`derive` (96-136) is the target — `derive` returns a
  `Flow`, the DRAFT arm is a `combine(planChanges, observeNewestNonCancelledOutlineForMission(mission.id),
  manager.coachConfigured) { … deriveCoachStatus(…) }.distinctUntilChanged()`, and the other arms stay
  `map`/`flowOf`.
