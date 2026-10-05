- [x] Hygiene: .gitignore and .gitattributes
  Add `.gitignore` (same patterns as nabu's: local.properties, .gradle/, build/, .kotlin/, .idea/, *.iml) and `.gitattributes` (* text=auto eol=lf, *.bat text eol=crlf, *.jar binary). Verify with `git add -n` that ignored files are excluded.

- [x] Gradle project setup: wrapper and build files
  Generate the Gradle 8.11.1 wrapper using the pinned toolchain (`JAVA_HOME=C:/Users/corpo/android-toolchain/jdk`, `C:/Users/corpo/android-toolchain/gradle/bin/gradle wrapper --gradle-version 8.11.1 --distribution-type bin`). Then add settings.gradle.kts (rootProject.name = "liftoff"), build.gradle.kts (copy of nabu's root with same plugins and versions), gradle.properties (copy of nabu's), and gradlew.sh (copy of nabu's). Stage gradlew and run `git update-index --chmod=+x gradlew`. Confirm gradle-wrapper.properties points at gradle-8.11.1-bin.zip.

- [x] App source: manifest, strings, MainActivity
  Add app/src/main/AndroidManifest.xml (INTERNET + ACCESS_NETWORK_STATE permissions, cleartextTraffic=true, Material.Light.NoActionBar theme, exported MainActivity), app/src/main/res/values/strings.xml (app_name = "Liftoff"), and app/src/main/java/com/liftoff/app/MainActivity.kt (ComponentActivity with a MaterialTheme Surface Text placeholder). Then run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` — it must pass. Verify `git ls-files -s gradlew` shows mode 100755.

- [x] CI workflow
  Add .github/workflows/ci.yml modelled on nabu's: on pull_request and push to main, concurrency group with cancel-in-progress, one gate job on ubuntu-latest (timeout 20m) that checks out, sets up Java 17, runs setup-gradle, then executes `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Include a comment about live coach test skipping in CI.

- [ ] Docs: ARCHITECTURE.md, CLAUDE.md, README.md
  Write ARCHITECTURE.md with the topology diagram summary, package layout (com.liftoff.app.*), resource locations, data flow, invariants (phone is source of truth, domain/coach are pure Kotlin, no DI, resumable generations, etc.), and milestone list. Write CLAUDE.md with read-first instructions, build/test gate commands, and conventions (commit message format, named files only, §2 vocabulary, snake_case JSON fields, LF endings, short comments). Write README.md with a one-paragraph overview, how to build/install, and pointers to the other docs.
