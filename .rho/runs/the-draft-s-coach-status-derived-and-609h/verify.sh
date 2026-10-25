#!/usr/bin/env bash
# Verify: "The draft's coach status, derived and live from Room (no UI)"
# (brief.md; DESIGN.md §4.2, §9 'Launchpad', §10). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the plain-JUnit cases for the derivation, then the live draft
# through one subscription to LaunchpadStates.observe(), then the classes the brief's "Must not break" list names,
# then what the brief says not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# sixteen names under the two new sections are new to this run and none of them is in the tree today — the derivation
# does not exist, LaunchpadState.Draft carries no status, and nothing in the launchpad reads the generation table.
# Running this script against the untouched branch reports the suite passing and exactly those sixteen names as
# "did not run".
#
# What is NOT checked here, and why:
# - That the derivation lives in com.liftoff.app.domain, or that its signature carries no Android or Room type. The
#   plain-JUnit names below can only run against a derivation callable on the plain JVM over plain values (a status,
#   an error string, a Boolean), which is the brief's rule in practice; which file it sits in and what its
#   parameters are named is reviewed by Kyle in the pull request.
# - That the derivation test class runs without Robolectric, or that the live test uses no WorkManager. Both are
#   stated in the brief and in the comments below; a test that broke that rule would still pass these names, and the
#   shape of a test file is the owner's call.
# - That LaunchpadScreen was not redrawn. The brief allows a compile-only edit, so a diff of that file would fail
#   correct work; what is checked instead is that the existing rendered Launchpad tests — the draft's controls,
#   guards, scrub, Re-fly and the view model's Confirm — still pass untouched, which is what says the draft still
#   draws Confirm and "Continue without outline" and nothing else new.
# - Which flow carries configured-ness to the draft (MissionManager's Flow or an injected SettingsStore), and
#   whether LaunchpadViewModel or LaunchpadStates gained a constructor parameter. A plan decision, reviewed in the PR.
# - The wording of the neutral line a FAILED row with no reason text gets, or of any other user-visible copy. The
#   names below require that a failed status carries a reason, not that it reads a particular way.
# - The names of the status variants or of their "offers Confirm / Retry / Continue" accessors. Each case below
#   asserts which actions that status offers, whichever way the status exposes them.
# - That the draft's status is state only and reaches no UI. There is no UI in this run to draw it; the plan records
#   that as a decision, and the gate proves no screen changed by running the screen tests that already exist.
# - The Room schema's shape beyond "unchanged": no entity change and no migration, checked by comparing the exported
#   schema under app/schemas against the tree this run started from.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/the-draft-s-coach-status-derived-and-609h"

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
# "<name>\t<pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report. Tab-separated so
# a name is one field whatever it contains. Fail is worse than skipped, and skipped is worse than pass, so one
# ignored test marks its whole class.
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

# t <method>: the named test must have run in this invocation and passed. Gradle reports nothing for a test that
# was never written, so a name missing from this run's reports is a failure, and one that ran and did not pass is a
# failure too. Names are matched across every report, so which file and package a test sits in is the implementer's
# call; what it must show is written in the comment above it.
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
# Brief Done-when: "these tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This
# gate is also:
# - the compile check for the new derivation, for Draft carrying the status, for the DRAFT arm's two new joins, and
#   for every construction site of LaunchpadStates and LaunchpadViewModel — the brief's "update LaunchpadScreen only
#   as far as it needs to compile" is proven by this compiling, and a site left unupdated does not compile at all;
# - the brief's "Must not break" check for the whole suite: the six Launchpad test classes, the worker tests,
#   In-Flight, Landed, Mission Control, the domain tests, and the rest;
# - the check that no test hangs and nothing depends on the local clock (CI is ubuntu-latest, UTC), since
#   app/build.gradle.kts kills a test task that goes five minutes without finishing — the hang the brief warns about
#   for this run, which it says to re-run rather than fix, and which is also what bounds every wait in the new
#   Robolectric test the brief asks for.
# Stale reports are moved aside first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor holds test-results/binary/output.bin, and rm -rf on a
    # directory it is holding fails. No gradlew --stop here: a stop is machine-wide and would take down the daemon
    # of another run's worktree. If the move fails the gate is not run, and the caller retries.
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
# - the known flake (doneBodyweightSetStepperShowsBW, a Room pool closed under a concurrent observe());
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - a hung suite that wrote no report, which the build kills at five minutes — the hang the brief names.
# A compile error, or a real failed assertion other than the flake, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Unable to delete directory|exceeded.*timeout|Test Executor' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
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
    if transient_failure; then
        echo "  … attempt $attempt failed on the known flake or on the build itself; running the gate again"
    else
        echo "  … attempt $attempt failed on something real; not running the gate again"
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
# Brief: "A plain-Kotlin derivation … It takes the newest non-cancelled outline row's status, error and offline flag
# (or none), plus whether the coach is configured." and "Plain JUnit (no Robolectric) for each derivation case".
# Each name below calls the derivation directly with plain values — a GenerationStatus or no row, an error string or
# null, the offline flag, and the configured Boolean — and asserts which status comes back and which actions that
# status says it offers: Confirm only on editable; "Continue without outline" on planning, offline, failed and not
# configured; Retry only on failed. No Android context, no database, no screen: a case that needs one of those is not
# this derivation.
echo ""
echo "=== the derivation (plain JUnit) ==="

# Brief: "editable: no row … The pattern can be edited and Confirm is offered." With the coach configured and no
# outline row at all, the status is editable, Confirm is offered, and neither Retry nor "Continue without outline"
# is — a draft with nothing queued has nothing to retry and no reason to skip the outline.
t noOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone

# Brief: "editable: … or the newest row SUCCEEDED." A configured coach and a newest outline row that SUCCEEDED:
# editable again, Confirm offered, Retry and "Continue without outline" not offered.
t aSucceededOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone

# Brief: "planning: the row is QUEUED or RUNNING and not offline." Both open statuses, each with the offline flag
# clear, give planning; "Continue without outline" is offered and Confirm is not, because the pattern is frozen
# while a generation is open.
t queuedAndRunningOutlineRowsArePlanningAndOfferContinueWithoutOutlineButNotConfirm

# Brief: "offline: the row is open and marked offline." A QUEUED row and a RUNNING row each carrying the offline
# mark (the mark the worker writes on a row still waiting on the coach) give offline, not planning; "Continue without
# outline" is offered and Confirm is not.
t anOpenOutlineRowCarryingTheOfflineMarkIsOfflineRatherThanPlanning

# Brief: "failed: carries the reason written on the row, word for word, and offers Retry." Assert the exact string
# the row carries. Use one reason of the kind a worker writes and one of the kind a validator writes — with
# punctuation and inner spacing — so a rewrite, a trim or a reassembly of the reason is caught. Retry and "Continue
# without outline" are offered; Confirm is not.
t aFailedOutlineRowReportsFailedWithItsReasonWordForWordAndOffersRetryAndContinueWithoutOutline

# Brief leaves open what a FAILED row with no reason text means (markFailed always writes one, so only an imported
# or hand-written row reaches this). Plan Decision "A FAILED row that carries no reason text gets a fixed neutral
# line": the failure is still reported — the status is failed, Retry and "Continue without outline" are offered — and
# it carries a reason that is not empty, rather than losing the failure or reporting nothing. The copy itself is the
# owner's call in review.
t aFailedOutlineRowWithNoReasonTextStillReportsFailedAndOffersRetryWithAReason

# Brief: "not configured: the host or token is blank and no row is open." With no outline row and the coach not
# configured, the status is not configured; "Continue without outline" is offered and Confirm is not — Confirm would
# only come back as "Mission Control isn't set up".
t aBlankHostOrTokenWithNoOutlineRowIsNotConfiguredAndOffersContinueWithoutOutlineButNotConfirm

# Plan Decision "A SUCCEEDED row with a blank host or token shows 'not configured', the same as no row": a newest
# outline row that SUCCEEDED with the host or token blank is not configured, not editable. The brief groups
# SUCCEEDED with no row, and its not-configured clause only requires that no row be open.
t aSucceededOutlineRowWithBlankSettingsIsNotConfiguredRatherThanEditable

# Plan Decision "When a FAILED or open row exists, the row decides; the settings decide only when there is no row
# worth reporting": a FAILED row with the host or token blank is still failed, still carries its reason word for
# word, and still offers Retry — a blank setting must not hide the reason the owner needs.
t aFailedOutlineRowOutranksBlankSettingsAndKeepsItsReasonAndRetry

# Plan Decision "An open row marked offline outranks the settings too": a QUEUED or RUNNING row with the host or
# token blank is planning, and the same rows carrying the offline mark are offline — never not configured. The mark
# is what actually happened to that generation; "not configured" would be a guess about why.
t anOpenOutlineRowOutranksBlankSettingsAndIsPlanningOrOffline

# Plan Decision "A CANCELLED status is treated as no row": the DAO's read never hands one over, but the status enum
# has CANCELLED, so the derivation has to answer it. Handed a CANCELLED row it must behave exactly as it does with
# no row at all — editable with a configured coach, not configured with a blank one — not planning and not failed.
t aCancelledStatusIsTreatedAsNoOutlineRow

# ─── 3. The live draft: one subscription, in-memory Room ───────────────────────
# Brief: "Robolectric (@Config(sdk = [34]), in-memory Room, every wait bounded, CI ubuntu-latest UTC; no
# WorkManager). With one subscription to LaunchpadStates.observe() for a current-week draft: writing a QUEUED
# OUTLINE row emits planning, GenerationDao.setCoachOfflineMark(id, Generation.COACH_OFFLINE) emits offline, and
# markFailed(id, "reason", t) emits failed with that reason, all without re-subscribing."
# Each name below is the same harness as LaunchpadStatesTest: in-memory Room, a real SettingsStore over a test
# DataStore, a MissionManager on a fixed UTC clock, and host and token set unless the case says otherwise.
# "One subscription" is the point of these cases: the collector is started once, before the write, and every
# emission read below comes out of that one collection — a second observe() or a fresh .first after the write
# re-subscribes and proves nothing about the draft being live. Every wait is bounded, and the draft seen before the
# write is the current week's Draft.
echo ""
echo "=== the draft's status, live from Room (one subscription) ==="

# Brief: "writing a QUEUED OUTLINE row emits planning". The draft seen first is editable; inserting one QUEUED
# OUTLINE Generation row for that draft's own mission — the way Confirm's row looks, not a FLIGHT_PLAN row — must
# make the same subscription emit a Draft whose status is planning, without re-subscribing.
t theDraftReportsPlanningWhenAQueuedOutlineRowIsWrittenToRoom

# Brief: "GenerationDao.setCoachOfflineMark(id, Generation.COACH_OFFLINE) emits offline". Within one subscription,
# after that QUEUED row, marking the row with the coach-offline error must emit a Draft whose status is offline — the
# row is still QUEUED, so this is the mark alone, not a failure.
t theDraftReportsOfflineWhenItsOutlineRowTakesTheCoachOfflineMark

# Brief: "markFailed(id, "reason", t) emits failed with that reason". Within one subscription, marking that row
# failed with a reason must emit a Draft whose status is failed and whose reason is that exact string — the reason
# written on the row reaching the draft word for word, not a paraphrase of it.
t theDraftReportsFailedWithTheExactReasonItsOutlineRowWasMarkedFailedWith

# Brief: "doesn't re-emit for unrelated changes", and Plan Decision "'Unrelated' means a change that cannot change
# the answer". On one subscription, with the draft already seen, each of these writes must leave the collected
# emissions unchanged — no new Draft, not even an identical one, within a bounded wait:
# - a generation row belonging to a different mission (Room's invalidation tracker fires on any write to the
#   generation table, so this is what a naive join would re-emit for);
# - a FLIGHT_PLAN generation row for the same mission;
# - a CANCELLED outline row for the same mission, inserted already cancelled — not cancelling a row the draft is
#   currently showing, which would legitimately change the status.
t theDraftDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow

# Brief: the draft's status also follows the settings. With host and token blank the draft seen by one subscription
# is not configured; setting a weight unit — a setting that leaves the coach exactly as configured as before — must
# emit no new Draft; then filling in a host and a token must turn that same subscription's draft to editable,
# without re-subscribing.
t theDraftFollowsTheSettingsFromNotConfiguredToEditableWithoutReSubscribing

# ─── 4. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the Launchpad tests (draft controls, guards, scrub, Re-fly, states, Confirm), the worker
# tests, In-Flight, Landed, Mission Control, the domain tests, and the build gate." The gate above runs the whole
# suite; these name the classes this run reaches underneath.
# The six Launchpad classes are the brief's own list, and they are also what proves the brief's "Update
# LaunchpadScreen only as far as it needs to compile. Don't change what it draws": they render the draft and assert
# the controls it draws and the actions they take, so a draft that started drawing something new, or stopped drawing
# Confirm or "Continue without outline", fails them. LaunchpadStatesTest is the one that keeps today's behaviour for
# every state the brief does not touch — Loading, Planned, Pending, InFlight, Closed — and the live transitions
# between them.
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadStatesTest
c LaunchpadConfirmTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadReFlyTest
c LaunchpadScrubTest

# The worker tests read the Generation entity, its offline flag and the DAO helpers this run reads from, and the
# enqueuer and canceller are what Confirm and the way out drive. Nothing here changes them.
c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationDaoTest
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest

# The settings flow this run joins into the draft is the one Mission Control writes through.
c SettingsStoreTest
c MissionControlScreenTest
c MissionControlViewModelTest
c ConnectionViewModelTest
c DaemonConnectionCheckTest

# In-Flight, Landed and the shell compose the same screens and states the draft arm is derived beside.
c InFlightStatesTest
c InFlightScreenTest
c InFlightViewModelTest
c InFlightLandScrubTest
c InFlightLeaveTest
c InFlightRunTest
c LandedStatesTest
c LiftoffShellTest
c ShellNavTest
c MainActivityTest

# The domain tests the brief names, and the schema test that says the exported schema still matches the database.
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
c ReFlyTest
t exportedV1SchemaMatchesDatabase

# ─── 5. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a new version file or a rewritten one is a schema change.
# The brief adds no entity and no migration, and the run's own DAO read already exists, so nothing here should move.
echo ""
echo "=== the Room schema ==="

# The baseline is the commit that ADDED this run's brief, not the newest commit that touched it, so the comparison
# does not move if the brief is ever edited during the run.
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
        # git diff cannot see an untracked file, and a Room entity change exports a NEW version file next to 1.json.
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
