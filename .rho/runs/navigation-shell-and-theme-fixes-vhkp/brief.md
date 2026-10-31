# Navigation shell and theme fixes

Replace MainActivity's placeholder with Liftoff's real navigation shell, in the Space Age look from `design/README.md` and DESIGN.md §9. Fix two small gaps in the theme. This brief is deliberately small. An earlier run that tried to build the shell together with the whole settings screen was stopped before it finished. Build only what is listed here. The settings screen's content and equipment management come in the next briefs.

Build on what's already in the repo:
- the theme and shared composables in `com.liftoff.app.ui.theme`: `LiftoffTheme`, `TriStripe`, `Wordmark`, `Eyebrow`/`DisplayTitle`/`TitleBlock`, the offset-shadow helpers and `LiftoffIcons` (rocket, planet, flag, sliders)
- the color tokens (Ink, Mustard, Rule, Cream and the rest)
- the `AppContainer` owned by `LiftoffApplication`

**Shell.** MainActivity renders, inside `LiftoffTheme` on cream:
- the three-band stripe at the top
- a top bar with the LIFTOFF wordmark on the left and a 44 dp outlined sliders button on the right (2 dp ink border, sliders icon). The button opens Mission Control.
- an ink bottom bar with three items, Launchpad (rocket), Mission (planet) and Landed (flag). Each item is an icon over an uppercase display-font label. The active item is mustard and the others use the 'rule' color.
Launchpad is the start destination. Launchpad, Mission and Landed are placeholder screens for now: an eyebrow and display title in the design style with one short line of text. Later briefs fill them in.

Mission Control is a separate full screen reached from the sliders button. It has no bottom-bar item. For now it shows an eyebrow and the title 'MISSION CONTROL' with a back affordance. The next brief fills it in, so leave a clear place for that content.

Navigation is plain Compose state. No navigation library is declared, so don't add one. System back behaves as people expect: from Mission Control it returns to the tab you came from, from a non-start tab it returns to Launchpad, and from Launchpad it leaves the app. The selected tab survives rotation.

**Theme fixes.**
- Every Material 3 typography role must use a bundled font. Today `LiftoffTypography()` in `ui/theme/Type.kt` sets only 8 roles. displaySmall, headlineMedium, headlineSmall, titleMedium, titleSmall, bodySmall and labelMedium still fall back to the system font, so text fields and dialogs would show it. Display, headline and title roles use Big Shoulders Display. Body and label roles use Work Sans. Keep the 8 roles that are already set as they are.
- The public `...Preview` composables in `ui/theme` (PrimaryButtonPreview, TriStripePreview and the others) have no `@Preview` annotation, so they are dead public API. Either add `@Preview` and make them private, or remove them. Update any test that refers to them.

UI strings follow the DESIGN.md §2 vocabulary. Must not break: the data layer, settings store, AppContainer and their tests, the launcher icon, the existing theme tests, and the build gate. Follow CLAUDE.md: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- The app opens to the shell on the Launchpad tab. All three tabs and the Mission Control screen are reachable, and back behaves as described.
- A JVM test covers the navigation state logic: tab selection, opening Mission Control, and what back does from each place.
- A test checks that every Material 3 typography role's font family is Big Shoulders Display or Work Sans, and never the default family.
- ARCHITECTURE.md's package table marks `ui` as existing, and the root package's row no longer calls MainActivity a placeholder.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
