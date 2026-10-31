# Plan: Settings Store, Exercise Names and AppContainer

## Approach

This work has three parts, all in the `app` module: the settings store, the exercise-name normalizer, and the `AppContainer` with its `Application` subclass. They are built in dependency order, because `AppContainer` depends on `SettingsStore`. Each part has its own tests. The plan uses the existing Gradle setup: no new dependencies, no new modules, no UI changes and no Room.

Follow CLAUDE.md throughout: LF endings, §2 vocabulary (`sortie`, never "session"), commit messages in the form `area: lowercase summary` (for example `settings: add datastore-backed settings store`), and stage named files only.

## Files and code involved

### 1. Settings store (`com.liftoff.app.settings`)

- **New file:** `app/src/main/java/com/liftoff/app/settings/Settings.kt`
  - `enum class WeightUnit { LB, KG }` and `enum class DistanceUnit { MI, KM }`.
  - `data class Settings(...)` with one property per setting. Every property's default value is the default from the table below, so `Settings()` is the all-defaults value.
- **New file:** `app/src/main/java/com/liftoff/app/settings/SettingsStore.kt`
  - `class SettingsStore(private val dataStore: DataStore<Preferences>)`. This is a plain class, **not** an `object` or singleton. The test gets its own DataStore through the constructor. `AppContainer` is what makes the store a single instance in the app.
  - `val settings: Flow<Settings>` = `dataStore.data.map { prefs -> Settings(...) }`. Every field falls back to its default when its key is missing.
  - One `suspend fun set…` per setting: `setDaemonHost(String)`, `setDaemonPort(Int)`, `setDaemonToken(String)`, `setCoachWorkspacePath(String)`, `setDefaultPattern(String)`, `setSortieLengthMinutes(Int)`, `setWeightUnit(WeightUnit)`, `setDistanceUnit(DistanceUnit)`, `setHistoryWindowDays(Int)`, `setGenerateRunPlans(Boolean)`, `setObjectives(String)`, `setConstraints(String)`.
  - Validation uses `require(...)`, which throws `IllegalArgumentException`. It runs **before** `dataStore.edit { }` is called, so nothing is written for a rejected value.
  - The keys go in an `internal object SettingsKeys`, using snake_case names such as `daemon_host`, `daemon_port` and `weight_unit`. They are `internal` so the test can write a corrupt unit value directly.

    | Setting | Key | Type | Default | Validation |
    |---|---|---|---|---|
    | daemon host | `daemon_host` | String | `""` | none (stored verbatim) |
    | daemon port | `daemon_port` | Int | 8737 | 1–65535 |
    | daemon token | `daemon_token` | String | `""` | none |
    | coach workspace path | `coach_workspace_path` | String | `""` | none |
    | default pattern | `default_pattern` | String | `"RLRLR"` | length 1–7, every char exactly `'R'` or `'L'` (case-sensitive) |
    | sortie length (minutes) | `sortie_length_minutes` | Int | 60 | > 0 |
    | weight unit | `weight_unit` | String (enum name) | `WeightUnit.LB` | typed enum, so it can't be invalid on write |
    | distance unit | `distance_unit` | String (enum name) | `DistanceUnit.MI` | typed enum |
    | history window (days) | `history_window_days` | Int | 28 | > 0 |
    | generate run plans | `generate_run_plans` | Boolean | false | none |
    | objectives | `objectives` | String | `""` | none |
    | constraints | `constraints` | String | `""` | none |

  - Reading units: `WeightUnit.entries.firstOrNull { it.name == stored } ?: WeightUnit.LB`, and the same for `DistanceUnit`. A stored unit that is missing or can't be read gives the default and never throws.

- **New file:** `app/src/test/java/com/liftoff/app/settings/SettingsStoreTest.kt`
  - A **plain JVM test**: JUnit 4 plus `kotlinx-coroutines-test`, **no Robolectric**. DataStore Preferences with a `File` needs no Android APIs.
  - `@get:Rule val tmp = TemporaryFolder()`. Each test builds its own store:
    ```kotlin
    scope = CoroutineScope(Dispatchers.IO + Job())   // cancelled in @After
    dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmp.root, "settings_test.preferences_pb")
    }
    store = SettingsStore(dataStore)
    ```
    The file name **must** end in `.preferences_pb`, because `PreferenceDataStoreFactory.create` rejects any other extension. (`PreferenceDataStoreFactory.createTempFile` does not exist.) Read values with `store.settings.first()` inside `runTest`.
  - Tests:
    - **Defaults:** a fresh store's `settings.first()` equals `Settings()`, and each field is also asserted explicitly against the brief's defaults: `""`, 8737, `""`, `""`, `"RLRLR"`, 60, LB, MI, 28, false, `""`, `""`.
    - **Round trip:** for every one of the 12 fields, set a value that isn't the default and read it back. Include the boundaries: port 1 and 65535, pattern `"R"` and `"RLRLRLR"`, sortie length 1, history window 1, both enum values for each unit.
    - **Rejections:** first set a valid value that isn't the default (for example pattern `"RRL"`, port 9000, sortie length 45, history window 14). Then assert `IllegalArgumentException` for:
      - pattern `""`, `"RLRLRLRL"` (8 chars), `"RLX"`, `"rlr"`, `"R L"`
      - port 0, 65536, -1
      - sortie length 0, -5
      - history window 0, -1

      After each rejection, assert that the stored value is still the earlier one.
    - **Corrupt unit:** `dataStore.edit { it[SettingsKeys.WEIGHT_UNIT] = "stone"; it[SettingsKeys.DISTANCE_UNIT] = "furlong" }`, then `settings.first()` gives LB and MI.

### 2. Exercise name normalizer (`com.liftoff.app.coach`)

- **New file:** `app/src/main/java/com/liftoff/app/coach/ExerciseNames.kt`
  - `object ExerciseNames { fun normalize(name: String): String }`. It is pure, with no imports outside the Kotlin stdlib.
  - Steps, in order:
    1. `lowercase()`, which uses `Locale.ROOT` in Kotlin, so there are no locale surprises.
    2. Keep only chars where `c.isLetterOrDigit() || c.isWhitespace() || c == '-'`.
    3. Turn every whitespace char into `' '` (`map { if (it.isWhitespace()) ' ' else it }`), then collapse runs of spaces with `replace(Regex(" +"), " ")`. This also collapses Unicode spaces such as NBSP, which Java's `\s` does not match.
    4. `trim()`.
  - Spaces never turn into hyphens, and hyphens are kept as they are.
- **New file:** `app/src/test/java/com/liftoff/app/coach/ExerciseNamesTest.kt`
  - A pure JVM test.
  - `"Bench Press"`, `"bench  press"`, `"BENCH PRESS"` and `" Bench Press. "` all give `"bench press"`.
  - `"Bench-Press"` gives `"bench-press"`.
  - Symbols are removed:
    - `"Smith Machine & Bench"` gives `"smith machine bench"`
    - `"Push-Up (Weighted)"` gives `"push-up weighted"`
    - `"Farmer's Walk"` gives `"farmers walk"`
  - Tabs and newlines collapse: `"Bench\t\nPress"` gives `"bench press"`.
  - A name with only symbols, `"!!!"`, gives `""`.
  - **Invariant check:** following the pattern in `ThemePackageTest`, read every `.kt` file under `src/main/java/com/liftoff/app/coach` (resolving the path the same way that test does). Assert that none contains `import android.` or `import androidx.` (ARCHITECTURE.md invariant 2).

### 3. AppContainer and Application subclass

- **New file:** `app/src/main/java/com/liftoff/app/AppContainer.kt`
  ```kotlin
  class AppContainer(context: Context) {
      private val appContext = context.applicationContext
      val settingsStore: SettingsStore by lazy {
          SettingsStore(PreferenceDataStoreFactory.create {
              appContext.preferencesDataStoreFile("settings")
          })
      }
  }
  ```
  The store is created once, lazily, from the application context. There is no DI framework (invariant 5). Only one DataStore may be active per file in a process, and this lazy property in the one container is what guarantees that.
- **New file:** `app/src/main/java/com/liftoff/app/LiftoffApplication.kt`
  - `class LiftoffApplication : Application()` with `lateinit var container: AppContainer; private set`, assigned once in `onCreate()` as `container = AppContainer(this)`.
- **Modified file:** `app/src/main/AndroidManifest.xml`
  - Add only `android:name=".LiftoffApplication"` to the existing `<application>` element. Leave every other attribute, the comment and the activity unchanged.
- **New file:** `app/src/test/java/com/liftoff/app/AppContainerTest.kt`
  - `@RunWith(RobolectricTestRunner::class)` plus **`@Config(sdk = [34])`**. Without the pin, Robolectric 4.14 runs at targetSdk 35, and SDK 35 needs Java 21. CI (`.github/workflows/ci.yml`) runs Java 17. The local toolchain is JDK 21, so the test would pass locally and fail in CI.
  - `val app = ApplicationProvider.getApplicationContext<Context>()`, then:
    - Assert it `is LiftoffApplication`. This proves the manifest registration, because `isIncludeAndroidResources = true` makes Robolectric use the merged manifest.
    - Assert `app.container` is the same instance (`assertSame`) on repeated access.
    - Assert `app.container.settingsStore` is the same instance on repeated access.
  - Don't read or write settings through this store in the test. Only identity is under test, which avoids touching the DataStore file.

### 4. Architecture doc update

- **Modified file:** `ARCHITECTURE.md`, package table only. Change the "Exists yet?" cells to:
  - root: `✅ (`MainActivity.kt`, `AppContainer.kt`, `LiftoffApplication.kt`)`
  - `settings`: `✅ (`SettingsStore.kt`)`
  - `coach`: `Started (`ExerciseNames.kt`: exercise-name normalizer, §7.7)`
- Leave the milestone table alone: M1 is not complete.

## Order of work

1. **Settings store:** `Settings.kt`, `SettingsStore.kt` and the test. Commit.
2. **Exercise name normalizer:** `ExerciseNames.kt` and the test. Commit.
3. **AppContainer + Application + manifest:** the source files, the manifest attribute and the Robolectric test. Commit.
4. **ARCHITECTURE.md:** update the package table. Commit.
5. **Gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` must pass with every existing test still green.

## Testing

- `SettingsStoreTest` covers every default, a round trip for all 12 fields, every rejected value with the earlier value still stored, and the fallback for a corrupt unit. Each test uses its own DataStore on a `TemporaryFolder` file.
- `ExerciseNamesTest` covers the four equivalent names from the brief, the kept hyphen, removal of `&`, `(` and `'`, whitespace collapsing, and the no-Android-imports invariant for `coach`.
- `AppContainerTest` (Robolectric, SDK 34) shows that the Application is `LiftoffApplication`, that it owns one container, and that the container returns the same `SettingsStore` every time.
- The build gate must pass.

## Risky or uncertain areas

- **Robolectric SDK vs JDK.** Covered by `@Config(sdk = [34])` on `AppContainerTest`. Do not add a global `robolectric.properties` unless the pin turns out not to be enough. On the first run, Robolectric downloads the SDK 34 `android-all` jar from Maven Central, so it needs network access.
- **DataStore file extension.** The test files must end in `.preferences_pb`, or `PreferenceDataStoreFactory.create` throws.
- **One DataStore per file.** In production, only `AppContainer.settingsStore` may create the `settings` DataStore. Don't add a second `preferencesDataStore` delegate anywhere.
- **Manifest.** Change only the single `android:name` attribute.

## Decisions

- **No open behaviors (plan's original entry).** The brief does not leave any behavior open that someone using this code could notice. All field names, defaults, validation rules, normalization logic, and container behavior are specified explicitly in the brief. The plan follows those specifications directly.
  Changed by review: the plan chose to record no decisions; the review chose to list the decisions below, because the brief does leave several visible behaviors open (unit typing, trimming, Unicode handling, empty normalized names) and the owner should see what was chosen.
- **Added by review: units are typed enums.** `WeightUnit { LB, KG }` and `DistanceUnit { MI, KM }` are stored by enum name. Callers can't pass an invalid unit, so there is no write-time rejection for units. A stored value that can't be read gives the default (LB or MI).
- **Added by review: pattern case.** Only uppercase `R` and `L` are accepted. `"rlr"` is rejected rather than uppercased, following the brief's "each exactly 'R' or 'L'".
- **Added by review: free-text fields are stored verbatim.** Host, token, workspace path, objectives and constraints are not trimmed or otherwise validated. Checking the host and token belongs to the later "Test connection" feature.
- **Added by review: rejection exception.** A rejected value throws `IllegalArgumentException` from `require`, before `dataStore.edit` is called.
- **Added by review: Unicode in exercise names.** Any Unicode letter or decimal digit is kept, so `"Café Curl"` gives `"café curl"`. Any Unicode whitespace, including tabs, newlines and NBSP, collapses to one space. Lower-casing is locale-independent.
- **Added by review: a name with only symbols normalizes to `""`.** The normalizer doesn't reject it. The database brief decides how to handle an empty normalized name.
- **Added by review: when the store is created.** `LiftoffApplication` creates its container in `onCreate()`. The container creates the DataStore-backed `SettingsStore` on first access to `settingsStore`, and the DataStore file is named `settings.preferences_pb`.
