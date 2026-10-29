# Plan

## Approach

The derivation and the action already exist: `SortieGenerationStatus` says what a PENDING sortie's coach is doing and splits a refused reply into a reason plus its validator lines, `LaunchpadStates` already joins that status live into `LaunchpadState.Pending`, and `LaunchpadViewModel.regenerate()` is already the Retry. What is missing is the drawing, plus one derived fact the card needs to decide whether Retry would be accepted. So this run is presentation only: cherry-pick the finished derivation (`e2199c5`, which adds `MissionManager.generateRunPlans` and `LaunchpadState.regenerateAvailable`), then change only how the PENDING card draws — a status line in the slot the fixed "Flight Plan not ready yet." text occupies, built by a private helper that returns text and colour exactly like `draftStatusLine` does for the draft in the same file, and a `PrimaryButton("Retry")` in `PendingActions` gated on `Failed` and `regenerateAvailable`. Nothing in the domain, the DAOs, the view model or the derivation changes, which is why the existing Launchpad tests should be untouched.

## Files

- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — arrives by cherry-pick only: `generateRunPlans: Flow<Boolean>` beside `coachConfigured`, and a KDoc paragraph on `regenerateFlightPlan` naming the mirror. No hand-written change.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` — arrives by cherry-pick only: `regenerateAvailable` on `Planned` and `Pending`, `manager.generateRunPlans` joined into the PENDING/PLANNED `combine`, set at all four construction sites.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — the only file edited by hand. `PendingContent`: replace the fixed text with the status line drawn in `LiftoffType.note()`, plus the validator lines under a failure; a private file-level helper (the `draftStatusLine` shape) works out the text and colour from `state.generationStatus`, keeping "Flight Plan not ready yet." for `Idle` and `RePlanning`. `PendingActions`: a `PrimaryButton(text = "Retry", onClick = …)` above the Re-fly line and Scrub, drawn only when the status is `Failed` and `pending.regenerateAvailable`; Re-fly and Scrub unchanged. `ActionButtons` already passes an `onRetry` bound to `viewModel.retry()`, which is the draft's outline retry, so the Pending card's Retry reaches the view model through its own callback bound to `viewModel.regenerate()`.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPendingStatusTest.kt` — new Robolectric Compose class, the brief's five cases.
- `.rho/runs/pending-card-coach-status-lines-and-j5et/` — this run's own brief, plan, map, tasks and verify. Nothing from the source branch's `.rho/runs/` is brought in.

What each file already holds is in the map.

## Order

1. Cherry-pick `e2199c5` with its message unchanged, staging only its two files. Read `git log` afterwards to confirm it landed. Build green; `LaunchpadSortieStatusTest`, `LaunchpadStatesTest` and `LaunchpadRegenerateTest` still pass unchanged. If the pick cannot be made, write the same change by hand (the map lists every site) and use the brief's commit message.
2. `PendingContent`: draw the status instead of the fixed text — the helper, the `note()` line, and the validator lines under a failure. Compile green.
3. `PendingActions`: the Retry `PrimaryButton` and its callback through `ActionButtons`. Compile green.
4. Write `LaunchpadPendingStatusTest` with the five cases and run it cold and class-scoped.
5. Run the classes the brief names as must-not-break plus the new one, then `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` detached, once.

## Testing

All five are Robolectric Compose renders of `LaunchpadContent` on a real in-memory Room + `SettingsStore` + `MissionManager`, driven by seeded generation rows rather than by hand-built states — the same shape as `LaunchpadDraftStatusTest` (harness in the map). Each case reaches a PENDING card through `onAppOpen` + `continueWithoutOutline` on the pattern `LRL`, so the current sortie is a LIFT and `regenerateAvailable` is true without touching the run-plans toggle; the not-configured case skips the host and token, and `advance` then queues no row for the sortie.

- Planning — a QUEUED FLIGHT_PLAN row draws "Coach is planning…", and no `RETRY` node exists at all.
- Offline — the same row carrying `Generation.COACH_OFFLINE` draws "Mission Control offline — retrying", and no `RETRY`.
- NotConfigured — host and token left blank, no row: draws `NOT_CONFIGURED_MESSAGE`, and no `RETRY`.
- Failed — `markFailed` with the worker's one-line "invalid output: …; …" draws "invalid output" and each of the two validator errors on its own line, and `RETRY` is drawn and enabled.
- Retry works — tapping `RETRY` moves the card to "Coach is planning…": `regenerateFlightPlan` inserts a new QUEUED row that is now the newest. The cancel helper is a no-op here, so the new row wins on id rather than on the failed row being cancelled. The enqueuer is a no-op lambda too, so no WorkManager is involved.
- Regression: `LaunchpadReFlyTest`, `LaunchpadDraftStatusTest`, `LaunchpadRegenerateTest`, `LaunchpadSortieStatusTest`, `LaunchpadStatesTest`, `LaunchpadScrubTest` and `LiftoffShellTest` pass unchanged.

## Risks

- **The cherry-pick.** `e2199c5` is reachable and its two files are byte-identical at HEAD and at its parent, so the pick should apply cleanly; the fallback is the brief's own hand-written expression. Confirm with `git log` that the commit landed, since a commit tool call can report success and create nothing.
- **The fixed clock and which row is newest.** `regenerateFlightPlan` stamps its new row at `clock.millis()`, the same instant every seeded row in this harness carries, and the query orders `createdAt DESC, id DESC`. The new row wins on id, so the Retry case works — but `markFailed`'s third argument must be a later instant than the row's `createdAt`, and the failed row must be the one `continueWithoutOutline` queued, not a row inserted at an earlier `at(step)`.
- **`Idle` and `RePlanning` cannot be reached on a PENDING card.** `derivePendingSortieGenerationStatus` never returns either, and `deriveActive`'s Idle fallback is the state `currentSortie` never returns. So the fallback line has no test through the screen; it exists to keep the `when` exhaustive. Do not invent a state to draw it.
- **Re-splitting the error.** The card must print `Failed.reason` and each `Failed.validatorErrors` entry as the domain already split them; splitting the raw row error in the UI would duplicate the work and disagree with `LaunchpadSortieStatusTest`.
- **The gate's second condition is not covered by the new tests.** No case has `regenerateAvailable == false`: reaching it needs a row queued while 'Generate run plans' was on and the toggle then switched off, because a run-first pattern with the toggle off leaves the sortie PLANNED on a simple plan rather than PENDING. Prove the `&& pending.regenerateAvailable` half bites with one mutation check (drop the condition, watch a test fail, restore the file), and note that the derivation half is already covered by `LaunchpadSortieStatusTest` and `LaunchpadStatesTest`.
- **A control pushed out of a test window.** The actions live in the Scaffold `bottomBar`, not the scrolled content, so only a taller bar can crowd them. Of the Launchpad classes only `LaunchpadDraftControlsTest`, `LaunchpadDraftStatusTest` and `LaunchpadReFlyTest` render Compose and all three already carry `qualifiers = "w411dp-h2000dp"`; `LiftoffShellTest` renders the shell's Launchpad with no host and token, which is a Draft card. If any class does lose a control, give it that qualifier and say so in the PR.
- **Closing the database under a live flow.** Copy the harness's tearDown: cancel the scope's job before `db.close()`, or the screen's `observe()` fails with a closed connection pool.
- **The full gate can hang for about five minutes on this machine.** Run it detached; re-run the identical tree once if it dies at the test-task timeout writing no `TEST-*.xml`.

## Decisions

**Retry appears only under a failure, not under Offline or NotConfigured.** DESIGN.md §9 says flatly "Retry, Re-fly and Scrub are available" on PENDING, and the brief narrows Retry to `Failed && regenerateAvailable`. So a card reading "Mission Control offline — retrying" has no Retry, even though a regenerate would be accepted there. That is the choice: under Offline the worker is already retrying on its own and the line says so in its own words, and a manual Retry would supersede the open row and start the same request again. Under NotConfigured a Retry would be refused by `regenerateFlightPlan`, so a button there would do nothing. The alternative is the literal reading of §9 — Retry on all three — which puts a control on the card that either duplicates the automatic retry or fails when tapped.

**The validator lines are Muted, not Red.** The brief puts the reason in Red and says each validator error goes on its own line below it, but names no colour for them. They are drawn in the same `note()` style in Muted, so the card has one Red line saying what went wrong and quieter lines underneath giving the detail. The alternative — all of them Red — makes a refused reply with several complaints read as several failures, and buries the one line that names the fault.

**A failure the app cannot retry shows no Retry and no explanation.** A RUN sortie whose generation failed while 'Generate run plans' was on, and which the owner has since switched off, is still PENDING with a failed row and `regenerateAvailable` false: the card shows the reason with no way forward. Chosen: nothing added, because the toggle lives in Mission Control and the reason already says what happened — and `PrimaryButton` carries no `enabled` parameter, so absent is the only form available in any case. The alternative is a Muted line naming the toggle, which is how this same bottom bar handles an unavailable Re-fly ("No earlier plan to re-fly."). If the owner wants the card to explain itself, that line is the change.

The brief settles the rest: the wording of each status line, the colours it names for them, Retry's slot above Re-fly and Scrub, and that Re-fly and Scrub stay exactly as they are.
