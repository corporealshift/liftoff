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
| `com.liftoff.app.data` | Room entities (including Equipment), DAOs, database builder, `MissionManager.kt`, which applies the domain rules to the store, export/import helpers, and the Re-fly copy in `ReFlyCopy.kt`. | ✅ (Room entities, DAOs, `LiftoffDatabase.kt`, `MissionManager.kt`, `DatabaseExport.kt`, `DatabaseImport.kt`, `BackupDocument.kt`, `ReFlyCopy.kt`) |
| `com.liftoff.app.settings` | App-level configuration backed by DataStore: daemon host, port and token, coach workspace path, default pattern, sortie length in minutes, weight and distance units, history window in days, the run-generation toggle, and objectives and constraints as free text. | ✅ (`Settings.kt`, `SettingsStore.kt`) |
| `com.liftoff.app.domain` | State machines for Mission and Sortie lifecycle, plus the draft and sortie coach-status derivations. Pure Kotlin, no Android imports. | ✅ (Week, pattern, Mission and sortie rules, rollover, sortie planning, the Re-fly source chooser `ReFly.kt`, the draft's coach status `CoachStatus.kt`, the sortie card's generation status `SortieGenerationStatus.kt`) |
| `com.liftoff.app.coach` | Prompt builder, validator, JSON schemas — the three output schemas (`outline.json`, `lift-plan.json`, `run-plan.json`) sit in `app/src/main/resources/schemas/`. Pure Kotlin, no Android imports. | ✅ (`ExerciseNames.kt` exercise-name normalizer §7.7; the JSON Schema checker `CoachSchema.kt` and `SchemaChecker.kt`; the coach reply validator `CoachModels.kt` and `CoachReplyValidator.kt` against recorded-style reply fixtures in `app/src/test/resources/fixtures/coach/`; the prompt builder `CoachPromptBuilder.kt` and `PromptInputs.kt` with the three golden-file prompts in `app/src/test/resources/prompts/`) |
| `com.liftoff.app.work` | Android-side workers (`GenerationWorker`). Uses WorkManager and Android APIs. | ✅ (`CoachTurnRunner.kt` one coach turn over the copied rho client, with no Room or WorkManager; `GenerationWorker.kt` one generation end to end, reading the `Generation` row by id, running the turn and storing the reply or ending the row FAILED with its reason; `GenerationEnqueuer.kt` the enqueue helper, unique one-time work per generation kept rather than replaced; `GenerationCanceller.kt` cancel that work and the row, then give up its rho session; `AppOpenSweep.kt` re-enqueue or cancel every open row at each app open) |
| `com.liftoff.app.ui` | Navigation shell, Launchpad, Mission, In-Flight, Landed history and Mission Control as real screens, plus the shared sortie-plan and theme composables. | ✅ (`LiftoffShell.kt` and `ShellNav.kt` cold-start reopen into the in-flight sortie; `LaunchpadScreen.kt` with the draft's coach status line, the Pending and Planned cards' generation status, Retry, Regenerate, Re-fly, View in rho and Scrub; `MissionScreen.kt`; `InFlightScreen.kt` with Land, Scrub, notes and run content; `LandedScreen.kt` history list and detail view with its note editor; `ui/control/MissionControlScreen.kt` settings, equipment, Test connection and export/import; the shared plan section in `ui/sortie/FlightPlanSection.kt`; the theme composables in `ui/theme/LiftoffTheme.kt`) |

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
| M2 | Missions and sorties — domain state machines, draft/confirm UI, Launchpad, Scrub | Done — the week, pattern and sortie rules, the draft and confirm flow, the Launchpad cards and Scrub are all built |
| M3 | Generation — rho client copy, `GenerationWorker`, outline + lift plan end to end | Done — `GenerationWorker.kt` runs one coach turn per `Generation` row through `CoachTurnRunner.kt` and stores the validated reply, with resume, offline and supersede handled there; `GenerationEnqueuer.kt` and `GenerationCanceller.kt` queue and cancel that work; `MissionManager.kt` queues the outline on Confirm and a Flight Plan row as each sortie becomes current; `LaunchpadScreen.kt` draws the status derived from that row through `SortieGenerationStatus.kt`, and `MissionManager.regenerateFlightPlan` queues a replacement plan for the current sortie |
| M4 | In flight — In-Flight screen, Launch, Land, resume after process kill | Done; the In-Flight screen is a real screen; the shell reopens into the in-flight sortie on a cold start; the screen leaves when its sortie is no longer in flight |
| M5 | Landed and safety nets — history screens, Re-fly, export/import | Partial: the Landed history list and its detail view are built; Re-fly is built — the pure source chooser, the copy through MissionManager and the Launchpad's PENDING action are in place; export and import are built |
| M6 | Run plans — run-generation toggle, run Flight Plans | Not started |
