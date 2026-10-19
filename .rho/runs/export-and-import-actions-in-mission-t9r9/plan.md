# Plan

## Approach

The data half is already merged (`c183c1c`, PR #27). It provides `exportDatabase(db, out, clock)`, which returns `Unit` and throws on an I/O error, and `importDatabase(db, input, manager)`, which returns `ImportResult.Refused(message)` or `Restored(missions, sorties)`. Import already refuses a non-empty database and already calls `onAppOpen()` after a restore. So this run is a UI task with two parts:

- A plain-Kotlin `BackupViewModel` in `com.liftoff.app.ui.control`. It holds Idle / Busy / Succeeded / Failed and takes its I/O as injectable `suspend` lambdas plus an injectable dispatcher.
- A BACKUP `SectionCard` in `MissionControlScreen`. It owns the two system file pickers and hands the picked file to that view model.

This follows patterns the codebase already has rather than adding new ones:
- Mission Control already keeps its state in plain Kotlin state holders with no Android imports (`MissionControlViewModel`, `EquipmentViewModel`).
- `SectionCard`, `InkRuledListRow` and the theme buttons already exist.
- `AppContainer` already exposes `database` and `missionManager`.
- The shell's Mission Control call site already has the container in scope.

`importDatabase` writes through Room DAOs inside one transaction, and Room's invalidation tracker then re-emits. So the other tabs need no new wiring. The screen only has to be handed the same `LiftoffDatabase` instance they observe.

## Files

- **new `app/src/main/java/com/liftoff/app/ui/control/BackupViewModel.kt`**: the brief's "busy/result logic in plain Kotlin outside Compose".
  - **State:** Idle; Busy, naming which action is running; Succeeded; Failed. Each carries the message to show.
  - **Actions:** an export action and an import action. Each takes a nullable opener.
    - A null opener means the picker was cancelled. The action returns without touching state.
    - Otherwise the action refuses to start while work is running, publishes Busy, and then on the injected dispatcher does three things: calls the opener, closes the stream with `use` after the data call, and publishes the result.
  - **Errors:**
    - A `Refused` result becomes Failed with the data layer's message, unchanged.
    - An exception from the opener or the data call (`IOException`, `SecurityException`, a null stream) becomes Failed with this app's own short wording (see Decisions).
    - `CancellationException` is rethrown, never shown as a failure.
  - No `android.*` imports, so a plain JUnit test can drive it with fake work.
- **new `app/src/main/java/com/liftoff/app/ui/control/BackupSection.kt`**: the BACKUP card's content. It holds the Export and Import rows drawn from the state, the exclusion line, the busy/result row, and the disabled-capable button this screen needs (see Risks). `SectionCard` and the other `internal` helpers in `MissionControlScreen.kt` are already visible to it.
- **`app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`**:
  - Add `database` and `missionManager` parameters, plus a defaulted seam that lets a test supply its own `BackupViewModel`.
  - Build that view model on the `scope` the screen is already given.
  - Add the two SAF launchers: `CreateDocument("application/json")` with the dated default name, and `OpenDocument`.
  - A null Uri (a cancelled picker) passes a null opener.
  - Otherwise pass an opener that resolves the Uri through `contentResolver`. Export opens it in `"wt"` mode, so writing over an existing file truncates it rather than leaving the old file's tail behind. Import uses `openInputStream`.
  - Draw the BACKUP card last, after EQUIPMENT.
- **`app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`**: pass `database` and `missionManager` from the `container` already in scope at the Mission Control call site (:111). Nothing else in the shell changes.
- **`app/src/test/java/com/liftoff/app/ui/control/BackupViewModelTest.kt`**: a new plain JUnit class for the busy/result behaviour, using fake work and a named worker dispatcher.
- **`app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt`**:
  - `launchScreen` passes the new parameters. It already builds an in-memory `LiftoffDatabase`; a `MissionManager(database, store)` is built beside it. It can also take a supplied view model.
  - `"BACKUP"` joins the heads list in `showsEverySectionHeadInOrder`.
  - The `assertDoesNotExist("Export")` line in `showsNoUnbuiltPlaceholders` is removed, since it was written to prove Export did not exist.
  - The content is unmounted before `tearDown` closes the database.
  - New tests cover the BACKUP head, the two buttons, the exclusion line and the success/failure rows.
- **`ARCHITECTURE.md`**:
  - M1's status cell (:80) currently reads `Done — test connection comes later; the export and import data layer is built`, which still frames the actions as missing. It becomes `Done — test connection comes later; export and import are built`.
  - M5's cell (:84) drops its last two clauses (`the export and import data layer is built; its Mission Control actions come next`) and ends with `export and import are built` instead.
  - Runner `verify.sh` scripts here read these cells clause by clause. So the clause that names export and import must carry a "built" word and no deferral word. A deferral in a clause that names neither (M1's "test connection comes later") is fine.

## Order

1. `BackupViewModel.kt` and `BackupViewModelTest.kt`: the state, the busy guard, the null-opener cancel, the exception mapping, the two actions and the injected dispatcher. Nothing Compose. `:app:compileDebugKotlin` and this one test class pass.
2. `BackupSection.kt`: the BACKUP card's rows, buttons and result line, rendered from a state the test can hand it. The build passes.
3. `MissionControlScreen.kt`: the new parameters, the defaulted view-model seam, the two SAF launchers with `"wt"` export mode, and the dated default file name, with the BACKUP card drawn after EQUIPMENT. In the same step, make the edits the new UI forces in `MissionControlScreenTest.kt` so the class stays green: `launchScreen` passes the new parameters, `"BACKUP"` joins the heads list, and the `assertDoesNotExist("Export")` line goes. The build passes.
4. `LiftoffShell.kt`: pass `database` and `missionManager` at the call site. `assembleDebug` passes.
5. The new BACKUP tests in `MissionControlScreenTest.kt`: the head, the buttons enabled, the exclusion line, the success and failure rows, and the buttons not enabled while busy. The content is unmounted before `tearDown` closes the database. This comes after the UI exists, so it is the last code step.
6. `ARCHITECTURE.md`: the M1 and M5 cells.
7. The whole gate, once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. If it outlasts a shell command, run it detached under `app/build/`. Read the counts out of the JUnit XML.

## Testing

- **Backup work leaves the calling thread** (plain JUnit). Run the view model on a named worker dispatcher. Assert that the fake opener and the fake export/import both recorded running on that thread, not on the test thread.
- **Idle → Busy → Succeeded, message kept** (plain JUnit). Use a fake that parks on a latch until released, so Busy is observable, then check the success message. Every latch wait has a timeout, and the latch is released in `finally`.
- **Idle → Busy → Failed, the data layer's message kept** (plain JUnit). Use a fake that returns `Refused`, and assert its message verbatim. Use a second fake that throws `IOException`, and assert this app's failure wording.
- **A second action while work is running is refused and changes nothing** (plain JUnit). Hold the worker thread with a latch so the second call must hit the guard. Assert that the first result survives.
- **A cancelled picker starts nothing** (plain JUnit). A null opener leaves the state exactly as it was, including any earlier result.
- **The BACKUP head, Export and Import present and enabled, and the exclusion line on screen** (Robolectric Compose test). No clicks on the buttons.
- **A success row and a failure row drawn from the state** (Robolectric).
  - Drive the injected view model with an in-memory stream.
  - The work runs on another dispatcher and `waitForIdle()` does not wait for it, so wait for the row with `composeRule.waitUntil(timeoutMillis = …)`.
  - Then `waitForIdle()` and assert the row is still there. That proves the result survives recomposition.
- **Both buttons are not enabled while work is running** (Robolectric). Pair `assertIsNotEnabled()` with the still-enabled mirror in the same test, so neither direction passes vacuously. Release the parked fake before the test ends.
- **Existing behaviour.** These run unchanged in the final gate: `MissionControlScreenTest`'s settings and equipment tests, `MissionControlViewModelTest`, `EquipmentViewModelTest`, `LiftoffShellTest` and the four data-layer test classes. The only changes to the screen test are the ones named in Files. The PR says that its heads list and its Export-placeholder assertion were updated to match the new section.
- No test clicks a system picker, and every wait in a test has a timeout.

## Risks

- **No button in `ui.theme` can be disabled, and `ui.theme` is out of this brief's scope.** `UnderlinedTextButton` (:155), `InkButton` (:92) and `PrimaryButton` (:28) all take `onClick` only.
  - The work builds one file-private underlined text button in `ui.control`, mirroring `UnderlinedTextButton`. It is a `Box` whose `clickable` carries `interactionSource`, `indication = null`, `role = Role.Button` and the `enabled` flag. Inside is a `LiftoffType.textButton()` label whose underline is drawn in `drawBehind` from the text baseline: `Ink` normally, `Muted` when disabled.
  - `clickable(..., enabled = false, role = Role.Button)` really does set `Enabled = false`.
  - Wrapping `InkButton` and adding `semantics { disabled() }` does not work, because its inner `clickable` is its own merged node.
  - Building the button from `OffsetShadowBox` / `ButtonShape` would give it the big red Launch/Land look that the Decisions reject.
- **Theme buttons uppercase their labels**, so the on-screen text is `EXPORT` / `IMPORT`. Assertions must match the uppercased form. The `showsNoUnbuiltPlaceholders` helper uses `ignoreCase = false`, which is why its Export line has to go rather than be reworded.
- **The data layer does not catch I/O errors.** `exportDatabase` throws from `out.write`, and `importDatabase` throws from `readBytes()`. Without the view model's catch, a full disk or a revoked document would crash the app, or leave the screen stuck on Busy.
- **`openOutputStream(uri)` defaults to mode `"w"`, which some providers do not truncate.** Writing over a longer existing backup would leave trailing bytes and an unreadable file, so export opens with `"wt"`.
- **Clicking Export or Import opens the system picker, and the test then waits on something that never arrives.** The picker is only asserted present and enabled. Behaviour is driven by calling the view model with a stream.
- **The screen must be handed `AppContainer`'s single `LiftoffDatabase`.** A second instance would import into a database no tab observes, and the brief's "the other tabs show the restored data" would silently fail. The shell already has that instance at the call site.
- **`MissionControlScreenTest.tearDown` closes the database while the content is still mounted.** That is the known "connection pool has been closed" flake in screens that observe Room, and the new tests make it likelier. So the content goes behind a flag that `tearDown` clears first.
- **`neverSaysSession` (:378) only scans a fixed list of texts**, so it will not catch new wording. The new strings must still avoid the word "session", which `CLAUDE.md` requires anyway.
- **The previews in `MissionControlScreen.kt` (:470-543) duplicate the section cards** and are guarded by `LocalInspectionMode`, so no test runs them. A BACKUP preview is optional and is not a step.
- **A cold gate run can outlast a shell command's limit** on this machine. Run `mkdir -p app/build`, then run the gate detached with its log and an exit-status sentinel in `app/build/`. Read the JUnit XML counts rather than `BUILD SUCCESSFUL`. Diagnose a hung run with `jstack` before touching any code.
- **The data layer is not to be touched.** If a UI need seems to require a change in `DatabaseExport.kt` or `DatabaseImport.kt` (for example a progress signal), record it as a gap instead of editing those files, because the brief forbids changing how export and import behave.

## Decisions

**Added by review: failures the data layer does not word itself get this app's own short message.** The brief says a failure shows "the data layer's message". The only failures that carry such a message are import's `Refused` cases, and those are shown unchanged. An I/O error during export, an unreadable picked file, or a provider that hands back no stream throws instead. These show `Export failed — the file could not be written.` / `Import failed — the file could not be read.` The alternative was to show the raw exception text, which names Java classes and provider internals the owner cannot act on.

**Added by review: leaving Mission Control mid-action does not cancel it.** The work runs on the shell's scope, which outlives the screen. So an import that has started still finishes, and its rows still reach the other tabs. The result row lives in the screen's view model and is not shown on return. The alternatives were cancelling on exit, which could abandon an export half-written, or hoisting backup state into the shell, which is more wiring than this brief asks for.

**Where BACKUP sits in Mission Control: last, after settings and equipment.** The alternative is first, since a lost phone is the reason the section exists. But settings and equipment are touched every week, and the brief's "must not break" list is about them. A backup is rare, and Import can replace the whole database. Putting the section last makes it harder to reach by accident and keeps the existing layout untouched.

**Export and Import are underlined text buttons sitting in ink-ruled rows, like the equipment list's Edit and Deactivate — not the big red Launch/Land button.** The brief says "buttons from `ui.theme`" without saying which. `design/README.md` reserves the primary red button for Launch and Land, and every other action in Mission Control is an underlined text button. A 72 dp red block in a settings screen would read as a bigger event than the fields around it.

**Both buttons go quiet while a backup action runs, and the row says which one.** The brief asks for a row that shows work is happening, but does not say what the buttons may do meanwhile. Leaving them live lets a second tap open a second picker, or start an import while an import is mid-write. A greyed Export and Import with an "Exporting…" / "Importing…" row is what a person expects from a button that is already doing something.

**Import stays enabled on a phone that already has training, with no confirmation dialog.** The data layer refuses a non-empty database. The alternatives were a "this will replace everything?" step, or greying Import out when the database is not fresh. The first only adds a click before a refusal that is the design's own guard, and a disabled button hides the reason. The refusal message the data layer already writes is shown as the failure row.

**Cancelling the picker is not an action: no row appears, and the previous result stays.** The brief says a cancel does nothing and that a result row stays until the next action. A cancel is not an action, so it must not wipe a message the owner may still be reading. The alternative, a "Cancelled" row, would replace a result for something that changed nothing.

**The three result rows are worded `Exported the whole database.` / `Restored 3 missions and 12 sorties.` (singular for a count of 1) / the data layer's own failure message, unchanged.** The alternative for the export line was to name the file the owner picked. SAF lets them rename it in the picker, so the name on screen would not reliably be the name written. The mission and sortie counts are what the data layer actually knows, and its refusal wording is already written for a person to read.

**A failed export may leave a partial file, and the plan does not clean it up.** Streaming straight into the file the picker created is the simple path. The alternative was writing to a scratch file and copying it across only on success. The picker has already created the file, the failure row says the export failed, and re-exporting to the same name replaces it, since export opens with `"wt"`. So the plan accepts the partial file rather than adding a second copy step.

**The default name is the local date only: `liftoff-2026-10-19.json`.** The brief gives this shape. The alternative was a time in the name, so two exports in one day are two files. But the system picker already asks what to do when the name is taken, and a name the owner has to read is worse than one the picker resolves.

**After an import, Mission Control still shows the phone's current settings, and its equipment list shows the restored equipment.** Equipment lives in Room and is restored. Settings and the daemon token live in the preferences file and are deliberately not in the export. The plan adds no reload of settings and no warning beyond the exclusion line the brief asks for, because that line tells the owner which half of what they see came back.
