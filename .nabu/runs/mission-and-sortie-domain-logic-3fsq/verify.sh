#!/bin/bash
# Verify: mission-and-sortie-domain-logic-3fsq
# Exits 0 only when the brief is done. Runs from the repository root.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# ── CI gate: build + unit tests ───────────────────────────────────────
# The same command CI runs. If this fails, nothing else matters.
echo "=== build and test ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee /tmp/gradle.log
if ! grep -q 'BUILD SUCCESSFUL' /tmp/gradle.log; then
    echo "FAIL: Gradle build did not succeed" >&2
    exit 1
fi

# ── ARCHITECTURE.md: domain row marked as existing ────────────────────
# The brief requires com.liftoff.app.domain to be listed with a checkmark
# (not "—" or blank) in the package table.
echo "=== ARCHITECTURE.md domain row ==="
if ! grep -q '| `com.liftoff.app.domain` |.*✅' ARCHITECTURE.md; then
    echo "FAIL: ARCHITECTURE.md does not mark com.liftoff.app.domain as existing" >&2
    exit 1
fi

# ── Test classes by name ──────────────────────────────────────────────
# These are the test files the plan names. Each must have been compiled
# and produced a result XML with zero failures and at least one test run.
#
# The brief's done criteria:
#   §13 'Missions' cases  → MissionsTest, MissionManagerTest
#   §13 'Sorties' cases   → SortiesTest
#   Week boundary         → WeekTest
#   Pattern validation    → PatternTest
#   Rollover              → RolloverTest
#   Sortie planning       → SortiePlanningTest
#   Room-backed operations→ MissionManagerTest (Robolectric)

REQUIRED_CLASSES=(
    # Monday boundary across time zones (Decision: Time zone)
    "WeekTest"
    # Valid/invalid patterns; brief asks for this test class
    "PatternTest"
    # Draft created with default pattern; override then frozen on confirm
    # Mission lifecycle: every legal and illegal transition (Decision: Mission transitions)
    "MissionsTest"
    # All 6 legal and 19 illegal sortie transitions; currentSortie skips landed/scrubbed
    # at most one IN_FLIGHT across all Missions
    "SortiesTest"
    # Rollover scrubs open sorties with "week ended"; no carry-over (Decisions: Unchecked sets at landing, Rollover scope)
    "RolloverTest"
    # RUN with generation off gets SimplePlan; LIFT and RUN with generation on get AwaitGeneration
    # (Decision: Where run focus goes — notes field stores the sortie's focus)
    "SortiePlanningTest"
    # Room-backed operations: onAppOpen, setPattern, confirm, launch, land, scrub
    # Covers simple run plan creation, land advancing to next sortie and closing Mission
    # (Decisions: Launch and land times — clock-derived; Scrub advances like land; Only current sortie prepared;
    #  Optional scrub reason — blank stored as null; Launch rules — current + no other in flight)
    "MissionManagerTest"
)

for cls in "${REQUIRED_CLASSES[@]}"; do
    echo "=== checking test class: $cls ==="
    # Find any result XML that mentions this class (JVM or Robolectric).
    xml=$(find app/build/test-results -name '*.xml' -exec grep -l "$cls" {} + 2>/dev/null || true)
    if [ -z "$xml" ]; then
        echo "FAIL: no test results found for $cls" >&2
        exit 1
    fi
    # Check every XML that mentions the class.
    for f in $xml; do
        tests=$(grep -oP 'tests="\K[0-9]+' "$f" | head -1)
        failures=$(grep -oP 'failures="\K[0-9]+' "$f" | head -1)
        errors=$(grep -oP 'errors="\K[0-9]+' "$f" | head -1)
        # Default to 0 if grep found nothing (shouldn't happen for a real XML).
        tests=${tests:-0}
        failures=${failures:-0}
        errors=${errors:-0}
        if [ "$tests" -eq 0 ]; then
            echo "FAIL: $cls ran 0 tests ($f)" >&2
            exit 1
        fi
        if [ "$failures" -gt 0 ] || [ "$errors" -gt 0 ]; then
            echo "FAIL: $cls had $failures failures, $errors errors ($f)" >&2
            exit 1
        fi
    done
done

echo "=== all checks passed ==="
