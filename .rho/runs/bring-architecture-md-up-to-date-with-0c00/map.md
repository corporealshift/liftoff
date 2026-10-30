# Map — bringing ARCHITECTURE.md up to date

Verified against HEAD `736004b` on 2026-10-29.

## The document being changed

`ARCHITECTURE.md` (85 lines):

| Section | Lines |
|---|---|
| Topology | 9–20 |
| Package layout table | 24–37 (root 30, `rho` 31, `data` 32, `settings` 33, `domain` 34, `coach` 35, `work` 36, `ui` 37) |
| Resource locations | 41–46 |
| Data flow | 50–54 |
| Invariants | 58–69 |
| Milestones | 73–85 (M0 79 … M6 85) |

Line 36 (`work`) is the run-on the brief wants cut to one or two sentences. Line 82 (M3) is by far the longest milestone cell and carries the "come next" tail the brief wants gone. Line 81 (M2) says "In-Flight is a placeholder until M4"; line 37 (ui) says "Mission Control stub" twice.

## Conventions that bind this work

- `CLAUDE.md`: commit message `area: lowercase summary` (brief asks for `docs: ...`); stage named files only, never `git add -A`; LF endings except `*.bat` (`.gitattributes`); gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.
- `DESIGN.md` is the authority. §14 (line 721) defines M0–M6 and their titles — the milestone titles in ARCHITECTURE.md already match it. §8 (line 520) is the data model and the DataStore settings list. §9 (line 553) is the screens.

## Source tree, as it actually is

Under `app/src/main/java/com/liftoff/app/`:

- **root** — `AppContainer.kt`, `LiftoffApplication.kt`, `MainActivity.kt`. `MainActivity.kt:35` calls `AppOpenSweep`, so the app-open sweep is wired into the entry point.
- **`rho/`** — `DaemonConnectionCheck.kt`, `net/DaemonClient.kt`, `protocol/Events.kt`, `protocol/RpcCodes.kt`. Nothing else.
- **`data/`** (22 files) — entities `Mission`, `Sortie`, `FlightPlan`, `PlannedExercise`, `PlannedSet`, `RunSegment`, `Exercise`, `Equipment`, `Generation`; `Enums.kt` (`FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }`); `Converters.kt`, `Relations.kt`; DAOs for each entity; `LiftoffDatabase.kt`; `MissionManager.kt`; export/import as `DatabaseExport.kt`, `DatabaseImport.kt`, `BackupDocument.kt`; the two plan-copy files `CoachPlanCopy.kt` (validated coach reply → plan draft) and `ReFlyCopy.kt` (the finished sorties to re-fly from, newest first, plus `reflyDraft`); `PromptInputLoader.kt`.
  - `MissionManager.kt` signatures worth knowing: `confirm(missionId)`, `scrub(sortieId, reason?)`, `land(...)`, `refly(sortieId): List<Long>`, `regenerateFlightPlan(sortieId, onQueued: (Long) -> Unit = {}): List<Long>`, private `advance(missionId, settings)`; `generateRunPlans` is exposed as a `Flow<Boolean>` (line 71).
- **`settings/`** — `Settings.kt` + `SettingsStore.kt`. `Settings` has exactly the §8 list: `daemonHost`, `daemonPort`, `daemonToken`, `coachWorkspacePath`, `defaultPattern`, `sortieLengthMinutes`, `weightUnit`, `distanceUnit`, `historyWindowDays`, `generateRunPlans`, `objectives`, `constraints`.
- **`domain/`** — `Week.kt`, `Pattern.kt`, `Missions.kt`, `Sorties.kt`, `Rollover.kt`, `SortiePlanning.kt` (`prepareCurrent(sortie, generateRunPlans)`; `simpleRunPlan` writes title `"Run"` with source `SIMPLE_RUN` when the toggle is off), `ReFly.kt` (the pure chooser), `Coach.kt` (whether Mission Control is configured: host and token each non-empty once trimmed), `CoachStatus.kt` (the draft's coach status, sealed interface + derivation, each variant naming the actions it offers), `SortieGenerationStatus.kt` (the sortie card's answer, plus `SortieGenerationAnswer` carrying the row's rho session id and the §5.4 Re-fly rule), `Exceptions.kt`.
- **`coach/`** — `ExerciseNames.kt`, `CoachSchema.kt`, `SchemaChecker.kt`, `CoachModels.kt`, `CoachReplyValidator.kt`, `CoachPromptBuilder.kt`, `PromptInputs.kt`. The row's current claims all hold.
- **`work/`** — all five files the brief names exist: `CoachTurnRunner.kt`, `GenerationWorker.kt`, `GenerationEnqueuer.kt`, `GenerationCanceller.kt`, `AppOpenSweep.kt`. `GenerationWorker.kt:436` is the invariant-7 label rule.
- **`ui/`** — `LiftoffShell.kt` hosts `MissionControlScreen` (111), `InFlightScreen` (127) and the Launchpad/Mission/Landed tabs (170–178); `ShellNav.kt`; `PlaceholderScreen.kt` is a shared eyebrow+title+one-line composable, imported by `LaunchpadScreen.kt` and `MissionScreen.kt` but not called in either — it is **not** Mission Control.
  - `ui/control/` — `MissionControlScreen.kt` + `MissionControlViewModel.kt`, `EquipmentSection/ViewModel`, `ConnectionSection/ViewModel` (Test connection), `BackupSection.kt` + `BackupViewModel.kt` (export/import). Mission Control is a real screen.
  - `ui/launchpad/` — `LaunchpadScreen.kt`: draft Retry in Confirm's slot (365); Planned `Regenerate` beside `Scrub` (432–436); Pending `Retry` / `Re-fly` / `Regenerate` / `View in rho` / `Scrub` (449–473); "View in rho" reveals a `rho session <id>` line (228–235), reveal state `rememberSaveable` keyed on the PENDING sortie's id (73).
  - `ui/inflight/` — screen, state(s), view model, `LandConfirmDialog`, `ScrubDialog`, `NoteDialog` (in-flight notes), `RunContent.kt`, `RunLandDialog.kt`, `RunLanding.kt`.
  - `ui/landed/`, `ui/mission/`, `ui/sortie/` (`FlightPlanSection.kt`, `PlanFormat.kt`), `ui/theme/` (11 files).

## Evidence for each milestone claim

- **M0**: `LiveCoachTest` appears nowhere under `app/src/test` — the live measurement has not been run. `coach/README.md` and `coach/COACH.md` exist. The three schemas are `app/src/main/resources/schemas/{outline,lift-plan,run-plan}.json`; golden prompts in `app/src/test/resources/prompts/`, fixtures in `app/src/test/resources/fixtures/`.
- **M6 (run plans)**: `SortiePlanning.prepareCurrent` writes the simple `SIMPLE_RUN` "Run" plan when `generateRunPlans` is off; `MissionManager.kt:365` refuses a RUN sortie regeneration while the toggle is off; In-Flight renders and lands a run sortie (`RunContent`, `RunLanding`, `RunLandDialog`). Tests: `InFlightRunTest`, `RunLandingTest`, `LaunchpadRegenerateTest`.
- **Everything the brief hedges on is on this branch**: `LaunchpadPendingStatusTest`, `LaunchpadSortieStatusTest`, `LaunchpadPlannedActionsTest`, `LaunchpadRegenerateTest`, `LaunchpadViewInRhoTest`, `LaunchpadCoachStatusTest` all exist and pass. So nothing needs to be reported as missing in the PR.
- **Data flow #3** (queueing on land) is still true: `land` → `advance` → one QUEUED `FLIGHT_PLAN` row handed to `GenerationEnqueuer`.

## Tests

89 test classes exist across the packages above. A docs-only change touches none of them; the gate is the whole suite (`:app:assembleDebug :app:testDebugUnitTest`), which the previous run's gate measured at 736 tests in 89 reports in about 1m30s warm.
