# Plan: Navigation shell and theme fixes

## Approach

Replace MainActivity's placeholder with a navigation shell built from plain Compose state. Do not add a navigation library. The navigation logic goes in a small pure-Kotlin state class that a JVM test can cover. The composables go under `com.liftoff.app.ui`, following the DESIGN.md §12 layout (`ui/launchpad`, `ui/mission`, `ui/landed`, `ui/control`), so the next briefs have an obvious place to fill in. Separately, give every Material 3 typography role a bundled font, turn the dead `...Preview` composables into real private previews, and update ARCHITECTURE.md.

Facts in the repo that this plan depends on:
- **The fonts are already bundled.** `app/src/main/res/font/` has the six TTFs, and `FontResourcesTest` asserts that there are **exactly 6**. Do not download or add any font files.
- **`ThemePackageTest` asserts that `ui/theme` has exactly 11 `.kt` files.** No new file may go in `ui/theme`.
- No test refers to any `...Preview` composable (grep confirms this), so removing or renaming them breaks no test.
- `androidx.compose.ui:ui-tooling-preview` is already an `implementation` dependency, and `ui-tooling` is a `debugImplementation` dependency.
- `BackHandler` comes from `androidx.activity:activity-compose` (1.9.3, already declared). `Icons.AutoMirrored.Filled.ArrowBack` comes from `material-icons-extended` (already declared).
- `MainActivityTest` contains only constant assertions. It keeps passing unchanged, so leave it alone.

## Files involved

| File | What changes |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/theme/Type.kt` | `LiftoffTypography()` sets all 15 roles. Drop its `@Composable` annotation (it reads no composition locals) so a JVM test can call it. |
| `app/src/main/java/com/liftoff/app/ui/theme/{Buttons,Stripes,Headings,PatternTrack,Icons,InkRuledListRow}.kt` | Each `...Preview` composable gets `@Preview` and becomes `private`. |
| `app/src/test/java/com/liftoff/app/ui/theme/TypographyTest.kt` | Add a test that every role uses a bundled family. The existing tests stay as they are. |
| `app/src/main/java/com/liftoff/app/ui/ShellNav.kt` (new) | Pure Kotlin: `enum class Tab`, `data class ShellNav`, transitions, encode/decode. No Android or Compose imports. |
| `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` (new) | The shell composable: stripe, top bar, bottom bar, content switch, `BackHandler`, saveable state, nav-bar icon appearance. |
| `app/src/main/java/com/liftoff/app/ui/PlaceholderScreen.kt` (new) | Shared placeholder layout: eyebrow, display title and one line. |
| `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (new) | `LaunchpadScreen()` placeholder. |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` (new) | `MissionScreen()` placeholder. |
| `app/src/main/java/com/liftoff/app/ui/landed/LandedScreen.kt` (new) | `LandedScreen()` placeholder. |
| `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt` (new) | Full-screen Mission Control: back button, title, and an empty `MissionControlContent()` slot. |
| `app/src/main/java/com/liftoff/app/MainActivity.kt` | `setContent { LiftoffTheme { LiftoffShell() } }`. Remove the "M1 replaces this" comment. |
| `app/src/test/java/com/liftoff/app/ui/ShellNavTest.kt` (new) | JVM tests of the navigation logic. |
| `ARCHITECTURE.md` | The `ui` row is marked existing. The root row no longer calls MainActivity a placeholder. |

## Order of work

1. **Typography (`Type.kt`).**
   - Remove `@Composable` from `LiftoffTypography()`. `LiftoffTheme` still calls it unchanged.
   - Keep the 8 existing role assignments exactly as they are.
   - Add the 7 missing roles. Each one starts from the Material 3 default style and swaps in our family, so sizes and line heights stay sensible. With `val base = Typography()`:
     - `displaySmall`, `headlineMedium`, `headlineSmall`, `titleMedium`, `titleSmall` = `base.<role>.copy(fontFamily = BigShoulders, fontWeight = FontWeight.W800)`. Only 700–900 are bundled, so W800 is set explicitly rather than falling back to the closest weight.
     - `bodySmall`, `labelMedium` = `base.<role>.copy(fontFamily = WorkSans)`. Their default weights, 400 and 500, are bundled.
   - Add one short comment explaining why the missing roles are filled: so text fields and dialogs never fall back to the system font.
2. **Typography test.** In `TypographyTest`, add `everyMaterialRoleUsesABundledFamily()`. It calls `LiftoffTypography()` and builds a `name to style` list of all 15 roles (displayLarge … labelSmall). For each role it asserts that `fontFamily` is not null, is not `FontFamily.Default`, and is one of `BigShoulders` or `WorkSans`. The failure message names the role. It also asserts that the 7 newly filled roles are in the right group: the display, headline and title roles are `BigShoulders`, and `bodySmall` and `labelMedium` are `WorkSans`.
3. **Preview composables.** For each of the 9 `...Preview` functions (HeadingsPreview, TriStripePreview, DuoStripePreview, PrimaryButtonPreview, InkButtonPreview, UnderlinedTextButtonPreview, PatternTrackPreview, IconsPreview, InkRuledListRowPreview), add `@Preview` (import `androidx.compose.ui.tooling.preview.Preview`) and change it to `private`. Leave the bodies unchanged. No test needs updating, but run a grep to confirm again before moving on.
4. **Nav state (`ui/ShellNav.kt`, pure Kotlin).**
   - `enum class Tab { Launchpad, Mission, Landed }`
   - `data class ShellNav(val tab: Tab = Tab.Launchpad, val missionControlOpen: Boolean = false)` with:
     - `fun select(tab: Tab): ShellNav = copy(tab = tab, missionControlOpen = false)`
     - `fun openMissionControl(): ShellNav = copy(missionControlOpen = true)`
     - `fun back(): ShellNav?` behaves as follows. If Mission Control is open, it closes it and keeps `tab`. If `tab != Launchpad`, it returns `ShellNav()`. Otherwise it returns `null`, which means the system handles back and the app leaves.
     - `fun encode(): String` (for example `"Mission|1"`) and `companion fun decode(s: String): ShellNav`. Malformed input or an unknown tab decodes to `ShellNav()`.
5. **Placeholder screens.**
   - `PlaceholderScreen(eyebrow, title, line)` is a `Column` with `verticalScroll`, padded 18 dp top and 20 dp horizontal (as in the mockup). It holds a `TitleBlock(eyebrow, title)`, a 12 dp spacer, then `Text(line, style = MaterialTheme.typography.bodyLarge, color = Muted)`.
   - Copy, in §2 vocabulary:
     - Launchpad: eyebrow "This week", title "Launchpad", line "Your next Flight Plan will appear here."
     - Mission: eyebrow "This week", title "Mission", line "This week's pattern and sorties will appear here."
     - Landed: eyebrow "History", title "Landed", line "Landed sorties will appear here, newest first."
6. **Mission Control (`ui/control/MissionControlScreen.kt`).**
   - `MissionControlScreen(onBack: () -> Unit)` is laid out as follows:
     - a top row (16 dp top and 20 dp side padding, matching the tab top bar) holding a 44 dp outlined back button on the left: 2 dp `Ink` border, `ButtonShape` (4 dp), `Icons.AutoMirrored.Filled.ArrowBack` tinted `Ink`, `contentDescription = "Back"`, calling `onBack`;
     - `TitleBlock(eyebrow = "Settings", title = "Mission Control")`;
     - then `MissionControlContent()`.
   - `MissionControlContent()` is a private composable with a one-line comment saying that the settings and equipment management land here in the next brief. For now it renders one line in `Muted`: "Settings will appear here."
   - The column scrolls vertically and pads for the navigation bar.
7. **Shell (`ui/LiftoffShell.kt`).**
   - State: `var nav by rememberSaveable(stateSaver = Saver<ShellNav, String>({ it.encode() }, { ShellNav.decode(it) })) { mutableStateOf(ShellNav()) }`.
   - `BackHandler(enabled = nav.back() != null) { nav.back()?.let { nav = it } }`. When it is disabled (on Launchpad with Mission Control closed), the activity's default back runs and the app leaves.
   - Layout: a `Column(Modifier.fillMaxSize().background(Cream))`, with `statusBarsPadding()` at the top and then `TriStripe()`.
     - When `nav.missionControlOpen`, the rest is `MissionControlScreen(onBack = { nav = nav.back() ?: nav })`.
     - Otherwise the rest is:
       - a top bar `Row` (padding 16 dp top, 20 dp sides, `SpaceBetween`, vertically centered) with `Wordmark()` on the left. On the right is a 44 dp outlined icon button: 2 dp `Ink` border, `ButtonShape`, `LiftoffIcons.sliders()` at 22 dp tinted `Ink`, `contentDescription = "Mission Control"`. Its click sets `nav = nav.openMissionControl()`.
       - a `Box(Modifier.weight(1f))` that shows the current tab's screen;
       - the bottom bar: a `Row` with `background(Ink)`, then `navigationBarsPadding()`, then padding 8 dp sides, 8 dp top and 14 dp bottom. It holds three equal-weight items. Each item is a `Column` (centered, 4 dp gap, 6 dp vertical padding, at least 44 dp tall) holding a 24 dp `Icon` (rocket, planet, flag) over `Text(label.uppercase(), style = LiftoffType.barLabel())`. Icon and text are both tinted `Mustard` when active and `Rule` otherwise. Each item uses `Modifier.selectable(selected, role = Role.Tab) { nav = nav.select(tab) }`, and the bar row uses `selectableGroup()`.
   - Navigation-bar icons: the ink bar runs under the gesture/nav bar, so the system nav icons must be light on tabs and dark on cream Mission Control. With `val view = LocalView.current`, add `SideEffect { (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = nav.missionControlOpen } }` and a one-line comment explaining why.
8. **MainActivity.** Keep `enableEdgeToEdge(...)` exactly as it is. Replace the body of `setContent` with `LiftoffTheme { LiftoffShell() }`, remove the stale comment and drop the unused imports. The shell needs nothing from `AppContainer` yet, so don't wire it in.
9. **ARCHITECTURE.md.**
   - Root row: "`MainActivity` (hosts the navigation shell), `AppContainer.kt`, app entry point."
   - `ui` row, "Exists yet?" column: "✅ (navigation shell, placeholder screens, Mission Control stub, `ui/theme`)".
   - Change nothing else.
10. **Gate.** Run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. All existing tests must still pass, especially `FontResourcesTest` (6 TTFs), `ThemePackageTest` (11 files in `ui/theme`) and `TypographyTest`.
11. **Commits.** Stage named files only, with LF endings. Suggested commits:
    - `theme: give every material typography role a bundled font`
    - `theme: make preview composables private previews`
    - `ui: add navigation shell with placeholder screens`
    - `docs: mark ui package as existing`

## Testing

### `ShellNavTest` (JVM, `com.liftoff.app.ui`)
- The initial state is Launchpad with Mission Control closed.
- `select(Mission)` and `select(Landed)` set the tab. Selecting the current tab changes nothing.
- `openMissionControl()` from each tab keeps that tab and opens Mission Control.
- `back()` from Mission Control opened on Landed gives Landed with Mission Control closed. One more `back()` gives Launchpad.
- `back()` from Mission and from Landed gives Launchpad.
- `back()` from Launchpad with Mission Control closed is `null` (the app leaves).
- `decode(encode(x)) == x` for every tab with Mission Control open and closed. This covers surviving rotation. `decode("garbage")` gives `ShellNav()`.

### `TypographyTest.everyMaterialRoleUsesABundledFamily` (JVM)
As described in step 2: all 15 roles are `BigShoulders` or `WorkSans`, never null or `FontFamily.Default`.

### Manual (on device, if one is available)
- The app opens on Launchpad. The stripe, top bar and ink bottom bar are visible, with the active item in mustard and the others in rule.
- Every tab can be reached. The sliders button opens Mission Control, which has no bottom bar. Both the back button and system back return to the tab you came from.
- Back from Mission or Landed goes to Launchpad. Back from Launchpad leaves the app.
- Rotating keeps the selected tab, and keeps Mission Control open if it was open.
- The system nav-bar icons are legible over the ink bar and over cream on Mission Control.

## Risk and uncertainty

- **R in JVM tests.** The new typography test reads `BigShoulders` and `WorkSans`, which are built from `R.font.*` IDs. Building a `ResourceFont` needs no `Context`, and unit tests compile against the app's `R` (`isIncludeAndroidResources = true`). If this fails anyway, fall back to running the test under Robolectric (already a test dependency).
- **The guard tests on file counts.** `ThemePackageTest` (11 files in `ui/theme`) and `FontResourcesTest` (6 TTFs) fail if files are added there. This plan adds none.
- **The 68 sp display title** wraps on "MISSION CONTROL". The title's line height (0.9em) handles two lines, and the screens scroll in landscape.

## Decisions

- **Shell placement.** Added by review. The shell and screens live under `com.liftoff.app.ui` in the §12 subpackages (`launchpad/`, `mission/`, `landed/`, `control/`). The navigation logic is a pure-Kotlin `ShellNav` in `ui/ShellNav.kt`. They are not all in `MainActivity.kt`, because the next briefs fill these screens in, ARCHITECTURE.md is to mark `ui` as existing, and `ui/theme` is pinned at 11 files by `ThemePackageTest`.
- **No new fonts.** Added by review. The six bundled TTFs are reused. The original plan's "download fonts" step was dropped because the fonts already exist and `FontResourcesTest` requires exactly 6.
- **The missing typography roles.** Added by review. The 7 unset roles take the Material 3 default size and line height, with the family swapped in. The display, headline and title roles use Big Shoulders at W800, because only 700–900 are bundled. `bodySmall` and `labelMedium` use Work Sans at their default weights. `LiftoffTypography()` loses `@Composable` so a JVM test can call it.
- **Preview composables.** Added by review. They get `@Preview` and become `private`, rather than being deleted, so the design can still be checked in the IDE. The tooling dependency is already declared.
- **Back behaviour.** Added by review. Back from Mission Control returns to the tab it was opened from, even when that is Mission or Landed, and a second back then goes to Launchpad. Reselecting the current tab does nothing.
- **Rotation.** Added by review. Both the selected tab and whether Mission Control is open survive rotation, so rotating the phone in Mission Control doesn't throw the user out of it.
- **Mission Control layout.** Added by review. It keeps the stripe and replaces the wordmark top bar with a 44 dp outlined back button. That button matches the sliders button and uses the Material auto-mirrored back arrow, because `LiftoffIcons` has no back glyph. The eyebrow is "Settings" (§2: Mission Control is the settings screen). The empty `MissionControlContent()` slot is where the next brief adds its content.
- **System nav-bar icons.** Added by review. The ink bottom bar extends under the system navigation bar. Its icons are switched to light on the tabs and dark on Mission Control's cream, so they stay legible in both places.
- **Placeholder copy.** Added by review. Launchpad: "This week" / "Launchpad" / "Your next Flight Plan will appear here." Mission: "This week" / "Mission" / "This week's pattern and sorties will appear here." Landed: "History" / "Landed" / "Landed sorties will appear here, newest first."
- The brief leaves no behaviors open that someone using this could notice. All navigation, styling, and typography choices are specified in the brief. No decisions section entries needed.
  Changed by review: the plan chose to record no decisions; the review chose to record the entries above, because the brief leaves visible behaviors open (back from Mission Control to a non-start tab, whether Mission Control survives rotation, the Mission Control layout and back affordance, placeholder copy, the sizes and weights of the new typography roles, keeping or deleting previews, and nav-bar icon contrast over the ink bar).
