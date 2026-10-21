#!/usr/bin/env bash
# Verify: "Store validated coach results in Room" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate (the two tasks .github/workflows/ci.yml runs), then every named test read out of the JUnit
# XML reports that run produced, then the classes the brief says must not break.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports.
# A test that was never written cannot pass. That is why this script fails on untouched code: the two store
# operations and the generation helpers do not exist yet, and neither do their tests. Names are matched across
# every report, so the implementer decides which class and package each test sits in; the comment above each name
# says what that test must show.
#
# The brief's Done-when names five things the tests must show: an outline activating the Mission, a lift plan
# stored, a run plan stored, each refusal changing nothing, and the generation row helpers. The brief's body adds
# what each of those has to carry (focus and rationale, the outline notes, sortie 1 current, the simple 'Run' plan
# when run generation is off, source GENERATED and rawJson, exercises with resolved ids, segments, the PLANNED
# step), and each is carried by the named test that covers its group. The plan's Decisions add four more cases
# (a past week or a changed pattern, replacement, finishedAt on FAILED and CANCELLED, what "for a Mission" means);
# each is named below and its comment says so.
#
# What is NOT checked here, and why:
# - No UI test. The brief has no worker, no network and no screen: "only Room writes and their tests", and its
#   Done-when names Robolectric data tests only. The gate compiles every screen and runs every screen test.
# - "in one transaction", and "every guard runs before the first write". Both are properties of how the operations
#   are written. The refusal tests below are what a user would feel: read the mission, the sorties and the plans
#   back and require them untouched. A script that inspects the operations for a withTransaction call fails
#   correct work that nests its writes differently.
# - "`coach` and `domain` stay free of Android imports". The coach half is already guarded by
#   ExerciseNamesTest.coachPackageHasNoAndroidImports, which the gate runs and which is class-checked below; the
#   plan puts the coach-plan → Room-row mapping in `data` and touches no file in `domain`. Grepping source for
#   import lines is a text check, not a behaviour test.
# - "fed from the existing valid reply fixtures". Whether a test reads a fixture file or holds the same document
#   inline is how it is written. The comments below name the fixture values each test must assert, so a test that
#   ignores the fixtures fails on the values.
# - ARCHITECTURE.md. The brief's Done-when names no doc update and the plan leaves the document alone; M3 still
#   has no worker and M6 still has no run-generation UI, so no milestone row is finished by this run.
# - The exact wording of a refusal's reason, and the names of the new operations, their result type and the new
#   DAO methods. The owner reviews those in the pull request. Each refusal test is required to name its reason
#   and to change nothing; which word it uses is the author's.
# - A coach plan of the wrong type for the sortie (a lift plan offered for a run sortie, or the reverse). The
#   plan's decision is that this throws rather than refusing, because the validator already rejected the
#   mismatch, and the plan's Testing list asks for no test of it. It is a caller bug, not an outcome a user can
#   reach, and naming a test for it would only pin the decision.
# - CI itself. The brief asks that the tests pass on ubuntu-latest in UTC; the plan fixes the clock and the zone
#   the way MissionManagerTest already does. The gate runs the suite on this machine; the pull request's CI run
#   is the one that proves UTC.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch files live under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
mkdir -p "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote. It writes $WORK/methods and $WORK/classes as
# "<name> <pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report.
# Fail is worse than skipped, and skipped is worse than pass, so one ignored test marks its whole class.
index_reports() {
    local file
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
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
            END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
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

# ─── 1. CI gate: the two tasks ci.yml runs ─────────────────────────────────────
# Brief Done-when: "./gradlew :app:assembleDebug :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the two operations, the coach-plan → FlightPlanDraft mapping, the new DAO queries, and
#   every call site the new `Valid` field touches;
# - the "must not break" check for the whole suite: MissionManager's confirm/scrub/launch/land/Re-fly/rollover
#   tests, the coach tests, export/import, every screen, and SchemaExportTest — the one that proves no Room
#   schema change was needed, and the one that fails if one was made without a migration and an updated export;
# - the check on "every wait in a test needs a timeout": a wait that never ends hangs the suite, and a suite that
#   outlives the timeout is a failure, not a run that is still going.
# Stale reports are removed first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

bash gradlew.sh --stop >/dev/null 2>&1 || true

run_gate() {
    local n="$1"
    rm -rf "$RESULTS" 2>/dev/null || true
    if [ -d "$RESULTS" ]; then
        echo "  … could not clear $RESULTS — a stranded Gradle test executor is holding it; running: bash gradlew.sh --stop"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
        rm -rf "$RESULTS" 2>/dev/null || true
        [ -d "$RESULTS" ] && { echo "could not clear $RESULTS — a stranded Gradle test executor is holding it" | tee "$WORK/gate-$n.log"; return 2; }
    fi
    mkdir -p app/build
    timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these failures get another gate:
# - the known flake (doneBodyweightSetStepperShowsBW, a closed Room connection pool; re-run it before treating it
#   as broken);
# - a build killed by a daemon or memory error, or a build stopped by a task-input race with no failed test;
# - a hung suite that wrote no report.
# A compile error, or a real failed assertion, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|which doesn.t exist|exceeded.*timeout' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
for attempt in 1 2 3; do
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure; then
        echo "  … attempt $attempt failed on the known flake or on the build itself; stopping daemons and running the gate again"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
    else
        echo "  … attempt $attempt failed on something real; not running the gate again"
        break
    fi
done

if [ "$gate_ok" -eq 1 ]; then
    ok "the CI gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 40 minutes"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK"/gate-*.log 2>/dev/null; then
    bad "unit tests executed in the gate run (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/classes"
else
    index_reports
fi

# ─── 2. The outline result ──────────────────────────────────────────────────────
# Brief: "Given a DRAFT Mission and a validated outline, in one transaction: activate the Mission with one sortie
# per pattern letter, each with the outline's focus and rationale; store the outline notes; make sortie 1 current
# through the existing advance-and-prepare step." The cheapest test that shows each one is a Robolectric test over
# an in-memory database, fed from `app/src/test/resources/fixtures/coach/outline-valid.txt` validated against the
# fixture context (pattern RLRLR, 60-minute sorties), in MissionManagerTest's shape: fixed clock, UTC zone.
echo ""
echo "=== the outline result ==="

# "activate the Mission with one sortie per pattern letter, each with the outline's focus and rationale" and
# "store the outline notes": the Mission reads ACTIVE; five sorties exist, in the pattern's own order
# RUN, LIFT, RUN, LIFT, RUN; each carries the fixture's focus ("Easy 5k", "Push + core", "Tempo intervals",
# "Legs + pull", "Long run") and the lift at index 1 carries its rationale, with the others holding none; the
# Mission holds the fixture's notes. Decision: "The order the week's sorties are created in, and which one
# becomes current" — pattern order, each sortie taking the outline entry at the same position.
t theStoredOutlineActivatesTheMissionWithItsSortiesFocusesAndNotes

# "make sortie 1 current through the existing advance-and-prepare step": after the outline is stored, the
# Mission's current sortie — the lowest index that is not LANDED or SCRUBBED — is the first one, index 0. The
# Mission is still ACTIVE with all five sorties present, because advance closes a Mission only when every sortie
# is done. With run generation off, that first sortie is the only one that has moved on from PENDING.
t theStoredOutlineMakesSortieOneCurrentAndLeavesTheMissionOpen

# "A run sortie still gets its simple 'Run' plan when run generation is off": with generateRunPlans off, the
# current run sortie holds a Flight Plan with source SIMPLE_RUN titled "Run" and holding the outline's own run
# focus as its notes — the plan the existing advance-and-prepare step already writes for a run sortie. No coach
# plan is stored for it. Decision: "The title of a generated run plan" — 'Run' stays on the SIMPLE_RUN plan.
t aRunSortieStillGetsTheSimpleRunPlanWhenRunGenerationIsOff

# "If the Mission is no longer a DRAFT, the operation changes nothing and says so": the operation is offered an
# outline for a Mission that is ACTIVE, or already CLOSED. The outcome names the refusal, and the Mission, its
# sorties and any plans they hold read back exactly as they were — no sortie added, no notes written, no status
# changed.
t anOutlineStoredForAMissionThatIsNoLongerADraftChangesNothing

# Decision: "an outline for a past week, or one that no longer fits the pattern" — two further refusals, each
# changing nothing and naming its reason: a DRAFT whose week has ended (the clock moved past its weekStart, as
# MissionManagerTest's advanceClock does it), and a Mission whose pattern was edited after the outline was
# validated against the old one. Both read back untouched.
t anOutlineForAPastWeekOrOneThatNoLongerFitsThePatternIsRefusedAndChangesNothing

# ─── 3. The Flight Plan result ──────────────────────────────────────────────────
# Brief: "Given a sortie and a validated lift plan or run plan, in one transaction: write the plan with source
# GENERATED and rawJson set to the validated JSON; for a lift: title, estimated minutes, warmup, notes, and
# exercises with their resolved exercise ids, equipment ids, rest and sets; for a run: run kind, target distance
# and pace, and segments; set the sortie PLANNED if it was PENDING." Fed from `lift-plan-valid.txt` and
# `run-plan-valid.txt`, validated against the fixture contexts. The lift fixture plans the sortie at pattern
# position 1 and the run fixture the one at position 0, so those are the sorties these tests use.
echo ""
echo "=== the Flight Plan result ==="

# "for a lift: title, estimated minutes, warmup, notes, and exercises with their resolved exercise ids, equipment
# ids, rest and sets" and "set the sortie PLANNED if it was PENDING": the stored plan carries the fixture's title
# "Push + core", 65 estimated minutes, its warmup and its notes. Its four exercises are in reply order, each with
# an exercise id that resolves through the existing exercise-name resolver (a name the database has never seen
# gets a row of its own), the fixture's equipment ids, rest seconds, and — on the two exercises that have them —
# its notes; each set carries the fixture's reps, seconds and weight in reply order, planned and not yet done.
# The sortie reads PLANNED.
t aValidatedLiftPlanIsStoredWithItsExercisesAndSetsAndTheSortieBecomesPlanned

# "write the plan with source GENERATED and rawJson set to the validated JSON". Decision: "where rawJson comes
# from" — the JSON text of the object the validator accepted, never the caller's raw reply. The same lift plan
# arriving wrapped in a Markdown code fence (`lift-plan-fenced.txt`) stores the same rawJson as the unfenced
# fixture, and that text holds no fence markers and no prose around the object.
t aStoredCoachPlanIsMarkedGeneratedAndHoldsTheValidatedJson

# "for a run: run kind, target distance and pace, and segments": the stored plan carries the fixture's kind
# "tempo", target distance 6.5 and pace "8:15/mi", and its four segments in reply order with their descriptions,
# distances and minutes. Decision: "The title of a generated run plan" — the coach's own title "Tempo intervals"
# is stored, not the simple plan's "Run". The sortie reads PLANNED.
t aValidatedRunPlanIsStoredWithItsKindTargetDistancePaceAndSegments

# "If the sortie is no longer current … the operation changes nothing and says so": a valid plan is offered for a
# sortie of the right type that is not the Mission's current one. The outcome names the refusal; that sortie
# still holds no plan, and the current sortie is untouched. This is how a later run drops a stale result.
t aPlanForASortieThatIsNoLongerCurrentChangesNothing

# "or is IN_FLIGHT, LANDED or SCRUBBED, the operation changes nothing and says so": each of the three states is
# refused for the current sortie, each names its reason, and the sortie keeps its state and any plan it already
# had. A plan arriving after the owner started or finished the sortie must not rewrite it.
t aPlanForASortieThatIsInFlightLandedOrScrubbedChangesNothing

# Decision: "A second plan arriving for a sortie that already has one" — the newer plan wins, whatever wrote the
# older one. A current PENDING sortie that already holds a plan (the simple 'Run' plan, or a plan written by
# Re-fly) ends up with exactly one plan for that sortie, holding the new coach plan's title and exercises, and it
# is still PLANNED.
t aSecondPlanForTheSameSortieReplacesThePlanAlreadyThere

# ─── 4. The generation row helpers ──────────────────────────────────────────────
# Brief: "Add what the worker will need on GenerationDao to: mark a row RUNNING, SUCCEEDED (with finishedAt),
# FAILED (with its error) or CANCELLED; save the session id and lastEventId; bump attempt; find the newest
# non-cancelled generation for a sortie or a Mission." These are plain DAO calls over an in-memory database, so
# the tests read the row back after each helper.
echo ""
echo "=== the generation row helpers ==="

# "mark a row RUNNING, SUCCEEDED (with finishedAt), FAILED (with its error) or CANCELLED": each helper updates
# the row it names and leaves the others alone. SUCCEEDED stamps finishedAt; FAILED stamps finishedAt and keeps
# its error text; CANCELLED stamps finishedAt; RUNNING stamps neither and clears nothing it should keep.
# Decision: "finishedAt on FAILED and CANCELLED" — a generation that failed or was cancelled has finished too, so
# Mission Control can say when it stopped whichever way it ended.
t markingAGenerationRunningSucceededFailedOrCancelledSetsItsStatusAndFinishedAt

# "save the session id and lastEventId" and "bump attempt": the cursor survives the status changes around it —
# invariant 6, a generation is resumable from its stored cursor — so a row that has been marked RUNNING and had
# its session and lastEventId saved still holds them, and attempt counts up from 0 rather than being set to a
# number the caller passes.
t savingTheSessionIdAndLastEventIdAndBumpingTheAttemptUpdatesTheGenerationRow

# "find the newest non-cancelled generation for a sortie": with several generations for one sortie, the lookup
# returns the newest one that is not cancelled, and returns nothing when every row for that sortie is cancelled.
# Decision: "Which generation row is the newest non-cancelled one" — newest by createdAt, then id, because the
# table records no activity, and a cancelled row is left out rather than ranked last.
t theNewestNonCancelledGenerationForASortieIgnoresCancelledRows

# "find the newest non-cancelled generation for … a Mission": the Mission's own OUTLINE generation, not one of
# its sorties' Flight Plan generations, even when a sortie's row is newer and non-cancelled. Decision: "what 'for
# a Mission' means in the generation lookup" — the draft screen needs the outline's own status to say "outline
# failed, retry", and a sortie's newer row would stand in for it and show the wrong thing.
t theNewestNonCancelledGenerationForAMissionIsItsOwnOutlineGeneration

# ─── 5. Must not break ──────────────────────────────────────────────────────────
# The gate ran the whole suite. These checks only stop the classes on the brief's "must not break" list from
# being dropped, renamed away, or having a test skipped — none of which a passing gate would notice.
echo ""
echo "=== must not break ==="

# "MissionManager's existing behaviour and tests (confirm, scrub, launch, land, Re-fly, rollover)" — the two
# operations land beside confirm and reuse advance, markPlanned and writePlan, so these are the tests that catch
# a change to any of them.
c MissionManagerTest
c MissionManagerReFlyTest
c RolloverTest
c MissionsTest
c SortiesTest
c SortiePlanningTest
c ReFlyTest
c WeekTest

# The DAO the new helpers are added to, and the one both operations write their plans through.
c GenerationDaoTest
c FlightPlanDaoTest
c SortieDaoTest
c MissionDaoTest
c ExerciseResolverTest
c EquipmentDaoTest

# "Don't change the Room schema unless it's unavoidable. If it is, add a real migration … and update the
# exported schema." This is the test that proves the plan's claim that no schema change was needed, and the one
# that fails if a column, index or entity changes without the export being updated to match.
c SchemaExportTest

# "the coach tests" — every class in com.liftoff.app.coach. The plan changes one of them: the fixture loader is
# lifted out of CoachReplyFixtureTest into a shared helper, and that test's fixture coverage check stays. The
# validator's `Valid` gains the JSON text, which is what the coach tests must still pass against.
c CoachReplyFixtureTest
c CoachReplySemanticTest
c CoachReplyExtractionTest
c CoachSchemaFilesTest
c CoachSchemaLoadTest
c SchemaCheckerKeywordTest
c SchemaCheckerValidDocumentTest
c CoachPromptBuilderTest
c CoachPromptGoldenFileTest
c ExerciseNamesTest

# "export/import".
c DatabaseExportTest
c DatabaseImportTest
c BackupRoundTripTest
c BackupImportRoomTest
c BackupViewModelTest

# "every screen".
c LiftoffShellTest
c MainActivityTest
c MissionControlScreenTest
c MissionControlViewModelTest
c EquipmentViewModelTest
c SettingsStoreTest
c AppContainerTest
c MissionStatesTest
c LaunchpadStatesTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadReFlyTest
c LaunchpadScrubTest
c InFlightScreenTest
c InFlightStatesTest
c InFlightLandScrubTest
c InFlightLeaveTest
c InFlightRunTest
c InFlightViewModelTest
c LandScrubDialogTest
c NoteDialogTest
c RunLandingTest
c LandedStatesTest
c FlightPlanSectionTest
c PlanFormatTest

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
