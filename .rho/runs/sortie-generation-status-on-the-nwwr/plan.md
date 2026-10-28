# Plan

## Approach

Follow the draft's precedent one-for-one. `CoachStatus` + `deriveCoachStatus` in `domain/` is a sealed type over a
generation row's plain values (`status`, `error`, the offline mark) plus `coachConfigured`, and `LaunchpadStates`
joins it only on the DRAFT arm through `observeNewestNonCancelledOutlineForMission` + `manager.coachConfigured` with
`distinctUntilChanged`. This run adds the same shape for a sortie: a new sealed type and derivation in `domain/`
(no Android or Room types in its signature — plain values in, plain values out, like the draft's), a Flow twin of
`GenerationDao.getNewestNonCancelledForSortie`, and a join on the PENDING and PLANNED arms only. The §5.4 Re-fly rule
lives inside the derivation — it takes "a landed plan of this type exists" as a plain Boolean input and answers the
rule — so the whole rule is covered by plain JUnit and `LaunchpadStates` just passes the answer through.
`LaunchpadScreen` already gates Re-fly on `pending.reFlyAvailable`, so it needs no change beyond compiling; no Room
schema change, no worker change, and the draft's `CoachStatus` is left alone. "Plain" means the same thing it means
next door: the `GenerationStatus` enum from `data/` is an acceptable input, as it is for `deriveCoachStatus`, but no
`Generation` row, no DAO and nothing from Android crosses the signature. The brief asks for no doc change, so
`ARCHITECTURE.md` is not touched.


## Files

- `app/src/main/java/com/liftoff/app/domain/SortieGenerationStatus.kt` (new) — the new sealed type for a sortie's
  generation status (planning / offline / failed with its reason and the split-out validator errors / not configured
  for PENDING; re-planning / idle for PLANNED), the rho session id it carries, and the plain-Kotlin derivation over
  the row's values, `coachConfigured` and whether a re-fly source exists. The draft's `CoachStatus` is not touched.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — add `observeNewestNonCancelledForSortie(sortieId):
  Flow<Generation?>`, the Flow twin of the existing suspend query at line 76 (same `WHERE sortieId = :sortieId AND
  status != 'CANCELLED' ORDER BY createdAt DESC, id DESC LIMIT 1`), so the live read and the read the worker's currency
  check uses cannot disagree. No `kind` filter is needed: an OUTLINE row carries no `sortieId`. A query, not a schema
  change.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` — `Pending` and `Planned` carry the derived
  status; `Pending.reFlyAvailable` comes from the derivation's §5.4 answer; `derive()` works out the current sortie
  before it picks flows, so only the PENDING and PLANNED arms join the generation Flow and `manager.coachConfigured`,
  and those arms end in `distinctUntilChanged()`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — only as far as it needs to compile; Re-fly
  stays shown only when `pending.reFlyAvailable`.
- `app/src/test/java/com/liftoff/app/domain/SortieGenerationStatusTest.kt` (new) — plain JUnit for the derivation.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadSortieStatusTest.kt` (new) — Robolectric live-subscription
  tests, shaped like `LaunchpadCoachStatusTest`.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadStatesTest.kt` and `LaunchpadReFlyTest.kt` — only if an
  existing Re-fly assumption meets the §5.4 rule; see Risks.


## Order

1. `GenerationDao`: add the Flow twin of `getNewestNonCancelledForSortie`. Nothing calls it yet. Gate:
   `:app:compileDebugKotlin` and the existing `GenerationDaoTest`.
2. `domain/`: the new sealed type and its derivation, then its plain JUnit test in the same step. Gate: that test class.
3. `LaunchpadState` / `LaunchpadStates`: `Pending` and `Planned` carry the status, `reFlyAvailable` comes from the
   derivation, the joins appear only on those two arms with `distinctUntilChanged()`. Gate: `LaunchpadStatesTest`,
   `LaunchpadReFlyTest`, `LaunchpadCoachStatusTest`, `LaunchpadRegenerateTest`.
4. The Robolectric live tests for the joined arms. Gate: the new class plus the Launchpad package.
5. The whole gate, detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, re-run once if it hangs.


## Testing

Plain JUnit (`SortieGenerationStatusTest`), each case one call of the derivation:

- PENDING with an open row that is not marked offline → planning.
- PENDING with an open row carrying the `Mission Control offline` mark → offline.
- PENDING with a FAILED row → failed, reason kept word for word.
- PENDING with a FAILED row from an invalid reply — `"invalid output: e1; e2; e3"` — → reason `invalid output` plus
  the three validator lines, in the order the worker wrote them.
- PENDING with a FAILED row whose error is blank or missing → the fallback line.
- PENDING with a FAILED row whose reason is `coach unreachable` → failed, not offline.
- PENDING with no row and the coach configured → failed with the short "nothing was queued" reason.
- PENDING with no row and a blank host or token → not configured.
- PENDING whose newest row is SUCCEEDED or CANCELLED → answered as if there were no row.
- PLANNED with a QUEUED row → re-planning; with a RUNNING row → re-planning; with a FAILED, SUCCEEDED or no row → idle.
- The rho session id is carried when the row has one and absent when it does not.
- §5.4: a source plan plus failed, offline or not-configured → Re-fly allowed; a source plan plus planning → refused;
  no source plan → refused under every status.

Robolectric (`LaunchpadSortieStatusTest`, `@Config(sdk = [34])`, in-memory Room, real `SettingsStore` and
`MissionManager`, no WorkManager, one collector launched before the writes, staggered `createdAt`, every wait bounded):

- One subscription to a PENDING sortie's state follows its row QUEUED → `setCoachOfflineMark` → `markFailed`, in three
  emissions from that one collector, and carries the session id once `saveSessionId` has written it.
- A PLANNED sortie shows re-planning when a new row is queued for it.
- A CANCELLED row is not the current one, and a row belonging to another sortie does not drive this card.
- An unrelated write — a plan for a different sortie, a settings value that does not change the answer — produces no
  new emission.

Nothing new is asked for the draft: `CoachStatusTest` and `LaunchpadCoachStatusTest` pass unchanged, as do the existing
Launchpad Re-fly assertions (see Risks).


## Risks

- **The brief's description of the stored error is wrong.** It says the validator's errors are "one per line"; the
  worker writes `"invalid output: " + errors.joinToString("; ")` on a single line, with the lead glued to the first
  error (`GenerationWorker.kt:266`, pinned by `GenerationWorkerTest:867`). The split is therefore on `"; "`, with the
  `invalid output: ` lead taken off. Do not change the worker's wording to make the brief's sentence true.
- A validator line could itself contain `"; "` when it quotes a coach-supplied string, so the split could cut one
  error in two. It only affects what a later screen lists — the repair prompt is built from the validator's own list,
  never from the stored error — so the plan accepts it rather than guessing at a smarter separator.
- **§5.4 hides Re-fly while a generation is open**, which is the one place an existing test can flip. The map shows why
  none is expected to: `LaunchpadStatesTest` and `LaunchpadReFlyTest` never configure the coach, and `advance` queues no
  row when the coach is not configured. If one does flip, the test changes to the rule and the PR names it — the rule
  does not change to the test.
- **Room wakes the join on any generation row**, including another sortie's, so the joined arms re-run often.
  `distinctUntilChanged()` on those arms is what keeps the state from re-emitting; the no-re-emit test proves it bites.
- **Restructuring the ACTIVE arm** (work out the current sortie before choosing which flows to join) touches the
  IN_FLIGHT and no-current-sortie paths. Those arms stay on `planChanges.map` as they are, and the gate for that step
  covers the whole Launchpad package plus the shell tests.
- **Keep the screen change to compiling only.** Drawing the status line is a later run's job; adding one now could push
  Re-fly or Scrub out of the fixed `@Config` window the Compose tests measure (`LaunchpadReFlyTest` uses
  `w411dp-h2000dp`).
- Known local hazards the work must respect: the full gate can hang about five minutes on one Robolectric/Room test —
  run it detached and re-run the same tree once without `--stop`; a Compose test that writes through Room must unmount
  the screen before `db.close()`; every wait in the new Robolectric class is bounded.


## Decisions

**Re-fly disappears from a PENDING card while the coach is working.** §5.4 offers Re-fly only when a source plan
exists *and* the generation failed, the coach is offline, or nothing is configured. Today `reFlyAvailable` is true
whenever a source plan exists, so this hides the control during QUEUED and RUNNING. The alternative — keep it whenever
a source plan exists — is the narrower reading of the current code, but it puts two competing ways of getting a plan on
one card, and Re-fly is the escape hatch for when the coach cannot do the work, not a second route while it is already
doing it. Existing Launchpad tests that assume Re-fly is always offered are updated to the rule rather than the rule
being relaxed for them.

**A PENDING sortie with no generation row at all reads as a failure with a plain reason.** The brief requires this so
Regenerate is offered; the open part is the wording. The plan uses a factual line in the draft's style —
"no Flight Plan generation was queued" — rather than "something went wrong", because the owner needs to know the coach
was never asked, which is what makes Regenerate the obvious next action.

**A FAILED row answers on its own even if the coach is no longer set up.** When the host or token has been cleared
after a generation already failed, the card shows the row's reason, not "Mission Control isn't set up". Letting the
settings win would bury a failure the owner can act on behind a setup message they already know about. This is the
draft's rule — a FAILED or open row answers on its own, and the settings answer only when no row is worth reporting
(`CoachStatus.kt:55-59`) — so the draft card and a sortie card cannot answer the same situation differently.

**A generation that gave up on an unreachable Mission Control reads as a failure, not as offline.** While a row is
still retrying the worker marks it "Mission Control offline", and the card says offline. Once the worker has ended it
FAILED with "coach unreachable", the plan reports that reason as a failure. The alternative — keep saying offline for a
row that has stopped retrying — matches §10's headline but leaves the owner reading "retrying" about a generation that
is not, with no way to act on it.

**A PLANNED sortie whose re-plan failed shows idle, not the failure.** The brief gives PLANNED only re-planning or
idle. The plan the sortie already holds is still usable and Regenerate is already offered on the Planned card, so the
owner can try again without being shown an error about a plan they can still land. The alternative — surfacing the
failure there too — alarms the owner about a plan that is fine.

**Every validator line is kept, and the reason is the lead sentence.** The stored error of an invalid reply is one
line, `"invalid output: e1; e2; e3"` (see Risks). The plan splits it at `"; "` into the reason "invalid output" and the
validator's lines, all of them, in the order the worker wrote them. Showing the stored line as one blob, or only the
first error, would hide problems the owner may need to fix in COACH.md or the prompt, and §10 asks for "the last error
list visible".

**A PLANNED sortie that has lost its plan row shows the same status any other PENDING card shows, but no Re-fly.**
`LaunchpadStates` already renders a Pending card for that case. It carries the derived status, so the owner sees why
there is no plan and gets Regenerate; Re-fly stays refused because `MissionManager.refly` refuses a PLANNED sortie, and
offering it would put a control on the card that fails when tapped.

