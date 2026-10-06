# Plan: bring in the Space Age design and build the theme

## Approach

1. Merge `origin/design/space-age-icon` into this branch, keeping its content unchanged. If `design/README.md` already exists, skip this step. The merge is clean: since the merge base `4a0c7bc`, this branch has only touched `.nabu/` files.
2. Bundle static TTF instances of Big Shoulders Display (700/800/900) and Work Sans (400/500/600) in `app/src/main/res/font/`. Ship each family's OFL text in `app/src/main/assets/licenses/`, so the license travels with the APK. The license cannot go in `res/font/`: aapt2 rejects anything there that is not a font or font XML, and it rejects upper-case file names, so it would break the build.
3. Build the theme and every small composable in **`com.liftoff.app.ui.theme`**. That is the package the brief names, and it matches the `ui/ … theme/` layout in DESIGN.md §12. Do not create a separate `ui/component` package.
4. Add the stroke icons as vector drawables in `res/drawable/`, using the mockup paths.
5. Render MainActivity inside `LiftoffTheme`, showing a placeholder: the 3-band stripe, then the LIFTOFF wordmark, on cream.
6. Add a JVM unit test that pins every color token to the hex value in the `design/README.md` color table.

No Gradle changes are needed. Compose, Material 3, `ui-tooling-preview` and JUnit are already in `app/build.gradle.kts`, and fonts are plain resources. Do not touch `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts` or `gradle.properties`. The only manifest change is the one the merge brings (`android:icon` and `android:roundIcon`). No pure package (`domain/`, `coach/`) is touched.

## Files involved

### From the merge (verbatim)
- `design/README.md`, `design/screens/launchpad.html`, `design/screens/in-flight.html`, `design/icon/liftoff-icon.svg`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`
- `app/src/main/res/values/ic_launcher_background.xml`
- `DESIGN.md` (§9 and §12 pointers, plus the amendment entry)
- `app/src/main/AndroidManifest.xml` (`android:icon`, `android:roundIcon`)

### Fonts and licenses (new)
Resource names must be `[a-z0-9_]` only:
- `app/src/main/res/font/big_shoulders_display_bold.ttf` (700)
- `app/src/main/res/font/big_shoulders_display_extrabold.ttf` (800)
- `app/src/main/res/font/big_shoulders_display_black.ttf` (900)
- `app/src/main/res/font/work_sans_regular.ttf` (400)
- `app/src/main/res/font/work_sans_medium.ttf` (500)
- `app/src/main/res/font/work_sans_semibold.ttf` (600)
- `app/src/main/assets/licenses/OFL-BigShouldersDisplay.txt`
- `app/src/main/assets/licenses/OFL-WorkSans.txt`
- `.gitattributes`: add one line, `*.ttf binary`, so git never treats a font as text and normalizes its line endings.

### Theme package `app/src/main/java/com/liftoff/app/ui/theme/` (new)
- `Color.kt`
  - `object LiftoffColors` holds the 12 tokens by name: `Cream`, `Paper`, `Sand`, `Ink`, `Red`, `RedPressed`, `Mustard`, `Teal`, `TealLight`, `Muted`, `Rule`, `White`. Each is a `Color(0xFF……)` with the README hex.
  - An internal `LiftoffColorScheme = lightColorScheme(...)` maps the tokens into the M3 slots, so nothing falls back to M3's purple or tonal defaults:
    - primary = Red, onPrimary = White
    - secondary = Mustard, onSecondary = Ink
    - tertiary = Teal, onTertiary = Cream
    - background and surface = Cream; onBackground and onSurface = Ink
    - surfaceVariant = Sand, onSurfaceVariant = Muted
    - every `surfaceContainer*` slot, plus surfaceBright and surfaceDim = Paper
    - surfaceTint = Cream
    - outline = Ink, outlineVariant = Rule
    - error = Red, onError = White
    - primaryContainer, secondaryContainer and tertiaryContainer use Red, Mustard and Teal with the matching on-colors (no pastel tonal containers)
- `Type.kt`
  - `LiftoffFonts.BigShoulders` and `LiftoffFonts.WorkSans` are `FontFamily(Font(R.font.…, FontWeight.W700), …)`.
  - `object LiftoffType` holds one `TextStyle` per README role, with the README size, weight and letter spacing (in `em`): `Wordmark`, `ScreenTitle` (lineHeight `0.9.em`), `HeaderTitle`, `LaunchLabel`, `LandLabel`, `SectionHead`, `CardTitle`, `SetValue`, `Load`, `Index`, `BarLabel`, `Eyebrow`, `ExerciseName`, `TextButton`, `Note`.
  - The M3 `Typography` maps display, headline and title slots to Big Shoulders roles, and body and label slots to Work Sans roles. A stock `Text` then defaults to Work Sans.
  - Compose has no text-transform, so the composables call `uppercase()` wherever the README says "uppercase".
- `Shape.kt`
  - M3 `Shapes`: extraSmall and small = `RoundedCornerShape(4.dp)` (buttons, checkboxes); medium, large and extraLarge = `RectangleShape` (cards).
  - Named vals `ButtonShape` and `CardShape`. Chips use `CircleShape`.
- `LiftoffTheme.kt`: `@Composable fun LiftoffTheme(content)` wraps `MaterialTheme(LiftoffColorScheme, LiftoffTypography, LiftoffShapes)`. It is light only and ignores system dark mode.
- `OffsetShadow.kt`: `Modifier.offsetShadow(color: Color = LiftoffColors.Ink, shape: Shape = RectangleShape, offset: Dp = 4.dp)`.
  - Uses `drawBehind`: build `shape.createOutline(size, layoutDirection, this)`, `translate(offset, offset)`, then `drawOutline` with a solid color and no blur.
  - It takes no layout space (like CSS box-shadow), so callers leave 4 dp of room right and below.
  - It must come before `border` and `background` in the modifier chain.
- `Stripes.kt`:
  - `TriStripe(modifier)`: full width, three 6 dp bands, Red, Mustard, Teal.
  - `DuoStripe(modifier)`: two 5 dp bands, Mustard then Red.
- `Buttons.kt`
  - `PrimaryButton(text, onClick, modifier, icon: Painter? = null, iconAtEnd = false, height = 72.dp, labelStyle = LiftoffType.LaunchLabel)`:
    - full width, 4 dp corners, 2 dp ink border, Ink `offsetShadow`
    - Red fill, RedPressed while pressed
    - White label, upper-cased
    - optional 26 dp icon tinted like the label, 12 dp gap
  - `InkButton(...)`: same structure, 64 dp, Ink fill, Cream label in `LandLabel`, Red offset shadow that turns RedPressed while pressed.
  - Both buttons track the press with `MutableInteractionSource.collectIsPressedAsState()`, set `indication = null` (no ripple) and `Role.Button`.
  - `UnderlinedTextButton(text, onClick, modifier)`:
    - Work Sans 15/600, Ink, no ripple
    - at least 44 dp tall, 8 dp horizontal padding
    - Compose cannot set an underline offset, so draw the underline manually: a 1 dp line 4 dp below the text's last baseline, read from `onTextLayout`
    - label and underline turn Red while pressed
- `Headings.kt`
  - `Eyebrow(text, color = Muted)`: upper-cased, `LiftoffType.Eyebrow`.
  - `DisplayTitle(text, style = ScreenTitle, color = Ink)`: upper-cased.
  - `TitleBlock(eyebrow, title, eyebrowColor, titleColor, titleStyle)`: a Column with a 4 dp gap. In-Flight will pass Mustard, Cream and `HeaderTitle`.
  - `Wordmark(color = Ink)`: "LIFTOFF" in `LiftoffType.Wordmark`.
- `PatternTrack.kt`
  - `enum class ChipState { Landed, Current, Upcoming }`, `data class PatternChip(val letter: String, val state: ChipState)`.
  - `PatternTrack(chips: List<PatternChip>, modifier)`:
    - a 52 dp tall Box
    - a 2 dp Ink line, vertically centred, running from the first chip's centre to the last chip's centre (inset 22 dp each side), drawn behind
    - a Row with `SpaceBetween` and the chips centred vertically
  - Chip styles, from the mockup:
    - Landed: 44 dp Ink circle, Cream `ic_check` at 20 dp.
    - Current: 52 dp Red circle, 3 dp Ink border, White letter, Big Shoulders 900, 26 sp.
    - Upcoming: 44 dp Cream circle, 2 dp Ink border, Ink letter, Big Shoulders 800, 22 sp.
- `InkRuledListRow.kt`: `InkRuledListRow(index: String, title: String, detail: String?, modifier)`.
  - Index in Red (`LiftoffType.Index`) in a 30 dp column, title (`ExerciseName`) weighted 1f, detail (`Load`) at the end.
  - All three use `alignByBaseline()`, with 9 dp vertical padding and a 1 dp Ink rule drawn along the bottom.
- `Icons.kt`: `object LiftoffIcons` with `@Composable` getters returning `painterResource(R.drawable.ic_…)` for rocket, planet, flag, sliders and check, so callers do not repeat resource ids.
- Each composable file carries one or two `@Preview`s on a Cream background.

### Icons `app/src/main/res/drawable/` (new)
All icons are 24 dp, viewport 24×24, `fillColor="@android:color/transparent"`, `strokeColor="#FF000000"` (tinted by `Icon`/`Image`), `strokeLineCap="round"`, `strokeLineJoin="round"`. Paths are copied from `design/screens/launchpad.html`:
- `ic_rocket.xml`: stroke 2, paths `M12 3c2.8 1.8 4.5 5 4.5 8.5V15h-9v-3.5C7.5 8 9.2 4.8 12 3z` and `M7.5 12.5l-2 3V18h2M16.5 12.5l2 3V18h-2M10 18.5l2 2.5 2-2.5`.
- `ic_planet.xml`: stroke 2.
  - the circle r=3.2 at (12,12) as two arcs
  - the ring ellipse rx=9.5, ry=4, as two arcs inside `<group android:rotation="-25" android:pivotX="12" android:pivotY="12">`
- `ic_flag.xml`: stroke 2, `M6 21V4M6 4h11l-2.5 4L17 12H6`.
- `ic_sliders.xml`: stroke 2, `M4 7h9M19 7h1M4 17h3M13 17h7`, plus circles r=2.5 at (16,7) and (10,17) as arcs.
- `ic_check.xml`: stroke 3, `M5 12l5 5L20 7` (landed chip; later the set box).

### Modified
- `app/src/main/java/com/liftoff/app/MainActivity.kt` (details in step 5 below).

### Test (new)
- `app/src/test/java/com/liftoff/app/ui/theme/ColorTokensTest.kt` is plain JUnit with no Robolectric.
  - It reads `File("../design/README.md")`. Gradle runs unit tests with the `app/` module as working directory; if the file is not found there, also try `design/README.md`.
  - It parses the color table rows (`` | `name` | `#XXXXXX` | ``) and asserts exactly 12 tokens were found.
  - For each token it asserts the matching `LiftoffColors` value equals the README hex, comparing `color.toArgb()` with `0xFF000000.toInt() or hex`. The name → `LiftoffColors.X` map lives in the test.
  - The README is the only place the hex values are written, so neither side can drift without the test failing.

## Order of work (one commit per step; stage named files only, never `git add -A`)

1. **Merge.**
   - If `design/README.md` exists, skip. Otherwise run `git merge --no-ff origin/design/space-age-icon -m "design: merge space age design and launcher icon"` and add the attribution trailer.
   - Check: `git diff origin/design/space-age-icon HEAD -- design DESIGN.md app/src/main/AndroidManifest.xml app/src/main/res` is empty.
2. **Fonts.**
   - Fetch static instances through the Google Fonts CSS2 API, which returns `.ttf` URLs to a plain curl user agent: `curl -s "https://fonts.googleapis.com/css2?family=Big+Shoulders+Display:wght@700;800;900&family=Work+Sans:wght@400;500;600"`. Then download each `fonts.gstatic.com/….ttf` into the named file.
   - Check each file starts with the TrueType magic `00 01 00 00` and is not HTML.
   - Fetch the license texts from `https://raw.githubusercontent.com/google/fonts/main/ofl/bigshouldersdisplay/OFL.txt` and `…/ofl/worksans/OFL.txt`. If the Big Shoulders path is gone (the family may have moved to `ofl/bigshoulders/`), use that family's `OFL.txt`.
   - Add `*.ttf binary` to `.gitattributes`.
   - Commit `app: bundle big shoulders display and work sans fonts`.
3. **Theme foundation.**
   - Write `Color.kt`, `Shape.kt`, `Type.kt` and `LiftoffTheme.kt`, plus `ColorTokensTest.kt`.
   - Run the gate.
   - Commit `ui: add space age color, type and shape theme`.
4. **Components and icons.**
   - Add the five drawables, then `OffsetShadow.kt`, `Stripes.kt`, `Buttons.kt`, `Headings.kt`, `PatternTrack.kt`, `InkRuledListRow.kt` and `Icons.kt`.
   - Run the gate.
   - Commit `ui: add space age components and stroke icons`.
5. **MainActivity.**
   - Call `enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT), navigationBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT))`. This gives dark system-bar icons on cream even when the phone is in dark mode.
   - Then `setContent { LiftoffTheme { … } }` with a Cream `Box(fillMaxSize)`. Inside it, a Column padded by `WindowInsets.systemBars` holds:
     - `TriStripe()`
     - `Wordmark()` with the mockup's top-bar padding (16 dp top, 20 dp sides)
   - Keep the "M1 replaces this with the navigation shell" comment.
   - Commit `app: show the space age placeholder in main activity`.
6. **Verify.**
   - Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` and confirm it is green.
   - `git ls-files --eol` shows LF for every new text file.
   - Check the built APK's launcher icon by running `aapt2 dump badging` on `app/build/outputs/apk/debug/app-debug.apk`; it must show `@mipmap/ic_launcher`.
   - If `adb devices` lists a device or emulator: install, launch, and take a screenshot showing the placeholder and the home-screen icon.
   - If no device is available, say so plainly in the report. Do not claim the install was checked.

## How the result will be tested

- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
- `ColorTokensTest` pins all 12 tokens to the README table.
- The merge contents match the design branch exactly (the diff check in step 1).
- On a device, if one is available: the dumbbell-satellite icon is on the launcher, and the app opens to a cream screen with the red/mustard/teal stripe and LIFTOFF in Big Shoulders 900.

## Risky or uncertain items

- **Font download.** The CSS2 API returns TTF only to non-browser user agents. If it returns woff2, retry with `-A "Mozilla/4.0"`. If static instances cannot be had, stop and report rather than shipping variable fonts untested.
- **License location.** Anything that is not a font in `res/font/` fails aapt2, which is why the licenses go in `assets/licenses/`.
- **Underline offset.** Compose's `TextDecoration.Underline` has no offset, hence the hand-drawn underline.
- **Offset shadow overflow.** The shadow draws outside the composable's bounds. A full-width button inside 20 dp screen padding has room. Callers placing one flush against an edge must add 4 dp.
- **Compose versions.** BOM 2024.12.01 with Kotlin 2.1.0. Use stable APIs only: `drawBehind`, `Shape.createOutline`, `collectIsPressedAsState`, `enableEdgeToEdge` (activity-compose 1.9.3).

## Decisions

- Original entry: "The brief leaves no behavior open — every aspect of the theme, colors, typography, shapes, composables, and placeholder screen is specified in `design/README.md` or the brief itself. No decisions section entries needed."
  Changed by review: the plan chose to record no decisions; the review records the ones below, because the brief and README leave several behaviors open (press states, button icon position, license location, system bars, how the test pins values), and the owner should see each choice.
- **Composables live in `com.liftoff.app.ui.theme`.** Changed by review: the plan chose a separate `com.liftoff.app.ui.component` package; the review chose `ui.theme`, because the brief says the building blocks go "under `com.liftoff.app.ui.theme`" and DESIGN.md §12 lists no component package.
- **No Gradle file changes.** Changed by review: the plan listed `app/build.gradle.kts` as modified "to add a font dependency"; the review chose not to touch it, because bundled fonts need no dependency and the brief says the existing build configuration must not break.
- **Licenses ship in `app/src/main/assets/licenses/`, one file per family.** Changed by review: the plan chose `res/font/OFL.txt`; the review chose assets, because aapt2 rejects non-font files and upper-case names in `res/font/` (the build would fail), and assets carry the license inside the APK as the OFL asks.
- **Added by review: the button icon can sit before or after the label (`iconAtEnd`), default before.** The brief says "leading icon", but the Launch mockup puts the rocket after LAUNCH while Land puts the flag before LAND. Supporting both lets the screens match the mockups.
- **Added by review: press feedback without ripples.** The red button fill becomes `red_pressed`. The ink button's red shadow becomes `red_pressed`, since the README defines no pressed color for ink. Text buttons turn `red` while pressed (the mockup's link color). All three set `indication = null`.
- **Added by review: the offset shadow takes no layout space,** like CSS `box-shadow`. Callers leave 4 dp of room.
- **Added by review: the underline is drawn by hand, 1 dp, 4 dp below the baseline,** to honor "offset 4", which Compose's underline cannot do.
- **Added by review: the M3 color scheme maps every surface and container slot to a token** (surfaces to cream or paper, containers to the solid accent colors), so stock M3 components never show tonal purple surfaces.
- **Added by review: typography is exposed as named roles (`LiftoffType.*`) and also mapped into M3 `Typography`.** The README's 15 roles do not line up one-to-one with M3's slots. Upper-casing is done by the composables.
- **Added by review: the placeholder draws edge to edge with dark system-bar icons.** Cream runs behind the status bar, and the stripe sits just below it. Without this, Theme.Material.Light's grey status bar would sit above the stripes. This is done in code; the manifest theme attribute is unchanged.
- **Added by review: the placeholder follows the mockup's top bar.** The stripe is at the top and the wordmark at the left, with 16 dp top and 20 dp side padding. It is not centred.
- **Added by review: the color test reads `design/README.md` itself.** It does not copy the hex values into the test, so the code and the README cannot drift apart unnoticed.
- **Added by review: a check-mark drawable (`ic_check`) is added beside the four named icons,** because the landed pattern chip needs it.
- **Added by review: `*.ttf binary` is added to `.gitattributes`,** so `text=auto` never mistakes a font for text.
