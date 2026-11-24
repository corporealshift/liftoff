# Tasks — issue 78: swap the current sortie's type (lift <-> run) mid-week

Six tasks, in this order, each ending in its own commit. Task 3 is the hinge: the Launchpad task
calls `MissionManager.swapSortieType`, and the prompt task asserts what the swap leaves in the rows.

`verify.sh` is the run's definition of done. It checks the CI gate, **16 named cases — all of them
new**, and 18 existing classes that must stay green. Baseline measured on this tree: the gate is
green, 93 report files, 775 test records, 0 failures, 0 errors, 0 skipped — so all 16 named cases
fail as "did not run at all" and nothing else fails.

**The four new test files are named by `verify.sh`, not by `plan.md`.** plan.md's Files list says
these cases go in `MissionManagerTest.kt`, `SortiesTest.kt` and `PromptInputLoaderTest.kt`;
verify.sh matches a case by its class name, so a case written in an existing file is never found and
the run fails. Write each named case in a new file whose class name is exactly the one verify.sh
uses, under `app/src/test/java/com/liftoff/app/` in the package its neighbours use:
`domain/SortieSwapTest.kt`, `data/MissionManagerSwapTest.kt`, `data/PromptInputLoaderSwapTest.kt`,
`ui/launchpad/LaunchpadSwapTest.kt`. A case name must match verify.sh's spelling exactly — a longer
or shorter name is not matched.

Gate on this machine (the committed `gradlew.sh` is Windows-only):
`~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest --no-build-cache
--console=plain`, or `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
./gradlew …`. Reports land in `app/build/test-results/testDebugUnitTest/TEST-*.xml` — clear that
directory before any run whose report you intend to read. Scratch tests and gate logs go under
`app/build/`, never `app/src/test`. Read `map.md` before writing: it records the traps
(`MissionManager.advance` reuses an open generation row; `GenerationCanceller` skips a row that is
not QUEUED or RUNNING; `SortiesTest` pins the illegal-transition count).

- [x] 1. The DESIGN.md amendment, before any code
  `DESIGN.md` only. One entry at the top of "Amendments after approval" (section at 808, first entry
  at 813), newest first, in the shape the newest entry actually uses: `- **<date> — <title>
  (§refs).**` followed by prose. DESIGN.md carries no `**Rule:**` / `**Reason:**` labels anywhere, and
  the newest entry is the 2026-11-02 un-launch one (813), not a Scrub one — plan.md's wording on this
  point is wrong; copy the entry that is there. Mark the body: §4.1 (111-148, the frozen-pattern line
  at 117) — frozen on Confirm except that the current PENDING or PLANNED sortie's type may be swapped
  mid-week, which rewrites that index's letter; §4.3 (149-161) and §15 (772-792) carry a mark; §5.1
  (162-205, diagram at 164-170) gains the PLANNED -> PENDING edge with its condition; §9 (574-682)
  gains the action on the PENDING and PLANNED cards and the confirmation only for a generated plan;
  §13 (683-745) — the checklist line at 697 that says the pattern can be overridden while a draft is
  amended to say the swap is the one override on an ACTIVE week; §16 (793-807) gains the assumption
  that a swapped type's focus is the new type's default.
  Done when: `git show --stat` for this commit lists `DESIGN.md` and nothing else, the new entry is
  the first in that section, and §5.1's diagram carries the new edge. Commit.

- [x] 2. The flip in the domain, and the transition it needs
  `domain/Sorties.kt` (66 lines): the flip — LIFT <-> RUN, PENDING or PLANNED in, PENDING out, the
  new type's default focus, the rationale cleared, the pattern letter at `sortie.index` flipped and
  every other letter and the length unchanged — plus `PLANNED to PENDING` in `legalTransitions` (19-23)
  and out of `illegalTransitions` (29-34). Name the two default focus strings once and derive both
  this function and `domain/Missions.kt:56` from those names; the strings themselves do not change.
  `domain/SortiesTest.kt` must follow in the same commit: `PLANNED to PENDING` leaves its illegal
  list (62) and the two counts that pin it — `assertEquals(18, illegalPairs.size)` (78) and
  `assertEquals(18, caught)` (90) — move with it.
  Tests: new `domain/SortieSwapTest.kt` with `swapSortieTypeFlipsTheTypeAndThePatternLetterOfTheSameIndex`,
  `swapSortieTypeSetsTheNewTypesDefaultFocusAndClearsTheOldRationale`,
  `plannedToPendingIsALegalTransitionAndEveryOtherIllegalTransitionIsStillRejected`.
  Done when: a cold run of `--tests "com.liftoff.app.domain.SortieSwapTest" --tests
  "com.liftoff.app.domain.SortiesTest"` is BUILD SUCCESSFUL, its reports list all three names as
  passing, and SortiesTest's count assertion still equals the size of the list it just lost a pair
  from. Commit.

- [x] 3. `MissionManager.swapSortieType` and what the swap writes
  `data/MissionManager.kt` (539 lines): a new suspend action beside `regenerateFlightPlan` (379-418);
  see plan.md for what it takes and returns. Gates copied from
  `regenerateFlightPlan` (388-396): this week's ACTIVE Mission, the current sortie, PENDING or PLANNED
  only — IN_FLIGHT, LANDED and SCRUBBED refused. Everything after the gates happens in one
  `db.withTransaction`: flip the sortie, rewrite `mission.pattern` at that index, delete the plan the
  sortie held, then either write the simple plan and mark the sortie PLANNED, or queue a FLIGHT_PLAN
  generation. `domain/SortiePlanning.kt` already holds that rule — `prepareCurrent(sortie,
  generateRunPlans)` (13) answers SimplePlan vs AwaitGeneration and `simpleRunPlan` (21) is the draft
  `advance` (511-533) writes with `flightPlanDao().writePlan` + `markPlanned`; the coach gate
  (`coachIsConfigured`) decides queue-or-not, not refuse-or-not, so a swap with no host and token
  still flips and leaves the sortie PENDING. Read the sortie's open FLIGHT_PLAN ids **before** the
  insert — `advance` at 521 returns an existing open row instead of queueing a second, and
  `getOpenFlightPlanForSortie` is newest-first. Return the ids for `GenerationCanceller`; never mark a
  row CANCELLED inside this transaction, because `GenerationCanceller.cancel` skips a row that is not
  QUEUED or RUNNING.
  Tests: new `data/MissionManagerSwapTest.kt` with five of its seven names —
  `swapOnAPendingSortieFlipsTheSortieAndThePatternLetterInPlace`,
  `swapOnAPlannedSortieDeletesItsFlightPlanAndQueuesAFreshFlightPlanGeneration`,
  `swapReturnsTheSortiesOpenGenerationsToCancelButNotTheOneItQueued`,
  `swapToARunWithRunGenerationOffWritesASimpleRunPlanAndQueuesNothing`,
  `swapWithTheCoachNotConfiguredFlipsTheSortieAndLeavesItPending`. Fixture in the shape
  `MissionManagerTest.kt` (1-90) uses: Robolectric + in-memory Room + a real `SettingsStore` over a
  `PreferenceDataStore` in `context.cacheDir`, `configureCoach()` (89) and
  `settingsStore.setGenerateRunPlans(...)` as the two knobs; no fake settings repository exists.
  Done when: a cold run of `--tests "com.liftoff.app.data.MissionManagerSwapTest"` is green with all
  five names in its report, and `MissionManagerTest`, `MissionManagerPlanTest`,
  `MissionManagerReFlyTest` and `MissionManagerResetTest` are still green. Commit.

- [x] 4. The swap's refusals, and the swapped rows reaching the coach's prompt
  Tests only, against task 3's action — no production change expected, and if a refusal case exposes
  a gate task 3 left out, fix the gate in this same commit. `data/MissionManagerSwapTest.kt` gains the
  two remaining names: `swapRefusesASortieThatIsNotCurrentOrNotInTheCurrentWeeksActiveMission` (a
  non-current sortie, a sortie of a Mission that is not ACTIVE, and a sortie of a Mission that is not
  this week's — each refused, nothing written) and `swapRefusesAnInFlightLandedOrScrubbedSortie`.
  New `data/PromptInputLoaderSwapTest.kt` with `promptInputsForASwappedSortieCarryItsNewTypeAndDefaultFocus`:
  after a swap, the inputs `PromptInputLoader` builds from the real rows carry the swapped sortie with
  its new type, the new type's default focus and no rationale, and the week line shows the flipped
  letter. `PromptInputLoaderTest.kt` (11 tests) is the model for what the week line asserts.
  Done when: a cold run of `MissionManagerSwapTest` is green with all seven names in its report and
  `--tests "com.liftoff.app.data.PromptInputLoaderSwapTest"` is green with its one name; `git show
  --stat` lists only those two test files plus `MissionManager.kt` if a gate was found. Commit.

- [x] 5. The action on the Launchpad, and the confirm dialog
  `ui/launchpad/LaunchpadState.kt` (326 lines): `Pending` (53-67) has no `sortieType`, so the label
  cannot be built on the PENDING card until it carries one — the three places that construct a
  `Pending` (233, 257, 309) all have the sortie in hand. The card already shows the flip without new
  display code: the eyebrow is built from `current.type` and a PENDING card's title is
  `current.focus ?: "Run"/"Lift"`, and the chip letters come from `mission.pattern` via `deriveChips`.
  `LaunchpadViewModel.kt` (250 lines): a swap action in `regenerate`'s shape (232-246) — read the
  state, act on PENDING and PLANNED only, hand each returned id to `cancelGeneration` — plus the
  dialog state, so a PLANNED card whose plan source is GENERATED opens the confirmation and a PENDING
  or non-generated PLANNED card runs the swap with no dialog. `LaunchpadScreen.kt` (544 lines): the
  action in `PendingActions` (451-484) and `PlannedActions` (423-449) only, and the dialog in the
  shape the scrub one already on this screen uses (`showScrubDialog` 116-121, the `ScrubDialog` block
  122-133, driven by `viewModel.scrubbing` / `confirmScrub` / `cancelScrub`). `UnderlinedTextButton` draws `text.uppercase()`
  (`ui/theme/Buttons.kt:179`), so a label written "Swap to run" is found as "SWAP TO RUN".
  Tests: new `ui/launchpad/LaunchpadSwapTest.kt` with `swapActionIsOfferedOnPendingAndPlannedAndOnNoOtherState`,
  `swapActionIsAbsentWhenTheMissionIsNotActiveOrTheSortieIsNotCurrent`,
  `swapOnAPlannedCardWithAGeneratedPlanAsksForConfirmationAndACancelWritesNothing`,
  `swapOnAPendingOrNonGeneratedPlannedCardRunsWithoutAConfirmDialog`,
  `confirmingTheSwapDialogRunsTheSwapAndCancelsTheOpenGenerations`. `LaunchpadRegenerateTest.kt` (628
  lines) is the model for the two availability cases
  (`regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState` at 547,
  `regenerateOnAMissionThatIsNotActiveIsRefusedAndWritesNothing` at 525) and `LaunchpadScrubTest.kt`
  (366 lines) for the dialog half; neither uses Compose, so a state-level or view-model-level case is
  a legitimate way to show "offered" and "absent". `LaunchpadPlannedActionsTest.kt` is the model for a
  drawn action row and pins geometry at 197-199 that a third action can move — update the assertion,
  not the wording.
  Done when: the whole CI gate runs cold and green rather than one class, because a new action row can
  break `LaunchpadPlannedActionsTest`, `LaunchpadStatesTest`, `LaunchpadRegenerateTest`,
  `LaunchpadScrubTest`, `LaunchpadReFlyTest` and `PatternTrackTest`, and the reports list all five
  names as passing. Commit.

- [ ] 6. The gate, and the brief read back against the tests
  Run `bash .rho/runs/issue-78-swap-the-current-sortie-s-type-377n/verify.sh` end to end and read its
  output line by line. Then read each bullet of `brief.md` and each "Must show" comment above a named
  case in verify.sh against the case that now proves it: the type and letter on the Launchpad card,
  the pattern rewritten at that index only, the exported Mission carrying the new pattern
  (`DatabaseExportTest` runs unchanged in the gate — the export writes `mission.pattern` out of the
  row task 3 proved flipped, so no new case), the prompt for the swapped sortie, the refusal cases
  writing nothing, the confirm dialog only for a generated PLANNED plan. Write or fix whatever a
  bullet has no case for, in the file verify.sh names; add no case verify.sh does not name. The
  golden prompts under `app/src/test/resources/prompts/` stay byte-for-byte unchanged — this issue
  changes no prompt text.
  Done when: `bash .rho/runs/issue-78-swap-the-current-sortie-s-type-377n/verify.sh` exits 0 with all
  16 named cases and all 18 classes ok, `git status` is clean, and the commit message records any
  bullet that had to be chased down. Commit.
