# Task 5 — gate and sweep

No source changed. The gate was run, read, and passed; the sweep for coach-facing text that
still calls the 0-based index "sortie N" found nothing to fix. What follows is what was run,
what each check reported, and what every sweep hit was.

## The gate

`bash .rho/runs/issue-61-coach-notes-number-sorties-2dp4/verify.sh`, run from the repository
root. Its own gradle line was
`/home/kyle/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest --no-build-cache`.

Three attempts, all recorded under `app/build/verify-issue61/`:

| attempt | result | detail |
| --- | --- | --- |
| 1 | BUILD FAILED in 1m 27s | `LiftoffShellTest > selectedTabSurvivesRecreation FAILED`, `androidx.compose.ui.test.ComposeTimeoutException` at `LiftoffShellTest.kt:43`; "778 tests completed, 1 failed" |
| 2 | BUILD FAILED in 1m 26s | `LiftoffShellTest > systemBackFromATabGoesToLaunchpad FAILED`, same exception and line; "778 tests completed, 1 failed" |
| 3 | BUILD SUCCESSFUL in 1m 7s | "46 actionable tasks: 1 executed, 45 up-to-date" |

Both failures are the recorded `LiftoffShellTest` flake this run's script retries: a different
test each time, the same 20 000 ms `waitFor` expiring at `LiftoffShellTest.kt:43`, and no
failure anywhere else in either run. `coach/` is pure Kotlin and touches none of the UI, so
neither failure is this change's; the script's `transient_failure` classified them that way and
ran the gate again, which is what it is written to do. Nothing was changed to chase them.

`> Task :app:testDebugUnitTest` in `gate-3.log` carries no `FROM-CACHE`, `UP-TO-DATE` or
`NO-SOURCE` suffix (`grep -c` over that log for those three words returns 0), and the run
lasted 1m 7s with one actionable task executed — the tests ran on this tree's code.

## What the script reported

```
  ok   promptNumbersEachSortieByItsDisplayNumberAndKeepsItsIndex
  ok   everyCoachPromptTellsTheCoachToNameSortiesByTheirNumberNotTheirIndex
  ok   flightPlanTaskLineNamesTheSortieByTheNumberItsWeekLinePrints
=== the three golden prompts ===
  ok   CoachPromptGoldenFileTest  (5 tests, all passed)
=== the builder's other rules ===
  ok   CoachPromptBuilderTest  (18 tests, all passed)
=== the 0-based index the coach replies with ===
  ok   CoachSchemaFilesTest  (3 tests, all passed)
  ok   CoachReplySemanticTest  (15 tests, all passed)
  ok   CoachReplyFixtureTest  (17 tests, all passed)
=== the loader and the worker around the prompt ===
  ok   PromptInputLoaderTest  (11 tests, all passed)
  ok   GenerationWorkerTest  (36 tests, all passed)
--- index: 778 test records over 93 reports; 0 not passing, of which 0 were skipped
verify: 778 unit tests ran and passed, including every test named above. Issue 61 done.
```

Read against the XML reports themselves rather than only the script's summary:

- 93 `TEST-*.xml` under `app/build/test-results/testDebugUnitTest`, 778 `<testcase>` elements;
- 0 `<failure>`, `<error>` or `<skipped>` elements in any of them
  (`grep -ho '<\(failure\|error\|skipped\)[ >/]'` over all 93 → 0);
- the three named tests appear as passing `<testcase>`s in
  `TEST-com.liftoff.app.coach.CoachPromptBuilderTest.xml`, each self-closing with a time and no
  failure child;
- 778 = the 775-record baseline plus the three tests task 3 wrote; `git show b6a2144` adds exactly
  three `@Test` methods, all inside the existing `CoachPromptBuilderTest`, so no new report file
  appeared.

`find app/src/test/resources -type f | LC_ALL=C tr -d -c '\r' | wc -c` → **0**: no CR byte
anywhere under the test resources, including the three golden files.

## The sweep

`grep -rn 'sortie ' app/src/main/java/com/liftoff/app/coach/` → 19 lines; the same grep over the
three golden files → 25, and over `app/src/test/java/com/liftoff/app/coach/` → 48. Also swept:
every string literal containing "sortie" in `app/src/main/java` (a wider net than the named grep),
the three schema resources the prompt embeds verbatim, `coach/COACH.md` and `coach/README.md`, and
every string sent to the coach outside `coach/` — `GenerationWorker`'s session labels and
description.

**Nothing was left calling the 0-based index "sortie N" in coach-facing text. No file changed.**
Each hit, and why it is not one:

- `CoachPromptBuilder.kt` 58, 75, 100, 111, 120, 136, 139, 238, 277, 280 — every number the
  builder prints after the word "sortie" comes from one helper, `sortieNumber(index)` at 280,
  `sortie ${index + 1} (index $index)`; the three sites that call it are the Task line at 60, the
  week line at 101 and the `planning:` line at 108. 111 is the convention line, 75 is
  "sortie length: … minutes", and the rest are loop variables and comments — the comment at 136 was
  corrected in task 4 to quote the new week line.
- `CoachModels.kt:22`, `CoachReplyValidator.kt:100, 195` — prose about a sortie's type or length;
  no number in it. `CoachReplyValidator.kt:137, 233` and `PromptInputs.kt:77, 113` are loop
  variables and doc comments.
- `CoachReplyValidator.kt:132, 141, 147` — `sorties[$position].index: must be $position` and the
  pattern-position lines. These are the 0-based index on the coach's side of the wire, which the
  brief keeps; the prompt now names both numbers so the coach can echo these back. Left as they
  are, deliberately.
- `PromptInputs.kt:106, 109` — `require()` messages ("sortie $sortieIndex is not a sortie of this
  week"). They are precondition failures inside the app, thrown before a prompt exists: nothing
  in `coach/` or `work/` catches them and puts them in a prompt or a repair turn, so they never
  reach the coach. `sortieIndex` is the field name, not a number printed for a sortie.
- The three golden files — 25 hits: in each file the five week lines `sortie 1 (index 0)` …
  `sortie 5 (index 4)`, the "sortie length" line, and the convention line; in the two Flight Plan
  files also the `planning:` line and the Task line. All already carry the display number beside
  the index; `CoachPromptGoldenFileTest`'s byte comparison is what proves they equal the builder's
  bytes.
- The coach tests — 48 hits. The ones that carry a number are assertions on the new text
  (`CoachPromptBuilderTest.kt:354–357, 451–455, 460`), the `NUMBER_AND_INDEX` regex at 723, and the
  numbering tests' own labels; the rest are prose about a sortie's length, position or state
  (`CoachFixtures.kt:14`, `CoachReplyFixtureTest.kt:21–22`, `CoachReplySemanticTest.kt:141`,
  `CoachSchemaLoadTest.kt:34`) or comments describing the old shape in the past tense
  (`CoachPromptBuilderTest.kt:482, 572`). No test asserts a 0-based number as if it were current.
- `app/src/main/resources/schemas/*.json` — `"index": { "type": "integer", "minimum": 0 }` and the
  `required` list. The Output schema keeps its 0-based index, as the brief says; the schemas
  print no "sortie N" at all.
- `coach/COACH.md`, `coach/README.md` — one hit in total, "the week's lift sorties" at
  `COACH.md:24`, which numbers nothing. Left untouched per plan Decision "Where the instruction
  lives": `coach/` is a template and the running coach is a hand-made copy on the PC.
- `GenerationWorker.kt:488` — the session description `"liftoff generation ${row.id}: flight plan
  for sortie ${row.sortieId}"`. This is the one piece of coach-visible text outside `coach/` that
  puts a number after the word "sortie", so it was checked rather than skipped: the number is a
  Room row id, not the 0-based week index, so it is not what this issue is about — it is a stable
  database key, and the comment above it says the description "carries no prompt text and no user
  data", existing to name which generation a session belongs to. Changing it would move the
  session-identity contract, and tasks.md lists `GenerationWorker.kt` under "What no task touches".
  Worth its own issue if the row id is ever shown to a person; not this one.
- `MissionManager.kt:454, 456, 461, 467, 484` — `StoreOutcome.Refused("sortie $sortieId …")`,
  again Room row ids, in store refusals that never leave the app.

## Note for whoever reads the pull request

The gate needed three attempts because of `LiftoffShellTest`, not because of this change. The
suspected cause, measured on earlier runs of this suite, is memory: Gradle's default 512m test
heap over ~780 Robolectric + Compose tests (three forced full reruns with `maxHeapSize = "2g"`
injected by an external init script were green). `app/build.gradle.kts`'s
`testOptions.unitTests.all { }` block currently sets only a 5-minute task timeout. The heap is
its own issue and is deliberately not folded into this run.
