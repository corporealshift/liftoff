# Tasks: Navigation shell and theme fixes

- [x] Fix every Material 3 typography role to use a bundled font
  Remove @Composable from LiftoffTypography() in ui/theme/Type.kt so a JVM test can call it. Keep the 8 existing role assignments unchanged. Add the 7 missing roles (displaySmall, headlineMedium, headlineSmall, titleMedium, titleSmall, bodySmall, labelMedium) using base.<role>.copy(fontFamily = ...) — Big Shoulders at W800 for display/headline/title roles, Work Sans for bodySmall and labelMedium. Add a one-line comment explaining why.
  Done when: LiftoffTypography() has all 15 Material 3 roles filled with either BigShoulders or WorkSans, no @Composable annotation, existing 8 roles unchanged.

- [x] Add TypographyTest.everyMaterialRoleUsesABundledFamily
  Create ui/theme/TypographyTest.kt (JVM test). It calls LiftoffTypography() and checks all 15 roles: fontFamily is not null, not FontFamily.Default, and is BigShoulders or WorkSans. The failure message names the role. It also asserts the newly filled roles are in the right group (display/headline/title → BigShoulders; bodySmall, labelMedium → WorkSans).
  Done when: The new test passes on its own, and existing TypographyTest tests still pass.

- [x] Make preview composables private with @Preview
  For each of the 9 ...Preview functions across Buttons.kt, Headings.kt, Icons.kt, InkRuledListRow.kt, PatternTrack.kt, and Stripes.kt: add @Preview (import androidx.compose.ui.tooling.preview.Preview) and change visibility to private. Leave bodies unchanged.
  Done when: All 9 previews have @Preview and are private. No test references broken (grep confirms none exist).

- [ ] Build the navigation shell with placeholder screens
  Create these files under com.liftoff.app.ui:
  - ShellNav.kt: pure-Kotlin enum Tab, data class ShellNav with select(), openMissionControl(), back(), encode(), decode(). No Android/Compose imports.
  - PlaceholderScreen.kt: shared Column with verticalScroll, eyebrow+display title via TitleBlock, one line of bodyLarge text, padded 18dp top / 20dp horizontal.
  - launchpad/LaunchpadScreen.kt, mission/MissionScreen.kt, landed/LandedScreen.kt: each uses PlaceholderScreen with §2 vocabulary copy.
  - control/MissionControlScreen.kt: back button (44dp outlined), TitleBlock(eyebrow="Settings", title="Mission Control"), empty MissionControlContent() slot with "Settings will appear here."
  - LiftoffShell.kt: the shell composable — stripe, top bar (wordmark + sliders button), bottom bar (3 tabs with ink background, mustard/rule colors), content switch, BackHandler, rememberSaveable state saver, nav-bar icon contrast SideEffect.
  Done when: All files compile together as a coherent shell. LiftoffShell shows the full UI: stripe, top bar, tab bar, and correct screen for each tab.

- [ ] Wire MainActivity and update ARCHITECTURE.md
  In MainActivity.kt: keep enableEdgeToEdge unchanged, replace setContent body with LiftoffTheme { LiftoffShell() }, remove stale comment and unused imports. Update ARCHITECTURE.md: root row says "MainActivity (hosts the navigation shell)", ui row marked existing with description of navigation shell/placeholder screens/Mission Control stub.
  Done when: MainActivity compiles with no unused imports, ARCHITECTURE.md updated per spec.

- [ ] Run the gate and verify everything passes
  Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. All existing tests must still pass (FontResourcesTest with 6 TTFs, ThemePackageTest with 11 files in ui/theme, TypographyTest), plus the new ShellNavTest and TypographyTest.everyMaterialRoleUsesABundledFamily.
  Done when: gradlew exits 0 with all tests passing.
