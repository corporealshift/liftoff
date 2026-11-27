# Plan

## Approach

The only place a run's duration is set is the Land dialog's Duration field (`RunLandDialogContent`);
DESIGN.md §16 keeps timers and GPS out of scope, so the change is to that entry. A **Seconds** field
goes beside Duration on the same row, and `parseRunEntry` — which already parses both fields before
anything is landed — gains a third text and combines minutes and seconds into the one number of
minutes the app already stores. Nothing in the data layer moves: `Sortie.runMinutes` stays a single
`Double`, so there is no Room migration, no schema bump and no change to the backup format. The rest
of the work is reading the value back. Today `MissionScreen` rounds the stored duration with
`%.0f min` — 18.5 min prints `19 min` — and the coach's History line would print a 34-second run as
`27.566666666666666 min`. One minutes-plus-seconds formatter replaces both and drops a zero seconds
part, which is why whole-minute runs — every existing fixture and golden prompt — read exactly as
they do now.

## Files

Main:

- `ui/inflight/RunLanding.kt` — `parseRunEntry` takes the seconds text too; `RunEntry.Valid` keeps
  one `minutes: Double?`, now minutes + seconds/60; `RunEntry.Invalid` gains a `secondsError`; a
  `SECONDS_ERROR` text joins the two existing ones; the "above 0" rule moves to the combined total.
- `ui/inflight/RunLandDialog.kt` — a third remembered blank field, `Seconds`, numeric keypad, on the
  same row as Duration so the two read as one value; `onLand` reports three strings.
- `ui/inflight/InFlightScreen.kt` — the run Land lambda passes the seconds text through.
- `ui/inflight/InFlightViewModel.kt` — `landRun` takes the seconds text and hands the combined
  minutes to `missionManager.land`.
- `ui/sortie/PlanFormat.kt` — a minutes-plus-seconds formatter for a recorded duration: whole
  minutes plus whole seconds, a zero part dropped (`32 min`, `27 min 34 s`, `45 s`). `formatMinutes`
  stays as it is, for coach-authored plan segments.
- `ui/mission/MissionScreen.kt` — the landed run line uses that formatter instead of rounding;
  `LandedDetailView` becomes `internal` so a test can read the line, the way `RunLandDialogContent`
  already is.
- `ui/landed/LandedState.kt` — `recordedDuration` uses the same formatter.
- `coach/CoachPromptBuilder.kt` — `runLine` prints the recorded duration with the same reading,
  through a private helper in `coach/` rather than an import from `ui/`.

Tests: `RunLandingTest`, `LandScrubDialogTest`, `InFlightRunTest`, `InFlightViewModelTest`,
`PlanFormatTest`, `LandedStatesTest`, `CoachPromptBuilderTest`, plus one new render of the Mission
landed detail.

Untouched, with the reason in the map: `Sortie.kt`, `MissionManager.kt`, `MissionState.kt`, the Room
schema, export and import, `BackupDocument`, and the design mocks.

## Order

Each step leaves the build green and the gate runnable.

1. The formatter in `PlanFormat.kt` and its cases in `PlanFormatTest` — no callers yet.
2. The three places a recorded duration is read back: `MissionScreen` (with `LandedDetailView` made
   `internal`, plus the new render), `LandedState`, and `CoachPromptBuilder.runLine`, each with its
   assertion. Whole minutes still read as they do today, so the golden prompt files are not edited —
   the golden test is run to confirm they come out byte-identical.
3. The entry: `RunLanding.kt`, then `RunLandDialog`, `InFlightScreen` and `InFlightViewModel` in the
   same step, because `parseRunEntry`'s extra argument reaches all of them at once.
4. The entry's tests: `RunLandingTest` cases, the Land card's field assertions in
   `LandScrubDialogTest`, and the `landRun` cases in `InFlightRunTest` and `InFlightViewModelTest`.

## Testing

- Landing with minutes and seconds stores the sum as the run's duration — view-model + DAO
  (`InFlightRunTest`), read back out of `sortieDao`.
- A whole-minute entry lands exactly as it does today, with the seconds field left blank — the
  existing `landRun` cases, unchanged except for the extra argument.
- Seconds outside 0–59, a negative, a fraction, or text are refused, the error is the seconds one,
  and nothing lands — pure parser (`RunLandingTest`) plus the view-model case that asserts the
  manager is not touched.
- Seconds with no minutes lands a sub-minute duration; minutes and seconds both zero are still
  refused — pure parser.
- The Land card shows a Seconds field beside Duration, starts empty, and puts its error under
  itself — Compose render of `RunLandDialogContent` (`LandScrubDialogTest`).
- The formatter prints `32 min` for whole minutes, `27 min 34 s` for a fractional value and `45 s`
  under a minute, and round-trips a 27 min 34 s entry back to that string — pure unit
  (`PlanFormatTest`).
- The Mission landed detail reads `18 min 30 s` where it reads `19 min` today — Compose render of
  the detail view from a state object, no database and no waits.
- The Landed history TIME line reads the same way, and `32 min` still reads `32 min` — state test
  (`LandedStatesTest`).
- The coach's History line prints a fractional recorded duration as minutes and seconds, and whole
  minutes exactly as the golden files already say — `CoachPromptBuilderTest`, with the golden-file
  test run unchanged.
- A seconds-precision duration still survives export and import — `BackupRoundTripTest` already
  round-trips a fractional duration; it is expected to pass untouched.

## Risks

- A duration entered as 27 min 34 s is stored as `27.566666666666666`. The formatter has to go
  through whole seconds with rounding, or it prints `27 min 33 s`. The formatter test covers the
  awkward values — `18.5`, `27.75`, and the `27.566666…` a 34-second entry produces — rather than
  one tidy case.
- `LandScrubDialogTest` counts the editable fields on the Land card (4 across two cards, 2 on one)
  and orders them by their vertical bounds to check each error sits under its own field. A third
  field changes both the counts and that ordering; they are updated deliberately, not relaxed,
  because the count is what proves the field exists.
- `LabeledTextField` is a full-width column, so two of them on one row each take half the card. The
  seconds label has to stay short (`Seconds`, not `Duration (sec)`), and the row is the one layout
  decision in the change.
- The coach prompt is a contract with three golden files. Whole minutes print exactly as today, so
  the fixtures are not edited; if the golden test disagrees, the builder is wrong and not the
  fixture.
- `MissionScreen` has no test coverage at all today. Making `LandedDetailView` `internal` is the
  only way to show the line, and the render must stay static — a state object in, no database, no
  waits — so it does not join the flaky Compose classes.
- `parseRunEntry`'s extra argument reaches the dialog, the screen and the view model at once, so the
  build is red until all four move together; that is why step 3 is one step.
- DESIGN.md §16 keeps timers and GPS out of scope and this is still manual entry, so no design doc
  needs editing. If the owner wants §5.3's "enter a distance and duration" to name seconds, that is
  a one-line edit in the same commit.

## Decisions

**Does the seconds control replace minutes, or sit beside them?** Minutes stay the primary unit and seconds sit beside them, on the same row as Duration. The alternative is one total-seconds field ("1830 s"), which is precise but reads badly and makes the common case — a round 30-minute run — harder to enter and harder to read. The issue says "just setting minutes isn't accurate enough", which asks for minutes *plus* the missing precision, not for minutes to go away. A single field that took `27:34` was considered and rejected: the Duration field uses the decimal keypad, which has no colon.

**How fine are the seconds, and what happens to a value outside them?** Whole seconds, 0 to 59. The alternatives — 5-, 15- or 30-second steps — need fewer taps but re-impose the rounding the issue is complaining about. A value outside 0–59 is refused with an error under the field rather than rolled into minutes: the app already refuses bad input instead of silently correcting it, and "90" is more likely a slip than a request for 1 min 30 s.

**Can a run be shorter than a minute?** Yes: blank or 0 minutes with seconds above 0 lands 45 s. The alternative is a one-minute floor, which would make the new field only useful for padding out a minute. The existing rule that a duration must be above 0 is kept; it just applies to the combined total.

**Does the Duration field still accept a decimal like 27.5?** Yes, and the seconds add to whatever it says. The alternative is whole numbers only now that a seconds field exists, but that would refuse input the app accepts today and that its tests already cover, for no gain to the person entering a run.

**What happens to durations already stored, and to how they are stored?** Nothing changes. A run saved as 32 minutes still reads 32 minutes, and the value stays one number of minutes in the same column, so exports, backups and imports keep their current shape. The alternative — a separate seconds column — would need a Room migration and a backup format change to hold something the column can already hold.

**How is the duration read back?** As minutes plus seconds, with a zero part dropped: `32 min`, `27 min 34 s`, `45 s`. That applies everywhere the recorded duration appears — the Mission landed line, the Landed history's TIME, and the coach's History line. The alternatives are to show `30:00` for every round value, which adds noise to runs that are usually round, or to leave the coach line as decimal minutes, which prints `27.566666666666666 min` for a 34-second run. Whole minutes print exactly as they do today, so the coach's golden prompts are untouched.
