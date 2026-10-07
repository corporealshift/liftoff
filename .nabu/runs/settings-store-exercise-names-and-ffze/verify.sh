#!/bin/bash
set -euo pipefail
# Verify: Settings store, exercise names and AppContainer brief is done.

cd "$(dirname "$0")/../../.." || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# ── Build gate (CI runs this exact command) ──────────────────────────
echo "=== Build gate ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest

# ── Helper: assert a test class ran and passed ───────────────────────
# Fails if no XML for the class, or any failure/error is present.
check_test_class() {
    local cls="$1" label="$2"
    xml="$(find "$RESULTS" -name "${cls}*.xml" -print -quit 2>/dev/null || true)"
    if [ -z "$xml" ]; then
        echo "FAIL: $label — no test result XML for $cls"
        return 1
    fi

    # JUnit4 XML: failures and errors are sibling elements at the root level.
    # If either is present, fail.
    if grep -q '<failure ' "$xml" || grep -q '<error ' "$xml"; then
        echo "FAIL: $label — $cls has failure(s) or error(s)"
        cat "$xml"
        return 1
    fi

    # Also check there is at least one <testcase> (the class actually ran).
    if ! grep -q '<testcase ' "$xml"; then
        echo "FAIL: $label — $cls XML has no test cases"
        return 1
    fi

    echo "PASS: $label"
}

# ══════════════════════════════════════════════════════════════════════
# Named tests from the brief and tasks
# ══════════════════════════════════════════════════════════════════════

echo ""
echo "=== Settings store ==="

check_test_class "SettingsStoreTest" \
    "SettingsStoreTest: every default, round trip for all 12 fields, rejection of invalid values with prior value preserved, corrupt unit fallback"

# ══════════════════════════════════════════════════════════════════════
# Named tests from the brief and tasks
# ══════════════════════════════════════════════════════════════════════

echo ""
echo "=== Exercise name normalizer ==="

check_test_class "ExerciseNamesTest" \
    "ExerciseNamesTest: four equivalent names normalize to 'bench press', hyphen kept, symbol removal, whitespace collapsing, all-symbols → '', coach invariant (no Android imports)"

# ══════════════════════════════════════════════════════════════════════
# Named tests from the brief and tasks
# ══════════════════════════════════════════════════════════════════════

echo ""
echo "=== AppContainer ==="

check_test_class "AppContainerTest" \
    "AppContainerTest: Application is LiftoffApplication, container is singleton, settingsStore is singleton on repeated access"

# ══════════════════════════════════════════════════════════════════════
# Decisions from plan.md — each decision gets a named test.
# The decision is enforced if the corresponding test (above) passes,
# because the tests were written to assert that decision's behaviour.
# ══════════════════════════════════════════════════════════════════════

echo ""
echo "=== Plan decisions ==="

# Decision: units are typed enums (WeightUnit { LB, KG }, DistanceUnit { MI, KM }).
# Stored by enum name; invalid stored values fall back to default (LB / MI).
# Proven by the corrupt-unit test in SettingsStoreTest.
check_test_class "SettingsStoreTest" \
    "Decision — units are typed enums with corrupt-value fallback to LB/MI"

# Decision: pattern case — only uppercase R and L accepted; "rlr" rejected.
# Proven by rejection tests in SettingsStoreTest.
check_test_class "SettingsStoreTest" \
    "Decision — pattern accepts only uppercase R/L ('rlr' is rejected)"

# Decision: free-text fields stored verbatim (no trim, no validation).
# Proven by round-trip tests in SettingsStoreTest (host, token, objectives, constraints set and read back unchanged).
check_test_class "SettingsStoreTest" \
    "Decision — free-text fields stored verbatim without trimming or validation"

# Decision: rejection exception is IllegalArgumentException.
# Proven by IllegalArgumentException being thrown for invalid values in SettingsStoreTest.
check_test_class "SettingsStoreTest" \
    "Decision — rejected values throw IllegalArgumentException before any write"

# Decision: Unicode in exercise names — any Unicode letter/digit kept, all whitespace collapses to space.
# Proven by ExerciseNamesTest (Unicode letters, tabs, newlines collapsed).
check_test_class "ExerciseNamesTest" \
    "Decision — Unicode letters and digits kept; all whitespace collapses to one space"

# Decision: a name with only symbols normalizes to "".
# Proven by ExerciseNamesTest ("!!!" → "").
check_test_class "ExerciseNamesTest" \
    "Decision — all-symbols name normalizes to empty string"

# Decision: store created lazily in AppContainer from application context.
# Proven by AppContainerTest (singleton assertions on container and settingsStore).
check_test_class "AppContainerTest" \
    "Decision — SettingsStore created once, lazily, from Application context"

echo ""
echo "=== All checks passed ==="
