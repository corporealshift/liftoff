# Plan: Mission and Sortie Domain Logic (M2)

## Approach

The work has two layers, built on the existing entities, DAOs and enums in `com.liftoff.app.data`. There is no schema change.

1. **Pure Kotlin rules in `com.liftoff.app.domain`.** These are plain functions over the data classes `Mission` and `Sortie`, the enums in `Enums.kt`, and `FlightPlanDraft`. No file in `domain/` may import `android.*` or `androidx.*` (ARCHITECTURE.md invariant 2). Every rule in the brief lives here and is unit-tested on the JVM: the week start, the pattern, the Mission lifecycle, sortie transitions, next-sortie selection, rollover, and how a sortie that becomes current gets prepared.
2. **A Room-backed `MissionManager` in `com.liftoff.app.data`.** It loads rows, calls the domain rules, and writes the results. Each public operation runs inside one `db.withTransaction { … }` (room-ktx is already a dependency). `@Transaction` only works on DAO methods, so it is **not** used on `MissionManager`. `AppContainer` exposes it.

Settings are read with `settingsStore.settings.first()` **before** the Room transaction opens. DataStore I/O is never done inside the transaction.

## Files to create / modify

### Create: domain layer (pure Kotlin)

1. **`domain/Week.kt`**
   - `fun weekStartOf(date: LocalDate): LocalDate`: `date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))`.
   - `fun currentWeekStart(clock: Clock, zone: ZoneId): LocalDate`: `weekStartOf(clock.instant().atZone(zone).toLocalDate())`. Do **not** use `LocalDate.ofInstant`, which needs Java 9 and so isn't available at minSdk 26.
   - `fun weekHasEnded(weekStart: LocalDate, today: LocalDate): Boolean`: `today >= weekStart.plusDays(7)`.

2. **`domain/Pattern.kt`**
   - `fun isValidPattern(p: String): Boolean`: length 1–7 and only `R` or `L`. This is the same rule as `SettingsStore.setDefaultPattern`.
   - `fun sortieTypesOf(pattern: String): List<SortieType>`: `R` → RUN, `L` → LIFT.

3. **`domain/Missions.kt`**
   - `object MissionLifecycle { fun canTransition(from, to): Boolean; fun requireTransition(from, to) }`. The legal transitions are DRAFT→ACTIVE (confirm), ACTIVE→CLOSED (all sorties done, or the week ended) and DRAFT→CLOSED (the week ended; used only by rollover). Everything else is illegal. `requireTransition` throws `IllegalTransitionException` (see 4).
   - `fun newDraft(weekStart: LocalDate, defaultPattern: String): Mission`: a DRAFT with that pattern and `outlineNotes = null`.
   - `fun overridePattern(mission: Mission, pattern: String): Mission`: throws `IllegalStateException` unless the Mission is DRAFT, and `IllegalArgumentException` unless the pattern is valid.
   - `fun confirmWithoutOutline(mission: Mission): Pair<Mission, List<Sortie>>`: requires DRAFT. It returns the Mission as ACTIVE and one PENDING sortie per pattern letter, with `index` 0..n-1. Lift sorties get focus `"full body"` and runs get `"easy"`. All other nullable fields are null.
   - `fun allSortiesDone(sorties: List<Sortie>): Boolean`: true when every sortie is LANDED or SCRUBBED.

4. **`domain/Sorties.kt`**
   - `class IllegalTransitionException(message: String) : IllegalStateException(message)`.
   - `object SortieTransitions { fun canTransition(from, to): Boolean; fun requireTransition(from, to) }`. Legal per §5.1: PENDING→PLANNED, PENDING→SCRUBBED, PLANNED→IN_FLIGHT, PLANNED→SCRUBBED, IN_FLIGHT→LANDED, IN_FLIGHT→SCRUBBED. All other pairs are illegal, including same-state pairs and anything out of LANDED or SCRUBBED.
   - `fun currentSortie(sorties: List<Sortie>): Sortie?`: the lowest `index` that is neither LANDED nor SCRUBBED, or null. It sorts by `index` itself and does not trust list order.
   - `fun requireNoneInFlight(inFlight: List<Sortie>)`: throws `IllegalStateException` if the list is non-empty. This is the "at most one IN_FLIGHT across all Missions" rule.
   - Pure transforms, each calling `requireTransition`:
     - `launch(s, at: Long)`: sets IN_FLIGHT and `launchedAt`.
     - `land(s, at: Long)`: sets LANDED and `landedAt`.
     - `scrub(s, reason: String?)`: sets SCRUBBED and `scrubReason`, with a blank reason stored as null.
     - `markPlanned(s)`: sets PLANNED.

5. **`domain/Rollover.kt`**
   - `data class RolloverResult(val mission: Mission, val scrubbed: List<Sortie>)`.
   - `const val WEEK_ENDED = "week ended"`.
   - `fun rollover(mission: Mission, sorties: List<Sortie>, today: LocalDate): RolloverResult?`. It returns null if the Mission is CLOSED or its week hasn't ended. Otherwise every sortie that isn't LANDED or SCRUBBED becomes SCRUBBED with reason `WEEK_ENDED`; IN_FLIGHT sorties are included and their sets are left alone. The Mission becomes CLOSED, from either DRAFT or ACTIVE. Nothing new is created: no carry-over.

6. **`domain/SortiePlanning.kt`**: the **one place** that decides how a sortie that has just become current is prepared (§5.3, §7.2). The later coach-generation brief changes this file to queue a generation.
   - `sealed interface Preparation { data class SimplePlan(val draft: FlightPlanDraft) : Preparation; data object AwaitGeneration : Preparation }`.
   - `fun prepareCurrent(sortie: Sortie, generateRunPlans: Boolean): Preparation`. A RUN with generation off gets `SimplePlan(simpleRunPlan(sortie))`. A LIFT, or a RUN with generation on, gets `AwaitGeneration`, with a one-line comment that generation will be queued here. No Generation rows are created.
   - `fun simpleRunPlan(sortie: Sortie): FlightPlanDraft`: `source = SIMPLE_RUN`, `title = "Run"`, `notes = sortie.focus`, `rawJson = "{}"`. Every other field is null or an empty list.

### Modify: DAOs (queries only; no entity or schema change)

7. **`data/MissionDao.kt`**: add
   - `@Query("SELECT * FROM mission WHERE id = :id") abstract suspend fun get(id: Long): Mission?`
   - `@Query("SELECT * FROM mission WHERE weekStart = :weekStart") abstract suspend fun getByWeekStart(weekStart: LocalDate): Mission?`
   - `@Query("SELECT * FROM mission WHERE weekStart < :weekStart AND status != 'CLOSED'") abstract suspend fun getUnclosedBefore(weekStart: LocalDate): List<Mission>`
8. **`data/SortieDao.kt`**: add
   - ``@Query("SELECT * FROM sortie WHERE missionId = :missionId ORDER BY `index`") suspend fun getForMission(missionId: Long): List<Sortie>``
   - `@Query("SELECT * FROM sortie WHERE state = 'IN_FLIGHT'") suspend fun getInFlight(): List<Sortie>`
9. **`data/FlightPlanDao.kt`**: add `markOpenSetsNotDone(sortieId)`, which sets status `'SKIPPED'` on that sortie's `OPEN` sets:
   ```
   UPDATE plannedSet SET status = 'SKIPPED' WHERE status = 'OPEN' AND plannedExerciseId IN
     (SELECT pe.id FROM plannedExercise pe JOIN flightPlan fp ON fp.id = pe.flightPlanId WHERE fp.sortieId = :sortieId)
   ```
   Room stores enums by name; the existing `observeHistory` query already relies on that.

### Create: service layer

10. **`data/MissionManager.kt`**
    - Constructor: `(db: LiftoffDatabase, settingsStore: SettingsStore, clock: Clock = Clock.systemUTC(), zone: () -> ZoneId = { ZoneId.systemDefault() })`. The zone is read on every operation, so a phone that changes time zone is respected. Tests pass a fixed `Clock` and zone.
    - `suspend fun onAppOpen(): Mission`. In one transaction:
      1. Roll over each `getUnclosedBefore(currentWeekStart)` Mission using `rollover(...)`, writing the scrubbed sorties and the closed Mission.
      2. Return the current week's Mission, inserting `newDraft(currentWeekStart, settings.defaultPattern)` if there is none.
      
      It never creates a Mission for any other week, and it leaves alone Missions dated after the current week.
    - `suspend fun setPattern(missionId: Long, pattern: String)`: applies `overridePattern`, then updates the Mission.
    - `suspend fun confirm(missionId: Long)`.
      - Requires a DRAFT whose `weekStart` is the current week start; otherwise it throws `IllegalStateException`.
      - Calls `confirmWithoutOutline`, updates the Mission, and inserts the sorties with `insertAll`.
      - Then calls `advance(missionId, settings)`.
    - `suspend fun scrub(sortieId: Long, reason: String? = null)`.
      - Requires the sortie to be `currentSortie` of an ACTIVE Mission.
      - Applies `scrub(...)`. Planned sets are not touched, so an IN_FLIGHT sortie keeps its checked sets and its unchecked sets stay OPEN.
      - Then calls `advance`.
    - `suspend fun launch(sortieId: Long)`.
      - Requires the sortie to be the current sortie of an ACTIVE Mission.
      - Calls `requireNoneInFlight(sortieDao.getInFlight())`, which checks across all Missions.
      - Applies `launch(s, clock.millis())`.
    - `suspend fun land(sortieId: Long)`. Requires IN_FLIGHT, then:
      1. Applies `land(s, clock.millis())`.
      2. Calls `flightPlanDao.markOpenSetsNotDone(sortieId)`.
      3. Calls `advance`.
    - `private suspend fun advance(missionId, settings)`: the single follow-up after confirm, scrub and land. It loads `getForMission` and finds `currentSortie`.
      - If there is no current sortie, the Mission goes ACTIVE→CLOSED through `MissionLifecycle.requireTransition`.
      - If the current sortie is PENDING, it calls `prepareCurrent(current, settings.generateRunPlans)`. For a `SimplePlan`, it calls `flightPlanDao.writePlan(current.id, draft)` and updates the sortie with `markPlanned`. For `AwaitGeneration` it does nothing.
    - Each public method reads settings first if it needs them, then does all its DB work in a single `db.withTransaction { }`.

### Modify: existing files

11. **`AppContainer.kt`**: add `val missionManager: MissionManager by lazy { MissionManager(database, settingsStore) }`. `onAppOpen` is not called from app start in this brief (see Decisions).
12. **`ARCHITECTURE.md`**:
    - In the package table, the `com.liftoff.app.domain` row becomes `✅ (week, pattern, Mission and sortie rules, rollover, sortie planning)`.
    - The `data` row's "exists" note adds `MissionManager`.

### Create: tests

JVM unit tests: plain JUnit 4, with no Robolectric runner.

13. **`domain/WeekTest.kt`**
    - Each day Monday through Sunday maps to that Monday.
    - Sunday 23:59 and Monday 00:00 fall in different weeks.
    - The Monday boundary across time zones: one fixed instant, `2026-01-12T03:00Z`, gives week start `2026-01-12` in UTC and `2026-01-05` in `America/New_York` (Sunday evening there).
    - `weekHasEnded` is false on Sunday and true on the next Monday.
14. **`domain/PatternTest.kt`**: valid patterns of length 1 and 7; rejects an empty pattern, length 8, and other letters.
15. **`domain/MissionsTest.kt`**
    - `newDraft` uses the default pattern and is DRAFT.
    - `overridePattern` works on a draft and rejects an invalid pattern.
    - After `confirmWithoutOutline`, `overridePattern` throws: the pattern is frozen.
    - Confirm gives one PENDING sortie per letter in order: R→RUN/"easy", L→LIFT/"full body".
    - `MissionLifecycle`: every one of the 9 from/to pairs is checked as legal or illegal.
    - `allSortiesDone` with mixed states.
16. **`domain/SortiesTest.kt`**
    - All 25 from/to pairs: the 6 legal ones succeed and the 19 illegal ones throw `IllegalTransitionException`.
    - `currentSortie` skips LANDED and SCRUBBED, handles unsorted input, and returns null when all are done.
    - `requireNoneInFlight` throws when one is in flight.
    - `scrub` stores a blank reason as null.
17. **`domain/RolloverTest.kt`**
    - A past ACTIVE Mission's PENDING, PLANNED and IN_FLIGHT sorties become SCRUBBED with "week ended"; LANDED ones are untouched; the Mission is CLOSED.
    - A past DRAFT closes.
    - A current-week Mission returns null.
    - A CLOSED Mission returns null.
    - No new Mission or sortie appears in the result: no carry-over.
18. **`domain/SortiePlanningTest.kt`**
    - A RUN with generation off gets a SimplePlan titled "Run", with source SIMPLE_RUN and the focus in `notes`.
    - A RUN with generation on, and any LIFT, gets AwaitGeneration.

Robolectric test with in-memory Room, following `SortieDaoTest`: `@Config(sdk = [34])`, `allowMainThreadQueries()`, a `SettingsStore` built the way `SettingsStoreTest` builds one, and a fixed `Clock` and zone. A new `MissionManager` is built over the same database to move time forward.

19. **`data/MissionManagerTest.kt`**
    - **onAppOpen**
      - Creates a DRAFT for the current Monday with the default pattern.
      - A second call creates nothing new.
      - A past week's ACTIVE Mission with an IN_FLIGHT sortie and checked sets is rolled over: its sorties are SCRUBBED "week ended", the checked sets are still DONE, and the Mission is CLOSED. Then a new draft appears for this week, with no sorties copied over.
      - A past DRAFT is closed.
    - **setPattern**: updates a draft; throws once the Mission is ACTIVE.
    - **confirm**
      - Pattern `RL` with run generation off: ACTIVE, 2 sorties. Sortie 0 is PLANNED with a SIMPLE_RUN "Run" plan whose `notes` is "easy". Sortie 1 is PENDING with no plan.
      - Pattern `LR`: sortie 0 stays PENDING with no plan.
      - Pattern `R` with generation on: stays PENDING.
      - The `generation` table stays empty.
    - **launch**
      - PLANNED→IN_FLIGHT with `launchedAt` equal to the clock.
      - Rejects a PENDING sortie.
      - Rejects a launch while another Mission has an IN_FLIGHT sortie: at most one in flight.
    - **land**
      - LANDED with `landedAt` equal to the clock.
      - OPEN sets become SKIPPED and DONE sets stay DONE. Use a plan written with `writePlan` that has exercises.
      - With pattern `RR`, the next run becomes current and PLANNED.
      - Landing the last sortie closes the Mission.
    - **scrub**
      - Scrubbing the current sortie with a reason stores it; the next run is prepared and becomes PLANNED.
      - Scrubbing an IN_FLIGHT sortie keeps its DONE sets.
      - Scrubbing the last sortie closes the Mission.
      - Scrubbing a sortie that isn't current throws.

## Order of work

1. Domain files 1–6 with tests 13–18.
2. DAO queries 7–9.
3. `MissionManager` (10) and `AppContainer` (11), with test 19.
4. `ARCHITECTURE.md` (12).
5. Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.
6. Commit with an `area: lowercase summary` message, staging named files only.

## Testing

§13 cases are mapped to tests as follows:

| §13 case | Tests |
|---|---|
| Draft created with the default pattern | MissionsTest, MissionManagerTest.onAppOpen |
| Override, then frozen on confirm | MissionsTest, MissionManagerTest.setPattern |
| Rollover scrubs with "week ended" | RolloverTest, MissionManagerTest.onAppOpen |
| No carry-over | RolloverTest, MissionManagerTest.onAppOpen |
| Monday boundary across time zones | WeekTest |
| Every legal and illegal transition | SortiesTest, MissionsTest |
| Next-sortie selection | SortiesTest |
| At most one in flight | SortiesTest, MissionManagerTest.launch |

Simple run plan creation and every Room-backed operation are covered in `MissionManagerTest`. That includes land advancing to the next sortie and closing the Mission.

## Risky or uncertain

1. **Transactions.** `db.withTransaction` from room-ktx 2.6.1 wraps the multi-DAO work. `FlightPlanDao.writePlan` is itself `@Transaction`, and nesting inside `withTransaction` is fine. The existing tests already run suspend `@Transaction` DAO methods under `runBlocking` in Robolectric.
2. **Domain purity.** Domain files use `com.liftoff.app.data` data classes (`Mission`, `Sortie`, `FlightPlanDraft`) and enums, but import nothing from `android.*` or `androidx.*`, and they run on the plain JVM. Duplicating those types in `domain/` would be churn without benefit.
3. **minSdk 26.** Use `instant.atZone(zone).toLocalDate()`, not `LocalDate.ofInstant`.
4. **Draft insert uniqueness.** `weekStart` is unique. The check and the insert both run inside one transaction, so repeated `onAppOpen` calls can't create a duplicate.

## Decisions

- **No open decisions.** The plan said the brief settles every behaviour and no open decisions remain.
  Changed by review: the plan chose "no open decisions"; the review chose to list the open behaviours below, because several are left to the implementer and the owner should see them.
- **Unchecked sets at landing.** The plan chose to leave them OPEN ("OPEN stays OPEN").
  Changed by review: the plan chose to leave unchecked sets OPEN; the review chose to set them to SKIPPED with null actuals, because the brief asks to *mark* them as not done, §8 only has OPEN/DONE/SKIPPED, and leaving OPEN on a landed sortie would read as still to do.
- **Launch and land times.** The plan took `landedAt` as a caller parameter.
  Changed by review: the plan chose a `landedAt` parameter; the review chose to take both `launchedAt` and `landedAt` from the injected clock, because the brief requires an injectable clock and the UI should not supply timestamps.
- **Mission transitions.** The plan listed DRAFT→CLOSED as illegal.
  Changed by review: the plan chose DRAFT→CLOSED as illegal; the review chose to allow it only for rollover, because the brief says a Mission whose week has ended closes, and an unconfirmed draft from last week otherwise stays open forever.
- **Time zone.** The plan named both a `Clock` and a `TimeZone`, then said the zone comes from the Clock.
  Changed by review: the plan chose to take the zone from the Clock, fixed at construction; the review chose an injectable `Clock` plus a zone provider read on every operation, because the week start must follow the phone's current local zone even after travel.
- **Added by review: scrub advances like land.** After a scrub, the next sortie becomes current and is prepared (a run with generation off gets its simple plan). Scrubbing the last open sortie closes the Mission. This follows §4.3 and the brief's "after the previous sortie lands or is scrubbed".
- **Added by review: only the current sortie is prepared.** On confirm, only sortie 0 is prepared, even when later sorties are runs. Each later run gets its plan when it becomes current, matching §5.3: the plan is made when a generation would be queued, and the toggle is read then.
- **Added by review: where the run focus goes.** The SIMPLE_RUN plan stores the sortie's focus in `notes`. `runKind` stays null because it is meant for a generated plan's kind. `rawJson` is `"{}"` because there is no coach output.
- **Added by review: scrubbing in flight.** Checked sets are kept and unchecked sets stay OPEN. The SCRUBBED state already says the sortie wasn't done, and the brief asks only to keep the checked ones.
- **Added by review: optional scrub reason.** A blank reason is stored as null.
- **Added by review: launch rules.** Only the current sortie of an ACTIVE Mission can be launched, and only if no sortie in any Mission is IN_FLIGHT. That includes a stale one from a past week that hasn't been rolled over yet.
- **Added by review: confirm rules.** Only the current week's DRAFT can be confirmed. A draft whose week has ended is closed by rollover instead.
- **Added by review: rollover scope.** Rollover only touches unclosed Missions from weeks before the current one. Missions dated after the current week (for example after the clock is changed) are left alone, and no Mission is ever created for any week but the current one.
- **Added by review: app start.** `onAppOpen` is exposed through `AppContainer.missionManager` but not called from app start in this brief. The Launchpad brief wires it in, since this brief adds no screens.
