# In-Flight notes on screen, reusing the finished note dialog

Show and edit In-Flight notes on screen: an optional note per exercise, and one note for the whole sortie (DESIGN.md §6). Read DESIGN.md §6, the 'In-Flight' section of `design/README.md` and `design/screens/in-flight.html` first. The mockup is static, and 1 px in it equals 1 dp. The data side is already done, so this brief is UI only and deliberately small. Earlier attempts were cut off by session interruptions, not by problems with the work. Commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Reuse the finished dialog.** A previous run already wrote and tested the note dialog. It is in commit `2cb0ed6` on the local branch `nabu/in-flight-notes-on-screen-with-the-note-74a3`, in two files:
- `app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt`, with the `internal` `NoteDialog(title, initial, onSave, onDismiss)` wrapping `NoteDialogContent(...)`. It draws a paper card with a 2 dp ink border, a `LabeledMultiLineTextField`, and Save and Cancel.
- `app/src/test/java/com/liftoff/app/ui/inflight/NoteDialogTest.kt`

Bring both files in first, for example with `git show 2cb0ed6:<path>`, and commit them. If that commit can't be reached, write the same small dialog again: a themed Dialog with a paper card and a 2 dp ink border, a title, one multi-line field pre-filled with the current note, and Save and Cancel. Don't use AlertDialog or an OutlinedTextField with a floating label.

Build on what's already in the repo:
- `InFlightViewModel` in `com.liftoff.app.ui.inflight` has `saveExerciseNote(id, text)` and `saveSortieNote(text)`. Both already trim the text, store blank text as null, and write straight to Room.
- The In-Flight state already carries each card's `note` (`ExerciseCard.note`) and the sortie note (`sortieNote` on both `InFlightState.Ready` and `InFlightState.NoChecklist`).
- `InFlightScreen.kt` is about 800 lines long. Change only these composables: `CardFooter` (its 'Note' `FooterCell` has `enabled = false`), `FullCard`, `ReadyContent`, `NoChecklistContent`, and the top-level `InFlightScreen`, which already shows `StepperDialog`. Don't restructure the rest.

**Exercise note.**
- The Note cell on each card becomes enabled. It opens the dialog for that exercise, pre-filled, and Save goes through `saveExerciseNote`.
- When a card's note isn't null, the card shows it as a short muted line (at most two lines, then an ellipsis), so the owner can see a note is there.

**Sortie note.**
- Add an underlined 'Sortie note' text action just under the header bands. It is always visible, on both the checklist and the no-checklist states. Keep it out of the bottom footer, because a later brief puts the full-width Land button there.
- It opens the same dialog, and Save goes through `saveSortieNote`.
- Both states show a saved sortie note as a short muted line near the action. The no-checklist state shows only 'No checklist for this sortie.' today, so add the line there too.

Nothing uses the network. Don't change the Room schema or the view model's note rules.

**Tests.** `InFlightScreenTest` already drives the screen with Compose UI tests on Robolectric, using an in-memory database and a `Dispatchers.Unconfined` scope; follow its style. Every wait needs a timeout. `FooterCell`, `PrimaryButton` and `UnderlinedTextButton` show their labels upper-cased. Keep the screen tests few:
- a seeded exercise note shows as the muted line on its card, with no dialog opened
- a seeded sortie note shows on the no-checklist state
- tapping the Note cell opens the dialog pre-filled, and Save stores the note
- tapping 'Sortie note' on a sortie with no checklist opens the dialog, and Save stores it
If typing into the field inside a Dialog is unreliable under Robolectric, save the pre-filled text unchanged and assert that it reaches Room; NoteDialogTest already covers editing. If opening a Dialog from the screen hangs Robolectric even without typing, keep the note-line tests, assert that the actions are present and enabled, and record that in the plan's decisions. Don't let a flaky test hold up the run.

Must not break: the checklist and its tests (`InFlightScreenTest`, `InFlightStatesTest`, `InFlightViewModelTest`), the Launchpad and Mission screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell, and the build gate. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The Note cell is enabled and opens the themed dialog, pre-filled. Save stores the note, and the muted line appears on the card.
- The 'Sortie note' action works on a lift sortie and on a sortie with no checklist, and both states show a saved sortie note.
- The tests above pass, along with NoteDialogTest.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
