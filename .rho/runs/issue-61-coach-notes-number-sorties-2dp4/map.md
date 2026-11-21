# Map of the code — issue 61

Where the coach's sortie numbering lives, and what already asserts it.

## The prompt builder (the thing this issue changes)

- `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` (308 lines) — §7.4's pure prompt
  builder; `prompt(CoachPromptInput)` joins eight `##` sections with a blank line, `repairPrompt(errors)`
  is the §7.3 step-5 repair text. Byte-identical for identical inputs (no clock, no random, `Locale.ROOT`).
  - `task()` (54–61) — FLIGHT_PLAN reads `Write the Flight Plan for sortie ${input.sortieIndex} of this week`,
    i.e. the **0-based** index printed as "sortie N". Comment at 57–58 cites plan decision 2 for that.
  - `thisWeek()` (98–111) — `pattern: …`, then one line per sortie:
    `"index ${sortie.index} — ${word(type)} — ${word(state)}"`, with ` — focus: …` appended only for a
    FLIGHT_PLAN that has a focus (104–105), then `planning: index ${it.index} — ${word(type)}` (107–109)
    for the sortie being planned. `input.plannedSortie` is the source of that last line.
  - `section(heading, lines)` (263–270) — `## Heading`, blank line, then the lines.
  - `word(type)` / `word(state)` (296–307) — `lift`/`run`, `pending`/`planned`/`in flight`/`landed`/`scrubbed`.
  - Comment at 134 describes a history header as reading differently from "a `index 0 — run — …` week line"
    — stale once the week lines change.
- `app/src/main/java/com/liftoff/app/coach/PromptInputs.kt` — `PromptSortie(index, type, state, focus?)` (35–41),
  `PromptWeek(pattern, sorties)` (33), `CoachPromptInput(..., sortieIndex: Int?)` with `plannedSortie`
  (96–116). No new field is needed: the display number is `index + 1`, derived in the builder.
- `app/src/main/java/com/liftoff/app/data/PromptInputLoader.kt` (76–85) — builds `PromptWeek` from the
  pattern or from the confirmed sortie rows (`PromptSortie(it.index, it.type, it.state, it.focus)`).
  Unchanged by this issue.

## What already pins the prompt text

- `app/src/test/resources/prompts/` — `outline.txt` (1917 B), `lift-flight-plan-with-outline.txt` (2875 B),
  `run-flight-plan.txt` (2341 B). The lines this issue moves: the five week lines at 27–31 and the
  `planning:` line at 32 in the two Flight Plan files; the Task line at 3 in the two Flight Plan files
  (`Write the Flight Plan for sortie 1 of this week` / `… sortie 2 …`). `outline.txt` also carries the
  outline schema's `"index": { "type": "integer", "minimum": 0 }` at 71–73 — the 0-based contract stays.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptGoldenFileTest.kt` (263 lines) — compares the three
  prompts with the golden files **byte for byte** (UTF-8, so a stray CR fails). Fixed inputs at 99–200:
  a five-position `RLRLR` week, indices 0–4, first three carrying a focus; `sortieIndex = 1` for the lift
  golden and `2` for the run golden. Also asserts the outline prompt contains
  `## Task\n\nWrite the outline for this week\n`, and contains neither `planning:` nor `focus:` (38–41);
  `outputSchemaSectionIsTheResourceFileVerbatim` (75–95) pins the schema tail;
  `buildingTheSamePromptTwiceGivesIdenticalText` (58–68) pins purity.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt` (644 lines) — the per-rule tests.
  Exact week-line assertions: 354–356 (outline, `sectionLines(prompt, "This week")`) and 450–453
  (Flight Plan, with `focus:` and `planning: index 1 — lift`); exact Task-line assertion at 458
  (`Write the Flight Plan for sortie 1 of this week`); eight-sections-in-order test at 401;
  `sectionLines(prompt, heading)` helper at 630.
- `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` (139–141) — the index error reads
  `sorties[$position].index: must be $position`, i.e. array position wording, not "sortie N". `GenerationWorker`
  hands those lines to `repairPrompt` (`GenerationWorkerTest.kt:807` compares them byte for byte).
  Not part of this issue: no numbering the coach could copy.

## The numbering the prompt has to match (UI, unchanged)

- `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt:139` — `"%02d".format(row.index + 1)`, the
  `01`–`05` the issue's screenshot is about.
- `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt:211` — eyebrow
  `SORTIE ${sortie.index + 1} · TYPE · STATE`.
- `app/src/main/java/com/liftoff/app/ui/landed/LandedState.kt:96, 191` and
  `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt:116` (`eyebrow` "IN FLIGHT · SORTIE n") —
  the same `index + 1` everywhere. So the display number is `index + 1`, printed unpadded in prose and
  `%02d` on screen.

## The coach workspace (why the rule goes in the prompt)

- `coach/COACH.md` (43 lines) — the coach's standing instructions (§7.1.1): role, never ask questions,
  final message is JSON, programming principles, exercise names, notes/memory, no writes.
- `coach/README.md:3–11, 29–31` — `coach/` is a **template**; the coach runs in a copy on the PC that is its
  own repo, and "later changes to this template reach the copy only by re-copying it by hand". A rule added
  only to `coach/COACH.md` therefore does not reach the coach that is actually running.

- Golden-file shape: LF endings (`.gitattributes`: `* text=auto eol=lf`, `*.bat eol=crlf`), and the field
  separator is an em dash `—` (U+2014), the same character `CoachPromptBuilder` writes. A hand-edit that
  types `-` in its place differs from the builder by one code point and fails the byte comparison.

## Docs that set the rules

- `DESIGN.md` §7.4 (366–395) — item 1 the Task line, item 5 "This week": "The pattern, with each sortie's
  index, type and state." §7.5.1 (402–427) — the outline schema's 0-based `index`. Decision 9 (784) — the
  output contract lives in the prompt, COACH.md holds judgment only. "Amendments after approval" (808+) —
  changes are listed newest-first **and** marked inline in the section they affect.
- `CLAUDE.md` — decisions are not re-litigated, propose them as an amendment at the end of DESIGN.md; commit
  messages are `area: lowercase summary`; stage named files only, never `git add -A`; LF line endings.
- `ARCHITECTURE.md` invariant 2 (63) — `coach/` is pure Kotlin with no Android imports. Milestone M0 (79)
  names the prompt builder and its golden prompts.

## Gate on this machine

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` is the documented gate, but the committed
`gradlew.sh` pins Windows paths (`C:/Users/corpo/android-toolchain/...`), so on this Linux box run:

```
JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk \
  $HOME/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest
```

Confirm the test task actually executed (no `FROM-CACHE` / `UP-TO-DATE` / `NO-SOURCE` on
`:app:testDebugUnitTest`). Scratch files and gate logs go under gitignored `app/build/`, never `app/src/test`.
