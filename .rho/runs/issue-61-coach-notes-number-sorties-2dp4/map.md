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
  (`Write the Flight Plan for sortie 1 of this week` / `… sortie 2 …`). `pattern:` is line 26 in all
  three and does not change; `outline.txt`'s Task line does not change. Confirmed with `cat -A`: every
  line ends in LF with no CR, and the separator is the em dash `—` (U+2014, `M-bM-^@M-^T`).
  `outline.txt` also carries the outline schema's `"index": { "type": "integer", "minimum": 0 }` at
  71–73 — the 0-based contract stays.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptGoldenFileTest.kt` (263 lines) — compares the three
  prompts with the golden files **byte for byte** (UTF-8, so a stray CR fails). Fixed inputs at 99–200:
  a five-position `RLRLR` week, indices 0–4, first three carrying a focus; `sortieIndex = 1` for the lift
  golden and `2` for the run golden. Also asserts the outline prompt contains
  `## Task\n\nWrite the outline for this week\n`, and contains neither `planning:` nor `focus:` (38–41);
  `outputSchemaSectionIsTheResourceFileVerbatim` (75–95) pins the schema tail;
  `buildingTheSamePromptTwiceGivesIdenticalText` (58–68) pins purity.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt` (644 lines) — the per-rule tests.
  Only two methods hold exact prompt text this issue moves, and both must move with the builder in the
  same commit: `outlinePromptWeekListsNoFocus` (338–362, the `sectionLines(prompt, "This week")` list at
  351–359, its five entries being `pattern: RLRLR` plus the three week lines) and
  `flightPlanWeekShowsFocusAndNamesTheSortieBeingPlanned` (444–461, the week list at 447–456 with
  `focus:` suffixes and `planning: index 1 — lift`, plus the Task-line assertion at 457–460).
  Its own `week` fixture (38–44) is a **three**-sortie RLRLR week (indices 0–2) and the
  `flightPlan(history)` helper (549–556) sets `sortieIndex = 1`; a new test that needs indices 0–4
  builds its own five-position week rather than widening that fixture, which those two assertions read.
  `sectionLines(prompt, heading)` helper at 630 — lines past the blank line after `## Heading`, up to
  the next `##`, trailing blanks dropped. `promptCarriesTheEightSectionsInOrder` (401) counts headings
  only, so a line added inside `This week` does not disturb it.
  The outline prompt's two substring bans live here too, at 360–361: `assertFalse(prompt.contains("focus:"))`
  and `assertFalse(prompt.contains("planning:"))` — so any new line in an outline prompt must avoid both
  substrings (`CoachPromptGoldenFileTest.kt:39–40` bans them in the golden text the same way).
- `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` (139–141) — the index error reads
  `sorties[$position].index: must be $position`, i.e. array position wording, not "sortie N". `GenerationWorker`
  hands those lines to `repairPrompt` (`GenerationWorkerTest.kt:807` compares them byte for byte).
  Not part of this issue: no numbering the coach could copy.

## Moving the text: what has to move together (worked out 2026-11-21)

- Only three sites in `app/src/main` print a sortie number into a prompt: `CoachPromptBuilder.kt:60`
  (Task line), `:101` (week line), `:108` (`planning:` line). Nothing else in `main` writes prompt text,
  so no other builder change is needed. `work/GenerationWorker.kt:488` logs
  `flight plan for sortie ${row.sortieId}` — a Room row id in a log line, not coach-facing prose; out of
  scope.
- The three-way coupling that decides task order: `CoachPromptGoldenFileTest` compares the builder's
  bytes with the three golden files, and `CoachPromptBuilderTest` quotes the week lines and the Task
  line. A commit that changes the builder without the golden files and those two methods leaves the
  suite red, so builder + golden files + the two methods land in the same commit.
- `data/PromptInputLoaderTest.kt:343` does render a prompt, but asserts only history strings
  (`"Legs + pull"`, and the absence of two coach notes), so prompt wording changes do not touch it.
- The brief's own example shape for a numbered line is `sortie 1 (index 0) — run — pending`
  (`brief.md:7`) — number first, index in parentheses beside it.
- DESIGN.md's amendment entry shape, from the newest one at 813: `- **YYYY-MM-DD — <short title>
  (§refs).** body` as the first bullet after the intro at 810–811. "Marked inline" is done by writing
  the new thing into the affected section's own text (as §5.1/§6/§9 do for Back to Launchpad) — there
  is no `(amended …)` marker in the section bodies.

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

## What the gate script names (verify.sh — the definition of done)

Three new tests, by name; the work steps must use these names, in any class:

1. `promptNumbersEachSortieByItsDisplayNumberAndKeepsItsIndex` — one test, the three prompts in it: every
   `This week` sortie line names the sortie by `index + 1` (1…5 for indices 0…4) and keeps the 0-based index
   beside it; the `planning:` line carries the same two numbers for the same sortie.
2. `everyCoachPromptTellsTheCoachToNameSortiesByTheirNumberNotTheirIndex` — one test, the three prompts in it:
   each carries one line in `This week` telling the coach to name a sortie in its notes by the number printed
   beside it, or by type and focus, never by index; the same line in all three; no `focus:` / `planning:`
   substring in it.
3. `flightPlanTaskLineNamesTheSortieByTheNumberItsWeekLinePrints` — one test, the two golden planned
   positions (index 1 → 2, index 2 → 3): the Task line's number equals the number the same prompt's week list
   prints for that sortie, with the index named too.

Whole classes it also requires green (rename-robust, no new tests): `CoachPromptGoldenFileTest`,
`CoachPromptBuilderTest`, `CoachSchemaFilesTest`, `CoachReplySemanticTest`, `CoachReplyFixtureTest`,
`PromptInputLoaderTest`, `GenerationWorkerTest`. No UI test is named, and none should be written for this issue.

## Baseline on the untouched tree (measured 2026-11-21)

`bash .rho/runs/issue-61-coach-notes-number-sorties-2dp4/verify.sh` on the untouched tree: gate green on
attempt 1 in 1m26s (local toolchain, `--no-build-cache`, 46 tasks executed), **775 test records over 93
reports, 0 failures, 0 skips**, all seven class checks ok (CoachPromptGoldenFileTest 5, CoachPromptBuilderTest
15, CoachSchemaFilesTest 3, CoachReplySemanticTest 15, CoachReplyFixtureTest 17, PromptInputLoaderTest 11,
GenerationWorkerTest 36), and the script exits 1 on the three names above. With those three names present and
passing the same script exits 0 (checked against a synthetic index, since a scratch test cannot be written in
`app/src/test` by this session). No test in the repo is skipped today, so the script treats a skip as a failure.

The text the change moves, as it stands in the golden files: `outline.txt` week lines 27–31 read
`index 0 — run — landed` … `index 4 — run — pending` with no `planning:` line; the two Flight Plan files read
the same five lines with ` — focus: Back squat` on indices 1 and 2, then `planning: index 1 — lift` /
`planning: index 2 — run`, and their Task lines read `Write the Flight Plan for sortie 1 of this week` /
`… sortie 2 …`. `CoachPromptBuilder.kt:60` is the Task line, `:101` the week line, `:107-109` the planning line.

## Gate on this machine

`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` is the documented gate, but the committed
`gradlew.sh` pins Windows paths (`C:/Users/corpo/android-toolchain/...`), so on this Linux box run:

```
JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk \
  $HOME/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest
```

Confirm the test task actually executed (no `FROM-CACHE` / `UP-TO-DATE` / `NO-SOURCE` on
`:app:testDebugUnitTest`). Scratch files and gate logs go under gitignored `app/build/`, never `app/src/test`.
