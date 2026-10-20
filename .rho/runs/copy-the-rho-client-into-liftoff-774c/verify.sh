#!/usr/bin/env bash
# Verify: "Copy the rho client into Liftoff" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate (the two tasks .github/workflows/ci.yml runs), then every named test read out of the JUnit
# XML reports that run produced, then the existing tests the brief says must not break.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports.
# A test that was never written cannot pass. That is why this script fails on untouched code: the connection check
# and its tests do not exist yet. Names are matched across every report, so the implementer decides which class and
# package each test sits in. The comment above each name says what the test must show.
#
# The brief's Done-when names four test cases: success with both versions, a major-version mismatch, a refused
# connection, and empty settings. The brief's own list of outcomes names a fifth, authentication rejected, so it is
# named here too. What the hello frame carries is named once, because the brief says the check "runs rho.hello with
# client liftoff".
#
# What is NOT checked here, and why:
# - Any UI. The brief says "This run adds no UI".
# - That the copied files carry their source header, match upstream rho apart from package and import lines, and
#   are the smallest compiling set. Those are properties of how the source text is written. A script that greps
#   them out of files fails correct work laid out differently. The gate proves the copy compiles under the app and
#   that the tests driving it pass. The owner checks the copy's header and its diff against upstream in the pull
#   request.
# - The DESIGN.md amendment and the ARCHITECTURE.md row. Those are documents, not behavior. The owner reviews their
#   wording in the pull request.
# - Line endings, "no DI framework", "add a dependency only if needed", and "nothing in rho changes". These are
#   properties of how the work is written, or of another repository. They are reviewed in the pull request.
# - The plan's second mismatch case (a daemon that accepts the hello but answers with a foreign major) and the check
#   closing its socket. Both are plan decisions the owner reviews. A socket left open hangs MockWebServer's shutdown,
#   which fails the gate on its own.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch files live under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
mkdir -p "$WORK" || exit 1
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
    grep '^C:' "$WORK/all" > "$WORK/classes" || true
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
# Brief Done-when: "./gradlew :app:assembleDebug :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the copied files under com.liftoff.app.rho;
# - the "must not break" check for the whole suite.
# Stale reports are removed first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

bash gradlew.sh --stop >/dev/null 2>&1 || true

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
# - the known flake (doneBodyweightSetStepperShowsBW);
# - a build killed by a daemon or memory error;
# - a hung suite that wrote no report.
# A compile error, or a real failed assertion, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|exceeded.*timeout' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
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
fi

# ─── 2. The connection check: the brief's outcomes ─────────────────────────────
# Brief: AppContainer builds a client from the current settings, connects it, and runs rho.hello with client
# liftoff. "The result says one of" five things. Plain Kotlin driven against a fake daemon that scripts rho.hello,
# with no screen, no real host, and a timeout on every wait.
echo ""
echo "=== the connection check's outcomes ==="

# "connected, with the daemon version and protocol version": the fake answers rho.hello with both, and the
# outcome carries each one exactly as the daemon sent it.
t helloSuccessReportsTheDaemonsVersionAndProtocolVersion

# "run rho.hello with client liftoff": the frame the fake received is a rho.hello that names the client liftoff
# and carries the copied client's protocol version. The request carried the stored token as a Bearer header.
t theHelloNamesTheClientLiftoffAndCarriesTheStoredToken

# "the protocol major version doesn't match": the fake refuses the hello with RpcCodes.PROTOCOL_MISMATCH (-32006).
# The outcome is the mismatch one, not unreachable. Decision: "A protocol major mismatch is its own outcome,
# naming both versions."
t aProtocolMajorMismatchIsReportedAsAMismatchAndNamesBothVersions

# "unreachable or timed out": nothing is listening on the port the test aims at (a server is started, its port
# taken, then shut down). The outcome is unreachable, and it arrives instead of hanging.
# Decision: "The check answers within 10 seconds and closes the socket it opened."
t aConnectionNobodyAcceptsIsReportedAsUnreachableWithinTheChecksTimeout

# "the host or token is empty": a blank host, and separately a blank token, each give the not-configured outcome,
# and no connection is opened. Decision: "A blank token means 'not configured', not 'try without a token'."
t emptySettingsAreReportedAsNotConfiguredAndOpenNoConnection

# "authentication rejected": the fake answers the upgrade with HTTP 401. The outcome is authentication rejected,
# not unreachable.
t aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable

# ─── 3. AppContainer builds it from the settings it stores ──────────────────────
# Brief: "Give AppContainer a way to build a client from the current settings (daemon host, port, token), connect
# it and run rho.hello with client liftoff."
echo ""
echo "=== AppContainer's connection check, built from the stored settings ==="

# Persist a host, port and token pointing at the fake daemon. The check the container builds from those stored
# settings reports connected, with the versions the fake gave.
t theContainerBuildsTheConnectionCheckFromTheStoredSettings

c AppContainerTest

# ─── 4. The Room column the brief says not to rename ────────────────────────────
# Brief: "Don't rename the Room column nabuSessionId, because that would change the schema." The exported-schema
# test compares the database against its checked-in schema, and the tests that build a Generation by that property
# name stop compiling if it is renamed.
echo ""
echo "=== the Room schema did not move ==="

t exportedV1SchemaMatchesDatabase

# ─── 5. Must not break ─────────────────────────────────────────────────────────
# The gate ran the whole suite. These checks only stop the classes on the brief's "must not break" list from being
# dropped, or from having a test skipped.
echo ""
echo "=== must not break ==="

# Mission Control, including export and import.
c MissionControlScreenTest
c MissionControlViewModelTest
c EquipmentViewModelTest
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest
c BackupImportRoomTest
c BackupViewModelTest

# The coach package, which stays free of Android imports (invariant 2).
t coachPackageHasNoAndroidImports
c CoachReplySemanticTest
c CoachPromptGoldenFileTest
c CoachSchemaLoadTest

# The other screens and the app's entry point.
c InFlightScreenTest
c LiftoffShellTest
c MainActivityTest

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
