# Land and scrub from In-Flight

Let the owner finish a sortie from the In-Flight screen, by landing it or scrubbing it. Read DESIGN.md §5.1 and §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first. The mockup is static, and 1 px in it equals 1 dp. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

Build on what's already in the repo:
- the In-Flight screen, state, notes and actions in `com.liftoff.app.ui.inflight`: `InFlightScreen(sortieId, container, onBack)`, `InFlightStates`, `InFlightViewModel`, and the themed note dialog in `NoteDialog.kt`. The bottom area is a 3 dp ink rule over a cream footer, which today holds only a Back text button. `InFlightScreen.kt` is large, so change only what this brief needs, and put new dialogs in their own files.
- `MissionManager` in `com.liftoff.app.data`, reached through `AppContainer`:
  - `land(sortieId)` sets the sortie LANDED with its time, marks open sets not done, advances to the next sortie and prepares its plan, and closes the Mission after the last one
  - `scrub(sortieId, reason)` keeps the checked sets and advances
- the theme: `InkButton` (ink with a red offset shadow), `UnderlinedTextButton`, `LiftoffIcons` (flag), the themed dialog style the In-Flight stepper and note dialogs use, and the Mission Control text-field style (`internal` composables in `com.liftoff.app.ui.control`)
- the shell (`ShellNav`, `LiftoffShell`), where `onBack` from In-Flight already returns to the Launchpad

**Land.**
- A 64 dp ink Land button with the flag glyph and a red offset shadow, full width in the bottom area as in the mockup. It replaces the Back text button. System back still leaves without landing, and the Launchpad keeps offering Resume.
- If any sets are still open, ask for confirmation first in a themed dialog that says how many (for example '3 sets unchecked. They will be recorded as not done.'). If none are open, land straight away.
- Landing goes through `MissionManager.land` and then returns to the Launchpad. The Launchpad then shows the next sortie, or the closed state after the last one.
- A sortie with no checklist (a run) can be landed too, with no confirmation. A later brief adds run distance and duration.

**Scrub.**
- An underlined Scrub text action in the bottom area. It opens a themed confirmation dialog with an optional reason. The reason field uses the Mission Control text-field style, not a stock M3 field. Build the dialog so the Launchpad can reuse it later.
- Scrubbing goes through `MissionManager.scrub`, keeps the sets already checked, then returns to the Launchpad.
- A blank or whitespace-only reason is stored as null. Today the domain `scrub` drops only null or empty reasons, so fix it to treat blank reasons the same way.

**Robustness.**
- A double tap must not land twice or crash.
- If `MissionManager` rejects an operation (for example, because the sortie is no longer in flight), the screen shows a short message instead of crashing.
- Expose the number of open sets from the state, so the confirmation logic can be tested without Compose.
- Every wait in a test needs a timeout.

Put the logic in plain Kotlin that a Robolectric test can drive against an in-memory database. Compose tests that open a Dialog are optional. Nothing uses the network. Don't change the Room schema.

Must not break: the checklist, the notes and their tests, the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- Launch → log → Land works for a seeded lift plan, and an in-flight sortie can be scrubbed from In-Flight.
- Robolectric tests cover:
  - the open-set count deciding whether confirmation is needed
  - landing with every set checked, and landing with unchecked sets, which are then recorded as not done
  - the sortie being LANDED with a landed time
  - the next sortie becoming current (a run under RLRLR gets its simple 'Run' plan), and the Mission closing after the last sortie
  - scrub from In-Flight keeping the checked sets and storing the reason, with a whitespace-only reason stored as null
  - a second land call being rejected without a crash
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
