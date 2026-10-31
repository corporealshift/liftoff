# Mission Control settings

Fill in the Mission Control screen so it edits every setting in DESIGN.md §8. Read DESIGN.md §2, §8 and §9 and `design/README.md` first.

Build on what's already in the repo:
- the navigation shell in MainActivity, where the top bar's sliders button opens a Mission Control screen that currently shows only its title. Put the content there.
- `SettingsStore` in `com.liftoff.app.settings`, reached through `AppContainer`. It has an observable `settings` flow and one setter per field. The setters already reject a port outside 1–65535, a pattern that isn't 1–7 R/L characters, and a non-positive sortie length or history window.
- the theme composables in `com.liftoff.app.ui.theme`: `TitleBlock`, `InkRuledListRow`, `PatternTrack`, the buttons and the offset-shadow helpers

Mission Control isn't mocked up. Build it from the design's parts: eyebrow and display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, and square or 4 dp corners. Don't use stock M3 tonal surfaces or pill shapes. It scrolls and edits:
- **Connection:** daemon host, port and token. The token is masked, with a way to reveal it.
- **Coach:** the coach workspace path.
- **Mission:**
  - the default pattern, edited as R/L chips: tap to toggle, add and remove, 1–7 sorties
  - sortie length in minutes
  - history window in days
- **Units:** weight lb/kg and distance mi/km.
- **Runs:** the 'Generate run plans' toggle.
- **Objectives and constraints:** two free-text fields.

Edits are saved to the settings store and survive an app restart. Invalid input is not saved, and a clear message is shown next to the field: a port that isn't a number in 1–65535, a non-positive or non-numeric length or window, or a pattern outside 1–7. The pattern editor must not let the pattern drop below 1 or go above 7 chips. When the screen opens it shows the stored values.

Put the validation and screen state in plain Kotlin that a JVM test can drive without Compose UI, for example a state holder or ViewModel built from the settings store. Compose UI tests are not required. Leave room in the screen for an Equipment section, which the next brief adds. Don't add 'Test connection' or export/import yet, and don't add placeholders for them that don't work.

UI strings follow the §2 vocabulary: say 'sortie length', never 'session'. Must not break: the shell and its back behaviour, the settings store and data layer and their tests, the theme tests, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Every §8 setting can be edited from Mission Control and keeps its new value after the app restarts.
- JVM tests cover loading the stored values, saving each kind of field, rejecting each invalid input with the stored value left unchanged, and the pattern editor's 1–7 limits.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
