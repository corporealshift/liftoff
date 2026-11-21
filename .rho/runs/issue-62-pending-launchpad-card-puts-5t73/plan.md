# Plan

## Approach

The PENDING card's title is the only Launchpad title that can be long, because it is the outline's own
focus while a PLANNED card's title is the plan's short one (map: `LaunchpadState.kt:193` vs `:209`). The
fix therefore goes at that one card's `TitleBlock` call site in `PendingContent`: the title gets a
two-line cap, and its display size steps down from the design's 68 sp until the focus fits in two lines,
down to a readable floor; past the floor the cap holds and the text ellipsizes. Everything else the card
draws — eyebrow, the 16 dp and 20 dp spacers, the pattern track, the status line — stays where it is, so
the PENDING card's layout is the PLANNED card's layout, which is what the brief asks for. The shared
`TitleBlock`/`DisplayTitle` keep today's behaviour for every other call site, so no other screen's title
moves. Where the fit rule itself lives (a small helper beside the Launchpad card, or in `ui/theme`) is the
work session's call; what matters is that it stays a rule about this title and not a general autosize for
every title in the app.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — `PendingContent`'s `TitleBlock`
  call site (216) is where the fitted size and the two-line cap are applied. Nothing else in the card changes.
- `app/src/main/java/com/liftoff/app/ui/theme/Headings.kt` — `DisplayTitle`/`TitleBlock` gain a line cap
  (`maxLines` + `overflow`) whose default leaves the other four Launchpad call sites and the Mission,
  Landed, Mission Control and Placeholder ones exactly as they are today.
- `app/src/main/java/com/liftoff/app/ui/theme/Type.kt` — only if the floor needs a display size the scale
  does not already have. The existing roles are asserted at their exact sizes by `TypographyTest`, so a new
  size is added beside them, never a change to one.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPendingTitleTest.kt` — new screen test, on
  `LaunchpadPendingStatusTest`'s harness (see map).
- `design/README.md` — one line on the Type table's "Screen title (Launchpad)" row, so the design doc says
  what the title does when the text is long instead of only what it is at 68 sp.

## Order

1. Write `LaunchpadPendingTitleTest` with the reported long focus on a PENDING sortie and run it alone
   against today's tree. It must fail there — the title node comes out several lines tall at 68 sp, and the
   number it reports is what sets the bound for steps 3 and 4.
2. Give `DisplayTitle`/`TitleBlock` the line cap, defaulted to today's behaviour; the build and the whole
   existing suite stay green because no call site asks for a cap yet.
3. Apply the fit at `PendingContent`'s call site; step 1's case now passes.
4. Add the short-focus case and the floor/ellipsis case to the same file, and the PLANNED-card case that
   shows its title is untouched.
5. The one line in `design/README.md`'s Type table.
6. The gate (map: the local command for it), then the run's verify step.

## Testing

All in the new `LaunchpadPendingTitleTest` (Compose screen test under Robolectric, the harness and the
`w411dp-h2000dp` qualifiers described in the map), unless noted.

- A PENDING LIFT sortie whose focus is the reported "LOWER STRENGTH – SQUAT & HINGE, PLUS CORE": the title
  node's `boundsInRoot` height is no more than two lines at the largest size the title may use — 68 sp at
  0.9 line-height is about 122 dp for two lines, plus room for font padding. The bound has to be tight
  enough that today's five-line 68 sp title breaks it — that is the assertion step 1 proves fails.
- The same card: the status line ("Coach is planning…") stays near the top of it — its top within a bound
  read off the passing render, with room for one line's worth of slack. The gap between the title and the
  status line is fixed by the card's own spacers, so it is the status line's distance from the top of the
  content that moves when the title grows; that is the brief's actual complaint, and it is what stops a
  shrink that merely squeezes three lines under the height bound from passing.
- A short focus ("Bench Press"): the title still renders as one line at the full 68 sp display size — its
  height is one line's worth at 68 sp, about 61 dp — so the common case is unchanged.
- A focus long enough that the shrink alone cannot fit it: the cap still holds at the floor — the title's
  height is under three lines at that size — so the layout survives the case the shrink cannot solve.
- The PLANNED card with the same long focus stored on the sortie: its title is the plan's short title at
  68 sp, unchanged — the fix did not reach it.
- `TypographyTest` and the existing Launchpad screen tests pass untouched; no type role's size changes and
  no other card's layout moves.

## Risks

- **Compose 1.7.6 gives a test no line count for a plain `Text`** (checked in the artifacts:
  `SemanticsActions.GetTextLayoutResult` is registered only by text fields and the scroll modifiers, and
  `BoundsAssertionsKt` has no `assertHeightIsAtMost`), so the case compares `boundsInRoot` heights. The work
  derives the two-line bound from the largest size the title may use and leaves room for Robolectric's font
  padding; the status-line position assertion is what keeps a merely-shrunk three-line title from passing.
- **A measure-then-shrink title recomposes** until it settles (the repo's `onTextLayout` idiom,
  `Buttons.kt:192`). The test must await the settled size with `waitUntil` rather than assert on the first
  frame, and the size ladder needs a floor so it cannot keep recomposing.
- **Robolectric's metrics may not match a device's**, so the reported 41-character focus may land at the
  floor and ellipsize instead of fitting whole. The tests assert the cap and the layout, not which size was
  chosen; only the short-focus case pins a size.
- **`TitleBlock`/`DisplayTitle` are shared by five screens.** The cap is a defaulted parameter and only
  `PendingContent` asks for it; the PLANNED-card case in the new test is the guard that the Launchpad card
  next to it did not move.
- **The same long title exists on the Mission screen's sortie titles** (`MissionState.kt:144,162`), and the
  brief names only the Launchpad card. The plan leaves it; the defaulted cap makes extending the rule there
  a one-call-site change later if the owner wants it.
- **The gate's known flake**: `LiftoffShellTest` intermittently times out one `waitFor` in a full-suite
  run. Rerun the gate before believing a failure there.
- **`gradlew.sh` is Windows-only at HEAD**, so the brief's own gate command cannot run in this worktree;
  the map has the local equivalent, and the run's `verify.sh` already handles it.

## Decisions

**Shrink the type, ellipsize only as a last resort.** The brief offers either ("shrink the type to
fit (or ellipsize)"). The plan shrinks the PENDING title's type until the whole focus fits in two
lines, down to a readable floor, and ellipsizes only when it still does not fit at that floor. The
alternative — ellipsize at today's display size — is simpler, but the focus is the only text on a
PENDING card that says *which* sortie is generating, so cutting it to "LOWER STRENGTH – SQUAT &…"
throws away the one piece of information the card carries. Shrinking keeps the whole name legible;
the floor and the ellipsis together still guarantee the two-line layout.

**Only the PENDING title changes; PLANNED keeps its display type.** The alternative — the same fit
rule on every Launchpad title — would make the two cards' type sizes always match, but the brief's
complaint is specifically the outline focus, which is long and all-caps, while plan titles are
already short ("LOWER — SQUAT & HINGE"). Re-typesetting the card the person is already reading, to
fix a card they are not, is the change they would not expect. The consequence is accepted: a long
focus renders smaller than the plan title that replaces it when generation lands.

**The cap is on the title text, not on the card's height.** The brief's symptom is the title running
to five lines and pushing the status to mid-screen, so the plan caps the title at two lines and leaves
the card's own layout (its padding, the status row, the buttons) exactly as PLANNED renders it. The
alternative — a fixed card height with the title clipped — would hold the layout but hide text that
the shrink rule can usually show.

**A focus that already fits is untouched.** Short focuses keep today's display type, so the common
case looks exactly as it does now and only the long-focus case changes. The alternative — a fixed
smaller type for every PENDING title — would shrink cards that never had a problem.

**The stored focus is not shortened; this is a display rule.** The alternative — trimming the focus
where it is saved, or in `LaunchpadState` — would fix the Launchpad card with no measuring at all, but
the same focus is shown in full elsewhere (the Mission screen's sortie rows, the PLANNED card's run
summary), and the coach's own words are what the person named their week by. A card that cuts the text
while every other screen still shows it whole is the version a person would notice as a bug.
