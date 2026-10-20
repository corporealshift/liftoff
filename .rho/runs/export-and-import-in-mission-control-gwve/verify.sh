#!/usr/bin/env bash
# Verify: "Export and Import in Mission Control: bring in the finished UI and make it pass CI" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate — the two tasks .github/workflows/ci.yml runs, run twice back to back because the brief's
# Done-when asks for "two full local gate runs in a row" — then every named test read out of the JUnit XML
# reports the last of those runs produced, then the ARCHITECTURE.md cells the brief names, then the finished
# branch's own run records staying out.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a piece that was not brought over cannot pass on its own. That is how this run's main requirement —
# the six commits arriving — is proved: by the tests only those commits carry, not by reading commit messages.
# Names are matched across every report, so which class and package each sits in is the implementer's call;
# what it must show is written above it.
#
# The brief's Done-when names three test classes, so each is also checked as a class: it ran, and nothing in it
# failed or was skipped. The same is done for the classes the brief's "must not break" list names, because the
# gate alone cannot tell a passing suite from a suite that had a class deleted out of it.
#
# What is NOT checked here, and why:
# - The pull request's CI run itself. It is outside this repository and this script runs before the pull request
#   exists; the plan's step 9 is the work step reading that run with gh and fixing what it names. What this
#   script can do is run CI's exact commands locally, twice, and name the one test CI's log named.
# - "Don't click a system picker in a test" and "every wait in a test needs a timeout". Neither is checkable
#   without reading test source, and both show up as behaviour: a test that launches a system file picker, or a
#   wait with no timeout, never settles, the suite outlives the gate's timeout, and the gate fails.
# - The card's row wording, the refusal sentence as printed, the exact spelling of the export file name, and the
#   six commits' messages and order. The owner reviews those in the pull request.
# - "Don't change how export or import behave in the data layer, and don't change the Room schema." The data
#   layer's own classes are class-checked below and the two schema tests are named; a behaviour change in that
#   layer shows up as one of them failing.
#
# The plan's "## Decisions" are reviewed by the owner in the pull request and get no test of their own. Where a
# check below also rests on one, the comment above it names it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote, into $WORK/methods and $WORK/classes as
# "<name> <pass|fail|skipped>" lines, one line per name, at its worst outcome across every report.
index_reports() {
    local file
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function rank(s) { return (s == "fail") ? 3 : (s == "pass") ? 2 : 1 }
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
            END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "pass" : "skipped") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        function rank(s) { return (s == "fail") ? 3 : (s == "pass") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "pass" : "skipped") }
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

# ─── 1. CI gate: the two tasks ci.yml runs, twice back to back ─────────────────
# Brief Done-when: "./gradlew :app:assembleDebug :app:testDebugUnitTest passes locally" and "two full local gate
# runs in a row". This is also the "must not break" check for every test the brief lists — Mission Control's
# settings and equipment editing, the shell, the export/import data tests, the other screens — since CI runs the
# whole unit-test suite.
# Stale reports are removed before each run so every named test below comes from this invocation;
# --no-build-cache stops the reports being restored from cache with no test executed.
# The timeout is the check on "every wait in a test needs a timeout": a wait that never ends hangs the suite,
# and a hung suite is a failure, not a run that is still going.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest, twice in a row (what CI runs) ==="

# Whatever daemon is left from an earlier step is stopped before the first cold run, so a stranded test
# executor is not holding $RESULTS when the first attempt tries to clear it.
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
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# The known flake (doneBodyweightSetStepperShowsBW, closed connection pool — the brief says to re-run it before
# treating it as broken), a build killed by a daemon or memory error, and a stranded test executor holding
# $RESULTS are the only cases that get another gate. A build that stopped before it reached the tests without any
# such line in its log, or a suite that ran and failed on a real assertion, is not retried: another cold build
# would fail the same way.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then return 1; fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    return 1
}

consecutive=0
gate_ok=0
for attempt in 1 2 3 4; do
    if run_gate "$attempt"; then
        consecutive=$((consecutive + 1))
        echo "  … gate attempt $attempt passed ($consecutive in a row)"
        if [ "$consecutive" -ge 2 ]; then gate_ok=1; break; fi
    else
        index_reports
        consecutive=0
        if transient_failure; then
            echo "  … attempt $attempt failed on the known flake or on the build itself; stopping daemons and running the gate again"
            bash gradlew.sh --stop >/dev/null 2>&1 || true
        else
            echo "  … attempt $attempt failed on something real; not running the gate again"
            break
        fi
    fi
done

if [ "$gate_ok" -eq 1 ]; then
    ok "two full gate runs passed back to back (the app assembles and the whole unit-test suite ran both times)"
else
    bad "two full gate runs in a row: the app does not assemble, the unit tests do not pass, or the suite did not finish within 30 minutes"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK"/gate-*.log 2>/dev/null; then
    bad "unit tests executed in each gate run (a run had the task come from the build cache or up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/classes"
else
    index_reports
fi

# ─── 2. The backup view model: busy, result rows, the dated name, the guard ─────
# Brief: "BackupViewModel.kt: plain-Kotlin busy and result state, with work on an injected dispatcher", and the
# Done-when's BackupViewModelTest. Plain Kotlin over the view model's own state — no screen, no picker.
echo ""
echo "=== the backup view model ==="

# Export and Import each report busy while the work runs, then a row saying what happened.
t exportShowsBusyThenThatItWasSaved
t importShowsBusyThenHowMuchWasRestored

# The work runs on the injected dispatcher, not on the thread that pressed the button.
t backupWorkRunsOffTheCallingThread

# A second Export or Import while work is already running is refused and changes nothing.
t aSecondActionWhileWorkIsRunningChangesNothing

# Work that throws ends as a failure row the user can read, not an exception escaping the view model.
t workThatThrowsBecomesAFailureRowNotACrash

# Decision: the date in the export file name is the date on the device, not the date in UTC. The suggested name
# carries today's date in the clock's own zone, and no file picker has to be opened to show it.
t theDefaultExportFileNameCarriesTodaysLocalDate

# Decision: import refuses rather than overwrites. A database that already holds data is refused, and the data
# layer's own refusal sentence reaches the state as written, not reworded.
t refusedImportShowsTheDataLayersMessageUnchanged

# A picker the user backed out of hands back nothing and leaves the database and the state as they were.
t aCancelledPickerChangesNothing

c BackupViewModelTest

# ─── 3. The import reaches the flows the other tabs observe ─────────────────────
# Brief: "the database and MissionManager passed from LiftoffShell.kt", so an import lands in the database the
# rest of the app reads. Robolectric over in-memory Room, with the subscription started before the action, so a
# write that only touched the handle it was given would show up as no emission.
echo ""
echo "=== the import going through room's flows ==="

t anImportThroughTheBackupActionReachesRoomsFlows

c BackupImportRoomTest

# ─── 4. The BACKUP card on Mission Control ──────────────────────────────────────
# Brief Done-when: "Mission Control shows a working BACKUP card with Export and Import". Compose screen tests on
# the real Mission Control, clicking the card's own controls and nothing that opens a system picker.
echo ""
echo "=== the BACKUP card ==="

# The card is on the screen with both actions present and tappable while nothing is running, and the line saying
# settings and the daemon token are not in the file is shown.
t backupShowsExportAndImportEnabledAndTheExclusionLine

# A success row and a refusal row are both drawn from the view model's state, and the refusal takes the row the
# success had.
t successAndFailureRowsAreDrawnFromTheState

# Both buttons are dimmed and untappable while work is running, and back to enabled once it finishes; another
# control on the same screen stays enabled, so the check cannot pass by dimming the whole screen.
t bothBackupButtonsAreDisabledWhileWorkIsRunning

c MissionControlScreenTest

# ─── 5. The CI failure this run was written to fix ──────────────────────────────
# Decision: the CI fix is the shell test's five-second wait, not the new backup tests. The earlier branch's CI
# log named one failure out of 539 tests — this test, timing out in that file's shared wait helper. It is named
# here so the run is not done until it passes in a full local gate run, with its assertions intact.
echo ""
echo "=== the shell, which is where CI failed ==="

t systemBackFromATabGoesToLaunchpad

c LiftoffShellTest

# ─── 6. ARCHITECTURE.md ─────────────────────────────────────────────────────────
# Brief Done-when: "M1's note no longer lists export/import as missing, and M5 says export/import is built."
# Both cells today say only "the export and import data layer is built", and M5 adds "its Mission Control actions
# come next". So a cell passes when it calls export and import built without holding any part of them back, and
# the check rejects that today's wording rather than demanding one new sentence.
echo ""
echo "=== ARCHITECTURE.md ==="

names_the_feature() { # export and import named together, or backup and restore named together
    local text="$1"
    { grep -qiE 'export' <<<"$text" && grep -qiE 'import' <<<"$text"; } \
      || { grep -qiE 'backup' <<<"$text" && grep -qiE 'restor' <<<"$text"; }
}

# A clause holds part of the feature back: it names export/import with a plain "not built" phrase, or claims only
# the data layer while saying nothing about the Mission Control side that uses it.
holds_export_import_back() {
    local clause="$1"
    grep -qiE 'not started|not built|not yet built|not yet|unbuilt|missing|come next|comes next|come later|comes later|placeholder' <<<"$clause" && return 0
    if grep -qiE 'data layer|data-layer' <<<"$clause" \
       && ! grep -qiE 'mission control|action|card|screen|button|\bui\b' <<<"$clause"; then
        return 0
    fi
    return 1
}

# A clause says the feature itself is built, not just a piece of it.
calls_export_import_built() {
    local clause="$1"
    grep -qiE 'built|exists|existing|in place|shipped|complete|implemented|done|added|landed' <<<"$clause" || return 1
    holds_export_import_back "$clause" && return 1
    return 0
}

cell() { # the status cell of one table row: the 4th field of the |-split line
    local prefix="$1"
    awk -v p="$prefix" 'index($0, p) == 1 { print; exit }' ARCHITECTURE.md \
        | awk -F'|' '{ print $4 }' | tr -d '\r'
}

# Read the cell clause by clause (split on clause punctuation, including the em dash this document uses).
cell_records_export_import_built() {
    local cell="$1" clause claim=0 held_back=0
    while IFS= read -r clause; do
        names_the_feature "$clause" || continue
        if calls_export_import_built "$clause"; then claim=1; fi
        if holds_export_import_back "$clause"; then held_back=1; fi
    done < <(sed 's/[;,.—–]/\n/g' <<<"$cell")
    [ "$claim" -eq 1 ] && [ "$held_back" -eq 0 ]
}

for row in '| M1 |' '| M5 |'; do
    celltext="$(cell "$row")"
    if [ -z "$celltext" ]; then
        bad "the ${row//|/} row is in ARCHITECTURE.md's milestone table"
    elif cell_records_export_import_built "$celltext"; then
        ok "ARCHITECTURE.md ${row//|/} records export and import as built"
    else
        bad "ARCHITECTURE.md ${row//|/} records export and import as built, with nothing held back (its status cell reads: $celltext)"
    fi
done

# ─── 7. The finished branch's own run records stay out ──────────────────────────
# Brief: "Don't bring over any .rho/runs/ files from that branch, and don't cherry-pick its run: commits."
# Every one of that branch's run: commits touches its own run directory, so nothing arriving from there covers
# both clauses.
echo ""
echo "=== the finished work arrives as code, not as another run's records ==="

OTHER_RUN=".rho/runs/export-and-import-actions-in-mission-t9r9"
if [ -n "$(git log --oneline -- "$OTHER_RUN")" ]; then
    bad "no commit on this branch brings in $OTHER_RUN"
elif [ -n "$(git ls-tree -r --name-only HEAD -- "$OTHER_RUN")" ]; then
    bad "nothing under $OTHER_RUN is in the tree"
else
    ok "no file from the finished branch's run directory arrived"
fi

# ─── 8. Must not break ──────────────────────────────────────────────────────────
# The gate above runs the whole suite, so these only guard the classes the brief names against being dropped or
# left with a test skipped, and the two clauses the brief forbids changing.
echo ""
echo "=== must not break ==="

# "Don't change the Room schema."
t exportedV1SchemaMatchesDatabase
t databaseRefusesVersionMismatchInsteadOfWiping

# "settings and equipment editing in Mission Control", and the data layer's own export/import tests.
c MissionControlViewModelTest
c EquipmentViewModelTest
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest

# "the other screens": the In-Flight screen and the app's own entry point.
c InFlightScreenTest
c MainActivityTest

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
