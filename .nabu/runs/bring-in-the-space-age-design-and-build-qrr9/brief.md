# Bring in the Space Age design and build the theme

The owner chose a visual design for Liftoff, direction B 'Space Age'. It is on the pushed branch `origin/design/space-age-icon` and is not on this branch yet. That branch adds `design/` (README.md with color, type, shape and component specs; screens/launchpad.html and screens/in-flight.html, static HTML mockups where 1 px = 1 dp; icon/liftoff-icon.svg), a DESIGN.md amendment pointing §9 and §12 at `design/`, and the dumbbell-satellite adaptive launcher icon resources wired into AndroidManifest.xml. Merge that branch into this one with its content unchanged. If `design/README.md` already exists here, that step is already done.

Then build the look described in `design/README.md` as reusable Compose building blocks under `com.liftoff.app.ui.theme`, which later screens will use:
- A custom MaterialTheme. It must have a color scheme built from the README's tokens (cream, paper, sand, ink, red, red_pressed, mustard, teal, teal_light, muted, rule, white) with the token values also available by name, typography covering the README's roles, and shapes (4 dp corners on buttons and checkboxes, square cards). Light theme only.
- Fonts: Big Shoulders Display (700, 800, 900) and Work Sans (400, 500, 600), bundled in `res/font/` from Google Fonts. Both are under the Open Font License; include the license text in the repo.
- Small composables for what Material 3 doesn't provide:
  - the 3-band (red/mustard/teal, 6 dp) and 2-band (mustard/red, 5 dp) stripes
  - a solid offset shadow (4 dp right and down, no blur, color configurable)
  - the primary red button (2 dp ink border, ink offset shadow, red_pressed when pressed, display-font label, optional leading icon) and the ink variant with a red shadow (used later for Land)
  - the underlined text button
  - an eyebrow and display-title pair
  - the pattern track: one chip per R/L in landed, current or upcoming style, joined by a 2 dp ink line
  - an ink-ruled list row
  - the 24 dp stroke icons for rocket, ringed planet, flag and sliders, as vector drawables from the mockup paths or the closest Material Symbols
  Do not use stock M3 visuals (tonal surfaces, pill buttons, ripples on cream) where the design shows something else.
- MainActivity should render inside the new theme and show a simple themed placeholder: the stripes and the LIFTOFF wordmark on cream. The navigation shell comes in a later brief.

Must not break: the build gate, the existing build configuration and manifest attributes, and the CLAUDE.md conventions (LF endings, commit message style, staging named files only). Pure packages must not gain Android imports.

Done when:
- `design/` and the launcher icon are on this branch.
- The app builds, installs with the dumbbell-satellite icon and shows the themed placeholder.
- A unit test pins the color token values to the README's hex values.
- The gate `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
