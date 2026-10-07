# Tasks: Settings Store, Exercise Names and AppContainer

1. **Settings store** — `Settings.kt`, `SettingsStore.kt`, `SettingsStoreTest.kt`. All defaults, round trips for every field, rejection of invalid values with prior value preserved, corrupt unit fallback.
2. **Exercise name normalizer** — `ExerciseNames.kt` and test. Covers the four equivalent names from the brief, hyphen kept, symbol removal, whitespace collapsing, all-symbols → empty string, and an invariant check that no `.kt` under `coach/` imports Android.
3. **AppContainer + Application + manifest** — `AppContainer.kt`, `LiftoffApplication.kt`, manifest edit (add `android:name=".LiftoffApplication"` only), and Robolectric test (`@Config(sdk = [34])`) proving the application is `LiftoffApplication` and both container and settings store are singletons.
4. **ARCHITECTURE.md update + build gate** — mark root, `settings`, and `coach` in the package table per the plan, then run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` and confirm it passes.

- [ ] Task 1: Settings store
  Create `app/src/main/java/com/liftoff/app/settings/Settings.kt` (enum classes, data class with all defaults) and `SettingsStore.kt` (DataStore-backed Flow, one suspend setter per setting with require-based validation, unit fallback). Write `app/src/test/java/com/liftoff/app/settings/SettingsStoreTest.kt` as a plain JVM test using TemporaryFolder + `.preferences_pb` files. Tests cover: every default value, set-then-read round trip for all 12 fields (including boundaries), rejection of invalid pattern/port/sortie-length/history-window with prior value still stored, and corrupt unit → default fallback.
  Done when the test file compiles and all tests pass as a JVM-only test (`:app:testDebugUnitTest` includes it).

- [ ] Task 2: Exercise name normalizer
  Create `app/src/main/java/com/liftoff/app/coach/ExerciseNames.kt` with `object ExerciseNames { fun normalize(name: String): String }` — pure Kotlin, no Android imports. Write `app/src/test/java/com/liftoff/app/coach/ExerciseNamesTest.kt` covering: the four equivalent names all normalizing to `"bench press"`, hyphen preservation, symbol removal (`&`, `(`, `)`, `'`), tab/newline collapse, all-symbols → `""`, and an invariant assertion that no `.kt` under `coach/` contains `import android.` or `import androidx.`.
  Done when the test file compiles and all tests pass as a pure JVM test.

- [ ] Task 3: AppContainer + Application + manifest
  Create `app/src/main/java/com/liftoff/app/AppContainer.kt` (lazy `SettingsStore` from application context) and `app/src/main/java/com/liftoff/app/LiftoffApplication.kt` (`lateinit var container`, created in `onCreate`). Edit `app/src/main/AndroidManifest.xml` to add only `android:name=".LiftoffApplication"` to the `<application>` element. Write `app/src/test/java/com/liftoff/app/AppContainerTest.kt` with `@Config(sdk = [34])` proving: app context is `LiftoffApplication`, container is a singleton, and `settingsStore` is a singleton on repeated access.
  Done when the test compiles (Robolectric SDK 34), runs, and all assertions pass.

- [ ] Task 4: ARCHITECTURE.md update + build gate
  Update the package table in `ARCHITECTURE.md`: root → mark as existing with AppContainer.kt and LiftoffApplication.kt listed; `settings` → mark as existing with SettingsStore.kt; `coach` → mark as Started with ExerciseNames.kt. Then run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` and confirm it passes with every existing test still green.
  Done when the gate command exits 0 and the ARCHITECTURE.md table matches the plan's specification.
