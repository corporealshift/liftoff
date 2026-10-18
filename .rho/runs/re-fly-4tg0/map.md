# Map — re-fly-4tg0

HEAD at plan time: `a0d7f70 run: plan outline` (on top of `9b1b7c7 Landed history screen (#24)`). HEAD at tasks time: `3ba745e run: verify.sh rewritten by claude review` (that rewrite dropped one named test, see verify.sh below). Nothing Re-fly-related exists in `app/src` yet beyond `FlightPlanSource.REFLY` in `data/Enums.kt`.
Docs that set rules: `CLAUDE.md` (gate via `bash gradlew.sh …`, `area: lowercase summary`, stage named files, §2 vocabulary, LF), `ARCHITECTURE.md` (invariant 2: `domain/` and `coach/` pure Kotlin), `DESIGN.md` §5.1 states (155–173), §5.4 Re-fly (195–200), §8 data model (520–551), §9 Launchpad PENDING (561–567), §13 Re-fly test cases (680–681), §10 failure handling (Re-fly available when the daemon is unreachable).

## Domain (pure, no Android imports) — `app/src/main/java/com/liftoff/app/domain/`

- `Sorties.kt` — `SortieTransitions` legal pairs (PENDING→PLANNED/SCRUBBED, PLANNED→IN_FLIGHT/SCRUBBED, IN_FLIGHT→LANDED/SCRUBBED); `currentSortie(sorties)` = lowest-index sortie that is not LANDED/SCRUBBED; `launch/land/scrub/markPlanned` return a copied `Sortie`; `requireNoneInFlight`.
- `SortiePlanning.kt` — `Preparation` (SimplePlan(draft) | AwaitGeneration) + `simpleRunPlan(sortie)`: builds a `FlightPlanDraft` with `rawJson = "{}"`, empty exercises/segments. Shows a `FlightPlanDraft` can be built off a `Sortie` alone. The re-fly copy is not like that: it reads a `FlightPlanDetail`, so it lives in `data/` (see the plan's Files).
- `Rollover.kt`, `Missions.kt`, `Pattern.kt`, `Week.kt`, `Exceptions.kt` (`IllegalTransitionException`).
- Domain files import `com.liftoff.app.data.*` rows (`Sortie`, `SortieState`, `FlightPlanDraft`) — Room-annotated but JVM-only, so that is the accepted reading of invariant 2. `FlightPlanDetail`/`Relations.kt` use `androidx.room.Embedded/Relation`; keep them out of domain.
- No test enforces purity; it is a convention.

## Data — `app/src/main/java/com/liftoff/app/data/`

- `MissionManager.kt` (159) — one `db.withTransaction` per op: `onAppOpen`, `setPattern`, `confirm`, `scrub`, `launch`, `land`. Guard pattern to copy: `check(mission.status == ACTIVE) { "Mission must be ACTIVE" }`, `check(current != null && current.id == sortieId) { "Sortie must be current" }`, `check(sortie.state == IN_FLIGHT) { … }`. Private `advance(missionId, settings)` prepares the current PENDING sortie (writes a SIMPLE_RUN plan + `markPlanned` for a run with generation off; otherwise leaves it PENDING). Ctor takes `clock`/`zone` (tests fix them).
- `FlightPlanDao.kt` (309) — `writePlan(sortieId, FlightPlanDraft)` is `@Transaction`: deletes any plan for that sortie, inserts the plan, then exercises via `exerciseDao().resolve(draft.name)` with `skipped = false`, `userNotes = null`, and sets with `actual* = null`, `status = OPEN`, `added = false`; then segments. So the copy's "planned values only / no added sets / cleared skips and notes" is mostly what `writePlan` already does — the draft just must not carry them. `getPlan(sortieId): FlightPlanDetail?`; `getTitlesForSortieIds(ids)` (cheap "which sorties have a plan"); `observeChanges(): Flow<Int>` (counts over the four plan tables — what makes the Launchpad re-derive); the In-Flight mutators (`checkSet`, `skipExercise`, `addSet`, `saveExerciseNote`, `markOpenSetsNotDone`).
- `SortieDao.kt` — `get`, `getForMission` (by `index`), `getInFlight`, `observeHistory()` and `getFinishedNewestFirst()` (LANDED+SCRUBBED, `ORDER BY m.weekStart DESC, s.index DESC`), `observeById`, `deleteById`, `updateNotes`.
- Rows: `Sortie` (`type`, `focus?`, `state`, `landedAt?`, `scrubReason?`, `runDistance?`, `runMinutes?`), `FlightPlan` (`sortieId` unique, `source`, `title`, `estimatedMinutes?`, `warmup?`, `notes?`, `runKind?`, `targetDistance?`, `targetPace?`, `rawJson` non-null), `PlannedExercise` (`order`, `exerciseId`, `equipmentIds`, `restSeconds?`, coach `notes`, `skipped`, `userNotes?`), `PlannedSet` (`order`, `reps?/seconds?/weight?`, `actual*`, `status`, `added`), `RunSegment` (`order`, `description`, `distance?`, `minutes?`).
- `Relations.kt` — `FlightPlanDetail(plan, exercises: List<PlannedExerciseDetail>, segments)`, `PlannedExerciseDetail(plannedExercise, displayName, sets)`, `FlightPlanDraft`/`ExerciseDraft`/`SetDraft`/`RunSegmentDraft`.
- `Enums.kt` — `FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }` (REFLY already exists), `SortieType { RUN, LIFT }`, `SortieState`, `SetStatus`.
- `PromptInputLoader.kt` reads `getFinishedNewestFirst()` and windows it in Kotlin — the precedent for reading history in one query.

## Launchpad — `app/src/main/java/com/liftoff/app/ui/launchpad/`

- `LaunchpadState.kt` (207) — sealed states `Loading/Draft/Planned/Pending/InFlight/Closed` *and* `LaunchpadStates(db, manager).observe()`/`deriveActive`, all in this one file. `Pending` carries `eyebrow, title, chips, sortieId, sortieIndex` only — no type/focus/plan, so it needs a field for "a source plan exists". It is built at three sites: 150 (PLANNED with no plan row), 165 (the real `SortieState.PENDING` branch), 197 (`else`); no test constructs a `Pending` by hand (`LaunchpadStatesTest:198` only casts an observed one), so a new field touches only those three. `observe()` = `manager.currentWeekStart.flatMapLatest { missionDao().observeWeek(ws).combine(flightPlanDao().observeChanges()) { … derive } }`; `derive` is `suspend` and already calls `getPlan`, so a history query there is in the existing grain. Title for a PENDING sortie is `focus ?: "Run"/"Lift"`.
- `LaunchpadViewModel.kt` (137) — `state = LaunchpadStates(...).observe().stateIn(scope, Lazily, Loading)`; each action reads `state.value`, guards on the state type, `scope.launch { try { manager.x } catch (IllegalStateException) { runCatching { manager.onAppOpen() } } }`. `scrubbing` is a `mutableStateOf` flag driving the dialog.
- `LaunchpadScreen.kt` (338) — `LaunchpadContent(viewModel, onOpenInFlight)` (internal, so tests render it with their own view model); `PendingContent` (137) shows TitleBlock + PatternTrack + a muted `Text("Flight Plan not ready yet.", LiftoffType.textButton().copy(color = Muted))`; bottom bar `ActionButtons` → `PendingActions(onRequestScrub)` = one `UnderlinedTextButton("Scrub", …)` (287); `PlannedActions` = Launch + `UnderlinedTextButton("Scrub")`. ScrubDialog is composed after the body when `viewModel.scrubbing`.
- `ui/theme/Buttons.kt` holds `UnderlinedTextButton`; `ui/sortie/FlightPlanSection.kt` + `PlanFormat.kt` render a `FlightPlanDetail` (`formatPlanHead`, `formatExerciseLoad`) and are shared by Launchpad/Mission/In-Flight/Landed.

## Tests that already exist (shape to copy)

- `domain/SortiePlanningTest.kt`, `SortiesTest.kt` — plain JUnit, `Sortie(...)` fixtures built inline with named args.
- `data/MissionManagerTest.kt` (446, Robolectric `@Config(sdk=[34])`, in-memory Room `allowMainThreadQueries`, fixed `Clock` 2026-01-12 UTC, `SettingsStore(PreferenceDataStoreFactory.create{cacheDir})`) — helpers `insertMission(weekStart, pattern, status)`, `insertSortie(missionId, index, type, focus, state)`, `advanceClock(days)`; refusals asserted with try/catch on `IllegalStateException`.
- `ui/launchpad/LaunchpadStatesTest.kt` (300) — derivation tests; `pendingAfterScrub` seeds pattern "RL", confirms, scrubs sortie 0 and reads `LaunchpadState.Pending` via `states.observe().first { … }`.
- `ui/launchpad/LaunchpadScrubTest.kt` (173) — view-model + DB test: helper builds the launchpad, `withTimeout(5_000)` on `vm.state.first { … }`, poll-with-timeout helper for the DB row, `job.cancel()` before `db.close()` in tearDown.
- `ui/launchpad/LaunchpadDraftControlsTest.kt` / `LaunchpadDraftGuardsTest.kt` — compose render tests of `LaunchpadContent`: a `draft(pattern)` helper builds the db + manager + `LaunchpadViewModel(db, manager, scope)`, and each case does `composeRule.setContent { LaunchpadContent(viewModel = viewModel, onOpenInFlight = {}) }`.
- `data/FlightPlanDaoTest.kt`, `ui/landed/LandedStatesTest.kt` (8 tests), `ui/LiftoffShellTest.kt` (12), `ui/inflight/InFlightScreenTest.kt` (15) — must stay green.

## Machine notes

- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; run it detached (`nohup … > app/build/gate.log 2>&1; echo EXIT=$?`) after `bash gradlew.sh --stop` and `mkdir -p app/build`; `wait` on `EXIT=`.
- Measured: the whole gate (`:app:assembleDebug :app:testDebugUnitTest --no-build-cache`) took 1m55s with a warm daemon and wrote 62 `TEST-*.xml` reports. A cold daemon adds ~30s. So the whole verify.sh runs in about 2–3 minutes — no need to detach it yourself, and verify.sh runs it inline under `timeout 2400`.
- `git show branch:path` fails in Git Bash here; `git commit -F <file>` needs the file in the worktree root.
- Scratch files for this run go under `app/build/` (already holds `verify-probe.log`, `m5probe.sh`, `m5body.sh`).

## verify.sh (committed by this session — the run's definition of done)

Shape copied whole from `.rho/runs/landed-history-screen-3c5m/verify.sh`: `index_reports` (one awk pass over every `TEST-*.xml` into `<method> <pass|fail|skipped>`, worst outcome wins), `t <method>` (the name must have run in this invocation and passed), `run_gate` (clears `$RESULTS`, `--no-build-cache`, rejects `FROM-CACHE|UP-TO-DATE`, one retry only when the log says the daemon died or OOM'd).

Ran at `e7f40fc` (untouched code): exit 1, 26 passed / 20 failed — the gate, 24 must-not-break names and one doc sub-check passed; the names below and the M5 row failed. That is the intended failure mode. `3ba745e` (claude's rewrite) removed one copy name, `reflyRefusesAPendingSortieWithNoSourcePlan`, and trimmed a few comments: 42 `t` calls now, 18 of them names this run must write. The behaviour is still the plan's (the chooser returns nothing, and `refly` refuses), it just has no test name of its own.

The 18 names this run must write (any class, any package — names are matched across every report):
- chooser (plain JUnit over the pure function): `reflyPrefersSameTypeAndFocusOverAMoreRecentDifferentFocusPlan`, `reflyFallsBackToTheMostRecentSameTypeWhenNoFocusMatches`, `reflyChoosesTheMostRecentlyLandedOfTheFocusMatches`, `reflyIgnoresScrubbedSortiesAndFinishedSortiesWithNoPlan`, `reflyReturnsNothingWhenNoLandedPlanOfThatTypeExists`
- copy (through `MissionManager` over in-memory Room): `reflyCopiesPlannedValuesAndNotActuals`, `reflyDoesNotCopyAddedSets`, `reflyClearsSkippedFlagsAndUserNotes`, `reflyCopiesRunFieldsAndSegments`, `reflyMarksTheCopyReFlyAndMakesTheSortiePlanned`, `reflyNeedsNoNetworkAndWritesNoGenerationRow`, `reflyRefusesASortieThatIsNotTheCurrentOne`, `reflyRefusesASortieThatIsNotPending`
- Launchpad (2 derivation + 3 screen): `pendingOffersReFlyWhenALandedPlanOfTheSameTypeExists`, `pendingOffersNoReFlyWithoutAMatchingLandedPlan`, `pendingScreenShowsReFlyBesideScrubWhenASourceExists`, `pendingScreenShowsAMutedLineAndNoReFlyWhenNoSourceExists`, `tappingReFlyCopiesThePlanAndMovesTheLaunchpadToPlanned`

The 24 "must not break" names it checks, all present and green at `e7f40fc` — do not rename them: `pendingAfterScrub`, `liveUpdateThroughStates`, `launchpadScrubTrimsTheReason`, `draftDrawsOneStepperPair`, `confirmLeavesLiftPending`, `scrubStoresReasonAndPreparesNextSortie`, `landAdvancesToNextSortie`, `landMarksUncheckedSetsNotDone`, `scrubInFlightKeepsCheckedSets`, `legalTransitionsSucceed`, `illegalTransitionsAreRejected`, `runWithGenerationOffGetsSimplePlan`, `writePlanReplacesExistingPlan`, `titlesForSortieIdsOmitSortiesWithNoPlan`, `historyHasOnlyLandedAndScrubbed`, `historyIsNewestFirstByWeekThenIndex`, `exportedV1SchemaMatchesDatabase`, `appOpensOnLaunchpad`, `bottomBarReachesEveryTab`, `pendingSortieWithoutPlanShowsNoPlan`, `seededLiftPlanShowsHeaderProgressAndCards`, `historyGroupsByWeekNewestFirstAcrossTwoWeeks`, `liftFlightPlanPromptWithOutlineMatchesGoldenFile`, `historyCarriesActualSetsAndExerciseNotes`.

The ARCHITECTURE.md check reads the M5 row with `awk '/^\| M5 \|/'` and takes field 4 (`-F'|'`), then reads the cell clause by clause (`;`, `.`, em dash end a clause; a comma does not, so a parenthetical list stays inside its clause). Re-fly passes only if some clause names it with a built word AND no clause names it as missing with nothing built in that clause; export/import must appear in a clause that calls it missing, so a bare "Done" fails. Not-built phrases are struck out before looking for a built word, so "not done" is not read as "done": `not (yet )?(started|built|done|shipped|implemented|covered|present|available|wired|in place)`, `not yet`, `missing`, `unbuilt`, `pending`, `to come`, `to do`, `todo`, `later`, `remaining`, `next`, `outstanding`, `no …yet`, ❌, ⏳. Built words: `built done shipped exists exist real complete wired in place available` ✅. Probed against ~48 candidate cells (scratch `app/build/probe.sh`, `probe2.sh`, `prog.awk` = the awk body extracted with sed): the current row and every "Re-fly not started"/"not yet built"/"all built" wording fails; every wording that marks Re-fly built and export/import to come passes, including "Re-fly built; export/import next" and "no export/import yet". Step 5 writes the row to match: keep `| M5 |` at the line start, one status cell, no `|` inside it.

## Tasks (written by the tasks session)

`tasks.md` splits the plan's 6 Order steps into 5 tasks, each ending in its own commit: 1 the chooser
(`domain/ReFly.kt` + `ReFlyTest`, 5 names) · 2 the copy (`data/ReFlyCopy.kt` + `MissionManager.refly` +
`MissionManagerReFlyTest`, 8 names) · 3 the availability derivation (`LaunchpadState.kt` +
`LaunchpadStatesTest`, 2 names) · 4 the screen (`LaunchpadViewModel.refly` + `PendingActions` +
`LaunchpadReFlyTest`, 3 names) · 5 `ARCHITECTURE.md`'s rows *plus* the cold whole-suite gate, so the
gate's totals land in that commit's body (a gate-only step has no repo artifact otherwise). 18 named
tests, 5 + 8 + 2 + 3, each named by exactly one task.
