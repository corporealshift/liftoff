# Tasks — issue #88: add a new sortie to a week

Plan: `.rho/runs/issue-88-add-ability-to-add-a-new-g0wj/plan.md`

- [x] Task 1 — domain: add a sortie to a confirmed week
  - `addSortie(mission, type): Pair<Mission, Sortie>` in `domain/Sorties.kt`, refusing through `require` like `swapSortieType`, `overridePattern` and `confirmWithoutOutline` do: it refuses a `DRAFT` mission and a pattern already seven letters long; otherwise it returns the mission with the type's letter appended and status `ACTIVE`, plus a `PENDING` sortie at `index = mission.pattern.length` with `DEFAULT_RUN_FOCUS`/`DEFAULT_LIFT_FOCUS`.
  - `MissionLifecycle.legalTransitions` gains `CLOSED → ACTIVE` in `domain/Missions.kt`, so a finished week can take a workout it didn't plan. `Pattern.kt` names its cap `MAX_PATTERN_LENGTH`, which `isValidPattern` and the new refusal both read.
  - Tests: new `app/src/test/java/com/liftoff/app/domain/SortieAddTest.kt` — `appendsLetterAndSortieAtEnd`, `reopensClosedMission`, `newSortieTakesTheTypesDefaultFocus`, `refusesDraftMission`, `refusesEighthSortie`, `leavesExistingSortiesAlone` (6 tests, green). `MissionsTest.missionLifecycleTransitions`, which had asserted `CLOSED → ACTIVE` was illegal, now asserts it is legal and keeps the other five pairs illegal.
  - Commit: `domain: add a sortie to a confirmed week`

- [x] Task 2 — manager: `MissionManager.addSortie`
  - `suspend fun addSortie(missionId, type, onQueued)` in `data/MissionManager.kt`, shaped like `swapSortieType` (line 484): refuse a mission that is not this week's, or a `DRAFT` one, or one whose pattern is already full; inside one `db.withTransaction` run the domain call, `missionDao().update`, `sortieDao().insert`, then `advance(mission.id, settings)`. Nothing is deleted, so nothing is superseded and the caller has nothing to cancel; the generation `advance` queued is handed to `onQueued` after the commit. The manager's own gates are `check`s, so every refusal it raises is an `IllegalStateException` — the one the UI already catches and re-reads the week on — and `domain.addSortie` keeps its own `require` as the invariant.
  - Tests: new `app/src/test/java/com/liftoff/app/data/MissionManagerAddSortieTest.kt` (harness copied from `MissionManagerSwapTest.kt`) — `addToActiveWeekAppendsPendingSortie`, `addToClosedWeekReopensAndPlansIt`, `closedRunWeekWithoutRunGenerationGetsASimplePlan`, `closedWeekWithoutACoachReopensAndQueuesNothing`, `addDoesNotTouchTheCurrentSortie`, `patternAndSortieCountsStayAligned`, `refusesNonCurrentWeek`, `refusesDraftMission` (8 tests, green). The refusals run through one `assertRefused` helper that shows the Mission row, the sortie rows, the Flight Plan counts and every Generation row are the ones that were there before.
  - Commit: `data: add a sortie to the current week`

- [ ] Task 3 — Mission tab: the Add sortie action
  - `MissionTabState.Week.canAdd` (`status != DRAFT && chips.size < 7`, since `deriveChips` emits one chip per pattern letter).
  - `MissionViewModel`: `adding` state, `requestAdd()`, `cancelAdd()`, `confirmAdd(type)` on `actionScope` like `confirmReset`, with a `queueGeneration` constructor param wired to `container.generationEnqueuer()` in `MissionScreen`.
  - New `ui/mission/AddSortieDialog.kt` (`AddSortieDialog` + `AddSortieDialogContent`, "Add a sortie to this week?", "Add lift" / "Add run" / "Cancel"); an `Add sortie` underlined button in `WeekContent` above `Reset week`.
  - Tests: `MissionTabStateTest` gains `canAdd` cases (active, closed, draft, seven letters); `MissionStatesTest` gains a derived-from-the-database `canAdd` case; new `app/src/test/java/com/liftoff/app/ui/mission/MissionViewModelAddTest.kt` — `requestAddOpensTheDialogOnlyOnAConfirmedWeek`, `confirmAddAppendsTheSortieAndQueuesTheGeneration`, `confirmAddOnAFullWeekWritesNothingAndReReadsTheWeek`, `cancelAddWritesNothing`; new `AddSortieDialogTest.kt` for the wording and both buttons.
  - Commit: `ui: add a sortie to the current week from the Mission tab`

- [ ] Task 4 — DESIGN.md amendment
  - An amendment dated 2026-11-26 in the list after approval, in the style of the 2026-11-23 swap entry, covering §4.1 (a confirmed week can take a sortie, up to the seven cap), §4.2 (a closed week reopens when a sortie is added), and §9 (the Mission tab now offers **Add sortie** beside **Reset week**). `ARCHITECTURE.md` needs no change: it lists no per-tab actions.
  - Commit: `docs: amend DESIGN.md for adding a sortie to a week`

- [ ] Task 5 — full gate and read-back
  - Run CI's command (`:app:assembleDebug :app:testDebugUnitTest`, `.github/workflows/ci.yml`) over the whole suite, fix anything that fails, and read the brief back against the cases: an added sortie is launchable, loggable, and visible on the Mission tab, the Launchpad and the coach prompt.
  - Commit: `run: task 5 done — gate green` plus any fixes.
