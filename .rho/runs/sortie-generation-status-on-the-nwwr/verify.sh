#!/usr/bin/env bash
# Verify: "Sortie generation status on the Launchpad, derived and live (no UI)"
# (brief.md; DESIGN.md §5.4, §9 'Launchpad', §10). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the plain-JUnit cases for the new derivation, then the live
# PENDING and PLANNED cards through one subscription to LaunchpadStates.observe(), then the one rendered case the
# plan asks for on the PENDING action row, then the classes the brief's "Must not break" list names, then what the
# brief says not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# nineteen names under the three new sections are new to this branch — no derivation for a sortie exists, no Launchpad
# state carries a generation status for a sortie, and nothing in the launchpad reads a sortie's generation row.
# Running this script against the untouched branch (it did, on 2026-10-28) reports the suite passing at 704 tests with
# 0 failures, every existing class and the schema check passing, and exactly those nineteen names as "did not run":
# 46 passed, 19 failed, exit 1. The first gate attempt hit this machine's known test-task hang and the retry ran the
# identical tree again, which is the behaviour the gate section below describes.
#
# What is checked, in one line: a plain-Kotlin derivation answers a sortie's newest non-cancelled generation row
# (planning / offline / failed with its reason and its validator errors split out / not configured / re-planning /
# idle), carries the rho session id the row carries, and answers the §5.4 Re-fly rule by itself; the PENDING and
# PLANNED cards follow that answer live from Room, one subscription at a time, without re-emitting for anything that
# cannot change it; and Re-fly is drawn only when the new rule says yes.
#
# What is NOT checked here, and why:
# - That the derivation lives in com.liftoff.app.domain, what its type and function are called, or that its signature
#   carries no Android or Room type. The plain-JUnit names below can only run against a derivation callable on the
#   plain JVM over plain values (a GenerationStatus or no row, an error string or null, the offline mark, the
#   configured Boolean, and whether a re-fly source exists), which is the brief's rule in practice. Which file it sits
#   in and what its parameters are named is reviewed by Kyle in the pull request.
# - That the derivation test class runs without Robolectric, or that the live class uses no WorkManager. Both are
#   stated in the brief and in the comments below; a test that broke that rule would still pass these names, and the
#   shape of a test file is the owner's call.
# - The names of the status variants, of the field each of Pending and Planned carries it in, or of any "offers
#   Re-fly" accessor. Each case below asserts which answer the card gives and whether Re-fly is offered, whichever
#   way the status exposes it.
# - The wording of the fallback reason for a FAILED row with no error text, or of the "nothing was queued" reason for
#   a PENDING sortie with no row at all. The plan records both as open wording, reviewed in the pull request; the
#   names below require that a reason is there and that it is not the row's, not what it says.
# - That the draft's CoachStatus was left alone. The brief says not to change it, and CoachStatusTest and
#   LaunchpadCoachStatusTest running and passing untouched is what says the draft still answers the same situations
#   the same way. A rewrite of that derivation that kept its answers is not something a script can call wrong.
# - That LaunchpadScreen drew nothing new. The brief limits it to compiling plus the Re-fly line condition, so a diff
#   of that file would fail correct work; what is checked instead is that the rendered Launchpad cases that already
#   exist — Re-fly both ways, Confirm, Scrub, the draft's controls, guards and status line — still pass, and the one
#   new rendered case below is the Re-fly rule on the card.
# - Which existing Launchpad test had to be updated to the §5.4 rule. The brief says some may be, and asks only that
#   the PR name them; the class checks below require those classes to pass, not to be unchanged.
# - The plan's Decisions. The owner reviews them in the pull request, so none gets a test of its own here. Where a
#   named test below happens to show one, its comment names it.
# - The Room schema's shape beyond "unchanged": no entity change and no migration, checked by comparing the exported
#   schema under app/schemas against the tree this run started from.
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
# - the compile check for the new derivation, for Pending and Planned carrying it, for the two new joins in
#   LaunchpadStates, for the Flow twin in GenerationDao, and for every construction site and every reader of
#   Pending and Planned — the brief's "Update LaunchpadScreen only as far as it needs to compile" is proven by this
#   compiling, and a site left unupdated does not compile at all;
# - the brief's "Must not break" check for the whole suite: the Launchpad tests, the draft's coach status,
#   generation queueing and Regenerate, the worker tests, In-Flight, Landed, Mission Control, the shell tests and the
#   domain tests all run here against the new states;
# - the check that no test hangs and nothing depends on this machine's clock (CI is ubuntu-latest, UTC, and slower),
#   since app/build.gradle.kts kills a test task that goes five minutes without finishing — the brief's "every wait
#   bounded", and the hang the brief says to re-run rather than fix.
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
# - the known hang: the build kills the test task at five minutes and writes no TEST-*.xml at all (the brief's "The
#   full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens");
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random on a Room pool
#   closed under a still-collecting observe() and passing on a re-run of the identical tree.
# A compile error, or any other reported assertion failure, is a real result and is not retried. That includes a new
# case that cannot get its fixture into the state its comment describes: that is a real result about how the case was
# written, not a machine fault. Only the attempt's own log is read — a hang on an earlier attempt must not turn a
# later attempt's reported assertion failure into a retry.
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
# Brief: "A plain-Kotlin derivation in com.liftoff.app.domain, with no Android or Room types in its signature, over
# the newest non-cancelled FLIGHT_PLAN row's values (or none) and whether the coach is configured", and "Plain JUnit
# for each derivation case".
#
# Each name below is one call of that derivation with plain values: a GenerationStatus or no row at all, the error
# text the row carries or null, the offline mark the row carries, whether the coach is configured, and whether a
# re-fly source plan exists. It asserts which answer comes back, and for the Re-fly names, whether the derivation
# says Re-fly is offered. No Android context, no database, no screen, no coroutine: a case that needs one of those is
# not this derivation.
#
# The row handed in is a FLIGHT_PLAN row for one sortie, the way the Launchpad's current sortie's row looks.
echo ""
echo "=== the derivation (plain JUnit) ==="

# Brief: "planning (row open, not offline)". A QUEUED row and a RUNNING row, each with the offline mark clear and the
# coach configured, both answer planning.
t anOpenFlightPlanRowThatIsNotMarkedOfflineIsPlanning

# Brief: "offline (row open and offline)". A QUEUED row and a RUNNING row each carrying the offline mark the worker
# writes on a row still waiting on the coach answer offline, not planning. And the offline answer needs an open row:
# a FAILED row carrying that same mark, and a FAILED row whose reason is the worker's "coach unreachable", both
# answer failed — that is Plan Decision "A generation that gave up on an unreachable Mission Control reads as a
# failure, not as offline", and it is what keeps a card from saying "retrying" about a generation that has stopped.
t anOpenRowCarryingTheOfflineMarkIsOfflineAndAFailedRowCarryingItIsFailed

# Brief: "failed (the reason …)". Assert the exact string the row carries, of the kind a worker writes — punctuation
# and inner spacing — so a rewrite, a trim or a reassembly of the reason is caught.
# And a FAILED row answers that way even when the host or token has since been cleared: the card gives the row's own
# reason, not "Mission Control isn't set up". This is Plan Decision "A FAILED row answers on its own even if the coach
# is no longer set up", and it is the draft's rule (CoachStatus.kt:55-59), so the draft card and a sortie card cannot
# answer the same situation two ways. The brief's "not configured (no open row, and the host or token is blank)" is
# about a row that is not worth reporting, not about a failure the owner can act on.
t aFailedRowReportsFailedWithItsReasonWordForWord

# Brief: "failed (the reason plus the validator errors split out of the stored error, if any)", and its "a failed row
# with several validator errors". The stored error of a rejected reply is what GenerationWorker writes today: one
# line, "invalid output: " glued to the validator's errors joined at "; ". The derivation must answer failed with the
# lead sentence as the reason — "invalid output" — and the validator's own lines, all of them, in the order the
# worker wrote them, each one readable on its own. Use at least three errors of the shape the validator writes, one
# of them naming an exercise and one naming a piece of equipment. This is Plan Decision "Every validator line is kept,
# and the reason is the lead sentence", and it is why the worker's wording must not change to make the brief's
# "one per line" true: GenerationWorkerTest pins the stored text.
t theValidatorErrorsStoredOnARejectedReplyAreSplitOutOfTheReasonInOrder

# Brief: "failed (the reason …)" for a row that has one. A FAILED row whose error is empty or blank still answers
# failed, with a reason of its own rather than an empty one — a card that says "failed" with nothing to show is a
# card the owner cannot act on. The wording is the plan's open choice; that a reason is there is not.
t aFailedRowWithNoErrorTextStillReportsFailedWithAFallbackReason

# Brief: "If there is no row and the coach is configured, treat it as failed with a short reason, so Retry is offered
# and the owner is never stuck." No row at all, coach configured: failed, with a reason that is not empty. This is
# Plan Decision "A PENDING sortie with no generation row at all reads as a failure with a plain reason".
t aSortieWithNoGenerationRowAndAConfiguredCoachIsFailedWithAReason

# Brief: "not configured (no open row, and the host or token is blank)". No row, and each of the two settings blank
# in turn — no host with a token, no token with a host — answers not configured, not failed. A blank host alone and a
# blank token alone are both unconfigured.
t aSortieWithNoGenerationRowAndABlankHostOrTokenIsNotConfigured

# Brief: the derivation reads "the newest non-cancelled FLIGHT_PLAN row", and the offline and planning answers are
# defined by an open row. A newest row that SUCCEEDED, and a newest row that CANCELLED, are each answered exactly as
# no row at all would be: failed with a reason when the coach is configured, not configured when it is not. A
# CANCELLED row must never be read as the current generation.
t aSucceededOrCancelledNewestRowIsAnsweredAsIfThereWereNoRow

# Brief: "For PLANNED it gives re-planning (a row is QUEUED or RUNNING …)". A QUEUED row and a RUNNING row for a
# sortie that already holds a plan each answer re-planning.
t aPlannedSortieWithAnOpenRowIsRePlanning

# Brief: "… or idle", and Plan Decision "A PLANNED sortie whose re-plan failed shows idle, not the failure". A FAILED
# row, a SUCCEEDED row, and no row at all each answer idle for a PLANNED sortie — the plan the sortie already holds
# is still landable, so a re-plan that failed is not shown as an error there.
t aPlannedSortieWithAFailedOrSucceededRowOrNoRowIsIdle

# Brief: "It also gives the rho session id when the row has one." A row carrying nabuSessionId hands that id to the
# caller beside the status, whichever status it is; a row with no session id, and no row at all, hand none. Assert
# the exact id the row carries.
t theRhoSessionIdIsHandedOutWhenTheRowHasOneAndNoneWhenItDoesNot

# Brief bullet 3 and DESIGN.md §5.4, the rule the brief asks to be tested by name: Re-fly is offered "only when a
# source plan exists and the generation failed, the coach is offline, or no connection is configured". With a source
# plan present, each of those three answers — failed with its reason, offline, and not configured — says Re-fly is
# offered. This is the escape hatch §5.4 describes for when the coach cannot do the work.
t aSourcePlanOffersReFlyWhenTheGenerationFailedIsOfflineOrUnconfigured

# Brief bullet 3, §5.4, and Plan Decision "Re-fly disappears from a PENDING card while the coach is working": with a
# source plan present but the row open — planning, or offline — the derivation refuses Re-fly. Re-fly is not a second
# route to a plan while the coach is already making one.
t aSourcePlanDoesNotOfferReFlyWhileAGenerationIsOpen

# Brief bullet 3, §5.4: "only when a source plan exists". With no source plan, Re-fly is refused under every answer
# the derivation can give — planning, offline, failed, not configured, re-planning and idle.
t noSourcePlanOffersReFlyUnderNoAnswer

# ─── 3. The live cards: one subscription, in-memory Room ───────────────────────
# Brief: "Robolectric (@Config(sdk = [34]), in-memory Room, every wait bounded, CI ubuntu-latest UTC, no
# WorkManager): one subscription to a PENDING sortie's state follows its row from QUEUED to offline
# (setCoachOfflineMark) to FAILED (markFailed), and a PLANNED sortie shows re-planning when a new row is queued for
# it." and "LaunchpadStates joins the generation Flow and the settings only on those branches, and doesn't re-emit
# for unrelated changes."
#
# Each name below is the harness LaunchpadCoachStatusTest already uses: in-memory Room, a real SettingsStore over a
# DataStore file of its own, a real MissionManager on a fixed UTC clock, and host and token set unless the case says
# otherwise. The week is ACTIVE with a current sortie — continueWithoutOutline is how a case gets there without a
# daemon — and each generation row is inserted straight through GenerationDao with its own later createdAt, so no
# worker and no daemon is involved anywhere in these cases.
#
# "One subscription" is the point of these cases: the collector is launched once, before the writes, and stays open
# until the case ends; every emission read below comes out of that one collection. A second observe() or a fresh
# .first after a write re-subscribes and proves nothing about the card being live. Every wait is bounded.
echo ""
echo "=== the sortie's status, live from Room (one subscription) ==="

# Brief: "one subscription to a PENDING sortie's state follows its row from QUEUED to offline (setCoachOfflineMark)
# to FAILED (markFailed)". The card seen first is planning; generationDao.setCoachOfflineMark(id, COACH_OFFLINE) on
# that same still-open row makes the same subscription emit a card that says offline; generationDao.markFailed(id,
# reason, t) on it makes the same subscription emit a card that says failed carrying that exact reason — all out of
# one collection, with no re-subscribe, and each step a new emission. The card is the PENDING one for this sortie
# throughout: same sortie id, same index.
#
# The brief also asks for the session id here: once generationDao.saveSessionId(id, "session-7") has written it to
# that row, the card this same subscription emits hands that id out. Read it off the card, not off the row.
t oneSubscriptionToAPendingSortieFollowsItsRowFromPlanningThroughOfflineToFailed

# Brief: "a PLANNED sortie shows re-planning when a new row is queued for it". A current PLANNED sortie — a plan
# written on it and the sortie marked PLANNED — is the card it holds, showing that plan's title. Inserting one QUEUED
# FLIGHT_PLAN generation row for that same sortie through GenerationDao makes the same subscription emit a card that
# still shows that same plan and now says re-planning. The plan is not replaced, dropped or re-read from elsewhere:
# the brief's Regenerate run is what queues rows like this, and the card keeps being landable while one is open.
t aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt

# Brief: the derivation reads "the newest non-cancelled FLIGHT_PLAN row" of this sortie, and GenerationDao's suspend
# read of the same thing is what the worker's own currency check uses — the live read and that read cannot disagree.
# Seed, before the subscription opens: a CANCELLED row for this sortie that is newer than every other row for it; an
# open row belonging to a different sortie of the same week, newer still, with its own error text; and a FAILED row
# for this sortie with its own error text. The card must answer from this sortie's FAILED row and its reason — not
# from the cancelled row that would win a read without the status filter, and not from the other sortie's row, which
# is what a read without the sortie filter would find.
t theCardFollowsThisSortiesNewestNonCancelledRowAndNotACancelledOrAnotherSortiesRow

# Brief: "doesn't re-emit for unrelated changes", and the plan's Risk that Room wakes the join on any generation row
# at all. On one subscription, with the PENDING card already seen, each of these writes must leave the collected
# emissions exactly as they were — no new card, not even an identical one, within a bounded wait:
# - a Flight Plan written for a later sortie of the same week, which does not change which sortie is current;
# - a QUEUED generation row for a different sortie of the same week;
# - a settings write that leaves the coach exactly as configured as before, such as a weight-unit change.
# Then the control, which proves the collector is still open and the silence above it is the wiring: an open
# generation row for this sortie does produce a new emission.
t theCardDoesNotReEmitForAnotherSortiesRowAPlanForAnotherSortieOrAnUnchangedSetting

# ─── 4. Re-fly on the rendered PENDING card ────────────────────────────────────
# Brief: "Update LaunchpadScreen only as far as it needs to compile, keeping Re-fly shown only when the new
# reFlyAvailable is true." The plan's Testing section asks for this one rendered case, and it is the only place the
# brief's screen rule is observable: the state-level rule is covered by the derivation names above, and the two
# rendered cases LaunchpadReFlyTest already has — Re-fly shown when a source exists with nothing configured, and the
# muted line when no source exists — are run by the class check below.
echo ""
echo "=== the PENDING action row under the new rule ==="

# A source plan exists — a landed sortie of this sortie's type with a plan — the coach is configured, and this
# sortie's newest generation row is open. Under §5.4 the derivation refuses Re-fly while the coach is working, so
# the card draws no Re-fly control (not a dimmed one either: nothing reading "re-fly" in any case), and it must not
# draw the line that stands in for "there is nothing to re-fly from", because here there is one. Scrub is still
# there and still enabled. This is the plan's review-added Decision "No earlier plan to re-fly.' is not shown while
# the coach is planning".
t neitherReFlyNorTheNoEarlierPlanLineIsShownWhileTheCoachIsPlanning

# ─── 5. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the Launchpad tests, the draft's coach status, generation queueing and Regenerate, the
# worker tests, In-Flight, Landed, Mission Control, the shell tests, the domain tests and the build gate." The gate
# above runs the whole suite; these name the classes so that one renamed away, skipped or deleted is caught, and a
# failure is reported as the class it belongs to rather than only as a red gate.
#
# The Launchpad classes are the brief's own list. They are also what says the screen still draws what it drew:
# Confirm, Scrub, the draft's controls, guards and status line, and Re-fly both ways. LaunchpadStatesTest is the one
# that keeps today's answers for every state the brief does not touch — Loading, InFlight, Closed — and its two
# re-fly cases keep theirs under §5.4 because neither configures the coach, so their current sortie is not
# configured and a source plan still offers Re-fly. If one of them does flip, the brief says the test changes to the
# rule and the PR names it; the class check does not care which way the assertions read.
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadStatesTest
c LaunchpadConfirmTest
c LaunchpadReFlyTest
c LaunchpadScrubTest
c LaunchpadRegenerateTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest

# The draft's coach status, which the brief says not to change: the derivation's own cases, and the live draft.
c CoachStatusTest
c LaunchpadCoachStatusTest
c LaunchpadDraftStatusTest

# Generation queueing and Regenerate, and the rows they write and cancel. GenerationDaoTest is the class beside the
# Flow twin this run adds, and the worker's currency check reads the same row the twin reads.
c GenerationDaoTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c AppOpenSweepTest
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest

# The worker tests, which the brief's Risks say must keep writing the stored error exactly as they do today.
c GenerationWorkerTest
c CoachTurnRunnerTest

# In-Flight and Landed compose and derive beside the states this run changes.
c InFlightStatesTest
c InFlightScreenTest
c InFlightViewModelTest
c InFlightLandScrubTest
c InFlightLeaveTest
c InFlightRunTest
c LandedStatesTest
c LandScrubDialogTest

# Mission Control, which writes the settings the new joins read.
c MissionControlScreenTest
c MissionControlViewModelTest
c EquipmentViewModelTest
c ConnectionViewModelTest
c DaemonConnectionCheckTest
c SettingsStoreTest

# The shell tests the brief names, and the app's own wiring.
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

# ─── 6. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a rewritten 1.json or a new version file beside it is a
# schema change. The brief adds no entity and no migration, and the DAO read it asks for is a query.
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
