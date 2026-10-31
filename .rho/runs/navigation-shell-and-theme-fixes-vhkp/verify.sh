#!/usr/bin/env bash
# Done-check for the navigation-shell-and-theme-fixes run.
# Runs the CI gate, then proves each named test of the new behaviour ran and passed,
# reading Gradle's JUnit XML reports (not console output, which Gradle doesn't print per test).
set -uo pipefail

# Repository root, whether called from the root or from the script's own directory.
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || (cd "$(dirname "$0")/../../.." && pwd))"
cd "$ROOT" || { echo "FAIL: cannot cd to repository root"; exit 1; }

RESULTS="app/build/test-results/testDebugUnitTest"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

fail() { echo "FAIL: $*"; exit 1; }

# Stale reports from an earlier run must not count as this run's results.
rm -rf "$RESULTS"

echo "=== CI gate: :app:assembleDebug :app:testDebugUnitTest ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
    fail "the CI gate failed (gradle exit $status)"
fi
echo "PASS: CI gate"

[ -d "$RESULTS" ] || fail "no unit test reports in $RESULTS: the test task did not run"

# check_test <fully.qualified.Class> <method>: the test ran, and did not fail, error or skip.
check_test() {
    local cls="$1" method="$2"
    local report="$RESULTS/TEST-$cls.xml"
    [ -f "$report" ] || fail "$cls.$method did not run (no report for $cls)"
    local line
    line="$(grep -F " name=\"$method\"" "$report" | grep -F "classname=\"$cls\"" | head -n 1 | tr -d '\r')"
    [ -n "$line" ] || fail "$cls.$method did not run"
    # A passing testcase is a self-closing element; failures and skips have child elements.
    case "$line" in
        *"/>") echo "PASS: $cls.$method" ;;
        *) fail "$cls.$method failed or was skipped" ;;
    esac
}

NAV="com.liftoff.app.ui.ShellNavTest"
SHELL="com.liftoff.app.ui.LiftoffShellTest"
TYPE="com.liftoff.app.ui.theme.TypographyTest"

# --- Brief: a JVM test covers the navigation state logic ---
# Tab selection, opening Mission Control, and what back does from each place.
check_test "$NAV" startsOnLaunchpadWithMissionControlClosed
check_test "$NAV" selectingATabShowsIt
check_test "$NAV" openingMissionControlKeepsTheTab
check_test "$NAV" backFromMissionOrLandedGoesToLaunchpad
check_test "$NAV" backFromLaunchpadLeavesTheApp

# --- Brief: the app opens to the shell on Launchpad; every tab and Mission Control is reachable ---
# LiftoffShellTest drives the real MainActivity under Robolectric with the Compose UI test rule
# (the worker adds the compose ui-test test dependencies; no navigation library).
check_test "$SHELL" appOpensOnLaunchpad
check_test "$SHELL" bottomBarReachesEveryTab
check_test "$SHELL" systemBackFromATabGoesToLaunchpad
check_test "$SHELL" systemBackFromLaunchpadFinishesTheActivity

# --- Brief: every Material 3 typography role uses Big Shoulders Display or Work Sans ---
check_test "$TYPE" everyMaterialRoleUsesABundledFamily
# Brief: the 8 roles already set are kept as they are.
check_test "$TYPE" originalEightRolesAreUnchanged
# Existing typography tests keep passing.
check_test "$TYPE" namedRolesMatchReadmeTypeTable
check_test "$TYPE" materialSlotsUseDesignFonts

# --- Decisions (plan.md ## Decisions) ---

# Decision: Shell placement. Navigation logic is a pure-Kotlin ShellNav in com.liftoff.app.ui,
# tested on the plain JVM without Robolectric; ui/theme stays at its 11 files.
check_test "$NAV" startsOnLaunchpadWithMissionControlClosed
check_test "com.liftoff.app.ui.theme.ThemePackageTest" buildingBlocksAreDeclaredInUiTheme

# Decision: No new fonts. The six bundled TTFs are reused, exactly six.
check_test "com.liftoff.app.ui.theme.FontResourcesTest" bundlesStaticBigShouldersAndWorkSansWeights

# Decision: The missing typography roles. The 7 filled roles keep the Material 3 default
# size and line height; display/headline/title use Big Shoulders W800, bodySmall/labelMedium Work Sans.
check_test "$TYPE" filledRolesKeepMaterialDefaultMetrics

# Decision: Preview composables. They are no longer public API (private @Preview, not deleted).
check_test "com.liftoff.app.ui.theme.PreviewsTest" noPreviewComposableIsPublicApi

# Decision: Back behaviour. Back from Mission Control returns to the tab it was opened from,
# a second back goes to Launchpad, and reselecting the current tab does nothing.
check_test "$NAV" backFromMissionControlReturnsToTabItWasOpenedFrom
check_test "$NAV" secondBackAfterMissionControlGoesToLaunchpad
check_test "$NAV" reselectingCurrentTabChangesNothing
check_test "$SHELL" systemBackFromMissionControlReturnsToTabItWasOpenedFrom

# Decision: Rotation. Both the selected tab and whether Mission Control is open survive rotation.
check_test "$NAV" stateSurvivesSaveAndRestore
check_test "$NAV" unreadableSavedStateRestoresToLaunchpad
check_test "$SHELL" selectedTabSurvivesRecreation
check_test "$SHELL" missionControlSurvivesRecreation

# Decision: Mission Control layout. Full screen without the bottom bar, opened by the sliders
# button, eyebrow "Settings" and title "Mission Control", and a back button to the origin tab.
check_test "$SHELL" slidersButtonOpensMissionControlWithoutBottomBar
check_test "$SHELL" missionControlShowsSettingsEyebrowAndTitle
check_test "$SHELL" missionControlBackButtonReturnsToTabItWasOpenedFrom

# Decision: System nav-bar icons. Light icons over the ink bar on tabs, dark on Mission Control's cream.
check_test "$SHELL" navBarIconsAreLightOnTabsAndDarkOnMissionControl

# Decision: Placeholder copy. Launchpad, Mission and Landed show their eyebrow, title and line.
check_test "$SHELL" placeholderScreensShowTheirCopy

echo ""
echo "=== All checks passed ==="
exit 0
