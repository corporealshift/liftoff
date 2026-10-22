#!/usr/bin/env bash
# Verify: "GenerationWorker: one successful generation end to end" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then every named test read out of the JUnit XML reports that
# run produced, then the two test classes the brief's "Must not break" list names, then ARCHITECTURE.md's
# com.liftoff.app.work row, then the Room schema the brief says not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is why this script fails on untouched code: the
# worker and its tests do not exist yet, and the work row still reads "GenerationWorker not written yet". Names
# are matched across every report, so which file and package each one sits in is the implementer's call; what it
# must show is written in the comment above it.
#
# The brief's test list has six bullets; the last one covers two different rows (a row that is not there, and a
# row that already finished), which are two behaviours, so they are named separately. Seven names for six bullets.
#
# What is NOT checked here, and why:
# - The non-success outcomes. The brief says "This run covers only the success path. A later run adds repairs and
#   the failure outcomes", and the plan's Testing section lists the retry/failure cases as implemented but not
#   tested here. No test is named for a retry, for a refused store, for an invalid reply, for a DaemonException,
#   or for a vanished Mission or Sortie.
# - Any UI. The plan adds no scheduling and no screen reaches the worker, and the brief names none. No UI test is
#   named.
# - "Close the client on every path", and the 20-minute turn limit. A limit that long cannot be waited out in a
#   test and nothing on the wire shows it. The plan's Risks keeps each test's own withTimeout short, which is what
#   keeps the suite off the build's five-minute test-task timeout.
# - How the turn's session id and cursor get onto the row — a channel, a queue, a wrapper that awaits them. That is
#   how it is built. What the outline test asserts is that both landed on the row.
# - The transient RUNNING mark. The brief's test list and the plan's Testing section both stop at the row's end
#   state, and a mark that a successful run replaces cannot be read after doWork returns.
# - "coach stays free of Android imports", and that the new file is in com.liftoff.app.work. Those are properties
#   of how the source is laid out; a script that greps them out of files fails correct work written differently.
#   The gate compiles the coach module and the whole app, so a coach file that gained an Android import would not
#   build.
# - "Every wait has a timeout, and nothing depends on timing, so the tests pass on the GitHub CI runner
#   (ubuntu-latest, UTC)". CI runs exactly the gate this script runs. Forcing UTC on this machine was tried and
#   does not work: the pinned JDK ignores TZ (verified 2026-10-21: java.time.ZoneId.systemDefault() stays
#   America/New_York with TZ=UTC set), and re-zoning Gradle's test JVMs would mean editing build files, which
#   this run does not. What keeps the tests zone- and timing-independent is in the plan's Testing section: seed
#   through the container's real clock with onAppOpen(), never a fixed date, and assert state (status, which
#   sortie is current), not dates.
# - Line endings, commit style, and CLAUDE.md's §2 vocabulary.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/generationworker-one-successful-2rsd"

# Scratch files live under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1
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
    grep '^C:' "$WORK/all" | sed 's/^C://' > "$WORK/classes" || true
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
# Brief Done-when: "bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the worker and for its tests;
# - the brief's "Must not break" check for the whole suite: the turn runner's tests, the coach, data and domain
#   tests, Test connection, and every other test the app already has.
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
        [ -d "$RESULTS" ] && { echo "could not clear $RESULTS" | tee "$WORK/gate-$n.log"; return 2; }
    fi
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these failures get another gate:
# - the known flake (doneBodyweightSetStepperShowsBW, a Room pool closed under a concurrent observe());
# - a build killed by a daemon or memory error;
# - a hung suite that wrote no report, which app/build.gradle.kts kills at 5 minutes.
# A compile error, or a real failed assertion, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|exceeded.*timeout' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
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
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 30 minutes"
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

# ─── 2. The worker: one successful generation, end to end ──────────────────────
# Brief: "Given a Generation row id, it runs one coach turn and stores a valid reply." Each test below drives the
# worker's doWork() over a real socket to the scripted fake daemon — the brief's own shape:
# TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(...), doWork() inside
# runBlocking { withTimeout(...) }, Robolectric @Config(sdk = [34]), the application's own container
# ((ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication).container — never a second
# AppContainer, which would open a second DataStore and a second Room database on the same files), the fake's
# host()/port(), a non-blank token and a coach workspace path written into settingsStore, and the four active
# equipment keys from CoachFixtures.KEYS inserted through equipmentDao().insertAll so the lift fixture is not
# refused as unknown equipment. Replies come from CoachFixtures.fixture(...).
echo ""
echo "=== the worker: the success path ==="

# "outline happy path: Result.success(), the row SUCCEEDED with nabuSessionId and lastEventId set, the Mission
# ACTIVE with sortie 1 current". Seed with container.missionManager on the real clock and onAppOpen() only (a
# fixed past date makes storeOutline refuse), kind OUTLINE, no sortie, fixture(OUTLINE). It must show all of that —
# nabuSessionId and lastEventId being the turn's session id and cursor saved through the runner's callbacks — plus
# what the brief says the worker hands the turn: the create frame the fake received carries the labels
# ["liftoff", "liftoff:outline"] (never a label starting with run:) and a one-line description, and the prompt
# frame carries the client_id built from the row's id and its attempt. Decision 3: the attempt is read off the row
# and never bumped, so a fresh row's client_id is `<id>-0`. Decision 6: the description is built from the row — the
# kind and what it belongs to — and carries no prompt text and no user data.
t anOutlineGenerationStoresTheReplyAndMarksTheRowSucceeded

# "lift Flight Plan happy path: the sortie PLANNED, and the plan stored with source GENERATED". Seed: onAppOpen(),
# confirm(id), then scrub the index-0 sortie, which leaves index 1 — a LIFT under the default RLRLR pattern —
# current and PENDING; kind FLIGHT_PLAN with that sortie's id, fixture(LIFT_PLAN). It must show the sortie PLANNED,
# its FlightPlan stored with source GENERATED, the row SUCCEEDED, and the create frame carrying the labels
# ["liftoff", "liftoff:flight-plan"].
t aLiftPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan

# "run Flight Plan happy path: the same, for the run". Seed: settingsStore.setGenerateRunPlans(true) before
# onAppOpen(), then confirm(id), which leaves index 0 a current PENDING RUN; kind FLIGHT_PLAN with that sortie's
# id, fixture(RUN_PLAN). Same assertions as the lift case, for the run sortie.
t aRunPlanGenerationMarksTheSortiePlannedAndStoresAGeneratedPlan

# "a row that already has a session id: no rho.session.create frame". Seed the row's nabuSessionId with the fake's
# own sessionId so the pushed events match the session the runner filters on. It must show that the turn still
# runs to the end and the row still ends SUCCEEDED on the session that was already stored — the resume case the
# stored cursor exists for (ARCHITECTURE.md invariant 6) — and that the fake received no rho.session.create frame
# at all.
t aGenerationRowWithASessionIdAlreadyStoredIsResumedWithoutCreatingAnotherSession

# "a missing row returns failure, and do nothing": no row with that id. It must show doWork() returning
# Result.failure() and the fake receiving no session frame at all — no session created, no prompt sent — and no
# Generation row written for that id.
t aGenerationRowThatDoesNotExistReturnsFailureAndSendsNothingToTheDaemon

# "a SUCCEEDED row is left unchanged": seed a row that is already SUCCEEDED, with its finishedAt, error,
# nabuSessionId and lastEventId already filled in. It must show doWork() returning Result.success() and every one
# of those fields still what it was — not marked RUNNING, not re-stamped, not stored over — and the fake receiving
# no session frame at all. The brief's test list names SUCCEEDED; FAILED and CANCELLED follow the same rule.
t aGenerationRowThatAlreadyFinishedIsLeftAsItIsAndReportedAsDone

# "one rho.session.stop frame after success": exactly one rho.session.stop frame, and it comes after the prompt
# frame. Decision 4: the rho session is stopped only after a successful store, so the stop is what a finished,
# stored generation leaves behind and nothing else sends it.
t oneSessionStopFrameIsSentAfterASuccessfulStore

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the turn runner's tests, ... Test connection". The gate above already runs the whole
# suite, so these two are the named proof that the classes the brief calls out are still there and still green:
# the runner the worker is built on, and the connection check the worker calls before it connects.
echo ""
echo "=== the turn runner and Test connection still pass ==="

c CoachTurnRunnerTest
c ConnectionViewModelTest

# ─── 4. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief Done-when: "ARCHITECTURE.md's work row names the worker". The row is looked up in the area-map table, and
# only its "Exists yet?" cell, so the check is that the cell no longer says the worker is missing and does name it.
echo ""
echo "=== ARCHITECTURE.md ==="

work_row="$(grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\r' || true)"
if [ -z "$work_row" ]; then
    bad "the com.liftoff.app.work row is in the area map"
else
    # The cell is a parenthesised list split on ';'. Each entry is read on its own, so a later entry that says
    # something else is still missing ("the failure outcomes not written yet") does not make this check fail, and
    # the row's own name for the worker is what is being judged.
    work_cell="$(printf '%s\n' "$work_row" | awk -F'|' '{ print $4 }' | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    if [ -z "$work_cell" ] || [ "$work_cell" = "—" ]; then
        bad "the com.liftoff.app.work row's 'Exists yet?' cell names the worker (it still reads the em dash that means 'does not exist yet')"
    else
        read -r miss named <<<"$(printf '%s\n' "$work_cell" | awk -F';' '
            BEGIN { miss = 0; named = 0 }
            {
                for (i = 1; i <= NF; i++) {
                    s = tolower($i)
                    gsub(/[ \t]+/, " ", s)
                    if (s ~ /generationworker|[[:space:]]worker|worker[[:space:]]|^worker/) {
                        if (s ~ /not (written|built|implemented|started|done) yet|does not exist|not started/) miss = 1
                        else named = 1
                    }
                }
            }
            END { print miss, named }')"
        if [ "${miss:-0}" -eq 1 ]; then
            bad "the com.liftoff.app.work row's 'Exists yet?' cell says the worker exists (it still reads: $work_cell)"
        elif [ "${named:-0}" -eq 1 ]; then
            ok "the com.liftoff.app.work row's 'Exists yet?' cell names the worker"
        else
            bad "the com.liftoff.app.work row's 'Exists yet?' cell names the worker (it reads: $work_cell)"
        fi
    fi
fi

# The M3 milestone row is not checked. The brief names only the work row, and M3 also covers scheduling and the
# live end-to-end run, which this run does not add; whether M3's wording should move with the worker arriving is
# the owner's call in the pull request.

# ─── 5. The Room schema the brief says not to change ───────────────────────────
# Brief: "Don't change the Room schema." LiftoffDatabase exports its schema to app/schemas at build time, so the
# exported schema is the record of what the entities are. It is compared against the tree this run started from,
# both as committed and as it stands after the gate run above — a new version file or a rewritten one is a schema
# change.
echo ""
echo "=== the Room schema ==="

brief_commit="$(git rev-list -1 HEAD -- "$RUN_DIR/brief.md")"
if [ -z "$brief_commit" ]; then
    bad "the Room schema is unchanged (this run's brief.md is not in the history to compare against)"
else
    base_commit="$(git rev-parse "$brief_commit^")"
    if ! git diff --quiet "$base_commit" HEAD -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the committed tree)"
    elif ! git diff --quiet "$base_commit" -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the working tree after the gate run)"
    else
        ok "the exported Room schema is byte-identical to the one this run started from"
    fi
fi

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
