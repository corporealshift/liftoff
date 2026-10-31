#!/usr/bin/env bash
# Verify: "Cancel and supersede coach generations" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then every named test read out of the JUnit XML reports that run
# produced, then the test classes the brief's "Must not break" list names, then the Room schema the brief says not
# to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# six named tests are new to this run and none of them exists in the tree today. Names are matched across every
# report, so which file each test sits in is the implementer's call; what it must show is written in the comment
# above it.
#
# The brief's five test bullets map onto the six names: its two cancel bullets are two names (a row with a session,
# and the daemon unreachable); the brief's "does nothing to a row that is already SUCCEEDED, FAILED or CANCELLED"
# clause is a third; the worker's store guard is three (cancelled mid-turn; a superseded Flight Plan, which the
# brief asks also shows the newer row still storing; a superseded outline). The cancel helper's other two asks —
# that it cancels the unique work, and that it is exposed from AppContainer — are not separate names: they are
# asserted inside the cancel names below, which reach the helper through the application's own container and read
# the work back under its name.
#
# What is NOT checked here, and why:
# - The wording of ARCHITECTURE.md's M3 row. The brief asks for it and the plan's Files list names the line, but a
#   regex over a doc row is a text check, not a behaviour check, and it rejects correct work worded differently.
#   Kyle reviews the row in the pull request. Nothing here reads a document.
# - That the check and the store sit in one Room transaction. That is how the guard is built, and it cannot be
#   observed from outside: a cancel racing a store cannot be forced to land in the gap between them. What the
#   cancelled-mid-turn name below shows is the behaviour the transaction is for — the worker reaches its store with
#   a valid reply in hand and a row that has since been cancelled, and nothing is written.
# - Cancelling an id that has no row at all. The plan's Decisions (added by review) says it cancels the work and
#   returns without throwing. That is a decision, not one of the brief's test bullets, and the brief's own
#   missing-row case is already on the worker side, where aGenerationRowThatDoesNotExistReturnsFailureAndSendsNothingToTheDaemon
#   stays green under the gate.
# - Whether queueing a newer generation cancels the older row at enqueue time. The plan's Decisions says it does
#   not: the enqueue helper is unchanged and the older row turns CANCELLED when its own worker reaches the guard.
#   The superseded names below assert exactly that shape — the older row is still cancellable-looking when it is
#   asked to run, and becomes CANCELLED by running — and no name here asks the enqueue helper to cancel anything.
# - The spelling of the unique work name, or that one file rather than two spells it. What is checked is the
#   behaviour: the work the enqueuer scheduled under its own name is the work the cancel helper cancels, read back
#   from WorkManager in the two cancel names below.
# - How many rho.session.stop frames a cancelled mid-turn worker sends. The helper stops the session and the
#   worker's own guard stops it again; both are harmless and the plan's Testing says not to pin a count there. The
#   count is pinned only in the first name, where the helper is the only thing that could have stopped anything.
# - The 20-minute turn limit, WorkManager's retry policy and backoff, and a WorkManager stop of a running worker.
#   A limit that long cannot be waited out in a test; the plan's Risks keeps every case inside the class's own
#   bounded wait. "Return success so WorkManager does not retry" is asserted as the Result doWork() returned.
# - Any UI. The brief names no screen and app wiring is explicitly the next run, so no UI test is named.
# - "Every wait has a timeout and nothing depends on timing (CI is ubuntu-latest, UTC)". CI runs exactly the gate
#   this script runs, and the build kills a test task that goes five minutes without finishing, writing no
#   reports — which this script treats as a failure and re-runs once, as the brief asks. Forcing UTC on this
#   machine was tried in an earlier run and does not work: the pinned JDK ignores TZ.
# - The Room schema's shape beyond "unchanged": no entity change and no migration are checked by comparing the
#   exported schema under app/schemas against the tree this run started from.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, and comment length.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/cancel-and-supersede-coach-generations-xega"

# Scratch files live under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
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
# a failure too.
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
# Brief Done-when: "these tests pass, the M3 row is updated, and bash gradlew.sh :app:assembleDebug
# :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the cancel helper, the container accessor, the shared work name and the store guard;
# - the brief's "Must not break" check for the whole suite: the worker's 25 existing tests, the enqueue helper's
#   test, the turn runner's, the coach, data and domain tests, and every other test the app already has;
# - the check that no test hangs and nothing depends on the local clock, since the build kills a test task that
#   goes five minutes without finishing — the hang the brief warns about for this run.
# Stale reports are removed first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

# No `gradlew --stop` here: other runs' worktrees on this machine have their own live daemons, and a stop would
# break a build that is not this one. A daemon that dies mid-gate is caught by the retry path below.

run_gate() {
    local n="$1"
    rm -rf "$RESULTS" 2>/dev/null || true
    if [ -d "$RESULTS" ]; then
        echo "  … could not clear $RESULTS — a stranded Gradle test executor is holding it; running: bash gradlew.sh --stop"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
        rm -rf "$RESULTS" 2>/dev/null || true
        [ -d "$RESULTS" ] && { echo "could not clear $RESULTS" | tee "$WORK/gate-$n.log"; return 2; }
    fi
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these failures get another gate:
# - the known flake (doneBodyweightSetStepperShowsBW, a Room pool closed under a concurrent observe());
# - a build killed by a daemon or memory error;
# - a hung suite that wrote no report, which app/build.gradle.kts kills at 5 minutes — the brief warns about this
#   one for this run, and says to re-run it.
# A compile error, or a real failed assertion, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|exceeded.*timeout' "$WORK"/gate-*.log 2>/dev/null; then
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
    return 1
}

gate_ok=0
for attempt in 1 2 3; do
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure; then
        echo "  … attempt $attempt failed on the known flake or on the build itself; stopping daemons and running the gate again"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
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
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK"/gate-*.log 2>/dev/null; then
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

# ─── 2. The cancel helper ──────────────────────────────────────────────────────
# All three names below reach the helper through the application's own container —
# (ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication).container — never a second
# AppContainer, which would open a second DataStore and a second Room database on the same files. That is also how
# the brief's "exposed from AppContainer" is proven: the call is made the way the app wiring that comes next will
# make it. WorkManager has to be initialised for these tests (WorkManagerTestInitHelper.initializeTestWorkManager
# in @Before, closeWorkDatabase() in @After, as GenerationEnqueuerTest does), because the helper cancels work as
# well as marking the row, and the work is read back from WorkManager. The daemon is the scripted FakeDaemon the
# worker test already uses, with its host/port/token/workspace written into container.settingsStore.
echo ""
echo "=== the cancel helper: a generation that is still going ==="

# "For a generation id it cancels the unique work, marks the row CANCELLED, and stops its rho session if the row has
# one and the daemon can be reached." Seed a QUEUED or RUNNING generation row that carries a nabuSessionId (the id
# the fake daemon gives every session it sees), and schedule its work through container.generationEnqueuer() first,
# so the unique work genuinely exists under the enqueuer's own name before the cancel. It must show: the row reads
# CANCELLED with finishedAt stamped; exactly one rho.session.stop frame reached the fake and the session_id it names
# is the one the row carried; the work under that same unique name now reads WorkInfo.State.CANCELLED, not
# ENQUEUED or RUNNING — which is also the proof that the helper cancels the work the enqueuer scheduled, not a
# differently-spelled one; and nothing was stored for that generation (no outline on the mission, no Flight Plan on
# the sortie). Today no cancel helper exists anywhere in the app, so this cannot pass on untouched code.
t cancellingAGenerationStopsItsSessionAndMarksTheRowCancelled

# "If the daemon is unreachable, cancelling still succeeds locally." Point the settings the container reads at a
# host and port nothing listens on — a port the test itself opened and closed, the way pointSettingsAtNothing does
# — and cancel a row that carries a session id. It must show: the call returns without throwing; the row reads
# CANCELLED with finishedAt stamped; and the work under the generation's unique name still reads CANCELLED. The
# local part happens whatever the daemon does.
# Plan Decision "Does a cancel still work when Mission Control is offline?": yes, and local-first — the work and the
# row are dealt with before any daemon call, and a failed connect or a refused stop is swallowed rather than
# turning the cancel into an error.
t cancellingAGenerationWithTheDaemonUnreachableStillCancelsTheRowAndItsWork

echo ""
echo "=== the cancel helper: a generation that already finished ==="

# "It does nothing to a row that is already SUCCEEDED, FAILED or CANCELLED." Seed one SUCCEEDED row whose result
# was genuinely stored (its outline or Flight Plan is on the mission) and one FAILED row carrying an error, both
# with nabuSessionId, finishedAt and error filled in, and cancel each id. It must show: each row still reads
# exactly as it was seeded — status, finishedAt, error and nabuSessionId all unchanged, the SUCCEEDED row not
# turned CANCELLED — the stored result is still there, and no rho.session.stop frame reached the fake at all: a
# generation that is over does not hand its session back. Whether the helper also cancels leftover work for a
# finished id is not pinned here; what must not happen is the row or its result changing.
# Plan Decision "What does cancelling a generation that already finished do?": nothing to the row — cancelling a
# result the user can still read would be a way to lose it.
t cancellingAGenerationThatAlreadyFinishedLeavesTheRowAndItsStoredResultAlone

# ─── 3. The worker stores only a current result ────────────────────────────────
# These names run the worker the way the existing GenerationWorkerTest harness does — TestListenableWorkerBuilder
# with the generation id in its input data, doWork() inside a bounded wait, one scripted turn per prompt on the
# same FakeDaemon, replies from CoachFixtures.fixture(...) — with the cancel helper called through the same
# container. WorkManager has to be initialised for the mid-turn case too, because cancelling it goes through
# WorkManager.
echo ""
echo "=== the worker: its row was cancelled while its turn was still going ==="

# "A worker whose row was cancelled before the turn ended stores nothing." Script an assistant reply with **no end
# state** — the fake's turn ends only when something pushes a state_change — and start the worker off the test
# thread under the class's own bounded wait. Wait (bounded) for this row's prompt frame to reach the fake, then
# cancel the generation through the container's helper; the fake answers rho.session.stop by pushing a completed
# state_change, which is what ends the turn, so the worker is forced to reach its store with a valid reply in hand
# and a row that has already been marked CANCELLED. It must show: nothing was stored — the sortie still has no
# Flight Plan (or the mission still has no outline) and the row is not SUCCEEDED; the row reads CANCELLED; and
# doWork() returned ListenableWorker.Result.Success, so WorkManager is not asked to retry a row it may never
# write. Do not require a single stop frame here: the helper's stop and the worker's own stop after the guard are
# both there, and both are harmless. Today the worker stores whatever its turn produced, so this cannot pass on
# untouched code.
# Plan Decision "When the worker finds its generation is no longer current, what do the row and the retry look
# like?": store nothing, CANCELLED if it isn't already, success rather than a retry.
t aWorkerWhoseGenerationWasCancelledMidTurnStoresNothingAndEndsAsDone

echo ""
echo "=== the worker: a generation that a newer one has replaced ==="

# "A worker whose generation was superseded by a newer row for the same sortie stores nothing, and the newer row's
# result is still stored when it runs." Seed two FLIGHT_PLAN rows for the same sortie, with createdAt values that
# make one clearly older (the plan's Risks says to space them out rather than lean on the id tie-break), and script
# two turns — one reply per prompt. Run the older worker first: it must store nothing (the sortie is still PENDING
# with no Flight Plan, the way assertAwaitingGeneration already shows), its row reads CANCELLED, and doWork()
# returned Result.Success rather than a retry. Then run the newer worker on the same sortie: its plan is stored and
# the sortie is PLANNED with a generated plan, and its own row reads SUCCEEDED — the guard rejects the older
# generation without blocking the current one.
# Plan Decision "What status does a superseded row get?": CANCELLED, the status that already exists, with no new
# status and no schema change. Plan Decision "Does queueing a newer generation cancel the older one in this run?":
# not at enqueue time — the enqueue helper is unchanged, and the older row turns CANCELLED when its own worker
# reaches the guard, which is what running the older worker here shows.
t aSupersededFlightPlanGenerationStoresNothingAndTheNewerGenerationStillStoresItsPlan

# "The same for an outline superseded by a newer outline for the same Mission." Seed two OUTLINE rows for the same
# mission — no sortie — with createdAt values that make one clearly older, and script two turns. Run the older
# outline worker: it stores nothing (the mission is left as it was, with no outline written over it), its row reads
# CANCELLED, and doWork() returned Result.Success. Then run the newer one: its outline is stored and its row reads
# SUCCEEDED.
t aSupersededOutlineGenerationStoresNothingAndTheNewerGenerationStillStoresItsOutline

# ─── 4. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the worker's and the enqueue helper's tests, the coach, data and domain tests, and the
# build gate." The gate above runs the whole suite; these are the named proof for the classes this run touches
# underneath: the worker's 25 existing tests (the guard sits after validation and after the repair loop, so the
# happy path, the repair cap, the resume path, the offline mark and the 8th-attempt give-up all have to have stayed
# where they were), the enqueue helper's test (its unique work name is shared with the new helper), the DAO whose
# three reads the brief names, the turn runner whose stop helper the cancel helper calls, the two MissionManager
# store paths the plan's Risks says the new transaction nests with, and the container the cancel accessor is added
# to.
echo ""
echo "=== the tests the brief says must not break ==="

c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationDaoTest
c CoachTurnRunnerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c AppContainerTest

# ─── 5. The Room schema the brief says not to change ───────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a new version file or a rewritten one is a schema change,
# and the plan's Files treats one as a mistake. GenerationStatus.CANCELLED and the three DAO reads already exist,
# so nothing here should move.
echo ""
echo "=== the Room schema ==="

brief_commit="$(git rev-list -1 HEAD -- "$RUN_DIR/brief.md" || true)"
if [ -z "$brief_commit" ]; then
    bad "the Room schema is unchanged (this run's brief.md is not in the history to compare against)"
else
    base_commit="$(git rev-parse "$brief_commit^")"
    if ! git diff --quiet "$base_commit" HEAD -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the committed tree)"
    elif ! git diff --quiet "$base_commit" -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the working tree after the gate run)"
    else
        ok "the exported Room schema is byte-identical to the one this run started from"
    fi
fi

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
