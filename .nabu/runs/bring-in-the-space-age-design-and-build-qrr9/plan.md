# Plan — bring in the Space Age design and build the theme

## Approach

1. Merge `origin/design/space-age-icon` into the current branch (its content is final; if `design/README.md` already exists, skip).
2. Download the two Google Fonts from fonts.google.com as ttf files, bundle them in `res/font/`, and include their OFL license text.
3. Create a new package `com.liftoff.app.ui.theme` with MaterialTheme, colors, typography, shapes, and small composables that reproduce the Space Age look — avoiding stock M3 visuals where the design specifies something different.
4. Wire MainActivity into the new theme with a placeholder showing stripes + LIFTOFF wordmark on cream.
5. Add a unit test that pins every color token to its hex value from `design/README.md`.

This fits the codebase because it follows the existing Compose-only, Material 3 foundation already in `build.gradle.kts`, adds only under `app/src` (no pure-package changes), and produces files small enough to verify incrementally.

## Files involved

### From merge
- `design/README.md` — visual design tokens and component specs
- `design/screens/launchpad.html` — Launchpad mockup
- `design/screens/in-flight.html` — In-Flight mockup
- `design/icon/liftoff-icon.svg` — icon source
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — vector drawable from SVG
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon entry point
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` — round variant
- `app/src/main/res/values/ic_launcher_background.xml` — background color (#E3A72F)
- `DESIGN.md` — §9 and §12 pointers (already amended on the branch)
- `app/src/main/AndroidManifest.xml` — icon attributes added

### New files (theme package)
- `app/src/main/java/com/liftoff/app/ui/theme/Color.kt` — color tokens as vals + LightTheme scope
- `app/src/main/java/com/liftoff/app/ui/theme/Type.kt` — Typography with Big Shoulders Display and Work Sans roles
- `app/src/main/java/com/liftoff/app/ui/theme/Shape.kt` — shapes (4dp rounded buttons/checkboxes, square cards)
- `app/src/main/java/com/liftoff/app/ui/theme/LiftoffTheme.kt` — MaterialTheme wrapper

### New files (composables)
- `app/src/main/java/com/liftoff/app/ui/component/Stripes.kt` — 3-band and 2-band stripe composables
- `app/src/main/java/com/liftoff/app/ui/component/Shadows.kt` — offset shadow modifier
- `app/src/main/java/com/liftoff/app/ui/component/Buttons.kt` — PrimaryRedButton, InkButton (red shadow), UnderlinedTextButton
- `app/src/main/java/com/liftoff/app/ui/component/Headings.kt` — Eyebrow + DisplayTitle pair
- `app/src/main/java/com/liftoff/app/ui/component/PatternTrack.kt` — R/L chips with ink line joins
- `app/src/main/java/com/liftoff/app/ui/component/ListRows.kt` — InkRuledListRow
- `app/src/main/java/com/liftoff/app/ui/component/Icons.kt` — vector drawable resources for rocket, ringed planet, flag, sliders

### New files (fonts + license)
- `app/src/main/res/font/big_shoulders_display_bold_700.ttf`
- `app/src/main/res/font/big_shoulders_display_bold_800.ttf`
- `app/src/main/res/font/big_shoulders_display_black_900.ttf`
- `app/src/main/res/font/work_sans_regular_400.ttf`
- `app/src/main/res/font/work_sans_medium_500.ttf`
- `app/src/main/res/font/work_sans_semibold_600.ttf`
- `app/src/main/res/font/OFL.txt` — OFL license text for both fonts

### Modified files
- `app/src/main/java/com/liftoff/app/MainActivity.kt` — renders inside LiftoffTheme, shows themed placeholder
- `app/build.gradle.kts` — add font dependency (no new gradle deps needed; fonts are bundled resources)

### Test file
- `app/src/test/java/com/liftoff/app/ui/theme/ColorTest.kt` — unit test pinning color hex values

## Order of work

1. **Merge `origin/design/space-age-icon`.** One merge commit, content unchanged.
2. **Download and bundle fonts.** Download from Google Fonts (Big Shoulders Display w700/w800/w900, Work Sans w400/w500/w600), place in `res/font/`, copy OFL license to `res/font/OFL.txt`.
3. **Create theme foundation.** Color.kt → Shape.kt → Type.kt → LiftoffTheme.kt (each compiles independently).
4. **Create composables.** Stripes.kt → Shadows.kt → Buttons.kt → Headings.kt → PatternTrack.kt → ListRows.kt → Icons.kt (each builds on the theme foundation).
5. **Update MainActivity.** Replace MaterialTheme with LiftoffTheme; show stripes + LIFTOFF wordmark on cream background.
6. **Write ColorTest.kt.** Pin every token hex value.
7. **Build and test.** Run `./gradlew.sh :app:assembleDebug :app:testDebugUnitTest` and verify green.

## How the result will be tested

- **Build gate:** `./gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
- **Unit test:** ColorTest.kt asserts each color token equals its hex from design/README.md.
- **Install + run:** Install on device/emulator, launch app — shows cream background with red/mustard/teal stripes at top and the LIFTOFF wordmark in Big Shoulders Display 900. The dumbbell-satellite launcher icon is visible on the home screen.

## Risky or uncertain items

- **Font availability.** Big Shoulders Display and Work Sans must be downloaded from Google Fonts before committing. If download fails, the build will not link the typefaces. Mitigation: download fonts during plan execution, commit ttf files.
- **Vector drawable conversion.** `ic_launcher_foreground.xml` is a vector drawable converted from SVG on the design branch. The merge brings it in verbatim; no conversion work needed.
- **Compose version compatibility.** The project uses Compose BOM 2024.12.01 with Kotlin 2.1.0 and Compose compiler plugin. All composables use stable APIs only (no alpha/beta).
- **No dark theme support.** The design is light-only; the theme explicitly does not define a dark color scheme. This is correct per the brief.

## Decisions

The brief leaves no behavior open — every aspect of the theme, colors, typography, shapes, composables, and placeholder screen is specified in `design/README.md` or the brief itself. No decisions section entries needed.
