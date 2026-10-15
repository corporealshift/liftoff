# Tasks: In-Flight notes on screen, reusing the finished note dialog

Six tasks, in order. Each is one session, ends in a commit, and is gated by the command under it.
`verify.sh` is runner-owned: no task edits it. The run is done when `bash
.rho/runs/in-flight-notes-on-screen-reusing-the-v7bt/verify.sh` exits 0 — task 6 is the only task
that runs it, and it must be run detached with an `EXIT=$?` sentinel.

Test names below are spelled exactly as `.rho/runs/in-flight-notes-on-screen-reusing-the-v7bt/verify.sh`
spells them; class and package are the implementer's call, and plan.md's "## How the result will be
tested" already says which class each one goes in.

- [x] Bring in the finished note dialog from `2cb0ed6`
  Recover the two files the previous run wrote and tested, unchanged, and make them compile and pass
  on their own. `git show 2cb0ed6:app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt >
  app/src/main/java/com/liftoff/app/ui/inflight/NoteDialog.kt` and the same for
  `app/src/test/java/com/liftoff/app/ui/inflight/NoteDialogTest.kt` — the sha form works here,
  `git show <branch>:<path>` does not in Git Bash on this machine. Read both back afterwards:
  `internal NoteDialog(title, initial, onSave, onDismiss)` wrapping `NoteDialogContent`, and the 2
  tests. If the commit cannot be reached, write the same dialog again per the brief (themed Dialog,
  paper card, 2 dp ink border, one multi-line field pre-filled, Save and Cancel; not AlertDialog, not
  a floating-label OutlinedTextField) and the same two tests. Nothing else changes; no screen wiring
  yet.
  Tests this task writes (both already exist in `2cb0ed6`, so copying brings them in):
  `prefilledFieldSaveHandsBackEditedText`, `cancelDismissesWithoutSaving`.
  Done when: both files exist with `git ls-files --eol` reporting `i/lf w/lf`, and
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests '*NoteDialogTest'`
  exits 0 with both names present as passing `<testcase>` entries in
  `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
  Commit `inflight: add themed note dialog`.

- [x] Show and edit the exercise note on a card
  The exercise half of the UI, plus the dialog state that both halves will share. In
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`, and only there: add the
  `private sealed interface NoteTarget` beside the existing `private data class RowCard` (~line 101)
  with the shared `val text: String?` declared on the interface itself — `NoteDialog(initial =
  target.text.orEmpty())` sits outside any `when` and gets no smart cast — and
  `Exercise(val id: Long, val name: String, override val text: String?)` /
  `Sortie(override val text: String?)`. In `InFlightScreen` (~line 42) add
  `var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }` beside `editingSetId` (~line 52)
  and render a `NoteDialog` block after the existing `StepperDialog` block (~lines 75-98), following
  the same dismiss-and-discard pattern (Decision 12). Thread `onOpenNote` from `ReadyContent`
  (~line 165) → `FullCard` (~line 369, both call sites ~189 and ~202) → `CardFooter` (~line 582),
  where the Note `FooterCell` (~604-612) becomes `enabled = true` with `onClick = { onOpenNote(...) }`
  — `FooterCell` itself (~638) is unchanged, and `CardFooter` must not gain a status check, because
  expanded DONE/SKIPPED cards render the same footer and are meant to have an enabled Note cell
  (Decision 2). Add the muted `card.note` line in `FullCard` between the set-rows `Column` (~413) and
  `CardFooter` (~425): `Muted`, 13 sp, `maxLines = 2`, `overflow = TextOverflow.Ellipsis`, no label,
  not clickable (Decisions 1, 4, 5) — new import `androidx.compose.ui.text.style.TextOverflow`.
  `LoadingContent`, `ReadyHeader`, `CollapsedRow`, `SetRowItem`, `StepperDialog` and `Footer` are not
  touched.
  Test this task writes: `seededExerciseNoteShowsMutedLineOnCard` — in `InFlightScreenTest`, following
  that class's existing style (`runBlocking`, `SettingsStore` over
  `File(context.cacheDir, "settings.preferences_pb")`, `MissionManager.onAppOpen()`, a hand-inserted
  `Sortie`, `flightPlanDao().writePlan(...)`, `composeRule.setContent { LiftoffTheme { InFlightScreen(...) } }`,
  `waitUntil(5_000)` on every wait; `@Config(qualifiers = "w411dp-h2000dp")` is already on the class).
  Seed the note straight through the DAO — `flightPlanDao().saveExerciseNote(<plannedExerciseId>,
  "Left shoulder twinge")`, the DAO is a dumb write — wait until the text is displayed, and assert
  `onAllNodes(isDialog()).assertCountEquals(0)`: the line comes from the state derived from Room, with
  nothing opened. No dialog-opening test in this task.
  Done when: the task's gate exits 0 and the XML shows `seededExerciseNoteShowsMutedLineOnCard`
  passing alongside the 8 `InFlightScreenTest` tests that were already there (none renamed or deleted).
  Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests '*InFlightScreenTest'`
  (run detached with an `EXIT=$?` sentinel; if the results dir is locked, `bash gradlew.sh --stop` first).
  Commit `inflight: show and edit the exercise note on the card`.

- [ ] Add the sortie note action and note line
  The sortie half of the UI, on both states, with no dialog opening yet. Add one private composable,
  e.g. `SortieNoteStrip(note: String?, onOpen: () -> Unit)`, used by **both** content composables so
  the two states cannot drift: an `UnderlinedTextButton("Sortie note", …)` (renders as `SORTIE NOTE`)
  fixed under the header bands and above the scrolling content — in `ReadyContent` between `DuoStripe()`
  (~line 176) and the `LazyColumn` (~178), and in `NoChecklistContent` after `DuoStripe()` (~145),
  above the centred "No checklist for this sortie." box, which stays exactly where it is (Decision 9).
  Below the action, the same muted two-line-clamped line as the card's, shown only when the note is
  non-null. Wire `onOpenSortieNote` to the existing `noteTarget` state as `NoteTarget.Sortie(...)` so
  the tap opens the same `NoteDialog` block; `saveSortieNote` is already wired there from the previous
  task's dialog block. Not shown while loading (Decision 8). Keep it out of the bottom `Footer` — a
  later brief puts the Land button there.
  Tests this task writes (both in `InFlightScreenTest`, same style, no dialog opening):
  `sortieNoteActionAndLineShowOnChecklistState` — a lift sortie with a Flight Plan: the `SORTIE NOTE`
  action is displayed and `assertIsEnabled()`, and a note stored on the sortie row shows as a muted
  line near it.
  `seededSortieNoteShowsOnNoChecklistState` — a RUN sortie with no plan and `notes = "Felt strong"`:
  wait until **both** "Felt strong" and "No checklist for this sortie." are displayed, which proves the
  line was added to the state that only ever showed the placeholder.
  Done when: the gate exits 0 and the XML shows both new names passing plus `seededExerciseNoteShowsMutedLineOnCard`,
  `noChecklistBackButtonTriggersCallback`, `footerAddSetAndSkipUnskip` and `openSetStepperShowsPlannedWeight`
  still passing.
  Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests '*InFlightScreenTest'`
  (detached, `EXIT=$?` sentinel).
  Commit `inflight: add the sortie note action and note line`.

- [ ] Test the note dialog opened from the In-Flight screen
  The two opening-and-saving tests, attempted **once each** — this is the known blocker, and the
  fallback is authorised in advance, so the decision is made here rather than chased.
  `noteCellOnActiveCardOpensDialogAndSaves` — use a **one-exercise** plan so `NOTE` is unique (three
  cards mean three `NOTE` cells). Seed the note **untrimmed**, `"  Left shoulder twinge  "`, straight
  through `flightPlanDao().saveExerciseNote`. Tap `NOTE`; wait for the dialog with
  `onAllNodes(isDialog()).fetchSemanticsNodes().size == 1` and `waitUntil(5_000)` — never on its title
  text; assert `onNode(hasSetTextAction()).assertTextEquals("  Left shoulder twinge  ")`, i.e. the
  field holds the stored value, not a trimmed copy. Tap `SAVE` with no typing, then poll
  `flightPlanDao().getPlan(sortieId)!!.exercises[0].plannedExercise.userNotes` with
  `waitUntil`/`withTimeout(5_000)` until it equals `"Left shoulder twinge"` — the value coming back
  **trimmed** proves the write went through `saveExerciseNote` and not a direct DAO call.
  `sortieNoteActionOnNoChecklistOpensDialogAndSaves` — a RUN sortie with no plan and
  `notes = "  Felt strong  "`. Tap `SORTIE NOTE`; wait for one `isDialog()` node (the action's label is
  the same text as the dialog title, so text cannot discriminate); tap `SAVE`; poll
  `sortieDao().get(sortieId)!!.notes` until it equals `"Felt strong"`.
  If either throws `AppNotIdleException` at the `waitUntil` right after the opening click (before any
  typing), do **not** chase it with clock, touch-mode, `DialogProperties`, `cursorBrush`,
  `AlertDialog` or a full-screen scrim editor — four earlier sessions failed at that, and the
  alternative needs a DESIGN.md amendment agreed with Kyle. Apply plan Decision 13 instead, keeping the
  same two names: assert the `NOTE` cell / `SORTIE NOTE` action is displayed and `assertIsEnabled()`,
  and that `onNode(hasSetTextAction())` does **not** exist before the tap. Read the real failing frame
  in `app/build/test-results/testDebugUnitTest/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml`
  (the `invokeSuspend(InFlightScreenTest.kt:NNN)` frame) before changing anything — the Gradle console
  shows only the outer `runBlocking`.
  Done when: the gate exits 0 and the XML shows both names passing in either authorised shape, with
  every other `InFlightScreenTest` test still passing. Whichever shape landed, say so in the commit
  body, and record for the PR that the one-line `onSave = { vm.saveExerciseNote(id, text) }` wiring is
  then untested at screen level (covered by `NoteDialogTest` + `InFlightViewModelTest`).
  Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests '*InFlightScreenTest'`
  (detached, `EXIT=$?` sentinel).
  Commit `inflight: test the note dialog from the In-Flight screen`.

- [ ] Run the whole suite and prove the new tests actually ran
  No source changes unless the full suite turns up a real break this run caused. Run the CI gate over
  the whole suite — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` —
  detached with an `EXIT=$?` sentinel, then read
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` and record the per-class `tests=` /
  `failures=` / `errors=` totals rather than trusting Gradle's exit code: `NoteDialogTest` 2,
  `InFlightScreenTest` 12 (8 existing + 4 new), `InFlightStatesTest` 4, `InFlightViewModelTest` 14,
  `failures="0" errors="0"` everywhere, and no `FROM-CACHE` / `UP-TO-DATE` on the test task in the log.
  Check the brief's "must not break" list is still green in the same reports (Launchpad and Mission
  screens, `MissionManager` and the domain tests, Mission Control, the coach package, the shell).
  Done when: the full-suite gate exits 0 and the XML totals above are written down in the commit body
  (or the run's note file) as evidence.
  Commit `inflight: full suite green with note tests` — or no commit at all if this task changed
  nothing; say so instead of committing an empty change.

- [ ] Run the run's verify.sh and record the result
  The definition of done, last. `bash .rho/runs/in-flight-notes-on-screen-reusing-the-v7bt/verify.sh`
  detached with an `EXIT=$?` sentinel — it clears `app/build/test-results/testDebugUnitTest` itself,
  runs the gate with `--no-build-cache` and matches all 17 names across the reports. Expect exit 0.
  **Do not edit, rename or delete `verify.sh` under any circumstance** — the runner throws away a
  session that touches it. If a name reads "did not run", the test is missing or misnamed: fix the test
  to the exact spelling `verify.sh` uses, in the class plan.md assigns it to, and re-run. If the gate
  fails on a stranded daemon holding the results dir, `bash gradlew.sh --stop` and re-run before
  concluding the code is at fault. If a genuine break in another area shows up, fix it with an
  `area: lowercase summary` commit and re-run.
  Done when: `verify.sh` prints `BRIEF DONE.` and exits 0, and the 17 ✓ lines are recorded for the PR.
  Commit only if this task had to change code; otherwise report the result.
