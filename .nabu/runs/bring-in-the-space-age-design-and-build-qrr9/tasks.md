- [x] Merge origin/design/space-age-icon
  Bring design/, DESIGN.md, AndroidManifest.xml changes, and launcher icon resources onto this branch unchanged. If design/README.md already exists the merge is a no-op.
  Check: `git diff origin/design/space-age-icon HEAD -- design DESIGN.md app/src/main/AndroidManifest.xml app/src/main/res` is empty.

- [x] Bundle fonts and licenses
  Download static TTF instances of Big Shoulders Display (700/800/900) and Work Sans (400/500/600) via the Google Fonts CSS2 API, write OFL license texts to assets/licenses/, add `*.ttf binary` to .gitattributes.
  Check: each TTF starts with TrueType magic `00 01 00 00`, both OFL files are present, and .gitattributes contains the line.

- [x] Build theme foundation (colors, typography, shapes, LiftoffTheme, color test)
  Write Color.kt (LiftoffColors + lightColorScheme), Type.kt (FontFamilies + LiftoffType + M3 Typography mapping), Shape.kt (RoundedCornerShape(4dp) for buttons/checkboxes, RectangleShape for cards), LiftoffTheme.kt, and ColorTokensTest.kt which reads design/README.md to pin all 12 hex values.
  Check: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes; the test finds exactly 12 tokens and all match.

- [x] Build components and stroke icons
  Add five vector drawables (rocket, planet, flag, sliders, check), then OffsetShadow.kt, Stripes.kt, Buttons.kt, Headings.kt, PatternTrack.kt, InkRuledListRow.kt, and Icons.kt — each with previews on a Cream background.
  Check: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes; all seven source files compile with no errors.

- [x] Wire MainActivity into LiftoffTheme with placeholder screen
  Call enableEdgeToEdge, wrap setContent in LiftoffTheme, render a Cream Box with a Column containing TriStripe() and Wordmark() at the mockup's padding (16 dp top, 20 dp sides). Keep the "M1 replaces this with the navigation shell" comment.
  Check: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes; APK installs on a device showing cream screen with stripes and LIFTOFF wordmark.

## Blockers from the final review

- [x] Make the offset shadow actually offset and match the button shape
  OffsetShadow.kt: OffsetShadowBox fills its own bounds with an unshaped rectangle (drawRect on matchParentSize). The private `Modifier.offset(offset: Dp)` just returns `this`, so the shadow is never moved 4 dp right and down and sits hidden behind the content. The brief asks for a solid 4 dp offset shadow. As written, none appears on either button. Implement it as planned: `Modifier.offsetShadow(color, shape, offset)` using drawBehind, translate(offset, offset) and drawOutline of shape.createOutline.
- [x] Fix PrimaryButton/InkButton press state, fill size and icon type
  Buttons.kt has three problems. (1) `isPressed` is a `mutableStateOf(false)` that nothing ever sets. The new `MutableInteractionSource()` is never collected with collectIsPressedAsState, so the red_pressed fill and the ink button's red_pressed shadow never appear. (2) The inner Box with the background, border and clickable has no fillMaxSize. It wraps only the label, so the button draws as an ink block with a small red box around the text, and only that box responds to taps. (3) `icon` is typed `VectorPainter?`, but LiftoffIcons returns `painterResource(...)`, whose static type is Painter. `PrimaryButton(icon = LiftoffIcons.rocket())` therefore will not compile. Use `Painter?`.
- [x] Draw the text-button underline 4 dp below the baseline at text width
  Buttons.kt UnderlinedTextButton draws the underline in a separate 1 dp Canvas under the Text. It uses fillMaxWidth, so the line spans the whole button width, not the label. `underlineY = baseline + 4f` is in px and is clamped to the canvas height (1 px), so the line is never 4 dp below the baseline. The stroke is 1 px, not 1 dp. The press colour never changes (same dead `isPressed`). The height is fixed at 44 dp rather than 'at least 44 dp', and fillMaxWidth makes the whole row the tap target. Draw the line from onTextLayout in dp, with the label's width, in a drawBehind on the Text.
- [x] Stripes render with zero width
  Stripes.kt: each band is `Box(Modifier.height(6.dp).background(...))` with no width modifier. An empty Box in a Column measures to 0 width, so TriStripe and DuoStripe draw nothing. That makes the placeholder required by the brief ('the stripes and the LIFTOFF wordmark on cream') show no stripes. Add fillMaxWidth to each band.
- [x] Placeholder ignores system-bar insets after enableEdgeToEdge
  MainActivity.kt turns on edge-to-edge but pads the Column only by a fixed 16/20 dp, with no WindowInsets.systemBars. The stripe and the LIFTOFF wordmark therefore draw under the status bar. The stripe is also inset 20 dp on each side instead of running full width at the top. The plan called for a systemBars-padded Column holding a full-width TriStripe, then the Wordmark with 16 dp top and 20 dp side padding.
- [ ] Fix pattern-track chip sizes, stroke widths and font
  PatternTrack.kt: every chip sits in a `Modifier.size(44.dp)` Box, so the current chip's 52 dp circle is clamped to 44 dp. Border strokes are `Stroke(width = 3f/2f)` in px, not 3 dp/2 dp, so they are hairlines on high-density screens. Chip letters set no fontFamily and fall back to Work Sans (bodyLarge) instead of Big Shoulders 900/800. The brief asks for chips in the landed, current and upcoming styles from the design.
- [ ] Load fonts from res/font and remove the duplicate assets/fonts copies
  The six TTFs are committed twice: in res/font and in assets/fonts. Type.kt loads the asset copies, so the res/font files the brief asks for are unused and the APK ships about 550 KB of duplicate fonts. The commit's justification ('R.java not generated') is contradicted by Icons.kt, which uses R.drawable successfully. Use `Font(R.font.…, weight)` in top-level FontFamily vals. createBigShoulders/createWorkSans also currently build new FontFamily objects on every recomposition.
- [ ] Replace tautological tests so verify.sh proves the behavior
  verify.sh relies on named tests, and most of them only assert constants against themselves. ButtonsTest checks `Red.hashCode()` and literal booleans. OffsetShadowTest asserts `4 == 4`. StripesTest asserts `6+6+6 == 18`. PatternTrackTest checks data-class fields. MainActivityTest asserts a hand-written list equals itself. ColorSchemeTest builds its own lightColorScheme instead of testing LiftoffColorScheme. All of these passed while the shadow, stripes, press states, underline and placeholder are broken. Robolectric is already available: use Compose UI tests (createComposeRule, captureToImage or semantics/bounds) to check the stripe band colours and heights, the shadow pixel offset, the pressed fill, the underline position, the chip sizes, and the placeholder layout. Have the scheme test call the real LiftoffColorScheme.
- [ ] Reword the two commits that break the CLAUDE.md message style
  3b23262 'Build theme foundation (...)' and 4d59aaa 'Build components and stroke icons (task 4/5)' do not follow the `area: lowercase summary` style. The brief explicitly says the commit message style must not be broken, so reword them (e.g. 'ui: add space age color, type and shape theme') before the PR.
