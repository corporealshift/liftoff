# Tasks: Launchpad and Mission screens

- [x] Step 1 — Ensure the current week, and the change signal
  Add `currentWeekStart` (StateFlow<LocalDate?>) to MissionManager; set it at the end of `onAppOpen()` after the transaction commits. Add `repeatOnLifecycle(Lifecycle.State.STARTED)` call in MainActivity so `container.missionManager.onAppOpen()` runs on app open and foreground return. Add `FlightPlanDao.observeChanges(): Flow<Int>` selecting from flightPlan, plannedExercise, plannedSet, runSegment as a change signal. Run existing MissionManagerTest and DAO tests to confirm they still pass.
  No new test files; only updates to existing code plus verification that existing tests pass.

- [x] Step 2 — Formatting, shared parts, ChipState.Scrubbed
  Write `PlanFormat.kt` with pure formatting functions: week eyebrow date (`OCT 5`), sortie label (`SORTIE 2 OF 5 · LIFT`), exercise load (`3×8 · 135`, `3×8 · BW`, `3×45 s`), Flight Plan head (`≈55 min · 14 sets`), and pattern chip states. Write `PlanFormatTest` covering every formatter including weight `135.0` → `135`, `22.5` → `22.5`, null weight → `BW`, seconds → `45 s`, differing sets, null estimate, singular "1 set".
  Write `FlightPlanSection` composable: FLIGHT PLAN head over ink rule, exercise rows (index, name, load) for lifts; run summary rows for runs; coach note ("Coach:" in Teal weight 600 + text in Muted with LiftoffType.note()).
  Add `ChipState.Scrubbed` to PatternTrack: 44 dp Sand circle, 2 dp ink border, letter in Muted. Handle it in `chipSizeDp` and `PatternChipView`. Do not add a new @Preview.
  Tests: PlanFormatTest (new file).

- [x] Step 3 — Launchpad derivation, state holder, and screen
  Write `LaunchpadState.kt`: sealed LaunchpadState (Loading, Draft, Planned, Pending, InFlight, Closed) with the derivation class `LaunchpadStates(db, manager)` deriving from `manager.currentWeekStart`, `missionDao.observeWeek()`, and `flightPlanDao.observeChanges()`.
  Write `LaunchpadViewModel.kt`: plain state holder with actions toggleChip, addChip, removeChip, confirm, launch, requestScrub, setScrubReason, confirmScrub, cancelScrub.
  Write the real `LaunchpadScreen.kt`: content scrolls (TitleBlock eyebrow + title, PatternTrack, FlightPlanSection); actions pinned at bottom. Each state renders per the plan (Planned: Launch button + Scrub; Pending: status line + Scrub; InFlight: Resume; Draft: editable chips + Confirm; Closed: completion; Scrub: confirmation dialog with Paper card). Actions catch IllegalStateException and re-run onAppOpen() on failure.
  Tests: LaunchpadStatesTest (new file) — Robolectric tests against in-memory DB and MissionManager with fixed clock: Loading before onAppOpen, Draft with default pattern, PLANNED lift with seeded exercises, PLANNED run after confirm, PENDING after scrub, IN_FLIGHT after launch, Closed. Also checks live state updates after confirm, scrub, launch.

- [x] Step 4 — Mission tab derivation, state holder, and screen
  Write `MissionState.kt`: MissionTabState (Loading, Week) with the `MissionStates(db, manager)` derivation, plus SortieDetailState and `observeSortie(sortieId)`.
  Write `MissionViewModel.kt`: plain state holder for the tab.
  Write the real `MissionScreen.kt`: this week's pattern track, outline notes, InkRuledListRow per sortie (index, type, focus, state). Sortie detail via rememberSaveable selected-id with BackHandler: PLANNED/IN_FLIGHT/PENDING with plan → FlightPlanSection; PENDING no plan → "No Flight Plan yet"; LANDED → record; SCRUBBED → record with reason.
  Tests: MissionStatesTest (new file) — pattern chips from mixed sortie states, outline notes present/absent, sortie rows, draft mission, sortie detail for planned/PENDING/landed/scrubbed sorties.

- [x] Step 5 — In-Flight placeholder, shell wiring, existing test updates, docs
  Write `InFlightScreen.kt`: placeholder showing sortie's Flight Plan title, eyebrow "IN FLIGHT · SORTIE N", and Back button.
  Update `ShellNav.kt`: add inFlightSortieId, openInFlight(id), back(), extend encode/decode with optional in-flight part.
  Update `LiftoffShell.kt`: get container, wire InFlightScreen branch (full height, no bars when active), pass onOpenInFlight to LaunchpadScreen, light nav-bar icons for Mission Control and In-Flight.
  Update `ShellNavTest.kt`: add cases for in-flight route, back behavior, encode/decode round trip.
  Update `LiftoffShellTest.kt`: replace placeholder copy assertions with real screen content (draft's CONFIRM button on Launchpad, Mission tab title), use composeRule.waitUntil(5_000) for Room off-main-thread; keep placeholderScreensShowTheirCopy for Landed check only.
  Update ARCHITECTURE.md: mark M2 as Done in milestone table, update ui row in package table.

## Blockers from the final review

- [x] Show the current sortie as a Current chip on the pattern track
  In app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt, deriveChips maps PENDING, PLANNED and IN_FLIGHT all to ChipState.Upcoming, so ChipState.Current never appears outside previews. The brief asks for a pattern track 'with landed, current and upcoming chips', and the mockup draws the current sortie as the larger 52 dp chip. deriveChips should mark the sortie returned by currentSortie(sorties) as Current, on both Launchpad and the Mission tab. Tests that would show it: PlanFormatTest.deriveChipsMixedStates and MissionStatesTest.patternChipsFromMixedSortieStates. The second one currently asserts Upcoming for index 2, which is the current PLANNED sortie.
- [ ] Put the estimated minutes and set count in the FLIGHT PLAN head
  app/src/main/java/com/liftoff/app/ui/sortie/FlightPlanSection.kt shows only the text 'FLIGHT PLAN'. formatPlanHead ('≈55 min · 14 sets') is written and tested but nothing in the UI calls it. The brief requires a 'FLIGHT PLAN' head 'with the estimated minutes and set count'. FlightPlanSection should show formatPlanHead(plan) next to the head.
- [ ] Make the Launchpad derivation tests actually drive LaunchpadStates
  In app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadStatesTest.kt, plannedLiftShowsPlan, pendingAfterScrub, inFlightAfterLaunch, closedAfterAllSortiesLanded and the second half of liftUpdateThroughStates never call states.observe(). They only query the DAOs and currentSortie(). The comments say observeWeek won't re-emit when only the sortie table changes, but that is wrong: the generated MissionDao_Impl observes both 'sortie' and 'mission'. So the done criterion 'tests cover how each Launchpad state (… PLANNED lift with seeded exercises, PENDING, IN_FLIGHT, closed) … are derived from the database' is not met. The PLANNED-lift test must also check the eyebrow, title, rows, load strings, head and coach note, as the plan says. Each of these tests should read states.observe().first { it is LaunchpadState.X } and assert on the derived fields. The live-update test should collect one subscription across confirm, scrub and launch.
- [ ] Use set counts on both sides of 'N of M sets landed'
  In app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt, the LANDED and SCRUBBED branches set planExerciseCount = plan.exercises.size but count DONE sets for planLandedCount. MissionScreen then prints 'N of M sets landed.', so a 3-exercise, 9-set plan with every set done reads '9 of 3 sets landed.' M must be the total set count. LandedDetailView also turns landedAt into a date in ZoneOffset.UTC instead of the device zone, so a sortie landed in the evening west of UTC shows the next day's date. A MissionStatesTest case for landed and scrubbed sorties with a multi-set plan would catch the count.
- [ ] Show the sortie number, not the database id, on the In-Flight placeholder
  app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt builds 'IN FLIGHT · SORTIE $sortieId' and the fallback title 'Sortie $sortieId' from the Room row id. After a few weeks of use the screen reads something like 'SORTIE 37'. The plan specifies 'SORTIE N', meaning index + 1. Load the sortie and use its index + 1.
- [ ] Run summary focus and coach note must not both repeat the plan notes
  In FlightPlanSection.kt, the run 'Focus' row takes its value from plan.plan.notes instead of the sortie's focus. The coach note then shows the same plan.plan.notes, filtered only against the plan title. A SIMPLE_RUN plan stores notes = sortie.focus (SortiePlanning.kt), so a run with focus 'easy' shows 'Focus: easy' and also 'Coach: easy'. A generated run with coach notes would show those notes mislabelled as Focus as well. Decision 10 says the coach note appears only when notes differ from the sortie's focus. The Focus row should come from the sortie's focus, and the coach note should be hidden when it equals that focus. A derivation or format-level test with a focused simple run would show this.
- [ ] Make the Mission tab list scroll
  MissionTabContent in app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt is a Column with no verticalScroll. The 68 sp title block, the pattern track, the outline notes and up to 7 sortie rows can be taller than the content area between the top bar and the bottom nav. The last sorties are then clipped and can't be tapped, which breaks 'tapping a sortie shows its Flight Plan'. Add verticalScroll, as SortieDetailView and the Launchpad already have.
