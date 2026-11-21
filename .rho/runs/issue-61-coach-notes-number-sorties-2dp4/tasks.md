# Tasks — issue 61: the coach's notes number sorties from 0, the app from 1

Five tasks, in the plan's order. Each ends in its own commit with an `area: lowercase summary`
message (CLAUDE.md), staging only the files its own entry names — never `git add -A`.

**The coupling every task must respect:** `CoachPromptGoldenFileTest` compares the builder's UTF-8
bytes with the three files under `app/src/test/resources/prompts/`, and `CoachPromptBuilderTest`
asserts the week lines and the Task line word for word. So the builder, the three golden files and
the exact assertions in `CoachPromptBuilderTest` move in the *same* commit: a commit that changes
one of the three leaves the other two red for the next session. Tasks 1 and 2 each do all three.

**What no task touches:** `PromptInputs.kt` (no new field — the display number is `index + 1`,
derived in the builder), `PromptInputLoader.kt`, the three schema files, `CoachReplyValidator.kt`,
`GenerationWorker.kt`, any UI file, and `coach/COACH.md` (plan decision "Where the instruction
lives": `coach/` is a template and the running coach is a hand-made copy, `coach/README.md`).

Test names below are exactly the method names
`.rho/runs/issue-61-coach-notes-number-sorties-2dp4/verify.sh` looks for (`t <method>`, matched
across every report of the gate run, so the class is the implementer's call). Each is claimed by
exactly one task — task 3. No other test in the repo is new; the classes verify.sh requires green
already exist.

Read before starting: `brief.md`, `plan.md`, `map.md`, DESIGN.md §7.4 and §7.5.1, CLAUDE.md.
Gate for each task (this machine's toolchain, see map.md "Gate on this machine"):

```
JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk \
  $HOME/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest --no-build-cache
```

then read `app/build/test-results/testDebugUnitTest/TEST-*.xml` rather than the exit code. Scratch
files go under gitignored `app/build/`, never `app/src/test`.

- [ ] 1. `coach: give every sortie in the prompt its display number beside its index`
  The numbering half of "Shape of the change", in `coach/CoachPromptBuilder.kt`: `thisWeek()`
  (98–111) writes each week line with the display number `index + 1` and keeps the 0-based index
  beside it — the brief's shape `sortie 1 (index 0) — run — pending` — with the number written plain
  in prose, not the screens' zero-padded `02`; the `planning:` line (107–109) carries the same two
  numbers for the same sortie; `task()` (54–61) names the sortie being planned by the number its own
  week list prints for it, index still named too. The `— focus: …` suffix and plan decision 0 (an
  OUTLINE prompt shows no focus) stay as they are.
  Same commit: the three golden files `app/src/test/resources/prompts/outline.txt`,
  `prompts/lift-flight-plan-with-outline.txt`, `prompts/run-flight-plan.txt` — the five week lines at
  27–31 in each (`pattern:` sits at 26 and does not change), the `planning:` line at 32 in the two
  Flight Plan files (outline.txt has none), and the Task line at 3 in the two Flight Plan files
  (`outline.txt`'s Task line is unchanged) — all brought to the builder's new bytes; and the exact
  assertions in `coach/CoachPromptBuilderTest.kt` at 351–359
  (`outlinePromptWeekListsNoFocus`) and 447–460
  (`flightPlanWeekShowsFocusAndNamesTheSortieBeingPlanned`, Task line included).
  Golden files: LF only (`.gitattributes` pins `* text=auto eol=lf`), and the separator is the
  builder's em dash `—` (U+2014) — a typed `-` differs by one code point and fails the byte
  comparison. Regenerate them from the builder rather than retyping the whole file, and confirm with
  `od -c` before committing.
  Done when: `CoachPromptGoldenFileTest`'s three byte comparisons pass against the updated files;
  `CoachPromptBuilderTest` is green with its week-line and Task-line assertions on the new text; in
  each of the three prompts the number printed for the sortie at index n is n+1 and that line still
  names index n; the Task line's number equals the number the same prompt's week list prints for the
  planned sortie; no failure or skip in any `TEST-*.xml`.

- [ ] 2. `coach: tell the coach to name a sortie in its notes by its number, not its index`
  The one convention line, as the last line of the `This week` section in all three prompts
  (`thisWeek()`), saying the coach should refer to a sortie in its notes by the number printed beside
  it, or by its type and focus, and never by its index. The sentence is the implementer's, with two
  constraints the tests around it already impose: the line contains neither the substring `focus:`
  nor `planning:` — `CoachPromptGoldenFileTest.kt:39–40` and `CoachPromptBuilderTest.kt:360–361`
  forbid both anywhere in an outline prompt — and it says what the coach writes in its notes rather
  than restating the numbering. One line, identical in all three kinds. `coach/COACH.md` untouched.
  Same commit: the line added to the three golden files, and to the two exact
  `sectionLines(prompt, "This week")` assertions in `CoachPromptBuilderTest.kt` (351–359, 447–456).
  Keep task 1's week-line format as you find it; read the builder and the golden files before editing.
  Done when: the line appears exactly once in the `This week` section of each of the three built
  prompts and is byte-identical across them; `CoachPromptGoldenFileTest` and `CoachPromptBuilderTest`
  green; the outline prompt still contains neither `focus:` nor `planning:`; no failure or skip in
  any `TEST-*.xml`.

- [ ] 3. `coach: tests for the numbering the coach is shown`
  The three tests verify.sh names, written in `coach/CoachPromptBuilderTest.kt` beside the existing
  §7.4 cases (a new `coach/` test class is fine too — names are matched across every report). All
  three build their inputs by hand the way that class's cases do, over a **five-position `RLRLR`
  week, indices 0–4** (the golden inputs' week): an OUTLINE prompt, a FLIGHT_PLAN whose planned
  sortie is at index 1, and one at index 2. Build that week inside these tests rather than widening
  the class's 3-sortie `week` fixture (38–44), which the exact assertions at 351–359 and 447–460
  depend on. No golden file is read, no UI test, no Robolectric.
  - `promptNumbersEachSortieByItsDisplayNumberAndKeepsItsIndex` — one test, the three prompts in it:
    every `This week` sortie line names the sortie at index n as n+1 (1…5) and still carries index n
    beside it; the `planning:` line carries the same two numbers for the same sortie.
  - `everyCoachPromptTellsTheCoachToNameSortiesByTheirNumberNotTheirIndex` — one test, the three
    prompts: each carries the convention line in `This week`, the same line in all three, containing
    neither `focus:` nor `planning:`, and it is an instruction about the coach's notes, not a
    restatement of the list.
  - `flightPlanTaskLineNamesTheSortieByTheNumberItsWeekLinePrints` — one test, the two planned
    positions (index 1 → 2, index 2 → 3): the Task line's number equals the number that same
    prompt's week list prints for that sortie, with the index named alongside it.
  Done when: those three method names appear as passing `<testcase>`s in this run's XML reports, and
  each one asserts the numbers themselves (a test that only checks a line exists does not count).

- [ ] 4. `docs: DESIGN.md §7.4 says what the prompt now numbers`
  `DESIGN.md` §7.4 item 1 (371–372) and item 5 (380–383) updated in place to describe the text the
  builder now sends: the Task line names the sortie being planned by its display number with the
  0-based index beside it; the week list gives each sortie its display number and its index; the
  section carries the line telling the coach how to refer to a sortie in its notes. §7.5.1's 0-based
  `index` contract is left as it is. A dated entry at the top of "Amendments after approval" (808+),
  newest-first, in the existing shape `- **2026-11-XX — <title> (§7.4).** …`: what changed, when, and
  why (the coach's prose numbered sorties from 0 while every screen numbers them from 1).
  Same commit: the stale comment at `coach/CoachPromptBuilder.kt:134`, which explains the uppercase
  history header by contrast with "a `index 0 — run — …` week line" that no longer exists.
  Done when: §7.4 items 1 and 5 describe the wording the builder actually emits; the amendment entry
  is the newest in the list and names §7.4; no comment in the builder describes the old week-line
  shape; DESIGN.md stays LF-only; the coach tests still green.

- [ ] 5. Gate: the whole suite runs clean, and the run's checks pass
  No new source. Run `bash .rho/runs/issue-61-coach-notes-number-sorties-2dp4/verify.sh` from the
  repository root and read its output, not just its exit code. Then sweep for anything left calling
  the 0-based index "sortie N" in coach-facing text: `grep -rn 'sortie ' app/src/main/java/com/liftoff/app/coach/`,
  the three golden files, and the coach tests; fix what the sweep turns up in this commit.
  Done when: the gate passes and `> Task :app:testDebugUnitTest` is **not** `FROM-CACHE`,
  `UP-TO-DATE` or `NO-SOURCE`; the three names verify.sh requires appear as passing `<testcase>`s;
  its seven class checks all read `ok` (`CoachPromptGoldenFileTest`, `CoachPromptBuilderTest`,
  `CoachSchemaFilesTest`, `CoachReplySemanticTest`, `CoachReplyFixtureTest`, `PromptInputLoaderTest`,
  `GenerationWorkerTest`); zero failures and zero skips across every `TEST-*.xml`; no CR byte under
  `app/src/test/resources` (`find app/src/test/resources -type f | LC_ALL=C tr -d -c '\r' | wc -c`
  reports 0). If the sweep finds nothing, still commit: write what was checked and what was found
  into `.rho/runs/issue-61-coach-notes-number-sorties-2dp4/findings.md` and commit that — a task that
  commits nothing is rejected. Never edit `verify.sh`.
