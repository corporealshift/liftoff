# Plan: reopen into an in-flight sortie

## Approach

Two independent pieces, each anchored on a plain Kotlin function so a unit test can drive it without
Compose UI tests.

The design rule this implements is `DESIGN.md:217-218`, the last bullet of §6 ("In flight (logging at
the gym)"): "Every action is written to Room immediately. If the process is killed, the In-Flight
screen comes back exactly as it was left." (The brief calls §6 'Persistence'; the heading is as above,
and that bullet is the persistence rule.) Room already holds everything. What is missing is the app
finding its way back to the in-flight sortie, and the In-Flight screen noticing that its sortie has
gone.

**Opening — the start decision in plain Kotlin.** Add `startNav` to the existing companion object of
`ui/ShellNav.kt` (lines 30-44). Saved state wins; otherwise the highest in-flight id; otherwise the
Launchpad:

```kotlin
/** Where the shell starts: saved state wins, else the in-flight sortie, else the Launchpad. */
fun startNav(saved: ShellNav?, inFlightIds: List<Long>): ShellNav {
    if (saved != null) return saved
    val id = inFlightIds.maxOrNull() ?: return ShellNav()
    return ShellNav().openInFlight(id)
}
```

`ui/LiftoffShell.kt:58-63` already keeps nav in `rememberSaveable(stateSaver = …) { mutableStateOf(ShellNav()) }`.
That init lambda *is* the cold-start hook: Compose runs it only when there is no restorable value,
which is exactly the brief's "no saved `ShellNav`". Declare a plain `remember { mutableStateOf(false) }`
`coldStart` flag above the nav `rememberSaveable` and set it to true from inside that init lambda. It
must not itself be saveable: on `recreate()` the composition is new, so the flag re-initialises to
false and the nav init lambda does not run — which is what "nothing new happens" on a restore means.

On a cold start, a `LaunchedEffect(Unit)` calls `sortieDao().getInFlight()` and applies `startNav(null, ids)`.
Room's suspend DAO already runs the query on its own dispatcher, so it is off the main thread without
an explicit `withContext`. The Launchpad is composed first and stays there while the read runs.

"If the user has already navigated, leave them where they are" is a second plain `remember`ed
`navigated` flag that every user-driven nav assignment sets: the `BackHandler` (67-69), Mission
Control's `onBack` (91), In-Flight's `onBack` (98), the sliders button (113), `onOpenInFlight` (132),
and the three bottom-bar tabs (148-168). When the read returns, the decision is applied only if that
flag is still false. Comparing `nav` against `ShellNav()` instead would be wrong: a user who tapped to
Mission and back to the Launchpad is back on the default value, and jumping them into In-Flight at
that moment is the exact surprise the brief is ruling out.

Nothing else changes. `ShellNav.back()` already returns `copy(tab = Launchpad, inFlightSortieId = null)`
(line 20), and the Launchpad already offers Resume (`LaunchpadScreen.kt:294`, `DESIGN.md:567`), so
"back from In-Flight still goes to the Launchpad, which still offers Resume" needs no work.

**Leaving — one gate in the view model.** `InFlightStates.observe()` ends in `mapNotNull`
(`InFlightStates.kt:25-28`), which drops a null sortie, and `deriveInFlight` (`InFlightState.kt:99-147`)
never looks at `sortie.state` at all. So a landed, scrubbed or deleted sortie still produces a full
`InFlightState` and the screen cannot learn from that flow that its sortie is gone. Do not add a new
`InFlightState` variant for it: every `when` over the sealed state is exhaustive — `InFlightScreen.kt:68`
and the scrub sortie-number `when` at 213-218 — so a new variant has to be handled at each one, and it
still would not give a single "leave once" point. Watch `SortieDao.observeById(sortieId)`
(`SortieDao.kt:44-45`, already exists) directly instead:

```kotlin
/** Plain signal: the sortie this screen is showing is gone or no longer in flight. */
fun sortieIsNoLongerInFlight(sortie: Sortie?): Boolean =
    sortie == null || sortie.state != SortieState.IN_FLIGHT
```

top-level in `ui/inflight/InFlightViewModel.kt`, plus in the class:

- `private var left = false` and `private fun leaveOnce(onLeave: () -> Unit)` — calls `onLeave` the
  first time only.
- `fun watchLeave(onLeave: () -> Unit)` — `scope.launch` collecting
  `sortieDao.observeById(sortieId).map(::sortieIsNoLongerInFlight)` and calling `leaveOnce(onLeave)` on
  the first `true`.
- `land` (58-74), `landRun` (85-112) and `scrub` (115-131) call `leaveOnce(onDone)` instead of `onDone()`.
  A Land on this screen now fires both the success callback and the DB observer, and `onBack` still runs
  exactly once.
- Once `left` is set, the action methods (`check`, `uncheck`, `saveEdit`, `skipSet`, `reopenSet`,
  `skipExercise`, `unskipExercise`, `addSet`, `saveExerciseNote`, `saveSortieNote`, `land`, `landRun`,
  `scrub`) return without writing: the screen never acts on a sortie that is no longer in flight.

`ui/inflight/InFlightScreen.kt` starts the watch next to the view model it already remembers
(lines 48-51): `LaunchedEffect(vm) { vm.watchLeave(onBack) }`. The existing `onBack` call sites
(69 `LoadingContent`, 80, 121, 185, 202, 224) stay as they are — the once-guard is in the view model.

This fits the codebase because `InFlightViewModel` is already a plain holder driven by an injected
`CoroutineScope` and an injected `MissionManager` (`InFlightViewModel.kt:16-24`), and the existing
Robolectric tests already build it against in-memory Room on a fixed clock.

**Rollover needs no ordering.** `MainActivity.kt:26-30` runs `MissionManager.onAppOpen()` inside
`repeatOnLifecycle(STARTED)`, which is where `domain/Rollover.kt:13-29` scrubs a past week's unfinished
sorties — including an IN_FLIGHT one — with reason `WEEK_ENDED` = "week ended" (line 9). On a cold start
after the week ended, the shell's read may open In-Flight a moment before that scrub lands; the leave
signal then returns to the Launchpad. The two hooks stay independent.

## Files

| File | Change |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/ShellNav.kt` | `startNav` in the existing companion object. |
| `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` | `coldStart` + `navigated` flags, the cold-start read, `startNav` applied; `navigated` set at the six nav-assignment sites listed above. Read the container with a safe cast (`as? LiftoffApplication`) so the `@Preview` at line 207 cannot crash the new effect. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` | `sortieIsNoLongerInFlight`, `left`/`leaveOnce`, `watchLeave`, the no-write guard after leaving. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | `LaunchedEffect(vm) { vm.watchLeave(onBack) }`. |
| `app/src/main/java/com/liftoff/app/data/SortieDao.kt` | `@Query("DELETE FROM sortie WHERE id = :id") suspend fun deleteById(id: Long)` — only the "no longer exists" test calls it. See Risks for why a DAO delete rather than raw SQL. A `@Query` is not a schema change, so `SchemaExportTest` (which reads `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json`) stays green. |
| `app/src/test/java/com/liftoff/app/ui/ShellNavTest.kt` | `startNav` cases. Plain JUnit, as that file already is. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLeaveTest.kt` (new) | Robolectric leave tests. |
| `ARCHITECTURE.md` | the `ui` row (line 37) and the M4 row (line 83). Exact text below. |

No Room schema change, no new entity, no network, no `ui/theme` change, no new `InFlightState` variant.

## Order

Each step compiles and its own tests pass before the next, and is committed on its own
(`area: lowercase summary`, named files staged only):

1. `ui: add the cold-start start decision to shell nav` — `startNav` + `ShellNavTest` cases.
   Check: `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.ui.ShellNavTest"`.
2. `ui: reopen into the in-flight sortie on a cold start` — `LiftoffShell` wiring.
   Check: the same with `--tests "com.liftoff.app.ui.LiftoffShellTest"`.
3. `inflight: leave the screen when its sortie is no longer in flight` — `SortieDao.deleteById`,
   the predicate, the view-model gate, the screen effect, `InFlightLeaveTest`.
   Check: `InFlightLeaveTest`, then `InFlightScreenTest` and `InFlightLandScrubTest`.
4. `docs: mark m4 done and in-flight a real screen` — the two `ARCHITECTURE.md` rows.
5. Full gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. It can outlast a shell
   command's limit on this machine: `bash gradlew.sh --stop`, `mkdir -p app/build`, then
   `nohup bash -c 'bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache > app/build/gate.log 2>&1; echo EXIT=$? >> app/build/gate.log' &`
   and wait on the `EXIT=` line; read the totals out of
   `app/build/test-results/testDebugUnitTest/TEST-*.xml`. One run is the evidence; do not re-run it.

## Testing

**`ShellNavTest` (plain JUnit, no Compose)** — the three cases the brief asks for, plus the tie-break:

- `startNav(null, listOf(7L))` → `inFlightSortieId == 7`, tab Launchpad.
- `startNav(null, listOf(3L, 9L, 6L))` → `9` (highest id).
- `startNav(null, emptyList())` → `ShellNav()`.
- `startNav(saved, listOf(7L))` → `saved`, for a saved Mission tab, a saved Mission Control, and a
  saved `ShellNav(tab = Launchpad, inFlightSortieId = 4L)`.

**`InFlightLeaveTest` (new, Robolectric)** — fixture copied from `InFlightLandScrubTest.kt:41-146`:
in-memory Room with `allowMainThreadQueries()`, a `PreferenceDataStoreFactory` settings store, a
`MissionManager` on `Clock.fixed(2026-01-12T00:00:00Z, UTC)` with `zone = { ZoneOffset.UTC }`, a scope of
`Dispatchers.Unconfined + Job() + CoroutineExceptionHandler`, `seedMission()` (onAppOpen + confirm on
RLRLR), `landSortie0`, `seedSortie1LiftPlan`, `manager.launch(...)` to get an IN_FLIGHT lift sortie, and
`InFlightViewModel(db, settingsStore, sortieId, scope, manager)`.

The counter is a `java.util.concurrent.atomic.AtomicInteger`, because Room delivers a Flow emission on
its own thread and the test thread only polls it: `vm.watchLeave { count.incrementAndGet() }`.

- **Rollover scrubs it**: rebuild the manager on a clock past the week, the `advanceClock` shape at
  `MissionManagerTest.kt:60-68` (+7 days), and call `onAppOpen()`. Assert the sortie is `SCRUBBED` with
  reason "week ended" and that `count` reaches exactly 1.
- **Landed**: `manager.land(sortieId)` → `count` reaches exactly 1.
- **No longer exists**: `sortieDao.deleteById(sortieId)` → `count` reaches exactly 1.
- **Still in flight**: `vm.check(setId)` and `vm.saveSortieNote("left shoulder twinge")`, then a bounded
  wait → `count` is 0, and the state flow still shows `Ready`.
- **Land on this screen leaves once**: `vm.watchLeave { count.incrementAndGet() }` and
  `vm.land { count.incrementAndGet() }` → `count` is exactly 1, not 2. Same for `vm.scrub(null) { … }`.
- **After leaving, no further action**: once the signal has fired, `vm.check(setId)` leaves the set
  `OPEN` in the database.

Every wait is `withTimeout(5_000)` polling the counter, the `awaitReady` shape at
`InFlightLandScrubTest.kt:148`; after reaching 1, wait a short bounded time and assert it is still 1.
`job.cancel()` before `db.close()` in `tearDown`.

**Must stay green** (read each class's own `TEST-<fully.qualified>.xml` header rather than a
package-scoped grep): `ShellNavTest`, `LiftoffShellTest`, `InFlightScreenTest`, `InFlightRunTest`,
`InFlightLandScrubTest`, `InFlightStatesTest`, `InFlightViewModelTest`, `LandScrubDialogTest`,
`NoteDialogTest`, `RunLandingTest`, the four `ui/launchpad` tests, `MissionManagerTest`, `SortieDaoTest`,
`SchemaExportTest`, and the coach tests.

## Risks

- **The brief names `MainActivityTest`, which does not exist and never did.** The shell's
  activity-level tests are `LiftoffShellTest`, which uses `createAndroidComposeRule<MainActivity>()`
  (line 26) and calls `onAppOpen()` in its `@Before` (42-50). Nothing else in the repo matches that
  name, so "must not break `MainActivityTest`" is satisfied by `LiftoffShellTest` staying green.
- **`SortieDao` has no delete today** — the only deletes in `app/src/main` are
  `FlightPlanDao.kt:21` (`DELETE FROM flightPlan WHERE sortieId`) and `GenerationDao.kt:17` (`@Delete`).
  The "no longer exists" case needs one. A `@Query` delete is chosen because Room's generated DAO code
  always notifies the invalidation tracker, so `observeById` is guaranteed to re-emit. Deleting with raw
  SQL (`db.openHelper.writableDatabase.execSQL(...)`) or `db.clearAllTables()` needs no production
  change, but whether they notify the tracker is not documented and would have to be checked by running
  the test; if the work step prefers no DAO change, try `clearAllTables()` first and switch to the DAO
  method if the flow never re-emits. A test that silently never fires is worse than one extra DAO line.
- **`LiftoffShellTest` runs the real `MainActivity` against the app's own database.** It never launches a
  sortie, so `getInFlight()` finds nothing and `appOpensOnLaunchpad` still holds. If a test in that class
  ever leaves an IN_FLIGHT sortie in that database, the shell now opens In-Flight on start — that is the
  new behaviour, not a bug, but it changes what such a test sees.
- **`selectedTabSurvivesRecreation` and `missionControlSurvivesRecreation`**
  (`LiftoffShellTest.kt:92, 106`) exercise the saved-state path. The `coldStart` flag must be a plain
  `remember`, not a `rememberSaveable`, or `recreate()` would restore `true` and fire the read a second
  time.
- **Cold-start detection relies on `rememberSaveable` running its init lambda only when nothing was
  restored.** That is documented Compose behaviour and is the brief's own definition of a cold start.
- **Room flows emit off the main thread.** The leave tests poll an `AtomicInteger` under a timeout; they
  must not assume a synchronous emission, and a plain `var Int` incremented from Room's thread is a
  visibility flake waiting to happen.
- **The watcher adds a second live Room flow per In-Flight screen.** A screen test that writes through
  Room must unmount the screen before `tearDown` closes the database, or the live `observe()` fails with
  "connection pool has been closed". `InFlightScreenTest` already follows that shape, and one such
  failure on a re-run of the same tree is the known flake (`doneBodyweightSetStepperShowsBW`) — re-run
  before treating it as broken.
- `InFlightScreenTest` seeds every sortie as `SortieState.IN_FLIGHT`, so the new watcher does not fire
  spuriously there; its landing test (368-398) asserts `onBack` was called exactly once, which the
  once-guard keeps true.

## Decisions

- **The user navigates while the cold-start read is running.** Chosen: any navigation counts, so they
  stay where they are — including a user who tapped to Mission and back to the Launchpad. Alternative:
  open In-Flight unless they are visibly somewhere else at the moment the read returns. Why: a screen
  that jumps under someone's finger feels broken, and Resume is still on the Launchpad.
- **Mission Control is open when the read returns.** Chosen: that counts as navigating, so Mission
  Control stays open. Alternative: In-Flight opens over it. Why: same reason — the app should not move
  the user out of a screen they opened.
- **Leaving because the sortie ended elsewhere (rollover, landed elsewhere, deleted).** Chosen: return to
  the Launchpad quietly, with no message. Alternative: show a notice such as "this sortie was scrubbed".
  Why: the brief says only "returns to the Launchpad", and the Launchpad already shows the current
  sortie and its state (`DESIGN.md:563-568`).
- **A cold start after the week has ended: the read may open In-Flight just before `onAppOpen` scrubs
  that sortie.** Chosen: let it open, then leave on the signal; the cold-start read does not wait for the
  rollover. Alternative: the shell waits for `onAppOpen()` to finish before reading. Why: reopening should
  be immediate, and the brief lists the week-ended rollover under "Leaving", which presumes the screen
  can be open when the rollover happens.
- **Saved state points at a sortie that has landed while a different sortie is IN_FLIGHT.** Chosen: leave
  to the Launchpad, which offers Resume for the other sortie. Alternative: jump straight to the in-flight
  sortie. Why: saved state wins on a restore and the brief says nothing new happens there, and the
  Launchpad is the one place the app offers Resume.
- **ARCHITECTURE.md's M2 row still says "In-Flight is a placeholder until M4".** Chosen: leave that row
  alone and change only the two rows the brief names. Alternative: update M2's status cell too. Why: the
  runner's checks grep these rows by their delimiters, the brief asks for exactly two, and M2's clause
  describes what M2 shipped rather than what the app does now.

The brief settles everything else it touches: which sortie to open when several are in flight (highest
id), that the Launchpad shows first while the read runs, that saved state wins, and that back from
In-Flight goes to the Launchpad.

### ARCHITECTURE.md rows, as they will be written

Both stay single-line table rows in the same columns, and the M4 status cell uses `;`-separated clauses
with no em dash inside it, because the runner's checks split that cell on `;` and on an em dash:

```
| `com.liftoff.app.ui` | Navigation shell, Launchpad, Mission and In-Flight as real screens, Landed as a placeholder, Mission Control stub, theme and shared composables. | ✅ (navigation shell with cold-start reopen into the in-flight sortie, Launchpad, Mission, In-Flight with Land, Scrub and leaving when the sortie is gone, Landed, Mission Control stub, `ui/theme`) |
```

```
| M4 | In flight — In-Flight screen, Launch, Land, resume after process kill | Done; the In-Flight screen is a real screen; the shell reopens into the in-flight sortie on a cold start; the screen leaves when its sortie is no longer in flight |
```
