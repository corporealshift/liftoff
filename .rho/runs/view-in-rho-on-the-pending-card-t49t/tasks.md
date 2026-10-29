# Tasks — View in rho on the Pending card

Three tasks, in order, each ending in a commit. `LaunchpadScreen.kt` is the only production file (plan
Approach: the id is already derived and live on `LaunchpadState.Pending.rhoSessionId`, so the data,
derivation and view-model layers are untouched). Task 1 draws it, task 2 tests it, task 3 runs the run's
gate.

The three test names under task 2 are spelled exactly as
`.rho/runs/view-in-rho-on-the-pending-card-t49t/verify.sh` looks for them, and task 2 writes all three.
No other task writes a test.

Every gate is a cold, detached, class-scoped run: `mkdir -p app/build` first, `timeout 1800 bash gradlew.sh
… 2>&1 | tee app/build/<name>.log`, then `echo "EXIT=$?"` on its own line (no pipe, or the sentinel reports
`tee`'s status). Read the XML totals under `app/build/test-results/testDebugUnitTest`, not
`BUILD SUCCESSFUL`. Move `test-results` aside with `mv` between runs, never delete it, never
`gradlew --stop`. Run one scratch script at a time: two Gradle runs in one invocation share the results
directory.

- [x] 1. Draw the View in rho control and the revealed id line on the PENDING card
  - Covers the whole production change, in
    `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` and no other file: the reveal
    state held in `LaunchpadContent` (l.64, where the card body and the bottom bar are both composed) as
    `rememberSaveable` keyed on the PENDING card's `sortieId` and null when the card is not PENDING — the
    plan's Decision says why that key and not plain `remember`; `UnderlinedTextButton(text = "View in rho")`
    in `PendingActions` (l.418-444) between the Re-fly block and Scrub, with the bar's usual 12 dp spacer,
    drawn only when that card's `rhoSessionId` is not null; the `rho session <id>` line at the end of
    `PendingContent` (l.199-217), after the status line and any validator lines, as `LiftoffType.note()` in
    `Muted`, drawn only while the reveal is set **and** the current state's `rhoSessionId` is not null; the
    two new arguments (revealed flag, reveal callback) threaded through `ActionButtons` (l.262-298) to the
    Pending arm only, defaulted so the Draft, Planned, In-Flight and Closed arms and their call sites are
    untouched. The reveal is one-way: the callback sets it and nothing clears it except the key changing.
    No test in this task, and no other line in the file changes.
  - Done when: `bash gradlew.sh :app:compileDebugKotlin` is green, and one cold class-scoped run naming the
    five Launchpad classes that render Compose plus the shell —
    `--tests com.liftoff.app.ui.launchpad.LaunchpadPendingStatusTest --tests com.liftoff.app.ui.launchpad.LaunchpadPlannedActionsTest --tests com.liftoff.app.ui.launchpad.LaunchpadReFlyTest --tests com.liftoff.app.ui.launchpad.LaunchpadDraftStatusTest --tests com.liftoff.app.ui.launchpad.LaunchpadDraftControlsTest --tests com.liftoff.app.ui.LiftoffShellTest`
    — reports 37 tests, 0 failures, 0 errors, 0 skipped (6 + 3 + 4 + 8 + 4 + 12, the counts before this
    task). The control is invisible to all of them because none seeds a session id on a generation row. If
    a class loses a node out of the fixed `w411dp-h2000dp` window, give that class the same `@Config`;
    never weaken an assertion. Commit `launchpad: the pending card offers View in rho and reveals the id`.

- [x] 2. Write the three PENDING-card cases and prove each one catches a fault
  - Covers the new file `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadViewInRhoTest.kt`, three
    Robolectric Compose cases over the real PENDING card, on `LaunchpadPendingStatusTest`'s harness
    (`@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, in-memory Room, a real `SettingsStore` on a
    cache-dir file, `MissionManager` on `Clock.fixed(2026-01-12T00:00:00Z, UTC)`, `LiftoffTheme {
    LaunchpadContent(viewModel, onOpenInFlight = {}) }`, no-op recording `queueGeneration` /
    `cancelGeneration`, no WorkManager, every wait bounded at 5 s, `tearDown` cancelling the scope's Job
    before `db.close()`). Seeding is `pendingCard`'s: host `127.0.0.1` + token, pattern `LRL`,
    `onAppOpen`, `continueWithoutOutline`, which leaves one QUEUED FLIGHT_PLAN row for sortie 0 and the
    status line `Coach is planning…`; the id goes on that row with
    `generationDao().update(row.copy(nabuSessionId = "sess-7f3a-liftoff"))`. The three methods, named
    exactly:
    - `pendingCardWithASessionIdShowsViewInRhoAndTappingItRevealsTheSessionLine` — wait for `VIEW IN RHO`
      (upper-cased by the theme) shown and enabled; no node whose text contains `sess-7f3a-liftoff` before
      the tap; after `performClick` the whole line `rho session sess-7f3a-liftoff` is displayed, and
      `Coach is planning…` and SCRUB are still displayed; VIEW IN RHO's bounds sit above SCRUB's in the
      bar's Column and the id line's bounds sit under the status line in the content Column — nothing
      compared between the two Scaffold slots; the recording enqueuer was handed no id and the row is still
      QUEUED with that id.
    - `pendingCardWithAGenerationRowThatHasNoSessionIdShowsNoViewInRho` — the row left with no id; wait for
      the PENDING card first (`Coach is planning…` or SCRUB), then no `VIEW IN RHO` and no text containing
      `rho session`.
    - `pendingCardWithNoGenerationRowShowsNoViewInRho` — host and token left blank so
      `continueWithoutOutline` queued no row (assert no open FLIGHT_PLAN row for this sortie); wait for
      `NOT_CONFIGURED_MESSAGE` (`internal`, `LaunchpadViewModel.kt:18`, same package), then no `VIEW IN RHO`
      and no text containing `rho session`.
  - Then prove each case bites, one one-token mutation of `LaunchpadScreen.kt` at a time, each with its own
    log under `app/build` and the results directory moved aside between runs: drop the id condition on the
    button (expect the two absence cases red), make the tap set nothing (expect the first case red), drop
    the reveal condition on the id line (expect the first case red, on the line being there before the tap).
    Take a byte-copy backup of the screen file before any mutation, restore with `cp`, and confirm
    `git diff` against it is empty before committing.
  - Done when: one cold class-scoped run `--tests com.liftoff.app.ui.launchpad.LaunchpadViewInRhoTest`
    reports 3 tests, 0 failures, 0 errors, 0 skipped; each of the three mutation logs reports the expected
    test red (read the failing test's name out of the XML, not the build status — a mutation run is
    supposed to print `BUILD FAILED`); the restored screen file is byte-identical to the backup and
    `git status` is clean apart from the new test file. Commit
    `launchpad tests: the pending card's View in rho and the revealed session id`.

- [ ] 3. Run the run's gate over the whole suite and record what it says
  - Covers the last step of the plan and nothing new: one cold detached run of
    `bash .rho/runs/view-in-rho-on-the-pending-card-t49t/verify.sh` (it runs `:app:assembleDebug
    :app:testDebugUnitTest --no-build-cache` itself, then the three names, then whole-class checks on the
    twelve Launchpad classes and `LiftoffShellTest`), with its output in `app/build/verify-t49t-final.log`
    and `echo "EXIT=$?"` on its own line afterwards. Do not edit `verify.sh`.
  - Done when: that log ends `Result: 17 passed, 0 failed` / `BRIEF DONE.` with `EXIT=0`, and the gate's own
    report line reads 736 tests (the 733 baseline plus the three new ones), 0 failures, 0 errors, 0 skipped.
    If a class broke, fix it — a `@Config` window for a class pushed out of the fixed height, or whatever
    the report actually names — never a weakened or deleted assertion, and re-run until the script exits 0.
    A run that wrote no XML report at all, or a build task that failed with no test failing, is the known
    hang: re-run the same tree once, detached, without `gradlew --stop`. Write the counts, the attempt count
    and anything the gate exposed into the run's `map.md` and commit that as `run: gate green`.
