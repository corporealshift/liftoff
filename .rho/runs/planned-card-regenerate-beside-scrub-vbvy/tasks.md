# Tasks

Run: planned-card-regenerate-beside-scrub-vbvy. Five tasks, one commit each. Tasks 1-2 write only
`app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`; tasks 3-4 write only the new
class `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPlannedActionsTest.kt`; task 5 may
touch one existing test file if a class loses sight of a control. `map.md` holds the line numbers,
the harness parts, the fixture recipes and the facts about generation rows.

- [ ] 1. Draw Regenerate beside Scrub on the PLANNED card
  Covers: `PlannedActions` (l.380) takes the Planned state as well as its two lambdas, and
  `ActionButtons`' Planned arm (l.282) hands it the state and `onRegenerate`, which
  `LaunchpadContent` already threads (l.102). When `regenerateAvailable` is true it draws Regenerate
  then Scrub as two `UnderlinedTextButton`s in one `Row` under Launch and its existing 12dp spacer,
  each weighted equally with 24dp between them (the plan's Decisions on spacing and order);
  `UnderlinedTextButton` takes a `modifier`, so the weight comes from the enclosing `Row` and no
  theme file changes. When `regenerateAvailable` is false it draws Scrub alone exactly as today. No
  test checks this yet.
  Done when: `bash gradlew.sh :app:assembleDebug` prints BUILD SUCCESSFUL and the change is
  committed.

- [ ] 2. Draw the re-planning line on the PLANNED card
  Covers: `PlannedContent` (l.180) draws 'Coach is re-planning…' — the ellipsis is U+2026, as in the
  card's other line consts — in `LiftoffType.note()` with Muted, between the 20dp spacer and
  `FlightPlanSection`, only while `generationStatus` is `RePlanning`. A PLANNED card's status is only
  ever `RePlanning` or `Idle`, so the line needs one arm, and it depends on `generationStatus` alone,
  not on `regenerateAvailable` (plan Decision). Put the text in a file-private const beside the
  card's other line consts (l.177, 223). TitleBlock, PatternTrack, `FlightPlanSection` and the bottom
  bar are otherwise untouched; the PENDING card is left alone.
  Done when: `bash gradlew.sh :app:assembleDebug` prints BUILD SUCCESSFUL and the change is
  committed.

- [ ] 3. Write the two render-only cases of LaunchpadPlannedActionsTest
  Covers: the new class, `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, on the harness
  `LaunchpadPendingStatusTest` defines and `LaunchpadDraftStatusTest` originated: in-memory Room with
  `allowMainThreadQueries()`, a real `SettingsStore` over a `PreferenceDataStoreFactory` file under
  `context.cacheDir`, `MissionManager` on `Clock.fixed(2026-01-12T00:00:00Z, UTC)` with
  `zone = { UTC }`, a `Dispatchers.Unconfined` scope over a `Job`, recording
  `queuedIds`/`cancelledIds`, `render` setting content to `LiftoffTheme { LaunchpadContent(viewModel,
  onOpenInFlight = {}) }`, `awaitText` through `waitUntil(5_000)`, and `tearDown` cancelling the job
  before `db.close()`. Lift fixture: host `127.0.0.1` and a token, pattern `LRL`, `onAppOpen`,
  `continueWithoutOutline` taking the id its `onQueued` callback hands so that QUEUED FLIGHT_PLAN row
  can be closed with `markSucceeded`, then `storePlan` with
  `CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)`, which returns
  `Stored`. Run-off fixture: pattern `RLL` with `generateRunPlans` left off, which is already PLANNED
  with a `SIMPLE_RUN` plan and has no generation row at all. WorkManager is never touched; SCRUB is
  never clicked.
  Tests written here, by no other task:
  `plannedLiftCardShowsRegenerateBesideScrubAndNoRePlanningLine` (REGENERATE and SCRUB both displayed
  and enabled, their bounds overlapping vertically, neither spanning the row on its own, REGENERATE
  to the left of SCRUB, and no 'Coach is re-planning…') and
  `plannedRunCardWithRunGenerationOffShowsScrubAndNoRegenerate` (SCRUB displayed and enabled, no
  REGENERATE, no re-planning line).
  Done when: one cold detached run of this class alone (`bash gradlew.sh :app:testDebugUnitTest
  --no-build-cache --tests com.liftoff.app.ui.launchpad.LaunchpadPlannedActionsTest`, scratch script
  and log under `app/build/`, `EXIT=` marker line, results dir moved aside not deleted) reports 2
  tests / 0 failures and its `TEST-*.xml` names both methods; committed.

- [ ] 4. Add the tap case to LaunchpadPlannedActionsTest
  Covers: the third case, on the lift fixture task 3 built. Wait for REGENERATE on screen before
  tapping, because `LaunchpadViewModel.regenerate()` reads `state.value` and returns null on a card
  that has not been derived yet, which would make the case pass without testing anything. Tap
  REGENERATE with `performTouchInput { click() }`. Assert `queuedIds` holds exactly one id and that
  id is a real QUEUED FLIGHT_PLAN row for this sortie read back from `generationDao`, not just some
  number; that 'Coach is re-planning…' is now drawn; that `LAUNCH`, the plan's title `Push + core`
  and REGENERATE are all still on screen and the plan is still in `flightPlanDao`; and that
  `cancelledIds` is empty, so the case cannot pass because of a cancel (the row
  `continueWithoutOutline` queued was closed with `markSucceeded`, so `regenerateFlightPlan` had no
  open row to hand back).
  Test written here, by no other task:
  `tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine`.
  Done when: one cold detached run of this class alone reports 3 tests / 0 failures and its
  `TEST-*.xml` names all three methods; committed.

- [ ] 5. Prove each case bites, then run the whole gate
  Covers: three one-token mutations of `LaunchpadScreen.kt`, each with its own scratch log under
  `app/build/` and one scratch Gradle script at a time (two runs in one script share
  `test-results`, so a later grep reads the other run's XML): drop the `regenerateAvailable`
  condition in `PlannedActions` and expect
  `plannedRunCardWithRunGenerationOffShowsScrubAndNoRegenerate` to fail; drop the `RePlanning`
  condition on the line and expect the two cases that assert the line is absent to fail; give the
  Regenerate button an empty `onClick` and expect
  `tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine` to fail. A red build
  in a mutation run is the expected result: read the failing test's name out of the JUnit XML, not
  the build status. Copy the screen file aside with `cp` before mutating and restore it with `cp`
  (`git checkout -- file` is denied by the approval gate in a runner session), then confirm the
  restored file still holds both screen changes.
  Done when: each mutation log names the expected failing test; the restored tree passes the cold
  class-scoped run of tasks 3-4's class; and one whole-suite run `bash gradlew.sh :app:assembleDebug
  :app:testDebugUnitTest --no-build-cache`, detached with its own log and `EXIT=` marker and re-run
  once on a hang or a build-task failure, ends BUILD SUCCESSFUL with 0 failures and 0 errors across
  its reports, including the twelve Launchpad and shell classes `verify.sh` names. If an existing
  Launchpad or `LiftoffShellTest` Compose class loses sight of a control, give that class
  `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")` in a test-only commit that says so in its
  body. Leave the tree clean.
