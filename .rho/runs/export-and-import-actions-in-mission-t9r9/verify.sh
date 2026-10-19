#!/usr/bin/env bash
# Verify: "Export and Import actions in Mission Control" (brief.md; DESIGN.md §8 'Export and import',
# §10 'Lost or reset phone'; design/README.md; CLAUDE.md §2 vocabulary). Definition of done for this run:
# exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest),
# then every named test read out of the JUnit XML reports this run produced, then the two ARCHITECTURE.md
# rows the brief names.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not written cannot pass on its own. Names are matched across every report, so
# which class and package each one sits in is the implementer's call; what it must show is written above
# it. The ten names under "the backup work", "the import reaches Room" and "Mission Control's BACKUP
# section" are tests this run has to write. The names under "must not break" already exist and this run has
# to keep them passing; showsEverySectionHeadInOrder is among them and may be updated to list BACKUP.
#
# The brief's own shape is what the names follow: "Keep the busy/result logic in plain Kotlin outside
# Compose, so a test can drive it with fake work", so the busy/result tests are driven with fake work and
# an injected dispatcher, with no Compose and no Android; the brief's second Done-when bullet asks for a
# Robolectric Compose test of the BACKUP card, so those tests are named too. No test here clicks a system
# file picker, which the brief forbids: the screen tests assert the buttons are present and enabled and
# drive the work through the view model.
#
# What is NOT checked here, and why: that Export opens the system picker to create a .json file and Import
# opens it to choose a file — the only way to see a picker is to open one, which the brief rules out of a
# test; the visual parts (a section head, ink-ruled rows, buttons from ui.theme, no stock M3 tonal
# surfaces), which no test here can judge; that the export stream is opened in truncate mode; and the exact
# wording of the three result rows. The owner reviews those in the pull request. The evidence this script
# can supply for the wiring is LiftoffShellTest, which composes the real shell against AppContainer's own
# database and so runs the real Mission Control with the pieces the shell hands it.
#
# The plan's "## Decisions" are not pinned here: BACKUP sitting last rather than first, both buttons going
# quiet while work runs (and refusing a second action), Import staying enabled with no confirmation dialog,
# a failed export leaving a partial file, settings not being restored. Where a named test also shows a
# decision, the comment above it names it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch stays inside the build directory: it is gitignored and it survives a long gate.
WORK="app/build/verify-run"
rm -rf "$WORK" 2>/dev/null || true
mkdir -p "$WORK"

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote, into $WORK/methods and $WORK/classes as
# "<name> <pass|fail|skipped>" lines, one line per name, at its worst outcome across every report. Handles
# self-closing testcases, a <failure> on a later line, and name="method[params]".
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
# Brief Done-when: "./gradlew :app:assembleDebug :app:testDebugUnitTest passes (use bash gradlew.sh on this
# machine)". This is also the "must not break" check for everything the brief lists — Mission Control's
# settings and equipment editing and their view models, the shell, the export/import data tests, the other
# screens and the coach package — since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run; --no-build-cache stops the
# reports being restored FROM-CACHE with no test executed. A suite that never finishes is a failure, not a
# run that is still going: the brief warns a full gate can outlast a shell command's limit, and this timeout
# is how the script waits for it.
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

# The known flake (doneBodyweightSetStepperShowsBW, a closed Room connection pool) and a killed daemon or a
# build that could not delete its own output are the only cases that get a second gate. A real assertion
# failure is not retried.
transient_failure() {
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then return 0; fi
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|Unable to delete directory' "$WORK"/gate-*.log 2>/dev/null; then
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

# ─── 2. The backup work: busy, result, and the thread it runs on ───────────────
# Brief: "Both run off the main thread and show that they are working while they run. The result shows as a
# row that stays until the next action … Cancelling the picker does nothing." The brief puts this logic "in
# plain Kotlin outside Compose, so a test can drive it with fake work", so each test below hands it fake
# work and a dispatcher it names, and none of them composes a screen.
echo ""
echo "=== the backup work ==="

# Brief: "Both run off the main thread." Drive the export action and the import action with fake work on a
# dispatcher the test names, and record the thread each step ran on: the stream opener and the export or
# import call must both have run on that dispatcher's thread, never on the thread that called the action.
t backupWorkRunsOffTheCallingThread

# Brief: "show that they are working while they run" and "after an export, that it was saved". Hold the fake
# export on a latch so the state can be read while it is still going: the state must first say work is
# running, and after the latch is released it must say the export was saved. Every latch wait has a
# timeout, and the latch is released whatever the outcome.
t exportShowsBusyThenThatItWasSaved

# Brief: "after an import, how much was restored". Same shape for the import action: busy while the fake
# import is parked, then a result that names how many missions and how many sorties were restored, read off
# what the fake returned. Decision: the counts are what the row says, singular for a count of one.
t importShowsBusyThenHowMuchWasRestored

# Brief: "after a failure, the data layer's message". Give the import action a refusal from the data layer
# and show the state going to a failure carrying that message unchanged — not a reworded version of it, not
# an exception.
t refusedImportShowsTheDataLayersMessageUnchanged

# Brief: a failure shows as a row, so work that throws must not escape to the caller or leave the state
# stuck saying work is running. Make the fake export throw, and make the stream opener fail (no stream at
# all), and show each one ending as a failure with a message a person can read. Decision: a failure the
# data layer does not word itself gets this app's own short message rather than the raw exception text.
t workThatThrowsBecomesAFailureRowNotACrash

# Brief: "Cancelling the picker does nothing." Hand the action no picked file at all and show the state
# exactly as it was — no busy state, no new row, and an earlier result still there. Decision: a cancel is
# not an action, so it must not wipe a message the owner may still be reading.
t aCancelledPickerChangesNothing

# Brief: "The default name carries today's local date, like liftoff-2026-10-19.json". Show the name Export
# would suggest for the file the picker creates: it ends in .json and its date is today's date on the
# device, not today's date in UTC. No test may open a picker to show this.
t theDefaultExportFileNameCarriesTodaysLocalDate

# ─── 3. The import reaches Room ────────────────────────────────────────────────
# Brief: "After a successful import, the other tabs show the restored data. They read Room through flows, so
# make sure the import goes through Room, not around it." Export from a seeded in-memory database, then run
# the backup import action with the real data-layer import into a fresh in-memory database while a Room
# flow of the kind the other tabs observe is being collected, and show that flow emits the restored rows.
# An import that wrote around Room leaves the flow silent. This needs Room, not Compose: no screen is
# composed, and every wait has a timeout.
echo ""
echo "=== the import reaches Room ==="

t anImportThroughTheBackupActionReachesRoomsFlows

# ─── 4. Mission Control's BACKUP section ───────────────────────────────────────
# Brief Done-when: "A Robolectric Compose test shows the BACKUP head, Export and Import enabled, the
# exclusion line, and a success and a failure row drawn from the state." These are the brief's own UI
# requirement, so they are driven through the screen. No test clicks Export or Import: the brief forbids
# clicking a system picker, so the rows are reached by handing the screen's view model a stream or a state.
echo ""
echo "=== Mission Control's BACKUP section ==="

# Brief: "A BACKUP section in Mission Control with an Export button and an Import button" and "a line saying
# settings and the daemon token are not in the file". Show the BACKUP head on screen, both buttons on screen
# and enabled — present alone is not enough, since a button that cannot be pressed is not an action — and
# the line that says settings and the daemon token are not in the backup file.
t backupShowsExportAndImportEnabledAndTheExclusionLine

# Brief: "The result shows as a row". Hand the screen's backup state a success and then a failure and show
# each one drawn as a row on the card, with the message the state carries. After each one, idle the screen
# again and show the row is still there: it stays until the next action, not just until the next
# recomposition.
t successAndFailureRowsAreDrawnFromTheState

# ─── 5. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief Done-when: "M1's note no longer lists export/import as missing, and M5 says export/import is built."
# Read off the two milestone rows the brief names, clause by clause (split on the clause punctuation this
# document uses, including the em dash). A clause counts as recording export/import as built when it names
# the feature, calls it built, carries no deferral, and does not narrow the claim to the data layer alone
# unless the same clause also credits the actions. A clause that defers the actions in a cell that names
# export/import is the "still missing" state the brief asks to be removed. The wording inside the cells is
# the implementer's.
echo ""
echo "=== ARCHITECTURE.md ==="

names_the_feature() { # export and import named together, or backup and restore named together
    local text="$1"
    { grep -qiE 'export' <<<"$text" && grep -qiE 'import' <<<"$text"; } \
      || { grep -qiE 'backup' <<<"$text" && grep -qiE 'restor' <<<"$text"; }
}

has_deferral() { grep -qiE 'not started|not built|not yet|unbuilt|missing|come next|comes next|coming next|later|to come|remaining|upcoming|not implemented|not shipped|await|awaiting' <<<"$1"; }
has_builtin()  { grep -qiE 'built|exists|exist|existing|in place|shipped|complete|completed|implemented|done|added|landed|available' <<<"$1"; }
names_actions() { grep -qiE 'action|button|\bui\b|screen|mission control|menu' <<<"$1"; }
narrows_to_data_layer() { grep -qiE 'data layer|data-layer|only|partly|partial' <<<"$1"; }

# built_clause <cell>: some clause of the cell records export/import as built, with no deferral and without
# narrowing the claim to the data layer on its own.
built_clause() {
    local clause
    while IFS= read -r clause; do
        names_the_feature "$clause" || continue
        has_builtin "$clause" || continue
        has_deferral "$clause" && continue
        if narrows_to_data_layer "$clause" && ! names_actions "$clause"; then continue; fi
        return 0
    done < <(sed 's/[;,.—–]/\n/g' <<<"$1")
    return 1
}

# deferred_clause <cell>: some clause still defers export/import, or defers the actions in a cell that names
# export/import — the state the brief asks to be removed. A clause that also calls it built is not a
# deferral, so "the Launchpad's PENDING action are in place" is not one.
deferred_clause() {
    local cell="$1" clause
    while IFS= read -r clause; do
        has_deferral "$clause" || continue
        has_builtin "$clause" && continue
        if names_the_feature "$clause" || { names_actions "$clause" && names_the_feature "$cell"; }; then
            return 0
        fi
    done < <(sed 's/[;,.—–]/\n/g' <<<"$cell")
    return 1
}

cell() { # the status cell of one milestone row: the 4th field of the |-split line
    local prefix="$1"
    awk -v p="$prefix" 'index($0, p) == 1 { print; exit }' ARCHITECTURE.md \
        | awk -F'|' '{ print $4 }' | tr -d '\r'
}

m1status="$(cell '| M1 |')"
if [ -z "$m1status" ]; then
    bad "the M1 row is in ARCHITECTURE.md's milestone table"
elif built_clause "$m1status" && ! deferred_clause "$m1status"; then
    ok "ARCHITECTURE.md's M1 note no longer lists export/import as missing"
else
    bad "ARCHITECTURE.md's M1 note records export and import as built, not as a data layer whose actions are still to come (its status cell reads: $m1status)"
fi

m5status="$(cell '| M5 |')"
if [ -z "$m5status" ]; then
    bad "the M5 row is in ARCHITECTURE.md's milestone table"
elif built_clause "$m5status" && ! deferred_clause "$m5status"; then
    ok "ARCHITECTURE.md's M5 status says export/import is built"
else
    bad "ARCHITECTURE.md's M5 status records export and import as built, with no clause deferring its Mission Control actions (its status cell reads: $m5status)"
fi

# ─── 6. must not break ─────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses that this
# work is the one touching, so a regression reads as a named failure rather than a line in a log.
echo ""
echo "=== must not break ==="

# "settings and equipment editing in Mission Control": the screen this run adds a card to, and its two view
# models. The screen test is the one this run edits (its launch helper, its heads list, and the assertion
# that Export did not exist), so the whole class has to be green, not just the new tests in it.
c MissionControlScreenTest
c MissionControlViewModelTest
c EquipmentViewModelTest

# The existing heads test, which the brief allows to be updated to list BACKUP; every head that was there
# before is still there.
t showsEverySectionHeadInOrder

# The settings and equipment behaviour the brief names, by name rather than only as a class.
t opensShowingStoredValues
t invalidPortShowsErrorMessage
t typingValidValueSavesWithoutSaveButton
t patternButtonsAndChipTapEditPattern
t patternButtonsDisabledAtLimits
t tappingUnitSegmentsSavesUnits
t tappingGenerateRunPlansRowTogglesSetting
t tokenRevealButtonShowsAndHidesToken
t editPersistsNameAndNotes
t deactivateMovesToLists
t reactivateReturnsToActive
t changesPersistAcrossViewModels

# "the shell": Mission Control still opens from the sliders button, still shows its eyebrow and title, still
# returns to the tab it was opened from, and still survives a recreation now that the shell hands it the
# database and MissionManager from its own container.
c LiftoffShellTest
t slidersButtonOpensMissionControlWithoutBottomBar
t missionControlShowsSettingsEyebrowAndTitle
t systemBackFromMissionControlReturnsToTabItWasOpenedFrom
t missionControlSurvivesRecreation

# "the export/import data tests": the data layer is not to be touched, so its own tests still pass.
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest
c SchemaExportTest
t exportWritesEveryRowOfEveryTableWithIdsAndBothVersions
t importRefusesANonEmptyDatabaseAndChangesNothing
t importReportsHowMuchWasRestored
t exportThenImportIntoAFreshDatabaseGivesAnIdenticalDatabase

# "the other screens" and CLAUDE.md §2: Mission Control's new wording must not say "session" for a sortie.
t neverSaysSession
t appOpensOnLaunchpad
t landedScreenShowsItsEmptyStateCopy

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
exit 0
