# Database export and import: bring in the finished data layer

Bring the finished export/import data layer into this branch. An earlier run wrote it and tested it, then stopped before its UI. This run adds no UI.

The work is on the local branch `rho/database-export-and-import-in-mission-fqp3`. Cherry-pick these four commits, in this order and with their original messages:
- `c3e1dbe` (data: export the whole database to a JSON stream)
- `2ff4bd9` (data: import a Liftoff export into an empty database)
- `f0d1720` (data: refuse an export this app cannot read)
- `c89dd1d` (data: prove export then import gives an identical database)

These commits add:
- `data/BackupDocument.kt`, `data/DatabaseExport.kt` and `data/DatabaseImport.kt`
- bulk read, insert and emptiness methods on the DAOs
- one draft-Mission delete on `MissionDao`, with the matching edit to `MissionDaoTest`
- `DatabaseExportTest`, `DatabaseImportTest` and `BackupRoundTripTest`

Don't bring over any `.rho/runs/` files from that branch, and don't cherry-pick its `run:` commits. If a cherry-pick conflicts, resolve it by keeping both sides' intent. Record what you did in the plan's decisions.

The behaviour these commits already give, which must still hold:
- Export writes every row of every table, ids included, with a format version and the Room schema version. Keys are snake_case. Settings and the daemon token are never included.
- Import works only into an empty database. A database holding only one DRAFT Mission with no sorties counts as empty, and that draft is replaced.
- A non-empty database, a malformed file, a file that isn't a Liftoff export, and an unknown or newer version are each refused with a clear message, and nothing changes.
- Import runs in one transaction, keeps ids, then runs `MissionManager.onAppOpen()` and reports how many Missions and sorties it restored.

After the cherry-picks:
- Run the gate.
- Fix only what the gate shows is broken.
- Update ARCHITECTURE.md: the `com.liftoff.app.data` row's 'Exists yet?' cell names export and import, and M5's status no longer says export/import is not started (say the data layer is built and its Mission Control actions come next).

If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Don't change the Room schema. Robolectric with Room can hang here, so every wait in a test needs a timeout. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status. One In-Flight screen test (`doneBodyweightSetStepperShowsBW`) sometimes fails with a closed connection pool and passes when run again; re-run it before treating it as broken.

Must not break: every existing test, Mission Control, the other screens, the coach package, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- the four commits are on the branch, and `DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest` and `MissionDaoTest` all pass
- the ARCHITECTURE.md cells above are updated
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine)
