#!/usr/bin/env bash
# Verify: "Copy the rho client into Liftoff" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate — the two tasks .github/workflows/ci.yml runs — then every named test read out of the JUnit
# XML reports that run produced, then the copied files checked against upstream rho, then the two documents the
# brief names, then the Room column and the packages the brief says must not break.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code:
# the connection check and its tests do not exist yet. Names are matched across every report, so which class and
# package each sits in is the implementer's call; what it must show is written above it.
#
# The brief's Done-when names four test cases (success with both versions, a major-version mismatch, a refused
# connection, empty settings). The brief's own list of outcomes names a fifth, authentication rejected, and the plan
# tests it, so it is named here too. What the hello frame carries is named once, because the brief's own sentence
# says the check "runs rho.hello with client liftoff".
#
# What is NOT checked here, and why:
# - Any UI. The brief says "This run adds no UI", so no Compose or screen test is named, and the gate's existing
#   screen classes are only guarded against being broken or dropped.
# - That the fake daemon is an OkHttp MockWebServer WebSocket, or that its tests are written "in the style of rho's
#   own client tests". That is how the plan says the tests are built, and it is a style the owner reads in the pull
#   request; from outside, what shows is that each outcome was produced against a socket the test opened itself,
#   which is what the named tests do.
# - "Leave out rho's UI, outbox, push and repository code." Listing the files that must not be there would dictate
#   the copy's shape. What shows from outside is that the gate compiles and runs what is there, and rho's UI,
#   outbox and push code needs rho's own view models, WorkManager jobs and Firebase, none of which Liftoff has.
# - Line endings. `.gitattributes` sets `* text=auto eol=lf`, so Git normalises them on checkout and a check on the
#   worktree could never fail.
# - The plan's second mismatch case — a daemon that accepts the hello but answers a foreign major version — and the
#   check closing the socket it opened. Both are plan decisions and details the owner reviews in the pull request;
#   a socket one end still holds hangs MockWebServer's shutdown and fails the gate by itself.
# - "Keep AppContainer hand-built, with no DI framework" (invariant 5). It is a property of how the file is
#   written, and the container's own class is checked below so nothing else can slip in.
# - "Add a dependency only if the copied code needs one." Whether a dependency is *needed* is a judgement about
#   the copy; what shows from outside is the gate compiling and running the copied code, which it does.
# - "Nothing in rho changes." rho is another repository, outside this one, and it is checked out in other
#   sessions at the same time; what this script can prove is that the copy came from a real commit in it.
# - "Tests must also pass on the GitHub CI runner." The CI run is outside this repository and this script runs
#   before the pull request exists. What this script can do is run CI's exact commands, and it does: nothing
#   below reads the wall clock, a local host, or a machine-specific path except the rho checkout.
# - The exact wording of the DESIGN.md amendment, the ARCHITECTURE.md row's prose, and the plan's other
#   decisions. The owner reviews those in the pull request. Where a check below also rests on a decision, the
#   comment above it names it.
#
# The rho checkout is read only to compare the copy against upstream at the commit the copy's own header names.
# The brief's stop condition — a checkout that cannot be read — is reported as a failure, not skipped.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RHO="C:/Users/corpo/Documents/projects/rho"
RHO_CLIENT="clients/android/app/src/main/java/com/rho/client"
RHO_OK=0
if git -C "$RHO" rev-parse --git-dir >/dev/null 2>&1; then
    RHO_OK=1
fi

# Scratch lives under app/build (gitignored), the only place a runner session may write outside the tracked tree.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
mkdir -p "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote, into $WORK/methods and $WORK/classes as
# "<name> <pass|fail|skipped>" lines, one line per name, at its worst outcome across every report
# (fail is worse than skipped, skipped is worse than pass — so one ignored test marks its class).
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
# Brief Done-when: "./gradlew :app:assembleDebug :app:testDebugUnitTest passes". This is also the compile check
# for the copied files under com.liftoff.app.rho, and the "must not break" check for Mission Control, the coach
# package, the other screens and every other test in the suite, since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this invocation; --no-build-cache stops
# the reports being restored from cache with no test executed.
# The timeout is the check on "every wait in a test needs a timeout": a wait that never ends hangs the suite, and
# a hung suite is a failure, not a run that is still going.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

# Whatever daemon is left from an earlier step is stopped before the cold run, so a stranded test executor is not
# holding $RESULTS when the attempt tries to clear it.
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

# The known flake (doneBodyweightSetStepperShowsBW, closed connection pool), a build killed by a daemon or memory
# error, and a hung suite that died at the build's own 5-minute test-task timeout without writing any report are
# the only cases that get another gate. A build that stopped on a compile error, or a suite that ran and failed on
# a real assertion, is not retried: another cold build would fail the same way.
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

# ─── 2. The connection check: the brief's five outcomes ────────────────────────
# Brief: AppContainer builds a client from the current settings, connects it and runs rho.hello with client
# liftoff, and "the result says one of" five things. Plain Kotlin driven against a fake daemon that scripts
# rho.hello — no screen, no real host, every wait with a timeout.
echo ""
echo "=== the connection check's outcomes ==="

# "connected, with the daemon version and protocol version": the fake answers rho.hello with both, and the
# outcome carries each one exactly as the daemon sent it.
t helloSuccessReportsTheDaemonsVersionAndProtocolVersion

# "run rho.hello with client liftoff": the frame the fake received is a rho.hello naming the client liftoff and
# carrying the copied client's protocol version, and the request carried the stored token as a Bearer header.
t theHelloNamesTheClientLiftoffAndCarriesTheStoredToken

# Decision: "A protocol major mismatch is its own outcome, naming both versions." The fake refuses the hello with
# RpcCodes.PROTOCOL_MISMATCH (-32006); the outcome is the mismatch one, naming the version the client expects and
# the version the daemon stated in its refusal, and it is not reported as unreachable.
t aProtocolMajorMismatchIsReportedAsAMismatchAndNamesBothVersions

# "unreachable or timed out": nothing is listening on the port the test aims at (a server started, its port
# taken, then shut down). The outcome is unreachable, and it arrives inside the check's own cap instead of
# hanging. Decision: "The check answers within 10 seconds and closes the socket it opened."
t aConnectionNobodyAcceptsIsReportedAsUnreachableWithinTheChecksTimeout

# "the host or token is empty": a blank host, and separately a blank token, each give the not-configured outcome,
# and the fake records no request at all — nothing was opened. Decision: "A blank token means 'not configured',
# not 'try without a token'." Blank covers empty and whitespace-only.
t emptySettingsAreReportedAsNotConfiguredAndOpenNoConnection

# "authentication rejected": the fake answers the upgrade with HTTP 401, which is what the real daemon does for a
# wrong token on a non-loopback connection. The outcome is authentication rejected, not unreachable.
t aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable

# ─── 3. AppContainer builds it from the settings it stores ──────────────────────
# Brief: "Give AppContainer a way to build a client from the current settings (daemon host, port, token), connect
# it and run rho.hello with client liftoff." Plain Kotlin, no DI framework, and the container holds no connection
# afterwards.
echo ""
echo "=== AppContainer's connection check, built from the stored settings ==="

# Persist a host, port and token pointing at the fake daemon; the container builds its check from those stored
# settings and that check reports connected, with the versions the fake gave, having sent a rho.hello naming
# liftoff and carrying the stored token.
t theContainerBuildsTheConnectionCheckFromTheStoredSettings

c AppContainerTest

# ─── 4. The copy is a copy: header, upstream fidelity, minimal set ─────────────
# Brief: "Copy net/DaemonClient.kt and the protocol files it imports … Change only the package and import lines,
# so the copy stays comparable with upstream … Each copied file starts with a short header comment giving its
# source path and the rho commit it came from." Invariant 4.
# Each file is found by name under app/src/main/java/com/liftoff/app/rho, whatever subpackage it sits in. Its
# header must name the upstream file and a commit; that commit must exist in the rho checkout; and the file's
# body, with its package and import lines removed, must match that commit's upstream file byte for byte. So a
# header that names a commit the file did not come from fails, and so does a rewrite dressed up as a copy.
echo ""
echo "=== the copied files against upstream rho ==="

if [ "$RHO_OK" -ne 1 ]; then
    bad "the rho checkout at $RHO can be read — the brief says to stop and report it rather than write a client from scratch"
else
    echo "  rho HEAD: $(git -C "$RHO" rev-parse HEAD)"
    copied_files=(DaemonClient.kt Events.kt RpcCodes.kt)
    for name in "${copied_files[@]}"; do
        local_file="$(find app/src/main/java/com/liftoff/app/rho -type f -name "$name" 2>/dev/null | head -1 || true)"
        if [ -z "$local_file" ]; then
            bad "$name is copied under app/src/main/java/com/liftoff/app/rho"
            continue
        fi
        header="$(awk '/^package[ \t]/{exit} {print}' "$local_file" | tr -d '\r')"
        sha="$(grep -oE '\b[0-9a-f]{40}\b' <<<"$header" | head -1 || true)"
        if [ -z "$sha" ]; then
            bad "$name's header names the rho commit it came from ($local_file)"
            continue
        fi
        if ! grep -q 'rho/client' <<<"$header" || ! grep -q "$name" <<<"$header"; then
            bad "$name's header names its source path in rho ($local_file)"
            continue
        fi
        if ! grep -qE '^package[ \t]+com\.liftoff\.app\.rho' "$local_file"; then
            bad "$name is declared under com.liftoff.app.rho ($local_file)"
            continue
        fi
        upath="$(git -C "$RHO" ls-tree -r --name-only "$sha" -- "$RHO_CLIENT" 2>/dev/null | grep "/$name\$" | head -1 || true)"
        if [ -z "$upath" ]; then
            bad "$name's header names a commit that holds $name in rho's Android client ($sha)"
            continue
        fi
        if ! git -C "$RHO" cat-file -e "${sha}^{commit}" 2>/dev/null; then
            bad "$name's header names a commit that exists in rho ($sha)"
            continue
        fi
        awk '/^package[ \t]/{seen=1} seen && !/^package[ \t]/ && !/^import[ \t]/ {print}' "$local_file" \
            | tr -d '\r' > "$WORK/body-copy"
        git -C "$RHO" show "${sha}:${upath}" \
            | awk '/^package[ \t]/{seen=1} seen && !/^package[ \t]/ && !/^import[ \t]/ {print}' \
            | tr -d '\r' > "$WORK/body-up"
        if diff -u "$WORK/body-up" "$WORK/body-copy" > "$WORK/diff-$name" 2>&1; then
            ok "$name is upstream rho's $upath at $sha, with only its package and import lines changed"
            copied_sha="$sha"
        else
            bad "$name is upstream rho's $upath at $sha with only its package and import lines changed ($(wc -l < "$WORK/diff-$name") line(s) of difference in $local_file)"
        fi
    done
fi

# ─── 5. ARCHITECTURE.md: the rho row, listed as existing, with the source commit ─
# Brief Done-when: "ARCHITECTURE.md lists com.liftoff.app.rho as existing, with the source commit", and "change
# the com.liftoff.app.nabu row to com.liftoff.app.rho and update the wording to match". Decision: "The documents
# rename ARCHITECTURE.md's whole vocabulary" — what this script checks is the row the brief names, not every
# mention.
echo ""
echo "=== ARCHITECTURE.md ==="

if grep -q 'com\.liftoff\.app\.nabu' ARCHITECTURE.md; then
    bad "ARCHITECTURE.md no longer has a com.liftoff.app.nabu row (the brief renames it to com.liftoff.app.rho)"
else
    ok "ARCHITECTURE.md's package table has no com.liftoff.app.nabu row"
fi

row="$(grep -m1 -E '^\|[^|]*com\.liftoff\.app\.rho' ARCHITECTURE.md | tr -d '\r' || true)"
cell="$(awk -F'|' '{ print $4; exit }' <<<"$row" | tr -d '\r')"
if [ -z "$row" ]; then
    bad "ARCHITECTURE.md has a com.liftoff.app.rho row in its package table"
elif [ -z "$cell" ]; then
    bad "ARCHITECTURE.md's com.liftoff.app.rho row has a status cell to read"
else
    row_sha="$(grep -oE '\b[0-9a-f]{40}\b' <<<"$cell" | head -1 || true)"
    if [ -z "$row_sha" ]; then
        bad "ARCHITECTURE.md's com.liftoff.app.rho row names the source commit (its status cell reads: $cell)"
    elif [ "$RHO_OK" -eq 1 ] && ! git -C "$RHO" cat-file -e "${row_sha}^{commit}" 2>/dev/null; then
        bad "the commit ARCHITECTURE.md's com.liftoff.app.rho row names is a commit in rho ($row_sha)"
    elif ! tr -d '[:space:]' <<<"$cell" | grep -qvE '^-+$'; then
        bad "ARCHITECTURE.md's com.liftoff.app.rho row is marked as existing, not with the table's not-yet marker (its status cell reads: $cell)"
    else
        ok "ARCHITECTURE.md lists com.liftoff.app.rho as existing, with the source commit $row_sha"
    fi
fi

# ─── 6. DESIGN.md: the rename recorded as an amendment ──────────────────────────
# Brief: "Add a short amendment at the end of DESIGN.md recording the rename." DESIGN.md's own rule for that
# section is that changes after approval are listed there, newest first, rather than rewritten into the body —
# which is why this checks for a bullet and not for the word rho anywhere in §3 or §11.
echo ""
echo "=== DESIGN.md ==="

amendments="$(awk '/^## Amendments after approval/{seen=1; next} seen && /^## /{exit} seen {print}' DESIGN.md)"
# one bullet per line, continuation lines joined, so a rename has to be inside a single bullet
bullets="$(awk '
    /^## Amendments after approval/{seen=1; next}
    seen && /^## /{exit}
    seen && /^[-*][ \t]/{ if (blk != "") print blk; blk = $0; next }
    seen && blk != "" { blk = blk " " $0 }
    END { if (blk != "") print blk }
' DESIGN.md)"
bullet="$(grep -iE '\brho\b' <<<"$bullets" | grep -iE '\bnabu\b' | head -1 || true)"
if [ -z "$amendments" ]; then
    bad "DESIGN.md has an \"Amendments after approval\" section to add to"
elif [ -z "$bullet" ]; then
    bad "DESIGN.md's amendments carry a bullet naming both the harness's old name and rho (its bullets today: $(grep -c . <<<"$bullets"))"
else
    ok "DESIGN.md's amendments record the harness rename to rho"
fi

# ─── 7. The Room column the brief says not to rename ────────────────────────────
# Brief: "Don't rename the Room column nabuSessionId, because that would change the schema." Nothing here greps a
# schema or an entity for that name — that would dictate how the data layer is written. What catches a rename is the
# gate: GenerationDaoTest, DatabaseExportTest and BackupRoundTripTest all build a Generation with that property by
# name and one reads it back, so renaming it stops the unit-test source set compiling and the gate fails. The
# exported schema is the database describing itself, and the named test is the one that compares it.
echo ""
echo "=== the Room schema did not move ==="

t exportedV1SchemaMatchesDatabase

# ─── 8. Must not break ─────────────────────────────────────────────────────────
# The gate ran the whole suite, so these only guard the classes the brief's "must not break" list names against
# being dropped or left with a test skipped, plus the two invariants the brief names by test.
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

# The coach package, which stays free of Android imports (invariant 2). This test scans every .kt in
# com.liftoff.app.coach, so it covers this run without a new test; the classes named after it are the coach
# package's own behaviour, which the copied client must not disturb.
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
