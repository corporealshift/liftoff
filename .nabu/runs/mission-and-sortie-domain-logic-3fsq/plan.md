# Plan: Mission and Sortie Domain Logic (M2)

## Approach

This brief implements the domain state machines and Room-backed service layer for Missions and Sorties. The codebase already has all data entities, DAOs, types (`MissionStatus`, `SortieState`, `SortieType`, etc.), and `AppContainer` wired to Room — so this work sits cleanly between them.

The approach is two layers:

1. **Pure Kotlin domain logic** in `com.liftoff.app.domain` with no Android imports. This holds the state-machine rules (valid transitions, rollover, next-sortie selection) and the week-start calculation. These are plain classes/functions that take data-in and produce data-out — fully testable on the JVM.

2. **A Room-backed service layer** (`MissionManager`) in `com.liftoff.app.data` that calls the domain logic and persists results through the existing DAOs, each operation in a single `@Transaction`. It is exposed through `AppContainer`.

This fits because:
- ARCHITECTURE.md §12 already reserves `domain/` for "State machines for Mission and Sortie lifecycle" and mandates pure Kotlin (invariant 2).
- The existing entities, DAOs, and types match the brief exactly — no schema changes needed.
- No new dependency-injection framework is needed (ARCHITECTURE.md invariant 5); `MissionManager` takes DAOs + settings store in its constructor.

## Files to create / modify

### Create — domain layer (pure Kotlin, JVM unit-testable)

1. **`app/src/main/java/com/liftoff/app/domain/Week.kt`**
   - `fun currentWeekStart(clock: Clock = Clock.systemDefaultZone()): LocalDate` — returns the Monday of the current week in the phone's local time zone.
   - `fun weekEnd(weekStart: LocalDate): LocalDate` — returns the Sunday of that week (weekStart + 6 days).

2. **`app/src/main/java/com/liftoff/app/domain/MissionStateMachine.kt`**
   - `fun validateTransition(from: MissionStatus, to: MissionStatus): Boolean` — DRAFT→ACTIVE and ACTIVE→CLOSED are legal; all others rejected (e.g., DRAFT→CLOSED, CLOSED→ACTIVE).
   - `fun isDraft(mission: Mission) = mission.status == MissionStatus.DRAFT`

3. **`app/src/main/java/com/liftoff/app/domain/SortieStateMachine.kt`**
   - `fun validateTransition(from: SortieState, to: SortieState): Boolean` — implements the §5.1 state machine:
     - PENDING → PLANNED (legal), PENDING → SCRUBBED (legal)
     - PLANNED → IN_FLIGHT (legal), PLANNED → SCRUBBED (legal)
     - IN_FLIGHT → LANDED (legal), IN_FLIGHT → SCRUBBED (legal)
     - LANDED — no outgoing transitions
     - SCRUBBED — no outgoing transitions
   - Every illegal transition throws `StateTransitionException` (a plain Kotlin exception, non-Android).

4. **`app/src/main/java/com/liftoff/app/domain/SortieSelection.kt`**
   - `fun nextSortieIndex(sorties: List<Sortie>): Int?` — returns the lowest index whose state is neither LANDED nor SCRUBBED; null if all are done.

5. **`app/src/main/java/com/liftoff/app/domain/Rollover.kt`**
   - `fun rolloverPastMissions(missions: List<MissionWithSorties>, now: LocalDate): List<RolloverChanges>` — for each Mission whose week has ended (Sunday < now), marks all open sorties as SCRUBBED with reason "week ended" and the Mission as CLOSED. Returns the changes so the service layer can apply them.

6. **`app/src/main/java/com/liftoff/app/domain/FlightPlanBuilder.kt`**
   - `fun buildSimpleRunPlan(sortie: Sortie): FlightPlanDraft` — for a run sortie with generation off, creates a SIMPLE_RUN plan titled "Run" with the sortie's focus. Returns the draft for the DAO to persist.

### Create — service layer (Room-backed, Android-context-needed)

7. **`app/src/main/java/com/liftoff/app/data/MissionManager.kt`**
   - Constructor takes `MissionDao`, `SortieDao`, `FlightPlanDao`, and `SettingsStore`. Also takes an injectable `Clock` and `TimeZone` for testability.
   - Operations (each in a single transaction):
     - `suspend fun onAppOpen()` — rolls over past Missions, then ensures the current week has a Mission (creates DRAFT with default pattern if none).
     - `suspend fun setPattern(missionId: Long, pattern: String)` — validates draft status, sets new pattern.
     - `suspend fun confirmMission(missionId: Long)` — transitions to ACTIVE; creates sorties per pattern letter (R=RUN focus="easy", L=LIFT focus="full body"); for the first sortie with run generation off, builds SIMPLE_RUN plan and advances it to PLANNED; all other sorties start PENDING.
     - `suspend fun scrubSortie(sortieId: Long, reason: String?)` — validates current sortie is next-sortie, transitions through legal path (PENDING→SCRUBBED or PLANNED→SCRUBBED or IN_FLIGHT→SCRUBBED), keeps checked sets if in-flight.
     - `suspend fun launchSortie(sortieId: Long)` — transitions PLANNED→IN_FLIGHT, records `launchedAt`.
     - `suspend fun landSortie(sortieId: Long, landedAt: Long)` — transitions IN_FLIGHT→LANDED with landed time; marks unchecked sets as not done (OPEN stays OPEN); advances to next sortie and prepares its plan; closes Mission if all sorties are done.

### Modify — existing files

8. **`app/src/main/java/com/liftoff/app/AppContainer.kt`**
   - Add `val missionManager: MissionManager` built from the existing `database` and `settingsStore`.
   - Keep it lazy (consistent with existing pattern).

9. **`ARCHITECTURE.md`**
   - Update the package table: mark `domain` as "Exists" in the `com.liftoff.app.domain` row.

### Create — tests

10. **`app/src/test/java/com/liftoff/app/domain/WeekTest.kt`** (JVM unit test)
    - `currentWeekStart` returns correct Monday for dates across a Monday boundary.
    - Time-zone handling: crossing midnight on different time zones yields the same Monday.

11. **`app/src/test/java/com/liftoff/app/domain/MissionStateMachineTest.kt`** (JVM unit test)
    - Legal transitions: DRAFT→ACTIVE, ACTIVE→CLOSED.
    - Illegal transitions: DRAFT→CLOSED, ACTIVE→DRAFT, CLOSED→ACTIVE, etc. — all throw.

12. **`app/src/test/java/com/liftoff/app/domain/SortieStateMachineTest.kt`** (JVM unit test)
    - Every legal transition from §5.1 succeeds: PENDING→PLANNED, PENDING→SCRUBBED, PLANNED→IN_FLIGHT, PLANNED→SCRUBBED, IN_FLIGHT→LANDED, IN_FLIGHT→SCRUBBED.
    - Every illegal transition rejected: PENDING→IN_FLIGHT, PENDING→LANDED, PLANNED→PENDING, IN_FLIGHT→PLANNED, LANDED→*, SCRUBBED→*.

13. **`app/src/test/java/com/liftoff/app/domain/SortieSelectionTest.kt`** (JVM unit test)
    - Next-sortie skips landed and scrubbed sorties.
    - Returns null when all are done.

14. **`app/src/test/java/com/liftoff/app/domain/RolloverTest.kt`** (JVM unit test)
    - Rollover scrubs open sorties with "week ended".
    - No carry-over: past week's Mission closes, new week starts clean.
    - Current week missions are not touched.

15. **`app/src/test/java/com/liftoff/app/domain/FlightPlanBuilderTest.kt`** (JVM unit test)
    - SIMPLE_RUN plan for a run sortie has correct title "Run", source SIMPLE_RUN, and focus.

16. **`app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt`** (Robolectric, in-memory Room)
    - `onAppOpen`: creates DRAFT with default pattern if none exists; does nothing if current week already has a Mission.
    - `setPattern`: updates draft pattern; rejects on non-draft.
    - `confirmMission`: transitions to ACTIVE, creates sorties per pattern, first run sortie gets SIMPLE_RUN plan and PLANNED state.
    - `scrubSortie`: scrubs current sortie with reason; IN_FLIGHT keeps checked sets.
    - `launchSortie`: transitions PLANNED→IN_FLIGHT, records launchedAt.
    - `landSortie`: transitions LANDED, advances to next sortie, closes Mission when all done.

## Order of work

1. **Domain layer (pure Kotlin)** — create `Week.kt`, `MissionStateMachine.kt`, `SortieStateMachine.kt`, `SortieSelection.kt`, `Rollover.kt`, `FlightPlanBuilder.kt`. Write JVM unit tests for each and verify they compile + pass.
2. **Service layer** — create `MissionManager.kt` in `data/`, wire it into `AppContainer.kt`.
3. **Integration tests** — write `MissionManagerTest.kt` with Robolectric/in-memory Room.
4. **ARCHITECTURE.md update** — mark `domain` as existing.
5. **Build gate** — run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

- **JVM unit tests** (no device, no Robolectric): domain logic classes are pure Kotlin — tested directly with JUnit 4 on the JVM. These cover state machines, week calculation, rollover logic, next-sortie selection, and flight plan building.
- **Robolectric integration tests**: `MissionManagerTest.kt` uses `Room.inMemoryDatabaseBuilder` with `ApplicationProvider.getApplicationContext<Context>()`, matching the existing test patterns (see `MissionDaoTest.kt`). Tests cover every Room-backed operation end-to-end through Room, including land advancing to next sortie and closing the Mission.
- The brief's §13 test cases map directly:
  - Draft created with default pattern → `MissionManagerTest.onAppOpen`
  - Override then frozen on confirm → `setPattern` + `confirmMission`
  - Rollover scrubs open sorties → `RolloverTest` (logic) + `onAppOpen` (integration)
  - No carry-over → same
  - Monday boundary across time zones → `WeekTest`
  - Every legal/illegal transition → `MissionStateMachineTest`, `SortieStateMachineTest`
  - Next-sortie selection skipping landed/scrubbed → `SortieSelectionTest`
  - At most one in flight → enforced by the brief's invariant; tested implicitly in state machine tests

## Risky or uncertain

1. **`@Transaction` scope across multiple DAOs.** Room's `@Transaction` annotation wraps an entire suspending function call. The brief requires each operation in one transaction — this is standard Room and works with Kotlin coroutines. No risk here.

2. **Clock/timezone injection for pure-Kotlin code.** `java.time.Clock` and `java.time.ZoneId` are java.time classes, not Android-specific. They work on the JVM test runtime. The brief says "injectable so tests can control them" — injecting `Clock` is sufficient; `ZoneId` comes from the Clock's zone or is passed explicitly.

3. **Sortie creation during confirm.** Creating multiple sorties in one transaction (one INSERT per pattern letter) is fine with Room's `@Transaction`. The existing `SortieDao.insertAll` already exists for this purpose.

4. **At-most-one-in-flight invariant.** This is a global constraint across all Missions. The service layer enforces it at the point of launch — if another Mission has an IN_FLIGHT sortie, launch is rejected. This is checked in `MissionManager.launchSortie` before transitioning.

## Decisions

The brief settles every behavior the plan touches. No open decisions remain.
