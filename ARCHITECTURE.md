# Liftoff — Architecture

Map of the project: topology, package layout, resource locations, data flow, invariants and milestones.

**Authority:** decisions live in `DESIGN.md`. This doc is the operational map.

---

## Topology

```
┌──────────── phone ────────────┐          ┌──────────────── owner's PC ────────────────┐
│ Liftoff (Kotlin/Compose)      │          │ rho daemon  ──► llama-server (local model) │
│  Room DB  ← source of truth   │◄────────►│     │                                      │
│  WorkManager generation jobs  │ Tailscale│     └─ session in workspace                │
│  copied rho DaemonClient      │  WS+JSON │        liftoff-coach/  (COACH.md, notes)   │
└───────────────────────────────┘   -RPC   └────────────────────────────────────────────┘
```

Liftoff is a **rho client**. It speaks rho's JSON-RPC 2.0 over WebSocket to the daemon on the owner's PC, using the same bearer-token auth as `rho/clients/android`. The phone holds all training data in Room; every generation prompt carries the history it needs so the coach stays stateless about training data.

---

## Package layout

All source lives under `com.liftoff.app`.

| Package | Responsibility | Exists yet? |
|---|---|---|
| `com.liftoff.app` (root) | `MainActivity` (hosts the navigation shell), `AppContainer.kt`, app entry point. | ✅ (`MainActivity.kt`, `AppContainer.kt`, `LiftoffApplication.kt`) |
| `com.liftoff.app.rho` | Copied rho `DaemonClient` and protocol types, unchanged apart from the package and import lines, plus Liftoff's own connection check built on them. | ✅ (`net/DaemonClient.kt`, `protocol/Events.kt`, `protocol/RpcCodes.kt` copied from rho `ffce07d38c0eba572f1b751701293ba0cf8b0fb6`; `DaemonConnectionCheck.kt`) |
| `com.liftoff.app.data` | Room entities (including Equipment), DAOs, database builder, export/import helpers. | ✅ (Room entities, DAOs, LiftoffDatabase, the export and import data layer) |
| `com.liftoff.app.settings` | App-level configuration backed by DataStore (units, run-toggle). | ✅ (`SettingsStore.kt`) |
| `com.liftoff.app.domain` | State machines for Mission and Sortie lifecycle. Pure Kotlin, no Android imports. | ✅ (Week, pattern, Mission and sortie rules, rollover, sortie planning, the Re-fly source chooser `ReFly.kt`) |
| `com.liftoff.app.coach` | Prompt builder, validator, JSON schemas — the three output schemas (`outline.json`, `lift-plan.json`, `run-plan.json`) sit in `app/src/main/resources/schemas/`. Pure Kotlin, no Android imports. | ✅ (`ExerciseNames.kt` exercise-name normalizer §7.7; the JSON Schema checker `CoachSchema.kt` and `SchemaChecker.kt`; the coach reply validator `CoachModels.kt` and `CoachReplyValidator.kt` against recorded-style reply fixtures in `app/src/test/resources/fixtures/coach/`; the prompt builder `CoachPromptBuilder.kt` and `PromptInputs.kt` with the three golden-file prompts in `app/src/test/resources/prompts/`) |
| `com.liftoff.app.work` | Android-side workers (`GenerationWorker`). Uses WorkManager and Android APIs. | Partly (`CoachTurnRunner.kt`: the coach turn runner over the copied rho client, using neither Room nor WorkManager; `GenerationWorker.kt`: one generation end to end, reading the `Generation` row by id, running one coach turn with the row's stored session id and cursor, repairing an invalid reply in the same session up to twice, validating and storing the reply, marking the row SUCCEEDED or FAILED with the reason the coach, the daemon or the store gave, and stopping the rho session on a finished generation and never on a retry; a turn that stops part-way sends `rho.session.resume` for the session it stopped in and retries with the row's session id and cursor left where that turn stopped, and the next attempt resumes that same session, with no second `rho.session.create`, re-sending the prompt under the `<generationId>-<attempt>` client_id the daemon already dedupes and reading from the stored cursor (a cursor that has walked past our own prompt still finds the reply); an unreachable daemon marks the row `Mission Control offline` while it stays QUEUED or RUNNING, which `Generation.isCoachOffline` reads, the mark cleared by the next attempt that reaches the coach, and the 8th attempt that cannot connect ends the row FAILED `coach unreachable`; `GenerationEnqueuer.kt`: the enqueue helper, one unique one-time request per generation named `gen-<generationId>` with `KEEP`, a connected-network constraint and a 1-minute exponential backoff) |
| `com.liftoff.app.ui` | Navigation shell, Launchpad, Mission, In-Flight and Landed history as real screens, Mission Control stub, theme and shared composables. | ✅ (navigation shell with cold-start reopen into the in-flight sortie, Launchpad with its Re-fly action beside Scrub on a pending sortie, Mission, In-Flight with Land, Scrub and leaving when the sortie is gone, Landed history list and detail view with its note editor, Mission Control stub, `ui/theme`) |

---

## Resource locations

| Resource | Path |
|---|---|
| JSON schemas (outline, lift plan, run plan) | `app/src/main/resources/schemas/` |
| Test fixtures | `app/src/test/resources/fixtures/` |

---

## Data flow

1. **UI ↔ Room.** The UI reads and writes through Room; the phone is the source of truth.
2. **Generation.** A WorkManager `GenerationWorker` connects to rho over WebSocket, sends a prompt (§7.4), waits for the turn, validates the JSON reply (§7.6), and stores the result in Room.
3. **Queueing.** When a sortie lands, the next sortie's generation is queued automatically. The plan is usually ready before the next gym visit.

---

## Invariants

| # | Invariant | Reference |
|---|---|---|
| 1 | The phone is the source of truth; every prompt carries the history it needs. | §3 |
| 2 | `domain/` and `coach/` are pure Kotlin with no Android imports. | §12 |
| 3 | Nothing in rho changes. Liftoff uses only existing protocol methods. | §3 |
| 4 | The rho code is copied, with a header recording the rho commit it came from. | §11 |
| 5 | No dependency-injection framework; a single `AppContainer` builds everything. | §11 |
| 6 | Generations are resumable from the `Generation` row (stored cursor). | §7.3 |
| 7 | Session labels never start with `run:` (rho's runner acts on those). | §7.3 |
| 8 | The phone validates every coach reply, with up to 2 in-session repairs. | §7.6 |

---

## Milestones

Each milestone ends with the project gate (`./gradlew :app:assembleDebug :app:testDebugUnitTest`) passing.

| Milestone | Title | Status |
|---|---|---|
| M0 | Coach reliability spike — coach workspace, validator, schemas, prompt builder, `LiveCoachTest` | Partial: the coach workspace template (`coach/README.md`, `coach/COACH.md`), the three schemas and the validator (`CoachReplyValidator.kt`) exist; the prompt builder (`CoachPromptBuilder.kt`, `PromptInputs.kt`) and its golden-file prompts exist; live measurement (`LiveCoachTest`) has not been run |
| M1 | Skeleton and data — Room schema, settings, `AppContainer`, theme, navigation shell, Mission Control including the equipment list | Done — test connection, export and import are built |
| M2 | Missions and sorties — domain state machines, draft/confirm UI, Launchpad, Scrub | Done — Regenerate comes with generation (M3); In-Flight is a placeholder until M4 |
| M3 | Generation — rho client copy, `GenerationWorker`, outline + lift plan end to end | In progress — the worker is built, resilience, cancel and supersede included: the copied rho client and its connection check exist, and `GenerationWorker` reads the `Generation` row by id, runs one coach turn with the row's session id and cursor, repairs an invalid reply in the same session, validates and stores the reply, and ends the generation with the reason the coach, the daemon or the store gave; `GenerationEnqueuer.kt` schedules that worker as unique work named `gen-<generationId>` with `KEEP`, a connected-network constraint and a 1-minute exponential backoff; a turn that stops part-way hands its session back with `rho.session.resume`, and the next attempt resumes that session from the row's stored session id and cursor, re-sending the prompt under the client_id the daemon dedupes; an unreachable daemon marks the row `Mission Control offline` until an attempt reaches the coach, and the 8th attempt that cannot connect ends it FAILED `coach unreachable`; `GenerationCanceller.kt` is the cancel side, exposed from `AppContainer` beside the enqueue helper: it cancels that same unique work, marks the row CANCELLED only while it is QUEUED or RUNNING, and gives up its rho session last, so a Mission Control that cannot be reached still leaves the row cancelled, and a generation that already decided its outcome is left alone; a worker stores only while its generation is still the current one — the currency check and the store share one Room transaction, so a cancelled or superseded generation stores nothing, turns its own row CANCELLED and ends as done rather than a retry. App wiring — the screens that queue a generation and cancel one — comes next |
| M4 | In flight — In-Flight screen, Launch, Land, resume after process kill | Done; the In-Flight screen is a real screen; the shell reopens into the in-flight sortie on a cold start; the screen leaves when its sortie is no longer in flight |
| M5 | Landed and safety nets — history screens, Re-fly, export/import | Partial: the Landed history list and its detail view are built; Re-fly is built — the pure source chooser, the copy through MissionManager and the Launchpad's PENDING action are in place; export and import are built |
| M6 | Run plans — run-generation toggle, run Flight Plans | Not started |
