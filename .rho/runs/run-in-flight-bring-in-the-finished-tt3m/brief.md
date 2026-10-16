# Run In-Flight: bring in the finished work and show the run plan

Show a run sortie's Flight Plan on the In-Flight screen. Read DESIGN.md §5.3, the run paragraph of §6, Sortie and RunSegment in §8, and `design/README.md` first. Build only what is listed here, and commit as soon as each part compiles and its tests pass. If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask.

**Start from finished work.** An earlier run on this topic was interrupted, but its first four commits are finished and tested. They sit on the unmerged local branch `rho/run-sorties-in-flight-9qtx`, directly on top of this branch's base. Bring in exactly these four commits with `git cherry-pick`, in this order:
- `f7522dc` ui: add distance and run kind formatting (`formatDistance` and `formatRunKind` in `ui/sortie/PlanFormat.kt`, with tests)
- `28e401a` inflight: parse run landing entry (`ui/inflight/RunLanding.kt`: `RunEntry` and `parseRunEntry`, with `RunLandingTest`)
- `e3f0af2` data: store run distance and minutes on land (`MissionManager.land(sortieId, runDistance, runMinutes)`, written in the landing transaction, with tests)
- `cb9316a` inflight: land a run with distance and duration (`InFlightViewModel.landRun`, `runErrors` and `clearRunErrors`, with tests)

Don't bring in that branch's `.rho/runs/` files. Run the gate once right after cherry-picking, before changing anything else. If the branch is missing or a commit won't apply cleanly, rebuild the same pieces from that branch's plan (`git show rho/run-sorties-in-flight-9qtx:.rho/runs/run-sorties-in-flight-9qtx/plan.md`). Nothing in this brief uses `landRun` or `parseRunEntry` yet. The next brief adds the landing dialog that uses them.

Build on what's already in the repo:
- the In-Flight screen in `com.liftoff.app.ui.inflight`: `InFlightState` (Loading, NoChecklist, Ready), `deriveInFlight`, `InFlightStates`, `InFlightViewModel`, and `InFlightScreen` with its lift checklist, notes, Land, the unchecked-sets confirmation and Scrub. Today a run sortie shows the NoChecklist state. `InFlightScreen.kt` is large, so change only what this brief needs and put new composables in their own files.
- a run's Flight Plan, read through `FlightPlanDao.getPlan`: the simple 'Run' plan (source SIMPLE_RUN) that `MissionManager` creates, the plan's run kind, target distance and target pace, and its `RunSegment` rows
- the distance unit setting (mi or km) in `SettingsStore`
- the formatting helpers in `com.liftoff.app.ui.sortie` and the theme parts in `com.liftoff.app.ui.theme` (`InkRuledListRow`, stripes, headings)

**Run In-Flight.**
- A run sortie that has a Flight Plan gets its own In-Flight state and screen. A run with no plan at all keeps today's NoChecklist screen.
- The run screen keeps the teal header and the stripe bands. It has no set counter, no progress bar and no set rows.
- Its body shows the plan in the design style: the title; one line with the plan notes, or the focus when there are no notes; the run kind; the target distance in the configured unit; the target pace; and the segments, when there are any, as ink-ruled rows.
- A row appears only when it has a value. The simple 'Run' plan therefore shows its title and focus without empty fields.
- Changing the distance unit in Mission Control updates the screen live.
- Scrub and the sortie note work on the run screen exactly as on lifts. Watch for any `when` over the In-Flight state with an `else` branch, such as the one that picks the sortie number for the scrub dialog. A new state must not fall into it and leave Scrub or the note dead on runs.
- Land on a run keeps today's behaviour for now: it lands straight away, with no dialog and no unchecked-sets confirmation.

Put the derivation in plain Kotlin that a Robolectric test can drive. Every wait in a test needs a timeout. Nothing uses the network. Don't change the Room schema. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status rather than re-running it.

Must not break: the lift In-Flight flow with its notes, Land, the unchecked-sets confirmation and Scrub, and their tests; the Launchpad and Mission screens; `MissionManager` and the domain tests; Mission Control; the coach package; and the build gate. If an existing test asserted that a run with a plan shows NoChecklist, change it to assert the new run state, and say so in the PR. Follow CLAUDE.md: the §2 vocabulary (never 'session' for a sortie), LF endings, `area: lowercase summary` commit messages, and staging named files only.

Done when:
- The four commits above are on the branch and their tests pass.
- Robolectric tests cover:
  - the run state derived from a seeded run plan with segments, in km and in mi
  - the run state from the simple 'Run' plan, with no empty rows
  - a run with no plan still showing NoChecklist
  - Launch → Land of a simple run from the run state, leaving the sortie LANDED
  - Scrub from the run state storing its reason
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
