- [ ] Add MissionControlViewModel
  A plain Kotlin state holder in `ui.control` that wraps `SettingsStore`, loads stored values into a draft on init, validates and writes each field (host, port, token, workspace path, pattern chips with toggle/add/remove within 1–7 limits, sortie length, history window, weight unit, distance unit, generate run plans, objectives, constraints), exposes `StateFlow<MissionControlState>`, and keeps all Android imports out.

- [ ] Add MissionControlViewModelTest
  JVM test in `ui.control` that builds a real `SettingsStore` on a temp DataStore, exercises loading stored values, saving each field type, rejecting invalid input with the error set and store unchanged, surviving restart, draft-not-clobbered, and pattern editor 1–7 limits. Run `bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.control.*"` to verify.

- [ ] Build Mission Control screen, wire LiftoffShell, update shell tests
  Replace the placeholder in `MissionControlScreen.kt` with the full scrollable section-card layout (Connection, Coach, Mission pattern chips + numeric fields, Units segments, Runs checkbox, Objectives and Constraints multi-line fields), all using existing theme parts. Wire `SettingsStore` and scope through `LiftoffShell.kt`. Update `LiftoffShellTest.kt` to replace `"Settings will appear here."` assertions with `onNodeWithText("CONNECTION")`.

- [ ] Run full build gate
  Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` and confirm it passes with no failures.
