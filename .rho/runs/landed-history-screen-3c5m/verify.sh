#!/usr/bin/env bash
# Verify: "Landed history screen" (brief.md; DESIGN.md §5.1 — a landed sortie "cannot be edited
# after landing, apart from its notes", and §9 — "History, newest first, grouped by week, with a
# detail view per sortie"). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports this run produced,
# then the two ARCHITECTURE.md rows the brief names.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not written cannot pass on its own. Names are matched across every report, so
# which class and package each one sits in is the implementer's call; what it must show is written above
# it. The eight names under "history list" and "detail view" are tests this run has to write. The names
# under "must not break" already exist and this run has to keep them passing.
#
# The brief's own shape is what the names follow: it asks for "the history and detail derivation in
# plain Kotlin that a Robolectric test can drive against an in-memory database", so every test this run
# has to write is a state-level test over the derivation and the Room reads, not a screen driven through
# Compose. That is also the cheapest proof of each requirement. The only tests that do compose a screen
# are the shell tests named under "must not break", which already exist and which this run has to keep
# passing.
#
# What is NOT checked here, and why: the brief says Compose UI tests are not required, so nothing in
# this script drives the Landed screen's UI. Four things can only be seen on the screen — a row tap
# opening that sortie's detail, the system back and the on-screen back both returning to the list, the
# note editor being offered from the detail view, and the visual parts the brief names (the eyebrow and
# display title, section heads, ink-ruled rows, paper cards with 2 dp ink borders, red deviations) — plus
# the §2 vocabulary on the new screen, "no Compose test may type into or focus a text field inside a
# Dialog", and "every wait in a test needs a timeout". The owner reviews those in the pull request. The
# evidence this script can supply for the screen itself is that LiftoffShellTest, which runs the real
# MainActivity against the app's own database, still reaches the Landed tab and still shows its copy —
# now the real screen's empty state rather than the placeholder line.
#
# The plan's "## Decisions" are not pinned here: which date a row shows when there is no landed time,
# which week a sortie is filed under, the order within a week, no limit from the history window, the
# note editor on a scrubbed detail too, a scrubbed sortie with no reason, and the type word for a sortie
# with no title and no focus. Where a named test also shows a decision, the comment says which.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote, into $WORK/index as
# "<method> <pass|fail|skipped>" lines, one line per method. Reading the reports once keeps this cheap
# even with many names below. Every report is read, so the new tests can sit in any package and any
# class. A method that ran several times (a parameterized test, or the same name in two classes) is
# recorded once, at its worst outcome: a failure anywhere is a failure.
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
                cur = name; pend = 1; st = "pass"
                if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) st = "fail"
                else if ($0 ~ /<skipped/) st = "skipped"
                if ($0 ~ /\/>[ \t]*$/ || $0 ~ /<\/testcase>/) { rec(cur, st); pend = 0; cur = "" }
                next
            }
            pend && /<(failure|error)[ \t\r\n>\/]/ { st = "fail" }
            pend && /<skipped/ { if (st == "pass") st = "skipped" }
            pend && /<\/testcase>/ { rec(cur, st); pend = 0; cur = "" }
            END {
                for (n in best)
                    print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "pass" : "skipped")
            }
        ' "$file" >> "$WORK/raw"
    done
    # One line per method, at its worst outcome across every report that named it.
    awk '
        function rank(s) { return (s == "fail") ? 3 : (s == "pass") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "pass" : "skipped") }
    ' "$WORK/raw" > "$WORK/index"
}

# t <method>: the named test must have run in this invocation and passed.
t() {
    local method="$1" st
    st="$(awk -v m="$method" '$1 == m { print $2; exit }' "$WORK/index" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$method" ;;
        fail)    bad "$method ran but did not pass" ;;
        skipped) bad "$method was skipped" ;;
        none)    bad "$method did not run" ;;
    esac
}

# ─── 1. CI gate: the two tasks .github/workflows/ci.yml runs ──────────────────
# This is also the "must not break" check for everything the brief lists — In-Flight, the Launchpad and
# Mission screens, the shell and its back behaviour, MissionManager and the domain tests, Mission Control,
# the coach package, and the build gate — since CI runs the whole unit-test suite. It is also what catches
# a placeholder assertion left behind when the Landed placeholder goes.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed,
# which would read as a suite that passed.
# The brief warns a full gate can outlast a shell command's limit; the timeout is how this script waits
# for it. A suite that never finishes is a failure, not a run that is still going.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

run_gate() {
    rm -rf "$RESULTS" 2>/dev/null || true
    if [ -d "$RESULTS" ]; then
        echo "  ✗ could not clear $RESULTS — a stranded Gradle test executor is holding it; run: bash gradlew.sh --stop" >&2
        return 2
    fi
    timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$1.log"
}

gate_ok=0
if run_gate 1; then gate_ok=1; fi

# A daemon that was killed, or a build that ran out of memory, is not a test failure and says nothing
# about the brief. It is retried once, after stopping whatever daemon is left. A build that failed to
# compile, or a suite that ran and failed, is not retried: its reports are already there.
if [ "$gate_ok" -eq 0 ] && ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1 \
   && grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon' "$WORK/gate-1.log" 2>/dev/null; then
    echo "  … the build died before it reached the tests; stopping daemons and running the gate once more"
    bash gradlew.sh --stop >/dev/null 2>&1 || true
    if run_gate 2; then gate_ok=1; fi
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
else
    index_reports
fi

# ─── 2. History list ──────────────────────────────────────────────────────────
# Brief "List": newest first, grouped by week under heads like 'WEEK OF OCT 5', each row showing the date,
# the type, the plan title or focus, and whether the sortie landed or was scrubbed, with the reason on a
# scrubbed row; an empty history shows a short empty state; the list updates live from Room. Driven over
# in-memory Room through the plain derivation, the fixture MissionStatesTest already uses: a fixed clock
# and zone, a Mission per week (weekStart is UNIQUE, so insert each one once), and the mission made ACTIVE
# before anything lands or scrubs (onAppOpen gives a DRAFT and advance() rejects a draft).
echo ""
echo "=== history list ==="

# The brief's grouping and ordering case, across two weeks: two Missions in different weeks, several LANDED
# sorties and a SCRUBBED one in each. It must show the weeks newest first, a head per week reading
# 'WEEK OF <date>', the rows inside a week newest first, and — on each row — its date, its type (run or
# lift), its title (the plan's title where the sortie has a plan, its focus where it does not), and whether
# it landed or was scrubbed, with the scrubbed row carrying its reason.
# Decision 2 (date): landedAt, else launchedAt, else the Mission's weekStart — the rule the app already
# uses for a finished sortie, so a scrubbed sortie with no timestamp still carries a date.
# Decision 3 (which week): the Mission's weekStart, which is also what observeHistory() orders by.
# Decision 4 (order within a week): the DAO's index DESC.
# Decision 11 (no title and no focus): the type word.
t historyGroupsByWeekNewestFirstAcrossTwoWeeks

# A history with no LANDED or SCRUBBED sortie reads as the empty state, not as a list with no rows in it
# and not as "still loading" forever.
t emptyHistoryShowsTheEmptyState

# The list is a live Room read: a sortie that is not in the history when the read starts appears in a later
# emission of the same flow after it is landed, without restarting anything.
t historyUpdatesWhenASortieIsLanded

# ─── 3. Detail view ───────────────────────────────────────────────────────────
# Brief "Detail view": a landed lift shows each exercise with every set's planned and actual values,
# deviations in red, skipped / not-done / added sets marked, and the exercise and sortie notes; a landed run
# shows the plan summary and the recorded distance and duration in the configured units; a scrubbed sortie
# shows its reason and any sets checked before the scrub; a landed sortie can't be edited except its note.
# Driven through the plain derivation over in-memory Room, the way InFlightStatesTest drives deriveInFlight
# on a fresh getPlan read. Every wait is bounded by a timeout.
echo ""
echo "=== detail view ==="

# A landed lift: a DONE set recorded with different reps and a different weight than planned reads as an
# actual that differs from its planned label, flagged on the weight and on the count; a DONE set recorded
# exactly as planned reads as no deviation on either. The two cases are what "deviations are shown in red"
# is built from, so the flags are what this test asserts.
# Decision 8 (what counts as a deviation): a DONE set whose recorded value differs from its planned value,
# compared field by field; a set that was skipped or not done is not a deviation.
t liftDetailShowsPlannedAndActualValuesAndFlagsDeviations

# A landed lift built through the real flow — launch, check some sets, skip one exercise, add a set to
# another, leave at least one set unchecked, then land: the skipped exercise reads as skipped with its sets
# marked SKIPPED, the set left unchecked reads as NOT_DONE, and the added set reads as added. It also
# covers a set still OPEN on a landed sortie reading as NOT_DONE.
# Decision 1 (skipped vs not done): SKIPPED inside an exercise whose skipped flag is set is "skipped";
# every other SKIPPED or OPEN set on a landed sortie is "not done", because land() rewrites every unchecked
# set to SKIPPED (MissionManager.markOpenSetsNotDone) and the Land confirmation promises "recorded as not
# done". A test that only ever sees SKIPPED rows would pass with the opposite rule, so the unchecked set
# must come from a real land, not from a hand-written row.
t liftDetailMarksSkippedNotDoneAndAddedSets

# A landed run: its recorded distance and duration, in the units the app is configured with — the same
# sortie read again after the distance unit is changed shows the other unit, so the value is formatted from
# the setting and not baked in.
# Decision 9: the run's own summary is formatted with the shared distance/minutes helpers, so every distance
# on the screen honours the configured unit rather than a hardcoded kilometre format.
t runDetailShowsRecordedDistanceAndDurationInConfiguredUnits

# A scrubbed sortie: its reason, and only the sets that were actually checked before the scrub — a plan with
# DONE, SKIPPED and OPEN sets shows its DONE sets and none of the others, with the checked-of-total counts.
# This is DESIGN.md §5.1: scrubbing "keeps the sets already checked as a partial record".
t scrubbedDetailShowsReasonAndOnlyTheSetsCheckedBeforeTheScrub

# The one thing a finished sortie can still be edited: its note. Saving a note on a landed sortie is written
# back and comes back through the same detail read; saving it blank clears it rather than leaving the old
# text on screen. No set, exercise or plan value is written by anything on this screen — §5.1.
# Decision 7 (a blank note): it clears the note.
t editingTheNoteOfALandedSortieWritesItBack

# ─── 4. ARCHITECTURE.md ───────────────────────────────────────────────────────
# Brief Done-when: ARCHITECTURE.md marks Landed as built under M5. The area map's com.liftoff.app.ui row
# still calls Landed a placeholder; the M5 status cell still reads "Not started". The wording inside the
# two rows is the implementer's.
echo ""
echo "=== ARCHITECTURE.md ==="

# The area-map row for com.liftoff.app.ui, read as one line. It must stop calling Landed a placeholder.
# Only the word "placeholder" next to Landed is rejected — the row still legitimately calls Mission Control
# a stub, and that must not read as a failure.
uirow="$(awk '/^\| `com\.liftoff\.app\.ui` \|/ { print; exit }' ARCHITECTURE.md | tr -d '\r')"
if [ -z "$uirow" ]; then
    bad "the com.liftoff.app.ui row is in ARCHITECTURE.md's area map"
elif grep -qiE 'landed[^,;|]*placeholder' <<<"$uirow"; then
    bad "ARCHITECTURE.md no longer calls the ui package's Landed a placeholder (its row reads: $uirow)"
else
    ok "ARCHITECTURE.md's ui area map no longer calls Landed a placeholder"
fi

# The M5 status cell, which currently reads "Not started". M5 also covers Re-fly and export/import, which
# this run does not build, so a cell that says only "Done" would be false: it has to name the Landed
# history as the part that exists. Any wording inside the cell is the implementer's — no delimiter is
# required — but the cell must name the Landed history and say that part is built.
m5status="$(awk '/^\| M5 \|/ { print; exit }' ARCHITECTURE.md | awk -F'|' '{ print $4 }' | tr -d '\r')"
if [ -z "$m5status" ]; then
    bad "the M5 row is in ARCHITECTURE.md's milestone table"
elif grep -qiE '^[[:space:]]*not started[[:space:]]*$' <<<"$m5status"; then
    bad "ARCHITECTURE.md marks Landed as built under M5 (its status cell still reads: $m5status)"
elif grep -qiE 'landed|histor' <<<"$m5status" \
  && grep -qiE 'done|built|exists|exist|shipped|partial|complete|progress|real|✅' <<<"$m5status"; then
    ok "ARCHITECTURE.md marks the Landed history built under M5"
else
    bad "ARCHITECTURE.md's M5 status cell names the Landed history as built (it reads: $m5status)"
fi

# ─── 5. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above, so a regression anywhere fails the run. These name the
# brief's own "must not" clauses that this work is the one touching, so a regression reads as a named
# failure rather than a line in a log. All of them exist today; this run has to keep them passing and
# keep their names.
echo ""
echo "=== must not break ==="

# "the shell and its back behaviour": the shell still opens on the Launchpad, still reaches every tab from
# the bottom bar, and its back still goes from a tab to the Launchpad. Three of these reach the Landed tab
# and assert on what it shows, so they are also the shell-side evidence that the real screen is there:
# their Landed assertions have to be updated to the real screen's copy — the empty state, because the
# shell's fixture has no finished sorties — in the same commit that replaces the placeholder.
t appOpensOnLaunchpad
t bottomBarReachesEveryTab
# The third test that reads the Landed tab's copy — the one that exists today to assert the placeholder
# line and the "HISTORY" eyebrow — is deliberately not named here: its name is about a placeholder this run
# removes, so renaming it is the right thing to do and naming it would punish that. The gate runs it
# whatever it is called, and its Landed assertions have to change in the same commit that replaces the
# placeholder.
t systemBackFromATabGoesToLaunchpad
# Opened from the Landed tab, so both of its Landed assertions have to change with the placeholder.
t missionControlBackButtonReturnsToTabItWasOpenedFrom
# ShellNav itself is untouched by this run: back from the Landed tab still goes to the Launchpad.
t backFromMissionOrLandedGoesToLaunchpad

# "In-Flight": the checklist, the Land and Scrub paths, the run state, and the note dialog the Landed
# detail view reuses.
t seededLiftPlanShowsHeaderProgressAndCards
t landWithEverySetChecked
t scrubFromInFlightKeepsCheckedSetsAndStoresReason
t runDistanceUnitChangeUpdatesStateLive
t prefilledFieldSaveHandsBackEditedText

# "the Launchpad and Mission screens": the Mission tab's rows and its thin detail views, which this run
# deliberately leaves as they are.
t liveUpdateThroughStates
t sortieRowsShowIndexTypeFocusState
t landedSortieDetailShowsRecord
t scrubbedSortieDetailShowsReason

# "MissionManager and the domain tests": the landing and scrubbing rules the Landed history is built on —
# the unchecked sets land() rewrites, the landed time and the run's recorded distance and minutes a row is
# dated and valued by, and the scrub that keeps the sets already checked.
t landMarksUncheckedSetsNotDone
t landRecordsLandedTime
t landStoresRunDistanceAndRunMinutes
t scrubInFlightKeepsCheckedSets

# "Mission Control": the screen this run must not disturb, and CLAUDE.md §2 — never 'session' for a sortie.
t showsEverySectionHeadInOrder
t neverSaysSession

# "Don't change the Room schema": the exported schema JSON still matches the database, no delete has
# appeared on the DAOs this run reads through, and the history query the list sits on still behaves.
t exportedV1SchemaMatchesDatabase
t noWipeForMissionsAndOnlyTheByIdDeleteForSorties
t historyIsNewestFirstByWeekThenIndex

# The shared formatting helpers the Landed derivation reads from, unchanged: the week head's date and the
# distance in the configured unit.
t formatWeekDateOCT5
t formatDistanceWholeKilometre

# "the coach package": the coach already marks a set that was skipped and a set still open on a scrubbed
# sortie as not done, the same two marks this screen uses. This run must not reach into the coach to
# change either.
t skippedSetsAreMarkedSkipped
t openSetsOnAScrubbedSortieReadNotDone

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
