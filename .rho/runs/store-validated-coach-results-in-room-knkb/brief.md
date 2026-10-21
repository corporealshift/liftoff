# Store validated coach results in Room

Add the data operations that store a validated coach reply. These are what the generation worker will call. This run has no worker and no network: only Room writes and their tests. Read DESIGN.md §4.2, §4.3, §5.3, §7.2, §7.5 and §7.6 first.

Build on what's already in the repo:
- the validator in `com.liftoff.app.coach` (`CoachReplyValidator.kt`, `CoachModels.kt`), which turns a reply into a typed outline, lift plan or run plan
- `MissionManager` in `com.liftoff.app.data`, with `confirm`, `advance` and the `Preparation` decision in `domain/SortiePlanning.kt`. Its `AwaitGeneration` branch does nothing yet.
- `FlightPlanDao.writePlan` with `FlightPlanDraft`
- `Mission.outlineNotes`, `Sortie.focus`/`focusRationale`, the `Generation` entity and `GenerationDao`
- the 'Generate run plans' setting

**Outline result.** Given a DRAFT Mission and a validated outline, in one transaction:
- activate the Mission with one sortie per pattern letter, each with the outline's focus and rationale
- store the outline notes
- make sortie 1 current through the existing advance-and-prepare step

The result is the same shape the current 'confirm without outline' path produces, plus the outline's text. A run sortie still gets its simple 'Run' plan when run generation is off. If the Mission is no longer a DRAFT, the operation changes nothing and says so.

**Flight Plan result.** Given a sortie and a validated lift plan or run plan, in one transaction:
- write the plan with source GENERATED and `rawJson` set to the validated JSON
- for a lift: title, estimated minutes, warmup, notes, and exercises with their resolved exercise ids, equipment ids, rest and sets
- for a run: run kind, target distance and pace, and segments
- set the sortie PLANNED if it was PENDING

If the sortie is no longer current, or is IN_FLIGHT, LANDED or SCRUBBED, the operation changes nothing and says so. A later run uses that to drop stale results.

**Generation row helpers.** Add what the worker will need on `GenerationDao` to:
- mark a row RUNNING, SUCCEEDED (with `finishedAt`), FAILED (with its error) or CANCELLED
- save the session id and `lastEventId`
- bump `attempt`
- find the newest non-cancelled generation for a sortie or a Mission

If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. Don't change the Room schema unless it's unavoidable. If it is, add a real migration (never a destructive one) and update the exported schema. `coach` and `domain` stay free of Android imports. Every wait in a test needs a timeout. Tests must also pass on the GitHub CI runner (ubuntu-latest, UTC). A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: `MissionManager`'s existing behaviour and tests (confirm, scrub, launch, land, Re-fly, rollover), the coach tests, export/import, every screen, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- Robolectric tests with an in-memory database, fed from the existing valid reply fixtures in `app/src/test/resources/fixtures/coach/`, show:
  - an outline activating the Mission with the right sorties, focuses and notes, and making sortie 1 current
  - a lift plan stored with its exercises and sets, and the sortie PLANNED
  - a run plan stored with its segments
  - each refusal (Mission not a draft; sortie no longer current) changing nothing
  - the generation row helpers working
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
