#!/usr/bin/env bash
# Verify: "Database export and import: bring in the finished data layer" (brief.md; DESIGN.md §8
# 'Export and import', §10 'Lost or reset phone', §13). Definition of done for this run: exit 0 only when
# the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest),
# then every named test read out of the JUnit XML reports this run produced, then the ARCHITECTURE.md rows the
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
# The four commits themselves are not checked by their hashes or their messages. What matters is observable:
# the ten tests that only that finished code can pass have run here, and the other branch's run records — which
# are exactly the files under .rho/runs/database-export-and-import-in-mission-fqp3/ — have not arrived. A pick
# that was skipped, or a piece rebuilt without its tests, shows up as a test that did not run; a merge instead
# of four picks shows up as those files landing.
#
# What is NOT checked here, and why: the wording of the five refusal messages and of the restored-count report,
# the exact JSON key spellings beyond the snake_case rule, whether the export is pretty-printed, and "every
# wait in a test needs a timeout" (a wait that never ends hangs the suite, and a hung suite fails the gate).
# There is no UI in this run, so no UI test is named: the brief says it adds none, and Mission Control is
# checked only by the existing test that asserts it still offers no Export action.
#
# The plan's "## Decisions" are reviewed by the owner in the pull request and get no test of their own. Where a
# check below also rests on one, the comment above it names it: Decision 1 (ARCHITECTURE.md's M1 row is
# corrected too), Decision 2 (nothing is pre-wired into AppContainer, so no screen calls export or import yet).
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
# "<name> <pass|fail|skipped>" lines, one line per name. Reading the reports once keeps this cheap even with
# many names below. Every report is read, so a test can sit in any package and any class. A name that ran
# several times (the same method in two classes) is recorded once, at its worst outcome: a failure anywhere is
# a failure.
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
    # One line per name, at its worst outcome across every report that named it.
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

# c <ClassName>: the brief's Done-when names a test class. It has to have run, and every test in it has to
# have passed — a class whose other tests broke is not a class that passes.
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
# break" check for everything the brief lists — every existing test, Mission Control, the other screens, the
# coach package, the build gate — since CI runs the whole unit-test suite, and it is what catches a per-class
# run that never reached the guards living outside the picked classes.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed, which
# would read as a suite that passed.
# The brief warns a full gate can outlast a shell command's limit on this machine; the timeout is how this
# script waits for it. A suite that never finishes is a failure, not a run that is still going.
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

# The known flake: one In-Flight screen test fails at random with a closed Room connection pool and passes on
# a re-run of the same tree (brief: "re-run it before treating it as broken"). A daemon that was killed or a
# build that ran out of memory is likewise not a test failure and says nothing about the brief. Those are the
# only cases that get a second gate; a suite that ran and failed on a real assertion is not retried.
transient_failure() {
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then return 0; fi
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    # Only the known flake failed: re-run. Something else failed, or nothing failed at all (a build that died
    # before the tests ran): the reports already say what they say.
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
# version. Keys are snake_case. Settings and the daemon token are never included." Proven over an in-memory
# Room database and a byte stream — the cheapest test that shows it, and the shape the picked tests use.
echo ""
echo "=== export ==="

# c3e1dbe data: export the whole database to a JSON stream. Seed one row in each of the nine tables, export,
# read the bytes back: every table appears with its row, each row with the id it has in the database, and the
# header carries both a format version and the Room schema version the file came from.
t exportWritesEveryRowOfEveryTableWithIdsAndBothVersions

# The file's shape: every key snake_case (a camelCase column such as weekStart reads as week_start), and no
# settings or daemon-token key anywhere in it, under any name, at any depth.
t exportFileUsesSnakeCaseKeysAndCarriesNoSettingsOrToken

# ─── 3. Import ─────────────────────────────────────────────────────────────────
# Brief: "Import works only into an empty database. A database holding only one DRAFT Mission with no sorties
# counts as empty, and that draft is replaced. A non-empty database, a malformed file, a file that isn't a
# Liftoff export, and an unknown or newer version are each refused with a clear message, and nothing changes.
# Import runs in one transaction, keeps ids, then runs MissionManager.onAppOpen() and reports how many
# Missions and sorties it restored."
echo ""
echo "=== import ==="

# 2ff4bd9 / f0d1720. A database that already has training data is refused, and every table reads exactly the
# same afterwards — no row added, changed or removed.
t importRefusesANonEmptyDatabaseAndChangesNothing

# A database whose only content is the DRAFT Mission onAppOpen() created for the current week imports cleanly,
# and that draft is gone afterwards: no second Mission for the current week, no duplicate id.
t importAcceptsADatabaseHoldingOnlyAFreshDraftAndReplacesIt

# A truncated or syntactically broken JSON document, and a valid JSON document that is simply something else,
# are each refused with a message and leave the database exactly as it was.
t importRefusesAFileThatIsNotALiftoffExportAndChangesNothing

# A format_version this app does not know, and a file whose Room schema version is newer than the app's own,
# are each refused with a message and leave the database exactly as it was.
t importRefusesAVersionItCannotReadAndChangesNothing

# All-or-nothing: a file that parses and passes every version check but points at a row the file does not
# contain is refused, and nothing is half-written — every table reads exactly as it did before the attempt.
# That is what "import runs in one transaction" has to mean.
t importWritesNothingWhenAFileHasADanglingReference

# After a successful import the app runs onAppOpen(), so the current week has a Mission and the file's unclosed
# past Mission is rolled over rather than left open or duplicated.
t importRunsOnAppOpenSoTheCurrentWeekIsSettled

# A successful import reports how much was restored: the number of Missions and the number of sorties.
t importReportsHowMuchWasRestored

# ─── 4. The round trip ─────────────────────────────────────────────────────────
# Brief: "Export then import gives an identical database" — the headline of DESIGN.md §13 and the reason the
# export keeps ids.
echo ""
echo "=== round trip ==="

# c89dd1d data: prove export then import gives an identical database. Export a seeded database and import it
# into a second, fresh one; every row of every table compares equal, ids included.
t exportThenImportIntoAFreshDatabaseGivesAnIdenticalDatabase

# ─── 5. The draft delete the import needs, and the guard that bounds it ────────
# Brief: "one draft-Mission delete on MissionDao, with the matching edit to MissionDaoTest". The picked edit
# keeps the guard test's name and changes its MissionDao half to allow exactly that one delete; its SortieDao
# half must keep passing, so no other delete arrives with the picks.
echo ""
echo "=== the delete import is allowed to make ==="

t noWipeForMissionsAndOnlyTheByIdDeleteForSorties
c MissionDaoTest

# The brief's Done-when names these four classes: they have to have run, with nothing in them failing.
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest

# ─── 6. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief Done-when: the com.liftoff.app.data row's 'Exists yet?' cell names export and import, and M5's status
# no longer says export/import is not started. Only the old state is rejected; the wording inside the cells is
# the implementer's.
echo ""
echo "=== ARCHITECTURE.md ==="

names_the_feature() { # export and import named together, or backup and restore named together
    local text="$1"
    { grep -qiE 'export' <<<"$text" && grep -qiE 'import' <<<"$text"; } \
      || { grep -qiE 'backup' <<<"$text" && grep -qiE 'restor' <<<"$text"; }
}

# A cell is read clause by clause — split on clause punctuation, including the em dash this document uses — so
# a cell that pairs "test connection comes later" with "export and import built" does not read as a failure.
# A clause counts as saying export/import is not built when it names it with a flat negation ("not started",
# "missing", "remain"), or with a deferral ("come later", "to come") that the same clause does not also call
# built. A deferral in some other clause ("its Mission Control actions come next") is not held against a clause
# that names export/import as built. So "the export/import data layer is built; its Mission Control actions come
# next" reads as built, and "test connection and export/import come later" does not.
names_export_missing() {
    local clause
    while IFS= read -r clause; do
        names_the_feature "$clause" || continue
        if grep -qiE 'not started|not built|not yet|not done|not in place|not available|missing|unbuilt|no longer|still needs|still to|todo|to do|remain|remains' <<<"$clause"; then
            return 0
        fi
        if grep -qiE 'come later|comes later|later|to come' <<<"$clause" \
           && ! grep -qiE 'built|exists|existing|in place|shipped|complete|implemented|done|works' <<<"$clause"; then
            return 0
        fi
    done < <(sed 's/[;,.—–-]/\n/g' <<<"$1")
    return 1
}

# Some clause that names export and import has to pair them with a word that means built.
names_export_built() {
    local clause
    while IFS= read -r clause; do
        if names_the_feature "$clause" \
           && grep -qiE 'built|exists|existing|in place|shipped|complete|implemented|done|works' <<<"$clause"; then
            return 0
        fi
    done < <(sed 's/[;,.—–-]/\n/g' <<<"$1")
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

# M5's status cell, which today ends "export/import not started". It has to say the data layer is built; the
# Mission Control actions being what comes next is the brief's own wording and is not held against it.
m5status="$(cell '| M5 |')"
if [ -z "$m5status" ]; then
    bad "the M5 row is in ARCHITECTURE.md's milestone table"
elif names_export_built "$m5status" && ! names_export_missing "$m5status"; then
    ok "ARCHITECTURE.md's M5 status no longer says export/import is not started"
else
    bad "ARCHITECTURE.md's M5 status records the export/import data layer as built (its status cell reads: $m5status)"
fi

# Decision 1 (ARCHITECTURE.md's M1 row is corrected too, not only the two cells the brief names): M1's status
# cell makes the same claim a few rows away — today "Done — test connection and export/import come later" — and
# would then contradict the two cells this run just changed. "test connection" may still be listed as later;
# this run does not build it.
m1status="$(cell '| M1 |')"
if [ -z "$m1status" ]; then
    bad "the M1 row is in ARCHITECTURE.md's milestone table"
elif names_export_missing "$m1status"; then
    bad "ARCHITECTURE.md's M1 status no longer lists export/import as missing (its status cell reads: $m1status)"
else
    ok "ARCHITECTURE.md's M1 status no longer lists export/import as missing"
fi

# ─── 7. The other branch's run records stay out ────────────────────────────────
# Brief: "Don't bring over any .rho/runs/ files from that branch, and don't cherry-pick its run: commits."
# Those commits touch only that run's directory, so the observable consequence is checked there: no commit on
# this branch touches it, and nothing in the tree came from it.
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

# Decision 2 (nothing is pre-wired: no AppContainer accessor, no UI). The existing Mission Control test asserts
# the screen offers no Export action today; this run adds no UI, so it has to keep passing. Naming it is what
# catches an Export button added "while here".
echo ""
echo "=== must not break ==="

t showsNoUnbuiltPlaceholders

# "Don't change the Room schema": the exported Room schema JSON still matches the database, and the app still
# refuses a version mismatch instead of wiping — the backup story is the reason destructive migration stays off.
t exportedV1SchemaMatchesDatabase
t databaseRefusesVersionMismatchInsteadOfWiping

# The DAOs the picks add bulk read / bulk insert / count methods to keep their deletes and their stored order.
t hasNoDeleteOperation
t writePlanThenGetPlanKeepsStoredOrder
t checkSetCopiesPlannedValuesIntoActuals

# "Import … then runs MissionManager.onAppOpen()" — the import calls it; its own behaviour is unchanged.
t onAppOpenCreatesDraftForCurrentWeek
t onAppOpenDoesNotDuplicateDraft
t onAppOpenRollsOverPastMission

# "Mission Control" and the shell that reaches it, untouched by this run.
t opensShowingStoredValues
t tappingUnitSegmentsSavesUnits
t missionControlShowsSettingsEyebrowAndTitle
t missionControlSurvivesRecreation

# CLAUDE.md §2 vocabulary, which the export/import naming has to obey, and the coach package this run must not
# disturb.
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
