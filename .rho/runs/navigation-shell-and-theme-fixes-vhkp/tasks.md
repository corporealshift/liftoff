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

- [x] Build the navigation shell with placeholder screens
  Create these files under com.liftoff.app.ui:
  - ShellNav.kt: pure-Kotlin enum Tab, data class ShellNav with select(), openMissionControl(), back(), encode(), decode(). No Android/Compose imports.
  - PlaceholderScreen.kt: shared Column with verticalScroll, eyebrow+display title via TitleBlock, one line of bodyLarge text, padded 18dp top / 20dp horizontal.
  - launchpad/LaunchpadScreen.kt, mission/MissionScreen.kt, landed/LandedScreen.kt: each uses PlaceholderScreen with §2 vocabulary copy.
  - control/MissionControlScreen.kt: back button (44dp outlined), TitleBlock(eyebrow="Settings", title="Mission Control"), empty MissionControlContent() slot with "Settings will appear here."
  - LiftoffShell.kt: the shell composable — stripe, top bar (wordmark + sliders button), bottom bar (3 tabs with ink background, mustard/rule colors), content switch, BackHandler, rememberSaveable state saver, nav-bar icon contrast SideEffect.
  Done when: All files compile together as a coherent shell. LiftoffShell shows the full UI: stripe, top bar, tab bar, and correct screen for each tab.

- [x] Wire MainActivity and update ARCHITECTURE.md
  In MainActivity.kt: keep enableEdgeToEdge unchanged, replace setContent body with LiftoffTheme { LiftoffShell() }, remove stale comment and unused imports. Update ARCHITECTURE.md: root row says "MainActivity (hosts the navigation shell)", ui row marked existing with description of navigation shell/placeholder screens/Mission Control stub.
  Done when: MainActivity compiles with no unused imports, ARCHITECTURE.md updated per spec.

- [x] Run the gate and verify everything passes
  Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. All existing tests must still pass (FontResourcesTest with 6 TTFs, ThemePackageTest with 11 files in ui/theme, TypographyTest), plus the new ShellNavTest and TypographyTest.everyMaterialRoleUsesABundledFamily.
  Done when: gradlew exits 0 with all tests passing.

## Blockers from the final review

- [x] Pad the ink bottom bar for the system navigation bar
  app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt, bottom-bar Row (around line 115): MainActivity turns on enableEdgeToEdge, but nothing in the shell applies navigationBarsPadding() or any other window-inset padding to the bottom bar. On a real device the system nav bar (three buttons or the gesture handle) draws on top of the Launchpad/Mission/Landed icons and labels, so they are hard to read and the bottom edge competes with system gestures. The plan called for background(Ink), then navigationBarsPadding(), then the 8/8/14 dp padding, so the ink runs under the system bar while the items sit above it. The current code has only an 8 dp padding inside each item. Robolectric reports no insets, so the tests can't catch this. Mission Control's scroll column has the same missing nav-bar padding, which the plan also called for.
- [x] Give the sliders button the 2 dp ink border the brief specifies
  LiftoffShell.kt lines 90-102: the sliders button is a plain OutlinedButton with no border argument, so it gets Material's default 1 dp border in colorScheme.outline (Ink). The brief says '44 dp outlined sliders button ... (2 dp ink border, sliders icon)'. Pass border = BorderStroke(2.dp, Ink). The Mission Control back button has the opposite problem: it adds .border(2.dp, Ink, ButtonShape) on top of the default 1 dp border. Pass the border argument there too instead of stacking two borders.
- [x] Use the existing Wordmark composable in the top bar
  LiftoffShell.kt line 88 renders Text("LIFTOFF", style = MaterialTheme.typography.headlineMedium). That is Big Shoulders at W800, Material's default 28 sp and no letter spacing, not the design's wordmark (24 sp, W900, 0.14em tracking, which LiftoffType.wordmark and the existing TypographyTest pin). The brief asks for 'the LIFTOFF wordmark' and lists Wordmark among the existing pieces to build on, and the plan says Wordmark(). Replace the Text with Wordmark().
