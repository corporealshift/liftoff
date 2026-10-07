# Settings store, exercise names and AppContainer

Add Liftoff's settings storage, the exercise-name normalizer and the app's single dependency container. Read DESIGN.md (§2, §7.7, §8, §11) and ARCHITECTURE.md first. No screens change, and this brief does not touch Room; the database comes in the next brief.

**Settings (`com.liftoff.app.settings`), backed by DataStore Preferences** (the dependency is already in app/build.gradle.kts). Provide an observable flow of all settings plus one update function per setting:
- daemon host (default empty), port (default 8737) and token (default empty)
- coach workspace path (default empty)
- default pattern (default RLRLR; must be 1–7 characters, each exactly 'R' or 'L')
- sortie length in minutes (default 60). DESIGN.md calls it 'session length', but code uses the §2 word 'sortie'.
- units: weight lb or kg (default lb), distance mi or km (default mi)
- history window in days (default 28)
- the 'Generate run plans' toggle (default off)
- objectives and constraints (free text, default empty)
Invalid values are rejected with an exception before anything is written, so the stored value is unchanged. This applies to a pattern that isn't 1–7 R/L characters, a port outside 1–65535, and a sortie length or history window that isn't positive. If a stored unit can't be read, use the default.

**Exercise names (§7.7).** Write a pure function that normalizes an exercise name. It lower-cases the name, removes every character that isn't a letter, a digit, whitespace or '-', collapses runs of whitespace to one space, and trims. Spaces are never turned into hyphens. Put it in `com.liftoff.app.coach`, which must have no Android imports (ARCHITECTURE.md invariant 2). The database brief that follows will use it to find or create exercises.

**AppContainer.** Add a single hand-built `AppContainer` class in the root package `com.liftoff.app`. It creates the settings store once, lazily, from the application context. Add an `Application` subclass that owns the one container and register it on the existing `<application>` element in AndroidManifest.xml. Do not change any other manifest attributes. Do not use a dependency-injection framework (invariant 5). Later briefs add the database and other services to this container.

Builds on the Space Age theme in `com.liftoff.app.ui.theme` and the themed placeholder that MainActivity shows. Must not break: those, the launcher icon, the existing tests, and the build gate. Follow CLAUDE.md: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- JVM unit tests cover every settings default, a set-then-read round trip of every field, and the rejected values above, with the previous value still stored after a rejection. Tests use their own DataStore on a temporary file.
- Unit tests show that 'Bench Press', 'bench  press', 'BENCH PRESS' and ' Bench Press. ' all normalize to 'bench press', that 'Bench-Press' keeps its hyphen, and that symbols such as '&', '(' and an apostrophe are removed.
- A test shows that the app's Application owns one container and that the container returns the same settings store on repeated access.
- ARCHITECTURE.md's package table marks `settings` and the root package's `AppContainer` as existing, and `coach` as started with the name normalizer.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
