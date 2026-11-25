Swap the current sortie's type (lift ↔ run) mid-week

This week I needed to turn a lift day into a run and couldn't. Once a Mission is confirmed its pattern is frozen (DESIGN.md §4.1), so the only options were to scrub the lift or reset the whole week.

## What to build

Add a **Swap to run / Swap to lift** action on the Launchpad for the current sortie. It changes the type of that one sortie in place and leaves the rest of the pattern alone, e.g. `R [L] R L R` → `R [R] R L R`.

This needs a DESIGN.md amendment first (CLAUDE.md: do not re-litigate decisions). It relaxes §4.1 ("once confirmed, the pattern is frozen"), and §4.3 / §5.1 / §9 need inline amendment marks too.

## Proposed rules

- **Which sortie:** only the current sortie of the current week's ACTIVE Mission, while it is `PENDING` or `PLANNED`. These are the same gates as `MissionManager.regenerateFlightPlan`. An `IN_FLIGHT` sortie can use Back to Launchpad first. `LANDED` and `SCRUBBED` sorties can't be swapped.
- **Type and pattern:** flip `Sortie.type`, and also flip the letter at that index in `Mission.pattern` so the pattern, the export and the coach prompts all agree. Not reordering: sortie count and order stay the same, so §4.3 rolling order is unaffected.
- **Focus:** use the "Continue without outline" defaults (§4.2): `easy` for a run, `full body` for a lift. Clear `focusRationale`. No new coach call for the focus. The outline's notes for that index no longer apply.
- **Flight Plan:** delete the sortie's existing Flight Plan (its exercises, sets and segments) if there is one, and cancel any open `FLIGHT_PLAN` generation for it, the same way Regenerate supersedes them. The sortie goes back to `PENDING`, then is prepared as the current sortie normally is (`prepareCurrent`):
  - run with **Generate run plans** off → simple "Run" plan, offline, `PLANNED` right away
  - otherwise → queue a `FLIGHT_PLAN` generation (needs the coach configured). If the coach isn't configured, the sortie stays `PENDING` and Re-fly (§5.4) is still available for the new type.
- **Confirmation:** show a confirm dialog only when the sortie is `PLANNED` with a generated plan, because a generated plan is lost. No dialog for `PENDING` or a simple run plan.
- **Runs in one transaction:** the type flip, pattern update, plan delete and generation queue all happen together. Open generations are returned for the caller to cancel, as in `regenerateFlightPlan` / `resetWeek`.

## Tests

- domain: the flip maps L↔R and sets the default focus; the pattern letter changes only at that index
- `MissionManager`: rejects a sortie that isn't current, isn't PENDING/PLANNED, or isn't in the current week's ACTIVE Mission; supersedes open generations; deletes the old plan; creates a simple run plan when run generation is off; queues a FLIGHT_PLAN generation otherwise
- the prompt for the swapped sortie carries the new type and default focus
- Launchpad: action shown/hidden under the same conditions; confirm dialog only for a generated PLANNED plan

(From issue corporealshift/liftoff#78: https://github.com/corporealshift/liftoff/issues/78)
