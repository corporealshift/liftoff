# Map — issue 68 (In-Flight un-launch back to PLANNED)

Branch HEAD at plan time: `c9f98ad` (branched from main after PR #52). Nothing named
`unlaunch`/`un-launch` exists anywhere in the repo today.

## Project docs that set rules

- **CLAUDE.md:10** — a decision that changes gets an **amendment at the end of `DESIGN.md`**.
- **DESIGN.md:783-805** — `## Amendments after approval`: newest first, dated, "what changed, when,
  and why", plus an inline mark in the section affected. Newest entry today is 2026-10-20.
- **DESIGN.md §5.1 (157-172)** — the sortie state diagram (`PENDING ──generation stored──► PLANNED
  ──launch──► IN_FLIGHT ──land──► LANDED`, scrub branches) and one bullet per state. The IN_FLIGHT
  bullet says at most one sortie is in flight at a time. No IN_FLIGHT → PLANNED edge.
- **DESIGN.md §6 (204-219)** — the In-Flight action list as prose: Launch, check a set off, edit a
  set (saving an edit also marks it done), skip a set or exercise, **add a set**, notes per exercise
  and per sortie, Land, "every action is written to Room immediately", no network.
- **DESIGN.md §9 (553-576)** — Launchpad card bullets: PLANNED → "Launch, Regenerate and Scrub";
  PENDING → status + Retry/Re-fly/Scrub/View in nabu; IN_FLIGHT → "a Resume button". Then
  "**In-Flight.** The checklist described in §6."
- **ARCHITECTURE.md** — has **no sortie-transition table**. Two places name In-Flight's actions:
  the `com.liftoff.app.ui` package row (line 37, "`InFlightScreen.kt` with Land, Scrub, notes and
  run content") and the **M4** milestone row (line 83, "In-Flight screen, Launch, Land, resume after
  process kill"). Invariants 1-8 (line 60-69) — invariant 2: `domain/` is pure Kotlin, no Android
  imports (it does import `data` entities today).

## Domain

- **`domain/Sorties.kt` (59 lines)** — `SortieTransitions.legalTransitions` is a `Set<Pair<SortieState,
  SortieState>>` holding PENDING→PLANNED, PENDING→SCRUBBED, PLANNED→IN_FLIGHT, PLANNED→SCRUBBED,
  IN_FLIGHT→LANDED, IN_FLIGHT→SCRUBBED; `canTransition` / `requireTransition` (throws
  `IllegalTransitionException` from `domain/Exceptions.kt`). Free functions: `currentSortie(sorties)`
  (lowest `index` not LANDED/SCRUBBED), `requireNoneInFlight(inFlight)`, `launch(sortie, at)` →
  `copy(state = IN_FLIGHT, launchedAt = at)`, `land(sortie, at)`, `scrub(sortie, reason)`,
  `markPlanned(sortie)` → `copy(state = PLANNED)` (does **not** touch `launchedAt`).
- **`data/Enums.kt`** — `SortieState { PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED }`,
  `SetStatus { OPEN, DONE, SKIPPED }`, `SortieType { RUN, LIFT }`, `MissionStatus { DRAFT, ACTIVE, CLOSED }`.

## Data

- **`data/MissionManager.kt` (484 lines)** — ctor `(db, settingsStore, clock, zone)`; every sortie
  action is a `db.withTransaction { }`:
  - `scrub(sortieId, reason)` (257-270): ACTIVE check → `check(current != null && current.id == sortieId)`
    → domain `scrub` → `update` → `advance(mission.id, settings)`; returns the generation id advance queued.
  - `launch(sortieId)` (272-284): ACTIVE → current → `requireNoneInFlight(db.sortieDao().getInFlight())`
    → `launch(sortie, clock.millis())` → `update`. No `advance`, returns Unit.
  - `land(sortieId, runDistance, runMinutes)` (287-303): `check(sortie.state == IN_FLIGHT)` → domain
    `land` + actuals → `update` → `markOpenSetsNotDone(sortieId)` → `advance`.
  - `regenerateFlightPlan(sortieId, onQueued)` (345-384): needs coach host+token, `mission.status ==
    ACTIVE`, **`mission.weekStart == currentWeekOf(clock, zone)`**, current, and
    `state == PENDING || state == PLANNED`; run sorties need `settings.generateRunPlans`. So a
    PLANNED sortie is already accepted — no change needed for "Regenerate works after un-launch".
  - `storePlan` (396-435) refuses a sortie that is not PENDING/PLANNED.
  - `private advance(missionId, settings)` (443-480): closes the Mission when all sorties are done,
    else prepares a **PENDING** current sortie (simple plan or a QUEUED FLIGHT_PLAN row). A PLANNED
    current sortie makes it a no-op.
  - `private currentWeekOf(clock, zone)` (482-483).
- **`data/SortieDao.kt`** — `get`, `getForMission` (ordered by `index`), `getInFlight`
  (`WHERE state = 'IN_FLIGHT'`), `update`, `observeById` (Flow), `updateNotes`.
- **`data/Sortie.kt`** — `id, missionId, index, type, focus, focusRationale, state, launchedAt,
  landedAt, scrubReason, notes, runDistance, runMinutes`. `launchedAt` is nullable, so clearing it is
  a plain `copy(launchedAt = null)`.
- **`data/FlightPlanDao.kt`** — `getPlan(sortieId): FlightPlanDetail?` (126) is the read that carries
  every set; `observeChanges()` (185) is the invalidation counter `InFlightStates` combines on.
  Set mutations, all `@Transaction` helpers over `updateSetActuals(setId, actualReps, actualSeconds,
  actualWeight, status)`:
  - `checkSet` (281) — actuals := the **planned** values, status DONE.
  - `uncheckSet` (288) — actuals := null, status OPEN. So check-then-uncheck returns the row to the
    untouched shape exactly.
  - `saveSetEdit` (295) — actuals := the edited values, status DONE.
  - `skipSet` (302) — actuals null, status SKIPPED. `reopenSet` (308) — actuals null, status OPEN.
  - `skipExercise` (315) — `skipped = true` and its OPEN sets → SKIPPED; `unskipExercise` (322)
    restores OPEN with null actuals.
  - `addSet` (336) → `addExtraSet` (199): a new row with `status = OPEN`, all actuals null,
    `added = true`, planned values copied from the last set.
  - `markOpenSetsNotDone(sortieId)` (244) — land's "unchecked = SKIPPED" write.
- **`data/PlannedSet.kt`** — `reps/seconds/weight` (planned), `actualReps/actualSeconds/actualWeight`,
  `status`, `added`. **`data/Relations.kt`** — `FlightPlanDetail(plan, exercises: List<PlannedExerciseDetail>,
  segments)`, `PlannedExerciseDetail(plannedExercise, displayName, sets: List<PlannedSet>)`.

## UI

- **`ui/inflight/InFlightState.kt` (280 lines)** — `sealed interface InFlightState`: `Loading`,
  `NoChecklist`, `Run`, `Ready`. `Ready` carries `doneCount/setCount/openCount`, `progress:
  List<SetStatus>` and `cards: List<ExerciseCard>` of `SetRow` (which already holds `status`,
  `actualReps/actualSeconds/actualWeight`, `added`). Precedent for a derived flag: the free function
  `needsLandConfirmation(state)` (57) sitting next to the sealed type. `deriveInFlight(plan, sortie,
  unit, distanceUnit)` (99) is the pure derivation — it never reads `sortie.state`.
- **`ui/inflight/InFlightStates.kt` (30 lines)** — `observe(sortieId)` combines
  `flightPlanDao().observeChanges()`, `sortieDao().observeById(sortieId)` and the two units, then
  re-runs `getPlan` + `deriveInFlight`. So any set edit re-derives the state: a flag computed here
  makes the action appear/disappear live with no extra plumbing.
- **`ui/inflight/InFlightViewModel.kt` (184 lines)** — `state` is `observe(...).stateIn(Lazily, Loading)`.
  `sortieIsNoLongerInFlight(sortie)` (19) + `watchLeave(onLeave)` (52) leave the screen the moment the
  row is not IN_FLIGHT, via the atomic `left` flag and `leaveOnce`. `land` (91), `landRun` (120) and
  `scrub` (152) all follow one shape: `if (left.get()) return; if (busy) return; busy = true;` →
  `scope.launch { try { manager…; enqueueQuietly(id); leaveOnce(onDone) } catch CancellationException
  rethrow / catch Exception → `message.value = "Couldn't … this sortie."` finally `busy = false } }`.
  `message: MutableStateFlow<String?>`.
- **`ui/inflight/InFlightScreen.kt` (1041 lines)** — `when (state)` arms at 79-140 hand
  `onLand`/`onScrub` closures to `LoadingContent` / `NoChecklistContent` (285) / `RunContent` /
  `ReadyContent` (327). All three content composables end with the **shared**
  `internal fun Footer(message, onLand, onScrub)` (986-1014): Divider, Cream block, optional red
  message line, `InkButton("Land", 64.dp)`, `Spacer(8.dp)`, `UnderlinedTextButton("Scrub")`.
  `BackFooter` (1018) is Loading's and draws `UnderlinedTextButton(text = "Back")` (1028). The three
  `Footer(...)` call sites a new argument has to reach: `InFlightScreen.kt:320` (`NoChecklistContent`, defined
  285), `InFlightScreen.kt:383` (`ReadyContent`, defined 327) and `RunContent.kt:87`; the `when (state)` arms
  that build the closures are 80 Loading, 81 NoChecklist, 99 Run, 118 Ready. `RunContent.kt:32` is its own
  `fun RunContent(...)`.
- **`ui/theme/Buttons.kt:155` `UnderlinedTextButton(text, onClick, modifier)`** — `fillMaxWidth`,
  `heightIn(min = 44.dp)`, `Role.Button`, and it **uppercases the label** (tests match "SCRUB",
  "LAND", "SORTIE NOTE"). It has **no `enabled` parameter**, which is why an unavailable action is
  absent here too (same reason as LaunchpadScreen.kt:450's comment).
- **`ui/LiftoffShell.kt:125-134`** — hosts `InFlightScreen` with `onBack = { nav = nav.back() ?: nav }`,
  i.e. back to the tab it came from (the Launchpad). No shell change is needed for "goes to the Launchpad".
- **`ui/launchpad/LaunchpadState.kt`** — the ACTIVE arm picks `currentSortie` and, for
  PENDING/PLANNED, `deriveSortieCard(...)`; `regenerateAvailable = coachConfigured && (type != RUN ||
  generateRunPlans)` (193). An IN_FLIGHT current sortie gets the Resume card (278). **Nothing here
  needs to change**: an un-launched sortie is a PLANNED current sortie and already gets the PLANNED
  card with Launch / Regenerate / Scrub.
- **`ui/launchpad/LaunchpadScreen.kt:426`** — the rule the brief points at: "An unavailable
  Regenerate is absent, not dimmed — the rule PendingActions already follows."

## Notes, and edges worth knowing

- **Notes are columns, not a table.** A sortie note is `Sortie.notes`, written by
  `SortieDao.updateNotes` (58-60, "notes-only, so a sortie with no Flight Plan can carry a note"). An
  exercise note is `PlannedExercise.userNotes`, written by `FlightPlanDao.saveExerciseNote` (330), which
  passes the row's own `skipped` back so a note stays inert. Neither is touched by a sortie state change,
  so both survive un-launch untouched.
- **`deriveInFlight`'s three arms** (`InFlightState.kt:12-53`): `NoChecklist` = "a sortie with no plan or
  no exercises (a run sortie whose plan never got written)"; `Run` requires
  `sortie.type == RUN && plan != null`; `Ready` = a LIFT sortie with exercises. A per-arm availability
  rule has to decide all three.
- `Ready.cards` is `List<ExerciseCard>`, and a skipped exercise is `CardStatus.SKIPPED` — so a predicate
  over the cards must not stop at the active card, or a skipped exercise looks like nothing was recorded.
- `Ready.progress` is `allProgress`, built by `allProgress.addAll(setRows.map { it.status })` over **every**
  exercise in `plan.exercises` (InFlightState.kt:222, 275) — skipped exercises included — so
  `progress.all { it == SetStatus.OPEN }` already covers the plan's named risk. `cards` is the only variant
  that also carries the actuals.
- **No existing query counts recorded sets** — nothing in `data/` matches `actualReps IS NOT NULL` or
  `status != 'OPEN'`. The read is `getPlan`, which already carries every set.
- `uncheckSet` (288) and `reopenSet` (308) restore actuals to null and status to OPEN, so a
  check-then-uncheck returns the plan to the untouched shape exactly — the flag can come back.
- `addSet`/`addExtraSet` writes `status = OPEN` with null actuals, so an added set does **not** read as
  recorded. `markOpenSetsNotDone` (244) is land's write, and only land does it.
- ARCHITECTURE.md has no transition table; the two rows that name In-Flight's actions are line 37 (the
  `com.liftoff.app.ui` package row) and line 83 (the M4 milestone row).

## verify.sh — the names this run has to produce

`verify.sh` was **rewritten after the verify step** by `df4c96c` "run: verify.sh rewritten by claude
review", which dropped the Compose footer test name. The table below is the current script: **10 names**.

`verify.sh` (this run's definition of done) runs the CI gate (`:app:assembleDebug :app:testDebugUnitTest`,
the same two tasks `.github/workflows/ci.yml` runs, via `bash gradlew.sh … --no-build-cache`, up to 3
attempts on a hang/build-only failure), then requires each name below to appear as a **passing** test case
in this run's `app/build/test-results/testDebugUnitTest/TEST-*.xml`. Names are matched across every report,
so which class a test sits in is the implementer's call; the class suggested here is where the plan puts it.

| name | suggested class |
| --- | --- |
| `unlaunchReturnsAnUntouchedInFlightLiftToPlannedWithLaunchedAtNull` | `MissionManagerTest` |
| `unlaunchIsRefusedOnceAnySetIsRecorded` | `MissionManagerTest` |
| `unlaunchIsRefusedForANonCurrentSortieOrANonActiveMission` | `MissionManagerTest` |
| `unlaunchIsRefusedForASortieThatIsNotInFlight` | `MissionManagerTest` |
| `anInFlightRunOrASortieWithNoChecklistIsUnLaunchable` | `MissionManagerTest` |
| `launchWorksAgainAfterUnlaunch` | `MissionManagerTest` |
| `regenerateFlightPlanIsAcceptedAfterUnlaunch` | `MissionManagerTest` |
| `unlaunchIsAvailableForAnUntouchedLiftAndForARun` | `InFlightStatesTest` |
| `unlaunchIsUnavailableOnceAnySetOfThePlanIsRecorded` | `InFlightStatesTest` |
| `unlaunchOnThisScreenLeavesOnlyOnce` | `InFlightLeaveTest` |

It also requires these classes to have run with everything in them passing (they pass today; they are the
"must not disturb" net): `SortiesTest`, `MissionManagerTest`, `MissionManagerPlanTest`,
`MissionManagerReFlyTest`, `SortieDaoTest`, `FlightPlanDaoTest`, `InFlightStatesTest`,
`InFlightViewModelTest`, `InFlightLandScrubTest`, `InFlightScreenTest`, `InFlightLeaveTest`,
`InFlightRunTest`, `LandScrubDialogTest`, `NoteDialogTest`, `LaunchpadStatesTest`,
`LaunchpadPlannedActionsTest`, `LaunchpadRegenerateTest`, `LiftoffShellTest`, `ShellNavTest`.
`LandScrubDialogTest` and `NoteDialogTest` render the whole screen at a fixed `@Config` window height, so
they are where a taller footer would show up.

**Proven to fail on untouched code** by one real detached run (`app/build/verify-final-i68.log`, against the
pre-rewrite script with 11 names): gate attempts 1 and 2
hit this repo's known 5-minute test-task hang (no `TEST-*.xml` written) and the script's retry handled both; attempt 3
was `BUILD SUCCESSFUL in 1m29s` with 736 tests / 0 failures / 0 errors / 0 skipped in 89 reports. Then all the new names
reported `did not run`, all 19 class checks passed, `Result: 20 passed, 11 failed`, `EXIT=1`. So the script fails today
for exactly the reason the brief is not yet met, and nothing else. The rewrite keeps the same gate machinery and the
same 19 class checks, so the counts a work step sees are unchanged apart from one fewer named test.

Deliberately **not** checked, and why, is written in verify.sh's header: DESIGN.md/ARCHITECTURE.md wording,
the label/colour/position of the new action, **a Compose test of the footer** (the availability is tested at the
state level; the footer itself is read off the diff by Kyle in the pull request), the view-model refusal message
and busy guard, which file each test sits in, and whether the transition goes through `SortieTransitions`.

## Fixture facts a work step needs (read off the existing tests)

- **`MissionManagerTest`**: `Clock.fixed(2026-01-12T00:00:00Z, UTC)`, `zone = { ZoneOffset.UTC }`, and
  `insertMission(LocalDate.of(2026, 1, 12), "R", ACTIVE)` is the current week. `insertSortie(missionId,
  index, type, focus, state)` inserts with `launchedAt = null`, so a test that needs an IN_FLIGHT row with a
  launch time sets it after insert or calls `manager.launch`. `advanceClock(days)` rebuilds the manager on a
  later instant — that is how `launchRecordsLaunchedAt` proves `launchedAt` is the clock's value.
  `configureCoach(host, token)` writes the two settings. Refusals are asserted as
  `try { manager.x(…); throw AssertionError(…) } catch (e: IllegalStateException) { }`.
- **`regenerateFlightPlan(sortieId, onQueued: (Long) -> Unit = {}): List<Long>`** — `onQueued` gets the **new**
  QUEUED `FLIGHT_PLAN` Generation id; the **return value is the ids it superseded**, empty when there were
  none. `LaunchpadRegenerateTest` is the fixture to copy for the after-un-launch case: `activeWeek()` sets
  host+token+defaultPattern, `manager.onAppOpen()`, `continueWithoutOutline`, and its `queuedRowEnqueued(...)`
  reads the enqueued row back. A LIFT current sortie (pattern starting `L`) avoids the run toggle.
- **`InFlightStatesTest`**: `buildFixture()` = DRAFT mission `weekStart 2026-01-05`, one IN_FLIGHT LIFT sortie
  (index 1), `flightPlanDao.writePlan` with 3 exercises / 7 sets (35.0 LB × 10, BW × 12, 20 LB × 8 and 45 s).
  Tests call `deriveInFlight(plan, sortie, WeightUnit.LB)` directly — `distanceUnit` has a default.
- **`InFlightLeaveTest`** / **`InFlightLandScrubTest`**: `manager.onAppOpen()` + `continueWithoutOutline` on
  the default RLRLR pattern → sortie 0 is a PLANNED run; `landSortie0` launches and lands it; sortie 1 is the
  PENDING LIFT, given a plan by `flightPlanDao.writePlan(sortie.id, liftPlanDraft())` and pushed to PLANNED
  with `markPlanned` (which leaves `launchedAt` null), then `manager.launch`. The leave counter is an
  `AtomicInteger` polled under `withTimeout`.
- **`InFlightScreenTest`**: `landFooterOffersLandAndScrubAndNoBack` (408) seeds a LIFT (index 1, 2 exercises /
  5 sets) and a RUN with no plan (index 0), both IN_FLIGHT, and switches between them with
  `var sortieId by mutableStateOf(...)` + `key(sortieId)` in one `setContent`. Its helper
  `assertFooterOffersLandAndScrub` (483) asserts `LAND` and `SCRUB` exist **and that no node's text is
  `BACK` case-insensitively** — so the new action's label cannot be the bare word "Back" on a loaded sortie.
  `UnderlinedTextButton` upper-cases what it is given, so the node text is the label upper-cased
  ("BACK TO LAUNCHPAD" is not `BACK`; `onAllNodesWithText` is exact unless `substring = true`).
  `doneBodyweightSetStepperShowsBW` (601) is the name this repo records as randomly flaky with a closed Room
  pool; verify.sh retries the gate when it is the only failure.
- `manager.land(sortieId)` has defaulted `runDistance`/`runMinutes`, so a run can be landed with one argument.
- `launch`'s third check is `requireNoneInFlight(sortieDao().getInFlight())`. It **cannot be copied into un-launch**:
  the sortie being un-launched is itself IN_FLIGHT, so `getInFlight()` is non-empty and reusing that guard refuses the
  only case the action exists for. un-launch's checks are Mission ACTIVE, this sortie is current, and state IN_FLIGHT.
- A LANDED or SCRUBBED sortie is never `currentSortie` (that function skips both states), so the cases that isolate
  the state check from the current check are a **PLANNED** and a **PENDING** current sortie of an ACTIVE Mission.
- `FlightPlanDao.addSet(plannedExerciseId: Long): Long` (336) is the added-set write; `getPlan` (126) is the read that
  carries every set of every exercise, skipped ones included.
- The regenerate read-back shape (`LaunchpadRegenerateTest`): `activeWeek()` (122) sets host + token + defaultPattern
  `"LRL"`, then `manager.onAppOpen()` and `continueWithoutOutline(missionId)`, and the current sortie is the one with
  `index == 0`; `queuedRowEnqueued(missionId, sortieId)` (199) reads `generationDao.get(queuedIds.single())` and
  asserts `kind == FLIGHT_PLAN`, `status == QUEUED`, and its `sortieId`.
- `assertFooterOffersLandAndScrub` (`InFlightScreenTest.kt:483`) rejects any node whose text is `BACK` with
  `ignoreCase = true` and `substring` left at its default `false`, so it matches the whole label only —
  `BACK TO LAUNCHPAD` passes it.
- `InFlightLeaveTest` drives the **view model**, not the Compose screen (`vm.land { count.incrementAndGet() }` after
  `watching(vm, count)`), so the un-launch leave case is a view-model test on Room, not a Robolectric tap.

## Tests that already exist (where new cases go)

- **`data/MissionManagerTest.kt`** — the launch/land/scrub cases live here: `launchRecordsLaunchedAt`
  (409), `launchRejectedWhileAnotherSortieInFlight` (425), `landMarksUncheckedSetsNotDone` (489),
  `landAdvancesToNextSortie` (521), `scrubInFlightKeepsCheckedSets` (584), plus the generation-id
  cases (682, 703). Fixtures are already in this file.
- **`domain/SortiesTest.kt`** — `legalTransitionsSucceed` (13) and `illegalTransitionsAreRejected` (40)
  enumerate the pairs; a new legal pair must be added to both (the illegal list must not claim
  IN_FLIGHT→PLANNED is illegal).
- **`ui/inflight/InFlightStatesTest.kt`** — derive-level tests over a seeded plan
  (`buildFixture` 45): `openCountCountsOpenSets` (289), `confirmationNeededOnlyWhenSetsAreOpen` (335),
  `noChecklistNeedsNoConfirmation` (371).
- **`ui/inflight/InFlightLandScrubTest.kt`** — view-model against in-memory Room on a Mission the app
  itself made (`continueWithoutOutline` on an RLRLR week, land sortie 0, seed a lift plan on sortie 1,
  launch it): `launchSortie1()` returns `(vm, Ready)`; `doubleTapLandsOnce` (445) and
  `secondLandIsRejectedWithoutCrash` (499) are the busy-guard shape; `scrubbingASortieThatIsNoLongerCurrentIsRejectedWithoutCrash`
  (410) is the refusal shape (asserts `message` and that nothing escaped via `escaped`).
- **`ui/inflight/InFlightScreenTest.kt`** — Robolectric Compose, `@Config(qualifiers =
  "w411dp-h2000dp")`, `AppContainer(context)` as the host; `landFooterOffersLandAndScrubAndNoBack`
  (408) + `assertFooterOffersLandAndScrub` (483) are the footer assertions a new action must not break.
- **`ui/inflight/InFlightLeaveTest.kt`** — the once-only leave gate: `landOnThisScreenLeavesOnlyOnce`
  (280), `scrubOnThisScreenLeavesOnlyOnce` (297), `noLeaveWhileTheSortieIsStillInFlight` (318),
  `afterLeavingTheScreenWritesNothing` (344).
- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. **Measured on this branch at the
  verify step: 736 tests in 89 reports, 0 failures / 0 errors / 0 skipped, BUILD SUCCESSFUL in 1m29s**
  (cold, `--no-build-cache`). Any expected total in the tasks has to be 736 plus the new tests.
  Re-counted at the tasks step from the reports that run left in `app/build/test-results/testDebugUnitTest`
  (89 `TEST-*.xml`, `tests=736 failures=0 errors=0 skipped=0`); the only commits since are `.rho` files and
  `verify.sh`, so that is still HEAD's baseline.
- `RunLandingTest` is `parseRunEntry` on its own — no Android, no Compose, no Room — so it does not render
  the In-Flight footer and is not in verify.sh's must-not-disturb list.
