# Run landing dialog with distance and duration

Let the user record distance and duration when landing a run. Read DESIGN.md §5.3, the run paragraph of §6, Sortie in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the run In-Flight state and screen in `com.liftoff.app.ui.inflight`. Today Land on a run lands straight away.
- `InFlightViewModel.landRun(distanceText, minutesText, onDone)`, which parses both fields, publishes field errors to `runErrors` and returns false without landing on bad input, and otherwise lands through `MissionManager.land(sortieId, runDistance, runMinutes)` in one transaction. Also `clearRunErrors()`.
- `parseRunEntry` in `ui/inflight/RunLanding.kt`: blank becomes null, and only a positive finite number is accepted
- the themed dialogs `LandConfirmDialog`, `ScrubDialog` and `NoteDialog` in `ui.inflight`, and the Mission Control text-field style (`LabeledTextField` in `ui/control/MissionControlScreen.kt`)
- the distance unit setting (mi or km)

**The dialog.**
- Land on a run that has a plan opens a themed dialog in the style of the existing In-Flight dialogs. No stock M3 visuals.
- It has two optional fields in the Mission Control text-field style, with a decimal keyboard: distance, labelled with the configured unit, and duration in minutes. Both start blank each time the dialog opens.
- The dialog's Land calls `landRun`. A field that isn't a positive number shows its error inline under that field, the dialog stays open and nothing is landed. On success the dialog closes and the screen leaves the way the lift Land does.
- Cancel, Back and tapping outside close the dialog, clear any errors and land nothing.
- Runs get no unchecked-sets confirmation.
- A sortie with no plan still lands without the dialog. Lift landing is unchanged.
- A second tap while the landing is being written is ignored, as on lifts.

Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema. No Compose test may type into or focus a text field inside a Dialog: under Robolectric that never goes idle and the test hangs (see PR #18). Test the behaviour through the view model and the database, and if you add a Compose test, only check what is rendered. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: the run In-Flight screen, the lift In-Flight flow with its notes, Land, the unchecked-sets confirmation and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → Land of a simple run with a distance and a duration works.
- Robolectric tests cover:
  - distance and duration stored on the sortie when it lands
  - both fields left blank and stored as null
  - an invalid distance and an invalid duration, each rejected with the error on the right field and the sortie still IN_FLIGHT
  - a run with no plan landing without the dialog
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
