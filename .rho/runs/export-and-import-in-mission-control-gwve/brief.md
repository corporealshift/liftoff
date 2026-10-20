# Export and Import in Mission Control: bring in the finished UI and make it pass CI

Bring the finished Export/Import UI for Mission Control into this branch, and make sure it passes the GitHub CI gate as well as the local one. An earlier run wrote it and tested it locally. The CI run on its pull request failed, and that run stopped before fixing it. This run writes no new features.

The work is on the branch `rho/export-and-import-actions-in-mission-t9r9`, also pushed to `origin`. Cherry-pick these six commits, in this order and with their original messages:
- `1375e11` (ui: add the backup view model with busy, result rows and the dated export name)
- `265e5bf` (test: prove the backup import action writes through room's flows)
- `7f855ad` (ui: draw the backup card's rows, result line and export/import buttons)
- `75e7490` (ui: put the backup card on mission control on the app's own database)
- `601e8d8` (test: drive the BACKUP card's rows, its buttons and its busy guard from the screen test)
- `578d3b5` (docs: record export and import as built in M1 and M5)

Don't bring over any `.rho/runs/` files from that branch, and don't cherry-pick its `run:` commits. If a cherry-pick conflicts, resolve it by keeping both sides' intent, and record what you did in the plan's decisions.

These commits add:
- `ui/control/BackupViewModel.kt`: plain-Kotlin busy and result state, with work on an injected dispatcher
- `ui/control/BackupSection.kt`: the BACKUP card with Export and Import, the line saying settings and the daemon token are not in the file, and the result row
- the system file pickers (create a dated `liftoff-YYYY-MM-DD.json`, open a document) in `MissionControlScreen.kt`
- the database and MissionManager passed from `LiftoffShell.kt`
- `BackupViewModelTest`, `BackupImportRoomTest` and new cases in `MissionControlScreenTest`
- the ARCHITECTURE.md M1/M5 wording

**The CI failure comes first.** CI is `.github/workflows/ci.yml`: ubuntu-latest, JDK 17, a 20-minute timeout, and `./gradlew :app:assembleDebug :app:testDebugUnitTest`. The build also fails any unit test run that hangs past five minutes.
1. Try to read the failed CI log for the earlier branch, for example with `gh run list --repo corporealshift/liftoff --branch rho/export-and-import-actions-in-mission-t9r9` and then `gh run view <id> --log-failed`. If you can read it, fix the cause it shows.
2. If you can't read it, assume the new tests depend on timing or on the machine. Check each one for:
   - waits too short for a slow runner
   - a state read before the work has settled
   - a dependence on the time zone (CI runs in UTC)
   - a tall Robolectric window
   - a worker thread or database left open after the test
   Make each one deterministic. Do this without weakening what it proves.
3. Record the cause you found, or the changes you made blind, in the plan's decisions, and say it in the PR.

Don't change how export or import behave in the data layer, and don't change the Room schema. Don't click a system picker in a test. In Compose tests, unmount the screen before closing its database. Every wait in a test needs a timeout. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status. One In-Flight screen test (`doneBodyweightSetStepperShowsBW`) sometimes fails with a closed connection pool and passes when run again; re-run it before treating it as broken. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Must not break: settings and equipment editing in Mission Control (`MissionControlScreenTest`, `MissionControlViewModelTest`, `EquipmentViewModelTest`), the shell (`LiftoffShellTest`), the export/import data tests (`DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`), the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The six commits are on the branch, plus any fix commits, and Mission Control shows a working BACKUP card with Export and Import.
- `BackupViewModelTest`, `BackupImportRoomTest` and `MissionControlScreenTest` pass, and so do two full local gate runs in a row.
- ARCHITECTURE.md: M1's note no longer lists export/import as missing, and M5 says export/import is built.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes locally (use `bash gradlew.sh ...` on this machine) and in the pull request's CI.
