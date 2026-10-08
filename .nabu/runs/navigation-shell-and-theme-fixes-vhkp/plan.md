# Plan: Navigation shell and theme fixes

## Approach

Replace MainActivity's placeholder with a Compose-only navigation shell (no nav library), styled to the Space Age design. The shell consists of: three-band stripe, top bar (wordmark + sliders button), bottom bar (three tab items), and four screens (Launchpad, Mission, Landed — all placeholder; Mission Control with back affordance). Simultaneously fix the typography so every Material 3 role uses a bundled font, clean up the dead-preview composables, and update ARCHITECTURE.md.

This builds directly on existing composables (`LiftoffTheme`, `TriStripe`, `Wordmark`, icon set, color tokens) and follows the repo's convention of no DI and plain Compose state — no navigation library is added.

## Files involved

| File | What changes |
|---|---|
| `app/src/main/java/com/liftoff/app/MainActivity.kt` | Replace placeholder with full shell: `LiftoffNavShell` composable + nav-state data class + back-stack logic. |
| `app/src/main/java/com/liftoff/app/ui/theme/Type.kt` | Add bundled fonts; set all 14 Material 3 roles to Big Shoulders Display or Work Sans. |
| `app/src/main/java/com/liftoff/app/ui/theme/*.kt` (preview composables) | Remove `@Preview` annotation and make preview composables private, or remove them entirely. Update any test referencing them. |
| `app/src/test/java/…/*Test*.kt` | Update tests that reference the removed preview API. |
| `ARCHITECTURE.md` | Mark `ui` package as "existing"; change root row to say MainActivity is the navigation shell (not placeholder). |
| `app/src/main/res/font/` (new) | Bundle Big Shoulders Display and Work Sans font files. |

## Order of work

1. **Bundle fonts.** Download Big Shoulders Display (.ttf, weights 700–900) and Work Sans (.ttf, weights 400–600) into `app/src/main/res/font/`. This is needed before the Type.kt fix compiles.
2. **Fix `Type.kt`.** Update `LiftoffTypography()` to assign font families for all 15 Material 3 roles (7 missing: displaySmall, headlineMedium, headlineSmall, titleMedium, titleSmall, bodySmall, labelMedium): display/headline/title roles → Big Shoulders Display; body/label roles → Work Sans. Keep existing 8 roles unchanged.
3. **Clean up preview composables.** Remove `@Preview` annotations and make the preview composables private (or remove them). Update any test that references them.
4. **Build navigation shell in MainActivity.kt.** Add nav-state data class, bottom bar composable, top bar, stripe, placeholder screens, Mission Control screen, and back-press handling — all plain Compose state with a custom `NavHost` implemented inline via `remember { mutableStateOf(...) }`.
5. **Update ARCHITECTURE.md.** Mark `ui` as existing; update root package row.
6. **Run the build gate.** `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Fix any compilation errors from preview removal or font changes.

## Testing

### JVM navigation-state test
A new test class (e.g. `NavStateTest.kt` in `app/src/test/java/com/liftoff/app/`) exercises the nav-state data class in pure Kotlin:
- Initial state is Launchpad, no back stack, Mission Control closed.
- Selecting a tab sets current screen and resets back stack.
- Opening Mission Control pushes onto the back stack with source tab recorded.
- Back from Mission Control pops to the source tab.
- Back from Launchpad returns `NavigateUp` (app exit).
- Tab selection survives a config-change-like reset (re-create state from saved instance).

### Typography font test
A new JVM test (e.g. `TypographyFontTest.kt`) constructs `LiftoffTypography()` and asserts that every Material 3 typography role's `fontFamily` is neither null nor the platform default. It checks all 14 roles: displayLarge through labelSmall, confirming each resolves to Big Shoulders Display or Work Sans.

### Manual / device test
- App opens to Launchpad tab with shell visible (stripe, top bar, bottom bar).
- All three tabs are tappable; active item is mustard, inactive is rule.
- Sliders button opens Mission Control; back returns to previous tab.
- Back from Launchpad exits the app.
- Tab selection survives rotation.

### Build gate
`./gradlew :app:assembleDebug :app:testDebugUnitTest` passes end-to-end.

## Risk and uncertainty

- **Font licensing.** Big Shoulders Display is Open Font License (confirmed by design/README.md). Need to verify the exact .ttf files from Google Fonts match the project's OFL requirements. No risk if they do.
- **Preview composable removal.** If other tests or modules import them, removing them breaks compilation. The plan checks all references before deleting.
- **No navigation library.** Implementing a custom `NavHost` is straightforward for four screens, but edge cases around back-stack semantics need care. The test covers the critical paths.
- **Font file size.** Two font families × several weights ≈ 1–2 MB total. This fits within Android's default resource limits and does not affect APK size significantly.

## Decisions

The brief leaves no behaviors open that someone using this could notice. All navigation, styling, and typography choices are specified in the brief. No decisions section entries needed.
