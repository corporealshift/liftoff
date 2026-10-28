#!/usr/bin/env bash
# Verify: "Sortie generation status on the Launchpad, derived and live (no UI)"
# (brief.md; DESIGN.md §5.4, §9 'Launchpad', §10). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the plain-JUnit cases for the new derivation, then the live
# PENDING and PLANNED cards through one subscription to LaunchpadStates.observe(), then the classes the brief's
# "Must not break" list names, then what the brief says not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# eighteen names under the two new sections are new to this branch — no derivation for a sortie exists, no Launchpad
# state carries a generation status for a sortie, and nothing in the launchpad reads a sortie's generation row.
#
# What is checked, in one line: a plain-Kotlin derivation answers a sortie's newest non-cancelled generation row
# (planning / offline / failed with its reason and its validator errors split out / not configured / re-planning /
# idle), carries the rho session id the row carries, and answers the §5.4 Re-fly rule by itself; the PENDING and
# PLANNED cards follow that answer live from Room, one subscription at a time, without re-emitting for anything that
# cannot change it.
#
# What is NOT checked here, and why:
# - That the derivation lives in com.liftoff.app.domain, what its type and function are called, or that its signature
#   carries no Android or Room type. The plain-JUnit names below can only run against a derivation callable on the
#   plain JVM over plain values, which is the brief's rule in practice. The rest is reviewed in the pull request.
# - The names of the status variants, of the field each of Pending and Planned carries it in, or of any "offers
#   Re-fly" accessor. Each case below asserts which answer comes back and whether Re-fly is offered.
# - The wording of the fallback reason for a FAILED row with no error text, or of the "nothing was queued" reason for
#   a PENDING sortie with no row at all. The plan records both as open wording; the names below require that a
#   reason is there, not what it says.
# - That the draft's CoachStatus was left alone: CoachStatusTest and LaunchpadCoachStatusTest passing is what says the
#   draft still answers the same situations the same way.
# - Anything rendered. The brief is "no UI" and limits LaunchpadScreen to compiling while Re-fly stays gated on
#   reFlyAvailable; the gate compiling it and the existing rendered Launchpad classes passing is the check. The plan's
#   review-added Decision about the "No earlier plan to re-fly." line is reviewed in the pull request.
# - Which existing Launchpad test had to be updated to the §5.4 rule. The brief asks only that the PR name them.
# - The plan's Decisions. The owner reviews them in the pull request, so none gets a test of its own here. Where a
#   named test below happens to show one, its comment names it.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/sortie-generation-status-on-the-nwwr"

# Scratch lives under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote. It writes $WORK/methods and $WORK/classes as
# "<name>\t<pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report.
index_reports() {
    local file
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
            function rec(n, s) { if (!(n in best) || rank(s) > best[n]) best[n] = rank(s) }
            /<testcase[ \t]/ {
                head = index($0, "<testcase")
                rest = substr($0, head)
                at = index(rest, " name=\"")
                if (at == 0) next
                tail = substr(rest, at + 7)
                q = index(tail, "\"")
                if (q == 0) next
                name = substr(tail, 1, q - 1)
                br = index(name, "[")
                if (br) name = substr(name, 1, br - 1)
                cn = ""
                at = index(rest, " classname=\"")
                if (at) {
                    tail = substr(rest, at + 12)
                    q = index(tail, "\"")
                    if (q) cn = substr(tail, 1, q - 1)
                }
                cur = name; pend = 1; st = "pass"
                if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) st = "fail"
                else if ($0 ~ /<skipped/) st = "skipped"
                if ($0 ~ /\/>[ \t]*$/ || $0 ~ /<\/testcase>/) {
                    rec(cur, st); if (cn != "") rec("C:" cn, st); pend = 0; cur = ""
                }
                next
            }
            pend && /<(failure|error)[ \t\r\n>\/]/ { st = "fail" }
            pend && /<skipped/ { if (st == "pass") st = "skipped" }
            pend && /<\/testcase>/ { rec(cur, st); if (cn != "") rec("C:" cn, st); pend = 0; cur = "" }
            END { for (n in best) printf "%s\t%s\n", n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        BEGIN { FS = "\t" }
        function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) printf "%s\t%s\n", n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
    ' "$WORK/raw" > "$WORK/all"
    grep '^C:' "$WORK/all" | sed 's/^C://' > "$WORK/classes" || true
    grep -v '^C:' "$WORK/all" > "$WORK/methods" || true
}

# t <method>: the named test must have run in this invocation and passed. Names are matched across every report, so
# which file and package a test sits in is the implementer's call; what it must show is written in the comment above.
t() {
    local name="$1" st
    st="$(awk -F '\t' -v m="$name" '$1 == m { print $2; exit }' "$WORK/methods" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$name" ;;
        fail)    bad "$name ran but did not pass" ;;
        skipped) bad "$name was skipped" ;;
        none)    bad "$name did not run" ;;
    esac
}

# c <ClassName>: the class has to have run, and every test in it has to have passed.
c() {
    local cls="$1" st
    st="$(awk -F '\t' -v m="$cls" 'index($1, "." m) == length($1) - length(m) { print $2; exit }' "$WORK/classes" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$cls ran and every test in it passed" ;;
        fail)    bad "$cls ran but something in it did not pass" ;;
        skipped) bad "$cls ran but a test in it was skipped" ;;
        none)    bad "$cls did not run" ;;
    esac
}

# ─── 1. CI gate: the two tasks ci.yml runs ─────────────────────────────────────
# Brief Done-when: "these tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This is
# also the compile check for every construction site and reader of Pending and Planned (LaunchpadScreen included),
# and the brief's "Must not break" check for the whole suite. Stale reports are moved aside first, so every named test
# below comes from this run. --no-build-cache stops the reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor can hold files under test-results. No gradlew --stop here:
    # a stop is machine-wide and would take down another worktree's daemon.
    local stamp
    stamp="$(date +%s)"
    mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || true
    [ -d "$RESULTS" ] && return 1
    return 0
}

run_gate() {
    local n="$1"
    clear_results || { echo "could not clear $RESULTS" | tee "$WORK/gate-$n.log"; return 2; }
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these get another gate:
# - the known hang (the brief's "The full gate can hang once for about 5 minutes on this machine ... re-run it");
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random.
# A compile error, or any other reported assertion failure, is a real result and is not retried.
transient_failure() {
    local log="$WORK/gate-$1.log"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted' "$log" 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk -F '\t' '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk -F '\t' '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    if [ "$any" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
gate_log=""
for attempt in 1 2 3; do
    gate_log="$WORK/gate-$attempt.log"
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure "$attempt"; then
        echo "  … attempt $attempt failed on the hang or on the build itself; running the gate again"
    else
        echo "  … attempt $attempt failed on a reported test result; not running the gate again"
        break
    fi
done

if [ "$gate_ok" -eq 1 ]; then
    ok "the CI gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 30 minutes"
fi
if [ -n "$gate_log" ] && grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$gate_log" 2>/dev/null; then
    bad "unit tests executed in the gate run (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/classes"
else
    index_reports
    awk '
        /<testsuite / {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^tests=/) { split($i, a, "\""); tests += a[2] }
                if ($i ~ /^failures=/) { split($i, a, "\""); failures += a[2] }
                if ($i ~ /^errors=/) { split($i, a, "\""); errors += a[2] }
                if ($i ~ /^skipped=/) { split($i, a, "\""); skipped += a[2] }
            }
        }
        END { printf "  … reports: %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped }
    ' "$RESULTS"/TEST-*.xml
fi

# ─── 2. The derivation: plain JUnit, no Robolectric, no Room ───────────────────
# Brief: "A plain-Kotlin derivation in com.liftoff.app.domain, with no Android or Room types in its signature ..." and
# "Plain JUnit for each derivation case, including the §5.4 Re-fly rule and a failed row with several validator
# errors". Each name below calls that derivation with plain values: a GenerationStatus or no row, the row's error
# text or null, the offline mark, the session id or null, whether the coach is configured, and whether a re-fly source
# plan exists. No Android context, no database, no screen, no coroutine.
echo ""
echo "=== the derivation (plain JUnit) ==="

# Brief: "planning (row open, not offline)". A QUEUED row and a RUNNING row, offline mark clear, coach configured.
t anOpenFlightPlanRowThatIsNotMarkedOfflineIsPlanning

# Brief: "offline (row open and offline)". A QUEUED and a RUNNING row carrying Generation.COACH_OFFLINE answer offline.
# Also shows Plan Decision "A generation that gave up on an unreachable Mission Control reads as a failure, not as
# offline": a FAILED row whose reason is "coach unreachable" answers failed.
t anOpenRowCarryingTheOfflineMarkIsOfflineAndAFailedRowCarryingItIsFailed

# Brief: "failed (the reason ...)". The exact string the row carries comes back as the reason. Also shows Plan
# Decision "A FAILED row answers on its own even if the coach is no longer set up": with host or token blank, a
# FAILED row still answers failed with its own reason.
t aFailedRowReportsFailedWithItsReasonWordForWord

# Brief: "the validator errors split out of the stored error" and "a failed row with several validator errors". The
# stored text is what GenerationWorker writes today, "invalid output: " + errors.joinToString("; "), with at least
# three errors. Answer: failed, reason "invalid output", and every validator line in the worker's order. Plan
# Decision "Every validator line is kept, and the reason is the lead sentence".
t theValidatorErrorsStoredOnARejectedReplyAreSplitOutOfTheReasonInOrder

# A FAILED row whose error is null or blank still answers failed with a non-empty reason (wording is open).
t aFailedRowWithNoErrorTextStillReportsFailedWithAFallbackReason

# Brief: "If there is no row and the coach is configured, treat it as failed with a short reason". No row, coach
# configured: failed with a non-empty reason (Plan Decision on the "no row" wording; the wording is not asserted).
t aSortieWithNoGenerationRowAndAConfiguredCoachIsFailedWithAReason

# Brief: "not configured (no open row, and the host or token is blank)". No row, coach not configured — a blank host
# and a blank token each make coachIsConfigured false — answers not configured.
t aSortieWithNoGenerationRowAndABlankHostOrTokenIsNotConfigured

# The derivation is over "the newest non-cancelled FLIGHT_PLAN row". A SUCCEEDED row and a CANCELLED row for a
# PENDING sortie are each answered as no row would be: failed with a reason when configured, not configured when not.
t aSucceededOrCancelledNewestRowIsAnsweredAsIfThereWereNoRow

# Brief: "For PLANNED it gives re-planning (a row is QUEUED or RUNNING)". A QUEUED and a RUNNING row each answer
# re-planning.
t aPlannedSortieWithAnOpenRowIsRePlanning

# Brief: "... or idle". A FAILED row, a SUCCEEDED row and no row each answer idle for a PLANNED sortie (Plan Decision
# "A PLANNED sortie whose re-plan failed shows idle, not the failure").
t aPlannedSortieWithAFailedOrSucceededRowOrNoRowIsIdle

# Brief: "It also gives the rho session id when the row has one." A row with nabuSessionId hands out that exact id;
# a row without one, and no row at all, hand out none.
t theRhoSessionIdIsHandedOutWhenTheRowHasOneAndNoneWhenItDoesNot

# Brief and §5.4: Re-fly "only when a source plan exists and the generation failed, the coach is offline, or no
# connection is configured". With a source plan, each of failed, offline (an open row carrying the offline mark — §10
# "Re-fly is available" while Mission Control is offline) and not configured offers Re-fly.
t aSourcePlanOffersReFlyWhenTheGenerationFailedIsOfflineOrUnconfigured

# §5.4 and Plan Decision "Re-fly disappears from a PENDING card while the coach is working": with a source plan and
# an open row that is NOT marked offline (QUEUED and RUNNING), the answer is planning and Re-fly is refused.
t aSourcePlanDoesNotOfferReFlyWhileAGenerationIsOpen

# §5.4: "only when a source plan exists". With no source plan, Re-fly is refused under every PENDING answer —
# planning, offline, failed and not configured.
t noSourcePlanOffersReFlyUnderNoAnswer

# ─── 3. The live cards: one subscription, in-memory Room ───────────────────────
# Brief: "Robolectric (@Config(sdk = [34]), in-memory Room, every wait bounded, CI ubuntu-latest UTC, no WorkManager):
# one subscription to a PENDING sortie's state follows its row from QUEUED to offline (setCoachOfflineMark) to FAILED
# (markFailed), and a PLANNED sortie shows re-planning when a new row is queued for it", and "LaunchpadStates joins
# the generation Flow and the settings only on those branches, and doesn't re-emit for unrelated changes."
#
# Harness as in LaunchpadCoachStatusTest: in-memory Room, a real SettingsStore, a real MissionManager on a fixed UTC
# clock, host and token set unless the case says otherwise, an ACTIVE week with a current sortie, generation rows
# inserted through GenerationDao with staggered createdAt. One collector is launched before the writes and stays open;
# every emission read comes from that one collection. Every wait is bounded.
echo ""
echo "=== the sortie's status, live from Room (one subscription) ==="

# The PENDING card is first planning; setCoachOfflineMark(id, Generation.COACH_OFFLINE) makes the same subscription
# emit offline; markFailed(id, reason, t) makes it emit failed with that exact reason. Same sortie id and index
# throughout. The plan also asks that once saveSessionId(id, ...) has written an id, the emitted card carries it.
t oneSubscriptionToAPendingSortieFollowsItsRowFromPlanningThroughOfflineToFailed

# A current PLANNED sortie with a plan; inserting a QUEUED FLIGHT_PLAN row for it makes the same subscription emit a
# Planned card that still shows that plan and now says re-planning.
t aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt

# The card reads this sortie's newest non-cancelled row: with a FAILED row for this sortie, a newer CANCELLED row for
# it, and a newer open row for another sortie, the card answers failed with this sortie's FAILED row's reason.
t theCardFollowsThisSortiesNewestNonCancelledRowAndNotACancelledOrAnotherSortiesRow

# Brief: "doesn't re-emit for unrelated changes". With the PENDING card seen, a plan written for a later sortie of
# the week, a QUEUED row for another sortie, and a settings write that leaves the coach configured as before (e.g. a
# weight-unit change) each produce no new emission within a bounded wait. Then a new open row for this sortie does
# produce one, proving the collector is still live.
t theCardDoesNotReEmitForAnotherSortiesRowAPlanForAnotherSortieOrAnUnchangedSetting

# ─── 4. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the Launchpad tests, the draft's coach status, generation queueing and Regenerate, the
# worker tests, In-Flight, Landed, Mission Control, the shell tests, the domain tests and the build gate." The gate
# runs the whole suite; these name the classes so one renamed away, skipped or deleted is caught. Launchpad classes
# may have assertions updated to the §5.4 rule (the brief allows it); they must still run and pass.
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadStatesTest
c LaunchpadConfirmTest
c LaunchpadReFlyTest
c LaunchpadScrubTest
c LaunchpadRegenerateTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest

# The draft's coach status, which the brief says not to change.
c CoachStatusTest
c LaunchpadCoachStatusTest
c LaunchpadDraftStatusTest

# Generation queueing and Regenerate.
c GenerationDaoTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c AppOpenSweepTest
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest

# The worker tests.
c GenerationWorkerTest
c CoachTurnRunnerTest

# In-Flight and Landed.
c InFlightStatesTest
c InFlightScreenTest
c InFlightViewModelTest
c InFlightLandScrubTest
c InFlightLeaveTest
c InFlightRunTest
c LandedStatesTest
c LandScrubDialogTest

# Mission Control.
c MissionControlScreenTest
c MissionControlViewModelTest
c EquipmentViewModelTest
c ConnectionViewModelTest
c DaemonConnectionCheckTest
c SettingsStoreTest

# The shell tests.
c LiftoffShellTest
c ShellNavTest
c MainActivityTest

# The domain tests, and the schema test that says the exported schema still matches the database.
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
c ReFlyTest
t exportedV1SchemaMatchesDatabase

# ─── 5. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." The exported schema under app/schemas is compared against the tree this run
# started from, committed and after the gate run; a rewritten 1.json or a new version file is a schema change.
echo ""
echo "=== the Room schema ==="

# The baseline is the commit that ADDED this run's brief, so the comparison does not move if the brief is edited.
brief_commit="$(git log --diff-filter=A --format=%H -- "$RUN_DIR/brief.md" | tail -1 || true)"
if [ -z "$brief_commit" ]; then
    bad "the Room schema is unchanged (this run's brief.md is not in the history to compare against)"
    base_commit=""
else
    base_commit="$(git rev-parse "$brief_commit^" || true)"
fi
if [ -n "$base_commit" ]; then
    if ! git diff --quiet "$base_commit" HEAD -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the committed tree)"
    elif ! git diff --quiet "$base_commit" -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the working tree after the gate run)"
    elif [ "$(git ls-tree -r --name-only "$base_commit" -- app/schemas | sort)" != "$(find app/schemas -type f 2>/dev/null | sort)" ]; then
        bad "the Room schema is unchanged (the set of files under app/schemas is not the set this run started from)"
    else
        ok "the exported Room schema is byte-identical to the one this run started from"
    fi
fi

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
