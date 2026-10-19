# Plan

## Approach

The data half is already merged (`c183c1c`, PR #27): `exportDatabase(db, out, clock)` and
`importDatabase(db, input, manager)` with `ImportResult.Refused(message)` / `Restored(missions, sorties)`,
already refusing a non-empty database and already calling `onAppOpen()` after a restore. So this run is a
UI task: a plain-Kotlin `BackupViewModel` in `com.liftoff.app.ui.control` that holds Idle / Busy /
Succeeded / Failed and takes its I/O as injectable `suspend` lambdas plus an injectable dispatcher, and a
BACKUP `SectionCard` in `MissionControlScreen` that owns the two system file pickers and hands the picked
file's stream to that view model. That fits the codebase rather than adding to it: Mission Control already
holds its state in plain Kotlin state holders with no Android imports (`MissionControlViewModel`,
`EquipmentViewModel`), `SectionCard` / `InkRuledListRow` / the theme buttons already exist, `AppContainer`
already exposes `database` and `missionManager`, and the shell's Mission Control call site already has the
container in scope. Because `importDatabase` writes through Room DAOs inside one transaction and Room's
invalidation tracker then re-emits, the other tabs need no new wiring — the screen only has to be handed the
same `LiftoffDatabase` instance they observe.

## Files

- **new `app/src/main/java/com/liftoff/app/ui/control/BackupViewModel.kt`** — the brief's "busy/result logic
  in plain Kotlin outside Compose": the state (Idle / Busy naming which action is running / Succeeded /
  Failed, each with the message to show), an export action and an import action that take an opener lambda
  returning the stream to write or read, refuse to start while work is running, publish Busy, run the data
  call under the injected dispatcher, and publish the result. No `android.*` imports, so a plain JUnit test
  can drive it with fake work.
- **new `app/src/main/java/com/liftoff/app/ui/control/BackupSection.kt`** — the BACKUP card's content: the
  Export and Import rows drawn from the state, the exclusion line, the busy/result row, and the
  disabled-capable button this screen needs (see Risks). `SectionCard` and the other `internal` helpers in
  `MissionControlScreen.kt` are already visible to it.
- **`app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`** — gain `database` and
  `missionManager` parameters plus a defaulted seam that lets a test supply its own `BackupViewModel`;
  compose the two SAF launchers (`CreateDocument("application/json")` with the dated default name,
  `OpenDocument`), pass openers that resolve the picked Uri to a stream through `contentResolver`, and draw
  the BACKUP card last, after EQUIPMENT.
- **`app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`** — pass `database` and `missionManager` from the
  `container` already in scope at the Mission Control call site (:111). Nothing else in the shell changes.
- **`app/src/test/java/com/liftoff/app/ui/control/BackupViewModelTest.kt`** — new plain JUnit class for the
  busy/result behaviour with fake work and a named worker dispatcher.
- **`app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt`** — `launchScreen` passes the
  new parameters (it already builds an in-memory `LiftoffDatabase`) and can take a supplied view model;
  `"BACKUP"` joins the heads list in `showsEverySectionHeadInOrder`; the `assertDoesNotExist("Export")` line
  in `showsNoUnbuiltPlaceholders` is removed, since it was written to prove Export did not exist; the content
  is unmounted before `tearDown` closes the database. New tests for the BACKUP head, the two buttons, the
  exclusion line and the success/failure rows.
- **`ARCHITECTURE.md`** — M1's status cell (:80) currently reads `Done — test connection comes later; the
  export and import data layer is built`, which still frames the actions as missing; it becomes
  `Done — test connection comes later; export and import are built`. M5's cell (:84) drops its last two
  clauses (`the export and import data layer is built; its Mission Control actions come next`) for
  `export and import are built`. Runner `verify.sh` scripts here read these cells clause by clause, so the
  clause that names export and import must carry a built word and no deferral word; a deferral in a clause
  that names neither (M1's "test connection comes later") is fine.

## Order

1. `BackupViewModel.kt` and `BackupViewModelTest.kt` — the state, the busy guard, the two actions, the
   injected dispatcher. Nothing Compose; `:app:compileDebugKotlin` and this one test class pass.
2. `BackupSection.kt` — the BACKUP card's rows, buttons and result line, rendered from a state the test can
   hand it. Build passes.
3. `MissionControlScreen.kt` — the new parameters, the defaulted view-model seam, the two SAF launchers and
   the dated default file name, with the BACKUP card drawn after EQUIPMENT; in the same step, the edits the
   new UI forces in `MissionControlScreenTest.kt` (`launchScreen` passes the new parameters, `"BACKUP"` joins
   the heads list, the `assertDoesNotExist("Export")` line goes) so the class stays green. Build passes.
4. `LiftoffShell.kt` — pass `database` and `missionManager` at the call site. `assembleDebug` passes.
5. The new BACKUP tests in `MissionControlScreenTest.kt` — head, buttons enabled, exclusion line, success and
   failure rows, buttons not enabled while busy — with the content unmounted before `tearDown` closes the
   database. This is done after the UI exists, so it is the last code step.
6. `ARCHITECTURE.md` M1 and M5 cells.
7. The whole gate once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, run detached under
   `app/build/` if it outlasts a shell command, and read the counts out of the JUnit XML.

## Testing

- **Backup work leaves the calling thread** — plain JUnit: run the view model on a named worker dispatcher
  and assert the fake export/import recorded running on that thread, not on the test thread.
- **Idle → Busy → Succeeded, message kept** — plain JUnit: a fake that parks until released, so Busy is
  observable, then the success message.
- **Idle → Busy → Failed, the data layer's message kept** — plain JUnit: a fake that throws or refuses.
- **A second action while work is running is refused and changes nothing** — plain JUnit, holding the worker
  thread with a latch so the second call must hit the guard, and asserting the first result survives.
- **A cancelled picker starts nothing** — plain JUnit: an opener returning null leaves the state exactly as
  it was, including any earlier result.
- **The BACKUP head, Export and Import present and enabled, and the exclusion line on screen** — Robolectric
  Compose test, no clicks on the buttons.
- **A success row and a failure row drawn from the state** — Robolectric: drive the injected view model with
  a stream, `waitForIdle()`, assert the line; `waitForIdle()` again and assert it is still there, which is
  what proves the result survives recomposition.
- **Both buttons are not enabled while work is running** — Robolectric, `assertIsNotEnabled()` paired with the
  still-enabled mirror in the same test, so neither direction passes vacuously.
- **Existing behaviour** — `MissionControlScreenTest`'s settings and equipment tests, `MissionControlViewModelTest`,
  `EquipmentViewModelTest`, `LiftoffShellTest` and the four data-layer test classes run unchanged in the final
  gate; the only changes to the screen test are the ones named in Files, and the PR says that its heads list and
  its Export-placeholder assertion were updated to match the new section.
- No test clicks a system picker, and every wait in a test has a timeout.

## Risks

- **No button in `ui.theme` can be disabled, and `ui.theme` is out of this brief's scope** — `UnderlinedTextButton`
  (:155), `InkButton` (:92) and `PrimaryButton` (:28) all take `onClick` only. The work builds one file-private
  underlined text button in `ui.control` mirroring `UnderlinedTextButton`: a `Box` whose `clickable` carries
  `interactionSource`, `indication = null`, `role = Role.Button` and the `enabled` flag, around a
  `LiftoffType.textButton()` label whose underline is drawn in `drawBehind` from the text baseline, `Ink` normally
  and `Muted` when disabled. `clickable(..., enabled = false, role = Role.Button)` really sets `Enabled = false`.
  Wrapping `InkButton` and adding `semantics { disabled() }` does not work — its inner `clickable` is its own
  merged node — and building the button from `OffsetShadowBox` / `ButtonShape` would give it the big red
  Launch/Land look the Decisions reject.
- **Theme buttons uppercase their labels**, so on-screen text is `EXPORT` / `IMPORT`; assertions must match the
  uppercased form, and the `showsNoUnbuiltPlaceholders` helper's `ignoreCase = false` is why its Export line has
  to go rather than be reworded.
- **Clicking Export or Import opens the system picker and the test then waits on something that never arrives.**
  The picker is asserted present and enabled; behaviour is driven by calling the view model with a stream.
- **The screen must be handed `AppContainer`'s single `LiftoffDatabase`.** A second instance would import into a
  database no tab observes, and the brief's "the other tabs show the restored data" would silently fail. The
  shell already has that instance at the call site.
- **`MissionControlScreenTest.tearDown` closes the database while the content is still mounted**, which is the
  known "connection pool has been closed" flake in screens that observe Room; the new tests make it likelier, so
  the content goes behind a flag that `tearDown` clears first.
- **`neverSaysSession` (:378) only scans a fixed list of texts**, so it will not catch new wording — the new
  strings must still avoid the word "session", which `CLAUDE.md` requires anyway.
- **The previews in `MissionControlScreen.kt` (:470-543) duplicate the section cards** and are guarded by
  `LocalInspectionMode`, so no test runs them; a BACKUP preview is optional and is not a step.
- **A cold gate run can outlast a shell command's limit** on this machine: `mkdir -p app/build`, run detached
  with the log and an exit-status sentinel in `app/build/`, then read the JUnit XML counts rather than
  `BUILD SUCCESSFUL`. A hung run is diagnosed with `jstack` before any code is touched.
- **The data layer is not to be touched.** If a UI need appears to require a change in `DatabaseExport.kt` or
  `DatabaseImport.kt` (for example a progress signal), the work records it as a gap instead of editing them,
  because the brief forbids changing how export and import behave.

## Decisions

**Where BACKUP sits in Mission Control: last, after settings and equipment.** The alternative is first, since a lost phone is the reason the section exists. Settings and equipment are touched every week and the brief's "must not break" list is about them; a backup is rare, and Import can replace the whole database, so putting the section last makes it harder to reach by accident and keeps the existing layout untouched.

**Export and Import are underlined text buttons sitting in ink-ruled rows, like the equipment list's Edit and Deactivate — not the big red Launch/Land button.** The brief says "buttons from `ui.theme`" without saying which. `design/README.md` reserves the primary red button for Launch and Land, and every other action in Mission Control is an underlined text button, so a 72 dp red block in a settings screen would read as a bigger event than the fields around it.

**Both buttons go quiet while a backup action runs, and the row says which one.** The brief asks for a row that shows work is happening but not what the buttons may do meanwhile. Leaving them live lets a second tap open a second picker, or start an import while an import is mid-write. A greyed Export and Import with an "Exporting…" / "Importing…" row is what a person expects from a button that is already doing something.

**Import stays enabled on a phone that already has training, with no confirmation dialog.** The data layer refuses a non-empty database, so the alternative — a "this will replace everything?" step, or greying Import out when the database is not fresh — only adds a click before a refusal that is the design's own guard, and a disabled button hides the reason. The refusal message the data layer already writes is shown as the failure row.

**Cancelling the picker is not an action: no row appears, and the previous result stays.** The brief says a cancel does nothing and that a result row stays until the next action; a cancel is not an action, so it must not wipe a message the owner may still be reading. The alternative — a "Cancelled" row — would replace a result for something that changed nothing.

**The three result rows, worded as: `Exported the whole database.` / `Restored 3 missions and 12 sorties.` / the data layer's own failure message, unchanged.** The alternative for the export line is to name the file the owner picked. SAF lets them rename it in the picker, so the name on screen would not reliably be the name written; the mission and sortie counts are what the data layer actually knows, and its refusal wording is already written for a person to read.

**A failed export may leave a partial file, and the plan does not clean it up.** Streaming straight into the file the picker created is the simple path; the alternative is writing to a scratch file and copying it across only on success. The picker has already created the file, the failure row says the export failed, and re-exporting to the same name replaces it — so the plan accepts the partial file rather than adding a second copy step.

**The default name is the local date only: `liftoff-2026-10-19.json`.** The brief gives this shape. The alternative is a time in the name so two exports in one day are two files; the system picker already asks what to do when the name is taken, and a name the owner has to read is worse than one the picker resolves.

**After an import, Mission Control still shows the phone's current settings, and its equipment list shows the restored equipment.** Equipment lives in Room and is restored; settings and the daemon token live in the preferences file and are deliberately not in the export. The plan adds no reload of settings and no extra warning beyond the exclusion line the brief asks for, because that line is what tells the owner which half of what they see came back.
