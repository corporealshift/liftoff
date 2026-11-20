#!/usr/bin/env bash
# Definition of done for issue 57: MissionManager.scrub and MissionManager.launch hand back the
# sortie's open FLIGHT_PLAN generation ids, and the Launchpad and In-Flight view models cancel them
# through the same canceller refly/regenerate already use.
#
# Run from the repository root:  bash .rho/runs/issue-57-missionmanager-scrub-and-07fb/verify.sh
#
# What it does:
#   1. the CI gate (.github/workflows/ci.yml): :app:assembleDebug :app:testDebugUnitTest;
#   2. reads the JUnit XML the gate wrote and requires each named test below to have run and passed.
#      A named test that is absent, skipped, failed or errored is a failure of this script.
#
# The tests are named here so the work is written to prove exactly these things. Each comment says
# what that test must show; write the test with that name in that class.

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
while [[ ! -f "$ROOT/settings.gradle.kts" && "$ROOT" != "/" ]]; do
    ROOT=$(dirname "$ROOT")
done
cd "$ROOT"

RESULTS=app/build/test-results/testDebugUnitTest
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ── the gradle the gate runs ───────────────────────────────────────────────
# CI runs ./gradlew; the brief names bash gradlew.sh. The repo's committed gradlew.sh is the Windows
# variant (it pins C:/Users/corpo/android-toolchain), so it is used where it actually resolves — Git
# Bash on Windows. On this Linux box the same pinned toolchain under ~/android-toolchain is used
# directly, and the POSIX wrapper CI uses is the last resort. LIFTOFF_GATE_GRADLE overrides all of it.
if [[ -n "${LIFTOFF_GATE_GRADLE:-}" ]]; then
    GRADLE=("$LIFTOFF_GATE_GRADLE")
elif bash gradlew.sh --version >/dev/null 2>&1; then
    GRADLE=(bash gradlew.sh)
elif [[ -x "$HOME/android-toolchain/gradle/bin/gradle" ]]; then
    export JAVA_HOME="${JAVA_HOME:-$HOME/android-toolchain/jdk}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-toolchain/sdk}"
    GRADLE=("$HOME/android-toolchain/gradle/bin/gradle")
elif [[ -x ./gradlew ]]; then
    GRADLE=(./gradlew)
else
    echo "verify: no gradle found (tried \$LIFTOFF_GATE_GRADLE, gradlew.sh, \$HOME/android-toolchain/gradle, ./gradlew)" >&2
    exit 1
fi

# ── 1. the gate: build the app and run the unit suite ───────────────────────
# The results directory is removed first so the suite is proved to have run now, not left over
# from an earlier build. Gradle re-runs a task whose declared outputs are missing.
rm -rf "$RESULTS"

echo "==> ${GRADLE[*]} :app:assembleDebug :app:testDebugUnitTest"
if ! "${GRADLE[@]}" :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee "$WORK/gate.log"; then
    echo "verify: FAIL — the CI gate (:app:assembleDebug :app:testDebugUnitTest) did not pass." >&2
    exit 1
fi

if [[ ! -d "$RESULTS" ]]; then
    echo "verify: FAIL — the gate wrote no unit-test results under $RESULTS." >&2
    exit 1
fi

# ── 2. the named tests ───────────────────────────────────────────────────────
# Names are compared with backticks, underscores and spaces removed and case folded, so a test
# named with backticks or underscores still counts.
normalize() { printf '%s' "$1" | LC_ALL=C tr -d '`_ '; }
fold() { printf '%s' "$1" | LC_ALL=C tr '[:upper:]' '[:lower:]'; }

# One line per test the class ran: "<folded name>\tPASS|FAIL\t<name as written>". A testcase
# carrying a failure, error or skipped child counts as not passing.
ran_tests() {
    LC_ALL=C awk -v RS='<testcase' '
        NR > 1 {
            name = ""
            if (match($0, /name="[^"]*"/)) name = substr($0, RSTART + 6, RLENGTH - 7)
            written = name
            gsub(/[`_ ]/, "", name)
            bad = ($0 ~ /<(failure|error|skipped)[ >/]/)
            print tolower(name) "\t" (bad ? "FAIL" : "PASS") "\t" written
        }' "$1"
}

MISSING=()
DUMPED=" "

require_test() {
    local fqn="$1" name="$2"
    local file="$RESULTS/TEST-$fqn.xml"
    local want ran line
    want=$(fold "$(normalize "$name")")

    if [[ ! -f "$file" ]]; then
        MISSING+=("$fqn — the class never ran, so it cannot have $name")
        return
    fi

    ran=$(ran_tests "$file")
    line=$(printf '%s\n' "$ran" | LC_ALL=C awk -F'\t' -v want="$want" '$1 == want { print; exit }')

    if [[ -z "$line" ]]; then
        MISSING+=("$fqn.$name — did not run")
        case "$DUMPED" in
            *" $fqn "*) ;;
            *)
                DUMPED="$DUMPED$fqn "
                ran_count=$(printf '%s' "$ran" | LC_ALL=C awk 'NF { n++ } END { print n + 0 }')
                echo "   ($fqn ran $ran_count tests: $(printf '%s\n' "$ran" | cut -f3 | head -12 | tr '\n' ' ')…)"
                ;;
        esac
    elif [[ "$(printf '%s' "$line" | cut -f2)" == "FAIL" ]]; then
        MISSING+=("$fqn.$name — ran and did not pass")
    else
        echo "ok  $fqn.$name"
    fi
}

# MissionManager.scrub returns the scrubbed sortie's own open FLIGHT_PLAN generation ids, the way
# refly does, instead of only the id advance queued for the sortie that became current. A row that
# is QUEUED or RUNNING on that sortie is in the list; a row that already SUCCEEDED, FAILED or
# CANCELLED is not, and rows belonging to other sorties are not.
#   app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt
#   fun scrubbingASortieWithAnOpenFlightPlanGenerationReturnsThatRowsId()
require_test com.liftoff.app.data.MissionManagerTest \
    scrubbingASortieWithAnOpenFlightPlanGenerationReturnsThatRowsId

# The same for the RUNNING row: scrub is legal from PENDING, PLANNED and IN_FLIGHT, and a coach turn
# already running on the sortie being scrubbed is the one a user expects to stop.
#   fun scrubbingASortieReturnsItsOpenRunningFlightPlanRowToo()
require_test com.liftoff.app.data.MissionManagerTest \
    scrubbingASortieReturnsItsOpenRunningFlightPlanRowToo

# MissionManager.launch returns the launched PLANNED sortie's open FLIGHT_PLAN id: the Regenerate the
# user superseded by pressing Launch. (Decision: "Launch while a Regenerate is in flight: cancel the
# generation, or block the Launch?" — the plan cancels and lets Launch proceed.)
#   fun launchingAPlannedSortieWithAnOpenFlightPlanGenerationReturnsThatRowsId()
require_test com.liftoff.app.data.MissionManagerTest \
    launchingAPlannedSortieWithAnOpenFlightPlanGenerationReturnsThatRowsId

# Neither action gained a guard: with nothing open on the sortie, scrub still scrubs and launch still
# launches, and each returns an empty list rather than refusing.
# (Decision: "Scrub and Launch keep working exactly as they do today when nothing is open.")
#   fun scrubbingOrLaunchingASortieWithNoOpenGenerationReturnsNothingAndStillWritesTheSortie()
require_test com.liftoff.app.data.MissionManagerTest \
    scrubbingOrLaunchingASortieWithNoOpenGenerationReturnsNothingAndStillWritesTheSortie

# The id advance queued for the sortie scrub made current still reaches the caller — now through the
# onQueued callback regenerateFlightPlan already uses — so the next sortie's plan is still enqueued.
# This case exists today and must keep its assertion in the new shape.
#   fun scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId()
require_test com.liftoff.app.data.MissionManagerTest \
    scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId

# The two channels apart: scrubbing a sortie that has its own open row while advance reuses a
# different sortie's row hands the scrubbed sortie's row back to cancel and gives onQueued the other
# id. This case exists today asserting advance's id alone; it must assert both now.
#   fun aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported()
require_test com.liftoff.app.data.MissionManagerTest \
    aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported

# Mission Control unreachable: the scrub still scrubs, queues nothing, and reports nothing to cancel.
# This case exists today and asserts scrub's old null return; it must keep asserting it in the new shape.
#   fun aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken()
require_test com.liftoff.app.data.MissionManagerTest \
    aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken

# The Launchpad's scrub: the view model cancels every id scrub handed back — the row reads back
# CANCELLED in the database — and the id scrub queued for the next sortie is still enqueued.
#   app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadScrubTest.kt
#   fun scrubFromTheLaunchpadCancelsTheScrubbedSortiesOpenFlightPlanGeneration()
require_test com.liftoff.app.ui.launchpad.LaunchpadScrubTest \
    scrubFromTheLaunchpadCancelsTheScrubbedSortiesOpenFlightPlanGeneration

# The Launchpad's Launch on a PLANNED sortie: the open row the Regenerate left is cancelled (reads
# back CANCELLED) and onLaunched still runs, so the user gets In-Flight.
#   app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadLaunchTest.kt  (new class)
#   fun launchFromTheLaunchpadCancelsTheSortiesOpenFlightPlanGenerationAndStillNavigates()
require_test com.liftoff.app.ui.launchpad.LaunchpadLaunchTest \
    launchFromTheLaunchpadCancelsTheSortiesOpenFlightPlanGenerationAndStillNavigates

# In-Flight's scrub: the view model cancels the in-flight sortie's open row and still enqueues the id
# it queued for the next sortie, with no failure message shown for a scrub that committed.
#   app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt
#   fun scrubFromInFlightCancelsTheSortiesOpenFlightPlanGenerationAndStillEnqueuesTheQueuedPlan()
require_test com.liftoff.app.ui.inflight.InFlightLandScrubTest \
    scrubFromInFlightCancelsTheSortiesOpenFlightPlanGenerationAndStillEnqueuesTheQueuedPlan

# The cancel outlives the screen: In-Flight closes as soon as the scrub commits (watchLeave) and
# Launch navigates to In-Flight, so a cancel started on the screen's own scope would be cut off and
# leave the coach turn running. Cancel with the screen's scope cancelled and the row still reaches
# CANCELLED. (Decision: "the cancels run on the shell's scope, not the screen's, and Launch navigates
# without waiting for them".) Written the way MissionViewModelResetTest separates screen and action
# scopes.
#   fun scrubFromInFlightCancelsTheOpenGenerationAfterTheScreenScopeIsCancelled()
require_test com.liftoff.app.ui.inflight.InFlightLandScrubTest \
    scrubFromInFlightCancelsTheOpenGenerationAfterTheScreenScopeIsCancelled

# Nothing else moved: every unit test the project already has still passes. The gate above is what
# runs them; this reads what ran and fails on any that did not pass.
total=0
BROKEN=()
for f in "$RESULTS"/TEST-*.xml; do
    [[ -f "$f" ]] || continue
    cls=$(basename "$f" .xml); cls=${cls#TEST-}
    while IFS=$'\t' read -r name status written; do
        [[ -z "$name" ]] && continue
        total=$((total + 1))
        [[ "$status" == "PASS" ]] || BROKEN+=("$cls.$written")
    done < <(ran_tests "$f")
done

echo

if [[ "$total" -eq 0 ]]; then
    echo "verify: FAIL — no unit tests ran." >&2
    exit 1
fi
if [[ ${#BROKEN[@]} -ne 0 ]]; then
    echo "verify: FAIL — $total unit tests ran and these ${#BROKEN[@]} did not pass:" >&2
    for b in "${BROKEN[@]}"; do
        echo "  - $b" >&2
    done
    exit 1
fi
if [[ ${#MISSING[@]} -ne 0 ]]; then
    echo "verify: FAIL — $total unit tests ran and passed, but these required tests are missing:" >&2
    for m in "${MISSING[@]}"; do
        echo "  - $m" >&2
    done
    exit 1
fi

echo "verify: $total unit tests ran and passed, including every test named above. Issue 57 done."
