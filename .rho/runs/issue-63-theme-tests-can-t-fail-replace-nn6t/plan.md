# Plan

## Approach

Every rewritten assertion reads something from production. There are two ways to do that, and the choice depends on what the claim is:

- **A number the test can pin.** Each dimension the tests talk about becomes a named `internal` constant in its own theme file. The composable reads that constant instead of the literal. The test asserts the constant against the value the design intends. In production the only change is a literal becoming a name; nothing drawn changes.
- **A layout the test can measure.** Where the claim is about how something is placed or how big it renders, the test sets the composable as content with `createComposeRule()`. It passes `Modifier.testTag(...)` through the composable's own `modifier` parameter, then asserts on that node's measured size or on a label's position. No test hooks go into production.

Some claims exist only inside a draw: a `drawBehind` stroke, a `Canvas` rect, a `background` fill. These have no node in the Compose semantics tree, so they cannot be measured. Examples are a circle's stroke width, a band's fill or order, the connector line and the underline. Those are pinned as constants where they are numbers, and the plan says so plainly rather than pretending a test can see the drawing. Assertions whose claim cannot be expressed at all are deleted.

This fits the codebase because the theme package already has the first half of the pattern. `PatternTrack.kt` exposes `internal fun chipSizeDp(state)`, and `PatternTrackTest` could assert it against 44 and 52, though today it doesn't. Robolectric and the Compose test rule are already test dependencies (`app/build.gradle.kts`). Twelve committed test files already use `@RunWith(AndroidJUnit4::class)` with `createComposeRule()` or `createAndroidComposeRule()`, so the second half needs no new setup.

## Files

Production: a literal becomes a named `internal` constant that the composable itself reads, and no value changes. What each file holds today is in `map.md`.

- `app/src/main/java/com/liftoff/app/ui/theme/Stripes.kt`: name the tri band height (6 dp) and the duo band height (5 dp).
- `app/src/main/java/com/liftoff/app/ui/theme/PatternTrack.kt`: name these values:
  - the track height (52 dp)
  - the three chip stroke widths (3 dp current, 2 dp upcoming, 2 dp scrubbed)
  - the connector line's width (2 dp) and inset (22 dp)
  - the check-mark size (20 dp)

  `chipSizeDp` stays the source of the chip sizes.
- `app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt`: name these values:
  - the primary height (72 dp) and ink height (64 dp), which are the defaults of the `height` parameters
  - the border width (2 dp)
  - the icon size (26 dp) and the icon–label gap (12 dp)
  - the underline stroke (1 dp) and its 4 dp gap below the baseline
  - the text button's 44 dp minimum touch height

  The two buttons' 4 dp shadow offset reads the `OffsetShadow.kt` default constant rather than a second name for the same number.
- `app/src/main/java/com/liftoff/app/ui/theme/OffsetShadow.kt`: name the default offset (4 dp) and the default corner radius (4 dp). Both `offsetShadow` and `OffsetShadowBox` read the offset constant.

Tests, in `app/src/test/java/com/liftoff/app/ui/theme/` unless stated:

- `StripesTest.kt`:
  - The band heights become the constants, pinned to 6 and 5.
  - A rendered `TriStripe` and `DuoStripe` are measured at 18 dp and 10 dp total, which shows that the composables apply the constants.
  - The `Mustard.value`-vs-`Mustard.value` and `Teal.value`-vs-`Teal.value` assertions are deleted, and so is the `Red` hex check. `ColorTokensTest` already pins the tokens, and band order is a fill with no node.
- `PatternTrackTest.kt`:
  - Kept: the color assertions that compare a hex literal with a token.
  - Added: `chipSizeDp` assertions (52 for current, 44 for the rest).
  - Dropped: the string-length check, the 44f-vs-44f and 52f-vs-52f checks, and the `Color.White`-vs-`Color.White` check.
  - The stroke, connector-width and connector-inset assertions read the new constants.
  - "One chip per letter" becomes a rendered track of two non-landed chips with distinct letters (for example "R" current and "L" upcoming; a landed chip shows a check icon, not its letter). Both letters must appear as text nodes, and the same render measures the track at 52 dp tall.
  - `chipTextUsesBigShouldersFontFamily` is deleted. Every assertion in it reads only locals and literals, and the font lives in a private composable with no semantics handle.
- `ButtonsTest.kt`:
  - The two `iconAtEnd` assertions become one rendered test. It flips `iconAtEnd` and asserts that the label shifts by the icon size plus the gap. The production `Icon` has `contentDescription = null`, so the label's position is the only handle the tree gives. The test uses a real icon from `LiftoffIcons`.
  - The heights, border, icon size, gap and underline assertions read the constants.
  - The local-vs-literal underline test goes.
  - The color tests keep their hex-vs-token checks. Their brightness comparison and their "colors are distinct" checks now compute from `Red`, `RedPressed` and `Ink` instead of from the hex literals, which today compare literal with literal.
- `OffsetShadowTest.kt`:
  - Drop `modifier != null` (the type is non-nullable) and the locals-vs-literals offset and corner checks.
  - Assert the two default constants.
  - Add the claim the file was reaching for: a tagged box with a fixed size and `offsetShadow` keeps the size it asked for, so the shadow takes no layout space.
- `PreviewsTest.kt`:
  - `assert(found)` becomes `check(found)`.
  - The `assert` inside the branch that already matched the same condition is deleted.
  - The scanning logic stays.
- `app/src/test/java/com/liftoff/app/MainActivityTest.kt`: deleted.

`FontLicenseTest.kt` is not touched (see Decisions).

## Order

1. **Constants in the four production files.** Name the dimensions and make the composables read them. The tests are untouched in this step, so the suite stays green and the diff is a pure refactor you can check against the values in `map.md`.
2. **`StripesTest` and `PatternTrackTest`.** Pin the constants, remove the vacuous assertions and the font test, and add the first rendered measurements (stripe totals, track height, the two letters). This step is also the probe: if a tagged node cannot be measured the way `map.md` expects, that shows up here, before the other rendered claims are written on the same assumption.
3. **`ButtonsTest`.** Pin the constants, make the color checks read the tokens, then add the `iconAtEnd` render. If the label-shift measurement does not hold up, the fallback is to delete the icon-order claim, not to keep a test that measures nothing.
4. **`OffsetShadowTest`.** Pin the constants, delete the null check and the locals, and add the no-layout-space render.
5. **Delete `MainActivityTest`; change `assert()` to `check()` in `PreviewsTest`.**
6. **Full gate.**

After each of steps 1–5, `:app:testDebugUnitTest --tests "com.liftoff.app.ui.theme.*"` passes. The full gate runs at the end.

## Testing

The behavior change is that the theme's dimensions become named values the composables read. The tests listed in Files are therefore the tests for it; no new production behavior is added.

- **Stripes.** TriStripe draws three 6 dp bands and DuoStripe two 5 dp bands: a constant-pinned unit test, plus a rendered total height for each composable.
- **Chip sizes.** A pattern track sizes its chips by state (52 dp for current, 44 dp for the rest): a unit test against `chipSizeDp`.
- **Chip letters and track height.** A pattern track draws one chip per letter and renders 52 dp tall: a rendered Compose test in which both letters appear as text nodes and the tagged track measures 52 dp.
- **Strokes.** The chip rings and the connector line have the widths the design gives: a constant-pinned unit test. They are canvas strokes with no node in the tree, so nothing stronger is claimed.
- **Button dimensions.** PrimaryButton is 72 dp with a 2 dp ink border, a 26 dp icon and a 12 dp gap. InkButton is 64 dp. UnderlinedTextButton is at least 44 dp tall, with a 1 dp underline 4 dp under the baseline. All are constant-pinned unit tests.
- **Pressed colors.** The pressed fill is darker than the normal fill and differs from it, computed from the tokens: a unit test.
- **Icon order.** PrimaryButton's icon leads its label by default and trails it when `iconAtEnd` is set: a rendered Compose test on the label's position.
- **Offset shadow.** `offsetShadow` defaults to a 4 dp offset and a 4 dp corner, and takes no layout space (a tagged box keeps the size it asked for): a constant-pinned unit test plus a rendered size check.
- **Previews.** Every `@Preview` composable in `ui/theme/` is private, and there are nine: a unit test using `check()`.
- **MainActivity.** `MainActivity` has no test of its own. Its `onCreate` wiring is exercised only indirectly, when `LiftoffShellTest` launches the activity through `createAndroidComposeRule<MainActivity>()`. Decisions explains this gap.

## Risks

- **New Compose tests add flake surface.** The suite already flakes in Compose rendering: `LiftoffShellTest`'s expired `waitFor`, and the `CalledFromWrongThreadException` family in `InFlightScreenTest` and `LaunchpadPendingTitleTest`. Keep the rendered claims to the four in Decisions, measure straight after `setContent`, and use no `waitFor`. If one proves flaky, fall back to the constant-pinned assertion it was meant to complement.
- **A rendered measurement may not work as sketched.** A `testTag` on a plain `Column` or `Box` has to produce a semantics node, and the size assertion has to convert dp at the Robolectric density. Order step 2 is the probe. If it fails, the claim stays constant-pinned and the findings say so.
- **The `iconAtEnd` test is the most fragile.** The label's shift depends on the icon's forced 26 dp size and the 12 dp gap. The row is centered and its total width is the same either way, so the label's left edge moves by exactly icon plus gap. Its fallback is deletion, not a weaker assertion.
- **Robolectric's default graphics mode fakes text metrics.** The label-shift claim compares the same label's position in two renders, so fake but consistent metrics are enough. No `@GraphicsMode(NATIVE)` is needed.
- **Two existing tests pin counts.** `ThemePackageTest` lists the 11 theme files, and `PreviewsTest` counts nine previews. The constants go in the existing files, and no `@Preview` is added or removed.
- **`OffsetShadow.kt:83` defines a private no-op `Modifier.offset(Dp)`** that shadows Compose's real `offset`. The design wants the content un-shifted, so today's behavior matches the design, and the plan leaves the stub alone. The new test asserts only that the shadow takes no layout space; it must not be written as evidence that the content moves. Anyone who later "fixes" that stub should re-read `design/README.md` line 78 first.
- **`assert()` → `check()` in `PreviewsTest`.** Gradle's `Test` task enables assertions by default, so these `assert()` calls do run under the gate today. The change removes the dependence on a JVM flag rather than waking up a silent check. The conditions hold today, so the change should stay green.
- **Deleting `MainActivityTest` leaves `MainActivity` with no test of its own.** See Decisions.
- **Pinning values means a deliberate restyle now fails the gate.** That is the point of the issue. A later run that legitimately changes a dimension must update the pinned assertion in the same commit.
- **Gate mechanics.** The committed `gradlew.sh` defaults to Windows toolchain paths and hard-codes the Gradle binary's path. The gate may need this machine's paths. `verify.sh` belongs to another step and is not touched here.

## Decisions

**`FontLicenseTest` uses the same Kotlin `assert()` the brief flags in `PreviewsTest`, yet the brief lists it among the files that are fine. Fix it too?** The plan replaces its four `assert()` calls with `check()`. In the sense the brief means "fine", the file is fine: its assertions compare real file contents to real expectations, unlike the theme tests. But the brief's fix line is about the mechanism, and leaving the file would mean this run leaves a silent check in the very directory it is fixing. Both license files satisfy all four conditions today, so the change cannot turn the gate red. If the owner wants the brief's list followed exactly, dropping this file removes one bullet from the diff.
Changed by review: the plan chose to convert `FontLicenseTest`'s `assert()` calls; the review chose to leave the file untouched. The brief names it explicitly as fine as it is, and its checks are not silent: Gradle's `Test` task enables assertions by default, so they already fail the gate when false.

**Added by review: what happens to the stripe color-order assertions?** The brief cites `StripesTest` lines 21–22 and 40 (`Mustard.value` vs `Mustard.value`) as tautological, so they cannot be kept. The plan deletes all of the color assertions in `StripesTest`, including the `Red` hex check. `ColorTokensTest` already pins every token's value. Band order is a `background` fill on untagged inner boxes, so the only way to see it would be a pixel capture under native graphics. That is a new, slower mechanism with more flake risk, for a claim the source shows at a glance. The test file keeps its heights, which are real.

**Added by review: `PatternTrackTest.chipTextUsesBigShouldersFontFamily`.** This test is vacuous from end to end: local sets checked against themselves, and `assertEquals(900, 900)`. The brief cites its lines 67–68. The chip letters' font family and weight sit inside a private composable, and the semantics tree has no handle on them. The plan deletes the test rather than extracting a style constant only to pin it, which the brief does not ask for.

**Do the composables switch to the new constants, or do the constants exist only for the tests?** The plan moves each literal out of `Stripes.kt`, `PatternTrack.kt`, `Buttons.kt` and `OffsetShadow.kt` into a named constant that the composable itself reads, and changes no value. The alternative is constants declared in the test file and read only by the tests. The brief does not ask for that, and it would recreate the problem: the test would keep passing while the drawing changed. A developer reading the theme file sees the same numbers they had, now named, and the test is pinned to what is actually drawn.

**Which claims get a rendered Compose test, and which are pinned as numbers?** The brief offers both ("assert against those, or render the composables with `createComposeRule`"). The plan renders four claims, and only four:
- the stripe totals
- the pattern track's height and its one chip per letter
- the primary button's icon order
- the offset shadow taking no layout space

Everything else is pinned as a constant: chip ring widths, the connector line, the underline's stroke and its 4 dp below the baseline, and the button heights and borders. Those are strokes, fills and layout constraints with no node in the Compose tree, so a render could not show them anyway. Rendering more would add slow Compose tests to a suite that already has a known Compose-rendering flake, for no added power. Users would expect the theme tests to stay fast and deterministic; the issue is about assertions that cannot fail, not about coverage by rendering.

**Do the rewritten tests pin the design values, or only the relationships between them?** Each rewritten test asserts a value against what the design intends: a constant against 6 dp, 52 dp, 1 dp or 4 dp. The rendered tests assert a measured size or position against the same design value. Asserting only that the values relate to each other would let a deliberate restyle stay green, but it would also let a slip go unnoticed, and slips are what this issue is about. Someone who changes a stripe band height and sees the gate fail gets a loud, reviewable signal rather than a silent one. That is what "the tests can now fail" means in practice.

**How much gets deleted?** An assertion whose claim cannot be expressed at all is deleted: `modifier != null` on a non-nullable type, or a local asserted against the literal it was just assigned. A test file is deleted only when no meaningful claim survives in it. The alternative is to keep every file and rename its vacuous assertions, which leaves the suite reading as though the theme were covered. A test that passes whatever the production code does is worse than no test.

**MainActivityTest: rewrite or delete?** The plan deletes it.
- **What could be tested.** Reading `MainActivity` turned up one behavior no other test asserts directly: the open sweep it runs on every `STARTED` transition (`onAppOpen` plus `AppOpenSweep`, `MainActivity.kt:29-44`).
- **Why not test it here.** Testing it means launching the activity and observing a `MissionManager` and a WorkManager enqueue. `MainActivity` gets those from the application's own container, and there is no way to inject a fake, so this would be an integration test of the container, not of the activity.
- **Existing coverage.** `LiftoffShellTest` already launches `MainActivity` through `createAndroidComposeRule<MainActivity>()`, so a failure in its `onCreate` already fails the gate.
- **The alternative.** The brief also allows a rewrite as a Compose test that launches the activity and asserts the root screen appears. That duplicates `LiftoffShellTest` at the highest cost and flake risk in the suite.

Users would expect MainActivity's test to say something true about MainActivity. Nothing true only of MainActivity is cheap enough to test, so having no test is honest, and Testing states the sweep-on-open gap rather than papering over it.
