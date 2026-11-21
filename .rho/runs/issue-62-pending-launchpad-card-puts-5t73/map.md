# Map — issue 62 (PENDING Launchpad title runs long in display type)

## Where the reported title comes from

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` — `deriveSortieCard` (182).
  The PENDING title is `current.focus ?: "Run"/"Lift"` (193–196): the outline's own focus, which the
  coach writes in long all-caps. The PLANNED title is `plan.plan.title` (209) — the short
  "LOWER — SQUAT & HINGE" the brief contrasts. `LaunchpadState.Pending` (52–67) carries
  `eyebrow, title, chips, sortieId, sortieIndex, generationStatus, rhoSessionId, reFlyAvailable,
  regenerateAvailable`; no type or line-count field. `PendingContent` is the one card for every
  pending-ish status (Idle / Working / Validating / Saving / Failed / RePlanning), and `deriveActive`'s
  fallback arm (304–323) builds the same state, so one call site covers them all. IN_FLIGHT (70+, and
  285–302) falls back to the focus too, but an in-flight sortie always has a plan, so the focus is
  rarely its title.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — a focus arrives only with
  `storeOutline` (228); `continueWithoutOutline` (197) confirms a week with no outline, so its sorties
  carry `focus = null`. A test that wants a long focus writes the sortie row itself
  (`LaunchpadStatesTest.kt:95`, `LaunchpadRegenerateTest.kt:268` both do this).

## Where the title is drawn

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` (544 lines) —
  `LaunchpadContent` (65) draws the stripes/Scaffold and one branch per state; the content Column is
  `fillMaxSize` with `padding(horizontal = 20.dp)` and `verticalScroll`.
  - `PendingContent` (211–241) — the card in question: `TitleBlock(eyebrow, title)` at 216, Spacer 16,
    `PatternTrack`, Spacer 20, then the one status line from `pendingStatusLine` (246; the strings at
    256–257), then optional validator-error lines and the rho-session line.
  - `PlannedContent` (190–208) — same shape: `TitleBlock` at 191, Spacer 16, `PatternTrack`, Spacer 20,
    optional RePlanning note, then `FlightPlanSection`. This is the layout the brief wants PENDING to keep.
  - Call sites of `TitleBlock` in this file: 139 (Draft), 191 (Planned), 216 (Pending), 261 (InFlight), 277 (Closed).
- `app/src/main/java/com/liftoff/app/ui/theme/Headings.kt` — `Eyebrow` (18), `DisplayTitle` (29, uppercases
  the text, `style` defaults to `LiftoffType.screenTitle()`), `TitleBlock` (41, eyebrow + 4 dp spacer +
  `DisplayTitle`, takes `titleStyle`). No `maxLines`/`overflow` parameter anywhere. Call sites: Launchpad
  (139, 191, 216, 261, 277), Mission (`MissionScreen.kt:111,165,176,202,242`), Landed (100, 178), Mission
  Control (143), `ui/PlaceholderScreen.kt:29`. `InFlightScreen.kt:440` and `RunContent.kt:58` call
  `DisplayTitle` directly with `LiftoffType.headerTitle()` — the app already typesets a screen title at the
  smaller 44 sp display size, which is the precedent for a Launchpad title that shrinks.
- `app/src/main/java/com/liftoff/app/ui/theme/Type.kt` — `screenTitle` = BigShoulders W900, 68 sp,
  lineHeight 0.9 em (32–35). Other display sizes in the same scale: `headerTitle` 44, `launchLabel` 34,
  `landLabel` 30, `cardTitle` 22. `app/src/test/.../ui/theme/TypographyTest.kt` asserts each role's exact
  size (screenTitle at :56), so the roles are locked by test.
- Precedent for two-line caps and for reading a layout result: `InFlightScreen.kt:412–414` and `:633`
  use `maxLines = 2` + `TextOverflow.Ellipsis`; `Buttons.kt:192` and `BackupSection.kt:161` use
  `onTextLayout = { result -> … result.lineCount … }`. No `TextMeasurer` or `BoxWithConstraints` in the
  repo yet.

## Tests that already cover this area

- `LaunchpadPendingStatusTest.kt` — the PENDING card's statuses and Retry. Harness the other Launchpad
  screen tests copy: in-memory Room, real `SettingsStore`, `MissionManager` on a fixed UTC clock,
  `LaunchpadContent` rendered apart from `AppContainer`, `@RunWith(AndroidJUnit4)`,
  `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")` (so the title's width is 411 − 2×20 = 371 dp).
- `LaunchpadViewInRhoTest.kt` — same harness; the model for geometry assertions: `boundsInRoot`
  comparisons between two nodes (188–199), `awaitText` via `waitUntil` on `fetchSemanticsNodes`.
- `LaunchpadStatesTest.kt` — state derivation only (inserts sorties with a focus, :95–101).
- `LaunchpadPlannedActionsTest.kt` — the PLANNED card's bar, also `boundsInRoot` (197–199). Its
  `plannedLift()` (153) is the precedent for reaching a PLANNED card in a compose test: `week("LRL")`
  queues the row, `generationDao().markSucceeded` closes it, `manager.storePlan(sortieId,
  CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))` stores the plan;
  `render()` (173) composes `LaunchpadContent`.
- `LiftoffShellTest.kt` — the shell's navigation; the known flake (see Risks in plan.md).
- No test measures a rendered text size or line count anywhere in `app/src/test` today. What a test *can*
  measure, checked against the 1.7.6 artifacts: `SemanticsActions.GetTextLayoutResult` exists but is
  registered only by text fields and the scroll modifiers — not by plain `Text` — so a title's line count
  is not readable from semantics; `BoundsAssertionsKt` has `assertHeightIsEqualTo` / `assertHeightIsAtLeast`
  and no `assertHeightIsAtMost`, so the case compares `boundsInRoot` heights with `assertTrue`, the idiom
  `LaunchpadViewInRhoTest` already uses.

## Where else the focus is shown

The same focus string appears in full on other screens, which is why the fix is a display rule at the
Launchpad card and not a trim of the stored value: `MissionState.kt:144,162` (sortie titles), `:230,245`
(`SortieRow`), `FlightPlanSection.kt:144,152` (the plan card's own focus row), `InFlightState.kt:122,130`,
`LandedState.kt:199`. The Mission screen's two `TitleBlock` call sites (`MissionScreen.kt:165,176`) can
carry the same long title; the brief names only the Launchpad card, so those are left alone.

## Docs that set rules

- `design/README.md` — Type table: "Screen title (Launchpad) | Big Shoulders | 68 / 900 | uppercase,
  line-height 0.9". Also: "The other Launchpad states in §9 (PENDING, …) are not mocked up. Build them
  from the same parts." 1 CSS px = 1 dp.
- `design/screens/launchpad.html` — the PLANNED mockup: eyebrow + title at 68 px, line-height 0.9, one
  line ("Push + core", :30–33); then pattern track (:35), Flight Plan (:46), actions (:63).
- `DESIGN.md` §9 (Launchpad states, 574+), §13 (the gate: `:app:assembleDebug :app:testDebugUnitTest`).
- `ARCHITECTURE.md` — the UI layer's structure (state derivation in `LaunchpadState`, drawing in
  `LaunchpadScreen`, tokens in `ui/theme`).

## Gate on this machine

`gradlew.sh` at HEAD is Windows-only (hard-coded `C:/Users/corpo/android-toolchain`), so the brief's
`bash gradlew.sh …` cannot run in this worktree. The equivalent here is:

```
JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk \
  ~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest
```

The run's own `verify.sh` (never edit it) is invoked as `bash .rho/runs/<run>/verify.sh`.
