# Liftoff — CLAUDE.md

Read this first. Then read `DESIGN.md` (decisions) and `ARCHITECTURE.md` (map).

---

## Read first

- **`DESIGN.md`** holds every decision. Do not re-litigate decisions; propose changes as an
  amendment at the end of `DESIGN.md`.
- **`ARCHITECTURE.md`** is the operational map: package layout, invariants, milestones.
- When in doubt, ask: "Is this decided in DESIGN.md?" If yes, follow it.

---

## Build and test gate

The gate **must pass** before any piece of work is called done:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

On this machine (toolchain not on PATH), run through `gradlew.sh`:

```bash
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
```

`local.properties` is not needed — `gradlew.sh` exports `ANDROID_HOME`.

---

## Conventions

- **Commit messages:** `area: lowercase summary`. Not conventional-commits.
  Example: `docs: add architecture doc with invariants and milestone list`
- **Stage named files only.** Never `git add -A`.
- **§2 vocabulary in code.** Use `Mission`, `Sortie`, `FlightPlan`, `Generation`. Never
  "session" for a sortie.
- **snake_case JSON fields** in coach output.
- **LF line endings** except `*.bat` (see `.gitattributes`).
- **Short comments.** Say why, not what. One or two lines max.
