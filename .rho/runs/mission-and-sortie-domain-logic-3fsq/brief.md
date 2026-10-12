# Mission and sortie domain logic

Implement the rules for weeks (Missions) and their sorties from DESIGN.md §4, §5.1 and §5.3. Read DESIGN.md §2, §4, §5, §8 and §13 first. Build on the Room database and DAOs in `com.liftoff.app.data` (Mission, Sortie, FlightPlan and their DAOs, including writing a whole Flight Plan in one transaction), the settings store, and `AppContainer`. No new screens in this brief; the next brief builds the Launchpad on it.

**Pure Kotlin in `com.liftoff.app.domain`, with no Android imports** (ARCHITECTURE.md invariant 2):
- the week start: Monday, in the phone's local time zone
- creating a DRAFT Mission for the current week with the default pattern
- overriding the pattern, only while the Mission is a draft (1–7 of R/L); the pattern is frozen once confirmed
- the Mission lifecycle DRAFT → ACTIVE → CLOSED
- the sortie state machine (PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED): every legal transition in §5.1 succeeds and every illegal one is rejected
- at most one sortie IN_FLIGHT across all Missions
- next-sortie selection: the lowest index that is neither landed nor scrubbed
- a Mission closes when every sortie has landed or been scrubbed
- rollover: once a Mission's week has ended, its open sorties become SCRUBBED with reason 'week ended' and the Mission closes. Nothing carries over, and no Mission is created ahead of its week.

**Confirming.** Coach generation doesn't exist yet, so confirming a draft takes the 'Continue without outline' path from §4.2. The Mission becomes ACTIVE and gets one sortie per pattern letter (R = run, L = lift). Lift sorties get focus 'full body' and runs get focus 'easy'.

**Planning the current sortie.** Whenever a sortie becomes current (after confirm, or after the previous sortie lands or is scrubbed), the app prepares its plan as §5.3 and §7.2 describe:
- With run generation off, a run sortie gets a simple Flight Plan titled 'Run' with source SIMPLE_RUN and its focus, and the sortie becomes PLANNED.
- Lift sorties, and runs with generation on, stay PENDING for now. Keep this decision in one clear place so the later coach-generation work can queue a generation there. Don't create Generation rows yet.

**A Room-backed layer the UI can call**, with each operation in one transaction:
- on app open, roll over past Missions and make sure the current week has a Mission, creating a draft if needed
- change a draft's pattern
- confirm
- scrub the current sortie with an optional reason. A scrubbed IN_FLIGHT sortie keeps the sets already checked.
- launch a PLANNED sortie (records launchedAt)
- land the IN_FLIGHT sortie: set it LANDED with its landed time, mark unchecked sets as not done, advance to the next sortie and prepare its plan, and close the Mission after the last one
Expose it through `AppContainer`. The clock and time zone must be injectable so tests can control them.

Must not break: the shell, Mission Control and equipment management, the data layer and its tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary: never 'session' for a sortie.

Done when:
- Unit tests cover the §13 'Missions' and 'Sorties' cases: draft created with the default pattern; override, then frozen on confirm; rollover scrubs open sorties with 'week ended'; no carry-over; the Monday boundary across time zones; every legal and illegal transition; next-sortie selection skipping landed and scrubbed sorties; at most one in flight.
- Robolectric tests with in-memory Room cover simple run plan creation and each Room-backed operation, including land advancing to the next sortie and closing the Mission.
- ARCHITECTURE.md's package table marks `domain` as existing.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
