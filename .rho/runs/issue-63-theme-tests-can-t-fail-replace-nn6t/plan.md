# Plan

## Approach

Every rewritten assertion reads something from production. Two mechanisms, chosen by what the claim is:

- **A number the test can pin.** Each dimension the tests talk about becomes a named constant in its own theme file, and the composable reads that constant instead of the literal. The test asserts the constant against the value the design intends. The production diff is a literal becoming a name; nothing drawn changes.
- **A layout the test can measure.** Where the claim is about how something is placed or how big it renders, the test sets the composable as content with `createComposeRule()` and passes `Modifier.testTag(...)` through the composable's own `modifier` parameter, then asserts on that node's measured size or on a label's position. No test hooks go into production.

Claims that only exist inside a draw — a `drawBehind` stroke, a `Canvas` rect, a `background` fill — have no node in the Compose semantics tree, so they cannot be measured: a circle's stroke width, a band's fill, the connector line, the underline. Those are pinned as constants, and the plan says so plainly rather than pretending a test can see the drawing. Assertions whose claim cannot be expressed at all are deleted.

This fits the codebase because the theme package already has the first half of the pattern: `PatternTrack.kt` exposes `internal fun chipSizeDp(state)` and `PatternTrackTest` asserts it against 44 and 52. The other three files have not done it yet. Robolectric and the Compose test rule are already test dependencies (`app/build.gradle.kts`), and 11 committed test files already use `@RunWith(AndroidJUnit4::class)` + `createComposeRule()`, so the second half needs no new setup.

## Files

Production — a literal becomes a named constant that the composable itself reads; no value changes. What each file holds today is in `map.md`.

- `app/src/main/java/com/liftoff/app/ui/theme/Stripes.kt` — name the tri band height (6 dp) and the duo band height (5 dp).
- `app/src/main/java/com/liftoff/app/ui/theme/PatternTrack.kt` — name the track height (52 dp), the three chip stroke widths (3 dp current, 2 dp upcoming, 2 dp scrubbed), the connector line's width (2 dp) and inset (22 dp), the check-mark size (20 dp). `chipSizeDp` stays the source of the chip sizes.
- `app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt` — name the primary height (72 dp) and ink height (64 dp), the border width (2 dp), the icon size (26 dp) and icon–label gap (12 dp), the shadow offset (4 dp), the underline stroke (1 dp) and its 4 dp below the baseline, the text button's 44 dp minimum touch height.
- `app/src/main/java/com/liftoff/app/ui/theme/OffsetShadow.kt` — name the default offset (4 dp) and default corner radius (4 dp).

Tests, in `app/src/test/java/com/liftoff/app/ui/theme/` unless stated:

- `StripesTest.kt` — keep the color-order assertions; the band heights become the constants pinned to 6 and 5, plus a rendered `TriStripe`/`DuoStripe` measured at 18 dp and 10 dp total, which is what shows the composables apply them.
- `PatternTrackTest.kt` — keep `chipSizeDp` and the color assertions; drop the string-length and `Color.White`-vs-`Color.White` assertions; the stroke, connector width and connector length assertions read the new constants; "one chip per letter" becomes a rendered track whose two letters both appear, and the track is measured 52 dp tall in the same render.
- `ButtonsTest.kt` — keep the color/brightness tests; the two `iconAtEnd` assertions become one rendered test that flips `iconAtEnd` and asserts the label shifts by the icon size plus the gap (the production `Icon` has `contentDescription = null`, so the label's position is the only handle the tree gives); the heights, border, icon size, gap, shadow offset and underline read the constants; the local-vs-literal underline test goes.
- `OffsetShadowTest.kt` — drop `modifier != null` (the type is non-nullable) and the offset/corner locals-vs-literals test; assert the two default constants, and add the claim the file was reaching for: a tagged box carrying `offsetShadow` keeps the size it asked for, so the shadow takes no layout space.
- `PreviewsTest.kt` — `assert(found)` becomes `check(found)`; the `assert` inside the branch that already matched the same condition is deleted; the scanning logic stays.
- `FontLicenseTest.kt` — its four `assert()` calls become `check()`; nothing else changes.
- `app/src/test/java/com/liftoff/app/MainActivityTest.kt` — deleted.

## Order

1. **Constants in the four production files.** Name the dimensions; make the composables read them. The theme tests are untouched in this step, so the suite stays green and the diff is a pure refactor checkable against the values in `map.md`.
2. **`StripesTest` and `PatternTrackTest`.** Constants pinned, vacuous assertions gone, first rendered measurements (stripe totals, track height, the two letters). This is also the probe: if a tagged node cannot be measured the way `map.md` expects, it is known here, before the other rendered claims are written on the same assumption.
3. **`ButtonsTest`.** Constants pinned, then the `iconAtEnd` render. If the label-shift measurement does not hold up, the fallback is to delete the icon-order claim, not to keep a test that measures nothing.
4. **`OffsetShadowTest`.** Constants pinned, null-check and locals deleted, the no-layout-space render added.
5. **`MainActivityTest` deleted; `assert()` → `check()` in `PreviewsTest` and `FontLicenseTest`.**
6. **Full gate.**

After each of 1–5, `:app:testDebugUnitTest --tests "com.liftoff.app.ui.theme.*"` passes; the full gate runs at the end.

## Testing

The behavior change is that the theme's dimensions become named values the composables read, so the tests listed in Files *are* the tests for it; no new production behavior is added.

- Stripes draw three 6 dp bands and two 5 dp bands — constant-pinned unit test, plus a rendered total height per composable.
- The stripe colors are the design tokens in the design's order — unit test, already real, kept.
- A pattern track sizes its chips by state (52 dp current, 44 dp the rest) — unit test against `chipSizeDp`, already real, kept.
- A pattern track draws one chip per letter, and renders 52 dp tall — rendered Compose test: both letters appear as text nodes, and the tagged track measures 52 dp.
- The track's chip rings and its connector line have the widths the design gives — constant-pinned unit test; they are `drawBehind` strokes, which have no node in the tree, so nothing stronger is claimed.
- PrimaryButton is 72 dp with a 2 dp ink border, a 26 dp icon and a 12 dp gap; InkButton is 64 dp; UnderlinedTextButton is at least 44 dp tall with a 1 dp underline 4 dp under the baseline — constant-pinned unit test.
- PrimaryButton's icon leads its label by default and trails it when `iconAtEnd` is set — rendered Compose test on the label's position.
- `offsetShadow` defaults to a 4 dp offset and a 4 dp corner, and takes no layout space: a tagged box keeps the size it asked for — constant-pinned unit test plus a rendered size check.
- Every `@Preview` composable in `ui/theme/` is private and there are nine — unit test, with `check()`.
- The two OFL license texts ship and contain what they must — unit test, with `check()`.
- `MainActivity` has no test of its own. Its `onCreate` wiring (the open sweep, which runs on every `STARTED` transition) is exercised only indirectly, by `LiftoffShellTest` launching the activity; that gap is explained in Decisions.

## Risks

- **New Compose tests add flake surface** to a suite that already flakes in Compose rendering (`LiftoffShellTest`'s expired `waitFor`, the `CalledFromWrongThreadException` family in `InFlightScreenTest`/`LaunchpadPendingTitleTest`). Keep the rendered claims to the four in Decisions, measure straight after `setContent`, use no `waitFor`; if one proves flaky, fall back to the constant-pinned assertion it was meant to complement.
- **A rendered measurement may not work as sketched**: a `testTag` on a plain `Column`/`Box` has to produce a semantics node, and the size assertion has to convert dp at the Robolectric density. Order step 2 is the probe; if it fails, the claim stays constant-pinned and the findings say so.
- **The `iconAtEnd` test is the most fragile** — the label's shift depends on the icon's forced 26 dp size and the 12 dp gap. Its fallback is deletion, not a weaker assertion.
- **Robolectric's default graphics mode fakes text metrics.** None of the claims here measure text, so no `@GraphicsMode(NATIVE)` is needed; if a later claim needs a text height or a line count, that class has to opt in.
- **Two existing tests pin counts**: `ThemePackageTest` fixes `ui/theme/` at 11 files and `PreviewsTest` at nine previews. Constants go in the existing files, and no `@Preview` is added or removed.
- **`OffsetShadow.kt:83`** defines a private no-op `Modifier.offset(Dp)` that shadows Compose's real `offset`. The design wants the content un-shifted, so today's behavior matches the design and the plan leaves it alone; the new test asserts only that the shadow takes no layout space and must not be written as evidence that the content moves. Anyone who later "fixes" that stub should re-read `design/README.md` line 78 first.
- **`assert()` → `check()` makes five currently-silent checks unconditional** (one in `PreviewsTest`, four in `FontLicenseTest`). Whether the runner passes `-ea` was not settled; the conditions hold today (both license files contain the required text, and there are exactly nine previews), so the conversion should stay green. If it turns out red, that is a real finding about the repo, not damage from this change.
- **Deleting `MainActivityTest` leaves `MainActivity` with no test of its own.** See Decisions.
- **Pinning values means a deliberate restyle now fails the gate.** That is the point of the issue, but a later run that legitimately changes a dimension must update the pinned assertion in the same commit.
- **Gate mechanics**: the committed `gradlew.sh` is Windows-only, so the gate needs this machine's toolchain paths; `verify.sh` belongs to another step and is not touched here.

## Decisions

**Do the composables switch to the new constants, or do the constants exist only for the tests?** The plan moves each literal out of `Stripes.kt`, `PatternTrack.kt`, `Buttons.kt` and `OffsetShadow.kt` into a named constant that the composable itself reads, and changes no value. The alternative — constants declared in the test file and read only by the tests — is not what the brief asks for, and it would recreate the problem: the test would keep passing while the drawing changed. A developer reading the theme file gets the same numbers they had, now named, and the test is pinned to what is actually drawn.

**Which claims get a rendered Compose test, and which are pinned as numbers?** The brief offers both ("assert against those, or render the composables with `createComposeRule`"). The plan renders four claims, and only four: the stripe totals, the pattern track's height and its one-chip-per-letter, the primary button's icon order, and the offset shadow taking no layout space. Everything else — chip ring widths, the connector line, the underline's stroke and its 4 dp below the baseline, the button heights and borders — is pinned as a constant, because those are strokes, fills and layout constraints with no node in the Compose tree, and a render could not show them anyway. Rendering more would add slow Compose tests to a suite that already has a known Compose-rendering flake for no added power. Users would expect the theme tests to stay fast and deterministic; the issue is about assertions that cannot fail, not about coverage by rendering.

**Do the rewritten tests pin the design values, or only the relationships between them?** Each rewritten test asserts a value against what the design intends — a constant against 6 dp, 52 dp, 1 dp, 4 dp — and the rendered tests assert a measured size or position against the same design value. The alternative — asserting only that the values relate to each other — would let a deliberate restyle stay green, but it would also let a slip go unnoticed, which is the thing this issue is about. Someone who changes a stripe band height and sees the gate fail gets a loud, reviewable signal rather than a silent one; that is what "the tests can now fail" means in practice.

**How much gets deleted?** An assertion whose claim cannot be expressed at all — `modifier != null` on a non-nullable type, a local asserted against the literal it was just assigned — is deleted, and a test file is deleted only when no meaningful claim survives in it. The alternative is to keep every file and rename its vacuous assertions, which leaves the suite reading as though the theme were covered. A test that passes whatever the production code does is worse than no test.

**MainActivityTest: rewrite or delete?** The plan deletes it. Reading `MainActivity` turned up one behavior no other test asserts directly — the open sweep it runs on every `STARTED` transition (`onAppOpen` plus `AppOpenSweep`, `MainActivity.kt:29-44`) — but testing that means launching the activity and observing a `MissionManager` and a WorkManager enqueue that `MainActivity` gets off the application's own container, with no way to inject a fake: an integration test of the container, not of the activity. `MainActivity` is already launched by `LiftoffShellTest` through `createAndroidComposeRule<MainActivity>()`, so a failure in its `onCreate` already fails the gate. The alternative the brief also allows — rewrite it as a Compose test that launches the activity and asserts the root screen appears — duplicates `LiftoffShellTest` at the highest cost and flake risk in the suite. Users would expect MainActivity's test to say something true about MainActivity; nothing that is true only there is cheap enough to be worth adding, so its absence is honest, and the sweep-on-open gap is stated in Testing rather than papered over.

**`FontLicenseTest` uses the same Kotlin `assert()` the brief flags in `PreviewsTest`, yet the brief lists it among the files that are fine. Fix it too?** The plan replaces its four `assert()` calls with `check()`. In the way the brief means "fine", the file is fine: its assertions compare real file contents to real expectations, unlike the theme tests. But the brief's own fix line is about the mechanism, and leaving it would mean this run leaves a silent check in the very directory it is fixing. Both license files satisfy all four conditions today, so the change cannot turn the gate red. If the owner wants the brief's list followed exactly, dropping this file costs one bullet of the diff.
