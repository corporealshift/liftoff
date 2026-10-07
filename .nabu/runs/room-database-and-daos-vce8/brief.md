# Room database and DAOs

Build Liftoff's Room database as described in DESIGN.md §8 and §7.7. No screens change; later briefs build on it. Read DESIGN.md and ARCHITECTURE.md first. Room 2.6.1 with KSP, room-testing and Robolectric are already declared in app/build.gradle.kts, so no dependency changes should be needed.

**Warning from a previous attempt.** An earlier run failed on this work. Room's KSP processor runs before the Kotlin compiler, so an ordinary Kotlin error in a file Room reads shows up only as `[MissingType]: Element '...LiftoffDatabase' references a type that is not present`, with no detail. The run retried the same build until it was stopped. The cause was simple: the database class used DAO types from another package without importing them. If you see that error, look for unresolved references, such as missing imports, wrong package names or misspelled types, in the database, entity, DAO and converter files. Add a few entities at a time and check that the build still compiles after each step.

**Database (`com.liftoff.app.data`).**
- Entities for Mission, Sortie, FlightPlan, PlannedExercise, PlannedSet, RunSegment, Exercise, Generation and Equipment, with the fields and enum states listed in §8. Use the §2 vocabulary in code, and never call a sortie a 'session'. Remember that `index`, `order` and `key` are SQL keywords.
- Uniqueness: Mission.weekStart, FlightPlan.sortieId, Exercise.normalizedName and Equipment.key are unique.
- The database is version 1 and must never be set up to drop data on migration, because the phone holds the only copy.
- DAOs for what the app will need next:
  - observing a given week's Mission with its sorties in index order
  - reading a sortie's Flight Plan with its exercises (including display names), sets and run segments, all in their stored order
  - writing a whole Flight Plan in one transaction, replacing any plan the sortie already has
  - updating a set's actual values and status, adding an extra set (marked as added), and updating an exercise's skipped flag and notes
  - updating sorties and missions
  - listing landed and scrubbed sorties newest first
  - insert, read, update and delete for Generation rows
  - equipment: add, edit, list active only or all, deactivate and reactivate
- **Exercise identity (§7.7).** Looking up an exercise by name uses the existing normalizer in `com.liftoff.app.coach`. It returns the existing row when the normalized name matches and creates one otherwise. The first spelling seen stays the display name. Writing a Flight Plan resolves its exercise names the same way.
- **Equipment.** A key must match `[a-z0-9_]+` and be unique. An invalid key is rejected. Removing an item marks it inactive and never deletes it, and there is no delete operation at all.

**AppContainer.** Add the database to the existing `AppContainer` in `com.liftoff.app`. It is created once, lazily, next to the settings store that is already there.

Builds on the AppContainer, Application subclass, settings store and exercise-name normalizer from the previous brief, and the Space Age theme and placeholder screen. Must not break: those and their tests, the launcher icon, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Robolectric tests with in-memory Room cover each DAO behaviour above: ordering, plan replacement, set and exercise updates, newest-first history, and Generation CRUD.
- Tests cover the four uniqueness rules, equipment key validation and 'deactivate, don't delete'.
- Tests show that spelling variants of an exercise name map to one Exercise row.
- ARCHITECTURE.md's package table marks `data` as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
