# Tasks — Reopen into an in-flight sortie

Read `.rho/runs/reopen-into-an-in-flight-sortie-9c1v/brief.md` and
`.rho/runs/reopen-into-an-in-flight-sortie-9c1v/plan.md` before your task. The plan's "Files", "Order",
"Testing", "Risks" and "Decisions" sections are the authority; its line numbers are from the commit this
run started at, so re-read the file you are about to edit before editing it.

Five commits, in this order. Task 2 builds on task 1; task 4 builds on task 3; task 5 is the last commit of
the run and carries the run's own verify.sh run. Tasks 3 and 4 are independent of 1 and 2.

Rules that bind every task: `bash gradlew.sh …` (never `./gradlew`); commit message
`area: lowercase summary`, staging named files only; LF endings; the CLAUDE.md §2 vocabulary (a sortie is
never a 'session'); no Room schema change and no new entity; no network; every wait in a test carries a
timeout; **no Compose UI test is required or wanted anywhere in this run** — the brief says so and
verify.sh names no test that composes a screen. Do not touch
`.rho/runs/reopen-into-an-in-flight-sortie-9c1v/verify.sh`. Do not push.

**The 12 test names verify.sh requires this run to write** (it matches them by method name across every
`app/build/test-results/testDebugUnitTest/TEST-*.xml`, so the class and package are yours to choose; the
name must be exactly as spelled):

- Task 1 writes the four opening names: `coldStartOpensTheInFlightSortie`,
  `coldStartWithNoneInFlightStaysOnLaunchpad`, `coldStartWithSeveralInFlightOpensTheHighestId`,
  `savedStateWinsOverTheInFlightSortie`.
- Task 3 writes six of the eight leaving names: `rolloverScrubLeavesTheInFlightScreenOnce`,
  `landElsewhereLeavesTheInFlightScreenOnce`, `deletedSortieLeavesTheInFlightScreenOnce`,
  `alreadyLandedSortieLeavesOnceWhenTheWatchStarts`, `landOnThisScreenLeavesOnlyOnce`,
  `scrubOnThisScreenLeavesOnlyOnce`.
- Task 4 writes the other two: `noLeaveWhileTheSortieIsStillInFlight`,
  `afterLeavingTheScreenWritesNothing`.

Facts that bite, shared by the tasks:

- Baseline: the suite is 478 tests, 0 failures / 0 errors / 0 skipped, green at this run's base commit.
  Iterate with `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` (~35 s) and one
  class at a time with `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests "<fully.qualified.ClassName>"`;
  never a package wildcard or the whole suite in a foreground shell call. The full cold gate takes a few
  minutes and must be run detached (task 5 spells the recipe).
- `SortieDao` (`app/src/main/java/com/liftoff/app/data/SortieDao.kt`) has `getInFlight()` and
  `observeById(sortieId): Flow<Sortie?>` and **no delete of any kind**.
- `InFlightStates.observe()` ends in `mapNotNull`, and `deriveInFlight` never reads `sortie.state`, so the
  state flow cannot tell the screen its sortie is gone. Do **not** add a new `InFlightState` variant: every
  `when` over the sealed state in `InFlightScreen.kt` is exhaustive with no `else` arm, so a new variant is
  a compile error at each site.
- Room delivers a Flow emission on its own thread. Count a leave callback in a
  `java.util.concurrent.atomic.AtomicInteger`, guard the once-only leave with an `AtomicBoolean`, and poll
  with `withTimeout(5_000) { … }` / `delay(25)` rather than assuming a synchronous emission. A plain
  `var Int` incremented from Room's query thread is a visibility flake.
- View-model test fixture (copy it from `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt`,
  which already has all of it): in-memory Room with `allowMainThreadQueries()`,
  `PreferenceDataStoreFactory.create { File(context.cacheDir, "settings.preferences_pb") }` →
  `SettingsStore`, `MissionManager(db, settingsStore, clock = Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"),
  ZoneOffset.UTC), zone = { ZoneOffset.UTC })`, a `CoroutineScope(Dispatchers.Unconfined + job +
  CoroutineExceptionHandler { _, e -> escaped += e })`, and `@After` that does `job.cancel()` **before**
  `db.close()` (a still-collecting Room flow fails the next test with "connection pool has been closed").
  A land/scrub needs an ACTIVE Mission: `manager.onAppOpen()` then `manager.confirm(mission.id)` —
  `onAppOpen()` alone leaves it DRAFT and the write is rejected with "Couldn't land this sortie."
- `doneBodyweightSetStepperShowsBW` fails every few runs with Room's closed connection pool and passes on a
  re-run of the same tree: re-run the gate once before treating it as broken, and do not "fix" it.

- [x] Put the start decision in `ShellNav.startNav`, and test it
  Covers: brief part 1's plain-Kotlin core. `app/src/main/java/com/liftoff/app/ui/ShellNav.kt`: add
  `fun startNav(saved: ShellNav?, inFlightIds: List<Long>): ShellNav` to the **existing** companion object
  (next to `decode`), exactly as the plan spells it — `saved` wins; else `inFlightIds.maxOrNull()` opened
  with `openInFlight(id)`; else `ShellNav()`. `openInFlight` is `copy(inFlightSortieId = id)`, so the tab
  stays `Tab.Launchpad` — that is what the opening tests assert. No other file changes; nothing calls it
  yet (task 2 wires it).
  Tests this task writes, appended to `app/src/test/java/com/liftoff/app/ui/ShellNavTest.kt` in that file's
  existing plain-JUnit style (no Robolectric, no Compose, no database), each named exactly as verify.sh
  spells it:
  - `coldStartOpensTheInFlightSortie` — `startNav(null, listOf(7L))` has `inFlightSortieId == 7L` and
    `tab == Tab.Launchpad`.
  - `coldStartWithNoneInFlightStaysOnLaunchpad` — `startNav(null, emptyList())` equals `ShellNav()`:
    `tab == Tab.Launchpad` and `inFlightSortieId == null`.
  - `coldStartWithSeveralInFlightOpensTheHighestId` — `startNav(null, listOf(3L, 9L, 6L))` gives `9L`, not
    the first in the list and not the lowest.
  - `savedStateWinsOverTheInFlightSortie` — for each of `ShellNav(tab = Tab.Mission)`,
    `ShellNav(missionControlOpen = true)`, and `ShellNav(tab = Tab.Launchpad, inFlightSortieId = 4L)`:
    `startNav(saved, listOf(7L)) == saved`, i.e. the saved state comes back unchanged whatever the read
    found (this is the plan's Decision "saved state points at a landed sortie while another is IN_FLIGHT").
  Done when `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.ShellNavTest"`
  succeeds and that class's `TEST-com.liftoff.app.ui.ShellNavTest.xml` reports 0 failures / 0 errors /
  0 skipped with all four names present as passing `<testcase>` entries **and** that class's existing
  `backFromInFlightClearsTheSortieId`, `backFromInFlightWhileOnMissionGoesToLaunchpad`,
  `selectFromInFlightGoesToLaunchpadAndClearsInFlight`, `stateSurvivesSaveAndRestore` and
  `unreadableSavedStateRestoresToLaunchpad` (verify.sh lists them under "must not break") still pass. Commit
  `ui: add the cold-start start decision to shell nav`.

- [x] Reopen into the in-flight sortie on a cold start, from the shell
  Covers: brief part 1's wiring, `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` only. Read the plan's
  "Opening" paragraphs and its Risks first; they settle every choice here.
  - A plain `remember { mutableStateOf(false) }` `coldStart` flag declared **above** the nav
    `rememberSaveable`, set to `true` from inside that `rememberSaveable`'s init lambda (Compose runs that
    lambda only when nothing was restorable, which is the brief's definition of a cold start). It must not
    be `rememberSaveable`: on `recreate()` it re-initialises to false, so the read does not fire a second
    time — that is what keeps `selectedTabSurvivesRecreation` and `missionControlSurvivesRecreation` doing
    what they say.
  - A plain `remember { mutableStateOf(false) }` `navigated` flag, set to `true` at **every** user-driven
    nav assignment: the `BackHandler` body, Mission Control's `onBack`, In-Flight's `onBack`, the sliders
    button's `onClick`, `LaunchpadScreen`'s `onOpenInFlight`, and the three bottom-bar `NavigationBarItem`
    `onClick`s. The cold-start assignment itself must not set it. Comparing `nav` against `ShellNav()`
    instead is wrong: a user who tapped to Mission and back is on the default value and must not be jumped.
  - `LaunchedEffect(coldStart)` (keyed on `coldStart` only, never on `nav`) that, when `coldStart` is true,
    waits for this run's rollover with `container.missionManager.currentWeekStart.filterNotNull().first()`
    (the same gate `LaunchpadState.kt` and `MissionState.kt` use), then
    `container.database.sortieDao().getInFlight()` — a suspend DAO, so it is already off the main thread —
    and applies `nav = startNav(null, ids.map { it.id })` **only if `navigated` is still false**. Read the
    container with a safe cast (`(LocalContext.current.applicationContext as? LiftoffApplication)?.container`)
    and skip the effect when it is null, so the `@Preview` at the end of the file cannot crash it.
  The Launchpad is composed first and stays there while the wait and the read run; `ShellNav.back()` and the
  Launchpad's Resume already do the rest of brief part 1, so nothing else changes.
  No new test name: verify.sh names none for the shell, and the brief rules out Compose UI tests. Do not add
  one — a Compose test that composes the shell while an IN_FLIGHT sortie exists in the app's own database
  would now open In-Flight, which is the new behaviour but is not what any existing test expects.
  Done when `bash gradlew.sh :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` succeeds and
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.LiftoffShellTest"`
  passes with 0 failures / 0 errors / 0 skipped in that class's XML — including `appOpensOnLaunchpad`,
  `selectedTabSurvivesRecreation`, `missionControlSurvivesRecreation`, `systemBackFromATabGoesToLaunchpad`
  and `bottomBarReachesEveryTab` — and
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache`
  with `--tests "com.liftoff.app.MainActivityTest" --tests "com.liftoff.app.ui.ShellNavTest"`
  still passes (`drawsEdgeToEdgeWithDarkSystemBarIcons` lives in `MainActivityTest`, and verify.sh lists it
  under "must not break"). Commit `ui: reopen into the in-flight sortie on a cold start`.

- [x] Leave the In-Flight screen once its sortie is no longer in flight
  Covers: brief part 2's signal, watcher and once-only gate, in one commit because the tests need the whole
  chain. Read the plan's "Leaving" and "Rollover" paragraphs first.
  - `app/src/main/java/com/liftoff/app/data/SortieDao.kt`: add
    `@Query("DELETE FROM sortie WHERE id = :id") suspend fun deleteById(id: Long)`. A `@Query` delete always
    notifies Room's invalidation tracker, so `observeById` is guaranteed to re-emit; a `@Query` is not a
    schema change, so `SchemaExportTest` stays green, and the flight plan, exercises and sets cascade.
  - `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`: top-level
    `fun sortieIsNoLongerInFlight(sortie: Sortie?): Boolean = sortie == null || sortie.state != SortieState.IN_FLIGHT`;
    `private val left = java.util.concurrent.atomic.AtomicBoolean(false)` and
    `private fun leaveOnce(onLeave: () -> Unit) { if (left.compareAndSet(false, true)) onLeave() }`;
    `fun watchLeave(onLeave: () -> Unit)` that `scope.launch`es a collect of
    `sortieDao.observeById(sortieId).map(::sortieIsNoLongerInFlight)` and calls `leaveOnce(onLeave)` on the
    first `true`; and `land`, `landRun` and `scrub` call `leaveOnce(onDone)` where they now call `onDone()`,
    so a Land on this screen leaves once and not twice. That file imports `data` classes one at a time, so
    `Sortie`, `SortieState` and `kotlinx.coroutines.flow.map` need adding there.
  - `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`: beside the view model it already
    remembers, `val currentOnBack by rememberUpdatedState(onBack)` and
    `LaunchedEffect(vm) { vm.watchLeave { currentOnBack() } }` — no new import, that file already has
    `androidx.compose.runtime.*`. The existing `onBack` call sites stay as they
    are; the once-guard is in the view model.
  Tests this task writes, all in a new `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLeaveTest.kt`
  (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`, the `InFlightLandScrubTest` fixture
  copied whole — `seedMission` → `confirm` on RLRLR, `landSortie0`, `seedSortie1LiftPlan`,
  `manager.launch(...)` giving an IN_FLIGHT lift sortie, `InFlightViewModel(db, settingsStore, sortieId,
  scope, manager)` — and `vm.watchLeave { count.incrementAndGet() }` on an `AtomicInteger`; every wait
  `withTimeout(5_000)`, and after the count reaches 1 wait a short bounded time and assert it is **still** 1,
  which is what "once, not twice" means):
  - `rolloverScrubLeavesTheInFlightScreenOnce` — rebuild the manager on a clock 7 days later (the
    `advanceClock` shape in `MissionManagerTest.kt`) and call `onAppOpen()`; assert the sortie is
    `SortieState.SCRUBBED` with `scrubReason == WEEK_ENDED` (`domain/Rollover.kt`) and the count reaches 1.
  - `landElsewhereLeavesTheInFlightScreenOnce` — `manager.land(sortieId)` (not through the view model) →
    count 1, sortie `LANDED`.
  - `deletedSortieLeavesTheInFlightScreenOnce` — `sortieDao.deleteById(sortieId)` → count 1.
  - `alreadyLandedSortieLeavesOnceWhenTheWatchStarts` — land the sortie **before** the watch starts (build
    the view model and call `watchLeave` only after `manager.land(sortieId)` has committed), then assert the
    count reaches exactly 1: the first emission `observeById` produces is already the LANDED row. Without
    this case a watcher that only reacts to a change after it starts would pass the three above and leave a
    restored screen stuck on a landed sortie.
  - `landOnThisScreenLeavesOnlyOnce` — `vm.watchLeave { count.incrementAndGet() }` and then
    `vm.land { count.incrementAndGet() }`: the count is exactly 1, not 2, and the sortie is `LANDED`.
  - `scrubOnThisScreenLeavesOnlyOnce` — the same on a fresh fixture with `vm.scrub("sore knee") { … }`:
    count 1, sortie `SCRUBBED`.
  Done when all six names read `pass` in this run's XML reports and `InFlightLeaveTest` reports 0 failures /
  0 errors / 0 skipped, and running the same cold `--no-build-cache` command over
  `com.liftoff.app.ui.inflight.InFlightScreenTest`, `InFlightLandScrubTest`, `InFlightRunTest`,
  `InFlightStatesTest`, `InFlightViewModelTest`, `LandScrubDialogTest` and `NoteDialogTest` leaves them all
  green — `noChecklistLandLandsAndCallsOnBack` and `landWithEverySetChecked` in particular, whose `onBack`
  may now come from the watcher but is still called exactly once. Commit
  `inflight: leave the screen when its sortie is no longer in flight`.

- [ ] Stop the screen acting on a sortie it has already left
  Covers: brief part 2's "it must not try to act on that sortie", on top of task 3.
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` only: once `left.get()` is true, every
  action method returns without writing — `check`, `uncheck`, `saveEdit`, `skipSet`, `reopenSet`,
  `skipExercise`, `unskipExercise`, `addSet`, `saveExerciseNote`, `saveSortieNote`, `land` and `scrub` return
  early, and `landRun` returns `false` (so its dialog stays as it is until the screen goes). The guard goes
  before the `busy` check, and `watchLeave` / `leaveOnce` / `state` are unchanged.
  Tests this task adds to `InFlightLeaveTest.kt` (same fixture, same `AtomicInteger` counter, same bounded
  waits), named exactly as verify.sh spells them:
  - `noLeaveWhileTheSortieIsStillInFlight` — the control for both the watcher and the guard: with the sortie
    still IN_FLIGHT, `vm.check(setId)` (take a `setId` from the `Ready` state's `cards`/`currentSetId`) and
    `vm.saveSortieNote("left shoulder twinge")` (a sortie-row-only write, so `observeById` does re-emit);
    after a bounded wait the count is still 0, the state flow still shows `InFlightState.Ready`, and the write
    landed — the set is `SetStatus.DONE` and the note is on the sortie row, read back through
    `flightPlanDao.getPlan(sortieId)` / `sortieDao.get(sortieId)`.
  - `afterLeavingTheScreenWritesNothing` — leave via the rollover scrub (it touches only the sortie row, so
    the sets stay `OPEN`; a Land would mark them not done and hide the check), wait until the count is 1,
    then `vm.check(setId)` and a bounded wait: that set is still `SetStatus.OPEN` in the database, i.e. the
    write never happened.
  Done when both names read `pass` in this run's XML reports, `InFlightLeaveTest` reports 0 failures /
  0 errors / 0 skipped, and the cold `--no-build-cache` run over `com.liftoff.app.ui.inflight.InFlightViewModelTest`
  and `InFlightScreenTest` (which checks and unchecks sets on a live screen) stays green. Commit
  `inflight: stop acting on a sortie the screen has left`.

- [ ] Mark M4 done and In-Flight a real screen, then run the full gate
  Covers: brief part 3 and the run's evidence. `ARCHITECTURE.md` only, the two rows the brief names, written
  as single-line table rows in the same columns, exactly as the plan's "ARCHITECTURE.md rows" section spells
  them: the `com.liftoff.app.ui` area-map row (currently "…Launchpad and Mission as real screens, In-Flight
  as a placeholder screen…") and the M4 milestone row (currently `| Not started |`). Keep the M4 status cell
  `;`-separated with no em dash inside it, as the plan spells it. Leave the M2 row's "In-Flight is a
  placeholder until M4" alone — the plan's Decisions say so.
  Check the two rows the way verify.sh does: the line starting `| \`com.liftoff.app.ui\` |` contains
  `in-flight` (case-insensitive) and the whole word `real`, and does **not** contain
  `in-flight placeholder` / `in-flight as a placeholder` / `in-flight is a placeholder` /
  `in-flight still a placeholder` / `in-flight remains a placeholder`; and
  `awk -F'|' '/^\| M4 \|/ { print $4 }' ARCHITECTURE.md` contains `done` (case-insensitive) and none of
  `not started`, `not done`, `partial`.
  Then run the run's own definition of done once, detached, and read its exit status — verify.sh runs the CI
  gate itself, so one run is the gate and every named check at once (it deletes
  `app/build/test-results/testDebugUnitTest` first, so nothing from an earlier scoped run counts).
  `bash gradlew.sh --stop`;
  `mkdir -p app/build` (a fresh worktree has none, and a detached redirect into it silently produces no
  log); then one line, exactly:
  `nohup bash -c 'bash .rho/runs/reopen-into-an-in-flight-sortie-9c1v/verify.sh > app/build/verify-run.log 2>&1; echo EXIT=$? >> app/build/verify-run.log' &`
  and wait for the `EXIT=` line rather than re-running it. Expect `EXIT=0` and `BRIEF DONE.` with 55 `✓`
  lines (the 12 new names, the 42 "must not break" names, the 2 ARCHITECTURE.md checks and the gate), the
  gate line showing `:app:testDebugUnitTest` executed rather than `FROM-CACHE`/`UP-TO-DATE`, and 0 failures /
  0 errors / 0 skipped in the XML totals — grown from 478 by the twelve new tests. Fix and commit separately
  anything this run breaks; the whole-suite run is also the evidence for verify.sh's 42 "must not break"
  names. `doneBodyweightSetStepperShowsBW` is the known flake: re-run once before treating it as broken. Put
  the XML totals, the `EXIT=` value and the `passed/failed` counts in the commit body — the runner only reads
  repo artifacts.
  Done when both ARCHITECTURE.md checks above read green and the detached verify.sh exited 0 with all twelve
  new names present as passing `<testcase>` entries. Commit
  `docs: mark m4 done and in-flight a real screen`.
