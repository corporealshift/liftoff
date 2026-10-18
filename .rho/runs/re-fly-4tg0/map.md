# Map — re-fly-4tg0

HEAD at plan time: `a0d7f70 run: plan outline` (on top of `9b1b7c7 Landed history screen (#24)`).
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

- `LaunchpadState.kt` (207) — sealed states `Loading/Draft/Planned/Pending/InFlight/Closed`. `Pending` carries `eyebrow, title, chips, sortieId, sortieIndex` only — no type/focus/plan, so it needs a field for "a source plan exists". `LaunchpadStates(db, manager).observe()` = `manager.currentWeekStart.flatMapLatest { missionDao().observeWeek(ws).combine(flightPlanDao().observeChanges()) { … derive } }`; `derive` is `suspend` and already calls `getPlan`, so a history query there is in the existing grain. Title for a PENDING sortie is `focus ?: "Run"/"Lift"`.
- `LaunchpadViewModel.kt` (137) — `state = LaunchpadStates(...).observe().stateIn(scope, Lazily, Loading)`; each action reads `state.value`, guards on the state type, `scope.launch { try { manager.x } catch (IllegalStateException) { runCatching { manager.onAppOpen() } } }`. `scrubbing` is a `mutableStateOf` flag driving the dialog.
- `LaunchpadScreen.kt` (338) — `LaunchpadContent(viewModel, onOpenInFlight)` (internal, so tests render it with their own view model); `PendingContent` (137) shows TitleBlock + PatternTrack + a muted `Text("Flight Plan not ready yet.", LiftoffType.textButton().copy(color = Muted))`; bottom bar `ActionButtons` → `PendingActions(onRequestScrub)` = one `UnderlinedTextButton("Scrub", …)` (287); `PlannedActions` = Launch + `UnderlinedTextButton("Scrub")`. ScrubDialog is composed after the body when `viewModel.scrubbing`.
- `ui/theme/Buttons.kt` holds `UnderlinedTextButton`; `ui/sortie/FlightPlanSection.kt` + `PlanFormat.kt` render a `FlightPlanDetail` (`formatPlanHead`, `formatExerciseLoad`) and are shared by Launchpad/Mission/In-Flight/Landed.

## Tests that already exist (shape to copy)

- `domain/SortiePlanningTest.kt`, `SortiesTest.kt` — plain JUnit, `Sortie(...)` fixtures built inline with named args.
- `data/MissionManagerTest.kt` (446, Robolectric `@Config(sdk=[34])`, in-memory Room `allowMainThreadQueries`, fixed `Clock` 2026-01-12 UTC, `SettingsStore(PreferenceDataStoreFactory.create{cacheDir})`) — helpers `insertMission(weekStart, pattern, status)`, `insertSortie(missionId, index, type, focus, state)`, `advanceClock(days)`; refusals asserted with try/catch on `IllegalStateException`.
- `ui/launchpad/LaunchpadStatesTest.kt` (300) — derivation tests; `pendingAfterScrub` seeds pattern "RL", confirms, scrubs sortie 0 and reads `LaunchpadState.Pending` via `states.observe().first { … }`.
- `ui/launchpad/LaunchpadScrubTest.kt` (173) — view-model + DB test: helper builds the launchpad, `withTimeout(5_000)` on `vm.state.first { … }`, poll-with-timeout helper for the DB row, `job.cancel()` before `db.close()` in tearDown.
- `ui/launchpad/LaunchpadDraftControlsTest.kt` / `LaunchpadDraftGuardsTest.kt` — compose render tests of `LaunchpadContent`.
- `data/FlightPlanDaoTest.kt`, `ui/landed/LandedStatesTest.kt` (8 tests), `ui/LiftoffShellTest.kt` (12), `ui/inflight/InFlightScreenTest.kt` (15) — must stay green.

## Machine notes

- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; run it detached (`nohup … > app/build/gate.log 2>&1; echo EXIT=$?`) after `bash gradlew.sh --stop` and `mkdir -p app/build`; `wait` on `EXIT=`.
- `git show branch:path` fails in Git Bash here; `git commit -F <file>` needs the file in the worktree root.
