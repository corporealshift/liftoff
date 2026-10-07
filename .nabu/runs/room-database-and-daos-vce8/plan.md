# Plan: Room database and DAOs

## Approach

This brief builds on M1's `AppContainer` (with `SettingsStore`), the `ExerciseNames` normalizer, the Space Age theme, and the placeholder screen. The work adds the Room entities, DAOs, `LiftoffDatabase`, a converter for JSON list fields, an `ExerciseResolver` helper (§7.7), and Robolectric tests.

The approach is incremental: write entity classes first (no KSP dependency), then DAO interfaces with their queries, then the database class, then the resolver, then the tests. After each small group of files I will run `compileDebugKotlin` to catch Room's `[MissingType]` errors early — specifically the warning in the brief about unresolved imports showing up as missing types in the KSP processor output.

## Files and code involved

### New entity classes (`com.liftoff.app.data`)

1. **`Mission.kt`** — `@Entity` with fields: `id`, `weekStart` (Long, epoch day), `pattern` (String), `status` (MissionStatus enum: DRAFT, ACTIVE, CLOSED), `outlineNotes` (String?). Unique on `weekStart`.
2. **`Sortie.kt`** — `@Entity` with fields: `id`, `missionId` (Long), `index` (Int), `type` (SortieType enum: RUN, LIFT), `focus` (String?), `focusRationale` (String?), `state` (SortieState enum: PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED), `launchedAt` (Long?), `landedAt` (Long?), `scrubReason` (String?), `notes` (String?), `runDistance` (Double?), `runMinutes` (Double?).
3. **`FlightPlan.kt`** — `@Entity` with fields: `id`, `sortieId` (Long, unique), `source` (FlightPlanSource enum: GENERATED, REFLY, SIMPLE_RUN), `title` (String), `estimatedMinutes` (Int?), `warmup` (String?), `notes` (String?), `runKind` (String?), `targetDistance` (Double?), `targetPace` (String?), `rawJson` (String). Unique on `sortieId`.
4. **`PlannedExercise.kt`** — `@Entity` with fields: `id`, `flightPlanId` (Long), `order` (Int), `exerciseId` (Long, FK to Exercise), `equipmentIds` (String, JSON list like `["db","bar"]`), `restSeconds` (Int?), `notes` (String?), `skipped` (Boolean), `userNotes` (String?).
5. **`PlannedSet.kt`** — `@Entity` with fields: `id`, `plannedExerciseId` (Long), `order` (Int), `reps` (Int?), `seconds` (Int?), `weight` (Float?), `actualReps` (Int?), `actualSeconds` (Int?), `actualWeight` (Float?), `status` (SetStatus enum: OPEN, DONE, SKIPPED), `added` (Boolean).
6. **`RunSegment.kt`** — `@Entity` with fields: `id`, `flightPlanId` (Long), `order` (Int), `description` (String), `distance` (Double?), `minutes` (Double?).
7. **`Exercise.kt`** — `@Entity` with fields: `id`, `normalizedName` (String, unique), `displayName` (String).
8. **`Generation.kt`** — `@Entity` with fields: `id`, `kind` (GenerationKind enum: OUTLINE, FLIGHT_PLAN), `missionId` (Long), `sortieId` (Long?), `status` (GenerationStatus enum: QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED), `nabuSessionId` (String?), `lastEventId` (String?), `attempt` (Int), `error` (String?), `createdAt` (Long), `finishedAt` (Long?).
9. **`Equipment.kt`** — `@Entity` with fields: `id`, `key` (String, unique, validated against `[a-z0-9_]+`), `name` (String), `notes` (String?), `active` (Boolean).

### Enum classes (`com.liftoff.app.data`)

10. **`MissionStatus.kt`** — DRAFT, ACTIVE, CLOSED
11. **`SortieType.kt`** — RUN, LIFT
12. **`SortieState.kt`** — PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED
13. **`FlightPlanSource.kt`** — GENERATED, REFLY, SIMPLE_RUN
14. **`SetStatus.kt`** — OPEN, DONE, SKIPPED
15. **`GenerationKind.kt`** — OUTLINE, FLIGHT_PLAN
16. **`GenerationStatus.kt`** — QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED

### Converter (`com.liftoff.app.data`)

17. **`EquipmentIdsConverter.kt`** — TypeConverter to/from `List<String>` and JSON String for `PlannedExercise.equipmentIds`.

### Database builder (`com/liftoff/app/data`)

18. **`LiftoffDatabase.kt`** — `@Database(entities = [...], version = 1)` with no fallback to destroy and recreate (no `fallbackToDestructiveMigration()`). DAOs exposed as abstract methods. Room database builder helper.

### DAO interfaces (`com/liftoff/app/data`)

19. **`MissionDao.kt`** — observeMissionWithSorties(weekStart: LocalDate): Flow<MissionWithSorties>; insert/insertAll Mission; update Mission; delete Mission
20. **`SortieDao.kt`** — update sortie state, notes, run metrics; listLandedAndScrubbed newest first; insert/update/delete Sortie
21. **`FlightPlanDao.kt`** — insert full plan in one transaction (upsert FlightPlan + PlannedExercises + PlannedSets + RunSegments); read flight plan with exercises, sets, segments; insert/update/delete
22. **`ExerciseDao.kt`** — upsert by normalizedName; get by normalizedName; update skipped and notes on PlannedExercise
23. **`GenerationDao.kt`** — insert Generation; update status, nabuSessionId, lastEventId, attempt, error, finishedAt; query by sortieId/missionId; delete
24. **`EquipmentDao.kt`** — add (validate key); edit; list active-only or all; deactivate; reactivate

### Exercise resolver (`com/liftoff/app/data`)

25. **`ExerciseResolver.kt`** — Uses `ExerciseNames.normalize()` and an `ExerciseDao` to look up an exercise by its display name: if the normalized name matches an existing row, return it; otherwise insert a new Exercise with that display name. This is what the Flight Plan writing path uses to resolve exercise names to IDs.

### AppContainer update (`com/liftoff/app`)

26. **`AppContainer.kt`** — Add `val database: LiftoffDatabase` as a lazy property alongside settingsStore, built from the application context.

### Test directory

27-34. Robolectric tests in `app/src/test/java/com/liftoff/app/data/`:
- `MissionDaoTest.kt` — insert, observe with sorties in index order
- `SortieDaoTest.kt` — update state, list landed/scrubbed newest first
- `FlightPlanDaoTest.kt` — write full plan in transaction, read back with exercises/exercise display names/sets/segments, replace existing plan
- `ExerciseResolverTest.kt` — spelling variants map to one Exercise row, normalizedName uniqueness
- `GenerationDaoTest.kt` — CRUD for Generation rows
- `EquipmentDaoTest.kt` — add/edit/list active/all/deactivate/reactivate, key validation rejects invalid keys, deactivate doesn't delete
- Uniqueness tests: Mission.weekStart unique, FlightPlan.sortieId unique, Exercise.normalizedName unique, Equipment.key unique

### Architecture update

35. **`ARCHITECTURE.md`** — Mark `data` package as existing (change "—" to "✅").

## Order of work

1. **Enums** — All 7 enum classes. Compile check.
2. **Entity classes** — All 9 entity classes with their fields, unique constraints, and FKs. Compile check.
3. **Converter** — `EquipmentIdsConverter.kt`. Compile check.
4. **DAO interfaces** — All 6 DAO interfaces with queries. Compile check.
5. **Database class** — `LiftoffDatabase.kt` with all entities and version=1, no destructive migration. Compile check.
6. **ExerciseResolver** — Uses ExerciseNames normalizer + ExerciseDao. Compile check.
7. **AppContainer** — Add database property. Compile check.
8. **Tests** — All 8 test classes. Build + run tests.
9. **ARCHITECTURE.md** — Mark `data` as existing.

Each step compiles before moving on, keeping Room's KSP errors isolated and debuggable.

## Testing

- **Robolectric** with in-memory Room database (`Room.inMemoryDatabaseBuilder`). Tests use `allowMainThreadQueries()` since they're unit tests, not production code.
- Each DAO test covers its full behaviour list from the brief:
  - Mission: observe with sorties ordered by index
  - FlightPlan: write entire plan in one transaction (replacing any existing), read back with exercise display names, sets, and segments
  - Set/exercise updates: actual values, status change, adding extra set (added=true), exercise skipped flag and notes
  - Sortie: update state/notes/run metrics; list landed and scrubbed newest first
  - Generation: insert, read, update, delete
  - Equipment: add with key validation, edit, active-only vs all listing, deactivate, reactivate
- Uniqueness tests: attempt to insert duplicate `weekStart`, duplicate `sortieId`, duplicate `normalizedName`, duplicate `key` — expect `SQLiteConstraintException`.
- Exercise identity test: insert "Bench Press", then look up "bench press" and "BENCH PRESS" — all return the same row.
- Equipment key validation: reject keys with uppercase, spaces, or special characters; accept `[a-z0-9_]+`.
- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.

## Risk and uncertainty

- **Room KSP processor errors.** The brief warns that Kotlin errors in entity/DAO files manifest as `[MissingType]` with no detail. Mitigation: compile after each small group of files, not at the end. Watch for missing imports on DAO return types (e.g., `Flow`, `List`) and misspelled entity class names.
- **`index` as a column name.** `index` is a SQL keyword. Room handles this by quoting it, but I'll use `@ColumnInfo(name = "index")` explicitly to be safe. Same for `order` → `@ColumnInfo(name = "order")`.
- **Room version 2.6.1 compatibility with KSP 2.1.0.** The existing build.gradle.kts uses these versions and the project builds, so they are compatible.
- **Flow queries in unit tests.** Room Flow reads can deadlock under Robolectric if called synchronously inside `runBlocking`. I'll use one-shot `suspend` query methods (e.g., `@Query("SELECT ...") suspend fun getAll(): List<...>`) for test assertions rather than `.first()` on a Flow.

## Decisions

The brief settles every observable behaviour the plan touches. All DAOs, entities, uniqueness rules, exercise resolution, equipment key validation, and the "deactivate not delete" policy are specified. No open decisions remain.
