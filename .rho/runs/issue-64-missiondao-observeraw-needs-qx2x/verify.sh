#!/usr/bin/env bash
# Verify: "MissionDao.observeRaw needs @Transaction" (brief.md; issue corporealshift/liftoff#64).
# Definition of done for this run: exit 0 only when the brief is met.
#
# What is checked, in one line: the CI gate (the two tasks .github/workflows/ci.yml runs) is green;
# one new MissionDaoTest case shows that a single observeWeek emission runs BOTH of Room's reads —
# the mission SELECT and the batched @Relation sortie SELECT that follows it — while the database is
# inside a transaction; the five existing MissionDaoTest cases still run and pass, so the annotation
# changed nothing about what the Flow emits; the cases that consume that Flow still run and pass;
# and Room's KSP processor prints no warning for any DAO query whose return value carries a
# @Relation, which is the brief's "the one warning the gate prints" and, at the same time, its
# "any other @Relation query in the DAOs" sweep.
#
# Order: the gate first, because everything below reads its reports; then the named cases; then the
# warning check, which is a separate forced run of :app:kspDebugKotlin because that task is
# UP-TO-DATE in a warm gate run and prints nothing there.
#
# It fails on the untouched tree, and for the right reason: the new case does not exist yet, and
# :app:kspDebugKotlin prints the MissionDao.kt:50 "@Relation … annotate this method with
# @Transaction" warning today. Both directions were measured: on this untouched tree it exits 1 with
# exactly those two failures (775 test records, 0 failures, 11 checks ok), and on a scratch copy of
# this tree with `@Transaction` on observeRaw and one case of the shape named below it exits 0 with
# 13 checks ok.
#
# What is NOT checked here, and why:
# - The race itself. No case interleaves a land/advance write with a collection. Plan Decision "the
#   behaviour test asserts the transaction bracket, not the race itself": the bracket is what makes
#   the stale combination impossible, and it is deterministic; the interleaving depends on timing and
#   would be a flaky gate case.
# - How the new case observes the transaction. The case is checked by name and by what it must show,
#   not by which hook it used. Plan Risks give a ladder (Room's query callback with db.inTransaction()
#   read inside it; then a RoomDatabase.Callback commit count; last, the generated MissionDao_Impl) and
#   the first rung was measured to work on this tree, so the ladder is there in case it stops working,
#   not as a choice. What the ladder rules out: runtime reflection on MissionDao cannot see
#   @Transaction (Room's annotations have Java CLASS retention), so a reflection test on the annotation
#   cannot satisfy the named case.
# - A Compose test. The brief names LaunchpadScreen and MissionScreen as the consumers of the Flow but
#   asks for a test of the behaviour change, and the plan's Testing section covers the consumers by
#   the existing suites running untouched against real in-memory DAOs. The cheapest test that shows
#   this behaviour is the DAO-level Room case, so no UI test is named; the consumer classes below are
#   run unchanged.
# - A build flag that turns warnings into errors, or a test that parses build output. Plan Decision
#   "the 'no Room warnings' check is a log read, not a test": either would make every future,
#   unrelated KSP warning fail the build, which goes beyond this issue. The warning check below reads
#   the log of one forced KSP run and of the gate run.
# - Room warnings that a warm gate run never prints. :app:kspDebugKotlin is UP-TO-DATE in a warm gate
#   and prints no `w:` line there whether or not the annotation is present, so the forced rerun below
#   is the check that carries the brief; the gate-log check only catches a relation warning the gate
#   itself printed, which is what CI (a cold tree) would show.
# - Which DAO carries the annotation. The brief says "observeRaw (and any other @Relation query in the
#   DAOs)". The plan's sweep found MissionWithSorties.sorties (Relations.kt:8) as the only @Relation
#   in app/src/main, so observeRaw is the only method to annotate; the warning check is what proves
#   that sweep, because an un-annotated @Relation query anywhere in the DAOs prints that warning.
# - gradlew.sh. Plan Decision: the gate runs the same two tasks through the local toolchain, because
#   the committed gradlew.sh hardcodes C:/Users/corpo/... paths. Fixing that script is outside this
#   issue, so it is left alone and is not checked.
# - That the five existing MissionDaoTest cases are byte-for-byte untouched. Only that they still run
#   and pass is checked; the diff is reviewed in the pull request.
# - Which other tests in the suite break. The gate runs every test in the repo, so a break anywhere
#   else already fails the gate. The class names below only catch one of them renamed away, skipped
#   or deleted.
# - ARCHITECTURE.md, DESIGN.md, CLAUDE.md wording, line endings, commit style, and the pull-request
#   description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch lives under app/build, which is gitignored. The gate logs stay there for the work steps.
WORK="app/build/verify64"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1

# ---------------------------------------------------------------------------
# The Gradle command, chosen the way CI chooses it.
# ci.yml runs `./gradlew :app:assembleDebug :app:testDebugUnitTest` on Ubuntu with Java 17.
# gradlew.sh is the owner's own shortcut and is Windows-only, so it is used only where it works.
# Elsewhere the pinned toolchain is used — the same Gradle the wrapper asks for — with JAVA_HOME and
# ANDROID_HOME pointed at it, and only where those directories exist. LIFTOFF_TOOLCHAIN overrides
# the toolchain root.
# ---------------------------------------------------------------------------
RUNNER=()
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        RUNNER=(bash gradlew.sh)
        ;;
    *)
        TOOLCHAIN="${LIFTOFF_TOOLCHAIN:-$HOME/android-toolchain}"
        if [ -z "${JAVA_HOME:-}" ] && [ -d "$TOOLCHAIN/jdk" ]; then
            export JAVA_HOME="$TOOLCHAIN/jdk"
        fi
        if [ -z "${ANDROID_HOME:-}" ] && [ -d "$TOOLCHAIN/sdk" ]; then
            export ANDROID_HOME="$TOOLCHAIN/sdk"
            export ANDROID_SDK_ROOT="$TOOLCHAIN/sdk"
        fi
        if [ -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
            RUNNER=("$TOOLCHAIN/gradle/bin/gradle")
        elif [ -x ./gradlew ]; then
            RUNNER=(./gradlew)
        else
            RUNNER=(bash gradlew.sh)
        fi
        ;;
esac

log() { printf '%s\n' "$*"; }

# ---------------------------------------------------------------------------
# Gradle's JUnit XML, indexed three ways: by method name, by class.method, and by class. Each name
# keeps the worst outcome it produced anywhere in the suite.
# ---------------------------------------------------------------------------
index_reports() {
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function flush() {
                if (intest) {
                    name = substr(name, 1, 100)
                    gsub(/`/, "", name)
                    sub(/\(.*/, "", name)
                    if (name != "") {
                        print "M\t" name "\t" status
                        print "Q\t" class "." name "\t" status
                        print "C\t" class "\t" status
                    }
                }
            }
            function rank(s) {
                if (s == "fail") return 3
                if (s == "skip") return 2
                return 1
            }
            function worse(a, b) { if (rank(a) > rank(b)) return a; return b }
            { gsub(/\r/, "") }
            /<testsuite / {
                match($0, /name="[^"]*"/)
                class = substr($0, RSTART + 6, RLENGTH - 7)
                next
            }
            /<testcase/ {
                flush()
                intest = 1
                status = "pass"
                match($0, /name="[^"]*"/)
                name = substr($0, RSTART + 6, RLENGTH - 7)
                if ($0 ~ /<skipped[ \/>]/) status = worse(status, "skip")
                if ($0 ~ /<failure|<error/) status = worse(status, "fail")
                if ($0 ~ /<\/testcase>/ || $0 ~ /\/>[ \t]*$/) { flush(); intest = 0 }
                next
            }
            /<skipped[ \/>]/ { if (intest) status = worse(status, "skip"); next }
            /<failure|<error/ { if (intest) status = worse(status, "fail"); next }
            /<\/testcase>/ { flush(); intest = 0; next }
            END { flush() }
        ' "$file" >> "$WORK/raw"
    done

    : > "$WORK/all"
    : > "$WORK/methods"
    : > "$WORK/qualified"
    : > "$WORK/classes"
    if [ -s "$WORK/raw" ]; then
        awk -F '\t' '
            function rank(s) { if (s == "fail") return 3; if (s == "skip") return 2; return 1 }
            { key = $1 SUBSEP $2
              if (!(key in seen) || rank($3) > rank(seen[key])) seen[key] = $3 }
            END { for (k in seen) { split(k, p, SUBSEP); print p[1] "\t" p[2] "\t" seen[k] } }
        ' "$WORK/raw" > "$WORK/all"
        awk -F '\t' '$1 == "M" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/methods"
        awk -F '\t' '$1 == "Q" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/qualified"
        awk -F '\t' '$1 == "C" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/classes"
    fi
}

# ---------------------------------------------------------------------------
# 1. The CI gate: the two tasks ci.yml runs.
# Brief: "run the gate (bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest) before opening the
# PR" — the same two tasks CI runs. The reports are moved aside first, so every named test below
# comes from this run; --no-build-cache stops them being restored from the cache.
# ---------------------------------------------------------------------------
clear_results() {
    if [ -d "$RESULTS" ]; then
        # Moved, not deleted: a stranded Gradle test executor can hold files under test-results. No
        # gradlew --stop here: a stop is machine-wide and would take down another worktree's daemon.
        local aside="app/build/verify-old-results-$1"
        rm -rf "$aside" 2>/dev/null || true
        mv "$RESULTS" "$aside" 2>/dev/null || true
    fi
}

# A run that failed only because Gradle itself broke, or because of the classes this machine has
# recorded failing at random. A failure in any other class is a real failure and stops the retry, so
# a case this run is supposed to create can never be retried into green.
transient_failure() {
    local attempt="$1"
    local gate_log="$WORK/gate-$attempt.log"

    # A compile error is a real failure, and it is checked before the infra phrases below: Gradle's
    # own diagnostics for a test task that could not run mention the test executor too.
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$gate_log" 2>/dev/null; then
            return 1
        fi
        log "  … Gradle wrote no test reports at all; running the gate again"
        return 0
    fi

    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|Test Executor|PersistentEnumerator storage corrupted|Unable to delete directory' "$gate_log" 2>/dev/null; then
        log "  … the build itself broke; running the gate again"
        return 0
    fi

    local any other
    any="$(awk -F '\t' '$2 == "fail" { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$any" -eq 0 ]; then
        log "  … the build failed without failing a test; running the gate again"
        return 0
    fi
    other="$(awk -F '\t' '$2 == "fail" && !($1 ~ /(^|\.)LiftoffShellTest\./) && !($1 ~ /(^|\.)InFlightScreenTest\./) && !($1 ~ /(^|\.)LaunchpadPendingTitleTest\./) { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$other" -eq 0 ]; then
        log "  … only the recorded flaky classes failed; running the gate again"
        return 0
    fi
    return 1
}

log "=== gate: ${RUNNER[*]} :app:assembleDebug :app:testDebugUnitTest ==="

gate_status="red"
last_log="$WORK/gate-1.log"
for attempt in 1 2 3; do
    clear_results "$attempt"
    log "--- attempt $attempt ---"
    rc=0
    last_log="$WORK/gate-$attempt.log"
    timeout 1800 "${RUNNER[@]}" :app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain > "$last_log" 2>&1 || rc=$?
    index_reports
    if [ "$rc" -ne 0 ] && transient_failure "$attempt"; then
        continue
    fi
    if [ "$rc" -eq 0 ]; then
        # The reports were moved aside, so the test task cannot legitimately be UP-TO-DATE.
        if grep -E '^> Task :app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$last_log" 2>/dev/null; then
            log "  … the test task was served from cache or skipped; that is not a run of the current tree"
            gate_status="red"
            break
        fi
        gate_status="green"
    else
        gate_status="red"
    fi
    break
done

passed=0
failed=0
ok()  { passed=$((passed + 1)); log "  ok   $*"; }
bad() { failed=$((failed + 1)); log "  FAIL $*"; }

if [ "$gate_status" = "green" ]; then
    ok "gate: :app:assembleDebug :app:testDebugUnitTest (the app assembles and the whole unit-test suite ran)"
else
    bad "gate: :app:assembleDebug :app:testDebugUnitTest"
    log "--- last gate output (tail) ---"
    tail -120 "$last_log" 2>/dev/null || true
    for f in "$WORK"/gate-*.log; do
        if [ "$f" = "$last_log" ]; then continue; fi
        log "--- $f (tail) ---"
        tail -40 "$f" 2>/dev/null || true
    done
fi

if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/qualified"; : > "$WORK/classes"
else
    index_reports
    log "  … $(wc -l < "$WORK/qualified") test records, $(wc -l < "$WORK/classes") classes indexed"
fi

# t <class> <method> — the case must exist, must have run in this gate, and must have passed.
t() {
    local cls="$1" method="$2" want status
    want="$(printf '%s' "$method" | tr -d '`')"
    status="$(awk -F '\t' -v k="$cls.$want" '{ p = index($1, "." k); if ($1 == k || (p > 0 && p + length(k) == length($1))) { print $2; exit } }' "$WORK/qualified")"
    case "$status" in
        pass) ok "$cls.$want" ;;
        fail) bad "$cls.$want — ran and FAILED" ;;
        skip) bad "$cls.$want — ran but was SKIPPED" ;;
        *)
            if awk -F '\t' -v k="$cls.$want" '{ p = index($1, "." k); if ($1 == k || (p > 0 && p + length(k) == length($1))) { found = 1 } } END { exit !found }' "$WORK/qualified"; then
                bad "$cls.$want — ran with an unreadable result"
            else
                bad "$cls.$want — did not run at all"
            fi
            ;;
    esac
}

# c <class> — every test Gradle recorded for this class must have run and passed.
c() {
    local cls="$1" status
    status="$(awk -F '\t' -v c="$cls" '{ p = index($1, "." c); if ($1 == c || (p > 0 && p + length(c) == length($1))) { print $2; exit } }' "$WORK/classes")"
    case "$status" in
        pass) ok "class $cls — every test in it passed" ;;
        fail) bad "class $cls — a test in it FAILED" ;;
        skip) bad "class $cls — a test in it was SKIPPED" ;;
        *) bad "class $cls — ran no tests at all" ;;
    esac
}

# ===========================================================================
# 2. The behaviour change: one emission, one transaction.
# Brief: "Room runs the parent and relation SELECTs separately, so a land/advance transaction
# between them can make observeWeek emit a sortie still PLANNED next to a plan row that was already
# replaced." Fix: "@Transaction" on observeRaw. Plan Decision "the behaviour test asserts the
# transaction bracket, not the race itself".
# ===========================================================================
log ""
log "=== one emission reads mission and sortie inside a single transaction ==="

# Brief test bullet "Add a test for the behaviour change". The case is named here; write it in
# MissionDaoTest (plan Files: no new files). It must show: one collected observeWeek emission runs
# BOTH of Room's reads for that emission — the parent SELECT (SELECT * FROM mission WHERE weekStart
# = ?) and the batched @Relation SELECT (SELECT … FROM `sortie` WHERE `missionId` IN (…)) that the
# generated callable runs after it — and that both of them happened while the database was inside a
# transaction. Without @Transaction on observeRaw Room wraps neither read, so the case fails.
#
# What the case has to look like. The first bullet below was measured on a scratch copy of this tree
# (a case of exactly this shape passed with the annotation and failed without it, reporting the
# mission read outside a transaction), so the plan's fallback ladder is not needed:
# - Build its own in-memory LiftoffDatabase with
#   RoomDatabase.Builder.setQueryCallback(callback, java.util.concurrent.Executor { it.run() }) — a
#   same-thread executor — and leave the shared createDb the other five cases use alone. In the
#   callback (override fun onQuery(sqlQuery: String, bindArgs: List<Any?>)) record the SQL it is
#   given and db.inTransaction() at that moment — read from inside the callback, because the
#   transaction is open only while Room's callable runs, on Room's executor, and only the callback is
#   known to be inside it. RoomDatabase.inTransaction() is
#   getOpenHelper().getWritableDatabase().inTransaction(), and with a query callback installed that
#   helper is Room's QueryInterceptorOpenHelper, so it reports the transaction Room just began.
# - Seed a mission with at least one sortie. Room skips the relation SELECT when the parent query
#   returns no rows, so a week with no mission shows one read, not two.
# - Collect one emission with first(), the way the existing cases take the Flow, and start recording
#   after the seeds. Only the two SELECTs of this collection count: the database's own open may reach
#   the callback before any of this, and counting everything the callback saw would pass with or
#   without the annotation. Assert the parent read was recorded, the relation read was recorded, the
#   parent read came first, and both were inside a transaction.
# - No threads and no concurrency: app/build.gradle.kts fails a unit test at 5 minutes precisely
#   because a Room + Robolectric test can hang.
# - If the transaction state ever turns out not to be observable through the query callback, plan
#   Risks gives the fallback in this order: a RoomDatabase.Callback that counts commits, asserting
#   one commit brackets the two SELECTs; and only as a last resort, the generated MissionDao_Impl
#   bracketing the flow callable with beginTransaction / setTransactionSuccessful / endTransaction.
#   Runtime reflection on MissionDao is not one of the options: Room's annotations have Java CLASS
#   retention, so @Transaction is invisible to it.
t MissionDaoTest observeWeekReadsMissionAndSortiesInsideOneTransaction

# ===========================================================================
# 3. What the annotation must not change: what the Flow emits.
# Brief: the fix is an annotation; nothing here changes what observeWeek emits. Plan Testing "The
# emission's content is unchanged. The five existing MissionDaoTest cases stay untouched and green.
# They are what prove the annotation leaves the emission's content unchanged."
# ===========================================================================
log ""
log "=== the emission's content is unchanged ==="

# Must show: a mission with sorties inserted out of order still emits them sorted by index — the
# sortedBy in observeWeek is applied to the @Relation list Room built inside the transaction.
t MissionDaoTest observeWeekReturnsSortiesInIndexOrder

# Must show: a week with no mission still emits null. Room skips the relation SELECT for an empty
# parent, and the annotation must not turn "no row" into an error or an empty object.
t MissionDaoTest observeWeekIsNullForWeekWithoutMission

# Must show: a mission update and a sortie update written before the collection are both visible in
# the one emission — the parent row and its relation rows still come from the same snapshot.
t MissionDaoTest missionAndSortieUpdatesAreVisible

# Must show: a duplicate weekStart is still rejected with SQLiteConstraintException. The annotation
# goes on a read; it does not change the write paths.
t MissionDaoTest duplicateWeekStartIsRejected

# Must show: MissionDao still has exactly deleteDraft and deleteWeek and SortieDao exactly deleteById.
# The one reflection-shaped case in the file; it is here because it is one of the five.
t MissionDaoTest noWipeForMissionsAndOnlyTheByIdDeleteForSorties

# The class as a whole: a sixth case added beside these, or a case renamed away, shows up here.
c MissionDaoTest

# ===========================================================================
# 4. The consumers of the Flow.
# Brief: "LaunchpadScreen and MissionScreen consume that Flow." Plan Testing "Consumers are
# unaffected … All of them stay green untouched". No new UI test is named (see the header): these
# already run against real in-memory DAOs, so they are the check.
# ===========================================================================
log ""
log "=== the consumers ==="

# Plan Risks "Nesting". The generated wrapper for observeRaw calls __db.beginTransaction() with no
# inTransaction() guard (measured in the annotated MissionDao_Impl), so a flow read reached from inside
# an already-open transaction on the same thread would hit SQLite's "cannot start a transaction within
# a transaction". This is the case that would notice: it watches observeWeek and asserts it re-emits
# across an import that DatabaseImport wraps in a transaction.
t BackupImportRoomTest anImportThroughTheBackupActionReachesRoomsFlows
c BackupImportRoomTest

# The suite that exercises observeWeek end to end, including the re-emit that comes from the sortie
# side of the @Relation (launch, scrub, close).
c LaunchpadStatesTest

# MissionStates.observe() is the other consumer of observeWeek (ui/mission/MissionState.kt).
c MissionStatesTest

# ===========================================================================
# 5. The brief's "confirm the gate prints no Room warnings".
# Plan Decision "the 'no Room warnings' check is a log read, not a test": no -Werror flag and no test
# that parses build output.
#
# :app:kspDebugKotlin is Room's processor and is where the warning is printed. In a warm gate run that
# task is UP-TO-DATE and prints nothing at all, so the warning would look gone whether or not the
# annotation is there. This is a forced rerun of that one task on the current tree, and it is the
# check that carries the brief. Measured on the untouched tree: the run takes a few seconds, the task
# executes, and its log holds exactly one `w:` line — MissionDao.kt:50, "The return value includes a
# POJO with a @Relation. It is usually desired to annotate this method with @Transaction…".
#
# It also carries the brief's "and any other @Relation query in the DAOs": that warning is printed per
# method, so an un-annotated @Relation query in any DAO shows up here.
#
# Why the check is this one task's log and not the whole gate log: :app:compileDebugUnitTestKotlin
# prints four pre-existing `w:` lines of its own (Java type mismatch in SchemaExportTest.kt, a
# nullable receiver, two "always true" checks) whenever it executes, which in CI it does. Those are
# not Room's warnings and are not this issue's business. :app:kspDebugKotlin is Room's processor over
# the main sources, and its warning surface today is the one @Relation line.
# ===========================================================================
log ""
log "=== the Room warning: :app:kspDebugKotlin, forced rerun ==="

ksp_log="$WORK/ksp.log"
rc=0
timeout 900 "${RUNNER[@]}" :app:kspDebugKotlin --rerun-tasks --no-build-cache --console=plain > "$ksp_log" 2>&1 || rc=$?

if [ "$rc" -ne 0 ]; then
    bad "the KSP rerun: :app:kspDebugKotlin did not build (exit $rc)"
    tail -40 "$ksp_log" 2>/dev/null || true
elif ! grep -qE '^> Task :app:kspDebugKotlin$' "$ksp_log" 2>/dev/null; then
    bad "the KSP rerun: :app:kspDebugKotlin did not execute (it was up to date, cached or skipped, so its warnings were never printed)"
else
    # `w:` is the whole warning surface of this task: today it is the one @Relation warning, and after
    # the change it should be empty.
    if grep -n '^w:' "$ksp_log" 2>/dev/null; then
        bad "the gate prints no Room warnings: :app:kspDebugKotlin still warns about a query that returns a POJO with a @Relation"
    else
        ok "the gate prints no Room warnings: :app:kspDebugKotlin executed and printed no warning"
    fi
fi

# The same warning, if the gate run itself printed one — including one from a test source, which the
# run above does not compile. Scoped to Room's relation warning so an unrelated compiler warning in
# new code is not read as this.
if [ -f "$last_log" ] && grep -n '^w:.*@Relation' "$last_log" 2>/dev/null; then
    bad "the gate prints no Room warnings: the gate run printed a @Relation/@Transaction warning"
fi

# ===========================================================================
# Result
# ===========================================================================
log ""
log "=== Result: $passed passed, $failed failed ==="
if [ "$failed" -gt 0 ]; then
    log "NOT DONE: $failed check(s) failed." >&2
    exit 1
fi
log "BRIEF DONE."
