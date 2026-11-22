# Map — issue 63 (theme tests can't fail)

Run dir: `.rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/`. Branch `rho/issue-63-theme-tests-can-t-fail-replace-nn6t`, HEAD `0ec1f86` ("run: verify.sh rewritten by claude review"), parent chain back to `baa0eb8` (= `origin/main`, 2026-11-05). **There is no commit `1111c8b` in this repository** (`git cat-file -t 1111c8b` fails) — an earlier version of this map cited it and described files that do not exist here. Everything below was re-read from `git show HEAD:<path>` at `0ec1f86`.

## Tool-output hazard (found while re-verifying this map)

The first read of some paths in this worktree returned content that is **not** in the tree: a 94-line `PatternTrackTest.kt` containing `PatternChipState` (no such identifier exists anywhere in the repo), a 124-line `PatternTrack.kt`, a 91-line `OffsetShadow.kt`, a 199-line `Buttons.kt`, and a `grep -c '@Preview'` listing that missed `Icons.kt`. A second read of the same path returned the real content. **How to apply:** before writing a fact into this map or asserting it in a test, confirm it twice — `git show HEAD:<path> | wc -l`, `grep -n`, `awk 'END{print NR}'` — and trust the git blob over a single read. The numbers below are the ones that reproduce.

## Project rules that bind this work

- `DESIGN.md` §13 "Testing and verification": unit tests cover "the theme components (stripes, pattern track, offset shadow, buttons)"; §13.3 "No test may depend on network access, wall-clock time, or a running emulator"; §13.4 gate = `:app:assembleDebug` + `:app:testDebugUnitTest`. §13.2 lists the Compose UI test library as a Phase 1 deliverable.
- `design/README.md` §Design principles line 78: "Offset shadow: a solid ink copy of the shape, **4 dp right and 4 dp down**, no blur." §Component inventory line 144: "Pattern track, stripes, offset shadow, underlined text button, ink button, primary button — all built in `ui/theme/`." The color table (12 tokens) is parsed by `ColorTokensTest`.
- `ARCHITECTURE.md` §10 Testing: "Phase 1 tests cover … the theme components." §11 Do Not: no Compose `ViewModel` in `ui/theme/`; theme components are stateless composables.
- Memory: `liftoff-robolectric-text-metrics-need-native-graphics` — Robolectric's default graphics mode fakes text metrics, so a test that must show a line count or text height needs `@GraphicsMode(GraphicsMode.Mode.NATIVE)` on its own class. No committed test in this repo uses `GraphicsMode` today.
- Memory: `liftoff-shelltest-full-suite-flake` — full-suite gate runs flake in `LiftoffShellTest` (expired `waitFor`) and in a `CalledFromWrongThreadException` family that has hit `InFlightScreenTest` and `LaunchpadPendingTitleTest`.
- Memory: `liftoff-gradle-gate-on-linux` — the committed `gradlew.sh` is Windows-only, so the gate needs the local `~/android-toolchain` paths.
- Memory: `liftoff-scratch-files-under-app-build` — probe classes and gate logs go under gitignored `app/build/scratch-taskN/`, driven by an init script that adds that dir to the unit-test source set; never under `app/src/test`.
- Memory: `liftoff-work-task-stops-when-its-gate-passes-once` — run the gate once per task; a third identical green run is the signal to commit and stop.

## Production: `app/src/main/java/com/liftoff/app/ui/theme/` (11 files — pinned by ThemePackageTest)

- `Stripes.kt` (56 lines) — `TriStripe(modifier = Modifier, red = Red, mustard = Mustard, teal = Teal)` (11-16): `Column(Modifier.fillMaxWidth())` of three `Box(Modifier.height(6.dp).fillMaxWidth().background(color))` at **24, 25, 26**. `DuoStripe(modifier, mustard = Mustard, red = Red)` (29-33): two `Box(Modifier.height(5.dp)…)` at **37, 38**. Note the chain is `height().fillMaxWidth().background()` — height first. Two `@Preview` (45-56).
- `PatternTrack.kt` (160 lines) — `enum class ChipState { Landed, Current, Upcoming, Scrubbed }` (20) and `data class PatternChip(val letter: String, val state: ChipState)` (21). **`internal fun chipSizeDp(state: ChipState): Int` (24-28): Landed 44, Current 52, Upcoming 44, Scrubbed 44** — the precedent for a test-visible dimension in this package, and it is *not* called by any test today. `PatternTrack(chips: List<PatternChip>, modifier: Modifier = Modifier)` (32-35) — no colour parameters. Track `Box(modifier.fillMaxWidth().height(52.dp))` (37-40). Connector: `Canvas(Modifier.fillMaxWidth().matchParentSize())` **only when `chips.size >= 2`** (44-46), inset `22.dp` at each end (48-49), `drawLine(color = Ink, strokeWidth = 2.dp.toPx())` (50-54). `Row(… Arrangement.SpaceBetween)` (60-66) draws `PatternChipView(chip)` per chip (67-69). `PatternChipView` (72-143): `Box(Modifier.size(chipSizeDp(chip.state).dp))`; **Landed** (82-91) `Canvas.drawCircle(Ink)` + `Icon(LiftoffIcons.check(), contentDescription = null, Modifier.size(20.dp), tint = Cream)`; **Current** (93-108) `drawCircle(Red)` + `drawCircle(Ink, Stroke(3.dp))` + `Text(letter, color = White, BigShoulders, W900, 26.sp)`; **Upcoming** (110-125) `drawCircle(Cream)` + `Stroke(2.dp)` + `Text(Ink, W800, 22.sp)`; **Scrubbed** (127-142) `drawCircle(Sand)` + `Stroke(2.dp)` + `Text(Muted, W800, 22.sp)`. Strokes are `density.run { N.dp.toPx() }` — px, not dp. One `@Preview` (146-160). No `Spacer`, no `CardShape`, no `InkRuledListDivider` here; `Scrubbed`'s fill is `Sand`, its letter colour is `Muted`.
- `Buttons.kt` (229 lines) — `PrimaryButton(text, onClick, modifier, icon: Painter? = null, iconAtEnd: Boolean = false, height: Dp = 72.dp, labelStyle = LiftoffType.launchLabel())` (28-36): `OffsetShadowBox(color = Ink, offset = 4.dp, modifier = modifier.fillMaxWidth().height(height))` (40-46); inner `Box(Modifier.fillMaxSize().background(if (isPressed) RedPressed else Red, ButtonShape).border(BorderStroke(2.dp, Ink), ButtonShape).clickable(interactionSource, indication = null, Role.Button))` (47-58); centred `Row` with `Icon(…, Modifier.size(26.dp))` + `Spacer(Modifier.width(12.dp))` + `Text(text.uppercase(), … Cream)` — icon-first at 60-76, icon-last at 77-87 (icon sizes 68/82, spacers 71/78). `InkButton(text, onClick, modifier, icon, iconAtEnd = false, height: Dp = 64.dp)` (92-99): shadow colour is **`Red` at rest, `RedPressed` while pressed** (104), `offset = 4.dp` (105), fill `Ink` + `border(2.dp, Ink)` (113-114), icon 26.dp (131/145), spacer 12.dp (134/141). `UnderlinedTextButton(text, onClick, modifier)` (155-159): `Box(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp).clickable(…))` (163-171), `Text` with `drawBehind` underline at `baselinePx + 4.dp.toPx()` and `strokeWidth = 1.dp.toPx()` (176-190), `onTextLayout` sets `baselinePx = getLineBaseline(lineCount - 1)` (191-196). Three `@Preview` (201-229), each wrapped in `if (LocalInspectionMode.current)`. All three buttons use `Modifier.clickable(…, role = Role.Button)`, which **merges descendants** — see the test-notes section.
- `OffsetShadow.kt` (83 lines) — `fun Modifier.offsetShadow(color: Color = Ink, shape: Shape = RoundedCornerShape(4.dp), offset: Dp = 4.dp)` (25-29): `drawBehind` takes `shape.createOutline(size, layoutDirection, this)` and fills it translated by `(offset.toPx(), offset.toPx())` for Rectangle/Rounded/Generic outlines (30-44); private `RoundRect.shifted(Offset)` (46-59). `OffsetShadowBox(color = Ink, offset = Dp = 4.dp, modifier = Modifier, content)` (63-81): outer `Box(modifier.offsetShadow(color, offset), contentAlignment = TopStart)` (68-71) — so **the caller's `modifier`, including `.height(...)`, lands on the outer Box and that node's layout size is the size asked for**; then `Canvas(Modifier.matchParentSize()) { drawRect(color) }` (73-74) — an *unshifted* solid fill of the whole box; then `Box(Modifier.offset(offset).align(Alignment.Center), content)` (76-79). **Line 83: `private fun Modifier.offset(offset: Dp): Modifier = this`** — a no-op that is the only `offset` in scope in this file, so the content is not actually shifted; a `fillMaxSize()` content covers the shadow. Design wants the content un-shifted (CSS `box-shadow: 4px 4px 0`), so the no-op matches the design today; a test must not read it as proof the content moves.
- `Shape.kt` (21 lines) — `LiftoffShapes(extraSmall = 4dp, small = 4dp, medium = 8dp, large = 8dp)`; `CardShape = Shape(CornerBasedShape(0,0,8,8,0,0))`; **`ButtonShape = CornerBasedShape(16,16,16,16,0,0)`** — `Buttons.kt` uses `ButtonShape`, never `CardShape`.
- `Color.kt` — 12 tokens as `val`/`@Composable` funs; `Red = Color(0xFFC23F14)`, `RedPressed = Color(0xFF8F2E0E)`, `Ink = Color(0xFF1D1B19)`, `InkRed = Color(0xFF34150F)`, `Cream = Color(0xFFF2EADB)`, `Teal = Color(0xFF145060)`, `TealLight = Color(0xFF6FA8A0)`, `Muted = Color(0xFF8A857C)`, `Sand`, `Mustard`, `White`, `Paper`.
- `Icons.kt` (111 lines) — `object LiftoffIcons` with `check(): Painter` (a `BitmapPainter`, 24×24) used by `PatternTrack`, plus `checkIcon/equipment/sortie/coach/mission/settings(): ImageVector`. One `@Preview`.
- `Type.kt` (144 lines) — `LiftoffType` extension data class with `eyebrow/screenTitle/sectionTitle/cardTitle/bodySmall/buttonLabel/wordmark/launchLabel/textButton` composable accessors; `LiftoffTypography` is the `MaterialTheme.typography` slot. No `@Preview`.
- `Headings.kt` (74 lines) — `Eyebrow`, `DisplayTitle`, `TitleBlock`, `Wordmark`; `Spacer(4.dp)` between eyebrow and title. Not named by the brief. One `@Preview`.
- `InkRuledListRow.kt` (111 lines) — `InkRuledListRow`, `InkRuledListScope`, `InkRuledListRowColors` (Default/Selected/Scrubbed). Not named by the brief. One `@Preview`.
- `LiftoffTheme.kt` (4 lines) — `LiftoffTheme(content)` = `MaterialTheme(colorScheme = liftoffColorScheme(), typography = LiftoffTypography, content)`. No `@Preview`.
- `@Preview` count in `ui/theme/` is **9** (Buttons 3, Stripes 2, Headings 1, Icons 1, InkRuledListRow 1, PatternTrack 1) — which is what `PreviewsTest` expects.

## Tests: `app/src/test/java/com/liftoff/app/ui/theme/` (13 files) + `app/src/test/java/com/liftoff/app/MainActivityTest.kt`

Case counts below are from the gate reports the verify step left in `app/build/test-results/testDebugUnitTest/TEST-*.xml` (the untouched tree): StripesTest 2, PatternTrackTest 3, ButtonsTest 6, OffsetShadowTest 2, MainActivityTest 2, PreviewsTest 1, TypographyTest 5, ColorSchemeTest / ColorTokensTest / FontLicenseTest / FontResourcesTest / IconsTest / ShapesTest / ThemePackageTest 1 each. None of the four theme test classes has `@RunWith` today — they are plain JUnit, so the render tests add `@RunWith(AndroidJUnit4::class)` + `@get:Rule createComposeRule()`.

Vacuous today (the brief's list):
- `StripesTest.kt` (45 lines, 2 cases) — `triStripeIsRedMustardTealSixDpBands` (9-27), `duoStripeIsMustardRedFiveDpBands` (29-44). Only real assertions: `Color(0xFFC23F14).value == Red.value` at **20** and **41**. Everything else is a local against its own literal: 12, 16 (`totalHeight = sixDpValue * 3`), 21, 22 (`Mustard.value == Mustard.value`, `Teal.value == Teal.value`), 25, 32, 36, 40, 43. No case named `stripeBandHeightsAreConsistent`.
- `PatternTrackTest.kt` (75 lines, 3 cases) — `drawsOneChipPerLetterInItsStateStyle` (10-36): vacuous at 13 (`"RLRL".length == 4`), 16-18 (`44f == 44f`, `52f == 52f`), 33 (`Color.White.value == Color.White.value`); real at 25-27, 30, 34-35 (literal hexes vs `Ink`/`Red`/`Cream`/`Ink`). `strokeWidthsAreDensityIndependent` (38-54): all vacuous, no production call. `chipTextUsesBigShouldersFontFamily` (56-74): real at 63-65 (literal hexes vs `Ink`/`Red`/`Cream`) and 71 (`BigShoulders.name == "BigShoulders"`). `chipSizeDp` is **not** called by any test; `PatternChipState` does not exist in the repo.
- `ButtonsTest.kt` (102 lines, 6 cases) — `primaryButtonIconLeadsLabelByDefault` (9-16) and `primaryButtonIconTrailsLabelWithIconAtEnd` (19-27): a local boolean compared with a literal, no composable called. `primaryButtonFillsRedPressedWhilePressedWithNoRipple` (30-48): real (literals vs `Red`/`RedPressed`, plus brightness via private `computeBrightness`, 96-101). `inkButtonShadowTurnsRedPressedWhilePressed` (51-61): real token comparisons, but its "Normal shadow should be Ink" comment is wrong — `InkButton`'s rest shadow is `Red`. `textButtonTurnsRedWhilePressed` (64-74): real. `textButtonUnderlineIsOneDpFourDpBelowBaseline` (77-94): vacuous locals vs their own literals.
- `OffsetShadowTest.kt` (39 lines, 2 cases) — `shadowIsSolidAndOffsetFourDp` (9-24): real only at 16 (`Color(0xFF1D1B19).value == Ink.value`); 12-13 and 19-21 are locals vs literals. `shadowTakesNoLayoutSpace` (26-34): vacuous, including `modifier != null` on a non-nullable `Modifier`. Its `assertTrue` is a private helper at 37-39 (no `org.junit.Assert.assertTrue` import).
- `MainActivityTest.kt` (64 lines, 2 cases) — `drawsEdgeToEdgeWithDarkSystemBarIcons`, `showsStripeThenWordmarkAtTopLeftOnCream`: never touches `MainActivity`; builds a local `mutableStateOf` list and asserts its own size/contents.
- `PreviewsTest.kt` (42 lines, 1 case) — a real source-scanning test: walks `app/src/main/java/com/liftoff/app/ui/theme` (repoRoot found by walking up from cwd for `settings.gradle.kts`), counts `@Preview` occurrences, expects 9. `assert(...)` at **26** (tautological by construction: the branch already matched the same `startsWith`) and `assert(found)` at **32** (a real check that only bites under `-ea`).

Fine as they are (brief's list, confirmed): `ColorTokensTest` (parses the 12-token table in `design/README.md` and compares to the production tokens), `FontResourcesTest` (6 TTFs, magic bytes, not HTML), `ColorSchemeTest`, `TypographyTest` (5 cases), `IconsTest`, `ShapesTest` (compares production shapes to expected corner radii).

Not named by the brief either way: `ThemePackageTest.kt` (52 lines, 1 case) — asserts the 11 expected file names in `ui/theme/`, that no file imports `androidx.compose.material3` except `Type.kt`, and pins the file count to 11. `FontLicenseTest.kt` (36 lines, 1 case) — real checks, but uses Kotlin `assert()` four times; the license files satisfy them today (`app/src/main/assets/licenses/OFL-BigShouldersDisplay.txt`, `OFL-WorkSans.txt`), so it stays green untouched.

## Test-writing notes for the render cases

- Compose test API available (compose-bom 2024.12.01 → compose-ui/ui-test 1.7.6): `Modifier.testTag` passed in through each composable's `modifier` parameter (all four theme composables take one), `onNodeWithTag(...)`, and in `androidx.compose.ui.test.BoundsAssertionsKt`: `assertHeightIsEqualTo`, `assertWidthIsEqualTo`, `assertHeightIsAtLeast`, `assertLeftPositionInRootIsEqualTo`, `assertTopPositionInRootIsEqualTo`, `assertPositionInRootIsEqualTo`.
- `Box(Modifier.background(...))`, `Canvas` drawing and `Icon(contentDescription = null)` produce **no** semantics node: a stripe band's fill, a `drawBehind` stroke or a `Canvas` circle is not observable in the test tree. In `PatternTrack`, a **Landed** chip is Canvas + null-description Icon only, so it contributes no node; Current/Upcoming/Scrubbed chips each carry a `Text`.
- `Modifier.clickable(..., role = Role.Button)` sets `mergeDescendants`, so the `Text` inside `PrimaryButton` / `InkButton` / `UnderlinedTextButton` is reachable as its own node only with `useUnmergedTree = true`.
- No committed test uses `testTag`, `assertHeightIsEqualTo` or any absolute dp→px comparison. The existing bounds assertions (`ui/launchpad/LaunchpadPlannedActionsTest.kt` 197-199, `LaunchpadReFlyTest.kt` 219-220, `LaunchpadViewInRhoTest.kt` 191-198) compare nodes **to each other** in px, so there is no precedent for assuming a density. Capture `LocalDensity` inside `setContent` (or compare against a sibling node) rather than assuming 1.0.
- Compose screen tests carry `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")` (e.g. `LaunchpadPlannedActionsTest.kt:66`); `MissionControlScreenTest.kt:52` uses `@Config(qualifiers = "w411dp-h2800dp")` — a tall window so everything is on-screen. `app/build.gradle.kts` has `testOptions { unitTests.isIncludeAndroidResources = true }` and `testImplementation.compose` (Robolectric 4.14.1, compose-ui-test-junit4), so a Robolectric Compose test needs no new dependency.
- Compose's real `Modifier.offset` is `OffsetKt` in `androidx.compose.foundation.layout`; `OffsetShadow.kt` imports `Box` from that package but not `offset`, so its private no-op at line 83 is the only `offset` in scope there.

## Other tests and patterns

- `MainActivity.kt` (61 lines) — `ComponentActivity`. `onCreate`: `enableEdgeToEdge` with light status/nav bar styles (24-27); `val container = (applicationContext as LiftoffApplication).container` (28); `lifecycleScope.launch { repeatOnLifecycle(STARTED) { missionManager.onAppOpen(); lifecycleScope.launch(Dispatchers.IO) { AppOpenSweep(db, enqueue, cancel).run(mission.weekStart) } } }` (29-44) — the open sweep runs on **every** STARTED transition, on the real container the application builds; then `setContent { LiftoffTheme { LiftoffShell() } }` (45-49). `LiftoffShell()` takes no arguments. One `@Preview` (53-60).
- `LiftoffShellTest.kt` — `@RunWith(AndroidJUnit4::class)`, `createAndroidComposeRule<MainActivity>()`: it already launches `MainActivity` through `ActivityScenario`, and calls `missionManager.onAppOpen()` manually in `@Before` "before the UI is composed so Launchpad shows Draft content".
- `AppOpenSweepTest.kt` covers the sweep logic with fakes; `MissionManagerTest` covers `onAppOpen`.
- Gate: `:app:assembleDebug :app:testDebugUnitTest` (DESIGN.md §13.4), which is exactly what `.github/workflows/ci.yml` runs (`./gradlew :app:assembleDebug :app:testDebugUnitTest`, Java 17, 20-minute timeout).

## Facts checked outside the repo

- Kotlin's `assert` really is `-ea`-gated: the stdlib docs say it "Throws an AssertionError if the value is false **and runtime assertions have been enabled on the JVM using the -ea JVM option**" (kotlinlang.org `kotlin/assert.html`). So the brief's claim is right; `check()`/explicit throw is unconditional.
- Whether AGP's unit-test task passes `-ea` **is settled**: Gradle 8.11.1's `org.gradle/api/tasks/testing/Test.class` (in `~/android-toolchain/gradle/lib/plugins/gradle-testing-jvm-8.11.1.jar`) calls `JavaForkOptions.setEnableAssertions(true)` in its constructor, and AGP 8.7.3's `com/android/build/gradle/tasks/factory/AndroidUnitTest` extends `org.gradle.api.tasks.testing.Test` and never mentions `enableAssertions`. So today's `assert(found)` in `PreviewsTest` does throw when `found` is false; the brief's point is that the *mechanism* is a JVM flag rather than an assertion that can fail, and `check()` makes it unconditional.

## Running the gate on this machine

- `gradlew.sh` at HEAD is Windows-only: it exports `C:/Users/corpo/android-toolchain/{jdk,sdk}` and execs `C:/Users/corpo/android-toolchain/gradle/bin/gradle`. On Linux it dies with "No such file or directory".
- `./gradlew` (the unix wrapper; `gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.11.1) exists but cannot run here: `~/.gradle/wrapper/dists` does not exist, so it would have to download the distribution, and `java` is not on `PATH`.
- The Gradle that works here is `~/android-toolchain/gradle/bin/gradle` — Gradle 8.11.1, the same version the wrapper pins — with `JAVA_HOME=~/android-toolchain/jdk` and `ANDROID_HOME=~/android-toolchain/sdk`. All three directories exist. There is no `local.properties`; `ANDROID_HOME` is what the Android plugin needs.
- A gate run on this machine takes about a minute when the tree is warm (compilation cached, tests re-run). The suite is 93 report classes / ~775 cases.
- `LiftoffShellTest` flaked on one of three consecutive gate runs (attempts 1 and 2 failed, attempt 3 passed) — the recorded flake family. A gate script must retry that rather than call it a failure.
- `assembleDebug` reports `UP-TO-DATE` on a warm tree; that is normal and is not a reason to fail. Only `:app:testDebugUnitTest` being `FROM-CACHE`/`UP-TO-DATE`/`NO-SOURCE` means the tests did not execute.
- Gradle's JUnit XML shape, read from the real reports: a passing case is self-closing on the line it opens it (`<testcase name="x" classname="com.liftoff.app.ui.theme.StripesTest" time="0.007" />`), so an index that looks for `<skipped/>` or `<failure>` only on the *following* lines reads a skipped or failed case as a pass. `<testsuite name="…">` carries the class; `tests="N"` counts cases including skipped ones.

## `verify.sh` for this run

`.rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/verify.sh` — **386 lines**, committed `bbabe90` ("run: verify") and rewritten by Claude's review at `0ec1f86` ("run: verify.sh rewritten by claude review"). Later steps must not edit it. It runs the CI gate (`:app:assembleDebug :app:testDebugUnitTest`, `--no-build-cache`, 30-minute timeout, toolchain chosen as above), moves the previous reports aside first so a replayed result cannot stand in for the current tree, indexes every `TEST-*.xml` into `class.method` and class records holding the worst outcome for that name, then runs **25 checks**: the gate itself, the 10 named cases, and 14 whole-class checks (`LiftoffShellTest`, `PreviewsTest`, `ColorTokensTest`, `FontResourcesTest`, `FontLicenseTest`, `ColorSchemeTest`, `TypographyTest`, `IconsTest`, `ShapesTest`, `ThemePackageTest`, and `StripesTest`/`PatternTrackTest`/`ButtonsTest`/`OffsetShadowTest` as whole classes). Retry path: up to 3 gate attempts, only when the only failing classes are `LiftoffShellTest`/`InFlightScreenTest`/`LaunchpadPendingTitleTest` or the log shows a daemon/OOM/timeout signature.

The ten named cases and the class each must live in: `StripesTest` (`stripeBandHeightsAreTheValuesTheStripesDraw`, `stripesRenderAtTheirBandHeights`), `PatternTrackTest` (`chipSizeFollowsChipState`, `trackDrawsOneChipPerLetterAndMeasuresItsHeight`, `chipRingsAndConnectorAreTheDesignWidths`), `ButtonsTest` (`buttonDimensionsAreTheDesignValues`, `pressedFillIsComputedFromTheTokens`, `labelShiftsWhenTheIconMoves`), `OffsetShadowTest` (`offsetShadowDefaultsAreFourDp`, `aShadowedBoxKeepsTheSizeItAskedFor`).

**The review's rewrite dropped two things an earlier draft had**: a check that `com.liftoff.app.MainActivityTest` contributes no case at all (it contributes 2 today), and any scan of test *source* — so the script cannot see a vacuous assertion, cannot see `assert()` vs `check()`, and does not probe `-ea`. Deleting `MainActivityTest` and the `PreviewsTest` `check()` swap are required by `plan.md`, not by the gate.

Proofs done while writing it (logs under gitignored `app/build/`): three real gate runs on the untouched tree — exit 1, gate green, all ten named cases "did not run at all"; one of the three hit the `LiftoffShellTest` flake twice and passed on attempt 3. Against synthetic report sets built from the real reports: untouched set → exit 1; the ten cases present and passing → exit 0; one named case failed → "ran and FAILED", one skipped → "ran but was SKIPPED", a failure inside a required class → that class fails.

## Scratch / probe classes (if a task needs one)

`app/build/scratch-taskN/` plus an init script that adds it to the unit-test source set:

```groovy
allprojects { p ->
    if (p.name == 'app') {
        p.plugins.withId('com.android.application') {
            p.android.sourceSets.test.java.srcDir(new File(p.buildDir, 'scratch-taskN'))
        }
    }
}
```

then `… gradle -I app/build/scratch-taskN/init.gradle :app:testDebugUnitTest --tests <probe FQCN> --no-build-cache`. It compiles for that invocation only, so a later full-suite run and `verify.sh` never see it. Delete it before committing and check `git status --short` shows only the task's real files.

## Constants named by task 1 (fill this in when task 1 runs)

Task 1 (see `tasks.md`) names the inline literals in `Stripes.kt`, `PatternTrack.kt`, `Buttons.kt`, `OffsetShadow.kt`. Record here, exactly: the constant name, its value, the file, and what it was. Tasks 2-5 read them from the production file — this section is so they do not have to guess which of several 4.dp literals a name refers to.

**Filled in by task 1.** 23 `internal val`s, each declared in the file whose drawing it came from and read at that draw site. No value changed, no test file touched, nothing moved between files. Gate green on this tree: `:app:assembleDebug` + `:app:testDebugUnitTest` (`--no-build-cache`, both tasks executed) — 93 report classes, no `<failure>`, `<error>` or `<skipped>` anywhere, and the same per-class case counts as the untouched tree (StripesTest 2, PatternTrackTest 3, ButtonsTest 6, OffsetShadowTest 2, PreviewsTest 1, ThemePackageTest 1). Log: gitignored `app/build/task1-gate.log`.

### Stripes.kt — constants at 17-18 (file 56 → 60 lines)

| constant | value | what it was |
| --- | --- | --- |
| `TriStripeBandHeightDp` | `6.dp` | the three `Modifier.height(6.dp)` bands of `TriStripe` (was 24-26; now 28-30) |
| `DuoStripeBandHeightDp` | `5.dp` | the two `height(5.dp)` bands of `DuoStripe` (was 37-38; now 41-42) |

### PatternTrack.kt — constants at 24-40 (file 160 → 179 lines)

| constant | value | what it was |
| --- | --- | --- |
| `ChipSizeLandedDp` | `44` (**Int**) | `chipSizeDp`'s `Landed` branch (now 44) |
| `ChipSizeCurrentDp` | `52` (**Int**) | its `Current` branch (now 45) |
| `ChipSizeUpcomingDp` | `44` (**Int**) | its `Upcoming` branch (now 46) |
| `ChipSizeScrubbedDp` | `44` (**Int**) | its `Scrubbed` branch (now 47) |
| `PatternTrackHeightDp` | `52.dp` | the track `Box`'s `.height(52.dp)` (was 40; now 59) |
| `ChipRingCurrentWidthDp` | `3.dp` | `Current`'s `Stroke(3.dp.toPx())` (now 118) |
| `ChipRingUpcomingWidthDp` | `2.dp` | `Upcoming`'s `Stroke(2.dp.toPx())` (now 134) |
| `ChipRingScrubbedWidthDp` | `2.dp` | `Scrubbed`'s `Stroke(2.dp.toPx())` (now 150) |
| `ConnectorWidthDp` | `2.dp` | the connector `drawLine`'s `strokeWidth` (now 71) |
| `ConnectorInsetDp` | `22.dp` | both ends of the connector line (was 48-49; now 67-68) |
| `LandedCheckSizeDp` | `20.dp` | the `Icon` inside a `Landed` chip (was 90; now 109) |

### Buttons.kt — constants at 28-35 (file 229 → 239 lines)

| constant | value | what it was |
| --- | --- | --- |
| `PrimaryButtonHeightDp` | `72.dp` | `PrimaryButton`'s `height` parameter default (now 44) |
| `InkButtonHeightDp` | `64.dp` | `InkButton`'s `height` parameter default (now 108) |
| `ButtonBorderWidthDp` | `2.dp` | both `BorderStroke(2.dp, Ink)` calls (now 61, 124) |
| `ButtonIconSizeDp` | `26.dp` | the four `Modifier.size(26.dp)` icons (now 78, 92, 141, 155) |
| `ButtonIconLabelGapDp` | `12.dp` | the four `Spacer(Modifier.width(12.dp))` (now 81, 88, 144, 151) |
| `TextButtonMinHeightDp` | `44.dp` | `UnderlinedTextButton`'s `heightIn(min = 44.dp)` (now 176) |
| `TextButtonUnderlineWidthDp` | `1.dp` | the underline `drawLine`'s `strokeWidth` (now 196) |
| `TextButtonUnderlineGapDp` | `4.dp` | `baselinePx + 4.dp.toPx()`, the underline's gap below the baseline (now 192) |

### OffsetShadow.kt — constants at 20-21 (file 83 → 87 lines)

| constant | value | what it was |
| --- | --- | --- |
| `OffsetShadowDefaultOffsetDp` | `4.dp` | `offsetShadow`'s `offset` default (now 32) **and** `OffsetShadowBox`'s `offset` default (now 69) |
| `OffsetShadowDefaultCornerRadiusDp` | `4.dp` | the `4.dp` inside `offsetShadow`'s default `Shape` argument, `RoundedCornerShape(...)` (now 31) |

### What tasks 2-5 need to know about these names

- **The four chip-size constants are `Int`, not `Dp`**, because `chipSizeDp` keeps its `Int` return type (plan: "`chipSizeDp` stays the source of the chip sizes"). `PatternTrackHeightDp` is a *separate* `52.dp` literal that happens to equal `ChipSizeCurrentDp` today; task 3's track-height assertion reads `PatternTrackHeightDp`, not `ChipSizeCurrentDp.dp`.
- **`Buttons.kt` names no shadow offset.** Its two `offset = 4.dp` arguments now read `OffsetShadowDefaultOffsetDp` from `OffsetShadow.kt` (plan: "reads the `OffsetShadow.kt` default constant rather than a second name for the same number"). So task 4's shadow-offset assertion reads that name from `OffsetShadow.kt`, and it is the same number task 5 pins.
- **`OffsetShadowDefaultCornerRadiusDp` is only reachable as a number inside a `Shape`** (`RoundedCornerShape(...)`), not as a `cornerRadius` parameter; `Buttons.kt` still uses `ButtonShape` (16.dp corners) from `Shape.kt` and names no radius of its own.
- **`OffsetShadowDefaultOffsetDp` is a default parameter value of two public functions**, and `PrimaryButtonHeightDp` / `InkButtonHeightDp` are default parameter values of public composables. Kotlin compiles that (it checks types in a signature, not default expressions); the gate proves it.
- **Still literals on purpose** — the plan does not name them: `UnderlinedTextButton`'s `padding(horizontal = 8.dp)`, the previews' `padding(20.dp)`, the chip letters' `26.sp` / `22.sp` font sizes, `Headings.kt`'s `Spacer(4.dp)`, and every literal in the screens that call these composables.
- **Line numbers in the Production section above are the pre-task-1 ones.** The four files grew (56→60, 160→179, 229→239, 83→87); the "now" numbers in these tables are the post-task-1 draw sites.

## Task 2 — `StripesTest` rewritten (what this task found)

`app/src/test/java/com/liftoff/app/ui/theme/StripesTest.kt` is now 90 lines and has exactly the two
cases `verify.sh` names, nothing else. Class setup: `@RunWith(AndroidJUnit4::class)` +
`@Config(qualifiers = "w411dp-h2000dp")` + `@get:Rule val composeRule = createComposeRule()`.

- `stripeBandHeightsAreTheValuesTheStripesDraw` — reads `TriStripeBandHeightDp` and
  `DuoStripeBandHeightDp` into locals, then five assertions: each band against the design value
  (`6.dp`, `5.dp`), `triBand > duoBand`, and the band counts as totals (`triBand * 3 == 18.dp`,
  `duoBand * 2 == 10.dp`). Every assertion reads a production constant on one side.
- `stripesRenderAtTheirBandHeights` — one `setContent` holding `TriStripe(Modifier.testTag("tri-stripe"))`
  and `DuoStripe(Modifier.testTag("duo-stripe"))`; `LocalDensity.current` is assigned inside
  `setContent` to a `lateinit var density: Density`; expected height is
  `3 * with(density) { TriStripeBandHeightDp.roundToPx() }` (and `2 *` the duo constant), compared
  against `onNodeWithTag(tag).fetchSemanticsNode().size.height`, which is Int px.

All color assertions are gone, including the `Color(0xFFC23F14).value == Red.value` ones; the class
no longer mentions `Red`/`Mustard`/`Teal`. `ColorTokensTest` pins the tokens.

**Mutation probes (the reason to believe the two cases can fail).** Logs and the mutated copies are
gitignored under `app/build/`; `Stripes.kt` was restored from a copy and `git status` confirmed it
unmodified afterwards.
- `TriStripeBandHeightDp` 6 dp → 7 dp: the constant case fails
  (`expected:<6.0.dp> but was:<7.0.dp>`), the render case **passes** — it tracks the constant, so it
  is not the absolute pin. The pair is what pins the design value.
- one `TriStripe` band Box sized with a literal `8.dp` while the constant stayed 6 dp: the render
  case fails (`expected:<18> but was:<20>`), the constant case passes. So a draw site that stops
  reading the constant is caught by the render.
- a wrong band count in the test (4 instead of 3): fails with `expected:<24> but was:<18>`, which is
  also what showed the measured height is a real measurement.

**Numbers worth reusing for tasks 3–5.**
- `@Config(qualifiers = "w411dp-h2000dp")` resolves to **density 1.0** in this suite (the failure
  message printed `at density 1.0`), so 6 dp → 6 px and a `TriStripe` measures 18 px. The test still
  captures `LocalDensity` rather than assuming it.
- `Modifier.testTag` passed through `TriStripe`'s/`DuoStripe`'s `modifier` reaches the `Column` as its
  own semantics node even though the three band `Box`es carry no semantics of their own, and
  `SemanticsNode.size` (Int px) is the layout size. No `waitFor`, no `useUnmergedTree`, no
  `@GraphicsMode` was needed.
- `Modifier.height(Dp)` rounds **each** band with `roundToPx()`, so the height to expect is band count
  × rounded band, not `roundToPx(band count × band)`. At density 1.0 the two agree; at 2.625 they
  differ by 1 px (3 × 16 = 48 vs round(47.25) = 47), so write the per-band form.
- Cost: the render case is ~3.8 s (Robolectric + Compose startup), the constant-only case 0.07 s.
- Gate on the final tree: `:app:assembleDebug` + `:app:testDebugUnitTest` (`--no-build-cache`;
  `compileDebugUnitTestKotlin` and `testDebugUnitTest` executed, `assembleDebug` UP-TO-DATE) — 93
  report classes, 775 cases, no `<failure>`, `<error>` or `<skipped>` anywhere; `StripesTest` 2 cases,
  both passing. Logs: gitignored `app/build/task2-gate.log`, `app/build/task2-gate-final.log`.

## Task 3 — `PatternTrackTest` rewritten (what this task found)

`app/src/test/java/com/liftoff/app/ui/theme/PatternTrackTest.kt` is now 187 lines with four cases: the
three `verify.sh` names plus `chipColoursAreTheTokensPatternTrackDrawsWith`, which holds the colour
assertions the old class had. Same class setup as `StripesTest` (`AndroidJUnit4` +
`@Config(qualifiers = "w411dp-h2000dp")` + `@get:Rule val composeRule = createComposeRule()`).

- `chipSizeFollowsChipState` — each chip-size constant against its design number (44/52/44/44), each
  `chipSizeDp(state)` against the constant named for that state, then the constants against each other
  (`Current > Landed`, `> Upcoming`, `> Scrubbed`; `Landed == Upcoming == Scrubbed`), and
  `ChipSizeCurrentDp.dp == PatternTrackHeightDp` (`PatternTrack.kt`'s own "as tall as the widest chip").
- `trackDrawsOneChipPerLetterAndMeasuresItsHeight` — four chips, one per state, letters "D"/"R"/"L"/"S"
  (R current and L upcoming, the letters `verify.sh`'s comment names), track tagged
  `pattern-track`. `onAllNodes(hasParent(hasTestTag(TRACK_TAG))).assertCountEquals(3)` counts the
  semantics nodes the track itself carries; `onNodeWithText(letter, substring = false).assertExists()`
  per visible letter — `assertExists` fails on zero matches *and* on more than one, which is what "one
  chip per letter" means — and `assertDoesNotExist()` for the Landed letter. Height is
  `with(density) { PatternTrackHeightDp.roundToPx() }` against `fetchSemanticsNode().size.height` (Int px).
- `chipRingsAndConnectorAreTheDesignWidths` — the four widths against their design numbers (3/2/2/2),
  then against each other: `currentRing > upcomingRing`, `currentRing > scrubbedRing`,
  `connector < currentRing`, `connector <= upcomingRing`, `connector <= scrubbedRing`,
  `upcomingRing == connector`. `ConnectorInsetDp` against 22 dp **and** against `ChipSizeLandedDp.dp / 2`
  (the inset is half a non-Current chip's diameter, which is why the line starts at that chip's edge);
  `LandedCheckSizeDp` against 20 dp and `< ChipSizeLandedDp.dp`.
- `chipColoursAreTheTokensPatternTrackDrawsWith` — `Color(0xFF1D1B19) == Ink`, `0xFFC23F14 == Red`,
  `0xFFF2EADB == Cream`, `0xFFFFFFFF == White`, `0xFFE8DFCD == Sand`, `0xFF5E5850 == Muted`, then five
  `!=` checks between tokens (Ink/Red, White/Ink, Cream/Sand, Muted/Ink, Sand/Red). The old
  `Color.White == Color.White` line is gone; `White` is now the repo token, not Material's.

**Two claims the old class made that are gone on purpose.** `chipTextUsesBigShouldersFontFamily`
compared two literal `Set`s of `BigShoulders` with each other and could not fail; the plan's
PatternTrackTest has no font case, so the font claim is untested here. And `tasks.md` says the colour
assertions "read `LiftoffTheme.palette`" — **no `palette` identifier exists in this repo**
(`grep -rn palette --include=*.kt app/src` → 0 hits). The theme's colours are the public top-level
tokens in `Color.kt`; `LiftoffColorScheme()` is declared in `Color.kt:21` (capital L, not
`liftoffColorScheme`) and `LiftoffTheme` is only a `MaterialTheme(...)` wrapper. `PatternTrack`,
`Stripes` and `Buttons` reference `MaterialTheme`/`colorScheme` **zero** times — they read the tokens
directly — so the colour case reads the tokens.

**The connector is not thinner than *either* ring.** `tasks.md`'s parenthetical says "connector thinner
than either"; `ConnectorWidthDp` is 2 dp and `ChipRingUpcomingWidthDp`/`ChipRingScrubbedWidthDp` are
also 2 dp. What is true on the current tree is `connector < currentRing` and
`connector <= upcomingRing`/`<= scrubbedRing`, with `upcomingRing == connector` asserted as what it is.
Do not write `connector < upcomingRing` — it is false.

**What a rendered `PatternTrack` puts in the semantics tree** (probe dump, density 1.0, w411dp): root →
the tagged track `Box` (411 × 52 px) → **exactly three** `Text` nodes, one per chip that draws its
letter. A Landed chip (`Canvas` circle + `Icon(contentDescription = null)`) contributes **no node at
all**: 0 nodes carry `Role.Image`, and `onNodeWithText("D")` finds nothing. The chip `Box`es, the `Row`
and the `Canvas` are invisible to the tree, so a chip count cannot be read off it. The `Text` nodes
measure **1 × 35 px** — Robolectric's default graphics mode fakes text widths, so never assert a
letter's width or position.

**Compose 1.7.6 test API names that are not what you'd guess** (each cost a compile error):
`createComposeRule` is `androidx.compose.ui.test.junit4.createComposeRule` — `...test.junit.` does not
resolve. `SemanticsNodeInteractionCollection` has **`assertCountEquals`**, not `assertCount`, and
`SemanticsNodeInteraction` has **no `onChildren()`** — scope a query with
`onAllNodes(hasParent(hasTestTag(tag)))` or read `fetchSemanticsNode().children`. `SemanticsConfiguration`
exposes no `keys` and no `getOrNull` (use `config.map { it.key.name }`, `contains` + `config[key]`).
`Role.Image`, not `Role.Imagery`. `with(density) { }` is `kotlin.with`; importing
`androidx.compose.ui.unit.with` fails. `onAllNodes` is a member of the rule (no import), while
`onNodeWithTag`/`onNodeWithText`/`hasParent`/`hasTestTag`/`assertCountEquals` are extensions in
`androidx.compose.ui.test`. `onNodeWithText`'s default is `substring = true`.

**Mutation probes** (mutated copies and logs gitignored under `app/build/`; `PatternTrack.kt` and
`Color.kt` restored from copies, and `git status` afterwards showed only the test file modified):
- `PatternTrackHeightDp` 52 → 48 dp: `chipSizeFollowsChipState` fails (`expected:<52.0.dp> but
  was:<48.0.dp>`); the render case **passes** — it tracks the constant, so it is not the absolute pin.
- the track drawn with `.height(48.dp)` while the constant stays 52 dp: the render case fails
  (`expected:<52> but was:<48>`, message `PatternTrack renders PatternTrackHeightDp (52.0.dp) tall
  (52px at density 1.0)`), the constant case passes. A draw site that stops reading the constant is
  caught by the render.
- `ChipRingCurrentWidthDp` 3 → 2 dp: only `chipRingsAndConnectorAreTheDesignWidths` fails.
- `ChipSizeCurrentDp` 52 → 44: only `chipSizeFollowsChipState` fails.
- `val Muted = Color(0xFF5E5850)` → `0xFF5E5851` in `Color.kt`: only the colour case fails.
- a `Text(chip.letter)` added to the Landed branch: the render case fails — the count goes 3 → 4 and
  `assertDoesNotExist` on "D" fires. So the count is a real count of what the tree sees.
- the Current branch drawing `text = ""`: the render case fails on `onNodeWithText("R")`.

**What the colour case cannot do — tasks 4 and 5 have the same limit.** Swapping a draw site
(`color = Muted` → `color = Ink` in `PatternTrack.kt`) is **not** caught by any assertion: colours are
not in the semantics tree and `PatternTrack(chips, modifier)` takes no colour parameters, so the only
thing a test can pin is the token value. The colour case pins tokens; it does not prove which token a
draw site uses. `ButtonsTest.pressedFillIsComputedFromTheTokens` (task 4) is pinned the same way — its
value is computing from `Red`/`RedPressed`/`Ink` rather than from hex.

**Gate on the final tree.** `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
ANDROID_SDK_ROOT=~/android-toolchain/sdk timeout 1800 ~/android-toolchain/gradle/bin/gradle
:app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain` → exit 0,
`testDebugUnitTest` executed (`compileDebugUnitTestKotlin` and `assembleDebug` UP-TO-DATE) — 93 report
classes, **776** cases (was 775; `PatternTrackTest` 3 → 4), no `<failure>`, `<error>` or `<skipped>`.
`PatternTrackTest` alone: 4 cases, 3.683 s (the first case pays the Robolectric + Compose startup) /
0.471 s (the render) / 0.001 s / 0.001 s. `verify.sh` on this tree: gate ok, `PatternTrackTest`'s three
names ok, `class com.liftoff.app.ui.theme.PatternTrackTest` ok — **20 ok, 5 failed**, the 5 being
`ButtonsTest`'s and `OffsetShadowTest`'s cases, which tasks 4 and 5 write. Logs: gitignored
`app/build/task3-alone.log`, `task3-alone2.log`, `task3-gate.log`, `task3-verify.log`,
`app/build/scratch-task3/mut-*.log`.
**`./gradlew.sh` cannot run on this machine** — it is `exec "C:/Program Files/Java/jdk-17.0.17+10/bin/java"`;
the earlier claim in this map that it works here is wrong. Use the toolchain `gradle` above, and
`ANDROID_HOME` is `~/android-toolchain/sdk`, not `android-sdk`.

**Scratch harness that worked.** `app/build/scratch-task3/init.gradle` (the `gradle.projectsEvaluated {
... srcDir(...) }` shape) plus `gradle --init-script app/build/scratch-task3/init.gradle
:app:testDebugUnitTest --tests com.liftoff.app.ui.theme.PatternTrackProbe`. A test's `println` does
**not** reach the console; read it from the report:
`grep -o "PROBE[^<]*" app/build/test-results/testDebugUnitTest/TEST-<class>.xml`.

### Task 4 — `ButtonsTest` rewritten (3 cases, 260 lines)

`buttonDimensionsAreTheDesignValues`, `pressedFillIsComputedFromTheTokens`,
`labelShiftsWhenTheIconMoves`. The old class's 6 cases are gone; its `Red`/`RedPressed`/`Ink` hex
pins live inside the colour case.

**`InkRed` and "the mix `Buttons.kt` performs" do not exist on this tree.** `grep -rn "InkRed" .rho/
app/src` hits only `tasks.md:83` and this map's own line 27 — no Kotlin file. `grep -rn
"mix\|lerp\|blend" app/src/main/java/com/liftoff/app/ui/theme/*.kt` hits nothing. What is true
instead: `val RedPressed = Color(0xFF8F2E0E)` is its own token at `Color.kt:12` (public `val`s, not
`internal`), pinned by `ColorTokensTest` against `design/README.md:38` (`red_pressed` `#8F2E0E`), and
`Buttons.kt` **swaps tokens and mixes nothing**: `if (isPressed.value) RedPressed else Red` at
`Buttons.kt:60` (`PrimaryButton`'s fill) and `:114` (`InkButton`'s shadow), `if (isPressed.value) Red
else Ink` at `:190` and `:195` (`UnderlinedTextButton`'s label and underline). So
`pressedFillIsComputedFromTheTokens` cannot derive `RedPressed`; it measures what `RedPressed` keeps
from `Red`, computed from the two tokens' channels: hue 14.88° vs 14.83° (asserted within 1°),
saturation 0.902 vs 0.897 (within 0.1), luminance 0.280 vs 0.381 (darker). `Ink` is hue 30.0°,
saturation 0.138, luminance 0.107 — darker than both reds and barely a colour, which is what the old
"Normal shadow should be Ink" comment got wrong: `InkButton`'s shadow is `Red` at rest and
`RedPressed` while pressed (`:114`); its **fill** is `Ink` in both states (`:123`).
`UnderlinedTextButton` swaps the other way, so its pressed colour is *lighter* than its rest colour —
asserted, because it is the one relationship the three tokens have that the filled buttons do not.

**`composeRule.setContent` cannot be called twice per test** — the second call throws
`IllegalStateException: Cannot call setContent twice per test!`. To measure one composable in two
states, hold `val iconAtEnd = mutableStateOf(false)`, pass `iconAtEnd = iconAtEnd.value`, and flip it
with `composeRule.runOnIdle { iconAtEnd.value = true }`. `runOnIdle` is a member of `ComposeTestRule`
(no import), and the flip recomposes and lays out before it returns.

**What the render case measures.** `Modifier.testTag` on `PrimaryButton`'s `modifier` reaches
`OffsetShadowBox`'s outer `Box` (`Buttons.kt:53-55` `modifier = modifier` `.fillMaxWidth()` `.height(height)` →
`OffsetShadow.kt:76`), and `fetchSemanticsNode().size` is 411 × 72 px at `w411dp-h2000dp` (density
1.0) — the height is `roundToPx(PrimaryButtonHeightDp)`, asserted. The label is only reachable with
`onNodeWithText(LABEL, substring = false, useUnmergedTree = true)`; `Modifier.clickable` has merged it
into the button node. `SemanticsNode.boundsInRoot.left` is a Float px. Measured: **221.0 px** with the
icon first, **183.0 px** with `iconAtEnd` — a shift of **−38 px** = `roundToPx(26.dp) +
roundToPx(12.dp)`. **The label moves left when the icon moves to the end**; assert `abs(shift)`
against the expected move and the direction (`shift < 0`) separately, or the sign will read as a bug.
Robolectric's fake text metrics give "LAUNCH" a 7 px width, so the `Row` is 45 px wide inside the 411
px button and the shift is centring arithmetic (the `Row` is wrap-content, centred by the `Box`'s
`contentAlignment = Center`) — never the label's own width. Each child is rounded separately, so the
expected move is `roundToPx(icon) + roundToPx(gap)`, not `roundToPx(icon + gap)`.

**Mutation probes** (mutated files restored from copies under gitignored `app/build/scratch63/`;
`git status` afterwards showed only `ButtonsTest.kt` modified):
- `PrimaryButton`'s icon-first `Modifier.size(ButtonIconSizeDp)` (`Buttons.kt:78`) → `24.dp`: the
  render case fails with `shift=-37.0`, the constant case passes. **37, not 36** — the narrower `Row`
  is centred, so the whole `Row` shifts 1 px and only the measured side moves 38→37.
- `ButtonIconLabelGapDp` 12 → 20 dp (`Buttons.kt:32`): only `buttonDimensionsAreTheDesignValues` fails
  (`expected:<12.0.dp> but was:<20.0.dp>`); the render case passes — it tracks the constant, so it is
  not the absolute pin.
- `val RedPressed` → `0xFF8F2E14` (`Color.kt:12`): the colour case fails on the hex pin. With the three
  hex pins commented out it fails instead on `the pressed fill is the same red as the rest fill, only
  darker: pressed hue=12.682926384715529 rest hue=14.82758507358443` — the relationship assertions are
  live, not dead weight sitting behind the pins.
- Colours are still uncatchable at the draw site (task 3's finding holds here): the colour case pins
  tokens, so changing `if (isPressed.value) RedPressed else Red` at `Buttons.kt:60` to another token is
  caught by nothing in `ButtonsTest`.

**More phantom content in this map's "Theme files" section — re-derive before use.** `read` returned a
199-line `Buttons.kt` that is not on disk (the real file is 239 lines: the constants are at 28-35, the
icon size is `modifier = Modifier.size(ButtonIconSizeDp)` on the `Icon` itself at 78/92/141/155 with no
`Row` wrapper, and `PrimaryButton`'s label colour is `labelStyle.copy(color = White)` inline at 85).
`read` returned a 35-line `Color.kt` (the real file is 54 lines; `val Ink` 10, `val Red` 11, `val
RedPressed` 12) and this map's line 27 names `InkRed` plus `Teal = 0xFF145060`, `TealLight =
0xFF6FA8A0`, `Muted = 0xFF8A857C`, none of which are in `Color.kt` (real: `Teal 0xFF1F5F6B`,
`TealLight 0xFF2F7A6E`, `Muted 0xFF5E5850`). `Icons.kt` is 42 lines on disk and in the git blob:
`object LiftoffIcons` is five `@Composable fun`s returning `painterResource` — `rocket` 19, `planet`
20, `flag` 21, `sliders` 22, `check` 23 — no ImageVector, no BitmapPainter, no `iconSizeDp` parameter.
Being `@Composable`, `LiftoffIcons.check()` can only be called inside `setContent`; it renders fine
under Robolectric (`isIncludeAndroidResources = true`), as the `LaunchpadScreen` tests already prove.
Confirm with `awk 'END{print NR}' <path>` against `git show HEAD:<path> | awk 'END{print NR}'`.

**Gate on the final tree.** The toolchain gradle command → exit 0, 93 report classes, **773** cases
(was 776; `ButtonsTest` 6 → 3), no `<failure>`, `<error>` or `<skipped>`. `ButtonsTest` alone: 3 cases,
3.533 s (the first pays the Robolectric + Compose startup) / 0.079 s / 0.455 s (the render).
`verify.sh` on this tree: gate ok, all three `ButtonsTest` names ok, `class
com.liftoff.app.ui.theme.ButtonsTest` ok — **23 ok, 2 failed**, the 2 being `OffsetShadowTest`'s cases,
which task 5 writes. Logs: gitignored `app/build/scratch63/buttons-alone*.log`,
`mutate-{iconsize,gap,redpressed,hue}.log`, `verify-task4.log`, and the pristine copies
`Buttons.kt.orig`, `Color.kt.orig`, `ButtonsTest.kt.final`.
