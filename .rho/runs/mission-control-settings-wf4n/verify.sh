#!/usr/bin/env bash
# Verification for mission-control-settings-wf4n
# Exit 0 only when the brief is fully done.
set -euo pipefail

# verify.sh lives at .nabu/runs/<run-id>/verify.sh; go up 3 levels to repo root.
cd "$(dirname "$0")/../../.."

XML="app/build/test-results/testDebugUnitTest"

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

# Clear old results so only this run counts; Gradle reruns the tests when its outputs are gone.
rm -rf "$XML"

echo "=== Build gate (same command as CI): assembleDebug + testDebugUnitTest ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest || fail "build gate failed"

# ---------------------------------------------------------------------------
# check_test CLASS METHOD
# Passes only if CLASS.METHOD ran in this build and passed.
# Fails if it is missing, skipped, failed or errored.
# Handles self-closing <testcase .../> and either attribute order.
# ---------------------------------------------------------------------------
check_test() {
    local cls="$1"
    local method="$2"
    local file="$XML/TEST-${cls}.xml"

    if [ ! -f "$file" ]; then
        fail "${cls}.${method} did not run (no results for ${cls})"
    fi

    local status
    status=$(awk -v m="$method" '
        BEGIN { RS = "<testcase[ \t\r\n]"; st = "missing" }
        NR > 1 {
            gt = index($0, ">")
            head = substr($0, 1, gt)
            if (head !~ ("(^|[ \t\r\n])name=\"" m "\"")) next
            if (substr(head, gt - 1, 1) == "/") {
                body = ""
            } else {
                e = index($0, "</testcase>")
                body = (e > 0) ? substr($0, gt + 1, e - gt - 1) : substr($0, gt + 1)
            }
            if (body ~ /<(failure|error)[ \t\r\n>\/]/) st = "failed"
            else if (body ~ /<skipped/) { if (st != "failed") st = "skipped" }
            else if (st == "missing") st = "passed"
        }
        END { print st }
    ' "$file")

    case "$status" in
        passed)  pass "${cls}.${method}" ;;
        missing) fail "${cls}.${method} did not run" ;;
        skipped) fail "${cls}.${method} was skipped" ;;
        *)       fail "${cls}.${method} ran but failed" ;;
    esac
}

VM="com.liftoff.app.ui.control.MissionControlViewModelTest"
SCREEN="com.liftoff.app.ui.control.MissionControlScreenTest"
SHELL_T="com.liftoff.app.ui.LiftoffShellTest"
STORE_T="com.liftoff.app.settings.SettingsStoreTest"

# ============================================================================
# Loading: when Mission Control opens it shows the stored values.
# ============================================================================
echo ""
echo "=== Loading stored values ==="
# View model: non-default stored values appear in every state field; loaded = true.
check_test "$VM" "loadsStoredValues"
# Screen (Robolectric): opening Mission Control shows stored host, port, pattern etc.
check_test "$SCREEN" "opensShowingStoredValues"

# ============================================================================
# Saving each kind of field: the view model method writes the new value to the store.
# ============================================================================
echo ""
echo "=== Saving each field ==="
check_test "$VM" "savesHost"
check_test "$VM" "savesPort"
check_test "$VM" "savesToken"
check_test "$VM" "savesWorkspacePath"
check_test "$VM" "savesSortieLength"
check_test "$VM" "savesHistoryWindow"
check_test "$VM" "savesWeightUnit"
check_test "$VM" "savesDistanceUnit"
check_test "$VM" "savesGenerateRunPlans"
check_test "$VM" "savesObjectives"
check_test "$VM" "savesConstraints"
check_test "$VM" "patternToggleFlipsChipAndSaves"
check_test "$VM" "patternAddAppendsRAndSaves"
check_test "$VM" "patternRemoveDropsLastChipAndSaves"

# ============================================================================
# Survives restart: values saved through one view model load in a fresh
# DataStore + SettingsStore + view model on the same file.
# ============================================================================
echo ""
echo "=== Survives restart ==="
check_test "$VM" "survivesRestart"

# ============================================================================
# Rejecting invalid input: the error is set, the stored value is unchanged,
# and a later valid entry clears the error.
# ============================================================================
echo ""
echo "=== Rejecting invalid input ==="
check_test "$VM" "rejectsInvalidPort"
check_test "$VM" "rejectsInvalidSortieLength"
check_test "$VM" "rejectsInvalidHistoryWindow"
# Screen: an invalid port shows its message on screen and the store keeps the old port.
check_test "$SCREEN" "invalidPortShowsErrorMessage"

# ============================================================================
# Draft not clobbered: an invalid port draft and its error stay after another field saves.
# ============================================================================
echo ""
echo "=== Draft not clobbered ==="
check_test "$VM" "draftNotClobberedByOtherSave"

# ============================================================================
# Pattern editor 1–7 limits
# ============================================================================
echo ""
echo "=== Pattern editor limits ==="
check_test "$VM" "patternAddAtSevenIsRejected"
check_test "$VM" "patternRemoveAtOneIsRejected"

# ============================================================================
# Plan Decisions: each decision has a named test of its own.
# ============================================================================
echo ""
echo "=== Plan Decisions ==="

# Decision: How are pattern chips added and removed?
# Square + (append R) and − (remove last) buttons under the chip row; tapping a chip toggles R/L.
# Screen: tapping +, − and a chip changes the stored pattern accordingly.
check_test "$SCREEN" "patternButtonsAndChipTapEditPattern"

# Decision: How are unit choices (weight, distance) presented?
# Two adjacent segments per unit; tapping the other segment selects and saves it.
check_test "$SCREEN" "tappingUnitSegmentsSavesUnits"

# Decision: Do sections get paper cards or sit directly on cream?
# One card per section, each with its head: CONNECTION, COACH, MISSION, UNITS, RUNS,
# OBJECTIVES AND CONSTRAINTS, in that order.
check_test "$SCREEN" "showsEverySectionHeadInOrder"

# Decision: Where does the Equipment section go?
# No visible Equipment section or placeholder; no Test connection or export/import either.
check_test "$SCREEN" "showsNoEquipmentOrUnbuiltPlaceholders"

# Decision: When are edits saved?
# Automatically on valid input, with no Save button; an invalid draft is dropped and the
# next open shows the stored value.
check_test "$SCREEN" "typingValidValueSavesWithoutSaveButton"
check_test "$VM" "reopenShowsStoredValueAfterInvalidDraft"

# Decision: How is numeric input parsed?
# Surrounding whitespace is trimmed; only whole numbers are accepted ("7.5", "", Int overflow rejected).
check_test "$VM" "numericInputIsTrimmed"
check_test "$VM" "rejectsNonWholeAndOverflowNumbers"

# Decision: What does the pattern editor show at its limits?
# + disabled at 7, − disabled at 1; an attempt at a limit shows "A pattern has 1 to 7 sorties".
check_test "$VM" "canAddChipFalseAtSeven"
check_test "$VM" "canRemoveChipFalseAtOne"
check_test "$SCREEN" "patternButtonsDisabledAtLimits"

# Decision: What control is 'Generate run plans'?
# A square check box whose whole row, label included, is the touch target.
check_test "$SCREEN" "tappingGenerateRunPlansRowTogglesSetting"

# Decision: How does the token reveal work?
# Eye icon button in the token field toggles masking ("Show token" / "Hide token"); the stored token is unchanged.
check_test "$VM" "toggleTokenVisibilityDoesNotWrite"
check_test "$SCREEN" "tokenRevealButtonShowsAndHidesToken"

# ============================================================================
# §2 vocabulary: the screen never says "session".
# ============================================================================
echo ""
echo "=== Vocabulary ==="
check_test "$SCREEN" "neverSaysSession"

# ============================================================================
# Must not break: the shell and its back behaviour, and the settings store.
# (The gate above already fails on any failing test; these confirm they still run.)
# ============================================================================
echo ""
echo "=== Shell and store still work ==="
check_test "$SHELL_T" "missionControlSurvivesRecreation"
check_test "$SHELL_T" "slidersButtonOpensMissionControlWithoutBottomBar"
check_test "$SHELL_T" "missionControlShowsSettingsEyebrowAndTitle"
check_test "$SHELL_T" "missionControlBackButtonReturnsToTabItWasOpenedFrom"
check_test "$SHELL_T" "systemBackFromMissionControlReturnsToTabItWasOpenedFrom"
check_test "$SHELL_T" "navBarIconsAreLightOnTabsAndDarkOnMissionControl"
check_test "$STORE_T" "freeTextFieldsAreStoredVerbatim"

echo ""
echo "=== All checks passed ==="
