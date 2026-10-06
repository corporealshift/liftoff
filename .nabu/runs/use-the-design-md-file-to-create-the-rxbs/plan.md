# Plan: Liftoff project fundamentals

The brief asks for "the fundamentals required to start this project: ci, project setup,
architecture docs, etc", based on DESIGN.md. Right now the repo holds only `DESIGN.md` and
`brief.md`. This plan adds three things:

- a Gradle/Android project that builds and passes the project gate (§13)
- a GitHub Actions CI workflow that runs the gate
- the architecture and convention docs that later milestones build against

**Out of scope.** This plan adds no feature code. The following are milestone work (§14),
not fundamentals, and are left for their milestones:

- the `coach/` template, the JSON schemas, the validator, the prompt builder, the fixtures and `LiveCoachTest` (M0)
- Room entities, DataStore settings, `AppContainer`, the theme and navigation (M1)

Do not create any of them here.

---

## 1. Approach

**Copy the build setup from nabu's Android client. Do not invent a new one.** DESIGN.md §11
says the stack matches `nabu/clients/android/app/build.gradle.kts` "so the copied code
compiles unchanged and both apps age together". That project builds on this machine with a
pinned toolchain:

- Gradle 8.11.1, JDK and Android SDK under `C:/Users/corpo/android-toolchain/`
- AGP 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29

The files to copy from live in `C:/Users/corpo/Documents/projects/nabu/clients/android/`.

**Inline dependency versions, as nabu does.** Use no version catalog. Two clients that are
meant to age together are easier to compare line by line when they use the same format.

**The docs follow the owner's convention in the nabu repo:**

- `ARCHITECTURE.md` at the root is the map and the invariants.
- `CLAUDE.md` at the root covers "read first", build and test, and conventions.
- `DESIGN.md` stays the authority on decisions; the new docs point to it and do not repeat it.

---

## 2. Files

### A. Repo hygiene
| File | Content |
|---|---|
| `.gitignore` | Same as nabu's (`local.properties`, `.gradle/`, `build/`, `.kotlin/`), plus `.idea/` and `*.iml`. |
| `.gitattributes` | `* text=auto eol=lf`, `*.bat text eol=crlf`, `*.jar binary`. Line endings are LF, as in nabu. |

### B. Gradle project
| File | Content |
|---|---|
| `settings.gradle.kts` | Copy of nabu's, with `rootProject.name = "liftoff"` and `include(":app")`. |
| `build.gradle.kts` | Copy of nabu's root file: the same plugin block and versions, `apply false`, and the same header comment about pinned versions. |
| `gradle.properties` | Copy of nabu's. |
| `gradlew.sh` | Copy of nabu's. It is the local entry point because the JDK, SDK and Gradle are not on PATH. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | The Gradle wrapper for Gradle 8.11.1 (§3 step 2). CI needs it, and it is the gate command DESIGN.md §13 names. |
| `app/build.gradle.kts` | Copy of nabu's `app/build.gradle.kts` (see the notes after this table). |

The changes to nabu's `app/build.gradle.kts`:

- Set `namespace` and `applicationId` to `com.liftoff.app` (§11).
- Keep `minSdk 26`, `compileSdk`/`targetSdk 35` and JVM 17.
- Keep the Compose, serialization and KSP plugins.
- Keep the debug-signed release build, because the app is installed directly onto one phone.
- Keep the `testOptions` block and the full dependency list unchanged. That list is the §11
  stack: Compose/M3, kotlinx.serialization, coroutines, OkHttp 4, Room with KSP, DataStore,
  WorkManager, JUnit 4, Robolectric, coroutines-test, MockWebServer, room-testing and
  work-testing. With it in place, later milestones do not have to touch the build.
- Add no dependency-injection library (§11: "No dependency-injection framework").

### C. Minimal app source

The goal is only that `assembleDebug` has something to build.

| File | Content |
|---|---|
| `app/src/main/AndroidManifest.xml` | `INTERNET` and `ACCESS_NETWORK_STATE` permissions, and `android:usesCleartextTraffic="true"` (the daemon speaks plain ws over Tailscale, as in nabu's manifest, including its comment). Set `android:label="@string/app_name"` and `android:theme="@android:style/Theme.Material.Light.NoActionBar"`. Leave out the icon attributes, so no mipmap assets are needed yet. Declare one exported launcher activity, `.MainActivity`. |
| `app/src/main/res/values/strings.xml` | `app_name` = `Liftoff`. |
| `app/src/main/java/com/liftoff/app/MainActivity.kt` | A `ComponentActivity` whose `setContent { MaterialTheme { Surface { Text("Liftoff") } } }` is a placeholder. Give it a one-line comment saying M1 replaces it with the navigation shell. |

Add no tests in this plan. `testDebugUnitTest` still runs and passes with no test sources.

### D. CI
`.github/workflows/ci.yml`, modelled on nabu's workflow:

- `on: pull_request` and `push` to `main`.
- `concurrency` keyed on the workflow and ref, with `cancel-in-progress: true`.
- `permissions: contents: read`.
- One job, `gate`, on `ubuntu-latest` with `timeout-minutes: 20`. The build is
  platform-independent, so there is no OS matrix. The steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` with `distribution: temurin` and `java-version: 17`
  3. `gradle/actions/setup-gradle@v4`, which handles caching
  4. `./gradlew :app:assembleDebug :app:testDebugUnitTest`

  The ubuntu runner already ships the Android SDK with `ANDROID_HOME` set and its licenses
  accepted. If platform 35 is missing, AGP downloads it.
- Add a comment noting that the live coach test (when it exists, M0) skips itself because
  the `LIFTOFF_LIVE_*` variables are never set in CI.

### E. Docs
| File | Content |
|---|---|
| `ARCHITECTURE.md` | The map, kept short (see below). |
| `CLAUDE.md` | The working instructions (see below). |
| `README.md` | A short overview: what Liftoff is, in one paragraph from §1. Then how to build: the gate, and `bash gradlew.sh …` locally. How to install: `adb install app/build/outputs/apk/debug/app-debug.apk`. Pointers to `DESIGN.md`, `ARCHITECTURE.md` and `CLAUDE.md`. Note that the coach workspace setup will live in `coach/README.md` (M0). |

`ARCHITECTURE.md` should cover:

- The topology diagram and a one-paragraph summary from §3.
- The package layout under `com.liftoff.app`, from §12: `nabu/`, `data/`, `settings/`,
  `domain/`, `coach/`, `ui/`. Give each one line of responsibility. Mark which ones exist
  yet: only `MainActivity` does, and the rest arrive by milestone.
- Resource locations: schemas in `src/main/resources/schemas/` and fixtures in
  `src/test/resources/fixtures/`.
- The data flow: UI ↔ Room as the source of truth; WorkManager `GenerationWorker` → nabu
  daemon → validate → Room.
- The invariants, each in one line with its section reference:
  - The phone is the source of truth, and every prompt carries the history it needs (§3).
  - `domain/` and `coach/` are pure Kotlin with no Android imports (§12).
  - Nothing in nabu changes (§3).
  - The nabu code is copied, with a header recording the nabu commit it came from (§11).
  - There is no DI framework; a single `AppContainer` builds everything (§11).
  - Generations are resumable from the `Generation` row (§7.3).
  - Session labels never start with `run:` (§7.3).
  - The phone validates every coach reply (§7.6).
- The milestone list from §14, with M0–M6 marked not started.

`CLAUDE.md` should cover:

- **Read first:** `DESIGN.md` holds every decision. Do not re-litigate decisions; propose
  changes as an amendment at the end of DESIGN.md. Also read `ARCHITECTURE.md`.
- **Build and test:**
  - The gate, `./gradlew :app:assembleDebug :app:testDebugUnitTest`, which must pass before
    work is called done.
  - On this machine, run it as `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`,
    since the toolchain is not on PATH.
- **Conventions:**
  - Commit messages are `area: lowercase summary`, not conventional-commits.
  - Stage named files, never `git add -A`.
  - Use the §2 vocabulary in code (`Mission`, `Sortie`, `FlightPlan`, `Generation`), and
    never "session" for a sortie.
  - Field names in coach JSON are snake_case.
  - Line endings are LF.
  - Comments are short and say why, not what.

---

## 3. Order of work

1. **Hygiene.** Add `.gitignore` and `.gitattributes`.
2. **Wrapper.** With `JAVA_HOME=C:/Users/corpo/android-toolchain/jdk`, run
   `C:/Users/corpo/android-toolchain/gradle/bin/gradle wrapper --gradle-version 8.11.1 --distribution-type bin`
   in the repo root. Do this before `settings.gradle.kts` exists, so no project needs
   configuring; an empty `settings.gradle.kts` is fine too.
   - Then run `git update-index --chmod=+x gradlew` after staging, so `./gradlew` is
     executable on the Linux runner.
   - Confirm that `gradle-wrapper.properties` points at `gradle-8.11.1-bin.zip`.
3. **Gradle files and `gradlew.sh`.** Add the files in §2B.
4. **App source.** Add the manifest, `strings.xml` and `MainActivity.kt` (§2C).
5. **Local gate.** Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. It
   must pass. `local.properties` is not needed, because `gradlew.sh` exports `ANDROID_HOME`.
6. **CI.** Add `.github/workflows/ci.yml`.
7. **Docs.** Write `ARCHITECTURE.md`, `CLAUDE.md` and `README.md`. Write them last, so the
   file names they mention match what was actually created.

---

## 4. Verification

- The local gate (step 5) passes.
- `git ls-files -s gradlew` shows mode `100755`.
- `gradle/wrapper/gradle-wrapper.jar` is committed as binary, and the `.gitattributes`
  rule takes effect.
- The CI workflow cannot be run locally. Its first real run is on the first push or PR,
  and the report should say so rather than claim CI passes.

---

## 5. Risks

| Risk | Mitigation |
|---|---|
| `./gradlew` locally needs to download Gradle 8.11.1 (`~/.gradle/wrapper/dists` is empty), and the toolchain's `JAVA_HOME` is not on PATH | Local verification goes through `gradlew.sh`, which uses the pinned toolchain exactly as nabu does. The wrapper exists for CI and for the gate command DESIGN.md names. |
| The plugins are not in the local Gradle cache | nabu builds with identical plugin and dependency versions on this machine, so they are cached. Do not change any version. |
| KSP and room-compiler with no `@Database` yet | KSP runs and generates nothing, so it is harmless. Keeping it now means M1 does not touch the build. |
| The `gradlew` exec bit is lost when committing from Windows | `git update-index --chmod=+x gradlew` (step 2), checked in §4. |
