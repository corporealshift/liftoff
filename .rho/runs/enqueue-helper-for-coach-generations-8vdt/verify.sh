#!/usr/bin/env bash
# Verify: "Enqueue helper for coach generations" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then every named test read out of the JUnit XML reports that
# run produced, then the test classes the brief's "Must not break" list names, then ARCHITECTURE.md's
# com.liftoff.app.work row.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code: the
# six named tests do not exist yet, and the work row still ends "nothing enqueues it yet". Names are matched
# across every report, so which file each test sits in is the implementer's call; what it must show is written in
# the comment above it.
#
# The brief's one test sentence names four scheduling behaviours (the name, KEEP, the network constraint, the
# 1-minute exponential backoff) and the helper's two rules from its first paragraph (it schedules a
# GenerationWorker run for a generation, and it only schedules). Six names cover them: the constraint and the
# backoff share one name because they are one request's scheduling shape; the name and KEEP share one name
# because KEEP is only visible as its effect on the item under the name; the rest are one each.
#
# What is NOT checked here, and why:
# - ARCHITECTURE.md's M3 milestone row. The brief names only the work row. The plan's Decisions adds the M3 row
#   because its "still to come" list names WorkManager's backoff, which this helper sets — a wording change the
#   owner reviews in the pull request, not a behaviour.
# - "Get WorkManager only when the helper is called, so AppContainerTest and the screen tests need no WorkManager
#   setup." The class checks below are the proof: those classes build an AppContainer today with no WorkManager
#   setup, so a helper that reached WorkManager at construction or in an eagerly-read field would throw in them.
#   No test can show the opposite case: WorkManager's initialized delegate is a process-wide static that leaks
#   across Robolectric test classes in one JVM, so "WorkManager was never initialized here" is not something a
#   later test class can rely on.
# - Whether the request is expedited. The plan's Decisions builds a plain one-time request; the brief says nothing
#   about expedited work, and the plan's Testing section names no test for it.
# - Calling the helper again for a generation whose run has already finished. The plan's Decisions accepts a fresh
#   run and adds no "has this already run" rule; the brief asks for none.
# - The worker actually running, and anything on the daemon side. The brief says the helper only schedules, and the
#   plan's Risks deliberately leaves the network constraint unmet rather than meeting it with TestDriver, so no
#   worker runs in these tests. GenerationWorkerTest is checked as a class instead.
# - App wiring: nothing in the UI calls the helper yet, so no screen test is named and no UI test is named.
# - The Room schema. The brief neither changes nor mentions it; the gate runs the data tests unchanged.
# - "Every wait has a timeout; CI is ubuntu-latest, UTC." CI runs exactly the gate this script runs, and the build
#   kills a test task that goes five minutes without finishing, writing no reports — which this script treats as a
#   failure. Forcing UTC on this machine was tried in an earlier run and does not work: the pinned JDK ignores TZ.
# - The test class's own shape (Robolectric @Config(sdk = [34]), WorkManagerTestInitHelper.initializeTestWorkManager,
#   which file the helper lives in, whether the helper returns the request it built). Those are how it is built;
#   the brief names them as the shape it expects, and the comments below say what each test has to show.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, and the wording of the work row beyond what is named.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/enqueue-helper-for-coach-generations-8vdt"

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

# t <method>: the named test must have run in this invocation and passed.
t() {
    local method="$1" st
    st="$(awk -v m="$method" '$1 == m { print $2; exit }' "$WORK/methods" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$method" ;;
        fail)    bad "$method ran but did not pass" ;;
        skipped) bad "$method was skipped" ;;
        none)    bad "$method did not run" ;;
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
# Brief Done-when: "bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the helper, for the AppContainer member that exposes it, and for the new test class;
# - the brief's "Must not break" check for the whole suite: the worker's tests, AppContainerTest, every screen and
#   every other test the app already has;
# - the check that no test hangs and nothing depends on the local clock, since the build kills a test task that
#   goes five minutes without finishing and writes no reports.
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
        if [ -d "$RESULTS" ]; then
            echo "could not clear $RESULTS" > "$WORK/gate-$n.log"
            return 2
        fi
    fi
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these failures get another gate:
# - the known flake (doneBodyweightSetStepperShowsBW, a Room pool closed under a concurrent observe());
# - a build killed by a daemon or memory error;
# - a hung suite that wrote no report, which the brief warns can happen once for about five minutes on this
#   machine and says to re-run rather than fix.
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
last_gate=""
for attempt in 1 2 3; do
    last_gate="$WORK/gate-$attempt.log"
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
# Only the attempt that is being reported on: an earlier attempt that was served from the cache is not this one.
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$last_gate" 2>/dev/null; then
    bad "unit tests executed in the gate run (the task came from the build cache, was up to date, or had no tests)"
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

# ─── 2. The enqueue helper ─────────────────────────────────────────────────────
# Every test below schedules work through the helper and reads back what WorkManager was handed. They run under
# Robolectric with WorkManager set up by WorkManagerTestInitHelper.initializeTestWorkManager, which gives an
# in-memory WorkManager the test can query on the main thread. The network constraint is left unmet on purpose
# (the plan's Risks): meeting it with TestDriver would let GenerationWorker actually run, with no daemon and
# through a container the test does not control. Left unmet the work stays pending, which is what the
# one-item-under-a-name checks read. Every wait takes a timeout or runs inside withTimeout.
#
# Where the four scheduling properties are readable: constraints and backoff are NOT on WorkInfo — they are on
# the built request's WorkSpec (constraints, backoffPolicy, backoffDelayDuration, input, workerClassName), which
# is why the plan has the helper hand the request it built back to the caller. The unique name and the item under
# it are readable through WorkManager's own getWorkInfosForUniqueWork(name), whose WorkInfo carries the id of
# the request that was scheduled.
echo ""
echo "=== the request the helper builds ==="

# "the network constraint and the 1-minute exponential backoff" (brief). For generation N, the work the helper
# schedules requires a connected network and backs off exponentially with a first delay of 60 000 ms:
# constraints.getRequiredNetworkType() is NetworkType.CONNECTED, backoffPolicy is BackoffPolicy.EXPONENTIAL, and
# backoffDelayDuration is 60_000 — not the 30 000 WorkManager defaults to, and not LINEAR.
t theScheduledWorkRequiresAConnectedNetworkAndExponentialBackoffFromOneMinute

# "schedules a GenerationWorker run for an existing Generation row" (brief). For generation N, the work the helper
# schedules runs GenerationWorker and carries N under the worker's own input key — GenerationWorker.GENERATION_ID,
# read off the worker class rather than retyped — so the run is aimed at that generation and not at some other
# row. A request with the right name and constraints but no input would make the worker fail on a missing row.
t theScheduledWorkCarriesTheGenerationIdInTheWorkersInputData

echo ""
echo "=== unique work named gen-<generationId>, with KEEP ==="

# "unique work named gen-<generationId>, with ExistingWorkPolicy.KEEP" (brief), and "Calling the helper twice for
# one generation leaves exactly one work item under that name". Call the helper twice for generation N and ask
# WorkManager for the work under the name `gen-N` (the generation id in the name, e.g. gen-42 for 42). It must
# show: exactly one item, still pending, and it is the item the first call scheduled — the same work id the first
# call produced, not a new one. KEEP is nowhere reported by WorkManager, and REPLACE would also leave exactly one
# item under the name; the retained id is the only thing that tells the two policies apart.
t enqueueingOneGenerationTwiceLeavesASingleWorkItemUnderItsName

# "unique work named gen-<generationId>" (brief) read the other way round: the name belongs to one generation.
# Schedule generation N and generation M and ask WorkManager for each name. It must show one item under `gen-N`
# and one under `gen-M`, with different ids: scheduling a second generation does not replace or swallow the first.
t twoGenerationsAreScheduledAsTwoWorkItemsUnderTheirOwnNames

echo ""
echo "=== the helper is exposed from AppContainer, and it only schedules ==="

# "AppContainer exposes it" (brief). The test calls the member the container exposes — on the application's own
# container, never a second AppContainer, which would open a second DataStore and Room database on the same
# files — for generation N, and WorkManager then has a pending item under `gen-N`. The container is where every
# other dependency in this app is wired (ARCHITECTURE invariant 5: no DI framework), so a helper nobody can reach
# from the container is not exposed.
t theAppContainerExposesTheEnqueueHelper

# "The helper only schedules. It never creates rows" (brief). Call the helper for a generation id that has no
# Generation row, and read the generation table through the container's own database (AppContainer exposes
# `database`, a lazy LiftoffDatabase, whose accessor is generationDao()). It must show the
# table exactly as it was — the row count unchanged and no row with that id, not a placeholder or a queued row —
# while the work item for that id is still scheduled. A helper that creates the row it was handed would pass
# every scheduling test above.
t theEnqueueHelperSchedulesWorkAndCreatesNoGenerationRows

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the worker's tests, AppContainerTest, every screen and the build gate." The gate above
# runs the whole suite; these are the named proof for the classes the brief calls out. AppContainerTest and the
# screen classes are also the brief's own guard on "Get WorkManager only when the helper is called": they all
# build an AppContainer today with no WorkManager setup, so a helper that reached WorkManager when the container
# is built or held it in a field would throw here. GenerationWorkerTest is the worker's own suite, which this run
# must leave untouched.
echo ""
echo "=== the tests the brief says must not break ==="

c GenerationWorkerTest
c AppContainerTest
c MainActivityTest
c LiftoffShellTest
c MissionControlScreenTest
c InFlightScreenTest
c InFlightStatesTest
c LaunchpadStatesTest
c MissionStatesTest
c LandedStatesTest

# ─── 4. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief Done-when: "ARCHITECTURE.md's work row names the helper". Two things on the com.liftoff.app.work row,
# read against the same row in the tree this run started from:
# - the one claim this run makes false is gone: the row today ends "nothing enqueues it yet". Only that literal
#   claim is looked for; other true statements ("nothing in the UI calls it yet") are the writer's call;
# - the row names the helper: a name the doc did not already use at the commit this run started from.
# What the helper is called is the implementer's call — the plan says the names are theirs to keep or adjust — so
# nothing here looks for a particular name.
echo ""
echo "=== ARCHITECTURE.md ==="

brief_commit="$(git rev-list -1 HEAD -- "$RUN_DIR/brief.md")"
if [ -z "$brief_commit" ]; then
    bad "the com.liftoff.app.work row names the helper (this run's brief.md is not in the history to compare the row against)"
else
    base_commit="$(git rev-parse "$brief_commit^")"
    new_row="$(grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\r' || true)"
    if [ -z "$new_row" ]; then
        bad "the com.liftoff.app.work row is in the area map"
    else
        lc_row="$(printf '%s\n' "$new_row" | tr '[:upper:]' '[:lower:]' | tr -d '`*_' | tr -s ' ')"
        if printf '%s\n' "$lc_row" | grep -Fq 'nothing enqueues it yet'; then
            bad "the com.liftoff.app.work row no longer claims nothing enqueues the worker (it still reads: $new_row)"
        else
            # What counts as naming a thing in this table: a backticked token (the doc's convention for an artifact),
            # or a plain word that looks like a code name — an uppercase-initial identifier of eight characters or
            # more, or a file name. Anything the doc already named at the commit this run started from is not the
            # new helper.
            quoted() {
                printf '%s\n' "$1" | awk '{ n = split($0, p, "`"); for (i = 1; i <= n; i++) if (i % 2 == 0 && p[i] != "") print p[i] }' \
                    | tr -d ' \t' | grep -E '^[A-Za-z][A-Za-z0-9_.]*$' | sort -u || true
            }
            plain() {
                printf '%s\n' "$1" | tr '`' '\n' | awk '
                    { n = split($0, w, /[^A-Za-z0-9_.]+/)
                      for (i = 1; i <= n; i++) {
                          sub(/^[.]+/, "", w[i]); sub(/[.]+$/, "", w[i])
                          if (w[i] == "") continue
                          if (w[i] ~ /\.kt$/) { print w[i]; continue }
                          if (w[i] !~ /^[A-Z][A-Za-z0-9_]*$/) continue
                          if (length(w[i]) >= 8) print w[i]
                      } }' | sort -u || true
            }
            names() { { quoted "$1"; plain "$1"; } | sort -u; }
            base_doc="$(git show "$base_commit:ARCHITECTURE.md" 2>/dev/null | tr -d '\r')"
            new_artifact="$(comm -13 <(names "$base_doc") <(names "$new_row") | head -1)"
            if [ -z "$new_artifact" ]; then
                bad "the com.liftoff.app.work row names the helper by name (the row names nothing the doc at $(git rev-parse --short "$base_commit") did not already name: $new_row)"
            else
                ok "the com.liftoff.app.work row names the helper ($new_artifact) and drops the claim that nothing enqueues the worker"
            fi
        fi
    fi
fi

# The M3 milestone row is not checked. The brief names only the work row; the plan's Decisions moves M3's
# "still to come" list because this helper sets the backoff, and that wording is the owner's call in the pull
# request.

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
