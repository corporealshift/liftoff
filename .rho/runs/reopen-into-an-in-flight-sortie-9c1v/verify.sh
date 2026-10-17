#!/usr/bin/env bash
# Verify: "Reopen into an in-flight sortie" (brief.md; DESIGN.md §6 'In flight (logging at the gym)',
# the bullet at 217-218 — "If the process is killed, the In-Flight screen comes back exactly as it was
# left"; CLAUDE.md §2 vocabulary). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports this run produced,
# then the two ARCHITECTURE.md rows the brief names.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not written cannot pass on its own. Names are matched across every report, so
# which class and package each one sits in is the implementer's call; what it must show is written above
# it. The eleven names under "opening" and "leaving" are tests this run has to write. The names under
# "must not break" already exist and this run has to keep them passing.
#
# The brief's own shape for the two pieces is what the names follow: the start decision is a plain Kotlin
# function over the saved state and the IN_FLIGHT sortie ids, so the opening tests are plain JUnit with no
# Compose; the "no longer in flight" signal is plain Kotlin in the view model or the state flow, so the
# leaving tests drive it against in-memory Room. No test here composes a screen.
#
# What is NOT checked here, and why: the brief says Compose UI tests are not required, so nothing in this
# script drives the shell's UI. Four clauses can only be seen in the shell — the Launchpad showing first
# while the cold-start read runs, a user who navigated during that read staying where they are, Mission
# Control staying open, and the shell actually applying the start decision it computed — plus "read
# getInFlight() off the main thread" and "every wait in a test needs a timeout". The owner reviews those
# in the pull request. The evidence this script can supply for the shell is that LiftoffShellTest, which
# runs the real MainActivity on a cold start against the app's own database, still opens on the Launchpad
# and still restores saved navigation after recreation.
#
# The plan's "## Decisions" are not pinned here: leaving quietly with no message, saved state winning even
# when a different sortie is IN_FLIGHT, and a rotation during the read not retrying it. Where a named test
# also shows a decision, the comment says which.
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
# This is also the "must not break" check for everything the brief lists — In-Flight for lifts and runs
# with notes, Land, the unchecked-sets confirmation, the run landing dialog and Scrub and their tests, the
# Launchpad and Mission screens, the shell's back behaviour, MissionManager and the domain tests, Mission
# Control, the coach package, and the build gate — since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed,
# which would read as a suite that passed.
# The brief warns a full gate can outlast a shell command's limit; the timeout is how this script waits
# for it. A suite that never finishes is a failure, not a run that is still going.
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

# ─── 2. Opening: the start decision ───────────────────────────────────────────
# Brief part 1: where the shell starts. Plain Kotlin over the saved navigation state, if any, and the ids
# of the sorties that are IN_FLIGHT — no Compose, no database, no Android. Each case is the destination
# itself: which tab, whether an In-Flight sortie id is set, and which one.
echo ""
echo "=== opening: the start decision ==="

# A cold start (no saved navigation state) with one IN_FLIGHT sortie opens that sortie's In-Flight screen:
# the returned navigation carries that sortie's id, and its tab is still the Launchpad, which is where
# In-Flight is opened from and where back returns to.
t coldStartOpensTheInFlightSortie

# A cold start with nothing in flight stays on the Launchpad: no tab change and no In-Flight sortie id.
# This is the case that must not regress for someone with no sortie open, and it is what the app does today.
t coldStartWithNoneInFlightStaysOnLaunchpad

# Several IN_FLIGHT sorties — which shouldn't happen, and the brief settles it anyway — open the one with
# the highest id, not the first in the list and not the lowest.
t coldStartWithSeveralInFlightOpensTheHighestId

# Saved navigation state wins over an IN_FLIGHT sortie: the state that was saved comes back unchanged,
# whatever the read found. Covers a saved Mission tab, a saved Mission Control, and a saved Launchpad that
# already points at a different In-Flight sortie id.
# Decision (plan): a restored state pointing at a sortie that has since landed stays put, and the Launchpad
# offers Resume for the other sortie rather than the app jumping to it.
t savedStateWinsOverTheInFlightSortie

# ─── 3. Leaving: the "no longer in flight" signal ──────────────────────────────
# Brief part 2: while In-Flight is open, its sortie becoming anything other than IN_FLIGHT, or vanishing,
# makes the screen leave exactly once and stops it acting on that sortie. Driven through the view model's
# leave callback against in-memory Room on a fixed clock, the fixture InFlightLandScrubTest already uses:
# a mission confirmed on RLRLR and a launched lift sortie that is IN_FLIGHT. The callback counts into an
# AtomicInteger because Room emits on its own thread, and every wait is bounded by a timeout; after the
# count reaches 1 the test waits a short bounded time and asserts it is still 1, which is what "once, not
# twice" means. No Compose, no screen composed.
echo ""
echo "=== leaving: the no-longer-in-flight signal ==="

# The week-ended rollover when the app returns to the foreground: the manager is rebuilt on a clock past
# the week and onAppOpen() scrubs the IN_FLIGHT sortie with reason 'week ended'. The screen leaves once.
# This is the brief's own leaving case, and it is the DESIGN.md §6 safety net: a sortie left open over the
# weekend does not stay open.
t rolloverScrubLeavesTheInFlightScreenOnce

# The sortie is landed by something other than this screen: the screen leaves once.
t landElsewhereLeavesTheInFlightScreenOnce

# The sortie no longer exists: its row is deleted, the screen leaves once. A screen that keeps showing a
# deleted sortie would offer Land and Scrub for a row that is gone.
t deletedSortieLeavesTheInFlightScreenOnce

# The control case: the sortie is still IN_FLIGHT and the screen writes to it — a set checked, a sortie
# note saved (a sortie-only write, the one that only re-derives because the state flow observes the sortie
# row). Over a bounded wait the leave callback never fires, and the state flow still shows the checklist.
# Without this, a watcher that fires on any re-emission would pass the three tests above.
t noLeaveWhileTheSortieIsStillInFlight

# A Land done on this screen leaves once, not twice: the screen's own Land both calls its done callback
# and re-emits the sortie row, so the watcher fires too. One leave, and the sortie is LANDED.
t landOnThisScreenLeavesOnlyOnce

# The same for a Scrub done on this screen, on its own fixture: one leave, and the sortie is SCRUBBED.
t scrubOnThisScreenLeavesOnlyOnce

# After leaving, the screen does not act on that sortie: leave via the rollover scrub (which touches only
# the sortie row, so the sets are still open), then check a set and wait. The set is still open in the
# database — the write never happened. This is the brief's "it must not try to act on that sortie".
t afterLeavingTheScreenWritesNothing

# ─── 4. ARCHITECTURE.md ───────────────────────────────────────────────────────
# Brief Done-when: the milestone table marks M4 done, and the area map describes com.liftoff.app.ui as
# having a real In-Flight screen rather than a placeholder. Both rows stay single lines in the same
# columns; the M4 status cell uses ';'-separated clauses with no em dash inside a clause.
echo ""
echo "=== ARCHITECTURE.md ==="

uirow="$(awk -F'|' '/^\| `com\.liftoff\.app\.ui` \|/' ARCHITECTURE.md)"
if [ -z "$uirow" ]; then
    bad "the com.liftoff.app.ui row is in ARCHITECTURE.md's area map"
elif grep -qiE 'in-flight[^,;|]*real|real[^,;|]*in-flight' <<<"$uirow" \
     && ! grep -qiE 'in-flight (as a|is a|still a|remains a) placeholder' <<<"$uirow"; then
    ok "ARCHITECTURE.md describes the ui package's In-Flight as a real screen"
else
    bad "ARCHITECTURE.md describes the ui package's In-Flight as a real screen, not a placeholder"
fi

m4status="$(awk -F'|' '/^\| M4 \|/ { print $4 }' ARCHITECTURE.md)"
if [ -z "$m4status" ]; then
    bad "the M4 row is in ARCHITECTURE.md's milestone table"
elif grep -qi 'done' <<<"$m4status" && ! grep -qiE 'not started|partial' <<<"$m4status"; then
    ok "ARCHITECTURE.md marks M4 done"
else
    bad "ARCHITECTURE.md marks M4 done (its status cell still reads: $m4status)"
fi

# ─── 5. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses that this
# work is the one touching, so a regression reads as a named failure rather than a line in a log.
echo ""
echo "=== must not break ==="

# "In-Flight for lifts ... Land, the unchecked-sets confirmation ... and their tests": the land path the
# new watcher now shares with its own done callback, and the confirmation the Land button gates on.
t landWithEverySetChecked
t landRecordsUncheckedSetsAsNotDone
t doubleTapLandsOnce
t confirmationNeededOnlyWhenSetsAreOpen
t openCountCountsOpenSets
t landConfirmStatesTheOpenCountAndSingularForOneSet
t landConfirmButtonsCallConfirmOrDismiss
t noChecklistLandLandsAndCallsOnBack
# "and Scrub": In-Flight's scrub path, unchanged.
t scrubFromInFlightKeepsCheckedSetsAndStoresReason
t scrubStoresWhitespaceReasonAsNull
# "In-Flight for ... runs ... the run landing dialog": the run land path and its dialog's fields.
t runLandWithDistanceAndDurationStoresBoth
t runLandDoubleTapLandsOnce
t runScreenShowsPlanAndOffersNoteLandAndScrub
t runLandDialogLabelsDistanceWithTheConfiguredUnit
# "with notes": the exercise and sortie notes, which the leave watcher must leave alone while the sortie is
# still in flight.
t exerciseNoteSavesAndSurvivesFreshRead
t sortieNoteSavesOnPlannedSortie
t seededExerciseNoteShowsMutedLineOnCard
# "the Launchpad and Mission screens": the Launchpad's lifecycle, including the state the cold start must
# not disturb, and the Mission screen's sortie detail.
t loadingBeforeOnAppOpen
t plannedLiftShowsPlan
t inFlightAfterLaunch
t closedAfterAllSortiesLanded
t patternChipsFromMixedSortieStates
t scrubbedSortieDetailShowsReason
# "the shell's back behaviour and ShellNavTest, LiftoffShellTest and MainActivityTest": back from In-Flight
# still lands on the Launchpad, saved navigation still survives a recreation, and a cold start of the real
# MainActivity still opens on the Launchpad — the app has no IN_FLIGHT sortie there, so the new read finds
# nothing and must not move anyone.
t backFromInFlightClearsTheSortieId
t backFromInFlightWhileOnMissionGoesToLaunchpad
t selectFromInFlightGoesToLaunchpadAndClearsInFlight
t stateSurvivesSaveAndRestore
t unreadableSavedStateRestoresToLaunchpad
t appOpensOnLaunchpad
t selectedTabSurvivesRecreation
t missionControlSurvivesRecreation
t systemBackFromATabGoesToLaunchpad
t drawsEdgeToEdgeWithDarkSystemBarIcons
# "MissionManager and the domain tests": the rollover that scrubs an IN_FLIGHT sortie, and the launch rule
# that keeps at most one sortie in flight — the reason the several-in-flight case is a tie-break and not a
# choice between two live screens.
t onAppOpenRollsOverPastMission
t rolloverScrubsOpenSortiesWithWeekEnded
t launchRejectedWhileAnotherSortieInFlight
t landAdvancesToNextSortie
# "Mission Control": the screen this run must not disturb, and CLAUDE.md §2 — never 'session' for a sortie.
t showsEverySectionHeadInOrder
t neverSaysSession
# "Don't change the Room schema": the exported schema JSON still matches the database, and the sortie
# queries the cold-start read and the leave watcher sit on still behave.
t exportedV1SchemaMatchesDatabase
t historyHasOnlyLandedAndScrubbed
# "the coach package": a recorded-style reply still parses into the plan the prompt builder asked for.
t liftPlanFixtureParsesIntoTheExpectedLiftPlan

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
