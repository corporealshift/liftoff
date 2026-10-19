# Export and Import actions in Mission Control

Give the owner Export and Import buttons in Mission Control. The phone holds the only copy of the training history, so this is the backup story. Read DESIGN.md §8 ('Export and import') and §10 ('Lost or reset phone'), and `design/README.md`.

The data layer already exists in `com.liftoff.app.data`, with tests:
- `DatabaseExport` writes the whole database to an OutputStream.
- `DatabaseImport` reads one from an InputStream. It refuses a non-empty database or an unreadable file with a message, and it runs `onAppOpen()` and reports how many Missions and sorties it restored.

This run adds only the UI and the wiring. Don't change how export or import behave. Mission Control lives in `com.liftoff.app.ui.control` (`MissionControlScreen.kt` and its view models). `LiftoffShell.kt` passes it pieces from `AppContainer`.

What to build:
- A BACKUP section in Mission Control with an Export button and an Import button.
- Next to them, a line saying settings and the daemon token are not in the file.
- Export opens the system file picker to create a `.json` file. The default name carries today's local date, like `liftoff-2026-10-19.json`.
- Import opens the system file picker to choose a file.
- Both run off the main thread and show that they are working while they run. The result shows as a row that stays until the next action:
  - after an import, how much was restored
  - after a failure, the data layer's message
  - after an export, that it was saved
- Cancelling the picker does nothing.
- Use the design's parts: a section head, ink-ruled rows, buttons from `ui.theme`, and no stock M3 tonal surfaces. Keep the busy/result logic in plain Kotlin outside Compose, so a test can drive it with fake work.
- After a successful import, the other tabs show the restored data. They read Room through flows, so make sure the import goes through Room, not around it.

If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Don't click a system picker in a test. In Compose tests, unmount the screen before closing its database. Every wait in a test needs a timeout. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: settings and equipment editing in Mission Control (`MissionControlScreenTest`, `MissionControlViewModelTest`, `EquipmentViewModelTest`), the shell (`LiftoffShellTest`), the export/import data tests, the other screens, and the build gate. Existing tests that assert there is no Export placeholder, or that list the section heads, may be updated to match. Say so in the PR. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- A plain test shows the backup work running off the calling thread and the state going from busy to success or failure, with the message kept.
- A Robolectric Compose test shows the BACKUP head, Export and Import enabled, the exclusion line, and a success and a failure row drawn from the state.
- ARCHITECTURE.md: M1's note no longer lists export/import as missing, and M5 says export/import is built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
