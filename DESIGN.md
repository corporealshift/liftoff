# Liftoff — Project Design

**Date:** 2026-10-05
**Author:** Kyle (corporealshift), with Claude
**Status:** Approved 2026-10-05
**Source brief:** `brief.md`

This is the design that the implementation builds from. It records what was decided, why,
and what was rejected. Where it says MUST, the implementation has no latitude; everything
else is the intended shape and may be refined in an amendment (see the end of the
document).

---

## 1. Purpose

Liftoff is a personal Android app that:

1. **Plans training weeks.** Each week follows a pattern of run and lift sessions, such as
   `R L R L R`. A local LLM, reached through nabu, produces a balanced outline for the week
   and then a detailed workout for each session.
2. **Runs the workout at the gym as a checklist.** Every set is pre-filled with the planned
   reps and weight. One tap checks a set off, and the numbers are edited only when what was
   done differs from the plan.
3. **Keeps a history of completed sessions.** That history is fed back into the next plan,
   so the load progresses based on what was actually lifted.

### The problem it solves (from the brief)

- Coming up with well-rounded lifting sessions is hard.
- The gym is small, so plans must use only the equipment that is actually there.
- The week has a structure: 2–3 lift days and 2–3 run days, alternating.
- Completed workouts should be recorded.

### Users

One: the owner. Liftoff is built for one person, installed directly onto one phone, and talks to
one nabu daemon on the owner's PC.

### Non-goals (v1)

- Accounts, multiple users, cloud sync, a backend server, or a Play Store release.
- Any paid LLM API. Generation runs on the owner's local model via nabu.
- LLM-driven exercise swaps mid-workout (e.g. "the rack is taken"). Manual edits only.
- Rest timers, charts and analytics, wearable or Health Connect integration.
- Generating anything while the phone is offline. Offline, the app reuses plans; it does
  not create them.

---

## 2. Vocabulary

The app uses space theming throughout. These terms are used in the UI **and** in the code.

| Term | Meaning | Origin |
|---|---|---|
| **Flight Plan** | A planned session that hasn't been started: a lift workout or a run | brief |
| **Launch** | Starting a Flight Plan. Tracking begins | brief |
| **In flight** | A launched session that hasn't been finished | this design |
| **Landed** | A completed session; also the name of the history screen | brief |
| **Mission** | One week: its pattern, its outline and its sessions | this design |
| **Mission Control** | The settings screen | this design |
| **Scrub** | Skipping a session on purpose | this design |
| **Re-fly** | Reusing a previous Flight Plan when a new one can't be generated | this design |
| **Coach** | The nabu agent that writes outlines and Flight Plans | this design |

Code names: `Mission`, `Sortie` (one slot in a Mission, holding a run or lift session —
"session" is avoided in code because nabu already uses it), `FlightPlan`, `Generation`.

---

## 3. Topology

```
┌──────────── phone ────────────┐          ┌──────────────── owner's PC ────────────────┐
│ Liftoff (Kotlin/Compose)      │          │ nabu daemon ──► llama-server (local model) │
│  Room DB  ← source of truth   │◄────────►│     │                                      │
│  WorkManager generation jobs  │ Tailscale│     └─ session in workspace                │
│  copied nabu DaemonClient     │  WS+JSON │        liftoff-coach/  (COACH.md, notes)   │
└───────────────────────────────┘   -RPC   └────────────────────────────────────────────┘
```

- **Liftoff is a nabu client.** It speaks nabu's protocol (`nabu/protocol/spec.md`,
  JSON-RPC 2.0 over WebSocket, version 1.x) to the daemon over Tailscale, using the bearer
  token, the same way nabu's own Android client does.
- **Each generation is one nabu session.** The session is created in a dedicated coach
  workspace on the PC. The agent there has nabu's full toolset (memory, notes, web search
  if configured, `claude.ask` if available), plus `COACH.md`, which the owner edits to tune
  the coaching.
- **The phone is the source of truth** for missions, plans and history. The coach is
  stateless with respect to the training data: every prompt carries what it needs. What the
  coach keeps in nabu memory and notes is its own working judgment, never the record.
- **Nothing in nabu changes.** Liftoff uses only existing protocol methods. If Liftoff ever
  needs a nabu change, that needs its own spec in the nabu repo.

### Rejected alternatives

| Alternative | Why not |
|---|---|
| Phone calls `llama-server` directly with a JSON-schema `response_format` | The JSON is guaranteed valid, but there are no tools, no memory, no inspectable record of why a plan was chosen, and llama-server would need its own exposure and auth on Tailscale. Kept as the **fallback for the final JSON step** if milestone M0 fails (§14). |
| nabu for outlines, llama-server direct for Flight Plans | Two integrations to maintain for a marginal gain. |
| Claude API (Messages API) | Usage is billed separately from the owner's Pro subscription. The owner chose the local model. |
| Backend proxy and cloud storage | One user; nothing to proxy for. |
| On-device LLM | Too weak to program a training week well. |
| Coach writes the plan to a file and nabu's `verify` gate validates it | `verify` runs only when files change, and only the phone knows the current equipment list and units. Validation belongs on the phone (§7.6). |

---

## 4. Missions (weeks)

### 4.1 Pattern

- A Mission is one calendar week, **Monday to Sunday in the phone's local time zone**.
- Its **pattern** is an ordered list of sortie types, `R` or `L`, between 1 and 7 long.
- The **default pattern** is set in Mission Control and starts as `R L R L R`.
- Each Mission's pattern can be **overridden** while the Mission is a draft, for example
  `L R L R L` for an alternate week. Once confirmed, the pattern is frozen.

### 4.2 Lifecycle

```
           confirm (online)                last sortie landed/scrubbed, or week ends
 DRAFT ─────────────────────► ACTIVE ─────────────────────────────────────► CLOSED
   │                            ▲
   └── outline generation ──────┘ (ACTIVE begins when the outline is stored)
```

- **Draft.** When the app is opened in a week that has no Mission, it creates a `DRAFT` for
  that week with the default pattern. The draft shows the pattern as editable chips.
- **Confirm.** Confirming the draft queues an **outline generation** (§7). The Mission
  becomes `ACTIVE` when the outline has been stored, and the Flight Plan generation for
  sortie 1 is queued at that point.
  - If outline generation fails, the Mission stays a draft with an error and a retry.
  - "Continue without outline" activates the Mission with no outline. Lift sorties get the
    focus "full body", runs get the focus "easy", and each Flight Plan is generated from
    history alone.
- **Closed.** A Mission closes when every sortie has landed or been scrubbed, or when its
  week ends. Any sortie not landed by the end of the week is marked `SCRUBBED`
  automatically, with the reason "week ended". **Unfinished sorties do not carry over** —
  each week starts clean.
- A Mission is never created ahead of its week. Planning next week early is out of scope
  for v1.

### 4.3 Rolling order

- Sorties are done **in pattern order, regardless of weekday**. The Launchpad always offers
  the lowest-index sortie that is neither landed nor scrubbed.
- A missed Tuesday does not skip anything. The next sortie is simply done on Wednesday.
- The owner can scrub the current sortie. The next sortie then becomes current, and its
  Flight Plan generation is queued if it isn't already planned.
- Sorties cannot be reordered within a Mission (v1).

---

## 5. Sorties and Flight Plans

### 5.1 Sortie states

```
PENDING ──generation stored──► PLANNED ──launch──► IN_FLIGHT ──land──► LANDED
   │                              │                    │
   └──────────────scrub───────────┴────────scrub───────┴──► SCRUBBED
```

- **PENDING:** the sortie has no Flight Plan yet. Its generation may be queued, running or
  failed.
- **PLANNED:** the Flight Plan is stored and the sortie can be launched. The owner can
  regenerate it from here, which replaces the plan.
- **IN_FLIGHT:** at most one sortie, across all Missions, is in flight at a time.
  Scrubbing a sortie that is in flight keeps the sets already checked as a partial record.
- **LANDED:** stores the landed time and the actual sets. It cannot be edited after
  landing, apart from its notes.

### 5.2 Lift sorties

A lift Flight Plan is an ordered list of exercises, and each exercise is an ordered list of
planned sets (§7.5). Each planned set holds either reps or a duration in seconds, plus an
optional weight. A missing weight means bodyweight.

### 5.3 Run sorties

Runs are always in the rotation. Mission Control has a toggle, **Generate run plans**,
which is off by default:

- **Off.** A run sortie gets a simple Flight Plan titled "Run" (`source = SIMPLE_RUN`),
  holding the outline's run focus if there is one. No generation is involved. The plan is
  created at the moment a Flight Plan generation would otherwise be queued for it (§7.2). At landing the owner can
  enter a distance and duration; both are optional.
- **On.** Run sorties get a generated Flight Plan (§7.5): a kind (easy, tempo, intervals,
  long or recovery), a target distance and pace, and segments.

The toggle is read at generation time. Changing it never alters a Flight Plan that already
exists.

### 5.4 Re-fly

When a sortie is PENDING and its generation has failed, or the coach can't be reached, the
Launchpad offers **Re-fly**. Re-fly copies the most recent landed Flight Plan of the same
type, preferring one with the same outline focus. The copy uses the planned sets, not the
actual ones, and the plan is marked `source = REFLY`. Re-fly works fully offline.

---

## 6. In flight (logging at the gym)

- **Launch** sets the sortie to `IN_FLIGHT` and opens the In-Flight screen: the
  exercises in order, each set as a row showing the planned reps or seconds and weight.
- **Check a set off:** one tap marks it done, with actual values equal to the planned ones.
- **Edit a set:** a long press, or tapping the numbers, opens a reps/weight stepper. Saving
  the edit also marks the set done.
- **Skip:** a set or a whole exercise can be marked skipped.
- **Add a set:** an extra set can be added to an exercise.
- **Notes:** an optional free-text note per exercise and per sortie, e.g. "left shoulder
  twinge".
- **Land:** finishes the sortie. Sets left unchecked are recorded as not done. If there are
  any, the app asks for confirmation before landing.
- **Every action is written to Room immediately.** If the process is killed, the In-Flight
  screen comes back exactly as it was left.
- **No network is needed anywhere in this flow.**

---

## 7. Generation via nabu

### 7.1 The coach workspace

The coach workspace is a directory on the PC, e.g. `C:/Users/corpo/liftoff-coach`. It is
its **own git repository and never this one**: the coach can write in its workspace, and it
must not be able to edit the app's source.

This repo carries the workspace template under `coach/`. The owner copies it out once.

```
coach/
  README.md     how to set the workspace up (below)
  COACH.md      the coach's standing instructions (§7.1.1)
```

**One-time setup on the PC** (documented in `coach/README.md`):

1. Copy `coach/` to the workspace path and run `git init` there.
2. In `~/.nabu/config.json`, turn off the verify gate for that path:
   `"modules": {"verify": {"commands": {"<workspace path>": ""}}}`. Without this, a global
   verify command such as `go build ./...` would run whenever the coach touched a file.
3. Ensure the daemon is reachable from the phone over Tailscale, and note its host, port
   (default 8737) and token for Mission Control.

#### 7.1.1 COACH.md contents

`COACH.md` holds the coaching judgment. The output contract lives in the prompt (§7.4),
because the app owns the contract and versions it. COACH.md MUST say:

- **Role.** You are the coach for one person's training. You plan weeks and individual
  sessions.
- **Never ask questions.** There is nobody to answer. Make the call yourself and state any
  assumption in the `notes` field.
- **The final message is the answer.** It must be exactly one JSON object matching the
  schema given in the prompt: no prose before or after it, and no code fence.
- **Programming principles.** These are the owner's to edit; the initial content should
  include:
  - Balance push, pull, legs, hinge and core across the week's lift sorties.
  - Progress load conservatively, using the actual sets in history. If every set was hit at
    a weight, increase it; if sets were missed, hold or reduce.
  - Put leg-heavy lifting away from the day after a hard run where the pattern allows.
  - Keep the session within the stated length.
- **Exercise names.** Reuse names from the "exercises used before" list for the same
  movement, so that history lines up. Introduce new names only for new movements.
- **Memory and notes.** You may use `memory.save` for durable facts about the athlete that
  are not in the prompt, such as how they respond to a movement. Use a `liftoff-progress`
  note for working observations. Neither ever replaces the history the prompt carries.
- **No writes outside notes and memory.** Do not create or edit files in the workspace.
  Do not run commands.

### 7.2 Generation kinds

| Kind | Trigger | Input | Output |
|---|---|---|---|
| `OUTLINE` | Mission confirmed | profile, pattern, history | one focus per sortie (§7.5.1) |
| `FLIGHT_PLAN` | outline stored (sortie 1); previous sortie landed or scrubbed (next sortie); "Regenerate" | profile, outline, this sortie, history | one Flight Plan (§7.5.2 / §7.5.3) |

- Generation is **queued for the next sortie as soon as the previous one lands**, so the
  plan is usually ready before the next gym visit.
- A run sortie with run generation off is never sent to the coach (§5.3).

### 7.3 Lifecycle of one generation

Generations run in a WorkManager `CoroutineWorker`:

- **Unique work** named `gen-<generationId>`, policy `KEEP`.
- **Constraint:** network connected.
- **Backoff:** exponential, starting at 1 minute.

The worker is **resumable**. All progress is stored on the `Generation` row (§8), so a
worker that is killed and restarted continues the same nabu session instead of starting a
new one. Note that the nabu session keeps running on the PC even if the phone's worker
dies.

1. **Connect.** Open the WebSocket and send `nabu.hello` with client `liftoff`. A
   protocol major-version mismatch is a **permanent failure** with a clear message; it is
   not retried.
2. **Create the nabu session, if the row has none yet.**
   - Call `nabu.session.create` with `workspace` set to the coach path, and these options:
     - `permission_mode: "auto"`
     - `labels: ["liftoff", "liftoff:outline"]` or `["liftoff", "liftoff:flight-plan"]`
     - `description`: a one-line summary such as "Liftoff outline — week of 2026-10-05"
   - Store the returned `session_id` before doing anything else.
   - Labels MUST NOT start with `run:`, because nabu's runner acts on those labels.
3. **Send the prompt.** Call `nabu.session.send_prompt` with the prompt (§7.4) and
   `client_id = "<generationId>-<attempt>"`. This makes a retry after a dropped
   connection idempotent (protocol §7.4).
4. **Wait for the turn to finish.**
   - Subscribe, and catch up with `events_after` from the stored `last_event_id`, saving the
     cursor as events arrive.
   - The turn is finished at the first `state_change` after our message whose `to` is
     `idle`, `completed`, `blocked`, `paused` or `error`.
   - Overall limit: 20 minutes from the attempt's start.
5. **Handle the outcome.**
   - **`idle` or `completed`:** take the content of the last assistant `message` and
     validate it (§7.6).
     - **Valid:** store the result in one transaction and mark the generation `SUCCEEDED`.
     - **Invalid, with fewer than 2 repairs used:** increment `attempt` and send a repair
       prompt in the **same session**: "Your reply was not valid: <list of errors>. Reply
       again with only the corrected JSON object." Then return to step 4.
     - **Invalid, with 2 repairs used:** mark the generation `FAILED`, reason "invalid
       output".
   - **`blocked`:** the coach asked a question or a gated call is waiting. Mark the
     generation `FAILED`, reason "coach needed input"; COACH.md forbids this. If the
     daemon sends a `nabu.rpc.ui.ask` or `nabu.rpc.permission.request` to Liftoff while it
     is subscribed, Liftoff answers with `"Decide yourself; there is nobody to ask."` or
     `deny` respectively, and keeps waiting.
   - **`paused` or `error`:** a retryable failure, so return `Result.retry()`. Before the
     retry, if the session is `paused`, call `nabu.session.resume`.
6. **Clean up.** On `SUCCEEDED` or permanent `FAILED`, call `nabu.session.stop`. Even if
   that call fails, nabu archives sessions untouched for 3 days on its own.

- **Unreachable daemon** (the connection is refused or times out): return
  `Result.retry()`, and the UI shows **"Mission Control offline"** (§9).
- **Retries before giving up:** after 8 attempts the generation is marked `FAILED`, reason
  "coach unreachable". The UI then offers Retry and Re-fly.
- **Superseded results:** when a regeneration is requested while a generation for the same
  sortie is queued or running, the old one is cancelled: its work is cancelled and its nabu
  session stopped. Results are stored only if the generation is still the sortie's
  current one.

### 7.4 Prompt contents

Prompts are plain text in Markdown sections, built by a pure function: the same inputs MUST
produce the same text. Sections, in order:

1. **Task.** One line: "Write the outline for this week" or "Write the Flight Plan for
   sortie N of this week".
2. **Instructions.** "Follow COACH.md in this workspace. Your final message must be only a
   JSON object matching the schema below."
3. **Athlete profile.** Objectives (free text), constraints (free text), target session
   length in minutes, units (lb or kg; mi or km).
4. **Equipment.** Every item as `id — name — notes`, e.g.
   `db — dumbbells — pairs 5–50 lb in 5 lb steps`. Exercises may use only these ids.
   Bodyweight needs no id.
5. **This week.**
   - The pattern, with each sortie's index, type and state.
   - The outline, if it exists, with the focus of each sortie.
   - For a Flight Plan, which sortie is being planned.
6. **History.** Landed and scrubbed sorties from the last **28 days** (configurable), newest
   first, one block per sortie:

   ```
   2026-10-01 LIFT "Push + core" — landed
     Bench press: 3×8 @135 lb ✓, 1×6 @135 lb (planned 8)
     Plank: 3×45 s ✓
     notes: "left shoulder twinge on last set"
   ```

7. **Exercises used before.** Every exercise name in history, sorted alphabetically.
8. **Output schema.** The JSON Schema for this generation kind (§7.5), verbatim.

### 7.5 Output schemas

The app holds these schemas as resources and validates against them. Field names are
`snake_case`.

#### 7.5.1 Outline

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["sorties", "notes"],
  "properties": {
    "sorties": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["index", "type", "focus"],
        "properties": {
          "index": { "type": "integer", "minimum": 0 },
          "type":  { "enum": ["lift", "run"] },
          "focus": { "type": "string", "minLength": 1, "maxLength": 60 },
          "rationale": { "type": "string", "maxLength": 300 }
        }
      }
    },
    "notes": { "type": "string", "maxLength": 1000 }
  }
}
```

`focus` examples: "Push + core", "Legs + pull", "Easy 5k", "Tempo".

#### 7.5.2 Lift Flight Plan

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["type", "title", "estimated_minutes", "exercises", "notes"],
  "properties": {
    "type": { "const": "lift" },
    "title": { "type": "string", "minLength": 1, "maxLength": 60 },
    "estimated_minutes": { "type": "integer", "minimum": 10, "maximum": 180 },
    "warmup": { "type": "string", "maxLength": 500 },
    "exercises": {
      "type": "array", "minItems": 3, "maxItems": 10,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["name", "equipment", "sets"],
        "properties": {
          "name": { "type": "string", "minLength": 1, "maxLength": 60 },
          "equipment": { "type": "array", "items": { "type": "string" } },
          "rest_seconds": { "type": "integer", "minimum": 0, "maximum": 600 },
          "notes": { "type": "string", "maxLength": 300 },
          "sets": {
            "type": "array", "minItems": 1, "maxItems": 8,
            "items": {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "reps":    { "type": "integer", "minimum": 1, "maximum": 50 },
                "seconds": { "type": "integer", "minimum": 5, "maximum": 600 },
                "weight":  { "type": "number",  "minimum": 0, "maximum": 1000 }
              }
            }
          }
        }
      }
    },
    "notes": { "type": "string", "maxLength": 1000 }
  }
}
```

Weights are in the owner's configured unit, which the prompt states.

#### 7.5.3 Run Flight Plan

Used only when run generation is on.

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["type", "title", "kind", "segments", "notes"],
  "properties": {
    "type": { "const": "run" },
    "title": { "type": "string", "minLength": 1, "maxLength": 60 },
    "kind": { "enum": ["easy", "tempo", "intervals", "long", "recovery"] },
    "target_distance": { "type": "number", "minimum": 0.5, "maximum": 50 },
    "target_pace": { "type": "string", "maxLength": 20 },
    "segments": {
      "type": "array", "minItems": 1, "maxItems": 20,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["description"],
        "properties": {
          "description": { "type": "string", "minLength": 1, "maxLength": 120 },
          "distance": { "type": "number", "minimum": 0 },
          "minutes":  { "type": "number", "minimum": 0 }
        }
      }
    },
    "notes": { "type": "string", "maxLength": 1000 }
  }
}
```

Distance and pace are in the owner's configured unit, e.g. `"9:30/mi"`.

### 7.6 Validation (the phone is the authority)

Validation is a pure function from (raw text, generation context) to either a parsed result
or a list of human-readable errors. The error list is sent back verbatim in repair prompts,
so each error must be specific, e.g. `exercises[2].equipment: "barbell" is not in the
equipment list`.

1. **Extract.** Trim the text. If it is wrapped in a single Markdown code fence, unwrap
   it. Then parse exactly one JSON object; trailing prose is an error.
2. **Schema.** Validate against the schema for this kind (§7.5).
3. **Semantic checks:**
   - **Outline:** there is exactly one entry per pattern position, indexes run
     `0..n-1`, and each `type` matches the pattern at that index.
   - **Lift:**
     - Each set has exactly one of `reps` or `seconds`.
     - Every `equipment` id exists in the *current* equipment list.
     - `estimated_minutes` is no more than 1.25 × the configured session length.
     - Exercise names are unique within the plan.
   - **Run:** `type` matches a run sortie.
   - **Every kind:** the plan's `type` matches the sortie being planned.

### 7.7 Exercise identity

Each exercise is stored once, in an `Exercise` table keyed by its **normalized name**:
lower-case, punctuation removed except `-`, whitespace collapsed. History, the "exercises
used before" list (§7.4) and progression all join on this table. This only lines up if the
coach reuses names, which COACH.md asks it to do (§7.1.1).

---

## 8. Data model

The data lives in **Room**. Settings live in **DataStore Preferences**.

| Entity | Fields (beyond `id`) |
|---|---|
| `Mission` | `weekStart` (LocalDate, Monday; unique), `pattern` (string like `"RLRLR"`), `status` (DRAFT/ACTIVE/CLOSED), `outlineNotes?` |
| `Sortie` | `missionId`, `index`, `type` (RUN/LIFT), `focus?`, `focusRationale?`, `state` (§5.1), `launchedAt?`, `landedAt?`, `scrubReason?`, `notes?`, `runDistance?`, `runMinutes?` |
| `FlightPlan` | `sortieId` (unique: the current plan), `source` (GENERATED/REFLY/SIMPLE_RUN), `title`, `estimatedMinutes?`, `warmup?`, `notes?`, `runKind?`, `targetDistance?`, `targetPace?`, `rawJson` (the validated output, for debugging) |
| `PlannedExercise` | `flightPlanId`, `order`, `exerciseId`, `equipmentIds` (list), `restSeconds?`, `notes?`, `skipped`, `userNotes?` |
| `PlannedSet` | `plannedExerciseId`, `order`, `reps?`, `seconds?`, `weight?`, `actualReps?`, `actualSeconds?`, `actualWeight?`, `status` (OPEN/DONE/SKIPPED), `added` (bool: added mid-flight) |
| `RunSegment` | `flightPlanId`, `order`, `description`, `distance?`, `minutes?` |
| `Exercise` | `normalizedName` (unique), `displayName` |
| `Generation` | `kind`, `missionId`, `sortieId?`, `status` (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED), `nabuSessionId?`, `lastEventId?`, `attempt`, `error?`, `createdAt`, `finishedAt?` |
| `Equipment` | `key` (short id used in prompts, unique, `[a-z0-9_]+`), `name`, `notes`, `active` |

- **Removing equipment** marks it inactive rather than deleting it, so history that refers
  to it stays readable. Inactive items are left out of prompts.
- **Settings (DataStore):**
  - daemon host, port and token
  - coach workspace path
  - default pattern, session length (minutes), units, history window (days)
  - run-generation toggle
  - objectives and constraints (free text)
- **Storage of the token.** The token is stored the way nabu's Android client stores it: in
  app-private DataStore. The daemon is reachable only over loopback and Tailscale. This is
  a deliberate match with nabu, not an oversight.
- **Export and import.** Mission Control can export the whole database to a single JSON
  file through the system file picker, and import it into an empty database. This is the
  backup story, since the phone is the only copy.

---

## 9. Screens

Built with Compose and Material 3. Navigation is a bottom bar: **Launchpad · Mission ·
Landed**. Mission Control opens from a top-bar icon.

- **Launchpad** (home). The current sortie, shown according to its state:
  - **PLANNED:** the plan summary (title, the exercise list or run summary, estimated time),
    with **Launch**, Regenerate and Scrub.
  - **PENDING:** generation status ("Coach is planning…", "Mission Control offline —
    retrying", or a failure with its reason). Retry, **Re-fly** and Scrub are available,
    plus "View in nabu", which shows the nabu session id so it can be opened in nabu's app.
  - **IN_FLIGHT:** a Resume button.
  - **No Mission this week:** the draft, with editable pattern chips and **Confirm**.
- **In-Flight.** The checklist described in §6.
- **Mission.** This week's pattern, the outline and its notes, and each sortie with its
  state. Tapping a sortie shows its plan, or for a landed sortie its record.
- **Landed.** History, newest first, grouped by week, with a detail view per sortie.
- **Mission Control.** Every setting in §8, plus:
  - equipment management (add, edit, deactivate)
  - export and import
  - "Test connection", which runs `nabu.hello` and shows the daemon and protocol versions

---

## 10. Failure handling

| Failure | Behaviour |
|---|---|
| Daemon unreachable (PC off, Tailscale down) | The generation retries with backoff; the Launchpad says "Mission Control offline". Re-fly is available. |
| Protocol major version mismatch | Permanent failure: "Liftoff speaks nabu protocol 1.x, the daemon speaks N.x". Not retried. |
| Invalid output | Up to 2 in-session repairs, then FAILED with the last error list visible, plus Retry and Re-fly. |
| Coach blocks on a question or permission | FAILED, reason "coach needed input"; the owner fixes COACH.md or the config. |
| Phone worker killed mid-generation | Resumes the same nabu session from the stored cursor. |
| Daemon restarted mid-generation | nabu pauses the session; the worker resumes it (§7.3 step 5). |
| App killed mid-workout | Nothing is lost; every tap is already in Room. |
| Coach suggests a bad weight | The owner edits the actual values. The next plan sees the actuals and adjusts. |
| Equipment removed after a plan was generated | The existing plan is kept. Validation applies only to new generations. |
| Lost or reset phone | Restore from the last JSON export. |

---

## 11. Technology

The stack matches nabu's Android client (`nabu/clients/android/app/build.gradle.kts`), so
the copied code compiles unchanged and both apps age together:

- Kotlin, JVM target 17, Jetpack Compose with Material 3, `minSdk 26`, `targetSdk 35`
- kotlinx.serialization (JSON), coroutines, OkHttp 4 (WebSocket)
- Room (with KSP), DataStore Preferences, WorkManager
- JSON Schema validation: a small validator written in-house for the subset of JSON Schema
  used in §7.5 (`type`, `const`, `enum`, `required`, `additionalProperties`, min/max
  bounds, `minLength`/`maxLength`, `minItems`/`maxItems`). This avoids a heavy dependency.
- **No dependency-injection framework.** A single `AppContainer` builds the dependencies by
  hand.
- Tests: JUnit 4, Robolectric, kotlinx-coroutines-test, OkHttp MockWebServer, room-testing,
  work-testing

**Reused from nabu:** `net/DaemonClient.kt` and the parts of `protocol/` that Liftoff needs
(`Events.kt`, `RpcCodes.kt`) are **copied** into `app/src/main/java/.../nabu/`, with a
header comment recording the nabu commit they came from. They are copied rather than shared
as a library, because a shared library is premature with two clients. Drift is caught at the
handshake: a protocol major-version mismatch fails loudly.

Package: `com.liftoff.app`.

---

## 12. Repository layout (target)

```
DESIGN.md                     this document
brief.md                      the original brief
coach/                        template for the coach workspace (§7.1)
  README.md
  COACH.md
app/
  build.gradle.kts
  src/main/java/com/liftoff/app/
    MainActivity.kt
    AppContainer.kt
    nabu/                     copied DaemonClient + protocol types (§11)
    data/                     Room entities, DAOs, database, export/import
    settings/                 DataStore settings
    domain/                   pure logic: mission/sortie state machines,
                              next-sortie selection, rollover, re-fly selection
    work/                     Android-side workers: GenerationWorker
    ui/                       launchpad/ inflight/ mission/ landed/ control/ theme/
  src/main/resources/schemas/ outline.json, lift-plan.json, run-plan.json
  src/test/...                unit and Robolectric tests
  src/test/resources/fixtures/ recorded coach replies (valid and invalid)
settings.gradle.kts
build.gradle.kts
gradle.properties
```

**Domain logic in `domain/` and `coach/` is pure Kotlin with no Android imports**, so it is
unit-testable on the JVM.

---

## 13. Testing and verification

**The project gate:**

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

It MUST pass before any piece of work is called done.

**Unit tests** (JVM, no device):

- **Missions:**
  - A draft is created with the default pattern.
  - The pattern can be overridden while a draft and is frozen on confirm.
  - Rollover at the week boundary scrubs open sorties with "week ended".
  - No carry-over between weeks.
  - Time-zone handling of the Monday boundary.
- **Sorties:**
  - Every legal transition in §5.1 succeeds, and every illegal one is rejected.
  - Next-sortie selection skips landed and scrubbed sorties.
  - At most one sortie is in flight.
- **Re-fly:** prefers a plan of the same type with the same focus, falls back to the same
  type only, copies planned values and not actuals, and works with no network.
- **Prompt builder:** golden-file tests, where the same inputs give byte-identical prompts.
  Also covers the history formatting, the 28-day window and the sorted exercise names.
- **Validator:** run against `fixtures/`.
  - Valid outlines and plans parse.
  - Each class of error gives a specific message: bad JSON, prose around the JSON, a code
    fence, a schema violation, unknown equipment, a pattern mismatch, both reps and
    seconds on one set, and over-length sessions.
- **Exercise normalization:** spelling variants map to one exercise.

**Integration tests** (Robolectric):

- `GenerationWorker` against a **fake nabu daemon**, an OkHttp MockWebServer WebSocket that
  scripts the protocol. This is the same approach nabu's Android client uses. Cases:
  - the happy path
  - an invalid reply, then a repair, then success
  - two failed repairs, ending FAILED
  - the session ends `blocked`
  - an incoming `ui.ask` is answered
  - a `paused` session is resumed
  - the connection drops mid-turn and the worker resumes from the stored cursor
  - `send_prompt` is idempotent on retry
  - a major-version mismatch is a permanent failure
  - a regeneration supersedes the running generation
- Room DAO tests, and an export → import round trip that gives an identical database.

**Live test** (skipped unless `LIFTOFF_LIVE_HOST`, `LIFTOFF_LIVE_PORT`,
`LIFTOFF_LIVE_TOKEN` and `LIFTOFF_LIVE_WORKSPACE` are set, so the gate stays hermetic):
`LiveCoachTest` runs real generations against the real daemon and the local model. This is
the M0 measurement (§14).

**On the device:** one real week, covering:

- confirming a Mission and seeing the outline
- launching and landing a lift sortie at the gym with no signal
- the next plan arriving after landing
- one scrub, and one Re-fly with the PC off

---

## 14. Milestones

Each milestone ends with the project gate passing.

- **M0 — Coach reliability spike.** Set up `coach/`, the validator, the schemas, the prompt
  builder and `LiveCoachTest`.
  - Run 10 outline generations and 10 lift Flight Plan generations against the real local
    model, with representative fixture profiles and history.
  - Record each reply as a fixture.
  - **Pass:** all 20 are valid within the 2-repair budget, and at least 7 of each 10 are
    valid on the first try.
  - **Fail:** stop and write an amendment that moves the final JSON step to llama-server
    with a grammar-constrained schema (approach B in §3). The rest of the design stands.
- **M1 — Skeleton and data.** Gradle project, Room schema, settings, AppContainer, theme,
  navigation shell, and Mission Control including the equipment list.
- **M2 — Missions and sorties.** The domain state machines, the draft/confirm UI with no
  generation (confirming uses "Continue without outline"), the Launchpad, and Scrub.
- **M3 — Generation.** The copied nabu client, `GenerationWorker`, outline and lift Flight
  Plan generation end to end, regenerate and supersede, and the offline status display.
- **M4 — In flight.** The In-Flight screen, Launch and Land, and resume after the process
  is killed.
- **M5 — Landed and safety nets.** History screens, Re-fly, export and import.
- **M6 — Run plans.** The run-generation toggle and run Flight Plans.

---

## 15. Decisions and rejected alternatives

| # | Decision | Chosen | Rejected and why |
|---|---|---|---|
| 1 | Audience | One owner, installed directly on one phone | Multi-user / Play Store (several times the scope; no need) |
| 2 | Platform | Native Kotlin + Compose, matching nabu's client | Flutter (no reuse of nabu's Kotlin client, cross-platform not needed); PWA (weaker offline and storage; owner asked for Android) |
| 3 | LLM | Local model via a nabu session in a coach workspace | Claude API (billed separately from Pro); llama-server direct (no tools, memory or inspectability; kept as M0 fallback); on-device model (too weak) |
| 4 | Schedule | Weekly pattern (default `RLRLR`, overridable per week before confirm), rolling order within the week | Fixed weekday calendar (a missed day loses a session); strict alternation across weeks (owner wants explicit weekly control); asking each day (no structure) |
| 5 | Carry-over | Unfinished sorties are scrubbed at week end | Carrying them into next week (pattern stops meaning anything) |
| 6 | Generation timing | Outline on confirm, then each Flight Plan when the previous sortie lands | Whole week up front (weights can't adapt mid-week); one at a time with no outline (nothing balances the week) |
| 7 | Logging | Pre-filled sets, one tap to check, edit only on deviation | Exercise-level checkboxes (no load data, no progression); full log with RPE (too much typing at the gym) |
| 8 | Runs | Always in rotation; LLM run plans behind a toggle | Runs ignored (no fatigue awareness); always generated (owner wants it optional) |
| 9 | Output contract | Lives in the prompt, owned and versioned by the app; COACH.md holds judgment only | Schema in COACH.md (app and workspace could disagree silently) |
| 10 | Validation | On the phone, with in-session repair (≤2) | nabu `verify` gate on a written file (only runs on file changes; phone owns equipment and units) |
| 11 | Source of truth | Phone (Room); every prompt carries the history it needs | History kept in the coach workspace (needs the PC to read your own log; not offline) |
| 12 | nabu client code | Copy `DaemonClient` and protocol types | Shared Kotlin library (premature for two clients) |
| 13 | Token storage | App-private DataStore, as nabu's client does | Keystore encryption (inconsistent with nabu; the daemon is only on loopback and Tailscale) |
| 14 | JSON Schema | Small in-house validator for the subset used | A full JSON Schema library (heavy for a fixed, small subset) |

---

## 16. Assumptions and open questions

- **The PC is usually on and reachable over Tailscale.** Plans are generated right after
  landing, which is often at the gym, so this matters. If it turns out to be wrong,
  generation simply happens later, and Re-fly covers the gap.
- **The local model is `qwen3.6-35b-a3b`, or whatever nabu's `default_model` is.** Liftoff
  doesn't choose a model; the coach session uses the daemon's default. M0 measures whether
  that model is good enough.
- **How the lift sorties split muscle groups is left to the coach** (COACH.md), not encoded
  in the app. If the owner wants a fixed split, that is a COACH.md edit, not an app change.
- **Time-based logging of runs** (live GPS, timers) is out of scope. Runs record an optional
  distance and duration at landing.

---

## Amendments after approval

Changes made to this document after approval are listed here, newest first, and marked
inline in the section they affect: what changed, when, and why.

- **2026-10-06:** `GenerationWorker` moved from `coach/` to a new `work/` line (§12).
  `coach/` is pure Kotlin with no Android imports (invariant §12, ARCHITECTURE.md invariant 2),
  but `GenerationWorker` uses WorkManager and Android APIs. The work tree in `work/` holds the
  worker; domain logic it depends on stays in `coach/`.
