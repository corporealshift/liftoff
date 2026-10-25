#!/usr/bin/env bash
# Verify: "Confirm queues the outline: bring in the five finished commits and test the view model"
# (brief.md; DESIGN.md §4.2 and §7.2). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then the tests only the five cherry-picked commits bring, then
# the four cases of the new LaunchpadConfirmTest, then the classes the brief's "Must not break" list names, then
# what the brief says not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code: the
# six names under "the draft through the view model" are new to this run, and the ten names under "the five
# cherry-picked commits" exist only once those commits are on this branch — none of the sixteen is in the tree
# today, and three names the picks retire (confirmPlansFirstRunWhenGenerationOff, confirmLeavesLiftPending,
# confirmLeavesRunPendingWhenGenerationOn) are deliberately not named because after the picks they are gone.
# Running this script against the untouched branch reports 624 tests, 0 failures, and exactly those sixteen names
# as "did not run".
#
# What is NOT checked here, and why:
# - That the five commits are those commits rather than an equivalent rewrite, and that their messages were kept.
#   A commit message is text, not behaviour. What is checked is that the tests only those commits provide are on
#   this branch and pass, which no rewrite would produce by accident, and that no other run's files came over.
# - That the new test file avoids WorkManager, or which harness it copies. The brief says so; a test that used
#   WorkManager would still pass these names, and the shape of a test file is reviewed by Kyle in the pull request.
# - The wording of the not-configured sentence or of any other user-visible copy. The names below require that a
#   message is set and drawn, not that it reads a particular way.
# - That the way out is the theme's UnderlinedTextButton, and its colour, underline and placement. What is checked
#   is that the draft draws a control for it, that it is enabled, and that tapping it leaves the draft.
# - The live "Coach is planning…" / outline-failure / Retry display. The plan says a configured Confirm leaves the
#   draft looking exactly as it did and that no Retry control appears here, so no name here asks the draft to show
#   an outline's status, and no name drives retry() through the UI.
# - A Retry the manager refuses. The plan settles that it stays silent, the swallow ships in a commit this run only
#   moves, and no UI reaches retry() yet; the "a new row exists" half of the retry case is what would catch a
#   refused retry anyway.
# - Which fixture sites the picks moved from confirm to continueWithoutOutline, or how many. The gate runs all of
#   them; the names under the picks are the ones only continueWithoutOutline can answer.
# - The Room schema's shape beyond "unchanged": no entity change and no migration, checked by comparing the
#   exported schema under app/schemas against the tree this run started from.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description of
#   which existing tests the picks changed and why — text the owner reads in the pull request.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/confirm-queues-the-outline-bring-in-the-7hce"

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
# Brief Done-when: "all five commits are on the branch, the four cases pass, and
# bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the five picks, for the two new view-model inputs and for every construction site the
#   picks update (LaunchpadScreen and the four Launchpad test classes), since a site left unupdated does not
#   compile, and for the new test file's use of the manager, the DAO reads and the view model's actions;
# - the brief's "Must not break" check for the whole suite: the worker, enqueue and cancel tests, In-Flight and
#   reopening, Landed, Re-fly, export/import, Mission Control and Test connection, the domain tests, the shell
#   tests and the Launchpad tests;
# - the check that no test hangs and nothing depends on the local clock (CI is ubuntu-latest, UTC), since
#   app/build.gradle.kts kills a test task that goes five minutes without finishing — the hang the brief warns
#   about for this run, which it says to re-run rather than fix.
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
# - a hung suite that wrote no report, which the build kills at five minutes.
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

# ─── 2. The five commits the brief says to bring in ────────────────────────────
# Brief Step 1: cherry-pick c748582, 29b1821, 99aceec, 6a16f32, 9dae7bb, in that order, keeping their messages,
# and bring no run: commit or .rho/runs/ file over. These ten names are the ones only those commits answer:
# on this branch today confirm still activates the week, there is no continueWithoutOutline, no configured check,
# no outline-row read, no pattern freeze, no retryOutline, and the draft draws no way out. They are named as the
# picks write them, so a pick that was skipped reads as a test that did not run.
echo ""
echo "=== the five cherry-picked commits ==="

# c748582 "data: add MissionManager.continueWithoutOutline and move the confirm fixtures to it" writes no test of
# its own: it adds the entry point as today's confirm body and moves 25 fixture call sites across ten test classes
# to it. That move is what the class checks in section 4 run, and it is the fixture move the PR statement is about.
# 29b1821 renames the three MissionManagerTest tests that recorded what confirm did when it activated the week,
# because continueWithoutOutline is what does that work now. This one must show that a draft that has never been
# confirmed comes out ACTIVE with sortie 1 planned, exactly as confirm used to — the new entry point does the
# week-starting work rather than being a stub. The other two are
# continueWithoutOutlineLeavesLiftPending and continueWithoutOutlineLeavesRunPendingWhenGenerationOn; naming one
# shows the rename landed, and the gate runs all three.
t continueWithoutOutlinePlansFirstRunWhenGenerationOff

# 29b1821 "data: confirm queues the week's outline, and an open outline row freezes the pattern":
# confirm now asks whether Mission Control is configured (host and token, each non-empty once trimmed) and, with
# either blank, changes nothing and reports CoachNotConfigured; otherwise it inserts exactly one QUEUED OUTLINE
# Generation row for the current draft and returns its id, inserting no sorties and not advancing the week, so the
# Mission is still DRAFT.
t confirmQueuesOneOutlineGenerationAndLeavesTheDraftUntouched
t confirmWithoutAHostOrTokenQueuesNothingAndSaysSo

# 29b1821: a second Confirm on the same draft reuses the open row's id rather than queueing a second outline.
t confirmingTheSameDraftTwiceReusesTheOpenOutlineGeneration

# 29b1821: while an outline row is QUEUED or RUNNING, setPattern refuses; once that row has FAILED the same edit
# lands, and the draft's stored pattern is what it was either way.
t theWeeklyPatternIsFrozenWhileAnOutlineGenerationIsOpenAndEditableAgainAfterItFails

# 29b1821: continueWithoutOutline hands back the ids of the mission's still-open (QUEUED or RUNNING) outline rows,
# read in its own transaction, for the caller to cancel — a row that already finished is not in the list.
t continueWithoutOutlineHandsBackEveryOpenOutlineGeneration

# 29b1821: GenerationDao's newest-non-cancelled-outline Flow for a mission drops a CANCELLED row and falls back to
# the older one, never reports a FLIGHT_PLAN row as the outline, and emits null for a mission with no outline.
t theLiveOutlineReadForAMissionDropsACancelledRow

# 99aceec "data: add MissionManager.retryOutline for a draft whose outline failed": retryOutline uses the same
# configured check and the same row shape as confirm, and refuses unless the Mission is the current week's DRAFT
# and its newest non-cancelled OUTLINE row is FAILED. Each refusal writes nothing: a draft with no outline row at
# all; one whose newest non-cancelled outline is QUEUED; one whose newest is SUCCEEDED; a Mission that is not this
# week's draft. With host or token blank the answer is the not-configured outcome rather than a throw.
t retryOutlineRefusesUnlessTheDraftsNewestOutlineHasFailed

# 6a16f32 "launchpad: wire the draft's actions to the enqueue and cancel helpers" adds no test of its own — it
# updates the four Launchpad test classes' construction sites, which the gate above compiles — and the behaviour it
# wires is what the new view-model file below proves.
# 9dae7bb "launchpad: draw the draft's way out and the not-configured line": the draft draws the
# Continue-without-outline control and it is enabled — tappable, not present and quietly doing nothing — on a draft
# with nothing queued, and tapping it calls the continue-without-outline action and leaves the draft. Rendered with
# the not-configured message set, the draft draws that message; rendered without it, no such line appears. Its
# exact wording is the owner's call in review.
t draftAlwaysOffersContinueWithoutOutline
t draftShowsTheNotConfiguredMessageWhenItIsSet

# ─── 3. The new file: the draft's actions through the view model ───────────────
# Brief Step 2: one new test file, LaunchpadConfirmTest, with no WorkManager in it — the enqueue and cancel
# helpers already have GenerationEnqueuerTest and GenerationCancellerTest. The view model is built on its own
# in-memory Room, a real SettingsStore, and a MissionManager on a fixed clock, and handed a queueGeneration lambda
# that records the ids it is given and a cancelGeneration lambda that records its ids and marks the row cancelled
# through GenerationDao.markCancelled, the way the real helper does. Three things decide whether these cases run at
# all, so they are restated here:
# - prime the draft first. state is stateIn(Lazily) and starts at Loading, and every action returns null unless
#   state.value is a Draft, so wait (bounded) for a LaunchpadState.Draft before calling anything and assert the
#   returned Job is non-null;
# - join each returned Job under a timeout, rather than guessing at a delay;
# - write host, token and a default pattern that starts with L before onAppOpen(): a Run-first first sortie is
#   given a simple plan and turns PLANNED, which would read as though the week had been advanced. With an L-first
#   pattern the first sortie stays PENDING, so after the way out the state is Pending, not Planned.
# The ids the lambdas recorded, and the rows read back from the database, are what each case asserts on.
echo ""
echo "=== the draft through the view model (LaunchpadConfirmTest) ==="

# Brief case 1: "Confirm with a host and token set: one QUEUED OUTLINE row, its id recorded once by the queue
# lambda, and the Mission still DRAFT with no sorties." Pressing Confirm through the view model must show exactly
# one Generation row for the mission, of kind OUTLINE and status QUEUED with no sortie attached; the queue lambda
# recorded exactly that row's id, once, and nothing else; the Mission still DRAFT at the current week's start; and
# no sorties at all. The enqueue happens only after the manager call has returned, so the id the lambda got is the
# id of a row that was committed. Plan Decision "A configured Confirm leaves the Launchpad looking exactly as it
# did before": nothing else is asserted about what the draft shows — no 'Planning…' line, because the live status
# display is a later run's.
t confirmWithMissionControlConfiguredQueuesOneOutlineGenerationForEnqueuingAndLeavesTheWeekADraft

# Brief case 2: "Confirm with a blank host, a whitespace-only host, and a blank token, each on a fresh draft: no
# row, nothing recorded, and notConfiguredMessage set." One test per setting the brief names, each on its own
# freshly primed draft (Plan Decision: a fixed clock gives one current-week draft per database, so a fresh draft
# means a fresh test). Each must show: no Generation row at all, neither lambda recording anything, the Mission
# still DRAFT, and the view model reporting a message the draft can show.
# Plan Decision "The not-configured line is cleared by the next attempt": each of these then sets a valid host and
# token and Confirms again on the same draft, and that second Confirm must clear the message and queue exactly one
# row — the line lingering after the owner fixes their settings in Mission Control is what they would notice.
t confirmWithABlankHostQueuesNothingAndReportsTheNotConfiguredMessage
t confirmWithAWhitespaceOnlyHostQueuesNothingAndReportsTheNotConfiguredMessage
t confirmWithABlankTokenQueuesNothingAndReportsTheNotConfiguredMessage

# Brief case 3: "Continue without outline after a Confirm: the Mission is ACTIVE, sortie 1 is current, the outline
# id has been passed to the cancel lambda, and the row is CANCELLED." Confirm first with Mission Control
# configured, so a QUEUED outline row genuinely exists, then call the view model's continueWithoutOutline(). It
# must show: the Mission reads ACTIVE at the current week's start; the first sortie exists and is the current one;
# the cancel lambda recorded exactly the outline row's id, once; and that row now reads CANCELLED.
# Plan Decision "Continue-without-outline cancels the outstanding outline, and that is the end of it": the row is
# the one WorkManager would have driven, so cancelling it stops a generation landing on a week the owner has
# already started without one.
t continueWithoutOutlineAfterAConfirmActivatesTheWeekMakesSortieOneCurrentAndCancelsTheOutlineGeneration

# Brief case 4: "Retry after a FAILED outline row with an error: a new QUEUED row is now the newest non-cancelled
# outline, the FAILED row keeps its status and error text, and the new id is recorded by the queue lambda." Leave
# the current draft with one FAILED OUTLINE row carrying an error — the way production would, through
# GenerationDao.markFailed — then call the view model's retry(). It must show: a new QUEUED OUTLINE row for the
# mission, which the mission's newest-non-cancelled-outline read now returns in preference to the FAILED one, with
# an id different from the failed row's; the FAILED row still FAILED, with its error text unchanged and not
# rewritten to CANCELLED; and the queue lambda recording the new id once, after the first Confirm's id.
# Plan Decision "A Retry the manager refuses stays silent": the view model swallows IllegalStateException, so a
# mistimed retry reaches no exception handler and shows nothing — the new row existing is what catches that case.
t retryAfterAFailedOutlineQueuesANewOutlineGenerationAsTheNewestAndRecordsItForEnqueuing

# ─── 4. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the worker, enqueue and cancel tests, In-Flight and reopening, Landed, Re-fly,
# export/import, Mission Control and Test connection, the domain tests, the shell tests, the Launchpad tests, and
# the build gate." The gate above runs the whole suite; these name the classes this run reaches underneath — the
# three work classes the view model now calls, the manager and DAO the picks change, the ten test classes whose
# fixtures move to continueWithoutOutline, and the two classes that render the shell through the real activity:
# they compose LaunchpadScreen with WorkManager never initialised, so a view model that asks the container for its
# enqueuer at construction time rather than when an action runs fails them.
echo ""
echo "=== the tests the brief says must not break ==="

c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationWorkerTest
c MissionManagerTest
c GenerationDaoTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c LaunchpadStatesTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadReFlyTest
c LaunchpadScrubTest
c InFlightLandScrubTest
c InFlightLeaveTest
c InFlightRunTest
c InFlightStatesTest
c InFlightScreenTest
c LandedStatesTest
c MissionManagerReFlyTest
c ReFlyTest
c DatabaseExportTest
c DatabaseImportTest
c BackupImportRoomTest
c BackupRoundTripTest
c BackupViewModelTest
c MissionControlScreenTest
c MissionControlViewModelTest
c ConnectionViewModelTest
c DaemonConnectionCheckTest
c LiftoffShellTest
c ShellNavTest
c MainActivityTest
# The domain tests the brief names, and the schema test that says the exported schema still matches the database.
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
t exportedV1SchemaMatchesDatabase

# ─── 5. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a new version file or a rewritten one is a schema change.
# The picks add two DAO reads and no entity, and the new test file adds no table, so nothing here should move.
echo ""
echo "=== the Room schema, and what came in from elsewhere ==="

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

    # Brief: "Do not bring over any run: commit or any file under .rho/runs/." Every file under .rho/runs that
    # this run's commits touch has to belong to this run; another run's plan, tasks or verify script arriving with
    # a pick is the shape a range cherry-pick leaves behind.
    stray="$(git log --no-merges --name-only --pretty=format: "$base_commit..HEAD" -- .rho/runs 2>/dev/null | grep -v '^[ \t]*$' | grep -v "^$RUN_DIR/" || true)"
    if [ -n "$stray" ]; then
        bad "only this run's files under .rho/runs changed (these came in from elsewhere): $(echo "$stray" | tr '\n' ' ')"
    else
        ok "no other run's files were brought in"
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
