# Landed history screen

Replace the Landed tab's placeholder with the real history screen. Read DESIGN.md §5.1 and §9 and `design/README.md` first. Landed has no mockup, so build it from the design's parts: the eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and red deviations as on In-Flight. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- `LandedScreen` in `com.liftoff.app.ui.landed` (a placeholder with no parameters today; change its signature as needed) and the shell in `LiftoffShell`
- `SortieDao.observeHistory()` (landed and scrubbed sorties, newest first) and `MissionDao`
- `FlightPlanDao.getPlan(sortieId)`, which carries planned and actual set values, skipped flags, added sets and exercise notes
- the Sortie row's notes, scrubReason, runDistance and runMinutes
- `SortieDao.updateNotes`
- the plain state-holder pattern that the Launchpad, Mission and In-Flight screens use, and the formatting helpers in `com.liftoff.app.ui.sortie` (including `formatDistance` and `formatRunKind`)
- the themed note dialog in `com.liftoff.app.ui.inflight` (`NoteDialog`) and the Mission Control text-field style

**List.**
- History is newest first and grouped by week, under heads like 'WEEK OF OCT 5'.
- Each row shows the date, the type (run or lift), the plan title or focus, and whether the sortie landed or was scrubbed. A scrubbed row shows its reason.
- An empty history shows a short empty state.
- The list updates live from Room.

**Detail view.** Tapping a row opens its detail view. Both the system back and an on-screen back return to the list.
- **Lift:** each exercise with every set's planned and actual values. Deviations are shown in red. Skipped, not-done and added sets are marked. Show the exercise notes and the sortie note.
- **Run:** the plan summary and the recorded distance and duration, in the configured units.
- **Scrubbed sortie:** the reason, and any sets checked before the scrub.
- A landed sortie can't be edited, except for its sortie note, which can be edited here (§5.1).

Put the history and detail derivation in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose UI tests are not required, and no Compose test may type into or focus a text field inside a Dialog, because that hangs under Robolectric. Every wait in a test needs a timeout. Don't change the Room schema. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: In-Flight, the Launchpad and Mission screens, the shell and its back behaviour, `MissionManager` and the domain tests, Mission Control, the coach package, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Landed tab lists history newest first and grouped by week, with a working detail view for a landed lift, a landed run and a scrubbed sortie.
- Robolectric tests cover:
  - grouping and ordering across two weeks
  - the detail of a lift with deviations, skipped, not-done and added sets
  - a run's recorded values
  - a scrubbed sortie's reason and partial sets
  - editing the sortie note of a landed sortie
- ARCHITECTURE.md marks Landed as built under M5.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
