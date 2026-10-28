# Sortie generation status on the Launchpad, derived and live (no UI)

Give the Launchpad's PENDING and PLANNED states a live coach status, as state only. A later run draws it. Read DESIGN.md §9 ('Launchpad'), §5.4 and §10.

Facts you need (all on this branch):
- `LaunchpadState.Pending(eyebrow, title, chips, sortieId, sortieIndex, reFlyAvailable)` and `LaunchpadState.Planned(eyebrow, title, chips, plan, sortieId, sortieIndex, sortieType, sortieFocus)`, derived in `LaunchpadStates` (`app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt`). Today `reFlyAvailable` is true whenever `chooseReflySource(current, reflyCandidates(...))` finds a source plan.
- The draft already does this for the outline: `com.liftoff.app.domain.CoachStatus` (`domain/CoachStatus.kt`) is a plain-Kotlin derivation, and `LaunchpadStates` joins `GenerationDao.observeNewestNonCancelledOutlineForMission` and the settings only on the DRAFT branch. Follow that shape, but use a new type for sorties. Don't change the draft's.
- `GenerationDao.getNewestNonCancelledForSortie(sortieId): Generation?`. Add a Flow version of the same query. A query is not a schema change.
- `Generation`: `status`, `error`, `nabuSessionId` (the rho session id) and `isCoachOffline`. After an invalid reply, `error` holds a lead sentence followed by the validator's errors, one per line. Read `GenerationWorker` for the exact text it writes.
- `com.liftoff.app.domain.coachIsConfigured(host, token)` and `SettingsStore.settings`.

Add:
- A plain-Kotlin derivation in `com.liftoff.app.domain`, with no Android or Room types in its signature, over the newest non-cancelled FLIGHT_PLAN row's values (or none) and whether the coach is configured.
  - For PENDING it gives one of: planning (row open, not offline), offline (row open and offline), failed (the reason plus the validator errors split out of the stored error, if any), or not configured (no open row, and the host or token is blank). If there is no row and the coach is configured, treat it as failed with a short reason, so Retry is offered and the owner is never stuck.
  - For PLANNED it gives re-planning (a row is QUEUED or RUNNING) or idle.
  - It also gives the rho session id when the row has one.
  - It gives whether Re-fly is allowed by §5.4: only when a source plan exists and the generation failed, the coach is offline, or no connection is configured.
- `Pending` and `Planned` carry the derived status, and `reFlyAvailable` follows the §5.4 rule. `LaunchpadStates` joins the generation Flow and the settings only on those branches, and doesn't re-emit for unrelated changes.
- Update `LaunchpadScreen` only as far as it needs to compile, keeping Re-fly shown only when the new `reFlyAvailable` is true.

No Room schema change.

Tests:
- Plain JUnit for each derivation case, including the §5.4 Re-fly rule and a failed row with several validator errors.
- Robolectric (`@Config(sdk = [34])`, in-memory Room, every wait bounded, CI ubuntu-latest UTC, no WorkManager): one subscription to a PENDING sortie's state follows its row from QUEUED to offline (`setCoachOfflineMark`) to FAILED (`markFailed`), and a PLANNED sortie shows re-planning when a new row is queued for it.

Existing Launchpad tests that assume Re-fly is always offered on PENDING (for example in `LaunchpadReFlyTest`) may be updated to the §5.4 rule. Say which in the PR.

Must not break: the Launchpad tests, the draft's coach status, generation queueing and Regenerate, the worker tests, In-Flight, Landed, Mission Control, the shell tests, the domain tests and the build gate. Follow CLAUDE.md conventions. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens.

Done when: these tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
