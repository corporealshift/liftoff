# Launchpad scrub dialog and draft controls cleanup

Fix two small drifts on the Launchpad. Everything in this brief has been decided, so there is nothing to ask. If some detail is still unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Commit as soon as each part compiles and its tests pass. Change only files in `com.liftoff.app.ui.launchpad` and their tests. If the themed dialog needs to be visible from the Launchpad package, you may also change that dialog's visibility.

Build on what's already in the repo:
- `LaunchpadScreen.kt`, `LaunchpadViewModel.kt` and `LaunchpadState.kt` in `com.liftoff.app.ui.launchpad`
- the themed scrub dialog in `com.liftoff.app.ui.inflight.ScrubDialog.kt`: `ScrubDialog(sortieNumber, onConfirm, onDismiss)` and `ScrubDialogContent`, both `internal` and used by In-Flight
- the In-Flight scrub path in `InFlightViewModel`, which trims the reason and stores blank text as null

**1. Scrub dialog.** `LaunchpadScreen.kt` has its own `private fun ScrubDialog` that uses a stock M3 `OutlinedTextField` with a floating label. Delete it and have the Launchpad show the In-Flight `ScrubDialog` instead, with the same sortie number the Launchpad's dialog shows today. Both are called `ScrubDialog`, so watch the import. In `LaunchpadViewModel`, trim the scrub reason and store blank or whitespace-only text as null, the same rule In-Flight uses. Interior line breaks are kept. Scrubbing from the Launchpad otherwise works as it does today.

**2. Draft −/+ controls.** In the draft state the pattern −/+ controls are drawn twice: once under the chips (`DraftPatternChips`) and once in the bottom bar (`DraftActions`, with the 'N sorties' label). Keep the bottom-bar set, with its 'N sorties' label, and remove the set under the chips. The − button is disabled at 1 sortie and the + button at 7. The view model keeps its own limit checks. Confirm and the chips keep working as they do now.

No Compose test may type into or focus a text field inside a Dialog, because under Robolectric that never goes idle and the test hangs. Test the reason handling through the view model and the database. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status rather than re-running it.

Must not break: In-Flight's own use of `ScrubDialog` and its tests (`LandScrubDialogTest`, `InFlightLandScrubTest`); the other Launchpad states and `LaunchpadStatesTest`; the Mission screen; `MissionManager` and the domain tests; and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- No stock `OutlinedTextField` remains in `com.liftoff.app.ui.launchpad`, and the Launchpad's private ScrubDialog is gone.
- Robolectric tests show that a Launchpad scrub stores `'  sore knee  '` as `'sore knee'`, and stores `'   '` as null.
- Tests show that the draft's controls stop at 1 and at 7 sorties, and that the draft draws only one −/+ pair.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
