#!/usr/bin/env bash
# Done-check for "Equipment management in Mission Control".
# Runs the CI gate, then requires each named test of the new behaviour to have
# run in this invocation and passed.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT" || exit 1

RESULTS_DIR="app/build/test-results/testDebugUnitTest"
FAILED=0

fail() { echo "FAIL: $*"; FAILED=1; }

# Passes only if <class>::<method> has a testcase entry in this run's JUnit XML
# with no <failure>, <error> or <skipped> child.
assert_test_passed() {
    local cls="$1" method="$2" xml status
    xml=$(find "$RESULTS_DIR" -type f -name "TEST-*.${cls}.xml" 2>/dev/null | head -n 1)
    if [ -z "$xml" ]; then
        fail "$cls::$method did not run (no results file for $cls)"
        return
    fi
    status=$(awk -v m="$method" '
        /<testcase / {
            inside = 0
            if (index($0, " name=\"" m "\"")) {
                found = 1
                if ($0 !~ /\/>[[:space:]]*$/) inside = 1
            }
            next
        }
        inside && /<(failure|error|skipped)/ { bad = 1 }
        inside && /<\/testcase>/ { inside = 0 }
        END {
            if (!found) print "missing"
            else if (bad) print "failed"
            else print "passed"
        }' "$xml")
    case "$status" in
        passed)  echo "  PASS: $cls::$method" ;;
        missing) fail "$cls::$method did not run" ;;
        *)       fail "$cls::$method failed, errored or was skipped" ;;
    esac
}

echo "=== Verify: Equipment management in Mission Control ==="

# ── Build gate (what CI runs) ────────────────────────────────────
# Remove old results so every result read below comes from this run.
rm -rf "$RESULTS_DIR"

echo ""
echo "--- Build gate ---"
if [ -f gradlew.sh ] && [ -d "C:/Users/corpo/android-toolchain" ]; then
    GRADLE=(bash gradlew.sh)
else
    GRADLE=(./gradlew)
fi
if ! "${GRADLE[@]}" :app:assembleDebug :app:testDebugUnitTest --console=plain; then
    fail "build gate :app:assembleDebug :app:testDebugUnitTest failed"
fi

# ── Brief behaviour: state logic against an in-memory database ───
echo ""
echo "--- Equipment state and validation ---"
VM="EquipmentViewModelTest"

# Key rule: "Bar", "pull up", "kb-24" and "" each set addKeyError; nothing is saved.
# Decision A: the key is not auto-normalised, so "Bar" is rejected, not saved as "bar".
assert_test_passed "$VM" "invalidKeyPatternSetsError"

# Key rule: a duplicate of an active item's key is rejected; exactly one row exists.
assert_test_passed "$VM" "duplicateAgainstActiveRejected"

# Key rule: a duplicate of a deactivated item's key is rejected; the one row stays inactive.
# Decision D: the error says the key is used "by a deactivated item".
assert_test_passed "$VM" "duplicateAgainstInactiveRejected"

# Name is required on add: blank or whitespace-only name sets addNameError, nothing is saved.
assert_test_passed "$VM" "nameRequiredOnAdd"

# Decision F: a successful add puts the item in state.active, then clears and closes the form.
assert_test_passed "$VM" "successfulAdd"

# Decision F: Cancel discards the add draft; reopening the form shows empty fields.
assert_test_passed "$VM" "cancelAddDiscardsDraft"

# Decision F: only one item is edited at a time; starting Edit on another item discards the first draft.
assert_test_passed "$VM" "startEditOnAnotherItemDiscardsFirstDraft"

# Edit persists the new name and notes (dao.get) and state.active shows them.
# Decision 5: the key is unchanged after an edit.
assert_test_passed "$VM" "editPersistsNameAndNotes"

# Decision B: name is required on edit; a blank name sets editNameError and the stored name is unchanged.
assert_test_passed "$VM" "nameRequiredOnEdit"

# Deactivate moves the item from state.active to state.deactivated; the row still exists, data intact.
# Decision 4: deactivation applies at once, with no confirmation step.
assert_test_passed "$VM" "deactivateMovesToLists"

# Reactivate moves the item from state.deactivated back to state.active, unchanged.
assert_test_passed "$VM" "reactivateReturnsToActive"

# Typing into a field clears that field's error (setAddKey after a key error).
assert_test_passed "$VM" "errorClearsOnTyping"

# Decision C: surrounding whitespace is trimmed from key, name and notes on add.
assert_test_passed "$VM" "whitespaceTrimmedOnAdd"

# Decision C: surrounding whitespace is trimmed from name and notes on edit.
assert_test_passed "$VM" "whitespaceTrimmedOnEdit"

# Decision G: showDeactivated starts false in a new view model, even after another one turned it on.
assert_test_passed "$VM" "showDeactivatedStartsOff"

# Changes persist: a new view model on the same database sees the added, edited and deactivated items.
assert_test_passed "$VM" "changesPersistAcrossViewModels"

# ── Brief behaviour: driven from the Mission Control screen ──────
echo ""
echo "--- Mission Control screen ---"
MC="MissionControlScreenTest"

# The EQUIPMENT section head appears in order with the other sections.
assert_test_passed "$MC" "showsEverySectionHeadInOrder"

# Test connection and Export are still absent; Equipment is now present.
assert_test_passed "$MC" "showsNoUnbuiltPlaceholders"

# No on-screen text, including the equipment labels, says "session".
assert_test_passed "$MC" "neverSaysSession"

echo ""
if [ "$FAILED" -ne 0 ]; then
    echo "=== VERIFY FAILED ==="
    exit 1
fi
echo "=== ALL CHECKS PASSED ==="
exit 0
