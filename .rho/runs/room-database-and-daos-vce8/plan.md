# Plan: Room database and DAOs

## Approach

This brief builds on M1's `AppContainer` (with `SettingsStore`), the `ExerciseNames` normalizer (`com.liftoff.app.coach.ExerciseNames.normalize`), the Space Age theme, and the placeholder screen. It adds the Room entities, type converters, DAOs, `LiftoffDatabase`, exercise-name resolution (§7.7), the database in `AppContainer`, and Robolectric tests. No screens change.

All new main code goes in **one package, `com.liftoff.app.data`** (`app/src/main/java/com/liftoff/app/data/`), so entities, DAOs and the database reference each other without imports. The only cross-package import is `com.liftoff.app.coach.ExerciseNames`. Every file still needs its own `androidx.room.*`, `kotlinx.coroutines.flow.*` and `java.time.LocalDate` imports.

The work is incremental. After each small group of files, run `bash gradlew.sh :app:compileDebugKotlin`, which runs `kspDebugKotlin` first. **If the build fails with `[MissingType]: Element '...' references a type that is not present`, do not rerun the same build.** Look for unresolved references in the data files: a missing import, a wrong package line, or a misspelled type. Fix that before building again.

## SQL keywords

`index`, `order` and `key` are SQL keywords. Keep the Kotlin property names as they are (`index`, `order`, `key`). Room quotes column names in the SQL it generates. **Every hand-written `@Query` must backtick them**: `` `index` ``, `` `order` ``, `` `key` ``. The same goes for the table names `Mission`, `Sortie`, `FlightPlan` and so on. These are Room's default table names (the class names), and they don't need quoting.

## Files

### Enums (`data/Enums.kt`, one file)

Room 2.6.1 stores enums as their name (TEXT) automatically, so no converter is needed.
- `MissionStatus { DRAFT, ACTIVE, CLOSED }`
- `SortieType { RUN, LIFT }`
- `SortieState { PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED }`
- `FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }`
- `SetStatus { OPEN, DONE, SKIPPED }`
- `GenerationKind { OUTLINE, FLIGHT_PLAN }`
- `GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`

### Entities

Each is a `data class` with `@PrimaryKey(autoGenerate = true) val id: Long = 0`. Timestamps are `Long` epoch millis. Distances, minutes and weights are `Double?`.

1. **`Mission`**: `weekStart: LocalDate` (Monday), `pattern: String`, `status: MissionStatus`, `outlineNotes: String?`. `indices = [Index(value = ["weekStart"], unique = true)]`.
2. **`Sortie`**: `missionId: Long`, `index: Int`, `type: SortieType`, `focus: String?`, `focusRationale: String?`, `state: SortieState`, `launchedAt: Long?`, `landedAt: Long?`, `scrubReason: String?`, `notes: String?`, `runDistance: Double?`, `runMinutes: Double?`. FK `missionId` → `Mission.id`, `onDelete = CASCADE`. Index on `missionId`.
3. **`FlightPlan`**: `sortieId: Long`, `source: FlightPlanSource`, `title: String`, `estimatedMinutes: Int?`, `warmup: String?`, `notes: String?`, `runKind: String?`, `targetDistance: Double?`, `targetPace: String?`, `rawJson: String`. Unique index on `sortieId`. FK → `Sortie.id`, CASCADE.
4. **`PlannedExercise`**: `flightPlanId: Long`, `order: Int`, `exerciseId: Long`, `equipmentIds: List<String>` (equipment keys), `restSeconds: Int?`, `notes: String?` (the coach's notes), `skipped: Boolean = false`, `userNotes: String?` (the owner's notes). FK `flightPlanId` → `FlightPlan.id` CASCADE. FK `exerciseId` → `Exercise.id` with no action. Indices on both columns.
5. **`PlannedSet`**: `plannedExerciseId: Long`, `order: Int`, `reps: Int?`, `seconds: Int?`, `weight: Double?`, `actualReps: Int?`, `actualSeconds: Int?`, `actualWeight: Double?`, `status: SetStatus = OPEN`, `added: Boolean = false`. FK → `PlannedExercise.id` CASCADE, indexed.
6. **`RunSegment`**: `flightPlanId: Long`, `order: Int`, `description: String`, `distance: Double?`, `minutes: Double?`. FK → `FlightPlan.id` CASCADE, indexed.
7. **`Exercise`**: `normalizedName: String` (unique index), `displayName: String`.
8. **`Generation`**: `kind: GenerationKind`, `missionId: Long`, `sortieId: Long?`, `status: GenerationStatus`, `nabuSessionId: String?`, `lastEventId: String?`, `attempt: Int = 0`, `error: String?`, `createdAt: Long`, `finishedAt: Long?`. No foreign keys, so a generation's record outlives any change to its sortie.
9. **`Equipment`**: `key: String` (unique index), `name: String`, `notes: String = ""` (§8 lists `notes` without `?`), `active: Boolean = true`.

`Room` enables `PRAGMA foreign_keys` itself when entities declare foreign keys. The plan-replacement test below proves that the cascade works.

### Converters (`data/Converters.kt`)

- `LocalDate` ↔ `Long` (`toEpochDay` / `ofEpochDay`). `minSdk` is 26, so `java.time` is available.
- `List<String>` ↔ JSON `String`, using kotlinx.serialization (`Json.encodeToString(ListSerializer(String.serializer()), …)`), which is already a dependency.

Register them with `@TypeConverters(Converters::class)` on the database.

### Relation and input types (`data/Relations.kt`)

- `MissionWithSorties(@Embedded val mission: Mission, @Relation(parentColumn = "id", entityColumn = "missionId") val sorties: List<Sortie>)`
- `PlannedExerciseRow(@Embedded val plannedExercise: PlannedExercise, val displayName: String)`: a query POJO.
- Read model, which is not a Room relation and is assembled in the DAO:
  - `FlightPlanDetail(val plan: FlightPlan, val exercises: List<PlannedExerciseDetail>, val segments: List<RunSegment>)`
  - `PlannedExerciseDetail(val plannedExercise: PlannedExercise, val displayName: String, val sets: List<PlannedSet>)`
- Write input:
  - `FlightPlanDraft(source, title, estimatedMinutes, warmup, notes, runKind, targetDistance, targetPace, rawJson, exercises: List<ExerciseDraft>, segments: List<RunSegmentDraft>)`
  - `ExerciseDraft(name: String, equipmentIds: List<String>, restSeconds: Int?, notes: String?, sets: List<SetDraft>)`
  - `SetDraft(reps: Int?, seconds: Int?, weight: Double?)`
  - `RunSegmentDraft(description: String, distance: Double?, minutes: Double?)`
  - Each `order` is the item's position in its list (0-based).

### DAOs

DAOs that need logic are `abstract class`es with `@Transaction open suspend fun` methods. This is well supported in Room 2.6.1. Pure query DAOs may be interfaces. Only the operations listed here are added. In particular there are no delete operations for missions, sorties, plans or equipment.

10. **`MissionDao`** (abstract class)
    - `@Insert suspend fun insert(mission: Mission): Long`
    - `@Update suspend fun update(mission: Mission)`
    - `@Transaction @Query("SELECT * FROM Mission WHERE weekStart = :weekStart") protected abstract fun observeRaw(weekStart: LocalDate): Flow<MissionWithSorties?>`
    - `fun observeWeek(weekStart: LocalDate): Flow<MissionWithSorties?>` maps `observeRaw` and sorts `sorties` by `index`, because `@Relation` does not guarantee order. It emits `null` when the week has no Mission.
11. **`SortieDao`** (interface)
    - `@Insert suspend fun insert(sortie: Sortie): Long`, `@Insert suspend fun insertAll(sorties: List<Sortie>)`
    - `@Update suspend fun update(sortie: Sortie)`
    - `@Query("SELECT * FROM Sortie WHERE id = :id") suspend fun get(id: Long): Sortie?`
    - `observeHistory(): Flow<List<Sortie>>` returns sorties in state `LANDED` or `SCRUBBED`, newest first:
      `SELECT s.* FROM Sortie s JOIN Mission m ON m.id = s.missionId WHERE s.state IN ('LANDED','SCRUBBED') ORDER BY m.weekStart DESC, s.` + "`index`" + ` DESC` (see Decisions).
12. **`ExerciseDao`** (abstract class)
    - `@Insert suspend fun insert(exercise: Exercise): Long` (default ABORT, so a duplicate `normalizedName` throws)
    - `@Query("SELECT * FROM Exercise WHERE normalizedName = :normalizedName") suspend fun findByNormalizedName(normalizedName: String): Exercise?`
    - `@Query("SELECT * FROM Exercise ORDER BY normalizedName") suspend fun getAll(): List<Exercise>`
    - `@Transaction open suspend fun resolve(name: String): Exercise` normalizes with `ExerciseNames.normalize`. It throws `IllegalArgumentException` if the result is blank. It returns the existing row if there is one. Otherwise it inserts `Exercise(normalizedName = n, displayName = name.trim())` and returns it. The first spelling seen stays the display name.
13. **`FlightPlanDao`** (abstract class, constructor `(private val db: LiftoffDatabase)`; Room allows a DAO constructor that takes the database as its only parameter)
    - `@Insert suspend fun insertPlan(plan: FlightPlan): Long`, `@Insert suspend fun insertExercise(e: PlannedExercise): Long`, `@Insert suspend fun insertSet(s: PlannedSet): Long`, `@Insert suspend fun insertSegment(s: RunSegment): Long`. These are plain ABORT inserts; the uniqueness test uses `insertPlan`.
    - `@Query("DELETE FROM FlightPlan WHERE sortieId = :sortieId") suspend fun deleteForSortie(sortieId: Long)`. Exercises, sets and segments go with it by cascade. Only `writePlan` uses it.
    - `@Transaction open suspend fun writePlan(sortieId: Long, draft: FlightPlanDraft): Long` runs `deleteForSortie(sortieId)`, inserts the plan, then for each exercise calls `db.exerciseDao().resolve(name)` and inserts the `PlannedExercise` and its sets, then inserts the segments. Each `order` comes from the list position. It returns the new plan id. It is all one transaction: if anything throws, the old plan is untouched.
    - `@Transaction open suspend fun getPlan(sortieId: Long): FlightPlanDetail?` assembles the detail from ordered queries:
      - plan by `sortieId`
      - ``SELECT pe.*, e.displayName AS displayName FROM PlannedExercise pe JOIN Exercise e ON e.id = pe.exerciseId WHERE pe.flightPlanId = :planId ORDER BY pe.`order` `` → `List<PlannedExerciseRow>`
      - ``SELECT ps.* FROM PlannedSet ps JOIN PlannedExercise pe ON pe.id = ps.plannedExerciseId WHERE pe.flightPlanId = :planId ORDER BY ps.`order` `` → grouped by `plannedExerciseId` in Kotlin
      - ``SELECT * FROM RunSegment WHERE flightPlanId = :planId ORDER BY `order` ``
    - `@Query("UPDATE PlannedSet SET actualReps = :actualReps, actualSeconds = :actualSeconds, actualWeight = :actualWeight, status = :status WHERE id = :setId") suspend fun updateSetActuals(setId: Long, actualReps: Int?, actualSeconds: Int?, actualWeight: Double?, status: SetStatus)`
    - `@Transaction open suspend fun addExtraSet(plannedExerciseId: Long, reps: Int?, seconds: Int?, weight: Double?): Long` inserts a set with `order` = (max `order` for that exercise) + 1, or 0 if there is none, and with `added = true` and `status = OPEN`.
    - `@Query("UPDATE PlannedExercise SET skipped = :skipped, userNotes = :userNotes WHERE id = :plannedExerciseId") suspend fun updateExercise(plannedExerciseId: Long, skipped: Boolean, userNotes: String?)`
14. **`GenerationDao`** (interface): `@Insert insert(g): Long`, `@Query get(id): Generation?`, `@Update update(g)`, `@Delete delete(g)`. No other queries.
15. **`EquipmentDao`** (abstract class)
    - `@Insert protected abstract suspend fun insertRaw(e: Equipment): Long`
    - `open suspend fun add(e: Equipment): Long` rejects keys that don't fully match `Regex("[a-z0-9_]+")` with `IllegalArgumentException`, then calls `insertRaw` (ABORT, so a duplicate key throws `SQLiteConstraintException`).
    - `@Query("UPDATE Equipment SET name = :name, notes = :notes WHERE id = :id") suspend fun edit(id: Long, name: String, notes: String)`. The key cannot be edited (see Decisions).
    - `@Query("SELECT * FROM Equipment WHERE active = 1 ORDER BY name") fun observeActive(): Flow<List<Equipment>>`, `@Query("SELECT * FROM Equipment ORDER BY name") fun observeAll(): Flow<List<Equipment>>`
    - `@Query("UPDATE Equipment SET active = 0 WHERE id = :id") suspend fun deactivate(id: Long)`, and `reactivate(id)` setting `active = 1`.
    - `@Query("SELECT * FROM Equipment WHERE id = :id") suspend fun get(id: Long): Equipment?`
    - **No `@Delete` and no DELETE query.**

### Database (`data/LiftoffDatabase.kt`)

`@Database(entities = [Mission::class, Sortie::class, FlightPlan::class, PlannedExercise::class, PlannedSet::class, RunSegment::class, Exercise::class, Generation::class, Equipment::class], version = 1, exportSchema = true)`, `@TypeConverters(Converters::class)`, `abstract class LiftoffDatabase : RoomDatabase()` with an abstract accessor for each of the six DAOs.

It never calls `fallbackToDestructiveMigration*`. Add a one-line comment saying why: the phone holds the only copy.

In `app/build.gradle.kts`, add `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`. This is a processor argument, not a dependency. Commit the generated `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json` (see Decisions).

### AppContainer (`com/liftoff/app/AppContainer.kt`)

Add next to `settingsStore`:
```kotlin
val database: LiftoffDatabase by lazy {
    Room.databaseBuilder(appContext, LiftoffDatabase::class.java, "liftoff.db").build()
}
```
Add a test to `AppContainerTest` that `app.container.database` returns the same instance twice.

### ARCHITECTURE.md

Change the `com.liftoff.app.data` row's "Exists yet?" to `✅ (Room entities, DAOs, LiftoffDatabase)`.

## Order of work

1. `Enums.kt`, `Converters.kt`, then `Exercise`, `Equipment` and `Generation` with a minimal `LiftoffDatabase` listing only those three entities and their DAOs (`ExerciseDao`, `EquipmentDao`, `GenerationDao`). Add the `ksp` schema argument. Compile.
2. Add `Mission`, `Sortie`, `MissionWithSorties`, `MissionDao`, `SortieDao`, and register them. Compile.
3. Add `FlightPlan`, `PlannedExercise`, `PlannedSet`, `RunSegment`, the draft and detail types, and `FlightPlanDao`, and register them. Compile.
4. Add `database` to `AppContainer`. Compile.
5. Write the tests. Run the full gate.
6. Update ARCHITECTURE.md. Commit the named files only, including `app/schemas/.../1.json`, with a message like `data: add room database, entities and daos`.

## Testing

All tests are in `app/src/test/java/com/liftoff/app/data/`. Each class uses `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [34])`, matching the existing tests. Each builds `Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java).allowMainThreadQueries().build()` in `@Before` and closes it in `@After`. Tests use `runBlocking`. Flows are read with `.first()`, so the observe queries themselves are tested and not stand-ins.

- **`MissionDaoTest`**
  - insert a Mission and sorties with indexes 2, 0, 1; `observeWeek(...).first()` returns them in order 0, 1, 2
  - another week returns `null`
  - `update` on a mission and on a sortie is visible on the next read
  - a second Mission with the same `weekStart` throws `SQLiteConstraintException`
- **`SortieDaoTest`**: history across two weeks contains only LANDED and SCRUBBED sorties, ordered by later week first, then higher index first. PENDING, PLANNED and IN_FLIGHT sorties are excluded.
- **`FlightPlanDaoTest`**
  - `writePlan` and then `getPlan` returns exercises, display names, sets and segments in stored order
  - writing a second plan for the same sortie replaces it: one FlightPlan row remains, and the old exercises, sets and segments are gone (count the rows)
  - a direct second `insertPlan` with the same `sortieId` throws `SQLiteConstraintException`
  - `updateSetActuals` changes the values and status
  - `addExtraSet` appends with `added = true` and the next order
  - `updateExercise` sets `skipped` and `userNotes`
  - `getPlan` for a sortie with no plan returns `null`
- **`ExerciseResolverTest`**
  - `resolve("Bench Press")`, `resolve("bench  press")`, `resolve("BENCH PRESS.")` return the same id, there is one row, and `displayName == "Bench Press"`
  - a plan that names "bench press" reuses that row
  - a blank or symbols-only name throws
  - a direct duplicate `insert` with the same `normalizedName` throws
- **`GenerationDaoTest`**: insert, get, update (status, cursor, attempt, error, finishedAt) and delete, after which `get` returns `null`.
- **`EquipmentDaoTest`**
  - `add` accepts `dumbbells_2`
  - it rejects `Bar`, `pull up`, `kb-24` and `""` with `IllegalArgumentException`
  - a duplicate key throws `SQLiteConstraintException`
  - `edit` changes the name and notes
  - after `deactivate` the item is missing from `observeActive` but still in `observeAll`, and `get` still returns it
  - `reactivate` restores it
  - a reflection check that `EquipmentDao` declares no method whose name contains `delete`
- **`AppContainerTest`**: the database is a single instance.
- **Gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Risk and uncertainty

- **`[MissingType]` from KSP.** This is handled by building in three entity groups and by the rule above: inspect, don't retry.
- **Unquoted keywords in `@Query`.** An unquoted `order`, `index` or `key` produces a KSP SQL error that names the query. Fix it with backticks.
- **Room 2.6.1 with Kotlin 2.1.0 and KSP 2.1.0-1.0.29.** These are expected to work (KSP1). If KSP fails on Kotlin metadata, stop and report it rather than changing dependency versions.
- **Cascade replacement.** This depends on Room enabling foreign keys. The replacement test counts rows to prove it.

## Decisions

- **No open decisions remain.** The brief settles every observable behaviour the plan touches. All DAOs, entities, uniqueness rules, exercise resolution, equipment key validation, and the "deactivate not delete" policy are specified.
  Changed by review: the plan chose "no open decisions"; the review chose to list the decisions below, because the brief leaves several behaviours open (history order, extra-set defaults, key edits, blank names, plan replacement mechanics, schema export).
- **Added by review — "newest first" history order.** Scrubbed sorties have no timestamp, so history is ordered by the Mission's `weekStart` descending, then the sortie's `index` descending. Sorties are flown in index order within a week, so this is chronological, and it matches the Landed screen's grouping by week.
- **Added by review — extra sets.** An added set goes after the exercise's last set, with `added = true` and status `OPEN`. The caller supplies its planned reps, seconds and weight, normally copied from the previous set. It is checked off like any other set.
- **Added by review — equipment keys cannot be edited.** `edit` changes the name and notes only. Keys are stored in `PlannedExercise.equipmentIds` and sent in prompts. Renaming a key would orphan history; to get a different key, add a new item and deactivate the old one.
- **Added by review — invalid equipment keys** throw `IllegalArgumentException` before any write. Duplicate keys surface as the database's `SQLiteConstraintException`.
- **Added by review — blank exercise names.** A name that normalizes to an empty string (for example `"!!!"`) is rejected with `IllegalArgumentException`, rather than stored as an exercise called "".
- **Added by review — plan replacement.** `writePlan` deletes the sortie's existing plan, along with its exercises, sets and segments by cascade, inside the same transaction as the insert. A failure leaves the old plan intact. Replacing a plan discards any actuals logged against it; per §5.1, regeneration happens only from PLANNED.
- **Added by review — the exported schema is committed.** The v1 schema JSON is exported to `app/schemas/` and committed. This adds a KSP argument, not a dependency. The phone holds the only copy, so every future version needs a real migration, and Room's migration tests and auto-migrations need the exported v1 schema.
- **Added by review — no extra deletes.** The plan drops the Mission, Sortie and FlightPlan delete operations it had proposed, because the brief does not ask for them. Generation keeps delete because the brief asks for full CRUD there.
