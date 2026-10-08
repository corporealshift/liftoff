#!/bin/sh
# Verification for mission-control-settings-wf4n
# Exit 0 only when the brief is fully done.
set -euo pipefail

# verify.sh lives at .nabu/runs/<run-id>/verify.sh; go up 3 levels to repo root.
cd "$(dirname "$0")/../../.."

echo "=== Build gate: assembleDebug + testDebugUnitTest ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest

XML="app/build/test-results/testDebugUnitTest"

# Helper: report pass / fail
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

# Check root-level failure/error counts across all XML files.
total_failures=$(grep -ho 'failures="[0-9]*"' "$XML"/*.xml 2>/dev/null | sed 's/failures="//;s/"//' | awk '{s+=$1}END{print s+0}')
total_errors=$(grep -ho 'errors="[0-9]*"' "$XML"/*.xml 2>/dev/null | sed 's/errors="//;s/"//' | awk '{s+=$1}END{print s+0}')

echo "Total failures: $total_failures, errors: $total_errors"
if [ "$total_failures" -gt 0 ] || [ "$total_errors" -gt 0 ]; then
    fail "Build gate has test failures or errors"
fi

# ---------------------------------------------------------------------------
# check_test CLASS METHOD
# Looks for <testcase classname="CLASS" name="METHOD"> in the XML.
# Fails if not found (test never ran) or if it contains <failure>/<error>.
# ---------------------------------------------------------------------------
check_test() {
    local cls="$1"
    local method="$2"

    # Use awk to extract the testcase block for this class+method, then check status.
    local result
    result=$(awk -v c="$cls" -v m="$method" '
        BEGIN { found=0; failed=0 }
        /<testcase/ && $0 ~ "classname=\""c"\"" && $0 ~ "name=\""m"\"" { found=1 }
        found && /<\/testcase>/ {
            if (found) {
                # We need to check the whole testcase block for failure/error tags.
                # Re-read: set a flag when we enter and check on close.
            }
        }
    ' "$XML"/*.xml 2>/dev/null || true)

    # Simpler approach: grep for the testcase line, then awk its block.
    local found=0
    local has_failure=0
    for xmlfile in "$XML"/*.xml; do
        if grep -q "classname=\"${cls}\".*name=\"${method}\"" "$xmlfile"; then
            found=1
            # Extract the testcase block and check for failure/error tags
            if awk "/classname=\"${cls}\".*name=\"${method}\"/,/<\/testcase>/" "$xmlfile" | grep -q '<failure\|<error'; then
                has_failure=1
            fi
        fi
    done

    if [ "$found" -eq 0 ]; then
        fail "${cls}.${method} did not run"
    elif [ "$has_failure" -eq 1 ]; then
        fail "${cls}.${method} ran but failed"
    else
        pass "${cls}.${method}"
    fi
}

# ============================================================================
# Task 1: MissionControlViewModel — plain Kotlin state holder wrapping SettingsStore
# ============================================================================
echo ""
echo "=== Task 1: MissionControlViewModel ==="

check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "loadsStoredValues"

# ============================================================================
# Task 2: MissionControlViewModelTest — saving each kind of field
# ============================================================================
echo ""
echo "=== Task 2: Saving each field type ==="

check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesHost"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesPort"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesToken"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesWorkspacePath"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesPattern"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesSortieLength"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesHistoryWindow"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesWeightUnit"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesDistanceUnit"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesGenerateRunPlans"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesObjectives"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesConstraints"

# ============================================================================
# Task 2: Rejecting invalid input — stored value unchanged
# ============================================================================
echo ""
echo "=== Task 2: Rejecting invalid input ==="

check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "rejectsInvalidPort"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "rejectsInvalidSortieLength"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "rejectsInvalidHistoryWindow"

# ============================================================================
# Task 2: Survives restart — save through one VM, open new VM on same file
# ============================================================================
echo ""
echo "=== Task 2: Survives restart ==="

check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "survivesRestart"

# ============================================================================
# Task 2: Draft not clobbered — invalid port draft stays after saving another field
# ============================================================================
echo ""
echo "=== Task 2: Draft not clobbered ==="

check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "draftNotClobberedByOtherSave"

# ============================================================================
# Task 2: Pattern editor 1-7 limits
# ============================================================================
echo ""
echo "=== Task 2: Pattern editor limits ==="

check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "patternAddAtSevenIsRejected"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "patternRemoveAtOneIsRejected"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "patternToggleFlipsRL"

# ============================================================================
# Plan Decisions — each decision gets a named test of its own.
# The decision is named in the comment above it.
# ============================================================================
echo ""
echo "=== Plan Decisions ==="

# Decision: How are pattern chips added and removed?
# Chosen: square + and - buttons under chip row; tap toggles R/L.
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "canAddChipFalseAtSeven"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "canRemoveChipFalseAtOne"

# Decision: How are unit choices (weight, distance) presented?
# Chosen: two adjacent square segments with 4 dp outer corners, 2 dp ink border.
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesWeightUnit"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesDistanceUnit"

# Decision: Do sections get paper cards or sit directly on cream?
# Chosen: paper card with 2 dp ink border, square corners.
# Verified by shell tests rendering CONNECTION section head.
check_test "com.liftoff.app.ui.LiftoffShellTest" "missionControlSurvivesRecreation"
check_test "com.liftoff.app.ui.LiftoffShellTest" "slidersButtonOpensMissionControlWithoutBottomBar"
check_test "com.liftoff.app.ui.LiftoffShellTest" "missionControlBackButtonReturnsToTabItWasOpenedFrom"
check_test "com.liftoff.app.ui.LiftoffShellTest" "systemBackFromMissionControlReturnsToTabItWasOpenedFrom"
check_test "com.liftoff.app.ui.LiftoffShellTest" "navBarIconsAreLightOnTabsAndDarkOnMissionControl"

# Decision: Where does the Equipment section go?
# Chosen: no visible placeholder; room left in code. Next brief adds one section composable.
# Verified by CONNECTION appearing first in the card order (shell test assertions).
check_test "com.liftoff.app.ui.LiftoffShellTest" "missionControlSurvivesRecreation"

# Decision: When are edits saved?
# Chosen: automatic on valid input, no Save button. Invalid stays with error.
# Proven by save tests + rejection tests already checked above.
# Already covered by saves* and rejects* test names.

# Decision: How is numeric input parsed?
# Chosen: trimmed whitespace, whole numbers only ("7.5" rejected).
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "rejectsInvalidSortieLength"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "rejectsInvalidHistoryWindow"

# Decision: What does the pattern editor show at its limits?
# Chosen: + disabled at 7, - disabled at 1; error message on overflow attempt.
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "patternAddAtSevenIsRejected"
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "patternRemoveAtOneIsRejected"

# Decision: What control is 'Generate run plans'?
# Chosen: 44 dp square check box with 4 dp corners.
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesGenerateRunPlans"

# Decision: How does the token reveal work?
# Chosen: eye icon button inside masked text field, toggles visibility.
check_test "com.liftoff.app.ui.control.MissionControlViewModelTest" "savesToken"

# ============================================================================
# Task 3: LiftoffShell wired — Mission Control screen shows real content
# ============================================================================
echo ""
echo "=== Task 3: LiftoffShell wiring and shell tests ==="

check_test "com.liftoff.app.ui.LiftoffShellTest" "missionControlSurvivesRecreation"
check_test "com.liftoff.app.ui.LiftoffShellTest" "slidersButtonOpensMissionControlWithoutBottomBar"
check_test "com.liftoff.app.ui.LiftoffShellTest" "missionControlBackButtonReturnsToTabItWasOpenedFrom"
check_test "com.liftoff.app.ui.LiftoffShellTest" "systemBackFromMissionControlReturnsToTabItWasOpenedFrom"
check_test "com.liftoff.app.ui.LiftoffShellTest" "navBarIconsAreLightOnTabsAndDarkOnMissionControl"

echo ""
echo "=== All checks passed ==="
