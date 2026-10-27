#!/usr/bin/env bash
# Verify: "App-open sweep: re-enqueue open generations, cancel stale ones" (brief.md; DESIGN.md §4.2, §4.3, §5.3, §7.2, §7.3).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the eight tests that prove what the sweep decides, read out of the
# reports that gate run produced, then the classes the brief's "Must not break" list names, then the exported Room
# schema.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all eight
# names under sections 2 and 3 are new to this branch — none of them appears anywhere under app/src/test today, nothing
# in app/src/main reads every open Generation row at app open, and MainActivity runs only onAppOpen() at open. Against
# the untouched branch the gate passes, every class check and the schema check pass, and the eight names report "did
# not run": exit 1.
#
# What is NOT checked here, and why:
# - That MainActivity calls the sweep after onAppOpen() returns, on a background dispatcher, with the returned
#   Mission's weekStart and the container's enqueue and cancel helpers. Showing that needs a Robolectric test that
#   launches MainActivity with WorkManager initialized and open rows seeded in the app's own database — a UI test the
#   brief's own test list rules out ("no WorkManager") and the plan's Testing section does not plan. What is checked
#   is the half a test can show: LiftoffShellTest runs MainActivity.onCreate against the real container with
#   WorkManager uninitialized, so anything WorkManager-related reached at composition or on an app open with no open
#   rows fails it, and the empty-table test below pins that the sweep hands nothing to either lambda when there is
#   nothing to act on. The wiring itself is read off the diff in the pull request.
# - "off the main thread, and never blocks the UI", and that the sweep re-runs on every return to the foreground
#   rather than once per process: both are only observable from the UI, and the plan's Decisions review them.
# - The plan's Decisions: that the sweep is silent (no message, snackbar or count); that a row whose Mission or sortie
#   has gone away is cancelled rather than skipped; that a generation for a sortie that has left PENDING/PLANNED is
#   cancelled even mid-turn; that the sweep only acts on generations that already exist and never queues one the app
#   did not ask for. Wanted rows being resumed under their own row, and wanted rows being resumed even when Mission
#   Control has no host or token now, are shown by the tests below: no case configures a daemon, and a wanted row is
#   handed to enqueue and not to cancel. Decisions are reviewed in the pull request.
# - The whole-sweep wrap for a failing read (the plan's Risks: a database that is gone ends the sweep quietly). The
#   per-row test below pins the swallow rule it shares, and the shell classes pin that app open never crashes.
# - ARCHITECTURE.md's com.liftoff.app.work row, which the brief asks to name the sweep. Doc wording is reviewed by
#   Kyle in the pull request, so no doc row is checked here.
# - Which class each new test sits in, and the sweep's own shape (its constructor, the added GenerationDao query). The
#   plan puts them in AppOpenSweepTest with a whole-table open query; the names below are matched across every report
#   this run wrote.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/app-open-sweep-re-enqueue-open-arfw"

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
# Fail is worse than skipped, and skipped is worse than pass, so one ignored test marks its whole class.
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
# was never written, so a name missing from this run's reports is a failure, and one that ran and did not pass is
# a failure too. Names are matched across every report, so which file and package a test sits in is the
# implementer's call; what it must show is written in the comment above it.
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
# Brief Done-when: "these tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes".
# This gate is also:
# - the brief's "Must not break" check for the whole suite: the shell tests and MainActivityTest, the worker, enqueue
#   and cancel tests, outline and Flight Plan queueing, In-Flight and reopening, the Launchpad tests, the domain
#   tests and the build gate;
# - the compile check for everything the sweep and its wiring touch: MainActivity, the container's helpers and every
#   existing call site have to keep compiling as they stand;
# - the check that nothing asks WorkManager for while composing or on an app open with no open rows: LiftoffShellTest
#   composes MainActivity and the shell with the real AppContainer and no WorkManager initialized, and
#   AppContainer.generationEnqueuer()/generationCanceller() build WorkManager on each call, so a sweep that reaches
#   either helper at app open over an empty generation table fails these classes rather than passing quietly;
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
# - the known hang: the build kills the test task at five minutes and writes no TEST-*.xml at all (the brief's
#   "can hang once for about 5 minutes on this machine ... re-run it if that happens");
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random on a Room pool
#   closed under a still-collecting observe() and passing on a re-run of the identical tree.
# A compile error, or any other reported assertion failure, is a real result and is not retried.
# Only the attempt's own log is read: a hang on an earlier attempt must not turn a later attempt's reported
# assertion failure into a retry.
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

# ─── 2. What the sweep decides about every open Generation row ─────────────────
# Brief: "look at every open (QUEUED or RUNNING) Generation row. Hand the ones still wanted to WorkManager, and
# cancel the ones whose target has moved on."
#
# Each case is the sweep against in-memory Room with a recording enqueue lambda and a recording cancel lambda in
# place of the container's helpers — no WorkManager, no screen, no daemon, no settings. The brief's rule is about
# rows that already exist, so a fixture that sets no Mission Control host or token must still hand a wanted row to
# enqueue: the sweep consults no settings (the plan's decision that a wanted row is resumed even when Mission
# Control is unset). Robolectric @Config(sdk = [34]), a fixed Clock, every wait bounded. The mission and sorties a
# case needs are built the way MissionManagerTest builds them, and the week the sweep judges against is the
# weekStart of the Mission onAppOpen() returned, which the case hands over rather than leaving the sweep to work the
# week out again.
#
# Every assertion is on the exact ids each lambda was handed: a wanted row's id in the enqueue list and not in the
# cancel list, an unwanted row's id the other way round. A lambda handed an id it was not given, or not handed one it
# was, fails the case. Marking a row CANCELLED is the canceller's job, so the recording lambdas record; the row each
# one names is read back out of the database to check it is the row the case meant.
echo ""
echo "=== the rows the sweep wants, and what it does with them ==="

# A QUEUED FLIGHT_PLAN row on the current week's ACTIVE Mission's current PENDING sortie is wanted: the enqueue
# lambda is handed exactly that row's id and the cancel lambda nothing. Shows the plan's decision that a wanted row
# is resumed under the row it already has, rather than cancelled and replaced by a fresh generation.
t queuedFlightPlanForTheCurrentPendingSortieIsEnqueuedNotCancelled

# A QUEUED OUTLINE row whose missionId is the current week's DRAFT Mission and whose sortieId is null is wanted: its
# id is handed to enqueue and to nothing else.
t queuedOutlineForTheCurrentDraftMissionIsEnqueuedNotCancelled

# The PLANNED arm of the Flight Plan rule, and the RUNNING half of "every open row": a RUNNING FLIGHT_PLAN row (a
# re-plan mid-turn) on the current week's ACTIVE Mission's current sortie that is already PLANNED is handed to
# enqueue, not to cancel. A sweep that accepted only PENDING sorties, or looked only at QUEUED rows, fails here.
t runningFlightPlanForTheCurrentPlannedSortieIsEnqueuedNotCancelled

# ─── 3. The rows whose target has moved on, and the sweep's own failures ───────
# Brief: "it cleans up after the week-ended rollover, which scrubs open sorties", and "Any failure (except
# cancellation) is swallowed per row, so one bad row doesn't stop the rest and the app never crashes". The cancel
# cases assert the exact ids the cancel lambda was handed and that the enqueue lambda was handed nothing; the last
# three assert that nothing is handed to either lambda, or that a lambda that threw did not stop the sweep.
echo ""
echo "=== the rows the sweep cancels, and what a failing row does ==="

# After onAppOpen() has run the week-ended rollover on a clock one week later (MissionManagerTest's advanceClock
# helper is one way to make it run) — the past Mission's open sorties are SCRUBBED with 'week ended' and that Mission
# is CLOSED. An open FLIGHT_PLAN row on that past Mission's sortie and an open OUTLINE row on a past week's DRAFT
# Mission are both unwanted: both ids handed to cancel, nothing handed to enqueue. The sweep is called with the
# weekStart of the Mission that rollover returned, so it judges against the week that did the scrubbing.
t weekEndedRolloverCancelsTheScrubbedSortiesRowAndThePastDraftsOutlineRow

# Open FLIGHT_PLAN rows on sorties that are no longer the Mission's current sortie, in a current week's ACTIVE
# Mission whose later sortie is now current: one on a LANDED sortie and one on a SCRUBBED sortie. Both ids handed to
# cancel, nothing to enqueue. The plan's decision: a row for a sortie that has left PENDING/PLANNED is cancelled even
# if its generation is mid-turn.
t rowsForSortiesThatAreNoLongerCurrentAreCancelled

# A row that has already decided its outcome is left alone: a SUCCEEDED, a FAILED and a CANCELLED row sitting in the
# same table beside wanted rows are handed to neither lambda.
t decidedGenerationsAreLeftAlone

# With no open rows in the generation table, neither lambda is called at all. This is what keeps the sweep away from
# WorkManager in the shell tests, which run MainActivity.onCreate against a database with no Generation rows and no
# WorkManager initialized.
t anEmptyGenerationTableCallsNeitherLambda

# One bad row must not stop the rest, and the app must not crash: a lambda that throws for the first id it is handed
# must still be handed the next row's id, and the sweep must return normally with nothing escaping it. The case may
# use the enqueue lambda, the cancel lambda, or both. The one failure that is not swallowed is cancellation: a lambda
# that throws CancellationException lets it propagate.
t aRowThatFailsDoesNotStopTheSweepOrTheOtherRows

# ─── 4. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the shell tests and MainActivityTest, the worker, enqueue and cancel tests, outline and
# Flight Plan queueing, In-Flight and reopening, the Launchpad tests, the domain tests and the build gate." The gate
# above runs the whole suite; these name the classes so that one renamed away, skipped or deleted is caught, and a
# failure is reported as the class it belongs to rather than only as a red gate.
echo ""
echo "=== the tests the brief says must not break ==="

# The shell tests and MainActivityTest, which run the app-open path with no WorkManager set up.
c LiftoffShellTest
c ShellNavTest
c MainActivityTest
c AppContainerTest

# The worker, the enqueue helper, the canceller, and the generation rows and turn they run on.
c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c CoachTurnRunnerTest
c GenerationDaoTest

# Outline and Flight Plan queueing.
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c LaunchpadConfirmTest
c InFlightLandScrubTest
c InFlightRunTest

# In-Flight and reopening.
c InFlightStatesTest
c InFlightLeaveTest
c InFlightViewModelTest
c InFlightScreenTest
c LandScrubDialogTest
c RunLandingTest
c NoteDialogTest

# The Launchpad tests.
c LaunchpadStatesTest
c LaunchpadScrubTest
c LaunchpadReFlyTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadDraftStatusTest
c LaunchpadCoachStatusTest

# The domain tests.
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
c ReFlyTest
c CoachStatusTest

# The data the sweep reads, and the import path that calls onAppOpen().
c MissionDaoTest
c SortieDaoTest
c FlightPlanDaoTest
c DatabaseImportTest
c DatabaseExportTest
c BackupRoundTripTest
c SettingsStoreTest

# ─── 5. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a rewritten 1.json or a new version file beside it is a
# schema change. A new @Query on GenerationDao is not one, and leaves these files byte-identical.
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
