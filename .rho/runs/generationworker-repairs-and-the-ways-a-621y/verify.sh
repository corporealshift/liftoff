#!/usr/bin/env bash
# Verify: "GenerationWorker: repairs and the ways a generation fails" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then every named test read out of the JUnit XML reports that
# run produced, then the test classes the brief's "Must not break" list names, then ARCHITECTURE.md's M3 row and
# the com.liftoff.app.work row, then the Room schema the brief says not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code: the
# ten named tests do not exist yet, the M3 row still reads "Not started — … the worker does not", and the work
# row still claims "the repairs and failure outcomes are not written yet". Names are matched across every report,
# so which file each test sits in is the implementer's call; what it must show is written in the comment above it.
#
# The brief's test list has six bullets. They become ten names: the mismatch bullet is three behaviours (the
# refused hello, the accepted hello that answers another major, and a refusal that names no version — the brief
# gives that case its own rule); the paused/error bullet is two outcomes the brief says "each"; and the brief's
# "Add:" list names two outcomes its test list never gives a bullet for (a permanent refusal at a call, and a
# valid reply whose target has moved on), which are behaviours the run has to get right, so each gets a name.
# The sixth bullet — one stop frame on success and on a permanent failure — is not named on its own: its success
# half is the run-before's test, which the class check below requires to still pass, and its permanent-failure
# half is asserted by the permanent-refusal test.
#
# What is NOT checked here, and why:
# - The retry paths that already work today: a timed-out turn, a dropped connection (a DaemonException with no
#   code), and every non-connected outcome other than a mismatch. The worker already returns Result.retry() for
#   all of them and this run does not change that; the brief's own test list names only paused and error. The
#   named paused/error tests are what show the retry path still leaves the row resumable.
# - A non-permanent DaemonException raised part-way through a prompt. The plan's Decisions retries it, but its
#   Testing section names no test for it, and the brief names none. The permanent-refusal test is the guard on
#   the change that was actually made (the catch now splits permanent from retryable).
# - A turn that ends with no assistant text. That is a plan decision added by review, not something the brief
#   asks for, and the plan's Testing section names no test for it. Decisions are reviewed in the pull request.
# - "Close the client on every path." Nothing on the wire or in the row shows a closed socket, and the plan's
#   Testing section names no test for it. The existing `finally { client.close() }` already covers the new paths.
# - The 20-minute turn limit, and WorkManager's own stop. A limit that long cannot be waited out in a test; the
#   plan's Risks keeps each test inside the class's own 30-second wait, which is also what keeps the suite off
#   the build's five-minute test-task timeout.
# - The retry backoff. The plan's Decisions takes WorkManager's default and sets no criteria; the brief says
#   `Result.retry()` and stops there.
# - How the repair turn's session id and cursor reach the row (a channel, a queue, a wrapper that awaits them),
#   and whether the repair loop is a loop, a recursion, or a second call. Those are how it is built. What the
#   named tests assert is the row's attempt, the frames the daemon received, and the row's end state.
# - "Every wait has a timeout, and nothing depends on timing (CI is ubuntu-latest, UTC)". CI runs exactly the
#   gate this script runs, so a test that hangs or depends on the local clock fails the gate. Forcing UTC on this
#   machine was tried in an earlier run and does not work: the pinned JDK ignores TZ.
# - The Room schema's shape beyond "unchanged": no entity change and no migration are checked by comparing the
#   exported schema under app/schemas against the tree this run started from.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, and the wording of the two ARCHITECTURE.md rows
#   beyond what is named below.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/generationworker-repairs-and-the-ways-a-621y"

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
# - the compile check for the worker's new outcome handling and for its tests;
# - the brief's "Must not break" check for the whole suite: the worker's own tests, the turn runner's, the coach,
#   data and domain tests, Test connection, and every other test the app already has;
# - the check that no test hangs and nothing depends on the local clock, since the build kills a test task that
#   goes five minutes without finishing.
# Stale reports are removed first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

# No `gradlew --stop` here: other runs' worktrees on this machine have their own live daemons, and a stop would
# break a build that is not this one. A daemon that dies mid-gate is caught by the retry path below.

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
# - a hung suite that wrote no report, which app/build.gradle.kts kills at 5 minutes — the brief warns about this
#   one for this run, and says to re-run it.
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
    awk '
        /<testsuite / {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^tests=/) { split($i, a, "\""); tests += a[2] }
                if ($i ~ /^failures=/) { split($i, a, "\""); failures += a[2] }
                if ($i ~ /^errors=/) { split($i, a, "\""); errors += a[2] }
                if ($i ~ /^skipped=/) { split($i, a, "\""); skipped += a[2] }
            }
        }
        END { printf "  … reports: %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped }
    ' "$RESULTS"/TEST-*.xml
fi

# ─── 2. The worker: repairs, and the ways a generation fails ───────────────────
# Every test below drives the worker's doWork() over a real socket to the scripted fake daemon, in the existing
# GenerationWorkerTest harness — the brief's own shape, and the setup the brief says to reuse rather than rebuild:
# TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(workDataOf(GenerationWorker.GENERATION_ID
# to id)), doWork() inside runBlocking { withTimeout(...) }, Robolectric @Config(sdk = [34]), the application's own
# container ((ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication).container — never a second
# AppContainer, which would open a second DataStore and a second Room database on the same files), the fake's
# host()/port(), a non-blank token and a coach workspace path written into settingsStore, and CoachFixtures.KEYS
# inserted through equipmentDao().insertAll so the lift fixture is not refused as unknown equipment. Replies come
# from CoachFixtures.fixture(...); daemon behaviour is scripted with DaemonScript and FakeDaemon.refuseMethod /
# refuseProtocolMismatch / answerHelloWithProtocol. One scripted turn per prompt: the fake repeats its last turn
# when its list runs out, which is how a run that keeps getting a bad reply is scripted without listing it again.
echo ""
echo "=== the worker: the repair loop ==="

# "invalid reply, then a repair, then success: two prompt frames in one session, client_ids ending -0 then -1,
# attempt 1, row SUCCEEDED". Script an invalid reply first (CoachFixtures.BAD_JSON) and a valid one second
# (fixture(OUTLINE)) on an OUTLINE row over a DRAFT mission seeded with onAppOpen() only. It must show: exactly
# two rho.session.send_prompt frames and exactly one rho.session.create frame, both prompts naming the same
# session — the repair goes into the session the first turn opened, not a new one; the first prompt's client_id is
# `<id>-0` and the second's is `<id>-1`, the second built from the attempt read after the bump; the second prompt
# is a repair rather than a repeat of the first — it is not the original prompt text, and it carries the validator's
# own error lines from the reply that was refused; the row's attempt is 1; the row is SUCCEEDED with its session id
# and cursor set; and the valid reply was stored (the mission is ACTIVE with a current sortie).
t anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds

# "two failed repairs: three prompt frames and no fourth, attempt 2, row FAILED with 'invalid output' and the last
# errors, nothing stored". Script two different invalid fixtures (CoachFixtures.BAD_JSON then
# SCHEMA_VIOLATION) so the errors the row ends with are the second reply's, not the first's; the fake repeats the
# last turn, so the third prompt gets SCHEMA_VIOLATION too. It must show: exactly three rho.session.send_prompt
# frames and no fourth, in one session; the row's attempt is 2 and no further prompt is sent — the cap is on the
# generation's repairs, so a row that already has attempt 2 asks the coach no more (plan Decision: the two repairs
# belong to the generation, not to one doWork()); doWork() returned Result.failure(); the row is FAILED with an
# error that starts 'invalid output' and carries the last turn's validator error lines word for word, joined rather
# than dropped (plan Decision: the row's error is the same lines the coach was handed); and nothing was stored —
# the mission is still DRAFT with no sorties.
t afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing

echo ""
echo "=== the worker: the permanent failures ==="

# "blocked becomes FAILED 'coach needed input'". Script a turn that ends blocked (DaemonScript.State("blocked")) on
# a row that has a session. It must show doWork() returning Result.failure(), the row FAILED with the error
# 'coach needed input', and nothing stored. A coach that asked for input is a generation that will not answer
# itself, so this is a failure rather than a retry — today this path returns Result.retry(), so the test cannot
# pass on untouched code.
t aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput

# "a mismatch through refuseProtocolMismatch(\"2.0\"): FAILED with the message above, and no create frame". The
# fake refuses rho.hello with PROTOCOL_MISMATCH and a message naming daemon 2.0, which is what
# DaemonConnectionCheck turns into a ProtocolMismatch carrying that version. It must show doWork() returning
# Result.failure(), the row FAILED with the error 'Liftoff speaks rho protocol 1.x, the daemon speaks 2.x' — the
# same sentence a user reads in Mission Control's Test connection — and no rho.session.create frame and no prompt
# frame: the connection check never opens a session, so a mismatch is a generation that never started. Today this
# path returns Result.retry() and leaves the row RUNNING.
t aProtocolMismatchFromARefusedHelloFailsTheGenerationWithoutOpeningASession

# "a mismatch through answerHelloWithProtocol(\"2.0\"): the same". The hello is accepted but answers
# protocol_version 2.0, which is the other way the check reaches ProtocolMismatch. Same expectations:
# Result.failure(), the row FAILED with 'Liftoff speaks rho protocol 1.x, the daemon speaks 2.x', and no
# rho.session.create frame and no prompt frame.
t aProtocolMismatchFromAnAcceptedHelloFailsTheGenerationWithoutOpeningASession

# "If the daemon named no version, use the refusal's own message." Refuse rho.hello through FakeDaemon.refuseMethod
# with RpcCodes.PROTOCOL_MISMATCH and a message that names no version, so the check's daemonVersion comes back
# empty. It must show the row FAILED with that refusal's message word for word — not the '1.x / N.x' sentence, and
# not a sentence with a blank or invented major in it — Result.failure(), and no rho.session.create frame.
t aProtocolMismatchThatNamesNoVersionFailsTheGenerationWithTheDaemonsOwnMessage

# "a permanent refusal from a call (DaemonException.isPermanent): FAILED with the daemon's message" and "one stop
# frame … on a permanent failure". Refuse rho.session.send_prompt through FakeDaemon.refuseMethod with a code in
# RpcCodes.PERMANENT (not UNAUTHORIZED, PROTOCOL_MISMATCH or INTERNAL_ERROR, which that set deliberately excludes)
# and a message of the fake's own. It must show doWork() returning Result.failure(), the row FAILED with the
# daemon's own message word for word, and exactly one rho.session.stop frame naming the session this generation
# opened — the session is given up even though the generation died, and it is given up once. Today every
# DaemonException returns Result.retry() and nothing is stopped, so this cannot pass on untouched code. A row that
# died this way keeps the session id and cursor it had (plan Decision: they are the last trace of it).
t aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession

# "a valid reply whose target has moved on (StoreOutcome.Refused(reason)): FAILED with the reason, because
# retrying can never succeed". Seed a row whose target is there when the row is inserted and gone by the time the
# worker stores into it: a FLIGHT_PLAN row for the current PENDING sortie, then MissionManager.scrub that sortie
# before running the worker, so the store turns the valid reply away. It must show doWork() returning
# Result.failure(), the row FAILED with the reason MissionManager itself gives for that state — the same sentence
# the store returns for it, not a generic 'the store refused' — nothing stored (no FlightPlan on that sortie, the
# sortie not PLANNED), and the session given up exactly once. Today this path returns Result.retry(), so the test
# cannot pass on untouched code.
t aValidReplyForASortieThatHasMovedOnFailsTheGenerationWithTheStoresOwnReason

echo ""
echo "=== the worker: the outcomes that still retry ==="

# "paused … return retry with the row RUNNING and no stop frame". Script a turn that ends paused
# (DaemonScript.State("paused")). It must show doWork() returning Result.retry(), the row still RUNNING with the
# session id and cursor the turn reported still on it, its error still null, its attempt unchanged, and no
# rho.session.stop frame: a paused coach is a generation that resumes the same turn later (invariant 6), not one
# that finished.
t aPausedTurnRetriesAndLeavesTheRowRunningWithItsSessionAndCursor

# "error … return retry with the row RUNNING and no stop frame". Script a turn that ends in error
# (DaemonScript.State("error")). Same expectations: Result.retry(), the row RUNNING with its session id and cursor
# kept and no error text, and no rho.session.stop frame.
t aTurnThatEndsInErrorRetriesAndLeavesTheRowRunningWithItsSessionAndCursor

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the worker's and the turn runner's existing tests, the coach, data and domain tests,
# Test connection, and the build gate." The gate above runs the whole suite; these are the named proof for the
# classes the brief calls out. GenerationWorkerTest as a class is how the worker's seven existing tests are
# covered: this run changes what the worker does on blocked, on an invalid reply and on a refused store, so the
# tests that were written against the old behaviour have to have been brought along, not dropped.
echo ""
echo "=== the tests the brief says must not break ==="

c GenerationWorkerTest
c CoachTurnRunnerTest
c DaemonConnectionCheckTest
c ConnectionViewModelTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c CoachPromptGoldenFileTest

# ─── 4. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief Done-when: "the M3 row is updated", and the brief's own words for it: "say the worker's core flow is
# built, with resilience, cancel, supersede and app wiring still to come". Three things, each rejecting or
# requiring a claim rather than a phrase:
# - the row's Status cell must stop saying the milestone is not started and stop saying the worker does not exist;
# - the four things the brief names as still to come have to be named somewhere in the row, in any wording a
#   reader of this project would recognise (resilience / repairs and retries; cancel(l)ation; supersede; wiring /
#   enqueueing / scheduling);
# - they have to read as outstanding, so the row carries a "still to come"-type marker and the Status cell does
#   not call the milestone Done.
# The plan also drops the now-false claim in the com.liftoff.app.work area-map row ("the repairs and failure
# outcomes are not written yet"). That claim is what this run makes false, so it is rejected by name; nothing else
# in that cell is required, and "nothing enqueues it yet" stays true and is left alone.
echo ""
echo "=== ARCHITECTURE.md ==="

m3_row="$(grep -m1 -E '^\|[[:space:]]*M3[[:space:]]*\|' ARCHITECTURE.md | tr -d '\r' || true)"
if [ -z "$m3_row" ]; then
    bad "the M3 milestone row is in the milestone table"
else
    m3_cell="$(printf '%s\n' "$m3_row" | awk -F'|' '{ print $4 }' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    lc="$(printf '%s\n' "$m3_cell" | tr '[:upper:]' '[:lower:]' | tr -d '`*_')"
    if [ -z "$m3_cell" ] || [ "$m3_cell" = "—" ]; then
        bad "the M3 row's Status cell says the milestone is in progress (it reads the em dash that means 'no status')"
    # Only the claims this run makes false are rejected, each in the shape it has today: the "Not started" marker,
    # the cell's trailing "the worker does not", and a reworded claim that the worker is unwritten. A row that says
    # the worker does not yet *do a named thing* ("does not yet cancel or supersede") is a correct row.
    elif printf '%s\n' "$lc" | grep -Eq '^[[:space:]]*not[[:space:]]+started|does[[:space:]]+not[^a-z]*$|worker[[:space:]]+does[[:space:]]+not[[:space:]]+exist|worker[^.;,|]{0,25}\bnot\b[^.;,|]{0,15}(written|built|implemented|started|there|present)'; then
        bad "the M3 row's Status cell no longer says the worker is missing (it still reads: $m3_cell)"
    else
        # The four things and the "outstanding" marker are read off the whole row, not just the Status cell, so the
        # row can carry them in whichever cell the writer chooses; only the claim that the worker is missing is
        # rejected where it lives today.
        lc_row="$(printf '%s\n' "$m3_row" | tr '[:upper:]' '[:lower:]' | tr -d '`*_')"
        missing=""
        for thing in 'resilien|repairs|retries' 'cancel' 'supersed' 'wirin|enqueu|schedul'; do
            printf '%s\n' "$lc_row" | grep -Eq "$thing" || missing="$missing [$thing]"
        done
        if [ -n "$missing" ]; then
            bad "the M3 row names what is still to come — resilience, cancel, supersede, app wiring (missing:$missing; it reads: $m3_cell)"
        elif ! printf '%s\n' "$lc_row" | grep -Eq 'still|remaining|to[[:space:]]+come|to[[:space:]]+be|not[[:space:]]+yet|not[[:space:]]+[a-z]+[[:space:]]+yet|outstanding|later|pending'; then
            bad "the M3 row's Status cell reads those four as outstanding, not as done (no 'still to come'-type marker in: $m3_cell)"
        elif printf '%s\n' "$lc" | grep -Eq '^[[:space:]]*(done|complete)'; then
            bad "the M3 row's Status cell does not call the milestone Done while four things are still to come (it reads: $m3_cell)"
        else
            ok "the M3 row says the worker's core flow is built and names resilience, cancel, supersede and app wiring as still to come"
        fi
    fi
fi

work_row="$(grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\r' || true)"
if [ -z "$work_row" ]; then
    bad "the com.liftoff.app.work row is in the area map"
else
    work_cell="$(printf '%s\n' "$work_row" | awk -F'|' '{ print $4 }' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    lc_cell="$(printf '%s\n' "$work_cell" | tr '[:upper:]' '[:lower:]' | tr -d '`*_')"
    # The claim the plan says goes false, matched in the shapes it appears in: the windows stop at '.', ';' and ','
    # so a clause about something else that really is unwritten ("supersede is not built yet") is not this claim.
    if printf '%s\n' "$lc_cell" | grep -Eq '(repairs?|failure outcomes)[^.;,|]{0,25}\bnot\b[^.;,|]{0,15}(written|built|implemented)'; then
        bad "the com.liftoff.app.work row no longer claims the repairs and failure outcomes are unwritten (it still reads: $work_cell)"
    else
        ok "the com.liftoff.app.work row's claim about the repairs and failure outcomes is gone"
    fi
fi

# ─── 5. The Room schema the brief says not to change ───────────────────────────
# Brief: "Don't change the Room schema." LiftoffDatabase exports its schema to app/schemas at build time, so the
# exported schema is the record of what the entities are. It is compared against the tree this run started from,
# both as committed and as it stands after the gate run above — a new version file or a rewritten one is a schema
# change, and the plan's Risks treats one as "the step went wrong".
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
