# Plan

## Approach

The brief's two halves follow two patterns this codebase already has. The manager operation copies the guards in `MissionManager.refly`: `check` that the Mission is ACTIVE and belongs to the current week (its `weekStart` equals `currentWeekOf(clock, zone)`, the private helper `continueWithoutOutline` compares against), that this sortie is `currentSortie`, and that it is PENDING or PLANNED. It reads settings before the transaction the way `confirm` does, and inserts the QUEUED FLIGHT_PLAN row the way `advance` does. It hands ids out the way `continueWithoutOutline` does: the new id reaches the enqueuer only after the transaction has committed. The view-model action uses `continueWithoutOutline`'s enqueue-then-cancel, and returns early like `refly` when the derived state isn't one the action applies to.

Nothing else has to change. The enqueue and cancel helpers, the worker's currency guard and the app-open sweep already exist. `MainActivity` already passes the view model its two lambdas. No new column, DAO query or screen is needed. DESIGN §7.2 already lists "Regenerate" as a `FLIGHT_PLAN` trigger, and §7.3 already states the supersede rule, so this fills in a trigger the design already promised rather than inventing a behaviour. §7.2 also says a run sortie with run generation off is never sent to the coach, so that is one more refusal. The map has the files, line numbers and harnesses.

## Files

- `app/src/main/java/com/liftoff/app/data/MissionManager.kt`: one new operation. It has these guards: ACTIVE, current week, current sortie, PENDING or PLANNED, coach configured, and not a RUN sortie while "Generate run plans" is off (the same rule `prepareCurrent` applies). It inserts one QUEUED `FLIGHT_PLAN` row built the way `advance` builds its row. It returns the new id plus the ids of the sortie's other open `FLIGHT_PLAN` rows. Every refusal throws `IllegalStateException`, including the unconfigured one. That differs from `confirm`/`retryOutline`, which return `ConfirmOutcome.CoachNotConfigured` instead of throwing.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`: one new action, returning `Job?`, on Pending and Planned only. It enqueues the new id, then cancels the others, and catches a refusal the way every other action does.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadRegenerateTest.kt`: new. It covers the brief's four behaviours plus the run-toggle refusal, with the harness copied from `LaunchpadConfirmTest`.
- `ARCHITECTURE.md`: in M3's row, move Regenerate out of the "come next" list at the end of the row and record what the operation does. Kyle reviews the wording in the pull request.
- Nothing else. There is no `Generation` column, no new DAO query, no screen, and no `AppContainer` or `MainActivity` change, so `app/schemas/…/1.json` must stay byte-identical.

## Order

1. The `MissionManager` operation. `bash gradlew.sh :app:compileDebugKotlin` must pass.
2. `LaunchpadViewModel.regenerate()`. Compile must pass.
3. `LaunchpadRegenerateTest`, run for that class only: `bash gradlew.sh :app:testDebugUnitTest --tests '*LaunchpadRegenerateTest'`.
4. Mutation-check each behaviour: remove one named token, expect that case to fail, then restore the file from a copy kept under `app/build/`.
5. The `ARCHITECTURE.md` row, then the full gate run detached.

## Testing

One new Robolectric class (`@Config(sdk = [34])`), with no Compose, no WorkManager and no daemon. The harness:
- in-memory Room;
- a real `SettingsStore` over a DataStore file in `cacheDir`;
- a fixed `Clock` with `zone = { ZoneOffset.UTC }`;
- recording `queueGeneration` / `cancelGeneration` lambdas, where the cancel lambda also marks the row CANCELLED the way `GenerationCanceller` does;
- a scope whose exception handler collects escaped throwables;
- every wait bounded at 5s.

The fixture for an ACTIVE week with a current PENDING lift sortie is `setDefaultPattern` → `onAppOpen()` → `continueWithoutOutline()`, as `LaunchpadReFlyTest` does. Settings are set before `onAppOpen()`.

- **Supersede on a PENDING sortie.** With one open row already on the sortie, the action enqueues exactly one id and cancels exactly one id. The enqueued row reads back QUEUED, `FLIGHT_PLAN`, this mission and this sortie. The cancelled row is the older one and reads back CANCELLED. The two ids are different and each is named.
- **Retry after a failure.** With the sortie's newest row FAILED, a new QUEUED row appears. The FAILED row keeps `FAILED`, its error and its `finishedAt`, and is neither cancelled nor enqueued.
- **PLANNED keeps its plan.** On a PLANNED current sortie the action queues a row, and the sortie's `FlightPlan` row and its `PLANNED` state are exactly what they were. The derived state is still `Planned`, with the same plan title.
- **Refusals write nothing.** No token, tested through the view model and through the manager. An IN_FLIGHT sortie and a PENDING sortie that isn't current are tested by calling the manager directly with that sortie's id, because the view model returns null on InFlight and only ever passes the current sortie. A RUN current sortie with the toggle off is tested through the manager. Each manager call must throw `IllegalStateException`. Each case leaves the generation table as it was, records no enqueue and no cancel, leaves the derived state as it was, and adds nothing to the scope's exception handler.
- **Availability.** The action returns a `Job` on Pending and on Planned, and null on Draft, InFlight, Closed and Loading. The harness's null-Job guard is what keeps the other assertions from passing without testing anything.
- **Mutation checks**, each a one-token edit that still compiles:
  - drop each manager guard in turn, and its refusal case fails;
  - enqueue the older id instead of the new one, or skip the cancels, and the supersede case fails;
  - return the open rows read *after* the insert, and the supersede case fails too.

## Risks

- **The brief's "Every action calls `manager.onAppOpen()` first" is not what the code does.** `MainActivity` runs it on cold start, and the view model calls it only inside its `IllegalStateException` catch. The work follows the code and re-syncs in the catch rather than running the app-open sweep on every tap. Making every action sweep would be a change to all of them, not to this one.
- **The other ids must be read before the insert.** `getOpenFlightPlanForSortie` returns newest first, so reading it after the insert includes the row just queued, and the action would cancel the generation it just enqueued. The manager hands back ids collected before the insert. The Testing mutation check makes the reverse fail.
- **A fixed test clock puts the new row in the same millisecond as the older one.** Both `getOpenFlightPlanForSortie` and the worker's `getNewestNonCancelledForSortie` break ties on `id DESC`, so the new row is still the current one. A case that seeds two rows staggers `createdAt`, so "older" means more than id order.
- **A failed cancel is not a correctness risk.** If the daemon is unreachable and the older row stays open, two `FLIGHT_PLAN` rows sit on one sortie. The worker stores only for the newest non-cancelled row, and `AppOpenSweep` re-enqueues both because it judges each row by its own target. The tests assert the ids handed to the cancel lambda, not that a daemon answered.
- **A refusal that wrote something would still look clean** in a test that only checks state. So each refusal case reads the generation table back and requires no new row, no recorded enqueue or cancel, and nothing in the scope's exception handler.
- **The PLANNED case has to assert the state, not just the row.** `LaunchpadStates.derive` maps a PLANNED sortie with no plan row back to `Pending`. Asserting that the state is still `Planned` is what catches an operation that cleared the plan. The case also reads the plan row back.
- **The gate can hang for about 5 minutes without writing any `TEST-*.xml`.** Run it detached, with the log and an `EXIT=` sentinel under `app/build/`. Move the earlier results directory aside with `mv`, never `rm`, because a stranded executor holds `binary/output.bin`. If it hangs, re-run the identical tree once.
- **`ARCHITECTURE.md`'s M3 row lists three things as coming next, not one.** It also lists In-Flight's enqueue, which has already shipped, and the Launchpad's status display. The work moves only Regenerate out of that list and leaves the rest of the sentence for Kyle's review in the pull request.

## Decisions

**Regenerate does not consult the "Generate run plans" toggle.** The toggle decides whether the app *voluntarily* sends a run sortie to the coach. It is read only in `advance`, and a run sortie with the toggle off is planned without the coach and stays PLANNED. DESIGN §5.3 says that with the toggle off "No generation is involved", which describes what the app queues on its own, not what the owner may ask for. Regenerate asks for a plan the owner asked for, so it queues one for a run sortie too, and the new result replaces the simple run plan the same way it replaces a coach plan. The alternative was to refuse on a run sortie while the toggle is off. That reads the setting as a ban on the coach ever touching runs, which is not what the setting says. It would also mean the same button doing nothing on some cards for a reason the owner can't see from the Launchpad. The button run can still choose not to offer the action there.
Changed by review: the plan chose to queue a coach generation for a run sortie even with the toggle off. The review chose to refuse with `IllegalStateException` on a RUN sortie while "Generate run plans" is off, and to allow it once the toggle is on (which then replaces a `SIMPLE_RUN` plan). The reason: DESIGN §7.2 says outright "A run sortie with run generation off is never sent to the coach (§5.3)", and DESIGN decisions are not re-litigated in a run. The worker never reads the toggle, so the manager is the only place this can be enforced. An owner who turned run plans off would not expect a run to reach the coach. The button run can hide the action on those cards.

**Asking twice supersedes the first request; it isn't refused.** A regenerate always inserts one new QUEUED row and hands back every other open FLIGHT_PLAN row for the sortie, including one that is already RUNNING. The alternative was to refuse while a generation is open, so the owner would have to wait or cancel first. That is what `MissionManager.retryOutline` does for a week's outline. For a Flight Plan the design already chose the other answer: DESIGN §7.3's supersede rule cancels the older generation when a regeneration is requested while it is queued or running, and the worker already treats only the newest non-cancelled row as current. A double tap therefore reaches the same outcome the app would reach on its own, only sooner and without a dead end.

**Retry after a failed Flight Plan starts a fresh conversation.** The FAILED row is not an open row, so regenerate leaves it FAILED with its error and queues a brand-new row, which opens a new rho session. The alternative was to resume the failed row's session id so the coach carried on where it stopped. A fresh start is what the owner would expect from asking the coach again: the coach re-reads the mission and sortie state that is already stored, and the old row stays as the record of what went wrong.

**A refusal says nothing, and nothing on screen changes.** The view model catches the manager's `IllegalStateException` and leaves the Launchpad state exactly as it was, with no message and no status change. The alternative was to reuse the Launchpad's existing "Mission Control isn't set up" line. That line is drawn only on the Draft's status line today, so setting it from a Pending or Planned action would produce text the owner never sees. The manager also reports every refusal as the same exception, so the view model couldn't tell the unconfigured one from the others without reading settings again. The later button run decides how to explain a refusal, most likely by not offering the action in those states.

**Nothing on screen says a regeneration is underway.** After the action the Launchpad shows the same card as before (the same plan, the same Launch/Scrub or Re-fly/Scrub) until the new plan lands and `storePlan` overwrites the old one. The alternative was a "Mission Control is writing…" line on the Pending and Planned cards. The Launchpad derives a coach status only for a DRAFT week today, and the brief says no UI change, so adding that line here would be a screen change nobody asked for. The run that adds the button has to decide how the owner sees progress.

**Regenerate checks that the coach is configured, not that it can be reached.** The manager refuses when the host or token is missing. A configured host that is down or unreachable still gets a new QUEUED row and an enqueue, and the worker reports it as an unreachable-coach failure. The alternative was to probe the daemon before queueing. Every other queueing path in this app already behaves this way. The worker already retries an unreachable daemon and ends the row FAILED `coach unreachable` after its eighth attempt, so the owner sees the same failure they see today from any other generation, and the same Re-fly offer afterwards.

The brief settles everything else this plan touches:
- its refusal conditions: the current sortie of the current week's ACTIVE Mission, PENDING or PLANNED, and the coach configured;
- the existing plan and PLANNED state staying untouched until the new result replaces them;
- `regenerate()` returning null outside Pending and Planned.
