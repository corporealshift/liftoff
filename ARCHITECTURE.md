# Liftoff — Architecture

Map of the project: topology, package layout, resource locations, data flow, invariants and milestones.

**Authority:** decisions live in `DESIGN.md`. This doc is the operational map.

---

## Topology

```
┌──────────── phone ────────────┐          ┌──────────────── owner's PC ────────────────┐
│ Liftoff (Kotlin/Compose)      │          │ nabu daemon ──► llama-server (local model) │
│  Room DB  ← source of truth   │◄────────►│     │                                      │
│  WorkManager generation jobs  │ Tailscale│     └─ session in workspace                │
│  copied nabu DaemonClient     │  WS+JSON │        liftoff-coach/  (COACH.md, notes)   │
└───────────────────────────────┘   -RPC   └────────────────────────────────────────────┘
```

Liftoff is a **nabu client**. It speaks nabu's JSON-RPC 2.0 over WebSocket to the daemon on the owner's PC, using the same bearer-token auth as `nabu/clients/android`. The phone holds all training data in Room; every generation prompt carries the history it needs so the coach stays stateless about training data.

---

## Package layout

All source lives under `com.liftoff.app`.

| Package | Responsibility | Exists yet? |
|---|---|---|
| `com.liftoff.app` (root) | `MainActivity` (hosts the navigation shell), `AppContainer.kt`, app entry point. | ✅ (`MainActivity.kt`, `AppContainer.kt`, `LiftoffApplication.kt`) |
| `com.liftoff.app.nabu` | Copied nabu `DaemonClient` and protocol types. No changes to upstream nabu. | — |
| `com.liftoff.app.data` | Room entities (including Equipment), DAOs, database builder, export/import helpers. | ✅ (Room entities, DAOs, LiftoffDatabase, the export and import data layer) |
| `com.liftoff.app.settings` | App-level configuration backed by DataStore (units, run-toggle). | ✅ (`SettingsStore.kt`) |
| `com.liftoff.app.domain` | State machines for Mission and Sortie lifecycle. Pure Kotlin, no Android imports. | ✅ (Week, pattern, Mission and sortie rules, rollover, sortie planning, the Re-fly source chooser `ReFly.kt`) |
| `com.liftoff.app.coach` | Prompt builder, validator, JSON schemas — the three output schemas (`outline.json`, `lift-plan.json`, `run-plan.json`) sit in `app/src/main/resources/schemas/`. Pure Kotlin, no Android imports. | ✅ (`ExerciseNames.kt` exercise-name normalizer §7.7; the JSON Schema checker `CoachSchema.kt` and `SchemaChecker.kt`; the coach reply validator `CoachModels.kt` and `CoachReplyValidator.kt` against recorded-style reply fixtures in `app/src/test/resources/fixtures/coach/`; the prompt builder `CoachPromptBuilder.kt` and `PromptInputs.kt` with the three golden-file prompts in `app/src/test/resources/prompts/`) |
| `com.liftoff.app.work` | Android-side workers (`GenerationWorker`). Uses WorkManager and Android APIs. | — |
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
2. **Generation.** A WorkManager `GenerationWorker` connects to nabu over WebSocket, sends a prompt (§7.4), waits for the turn, validates the JSON reply (§7.6), and stores the result in Room.
3. **Queueing.** When a sortie lands, the next sortie's generation is queued automatically. The plan is usually ready before the next gym visit.

---

## Invariants

| # | Invariant | Reference |
|---|---|---|
| 1 | The phone is the source of truth; every prompt carries the history it needs. | §3 |
| 2 | `domain/` and `coach/` are pure Kotlin with no Android imports. | §12 |
| 3 | Nothing in nabu changes. Liftoff uses only existing protocol methods. | §3 |
| 4 | The nabu code is copied, with a header recording the nabu commit it came from. | §11 |
| 5 | No dependency-injection framework; a single `AppContainer` builds everything. | §11 |
| 6 | Generations are resumable from the `Generation` row (stored cursor). | §7.3 |
| 7 | Session labels never start with `run:` (nabu runner acts on those). | §7.3 |
| 8 | The phone validates every coach reply, with up to 2 in-session repairs. | §7.6 |

---

## Milestones

Each milestone ends with the project gate (`./gradlew :app:assembleDebug :app:testDebugUnitTest`) passing.

| Milestone | Title | Status |
|---|---|---|
| M0 | Coach reliability spike — coach workspace, validator, schemas, prompt builder, `LiveCoachTest` | Partial: the coach workspace template (`coach/README.md`, `coach/COACH.md`), the three schemas and the validator (`CoachReplyValidator.kt`) exist; the prompt builder (`CoachPromptBuilder.kt`, `PromptInputs.kt`) and its golden-file prompts exist; live measurement (`LiveCoachTest`) has not been run |
| M1 | Skeleton and data — Room schema, settings, `AppContainer`, theme, navigation shell, Mission Control including the equipment list | Done — test connection comes later; export and import are built |
| M2 | Missions and sorties — domain state machines, draft/confirm UI, Launchpad, Scrub | Done — Regenerate comes with generation (M3); In-Flight is a placeholder until M4 |
| M3 | Generation — nabu client copy, `GenerationWorker`, outline + lift plan end to end | Not started |
| M4 | In flight — In-Flight screen, Launch, Land, resume after process kill | Done; the In-Flight screen is a real screen; the shell reopens into the in-flight sortie on a cold start; the screen leaves when its sortie is no longer in flight |
| M5 | Landed and safety nets — history screens, Re-fly, export/import | Partial: the Landed history list and its detail view are built; Re-fly is built — the pure source chooser, the copy through MissionManager and the Launchpad's PENDING action are in place; export and import are built |
| M6 | Run plans — run-generation toggle, run Flight Plans | Not started |
