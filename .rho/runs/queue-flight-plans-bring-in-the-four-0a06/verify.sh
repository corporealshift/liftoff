#!/usr/bin/env bash
# Verify: "Queue Flight Plans: bring in the four finished commits" (brief.md; DESIGN.md §4.3, §5.3, §7.2;
# ARCHITECTURE.md M3). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then the twelve tests the four picks bring, read out of the
# reports this run produced, then the classes the brief's "Must not break" list names, then the exported schema.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never brought over cannot pass on its own. That is what makes this script fail on untouched code:
# all twelve names under sections 2-4 are new to this branch — they are the tests the four commits add, and none of
# them appears anywhere under app/src/test today. Against the untouched branch the gate passes and every class check
# and the schema check pass, and the twelve names report "did not run": exit 1.
#
# Each pick has at least one name of its own, so a pick that was skipped, or rebuilt without its tests, shows up as
# a test that did not run rather than as a branch that looks like it has the work: bcf6bae the four manager cases,
# a74b099 the five guard cases, 1afea86 the worker's enqueue case, a27d999 the two Launchpad cases.
#
# What is NOT checked here, and why:
# - The wording of the ARCHITECTURE.md M3 row. The brief asks for it and the plan makes it a step; a regex over a
#   doc row is a text check, not a behaviour check, and it rejects correct work worded differently. Kyle reads the
#   row in the pull request.
# - That the four commits arrived by cherry-pick, kept their messages, or brought no `run:` commit and no file under
#   .rho/runs/. That is history, and the pull request shows it. What is checked is that each pick's content and its
#   tests are on this branch, which is what the twelve names prove.
# - That In-Flight was left alone. The brief defers InFlightViewModel's use of the id land and scrub report to a
#   later run, and the plan accepts the QUEUED row nothing schedules in the meantime. The In-Flight classes the gate
#   runs are what show nothing there broke.
# - That `onQueued` fires strictly after `withTransaction` returns rather than inside it. No test can tell those
#   apart from outside the transaction, and none of the four commits contains one that could. What is checked is the
#   part a test can show: the id the callback received is the id of a QUEUED FLIGHT_PLAN row that is in the database
#   when the manager call has returned, and the same id is what the caller hands to the enqueuer.
# - That Re-fly hands manager.refly's open FLIGHT_PLAN ids to cancelGeneration. The pick that wires it says the
#   Re-fly cancel has no named test of its own; LaunchpadReFlyTest and MissionManagerReFlyTest staying green is the
#   check, and the pull request shows the wiring.
# - Which class or package each named test sits in, the number of tests in the suite, whether a view-model case uses
#   a recording lambda rather than WorkManager, and how the queued id travels out of the manager. The plan settles
#   those; the gate compiles everything else.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/queue-flight-plans-bring-in-the-four-0a06"

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
# "<name> <pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report.
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
            END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
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
    st="$(awk -v m="$name" '$1 == m { print $2; exit }' "$WORK/methods" 2>/dev/null || true)"
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
    st="$(awk -v m="$cls" 'index($1, "." m) == length($1) - length(m) { print $2; exit }' "$WORK/classes" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$cls ran and every test in it passed" ;;
        fail)    bad "$cls ran but something in it did not pass" ;;
        skipped) bad "$cls ran but a test in it was skipped" ;;
        none)    bad "$cls did not run" ;;
    esac
}

# ─── 1. CI gate: the two tasks ci.yml runs ─────────────────────────────────────
# Brief Done-when: "the four commits are on the branch, the M3 row is updated, and
# bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This gate is also:
# - the brief's "Must not break" check for the whole suite: the worker, enqueue and cancel tests, Confirm's outline
#   queueing and the draft status, In-Flight and reopening, Landed, Re-fly, export/import, Mission Control, the shell
#   tests, the domain tests and the build gate;
# - the compile check for the ids land, scrub and refly now report, for the onQueued parameter storeOutline and
#   continueWithoutOutline take, and for every call site of all of them across the test classes, since a site left
#   unupdated does not compile;
# - the check that no test hangs and nothing depends on this machine's clock (CI is ubuntu-latest, UTC, and slower),
#   since app/build.gradle.kts kills a test task that goes five minutes without finishing — the brief's "every test
#   wait must be bounded", and the hang the brief says to re-run rather than fix.
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

# Only these get another gate, per the plan's decision as revised by review — a hang or a missing report is retried,
# a reported assertion failure is real wherever it sits:
# - the known hang: the build kills the test task at five minutes and writes no TEST-*.xml at all;
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random on a Room pool
#   closed under a still-collecting observe() and passing on a re-run of the identical tree. Any other failing name
#   is treated as a real result and is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Unable to delete directory|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
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

# ─── 2. advance queues the Flight Plan, and reports the id ─────────────────────
# Brief: advance "inserts one QUEUED FLIGHT_PLAN Generation row when the current sortie is PENDING, needs generation
# (every lift; a run only when 'Generate run plans' is on) and the coach is configured", and "land(...) and scrub(...)
# return Long?: the Flight Plan id queued for the sortie that became current, or null". These are the manager's own
# cases from bcf6bae: in-memory Room, the class's existing configureCoach helper, assertions on the Generation rows
# and on the ids the manager methods report. Each one reads the row back out of the database after the call has
# returned, so the id the caller was handed is the id of a row that is really committed.
echo ""
echo "=== the manager: the row it writes and the id it reports ==="

# Storing a validated outline on a DRAFT week with Mission Control configured must leave exactly one FLIGHT_PLAN
# Generation row — status QUEUED, on the sortie that just became current, which stays PENDING because the plan is what
# the coach will write back — and storeOutline must report that row's id to its onQueued callback.
t storingAnOutlineQueuesSortieOnesFlightPlanAndReportsItsId

# Continuing without an outline must write the same one QUEUED FLIGHT_PLAN row for the current sortie and report its
# id, and must still hand back the ids of the week's open OUTLINE rows for the caller to cancel. The plan it just
# queued must not be among the ids to cancel, or continuing without an outline would cancel the plan it queued.
t continuingWithoutAnOutlineQueuesSortieOnesFlightPlanAndStillHandsBackTheOpenOutlineIdsToCancel

# Landing the current sortie moves the week on; the sortie that has just become current and is a lift waiting on a
# generation must get exactly one QUEUED FLIGHT_PLAN row, with land reporting that row's id.
t landingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId

# The same through scrub: the sortie scrub made current gets exactly one QUEUED FLIGHT_PLAN row, and scrub reports
# that row's id.
t scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId

# ─── 3. The guards: the toggle both ways, an unconfigured Mission Control, never twice ──
# Brief: "It never inserts a second row while one is QUEUED or RUNNING for that sortie", "a run only when 'Generate
# run plans' is on", "With the toggle off a run gets the simple 'Run' plan", and "With no host or token nothing is
# queued and the sortie stays PENDING". The five cases a74b099 adds, against the behaviour bcf6bae wrote.
echo ""
echo "=== the guards ==="

# With Generate run plans on and Mission Control configured, a current PENDING run sortie is prepared by a
# generation: exactly one QUEUED FLIGHT_PLAN row for it, its id reported, the sortie still PENDING, and no simple
# 'Run' plan written for it.
t aCurrentRunSortieWithRunGenerationOnQueuesAFlightPlanAndWritesNoSimplePlan

# With Generate run plans off and host and token both set, the fallback prepares the run: the sortie takes the simple
# 'Run' plan and moves to PLANNED, nothing at all is queued, and nothing is reported.
t aCurrentRunSortieWithRunGenerationOffGetsTheSimpleRunPlanAndQueuesNothing

# The toggle is read at the moment a sortie is prepared, and changing it later never alters a plan that sortie
# already has: let a current run take the simple plan with the toggle off, turn it on, drive the manager again on the
# same week, and that sortie still holds the same plan row, still PLANNED, still with no FLIGHT_PLAN row.
t turningRunGenerationOnAfterwardsLeavesTheSimpleRunPlanThatSortieAlreadyHas

# With the host blank, and again with the token blank, on sorties that need a generation: no Generation row at all,
# nothing reported by the action, and each sortie left PENDING with no plan.
t aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken

# A sortie that already has an open Flight Plan generation is not asked for a second one, and the id reported is the
# open row it found rather than a new one. Once with that row QUEUED and once with it RUNNING, the sortie stays
# PENDING with no plan and the mission holds no more FLIGHT_PLAN rows than the seeded ones.
t aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported

# ─── 4. The callers enqueue what their own transaction queued ──────────────────
# Brief: "GenerationWorker enqueues sortie 1's Flight Plan after a stored outline, outside its transaction" and
# "LaunchpadViewModel passes the queued id to queueGeneration after continue-without-outline and after confirmScrub".
# In each case the id the caller passed on must be exactly the id of the QUEUED FLIGHT_PLAN row in the database
# afterwards, passed once, and must not be among the ids handed to the canceller.
echo ""
echo "=== the callers: the worker and the Launchpad ==="

# GenerationWorker running an OUTLINE generation whose reply stores, with Generate run plans set before the week is
# opened so the current sortie is a run that needs a generation: the worker ends in success with its outline row
# SUCCEEDED and the week ACTIVE, exactly one QUEUED FLIGHT_PLAN row exists for that sortie, and work is scheduled for
# that row — read back off WorkManager as ENQUEUED under gen-<that row's id>, under a bounded wait — and nothing is
# scheduled under the outline row's own name. The rest of GenerationWorkerTest builds its worker with no WorkManager
# running, which keeps proving that a stored reply still ends in success when nothing can be scheduled.
t aWorkerThatStoresAnOutlineEnqueuesTheFlightPlanItQueuedForSortieOne

# Through LaunchpadViewModel, continuing without an outline after a Confirm must hand the enqueuer the id of the
# FLIGHT_PLAN row the week queued for the current sortie — once — and must still hand the outline row's id to the
# canceller, with the plan row not among the cancelled ids and still QUEUED after the action has finished.
t continueWithoutOutlineQueuesSortieOnesFlightPlanGenerationForEnqueuing

# The Launchpad's Scrub, with Mission Control configured, must hand the enqueuer the id of the QUEUED FLIGHT_PLAN row
# queued for the sortie scrub made current — once — and must hand nothing to the canceller. The other cases in that
# class run with Mission Control unset up, where the same scrub queues nothing.
t scrubFromTheLaunchpadQueuesTheNextSortiesFlightPlanGenerationForEnqueuing

# ─── 5. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the worker, enqueue and cancel tests, Confirm's outline queueing and the draft status,
# In-Flight and reopening, Landed, Re-fly, export/import, Mission Control, the shell tests ..., the domain tests and
# the build gate." The gate above runs the whole suite; these name the classes this run reaches underneath, so a
# failure there is reported as the class it belongs to rather than only as a red gate.
#
# GenerationWorkerTest and GenerationCancellerTest configure Mission Control, so their outline-storing and
# onAppOpen cases now find an auto-queued FLIGHT_PLAN row beside the rows they seed; LaunchpadConfirmTest's existing
# continue-without-outline case now expects the plan id beside the outline id in the queue lambda. Those are the
# tests the brief says "now see an auto-queued row": a count that was written against a week that queued nothing is
# the thing that breaks, and the fix is to scope the count to the kind it means or to assert the FLIGHT_PLAN row
# explicitly — never to drop or loosen the check.
# deriveCoachStatus reads only the newest outline row, so the draft's status line must not move; the draft-status and
# coach-status classes below are what show that.
echo ""
echo "=== the tests the brief says must not break ==="

c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c CoachTurnRunnerTest
c GenerationDaoTest
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c LaunchpadConfirmTest
c LaunchpadStatesTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadDraftStatusTest
c LaunchpadCoachStatusTest
c LaunchpadScrubTest
c LaunchpadReFlyTest
c InFlightLandScrubTest
c InFlightRunTest
c InFlightStatesTest
c InFlightLeaveTest
c InFlightViewModelTest
c InFlightScreenTest
c LandScrubDialogTest
c RunLandingTest
c NoteDialogTest
c LandedStatesTest
c ReFlyTest
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest
c BackupImportRoomTest
c BackupViewModelTest
c MissionControlScreenTest
c MissionControlViewModelTest
c ConnectionViewModelTest
c EquipmentViewModelTest
c LiftoffShellTest
c ShellNavTest
c MainActivityTest
c AppContainerTest
# The domain tests the brief names, and the schema test that says the exported schema still matches the database.
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
c CoachStatusTest
c SchemaExportTest

# ─── 6. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change: the exported schema under app/schemas must stay byte-identical." LiftoffDatabase
# exports its schema to app/schemas at build time, so the exported schema is the record of what the entities are. It
# is compared against the tree this run started from, both as committed and as it stands after the gate run above —
# a rewritten 1.json or a new version file beside it is a schema change.
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
