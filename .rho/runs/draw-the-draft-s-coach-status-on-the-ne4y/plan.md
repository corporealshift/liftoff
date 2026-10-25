# Plan

## Approach

The hard half of this is already merged: `LaunchpadState.Draft` carries a `CoachStatus` that names the actions
it offers, and `LaunchpadStates` keeps it live from Room (see the map). What is missing is the drawing. So the
change is confined to the draft arm of the screen: `DraftContent` gains one status line under the pattern and
decides whether the pattern is interactive, and `DraftActions` draws only the controls the status offers
instead of the same stepper, Confirm and 'Continue without outline' whatever the coach is doing. No new state,
no new view-model action, no new seam — the existing `LaunchpadContent` seam plus rows written to in-memory
Room reach every status, because the Draft's status is derived in the DRAFT arm from exactly those rows (map).
`LaunchpadCoachStatusTest` already seeds the rows each status needs, without Compose; a render test copies that
fixture recipe. The view model keeps its `notConfiguredMessage` report; only the wording of the shared sentence
changes.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — `DraftContent`: one status line
  replacing the `notConfiguredMessage` block (planning / offline line, failed reason in `Red`, the
  not-configured sentence), and the chips editable except while a generation is open. `DraftActions`: the −/+
  row only where the pattern is editable, `PrimaryButton("Confirm")` only in `editable`, `PrimaryButton`
  "Retry" in `failed`, `UnderlinedTextButton("Continue without outline")` only where the status offers it. The
  always-visible button and the separate message block go.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` — `NOT_CONFIGURED_MESSAGE` loses its
  trailing "or continue without an outline"; it is now the draft's not-configured line as well as the report a
  tapped Retry leaves. No behaviour change in the view model.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftControlsTest.kt` — its fixture must set a host
  and token (map: it currently leaves them blank, so every test there now derives `NotConfigured`); the stepper
  cases then keep their assertions unchanged. The two cases built on the old drawing are rewritten:
  `draftAlwaysOffersContinueWithoutOutline` (it asserted the button beside Confirm) and
  `draftShowsTheNotConfiguredMessageWhenItIsSet` (a configured fixture can no longer make `confirm()` report
  not-configured, so that case moves to the new class, driven through Retry).
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftStatusTest.kt` (new) — the five statuses
  rendered through `LaunchpadContent`, plus the one-line rule and the not-configured report a Retry leaves.
- `ARCHITECTURE.md` — the M3 row.

## Order

1. Draw the status in `DraftContent` / `DraftActions` and drop the always-visible button and the message block;
   re-word `NOT_CONFIGURED_MESSAGE`. Repair `LaunchpadDraftControlsTest` (configure the coach in the fixture,
   rewrite the two cases). Gate: assembleDebug + that test class.
2. Add `LaunchpadDraftStatusTest` with the five statuses and the −/+ absence while planning, seeded the way
   `LaunchpadCoachStatusTest` seeds rows. Gate: assembleDebug + that class.
3. Update the ARCHITECTURE.md M3 row: Confirm queues the outline and the draft shows its status; queueing
   Flight Plans and the sortie status display come next.
4. Run the whole gate detached (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, log to
   `app/build/`, `echo "EXIT=$?"` with no pipe) and read the reports; re-run once if it hangs.

## Testing

All render-only Robolectric Compose through `LaunchpadContent` on a view model built on in-memory Room,
`@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, no text fields, no WorkManager, every wait bounded.

- editable (host and token set, no outline row): chips tappable, one −/+ pair, `CONFIRM`, no `CONTINUE WITHOUT
  OUTLINE`, no status line.
- planning (a QUEUED outline row): 'Coach is planning the week…' displayed; no −/+, no `CONFIRM`; `CONTINUE
  WITHOUT OUTLINE` displayed and enabled.
- planning, chips read-only: the chips are drawn as the repo's proven dimmed-and-untappable control, so a chip
  asserts `assertIsNotEnabled()` — the proof that there is no chip toggling.
- offline (an open outline row carrying the `Mission Control offline` mark): 'Mission Control offline —
  retrying' displayed, the same buttons as planning, and no `RETRY` — the worker retries on its own.
- failed (a FAILED outline row with an error): the row's reason displayed word for word, `RETRY` and `CONTINUE
  WITHOUT OUTLINE` displayed, no `CONFIRM`; the chips and the −/+ pair are still there and tappable (see
  Decisions).
- not configured (no row worth reporting, host and token blank): the not-configured sentence displayed,
  `CONTINUE WITHOUT OUTLINE` displayed, no `CONFIRM`; the chips and the −/+ pair are still there and tappable.
- one line only: a failed draft with blank settings shows its reason and nothing else; tapping Retry leaves
  the not-configured sentence in that same slot, and only one such line exists.
- updated existing test: `draftAlwaysOffersContinueWithoutOutline` no longer expects the button in the editable
  state; it becomes the planning case that clicks `CONTINUE WITHOUT OUTLINE` and waits for `LAUNCH`.
- `draftDrawsOneStepperPair`, `draftMinusDisabledAtOneSortie`, `draftPlusDisabledAtSevenSorties` and the
  guard/derivation/confirm classes keep passing unchanged apart from the fixture.

## Risks

- `LaunchpadDraftControlsTest`'s fixture has blank settings, so its stepper tests now run against a
  `NotConfigured` draft. Most of them would keep passing against the wrong state — the −/+ pair is drawn there
  too — but `draftPlusDisabledAtSevenSorties` asserts `CONFIRM` and would fail. The fixture sets host and token
  in step 1, so the class tests the editable draft it was written for.
- The failure reason's red cannot be asserted: Compose semantics carry no text colour. The tests assert the
  reason's words and the buttons around it; the colour is read off the code.
- A chip with no `clickable` at all has no `Enabled` semantics to assert, so `assertIsNotEnabled()` would be
  vacuous or fail on a missing property. The read-only chips are drawn with the `DraftStepButton` shape
  (`clickable(enabled = false, role = Role.Button)`), which really sets `Enabled = false`.
- The two new strings contain an em dash and an ellipsis; the edit and read tools show real UTF-8 punctuation
  as a replacement character. Check once with a `U+FFFD` grep and leave those lines alone.
- The draft reaches the screen through Room's flow, so each test waits for a known string before asserting, and
  `tearDown` must cancel the scope's `Job` before `db.close()` — the existing draft harness already does both;
  copy it rather than rebuilding it.
- Adding a status line can push a control out of the fixed test window. Use the same `w411dp-h2000dp`
  qualifiers as the existing draft test.
- The full gate can hang for about 5 minutes on this machine. Run it detached, read the log and the XML
  reports, and re-run the same tree if a test executor is stranded (move the results dir with `mv`, never `rm`,
  and do not run a second Gradle invocation in the same worktree at the same time).

## Decisions

**The pattern stays editable whenever no generation is open — including a failed draft and a draft with
Mission Control not set up.** The brief names the chips, the −/+ and Confirm for the `editable` status and
says the pattern is read-only while planning, but says nothing about `failed` and `not configured`. The
screen will make the pattern read-only only in `planning` and `offline`, the two states where a generation is
actually running, and leave it editable in `editable`, `failed` and `not configured`. The alternative is the
narrower reading — editable chips only in the `editable` status — which would mean a week whose coach was
never set up could not be reshaped at all: the only way to a 3-day week would be Mission Control's default
pattern. Editing the pattern is the owner's own choice and cannot collide with a coach that is not working;
editing it while the coach is writing this week's outline can. Confirm, Retry and 'Continue without outline'
still come from the flags the shipped `CoachStatus` already names, so this only decides the chips.

**Retry is the big red primary button in the failed state; 'Continue without outline' is always the smaller
underlined text button.** `design/README.md` says the un-mocked Launchpad states "put Retry / Re-fly / Scrub
where Launch is", and Confirm already sits in that slot, so Retry takes it: it is the way on for a failed
draft. 'Continue without outline' is the escape hatch, not the recommended path, so it stays secondary even
in the states where it is the only control. The alternative — Retry as another text button — would leave the
failed draft with no visible way on.

**One status line, and a Retry that finds nothing configured replaces it rather than adding a second line.**
The brief asks for the `not configured` line and for the separate `notConfiguredMessage` line to go, "unless a
Confirm tapped with nothing configured would otherwise show nothing". That case is real, but it arrives
through Retry, not Confirm: a `FAILED` outline row outranks blank settings, so a draft with cleared settings
shows its failure reason and Retry, and tapping Retry then reports `CoachNotConfigured`. The draft will draw
one line under the pattern: the status line, replaced by the not-configured sentence when an action just
reported it, cleared by the next attempt. Two lines would say "isn't set up" twice; dropping the report would
leave a Retry tap that does nothing visible. The sentence loses its trailing "or continue without an
outline", because the button now says that.

**The failure reason is the row's own words, in red, with no label.** `CoachStatus.Failed` keeps the reason
word for word, and the design's "Coach:" prefix is teal and marks advice, not a fault. The alternative — a
"Coach failed:" or "Couldn't plan this week:" label in front of the reason — would put words in the row that
the row does not contain, and would read oddly for the reasons the worker writes today.

**The draft keeps its own chips in every state, rather than the theme's pattern track.** The theme's
`PatternTrack` draws landed / current / upcoming chips joined by a rule, which describes sortie progress a
draft does not have yet. The draft's chips stay, drawn untappable in `planning` and `offline`. The
alternative — switching to `PatternTrack` outside the editable state — would make the same pattern change
appearance as the coach starts working, and would imply a current sortie.
