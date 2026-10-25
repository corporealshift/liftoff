# Tasks

Five tasks, in this order. Task 1 is the drawing change plus the repair of the one class built on the old
drawing. Tasks 2 and 3 add the new render class in two halves — 3 adds to the file 2 created. Task 4 is the doc
row. Task 5 is the whole gate.

The six tests `verify.sh` names by name are split 3 / 3 across tasks 2 and 3, spelled exactly as `verify.sh`
spells them. It names no other new test. `draftAlwaysOffersContinueWithoutOutline` and
`draftShowsTheNotConfiguredMessageWhenItIsSet` are named by no task on purpose: their names are the things being
removed, and `verify.sh` checks `LaunchpadDraftControlsTest` as a whole class.

- [x] 1. Draw the coach status on the draft, and repair the class built on the old drawing
  Covers plan step 1, in one commit. The plan's Decisions are what to honour: one status line, the report
  replacing it only in `failed`, Retry as the big primary button, the pattern editable whenever no generation is
  open, the failure reason word for word with no label.
  Files: `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — `DraftContent` draws one status line
  under the pattern in place of
  the `notConfiguredMessage` block (the planning line, the offline line, the failed row's reason in `Red`, or the
  not-configured sentence); `DraftActions` draws only what the status offers — the −/+ row wherever the pattern is
  editable (`editable`, `failed`, `not configured`), read-only in `planning` and `offline` drawn in the
  `DraftStepButton` `clickable(enabled = false, role = Role.Button)` shape so `assertIsNotEnabled()` is not
  vacuous, `PrimaryButton("Confirm")` only in `editable`, `PrimaryButton("Retry")` only in `failed`,
  `UnderlinedTextButton("Continue without outline")` only where the status offers it; the always-visible button and
  the message block go; the draft arm of `ActionButtons` gains the Retry path it does not have today, wired to
  `viewModel.retry()`. Keep `LaunchpadContent`'s existing two-argument call sites compiling — `LaunchpadReFlyTest`
  calls it as `LaunchpadContent(viewModel = …, onOpenInFlight = {})`, and `verify.sh` checks that class whole.
  `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` — `NOT_CONFIGURED_MESSAGE` loses its trailing "or continue without an
  outline"; `retry()` clears `notConfiguredMessage` at the start of its launch, as `confirm()` already does.
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftControlsTest.kt` — the fixture sets a host and a token so its stepper cases test the
  editable draft they were written for; `draftAlwaysOffersContinueWithoutOutline` becomes the planning case that
  clicks `CONTINUE WITHOUT OUTLINE` and waits for `LAUNCH`; `draftShowsTheNotConfiguredMessageWhenItIsSet` leaves
  this class, and task 3 writes its replacement through Retry.
  Tests it writes: none named by `verify.sh`. `draftDrawsOneStepperPair`, `draftMinusDisabledAtOneSortie` and
  `draftPlusDisabledAtSevenSorties` keep their assertions unchanged apart from the fixture. Do not weaken an
  existing Launchpad test to make it pass — if one fails, the drawing is wrong.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.LaunchpadDraftControlsTest"` passes cold with every case in that class green, and
  `LaunchpadConfirmTest`, `LaunchpadCoachStatusTest` and `LaunchpadStatesTest` still pass unedited.

- [x] 2. `LaunchpadDraftStatusTest`: the editable and planning renders
  Covers the first half of plan step 2. File:
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftStatusTest.kt` (new). Copy the harness from
  `LaunchpadDraftControlsTest` rather than rebuilding it: in-memory Room, `PreferenceDataStoreFactory.create` on
  `settings.preferences_pb`, `MissionManager` on a fixed UTC clock, a `LaunchpadViewModel` with no-op
  `queueGeneration` / `cancelGeneration`, `LaunchpadContent` as the seam, `@Config(sdk = [34], qualifiers =
  "w411dp-h2000dp")`, a bounded `waitUntil` on a known string before any assertion, `tearDown` cancelling the
  scope's `Job` before `db.close()`. Seed the status the way `LaunchpadCoachStatusTest` seeds rows: host and token
  through the settings store, `OUTLINE` rows through `db.generationDao()` for the draft's own mission, `createdAt`
  staggered. No text fields, no WorkManager.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `editableDraftDrawsItsChipsStepperAndConfirmAndNoOtherAction` (configured coach, no outline row: the chips drawn
  and tapping one changes the pattern the draft shows; one −/+ pair, both enabled; `CONFIRM`; no status line, no
  `RETRY`, no `CONTINUE WITHOUT OUTLINE`),
  `planningDraftDrawsItsStatusLineAndContinueWithoutOutlineAndNoStepperOrConfirm` (a QUEUED `OUTLINE` row: 'Coach
  is planning the week…' displayed; `CONTINUE WITHOUT OUTLINE` displayed and enabled; `+` and `−` not drawn at all;
  no `CONFIRM`, no `RETRY`),
  `planningDraftDrawsItsPatternChipsReadOnly` (the chips are still drawn and each asserts `assertIsNotEnabled()`,
  and tapping one leaves the pattern the draft shows unchanged).
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.LaunchpadDraftStatusTest"` passes cold with those three cases green, and the run
  wrote a `TEST-*.xml` naming all three.

- [x] 3. `LaunchpadDraftStatusTest`: the offline, failed and not-configured renders
  Covers the second half of plan step 2, in the class task 2 created —
  `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftStatusTest.kt`. Read what it left there first, and
  reuse its seeding helpers.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `offlineDraftDrawsItsStatusLineAndTheSameActionsAsPlanning` (an outline row still open and carrying the
  `Mission Control offline` mark: 'Mission Control offline — retrying' displayed; `CONTINUE WITHOUT OUTLINE`
  displayed and enabled; no `CONFIRM`, no `RETRY`, no −/+ pair),
  `failedDraftDrawsItsReasonAndRetryAndContinueWithoutOutlineAndNoConfirm` (a `FAILED` `OUTLINE` row whose reason
  has punctuation and inner spacing: the reason displayed word for word; `RETRY` and `CONTINUE WITHOUT OUTLINE`
  displayed and enabled; no `CONFIRM`; the chips and the −/+ pair still drawn and enabled; with host and token set,
  tapping `RETRY` makes the draft show 'Coach is planning the week…'),
  `notConfiguredDraftDrawsItsSetupLineAndContinueWithoutOutlineAndNoConfirmOrRetry` (blank host and token, no
  outline row worth reporting: a line saying Mission Control isn't set up and that the host and token go there;
  `CONTINUE WITHOUT OUTLINE` displayed and enabled; no `CONFIRM`, no `RETRY`; the chips and the −/+ pair still drawn
  and enabled).
  Also in this class, the plan's two Decision cases that `verify.sh` does not name: a `FAILED` draft with blank
  settings shows its reason and nothing else, and a `RETRY` tap there puts the not-configured sentence in that same
  single slot with only one such line on screen; then setting host and token and tapping `RETRY` again shows 'Coach
  is planning the week…' with the not-configured sentence gone. `retryOutline` refuses unless a `FAILED` row is the
  newest and the first tap reports without writing a row, so the `FAILED` row is still there for the second tap.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.LaunchpadDraftStatusTest"` passes cold with every case in the class green, task 2's
  three included, and the run wrote a `TEST-*.xml` naming all six of the names above.

- [x] 4. Update the ARCHITECTURE.md M3 row
  Covers plan step 3. File: `ARCHITECTURE.md` line 82, the M3 row only. Replace its closing "App wiring — the
  screens that queue a generation and cancel one — comes next" with what is now true — Confirm queues the outline
  and the draft shows the status derived from that row — and name what comes next: queueing Flight Plans and the
  sortie status display. No other row, no other file. `verify.sh` does not grep this row; Kyle reviews the wording
  in the pull request, so write it plainly rather than to a phrase.
  Done when: `git show --stat HEAD` lists `ARCHITECTURE.md` and nothing else, and the row says both halves the
  brief asks for.

- [ ] 5. The whole gate, run once, and any fallout it turns up
  Covers plan step 4 and the brief's Done-when. Run `.rho/runs/draw-the-draft-s-coach-status-on-the-ne4y/verify.sh`
  detached through a copy under `app/build/` (`mkdir -p app/build` first; run the copy, never the real file, so
  editing it mid-run cannot give a phantom syntax error), logging to `app/build/` with an `EXIT=$?` sentinel on its
  own line and no pipe. It moves `app/build/test-results/testDebugUnitTest` aside with `mv` and runs
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` itself, then looks up every name and
  class it lists. One Gradle invocation in this worktree at a time; never `rm` the results dir (a stranded test
  executor holds `binary/output.bin`); never `gradlew --stop` (machine-wide). Read the log: the gate's own output,
  the report totals it prints, and its ✓/✗ list. A hang that writes no `TEST-*.xml` is the hang the brief warns
  about — move the results aside and re-run the same tree once; if it hangs twice, find the worker JVM and `jstack`
  it rather than re-running again.
  Done when: the log ends with `EXIT=0` and `BRIEF DONE`, with the six new names ✓ and the whole-class checks ✓,
  and the reports show 0 failures and 0 errors. Fix and commit anything the gate turns up; if it turns up nothing,
  record the log path and the report totals in a commit message so the run's evidence is in the history.
