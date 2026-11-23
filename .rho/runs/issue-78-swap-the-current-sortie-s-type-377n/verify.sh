#!/usr/bin/env bash
# Verify: "Swap the current sortie's type" (brief.md; issue corporealshift/liftoff#78).
# Definition of done for this run: exit 0 only when the brief is met.
#
# What is checked, in one line: the CI gate (the two tasks .github/workflows/ci.yml runs) is green;
# eighteen new cases show that swapping a sortie's type flips that sortie's type AND the pattern
# letter at the same index, in place, on the current sortie of the current week's ACTIVE Mission and
# on no other, only while it is PENDING or PLANNED; that a PLANNED swap deletes that sortie's plan
# row and queues a fresh flight-plan generation exactly as Regenerate does, and creates no new week;
# that the swapped sortie reaches the coach's week prompt with its new type and default focus; and
# that the Launchpad offers the action under the same conditions Regenerate is offered, labels it
# with the type it swaps to, and updates the card and the pattern track when confirmed.
#
# Order: the gate first, because everything below reads the reports it writes; then the named cases;
# then the classes this change must not break.
#
# It fails on the untouched tree, and for the right reason, measured on this tree: the gate is green
# (775 test records, 93 classes indexed), the 37 checks that already hold are ok, and each of the
# eighteen named cases reports "did not run at all" — 18 failed, exit 1. The other direction is not
# measured here: proving a named case can pass means writing it, which is a later step of this run.
#
# What is NOT checked here, and why:
# - The DESIGN.md amendment. The brief requires it first ("DESIGN.md §4.1 freezes the pattern after
#   confirmation"), and plan Task 1 is that amendment, covering §4.1 (line 117: "Once confirmed, the
#   pattern is frozen") and §13 (line 697: "frozen on confirm"), which the swap makes untrue as
#   written. A design document can only be checked by its wording, and a wording grep would dictate
#   the wording and pass an amendment that names the exception in the wrong place. It is reviewed in
#   the pull request, which is where the owner reads DESIGN.md.
# - The export. The brief's Proposed rules want "the pattern, the export and the coach prompts" to
#   agree. DatabaseExport writes mission.pattern straight out of the row the MissionManager cases
#   below prove flipped, so a case for it would re-test the export's existing behaviour against data
#   these cases already assert. DatabaseExportTest is run unchanged instead.
# - A Mission-tab action. The brief asks for the action "on the Launchpad"; plan Files leaves
#   ui/mission/MissionScreen.kt alone. The Mission tab's state and the shell's tab wiring are run
#   unchanged, because the transition table and the pattern letter reach them.
# - Undo. Plan Decision "No undo; the confirm dialog is the guard": there is no undo affordance to
#   test. What is tested instead is that cancelling the dialog writes nothing.
# - settings.defaultPattern. Plan Decision "the flip is this week's only": no case pins it, because
#   the swap takes the pattern from the Mission row and never reads settings. The cases that assert
#   no new week and no other Mission row is touched are what would notice if it did.
# - Which file each new case lives in beyond the four the plan names, and how a case is written
#   internally. Each case is checked by name and by what it must show, not by its assertions.
# - Which other tests in the suite break. The gate runs every test in the repo, so a break anywhere
#   else already fails the gate. The class names below only catch one of them renamed away, skipped
#   or deleted.
# - ARCHITECTURE.md, CLAUDE.md wording, line endings, commit style, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch lives under app/build, which is gitignored. The gate logs stay there for the work steps.
WORK="app/build/verify78"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1

# ---------------------------------------------------------------------------
# The Gradle command, chosen the way CI chooses it.
# ci.yml runs `./gradlew :app:assembleDebug :app:testDebugUnitTest` on Ubuntu with Java 17.
# gradlew.sh is the owner's own shortcut and hardcodes C:/Users/corpo/... paths, so it is used only
# where it works. Elsewhere the pinned toolchain is used — the same Gradle the wrapper asks for —
# with JAVA_HOME and ANDROID_HOME pointed at it, and only where those directories exist.
# LIFTOFF_TOOLCHAIN overrides the toolchain root.
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
# 1. The CI gate: the two tasks ci.yml runs, in one run. The reports are moved aside first, so every
# named case below comes from this run; --no-build-cache stops them being restored from the cache.
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
# 2. The domain rule: one flip, and the pattern letter at the same index flips with it.
# Brief: "It changes one sortie's type in place and leaves the rest of the pattern alone — e.g.
# R [L] R L R becomes R [R] R L R." Plan Files: domain/SortieSwapTest.kt is new.
# ===========================================================================
log ""
log "=== swapping a sortie flips its type and the pattern letter at the same index ==="

# Must show: swapping a sortie returns a Mission whose sortie at that index has the other type, and
# whose pattern string is the original pattern with the letter at that same index changed and every
# other letter and length untouched — R L R L R swapped at index 1 is R R R L R. Swapping a Mission
# twice at the same index returns the original Mission. A helper that flipped only Sortie.type, which
# is what the brief calls "a half change", fails here: the Launchpad's own track reads the letter out
# of mission.pattern (ui/sortie/PlanFormat.kt:106), so the card and the track would contradict each
# other.
t SortieSwapTest swapSortieTypeFlipsTheTypeAndThePatternLetterOfTheSameIndex

# Must show: after a lift→run swap the sortie's focus is the run's default focus and its
# focusRationale is gone; after a run→lift swap it is the lift's default. Those defaults are the ones
# the app already uses when it builds a sortie out of a pattern letter (domain/Missions.kt:56 —
# "easy" for R, "full body" for L), so the swap introduces no third pair of strings and does not
# spell the defaults out a second time.
# Plan decision "A focus the user set for that sortie is replaced by the new type's default": a focus
# the user edited by hand for the old type is replaced too, not kept.
t SortieSwapTest swapSortieTypeSetsTheNewTypesDefaultFocusAndClearsTheOldRationale

# ===========================================================================
# 3. The state machine change.
# Brief: "Add a Swap to run / Swap to lift action … The state machine change." Plan Decision "the
# transition table gains PLANNED → PENDING": the swap re-prepares a PLANNED sortie, so it needs the
# edge Regenerate already relies on in practice but the table never declared.
# ===========================================================================
log ""
log "=== PLANNED → PENDING becomes a legal transition ==="

# Must show: PLANNED → PENDING is now legal, and every pair SortiesTest already rejects stays
# rejected — LANDED and SCRUBBED to anything, IN_FLIGHT → PENDING, PENDING → LANDED, and each
# same-state pair. The swap is only ever offered on PENDING and PLANNED, so no other edge is added:
# an edge that let a LANDED or SCRUBBED sortie be re-opened would fail this case.
t SortieSwapTest plannedToPendingIsALegalTransitionAndEveryOtherIllegalTransitionIsStillRejected

# ===========================================================================
# 4. The MissionManager rule: the gates, and the in-place flip.
# Brief: "Only the current sortie of the current week's ACTIVE Mission can be swapped. Only while
# that sortie is PENDING or PLANNED. These are the same gates MissionManager.regenerateFlightPlan
# already enforces." And: "no new week is created". Plan Files: data/MissionManagerSwapTest.kt is
# new. Plan Risks: MissionManager.advance reuses an existing open FLIGHT_PLAN row, so the swap must
# read the sortie's open generation ids before inserting its own — that is what the last two cases
# below would notice.
# ===========================================================================
log ""
log "=== the swap respects the gates Regenerate already enforces ==="

# Must show: on a PENDING current sortie of the ACTIVE Mission, the swap writes the same Mission row
# (same id, same weekStart) with the flipped pattern letter, writes the same sortie row (same id) with
# the flipped type and the new type's default focus, and leaves every other sortie row and every other
# pattern letter alone. No new mission row appears anywhere, and the missions table still holds one row
# for that week.
t MissionManagerSwapTest swapOnAPendingSortieFlipsTheSortieAndThePatternLetterInPlace

# Must show: on a PLANNED current sortie, the swap does the same in-place flip, and the sortie's own
# QUEUED flight-plan row is gone — deleted, exactly as regenerateFlightPlan deletes it — while the next
# sortie's QUEUED flight-plan row is still there, unchanged. This is the brief's "the PLANNED case also
# has to delete and re-queue that sortie's plan row", and it is what proves the swap reuses the
# existing rows instead of building a new week around them.
t MissionManagerSwapTest swapOnAPlannedSortieFlipsTheSortieAndDeletesItsPlanRow

# Must show: a fresh FLIGHT_PLAN generation is queued for the swapped sortie, and the swapped sortie's
# own still-open rows are cancelled rather than left to run against the type they were written for.
# Plan Decision "the swap reuses regenerateFlightPlan's machinery, and the cancelled ids are the
# sortie's own, read before the swap inserts its row": MissionManager.advance hands back an existing
# open FLIGHT_PLAN row instead of inserting a new one, so a swap that lets advance pick the ids
# cancels the row it has just inserted and queues nothing. A swapped sortie must end with exactly one
# open FLIGHT_PLAN row, and GenerationCanceller must be given the old one.
t MissionManagerSwapTest swapQueuesAFreshFlightPlanGenerationAndCancelsTheSwappedSortiesOpenGenerations

# Must show: the swap refuses a sortie that is not the current one, and a sortie of a Mission that is
# not ACTIVE, and a sortie of a week that is not the current week — and in every one of those cases it
# writes nothing at all: no Mission update, no sortie update, no generation row. The brief's "These are
# the same gates MissionManager.regenerateFlightPlan already enforces" is what makes each one a refusal
# rather than a partial effect; LaunchpadRegenerateTest already pins the same three refusals one level
# up, and the swap must refuse at the manager too, not only in the UI.
t MissionManagerSwapTest swapRefusesASortieThatIsNotCurrentOrOfANonActiveMissionAndWritesNothing

# Must show: the swap refuses a sortie in every state the Launchpad cannot swap it in — IN_FLIGHT,
# LANDED, SCRUBBED — and writes nothing. An IN_FLIGHT lift is swapped only after Back to Launchpad
# (brief: "An IN_FLIGHT lift would use Back to Launchpad first"), and a LANDED or SCRUBBED sortie can
# never be swapped at all.
t MissionManagerSwapTest swapRefusesAnInFlightLandedOrScrubbedSortieAndWritesNothing

# Must show: swapping the same sortie twice returns the original type, the original pattern letter and
# the original week — no second Mission, no third state, no leftover generation row. Brief: "A swapped
# sortie can be swapped again." Plan Decision "A swapped sortie can be swapped again": the swap is
# idempotent over two applications, not a one-shot mutation of a week.
t MissionManagerSwapTest swappingTheSameSortieTwiceRestoresTheOriginalTypeLetterAndFocus

# Must show: on a sortie whose plan was not generated — a SIMPLE_RUN or a REFLY plan — the swap still
# flips the sortie and the pattern letter in place, and writes no generation row at all, because there
# is nothing to re-queue. This is the brief's "no new week is created" for the case where the sortie
# has no flight-plan row to delete.
t MissionManagerSwapTest swapOnASimpleRunOrReFlySortieFlipsItAndWritesNoGenerationRow

# ===========================================================================
# 5. The coach's prompt.
# Brief: "so the pattern, the export and the coach prompts all agree", and the Tests list asks for
# "Prompt tests that the swapped sortie appears in the coach's week prompt with its new type and
# focus." Plan Decision "the coach prompt needs no builder change": PromptInputLoader.weekOf reads
# type, focus and focusRationale out of the sortie rows, so the swap reaches the prompt by itself.
# Plan Files: data/PromptInputLoaderSwapTest.kt is new.
# ===========================================================================
log ""
log "=== the swapped sortie reaches the coach's week prompt ==="

# Must show: after a swap, the current week's prompt inputs carry the swapped sortie with its new type
# and its new default focus, and every other sortie of that week unchanged. The coach's own wording is
# not re-derived here: CoachPromptBuilderTest and CoachPromptGoldenFileTest run unchanged below, so
# what is being proved is that the inputs changed and the builder needed no change to match them.
t PromptInputLoaderSwapTest promptInputsForASwappedSortieCarryItsNewTypeAndDefaultFocus

# ===========================================================================
# 6. The Launchpad action.
# Brief: "Add a Swap to run / Swap to lift action on the Launchpad for the current sortie … the same
# conditions Regenerate uses … labelled with the type it swaps to … confirming it updates the card and
# the pattern track." The brief's Tests list names the Launchpad, so these are UI cases.
# Plan Files: ui/launchpad/LaunchpadSwapTest.kt is new. Plan Decision "Where the action lives, and
# what it is called": beside Regenerate on the PENDING and PLANNED cards, labelled "Swap to run" on a
# lift and "Swap to lift" on a run, and it is a second action rather than a mode switch on the card.
# ===========================================================================
log ""
log "=== the Launchpad offers the swap under the conditions Regenerate is offered ==="

# Must show: the action is offered on the PENDING and PLANNED cards of the current sortie, and is not
# offered on that card in IN_FLIGHT, LANDED or SCRUBBED. LaunchpadRegenerateTest's
# regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState is the shape this mirrors.
t LaunchpadSwapTest swapActionIsOfferedOnPendingAndPlannedAndOnNoOtherState

# Must show: the action is not offered when the Mission is not ACTIVE, when the sortie is not the
# current one, or when the week is not the current week — the same three refusals
# LaunchpadRegenerateTest already pins for Regenerate
# (regenerateOnAMissionThatIsNotActiveIsRefusedAndWritesNothing,
# regenerateOnASortieOfAWeekThatIsNotCurrentIsRefusedAndWritesNothing).
t LaunchpadSwapTest swapActionIsAbsentWhenTheMissionIsNotActiveOrTheSortieIsNotCurrent

# Must show: the label names the type the action swaps TO, in both directions — "Swap to run" on a
# PLANNED or PENDING lift card, "Swap to lift" on a run card. A label that named the type being left,
# or a single generic label, fails here. (Plan decision "Where the action lives, and what it is
# called".)
t LaunchpadSwapTest swapLabelNamesTheTypeItSwapsToInBothDirections

# Must show: confirming the swap on a PENDING card updates the card to the other type — its title, its
# type chip and its focus line — and updates the pattern track's chip at that index to the other
# letter, in the same frame, with no intermediate frame where the card says one type and the track the
# letter it was swapped out of. The next sortie's queued plan is untouched.
t LaunchpadSwapTest swapOnAPendingSortieFlipsTheCardAndThePatternTrackWithoutTouchingTheNextSortiesPlan

# Must show: on a PLANNED card whose plan was GENERATED, tapping the swap opens a confirm dialog that
# says the plan will be replaced and the type changed, and cancelling it writes nothing — the sortie
# keeps its type, the Mission keeps its pattern letter, and the QUEUED plan row is still QUEUED.
# (Plan decision "No undo; the confirm dialog is the guard": the dialog is the only guard, so a cancel
# must be a complete no-op.)
t LaunchpadSwapTest swapOnAPlannedCardWithAGeneratedPlanAsksForConfirmationAndACancelWritesNothing

# Must show: on a PLANNED card whose plan was not GENERATED — a SIMPLE_RUN or a REFLY card — tapping the
# swap runs it immediately, with no confirm dialog. (Plan decision "A re-flown plan is deleted without
# a dialog": the dialog exists to warn about a plan the user has to re-read, and a re-flown or simple
# run plan has nothing to lose.)
t LaunchpadSwapTest swapOnAPlannedCardWhosePlanWasNotGeneratedRunsTheSwapWithoutAConfirmDialog

# Must show: confirming the swap on a PLANNED card shows the re-planning line for the swapped sortie,
# the way Regenerate does today, and leaves the next sortie's queued plan line as it was — the swap
# re-plans one sortie, not the week. This mirrors
# LaunchpadPlannedActionsTest.tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine
# and LaunchpadScrubTest.scrubFromTheLaunchpadQueuesTheNextSortiesFlightPlanGenerationForEnqueuing.
t LaunchpadSwapTest confirmingTheSwapDialogFlipsTheCardAndThePatternTrackAndCancelsTheOpenGenerations

# ===========================================================================
# 7. What this run must not break.
# The gate already runs every test in the repo, so these only catch a case renamed away, skipped or
# deleted, or a class whose behaviour the swap changed without the brief asking for it.
# ===========================================================================
log ""
log "=== the neighbours ==="

# The state machine the swap edits. SortiesTest already pins the legal and illegal transition lists,
# including the count of illegal pairs; the swap removes PLANNED → PENDING from the illegal list, so
# that count moves in the same commit (plan Risks).
c SortiesTest
c MissionsTest
c SortiePlanningTest
c ReFlyTest
c SortieGenerationStatusTest

# The manager whose gates the swap copies, and whose plan-row rules it must not change.
c MissionManagerTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c MissionManagerResetTest
c MissionManagerOutlineTest

# The prompt the swap feeds. The golden files must come out byte-for-byte unchanged: the prompt is
# built from the sortie rows, so a swapped sortie reaches it with no builder change.
c PromptInputLoaderTest
c CoachPromptBuilderTest
c CoachPromptGoldenFileTest

# The export the brief wants to agree with the pattern. It writes mission.pattern out of the row the
# cases above prove flipped, so it is run unchanged rather than given a case of its own.
c DatabaseExportTest

# The generation machinery the swap reuses. GenerationCanceller skips a row that is not QUEUED or
# RUNNING, so the swap must not mark rows CANCELLED inside its own transaction.
c GenerationCancellerTest
c GenerationWorkerTest

# The Launchpad's action rows and dialogs. The plan's Risks say these already pin the action row on a
# PLANNED card, so a second action on that row is expected to touch their assertions — their behaviour,
# not the wording, is what must still hold.
c LaunchpadRegenerateTest
c LaunchpadPlannedActionsTest
c LaunchpadScrubTest
c LaunchpadConfirmTest
c LaunchpadReFlyTest
c LaunchpadStatesTest
c LaunchpadPendingStatusTest
c LaunchpadSortieStatusTest
c LaunchpadDraftStatusTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadCoachStatusTest
c LaunchpadViewInRhoTest

# The pattern track and the chips, which read the letter out of mission.pattern.
c PlanFormatTest
c PatternTrackTest

# The Mission tab and the shell: unchanged by this run, but they read the transition table and the
# pattern letter the swap changes.
c MissionStatesTest
c MissionTabStateTest
c MissionViewModelResetTest
c ResetWeekDialogTest
c LiftoffShellTest

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
