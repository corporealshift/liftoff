#!/usr/bin/env bash
# Verify that the Launchpad and Mission screens brief is done.
# Exit 0 only when the gate passes and every named test of the new behavior runs and passes.
set -uo pipefail

# This script sits in .nabu/runs/<run>/, so the repository root is three levels up.
cd "$(dirname "$0")/../../.." || exit 1

PASS=0
FAIL=0

pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

# Run one test filter. Gradle fails the task when a --tests filter matches no test,
# so a test that is missing or did not run counts as a failure.
run_tests() {
    local label="$1" filter="$2"
    echo ""
    echo "=== $label ==="
    if bash gradlew.sh :app:testDebugUnitTest --tests "$filter" > "$LOG" 2>&1; then
        pass "$filter ran and passed"
    else
        tail -n 40 "$LOG"
        fail "$filter failed or did not run"
    fi
}

# ── Gate: what CI runs (.github/workflows/ci.yml) ───────────────────
# Also keeps the shell, Mission Control, the domain rules and their tests green.
echo ""
echo "=== Build gate ==="
if bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest > "$LOG" 2>&1; then
    pass "assembleDebug and testDebugUnitTest pass"
else
    tail -n 60 "$LOG"
    fail "assembleDebug or testDebugUnitTest failed"
fi

# ── Launchpad state derived from the database ───────────────────────
# Robolectric against an in-memory DB: draft with the default pattern on a fresh
# install, PLANNED lift with seeded exercises, PLANNED run (sortie 1 under RLRLR
# after confirm, Launch available), PENDING after scrub (scrub advances to the next
# sortie), IN_FLIGHT after launch, and closed; the state updates live.
run_tests "Launchpad state tests" "com.liftoff.app.ui.launchpad.LaunchpadStatesTest"

# ── Mission tab derived from the database ───────────────────────────
# Pattern track, outline notes, sortie rows (index, type, focus, state), and the
# sortie detail: the Flight Plan, or the record for a landed or scrubbed sortie with its reason.
run_tests "Mission tab tests" "com.liftoff.app.ui.mission.MissionStatesTest"

# ── Flight Plan formatting ──────────────────────────────────────────
# The exercise load ('3×8 · 135') and the Flight Plan head the brief asks for.
# Decision 9 (how an exercise's load is written) shows up here.
run_tests "Format tests" "com.liftoff.app.ui.sortie.PlanFormatTest"

# ── In-Flight route ─────────────────────────────────────────────────
# Launch and Resume open an In-Flight route. Test names in ShellNavTest that cover
# it contain "InFlight" or "inFlight". Decision 5 (back from In-Flight returns to the Launchpad) shows up here.
run_tests "ShellNav in-flight tests" "com.liftoff.app.ui.ShellNavTest.*nFlight*"

# ── ARCHITECTURE.md marks M2 as done ────────────────────────────────
# A done criterion in the brief that no test can show: the status column of the M2 row.
echo ""
echo "=== Architecture M2 marker ==="
if awk -F'|' '$2 ~ /^[[:space:]]*M2[[:space:]]*$/ && $4 ~ /^[[:space:]]*Done/ { found = 1 } END { exit !found }' ARCHITECTURE.md 2>/dev/null; then
    pass "M2 marked as Done in ARCHITECTURE.md"
else
    fail "M2 not marked as Done in ARCHITECTURE.md"
fi

# ── Summary ─────────────────────────────────────────────────────────
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "Brief NOT done."
    exit 1
fi

echo "All checks passed. Brief is done."
exit 0
