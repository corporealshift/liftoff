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
  `BackFooter` (1018) is Loading's. `RunContent.kt:87` calls the same `Footer`.
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
- **No existing query counts recorded sets** — nothing in `data/` matches `actualReps IS NOT NULL` or
  `status != 'OPEN'`. The read is `getPlan`, which already carries every set.
- `uncheckSet` (288) and `reopenSet` (308) restore actuals to null and status to OPEN, so a
  check-then-uncheck returns the plan to the untouched shape exactly — the flag can come back.
- `addSet`/`addExtraSet` writes `status = OPEN` with null actuals, so an added set does **not** read as
  recorded. `markOpenSetsNotDone` (244) is land's write, and only land does it.
- ARCHITECTURE.md has no transition table; the two rows that name In-Flight's actions are line 37 (the
  `com.liftoff.app.ui` package row) and line 83 (the M4 milestone row).

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
- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. Suite baseline on **this** branch
  is not yet measured — measure it before writing an expected total anywhere.
