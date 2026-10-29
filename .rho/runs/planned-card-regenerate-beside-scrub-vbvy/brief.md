# Planned card: Regenerate beside Scrub and the re-planning line

Give the Launchpad's PLANNED card the Regenerate action the mockup draws. design/screens/launchpad.html puts two underlined text buttons, Regenerate and Scrub, side by side under the big red Launch button. DESIGN.md §9: 'PLANNED: the plan summary, with Launch, Regenerate and Scrub.'

Builds on work already on this branch: `LaunchpadState.Planned` carries `generationStatus` (`SortieGenerationStatus.RePlanning` while a new FLIGHT_PLAN generation is open for the sortie, otherwise `Idle`) and `regenerateAvailable: Boolean` (false when Mission Control has no host or token, or when the sortie is a RUN and 'Generate run plans' is off). `LaunchpadViewModel.regenerate()` already queues a new generation and leaves the existing plan in place until the new one replaces it. The PENDING card's status lines and Retry were added by the previous run; leave them alone.

Change only `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`:
- `PlannedActions`: when `regenerateAvailable` is true, put Regenerate and Scrub as two `UnderlinedTextButton`s in one Row under Launch. UnderlinedTextButton fills its width, so give each equal weight. Regenerate calls `viewModel.regenerate()`. When it's false, show Scrub alone, as today. Thread the state and the new lambda through `ActionButtons`.
- `PlannedContent`: while `generationStatus` is `RePlanning`, show the line 'Coach is re-planning…' in `LiftoffType.note()` (Muted) above the Flight Plan. Keep the plan and Launch on screen, and keep Regenerate available.
- Use theme parts only. No stock M3 visuals. Labels are upper-cased, so tests look for 'REGENERATE' and 'SCRUB'.

Tests: write a new Robolectric Compose class `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPlannedActionsTest.kt` with `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`. Use the harness from LaunchpadDraftStatusTest.kt (in-memory Room, a real SettingsStore, MissionManager on `Clock.fixed(2026-01-12T00:00:00Z, UTC)`, and `LaunchpadContent(viewModel, onOpenInFlight = {})` in `LiftoffTheme`). The view model gets a recording `queueGeneration` lambda and a `cancelGeneration` that calls `db.generationDao().markCancelled(id, clock.millis())`. Never touch WorkManager, and bound every wait at 5 s.

To reach a PLANNED lift card: set host '127.0.0.1' and a token, `setDefaultPattern("LRL")`, `manager.onAppOpen()`, `manager.continueWithoutOutline(missionId)`, then `manager.storePlan(sortieId, CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))`, which returns `StoreOutcome.Stored`. LaunchpadRegenerateTest.kt does exactly this. A RUN sortie with run generation off, from pattern "RLL" with the toggle left off, is already PLANNED with a simple 'Run' plan and has `regenerateAvailable` false. Cases:
- a PLANNED lift shows REGENERATE and SCRUB and no re-planning line
- tapping REGENERATE records one queued id, and the card then shows 'Coach is re-planning…' while LAUNCH and the plan's title stay on screen
- the run-off PLANNED card shows SCRUB and no REGENERATE

Must not break: the other Launchpad tests, LiftoffShellTest, or the build gate. If an existing Compose test loses sight of a control, give that class the tall-window qualifier and say so in the PR. Do not change ARCHITECTURE.md in this run. Follow CLAUDE.md conventions. Decide unclear points, record them in the plan, and carry on. The gate can hang once for about 5 minutes: run it detached and re-run it once if it hangs.

Done when: the three tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
