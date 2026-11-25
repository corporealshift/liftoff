#!/usr/bin/env bash
# Verify: "run duration should allow setting seconds too" (brief.md; issue corporealshift/liftoff#86).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Checked: the CI gate (./gradlew :app:assembleDebug :app:testDebugUnitTest) is green, and named
# cases for each thing the brief asks for ran and passed:
# - the run Land card offers a field for seconds beside Duration, starts it empty like its other
#   fields, and puts a seconds error under it
# - the entry parser refuses seconds outside 0-59, a negative, a fraction and text with the seconds
#   error alone, combines valid minutes and seconds into one duration, and accepts seconds with no
#   minutes; landRun refuses a bad seconds without landing anything
# - landing 27 min 34 s stores 27 + 34/60 minutes in the sortie row read back out of the DAO
# - the recorded duration reads back as minutes plus seconds (32 min / 27 min 34 s / 45 s) through
#   the shared formatter, in the Landed history's TIME, and in the coach's History line
# - whole-minute durations still land and still read exactly as they do today, so the coach's three
#   golden prompts and the existing whole-minute cases are untouched and still pass
#
# Not checked:
# - The plan's Decisions (seconds beside rather than instead of minutes, 0-59 refused rather than
#   rolled into minutes, sub-minute runs allowed, Duration still accepting a decimal, storage staying
#   one number of minutes, the zero part dropped on read-back). The owner reviews them in the PR.
# - A Compose render of the Mission tab's landed detail. The formatter it uses is checked as logic
#   below; a UI render of that screen goes beyond the brief.
# - The Room schema, the export and the backup format. No column changes, so the existing backup
#   round-trip and export/import classes are required green rather than a new case for them.
# - DESIGN.md. §16 keeps live timers and GPS out of scope and this is still manual entry at landing,
#   so the plan rules out a design-doc edit and nothing here needs one.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch lives under app/build, which is gitignored.
WORK="app/build/verify86"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1

# ---------------------------------------------------------------------------
# The Gradle command. On Windows (Git Bash) use gradlew.sh, the repo's own wrapper for this machine.
# Elsewhere use the pinned toolchain if present, else ./gradlew.
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
# Index Gradle's JUnit XML by class.method and by class. Each name keeps the worst outcome it had.
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
    : > "$WORK/qualified"
    : > "$WORK/classes"
    if [ -s "$WORK/raw" ]; then
        awk -F '\t' '
            function rank(s) { if (s == "fail") return 3; if (s == "skip") return 2; return 1 }
            { key = $1 SUBSEP $2
              if (!(key in seen) || rank($3) > rank(seen[key])) seen[key] = $3 }
            END { for (k in seen) { split(k, p, SUBSEP); print p[1] "\t" p[2] "\t" seen[k] } }
        ' "$WORK/raw" > "$WORK/all"
        awk -F '\t' '$1 == "Q" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/qualified"
        awk -F '\t' '$1 == "C" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/classes"
    fi
}

# ---------------------------------------------------------------------------
# 1. The CI gate. Old reports are moved aside so every case below comes from this run.
# ---------------------------------------------------------------------------
clear_results() {
    if [ -d "$RESULTS" ]; then
        # Moved, not deleted: a stranded test executor can hold files open.
        local aside="app/build/verify-old-results-$1"
        rm -rf "$aside" 2>/dev/null || true
        mv "$RESULTS" "$aside" 2>/dev/null || true
    fi
}

# Retry only when Gradle itself broke or only the known-flaky classes failed.
transient_failure() {
    local attempt="$1"
    local gate_log="$WORK/gate-$attempt.log"

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
    other="$(awk -F '\t' '$2 == "fail" && !($1 ~ /(^|\.)LiftoffShellTest\./) && !($1 ~ /(^|\.)InFlightScreenTest\./) { n++ } END { print n + 0 }' "$WORK/qualified")"
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
    ok "gate: :app:assembleDebug :app:testDebugUnitTest"
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
    : > "$WORK/qualified"; : > "$WORK/classes"
else
    index_reports
    log "  … $(wc -l < "$WORK/qualified") test records, $(wc -l < "$WORK/classes") classes indexed"
fi

# t <class> <method>: the case ran in this gate and passed.
t() {
    local cls="$1" method="$2" want status
    want="$(printf '%s' "$method" | tr -d '`')"
    status="$(awk -F '\t' -v k="$cls.$want" '{ p = index($1, "." k); if ($1 == k || (p > 0 && p + length(k) == length($1))) { print $2; exit } }' "$WORK/qualified")"
    case "$status" in
        pass) ok "$cls.$want" ;;
        fail) bad "$cls.$want — ran and FAILED" ;;
        skip) bad "$cls.$want — ran but was SKIPPED" ;;
        *)    bad "$cls.$want — did not run at all" ;;
    esac
}

# c <class>: every test Gradle recorded for this class passed.
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
# 2. The run Land card (Compose render of RunLandDialogContent).
# Brief: "run duration should allow setting seconds too" — the seconds have to be enterable at the
# one place a run's duration is set.
# ===========================================================================
log ""
log "=== the run Land card ==="

# Must show: the Land card for a run shows a Seconds field beside its Duration field, that all three
# of its fields (Distance, Duration, Seconds) are present and start empty, and that when
# parseRunEntry reports a seconds error the card draws that error under the Seconds field and not
# under Distance or Duration. (plan decision "Does the seconds control replace minutes, or sit
# beside them?")
t LandScrubDialogTest runLandDialogShowsSecondsBesideDurationAndItsErrorUnderItsOwnField

# ===========================================================================
# 3. The entry parser (pure Kotlin, no device).
# Brief: "just setting minutes isn't accurate enough" — seconds are part of the entry and combine
# with the minutes into the one duration the app stores.
# ===========================================================================
log ""
log "=== the entry parser with seconds ==="

# Must show: "90", "60", "-5", "0.5" and "abc" in the seconds field each give the seconds error and
# no other field's error, and that a whole 0-59 beside minutes gives a duration of minutes +
# seconds/60 (27 min + 34 s -> 27.566666...). (plan decision "How fine are the seconds, and what
# happens to a value outside them?")
t RunLandingTest secondsOutsideZeroToFiftyNineAreRejectedWithTheSecondsError

# Must show: seconds with the minutes field blank or 0 is accepted as that many seconds of a minute
# (45 s -> 0.75), and 0 minutes with 0 seconds is still refused with the duration error — the
# above-0 rule now applies to the combined total. (plan decision "Can a run be shorter than a
# minute?")
t RunLandingTest secondsWithNoMinutesIsValidAndZeroMinutesWithZeroSecondsIsRejected

# ===========================================================================
# 4. The view model's refusal, before anything is landed.
# ===========================================================================
log ""
log "=== landRun refuses a bad seconds ==="

# Must show: landRun with a good distance and duration and a seconds value it refuses returns false,
# raises only the seconds error, raises no message, never reaches the manager (the sortie row is
# still IN_FLIGHT with both run fields null) and calls its onDone callback zero times.
t InFlightViewModelTest landRunRejectsInvalidSecondsWithoutTouchingTheManager

# ===========================================================================
# 5. Landing a run with seconds (view model + real Room DAO).
# Brief: the seconds have to reach the stored duration, not just the dialog.
# ===========================================================================
log ""
log "=== landing stores the seconds ==="

# Must show: landing a run with 27 minutes and 34 seconds leaves the sortie LANDED with a run
# duration of 27 + 34/60 minutes read back out of sortieDao — the seconds are stored, not rounded
# away into the minutes. (plan decision "What happens to durations already stored, and to how they
# are stored?")
t InFlightRunTest runLandWithMinutesAndSecondsStoresTheCombinedDuration

# Must show: the whole-minute case that exists today still lands 5.2 mi and 31 min with the seconds
# field left blank — the same stored distance and duration as today, only an extra argument at the
# call.
t InFlightRunTest runLandWithDistanceAndDurationStoresBoth

# ===========================================================================
# 6. Reading the duration back.
# Brief: "just setting minutes isn't accurate enough" — what is shown must carry the seconds.
# ===========================================================================
log ""
log "=== the recorded duration reads back as minutes and seconds ==="

# Must show: the recorded-duration formatter gives "32 min" for 32.0, "27 min 34 s" for the
# 27.566666... that a 27 min 34 s entry produces, "18 min 30 s" for 18.5, "27 min 45 s" for 27.75,
# and "45 s" for 0.75 — whole seconds taken with rounding, and a zero part dropped.
# (plan decision "How is the duration read back?")
t PlanFormatTest formatRunDurationMinutesAndSeconds

# Must show: the Landed history's run detail carries recordedDuration "18 min 30 s" for a sortie
# stored with runMinutes 18.5, where it carries "18.5 min" today, and still carries "32 min" for a
# whole-minute run. (plan decision "How is the duration read back?")
t LandedStatesTest runDetailRecordedDurationReadsMinutesAndSeconds

# Must show: the coach's History section writes the run line as "run: 3.1 mi in 27 min 34 s" for a
# sortie stored with runMinutes 27.566666..., where it writes "27.566666666666666 min" today, and
# writes a whole 28.0 as "28 min" exactly as it does now. (plan decision "How is the duration read
# back?")
t CoachPromptBuilderTest historyLineWritesARunDurationInMinutesAndSeconds

# Must show: the three golden prompts still match byte for byte — a whole-minute run's History line
# is unchanged, so the fixtures were not edited to fit the new reading.
c CoachPromptGoldenFileTest

# ===========================================================================
# 7. Neighbours. The gate already runs everything; these catch one renamed away or skipped.
# ===========================================================================
log ""
log "=== the neighbours ==="

c RunLandingTest
c LandScrubDialogTest
c InFlightRunTest
c InFlightViewModelTest
c InFlightStatesTest
c PlanFormatTest
c LandedStatesTest
c MissionStatesTest
c CoachPromptBuilderTest
c SortieDaoTest
c MissionManagerTest
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest
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
