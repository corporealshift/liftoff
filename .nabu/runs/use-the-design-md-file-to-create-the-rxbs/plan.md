# Plan — Fundamentals for Liftoff

This plan covers everything needed to turn the empty repo (DESIGN.md + brief.md) into a working project scaffold: CI, Gradle project, architecture docs, coach workspace template, validator, schemas, prompt builder, and LiveCoachTest. This is essentially M0 from §14 of DESIGN.md, plus CI and architecture scaffolding that enable every subsequent milestone.

---

## 1. Approach and Why It Fits

The design doc is thorough and the codebase doesn't yet exist, so the approach is sequential: scaffold → coach tools → CI → architecture docs. Each step produces a compilable state and can be merged independently.

**Why this order:**
- The Gradle project must exist before anything else compiles.
- Coach tools (schemas, validator, prompt builder) are pure Kotlin functions/resources — no UI, no device needed — so they can be built first and tested immediately with JVM unit tests.
- CI is a single file that runs the project gate command from §13, so it only makes sense once the Gradle project exists.
- Architecture docs describe decisions already made in DESIGN.md; writing them after the scaffold ensures they reflect actual file names and module structure.

This matches the design's milestone ordering (M0 → M1→…) while collapsing M0's pieces into one fundaments pass, because they're all prerequisites for any further work.

---

## 2. Files and Parts of Code Involved

### A. Gradle Project Setup
| File | Purpose |
|---|---|
| `settings.gradle.kts` | Root project name, include `:app`, enable version catalogs |
| `build.gradle.kts` (root) | Plugin declarations, buildscript dependencies |
| `gradle/libs.versions.toml` | Version catalog for all dependencies (Compose, Room, WorkManager, OkHttp, JSON Schema validator, JUnit, MockWebServer, Robolectric) |
| `app/build.gradle.kts` | Android app module: minSdk 26 (nabu client baseline), compileSdk 34, AGP config, Compose BOM, Room compiler, WorkManager, dependency injection setup |
| `gradle.properties` | AndroidX flags, org.gradle.jvmargs |
| `.gitignore` | Standard Android + Gradle ignores |

### B. Coach Workspace Template (`coach/`)
| File | Purpose |
|---|---|
| `coach/README.md` | One-time setup instructions (copy to workspace, git init, nabu config tweak — from §3 and §7.1) |
| `coach/COACH.md` | Coach standing instructions (§7.1.1): role, never ask questions, JSON-only final message, programming principles, exercise names, memory/notes rules, no writes outside notes and memory |

### C. JSON Schema Resources (`app/src/main/res/raw/`)
| File | Purpose |
|---|---|
| `schema_outline.json` | Outline schema (§7.5.1) |
| `schema_lift_flight_plan.json` | Lift Flight Plan schema (§7.5.2) |
| `schema_run_flight_plan.json` | Run Flight Plan schema (§7.5.3) |

### D. Validator (`app/src/main/java/liftoff/validator/`)
| File | Purpose |
|---|---|
| `JsonSchemaValidator.kt` | In-house validator for the JSON Schema subset used (Decision #14: small in-house validator, not a full library). Supports: object type, required, additionalProperties, string minLength/maxLength, integer minimum/maximum, number minimum/maximum, enum, const, array minItems/maxItems/items, nested objects. No $ref, no anyOf/oneOf (not needed for these schemas). |
| `ValidationResult.kt` | Data class: `valid: Boolean`, `errors: List<String>` with specific messages per error type |

### E. Prompt Builder (`app/src/main/java/liftoff/generation/`)
| File | Purpose |
|---|---|
| `PromptBuilder.kt` | Pure function building prompt text from inputs (§7.4). Same inputs → same text (deterministic). Builds sections in order: Task, Instructions, Athlete profile, Equipment, This week (pattern + outline), History (last 28 days, newest first), Exercises used before (sorted), Output schema (read from resource JSON). |
| `PromptBuilderTest.kt` | Golden-file tests: same inputs give byte-identical prompts. Tests history formatting, 28-day window, sorted exercise names. |

### F. Fixtures (`app/src/test/fixtures/`)
| Directory/File | Purpose |
|---|---|
| `fixtures/valid_outline.json` | Valid outline for validator |
| `fixtures/invalid_prose_around_json.json` | Prose before/after JSON object |
| `fixtures/invalid_code_fence.json` | JSON wrapped in ```json ... ``` |
| `fixtures/invalid_schema_violation.json` | Schema violation (e.g., unknown equipment, both reps and seconds on one set) |
| `fixtures/invalid_unknown_equipment.json` | Equipment id not in prompt's equipment list |
| `fixtures/invalid_pattern_mismatch.json` | Outline sorties don't match mission pattern |
| `fixtures/invalid_reps_and_seconds.json` | Set with both reps and seconds |
| `fixtures/valid_lift_plan.json` | Valid lift Flight Plan |
| `fixtures/valid_run_plan.json` | Valid run Flight Plan |

### G. LiveCoachTest (`app/src/androidTest/java/liftoff/coach/`)
| File | Purpose |
|---|---|
| `LiveCoachTest.kt` | Runs real generations against the real nabu daemon and local model. Skipped unless `LIFTOFF_LIVE_HOST`, `LIFTOFF_LIVE_PORT`, `LIFTOFF_LIVE_TOKEN`, `LIFTOFF_LIVE_WORKSPACE` environment variables are set. Tests 10 outline + 10 lift Flight Plan generations with representative fixture profiles and history. Records each reply as a fixture. Pass criteria from §14 M0: all 20 valid within 2-repair budget, ≥7 of each 10 valid on first try. |

### H. CI (`/.github/workflows/ci.yml`)
| File | Purpose |
|---|---|
| `.github/workflows/ci.yml` | GitHub Actions workflow: on push/PR to any branch, run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Uses Android SDK setup action. Caches Gradle dependencies. |

### I. Architecture Docs (`.nabu/docs/`)
| File | Purpose |
|---|---|
| `.nabu/docs/architecture.md` | High-level architecture overview: module structure, data flow (Room → UI, WorkManager → nabu daemon), key classes and their responsibilities. References DESIGN.md for detailed specs. |
| `.nabu/docs/conventions.md` | Code conventions: naming (space-themed vocabulary from §2), comment style (one or two lines, why not what), test structure (golden-file for prompt builder, fixture-based for validator). |

### J. Misc
| File | Purpose |
|---|---|
| `README.md` | Project overview, how to set up the coach workspace, how to run tests |

---

## 3. Order of Work

1. **Gradle project scaffold** — settings.gradle.kts, build files, version catalog, app/build.gradle.kts, .gitignore, README.md. Result: `./gradlew :app:assembleDebug` succeeds (with no source files yet).
2. **Coach workspace template** — `coach/README.md`, `coach/COACH.md`. No compilation dependency; copy-paste from DESIGN.md §7.1.
3. **JSON Schema resources** — three schema files in `app/src/main/res/raw/`. Copy from DESIGN.md §7.5.
4. **Validator** — `JsonSchemaValidator.kt`, `ValidationResult.kt`, fixtures directory, fixture JSON files, validator tests. Tests verify each error class produces the correct message.
5. **Prompt builder** — `PromptBuilder.kt`, golden-file tests. The prompt builder reads schema resources from step 3 for the "Output schema" section.
6. **LiveCoachTest** — `LiveCoachTest.kt`. Skipped by default; only runs with env vars set.
7. **CI** — `.github/workflows/ci.yml`. Only makes sense after Gradle project exists.
8. **Architecture docs** — `.nabu/docs/architecture.md`, `.nabu/docs/conventions.md`. Written last so they reference actual file names and module structure.

Each step is self-contained and builds on the previous. Steps 2–5 can be tested with `./gradlew :app:testDebugUnitTest` after the Gradle scaffold.

---

## 4. Testing

**Project gate (§13):**
```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

This must pass at every step. Specifically:

- **Validator tests:** Run each fixture through `JsonSchemaValidator`. Valid fixtures return `valid = true`; invalid ones return `valid = false` with the expected specific error message (bad JSON, prose around JSON, code fence, schema violation, unknown equipment, pattern mismatch, both reps and seconds on one set, over-length sessions).
- **Prompt builder golden tests:** Build prompts from fixture inputs, compare against stored golden output files byte-for-byte. Test history formatting, 28-day window truncation, sorted exercise names.
- **LiveCoachTest:** Skipped unless env vars are set. When enabled, runs 20 real generations and checks pass criteria.

No device is needed for steps 1–7. The project gate uses only JVM tests and debug APK assembly.

---

## 5. Risks and Uncertainties

| Risk | Impact | Mitigation |
|---|---|---|
| Nabu DaemonClient copy — DESIGN.md §3 says "copied nabu DaemonClient" but the nabu repo isn't in this workspace. LiveCoachTest needs a working nabu connection, which requires the nabu daemon to be reachable over Tailscale. | M0 live test may fail if nabu protocol changes or Tailscale isn't configured. | The live test is skipped by default (gate stays hermetic). If it fails, the design says to move to approach B (§3 rejected alternatives table) for the final JSON step only — the rest stands. For now, we build the scaffold and assume the daemon connection works when env vars are set. |
| In-house JSON Schema validator scope | Building a validator from scratch vs using a library. DESIGN.md Decision #14 chooses in-house because the subset is small. | We only support the features actually needed by the three schemas: object, required, additionalProperties, string minLength/maxLength, integer minimum/maximum, number minimum/maximum, enum, const, array minItems/maxItems/items, nested objects. If we find we need more features, we reconsider. |
| Gradle version compatibility | The owner's machine may have a specific Gradle wrapper version pinned by other projects (farthing, nabu). | Use the latest stable AGP 8.x and Gradle 8.x that matches nabu's Android client baseline. Pin versions in libs.versions.toml so they're explicit and changeable. |
| Coach workspace path on Windows | The design uses `C:/Users/corpo/liftoff-coach` as an example (§7.1). The owner's machine is Windows (from git log: `corpo`). | COACH.md should use Windows-style paths. README.md setup instructions should account for this. |
