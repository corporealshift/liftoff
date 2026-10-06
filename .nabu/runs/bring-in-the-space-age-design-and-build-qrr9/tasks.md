- [ ] Merge origin/design/space-age-icon
  Bring design/, DESIGN.md, AndroidManifest.xml changes, and launcher icon resources onto this branch unchanged. If design/README.md already exists the merge is a no-op.
  Check: `git diff origin/design/space-age-icon HEAD -- design DESIGN.md app/src/main/AndroidManifest.xml app/src/main/res` is empty.

- [ ] Bundle fonts and licenses
  Download static TTF instances of Big Shoulders Display (700/800/900) and Work Sans (400/500/600) via the Google Fonts CSS2 API, write OFL license texts to assets/licenses/, add `*.ttf binary` to .gitattributes.
  Check: each TTF starts with TrueType magic `00 01 00 00`, both OFL files are present, and .gitattributes contains the line.

- [ ] Build theme foundation (colors, typography, shapes, LiftoffTheme, color test)
  Write Color.kt (LiftoffColors + lightColorScheme), Type.kt (FontFamilies + LiftoffType + M3 Typography mapping), Shape.kt (RoundedCornerShape(4dp) for buttons/checkboxes, RectangleShape for cards), LiftoffTheme.kt, and ColorTokensTest.kt which reads design/README.md to pin all 12 hex values.
  Check: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes; the test finds exactly 12 tokens and all match.

- [ ] Build components and stroke icons
  Add five vector drawables (rocket, planet, flag, sliders, check), then OffsetShadow.kt, Stripes.kt, Buttons.kt, Headings.kt, PatternTrack.kt, InkRuledListRow.kt, and Icons.kt — each with previews on a Cream background.
  Check: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes; all seven source files compile with no errors.

- [ ] Wire MainActivity into LiftoffTheme with placeholder screen
  Call enableEdgeToEdge, wrap setContent in LiftoffTheme, render a Cream Box with a Column containing TriStripe() and Wordmark() at the mockup's padding (16 dp top, 20 dp sides). Keep the "M1 replaces this with the navigation shell" comment.
  Check: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes; APK installs on a device showing cream screen with stripes and LIFTOFF wordmark.
