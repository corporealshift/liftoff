#!/bin/bash
# Verify: mission-and-sortie-domain-logic-3fsq
# Exits 0 only when the brief is done. Runs from the repository root under bash (Git Bash on Windows).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

RESULTS=app/build/test-results/testDebugUnitTest

# ── CI gate: build + unit tests ───────────────────────────────────────
# Same tasks as .github/workflows/ci.yml; gradlew.sh supplies the toolchain on this machine.
# Old results are removed so every test below must actually run in this invocation.
echo "=== build and test ==="
rm -rf "$RESULTS"
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest

# ── ARCHITECTURE.md: domain row marked as existing ────────────────────
# The brief's done list asks for this explicitly.
echo "=== ARCHITECTURE.md domain row ==="
if ! grep -q '^| `com.liftoff.app.domain` |.*✅' ARCHITECTURE.md; then
    echo "FAIL: ARCHITECTURE.md does not mark com.liftoff.app.domain as existing" >&2
    exit 1
fi

# ── Named tests ───────────────────────────────────────────────────────
# Each named test must appear in the JUnit XML for its class and must have
# passed: not failed, errored, skipped, or missing.
failed=0
check() {
    local cls="$1" method="$2"
    local xml="$RESULTS/TEST-$cls.xml"
    if [ ! -f "$xml" ]; then
        echo "FAIL: $cls.$method did not run (no results for $cls)" >&2
        failed=1
        return
    fi
    local verdict
    verdict=$(awk -v name="$method" -v cls="$cls" '
        BEGIN { state = "missing" }
        index($0, "<testcase name=\"" name "\" classname=\"" cls "\"") {
            state = "passed"
            if ($0 ~ /\/>[[:space:]]*$/) exit
            inside = 1
            next
        }
        inside && /<(failure|error|skipped)/ { state = "failed"; exit }
        inside && /<\/testcase>/ { exit }
        END { print state }
    ' "$xml")
    case "$verdict" in
        passed) echo "ok: $cls.$method" ;;
        missing) echo "FAIL: $cls.$method did not run" >&2; failed=1 ;;
        *) echo "FAIL: $cls.$method failed or was skipped" >&2; failed=1 ;;
    esac
}

D=com.liftoff.app.domain
M=com.liftoff.app.data.MissionManagerTest

echo "=== domain rules (pure JVM) ==="
# Week start is Monday in local time.
check $D.WeekTest everyDayMapsToItsMonday
# Monday boundary across time zones (Decision: Time zone).
check $D.WeekTest mondayBoundaryAcrossTimeZones

# Draft for the current week with the default pattern.
check $D.MissionsTest newDraftUsesDefaultPattern
# Pattern override on a draft; only 1–7 of R/L accepted.
check $D.MissionsTest overridePatternOnDraft
check $D.MissionsTest overridePatternRejectsInvalidPattern
# Pattern frozen once confirmed.
check $D.MissionsTest patternFrozenAfterConfirm
# Confirm without outline: one sortie per letter, R = run/"easy", L = lift/"full body".
check $D.MissionsTest confirmCreatesOneSortiePerPatternLetter
# DRAFT → ACTIVE → CLOSED; everything else rejected (Decision: Mission transitions).
check $D.MissionsTest missionLifecycleTransitions
# A Mission is done when every sortie landed or was scrubbed.
check $D.MissionsTest allSortiesDoneOnlyWhenEachLandedOrScrubbed

# Every legal §5.1 sortie transition succeeds; every illegal one is rejected.
check $D.SortiesTest legalTransitionsSucceed
check $D.SortiesTest illegalTransitionsAreRejected
# Next sortie: lowest index neither landed nor scrubbed.
check $D.SortiesTest currentSortieSkipsLandedAndScrubbed
# At most one sortie IN_FLIGHT across all Missions.
check $D.SortiesTest atMostOneInFlight

# Ended week: open sorties SCRUBBED with 'week ended', Mission CLOSED.
check $D.RolloverTest rolloverScrubsOpenSortiesWithWeekEnded
# Nothing carries over.
check $D.RolloverTest rolloverDoesNotCarryOver
# The current week's Mission is left alone.
check $D.RolloverTest currentWeekMissionIsNotRolledOver

# Run with generation off gets a SIMPLE_RUN 'Run' plan with its focus (Decision: where the run focus goes).
check $D.SortiePlanningTest runWithGenerationOffGetsSimplePlan
# Lifts, and runs with generation on, stay PENDING awaiting generation.
check $D.SortiePlanningTest liftAndGeneratedRunAwaitGeneration

echo "=== Room-backed operations (Robolectric, in-memory Room) ==="
# App open creates this week's draft with the default pattern, once.
check $M onAppOpenCreatesDraftForCurrentWeek
check $M onAppOpenDoesNotDuplicateDraft
# App open rolls over a past Mission, keeps checked sets, copies nothing (Decision: rollover scope).
check $M onAppOpenRollsOverPastMission

# Pattern change on a draft; rejected once ACTIVE.
check $M setPatternUpdatesDraftAndRejectsActive

# Confirm: ACTIVE, sorties created, first run planned when generation is off (Decision: only the current sortie is prepared).
check $M confirmPlansFirstRunWhenGenerationOff
check $M confirmLeavesLiftPending
check $M confirmLeavesRunPendingWhenGenerationOn

# Launch records launchedAt from the injected clock (Decision: launch and land times).
check $M launchRecordsLaunchedAt
# Launch refused while another sortie is in flight (Decision: launch rules).
check $M launchRejectedWhileAnotherSortieInFlight

# Land records the landed time from the injected clock (Decision: launch and land times).
check $M landRecordsLandedTime
# Unchecked sets marked not done, checked sets stay done (Decision: unchecked sets at landing).
check $M landMarksUncheckedSetsNotDone
# Land advances to the next sortie and prepares its plan.
check $M landAdvancesToNextSortie
# Landing the last sortie closes the Mission.
check $M landingLastSortieClosesMission

# Scrub stores the reason and prepares the next sortie (Decision: scrub advances like land).
check $M scrubStoresReasonAndPreparesNextSortie
# A scrubbed IN_FLIGHT sortie keeps its checked sets (Decision: scrubbing in flight).
check $M scrubInFlightKeepsCheckedSets
# Scrubbing the last open sortie closes the Mission.
check $M scrubbingLastSortieClosesMission

if [ "$failed" -ne 0 ]; then
    echo "=== FAILED ===" >&2
    exit 1
fi
echo "=== all checks passed ==="
