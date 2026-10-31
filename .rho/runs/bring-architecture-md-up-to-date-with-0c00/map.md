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
- **`ui/`** — `LiftoffShell.kt` hosts `MissionControlScreen` (111), `InFlightScreen` (127) and the Launchpad/Mission/Landed tabs (170–178); `ShellNav.kt`; `PlaceholderScreen.kt` is a shared eyebrow+title+one-line composable, imported by `LaunchpadScreen.kt:31` and `MissionScreen.kt:22` but not called in either — it is **not** Mission
   Control. Leave the imports alone: deleting one is a Kotlin change, and the document just stops calling Mission
   Control a stub. `LaunchpadRegenerateTest.kt` exists (the plan names it among the tests the Launchpad row rests
   on); it is not one of verify.sh's 19.
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

## The check (verify.sh)

`verify.sh` is this run's definition of done, in four sections:

1. **CI gate** — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, the one build-and-test step in `.github/workflows/ci.yml`. Run detached with a log under `app/build/`; stale `app/build/test-results` moved aside first (never `rm`, a stranded executor holds `binary/output.bin`) and `--no-build-cache`, so every report below comes from that invocation; retried once on a hang.
2. **19 named classes**, each of which must have run in that invocation with nothing failed or skipped: `DaemonConnectionCheckTest`; `MissionManagerTest`, `BackupRoundTripTest`, `MissionManagerReFlyTest`; `SettingsStoreTest`; `SortiePlanningTest`, `CoachStatusTest`, `SortieGenerationStatusTest`; `CoachTurnRunnerTest`, `GenerationWorkerTest`, `GenerationEnqueuerTest`, `GenerationCancellerTest`, `AppOpenSweepTest`; `MissionControlScreenTest`; `LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`, `LaunchpadViewInRhoTest`; `InFlightRunTest`, `RunLandingTest`. All 19 exist today, so none of them is what makes the script fail — the plan writes no test, so there is no new name to demand.
3. **ARCHITECTURE.md's two tables** — rejects the wording the brief says is wrong rather than demanding new wording, measures the `work` and M3 rows against the base version (they must shrink), requires the `data`/`settings`/`domain`/`work`/`ui` rows to have actually changed, and resolves every backticked file name in the package table to a tracked path. `rho`, `coach` and M0 are exempt from the "must have changed" / "reads as done" checks.
4. **Scope** — nothing outside `ARCHITECTURE.md` and `.rho/runs/bring-architecture-md-up-to-date-with-0c00/` changed since the run's base commit `8de2795` (`run: brief`), counting committed, worktree and untracked; plus ARCHITECTURE.md keeps LF endings.

Measured on untouched code: gate green (736 tests, 0 failures, 0 skipped, `BUILD SUCCESSFUL`), **55 passed / 15 failed, exit 1**. The 15 are the document checks listed in verify.sh's header comment. Scratch copies used while writing it are under `app/build/` (gitignored): `arch-done*.md`, `arch-bad*.md`, `vd*.sh`, `verify-*.log`.

## What verify.sh enforces, cell by cell (read this before editing a row)

Checked at HEAD `b568c53` (run: verify). `ARCHITECTURE.md` is byte-identical to its version at `8de2795^`, so every
document check still fails and every row below is still the original.

- Rows are found by content, not by line number: a package row by the `com.liftoff.app...` name in its first cell, a
  milestone row by its label cell with `*` and backticks stripped. Reordering rows or rewording a title cell is fine.
- "Last cell" means the final cell of the row: the `Exists yet?` cell, or the milestone Status cell.
- Banned wording, and where each list is applied:
  - `partly|partial|not started|not begun|not yet|in progress` - the last cell of **all eight** package rows (today
    only `work` fails), and the last cell of **M1-M6** (today M3 "In progress", M5 "Partial", M6 "Not started"). M0 is
    exempt, so `Partial:` in M0's cell is allowed to stay.
  - `stub` - the **whole** of all eight package rows (today `ui` fails twice).
  - `placeholder` - the **whole** of all seven milestone rows (today M2 fails).
  - `comes next|come next|coming next|next milestone|next up|until m[0-9]|comes? with generation` - the **whole** of
    all seven milestone rows, case-insensitive (today M2 "comes with generation (M3)" and "until M4", M3's "come
    next" tail). A plain "next" that is not one of these phrases is not caught, but avoid it in a milestone cell.
  - A denial is not caught: `negated()` strips "not a stub" / "no longer the placeholder" before matching. Do not
    rely on that; just do not write the word.
- "Was it rewritten at all" applies to exactly five package rows: `data`, `settings`, `domain`, `work`, `ui`, compared
  byte-for-byte with the row at `8de2795^`. There is **no** such check on any milestone cell; M2/M3/M5/M6 have to
  change only because the wording checks above bite them today. `rho` and `coach` are exempt from the rewrite check,
  so leave them alone rather than manufacture churn.
- Length ceilings, measured as `printf '%s' "$row" | wc -c` (no trailing newline), against the base row: the `work`
  row is 1782 bytes and must be **891 or fewer**; the M3 row is 3230 bytes and must be **1615 or fewer**. Measure a
  line the same way: `sed -n '36p' ARCHITECTURE.md | tr -d '\n' | wc -c`.
- Table shape: every row after a table's header must have as many pipes as the header, so 4 for both tables. The
  Resource locations table (3 pipes) and the Invariants table (4) are not checked.
- File resolution: only lines containing `com.liftoff.app` are scanned, so only the package table (lines 30-37), and
  only backticked tokens ending `.kt`, `.json` or `.md`. Each must match a tracked path's tail. The milestone table
  is not scanned, which is why M0 can name `LiveCoachTest`.
- Scope: `git diff --name-only 8de2795^` plus untracked files, minus `ARCHITECTURE.md` and anything under `.rho/`. So
  a scratch file under `app/build/` is invisible and a note under the run folder is allowed and committable.

## Em dashes in the rows being rewritten

`od -c` shows an em dash as the three bytes `342 200 224`. Counts per line: 35 has 1; 36 has **none**, so the `work`
row can be rewritten whole with the edit tool; 79 has 1, 80 has 2, 81 has 2, 82 has **7**, 83 has 1, 84 has 2, 85 has
1. The file is UTF-8, LF, no CR bytes today. `grep -c $'\r' ARCHITECTURE.md` prints 0 and is the check `verify.sh`
uses (line 501, `grep -q $'\r' "$DOC"`); `tr -cd '\r' < ARCHITECTURE.md | wc -c` agrees (0). `grep -c $'\ufffd'
ARCHITECTURE.md` prints 0 today and is a reliable check for a character corrupted into U+FFFD — tested against a file
with one deliberate U+FFFD, where it printed 1. Beware of your own shell quoting: `$'\r'` written inside a
double-quoted command string is not ANSI-C quoted and gives a nonsense count; run these checks as plain commands.

## Names that resolve to tracked paths (verified against `git ls-files`)

`net/DaemonClient.kt`, `protocol/Events.kt`, `protocol/RpcCodes.kt`, `DaemonConnectionCheck.kt`;
`LiftoffDatabase.kt`, `MissionManager.kt`, `DatabaseExport.kt`, `DatabaseImport.kt`, `BackupDocument.kt`,
`ReFlyCopy.kt`, `CoachPlanCopy.kt`; `Settings.kt`, `SettingsStore.kt`; `Week.kt`, `Pattern.kt`, `Missions.kt`,
`Sorties.kt`, `Rollover.kt`, `SortiePlanning.kt`, `ReFly.kt`, `Coach.kt`, `CoachStatus.kt`,
`SortieGenerationStatus.kt`; `CoachTurnRunner.kt`, `GenerationWorker.kt`, `GenerationEnqueuer.kt`,
`GenerationCanceller.kt`, `AppOpenSweep.kt`; `LiftoffShell.kt`, `ShellNav.kt`, `MissionControlScreen.kt`,
`MissionControlViewModel.kt`, `EquipmentSection.kt`, `ConnectionSection.kt`, `BackupSection.kt`,
`BackupViewModel.kt`, `LaunchpadScreen.kt`, `InFlightScreen.kt`, `NoteDialog.kt`, `RunContent.kt`, `RunLanding.kt`,
`RunLandDialog.kt`, `LandConfirmDialog.kt`, `ScrubDialog.kt`, `FlightPlanSection.kt`, `PlanFormat.kt`,
`PlaceholderScreen.kt`; `MainActivity.kt`, `AppContainer.kt`, `LiftoffApplication.kt`; `coach/README.md`,
`coach/COACH.md`, `outline.json`, `lift-plan.json`, `run-plan.json`. **Not** a file: `reflyDraft` (a function in
`ReFlyCopy.kt`), and `LiveCoachTest` (does not exist).

## Tasks (tasks.md, committed as `run: tasks`)

Six tasks, each ending in a `docs: ` commit that stages `ARCHITECTURE.md` by name: 1 `data`/`settings`/`domain`
package rows (plus a check of `rho`); 2 the `work` row cut to <=891 bytes (plus the `coach` check); 3 the `ui` row;
4 milestones M0-M3 (M3 cut to <=1615 bytes); 5 milestones M4-M6; 6 the whole-document sweep, the detached gate run,
and `.rho/runs/bring-architecture-md-up-to-date-with-0c00/gate.md` as the gate evidence. No task writes a test: all
19 classes verify.sh names already exist and pass.
