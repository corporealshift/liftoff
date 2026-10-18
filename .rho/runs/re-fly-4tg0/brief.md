# Re-fly

Add Re-fly (DESIGN.md §5.4), which reuses a previous Flight Plan when a new one can't be generated. Read DESIGN.md §5.1, §5.4 and §13 ('Re-fly') first. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the pure domain package `com.liftoff.app.domain`, which has no Android imports (ARCHITECTURE.md invariant 2)
- `MissionManager` in `com.liftoff.app.data`, which uses one transaction per operation, and `FlightPlanDao` (`getPlan`, and `writePlan` with `FlightPlanDraft`)
- the FlightPlan source enum, which includes REFLY
- the Launchpad's PENDING state in `com.liftoff.app.ui.launchpad` (`LaunchpadState`, `LaunchpadViewModel`, `LaunchpadScreen`)

**Choosing the source.** A pure function picks the source plan for a PENDING sortie. It returns:
- the most recent LANDED sortie of the same type that has a plan
- preferring one whose focus matches the current sortie's focus, and otherwise falling back to any of the same type
- nothing, when there is no such plan

**Copying.** A `MissionManager` operation copies that plan onto the current PENDING sortie in one transaction. It:
- copies planned values only, never actuals
- doesn't copy added sets
- clears skipped flags and user notes
- copies a run's run fields and segments
- sets the copy's source to REFLY and makes the sortie PLANNED
- works with no network
- refuses a sortie that isn't the current PENDING one

**Launchpad.** On the PENDING state, show an underlined Re-fly action next to Scrub when a source plan exists. When none exists, say so in a short muted line instead of showing a dead button. Coach generation isn't wired into the app yet, so for now Re-fly is offered on any PENDING sortie. A later brief will limit it to failed or offline generation.

Every wait in a test needs a timeout. Don't change the Room schema. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: In-Flight, Landed, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Pure unit tests cover the §13 Re-fly cases. The chooser:
  - prefers the same type and focus
  - falls back to the same type only
  - picks the most recent plan
  - ignores scrubbed sorties
  - returns nothing when there is no landed plan of that type
- Robolectric tests show that:
  - the copy has planned values and no actuals, has no added sets, has source REFLY, and leaves the sortie PLANNED
  - it needs no network
  - it refuses a non-current or non-PENDING sortie
  - the Launchpad's PENDING state shows Re-fly only when a source exists
- ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
