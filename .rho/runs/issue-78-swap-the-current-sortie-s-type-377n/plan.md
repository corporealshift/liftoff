# Plan — issue 78: swap the current sortie's type

## Approach

There are four steps, done in the order `CLAUDE.md` requires. The map has the line numbers; this section gives the shape.

**Amend the design before the code.** Add a new entry at the top of DESIGN.md's `## Amendments after approval`
(newest first, with **Rule:** / **Reason:** written the way the 2026-11-02 Scrub entry writes them). Add inline
`(amended <date> — …)` marks on §4.1, §4.3, §5.1 and §9, the four the brief names. §4.1's "once confirmed, the
pattern is frozen" becomes *frozen, except that the current sortie's letter can be flipped in place, for this week's Mission only*. §4.3 gets a mark
because a swap changes a letter, not the count or the order. §5.1 gains the `PLANNED → PENDING` edge the swap
needs. §9's Launchpad paragraph gains the action. §13's checklist line "the pattern … is frozen on confirm"
would contradict the amendment, so the same entry qualifies it.

**Put the rule in the domain.** Write a pure function over `(mission, sortie)` that returns the new pattern and the new
sortie. It flips the letter at `sortie.index`, sets the new type's default focus, clears `focusRationale` and sets the
state to `PENDING`. A sortie that is already `PENDING` stays `PENDING`, so only a `PLANNED` start goes through
`requireTransition`. `domain/` is pure Kotlin, so the flip can be asserted here without a device. Name the
two default focus strings once, and derive both this function and `Missions.kt:56` from those names, so the
literals don't appear twice. `SortieTransitions` gains `PLANNED → PENDING`, which is what §5.1's mark describes.

**One manager transaction.** Add `swapSortieType(sortieId, onQueued)` to `MissionManager`, with
`regenerateFlightPlan`'s gates: ACTIVE mission, current week, current sortie, `PENDING` or `PLANNED`. There is
no settings gate, because both arms have an offline path. Inside the transaction:
1. Read the sortie's open `FLIGHT_PLAN` generation ids *before* writing anything (`MissionManager.kt:402-404` explains why).
2. Delete the sortie's plan.
3. Write the flipped pattern and sortie.
4. Prepare the sortie the way `advance` prepares a current `PENDING` sortie: `prepareCurrent`'s two arms, plus
   `advance`'s coach-not-configured guard, so a swap with no coach leaves the sortie `PENDING` with Re-fly.

It does **not** call `advance`. At `MissionManager.kt:520`, `advance` hands back the sortie's already-open row, which the caller is about to
cancel. The old ids go back to the caller to cancel after the commit, as in `refly`, `regenerateFlightPlan` and `resetWeek`.

**Then the Launchpad.** The button goes in the action row that holds the current sortie's other actions, on the
`PENDING` and `PLANNED` cards only. That alone is the rule for hiding it: `currentSortie` skips `LANDED` and
`SCRUBBED`, so those never get a card, and the `IN_FLIGHT`, `Draft` and `Closed` cards get no new action. The label
names the target type. `Planned` already carries `sortieType`; `Pending` needs it added. The confirm dialog follows
the scrub dialog's shape (a flag on the view model, with the dialog drawn in `LaunchpadScreen`). Its condition is
the `PLANNED` card's `plan.source`: `GENERATED` asks for confirmation, `SIMPLE_RUN` and `REFLY` do not. The wording goes in internal
consts in `LaunchpadViewModel.kt`, as `NOT_CONFIGURED_MESSAGE` does, so the screen and its test use the
same sentence byte-for-byte.

## Files

- `DESIGN.md`: the amendment entry (newest first) and the inline marks on §4.1, §4.3, §5.1 and §9; qualify §13's
  "frozen on confirm".
- `domain/Missions.kt`: the flip, next to `overridePattern`; name the two default focus strings once and derive
  line 56 from them. If the flip reads better as its own file, use `domain/SortieSwap.kt` instead. Either
  way it stays pure Kotlin.
- `domain/Sorties.kt`: `PLANNED → PENDING` joins `legalTransitions`.
- `data/MissionManager.kt`: `swapSortieType`, next to `regenerateFlightPlan`.
- `ui/launchpad/LaunchpadState.kt`: `sortieType` on `Pending`, so the label can name the target type.
- `ui/launchpad/LaunchpadViewModel.kt`: the swap call, the dialog flag, the cancel loop and the shared wording
  consts.
- `ui/launchpad/LaunchpadScreen.kt`: the button in `PlannedActions` and `PendingActions`, and the dialog.
- Tests: `data/MissionManagerTest.kt`, `domain/SortiesTest.kt`, `domain/MissionsTest.kt`,
  `ui/launchpad/LaunchpadStatesTest.kt`, `data/PromptInputLoaderTest.kt`, `data/DatabaseExportTest.kt`, and a new
  `ui/launchpad/LaunchpadSwapTest.kt`.

Nothing else changes. `coach/CoachPromptBuilder.kt` and the golden prompts under `app/src/test/resources/prompts/` are
untouched: the prompt is built from the sortie rows, so a swapped sortie shows its new type and focus without changes. These also stay as they are:
- `ui/sortie/PlanFormat.kt` and `data/DatabaseExport.kt`
- `data/FlightPlanDao.kt` (`deleteForSortie` already exists, and `writePlan` deletes before it inserts)
- `GenerationCanceller` and `GenerationWorker`
- `refly` and `setPattern`
- `MissionScreen.kt`

## Order

1. DESIGN.md: the amendment entry and the inline marks. Docs only; nothing compiles differently.
2. Domain: the flip, the named default focus strings, `PLANNED → PENDING`, and their tests. This includes moving
   `PLANNED to PENDING` out of `SortiesTest`'s illegal list.
3. `MissionManager.swapSortieType` and its tests: the two arms (simple plan / queued generation), the cancel ids,
   the refusals, and agreement between the prompt input and the export.
4. The Launchpad: the state field, the view model's swap and dialog, the button on both cards, and the UI tests.
5. Run the full gate (`:app:assembleDebug :app:testDebugUnitTest`), then read the brief's bullets back against the tests
   to catch any behavior that no test covers.

## Testing

Domain unit tests (`domain/`, no device):
- The flip maps `L ↔ R`, sets the new type's default focus and clears `focusRationale`.
- Only the letter at that index changes, and the length stays the same.
- Swapping twice returns the original pattern and type.
- Both a `PENDING` and a `PLANNED` sortie come out `PENDING`.
- `PLANNED → PENDING` is legal, and everything else in `SortiesTest`'s illegal list is still illegal.

Manager tests (`MissionManagerTest.kt`, Robolectric with in-memory Room, the style the existing `regenerateFlightPlan` tests
use):

- A `PLANNED` sortie with a generated plan: the plan row is gone, the sortie is prepared again, and a new
  `FLIGHT_PLAN` generation row is queued.
- The sortie's open generations are returned for the caller to cancel, and the row the swap queued itself is not
  among them.
- A run sortie with run generation off: the simple run plan is written, the sortie is `PLANNED` and nothing is queued.
- With the coach not configured, the swap still happens: the pattern and focus change, the sortie is `PENDING`
  and nothing is queued.
- The swap is refused for a landed sortie, a scrubbed sortie, a sortie that is not the current one, a mission that is not
  ACTIVE and a mission that is not in the current week.
- After a swap, the prompt inputs built from the real rows carry the new type and focus for that index
  (`PromptInputLoaderTest.kt`), and the exported mission row carries the flipped pattern (`DatabaseExportTest.kt`).

Launchpad UI tests (Compose, `ui/launchpad/`, following `LaunchpadScrubTest.kt` for the dialog and
`LaunchpadRegenerateTest.kt` for the action row):
- The action appears on the `PENDING` and `PLANNED` cards, and not on the `Draft`, `IN_FLIGHT` or `Closed` card.
- The label names the target type in both directions.
- The confirm dialog appears for a `PLANNED` sortie whose plan is `GENERATED`, and not for one whose plan is `SIMPLE_RUN` or
  `REFLY`. Cancelling it changes nothing.
- Confirming runs the swap and cancels the open generations, and the pattern track and eyebrow show the new letter and type.

## Risks

- **`advance` would hand back the row the caller is about to cancel** (`MissionManager.kt:520`). The swap
  prepares the sortie itself and reads the open ids before it inserts its own row, as `regenerateFlightPlan` does.
- **A generation already mid-turn could store a plan for the old type.** The worker re-reads its row and skips a
  `CANCELLED` one (`GenerationWorker.kt:338`), and `storePlan` throws on a type mismatch
  (`MissionManager.kt:460`, `:466`). The cancel lands after the commit, so the window is the same one Regenerate
  already accepts. The tests assert that the ids are returned and cancelled, not that the window closes.
- **`PLANNED → PENDING` joins a table that every caller of `requireTransition` shares.** No existing path makes that
  move today, and §5.1's mark says what the edge is for.
- **The Launchpad's existing tests pin the action row** (`LaunchpadPlannedActionsTest.kt`,
  `LaunchpadRegenerateTest.kt`). Read them before adding a button, and update the assertions rather than the
  wording.
- **§13's "frozen on confirm" and `setPatternUpdatesDraftAndRejectsActive`**: `setPattern` still refuses anything
  but a DRAFT, the swap is a separate path, and that test stays. The amendment qualifies the checklist wording so
  the doc and the code agree.
- **The week's outline is not re-queued.** The coach's outline still describes the week as it was planned. Only
  the swapped sortie's plan is regenerated, from a prompt that now shows the new letter. The brief does not ask
  for a new outline; if the owner wants one, that is a separate decision.
- **Known flakes on this machine**: `LiftoffShellTest`'s expired `waitFor` and the `CalledFromWrongThreadException`
  family that has hit `InFlightScreenTest` and `LaunchpadPendingTitleTest` (memory
  `liftoff-shelltest-full-suite-flake`). Re-run a failing case before calling it a regression.

## Decisions

**The pattern flip changes this week's Mission only; later weeks are unaffected.** The plan flips the letter at that index in the confirmed `Mission.pattern`, and wrote that "a later week's pattern has the new letter too". The alternative is a one-week override that leaves `Mission.pattern` untouched. The brief asks for the flip so that the pattern, the export and the coach prompts agree. An override would make the export and the coach describe a pattern the user never confirmed, and would need its own rule for when it expires.
Changed by review: the plan chose "the flip carries into later weeks"; the review chose "the flip is this week's only", because a new week's draft is built from `settings.defaultPattern` (`MissionManager.kt:98`), not from the previous Mission. The swap therefore can't carry forward, and a user who swaps one day expects next week to keep their usual pattern. The swap does not touch `defaultPattern`. Reset Week rebuilds the draft from `mission.pattern` (`MissionManager.kt:437`), so a reset after a swap keeps the swapped letter. That is the week as it now stands, and the user can edit the draft before re-confirming.

**Where the action lives, and what it is called.** The brief says "on the Launchpad for the current sortie" but
not where. The plan puts it next to the current sortie's other actions, labelled **Swap to run**
or **Swap to lift** depending on the sortie's current type. The alternative is a long-press or overflow menu.
Users look for an action next to the ones they already use on that sortie (Regenerate, Scrub), and a label
that names the *target* type describes what they are choosing, not the type they already have.

**A swapped sortie can be swapped again, including back to its original type.** Nothing in the gates stops this: the sortie
is still current and still `PENDING`/`PLANNED`. The alternative is a limit of one swap per sortie. A user
who changed their mind, or swapped by mistake, expects the same action to put it back; a limit would only make
the mistake permanent.

**No undo; the confirm dialog is the guard.** The swap destroys a generated Flight Plan and offers no way to get
it back. The alternative is an undo snackbar. The brief already decides when the dialog appears: only when a
generated plan would be lost, which is exactly the case where an undo would matter. Regenerate also destroys a
generated plan with no undo, so a swap that works the same way is easier to predict than one that doesn't.

**A focus the user set for that sortie is replaced by the new type's default.** The brief sets the defaults
(`easy` for a run, `full body` for a lift) and clears `focusRationale`. The plan applies that even when the
sortie's focus was edited by hand. The alternative is to keep the old focus. A lift focus such as "full body" or "chest and
legs" makes no sense on a run day, and keeping it would leave the coach prompting a
run with a lift's focus.

**The week's outline notes stay on screen.** `Mission.outlineNotes` is one string for the whole week, not one per
index, so "the outline's notes for that index no longer apply" does not mean deleting anything. The
swapped sortie takes the type's default focus, and the notes stop describing it while still describing the rest of the week. The
alternative is to blank or rewrite the notes. Blanking them to fix one sortie would throw away guidance the user
still wants for the others.

**A re-flown plan is deleted without a dialog.** A `PLANNED` sortie whose plan came from Re-fly is swapped with
no confirmation, because the plan it was copied from is still in history. That is the same reason Re-fly itself needs no
dialog, which the Re-fly amendment states as "nothing is lost". The alternative is to ask whenever any plan is
about to go. The brief's rule is about losing a *generated* plan, and a re-flown plan is a copy of one that
still exists.
