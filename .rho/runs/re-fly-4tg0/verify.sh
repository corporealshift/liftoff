#!/usr/bin/env bash
# Verify: "Re-fly" (brief.md; DESIGN.md §5.4 and §13). Definition of done for this run: exit 0 only
# when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports that run produced,
# then the ARCHITECTURE.md M5 row the brief names.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not written cannot pass on its own. Names are matched across every report, so
# which class and package each one sits in is the implementer's call; what it must show is written above
# it. The names under "the chooser", "the copy" and "the Launchpad" are tests this run has to write. The
# names under "must not break" already exist and this run has to keep them passing.
#
# The brief's own shape is what the names follow: it asks for pure unit tests over the chooser, and
# Robolectric tests for the copy through MissionManager and for the Launchpad's PENDING state. So the
# chooser cases are plain JUnit over the pure function — no Room, no Compose — and the copy cases are
# driven through MissionManager against in-memory Room, which is where its guards, its transaction and
# its state change live. Only three of the Launchpad names are driven through the screen, because the
# brief asks for the action to be shown there; the other two read the availability decision off the
# PENDING state itself, which is the cheaper proof and the one place a later brief can limit Re-fly.
#
# What is NOT checked here, and why: the visual parts the brief names — the Re-fly action being
# underlined and sitting next to Scrub, the muted styling of the line that replaces it — are read off the
# screen by the three screen names below only as "the control is there / is not there" and "a line is
# there"; how they are drawn is the owner's review. The §2 vocabulary (never 'session' for a sortie),
# LF endings, and "every wait in a test needs a timeout" are not machine-checked. ARCHITECTURE.md's
# package-layout rows are not pinned either: the brief's Done-when names only the M5 row.
#
# The plan's "## Decisions" get no tests of their own: the words of the muted line, and the choice not to
# record which sortie was copied beyond source = REFLY, are the owner's review. Where a named test also
# shows a decision, the comment above it names that decision.
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
# This is also the "must not break" check for everything the brief lists — In-Flight, Landed, the
# Launchpad and Mission screens, MissionManager and the domain tests, Mission Control, the coach package,
# and the build gate — since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed,
# which would read as a suite that passed.
# The brief warns a full gate can outlast a shell command's limit; the timeout is how this script waits
# for it. A suite that never finishes is a failure, not a run that is still going.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

bash gradlew.sh --stop >/dev/null 2>&1 || true

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

# ─── 2. the chooser ───────────────────────────────────────────────────────────
# Brief "Choosing the source": a pure function picks the source plan for a PENDING sortie — the most
# recent LANDED sortie of the same type that has a plan, preferring one whose focus matches the current
# sortie's focus and otherwise falling back to any of the same type, and nothing when there is no such
# plan. DESIGN.md §13's Re-fly cases. Plain JUnit over the pure function, on hand-built candidates: no
# Room and no Compose, because the ordering and the focus preference are pure decisions and this is the
# cheapest place to show each one.
echo ""
echo "=== the chooser ==="

# Same type and same focus beats a plan of the same type that landed more recently but carries a
# different focus. The fixture has to hold both, or a chooser that ignores focus entirely would pass.
t reflyPrefersSameTypeAndFocusOverAMoreRecentDifferentFocusPlan

# With no focus match anywhere, the most recent plan of the same type is used — and a plan of the other
# type is never used, however recent or however well its focus reads.
# Decision 2 (either side has no focus): it counts as no match, so the fallback is the most recent plan
# of the same type. The case that pins it is a current sortie with no focus at all, or a candidate with
# no focus: neither may be chosen over a same-type plan just for being blank. Matching is exact text
# after trimming and case-folding, so '5k' and '5K' do match.
t reflyFallsBackToTheMostRecentSameTypeWhenNoFocusMatches

# Among focus-matching candidates, the one that landed last is chosen — including when the more recently
# landed candidate's plan row was written earlier than the older one's.
# Decision 1 (what "most recent" means): the candidate sortie's landed time, not the moment its plan row
# was last written. A LANDED candidate with no landed time sorts as oldest rather than guessing.
t reflyChoosesTheMostRecentlyLandedOfTheFocusMatches

# A SCRUBBED sortie is never a source, even when it is the only finished sortie of that type and its
# plan is intact; and a LANDED sortie with no plan row is never a source either.
t reflyIgnoresScrubbedSortiesAndFinishedSortiesWithNoPlan

# Nothing of that type has ever landed with a plan: the chooser returns nothing. It must say nothing,
# not fall back to a plan of the other type and not return a candidate it cannot copy.
t reflyReturnsNothingWhenNoLandedPlanOfThatTypeExists

# ─── 3. the copy ──────────────────────────────────────────────────────────────
# Brief "Copying": a MissionManager operation copies that plan onto the current PENDING sortie in one
# transaction — planned values only, never actuals; no added sets; skipped flags and user notes cleared;
# a run's run fields and segments copied; the copy's source is REFLY and the sortie becomes PLANNED; no
# network; and a sortie that isn't the current PENDING one is refused. Driven through MissionManager
# against in-memory Room, the fixture MissionManagerTest already uses: a fixed clock and zone, the
# mission made ACTIVE (onAppOpen gives a DRAFT and the guards reject a draft), and a source plan that
# really carries actuals, an added set and a skip. Every wait is bounded by a timeout.
echo ""
echo "=== the copy ==="

# A source whose sets are all DONE with actuals that differ from what was planned: the copy carries the
# planned reps / seconds / weight, no actual values on any set, and every set OPEN. It also points at the
# same exercises as the source — the same exercise ids, in the same order, with the same equipment keys,
# rest seconds and coach notes — and carries the source plan's own fields (title, estimated minutes,
# warmup, notes). A copy that re-resolved a display name onto a different exercise would read as the
# same plan and be the wrong plan.
t reflyCopiesPlannedValuesAndNotActuals

# A set added mid-flight in the source plan is absent from the copy, and the copy's remaining sets keep
# their order.
t reflyDoesNotCopyAddedSets

# An exercise the source skipped, with a user note on it, arrives in the copy not skipped, with no user
# note, and with its sets open rather than SKIPPED.
t reflyClearsSkippedFlagsAndUserNotes

# A landed run source: the copy carries its run kind, target distance and target pace, and its segments
# with their descriptions and distances and minutes.
t reflyCopiesRunFieldsAndSegments

# The copy's plan is marked REFLY, and afterwards the sortie is PLANNED — through the legal
# PENDING → PLANNED transition, with the sortie otherwise unchanged. The source sortie is still LANDED
# with its plan exactly as it was: same plan row, same exercises, same sets, same actuals.
# Decision 4 (no provenance): the copy carries source = REFLY and nothing naming the sortie it came
# from, because recording that would need a Room schema change, which the brief forbids.
t reflyMarksTheCopyReFlyAndMakesTheSortiePlanned

# The copy completes with no daemon configured and nothing injected, and writes no Generation row — no
# row for this sortie, and none for the source. It is offline by what it reads, not by skipping a call.
t reflyNeedsNoNetworkAndWritesNoGenerationRow

# A sortie that is not the current one is refused: a finished one, and a later not-yet-current sortie in
# the same mission. Each refusal leaves that sortie in the state it was in and writes no plan for it.
t reflyRefusesASortieThatIsNotTheCurrentOne

# The current sortie when it is PLANNED or IN_FLIGHT is refused. Each leaves the sortie in its state
# with no plan written or changed, and nothing else in the mission moved either.
t reflyRefusesASortieThatIsNotPending

# ─── 4. the Launchpad ─────────────────────────────────────────────────────────
# Brief "Launchpad": on the PENDING state, show an underlined Re-fly action next to Scrub when a source
# plan exists; when none exists, say so in a short muted line instead of showing a dead button. Coach
# generation isn't wired yet, so Re-fly is offered on any PENDING sortie. The first two names are the
# availability decision read off the PENDING state itself, which is where a later brief will limit Re-fly
# to failed or offline generation; the rest are the screen the user sees.
echo ""
echo "=== the Launchpad ==="

# The PENDING state reports a source as available when a LANDED sortie of the same type has a plan —
# including the ordinary path there: confirm a mission, land the first sortie with a plan, and read the
# PENDING state of the sortie that follows.
t pendingOffersReFlyWhenALandedPlanOfTheSameTypeExists

# The PENDING state reports no source in each of the three ways that can happen: nothing has ever landed
# with a plan; the only finished sortie of that type was scrubbed; the only landed plan is of the other
# type.
t pendingOffersNoReFlyWithoutAMatchingLandedPlan

# The PENDING screen shows a Re-fly control alongside Scrub when a source exists — Scrub is still there
# and still works.
t pendingScreenShowsReFlyBesideScrubWhenASourceExists

# The PENDING screen with no source shows no Re-fly control at all — not a disabled one — and shows a
# short line saying why. Scrub is still there.
# Decision 5 (no source plan): a short line in the action row where the button would have been, rather
# than a dead control. Its words are not pinned.
t pendingScreenShowsAMutedLineAndNoReFlyWhenNoSourceExists

# One tap on Re-fly, with no confirmation step in between, writes the REFLY plan onto the current
# PENDING sortie and the Launchpad moves to its Planned state showing the copied plan's title. The
# source plan in history is untouched.
# Decision 3 (one tap, no confirm dialog): tapping Re-fly acts immediately; only Scrub asks.
t tappingReFlyCopiesThePlanAndMovesTheLaunchpadToPlanned

# ─── 5. ARCHITECTURE.md ───────────────────────────────────────────────────────
# Brief Done-when: ARCHITECTURE.md marks Re-fly as built under M5, with export/import still to come.
# The M5 status cell currently reads "Partial: the Landed history list and its detail view are built;
# Re-fly and export/import not started". The wording inside the cell is the implementer's.
echo ""
echo "=== ARCHITECTURE.md ==="

m5status="$(awk '/^\| M5 \|/ { print; exit }' ARCHITECTURE.md | awk -F'|' '{ print $4 }' | tr -d '\r')"
if [ -z "$m5status" ]; then
    bad "the M5 row is in ARCHITECTURE.md's milestone table"
else
    # The cell is read clause by clause: ';' '.' and an em dash end a clause, a comma does not, so a
    # parenthetical list inside a clause stays inside it. Not-built phrases ("not started", "not yet
    # built", "missing", "to come", "remaining", …) are struck out before looking for a built word, so
    # "not done" is not read as "done". A clause naming Re-fly counts as built when a built word
    # survives the strike-out, and as missing when it does not and a not-built phrase was there; a
    # clause naming Re-fly with neither says nothing either way. Any clause calling Re-fly missing
    # outweighs one calling it built. export/import must appear in a clause that calls it missing.
    verdict="$(awk -v cell="$m5status" 'BEGIN {
        c = tolower(cell)
        gsub(/—|–/, ";", c)
        n = split(c, clause, /[;.]/)
        nbre = "not (yet )?(started|built|done|shipped|implemented|covered|present|available|wired|in place)|not yet|missing|unbuilt|pending|to come|to do|todo|later|remaining|next|outstanding|no [^;.]*yet|❌|⏳"
        bre  = "built|done|shipped|exists|exist|real|complete|wired|in place|available|✅"
        rfBuilt = 0; rfMissing = 0; expMissing = 0
        for (i = 1; i <= n; i++) {
            s = clause[i]
            miss = (s ~ nbre)
            t = s; gsub(nbre, " ", t)
            b = (t ~ bre)
            if (s ~ /re-?fly/) { if (b) rfBuilt = 1; else if (miss) rfMissing = 1 }
            if (s ~ /export/) { if (miss) expMissing = 1 }
        }
        print (rfBuilt && !rfMissing ? "built" : "unbuilt"), (expMissing ? "pending" : "claimed")
    }')"
    if [ "${verdict%% *}" = "built" ]; then
        ok "ARCHITECTURE.md marks Re-fly as built under M5"
    else
        bad "ARCHITECTURE.md marks Re-fly as built under M5 (its M5 status cell reads: $m5status)"
    fi
    # export/import must still be named as to come, which is what keeps a bare "Done" from passing.
    if [ "${verdict##* }" = "pending" ]; then
        ok "ARCHITECTURE.md's M5 row still names export/import as to come"
    else
        bad "ARCHITECTURE.md's M5 status cell names export/import as still to come (it reads: $m5status)"
    fi
fi

# ─── 6. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above, so a regression anywhere fails the run. These name the
# brief's own "must not" clauses that this work is the one touching, so a regression reads as a named
# failure rather than a line in a log. All of them exist today; this run has to keep them passing and
# keep their names.
echo ""
echo "=== must not break ==="

# "the Launchpad": the PENDING state this run extends, the derivation that re-runs on every plan change
# and now also reads history, and the Scrub path that shares the PENDING action row.
t pendingAfterScrub
t liveUpdateThroughStates
t launchpadScrubTrimsTheReason
t draftDrawsOneStepperPair

# "MissionManager": the preparation of the next sortie that Re-fly must not disturb or duplicate —
# Re-fly moves a sortie to PLANNED without advancing the mission — and the landing and scrubbing rules
# the source plan is produced by.
t confirmLeavesLiftPending
t scrubStoresReasonAndPreparesNextSortie
t landAdvancesToNextSortie
t landMarksUncheckedSetsNotDone
t scrubInFlightKeepsCheckedSets

# "MissionManager and the domain tests": the transitions Re-fly is built on, and the planning that stays
# as it was.
t legalTransitionsSucceed
t illegalTransitionsAreRejected
t runWithGenerationOffGetsSimplePlan

# The DAO reads and writes Re-fly is built on, unchanged: writePlan is what the copy writes through, the
# cheap "which sorties have a plan" read, and the finished-history read the candidates come from.
t writePlanReplacesExistingPlan
t titlesForSortieIdsOmitSortiesWithNoPlan
t historyHasOnlyLandedAndScrubbed
t historyIsNewestFirstByWeekThenIndex

# "Don't change the Room schema": the exported schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase

# "the Launchpad and Mission screens" and the shell that hosts them: the Launchpad still opens as the
# app's first screen, and the Mission tab's PENDING row — which this run leaves alone — still shows no
# plan for a sortie that has none.
t appOpensOnLaunchpad
t bottomBarReachesEveryTab
t pendingSortieWithoutPlanShowsNoPlan

# "In-Flight" and "Landed": the checklist a source plan is built through, and the history list that
# shows the plan Re-fly copied.
t seededLiftPlanShowsHeaderProgressAndCards
t historyGroupsByWeekNewestFirstAcrossTwoWeeks

# "the coach package": the prompt builder and the history loader this run must not reach into.
t liftFlightPlanPromptWithOutlineMatchesGoldenFile
t historyCarriesActualSetsAndExerciseNotes

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
