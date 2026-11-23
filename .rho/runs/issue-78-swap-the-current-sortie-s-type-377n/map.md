# Map — issue 78 (swap the current sortie's type)

Line numbers from `grep -n` / `sed -n` on the tree at `HEAD = 4bea6e8`.

## Project rules that bind this work

- `CLAUDE.md` — "Design changes first": a change DESIGN.md does not already describe gets an
  **amendment entry at the end of DESIGN.md** (newest first) plus an inline
  `(amended <date> — <change>)` mark at the affected section. Amendments are not renumbered.
  Gate: `./gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (the committed launcher is
  Windows-only; on this machine use the local toolchain paths — memory `liftoff-gradle-gate-on-linux`).
  Commit style: `scope: what`, imperative, ≤72 chars.
- `ARCHITECTURE.md` — `domain/` and `coach/` are pure Kotlin (no Android/Room imports), so a rule a
  test must assert lives there. "A rule with no test is a rule that can break without anyone noticing."

## DESIGN.md

- §4.1 Weekly pattern — line 117: "Once confirmed, the pattern is frozen." **The relaxation.**
- §4.2 Mission lifecycle — 119-148; line 147 (amended 2026-10-31) is the `Continue without outline`
  entry: `L R L R L`, **focus `full body` for a lift and `easy` for a run**, no notes. That is where
  the swap's defaults come from.
- §4.3 Rolling order — 149-159: "Sorties cannot be reordered within a Mission (v1)"; the letter at
  each index is what the sortie is. Needs a mark: a swap changes a letter, not the count or the order.
- §5.1 Sortie states — 162-184: the state diagram and the actions. No `PLANNED → PENDING` edge today;
  the swap needs one. Line 181 (amended 2026-11-02) is the Scrub mark — the format to copy.
- §5.3 Run sorties — 191-205: a simple run plan "is created at the moment a Flight Plan generation
  would otherwise be queued for it". A swap to a run is exactly such a moment, so the offline arm is
  already described here.
- §5.4 Re-fly — 206-214, and its amendment entry (2026-10-29): "No confirmation, because nothing is
  lost" — the precedent for the swap's dialog rule.
- §9 Screens — 574-604: the Launchpad paragraph (582-592) lists the actions on the current sortie;
  line 592 (amended 2026-11-02) shows the inline-mark style. Mission paragraph 594-598.
- §13 Testing — 697: "The pattern can be overridden while a draft and is frozen on confirm." Becomes
  untrue as written once a swap exists; the amendment should qualify it.
- §15 Decisions table — 772-806; §16 Assumptions — 808-819.
- `## Amendments after approval` — 821+, newest first, `### <date> — <title>` with a **Rule:** line and
  a **Reason:** line. Newest entry today: 2026-11-02 (Scrub).

## Domain (pure Kotlin)

- `domain/SortiePlanning.kt` (65 lines) — `Preparation` sealed interface (8: `SimplePlan(draft)`,
  `AwaitGeneration`); `prepareCurrent(sortie, generateRunPlans: Boolean)` (13) — the arm the brief means
  by "prepared as the current sortie normally is": a `RUN` with `generateRunPlans == false` gets
  `SimplePlan`, everything else `AwaitGeneration`; `simpleRunPlan(sortie)` (21) — title "Run",
  `estimatedMinutes = null`, `notes = sortie.focus`, `source = SIMPLE_RUN`. A swapped run's simple plan
  carries the new default focus for free, because its notes are the focus.
- `domain/Sorties.kt` (101 lines) — `SortieTransitions.legalTransitions` (7-15): PENDING→PLANNED,
  PENDING→SCRUBBED, PLANNED→IN_FLIGHT, PLANNED→SCRUBBED, IN_FLIGHT→LANDED, IN_FLIGHT→SCRUBBED,
  IN_FLIGHT→PLANNED. **`PLANNED → PENDING` is absent**; the swap needs it. `canTransition` (17),
  `requireTransition` (20). `currentSortie` (29) sorts by index and **skips LANDED and SCRUBBED** — an
  IN_FLIGHT sortie *is* the current one. `unlaunch` (~44) uses IN_FLIGHT→PLANNED, `land` (51),
  `scrub` (56), `markPlanned` (63), `isSortieDone`.
- `domain/Missions.kt` (81 lines) — `MissionLifecycle` (9), `newDraft` (28), `overridePattern` (36,
  DRAFT only), `confirmWithoutOutline` (46) — **line 56 is the only place in main that spells the
  defaults**: `focus = if (ch == 'R') "easy" else "full body"`, keyed off the pattern letter because
  that path creates the sorties from the pattern. `allSortiesDone` (70).
- `domain/Pattern.kt` (31 lines) — `isValidPattern` (19), `sortieTypesOf` (26).

## Entities and DAOs

- `data/Sortie.kt` — `type`, `focus`, `focusRationale` (null = the user's own), `state`, `index`,
  `missionId`.
- `data/Mission.kt` — `pattern`, `outlineNotes` (**one string for the whole week**, not per-index — so
  "the notes for that index no longer apply" deletes nothing; the swapped sortie simply takes the
  type's default focus).
- `data/FlightPlan.kt` — `source: FlightPlanSource`; `data/Enums.kt` — `FlightPlanSource { GENERATED,
  REFLY, SIMPLE_RUN }`, `GenerationKind { OUTLINE, FLIGHT_PLAN }`, `GenerationStatus { QUEUED, RUNNING,
  COMPLETED, FAILED, CANCELLED }`.
- `data/FlightPlanDao.kt` — `deleteForSortie(sortieId)` (22), `writePlan` (63) which **calls
  deleteForSortie first** (64), `getPlan` (126). `flightPlan.sortieId` is a FK with CASCADE, so the
  planned exercises and run segments go with the plan row.
- `data/GenerationDao.kt` — `getOpenFlightPlanForSortie` (114, QUEUED/RUNNING only, newest first),
  `getOpenForMission` (121), `insert` (41), `markCancelled` (51).
- `data/Relations.kt:22` — `FlightPlanDetail(plan, exercises, segments)`; `plan.source` is how "a
  generated plan is lost" is told apart from a simple or a re-flown one.

## Manager

- `data/MissionManager.kt` (538 lines), every write inside `db.withTransaction`.
  - `regenerateFlightPlan` (379) — **the model to copy**: settings checks first (380-381 coach,
    397-399 run toggle), then `getOpenFlightPlanForSortie` read *before* its own insert (402-404, with
    the comment explaining why), `insert` a fresh FLIGHT_PLAN row, return the old ids for the caller.
    Its checks are the brief's gates: ACTIVE mission (388), current week (391), current sortie (394),
    PENDING or PLANNED (396).
  - `advance` (498) — closes a finished mission, then prepares the current `PENDING` sortie:
    `prepareCurrent` → `writePlan` + `markPlanned`, or queue a FLIGHT_PLAN row, guarded by
    `coachIsConfigured`. **Trap:** the queue arm reuses
    `getOpenFlightPlanForSortie(current.id).firstOrNull()` (520) — an already-open row, not a fresh
    one — so calling it while the swap's old generation is still QUEUED hands the caller the very row
    it is about to cancel.
  - `storePlan` (451) — refuses a plan for a non-current / SCRUBBED / LANDED sortie, and
    `require(sortie.type == LIFT)` (460) / `require(sortie.type == RUN)` (466) throw on a type mismatch.
  - `refly` (345) — writes a plan and `markPlanned` directly, returns open ids (361).
  - `scrub` (264), `unlaunch` (299), `resetWeek` (429), `continueWithoutOutline` (197),
    `storeOutline` (228) — the "cancel open generations, return ids, caller cancels after commit"
    pattern.
  - `setPattern` (113) — still refuses anything but a DRAFT. Unchanged by this work.
  - `currentWeekStart` (84), `activeMission` (91), `currentSortie` (105).
- `work/GenerationCanceller.kt` — `cancel(generationId)` (34): reads the row, **returns null unless the
  row is QUEUED or RUNNING**, then `workManager.cancelWork` + `markCancelled` + `nabu.session.stop`. So
  the manager must not mark rows CANCELLED itself: the canceller would skip them and the rho session
  would never be given up.
- `work/GenerationWorker.kt` — `markRunning` returning 0 → `Result.success()` (66); re-reads the row and
  skips storing when `status == CANCELLED` (338). That is the protection a swap leans on.

## Launchpad UI

- `ui/launchpad/LaunchpadState.kt` (326) — sealed `LaunchpadState`: `Planned` (34) carries
  `plan: FlightPlanDetail` (38), `sortieType` (41), `sortieFocus` (42), `regenerateAvailable` (48);
  `Pending` (53-67) carries `generationStatus`, `rhoSessionId`, `reFlyAvailable`, `regenerateAvailable`
  but **no `sortieType`** — the swap's label needs it; `InFlight` (70). `class LaunchpadStates` (92),
  `observe()` (96) — it observes `manager.currentWeekStart` and then that week's mission, so the card is
  always the current week's mission and the ACTIVE arm is always an ACTIVE mission.
  `deriveChips(mission.pattern, sorties)` is called at 168, 191, 281. `regenerateAvailable` is computed
  once (198) and passed at 218, 242, 266, 321. Nothing in tests or previews constructs these states, so
  a new field is safe (give it a default anyway).
- `ui/launchpad/LaunchpadViewModel.kt` (250) — `NOT_CONFIGURED_MESSAGE` internal const (18) is the
  shared-wording precedent. `var scrubbing by mutableStateOf(false)` (32) with `requestScrub` (174) /
  `confirmScrub` (180) / `cancelScrub` (200) is the confirm-dialog precedent. `refly` (211) and
  `regenerate` (232) both loop `canceller?.cancel(id)` on the ids the manager returned.
- `ui/launchpad/LaunchpadScreen.kt` (611) — `LaunchpadScreen` (40) draws the scrub dialog at 116-134 off
  `viewModel.scrubbing`, reusing `ui/inflight/ScrubDialog` (imported at 32). `PlannedContent` (190) and
  `PendingContent` (211) both pass `sortieType` to the eyebrow; `ActionButtons` (291) → `PlannedActions`
  (423) / `PendingActions` (451) / `InFlightActions` (487).
- `ui/sortie/PlanFormat.kt` — `formatEyebrow(weekStart, sortieIndex, patternLength, type)` (22) and
  `deriveChips(pattern, sorties)` (106): the chip **letter comes from `mission.pattern`**, its state from
  the sortie rows. So the pattern flip is what makes the Launchpad's own track show the new letter, and
  the eyebrow follows the sortie type. No change needed here.
- `ui/mission/MissionScreen.kt` — shows the pattern and each sortie's state; no per-sortie actions. Out
  of scope (the brief says the Launchpad).

## Coach prompts and export

- `coach/CoachPromptBuilder.kt` — 99-108 print the week: the pattern line, then
  `index N — run — PENDING — focus: easy` per sortie. All from `PromptInputs`, so a swapped sortie
  appears with its new type and focus with no builder change. Golden prompts live in
  `app/src/test/resources/prompts/` — **do not touch them** (memory `liftoff-coach-prompts-golden-file-locked`);
  this work changes no prompt wording.
- `data/PromptInputLoader.kt` — `weekOf` (85) builds `PromptSortie(index, type, state, focus)` from the
  sortie rows; `flightPlanInput` (104) is what a test builds after a swap to see the new line.
- `data/DatabaseExport.kt` — exports `pattern` (49) and `outlineNotes` (58) straight from the row, so the
  export agrees with the flip for free.

## Tests that exist and what they pin

- `data/MissionManagerTest.kt` (1164) — where `regenerateFlightPlan` is tested:
  `regenerateQueuesFlightPlanGenerationAndSupersedesOpenRows` (317),
  `regenerateRefusesWhenCoachNotConfigured` (344), `regenerateRefusesWhenRunGenerationIsOff` (356),
  `regenerateRefusesForScrubbedSortie` (368), `regenerateRefusesForLandedSortie` (381),
  `reflyCancelsOpenGenerationsForTheSortie` (291), `setPatternUpdatesDraftAndRejectsActive` (213),
  `storePlanRefusesPlanForNonCurrentSortie` (441), `storePlanRefusesPlanForScrubbedSortie` (459).
  Robolectric + in-memory Room; `settings` is a `FakeSettingsRepository`.
- `domain/SortiesTest.kt` (153) — 53-72 lists **illegal** transitions, and line 62 is
  `PLANNED to PENDING`. Adding the swap's edge means amending that list.
- `domain/SortiePlanningTest.kt` (56) — `prepareCurrent` arms, `simpleRunPlan` fields.
- `domain/MissionsTest.kt` (135) — lifecycle, `confirmWithoutOutline` defaults.
- `ui/launchpad/LaunchpadStatesTest.kt` (374) — the derivation's arms.
- `ui/launchpad/LaunchpadRegenerateTest.kt` (628) — the biggest Launchpad UI test; the model for a swap
  UI test (availability, action row, cancel loop). `LaunchpadScrubTest.kt` (234) is the dialog pattern in
  the UI; `LaunchpadPlannedActionsTest.kt` (301) pins the Planned card's actions.
- `data/PromptInputLoaderTest.kt` (477) — builds prompt inputs from real Room rows.
- `data/DatabaseExportTest.kt` (306) — asserts exported rows.
