# Tasks — issue #63: the theme tests can't fail

Seven tasks, in order. Task 1 is the production refactor the rest read from; tasks 2–5 rewrite
one test class each; task 6 does the two things that are not test rewrites; task 7 runs the gate
and sweeps. Each task ends in its own commit. Do not edit or delete
`.rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/verify.sh`; do not push.

**The gate every task runs** (there is no `./gradlew` on this machine — see map.md):

```
JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk \
ANDROID_SDK_ROOT=$ANDROID_HOME ~/android-toolchain/gradle/bin/gradle \
  :app:assembleDebug :app:testDebugUnitTest --no-build-cache
```

Add `--tests <fqcn>` to run one class. A task's named cases are proven when
`app/build/test-results/testDebugUnitTest/TEST-<fqcn>.xml` records each one as a `testcase` with
no `<failure>` and no `<skipped/>` — a case that never appears in the XML did not run. Scratch
probe classes go under gitignored `app/build/scratch-taskN/` with an init script (map.md), never
under `app/src/test`. Run the gate once per task; a second identical green run adds nothing.

Read `brief.md`, `plan.md` and `map.md` before writing. Two things about map.md: it records the
verified line numbers and case names of every file this run touches, and if a `read` of a file
disagrees with it, re-check with `git show HEAD:<path> | wc -l` and `grep -n` before believing
either — a first read of some paths in this worktree returned content that is not in the tree.
Tasks 2–5 read the constants from the production files; if one they need was never named, name it
in that same file and record it in map.md. Append what you learn to map.md; do not rewrite it.

- [x] 1. Name the literals in the four theme files
  Production-only refactor, plan step 1: `app/src/main/java/com/liftoff/app/ui/theme/Stripes.kt`,
  `PatternTrack.kt`, `Buttons.kt`, `OffsetShadow.kt`. Every value the plan lists — band heights,
  chip sizes, connector width and its inset, check-icon size, the chip stroke widths, button
  heights, shadow offsets, border widths, icon size, the shadow corner radius — becomes an
  `internal` val in its own file, used at the draw site. No test file is touched, nothing moves
  between files, no value changes. Note: `OffsetShadow`'s radius is inside a default `Shape`
  argument (`RoundedCornerShape(4.dp)`), and `Buttons` uses `ButtonShape` from `Shape.kt` rather
  than a radius literal; the icon/label `Spacer(12.dp)` also needs a name, because task 4 asserts
  a shift that includes it.
  Done when: `:app:assembleDebug` and the full unit suite are green with `git status` showing only
  those four files, and the constant names and values are appended to map.md under "Constants
  named by task 1" so tasks 2–5 read them instead of re-deriving them.

- [x] 2. Rewrite `StripesTest`
  `app/src/test/java/com/liftoff/app/ui/theme/StripesTest.kt`. Tests it writes:
  **`StripesTest.stripeBandHeightsAreTheValuesTheStripesDraw`** — the named band-height constants
  read from `Stripes.kt`, compared against each other (TriStripe's three equal bands, DuoStripe's
  two, TriStripe's band > DuoStripe's), never against a copy of themselves.
  **`StripesTest.stripesRenderAtTheirBandHeights`** — render `TriStripe()` and `DuoStripe()` with
  `createComposeRule()` + `@RunWith(AndroidJUnit4::class)`, tag each, and assert the measured
  height equals 3× `TriStripe`'s band constant and 2× `DuoStripe`'s band constant. Convert dp→px with the
  composition's own density (capture `LocalDensity` in `setContent`); do not assume 1.0.
  The vacuous `Red.value == Red.value` / `Mustard.value == Mustard.value` /
  `Teal.value == Teal.value` checks and the `0xFFC23F14` hex check are deleted — `ColorTokensTest`
  already pins the tokens. The class ends with just these two cases.
  Done when: the class runs alone green, both names are in its XML report, and the full gate is green.

- [x] 3. Rewrite `PatternTrackTest`
  `app/src/test/java/com/liftoff/app/ui/theme/PatternTrackTest.kt`. Tests it writes:
  **`PatternTrackTest.chipSizeFollowsChipState`** — `chipSizeDp(state)` called with each of the
  four `ChipState` values, compared against the named size constants and against each other
  (Current larger than Landed/Upcoming/Scrubbed).
  **`PatternTrackTest.trackDrawsOneChipPerLetterAndMeasuresItsHeight`** — render `PatternTrack`
  with a known list of `PatternChip(letter, state)` values and assert one chip per letter, plus the
  measured track height against the named track-height constant. A Landed chip is a `Canvas` plus an
  `Icon(contentDescription = null)`, so it produces no semantics node — count what the tree can
  actually see, and say in the assertion what is being counted.
  **`PatternTrackTest.chipRingsAndConnectorAreTheDesignWidths`** — the named stroke widths and the
  connector width compared against each other (Current's ring thicker than Upcoming's, connector
  thinner than either), not against copies of themselves.
  The existing Ink/Red/Cream/White-vs-token colour assertions are kept in some form (their own
  cases or folded into the named ones); `PatternTrack(chips, modifier)` takes no colour
  parameters, so they read `LiftoffTheme.palette` against the literals. `ChipState` is the enum's
  real name — `PatternChipState` does not exist in this repo.
  Done when: the class runs alone green, all three names are in its XML report, and the full gate
  is green.

- [x] 4. Rewrite `ButtonsTest`
  `app/src/test/java/com/liftoff/app/ui/theme/ButtonsTest.kt`. Tests it writes:
  **`ButtonsTest.buttonDimensionsAreTheDesignValues`** — the named height, shadow-offset,
  border-width and icon-size constants compared against each other (PrimaryButton taller than
  InkButton, both taller than the UnderlinedTextButton minimum, icon smaller than the height).
  **`ButtonsTest.pressedFillIsComputedFromTheTokens`** — `RedPressed` derived from `Red` and
  `InkRed` by the same mix `Buttons.kt` performs, asserted against the palette values, not against
  a literal.
  **`ButtonsTest.labelShiftsWhenTheIconMoves`** — render `PrimaryButton` twice with the same label
  and icon, `iconAtEnd` false and true, and assert the label's left edge moves by the named icon
  size plus the named gap. `Modifier.clickable` merges descendants, so the label node needs
  `useUnmergedTree = true`; the two renderings must be the same width to be comparable.
  The existing `RedPressed`/`Red`/`Ink` colour assertions are kept — note `InkButton`'s shadow is
  `Red` at rest and `RedPressed` when pressed, and the case that asserts "Normal shadow should be
  Ink" is comparing against a wrong comment. The two `iconAtEnd` cases that
  only assert a local against a literal are replaced by the rendered test.
  Done when: the class runs alone green, all three names are in its XML report, and the full gate
  is green.

- [ ] 5. Rewrite `OffsetShadowTest`
  `app/src/test/java/com/liftoff/app/ui/theme/OffsetShadowTest.kt`. Tests it writes:
  **`OffsetShadowTest.offsetShadowDefaultsAreFourDp`** — the named default offset (and the default
  shape's radius) read from `OffsetShadow.kt` and compared against each other, plus the default
  shadow colour against the `Ink` token.
  **`OffsetShadowTest.aShadowedBoxKeepsTheSizeItAskedFor`** — render a `Box` of a known size with
  `Modifier.offsetShadow()` and one with `OffsetShadowBox(...)`, tag them, and assert the measured
  layout bounds are the size asked for: the shadow adds no width or height, and the paint does not
  change the node's bounds. The caller's `modifier` (including `.height(...)`) lands on
  `OffsetShadowBox`'s outer Box, so the tagged node is the size that was asked for; the private
  `Modifier.offset(Dp)` at line 83 is a no-op, so nothing in this file may assert that the content
  shifts.
  Both existing cases (`shadowIsSolidAndOffsetFourDp`, `shadowTakesNoLayoutSpace`) are replaced;
  the file's private `assertTrue` helper goes with them.
  Done when: the class runs alone green, both names are in its XML report, and the full gate is
  green.

- [ ] 6. Delete `MainActivityTest`, and make `PreviewsTest`'s failure real
  Delete `app/src/test/java/com/liftoff/app/MainActivityTest.kt` (its 2 cases) — `LiftoffShellTest`
  already launches `MainActivity` through `createAndroidComposeRule<MainActivity>()`. Note that
  `verify.sh` no longer checks for its absence (the review dropped that check), but the plan's
  Decision deletes it, so delete it. In `app/src/test/java/com/liftoff/app/ui/theme/PreviewsTest.kt`,
  replace the Kotlin `assert(...)` calls with `check(...)` so the missing-preview case actually
  throws; the 9 `@Preview` functions in `ui/theme` are unchanged. `FontLicenseTest` is left alone.
  Done when: the two files are the only changes, `LiftoffShellTest` and `PreviewsTest` both pass in
  the full suite, and the commit exists.

- [ ] 7. Run the gate and sweep for what is left
  Run `bash .rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/verify.sh` once and read its
  report: every line must be `ok` and the verdict `done`. If a named case is reported as not
  running, fix the test that names it (tasks 2–5), not the script. Then sweep the four rewritten
  classes for any remaining assertion that cannot fail — a literal against a copy of itself, a
  local against a literal, a token against itself — and fix what the sweep finds.
  Done when: `verify.sh` prints `VERDICT: done` once on the tree as it stands, and the task has
  its own commit — if the sweep finds nothing, commit `.rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/findings.md`
  saying what was checked and what was found.
