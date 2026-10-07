# Plan: Settings Store, Exercise Names and AppContainer

## Approach

Three self-contained source areas that share one compile boundary (the `app` module). They are built in dependency order because `AppContainer` depends on `SettingsStore`, but they can be tested independently. The plan stays within the existing Gradle setup — no new dependencies, no new modules, no UI changes.

## Files and code involved

### 1. Settings store (`com.liftoff.app.settings`)
- **New file:** `app/src/main/java/com/liftoff/app/settings/SettingsStore.kt`
  - A singleton-like object backed by `androidx.datastore.preferences.core.PreferenceDataStoreFactory`.
  - Exposes a `Flow<Settings>` of all settings.
  - Provides one suspend update function per setting:
    | Setting | Key | Type | Default | Validation |
    |---|---|---|---|---|
    | daemon host | `daemonHost` | String | empty string | none |
    | daemon port | `daemonPort` | Int | 8737 | 1–65535 |
    | daemon token | `daemonToken` | String | empty string | none |
    | coach workspace path | `coachWorkspacePath` | String | empty string | none |
    | default pattern | `defaultPattern` | String | "RLRLR" | 1–7 chars, each 'R' or 'L' |
    | sortie length (minutes) | `sortieLengthMinutes` | Int | 60 | positive |
    | weight unit | `weightUnit` | String ("lb"/"kg") | "lb" | reject unknown on write; fall back to default on read |
    | distance unit | `distanceUnit` | String ("mi"/"km") | "mi" | same |
    | history window (days) | `historyWindowDays` | Int | 28 | positive |
    | generate run plans toggle | `generateRunPlans` | Boolean | false | none |
    | objectives | `objectives` | String | empty string | none |
    | constraints | `constraints` | String | empty string | none |

  - Each setter validates *before* writing. If invalid, throws an `IllegalArgumentException` and the old value remains in DataStore.
  - On read, if a stored unit value is not "lb"/"kg" (or "mi"/"km"), the default is used instead.

- **New file:** `app/src/test/java/com/liftoff/app/settings/SettingsStoreTest.kt`
  - JVM test using JUnit 4 + Robolectric (`@RunWith(RobolectricTestRunner::class)`).
  - Uses a temporary file for DataStore (via `PreferenceDataStoreFactory.createTempFile` or equivalent).
  - Tests:
    - Every default value is correct.
    - Set-then-read round trip for every field.
    - Rejected values throw and the previous value remains stored.
    - Invalid pattern, port out of range, non-positive sortie length/history window.
    - Unknown unit falls back to default on read.

### 2. Exercise name normalizer (`com.liftoff.app.coach`)
- **New file:** `app/src/main/java/com/liftoff/app/coach/ExerciseNameNormalizer.kt`
  - A pure Kotlin object with one public function:
    ```kotlin
    fun normalize(name: String): String
    ```
  - Algorithm (in order):
    1. `lowercase()`
    2. Remove every character that isn't a letter, digit, whitespace, or `-`
    3. Collapse runs of whitespace to a single space
    4. Trim leading/trailing whitespace
  - Spaces are never turned into hyphens.
  - No Android imports whatsoever (ARCHITECTURE.md invariant 2).

- **New file:** `app/src/test/java/com/liftoff/app/coach/ExerciseNameNormalizerTest.kt`
  - Pure JVM test (no Robolectric needed).
  - Tests:
    - "Bench Press", "bench  press", "BENCH PRESS", " Bench Press. " all normalize to "bench press".
    - "Bench-Press" keeps its hyphen → "bench-press".
    - Symbols like `&`, `(`, `'` are removed (e.g., "Smith Machine & Bench" → "smith machine bench").

### 3. AppContainer and Application subclass
- **New file:** `app/src/main/java/com/liftoff/app/AppContainer.kt`
  - A hand-built class in package `com.liftoff.app` (root).
  - Creates the settings store once, lazily, from the application context.
  - Provides a single public accessor: `val settingsStore: SettingsStore`.
  - No DI framework.

- **New file:** `app/src/main/java/com/liftoff/app/LiftoffApplication.kt`
  - An `android.app.Application` subclass.
  - Owns one `AppContainer` instance.
  - Registered in `AndroidManifest.xml` via `android:name=".LiftoffApplication"` on the existing `<application>` element.

- **Modified file:** `app/src/main/AndroidManifest.xml`
  - Add `android:name=".LiftoffApplication"` to the `<application>` tag. No other attribute changes.

- **New file:** `app/src/test/java/com/liftoff/app/AppContainerTest.kt`
  - Robolectric test (`@RunWith(RobolectricTestRunner::class)`).
  - Shows that the Application owns one container and the container returns the same settings store on repeated access (identity check).

### 4. Architecture doc update
- **Modified file:** `ARCHITECTURE.md`
  - Update the package table:
    - Mark `settings` as existing ✅
    - Mark `coach` as started with the name normalizer ✅
    - Mark root package's `AppContainer` as existing ✅

## Order of work

1. **Settings store** — write `SettingsStore.kt` and its test. Build compiles; tests pass.
2. **Exercise name normalizer** — write `ExerciseNameNormalizer.kt` and its test. Pure Kotlin, no dependencies on step 1.
3. **AppContainer + Application** — write both files, update manifest. Test that the container is a singleton from the Application.
4. **ARCHITECTURE.md update** — mark packages as existing/started.
5. **Final build gate** — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Testing

The brief's "done when" criteria are met by:

- **SettingsStoreTest.kt:** covers every default, every set-then-read round trip, and every rejection case (invalid pattern, port out of range, non-positive integers). Each test uses its own DataStore on a temporary file.
- **ExerciseNameNormalizerTest.kt:** covers the four equivalence examples from the brief, hyphen preservation, and symbol removal.
- **AppContainerTest.kt:** verifies the Application owns one container and repeated access returns the same settings store.
- **Build gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` must pass with no failures or errors.

## Risky or uncertain areas

- **DataStore on JVM tests.** DataStore Preferences is an Android library. Robolectric (`@RunWith(RobolectricTestRunner::class)`) provides a `Context`, but we need to verify that `PreferenceDataStoreFactory.createTempFile` works under Robolectric. If it doesn't, the fallback is to use `RuntimeEnvironment.getApplication()` for context and construct the DataStore manually.
- **Manifest change.** Adding `android:name=".LiftoffApplication"` must not break the existing `MainActivity` launch or theme. The brief says "Do not change any other manifest attributes."
- **Existing tests.** The plan must not break the Space Age theme tests, `MainActivityTest`, or the build gate. No changes are made to existing files except the manifest and ARCHITECTURE.md.

## Decisions

The brief does not leave any behavior open that someone using this code could notice. All field names, defaults, validation rules, normalization logic, and container behavior are specified explicitly in the brief. The plan follows those specifications directly.
