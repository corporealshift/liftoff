- [x] Add MissionControlViewModel
  A plain Kotlin state holder in `ui.control` that wraps `SettingsStore`, loads stored values into a draft on init, validates and writes each field (host, port, token, workspace path, pattern chips with toggle/add/remove within 1–7 limits, sortie length, history window, weight unit, distance unit, generate run plans, objectives, constraints), exposes `StateFlow<MissionControlState>`, and keeps all Android imports out.

- [x] Add MissionControlViewModelTest
  JVM test in `ui.control` that builds a real `SettingsStore` on a temp DataStore, exercises loading stored values, saving each field type, rejecting invalid input with the error set and store unchanged, surviving restart, draft-not-clobbered, and pattern editor 1–7 limits. Run `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.control.*"` to verify.

- [x] Build Mission Control screen, wire LiftoffShell, update shell tests
  Replace the placeholder in `MissionControlScreen.kt` with the full scrollable section-card layout (Connection, Coach, Mission pattern chips + numeric fields, Units segments, Runs checkbox, Objectives and Constraints multi-line fields), all using existing theme parts. Wire `SettingsStore` and scope through `LiftoffShell.kt`. Update `LiftoffShellTest.kt` to replace `"Settings will appear here."` assertions with `onNodeWithText("CONNECTION")`.

- [x] Run full build gate
  Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` and confirm it passes with no failures.

## Blockers from the final review

- [ ] Lay out the 'Generate run plans' row so the label sits beside the check box instead of on top of it
  In MissionControlScreen.kt, GenerateRunPlansCheckbox (around lines 430-459) puts the 44 dp check box, a Spacer and the 'Generate run plans' Text inside a Box with contentAlignment = CenterStart. A Box stacks its children on top of each other, and a Spacer does nothing inside a Box. So the label is drawn over the check box at the left edge, and the check mark and red fill are hidden under the text. The plan's decision says the label sits beside the box. Change the outer Box to a Row with verticalAlignment = CenterVertically, keep the clickable on the Row, and the 12 dp spacer will then work. The test tappingGenerateRunPlansRowTogglesSetting can't catch this because it only checks that a tap toggles the setting, not the layout.
