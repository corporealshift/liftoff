#!/usr/bin/env bash
# Verify: "Test connection in Mission Control" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate (the two tasks .github/workflows/ci.yml runs), then every named test read out of the JUnit
# XML reports that run produced, then ARCHITECTURE.md's M1 row, then the classes the brief says must not break.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports.
# A test that was never written cannot pass. That is why this script fails on untouched code: the action and its
# tests do not exist yet, and ARCHITECTURE.md's M1 row still defers test connection. Names are matched across
# every report, so the implementer decides which class and package each test sits in; the comment above each name
# says what that test must show.
#
# The brief's Done-when names four things the tests must show: each result message, the second tap doing nothing,
# the button in the Connection section, and a result row drawn from the state. It also names the gate. The brief's
# own list of messages has five entries, so five tests carry them. The rest of the brief's body ("saved first",
# "run it off the main thread") is each named once, in plain Kotlin over the action's own state. "Show that it's
# working" is shown by the busy state in the second-tap test and by the busy row in the render-only screen test.
# The only Robolectric tests named are the two the brief's Done-when asks for.
#
# What is NOT checked here, and why:
# - "Give up after a reasonable timeout" and "always close the connection". The copied client already caps the
#   check at DaemonConnectionCheck.TIMEOUT_MS and closes its socket in a finally, and DaemonConnectionCheckTest
#   already proves both; the brief says not to change the client copy. A test that waits out the real 10-second
#   cap would only make CI slower, and a socket left open hangs MockWebServer's shutdown, which fails the gate on
#   its own. The row the action shows when the answer never arrives is the offline row, named below.
# - "Use buttons from ui.theme and the design's rows, not stock M3 visuals", "keep the state logic in plain
#   Kotlin outside Compose", and "don't change the client copy". These are properties of how the work is written.
#   A script that greps them out of files fails correct work laid out differently. The gate compiles the app and
#   runs the client's own tests; the owner reviews the visuals in the pull request.
# - The exact wording of the rows the plan's Decisions fix (the refused-token sentence, the missing-settings
#   sentences, the busy words), the muted note naming a setting the daemon cannot be reached through, the row for
#   a daemon that named no version, and the invalid port stopping the test. The owner reviews those decisions in
#   the pull request. Where a named test below also rests on one, its comment says so.
# - The update to the existing placeholder test the brief allows to change. Its name would no longer describe it,
#   so it is not named here; the gate runs it whatever it is called, and it fails by itself if the button arrives
#   without the update.
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
# - the compile check for the new action, its section, and the seam the shell passes into the screen;
# - the "must not break" check for the whole suite, including the existing placeholder test the brief allows to
#   change. That test fails on its own if the button arrives without the update;
# - the check on "every wait in a test needs a timeout": a wait that never ends hangs the suite, and a suite that
#   outlives the timeout is a failure, not a run that is still going.
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
        [ -d "$RESULTS" ] && { echo "could not clear $RESULTS — a stranded Gradle test executor is holding it" | tee "$WORK/gate-$n.log"; return 2; }
    fi
    mkdir -p app/build
    timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these failures get another gate:
# - the known flake (doneBodyweightSetStepperShowsBW, a closed Room connection pool; re-run it before treating it
#   as broken);
# - a build killed by a daemon or memory error, or a build stopped by a task-input race with no failed test;
# - a hung suite that wrote no report.
# A compile error, or a real failed assertion, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|which doesn.t exist|exceeded.*timeout' "$WORK"/gate-*.log 2>/dev/null; then
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
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 40 minutes"
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

# ─── 2. The action's result rows: the brief's five messages ─────────────────────
# Brief: "The result shows as an ink-ruled row", listing five outcomes. The cheapest test that shows each one is
# plain Kotlin over the action's own state, with no screen and no Robolectric. It drives the action against a fake
# daemon (OkHttp MockWebServer WebSocket) or a fake of the container helper, as the brief's Done-when allows.
# Every wait has a timeout, so nothing depends on timing or on this machine: CI runs ubuntu-latest in UTC.
echo ""
echo "=== the result the action reports ==="

# "on success, the daemon version and the protocol version": the check (a fake daemon answering a real rho.hello,
# or a fake of the container helper) reports both versions, and the row the state carries names each version the
# daemon actually gave.
t aSuccessfulConnectionTestShowsTheDaemonsVersionAndTheProtocolVersion

# "major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x'": the fake daemon refuses the
# hello for a different protocol major, or the outcome handed in is the mismatch one. The row names the daemon's
# major. Liftoff's own major is the one the copied client expects, read off its protocol version rather than a
# number typed into the row.
t aProtocolMajorMismatchShowsBothProtocolMajors

# "unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'": the answer never arrives,
# and the row is that sentence. It is the same row whether the socket would not connect or the daemon never
# answered. Decision: "How long the test waits before calling it offline". The cap is the copied check's own, so
# this test hands in the unreachable outcome instead of waiting out 10 seconds.
t anUnreachableOrTimedOutDaemonShowsTheOfflineRow

# "auth rejected: a clear message saying the token was refused": the fake daemon answers the upgrade with HTTP
# 401, or the outcome handed in is the authentication-rejected one. The row says the token was refused, and it is
# a different row from the offline one.
t aRefusedTokenShowsThatTheTokenWasRefused

# "empty host or token: a clear message saying which is missing": a blank host, a blank token, and both blank.
# Each names the setting that is missing, and no check is run for any of them.
t missingHostAndMissingTokenEachSayWhichSettingIsMissing

# ─── 3. What the action does while and before it runs ───────────────────────────
# Brief: "While the test runs, show that it's working and ignore a second tap", "Run it off the main thread",
# and "If the owner has unsaved edits in the connection fields, they are saved first."
echo ""
echo "=== the action's own behaviour ==="

# "show that it's working and ignore a second tap": a latch holds the worker inside the check, so the second call
# meets the action while it is still busy. The state is the busy one, the second call changes nothing, and the
# check runs once.
t aSecondTapWhileTheTestIsRunningChangesNothing

# "Run it off the main thread": the check runs on the action's worker dispatcher, not on the thread that asked
# for it. This is the pattern the BACKUP tests already use.
t theConnectionTestRunsOffTheCallingThread

# "they are saved first": the fields hold a host the store does not hold yet. When the fake check runs, the store
# already holds that host, so the tap tested the address that was just typed. Decision: "Whether the test uses
# edits the owner has not saved". Edits already save as typed; the action waits for that write.
t theSettingsInTheFieldsAreSavedBeforeTheCheckRuns

# ─── 4. The button and the row on Mission Control ───────────────────────────────
# Brief Done-when: "A Robolectric Compose test shows the button in the Connection section and a result row drawn
# from the state." These are the only UI tests this run needs. The screen is unmounted before its database is
# closed, and every wait is a waitUntil with a timeout.
echo ""
echo "=== Mission Control's Connection section ==="

# "A 'Test connection' button in the Connection section": the button is on the screen, inside the CONNECTION card.
t testConnectionButtonIsShownInTheConnectionSection

# "a result row drawn from the state": a result row, and the busy row, are drawn from the state the section is
# given, with no typing and no daemon. Decision: "What the button looks like while the test runs". The busy words
# sit in the ruled row.
t aConnectionResultRowIsDrawnFromTheState

# ─── 5. ARCHITECTURE.md's M1 row ────────────────────────────────────────────────
# Brief Done-when: "M1's note no longer lists test connection as missing (M1 is done)." Today the row's status
# cell reads "Done — test connection comes later; export and import are built". The check rejects that deferral
# instead of demanding one new sentence. No clause that names the connection test may hold it back, and the cell
# must still say the milestone is done. Clauses are split on ';' and on the dashes this document uses, so a
# deferral counts only against the clause that names the connection test.
echo ""
echo "=== ARCHITECTURE.md ==="

cell() { # the status cell of one milestone table row: the 4th field of the |-split line
    local prefix="$1"
    awk -v p="$prefix" 'index($0, p) == 1 { print; exit }' ARCHITECTURE.md \
        | awk -F'|' '{ print $4 }' | tr -d '\r'
}

# A clause names the connection test. The wording is the author's, so the phrase is matched loosely.
names_the_connection_test() {
    grep -qiE 'test connection|connection test|connection check|test the connection|connection card|connection action|connection section|daemon handshake|rho\.hello' <<<"$1"
}

# A clause defers it. Only phrases that put the thing off count, so a clause describing the feature's own copy
# ("the row says which setting is missing") is not read as a deferral.
holds_the_connection_test_back() {
    local clause="$1"
    names_the_connection_test "$clause" || return 1
    grep -qiE 'later|to come|comes next|come next|is next|not built|not yet|not started|not implemented|unbuilt|placeholder|pending|deferred|left out|still missing|remains|awaiting' <<<"$clause"
}

m1_cell="$(cell '| M1 |')"
if [ -z "$m1_cell" ]; then
    bad "the M1 row is in ARCHITECTURE.md's milestone table"
else
    held_back=0
    while IFS= read -r clause; do
        if holds_the_connection_test_back "$clause"; then held_back=1; fi
    done < <(sed 's/[;—–]/\n/g' <<<"$m1_cell")
    if [ "$held_back" -eq 1 ]; then
        bad "ARCHITECTURE.md's M1 row does not hold the connection test back (a clause that names it defers it; the status cell reads: $m1_cell)"
    elif ! grep -qiE 'done|built|exists|in place|shipped|complete|implemented|landed|✅' <<<"$m1_cell"; then
        bad "ARCHITECTURE.md's M1 row says the milestone is done (its status cell reads: $m1_cell)"
    else
        ok "ARCHITECTURE.md's M1 row no longer lists the connection test as missing"
    fi
fi

# ─── 6. Must not break ──────────────────────────────────────────────────────────
# The gate ran the whole suite. These checks only stop the classes on the brief's "must not break" list from
# being dropped, or from having a test skipped.
echo ""
echo "=== must not break ==="

# "settings, equipment and export/import in Mission Control and their tests".
c MissionControlScreenTest
c MissionControlViewModelTest
c SettingsStoreTest
c EquipmentViewModelTest
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest
c BackupImportRoomTest
c BackupViewModelTest

# "the shell".
c LiftoffShellTest

# "the rho client tests": every class in the copied client's package, which the brief says not to change.
# The class keys in $WORK/classes start with "C:", so the prefix match includes it. index(), not a regex.
rho_classes="$(awk 'index($1, "C:com.liftoff.app.rho.") == 1' "$WORK/classes" | wc -l)"
rho_bad="$(awk 'index($1, "C:com.liftoff.app.rho.") == 1 && $2 != "pass"' "$WORK/classes" | wc -l)"
if [ "$rho_classes" -gt 0 ] && [ "$rho_bad" -eq 0 ]; then
    ok "every test class in com.liftoff.app.rho ran and passed ($rho_classes classes)"
else
    bad "the copied rho client's tests ran and passed"
fi
c AppContainerTest

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
