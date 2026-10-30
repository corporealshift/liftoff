# Task 3 — the `ui` package row

One file changed: `ARCHITECTURE.md`, line 37 (the `com.liftoff.app.ui` row), replaced whole by a byte-level
perl splice from `app/build/ui-row.txt`. No Kotlin, resource or build file touched; `PlaceholderScreen.kt`
left exactly as it is.

## The row

Before (`8de2795^`, 468 bytes, "stub" twice — once in each cell):

    | `com.liftoff.app.ui` | Navigation shell, Launchpad, Mission, In-Flight and Landed history as real
    screens, Mission Control stub, theme and shared composables. | ✅ (… Mission Control stub, `ui/theme`) |

After (780 bytes):

    | `com.liftoff.app.ui` | Navigation shell, Launchpad, Mission, In-Flight, Landed history and Mission
    Control as real screens, plus the shared sortie-plan and theme composables. | ✅ (`LiftoffShell.kt` and
    `ShellNav.kt` cold-start reopen into the in-flight sortie; `LaunchpadScreen.kt` with the draft's coach
    status line, the Pending and Planned cards' generation status, Retry, Regenerate, Re-fly, View in rho and
    Scrub; `MissionScreen.kt`; `InFlightScreen.kt` with Land, Scrub, notes and run content; `LandedScreen.kt`
    history list and detail view with its note editor; `ui/control/MissionControlScreen.kt` settings,
    equipment, Test connection and export/import; the shared plan section in
    `ui/sortie/FlightPlanSection.kt`; the theme composables in `ui/theme/LiftoffTheme.kt`) |

`ui/theme` is now backticked as `ui/theme/LiftoffTheme.kt` and `ui/sortie` as
`ui/sortie/FlightPlanSection.kt`, so every backticked name in the row is a tracked path, not a directory.

## Claims, each read off the source on this branch

| Claim in the row | Where it was checked |
|---|---|
| cold-start reopen into the in-flight sortie | `ui/LiftoffShell.kt` `coldStart` + `ShellNav.startNav(null, ids)` |
| draft's coach status line | `LaunchpadScreen.kt` `draftStatusLine` (`CoachStatus` Planning / Offline / Failed / NotConfigured) |
| Pending card's generation status | `pendingStatusLine(state.generationStatus)` (`SortieGenerationStatus`) |
| Planned card's generation status | `PlannedContent`: `state.generationStatus == SortieGenerationStatus.RePlanning` → "Coach is re-planning…" |
| Retry | `PendingActions`: `PrimaryButton(text = "Retry")` under a Failed status when `regenerateAvailable` |
| Regenerate | `PlannedActions`: `UnderlinedTextButton(text = "Regenerate")` when `planned.regenerateAvailable` |
| Re-fly | `PendingActions`: `UnderlinedTextButton(text = "Re-fly")` when `pending.reFlyAvailable` |
| View in rho | `PendingActions`: `UnderlinedTextButton(text = "View in rho")` when `pending.rhoSessionId != null` |
| Scrub | `PendingActions` and `PlannedActions` both draw `Scrub` |
| In-Flight Land, Scrub, notes, run content | `InFlightScreen.kt` "Land", "Scrub", "Sortie note", "Note"; `RunContent.kt` |
| Landed history list + detail with note editor | `LandedScreen.kt` `TitleBlock(eyebrow = "History", title = "Landed")`, "Edit note" |
| Mission Control settings, equipment, Test connection, export/import | `MissionControlScreen.kt` cards EQUIPMENT / BACKUP / CONNECTION / COACH / MISSION / UNITS / RUNS / OBJECTIVES AND CONSTRAINTS; `ConnectionSection.kt` "Test connection"; `BackupSection.kt` "Export", "Import" |
| shared sortie-plan composable | `ui/sortie/FlightPlanSection.kt`, used by launchpad, mission, inflight and landed |
| theme composables | 11 files under `ui/theme/`, entry `LiftoffTheme.kt` |

## Done-when, with the commands and their real output

1. `git show 8de2795^:ARCHITECTURE.md | grep -m1 'com\.liftoff\.app\.ui' > app/build/ui-base.txt` then
   `grep -m1 'com\.liftoff\.app\.ui' ARCHITECTURE.md > app/build/ui-cur.txt` then `cmp -s …`
   → `differs (good)`; `git diff 8de2795^ -- ARCHITECTURE.md` changes 5 lines in total — `data`, `settings`,
   `domain` (task 1), `work` (task 2), `ui` (this task). `rho` and `coach` are untouched.
2. `grep -m1 'com\.liftoff\.app\.ui' ARCHITECTURE.md | grep -ci stub` → `0`, `exit=1`.
3. Every backticked name resolves against `git ls-files` (verify.sh's own `(^|/)$tok$` rule, run over the row):
   `LiftoffShell.kt`, `ShellNav.kt`, `LaunchpadScreen.kt`, `MissionScreen.kt`, `InFlightScreen.kt`,
   `LandedScreen.kt`, `ui/control/MissionControlScreen.kt`, `ui/sortie/FlightPlanSection.kt`,
   `ui/theme/LiftoffTheme.kt` — all OK. The only other backticked token is the package name
   `com.liftoff.app.ui` in the first cell, which is what every row of this table carries and what
   verify.sh's `pkg_rows` matches on.
4. `grep -m1 'com\.liftoff\.app\.ui' ARCHITECTURE.md | tr -cd '|' | wc -c` → `4`.

## Checks run

- The document + scope half of `verify.sh` (`sed -n '58,74p'` + `sed -n '248,531p'` into
  `app/build/doc-checks.sh`, then `bash -n` and `bash`): **43 passed, 7 failed, exit 1**. Before this task it
  was 43/9 — the two `ui` checks (`no longer carries the stale wording` for 'stub', and `has been rewritten`)
  flipped to ✓. The 7 left are exactly tasks 4 and 5's milestone checks: M3 length (3230 > 1615), M3 status
  "In progress", M5 "Partial", M6 "Not started", M2 "placeholder", M2 and M3 forward references.
- `check_table_shape` for both tables: package table 10 rows × 4 pipes, milestone table 9 rows × 4 pipes.
- File hygiene: `tr -cd '\015' < ARCHITECTURE.md | wc -c` → 0; `grep -c $'\ufffd' ARCHITECTURE.md` → 0 (exit 1);
  `git ls-files --eol` → `i/lf w/lf attr/text=auto eol=lf`.
- No build gate for this task: `tasks.md` says only task 6 runs it and "a markdown row cannot break the
  build", so tasks 1–5 are checked by their own structural Done-when. Nothing outside `ARCHITECTURE.md` and
  the run folder changed, which the scope check above confirms ✓.
