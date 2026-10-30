# Tasks — bringing ARCHITECTURE.md up to date with what is built

Six tasks. Each one ends in a commit. Tasks 1–5 change only `ARCHITECTURE.md`; task 6 does the consistency
sweep, runs the gate, and closes the run.

## No task writes a test

`verify.sh` names 19 test classes and all 19 already exist and pass (`DaemonConnectionCheckTest`,
`MissionManagerTest`, `BackupRoundTripTest`, `MissionManagerReFlyTest`, `SettingsStoreTest`, `SortiePlanningTest`,
`CoachStatusTest`, `SortieGenerationStatusTest`, `CoachTurnRunnerTest`, `GenerationWorkerTest`,
`GenerationEnqueuerTest`, `GenerationCancellerTest`, `AppOpenSweepTest`, `MissionControlScreenTest`,
`LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`, `LaunchpadViewInRhoTest`, `InFlightRunTest`,
`RunLandingTest`). None of them is a name to write; they are names this run must not break. Do not create, rename,
skip or delete a test class, and do not touch any Kotlin, resource or build file.

## Rules that bind every task

- `ARCHITECTURE.md` is the only file changed. Scratch goes under `app/build/` (gitignored). `verify.sh` fails the
  run if anything outside `ARCHITECTURE.md` and `.rho/runs/bring-architecture-md-up-to-date-with-0c00/` differs
  from this run's base commit `8de2795`. Do not edit `brief.md`, `plan.md` or `verify.sh`; `map.md` is notes you may
  add to.
- Only task 6 runs the build gate. A markdown row cannot break the build, so tasks 1–5 are checked by the structural
  checks in their own Done-when, not by a suite run.
- Commit with a `docs: ` message, staging `ARCHITECTURE.md` by name (never `git add -A`). No push, no PR.
- A markdown table row is one line with exactly 4 pipes, like its header. No stray `|`, no wrapped row.
- LF endings, which `.gitattributes` pins. Before committing, `grep -c $'\r' ARCHITECTURE.md` must print 0 — the
  same test `verify.sh` uses. `tr -cd '\r' < ARCHITECTURE.md | wc -c` is an equivalent byte count.
- Wording `verify.sh` rejects, so never write it:
  - in **any** package row: `stub`, `placeholder`;
  - in a package row's **last** cell (`Exists yet?`): `partly`, `partial`, `not started`, `not begun`, `not yet`,
    `in progress`;
  - in **any** milestone row: `placeholder`, `comes next`, `come next`, `coming next`, `next milestone`, `next up`,
    `until M0`…`until M9`, `comes with generation`, `come with generation`;
  - in a milestone's **last** cell for M1–M6: `not started`, `not begun`, `not yet`, `in progress`, `partial`,
    `partly`. (M0 is exempt from that last list — see task 4.)
- Every file name in backticks ending `.kt`, `.json` or `.md` on a package-table line must be a tracked path.
  `verify.sh` resolves each one against `git ls-files`. Every name used in the entries below is a tracked path,
  checked against this branch; the one thing that is *not* a file is `reflyDraft`, a function in `ReFlyCopy.kt`, so
  never put it in backticks.
- Two rows have a hard length ceiling, because the brief calls them run-ons. Measure a row the way `verify.sh` does,
  by grepping it rather than by line number (line numbers move if a row is ever split):
  `grep -m1 'com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\n' | wc -c` and
  `grep -m1 '^| M3 |' ARCHITECTURE.md | tr -d '\n' | wc -c`. The `work` row (line 36 today) is 1782 bytes and must
  come to **891 or fewer**; the M3 row (line 82 today) is 3230 bytes and must come to **1615 or fewer**.
- `verify.sh` also fails a row that was never rewritten: the `data`, `settings`, `domain`, `work` and `ui` rows must
  each differ from their version at `8de2795^`. `git diff 8de2795^ -- ARCHITECTURE.md` shows whether they do. The
  `rho` and `coach` rows are exempt — they are already right, so do not churn them.
- Rows contain em dashes (line 35 has 1, line 82 has 7, lines 79–85 have 1–7 each; line 36 has none). Anchor an
  edit on a short dash-free substring of the line, or rewrite the whole line with a byte-level substitution
  (`perl -CSD`) and confirm the bytes with `od -c`. After any whole-row rewrite, `grep -c $'\ufffd' ARCHITECTURE.md`
  must print 0: that catches an em dash corrupted into a replacement character.

## The tasks

- [x] 1. Package table: the `data`, `settings` and `domain` rows, and a check of `rho`
  Lines 31–34. `rho` (31): confirm it already names `net/DaemonClient.kt`, `protocol/Events.kt`,
  `protocol/RpcCodes.kt` and `DaemonConnectionCheck.kt`, and keep the rho commit hash `ffce07d3…` — invariant 4
  depends on it. Edit only if a claim is wrong. `data` (32): the responsibility and the ✅ cell must name
  `MissionManager.kt` and the Re-fly copy `ReFlyCopy.kt` beside the entities, DAOs, `LiftoffDatabase.kt` and the
  export/import files it already names. `settings` (33): replace "(units, run-toggle)" with the §8 list — daemon
  host/port/token, coach workspace path, default pattern, sortie length, weight and distance units, history window,
  run-generation toggle, objectives, constraints — and name `Settings.kt` beside `SettingsStore.kt`. `domain` (34):
  add the draft coach status `CoachStatus.kt` and the sortie generation status `SortieGenerationStatus.kt` to what
  the row already names.
  Done when: `git diff 8de2795^ -- ARCHITECTURE.md` shows the `data`, `settings` and `domain` rows changed and the
  `rho` row unchanged or corrected with a reason in the commit body; every backticked file name in the table resolves
  to a tracked path; each row you touched has 4 pipes; `grep -c $'\r' ARCHITECTURE.md` prints 0.
  Commit `docs: ...`.

- [x] 2. Package table: the `work` row — ✅ and cut to one or two sentences
  Line 36. Replace `Partly (…)` and its 1782-byte run-on with a ✅ cell that names the five files in short phrases:
  `CoachTurnRunner.kt`, `GenerationWorker.kt`, `GenerationEnqueuer.kt`, `GenerationCanceller.kt`, `AppOpenSweep.kt`.
  One or two sentences in total, the whole row 891 bytes or fewer. The resume, supersede and offline detail being
  cut is not lost: it lives in `DESIGN.md` §7.3 and §10 and in the code's own comments — say that in the commit
  body. Same task, no edit expected: read the `coach` row (line 35) against the seven files in `coach/` and change
  it only if a claim is wrong; `map.md` checked that every claim holds.
  Done when: `grep -m1 'com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\n' | wc -c` is 891 or less; the `work`
  row differs from its version at `8de2795^`; its last cell contains none of partly/partial/not started/in progress;
  the five names resolve; the `coach` row is unchanged or the commit body says which claim was wrong.
  Commit `docs: ...`.

- [x] 3. Package table: the `ui` row
  Line 37. Mission Control is a real screen in `ui/control` — settings, equipment, Test connection, export/import —
  and the word "stub" must not appear anywhere in the row, in either cell; it appears twice today. Name the
  Launchpad's controls: the draft's coach status, the Pending and Planned cards' generation status, Retry,
  Regenerate, Re-fly, View in rho and Scrub. Keep the rest the row covers: the navigation shell, Mission, In-Flight
  (Land, Scrub, notes, run content), Landed history and detail, `ui/sortie`, `ui/theme`. Do not touch
  `PlaceholderScreen.kt` — removing its unused imports is a Kotlin change and out of scope.
  Done when: the `ui` row differs from its version at `8de2795^`;
  `grep -m1 'com\.liftoff\.app\.ui' ARCHITECTURE.md | grep -ci stub` prints 0 (grep exits 1 when it finds none);
  every backticked name in the row resolves to a tracked path; the row has 4 pipes. Commit `docs: ...`.

- [ ] 4. Milestones M0–M3
  Lines 79–82. M2 (81): drop "In-Flight is a placeholder until M4" and "Regenerate comes with generation (M3)";
  one short sentence reading Done over what M2 really is — domain state machines, draft/confirm UI, Launchpad, Scrub.
  M3 (82): the longest cell in the document, 3230 bytes with 7 em dashes. Cut it to 1615 bytes or fewer, reading
  Done, covering the worker, queueing, Regenerate and the Launchpad's generation status in short phrases that point
  at the files rather than restating the rules; no "In progress" and no "…come next" tail. M0 (79): stays short of
  Done (plan Decision 1) and must still say the coach workspace template, the three schemas, the validator and the
  prompt builder exist and that live measurement (`LiveCoachTest`) has not been run. M1 (80) already reads Done;
  leave it or tidy it.
  Done when: `grep -m1 '^| M3 |' ARCHITECTURE.md | tr -d '\n' | wc -c` is 1615 or less; the M2 and M3 rows differ
  from their version at `8de2795^`; no milestone row contains placeholder / comes next / come next / next milestone /
  next up / `until M<n>` / comes with generation; M3's last cell has no "In progress";
  `tr -cd '\r' < ARCHITECTURE.md | wc -c` prints 0 and `grep -c $'\ufffd' ARCHITECTURE.md` prints 0, which is how you
  catch an em dash corrupted into a replacement character; each row has 4 pipes. Commit `docs: ...`.

- [ ] 5. Milestones M4–M6
  Lines 83–85. M5 (84): from "Partial: …" to one short sentence reading Done over Landed history, Re-fly and
  export/import. M6 (85): from "Not started" to one short sentence reading Done that says the run-generation toggle
  is honoured — a simple "Run" plan when it is off, a generated run Flight Plan when it is on. M4 (83) already reads
  Done; shorten it to one sentence if it is not already one.
  Done when: the M5 and M6 rows differ from their version at `8de2795^`; the last cell of M4, M5 and M6 contains none
  of partial/partly/not started/not yet/in progress; each of the three rows has 4 pipes; the milestone table still
  has 7 rows and its header is unchanged. Commit `docs: ...`.

- [ ] 6. Whole-document sweep, the gate, and the run's last commit
  Read `ARCHITECTURE.md` end to end once and fix only what the rewritten tables now contradict: a leftover "stub",
  "placeholder" or "comes next" outside the tables, or a status word that no longer matches its row. Topology,
  resource locations, data flow and the invariants stay as they are — data-flow line 3 (queueing on land) is still
  true, and `map.md` checked it. Then run the gate detached, logging under `app/build/` with an `EXIT=` sentinel:
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. Re-run the identical tree once if it hangs (a run
  that writes no `TEST-*.xml`); do not run `gradlew --stop`. Write the command, the `BUILD …` line, the report
  totals and the sweep's findings into `.rho/runs/bring-architecture-md-up-to-date-with-0c00/gate.md` — that path
  is inside `verify.sh`'s scope exemption for `.rho/`, so it is the run's evidence even when the sweep changed no
  line — and repeat the gate result in the commit body.
  Done when: the gate log ends `BUILD SUCCESSFUL` with 0 failed tests (the last whole-suite run was 736 tests in 89
  reports; a different count is not a failure here, but note it); `gate.md` is committed;
  `grep -niE 'stub|placeholder|comes next|come next|until M[0-9]' ARCHITECTURE.md` prints nothing;
  `git status --short` is clean.
