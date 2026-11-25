#!/usr/bin/env bash
# Verify: "Swap the current sortie's type (lift <-> run) mid-week" (brief.md; issue corporealshift/liftoff#78).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Checked: the CI gate (./gradlew :app:assembleDebug :app:testDebugUnitTest) is green, and named
# cases for each item in the brief's Tests list ran and passed:
# - domain: the flip maps L<->R, sets the default focus, clears the rationale, and changes the
#   pattern letter at that index only; PLANNED -> PENDING is legal
# - MissionManager: the gates, superseding open generations, deleting the old plan, the simple run
#   plan when run generation is off, and the queued FLIGHT_PLAN generation otherwise
# - the prompt inputs carry the new type and default focus
# - Launchpad: the action is shown or hidden under Regenerate's conditions, and the confirm dialog
#   appears only for a PLANNED sortie with a generated plan
#
# Not checked:
# - The DESIGN.md amendment. It can only be checked by its wording; the owner reviews it in the PR.
# - The plan's Decisions (the label naming the target type, swapping twice, no undo, the outline
#   notes left as they are, this week's Mission only). The owner reviews them in the PR.
# - The export. It writes mission.pattern straight from the row the manager cases prove flipped;
#   DatabaseExportTest still runs as part of the gate.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch lives under app/build, which is gitignored.
WORK="app/build/verify78"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1

# ---------------------------------------------------------------------------
# The Gradle command. On Windows (Git Bash) use gradlew.sh, the repo's own wrapper for this machine.
# Elsewhere use the pinned toolchain if present, else ./gradlew.
# ---------------------------------------------------------------------------
RUNNER=()
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        RUNNER=(bash gradlew.sh)
        ;;
    *)
        TOOLCHAIN="${LIFTOFF_TOOLCHAIN:-$HOME/android-toolchain}"
        if [ -z "${JAVA_HOME:-}" ] && [ -d "$TOOLCHAIN/jdk" ]; then
            export JAVA_HOME="$TOOLCHAIN/jdk"
        fi
        if [ -z "${ANDROID_HOME:-}" ] && [ -d "$TOOLCHAIN/sdk" ]; then
            export ANDROID_HOME="$TOOLCHAIN/sdk"
            export ANDROID_SDK_ROOT="$TOOLCHAIN/sdk"
        fi
        if [ -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
            RUNNER=("$TOOLCHAIN/gradle/bin/gradle")
        elif [ -x ./gradlew ]; then
            RUNNER=(./gradlew)
        else
            RUNNER=(bash gradlew.sh)
        fi
        ;;
esac

log() { printf '%s\n' "$*"; }

# ---------------------------------------------------------------------------
# Index Gradle's JUnit XML by class.method and by class. Each name keeps the worst outcome it had.
# ---------------------------------------------------------------------------
index_reports() {
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function flush() {
                if (intest) {
                    name = substr(name, 1, 100)
                    gsub(/`/, "", name)
                    sub(/\(.*/, "", name)
                    if (name != "") {
                        print "Q\t" class "." name "\t" status
                        print "C\t" class "\t" status
                    }
                }
            }
            function rank(s) {
                if (s == "fail") return 3
                if (s == "skip") return 2
                return 1
            }
            function worse(a, b) { if (rank(a) > rank(b)) return a; return b }
            { gsub(/\r/, "") }
            /<testsuite / {
                match($0, /name="[^"]*"/)
                class = substr($0, RSTART + 6, RLENGTH - 7)
                next
            }
            /<testcase/ {
                flush()
                intest = 1
                status = "pass"
                match($0, /name="[^"]*"/)
                name = substr($0, RSTART + 6, RLENGTH - 7)
                if ($0 ~ /<skipped[ \/>]/) status = worse(status, "skip")
                if ($0 ~ /<failure|<error/) status = worse(status, "fail")
                if ($0 ~ /<\/testcase>/ || $0 ~ /\/>[ \t]*$/) { flush(); intest = 0 }
                next
            }
            /<skipped[ \/>]/ { if (intest) status = worse(status, "skip"); next }
            /<failure|<error/ { if (intest) status = worse(status, "fail"); next }
            /<\/testcase>/ { flush(); intest = 0; next }
            END { flush() }
        ' "$file" >> "$WORK/raw"
    done

    : > "$WORK/all"
    : > "$WORK/qualified"
    : > "$WORK/classes"
    if [ -s "$WORK/raw" ]; then
        awk -F '\t' '
            function rank(s) { if (s == "fail") return 3; if (s == "skip") return 2; return 1 }
            { key = $1 SUBSEP $2
              if (!(key in seen) || rank($3) > rank(seen[key])) seen[key] = $3 }
            END { for (k in seen) { split(k, p, SUBSEP); print p[1] "\t" p[2] "\t" seen[k] } }
        ' "$WORK/raw" > "$WORK/all"
        awk -F '\t' '$1 == "Q" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/qualified"
        awk -F '\t' '$1 == "C" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/classes"
    fi
}

# ---------------------------------------------------------------------------
# 1. The CI gate. Old reports are moved aside so every case below comes from this run.
# ---------------------------------------------------------------------------
clear_results() {
    if [ -d "$RESULTS" ]; then
        # Moved, not deleted: a stranded test executor can hold files open.
        local aside="app/build/verify-old-results-$1"
        rm -rf "$aside" 2>/dev/null || true
        mv "$RESULTS" "$aside" 2>/dev/null || true
    fi
}

# Retry only when Gradle itself broke or only the known-flaky classes failed.
transient_failure() {
    local attempt="$1"
    local gate_log="$WORK/gate-$attempt.log"

    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$gate_log" 2>/dev/null; then
            return 1
        fi
        log "  … Gradle wrote no test reports at all; running the gate again"
        return 0
    fi

    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|Test Executor|PersistentEnumerator storage corrupted|Unable to delete directory' "$gate_log" 2>/dev/null; then
        log "  … the build itself broke; running the gate again"
        return 0
    fi

    local any other
    any="$(awk -F '\t' '$2 == "fail" { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$any" -eq 0 ]; then
        log "  … the build failed without failing a test; running the gate again"
        return 0
    fi
    other="$(awk -F '\t' '$2 == "fail" && !($1 ~ /(^|\.)LiftoffShellTest\./) && !($1 ~ /(^|\.)InFlightScreenTest\./) { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$other" -eq 0 ]; then
        log "  … only the recorded flaky classes failed; running the gate again"
        return 0
    fi
    return 1
}

log "=== gate: ${RUNNER[*]} :app:assembleDebug :app:testDebugUnitTest ==="

gate_status="red"
last_log="$WORK/gate-1.log"
for attempt in 1 2 3; do
    clear_results "$attempt"
    log "--- attempt $attempt ---"
    rc=0
    last_log="$WORK/gate-$attempt.log"
    timeout 1800 "${RUNNER[@]}" :app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain > "$last_log" 2>&1 || rc=$?
    index_reports
    if [ "$rc" -ne 0 ] && transient_failure "$attempt"; then
        continue
    fi
    if [ "$rc" -eq 0 ]; then
        if grep -E '^> Task :app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$last_log" 2>/dev/null; then
            log "  … the test task was served from cache or skipped; that is not a run of the current tree"
            gate_status="red"
            break
        fi
        gate_status="green"
    else
        gate_status="red"
    fi
    break
done

passed=0
failed=0
ok()  { passed=$((passed + 1)); log "  ok   $*"; }
bad() { failed=$((failed + 1)); log "  FAIL $*"; }

if [ "$gate_status" = "green" ]; then
    ok "gate: :app:assembleDebug :app:testDebugUnitTest"
else
    bad "gate: :app:assembleDebug :app:testDebugUnitTest"
    log "--- last gate output (tail) ---"
    tail -120 "$last_log" 2>/dev/null || true
    for f in "$WORK"/gate-*.log; do
        if [ "$f" = "$last_log" ]; then continue; fi
        log "--- $f (tail) ---"
        tail -40 "$f" 2>/dev/null || true
    done
fi

if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/qualified"; : > "$WORK/classes"
else
    index_reports
    log "  … $(wc -l < "$WORK/qualified") test records, $(wc -l < "$WORK/classes") classes indexed"
fi

# t <class> <method>: the case ran in this gate and passed.
t() {
    local cls="$1" method="$2" want status
    want="$(printf '%s' "$method" | tr -d '`')"
    status="$(awk -F '\t' -v k="$cls.$want" '{ p = index($1, "." k); if ($1 == k || (p > 0 && p + length(k) == length($1))) { print $2; exit } }' "$WORK/qualified")"
    case "$status" in
        pass) ok "$cls.$want" ;;
        fail) bad "$cls.$want — ran and FAILED" ;;
        skip) bad "$cls.$want — ran but was SKIPPED" ;;
        *)    bad "$cls.$want — did not run at all" ;;
    esac
}

# c <class>: every test Gradle recorded for this class passed.
c() {
    local cls="$1" status
    status="$(awk -F '\t' -v c="$cls" '{ p = index($1, "." c); if ($1 == c || (p > 0 && p + length(c) == length($1))) { print $2; exit } }' "$WORK/classes")"
    case "$status" in
        pass) ok "class $cls — every test in it passed" ;;
        fail) bad "class $cls — a test in it FAILED" ;;
        skip) bad "class $cls — a test in it was SKIPPED" ;;
        *) bad "class $cls — ran no tests at all" ;;
    esac
}

# ===========================================================================
# 2. Domain (pure Kotlin, no device).
# Brief Tests: "the flip maps L<->R and sets the default focus; the pattern letter changes only at
# that index".
# ===========================================================================
log ""
log "=== domain: the flip ==="

# Must show: a lift sortie becomes a run and a run becomes a lift; the pattern letter at
# sortie.index flips and every other letter and the length stay the same (R L R L R at index 1 ->
# R R R L R); a PENDING or PLANNED sortie comes out PENDING.
t SortieSwapTest swapSortieTypeFlipsTheTypeAndThePatternLetterOfTheSameIndex

# Must show: after the flip the focus is the new type's "Continue without outline" default ("easy"
# for a run, "full body" for a lift) and focusRationale is cleared, even when the old focus was
# edited by hand (plan decision "A focus the user set for that sortie is replaced by the new type's
# default").
t SortieSwapTest swapSortieTypeSetsTheNewTypesDefaultFocusAndClearsTheOldRationale

# Must show: PLANNED -> PENDING is legal (the swap sends a PLANNED sortie back to PENDING), and every
# other transition that was illegal before is still rejected.
t SortieSwapTest plannedToPendingIsALegalTransitionAndEveryOtherIllegalTransitionIsStillRejected

# ===========================================================================
# 3. MissionManager (Robolectric, in-memory Room).
# Brief Tests: "rejects a sortie that isn't current, isn't PENDING/PLANNED, or isn't in the current
# week's ACTIVE Mission; supersedes open generations; deletes the old plan; creates a simple run plan
# when run generation is off; queues a FLIGHT_PLAN generation otherwise".
# ===========================================================================
log ""
log "=== MissionManager: the swap ==="

# Must show: on a PENDING current sortie of this week's ACTIVE Mission, the same Mission row gets the
# flipped letter at that index, the same sortie row gets the new type and default focus, and no
# other sortie or pattern letter changes.
t MissionManagerSwapTest swapOnAPendingSortieFlipsTheSortieAndThePatternLetterInPlace

# Must show: on a PLANNED sortie with a generated Flight Plan (coach configured), the old plan's rows
# (exercises, sets, segments) are gone and a new FLIGHT_PLAN generation is queued for the swapped
# sortie. Lift -> run with Generate run plans on also queues a generation.
t MissionManagerSwapTest swapOnAPlannedSortieDeletesItsFlightPlanAndQueuesAFreshFlightPlanGeneration

# Must show: the sortie's open FLIGHT_PLAN generations from before the swap are returned for the
# caller to cancel, and the generation the swap queued is not among them.
t MissionManagerSwapTest swapReturnsTheSortiesOpenGenerationsToCancelButNotTheOneItQueued

# Must show: swapping a lift to a run with Generate run plans off writes the simple "Run" plan, leaves
# the sortie PLANNED and queues no generation.
t MissionManagerSwapTest swapToARunWithRunGenerationOffWritesASimpleRunPlanAndQueuesNothing

# Must show: with the coach not configured, a swap that needs a generation still flips the type, the
# pattern letter and the focus, leaves the sortie PENDING and queues nothing.
t MissionManagerSwapTest swapWithTheCoachNotConfiguredFlipsTheSortieAndLeavesItPending

# Must show: a sortie that is not the current one, a sortie of a Mission that is not ACTIVE, and a
# sortie of a Mission that is not the current week's are each refused, and nothing is written.
t MissionManagerSwapTest swapRefusesASortieThatIsNotCurrentOrNotInTheCurrentWeeksActiveMission

# Must show: an IN_FLIGHT, LANDED or SCRUBBED sortie is refused, and nothing is written.
t MissionManagerSwapTest swapRefusesAnInFlightLandedOrScrubbedSortie

# ===========================================================================
# 4. The coach prompt.
# Brief Tests: "the prompt for the swapped sortie carries the new type and default focus".
# ===========================================================================
log ""
log "=== the swapped sortie reaches the coach's prompt ==="

# Must show: after a swap, the prompt inputs loaded from the real rows carry the swapped sortie with
# its new type and default focus and no rationale, and the week's pattern shows the flipped letter.
t PromptInputLoaderSwapTest promptInputsForASwappedSortieCarryItsNewTypeAndDefaultFocus

# ===========================================================================
# 5. The Launchpad.
# Brief Tests: "action shown/hidden under the same conditions; confirm dialog only for a generated
# PLANNED plan".
# ===========================================================================
log ""
log "=== the Launchpad ==="

# Must show: the swap action is on the current sortie's PENDING and PLANNED cards, and not on the
# Draft, IN_FLIGHT or Closed cards.
t LaunchpadSwapTest swapActionIsOfferedOnPendingAndPlannedAndOnNoOtherState

# Must show: the action is absent when the Mission is not ACTIVE or the sortie is not the current one.
t LaunchpadSwapTest swapActionIsAbsentWhenTheMissionIsNotActiveOrTheSortieIsNotCurrent

# Must show: on a PLANNED card whose plan is GENERATED, tapping the action opens a confirm dialog, and
# cancelling it leaves the type, the pattern letter and the plan as they were.
t LaunchpadSwapTest swapOnAPlannedCardWithAGeneratedPlanAsksForConfirmationAndACancelWritesNothing

# Must show: on a PENDING card, and on a PLANNED card whose plan is SIMPLE_RUN or REFLY, tapping the
# action runs the swap with no dialog (plan decision "A re-flown plan is deleted without a dialog").
t LaunchpadSwapTest swapOnAPendingOrNonGeneratedPlannedCardRunsWithoutAConfirmDialog

# Must show: confirming the dialog runs the swap (the card shows the new type) and the open
# generations the manager returned are cancelled.
t LaunchpadSwapTest confirmingTheSwapDialogRunsTheSwapAndCancelsTheOpenGenerations

# ===========================================================================
# 6. Neighbours. The gate already runs everything; these catch one renamed away or skipped.
# ===========================================================================
log ""
log "=== the neighbours ==="

c SortiesTest
c MissionsTest
c MissionManagerTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c MissionManagerResetTest
c PromptInputLoaderTest
c CoachPromptBuilderTest
c CoachPromptGoldenFileTest
c DatabaseExportTest
c GenerationCancellerTest
c GenerationWorkerTest
c LaunchpadRegenerateTest
c LaunchpadPlannedActionsTest
c LaunchpadScrubTest
c LaunchpadReFlyTest
c LaunchpadStatesTest
c PatternTrackTest

# ===========================================================================
# Result
# ===========================================================================
log ""
log "=== Result: $passed passed, $failed failed ==="
if [ "$failed" -gt 0 ]; then
    log "NOT DONE: $failed check(s) failed." >&2
    exit 1
fi
log "BRIEF DONE."
