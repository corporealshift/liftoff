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
  `DisplayTitle`, takes `titleStyle`). No `maxLines`/`overflow` parameter anywhere. **Seventeen call sites
  outside `Headings.kt`** (`grep -rn "TitleBlock(\|DisplayTitle(" app/src/main/java | grep -v theme/Headings.kt`):
  14 `TitleBlock` — Launchpad (139, 191, 216, 261, 277), Mission (`MissionScreen.kt:111,165,176,202,242`),
  Landed (`LandedScreen.kt:100,178`), Mission Control (`control/MissionControlScreen.kt:143`),
  `ui/PlaceholderScreen.kt:29` — and 3 direct `DisplayTitle` (`InFlightScreen.kt:313` and `:440`,
  `RunContent.kt:58`), all three with `LiftoffType.headerTitle()`. That is the app already typesets a screen
  title at the smaller 44 sp display size — the precedent for a Launchpad title that shrinks. `Headings.kt`
  also calls both in its own `@Preview HeadingsPreview` (65–79), behind `LocalInspectionMode`.
- `app/src/main/java/com/liftoff/app/ui/theme/Type.kt` — `screenTitle` = BigShoulders W900, 68 sp,
  lineHeight 0.9 em (32–35). Other display sizes in the same scale: `headerTitle` 44, `launchLabel` 34,
  `landLabel` 30, `cardTitle` 22. `app/src/test/.../ui/theme/TypographyTest.kt` asserts each role's exact
  size (screenTitle at :56), so the roles are locked by test. Its five tests: `namedRolesMatchReadmeTypeTable`
  asserts every README role **by hand** — it never opens `design/README.md`, so editing that table cannot
  break it; `originalEightRolesAreUnchanged` compares whole `TextStyle` objects against
  `LiftoffTypography()`'s eight M3 slots, so a change to `screenTitle`'s metrics fails it;
  `materialSlotsUseDesignFonts`, `everyMaterialRoleUsesABundledFamily` and
  `filledRolesKeepMaterialDefaultMetrics` cover the fonts and the seven filled M3 slots. A new role added
  beside the existing ones breaks none of the five.
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

- `design/README.md` — Type table (header at :56, the Launchpad row at **:59**): "Screen title (Launchpad) |
  Big Shoulders | 68 / 900 | uppercase, line-height 0.9". Also: "The other Launchpad states in §9 (PENDING, …) are not mocked up. Build them
  from the same parts." 1 CSS px = 1 dp.
- `design/screens/launchpad.html` — the PLANNED mockup: eyebrow + title at 68 px, line-height 0.9, one
  line ("Push + core", :30–33); then pattern track (:35), Flight Plan (:46), actions (:63).
- `DESIGN.md` §9 (Launchpad states, 574+) — describes the five states' content and the status line's wording;
  it says nothing about title sizes or line counts, so a title-fit rule contradicts nothing there and needs no
  amendment. §13 (the gate: `:app:assembleDebug :app:testDebugUnitTest`).
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

## Measuring the title: what the harness actually gives (probed, not guessed)

Scratch probes were run under `app/build/scratch-verify/` (gitignored, applied with
`gradle -I app/build/scratch-verify/init.gradle`, which adds that directory to the `testDebugUnitTest`
Kotlin source set — the only way a scratch test class outside `app/src/test` gets compiled). Their output
is only readable from `app/build/test-results/testDebugUnitTest/TEST-*.xml` under `<system-out>`, because
Gradle prints no test stdout here.

- **Robolectric's default graphics mode cannot measure this.** Without `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
  every character is 1 dp wide and every line is 35 dp tall whatever the font size: the 41-character focus reads
  41 dp wide × 35 dp tall, and a five-line title is indistinguishable from a one-line title. Robolectric is 4.14.1
  and no test in this repo sets a graphics mode, so a title-height case has to add the annotation itself.
- **Density is 1.0** with `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, so `boundsInRoot` pixels are already
  dp. The content Column is 411 dp wide, the card's inner Column 371 dp.
- **Compose 1.7.6 registers no `GetTextLayoutResult` semantics action on a plain `Text`** — the card's title node
  carries only `Text`, and `performSemanticsAction(GetTextLayoutResult)` fails with "Semantics action
  GetTextLayoutResult is not defined on node". The plan's Risk is confirmed: no line count is readable, so a case
  reads the node's height.
- **Untouched card, focus `"Lower Strength – Squat & Hinge, plus core"` written on the sortie row, plus a QUEUED
  generation row so the status reads "Coach is planning…"** (a QUEUED row is what makes the status Planning; with no
  generation row the status is Idle and the line reads "Flight Plan not ready yet." — same position, different text):
  eyebrow top 0 / height 14 dp; title top 18 / height **329 dp** (five lines, 371 dp wide); pattern track top 363 /
  height 52 dp; status top **435**. Short focus "Bench Press" → "BENCH PRESS": title height **81 dp** (one line,
  334 dp wide), status top **187**. 112-character focus: title height **701 dp** (eleven lines), status top **807**.
- **The same title in the card's 371 dp column and `screenTitle`'s own style, capped by hand** (`maxLines = n`,
  `TextOverflow.Ellipsis`): 68 sp — 1 line 81, 2 lines **143**, 3 lines **205**, 4 lines 267 (a line costs 62 dp);
  44 sp — 2 lines 92, 3 lines 132 (a line costs 40 dp); 34 sp — 2 lines 71; 30 sp — 2 lines 63; 22 sp — 2 lines 47.
- **Everything under the title is fixed:** the status line's top is the title's height + 106 dp (18 dp title top
  + 16 dp spacer + 52 dp `PatternTrack` + 20 dp spacer). 329+106=435, 81+106=187, 701+106=807 all hold. So a
  two-line title at 68 sp leaves the status at 249 dp and at 44 sp at 198 dp; three lines at 68 sp put it at 311 dp.
- `PatternTrack` is 52 dp tall whatever the chips; the letters inside it are centred, so the L/R text nodes read a
  top a few dp below the track's own top.

`verify.sh`'s bounds come from these: title node ≤ 175 dp (between 143 and 205), status top ≤ 280 dp (between 249
and 311), one line at 68 sp accepted as 75–110 dp (81 dp measured; the upper end also covers a case that measures
the `TitleBlock` column, 14 + 4 + 81 = 99 dp).

## What the run's verify.sh fixes in place of a task

`verify.sh` (never edit it) names five test **methods**, matched as `<testcase name=…>` across every report
of the gate run, so the file and package are the implementer's call — the plan puts all five in a new
`LaunchpadPendingTitleTest.kt`: `pendingTitleWithALongFocusIsAtMostTwoLinesTall` (title node ≤ 175 dp),
`pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus` (status top ≤ 280 dp),
`pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize` (75–110 dp),
`pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall` (≤ 175 dp),
`plannedCardTitleIsStillOneLineAtTheFullDisplaySize` (75–110 dp, node found by "PUSH + CORE"). It requires
six classes green as a class: `TypographyTest`, `LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`,
`LaunchpadViewInRhoTest`, `LaunchpadCoachStatusTest`, `LaunchpadStatesTest`.

The two focus strings it spells out, en dash U+2013 both times (a hyphen measures a different width):
`"Lower Strength – Squat & Hinge, plus core"` (41 characters) and
`"Lower Strength – Squat, Hinge, Core, Lunges, Calf Raises, Seated Cable Rows, Face Pulls, Hamstring Curls and Abs"`
(112 characters).

Recipes the cases need, all already in the repo:
- A PENDING card with a chosen focus: `LaunchpadStatesTest.kt:94–95` inserts a `Sortie` row straight into the
  Room database with `focus = "Bench Press"` (every nullable field named), or write it onto the row
  `continueWithoutOutline` created with `dao.update(...copy(focus = …))` as `LaunchpadPendingStatusTest.kt:111`
  does. The QUEUED FLIGHT_PLAN row `continueWithoutOutline` queues is what makes the status read
  "Coach is planning…" (`LaunchpadScreen.kt:256`); with no generation row it reads
  "Flight Plan not ready yet." (`:257`).
- A PLANNED card: `LaunchpadPlannedActionsTest.kt`'s `plannedLift()` (153) — `markSucceeded(queuedRow,
  seededAt + 60_000)` then `manager.storePlan(sortieId, CoachFixtures.validReply(CoachFixtures.LIFT_PLAN,
  CoachFixtures.LIFT_CONTEXT))`. `CoachFixtures.LIFT_PLAN`'s plan title is "Push + core"
  (`coach/CoachFixtures.kt:114`), and the PLANNED card's title is the plan's title (`LaunchpadState.kt:209`),
  so its title node reads "PUSH + CORE" whatever the row's focus says.
- `boundsInRoot` is already used in these tests (`LaunchpadViewInRhoTest.kt:191–198`,
  `LaunchpadReFlyTest.kt:219–220`, `LaunchpadPlannedActionsTest.kt:197–199`) but only to compare one node's
  position with another's — no existing test measures a text height, so a title that shrinks breaks none of
  them, and their cards' titles are short anyway.

CLAUDE.md's rules for every commit here: message `area: lowercase summary` (no ticket number, no tags),
stage only the files the task names — never `git add -A`, because `.rho/` is untracked scratch; LF endings
everywhere (`* text=auto eol=lf`); `:app:assembleDebug :app:testDebugUnitTest` green before a task is called
done.

## After the PR #83 review (2026-11-23)

The review raised the ladder's two known limits. Measured in the harness, one pumped frame at a time
(`mainClock.autoAdvance = false`, w411dp, NATIVE graphics, scratch probe under `app/build/scratch-pr83/`), the
PENDING card with the 41-character focus:

- frame 0: title 143 dp (2 lines at 68 sp, ellipsized), status line top 249 dp
- frame 1: title 92 dp (2 lines at 44 sp, ellipsized), status line top 198 dp
- frame 2 onward: title 71 dp (2 lines at 34 sp, ellipsized), status line top 177 dp

So the card is laid out at the too-big sizes for two frames before it settles. Gating the title's *draw* until it
settles would hide the wrong sizes but leave the 72 dp of motion in the chips row and the status line, and would
blank the card's only identifying text for those two frames.

**Why `remember(title)` is keyed on the title alone.** The width this title is laid out in changes only with a
configuration change the activity does not handle — orientation, window size, display size, font scale — and
`app/src/main/AndroidManifest.xml` declares no `android:configChanges` on `MainActivity`, so each of those
destroys and recreates it and the plain `remember` goes with the composition: the ladder starts over at 68 sp.
The step-down-only limit becomes reachable the day the app handles those changes itself, and then the width
belongs in the key.
