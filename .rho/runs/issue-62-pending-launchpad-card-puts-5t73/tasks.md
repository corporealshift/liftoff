# Tasks — issue 62: the PENDING Launchpad title runs long in display type

Five tasks, in the plan's order. Each ends in its own commit with an `area: lowercase summary` message
(CLAUDE.md), staging only the files its own entry names — never `git add -A`.

**The coupling every task must respect.** `DisplayTitle`/`TitleBlock` (`ui/theme/Headings.kt`) draw the
title on five screens — Launchpad (139, 191, 216, 261, 277), Mission (`MissionScreen.kt` 111, 165, 176,
202, 242), Landed (100, 178), Mission Control (143), `PlaceholderScreen.kt:29` — plus three direct
`DisplayTitle` calls (`InFlightScreen.kt:313` and `:440`, `RunContent.kt:58`), which already use the 44 sp
`headerTitle`. Only `PendingContent` (216) may end up drawing anything differently; every other call site
has to come out of each commit rendering exactly what it renders today. `TypographyTest` locks each type
role's exact size, weight and line-height (`originalEightRolesAreUnchanged` compares whole `TextStyle`
objects), so a size the ladder needs is added beside the roles, never a change to one.
`LaunchpadPendingTitleTest.kt` is written across tasks 2 and 3: task 3 reads the file task 2 left and adds
to it rather than starting a second file.

**What no task touches:** `verify.sh`; `LaunchpadState.kt` (no new field, no trim — plan decision "The
stored focus is not shortened; this is a display rule"); `MissionState.kt` and `MissionScreen.kt` (plan
Risk: the Mission screen's long sortie titles are left alone); the Launchpad tests that already exist; and
inside `PendingContent` everything but the `TitleBlock` call site — the 16 dp spacer, `PatternTrack`
(52 dp tall), the 20 dp spacer and the status line (plan decision "The cap is on the title text, not on the
card's height").

**The five tests verify.sh names** — its `t <method>` lines, matched across every report of the gate run,
so which file and package they sit in is the implementer's call — are all new and all belong in
`app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPendingTitleTest.kt`: task 2 writes
`pendingTitleWithALongFocusIsAtMostTwoLinesTall` and
`pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus`; task 3 writes
`pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize`,
`pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall` and
`plannedCardTitleIsStillOneLineAtTheFullDisplaySize`. Nothing else in the repo is a new test; the six
classes verify.sh requires green already exist.

**Every case in that file needs real text metrics**: `@GraphicsMode(GraphicsMode.Mode.NATIVE)` on the
class (`org.robolectric.annotation.GraphicsMode`). Map: Robolectric's default graphics mode on this project
makes every character 1 dp wide and every line 35 dp tall whatever the font size, so a five-line title is
indistinguishable from a one-line one and a case that measures 35 dp is not measuring the card. No test in
this repo sets a graphics mode today, so this file is the first to.

Read before starting: `brief.md`, `plan.md`, `map.md`, CLAUDE.md, and the harness to copy —
`LaunchpadPendingStatusTest.kt` for a PENDING card, `LaunchpadPlannedActionsTest.kt` for a PLANNED one,
`LaunchpadViewInRhoTest.kt:188–199` for the `boundsInRoot` idiom. Gate for each task (map: "Gate on this
machine"):

```
JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk \
  $HOME/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest --no-build-cache
```

`--tests com.liftoff.app.ui.launchpad.LaunchpadPendingTitleTest` is fine while iterating; commit only after
a full run in which no `app/build/test-results/testDebugUnitTest/TEST-*.xml` reports a failure, an error or
a skip. Scratch files and gate logs go under gitignored `app/build/`, never `app/src/test`.

- [x] 1. `theme: give the shared title composables a line cap that defaults to today`
  Plan step 2, landed alone with no caller yet. `DisplayTitle` and `TitleBlock` in
  `app/src/main/java/com/liftoff/app/ui/theme/Headings.kt` gain a line cap — a maximum line count and an
  overflow — whose defaults leave all seventeen call sites outside `Headings.kt` (14 `TitleBlock`, 3 direct
  `DisplayTitle`) drawing exactly what they draw today
  (`DisplayTitle` uppercases the text and takes `style`/`color`; `TitleBlock` is eyebrow + 4 dp spacer +
  `DisplayTitle` and takes `titleStyle`; neither caps anything today). The cap's shape is the implementer's
  call, but it is a per-call-site parameter, not a new default for screen titles, and it does not touch
  `LiftoffType.screenTitle()` or any other role. No call site in `app/src/main` asks for the cap in this
  task — task 2 is the one that asks for it.
  Done when: `:app:assembleDebug` and `:app:testDebugUnitTest` ran with `--no-build-cache` and no report has
  a failure, error or skip; `TypographyTest` green with no role's size, weight, line-height or letter
  spacing changed; `LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`, `LaunchpadViewInRhoTest`,
  `LaunchpadCoachStatusTest` and `LaunchpadStatesTest` green; and
  `grep -rn "TitleBlock(\|DisplayTitle(" app/src/main/java | grep -v theme/Headings.kt` shows no call site
  passing a cap yet.

- [ ] 2. `launchpad: keep the PENDING title inside two lines`
  Plan steps 1 and 3, in that order. **Write the two cases below first and run them against the tree as it
  is** — they must fail there, and what they report is the map's measurement (title node 329 dp, status top
  435 dp), which is what the fix is then measured against. Then the fix, at `PendingContent`'s `TitleBlock`
  call site (`LaunchpadScreen.kt:216`): measure the title in the card's own 371 dp column and step its
  display size down from `screenTitle`'s 68 sp until the focus fits two lines, down to a readable floor, and
  hold the cap with an ellipsis past the floor. The repo's measure-then-shrink idiom is `onTextLayout` with
  `result.lineCount` (`Buttons.kt:192`, `BackupSection.kt:161`); the ladder needs a floor so the
  recomposition settles rather than looping. Where the rule lives — a private helper beside the card, or in
  `ui/theme` — is the implementer's call; it stays a rule about this title and only this title. If task 1's
  cap shape does not fit what the measuring needs, adjust `Headings.kt` in this commit, with the other call
  sites' rendering unchanged. `Type.kt` only if the ladder needs a size the scale does not already have (it
  has 68 / 44 / 34 / 30 / 22), added beside the roles.
  File: `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPendingTitleTest.kt`, on
  `LaunchpadPendingStatusTest`'s harness — `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, so the
  card's inner Column is 371 dp wide and density 1.0 makes `boundsInRoot` pixels already dp — plus
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)`. The focus goes on the sortie row itself (`SortieDao.update` on
  the row `continueWithoutOutline` created, the way `LaunchpadStatesTest.kt:94–95` writes one); that queued
  FLIGHT_PLAN row is what makes the status line read "Coach is planning…". Use verify.sh's own string for
  the reported focus — `"Lower Strength – Squat & Hinge, plus core"`, 41 characters, the dash an en dash
  U+2013, since a hyphen measures a different width. Find the title node by a substring of the focus or by
  its position between the eyebrow and the chips row, never by the whole focus. Await the settled render
  with `waitUntil` before measuring.
  Tests written here:
  - `pendingTitleWithALongFocusIsAtMostTwoLinesTall` — that card's title node at most 175 dp tall (two
    lines at 68 sp is 143 dp, three is 205 dp, today it is 329 dp).
  - `pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus` — the same card's status line node with its
    top edge at most 280 dp from the top of the render (a two-line title puts it at 249 dp at 68 sp and at
    198 dp at 44 sp, three lines at 311 dp, today 435 dp).
  Done when: both names appear as passing `<testcase>`s in this run's reports; both were run against the
  pre-fix tree first and failed there; a full gate run shows no failure, error or skip anywhere; the five
  Launchpad classes verify.sh names are still green.

- [ ] 3. `launchpad: tests that the cap leaves the short focus and the PLANNED card alone`
  Plan step 4: three more cases in the file task 2 left — read it first and reuse its harness helpers, its
  node-finding and its `@GraphicsMode`. No new source unless one of these fails, in which case task 2's fix
  is corrected in this same commit: these three are the cases that catch a cap tuned to the one length the
  issue happens to report, a cap that shrinks a title that already fits, and a fit that reached
  `PlannedContent`.
  - `pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize` — PENDING card, focus "Bench Press": the
    title node's height between 75 dp and 110 dp (one line at 68 sp measures 81 dp; 35 dp is the default
    graphics mode, i.e. the case is not measuring the card).
  - `pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall` — the 112-character focus verify.sh
    spells out ("Lower Strength – Squat, Hinge, Core, Lunges, Calf Raises, Seated Cable Rows, Face Pulls,
    Hamstring Curls and Abs"): the title node at most 175 dp, the same bound as task 2's case (today 701 dp,
    eleven lines).
  - `plannedCardTitleIsStillOneLineAtTheFullDisplaySize` — the PLANNED card for the same sortie: the long
    focus written on the row, and the card reached the way `LaunchpadPlannedActionsTest.kt`'s `plannedLift()`
    (153) reaches it — `markSucceeded` on the row `continueWithoutOutline` queued, then `manager.storePlan`
    with `CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)`. Its title node is
    the plan's title, found by "PUSH + CORE", height between 75 dp and 110 dp.
  Done when: those three names appear as passing `<testcase>`s in this run's reports;
  `LaunchpadPendingTitleTest` green as a class with all five cases in it; a full gate run shows no failure,
  error or skip anywhere.

- [ ] 4. `docs: design/README.md says what the Launchpad title does when the text is long`
  Plan step 5, one line: the Type table's "Screen title (Launchpad)" row (`design/README.md:59`) says what
  the title does when the text does not fit at 68/900 — the PENDING card's title, the sortie's focus, is
  capped at two lines, shrinks to fit down to a floor and ellipsizes past it, while a title that already
  fits keeps 68/900 — in that row's Notes cell or as one line under the table. No other row of the table
  changes, and no font, size or weight in it changes (`TypographyTest.namedRolesMatchReadmeTypeTable` is the
  code side of these rows; it asserts the roles by hand and does not read the file). DESIGN.md §9 says
  nothing about title sizes and the plan's Files list names only this file, so no DESIGN.md amendment is
  needed here.
  Done when: the row says what happens when the text is long, not only what the role is at 68/900; every
  other row of the table byte-identical to before; the file stays LF-only
  (`LC_ALL=C tr -d -c '\r' < design/README.md | wc -c` reports 0); the suite still green.

- [ ] 5. Gate: the whole suite runs clean, and the run's checks pass
  No new source. Run `bash .rho/runs/issue-62-pending-launchpad-card-puts-5t73/verify.sh` from the repository
  root and read its output, not just its exit code. Then sweep for what the plan decided to leave:
  `grep -rn "TitleBlock(\|DisplayTitle(" app/src/main/java | grep -v theme/Headings.kt` — every call site
  except `PendingContent` still uncapped, including the two Mission ones that can carry the same long focus
  (`MissionScreen.kt:165,176`, plan Risk) and the other four Launchpad ones (139, 191, 261, 277). Fix in
  this commit only what the sweep turns up that the plan did not already decide to leave.
  Done when: the gate passes and `> Task :app:testDebugUnitTest` is **not** `FROM-CACHE`, `UP-TO-DATE` or
  `NO-SOURCE`; the five names verify.sh requires appear as passing `<testcase>`s; its six class checks all
  read ok (`TypographyTest`, `LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`,
  `LaunchpadViewInRhoTest`, `LaunchpadCoachStatusTest`, `LaunchpadStatesTest`); zero failures, errors and
  skips across every `TEST-*.xml`. `LiftoffShellTest` intermittently expires one `waitFor` in a full-suite
  run on this machine — rerun the gate before believing a failure there (verify.sh retries it itself). If the
  sweep finds nothing, still commit: write what was checked and what was found into
  `.rho/runs/issue-62-pending-launchpad-card-puts-5t73/findings.md` and commit that — a task that commits
  nothing is rejected. Never edit `verify.sh`.
