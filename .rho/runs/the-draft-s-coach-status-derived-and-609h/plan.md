# Plan

## Approach

The brief's derivation is a pure function, so it lives in `com.liftoff.app.domain` beside `coachIsConfigured`,
taking the row's status, error and offline flag as plain values rather than the `Generation` entity — the shape
`domain/` already uses for `MissionStatus` and `SortieState` (see the map). `LaunchpadState.Draft` carries the
result. `LaunchpadStates.observe()` already flatMapLatests over the week and combines the mission with
`flightPlanDao().observeChanges()`; the DRAFT arm gains one more join — the DAO's existing
`observeNewestNonCancelledOutlineForMission(missionId)` plus a configured-ness flow — and every other arm stays
exactly as it is. The configured-ness flow comes from `MissionManager`, which already holds the `SettingsStore`
and already asks this exact question in `confirm` and `retryOutline`: exposing it as a `coachConfigured` Flow
(settings mapped through `coachIsConfigured`, `distinctUntilChanged`) leaves both `LaunchpadStates`' and
`LaunchpadViewModel`' constructors untouched, so none of the six `LaunchpadViewModel(...)` test call sites move.
The draft branch ends in `distinctUntilChanged`, so a write that leaves the derived status equal — another
mission's plan generation, an unrelated setting — emits nothing. No Room schema change, and `LaunchpadScreen`
needs nothing: it reads only `weekStart` and `pattern` off the draft and never constructs `Draft`.

## Files

- `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt` (new) — the sealed status the draft carries
  (editable / planning / offline / failed-with-reason / not-configured), each variant recording whether Confirm
  or "Continue without outline" is offered, plus the plain derivation over the row's status, error, offline flag
  and configured-ness. Sits beside `Coach.kt`, whose `coachIsConfigured` it calls; no Android or Room types in
  either signature.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — one new public Flow exposing whether the coach is
  configured, built from the `SettingsStore` it already holds. Nothing else in the file changes.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` — `Draft` gains the derived status; the
  DRAFT arm of `observe()`/`derive()` joins the outline Flow and the configured-ness Flow and ends in
  `distinctUntilChanged`. `Planned`, `Pending`, `InFlight`, `Closed` and `Loading` keep today's behaviour.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — no change expected; it reads only
  `weekStart` and `pattern` and never builds a `Draft`. Any edit here is a compile-only fix, not a drawing change.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` — no change expected; its actions read
  `pattern` and `missionId` only.
- `app/src/test/java/com/liftoff/app/domain/CoachStatusTest.kt` (new) — plain JUnit.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadCoachStatusTest.kt` (new) — Robolectric, on the
  `LaunchpadStatesTest` harness recorded in the map.

## Order

1. The derivation and its plain JUnit cases, in `domain`. Nothing reads it yet; the build and the domain tests
   pass on their own.
2. `MissionManager`'s configured-ness Flow. No caller yet, so nothing can break.
3. `Draft` carries the status and `LaunchpadStates` joins the two Flows on the DRAFT arm. This is the step that
   could break something: the six existing Launchpad test classes must still compile and pass, and
   `LaunchpadScreen`/`LaunchpadViewModel` stay as they are.
4. The Robolectric live-status test: one subscription, three writes, three emissions.
5. The whole gate once — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, detached, run again if it
   hangs.

## Testing

Plain JUnit, in `CoachStatusTest` (no Robolectric, no Room):

- No row and a configured coach → editable, Confirm offered.
- Newest row SUCCEEDED → editable, even though a generation happened.
- QUEUED and RUNNING, not offline → planning; the way out offered, Confirm not.
- QUEUED and RUNNING carrying the `COACH_OFFLINE` mark → offline; the way out offered.
- FAILED → failed, carrying the row's error **word for word** — assert the exact string, using a reason of the
  kind the worker writes ("coach unreachable") and one of the kind the validator writes, so a rewrite or a
  trim is caught.
- Blank host alone, and blank token alone, with no row → not configured; a whitespace-only host counts as blank,
  matching `coachIsConfigured`.
- Precedence, per the Decisions: FAILED with blank settings → failed with the reason, not not-configured; an
  open offline-marked row with blank settings → offline; SUCCEEDED with blank settings → editable.
- A CANCELLED row is not an input at all: the derivation is only ever given a non-cancelled row, and the
  no-row case is what a cancelled-only mission looks like → editable when configured.
- Each variant says which action it offers: Confirm only on editable, "Continue without outline" on planning,
  offline, failed and not configured.

Robolectric (`@Config(sdk = [34])`, in-memory Room, fixed UTC clock, bounded waits, no WorkManager), in
`LaunchpadCoachStatusTest`, all from **one** subscription to `LaunchpadStates.observe()` for the current-week
draft:

- Inserting a QUEUED OUTLINE row for that mission emits planning.
- `GenerationDao.setCoachOfflineMark(id, Generation.COACH_OFFLINE)` on that row emits offline.
- `markFailed(id, "reason", t)` emits failed carrying that exact reason.
- A row for a different mission, a row whose kind is FLIGHT_PLAN, and a row that is CANCELLED leave the draft's
  status untouched.
- Editing a setting that does not change configured-ness (weight unit, sortie length) leaves it untouched too;
  filling in a host and token turns a not-configured draft into an editable one without re-subscribing.
- The existing draft states still arrive: `LaunchpadStatesTest`'s draft assertions keep passing unchanged.

Regression: the six Launchpad test classes, the worker tests, In-Flight, Landed, Mission Control and the domain
tests all pass untouched, and the gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Risks

- `combine` produces nothing until every joined Flow has emitted once, so the draft state now waits on a
  DataStore read it never used to need. The work maps the settings down to the single configured-ness Boolean
  and keeps every wait in the new test bounded, as the existing states tests already do.
- Room's invalidation tracker fires on any write to the `generation` table, including another mission's plan
  generation, so the draft arm re-derives often. Without `distinctUntilChanged` on the derived state the
  "unrelated change emits nothing" cases would see a duplicate emission.
- Nested latest-of-latests (week → mission → outline) can miss a row written between the mission emission and the
  outline subscription. The new test observes the first Draft before it writes any row, which is the order the
  app has anyway.
- `Draft` is a data class and the StateFlow compares by equality, so the new field is what makes a status change
  visible. That is intended here; a later run that wants the status to change without re-emitting the draft has
  to change this.
- The derivation must be handed the row's status, error and offline flag, not the `Generation` entity — the
  entity is a Room type and the brief rules it out of the signature. `isCoachOffline` is a getter on that
  entity, so the flag has to be read out and passed.
- `MissionManager` gaining a public Flow is new surface. If the owner would rather inject `SettingsStore` into
  `LaunchpadStates`, the behaviour is identical but `LaunchpadViewModel` gains a parameter and six test call
  sites have to be edited; the plan picks the smaller change and records it here.
- The full gate can hang for about five minutes once on this machine, writing no reports. Run it detached, move
  the results directory aside before a re-run, and re-run rather than debug a hang.

## Decisions

**When a row exists, the row decides; the settings only decide when there is no row.** The brief's
"not configured" clause says "no row is *open*", which read strictly would let a blank host or token hide a
FAILED row — the user would lose the reason and the Retry button — and would also hide a SUCCEEDED outline that
is already sitting on the draft. The plan instead ranks the newest non-cancelled row's status first, and only
falls through to "not configured" when there is no outline row to report on at all. The alternative — check the
settings first — is the narrower reading of the brief's words, but it throws away the one piece of information
the user needs most after a failure, and the settings problem is already visible in Mission Control.

**An open row marked offline outranks the settings too**, by the same rule: a QUEUED or RUNNING row carrying the
offline mark shows "offline" even if the host or token is blank. The mark is what actually happened to that
generation; "not configured" would be a guess about why.

**Only the newest non-cancelled row is ever visible.** After a Retry, the older FAILED row and its reason
disappear and the status becomes "planning"; a CANCELLED row is invisible, so cancelling an outline leaves the
draft as if no generation had been queued. The alternative — showing a history of attempts, or keeping the last
failure visible next to a new attempt — is more information than a status line can carry, and the brief names
one row as the input. The failure history stays readable in the generation table for any later run that wants it.

**A FAILED row that carries no reason text gets a fixed neutral line.** `markFailed` always writes a reason, so
this is a corner reached only by an imported or hand-written row, but the status type is built now and the run
that draws it should not have to invent copy for a case it cannot test. The alternative — a nullable reason —
leaves the later screen deciding between an empty line and a made-up one.

**"Unrelated" means a change that cannot change the answer.** A settings edit that leaves the coach just as
configured as it was (a weight unit, a sortie length) and a generation row belonging to another mission, a
flight-plan generation, or a cancelled row all leave the draft's status emission untouched. The alternative —
combine the whole `Settings` object and the whole generation table — re-emits a draft that is identical in every
way, which the later screen would have to absorb as a redraw for no reason.

**This run changes no visible thing on the Launchpad.** The screen keeps drawing Confirm and "Continue without
outline" on every draft, and the view model's guards stay as they are; the status is state only, and the run
that draws it is the one that decides where the reason, the offline line and the Retry button appear. The
alternative — wiring the buttons now — would be a UI change the brief explicitly puts off, and it would be
drawn before there is a design for it.
