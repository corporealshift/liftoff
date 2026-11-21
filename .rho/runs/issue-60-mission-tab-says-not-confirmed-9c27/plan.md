# Plan

## Approach

The Mission tab's DRAFT arm reads the mission row and nothing else, which is exactly why it cannot tell a draft that
has been confirmed and is waiting on the coach from a draft nobody has touched. The fix gives that arm the two live
sources the Launchpad's draft arm already combines — the mission's newest non-cancelled OUTLINE `Generation` row and
`MissionManager.coachConfigured` — and reuses `domain/deriveCoachStatus` unchanged, so both tabs answer from one
rule instead of a second copy of it. `MissionTabState.Week` carries that status, and the Mission tab's draft block
picks its line from it: the Launchpad's own wording for planning, offline and failed, and the existing sentence for
everything else. No new domain rule, no new DAO query, no rho call, and no new action on the tab.

## Files

What each file already holds is in `map.md`; only the change is listed here.

- `ui/mission/MissionState.kt` — the DRAFT arm of `MissionStates` joins `observeNewestNonCancelledOutlineForMission`
  and `coachConfigured` and derives the coach status through `deriveCoachStatus`; `MissionTabState.Week` carries it.
  `observe()` currently maps one flow, so the DRAFT arm needs the flow-returning shape `LaunchpadStates.derive`
  already uses.
- `ui/mission/MissionScreen.kt` — the draft block (128-134) keeps its draft gate, and inside it picks the line from
  the coach status rather than drawing one sentence for every draft: the coach line for planning/offline/failed,
  "Not confirmed yet…" for the rest.
- `ui/launchpad/LaunchpadScreen.kt` — the planning and offline strings are private to this file. Lift them so both
  screens read one copy; `NOT_CONFIGURED_MESSAGE` in `LaunchpadViewModel.kt` is the precedent the codebase already
  uses for a coach line shared across a screen and its tests. If the work keeps them private instead, it restates
  them in `MissionScreen.kt` and says so in the PR.
- `app/src/test/java/com/liftoff/app/ui/mission/MissionStatesTest.kt` — the new cases.
- `ui/mission/MissionTabStateTest.kt` and `MissionScreen.kt`'s `MissionTabPreview` — only if `Week` gains the field
  without a default; both construct it.

## Order

1. `MissionState.kt`: the DRAFT arm reads the outline row and the settings, `Week` carries the derived status. Give
   the new field a default (or update the two call sites in `MissionTabStateTest.kt` and the preview in the same
   step) so the build stays green with the screen still drawing the old line.
2. `MissionScreen.kt`: the draft block draws the coach line for planning/offline/failed and keeps the existing
   sentence for the rest. The reported bug is fixed at this point.
3. Share the wording between the two screens, or restate it in `MissionScreen.kt`, and re-run the Launchpad draft
   status tests.
4. Add the `MissionStatesTest` cases.
5. Gate: the run's `verify.sh`, which is the brief's `:app:assembleDebug :app:testDebugUnitTest`. On this machine
   the committed `gradlew.sh` is Windows-only; use the toolchain paths recorded in memory
   (`liftoff-gradle-gate-on-linux`) rather than editing the script.

## Testing

All in `MissionStatesTest.kt` — Robolectric + in-memory Room, reading `states.observe()` the way the existing cases
do. These are the behaviors; the work writes the test code, and the verify step names the tests.

- A draft whose newest non-cancelled OUTLINE row is QUEUED or RUNNING reports the planning status, not the
  unconfirmed one. This is the brief's required case.
- A draft whose open row carries the coach-offline mark reports offline.
- A draft whose newest outline row is FAILED reports failed, holding that row's own reason word for word.
- A draft with no outline row, and a draft whose only outline row is CANCELLED, keep the status the current line is
  drawn from — the "Not confirmed yet" case the brief says to keep.
- The tab follows its own outline row from QUEUED to FAILED without re-subscribing, and does not re-emit for another
  mission's row or for a cancelled row — the wake rule `LaunchpadCoachStatusTest` already tests for the draft card.
- The 12 existing `MissionStatesTest` cases and `MissionTabStateTest`'s Reset-week rule keep passing unchanged.

## Risks

- **Restructuring `MissionStates.observe()` is the risky part.** The ACTIVE and CLOSED arms and `observeSortie` must
  behave exactly as they do now; keep the flow-returning change inside the DRAFT arm, and let the 12 existing cases
  be the check.
- **A generation-row write wakes every observer of that table.** Without `distinctUntilChanged` the Mission tab
  re-emits for another mission's row or a sortie's plan row. Copy the Launchpad draft arm, which already handles it.
- **The Mission tab reads settings for the first time** (`coachConfigured`). Join it only in the DRAFT arm, so an
  ACTIVE or CLOSED week pays no settings read it did not pay before.
- **Wording drift between the two tabs.** The planning and offline strings are private to `LaunchpadScreen.kt`, and
  only the Launchpad's copy is pinned by a test today. Lifting them into one shared place is what keeps the tabs
  saying the same thing; duplicating them is the fallback, and it should be named in the PR if taken.
- **Nothing asserts the drawn line.** There is no Compose screen test for the Mission tab, and the brief asks for a
  state test, so the plan adds none. If the reviewer wants the rendered line pinned, `LaunchpadDraftStatusTest.kt` is
  the model to copy.
- **`MissionTabState.Week` gaining a field** touches `MissionTabStateTest.kt`'s `week(status)` helper and the
  `MissionTabPreview` in `MissionScreen.kt`; a default value avoids both.
- **A failed draft on the Mission tab no longer names the Launchpad** — see the Decisions entry. The work does not
  add a pointer; the owner can ask for one.
- **`LiftoffShellTest` flakes in full-suite gate runs** (memory `liftoff-shelltest-full-suite-flake`): rerun the gate
  before believing a failure there, and do not change feature code to chase it.

## Decisions

**What wording the Mission tab uses for the planning state.** The brief says "the same coach status the Launchpad
draft shows", so the Mission tab reuses the Launchpad's exact strings ("Coach is planning the week…", its offline
line, and for a failure the row's own reason in red) rather than a Mission-flavoured paraphrase. The alternative — a
second set of Mission-specific strings — would read as two different things happening on two screens, and the user
already sees both. One difference follows from it: the Launchpad replaces a failed draft's reason with its own report
when a Retry found nothing configured, and that report lives in the Launchpad's view model. The Mission tab runs no
Retry, so its failed line is always the row's own reason.

**Does the status line replace "Not confirmed yet", or sit next to it?** It replaces it. The two lines contradict
each other: one says the Mission still needs confirming, the other says the coach is already working on it. Showing
both would be the confusing outcome the issue is about.

**A failed outline generation is one of the statuses the tab reports.** The brief lists "planning, offline, or
failed", so a generation the coach could not finish keeps the coach line rather than falling back to "Not confirmed
yet". In the code a FAILED row is not an open generation, but `deriveCoachStatus` already reports it on its own
rather than as a draft waiting to be confirmed; following that is what the brief asks for. The alternative (only a
QUEUED or RUNNING row counts) would send the user back to the Launchpad to find out why nothing arrived, which is the
complaint the issue makes.

**Which drafts keep "Not confirmed yet".** It stays for the two statuses that mean "nothing is in flight": `Editable`
— no outline row worth reporting, Mission Control configured, which covers a draft nobody has confirmed and one whose
row was cancelled — and `NotConfigured`, the same with nothing configured. A SUCCEEDED outline cannot show on a draft
at all: `MissionManager.storeOutline` activates the mission in the same transaction. So the line is replaced for
exactly the three statuses the brief names, and nothing else on this tab changes. The alternative is to mirror the
Launchpad wholesale, which would also rewrite what the tab says for an unconfigured coach — a case the issue does not
mention.

**The Mission tab reports the status; the actions stay on the Launchpad.** "Failed with Retry/Continue" is how
`CoachStatus.Failed` is defined — the brief is naming the Launchpad's status variants, not asking the Mission tab to
grow a button bar. The Mission tab shows the same line in the same words, and Retry and "Continue without outline"
stay where every other Mission action is. The alternative is to duplicate those two controls on the Mission tab:
DESIGN.md §9 gives this tab no actions beyond Reset week, the brief's own test is a `MissionState` test rather than
an action test, and the Launchpad is one tap away in the bottom bar and shows the identical line with Retry beside
it. The cost is that a failed draft on the Mission tab no longer says where to act — the red reason replaces the
sentence that used to name the Launchpad. If the owner wants that pointer back, it is one string in the failed case.
