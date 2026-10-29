# Bring ARCHITECTURE.md up to date with what is built

Documentation only: update ARCHITECTURE.md so its package table and milestone table match the code on this branch. Change no Kotlin, resource or build files. Check each claim against the source tree (`app/src/main/java/com/liftoff/app/...`) before writing it.

Package table ('Exists yet?' and responsibility cells). Every row should read ✅ and name what is really there:
- `rho`: the copied `net/DaemonClient.kt`, `protocol/Events.kt` and `protocol/RpcCodes.kt`, plus `DaemonConnectionCheck.kt`.
- `data`: Room entities, the DAOs, LiftoffDatabase, MissionManager, export/import, and the Re-fly copy.
- `settings`: SettingsStore with every DESIGN.md §8 setting.
- `domain`: week, pattern, Mission and sortie rules, rollover, sortie planning, Re-fly chooser, and the draft and sortie coach-status derivations.
- `coach`: unchanged unless it's wrong.
- `work`: change 'Partly' to ✅. List CoachTurnRunner, GenerationWorker, GenerationEnqueuer, GenerationCanceller and AppOpenSweep in short phrases; cut the current long run-on description down to one or two sentences.
- `ui`: Mission Control is a real screen in `ui/control` (settings, equipment, Test connection, export/import), not a stub. The Launchpad draws the draft's coach status, and the Pending and Planned cards' generation status, Retry, Regenerate, Re-fly, View in rho and Scrub. The row also covers Mission, In-Flight, Landed, the shell and `ui/theme`.

Milestone table:
- M1–M6 are Done. Rewrite each status as one short sentence with no 'comes next' or 'placeholder' left over. M2 must no longer say In-Flight is a placeholder. M3 is the worker, queueing, Regenerate and the Launchpad status. M5 is Landed, Re-fly and export/import. M6 says the run-generation toggle is honoured: a simple 'Run' plan when it's off, a generated run Flight Plan when it's on.
- M0 says the coach/ template, schemas, validator and prompt builder exist and that the live measurement (`LiveCoachTest`) hasn't been run.

If the Launchpad's Pending status lines, Planned Regenerate or View in rho are not on this branch, describe only what is there, and say in the PR which ones are missing. Keep the rest of the document (topology, invariants, data flow) unless a line is plainly wrong. In particular, the data-flow line about queueing is still true.

Must not break: the build gate. Follow CLAUDE.md conventions: commit message `docs: ...`, stage ARCHITECTURE.md by name, LF line endings. Decide unclear points, record them in the plan, and carry on. The gate can hang once for about 5 minutes: run it detached and re-run it once if it hangs.

Done when: ARCHITECTURE.md matches the code as described and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
