# Map — the draft's coach status

Run: `the-draft-s-coach-status-derived-and-609h`. Branch HEAD at planning: `796c5cc` (plan outline), on top of
PR #39 (`39aaef1`), which is where `confirm` / `retryOutline` / `continueWithoutOutline` and the outline
generation rows landed. HEAD now: `b15ec7d` (`run: verify.sh rewritten by claude review`); the plan is the
revised one, `562e415`. Tasks: `.rho/runs/the-draft-s-coach-status-derived-and-609h/tasks.md` (four tasks:
1 derivation + plain JUnit, 2 the wiring, 3 the live Robolectric class, 4 the whole gate).

## Docs that set rules

- `DESIGN.md` §4.2 (lines 119–143): Confirm queues an outline generation; "If outline generation fails, the
  Mission stays a draft with an error and a retry"; "Continue without outline" is the escape hatch out of DRAFT.
- `DESIGN.md` §9 (553–577): the Launchpad's draft line — "the draft, with editable pattern chips and **Confirm**".
  The generation-status wording ("Coach is planning…", "Mission Control offline — retrying", failure with its
  reason) is drawn there for the PENDING sortie; this run builds the same status for the **draft**, state only.
- `DESIGN.md` §10 (580–593): unreachable daemon → "the Launchpad says 'Mission Control offline'".
- `ARCHITECTURE.md` invariant 2: `domain/` and `coach/` are pure Kotlin, no Android imports. Invariant 5: no DI
  framework, one `AppContainer`.
- `CLAUDE.md`: gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages
  `area: lowercase summary`; stage named files only, never `git add -A`; §2 vocabulary in code; short comments.

## Main source

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt`
  - `sealed interface LaunchpadState`: `Loading`, `Draft(weekStart, pattern, missionId)`, `Planned`, `Pending`,
    `InFlight`, `Closed`. `Draft` is the only variant this run touches.
  - `class LaunchpadStates(db: LiftoffDatabase, manager: MissionManager)`; `observe()` =
    `manager.currentWeekStart.flatMapLatest { ws -> if (ws == null) flowOf(Loading) else
    db.missionDao().observeWeek(ws).combine(db.flightPlanDao().observeChanges()) { mws, _ -> derive(mws) } }`
    `.flowOn(Dispatchers.IO)`. `derive()` is suspend; the `MissionStatus.DRAFT` arm builds `Draft(...)` with no
    DAO read of its own. `deriveClosed` / `deriveActive` read `flightPlanDao` and `sortieDao` — untouched here.
  - Nothing today reads the `generation` table from this file.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`
  - `state: StateFlow<LaunchpadState>` = `LaunchpadStates(db, manager).observe().stateIn(scope, Lazily, Loading)`.
    Ctor is `(db, manager, scope, queueGeneration: (Long) -> Unit, cancelGeneration: suspend (Long) -> Unit)` —
    **no SettingsStore**, so anything the states class needs must arrive through `db` or `manager`.
  - `NOT_CONFIGURED_MESSAGE` (internal const, line 17) is the message a Confirm/Retry leaves when the coach isn't
    configured; `notConfiguredMessage` is a `mutableStateOf<String?>`, not part of the state.
  - Every draft action reads `state.value` and returns early unless it is a `Draft`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`
  - `DraftContent` (127–151) reads only `draft.weekStart` and `draft.pattern`, plus `viewModel.notConfiguredMessage`.
  - `DraftActions` (236–274) always draws `PrimaryButton("Confirm")` and `UnderlinedTextButton("Continue without
    outline")`. Both read only `draft.pattern.length`.
  - So adding a field to `Draft` needs **no change in this file** — verified: it never constructs `Draft` and
    reads no other field.
- `app/src/main/java/com/liftoff/app/data/Generation.kt` — `@Entity(tableName = "generation")`; `kind`,
  `missionId`, `sortieId?`, `status`, `error: String?`, `createdAt`, `finishedAt?`. `companion object` holds
  `const val COACH_OFFLINE = "Mission Control offline"`. `val isCoachOffline: Boolean get() = error ==
  COACH_OFFLINE && status in {QUEUED, RUNNING}`.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`
  - **Already exists and is exactly this run's read:** `fun observeNewestNonCancelledOutlineForMission(missionId):
    Flow<Generation?>` — `WHERE missionId = :missionId AND kind = 'OUTLINE' AND status != 'CANCELLED' ORDER BY
    createdAt DESC, id DESC LIMIT 1`. Its suspend twin is `getNewestNonCancelledOutlineForMission`.
  - `getOpenOutlineForMission(missionId)` (QUEUED/RUNNING outlines) is what freezes the pattern and what
    `continueWithoutOutline` hands back to cancel.
  - `setCoachOfflineMark(id, error: String?)` — `UPDATE ... SET error = :error WHERE id = :id AND status IN
    ('QUEUED','RUNNING')`; passing null clears the mark.
  - `markFailed(id, error, finishedAt)` writes the reason and stamps `finishedAt`; `markCancelled(id, t)`.
- `app/src/main/java/com/liftoff/app/data/Enums.kt` — plain Kotlin enums, no Room or Android imports, including
  `GenerationKind { OUTLINE, FLIGHT_PLAN }` and `GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`.
  `domain/` already imports such enums (`Missions.kt` imports `MissionStatus`, `SortieState`, …), so a domain
  derivation may take a `GenerationStatus` without breaking invariant 2. The Room `Generation` **entity** may not
  appear in the derivation's signature.
- `app/src/main/java/com/liftoff/app/domain/Coach.kt` — the only content is
  `fun coachIsConfigured(daemonHost: String, daemonToken: String): Boolean = host.trim().isNotEmpty() &&
  token.trim().isNotEmpty()`, with a comment saying it is the same rule that decides whether Confirm can ask for
  an outline. Natural home for the new status type + derivation.
- `app/src/main/java/com/liftoff/app/settings/SettingsStore.kt` — `val settings: Flow<Settings>`;
  `daemonHost` / `daemonToken` default to `""`. `Settings` is a plain data class in `com.liftoff.app.settings`.
  Setters a test uses: `setDaemonHost`, `setDaemonToken`, `setWeightUnit`, `setDefaultPattern` (each suspend).
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt`
  - Ctor `(db, settingsStore, clock = Clock.systemUTC(), zone = { ZoneId.systemDefault() })`; already imports
    `coachIsConfigured`. Public `val currentWeekStart = MutableStateFlow<LocalDate?>(null)` — the precedent for a
    manager exposing a Flow the states class combines.
  - `confirm(missionId)` and `retryOutline(missionId)` both do `settingsStore.settings.first()` then
    `if (!coachIsConfigured(...)) return ConfirmOutcome.CoachNotConfigured`; `retryOutline` additionally requires
    the newest non-cancelled outline to be FAILED, else `IllegalStateException`.
  - `setPattern` refuses while `getOpenOutlineForMission` is non-empty.
- `app/src/main/java/com/liftoff/app/AppContainer.kt` — `settingsStore` (line 20), `MissionManager(database,
  settingsStore)` (line 31). `LaunchpadViewModel` is **not** built here; it is built in `LaunchpadScreen.kt:52`.

## Tests that must keep passing

- `app/src/test/java/com/liftoff/app/ui/launchpad/`: `LaunchpadStatesTest`, `LaunchpadConfirmTest`,
  `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LaunchpadReFlyTest`, `LaunchpadScrubTest`.
  - `LaunchpadStatesTest` is the harness to copy for a new Robolectric states test: `@Config(sdk = [34])`,
    in-memory Room with `allowMainThreadQueries()`, `PreferenceDataStoreFactory.create { File(context.cacheDir,
    "settings.preferences_pb") }` (deleted in `tearDown`), `MissionManager` with
    `Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` and `zone = { ZoneOffset.UTC }`,
    `states = LaunchpadStates(db, manager)`.
  - Six test call sites construct `LaunchpadViewModel(...)`; **no test constructs `LaunchpadState.Draft`** (they
    all cast from `observe()`/`vm.state`), so a new `Draft` field is compile-safe.
  - `LaunchpadConfirmTest` (PR #39) records `queueGeneration` / `cancelGeneration` ids and has `primedDraft` /
    `awaitDraft` helpers; it uses no WorkManager.
- `app/src/test/java/com/liftoff/app/domain/`: `MissionsTest`, `ReFlyTest`, `RolloverTest`, `SortiePlanningTest`,
  `SortiesTest`, `WeekTest` — plain JUnit, no `@RunWith`, no Robolectric. That is the shape for the derivation test.
- Worker tests live under `app/src/test/java/com/liftoff/app/work/` (GenerationWorker, CoachTurnRunner,
  GenerationEnqueuer, GenerationCanceller) — this run changes nothing there, but they read `Generation` and the
  DAO helpers named above.

## Facts confirmed while writing the check

- `.github/workflows/ci.yml` runs exactly `./gradlew :app:assembleDebug :app:testDebugUnitTest` (ubuntu-latest,
  Temurin 17, 20-minute job). Nothing else — no lint, no detekt, no instrumentation run.
- `app/build.gradle.kts` sets `testOptions.unitTests.all { timeout = 5 minutes }` per test task, with the comment
  "A Robolectric + Room test can hang forever on the paused clock". That is the hang the brief warns about, and it is
  also what bounds every wait in the new Robolectric test whether or not the test says so.
- The gate at this branch's HEAD (`562e415`) is 639 tests, 0 failures, 0 errors, 0 skipped, and it finished on the
  first attempt in a few minutes.
- The known flake is `doneBodyweightSetStepperShowsBW` (`ui/inflight/InFlightScreenTest.kt:601`): a Room pool closed
  under a concurrent `observe()`, red at random and green on a re-run of the same tree.
- The only purity guard test in the repo is `coachPackageHasNoAndroidImports` in
  `app/src/test/java/com/liftoff/app/coach/ExerciseNamesTest.kt`, and it reads only
  `src/main/java/com/liftoff/app/coach` — nothing enforces `domain/`'s purity mechanically.
- `Generation`'s constructor: `id` defaults to 0, `createdAt` has **no** default, and `kind`, `missionId`, `status`
  are required, so a seeded row must pass `createdAt` itself.
- `exportedV1SchemaMatchesDatabase` is in `data/SchemaExportTest.kt` — the test that says the exported
  `app/schemas/.../1.json` still matches the entities.

## Facts confirmed while writing the tasks (HEAD `b15ec7d`)

- `MissionManager`'s four constructor parameters are all `private val`, so a configured-ness Flow has to be
  built inside the class from the private `settingsStore`. It already imports `coachIsConfigured`, `Settings`,
  `SettingsStore`, `MutableStateFlow` and `flow.first`; it does **not** import `map` or `distinctUntilChanged`
  today, so those imports are new. `currentWeekStart` (line 60) is the precedent for a public Flow here.
- `LaunchpadState.kt` as it stands: `Draft(weekStart: LocalDate, pattern: String, missionId: Long)`;
  `LaunchpadStates(db, manager)`; `observe()` is `manager.currentWeekStart.flatMapLatest { ws -> if (ws == null)
  flowOf(Loading) else missionDao().observeWeek(ws).combine(flightPlanDao().observeChanges()) { mws, _ ->
  derive(mws) } }.flowOn(Dispatchers.IO)`; `derive(missionWithSorties)` is `private suspend` and the
  `MissionStatus.DRAFT` arm (lines 93–97) is the only arm that needs the new join. `missionId` is only known
  inside `derive`, so the outline/settings join has to reach that arm — how is the implementer's call. The file
  already imports `kotlinx.coroutines.flow.*`, so `combine`, `flatMapLatest` and `distinctUntilChanged` need no
  new import.
- Nothing in `app/src` mentions `CoachStatus`, `coachConfigured` or `coachStatus` at HEAD: none of this run's
  work exists yet, and the tree is clean.
- `LaunchpadStatesTest` (the harness to copy) collects with `states.observe().first { it is LaunchpadState.Draft }`
  inside `runBlocking` — a one-shot read that stops at the first match. `LaunchpadConfirmTest` holds a
  subscription open through the view model's `stateIn(Lazily)` scope (its line 91 comment) and reads with
  `withTimeout(5_000L) { vm.state.first { it is LaunchpadState.Draft } }` (`primedDraft` / `awaitDraft` helpers).
  A test that must keep **one** subscription to `observe()` alive across three writes needs its own launched
  collector job plus bounded `withTimeout` waits — a chain of `.first()` calls re-subscribes each time, which is
  exactly what those cases are meant to rule out. The known Robolectric deadlock is a Room `Flow` read that never
  completes while the main looper is paused, so every wait in the new class has to be bounded.
- `gradlew.sh` sits at the repository root here, so `bash gradlew.sh …` from the toplevel is the right
  invocation (the `android-dev` skill's pinned toolchain is inside it). Test counts come from the XML
  (`tests=` / `failures=` / `errors=` / `skipped=` on each `TEST-*.xml`), never from `BUILD SUCCESSFUL`.

## verify.sh (committed by the verify step, then rewritten by review)

`c143ce1 run: verify` wrote it; `b15ec7d run: verify.sh rewritten by claude review` is the current
version and **dropped four names** — `aFailedOutlineRowWithNoReasonTextStillReportsFailedAndOffersRetryWithAReason`,
`aSucceededOutlineRowWithBlankSettingsIsNotConfiguredRatherThanEditable`,
`aFailedOutlineRowOutranksBlankSettingsAndKeepsItsReasonAndRetry`,
`aCancelledStatusIsTreatedAsNoOutlineRow`. Its header now says those four plan Decisions are reviewed by
Kyle in the pull request and get no test of their own in the check. The behaviour is still the plan's;
only the test names went. Re-read `verify.sh` itself rather than trusting this section.

One Gradle invocation: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, with stale
`app/build/test-results/testDebugUnitTest` moved aside first and up to three attempts when the failure is the known
flake, a build/daemon error, or a hung suite that wrote no reports. Every named test is then looked for by name in
that run's XML reports (tab-separated index of `TEST-*.xml`), so a test never written reads as "did not run".
Regression classes are checked whole-class (`c <ClassName>`), new tests only by method name — the file and package
are the implementer's call.

Twelve names, all new to this run (none exists at HEAD, so the script fails on the untouched branch). Task 1 of
`tasks.md` writes 1–7, task 3 writes 8–12; no other task writes a name this script looks for.

Plain JUnit, the derivation called directly over plain values:
1. `noOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone`
2. `aSucceededOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone`
3. `queuedAndRunningOutlineRowsArePlanningAndOfferContinueWithoutOutlineButNotConfirm`
4. `anOpenOutlineRowCarryingTheOfflineMarkIsOfflineRatherThanPlanning`
5. `aFailedOutlineRowReportsFailedWithItsReasonWordForWordAndOffersRetryAndContinueWithoutOutline`
6. `aBlankHostOrTokenWithNoOutlineRowIsNotConfiguredAndOffersContinueWithoutOutlineButNotConfirm`
7. `anOpenOutlineRowOutranksBlankSettingsAndIsPlanningOrOffline` (Decision: the offline mark outranks them too)

Robolectric, one subscription held open across the writes (a second `observe()` or a fresh `.first` proves nothing):
8. `theDraftReportsPlanningWhenAQueuedOutlineRowIsWrittenToRoom`
9. `theDraftReportsOfflineWhenItsOutlineRowTakesTheCoachOfflineMark`
10. `theDraftReportsFailedWithTheExactReasonItsOutlineRowWasMarkedFailedWith`
11. `theDraftDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow`
12. `theDraftFollowsTheSettingsFromNotConfiguredToEditableWithoutReSubscribing` (blank host/token → not configured;
    a weight-unit edit emits nothing; filling host and token turns the same subscription's draft editable)

Also checked: the exported schema under `app/schemas` byte-identical to the tree this run's brief was added on top
of, and `exportedV1SchemaMatchesDatabase`. Not checked (owner reviews in the PR): where the derivation lives, its
signature's purity, which flow carries configured-ness, test-class shape (no Robolectric / no WorkManager), copy
wording, and whether `LaunchpadScreen.kt` was edited — the brief allows a compile-only edit, so the six rendered
Launchpad classes are what say the drawing did not change.

## Machine notes (from memory, not the repo)

- Run the gate detached through `bash gradlew.sh`, `mkdir -p app/build` first, log + `EXIT=$?` sentinel with no
  pipe. The full gate can hang ~5 minutes once here; a hung run writes no `TEST-*.xml` — re-run rather than fix.
- Move (`mv`) `app/build/test-results` out of the way before a re-run, never delete it: a stranded test executor
  holds `binary/output.bin`. Do not `gradlew --stop` — it kills every worktree's daemon.
