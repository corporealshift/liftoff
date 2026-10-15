# Plan: In-Flight notes on screen, reusing the finished note dialog

## Approach

UI only. Bring the finished `NoteDialog` in from `2cb0ed6`, then wire two entry points into
`InFlightScreen.kt`: the existing (currently disabled) Note `FooterCell` on each card, and a new
underlined "Sortie note" action under the header bands. Dialog state lives in `InFlightScreen`
itself, next to the existing `editingSetId` — the same pattern the `StepperDialog` already uses,
so no new class, no new state holder, and no restructuring of the 806-line screen.

This fits the codebase because everything below the UI already exists and is already tested:

- `InFlightViewModel.saveExerciseNote(id, text)` / `saveSortieNote(text)`
  (`InFlightViewModel.kt:35-41`) both run `normalizeNote` = `trim().takeIf { isNotEmpty() }`, so
  blank text stores null, and they write straight to Room through `FlightPlanDao.saveExerciseNote`
  and `SortieDao.updateNotes`.
- `InFlightState` already carries the data: `ExerciseCard.note` (`InFlightState.kt:44`, from
  `plannedExercise.userNotes`) and `sortieNote` on both `Ready` (`InFlightState.kt:30`) and
  `NoChecklist` (`InFlightState.kt:16`). `deriveInFlight` fills both.
- The Room flow already redraws the screen, so a saved note appears with no extra plumbing.

So the work is: enable a cell, add one line to `FullCard`, add one small shared strip to both
content composables, and hold one piece of dialog state. Nothing touches the Room schema, the DAOs,
the view model's note rules, or the derivation.

## Files and parts involved

**New (copied from `2cb0ed6`, unchanged):**
- `app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt` — `internal NoteDialog(title,
  initial, onSave, onDismiss)` = `Dialog(onDismissRequest = onDismiss) { NoteDialogContent(...) }`;
  `NoteDialogContent` owns the draft (`var draft by remember(title)`) and draws the `Paper`
  `Column` with `CardShape` + 2 dp `Ink` border, a `LiftoffType.cardTitle()` title,
  `LabeledMultiLineTextField(label = "Note", …)` and `PrimaryButton("Save", height = 48.dp)` /
  `UnderlinedTextButton("Cancel")`, each with `Modifier.weight(1f)`.
- `app/src/test/java/com/liftoff/app/ui/inflight/NoteDialogTest.kt` — 2 tests, rendering
  `NoteDialogContent` in the main window (typing, Save hands the text back, Cancel dismisses
  without saving). `@Config(qualifiers = "w411dp-h2000dp")`.

**Modified — `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`, five composables
only:**
- `InFlightScreen` (line 42): add `var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }`
  beside `editingSetId` (line 52); render a `NoteDialog` block after the existing `StepperDialog`
  block (line 75-98); pass `onOpenNote` / `onOpenSortieNote` down.
- A small `private sealed interface NoteTarget` beside the existing `private data class RowCard`
  (line 101). It needs the shared field on the interface itself, because the `NoteDialog(initial =
  target.text.orEmpty())` call sits outside any `when` and gets no smart cast:
  `NoteTarget { val text: String? }` with `Exercise(val id: Long, val name: String, override val
  text: String?)` and `Sortie(override val text: String?)`.
- `ReadyContent` (line 165): two new params; the sortie strip goes between `DuoStripe()` (line 176)
  and the `LazyColumn` (line 178), so it never scrolls away; `onOpenNote` threaded to `FullCard`
  at both call sites (lines 189 and 202).
- `NoChecklistContent` (line 131): one new param; the same strip after `DuoStripe()` (line 145),
  above the centred "No checklist for this sortie." box, which stays as it is.
- `FullCard` (line 369): new `onOpenNote` param; the muted note line goes between the set-rows
  `Column` (line 413) and `CardFooter(card, vm)` (line 425).
- `CardFooter` (line 582): the Note `FooterCell` (line 604-612) becomes `enabled = true` and
  `onClick = { onOpenNote(...) }`. `FooterCell` itself (line 638) is unchanged — it already draws
  `Muted` + `semantics { disabled() }` when not enabled.
- One new private composable, e.g. `SortieNoteStrip(note: String?, onOpen: () -> Unit)`, used by
  both content composables so the two states cannot drift.
- One new import: `androidx.compose.ui.text.style.TextOverflow`.

`LoadingContent`, `ReadyHeader`, `CollapsedRow`, `SetRowItem`, `StepperDialog`, `Footer` and
`DuoStripe` are not touched.

## Order

1. **Bring in the dialog.** `git show 2cb0ed6:app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt`
   and the same for the test file, redirected to those paths. (The sha form works here;
   `git show <branch-name>:<path>` does not, in Git Bash on this machine.) Check
   `git ls-files --eol` reports `i/lf w/lf` for both.
   Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --tests '*NoteDialogTest'`.
   Commit `inflight: add themed note dialog`.
2. **Exercise note.** Enable the Note cell, thread `onOpenNote` through `ReadyContent` → `FullCard`
   → `CardFooter`, add the `NoteTarget` state and the `NoteDialog` block in `InFlightScreen`, and
   add the muted `card.note` line in `FullCard`. Add test 1 only (no dialog opening).
   Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --tests '*InFlightScreenTest'`.
   Commit `inflight: show and edit the exercise note on a card`.
3. **Sortie note.** Add `SortieNoteStrip` and place it in `ReadyContent` and `NoChecklistContent`;
   open the same `NoteDialog` from it. Add test 2 (no dialog opening).
   Gate: same as step 2. Commit `inflight: add the sortie note action and note line`.
4. **The two dialog-opening tests.** Add tests 3 and 4. Attempt each once. If either throws
   `AppNotIdleException` at the `waitUntil` right after the click that opens the dialog, apply the
   brief's authorised fallback (see Risks and Decision 13) instead of chasing it.
   Gate: same as step 2. Commit `inflight: test the note dialog from the In-Flight screen`.
5. **Full gate.** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, then confirm from
   `app/build/test-results/testDebugUnitTest/TEST-*.xml` that the new tests actually ran
   (`tests="N"` on `NoteDialogTest` and `InFlightScreenTest`, `failures="0" errors="0"`
   everywhere) rather than trusting Gradle's exit code alone.

## How the result will be tested

Four new tests in `InFlightScreenTest`, following its existing style: `runBlocking`, a
`SettingsStore` over `File(context.cacheDir, "settings.preferences_pb")`,
`MissionManager.onAppOpen()`, a hand-inserted `Sortie`, `flightPlanDao().writePlan(sortieId,
FlightPlanDraft(...))`, `composeRule.setContent { LiftoffTheme { InFlightScreen(...) } }`, and
`composeRule.waitUntil(5_000) { … }` on **every** wait. `@Config(qualifiers = "w411dp-h2000dp")`
is already on the class.

1. `seededExerciseNoteShowsMutedLineOnCard` — write a plan, then seed the note through
   `flightPlanDao().saveExerciseNote(cards[0].plannedExerciseId, "Left shoulder twinge")` (the DAO
   is a dumb write; the view model is not involved). Wait until the text is on screen, assert it is
   displayed, and assert `onAllNodes(isDialog()).assertCountEquals(0)` — the line appears without
   anything being opened.
2. `seededSortieNoteShowsOnNoChecklistState` — a RUN sortie with no plan and `notes = "Felt strong"`.
   Wait until both "Felt strong" and "No checklist for this sortie." are shown, proving the line
   was added to the state that only ever showed the placeholder.
3. `noteCellOnActiveCardOpensDialogAndSaves` — use a **one-exercise** plan so "NOTE" is unique
   (three cards mean three "NOTE" cells). Seed the note untrimmed, `"  Left shoulder twinge  "`,
   straight through the DAO. Tap "NOTE"; wait for the dialog with
   `onAllNodes(isDialog()).fetchSemanticsNodes().size == 1` rather than on its title text; assert
   `onNode(hasSetTextAction()).assertTextEquals("  Left shoulder twinge  ")` — the field is
   pre-filled with the stored value, not a trimmed copy. Tap "SAVE" without typing, then poll
   `flightPlanDao().getPlan(sortieId)!!.exercises[0].plannedExercise.userNotes` until it equals
   `"Left shoulder twinge"`. Because the stored value comes back **trimmed**, a changed value proves
   the write went through `saveExerciseNote` and not a direct DAO call, even with no typing.
4. `sortieNoteActionOnNoChecklistOpensDialogAndSaves` — seed the sortie with `notes = "  Felt strong  "`.
   Tap "SORTIE NOTE"; wait for one `isDialog()` node (the action's own label is the same text as
   the dialog title, so text is not a usable discriminator here). Tap "SAVE"; poll
   `sortieDao().get(sortieId)!!.notes` until it equals `"Felt strong"`.

Plus, unchanged: `NoteDialogTest` (2), `InFlightScreenTest` (8 existing), `InFlightStatesTest` (4),
`InFlightViewModelTest` (14, which already covers the Room round-trip, blank → null, trimming, and
notes on skipped exercises). All under the full gate.

Naming note for the verify step: tests 3 and 4 keep these names under the fallback shape below, so
the names stay stable whichever test body lands.

## Risks and uncertainties

- **The known blocker: a `BasicTextField` inside a `Dialog` window never lets Compose go idle under
  Robolectric.** This exact test shape hung in the previous run — `AppNotIdleException: Compose did
  not get idle after 695323 attempts in 60 SECONDS` with an *empty* "Idle Conditions failed" list,
  at the `waitUntil` immediately after `performClick()` on "NOTE", i.e. at dialog **open**, before
  any typing. Control cases from the same run: `openSetStepperShowsPlannedWeight` (a `Dialog` with
  no text field) passes, and `NoteDialogTest` (the same field, main window) passes. Only the
  combination hangs. Four earlier sessions burned 130/101/101/89 turns on clock, touch-mode,
  `DialogProperties`, `cursorBrush` and `AlertDialog` variants; none worked, and none are worth
  retrying.
  So: attempt tests 3 and 4 **once each**. If the hang reproduces, do not chase it — apply the
  brief's authorised fallback in the same step: keep tests 1 and 2, change 3 and 4 to assert the
  Note cell and the "Sortie note" action are present and enabled (`assertIsEnabled`, and
  `onNode(hasSetTextAction())` is *not* on screen before the tap), and keep the dialog's
  pre-fill/Save/Cancel behaviour covered by `NoteDialogTest` and the Room round-trip by
  `InFlightViewModelTest`. Record it as Decision 13 and state the untested one-line
  `onSave = { vm.saveExerciseNote(id, text) }` wiring in the PR.
- **Reading a failing Compose test:** the Gradle console shows only the outer `runBlocking` frame.
  The step that actually hung is in `app/build/test-results/testDebugUnitTest/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml`,
  in the `invokeSuspend(InFlightScreenTest.kt:NNN)` frame. Read that before changing anything.
- **Text collisions.** `FooterCell`, `PrimaryButton` and `UnderlinedTextButton` all uppercase their
  labels, so the screen shows `NOTE`, `SAVE`, `CANCEL`, `SORTIE NOTE`. The dialog's field label is
  `"Note"` in mixed case and its title is `"SORTIE NOTE"` — the same text as the action that opens
  it. Discriminate the dialog with `isDialog()` or `hasSetTextAction()`, never with its title text.
  The card header already draws `card.name.uppercase()`, so waiting on `"BENCH PRESS"` proves
  nothing; the exercise dialog's `"BENCH PRESS · NOTE"` is unique.
- **Seeding a note with spaces** means the on-screen text has them too; match with
  `substring = true` or assert on the trimmed substring.
- **A Room read can race the write.** `saveExerciseNote` goes through `scope.launch`, so poll the
  DAO with `waitUntil`/`withTimeout(5_000)` instead of reading once after the dialog closes.
- **Enabling the Note cell also enables it on expanded DONE/SKIPPED cards**, because they render the
  same `FullCard` → `CardFooter`. That is intended (Decision 2/3), but it means `CardFooter` must
  not be given a status check by accident.
- **Machine state:** a long Gradle run can wait on a lock or a stranded daemon holding
  `app/build/test-results/testDebugUnitTest`; `bash gradlew.sh --stop` and re-run before concluding
  the code is at fault. Run long gates detached with an `EXIT=$?` sentinel and `wait` on it.
- The brief does not ask for any doc change, so none is planned. If the runner's `verify.sh` turns
  out to grep an `ARCHITECTURE.md` row for this work, that is a one-line follow-up, not part of
  this plan.

## Decisions

1. **Where does an exercise note appear on its card?** Inside the card, as a muted line between the
   set rows and the `+ Add set · Note · Skip` footer. *Alternative:* under the ink header bar, or
   beside the exercise name. *Why:* it sits next to the Note cell that edits it, it does not push the
   58 dp set rows down, and the footer is the only Note affordance in the mockup.
2. **Is the Note cell enabled on an expanded DONE or SKIPPED card?** Yes — every card that shows the
   footer shows an enabled Note cell. *Alternative:* only ACTIVE/UPCOMING cards. *Why:* notes are
   usually written after the exercise is finished ("left shoulder twinge"), and an expanded card is
   already the editable view of that exercise.
3. **Does a collapsed DONE/SKIPPED row show that a note exists?** No. Expanding the card shows the
   note line and the Note cell. *Alternative:* a marker or a second line on the collapsed row. *Why:*
   the collapsed row is designed as one line (index, struck-through name, "3/3 LANDED"); the brief
   leaves `CollapsedRow` unchanged.
4. **Is the muted note line tappable?** No — it is a display line; the Note cell is the entry point.
   *Alternative:* tapping the line opens the dialog. *Why:* a 13 sp line of text is not a hit target,
   and the mockup's footer already has the button.
5. **Is the muted line labelled?** No label, just the note text. *Alternative:* a "Note:" prefix, or
   labelling it as a coach note. *Why:* it is the owner's own note (`userNotes`); the mockup's
   "COACH NOTE" belongs to a different field, and a label on a two-line clamp wastes the space.
6. **Can a skipped exercise carry a note?** Yes, through its expanded card's Note cell. *Alternative:*
   notes only on exercises you actually did. *Why:* "shoulder is done for today" is a common reason to
   skip, and the view model already keeps the note on skipped cards (`noteOnSkippedExerciseKeepsSkipAndSets`).
7. **Does the "Sortie note" action scroll with the cards?** No — it sits fixed under the header bands,
   above the scrolling list, on both the checklist and no-checklist states. *Alternative:* make it the
   first item of the `LazyColumn`. *Why:* the brief says it is always visible, and a sortie with no
   note can only get one if the action is reachable before there is a note.
8. **Is the action shown while the screen is loading?** No — only on the checklist and no-checklist
   states. *Why:* there is no note to pre-fill until the state has loaded, and the loading state is a
   skeleton.
9. **Where does the saved sortie note sit, and what happens to the no-checklist placeholder?** The
   muted line goes directly under the action, in both states; the centred "No checklist for this
   sortie." box stays where it is. *Alternative:* replace the placeholder text when a note exists.
   *Why:* the two say different things — one is about the action, the other about the absence of a
   plan — and the brief says to add the line, not to swap the box.
10. **Dialog titles.** `"BENCH PRESS · NOTE"` for an exercise (the uppercase card name, matching the
    stepper's `"BENCH PRESS · SET 1"`), `"SORTIE NOTE"` for the sortie. *Alternative:* a generic
    "Note" for both. *Why:* the owner can see which note they are editing, which matters most when
    the exercise name is also on the card behind the dialog.
11. **Saving an empty field.** It removes the note and the muted line disappears. *Alternative:* block
    Save while the field is empty. *Why:* clearing the text is the obvious way to delete a note, and
    the view model already stores blank as null (`blankExerciseNoteStoresNull`).
12. **Cancel, Back, or tapping outside the dialog.** The draft is discarded and nothing is saved.
    *Alternative:* keep the draft for next time. *Why:* that is how `StepperDialog` behaves today, and
    a half-typed note appearing later on a different exercise would be worse.
13. **If opening the dialog from the screen hangs Robolectric (the known blocker), what do the screen
    tests assert?** Keep tests 1 and 2 as written; change tests 3 and 4 to assert the Note cell and the
    "Sortie note" action are present and enabled, and cover the dialog's pre-fill/Save/Cancel in
    `NoteDialogTest` and the Room round-trip in `InFlightViewModelTest`. *Alternative:* draw the editor
    in the screen's own window (full-screen scrim + `NoteDialogContent`) instead of a `Dialog` window,
    which would make the full flow testable. *Why:* the brief explicitly authorises this fallback and
    says a flaky test must not hold up the run; the alternative overturns the settled "copy the
    `StepperDialog` shell" design and would need a `DESIGN.md` amendment agreed with Kyle, which a
    work step must not do quietly. The gap to state in the PR is the one-line
    `onSave = { vm.saveExerciseNote(id, text) }` wiring.
