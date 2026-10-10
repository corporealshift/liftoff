# Tasks: Launchpad and Mission screens

- [x] Step 1 — Ensure the current week, and the change signal
  Add `currentWeekStart` (StateFlow<LocalDate?>) to MissionManager; set it at the end of `onAppOpen()` after the transaction commits. Add `repeatOnLifecycle(Lifecycle.State.STARTED)` call in MainActivity so `container.missionManager.onAppOpen()` runs on app open and foreground return. Add `FlightPlanDao.observeChanges(): Flow<Int>` selecting from flightPlan, plannedExercise, plannedSet, runSegment as a change signal. Run existing MissionManagerTest and DAO tests to confirm they still pass.
  No new test files; only updates to existing code plus verification that existing tests pass.

- [ ] Step 2 — Formatting, shared parts, ChipState.Scrubbed
  Write `PlanFormat.kt` with pure formatting functions: week eyebrow date (`OCT 5`), sortie label (`SORTIE 2 OF 5 · LIFT`), exercise load (`3×8 · 135`, `3×8 · BW`, `3×45 s`), Flight Plan head (`≈55 min · 14 sets`), and pattern chip states. Write `PlanFormatTest` covering every formatter including weight `135.0` → `135`, `22.5` → `22.5`, null weight → `BW`, seconds → `45 s`, differing sets, null estimate, singular "1 set".
  Write `FlightPlanSection` composable: FLIGHT PLAN head over ink rule, exercise rows (index, name, load) for lifts; run summary rows for runs; coach note ("Coach:" in Teal weight 600 + text in Muted with LiftoffType.note()).
  Add `ChipState.Scrubbed` to PatternTrack: 44 dp Sand circle, 2 dp ink border, letter in Muted. Handle it in `chipSizeDp` and `PatternChipView`. Do not add a new @Preview.
  Tests: PlanFormatTest (new file).

- [ ] Step 3 — Launchpad derivation, state holder, and screen
  Write `LaunchpadState.kt`: sealed LaunchpadState (Loading, Draft, Planned, Pending, InFlight, Closed) with the derivation class `LaunchpadStates(db, manager)` deriving from `manager.currentWeekStart`, `missionDao.observeWeek()`, and `flightPlanDao.observeChanges()`.
  Write `LaunchpadViewModel.kt`: plain state holder with actions toggleChip, addChip, removeChip, confirm, launch, requestScrub, setScrubReason, confirmScrub, cancelScrub.
  Write the real `LaunchpadScreen.kt`: content scrolls (TitleBlock eyebrow + title, PatternTrack, FlightPlanSection); actions pinned at bottom. Each state renders per the plan (Planned: Launch button + Scrub; Pending: status line + Scrub; InFlight: Resume; Draft: editable chips + Confirm; Closed: completion; Scrub: confirmation dialog with Paper card). Actions catch IllegalStateException and re-run onAppOpen() on failure.
  Tests: LaunchpadStatesTest (new file) — Robolectric tests against in-memory DB and MissionManager with fixed clock: Loading before onAppOpen, Draft with default pattern, PLANNED lift with seeded exercises, PLANNED run after confirm, PENDING after scrub, IN_FLIGHT after launch, Closed. Also checks live state updates after confirm, scrub, launch.

- [ ] Step 4 — Mission tab derivation, state holder, and screen
  Write `MissionState.kt`: MissionTabState (Loading, Week) with the `MissionStates(db, manager)` derivation, plus SortieDetailState and `observeSortie(sortieId)`.
  Write `MissionViewModel.kt`: plain state holder for the tab.
  Write the real `MissionScreen.kt`: this week's pattern track, outline notes, InkRuledListRow per sortie (index, type, focus, state). Sortie detail via rememberSaveable selected-id with BackHandler: PLANNED/IN_FLIGHT/PENDING with plan → FlightPlanSection; PENDING no plan → "No Flight Plan yet"; LANDED → record; SCRUBBED → record with reason.
  Tests: MissionStatesTest (new file) — pattern chips from mixed sortie states, outline notes present/absent, sortie rows, draft mission, sortie detail for planned/PENDING/landed/scrubbed sorties.

- [ ] Step 5 — In-Flight placeholder, shell wiring, existing test updates, docs
  Write `InFlightScreen.kt`: placeholder showing sortie's Flight Plan title, eyebrow "IN FLIGHT · SORTIE N", and Back button.
  Update `ShellNav.kt`: add inFlightSortieId, openInFlight(id), back(), extend encode/decode with optional in-flight part.
  Update `LiftoffShell.kt`: get container, wire InFlightScreen branch (full height, no bars when active), pass onOpenInFlight to LaunchpadScreen, light nav-bar icons for Mission Control and In-Flight.
  Update `ShellNavTest.kt`: add cases for in-flight route, back behavior, encode/decode round trip.
  Update `LiftoffShellTest.kt`: replace placeholder copy assertions with real screen content (draft's CONFIRM button on Launchpad, Mission tab title), use composeRule.waitUntil(5_000) for Room off-main-thread; keep placeholderScreensShowTheirCopy for Landed check only.
  Update ARCHITECTURE.md: mark M2 as Done in milestone table, update ui row in package table.
