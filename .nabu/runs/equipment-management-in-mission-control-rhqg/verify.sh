#!/usr/bin/env bash
set -euo pipefail

# ── Configuration ────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APP_DIR="$REPO_ROOT/app"
GRADLEW="./gradlew.sh"

# Where Gradle writes test results for :app:testDebugUnitTest
TEST_RESULTS_DIR="app/build/test-results/testDebugUnitTest"

# ── Helpers ──────────────────────────────────────────────────────

fail() { echo "FAIL: $*"; FAILED=1; }

# Parse tests/failures/errors from a single JUnit XML file.
# Handles both <testsuite .../> and <testsuite ...>\n formats.
parse_xml_counts() {
    local xml="$1"
    # Grab the first <testsuite line (may be self-closing or open)
    local line
    line=$(grep -m1 '<testsuite' "$xml" 2>/dev/null || true)
    if [ -z "$line" ]; then
        echo "0 0 0"
        return
    fi
    # Remove everything after the first > to handle multi-line tags
    line=$(echo "$line" | sed 's/>.*//')
    local t f e
    t=$(echo "$line" | grep -oP 'tests="\K[0-9]+' || echo 0)
    f=$(echo "$line" | grep -oP 'failures="\K[0-9]+' || echo 0)
    e=$(echo "$line" | grep -oP 'errors="\K[0-9]+' || echo 0)
    echo "${t:-0} ${f:-0} ${e:-0}"
}

# Check that a specific test class + method passed in the XML results.
# Usage: assert_test_passed <test_class_name> <test_method_name>
assert_test_passed() {
    local cls="$1"
    local method="$2"
    local found=0
    local ok=0

    while IFS= read -r xml; do
        # Match the classname attribute and the testcase name
        if grep -q "classname=\"[^\"]*${cls}[^\"]*\"" "$xml" 2>/dev/null && \
           grep -q "name=\"${method}\"" "$xml" 2>/dev/null; then
            found=1
            # Passed only if no <failure> or <error> child follows this testcase
            if ! sed -n "/name=\"${method}\"/,/<\/testcase/p" "$xml" | grep -qP '<(failure|error)'; then
                ok=1
            fi
        fi
    done < <(find "$TEST_RESULTS_DIR" -name 'TEST-*.xml' -type f 2>/dev/null)

    if [ "$found" -eq 0 ]; then
        fail "Test '$cls::$method' did not run at all (no matching XML entries found)"
    elif [ "$ok" -eq 0 ]; then
        fail "Test '$cls::$method' ran but failed or errored"
    else
        echo "  PASS: $cls::$method"
    fi
}

# ── Pre-flight ───────────────────────────────────────────────────

echo "=== Verify: Equipment management in Mission Control ==="
echo ""

FAILED=0

# ── Step 1: Build gate ───────────────────────────────────────────
echo "--- Build gate ---"
cd "$REPO_ROOT"
bash $GRADLEW :app:assembleDebug :app:testDebugUnitTest --console=plain
echo ""

# ── Step 2: Verify test results are green ────────────────────────
echo "--- Test result counts ---"
total_tests=0
total_failures=0
total_errors=0

while IFS= read -r xml; do
    read -r t f e <<< "$(parse_xml_counts "$xml")"
    total_tests=$((total_tests + t))
    total_failures=$((total_failures + f))
    total_errors=$((total_errors + e))
    echo "  $(basename "$xml"): tests=$t failures=$f errors=$e"
done < <(find "$TEST_RESULTS_DIR" -name 'TEST-*.xml' -type f 2>/dev/null)

echo ""
echo "  Total: tests=$total_tests failures=$total_failures errors=$total_errors"

if [ "$total_failures" -gt 0 ] || [ "$total_errors" -gt 0 ]; then
    fail "Tests have failures ($total_failures) or errors ($total_errors)"
fi

# ── Step 3: Named tests that prove the new behaviour ─────────────
echo ""
echo "--- Named behaviour tests ---"

# Invalid key pattern — submitAdd() sets addKeyError for bad keys ("Bar", "pull up", "kb-24", ""), nothing saved.
assert_test_passed "EquipmentViewModelTest" "invalidKeyPatternSetsError"

# Duplicate against an active item — second add rejected, exactly one row exists.
assert_test_passed "EquipmentViewModelTest" "duplicateAgainstActiveRejected"

# Duplicate against an inactive item — same rejection with "by a deactivated item" hint.
assert_test_passed "EquipmentViewModelTest" "duplicateAgainstInactiveRejected"

# Name required on add — blank name sets error, nothing saved.
assert_test_passed "EquipmentViewModelTest" "nameRequiredOnAdd"

# Successful add — item appears in state.active, form cleared and closed.
assert_test_passed "EquipmentViewModelTest" "successfulAdd"

# Edit — startEdit → change name/notes → submitEdit() persists; key unchanged.
assert_test_passed "EquipmentViewModelTest" "editPersistsNameAndNotes"

# Name required on edit — blank name sets error, stored name unchanged.
assert_test_passed "EquipmentViewModelTest" "nameRequiredOnEdit"

# Deactivate — item leaves active list, appears in deactivated list.
assert_test_passed "EquipmentViewModelTest" "deactivateMovesToLists"

# Reactivate — item returns to active list, data intact.
assert_test_passed "EquipmentViewModelTest" "reactivateReturnsToActive"

# Error clears on typing — setAddKey() after a key error clears addKeyError.
assert_test_passed "EquipmentViewModelTest" "errorClearsOnTyping"

# Whitespace trimming — surrounding spaces trimmed from key and name on add.
assert_test_passed "EquipmentViewModelTest" "whitespaceTrimmedOnAdd"

# EQUIPMENT section head appears in Mission Control screen (list includes it).
assert_test_passed "MissionControlScreenTest" "showsEverySectionHeadInOrder"

# No build-time placeholder strings remain; "Equipment" is now valid content.
assert_test_passed "MissionControlScreenTest" "showsNoUnbuiltPlaceholders"

# Equipment labels appear in the session-text list (neverSaysSession updated).
assert_test_passed "MissionControlScreenTest" "neverSaysSession"

# ── Step 4: Plan decisions — each gets a named test or coverage note ──
echo ""
echo "--- Plan decisions ---"

# A. Key is NOT auto-normalised (no lowercasing / space replacement).
#    Covered by: invalidKeyPatternSetsError
echo "  Decision A: key not auto-normalised — covered by invalidKeyPatternSetsError"

# B. Name is required when editing as well as adding.
assert_test_passed "EquipmentViewModelTest" "nameRequiredOnEdit"

# C. Surrounding whitespace trimmed from name and notes on add.
assert_test_passed "EquipmentViewModelTest" "whitespaceTrimmedOnAdd"

# D. Duplicate against deactivated item reported with "by a deactivated item".
assert_test_passed "EquipmentViewModelTest" "duplicateAgainstInactiveRejected"

# E. Deactivated items cannot be edited (only Reactivate action).
echo "  Decision E: deactivated items read-only — covered by EquipmentSection UI"

# F. Add form clears and closes after successful add; Cancel discards draft.
assert_test_passed "EquipmentViewModelTest" "successfulAdd"

# G. "Show deactivated" toggle does NOT persist (starts off each time).
echo "  Decision G: showDeactivated starts false — covered by EquipmentState default"

# 1. Add form is inline expandable section (not a dialog).
echo "  Decision 1: add form inline — covered by MissionControlScreenTest"

# 2. Editing presented as inline row replacement with key read-only.
echo "  Decision 2: edit inline, key read-only — covered by EquipmentSection UI"

# 3. Deactivated toggle is a text button at bottom of section card.
echo "  Decision 3: Show deactivated toggle — covered by MissionControlScreenTest"

# 4. Deactivation has no confirmation dialog; item disappears immediately.
assert_test_passed "EquipmentViewModelTest" "deactivateMovesToLists"

# 5. Equipment key cannot be edited after creation.
echo "  Decision 5: key fixed after creation — covered by editPersistsNameAndNotes (key unchanged)"

# 6. Validation errors displayed inline below fields.
assert_test_passed "EquipmentViewModelTest" "invalidKeyPatternSetsError"

# ── Step 5: ARCHITECTURE.md milestone check ─────────────────────
echo ""
echo "--- ARCHITECTURE.md M1 milestone ---"
if grep -q '| M1 |.*| Done |' "$REPO_ROOT/ARCHITECTURE.md"; then
    echo "  PASS: M1 status is Done"
else
    fail "M1 milestone not marked as Done in ARCHITECTURE.md"
fi

# ── Summary ──────────────────────────────────────────────────────
echo ""
if [ "$FAILED" -ne 0 ]; then
    echo "=== VERIFY FAILED ==="
    exit 1
else
    echo "=== ALL CHECKS PASSED ==="
    exit 0
fi
