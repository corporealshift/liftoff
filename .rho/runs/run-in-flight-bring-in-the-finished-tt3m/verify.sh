#!/usr/bin/env bash
# Verify: "Run In-Flight: bring in the finished work and show the run plan" (brief.md; DESIGN.md §5.3,
# the run paragraph of §6, §8 Sortie and RunSegment, design/README.md). Definition of done for this run.
# Exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports this run produced.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this
# run's reports; a test that was not written cannot pass on its own.
#
# Named tests are matched by method name across every test report, so which class and package each one
# sits in is the implementer's call; the behaviour it must show is written above it. Every name below
# is a test this run has to write, except the ones under "the four finished commits" and "must not
# break", which are names the repo or the picked branch already has and which this run has to keep
# passing.
#
# The brief's Done-when names Robolectric tests over the derived state and the view model against
# in-memory Room, so those carry the behaviour checks. One screen test is named, because the run
# screen is what the brief asks for and no view-model test can show it; it only reads, because a
# BasicTextField inside a Dialog never lets Compose go idle under Robolectric, so no named test here
# opens a dialog or types into a field. The run screen's layout — teal header, stripe bands, no set
# counter, no progress bar, no set rows — is reviewed by the owner in the pull request.
#
# The plan's "## Decisions" are not pinned here: the wording of a segment row and what a missing part
# of it does, how the run kind is written, whether a unit is appended to the pace, whether the notes
# line is hidden when it would repeat the title, whether kind / target distance / target pace are
# separate rows or one summary line, and how a run reaches Scrub or the note on screen. The owner
# reviews those in the pull request. Where a named test also shows a decision, the comment says which.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# status <method>: pass | fail | skipped | missing | none, for one test method across every report of
# this run. Every report is read, so the new tests can sit in any package and any class. Self-closing
# testcases and name="method[params]" are handled.
status() {
    local method="$1" file out
    out=""
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        out+="$(awk -v m="$method" '
            /<testcase[ \t]/ {
                inside = 0
                if ($0 ~ ("name=\"" m "\"") || $0 ~ ("name=\"" m "\\[")) {
                    found = 1
                    if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) bad = 1
                    if ($0 ~ /<skipped/) skipped = 1
                    if ($0 !~ /\/>[ \t]*$/) inside = 1
                }
                next
            }
            inside && /<(failure|error)[ \t\r\n>\/]/ { bad = 1 }
            inside && /<skipped/ { skipped = 1 }
            inside && /<\/testcase>/ { inside = 0 }
            END {
                if (!found) print "missing"
                else if (bad) print "fail"
                else if (skipped) print "skipped"
                else print "pass"
            }
        ' "$file")"$'\n'
    done
    if grep -q '^fail$' <<<"$out"; then echo fail
    elif grep -q '^pass$' <<<"$out"; then echo pass
    elif grep -q '^skipped$' <<<"$out"; then echo skipped
    elif grep -q '^missing$' <<<"$out"; then echo missing
    else echo none
    fi
}

# t <method>: the named test must have run in this invocation and passed.
t() {
    local method="$1" st
    st="$(status "$method")"
    case "$st" in
        pass)    ok "$method" ;;
        fail)    bad "$method ran but did not pass" ;;
        skipped) bad "$method was skipped" ;;
        missing) bad "$method did not run" ;;
        none)    bad "$method did not run (no test reports under $RESULTS)" ;;
    esac
}

# ─── 1. CI gate: the two tasks .github/workflows/ci.yml runs ──────────────────
# This is also the "must not break" check for everything the brief lists — the lift In-Flight flow with
# its notes, Land, the unchecked-sets confirmation and Scrub, the Launchpad and Mission screens,
# MissionManager and the domain tests, Mission Control, the coach package, the build gate — since CI
# runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed,
# which would read as a suite that passed.
# The timeout is the check on "every wait in a test needs a timeout": a wait that never ends hangs the
# suite, and a hung suite is a failure, not a run that is still going.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="
rm -rf "$RESULTS" 2>/dev/null || true
if [ -d "$RESULTS" ]; then
    echo "  ✗ could not clear $RESULTS — a stranded Gradle test executor is holding it; run: bash gradlew.sh --stop" >&2
    exit 1
fi
if timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 | tee "$WORK/gate.log"; then
    ok "gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "gate failed: the app does not assemble, the unit tests do not pass, or the suite did not finish in 40 minutes"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK/gate.log"; then
    bad "unit tests executed (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
fi

# ─── 2. The four finished commits, brought in as they are ─────────────────────
# Brief: "Start from finished work … Bring in exactly these four commits with git cherry-pick" and
# "The four commits above are on the branch and their tests pass." Two tests per commit are named —
# the ones that only that commit's piece can answer — so a pick that was skipped, or a piece rebuilt
# without its tests, shows up as a test that did not run rather than as a branch that looks like it has
# the work. Nothing else in this run uses these pieces; the next brief adds the landing dialog.
echo ""
echo "=== the four cherry-picked commits ==="

# f7522dc ui: add distance and run kind formatting. A distance is written with the stored number and
# the configured unit's label, a fraction keeping its decimal and a whole number carrying no trailing
# '.0'; a run kind is written as a label whatever case the plan stored it in.
t formatDistanceWholeMile
t formatRunKindTempo

# 28e401a inflight: parse run landing entry. What the next brief's Land dialog will call: a typed
# distance and duration parsed into values, and an entry with both fields bad reported as an error on
# each field.
t decimalDistanceIsAccepted
t bothFieldsBadReportBothErrors

# e3f0af2 data: store run distance and minutes on land. MissionManager.land takes the two run actuals
# and writes them on the sortie row inside the landing transaction; a land that carries none leaves
# both null rather than inventing a distance or a duration.
t landStoresRunDistanceAndRunMinutes
t landWithoutRunActualsLeavesThemNull

# cb9316a inflight: land a run with distance and duration. The view model's run Land refuses a bad
# distance, and separately a bad duration, on its own field only — without reaching MissionManager,
# landing the sortie, running the completion callback, or raising a failure message.
t landRunRejectsInvalidDistanceWithoutTouchingTheManager
t landRunRejectsInvalidDurationWithoutTouchingTheManager

# ─── 3. The run's In-Flight state, derived from its Flight Plan ───────────────
# Brief: "A run sortie that has a Flight Plan gets its own In-Flight state and screen. A run with no
# plan at all keeps today's NoChecklist screen." Plain Kotlin over the derived state, driven through
# the view model against in-memory Room on a seeded Mission, as the brief asks ("Put the derivation in
# plain Kotlin that a Robolectric test can drive").
echo ""
echo "=== the run state and what it carries ==="

# A launched run sortie whose plan was replaced with a generated one — a title, notes, a run kind, a
# target distance, a target pace and two segments each with a distance and a duration — is no longer
# NoChecklist: the state is the run state, and it carries the sortie number, the plan's title, the
# notes line, the run kind, the target distance, the target pace, and one row per segment holding that
# segment's description with its distance and duration. The same plan read in the other distance unit
# gives the same numbers under that unit's label.
# Decision (plan): the unit setting picks the label and the stored number is what is shown, never
# converted — so a plan's 5.0 reads under mi and under km as 5.
t runStateFromSeededPlanWithSegments

# The simple 'Run' plan MissionManager creates for a run sortie (title 'Run', notes copied from the
# sortie's focus, no kind, no target distance, no target pace, no segments) gives the run state too,
# and carries only the values that plan actually has: no kind, no target distance, no target pace, no
# segment rows. That is the brief's "A row appears only when it has a value" and "shows its title and
# focus without empty fields" — the state holds nothing the screen would have to print as an empty row.
# The same plan with no notes at all still reads the sortie's focus as its notes line.
t runStateFromSimpleRunPlan

# A run sortie with no Flight Plan at all keeps today's NoChecklist state.
t runWithNoPlanStillShowsNoChecklist

# The other side of that boundary, which nothing else in the repo asserts once the run state exists: a
# LIFT sortie whose plan has segments but no exercises is still NoChecklist. The run state is for a
# run, not for any plan that carries segments, so the lift flow is untouched by this brief.
t liftWithSegmentsOnlyPlanStillShowsNoChecklist

# ─── 4. The distance unit setting drives the run screen live ──────────────────
# Brief: "Changing the distance unit in Mission Control updates the screen live." Asserted on the state
# the In-Flight view model publishes while a run sortie is in flight, so it is the screen's own state
# flow that moves when the setting changes, not a formatting helper called a second time.
# Decision (plan): the stored number is shown under the configured unit, not converted.
t runDistanceUnitChangeUpdatesStateLive

# ─── 5. Land, Scrub and the sortie note from the run state ────────────────────
# Brief: "Land on a run keeps today's behaviour for now: it lands straight away, with no dialog and no
# unchecked-sets confirmation." and "Scrub and the sortie note work on the run screen exactly as on
# lifts." All three driven from the run state through the view model against in-memory Room.
echo ""
echo "=== landing, scrubbing and noting a run ==="

# Launch then Land a simple run from the run state: the completion callback (what returns to the
# Launchpad) runs exactly once, the sortie row is LANDED with the landed time recorded, and no failure
# message is raised. It stores no run actuals, because this brief keeps today's Land and nothing here
# uses landRun yet.
t launchAndLandSimpleRunFromRunState

# Scrub from the run state: the reason the owner typed is stored trimmed, the sortie is SCRUBBED, the
# Mission has moved on, and nothing is raised. Named apart from the lift scrub test the gate already
# runs, because it starts from the run state — where the brief's warning about a `when` over the
# In-Flight state with an `else` branch bites: the screen picks the sortie number for the scrub dialog
# that way, and a run falling into the else leaves Scrub dead on runs.
t scrubFromRunStateStoresReason

# The sortie note on a run that has a Flight Plan, through the same note rule the lifts use: the text
# the owner saves is stored trimmed on the sortie row and survives a fresh read.
t sortieNoteSavesOnRunWithAPlan

# ─── 6. The run screen ────────────────────────────────────────────────────────
# The one screen-level test: the brief asks for a run screen, and no view-model test can show that the
# run state is rendered at all. It only reads — no dialog is opened and nothing is typed, because a
# BasicTextField inside a Dialog never lets Compose go idle under Robolectric. The layout itself is
# reviewed by the owner in the pull request.
echo ""
echo "=== the run screen ==="

# A launched run sortie with its simple 'Run' plan shows the plan on screen: the plan's title and its
# notes/focus line are displayed, the NoChecklist placeholder ("No checklist for this sortie.") is
# not, and the footer offers Land and Scrub with the sortie note action — all displayed and enabled,
# as they are on a lift.
t runScreenShowsPlanAndOffersNoteLandAndScrub

# ─── 7. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses that
# this screen is the one touching.
echo ""
echo "=== must not break ==="

# "Don't change the Room schema": the exported Room schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase
# "the lift In-Flight flow … and their tests": the derivation, the check actions, the open-set count
# the confirmation reads, the card footer's Add set / Skip / Unskip, the StepperDialog, the note lines
# on both states, the note dialog's save path, and the two confirmation dialogs.
t initialState
t checkAndUncheck
t openCountCountsOpenSets
t confirmationNeededOnlyWhenSetsAreOpen
t noChecklistNeedsNoConfirmation
t checkingASetMarksItDoneInRoomAndUpdatesCounter
t footerAddSetAndSkipUnskip
t openSetStepperShowsPlannedWeight
t seededExerciseNoteShowsMutedLineOnCard
t sortieNoteActionAndLineShowOnChecklistState
t seededSortieNoteShowsOnNoChecklistState
t noteCellOnActiveCardOpensDialogAndSaves
t sortieNoteActionOnNoChecklistOpensDialogAndSaves
t notesAreStoredTrimmed
t blankExerciseNoteStoresNull
t landConfirmStatesTheOpenCountAndSingularForOneSet
t landConfirmButtonsCallConfirmOrDismiss
t scrubDialogSendsTheTypedReasonOnConfirm
t scrubDialogCancelDismissesWithoutConfirming
# The lift Land and Scrub flow, unchanged, including the run sortie that follows a landed lift: it is
# still launched and landed as before. The no-checklist screen still lands a plan-less run on the first
# tap, with no confirmation. (landingARunNeedsNoChecklist is the existing test this brief changes what
# it observes — seedMission() gives that run a plan, so it now waits for the run state. The gate runs
# it whichever way it is written; it is not named here.)
t landWithEverySetChecked
t landRecordsUncheckedSetsAsNotDone
t landStoresLandedTime
t landMakesNextRunSortieCurrentWithSimpleRunPlan
t landingLastSortieFromInFlightClosesMission
t noChecklistLandLandsAndCallsOnBack
t scrubFromInFlightKeepsCheckedSetsAndStoresReason
t scrubStoresWhitespaceReasonAsNull
t doubleTapLandsOnce
t secondLandIsRejectedWithoutCrash
# "MissionManager and the domain tests": the lifecycle this brief drives, unchanged.
t landAdvancesToNextSortie
t landMarksUncheckedSetsNotDone
t scrubInFlightKeepsCheckedSets
t scrubbingLastSortieClosesMission
t scrubStoresBlankReasonAsNull
t legalTransitionsSucceed
t illegalTransitionsAreRejected
# "the Launchpad and Mission screens": the Launchpad still reaches the in-flight sortie, still shows a
# scrubbed one as pending, still reaches the closed state; the Mission still shows what was landed and
# why a sortie was scrubbed.
t inFlightAfterLaunch
t pendingAfterScrub
t closedAfterAllSortiesLanded
t landedSortieDetailShowsRecord
t scrubbedSortieDetailShowsReason
# "Mission Control", the coach package, and the §2 vocabulary: never 'session' for a sortie.
t neverSaysSession
t runFlightPlanPromptMatchesGoldenFile

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
