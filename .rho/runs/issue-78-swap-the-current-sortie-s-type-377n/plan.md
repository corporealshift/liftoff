# Plan — issue 78: swap the current sortie's type

## Approach

Four moves, in the order `CLAUDE.md` requires them. The map carries the line numbers; this is the shape.

**Amend the design before the code.** A new entry at the top of DESIGN.md's `## Amendments after approval`
(newest first, **Rule:** / **Reason:** as the 2026-11-02 Scrub entry writes them), plus inline
`(amended <date> — …)` marks on §4.1, §4.3, §5.1 and §9 — the four the brief names. §4.1's "once confirmed, the
pattern is frozen" becomes *frozen, except the current sortie's letter, flipped in place*. §4.3 gets a mark
because a swap changes a letter, not the count or the order. §5.1 gains the `PLANNED → PENDING` edge the swap
needs. §9's Launchpad paragraph gains the action. §13's checklist line "the pattern … is frozen on confirm"
would otherwise contradict the amendment, so the same entry qualifies it.

**Put the rule in the domain.** A pure function over `(mission, sortie)` returning the new pattern and the new
sortie: flip the letter at `sortie.index`, set the new type's default focus, clear `focusRationale`, set the
state to `PENDING`. `domain/` is pure Kotlin, so this is where the flip can be asserted without a device. The
two default focus strings are named once and both this and `Missions.kt:56` derive from that name, instead of
the literals appearing twice. `SortieTransitions` gains `PLANNED → PENDING`, which is what §5.1's mark describes.

**One manager transaction.** `swapSortieType(sortieId, onQueued)` in `MissionManager`, with
`regenerateFlightPlan`'s gates — ACTIVE mission, current week, current sortie, `PENDING` or `PLANNED` — and no
settings gate, because both arms have an offline path. Inside the transaction: read the sortie's open
`FLIGHT_PLAN` generation ids *before* writing anything (the ordering `MissionManager.kt:402-404` explains why),
delete the sortie's plan, write the flipped pattern and sortie, then prepare it the way `advance` prepares a
current `PENDING` sortie — `prepareCurrent`'s two arms, plus `advance`'s coach-not-configured guard, so a swap
with no coach leaves the sortie `PENDING` with Re-fly. It does **not** call `advance`: map,
`MissionManager.kt:520` — `advance` hands back the sortie's already-open row, which the caller is about to
cancel. The old ids come back to the caller to cancel after the commit, as `refly`, `regenerate` and `resetWeek`
do.

**Then the Launchpad.** The button goes in the same action row the current sortie's other actions live, on the
`PENDING` and `PLANNED` cards only — which is the whole rule for hiding it: `currentSortie` skips `LANDED` and
`SCRUBBED`, so those never have a card, and `IN_FLIGHT`, `Draft` and `Closed` cards gain no action. The label
names the target type, so the `PENDING` card needs the sortie's type on the state. The confirm dialog follows
the scrub dialog's shape (a flag on the view model, the dialog drawn in `LaunchpadScreen`), and its condition is
the `PLANNED` card's `plan.source`: `GENERATED` asks, `SIMPLE_RUN` and `REFLY` do not. Wording goes in internal
consts in `LaunchpadViewModel.kt`, the way `NOT_CONFIGURED_MESSAGE` does, so the screen and its test say the
same sentence byte-for-byte.

## Files

- `DESIGN.md` — the amendment entry (newest first) and the inline marks on §4.1, §4.3, §5.1, §9; qualify §13's
  "frozen on confirm".
- `domain/Missions.kt` — the flip, next to `overridePattern`; name the two default focus strings once and derive
  line 56 from them. If the flip reads better as its own file, `domain/SortieSwap.kt` is the alternative; either
  way it stays pure Kotlin.
- `domain/Sorties.kt` — `PLANNED → PENDING` joins `legalTransitions`.
- `data/MissionManager.kt` — `swapSortieType`, beside `regenerateFlightPlan`.
- `ui/launchpad/LaunchpadState.kt` — `sortieType` on `Pending`, defaulted, so the label can name the target type.
- `ui/launchpad/LaunchpadViewModel.kt` — the swap call, the dialog flag, the cancel loop, the shared wording
  consts.
- `ui/launchpad/LaunchpadScreen.kt` — the button in `PlannedActions` and `PendingActions`, and the dialog.
- Tests: `data/MissionManagerTest.kt`, `domain/SortiesTest.kt`, `domain/MissionsTest.kt`,
  `ui/launchpad/LaunchpadStatesTest.kt`, `data/PromptInputLoaderTest.kt`, and a new
  `ui/launchpad/LaunchpadSwapTest.kt`.

Nothing else. `coach/CoachPromptBuilder.kt` and the golden prompts under `app/src/test/resources/prompts/` are
untouched — the prompt is built from the sortie rows, so a swapped sortie appears with its new type and focus on
its own. `ui/sortie/PlanFormat.kt`, `data/DatabaseExport.kt`, `data/FlightPlanDao.kt` (`deleteForSortie` already
exists, and `writePlan` deletes before inserting), `GenerationCanceller`, `GenerationWorker`, `refly`,
`setPattern` and `MissionScreen.kt` stay as they are.

## Order

1. DESIGN.md: the amendment entry and the inline marks. Docs only; nothing compiles differently.
2. Domain: the flip, the named default focus strings, `PLANNED → PENDING`, and their tests — including moving
   `PLANNED to PENDING` out of `SortiesTest`'s illegal list.
3. `MissionManager.swapSortieType` and its tests: the two arms (simple plan / queued generation), the cancel ids,
   the refusals, and the prompt-input and export agreement.
4. The Launchpad: the state field, the view model's swap and dialog, the button on both cards, and the UI tests.
5. Full gate (`:app:assembleDebug :app:testDebugUnitTest`), then the brief's bullets read back against the tests,
   to catch any behavior no test covers.

## Testing

Domain unit tests (`domain/`, no device): the flip maps `L ↔ R` and sets the new type's default focus and clears
`focusRationale`; only the letter at that index changes, and the length is unchanged; swapping twice returns the
original pattern and type; `PLANNED → PENDING` is legal and the rest of `SortiesTest`'s illegal list still is.

Manager tests (`MissionManagerTest.kt`, Robolectric + in-memory Room, the style the `regenerateFlightPlan` tests
there already use):

- A `PLANNED` sortie with a generated plan: the plan row is gone, the sortie is prepared again, and a fresh
  `FLIGHT_PLAN` generation row is queued.
- The sortie's open generations are returned for the caller to cancel, and the row the swap queued itself is not
  among them.
- A run sortie with run generation off: the simple run plan is written, the sortie is `PLANNED`, nothing queued.
- With the coach not configured the swap still happens: pattern and focus change, the sortie is `PENDING`,
  nothing is queued.
- Refused for a landed sortie, a scrubbed sortie, a sortie that is not the current one, a mission that is not
  ACTIVE, and a mission that is not the current week.
- After a swap, the prompt inputs built from the real rows carry the new type and focus for that index
  (`PromptInputLoaderTest.kt`), and the exported mission row carries the flipped pattern (`DatabaseExportTest.kt`).

Launchpad UI tests (Compose, `ui/launchpad/`, following `LaunchpadScrubTest.kt` for the dialog and
`LaunchpadRegenerateTest.kt` for the action row): the action appears on the `PENDING` and `PLANNED` cards and not
on the `Draft`, `IN_FLIGHT` or `Closed` card; the label names the target type in both directions; the confirm
dialog appears for a `PLANNED` sortie whose plan is `GENERATED` and not for one whose plan is `SIMPLE_RUN` or
`REFLY`, and cancelling it changes nothing; confirming runs the swap, cancels the open generations, and the
pattern track and eyebrow show the new letter and type.

## Risks

- **`advance` would hand back the row the caller is about to cancel** (map: `MissionManager.kt:520`). The swap
  prepares the sortie itself, reading the open ids before it inserts its own, as `regenerateFlightPlan` does.
- **A generation already mid-turn could store a plan for the old type.** The worker re-reads its row and skips a
  `CANCELLED` one (`GenerationWorker.kt:338`), and `storePlan` throws on a type mismatch
  (`MissionManager.kt:460`, `:466`). The cancel lands after the commit, so the window is the one Regenerate
  already accepts; the tests assert the ids are returned and cancelled, not that the window closes.
- **`PLANNED → PENDING` joins a table every caller of `requireTransition` shares.** No existing path makes that
  move today, and §5.1's mark says what the edge is for.
- **The Launchpad's existing tests pin the action row** (`LaunchpadPlannedActionsTest.kt`,
  `LaunchpadRegenerateTest.kt`). Read them before adding a button, and update the assertions rather than the
  wording.
- **§13's "frozen on confirm" and `setPatternUpdatesDraftAndRejectsActive`**: `setPattern` keeps refusing anything
  but a DRAFT, the swap is a different path, and that test stays. The amendment qualifies the checklist wording so
  the doc and the code agree.
- **The week's outline is not re-queued.** The coach's outline still describes the week as it was planned; only
  the swapped sortie's plan is regenerated, from a prompt that now shows the new letter. The brief does not ask
  for a new outline; if the owner wants one, that is a separate decision.
- **Known flakes on this machine**: `LiftoffShellTest`'s expired `waitFor` and the `CalledFromWrongThreadException`
  family that has hit `InFlightScreenTest` and `LaunchpadPendingTitleTest` (memory
  `liftoff-shelltest-full-suite-flake`). Re-run a failing case before calling it a regression.

## Decisions

**Where the action lives, and what it is called.** The brief says "on the Launchpad for the current sortie" but
not where. The plan puts it in the same place the current sortie's other actions live, labelled **Swap to run**
or **Swap to lift** depending on the sortie's current type. The alternative is a long-press or overflow menu.
Users look for an action next to the ones they already use on that sortie (Regenerate, Scrub), and the label
naming the *target* type is what they are deciding, not the type they have.

**The pattern flip is the Mission's pattern, so it carries into later weeks.** Flipping the letter at that index
changes the confirmed pattern, so a later week's pattern has the new letter too. The alternative is a one-week
override that leaves `Mission.pattern` untouched. The brief asks for the flip precisely so the pattern, the export
and the coach prompts agree; an override would make the export and the coach describe a pattern the user never
confirmed, and would need its own rule for when it expires.

**A swapped sortie can be swapped again, including back to its original type.** Nothing in the gates stops it: it
is still the current sortie and still `PENDING`/`PLANNED`. The alternative is a one-swap-per-sortie limit. A user
who changed their mind, or who swapped by mistake, expects the same action to put it back; a limit would only make
the mistake permanent.

**No undo; the confirm dialog is the guard.** The swap destroys a generated Flight Plan and offers no way to get
it back. The alternative is an undo snackbar. The brief already decides when the dialog appears — only when a
generated plan is lost — which is exactly the case where an undo would matter, and Regenerate already destroys a
generated plan with no undo; a swap that behaved differently would be harder to predict than one that behaves
like the action it replaces.

**A focus the user set for that sortie is replaced by the new type's default.** The brief sets the defaults
(`easy` for a run, `full body` for a lift) and clears `focusRationale`; the plan applies that even when the
sortie's focus was edited by hand. The alternative is to keep the old focus. A lift focus ("full body", chest and
legs) on a run day is not something a user would want to keep, and keeping it would leave the coach prompting a
run with a lift's outline.

**The week's outline notes stay on screen.** `Mission.outlineNotes` is one string for the whole week, not one per
index, so "the outline's notes for that index no longer apply" is not a deletion: the swapped sortie takes the
type's default focus, and the notes stop describing it while still describing the rest of the week. The
alternative is to blank or rewrite the notes; blanking them to fix one sortie would throw away guidance the user
still wants for the others.

**A re-flown plan is deleted without a dialog.** A `PLANNED` sortie whose plan came from Re-fly is swapped with
no confirmation, because the plan it was copied from is still in history — the same reason Re-fly itself needs
none, which the Re-fly amendment states as "nothing is lost". The alternative is to ask whenever any plan is
about to go. The brief's rule is about a *generated* plan being lost, and a re-flown plan is a copy of one that
still exists.
