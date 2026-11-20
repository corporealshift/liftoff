# Plan

## Approach

The whole change sits in the `CoachReplyResult.Invalid` arm of `storeFinishedTurn`'s loop (see the map): the worker
picks the session the repair runs on from how the refused turn ended. `Idle` keeps repairing on the same session,
exactly as today; `Completed` hands `runCoachTurn` a null session id, which is already what starts a session there,
with the `CoachSessionOptions` the worker has been holding for exactly this purpose — so the replacement session gets
the same workspace, labels and description as the one it replaces. A session with no history cannot be handed the
two-line "your reply was not valid" prompt, because it has no reply to look at, so the fresh session gets a second
repair shape built beside `repairPrompt`: the original prompt, the refused reply, and the validator's error lines.
Everything around it already works for a new session — `savingProgressTo` writes the new id onto the row, the runner
ignores events older than the prompt it just sent, and the repair cap is counted on the row rather than per run — so
the Idle path and the two existing repair tests are untouched. DESIGN.md §7.3 step 5 and ARCHITECTURE.md invariant 8
both say "same session"/"in-session" outright, so both are amended alongside the code.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the Invalid arm chooses the session from
  `refused.end`; the original prompt is carried into `storeFinishedTurn` so a fresh-session repair can send it; a
  fresh session is started without the refused turn's cursor.
- `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` — the second repair shape (original prompt +
  refused reply + error lines), next to `repairPrompt`. Prompt text lives in `coach/`, which keeps the worker's loop
  the same size and gives the shape its own test. The alternative is building it inline in the worker; the brief names
  only `GenerationWorker.kt`, so that is a defensible smaller footprint, but the builder is where §7.4 puts prompts.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — the new case, beside the two existing repair cases.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt` — the new prompt shape on its own. Not a golden
  file: `CoachPromptGoldenFileTest` covers the three §7.4 prompts only.
- `DESIGN.md` — §7.3 step 5 amended inline for the `completed` split, §10's "in-session repairs" row (611), and one
  entry at the top of "Amendments after approval" (808), dated 2026-11-20.
- `ARCHITECTURE.md` — invariant 8's "up to 2 in-session repairs" wording.

## Order

1. The fresh-session repair shape in `CoachPromptBuilder`, with its own test in `CoachPromptBuilderTest`. Nothing
   calls it yet, so the build and the suite stay green.
2. The Invalid arm in `GenerationWorker`: the session from `refused.end`, the prompt that matches that session, no
   cursor for a new session, and the original prompt carried into `storeFinishedTurn`. The Idle path is unchanged, so
   every existing test still passes.
3. The new `GenerationWorkerTest` case, plus the one for running out of repairs after a `completed` end.
4. The `DESIGN.md` §7.3 and §10 amendments, the "Amendments after approval" entry, and `ARCHITECTURE.md` invariant 8.
5. The full gate.

## Testing

All the worker cases are `GenerationWorkerTest` cases against the scripted fake daemon, in the style of the two
repair cases already there.

- A turn that ends `completed` with an invalid reply opens a **second** session: two `rho.session.create` frames, and
  the repair prompt names the second session id, not the first. This is the assertion that fails before the fix.
- That repair prompt carries the original prompt text, the refused reply, and every validator error line.
- The generation then finishes the way a same-session repair does: `SUCCEEDED`, `attempt` 1, outline and flight plan
  stored, the row's `nabuSessionId` the new session, and the session given up is the new one.
- A `completed` turn still gets the full repair budget: another `completed` end opens yet another session, and running
  out ends the row `FAILED` with `"invalid output: …"` — the last turn's validator lines — never the daemon's
  `INVALID_TRANSITION` text.
- `Idle` still repairs in the same session: the two existing repair cases pass unchanged, with their exactly-one-create
  assertions intact.
- A genuine permanent refusal still ends the row with the daemon's message: the existing
  `aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession` case passes unchanged.
- The new prompt shape is pure and carries the error list verbatim (§7.6): a `CoachPromptBuilderTest` case.
- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. On this machine that script pins Windows paths,
  so the same gate runs as `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
  ~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest`.

## Risks

- **The fake daemon has one session id and one shared log.** Give the new case a second, distinct id by setting
  `daemon.sessionId` from a `DaemonScript.Act` at the end of the first turn rather than by reworking `FakeDaemon`'s
  session model. If that turns out not to work, the smallest per-session knob is acceptable — but no existing test's
  expectations may change.
- **The fake never refuses a prompt on an ended session**, so the new case proves which session the worker chose, not
  the daemon's refusal. That is enough: before the fix there is one create frame and the repair names the old id.
- **The cursor is the trap the fake will not show.** Handing the refused turn's cursor to a brand-new session makes the
  real daemon answer `CURSOR_UNKNOWN`, and the runner survives it by re-reading from the start. Start a new session
  with no cursor.
- **`storeFinishedTurn` does not receive the original prompt today.** Carrying it in is a private-signature change.
  Rebuilding it there with `promptFor` would produce identical text but re-reads Room and could drift if the row moved
  under the worker; carry it in.
- **A `completed` turn with no assistant text at all** yields an empty refused reply in the fresh prompt. Not
  special-cased: the validator's error lines are what the coach is told, as today.
- **Out of scope, and worth naming in the PR:** the same refusal still exists on the *resume* path — a row whose stored
  `nabuSessionId` has since completed (worker killed between the turn and its repair) still fails with the RPC message.
  The copied protocol has no call that reports a session's state, and ARCHITECTURE.md invariant 3 forbids adding one,
  so it cannot be pre-checked. The brief's fix is the repair path only.
- **Docs are part of this change, not an extra.** Leaving §7.3 step 5 saying "same session" leaves the repo's authority
  contradicting the code.
- **`LiftoffShellTest` is the known full-suite flake** (512m test heap for a 783-test Robolectric suite). Rerun before
  believing a failure there; it is unrelated to this change.

## Decisions

**How many repair rounds a `completed` turn gets.** The brief says to run the repair on a new session but not whether that session's reply, if it is also invalid, gets another round. The plan keeps the worker's existing repair budget and just changes *which session* each round runs on: a round whose turn ended `completed` starts a fresh session, a round whose turn ended `idle` repairs on the same one. The alternative is to allow exactly one repair for a `completed` turn. Rejected because users would read the fix as removing a spurious RPC failure, not as giving the coach fewer chances; a completed turn is rare enough that a bounded loop cannot run away.

**What the generation reports when the repair also fails.** Today it reports the rho RPC error (`INVALID_TRANSITION`), which is what the brief calls out as the symptom. The plan lets it fall through to the validator's errors from the last invalid reply, exactly as the `idle` path already does. The alternative — keep surfacing the RPC error — is what a narrow reading of "refused, losing both repairs" could mean, but the RPC error is an artifact of the bug and tells the user nothing about the reply.

**Whether the worker stops the session it is leaving before opening the replacement.** The plan does not. rho has
already ended it — that is what `completed` means — and `rho.session.stop` on an ended session is itself a transition
refusal, which `giveUpSession` would swallow anyway. The alternative is a stop-then-create, which adds a call that can
only fail. What the user sees either way is the same: the ended conversation stays in rho's list, readable, and the
worker's `rho.session.stop` at the end of a successful store goes to the replacement session.

**Which session id the generation row keeps when a repair succeeded on a new session.** The plan keeps what the code
already does: `savingProgressTo` writes every session id a turn reports onto the row, so the row ends pointing at the
session that produced the accepted reply. The alternative is to hold on to the first session's id so the generation
points at the conversation it started. Rejected: the Launchpad card's link into rho reads `nabuSessionId`, and it
should open the conversation that contains the reply the card is showing — the completed session's transcript stops at
the rejected reply. A user cancel follows the same field, so it stops the live session rather than the ended one.

**Whether the replacement session is created the same way as the first.** The plan reuses the `CoachSessionOptions`
the worker already builds for its first `rho.session.create`, so the only difference is the prompt. The alternative is
a create with defaults, which would give a session in rho's list that looks unrelated to the sortie it belongs to.
