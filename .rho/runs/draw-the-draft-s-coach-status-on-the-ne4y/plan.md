# Plan

## Approach

The hard half of this is already merged: `LaunchpadState.Draft` carries a `CoachStatus` that names the actions
it offers, and `LaunchpadStates` keeps it live from Room (see the map). What is missing is the drawing. So the
change is confined to the draft arm of the screen: `DraftContent` gains one status line under the pattern and
decides whether the pattern is interactive, and `DraftActions` draws only the controls the status offers
instead of the same stepper, Confirm and 'Continue without outline' whatever the coach is doing. No new state
and no new seam. The existing `LaunchpadContent` seam plus rows written to in-memory Room reach every status,
because the DRAFT arm derives the Draft's status from exactly those rows (map). `LaunchpadCoachStatusTest`
already seeds the rows each status needs, without Compose, and a render test copies that fixture recipe. The
view model keeps its `notConfiguredMessage` report, with one fix: `retry()` must clear it the way `confirm()`
already does.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`
  - `DraftContent`: one status line replaces the `notConfiguredMessage` block. It shows the planning or
    offline line, the failed reason in `Red`, or the not-configured sentence. The chips are editable except
    while a generation is open.
  - `DraftActions`: the −/+ row appears only where the pattern is editable. `PrimaryButton("Confirm")`
    appears only in `editable`, and `PrimaryButton` "Retry" in `failed`. `UnderlinedTextButton("Continue
    without outline")` appears only where the status offers it. The always-visible button and the separate
    message block go.
  - `ActionButtons` and `LaunchpadContent`: gain an `onRetry` callback wired to `viewModel.retry()`. The
    bottom bar has no Retry path today.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`
  - `NOT_CONFIGURED_MESSAGE` loses its trailing "or continue without an outline". It is now both the draft's
    not-configured line and the report a tapped Retry leaves.
  - `retry()` sets `notConfiguredMessage = null` at the start of its launch, as `confirm()` does. Today a
    report from one Retry outlives the next, successful one. The field's own comment already says "cleared by
    the next attempt".
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftControlsTest.kt`
  - The fixture must set a host and token (`setDaemonHost` / `setDaemonToken`). It leaves them blank today, so
    every test there would now derive `NotConfigured`. With that set, the stepper cases keep their assertions
    unchanged.
  - Two cases were built on the old drawing and are rewritten. `draftAlwaysOffersContinueWithoutOutline`
    asserted the button beside Confirm. `draftShowsTheNotConfiguredMessageWhenItIsSet` can no longer work:
    with a configured fixture, `confirm()` cannot report not-configured. That case moves to the new class and
    is driven through Retry.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadDraftStatusTest.kt` (new): the five statuses
  rendered through `LaunchpadContent`, plus the one-line rule and the not-configured report a Retry leaves and
  clears.
- `ARCHITECTURE.md`: the M3 row.

## Order

1. Draw the status in `DraftContent` / `DraftActions`, wire `onRetry`, and drop the always-visible button and
   the message block. Re-word `NOT_CONFIGURED_MESSAGE` and clear the report at the start of `retry()`. Repair
   `LaunchpadDraftControlsTest`: configure the coach in the fixture and rewrite the two cases. Gate:
   assembleDebug plus that test class.
2. Add `LaunchpadDraftStatusTest` with the five statuses and the −/+ absence while planning, seeded the way
   `LaunchpadCoachStatusTest` seeds rows. Gate: assembleDebug plus that class.
3. Update the ARCHITECTURE.md M3 row: Confirm queues the outline and the draft shows its status; queueing
   Flight Plans and the sortie status display come next.
4. Run the whole gate detached (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, log to
   `app/build/`, `echo "EXIT=$?"` with no pipe) and read the reports. Re-run once if it hangs.

## Testing

Every test is render-only Robolectric Compose through `LaunchpadContent`, on a view model built on in-memory
Room, with `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`. No text fields, no WorkManager, every wait
bounded.

- editable (host and token set, no outline row): chips tappable, one −/+ pair, `CONFIRM`. No `RETRY`, no
  `CONTINUE WITHOUT OUTLINE`, no status line.
- planning (a QUEUED outline row): 'Coach is planning the week…' displayed. No −/+, no `CONFIRM`, no `RETRY`.
  `CONTINUE WITHOUT OUTLINE` displayed and enabled.
- planning, chips read-only: the chips are drawn as the repo's proven dimmed-and-untappable control, so a chip
  asserts `assertIsNotEnabled()`. That proves there is no chip toggling.
- offline (an open outline row carrying the `Mission Control offline` mark): 'Mission Control offline —
  retrying' displayed, with the same buttons as planning. No `RETRY`, because the worker retries on its own.
- failed (a FAILED outline row with an error): the row's reason displayed word for word. `RETRY` and `CONTINUE
  WITHOUT OUTLINE` displayed, no `CONFIRM`. The chips and the −/+ pair are still there and tappable (see
  Decisions).
- not configured (no row worth reporting, host and token blank): the not-configured sentence displayed and
  `CONTINUE WITHOUT OUTLINE` displayed. No `CONFIRM`, no `RETRY`. The chips and the −/+ pair are still there
  and tappable.
- one line only: a failed draft with blank settings shows its reason and nothing else. Tapping Retry puts the
  not-configured sentence in that same slot, and only one such line exists.
- the report clears: then set host and token and tap Retry again. The draft shows 'Coach is planning the
  week…', and the not-configured sentence is gone.
- updated existing test: `draftAlwaysOffersContinueWithoutOutline` no longer expects the button in the editable
  state. It becomes the planning case that clicks `CONTINUE WITHOUT OUTLINE` and waits for `LAUNCH`.
- `draftDrawsOneStepperPair`, `draftMinusDisabledAtOneSortie`, `draftPlusDisabledAtSevenSorties` and the
  guard, derivation and confirm classes keep passing unchanged apart from the fixture.

## Risks

- `LaunchpadDraftControlsTest`'s fixture has blank settings, so its stepper tests would now run against a
  `NotConfigured` draft. Most of them would keep passing against the wrong state, because the −/+ pair is
  drawn there too. But `draftPlusDisabledAtSevenSorties` asserts `CONFIRM` and would fail. Step 1 sets host
  and token in the fixture, so the class tests the editable draft it was written for.
- The failure reason's red cannot be asserted, because Compose semantics carry no text colour. The tests
  assert the reason's words and the buttons around it; the colour is checked by reading the code.
- A chip with no `clickable` at all has no `Enabled` semantics, so `assertIsNotEnabled()` would be vacuous or
  fail on a missing property. So the read-only chips are drawn the way `DraftStepButton` is drawn
  (`clickable(enabled = false, role = Role.Button)`), which really sets `Enabled = false`.
- The two new strings contain an em dash and an ellipsis. The edit and read tools show real UTF-8 punctuation
  as a replacement character, so check once with a `U+FFFD` grep and leave those lines alone.
- The draft reaches the screen through Room's flow. So each test waits for a known string before asserting,
  and `tearDown` must cancel the scope's `Job` before `db.close()`. The existing draft harness already does
  both: copy it rather than rebuilding it.
- Adding a status line can push a control out of the fixed test window. Use the same `w411dp-h2000dp`
  qualifiers as the existing draft test.
- The full gate can hang for about 5 minutes on this machine. Run it detached, read the log and the XML
  reports, and re-run the same tree if a test executor is stranded. Move the results dir with `mv`, never
  `rm`, and never run a second Gradle invocation in the same worktree at the same time.

## Decisions

**Added by review: the not-configured report shows only on a failed draft, and every Retry clears it first.**
Today `retry()` never clears `notConfiguredMessage`. So a report left by one Retry would survive the owner
setting up Mission Control and retrying again. It would then replace 'Coach is planning the week…' with
"isn't set up", which is now false. `retry()` will clear the report at its start, as `confirm()` already
does. The draft will also draw the report only in `failed`, the one status where a Retry can produce it. In
`not configured` the status line already says the same sentence. In the other statuses a left-over report
would contradict what the coach is doing.

**The pattern stays editable whenever no generation is open, including a failed draft and a draft with
Mission Control not set up.** The brief names the chips, the −/+ and Confirm for the `editable` status and
says the pattern is read-only while planning. It says nothing about `failed` and `not configured`. The screen
will make the pattern read-only only in `planning` and `offline`, the two states where a generation is
actually running. It stays editable in `editable`, `failed` and `not configured`. The alternative is the
narrower reading: editable chips only in the `editable` status. Then a week whose coach was never set up could
not be reshaped at all, and the only way to a 3-day week would be Mission Control's default pattern. Editing
the pattern is the owner's own choice and cannot collide with a coach that is not working; editing it while
the coach is writing this week's outline can. Confirm, Retry and 'Continue without outline' still come from
the flags the shipped `CoachStatus` already names, so this decision covers only the chips.

**Retry is the big red primary button in the failed state; 'Continue without outline' is always the smaller
underlined text button.** `design/README.md` says the Launchpad states with no mockup "put Retry / Re-fly /
Scrub where Launch is". Confirm already sits in that slot, so Retry takes it: it is the way on for a failed
draft. 'Continue without outline' is the escape hatch, not the recommended path, so it stays secondary even in
the states where it is the only control. The alternative, Retry as another text button, would leave the
failed draft with no clear main action.

**One status line, and a Retry that finds nothing configured replaces it rather than adding a second line.**
The brief asks for the `not configured` line and for the separate `notConfiguredMessage` line to go, "unless a
Confirm tapped with nothing configured would otherwise show nothing". That case is real, but it arrives
through Retry, not Confirm. A `FAILED` outline row outranks blank settings, so a draft with cleared settings
shows its failure reason and Retry, and tapping Retry then reports `CoachNotConfigured`. The draft will draw
one line under the pattern: the status line, replaced by the not-configured sentence when a Retry just
reported it, and cleared by the next attempt. Two lines would say "isn't set up" twice, and dropping the
report would leave a Retry tap with no visible effect. The sentence loses its trailing "or continue without an
outline", because the button now says that.

**The failure reason is the row's own words, in red, with no label.** `CoachStatus.Failed` keeps the reason
word for word, and the design's "Coach:" prefix is teal and marks advice, not a fault. The alternative is a
"Coach failed:" or "Couldn't plan this week:" label in front of the reason. That would add words the row does
not contain, and would read oddly with the reasons the worker writes today.

**The draft keeps its own chips in every state, rather than the theme's pattern track.** The theme's
`PatternTrack` draws landed / current / upcoming chips joined by a rule. That describes sortie progress, which
a draft does not have yet. The draft's chips stay, drawn untappable in `planning` and `offline`. Switching to
`PatternTrack` outside the editable state would make the same pattern change appearance as the coach starts
working, and would imply a current sortie.
