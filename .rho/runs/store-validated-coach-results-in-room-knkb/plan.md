# Plan

## Approach

Three pieces, matching the brief's three headings, each built on a shape the code already has.

The two store operations go on `MissionManager`, which already owns the DRAFT → ACTIVE transition, the one-`withTransaction`-per-operation shape, and the private `advance` that makes a sortie current. The outline operation mirrors `confirm` exactly — same guard, insert the sorties, then `advance` — with the sorties' `focus` and `focusRationale` taken from the validated outline instead of the two hardcoded defaults, and `Mission.outlineNotes` written on the activation. Reusing `advance` is what makes sortie 1 current and what writes the simple 'Run' plan when run generation is off, because that is precisely what `confirm` does today; the `AwaitGeneration` branch stays empty, since nothing queues a generation in this run. The flight-plan operation converts a `CoachLiftPlan` or `CoachRunPlan` into a `FlightPlanDraft` with `source = GENERATED` and hands it to the existing `FlightPlanDao.writePlan`, which already deletes the sortie's current plan, resolves exercise names to exercise ids, and writes exercises, sets and segments in reply order; then the sortie moves with the existing `markPlanned`, only when it was PENDING. A `Valid` result carries only the typed plan, so both operations also take the validated JSON text from their caller; the worker and the tests get it from the validator's own extraction step, which is `internal` in the same Gradle module. The refusal cases are reported as a returned outcome rather than an exception, because the caller is a background worker that has to branch on them (see Decisions). The generation helpers are new `@Query` updates and two lookups on `GenerationDao`, which today has only the CRUD the export and import needed.

Nothing here needs a Room schema change: every field the brief names already has a column, and new `@Query` methods do not alter the schema — `data/SchemaExportTest.kt` is the guard that proves it. `coach` and `domain` gain no Android imports; the coach-plan → Room-row mapping sits in `data`, as `ReFlyCopy.kt` does. See `map.md` for what each file already holds.

## Files

- `data/MissionManager.kt` — the two store operations and the outcome they return, beside `confirm`.
- `data/CoachPlanCopy.kt` (new, beside `ReFlyCopy.kt`) — the validated coach plan → `FlightPlanDraft` mapping, so `domain` and `coach` keep their shape.
- `data/GenerationDao.kt` — the status/session/attempt `@Query` updates and the two newest-non-cancelled lookups.
- `data/MissionManagerOutlineTest.kt`, `data/MissionManagerPlanTest.kt` (new) — the two operations, fed from the fixtures.
- `data/GenerationDaoTest.kt` — extended with the helper tests.

No schema change, so `app/schemas/` is untouched.

## Order

1. Generation row helpers on `GenerationDao`, with their tests. Nothing depends on them yet, so this is the smallest self-contained step.
2. The coach-plan → `FlightPlanDraft` mapping and the flight-plan operation, with its tests: lift, run, both refusals, and the replace-an-existing-plan case.
3. The outline operation, with its tests: activation, focuses and notes, sortie 1 current, the not-a-draft refusal, and the simple 'Run' plan when run generation is off.
4. The full gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`), run once, detached.

## Testing

Robolectric with an in-memory database, in the `MissionManagerTest` shape (fixed clock, UTC zone), fed through the fixture loader in `coach/CoachReplyFixtureTest.kt` from `outline-valid.txt`, `lift-plan-valid.txt` and `run-plan-valid.txt`; lift fixtures use the sortie at pattern position 1, run fixtures position 0.

- An outline activates the Mission, creates one sortie per pattern letter in pattern order, carries each sortie's focus and rationale, stores the outline notes, and leaves sortie 1 current — and planned, because the toggle is off and the fixture pattern starts with a run.
- With run generation off, the outline's run sortie still holds the simple 'Run' plan built from its focus, as `confirm` produces today.
- A lift plan is stored with its title, estimated minutes, warmup, notes, exercises carrying resolved exercise ids and equipment ids, rest and sets, `source = GENERATED` and `rawJson` set to the validated JSON, and the sortie moved PLANNED.
- A run plan is stored with its kind, target distance and pace, and its segments in reply order.
- A plan arriving for a sortie that already has one replaces it.
- Each refusal — Mission no longer a DRAFT; sortie no longer current, or IN_FLIGHT, LANDED or SCRUBBED — changes nothing: read the mission, sorties and plans back and assert they are untouched, and the outcome names the reason.
- Each generation helper: RUNNING, SUCCEEDED with `finishedAt`, FAILED with its error, CANCELLED; the session id and `lastEventId` saved; `attempt` bumped; and the newest non-cancelled row found for a sortie and for a Mission, with cancelled rows excluded.
- `MissionManagerTest`, `MissionManagerReFlyTest`, the coach tests, export/import and `SchemaExportTest` all still pass unchanged — the last one is what proves no schema change was needed.
- Every wait has a timeout, and the tests use a fixed clock with a UTC zone so they behave the same on the CI runner.

## Risks

- A `Valid` result carries no JSON. The operations take the validated text alongside the plan; if the validator's extraction step turns out not to be usable that way, the work passes the caller's own raw text through rather than widening `coach`'s public shape.
- The fixture loader sits in a `coach` test class. If `data` tests cannot reach it, the work lifts it into a shared test helper and leaves the `COVERED` set and its coverage test alone.
- `writePlan` is already a transaction inside the manager's, and it deletes the sortie's existing plan. Room nests the two, but the refusal tests have to prove nothing was written, not only that the outcome is right.
- `advance` does more than make sortie 1 current — it can close a Mission and prepares whatever it finds current. Reusing it is the point, so the outline tests must show the Mission stays open with all five sorties and only the first is planned.
- `markPlanned` rejects an illegal transition, so the PLANNED step stays conditional on the sortie being PENDING, as the brief words it.
- `ARCHITECTURE.md` describes what `data` holds and the brief asks for no doc change, so the plan leaves it; the verify step may want a row added.
- The known flake in the In-Flight screen test (closed Room pool) can fail a green tree: re-run the gate before treating it as a break. Detached run, `mkdir -p app/build` first, read its exit status.

## Decisions

**What a refusal looks like to the caller.** The brief says a stale result "changes nothing and says so", but not how it says so. The plan has each operation return a result value naming the outcome — stored, or refused with the reason — and never throw for the two named cases. The alternative is the `MissionManager` house style, `check` → `IllegalStateException`, which the screens already catch and turn into "Couldn't land this sortie." A stale reply is an ordinary outcome of a background job, not a fault: the worker will call these from a loop and has to branch on it (drop the result, move on) without an exception path. Either way the user sees nothing change on screen, which is the point of the refusal.

**The order the week's sorties are created in, and which one becomes current.** The plan creates one sortie per pattern letter in the pattern's own order — the order the existing confirm-without-outline path already uses — and takes each sortie's focus and rationale from the outline entry at the same position, which the validator already guarantees lines up with the pattern. Sortie 1 is the first of those, made current by the existing advance-and-prepare step. The pattern is what fixes the shape of the week and which day each session lands on, and the brief says the result is "the same shape the current 'confirm without outline' path produces", so the cards a user sees stay in the order they know, with the coach's text added to each.

**A second plan arriving for a sortie that already has one.** The plan replaces the existing plan, whatever wrote it, and keeps the refusal list to exactly the two cases the brief names. The alternative is to refuse whenever a plan already exists. A re-generation — a repair round, or a retry after a failed attempt — is the normal reason a second result arrives, and the user would expect the newer plan to win. Only a sortie the user has already started, landed or scrubbed is protected.

**The title of a generated run plan.** The plan stores the coach's own title, as it does for a lift plan. The brief lists a title only for the lift plan, so the alternative is to keep the simple plan's title "Run" on a plan the coach wrote. `title` is a required field of the run-plan schema, and the PLANNED card shows the plan's title, so a generated run would otherwise display "Run" while a generated lift shows the coach's wording. The 'Run' title stays where the design puts it: on the `SIMPLE_RUN` plan written when run generation is off.

**Which generation row is "the newest non-cancelled one".** The plan ranks by the most recent activity on the row, and excludes cancelled rows outright rather than ranking them last. The alternative is to rank by `attempt`. The worker and Mission Control both want the live or most recent session for a sortie, and a cancelled row is a dead end that should not surface as the current status.
