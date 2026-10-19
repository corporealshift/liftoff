#!/usr/bin/env bash
# Verify: "Database export and import: bring in the finished data layer" (brief.md; DESIGN.md §8
# 'Export and import', §10 'Lost or reset phone', §13). Definition of done for this run: exit 0 only when
# the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest),
# then every named test read out of the JUnit XML reports this run produced, then the ARCHITECTURE.md cells the
# brief names, then the brief's "don't bring over any .rho/runs/ files from that branch".
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not brought over cannot pass on its own. Names are matched across every report, so
# which file each one sits in is the implementer's call; what it must show is written above it.
#
# This run writes no test of its own: every name below already exists on the source branch (the ten export /
# import / round-trip tests, brought by the four picks) or already exists on this branch (the guard and the
# "must not break" names). The brief's Done-when names four test classes, so each is also checked as a class:
# it ran, and nothing in it failed or was skipped.
#
# What is NOT checked here, and why: the wording of the refusal messages and of the restored-count report,
# the exact JSON key spellings beyond the snake_case rule, whether the export is pretty-printed, and "every
# wait in a test needs a timeout" (a wait that never ends hangs the suite, and a hung suite fails the gate).
# There is no UI in this run, so no UI test is named.
#
# The plan's "## Decisions" are reviewed by the owner in the pull request and get no test of their own
# (so ARCHITECTURE.md's M1 row, Decision 1, is not checked here). Where a check below also rests on one, the
# comment above it names it.
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

# ─── 1. CI gate: the two tasks .github/workflows/ci.yml runs ──────────────────
# Brief Done-when: "./gradlew :app:assembleDebug :app:testDebugUnitTest passes". This is also the "must not
# break" check for every existing test, Mission Control, the other screens and the coach package.
# Stale reports are removed first so every named test below comes from this run; --no-build-cache stops the
# reports being restored from cache with no test executed. A suite that never finishes is a failure.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

run_gate() {
    rm -rf "$RESULTS" 2>/dev/null || true
    if [ -d "$RESULTS" ]; then
        echo "  … could not clear $RESULTS — a stranded Gradle test executor is holding it; running: bash gradlew.sh --stop"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
        rm -rf "$RESULTS" 2>/dev/null || true
        [ -d "$RESULTS" ] && return 2
    fi
    mkdir -p app/build
    timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$1.log"
}

# The known flake (doneBodyweightSetStepperShowsBW, closed connection pool) and a killed daemon or OOM build
# are the only cases that get a second gate; a real assertion failure is not retried.
transient_failure() {
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then return 0; fi
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
if run_gate 1; then gate_ok=1; fi
if [ "$gate_ok" -eq 0 ]; then
    index_reports
    if transient_failure; then
        echo "  … the gate failed on the known flake or on the build itself; stopping daemons and running it once more"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
        if run_gate 2; then gate_ok=1; fi
    fi
fi

if [ "$gate_ok" -eq 1 ]; then
    ok "gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "gate failed: the app does not assemble, the unit tests do not pass, or the suite did not finish in 40 minutes"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK"/gate-*.log 2>/dev/null; then
    bad "unit tests executed (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/classes"
else
    index_reports
fi

# ─── 2. Export ─────────────────────────────────────────────────────────────────
# Brief: "Export writes every row of every table, ids included, with a format version and the Room schema
# version. Keys are snake_case. Settings and the daemon token are never included."
echo ""
echo "=== export ==="

# Every table appears with its rows and their ids; the header carries the format and Room schema versions.
t exportWritesEveryRowOfEveryTableWithIdsAndBothVersions

# Every key is snake_case, and no settings or daemon-token key appears anywhere in the file.
t exportFileUsesSnakeCaseKeysAndCarriesNoSettingsOrToken

# ─── 3. Import ─────────────────────────────────────────────────────────────────
# Brief: import only into an empty database (a lone DRAFT Mission with no sorties counts as empty and is
# replaced); a non-empty database, a malformed file, a non-Liftoff file and an unknown or newer version are
# each refused with nothing changed; one transaction, ids kept, onAppOpen() run, counts reported.
echo ""
echo "=== import ==="

t importRefusesANonEmptyDatabaseAndChangesNothing
t importAcceptsADatabaseHoldingOnlyAFreshDraftAndReplacesIt
# Malformed JSON and valid JSON that is not a Liftoff export.
t importRefusesAFileThatIsNotALiftoffExportAndChangesNothing
# An unknown format version and a newer Room schema version.
t importRefusesAVersionItCannotReadAndChangesNothing
# "Import runs in one transaction": a file that fails part-way leaves every table as it was.
t importWritesNothingWhenAFileHasADanglingReference
t importRunsOnAppOpenSoTheCurrentWeekIsSettled
t importReportsHowMuchWasRestored

# ─── 4. The round trip ─────────────────────────────────────────────────────────
echo ""
echo "=== round trip ==="

# Export a seeded database, import into a fresh one: every row of every table equal, ids included.
t exportThenImportIntoAFreshDatabaseGivesAnIdenticalDatabase

# ─── 5. The draft delete and the guard that bounds it; the four Done-when classes ─
# Brief: "one draft-Mission delete on MissionDao, with the matching edit to MissionDaoTest".
echo ""
echo "=== the delete import is allowed to make ==="

t noWipeForMissionsAndOnlyTheByIdDeleteForSorties
c MissionDaoTest
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest

# ─── 6. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief: the com.liftoff.app.data row's 'Exists yet?' cell names export and import, and M5's status no longer
# says export/import is not started. Only the old "not built" state is rejected; the wording is the implementer's.
echo ""
echo "=== ARCHITECTURE.md ==="

names_the_feature() { # export and import named together, or backup and restore named together
    local text="$1"
    { grep -qiE 'export' <<<"$text" && grep -qiE 'import' <<<"$text"; } \
      || { grep -qiE 'backup' <<<"$text" && grep -qiE 'restor' <<<"$text"; }
}

# Read clause by clause (split on clause punctuation, including the em dash this document uses). A clause says
# export/import is not built when it names the feature with a plain "not built" phrase and does not also call
# it built, so "the export/import data layer is built; its Mission Control actions come next" passes.
names_export_missing() {
    local clause
    while IFS= read -r clause; do
        names_the_feature "$clause" || continue
        if grep -qiE 'not started|not built|not yet built|unbuilt|missing|come later|comes later' <<<"$clause" \
           && ! grep -qiE 'built|exists|existing|in place|shipped|complete|implemented|done|added|landed' <<<"$clause"; then
            return 0
        fi
    done < <(sed 's/[;,.—–]/\n/g' <<<"$1")
    return 1
}

cell() { # the status / 'Exists yet?' cell of one table row: the 4th field of the |-split line
    local prefix="$1"
    awk -v p="$prefix" 'index($0, p) == 1 { print; exit }' ARCHITECTURE.md \
        | awk -F'|' '{ print $4 }' | tr -d '\r'
}

dataexists="$(cell '| `com.liftoff.app.data` |')"
if [ -z "$dataexists" ]; then
    bad "the com.liftoff.app.data row is in ARCHITECTURE.md's area map"
elif names_the_feature "$dataexists" && ! names_export_missing "$dataexists"; then
    ok "ARCHITECTURE.md's data row records export and import as existing"
else
    bad "ARCHITECTURE.md's data row records export and import as existing (its 'Exists yet?' cell reads: $dataexists)"
fi

m5status="$(cell '| M5 |')"
if [ -z "$m5status" ]; then
    bad "the M5 row is in ARCHITECTURE.md's milestone table"
elif names_the_feature "$m5status" && ! names_export_missing "$m5status"; then
    ok "ARCHITECTURE.md's M5 status no longer says export/import is not started"
else
    bad "ARCHITECTURE.md's M5 status records the export/import data layer as built (its status cell reads: $m5status)"
fi

# ─── 7. The other branch's run records stay out ────────────────────────────────
# Brief: "Don't bring over any .rho/runs/ files from that branch, and don't cherry-pick its run: commits."
echo ""
echo "=== the finished work arrives as code, not as another run's records ==="

OTHER_RUN=".rho/runs/database-export-and-import-in-mission-fqp3"
if [ -n "$(git log --oneline -- "$OTHER_RUN")" ]; then
    bad "no commit on this branch brings in $OTHER_RUN"
elif [ -n "$(git ls-tree -r --name-only HEAD -- "$OTHER_RUN")" ]; then
    bad "nothing under $OTHER_RUN is in the tree"
else
    ok "no file from the finished branch's run directory arrived"
fi

# ─── 8. Must not break ─────────────────────────────────────────────────────────
# Decision 2 (nothing is pre-wired, no UI): Mission Control's existing test that it offers no Export action
# keeps passing.
echo ""
echo "=== must not break ==="

t showsNoUnbuiltPlaceholders

# "Don't change the Room schema."
t exportedV1SchemaMatchesDatabase
t databaseRefusesVersionMismatchInsteadOfWiping

# The DAOs the picks extend keep their deletes and stored order.
t hasNoDeleteOperation
t writePlanThenGetPlanKeepsStoredOrder
t checkSetCopiesPlannedValuesIntoActuals

# MissionManager.onAppOpen(), which import calls, is unchanged.
t onAppOpenCreatesDraftForCurrentWeek
t onAppOpenDoesNotDuplicateDraft
t onAppOpenRollsOverPastMission

# Mission Control and the shell.
t opensShowingStoredValues
t tappingUnitSegmentsSavesUnits
t missionControlShowsSettingsEyebrowAndTitle
t missionControlSurvivesRecreation

# §2 vocabulary and the coach package.
t neverSaysSession
t outlinePromptMatchesGoldenFile

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
