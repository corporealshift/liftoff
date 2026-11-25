# Map — issue 78: swap the current sortie's type (lift <-> run) mid-week

Every line number below was re-derived from HEAD `f4e85b4` with `grep -n` / `sed -n` on 2026-11-23.
The source files are unchanged since the run's base `4bea6e8` (every commit after it touches only
`.rho/runs/…`). Package `com.liftoff.app`; main root `app/src/main/java/com/liftoff/app/`, test root
`app/src/test/java/com/liftoff/app/`.

**The earlier version of this map was wrong about whole files** — it described `Sorties.kt` as holding
a `SortieType` enum, a `legalTransitions`/`illegalTransitions` pair of lists, `advanceTo` and
`markInFlight`/`markLanded`/`markScrubbed`; a `Missions.kt` of 171 lines with `newMission`,
`buildSorties`, `formatEyebrow`, `deriveChips`, `confirm` and `closeIfComplete`; a `PromptInputLoader`
that built `weekLine`/`sortieLine`/`liftLine`; `data/LiftoffDao.kt` and `data/LiftoffEntities.kt`; a
`GenerationStatusLine` in `LaunchpadScreen.kt`; a `LaunchpadViewModel` with `trackGeneration` and
`replanNotice`. **None of those exist.** What follows is what is actually there.

## Gate, measured on this tree

`JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk ./gradlew
:app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain` — BUILD SUCCESSFUL in
1m48s, **93 report files, 775 test records, 0 failures, 0 errors, 0 skipped**. Log
`/tmp/gate78-baseline.log`. `gradlew.sh` in the repo is Windows-only (`%LOCALAPPDATA%…`), so it is not
the launcher here; `./gradlew` works with those env vars, and verify.sh prefers
`$HOME/android-toolchain/gradle/bin/gradle`.

The earlier map recorded "396 tests, 388 pass, 8 fail". That is Gradle's partial "396 tests completed,
1 failed, 1 skipped" line from a CI log, not this suite's total. **The tree is green, so every failure
verify.sh reports will be a named case that did not run at all.**

## verify.sh (HEAD `f4e85b4`) — the definition of done

`bash .rho/runs/issue-78-swap-the-current-sortie-s-type-377n/verify.sh` = 1 gate + **16 `t` named
cases** + **18 `c` whole classes** = 35 checks. All 16 named cases are new; none of the four files
exists. `t` matches a report's `testcase` classname/method by exact method name and class-suffix, so a
case whose name is longer or shorter than the wanted one is not matched.

- `SortieSwapTest` (3), `MissionManagerSwapTest` (7), `PromptInputLoaderSwapTest` (1),
  `LaunchpadSwapTest` (5).
- `c` classes, all existing and all green today: SortiesTest (153), MissionsTest (135),
  MissionManagerTest (1164), MissionManagerPlanTest (407), MissionManagerReFlyTest (307),
  MissionManagerResetTest (184), PromptInputLoaderTest (477), CoachPromptBuilderTest (644),
  CoachPromptGoldenFileTest (263), DatabaseExportTest (306), GenerationCancellerTest (333, in `work/`),
  GenerationWorkerTest (2167, in `work/`), LaunchpadRegenerateTest (628), LaunchpadPlannedActionsTest
  (301), LaunchpadScrubTest (234), LaunchpadReFlyTest (334), LaunchpadStatesTest (374),
  PatternTrackTest (75, in `ui/theme/`).
- Transient-failure allowlist: `LiftoffShellTest > bottomBarReachesEveryTab` (ComposeTimeoutException)
  and `CalledFromWrongThreadException` in `LaunchpadPendingTitleTest` / `LaunchpadRegenerateTest` /
  `LaunchpadPlannedActionsTest` / `LaunchpadScrubTest` / `LaunchpadReFlyTest` / `PatternTrackTest` /
  `LaunchpadViewInRhoTest` / `LaunchpadDraftControlsTest` / `LaunchpadDraftStatusTest` /
  `LaunchpadPendingStatusTest` / `LaunchpadGenerationStatusTest` / `LaunchpadGenerationPendingTest` /
  `LaunchpadCoachStatusTest` / `LiftoffAppTest` / `GenerationWorkerTest` / `MissionManagerPlanTest`.
  A named case that fails transiently twice is a real failure.

**verify.sh was rewritten after the map was first written.** `d8c75bf` named 18 cases; `f4e85b4`
("run: verify.sh rewritten after claude review") reduced it to 16. Dropped:
`swapSortieTypeChangesOnlyThePatternLetterAtThatIndex`, `swapTwiceRestoresTheOriginalTypeAndPattern`,
`swapRefusesASortieOutsideTheCurrentActiveMission` + `swapRefusesASortieOfAMissionThatIsNotActive`
(merged into `swapRefusesASortieThatIsNotCurrentOrNotInTheCurrentWeeksActiveMission`),
`swapLabelNamesTheTypeItSwapsToOnBothCards`, `confirmingTheSwapShowsTheRePlanningLineForThatSortieOnly`.
Renamed: `…FlipsTheSortieAndItsPatternLetter…` → `…AndThePatternLetter…`;
`…WritesASimpleRunOrReFlyPlan…` → `…WritesASimpleRunPlan…`; and
`swapWithTheCoachNotConfiguredRefusesAndWritesNothing` → `swapWithTheCoachNotConfiguredFlipsTheSortieAndLeavesItPending`
— **the semantics changed: an unconfigured swap is not a refusal.** verify.sh also dropped
`c SortiePlanningTest`, `c ReFlyTest`, `c PlanFormatTest` and `c LiftoffShellTest` (the last is still
covered by the gate).

**plan.md's Files list is out of sync with verify.sh.** It says these cases go in
`MissionManagerTest.kt`, `SortiesTest.kt`, `PromptInputLoaderTest.kt` and `DatabaseExportTest.kt`.
verify.sh matches on the class name, so write each named case in a new file named exactly as verify.sh
names it: `domain/SortieSwapTest.kt`, `data/MissionManagerSwapTest.kt`,
`data/PromptInputLoaderSwapTest.kt`, `ui/launchpad/LaunchpadSwapTest.kt`. verify.sh's header also says
the export is **not** checked separately — `DatabaseExportTest` runs unchanged in the `c` list.

## data/Enums.kt — 9 lines. The vocabulary.

`MissionStatus { DRAFT, ACTIVE, CLOSED }` (3); **`SortieType { RUN, LIFT }` (4)**;
**`SortieState { PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED }` (5)** — five states, no DRAFT sortie
state (a draft is a Mission status); `FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }` (6);
`SetStatus` (7); `GenerationKind { OUTLINE, FLIGHT_PLAN }` (8);
`GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }` (9).

## domain/Sorties.kt — 66 lines. Where the flip goes.

`object Sorties` (5). `object SortieTransitions` (6-27): `private val legalTransitions` (7-15) —
PENDING→PLANNED (8), PENDING→SCRUBBED (9), PLANNED→IN_FLIGHT (10), PLANNED→SCRUBBED (11),
IN_FLIGHT→LANDED (12), IN_FLIGHT→SCRUBBED (13), **IN_FLIGHT→PLANNED (14, the un-launch edge)**;
`canTransition` (17-18); `requireTransition` (20-26), which throws
`IllegalTransitionException` (`domain/Exceptions.kt:3`) with a "no arrow between X and Y" message.
`currentSortie` (29-32) — lowest-index sortie that is not LANDED or SCRUBBED; `requireNoneInFlight`
(34-38). Named transition helpers, all of them the model for the flip: `launch` (40-43), **`unlaunch`
(45-49) — IN_FLIGHT→PLANNED, "the only transition that carries a sortie backwards"**, `land` (51-54),
`scrub` (56-61), `markPlanned` (63-66).

There is **no `illegalTransitions` list in main** and no `advanceTo`. The domain change is: add
`SortieState.PLANNED to SortieState.PENDING` to `legalTransitions` (7-15), and add the flip as a named
helper beside `markPlanned`/`unlaunch` that calls `SortieTransitions.requireTransition`.

## domain/Missions.kt — 71 lines. The pattern rules.

`object MissionLifecycle` (10). `MAX_CHIPS = 7` (11), `CHIP_LETTERS = "LR"` (12), `MIN_CHIPS = 1` (13).
`DraftState` sealed interface (16-26): `AwaitingOutline`, `Outlining`, `AwaitingPattern`, `PatternDraft`
(24-26, `pattern: String`). `newDraft` (28-34) — `PatternDraft("")`. `overridePattern` (36-44) — the
only writer of a draft pattern, `require(isValidPattern(pattern))`. **`confirmWithoutOutline` (46-68)**
— the only place the two default focus strings are written today: line **56**
`focus = if (ch == 'L') "easy" else "full body"`, `focusRationale = null` (57), `state = PENDING` (58).
`allSortiesDone` (70-71). `isValidPattern` is `domain/Pattern.kt:5`.

The two focus strings are the swap's only visible copy. Name them once and derive both line 56 and the
new flip from those names; the strings themselves must not change, or the golden prompts move.

## domain/SortiePlanning.kt — 36 lines. The rule the swap's run arm already has.

`Preparation` sealed interface (9-11): `SimplePlan(draft)` or `AwaitGeneration`. `prepareCurrent(sortie,
generateRunPlans)` (13-19): a RUN with `generateRunPlans` off → `SimplePlan(simpleRunPlan(sortie))`,
everything else → `AwaitGeneration`. `simpleRunPlan(sortie)` (21-27): `FlightPlan(source = "SIMPLE_RUN",
title = "Run", notes = sortie.focus)`. `MissionManager.advance` (512-518) is the only writer today.
`domain/SortiePlanningTest.kt` (56 lines) covers it.

## data/MissionManager.kt — 539 lines. Where the action goes.

`onAppOpen` 82, `setPattern` 111, `confirm` 132, `retryOutline` 164, `continueWithoutOutline` 197,
`storeOutline` 228, `scrub` 264, `launch` 279, `unlaunch` 299, `land` 321, `refly` 345,
**`regenerateFlightPlan` 379**, `resetWeek` 429, `storePlan` 451, `private advance` 498,
`currentWeekOf` 537.

`regenerateFlightPlan` (379-418) is the shape to copy: `coachIsConfigured` guard **outside** the
transaction (381-383); `var queued` (385); `db.withTransaction {` (386); sortie (386-387); ACTIVE gate
(388); current-week gate `currentWeekStart() == mission.weekStart` (389-391); current-sortie gate
(392-393); state gate PENDING-or-PLANNED (394-396); run-toggle (399-401); **open ids read before the
insert** (404); one FLIGHT_PLAN insert (405-413); `toCancel` (414); `queued?.let(onQueued)` (416);
`return supersededIds` (417). Its doc says being unconfigured is a refusal *here*, unlike
`confirm`/`retryOutline` — the swap inverts that: the coach gate decides queue-or-not, never
refuse-or-not.

`refly` (345-363) is the shape of the swap's simple-plan arm: gates (347-352),
`writePlan(sortieId, reflyDraft(sourcePlan))` (358) then `db.sortieDao().update(markPlanned(sortie))`
(360) with the comment "markPlanned, not advance", then `getOpenFlightPlanForSortie(sortieId).map {
it.id }` (361).

`advance` (498-536) is the trap. 512 `prepareCurrent(current, settings.generateRunPlans)`; 514-518 the
simple-plan arm (`writePlan` 515, `markPlanned` 516); **520 `val open = …getOpenFlightPlanForSortie
(current.id)`, 521 `if (open.isNotEmpty()) return open.first().id` — it returns an existing open row
instead of queueing a second**; 522+ the insert. A swap that reuses `advance` would cancel the row it
just queued.

`storePlan` (451-496) has a type guard the swap must respect: `require(sortie.type == SortieType.LIFT)`
for a coach lift draft and `require(sortie.type == SortieType.RUN)` for a coach run draft (461-470),
plus ACTIVE / PENDING-or-PLANNED / current gates (475-486). So a generation queued for the old type
whose result arrives after the swap hits that `require` — which is why the brief makes cancelling the
sortie's open generations part of the action, and why the swap returns those ids instead of marking them
itself.

`scrub` (264-277) returns the id to cancel and marks nothing. `resetWeek` (429-455) returns ids and
marks nothing. `continueWithoutOutline` (197-219) is the third. `GenerationCanceller`
(`work/GenerationCanceller.kt`, 72 lines) `cancel(generationId)` (32-…): cancels the unique work (33),
then in one transaction reads the row and **returns without marking if the row is null or its status is
not in `CANCELLABLE_STATUSES`** (37-44), then `markCancelled` (42). A manager that marked a row
CANCELLED inside its own transaction would make this a no-op.

## data/Generation.kt — 31 lines, and data/GenerationDao.kt — 129 lines.

`Generation` (8-20): `id`, `kind`, `missionId`, **`sortieId: Long?` (11)**, `status` (no default),
`nabuSessionId`, `lastEventId`, `attempt`, `error`, `createdAt`, `finishedAt`.
`GenerationDao.insert` (104-105) returns the new row id. **`getOpenFlightPlanForSortie` (110-114)** —
`WHERE sortieId = :sortieId AND kind = 'FLIGHT_PLAN' AND status IN ('QUEUED','RUNNING') ORDER BY
createdAt DESC, id DESC` — newest first.

## data/Mission.kt (19) and data/Sortie.kt (35). The rows the swap rewrites.

`Mission` (13-19): `id`, `weekStart: LocalDate` (15), **`pattern: String` (16)**, `status`,
`outlineNotes`. `Sortie` (21-35): `id`, `missionId`, **`index` (24)**, **`type: SortieType` (25)**,
**`focus: String` (26)**, **`focusRationale: String?` (27)**, **`state: SortieState` (28)**,
`launchedAt`, `landedAt`, `scrubReason`, `notes`, `runDistance`, `runMinutes`. No `rhoSessionId` on
`Sortie`.

## ui/launchpad/LaunchpadState.kt — 326 lines. Where the card gets its type.

`LaunchpadState` sealed interface (21) with `Loading` (23); `Draft` (26-32); **`Planned` (34-52)** —
`sortieType` (44), `sortieFocus` (45), `distanceUnit` (46), `reFlyAvailable` (47),
`regenerateAvailable` (48); **`Pending` (53-69)** — `rhoSessionId` (63), `reFlyAvailable` (64),
`coachConfigured` (65), **`regenerateAvailable` (66)**, but **no `sortieType`, no `sortieFocus`, no
`distanceUnit`** — so a swap label cannot be built on the PENDING card until `Pending` carries the
type; `InFlight` (70-82); `Closed` (83-95).
`class LaunchpadStates` (97), `observe()` (104-112), `derive` (114-162), `deriveClosed` (164-180),
**`deriveSortieCard` (182-273)**, `deriveActive` (274-300).
`regenerateAvailable = coachConfigured && (current.type != SortieType.RUN || generateRunPlans)`
(**198**) — the existing availability-flag pattern. Construction sites: `Closed` 170, `Planned` 207,
**`Pending` 233, `Pending` 257**, `InFlight` 291, **`Pending` 309** — all three `Pending` sites have
the sortie in hand.

The card already shows a swap with no new display code: the eyebrow is `formatEyebrow(...)`
(`ui/sortie/PlanFormat.kt:22`, which takes `type: SortieType`), the chip letters come from
`deriveChips(pattern, sorties)` (`ui/sortie/PlanFormat.kt:106`), and a PENDING card's title is
`current.focus` or `"Run"`/`"Lift"`. Only the button's label needs the type on the state.

## ui/launchpad/LaunchpadViewModel.kt — 250 lines. Where the action goes.

Constructor takes `queueGeneration` (27) and `cancelGeneration` (29) as callbacks; `var scrubbing by
mutableStateOf(false)` (32); `notConfiguredMessage` (36); `state` (39-45, built from `LaunchpadStates`).
Draft actions: `toggleChip` (47), `addChip` (62), `removeChip` (75), `confirm` (92),
`continueWithoutOutline` (111), `retry` (128), `queueOrReport` (142).
`launch(onLaunched)` (151-172); **the dialog shape: `requestScrub` (174-178), `confirmScrub(reason)`
(180-198) — sets `scrubbing` true, calls `manager.scrub`, cancels each returned id with
`cancelGeneration`, refreshes, clears `scrubbing` — and `cancelScrub` (200-202)**; `refly()` (211-223)
— the one-tap-with-no-dialog shape; **`regenerate()` (232-249)** — read `state.value`, act on `Pending`
and `Planned` only, `runCatching { manager.regenerateFlightPlan(sortieId, onQueued = ::queueGeneration) }`,
`cancels.forEach(::cancelGeneration)`, `onLaunchpadAction()`. No swap call anywhere.

## ui/launchpad/LaunchpadScreen.kt — 544 lines. Where the button and the dialog go.

`LaunchpadScreen` (39), the content composable (64) with the **scrub confirmation at 114-133**
(`if (viewModel.scrubbing)` 115, `ScrubDialog(...)` 117-124, `onConfirm = { viewModel.confirmScrub(reason) }`
121, `onCancel = { viewModel.cancelScrub() }` 122, and the "Sortie scrubbed." line 127-133).
`DraftContent` (138), `draftStatusLine` (169), `PlannedContent` (190-209), **`PendingContent`
(211-245)** — TitleBlock 222-226, PatternTrack 227-229, `pendingStatusLine` (246-258), `InFlightContent`
(260-274), `ClosedContent` (276-289), **`ActionButtons` (291-332)** — dispatches `when (state)` to
`DraftActions` (334-386), `DraftStepButton` (388-421), **`PlannedActions` (423-448)** — Launch
PrimaryButton 429-434, then a Row of Regenerate 439-441 + Scrub 442-444 when `planned.regenerateAvailable`,
else Scrub alone 446; **`PendingActions` (451-484)** — Retry 461-464, Re-fly 467-472 or the "No earlier
plan to re-fly." text 473-477, View in rho 479-482, Scrub 483; `InFlightActions` (487-496),
`DraftPatternChips` (498-…).
`UnderlinedTextButton` (`ui/theme/Buttons.kt` 155-…) draws `text.uppercase()` (**179**) — so a label
written "Swap to run" is found in a test as "SWAP TO RUN". `PrimaryButton` 28 (uppercase 74).

## data/PromptInputLoader.kt — 165 lines. Where the flip reaches the coach.

`class PromptInputLoader(` (29), `suspend fun load(missionId: Long): CoachPromptInput?` (41-69): reads
the mission (42), returns null unless ACTIVE (43), reads the week's settings (44-46), reads the sorties
(47-49), `sortieIndex = Sorties.currentSortie(sorties)?.index` (50), `liftPlan`/`runPlan` (51-58),
`sets` (59-62), and builds `CoachPromptInput(... week = weekOf(mission) ...)` (63-68).
**`weekOf(mission)` (71-87)** — `pattern = mission.pattern` (73), `weekStart` (74), `status` (75), and
`PromptSortie(index = it.index, type = it.type, state = it.state, focus = it.focus)` (**85**) from
`sortieDao().getForMission(mission.id)`. `dateOf` (116), `titleOf` (149-151, uses `sortie.focus`),
`toPromptSet` (155).
**Nothing to change: every field is read from the rows the swap rewrites.** The swapped type, the new
default focus and the flipped letter all reach the prompt through `weekOf`.

## data/DatabaseExport.kt — 152 lines. Nothing to change.

`Mission.toBackup()` (53-59) — `pattern = pattern` (**56**). `Sortie.toBackup()` (61-…) — `focus = focus`
(66), `focusRationale = focusRationale` (67). verify.sh names no export case; `DatabaseExportTest` runs
unchanged in the gate.

## Tests that exist

- `domain/SortiesTest.kt` (153): `legalTransitionsSucceed` (12-43); `illegalTransitionsAreRejected`
  (45-91) — the pair list at **51-76**, `PLANNED to PENDING` at **62**, the comment at 49 ("7 legal are
  tested above; verify the remaining 18 throw"), `assertEquals(18, illegalPairs.size)` at **78** and
  `assertEquals(18, caught)` at **90**. Both counts move the moment the pair leaves the list.
- `domain/MissionsTest.kt` (135): `confirmWithoutOutlineBuildsPendingSortiesFromPattern` (47-61) asserts
  `focus == "easy"` / `"full body"`; `overridePatternReplacesDraftPatternEntirely` (111-121).
- `domain/SortiePlanningTest.kt` (56).
- `data/MissionManagerTest.kt` (1164): Robolectric + in-memory Room + a **real `SettingsStore` over a
  `PreferenceDataStore` in `context.cacheDir`** (47-50) — there is no `FakeSettingsRepository` anywhere
  in the repo; `configureCoach()` (**89**) and `settingsStore.setGenerateRunPlans(...)` are the two
  knobs; `MissionManager(db, settingsStore, clockOf(...))` (68-72); the fixture path is `newMission` +
  `setPattern` + `confirm` + `onAppOpen` (52-66). `regenerateFlightPlan` tests 317-400; `refly` tests
  402-560.
- `data/MissionManagerPlanTest.kt` (407), `MissionManagerReFlyTest.kt` (307),
  `MissionManagerResetTest.kt` (184).
- `data/PromptInputLoaderTest.kt` (477, 11 `@Test`); `coach/CoachPromptBuilderTest.kt` (644);
  `coach/CoachPromptGoldenFileTest.kt` (263); `data/DatabaseExportTest.kt` (306);
  `work/GenerationCancellerTest.kt` (333); `work/GenerationWorkerTest.kt` (2167).
- `ui/launchpad/LaunchpadRegenerateTest.kt` (628) — **view-model/manager level, no Compose**:
  `regenerateOnAMissionThatIsNotActiveIsRefusedAndWritesNothing` (**525**),
  `regenerateOnASortieThatIsNotCurrentIsRefusedAndWritesNothing` (536),
  `regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState` (**547**),
  `regenerateOnARunSortieWithRunGenerationOffIsNotOffered` (571),
  `regenerateOnALandedSortieIsRefusedAndWritesNothing` (587),
  `regenerateOnAScrubbedSortieIsRefusedAndWritesNothing` (607).
- `ui/launchpad/LaunchpadScrubTest.kt` (**234**) — **no Compose**; the model for the confirm-dialog
  half.
- `ui/launchpad/LaunchpadPlannedActionsTest.kt` (301) — **uses `createComposeRule`**; pins the Planned
  action row (111-134) and its geometry (**197-199**: regenerate beside scrub, row width from LAUNCH);
  its harness is `week(pattern)` (131), `plannedLift()` (154), `render(viewModel)` (172-177).
- `ui/launchpad/LaunchpadStatesTest.kt` (374) — derives states from real rows.
- `ui/launchpad/LaunchpadReFlyTest.kt` (334).
- `ui/theme/PatternTrackTest.kt` (75).
- Compose-rule Launchpad tests: LaunchpadDraftControlsTest, LaunchpadDraftStatusTest,
  LaunchpadPendingStatusTest, LaunchpadPlannedActionsTest, LaunchpadReFlyTest, LaunchpadViewInRhoTest.

## Docs that set rules

- `CLAUDE.md` — "Design changes come before code, not after"; a change to an approved design is an
  amendment entry at the end of DESIGN.md (**newest first**) plus an inline mark in the section it
  changes; "a new trigger is a new action on the current sortie, not a new screen"; "a confirmation is
  required only where an action destroys work already done"; "every rule has a test named for it";
  "no new colors, no new type sizes"; "no `GlobalScope`"; "no `runBlocking` in the app's own code".
- `DESIGN.md` — 911 lines. §4.1 Pattern Draft (111-148; the frozen-pattern line at **117**); §4.3
  (149-161); §5.1 Sortie states (162-205; the state-diagram code block at **164-170**); §5.4 Re-fly
  (206-214); §7.2 Triggers (279-311); §7.3 Generations (312-345); §9 Launchpad (574-682); §13 Testing
  (683-745; the "pattern can be overridden while a draft" line at **697**); §15 Decisions table
  (772-792); §16 Assumptions (793-807); `## Amendments after approval` (**808**, preamble 808-812,
  first entry **813**).
  Amendment entries are `- **<date> — <title> (§refs).**` followed by prose — **DESIGN.md carries no
  `**Rule:**` / `**Reason:**` labels anywhere**, and there is no Scrub amendment entry (Scrub is
  2026-10-29 inline marks). Newest entry is `- **2026-11-02 — un-launch: IN_FLIGHT → PLANNED, with the
  plan it already holds (§5.1, §6, §9).**` (813), then 2026-11-02 "Regenerate" (815), 2026-11-01
  "Generation status on the Launchpad" (810). plan.md's "written the way the 2026-11-02 Scrub entry
  writes them" is wrong; copy the entry that is actually there. Inline marks look like
  `(amended 2026-11-02: …)`.
- `app/src/test/resources/prompts/README.md` — golden prompts, byte-for-byte; `CoachPromptGoldenFileTest`
  fails on any prompt-text change. This issue changes no prompt text.

## Traps

- `SortiesTest`'s two `18` counts (78, 90) move in the same commit that removes `PLANNED to PENDING`
  from the test's illegal pair list (62), or `c SortiesTest` fails.
- `MissionManager.advance` (520-521) returns an existing open generation instead of queueing a second.
- `GenerationCanceller.cancel` skips a row that is null or not in `CANCELLABLE_STATUSES`.
- `storePlan`'s type `require` (461-470) rejects a plan of the other type, so an uncancelled generation
  from before the swap can throw on arrival.
- `UnderlinedTextButton` upper-cases its label (Buttons.kt:179).
- `LaunchpadPlannedActionsTest`'s geometry assertions (197-199) can move when a third action joins the
  Planned row.
- Robolectric fakes text metrics by default: a test that must show a line count or a text height needs
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` on its own class.
- Scratch tests and gate logs go under gitignored `app/build/`, never `app/src/test`.
