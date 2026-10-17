# Reopen into an in-flight sortie

Make the app come back to an in-flight sortie after it is closed or killed, and make the In-Flight screen leave once its sortie is no longer in flight. Read DESIGN.md §6 ('Persistence') first. Every behaviour below has been decided, so there is nothing to ask. If some detail is still unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Commit as soon as each part compiles and its tests pass.

Build on what's already in the repo:
- `ShellNav` in `com.liftoff.app.ui` (tab, missionControlOpen, inFlightSortieId, `openInFlight`, `back`, `encode`/`decode`), and `LiftoffShell`, which keeps a `ShellNav` in `rememberSaveable`
- `MainActivity`, which runs `MissionManager.onAppOpen()` each time the app reaches STARTED
- `SortieDao.getInFlight()` and `SortieDao.observeById(id)`
- `InFlightScreen(sortieId, container, onBack)`, `InFlightViewModel` and `InFlightStates` in `com.liftoff.app.ui.inflight`. Land and Scrub already call `onBack` once their write succeeds.

**Opening.**
- A cold start is one where `rememberSaveable` has no saved `ShellNav`. On a cold start, read `getInFlight()` off the main thread. If a sortie is IN_FLIGHT, open its In-Flight screen. If several are IN_FLIGHT, which shouldn't happen, open the one with the highest id. If none is, stay on the Launchpad.
- The Launchpad shows first while that read runs. If the user has already navigated before the read returns, leave them where they are.
- When saved navigation state exists, such as after a rotation or a process restore, it wins as it does today. Nothing new happens.
- Back from In-Flight still goes to the Launchpad, which still offers Resume.

**Leaving.**
- While In-Flight is open, if its sortie becomes anything other than IN_FLIGHT, or no longer exists, the screen calls `onBack` exactly once and returns to the Launchpad. It must not try to act on that sortie. This covers the week-ended rollover when the app returns to the foreground, a restored navigation state that points at a sortie that has since landed, and the sortie being landed or scrubbed in another way.
- A Land or Scrub done on this screen must still leave only once, not twice.

Put the start decision in a plain Kotlin function that takes the saved state, if any, and the IN_FLIGHT sortie ids, so a unit test can drive it. Also put the 'this sortie is no longer in flight' signal in plain Kotlin, in the view model or the state flow, so a Robolectric test can drive it against an in-memory database. Compose UI tests are not required. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status rather than re-running it.

Must not break: In-Flight for lifts and runs, with notes, Land, the unchecked-sets confirmation, the run landing dialog and Scrub, and their tests; the Launchpad and Mission screens; the shell's back behaviour and `ShellNavTest`, `LiftoffShellTest` and `MainActivityTest`; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Unit tests show that the start destination is:
  - In-Flight for the IN_FLIGHT sortie on a cold start
  - the Launchpad on a cold start with none in flight
  - the saved state whenever one exists
- Robolectric tests show that the leave signal fires once when the sortie:
  - is scrubbed by the week-ended rollover (`onAppOpen` with a clock past the week)
  - is landed
  - no longer exists
  and that it doesn't fire while the sortie is still IN_FLIGHT.
- ARCHITECTURE.md marks M4 done and describes `ui` as having a real In-Flight screen.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
