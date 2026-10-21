#!/usr/bin/env bash
# Verify: "Coach turn runner over the copied rho client" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest),
# then every named test read out of the JUnit XML reports that run produced, then the two test classes the brief
# says the cherry-pick must leave green, then the ARCHITECTURE.md rows the brief names, then the brief's
# "don't bring any .rho/runs/ file from that branch".
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is why this script fails on untouched code: the turn
# runner and its tests do not exist yet, and the com.liftoff.app.work row still reads "—". Names are matched
# across every report, so which file and package each one sits in is the implementer's call; what it must show is
# written in the comment above it.
#
# The brief's "Cover:" list has seven bullets; Ask and Permission are answered by a daemon for different reasons, so
# they are named separately, as are the blocked and paused ends, and the stop helper is its own thing the brief asks
# for. Nine names for seven bullets.
#
# What is NOT checked here, and why:
# - Any UI. There is none: the runner has no Android imports and the brief names no screen. No UI test is named.
# - That the copied files under com.liftoff.app.rho are byte-for-byte unchanged (invariant 3), that the new file
#   has no Android imports, and that it uses neither Room nor WorkManager. Those are properties of how the source
#   is written, and a script that greps them out of files fails correct work laid out differently. DaemonConnectionCheckTest
#   and AppContainerTest drive the copied client and are named below; the copy's diff against upstream rho is what
#   the owner reviews in the pull request.
# - The brief's protocol facts that its own test list does not reach: refetching from null after CURSOR_UNKNOWN,
#   skipping an id already seen, and a repeated client_id returning the original event. The brief states them as
#   facts the runner must honour, not as tests to write.
# - Line endings, commit style, and "the runner never closes the client it was handed".
# - That the cherry-pick kept its commit message, and that it arrived as a pick rather than as a rewrite. The
#   scripted fake it brings is what the tests below run against, so its behaviour is checked; the shape of the
#   commit is what the owner reviews in the pull request.
# - "They must also pass on the GitHub CI runner (ubuntu-latest, UTC)". CI runs exactly the gate this script runs,
#   and the fake's clock is fixed, so no test here reads a real clock.
# - The plan's "## Decisions" that no test below rests on: Decision 1 (a refused call lets DaemonException out
#   unchanged, code and isPermanent intact, except CURSOR_UNKNOWN) and Decision 6 (an Ask or Permission re-sent by
#   rho.session.subscribe from somebody else's turn is answered the same way). The owner reviews those in the pull
#   request, and a test written only to pin one of them is not asked for by the brief.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/coach-turn-runner-over-the-copied-rho-4bvb"

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
# - the compile check for the new file and for the cherry-picked fake;
# - the "must not break" check for the whole suite, including Test connection and the copied client's tests.
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

# ─── 2. The turn runner: one coach turn, end to end ────────────────────────────
# Brief: "the piece that runs one coach turn in a rho session: create or reuse the session, send a prompt, watch
# events until the turn ends, and hand back what happened." Each test below drives the runner over a real socket
# to the scripted fake daemon, with a timeout on every wait. No screen, no Room, no WorkManager.
echo ""
echo "=== the turn runner ==="

# "a fresh turn: one create frame carrying the workspace, permission_mode auto, the labels and the description;
# the prompt frame carrying the given client_id; the returned text equal to the scripted assistant message; ended
# idle; the callbacks receiving the session id and a final cursor". The value handed back carries the session id
# and the final cursor too. The fake logs what rho.session.create was sent with, so the frame is read back from it
# rather than assumed.
t aFreshTurnCreatesOneSessionAndReturnsItsReplyAndCursor

# "an existing session id and cursor: no create frame". The runner prompts on the session it was handed and asks
# the daemon only for what came after the cursor it was handed — the cursor comes from the fake's own log, so the
# fake knows it. Decision 4: the session-id callback fires for a supplied id too, so the caller learns the id the
# runner is actually using either way.
t aSessionAndCursorHandedInIsUsedWithoutCreatingAnother

# "a state_change to idle logged before the prompt (logEvent) does not end the turn". The turn must still reach
# the scripted end and report its reply, not stop at the older idle.
t aStateChangeLoggedBeforeThePromptDoesNotEndTheTurn

# "a scripted Ask is answered with the sentence above" — {"answer": "Decide yourself; there is nobody to ask."},
# read out of the fake's answers. "each turn still ends idle with the reply": the daemon holds the rest of the
# turn back until the client answers, so an unanswered question is a turn that never ends.
t aDaemonQuestionIsAnsweredWithTheFixedSentenceAndTheTurnStillEndsIdle

# "a scripted Permission is answered deny", read out of the fake's answers, and the turn still ends idle with its
# reply. Denying is what a runner with nobody watching should do to a tool it cannot vouch for.
t aPermissionRequestIsAnsweredDenyAndTheTurnStillEndsIdle

# "a turn scripted to end blocked … report that end". The end is the first state_change after the prompt whose
# target is blocked, and it is reported as blocked rather than as idle or as a timeout.
t aTurnThatEndsBlockedReportsBlocked

# "… and one ending paused, report that end". Paused is its own end reason, not a timeout.
t aTurnThatEndsPausedReportsPaused

# "a turn with no end inside a short limit reports timed out". The runner returns the timeout instead of letting
# the wait escape. Decision 2: the limit covers the whole turn from the call, so a turn that never reaches the
# prompt is caught too. Decision 3: the cursor that comes back is the furthest event actually read, not the one
# that was passed in, so a retry picks up where this attempt stopped.
t aTurnWithNoEndInsideItsLimitReportsTimedOut

# ─── 3. The stop helper ────────────────────────────────────────────────────────
# Brief: "Also add a small helper that sends rho.session.stop {session_id}; the worker calls it later." One call,
# one frame, carrying the session id it was given. Decision 5: the runner never stops the session itself, not even
# on a timeout — stopping stays the caller's decision, which is why this helper exists at all.
echo ""
echo "=== the rho.session.stop helper ==="

t theStopHelperSendsOneSessionStopFrame

# ─── 4. The cherry-pick must not have moved anything else ──────────────────────
# Brief: "After the pick, DaemonConnectionCheckTest and AppContainerTest must still pass." Both classes drive the
# copied client over the fake, so they are the behaviour proof that the fake's new answers (rho.hello, rho.session.*)
# still line up with what the copied client sends and expects.
echo ""
echo "=== the copied client and Test connection still behave ==="

c DaemonConnectionCheckTest
c AppContainerTest

# ─── 5. ARCHITECTURE.md ────────────────────────────────────────────────────────
# Brief: "Update ARCHITECTURE.md's com.liftoff.app.work row ('Exists yet?' column, currently —) to name the turn
# runner. Don't touch the M3 row." The row is looked up in the table, not anywhere in the file.
echo ""
echo "=== ARCHITECTURE.md ==="

work_row="$(grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\r' || true)"
if [ -z "$work_row" ]; then
    bad "the com.liftoff.app.work row is in the area map"
else
    work_cell="$(printf '%s\n' "$work_row" | awk -F'|' '{ print $4 }' | tr -d ' \t')"
    if [ -z "$work_cell" ] || [ "$work_cell" = "—" ]; then
        bad "the com.liftoff.app.work row's 'Exists yet?' cell names the turn runner (it still reads the em dash that means 'does not exist yet')"
    elif printf '%s\n' "$work_cell" | grep -qi 'turn' && printf '%s\n' "$work_cell" | grep -qi 'runner'; then
        ok "the com.liftoff.app.work row's 'Exists yet?' cell names the turn runner"
    else
        bad "the com.liftoff.app.work row's 'Exists yet?' cell names the turn runner (it reads: $work_cell)"
    fi
fi

# "Don't touch the M3 row." Compared against the same row in the tree this run started from, so the check is only
# that the row did not move.
brief_commit="$(git rev-list -1 HEAD -- "$RUN_DIR/brief.md")"
if [ -z "$brief_commit" ]; then
    bad "ARCHITECTURE.md's M3 row is unchanged (this run's brief.md is not in the history to compare against)"
else
    base_commit="$(git rev-parse "$brief_commit^")"
    m3_base="$(git cat-file blob "$base_commit:ARCHITECTURE.md" | grep -m1 -E '^\| M3 \|' | tr -d '\r' || true)"
    m3_head="$(grep -m1 -E '^\| M3 \|' ARCHITECTURE.md | tr -d '\r' || true)"
    if [ -z "$m3_base" ] || [ -z "$m3_head" ]; then
        bad "ARCHITECTURE.md's M3 row is unchanged (the row could not be read on one side or the other)"
    elif [ "$m3_base" = "$m3_head" ]; then
        ok "the M3 milestone row is untouched"
    else
        bad "ARCHITECTURE.md's M3 row is untouched (it now reads: $m3_head)"
    fi
fi

# ─── 6. The finished fake arrives as code, not as another run's records ─────────
# Brief: "Don't bring any .rho/runs/ file from that branch."
echo ""
echo "=== the cherry-pick brings test code only ==="

OTHER_RUN=".rho/runs/generationworker-core-flow-j5ca"
if [ -n "$(git log --oneline -- "$OTHER_RUN" 2>/dev/null || true)" ]; then
    bad "no commit on this branch brings in $OTHER_RUN"
elif [ -n "$(git ls-tree -r --name-only HEAD -- "$OTHER_RUN" 2>/dev/null || true)" ]; then
    bad "nothing under $OTHER_RUN is in the tree"
else
    ok "no file from the finished branch's run directory arrived"
fi

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
