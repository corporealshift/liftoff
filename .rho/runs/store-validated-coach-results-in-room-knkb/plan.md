# Plan

## Approach

Three pieces, matching the brief's three headings, each built on a shape the code already has.

The two store operations go on `MissionManager`. It already owns the DRAFT → ACTIVE transition, one `withTransaction` per operation, and the private `advance` that makes a sortie current.

**The outline operation** mirrors `confirm`: guard, insert the sorties, then `advance`. The differences:
- Each sortie's `focus` and `focusRationale` come from the validated outline, not the two hardcoded defaults.
- `Mission.outlineNotes` is written as part of the activation.
- `confirm`'s two conditions (DRAFT, and the current week) become returned refusals here instead of `check` failures. A third refusal is added: the outline must still match the Mission's pattern.

Reusing `advance` is what makes sortie 1 current. It is also what writes the simple 'Run' plan when run generation is off, and `simpleRunPlan` puts the outline's run focus into it. The `AwaitGeneration` branch stays empty, because nothing queues a generation in this run.

**The flight-plan operation** turns a `CoachLiftPlan` or `CoachRunPlan` into a `FlightPlanDraft` with `source = GENERATED` and hands it to the existing `FlightPlanDao.writePlan`. That method already does the rest:
- deletes the sortie's current plan;
- resolves exercise names to exercise ids through `ExerciseDao.resolve`;
- writes exercises, sets and segments in reply order.

Equipment ids are the coach's equipment keys, which `PlannedExercise.equipmentIds` already stores as strings, so they pass straight through. The sortie then moves with the existing `markPlanned`, but only when it was PENDING.

**The validated JSON.** `rawJson` must hold the validated JSON, and today `CoachReplyResult.Valid` carries only the typed plan. So `Valid` gains the extracted object's JSON text, which `validate` already has in hand. The operations take that text next to the plan, so the worker makes one call and never re-extracts. The caller's raw reply, which may have fences or prose around it, is never stored.

**Refusals** come back as a returned outcome, not an exception, because the caller is a background worker that has to branch on them (see Decisions).

**The generation helpers** are new `@Query` updates and two lookups on `GenerationDao`. Today it has only the CRUD that export and import needed.

No Room schema change is needed. Every field the brief names already has a column, and new `@Query` methods do not alter the schema; `data/SchemaExportTest.kt` is the guard that proves it. `coach` and `domain` gain no Android imports. The coach-plan → Room-row mapping lives in `data`, as `ReFlyCopy.kt` does. See `map.md` for what each file already holds.

## Files

- `coach/CoachModels.kt`, `coach/CoachReplyValidator.kt`: `Valid` carries the validated JSON text beside the plan.
- `data/MissionManager.kt`: the two store operations and the outcome they return, beside `confirm`.
- `data/CoachPlanCopy.kt` (new, beside `ReFlyCopy.kt`): maps a validated coach plan to a `FlightPlanDraft`.
- `data/GenerationDao.kt`: the status, session, cursor and attempt `@Query` updates, and the two newest-non-cancelled lookups.
- A shared test fixture loader (new, under `app/src/test/.../coach/`). It is lifted out of `CoachReplyFixtureTest`, where the loader is `private`. That test then uses the shared loader, and its `COVERED` set and coverage test stay as they are.
- `data/MissionManagerOutlineTest.kt`, `data/MissionManagerPlanTest.kt` (new): the two operations, fed from the fixtures.
- `data/GenerationDaoTest.kt`: extended with the helper tests.

No schema change, so `app/schemas/` is untouched.

## Order

1. Generation row helpers on `GenerationDao`, with their tests. Nothing depends on them yet, so this is the smallest self-contained step.
2. `Valid` gains the validated JSON. Lift the fixture loader into the shared helper. The coach tests still pass.
3. The coach-plan → `FlightPlanDraft` mapping and the flight-plan operation, with its tests: lift, run, every refusal, and replacing an existing plan.
4. The outline operation, with its tests: activation, focuses and notes, sortie 1 current, each refusal, and the simple 'Run' plan when run generation is off.
5. The full gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`), run once, detached.

## Testing

Robolectric with an in-memory database, in the `MissionManagerTest` shape (fixed clock, UTC zone). The tests are fed through the shared loader from `outline-valid.txt`, `lift-plan-valid.txt` and `run-plan-valid.txt`, validated against a context with pattern `RLRLR`.

Fixture setup:
- The lift fixture plans the sortie at pattern position 1. To make it current and PENDING, the test scrubs sortie 0 first.
- The run fixture plans position 0, with run generation on so that sortie 0 is left PENDING.

What the tests show:
- **Outline.** It activates the Mission and creates one sortie per pattern letter, in pattern order. Each sortie carries its focus and rationale, and the Mission holds the outline notes. Sortie 1 is current, and also planned, because the toggle is off and the pattern starts with a run. The Mission stays open with all five sorties, and only the first is planned.
- **Run generation off.** The outline's run sortie holds the simple 'Run' plan, with the outline's run focus as its notes.
- **Lift plan.** It is stored with title, estimated minutes, warmup and notes. Its exercises carry resolved exercise ids, equipment ids, rest and sets in order. The plan has `source = GENERATED` and `rawJson` equal to the validated JSON, and the sortie is PLANNED.
- **Run plan.** It is stored with its kind, target distance and pace, its segments in reply order, and `source = GENERATED`.
- **Replacement.** A plan that arrives for a sortie that already has one replaces it.
- **Refusals.** Each one changes nothing:
  - Mission no longer a DRAFT, Mission's week has ended, outline no longer matches the pattern;
  - sortie no longer current, IN_FLIGHT, LANDED or SCRUBBED.

  Each test reads the mission, sorties and plans back and asserts they are untouched, and checks that the outcome names the reason.
- **Generation helpers.**
  - Marking RUNNING, SUCCEEDED with `finishedAt`, FAILED with its error and `finishedAt`, and CANCELLED with `finishedAt`.
  - Saving the session id and `lastEventId`, and bumping `attempt`.
  - Finding the newest non-cancelled row for a sortie and for a Mission, with cancelled rows left out and a sortie's rows kept out of the Mission lookup.
- **Unchanged.** `MissionManagerTest`, `MissionManagerReFlyTest`, the coach tests, export/import and `SchemaExportTest` all still pass. `SchemaExportTest` is the one that proves no schema change was needed.
- Every wait has a timeout. The tests use a fixed clock with a UTC zone, so they behave the same on the CI runner.

## Risks

- `writePlan` is already a transaction inside the manager's, and it deletes the sortie's existing plan. Room nests the two transactions. The refusal tests therefore have to prove nothing was written, not only that the outcome is right, so every guard runs before the first write.
- `advance` does more than make sortie 1 current: it can close a Mission, and it prepares whatever it finds current. Reusing it is the point. The outline tests must show the Mission stays open.
- `markPlanned` rejects an illegal transition. The PLANNED step therefore stays conditional on the sortie being PENDING, as the brief words it.
- Adding a field to `Valid` changes a `coach` type. Its only constructor call is in `validate`, and tests read only `.plan`, so nothing else moves.
- `ARCHITECTURE.md` describes what `data` holds, and the brief asks for no doc change, so the plan leaves it. The verify step may want a row added.
- The known flake in the In-Flight screen test (closed Room pool) can fail a green tree. Re-run the gate before treating a failure there as a real break. Run the gate detached: `mkdir -p app/build` first, then read its exit status.

## Decisions

**Which generation row is "the newest non-cancelled one".** Changed by review: the plan chose to rank by the most recent activity on the row; the review chose newest by `createdAt`, then `id`, because the `generation` table has no column that records activity, and adding one would be a schema change the brief forbids unless it is unavoidable. Cancelled rows are still excluded outright rather than ranked last, as the plan chose. The alternative was ranking by `attempt`. The worker and Mission Control both want the live or most recent generation, and a cancelled row is a dead end that should not show up as the current status.

**Added by review — what "for a Mission" means in the generation lookup.** The Mission lookup returns only the Mission's own OUTLINE generations, not its sorties' Flight Plan generations. The draft screen needs this to show "outline failed, retry" (§4.2). If a sortie's newer generation could stand in for the outline's, it would show the wrong status. The sortie lookup already covers Flight Plan generations.

**Added by review — `finishedAt` on FAILED and CANCELLED.** The brief names `finishedAt` only for SUCCEEDED. The helpers also stamp it when a row is marked FAILED or CANCELLED, because those generations have finished too. Mission Control can then say when a generation stopped, whichever way it ended.

**Added by review — an outline for a past week, or one that no longer fits the pattern.** Besides "no longer a DRAFT", the outline operation also refuses, changing nothing, in two cases:
- The Mission's week has ended. `confirm` already requires the current week, and activating a past week would just let rollover scrub every sortie.
- The outline's entries no longer match the Mission's pattern. A draft's pattern can still be edited, so the outline may have been validated against an older pattern.

These are refusals, not exceptions, for the same reason as the two refusals the brief names.

**Added by review — where `rawJson` comes from.** `CoachReplyResult.Valid` carries the JSON text of the object it validated, and the operations store that text. The plan had the caller re-run the validator's internal extraction step, and fall back to the caller's raw reply text if that failed. But the raw reply can hold a code fence or prose, which is not "the validated JSON". Passing the text on `Valid` also means the worker makes one call instead of two.

**Added by review — a plan of the wrong type for the sortie.** A lift plan offered for a run sortie, or the reverse, is a caller bug, because the validator already rejected that mismatch. So the operation throws (`require`) instead of returning a refusal. Refusals are kept for stale results, which are an ordinary outcome.

**What a refusal looks like to the caller.** The brief says a stale result "changes nothing and says so", but not how it says so. The plan has each operation return a result value naming the outcome: stored, or refused with the reason. It never throws for the named cases. The alternative is the `MissionManager` house style, `check` → `IllegalStateException`, which the screens already catch and turn into "Couldn't land this sortie." A stale reply is an ordinary outcome of a background job, not a fault. The worker will call these operations from a loop and has to branch on the outcome (drop the result, move on) without an exception path. Either way the user sees nothing change on screen, which is the point of the refusal.

**The order the week's sorties are created in, and which one becomes current.** The plan creates one sortie per pattern letter, in the pattern's own order, which is the order the existing confirm-without-outline path already uses. Each sortie takes its focus and rationale from the outline entry at the same position. The validator guarantees those line up with the pattern it was given, and the operation re-checks this against the Mission's pattern. Sortie 1 is the first of those, made current by the existing advance-and-prepare step. The pattern fixes the shape of the week and which day each sortie lands on. The brief says the result is "the same shape the current 'confirm without outline' path produces", so the cards a user sees stay in the order they know, with the coach's text added to each.

**A second plan arriving for a sortie that already has one.** The plan replaces the existing plan, whatever wrote it, and keeps the sortie refusals to the cases the brief names. The alternative is to refuse whenever a plan already exists. A re-generation (a repair round, or a retry after a failed attempt) is the normal reason a second result arrives, and the user would expect the newer plan to win. Only a sortie the user has already started, landed or scrubbed is protected.

**The title of a generated run plan.** The plan stores the coach's own title, as it does for a lift plan. The brief lists a title only for the lift plan, so the alternative is to keep the simple plan's title "Run" on a plan the coach wrote. But `title` is a required field of the run-plan schema, and the PLANNED card shows the plan's title. A generated run would otherwise display "Run" while a generated lift shows the coach's wording. The 'Run' title stays where the design puts it: on the `SIMPLE_RUN` plan written when run generation is off.
