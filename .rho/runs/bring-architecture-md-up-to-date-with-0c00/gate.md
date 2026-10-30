# Gate — task 6: whole-document sweep, the suite run, the run's last commit

Run `bring-architecture-md-up-to-date-with-0c00`. 2026-10-30. Tree gated: `ccb28ea` (task 5 done),
`ARCHITECTURE.md` as rewritten by tasks 1–5.

## 1. The gate

Run detached from `app/build/t6-gate.sh`, log `app/build/t6-gate1.log`, sentinel line at the end:

```
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache
```

Before the run, the previous session's `app/build/test-results/testDebugUnitTest` was **moved** to
`app/build/t6-old-results-1793351077` (moved, not deleted — a stranded test executor can hold
`binary/output.bin`), and `--no-build-cache` was passed, so every report below comes from this
invocation. `:app:testDebugUnitTest` appears in the log with no `UP-TO-DATE` or `FROM-CACHE` marker,
i.e. it executed. No `gradlew --stop` (a stop is machine-wide and would take down another worktree's
daemon).

Verdict, last three lines of the log:

```
BUILD SUCCESSFUL in 2m 8s
46 actionable tasks: 1 executed, 45 up-to-date
EXIT=0
```

One attempt. No hang, so no re-run of the identical tree was needed — the run wrote 89 reports.
`:app:assembleDebug` was `UP-TO-DATE` because this run changes no Kotlin, resource or build file.

Report totals, read off `tests=`, `failures=`, `errors=`, `skipped=` on the 89
`app/build/test-results/testDebugUnitTest/TEST-*.xml` roots:

```
736 tests, 0 failures, 0 errors, 0 skipped, in 89 reports
```

That is the same 736 tests in 89 reports as the run's baseline, so no test count moved. All 19
classes `verify.sh` names have exactly one report from this invocation:
`DaemonConnectionCheckTest`, `MissionManagerTest`, `BackupRoundTripTest`, `MissionManagerReFlyTest`,
`SettingsStoreTest`, `SortiePlanningTest`, `CoachStatusTest`, `SortieGenerationStatusTest`,
`CoachTurnRunnerTest`, `GenerationWorkerTest`, `GenerationEnqueuerTest`, `GenerationCancellerTest`,
`AppOpenSweepTest`, `MissionControlScreenTest`, `LaunchpadPendingStatusTest`,
`LaunchpadPlannedActionsTest`, `LaunchpadViewInRhoTest`, `InFlightRunTest`, `RunLandingTest`.

## 2. The sweep, and what it found

`ARCHITECTURE.md` was read end to end (85 lines: topology 9–20, package table 28–37, resource
locations 41–46, data flow 50–54, invariants 58–69, milestones 73–85). **The sweep changed no line.**
Nothing outside the two tables contradicts the rewritten rows, and no leftover forward reference or
mismatched status word survived the rewrites.

`grep -niE 'stub|placeholder|comes next|come next|until M[0-9]' ARCHITECTURE.md` prints nothing
(exit 1). `PlaceholderScreen.kt` — a real file, and the one name that would have tripped that grep —
is not named anywhere in the document: task 3's `ui` row rewrite dropped it, and its unused imports
in `LaunchpadScreen.kt` / `MissionScreen.kt` are a Kotlin change and out of this run's scope.

Status words, checked against their rows:

| Where | Now | Verdict |
|---|---|---|
| `Exists yet?` cell, all 8 package rows | ✅ | matches the row; no `Partly` left (task 2 fixed `work`) |
| Status cell, M1–M6 | `Done — …` | matches each row's title and its evidence in `map.md` |
| Status cell, M0 | `Partial: … live measurement (`LiveCoachTest`) has not been run` | **deliberate, and still true**: `git ls-files \| grep -i livecoach` finds nothing, so the measurement has not been run. Plan Decision 1 keeps M0 short of Done and `verify.sh` exempts M0 from the "reads as done" check. Left as it is. |

Prose outside the tables, read against the source rather than assumed — every line still holds, so
none was edited:

| Line | Claim | Checked against |
|---|---|---|
| 12–20 | Topology: Room as source of truth, WorkManager generation jobs, the copied rho `DaemonClient`, the daemon on the owner's PC | `rho/net/DaemonClient.kt`, `work/GenerationWorker.kt`, `AppContainer.kt` |
| 30 | `MainActivity` hosts the navigation shell | `MainActivity.kt:15,47,58` (`LiftoffShell`), `:17,35` (`AppOpenSweep`) |
| 45–46 | Schemas in `app/src/main/resources/schemas/`, fixtures in `app/src/test/resources/fixtures/` | both directories exist, as does `app/src/test/resources/prompts/` |
| 53 | Data flow 2: the worker connects, runs the turn, validates the reply, stores it | `GenerationWorker.kt` |
| 54 | Data flow 3: when a sortie lands, the next sortie's generation is queued | `MissionManager.kt:287` `land` → `advance` at `:301` → `GenerationEnqueuer`; `map.md` checked the same line |
| 63 | Invariant 2: `domain/` and `coach/` are pure Kotlin | no `import android` in either package |
| 66 | Invariant 5: no DI framework, one `AppContainer` | no hilt / dagger / koin in `build.gradle.kts` |
| 67 | Invariant 6: generations resumable from the row's stored cursor | `GenerationDao.kt:33,57`, `GenerationWorker.kt:248` |
| 68 | Invariant 7: session labels never start with `run:` | `GenerationWorker.kt:436` |
| 69 | Invariant 8: up to 2 in-session repairs | `GenerationWorker.kt:464` ("§7.5.3's two repairs", counted on `Generation.attempt`) |
| 75 | Each milestone ends with `./gradlew :app:assembleDebug :app:testDebugUnitTest` passing | the one build-and-test step in `.github/workflows/ci.yml` |

## 3. The document's own checks, after the sweep

`verify.sh`'s document and scope sections (lines 58–74 and 248–531, run on their own from
`app/build/t6-doc-checks.sh` so no second Gradle run was started alongside the gate):
**50 passed, 0 failed**, exit 0, `BRIEF DONE.`

- package table 10 rows and milestone table 9 rows, every row 4 pipes like its header;
- `work` row 636 bytes (ceiling 891), M3 row 649 bytes (ceiling 1615);
- the `data`, `settings`, `domain`, `work` and `ui` rows each differ from their version at `8de2795^`;
- all 42 backticked `.kt` / `.json` / `.md` names in the package table resolve to tracked paths;
- no banned wording left in any package or milestone row;
- `tr -cd '\015' < ARCHITECTURE.md \| wc -c` → 0 CR bytes; `grep -c $'\ufffd'` → 0 replacement
  characters; `git ls-files --eol` → `attr/text=auto eol=lf`;
- scope: nothing outside `ARCHITECTURE.md` and this run folder changed since `8de2795`.
