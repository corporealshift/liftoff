Theme tests can't fail: replace the tautological assertions

From rho's review of #52. `app/src/test/java/com/liftoff/app/ui/theme/` is 13 files that never render a composable, and many of their assertions compare a value with itself:

- `StripesTest.kt` ~21-22, 40: `assertEquals(Mustard.value, Mustard.value)`, plus locals asserted against the literals they were just set to
- `PatternTrackTest.kt` ~16-17, 44-45, 67-68: `assertEquals(44f, 44f)`, `assertEquals(900, 900)`
- `ButtonsTest.kt` ~13, 23: `val iconAtEnd = true; assertEquals(true, iconAtEnd)`
- `OffsetShadowTest.kt` ~11-12, 30-33: `modifier != null` on a non-nullable type, and literal-vs-literal checks
- `app/src/test/java/com/liftoff/app/MainActivityTest.kt` ~22-27, 41-43, 52-53: never touches MainActivity, and its comment describes the old placeholder screen
- `PreviewsTest.kt` ~26, 34: uses Kotlin `assert()`, which only throws under `-ea`

**Fix:** expose the real dimensions (stripe heights, chip sizes and strokes, shadow offset, underline widths) as constants in `Stripes.kt`, `PatternTrack.kt`, `Buttons.kt` and `OffsetShadow.kt`, and assert against those, or render the composables with `createComposeRule` where the claim is about layout. Delete what can't be made meaningful. Rewrite or delete MainActivityTest. Replace `assert()` with `check()` or an explicit throw.

ColorTokensTest, FontResourcesTest, FontLicenseTest, ColorSchemeTest, TypographyTest and IconsTest are fine as they are.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#63: https://github.com/corporealshift/liftoff/issues/63)
