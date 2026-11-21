# Plan

## Approach

Everything this issue touches is prompt text, plus the fixtures and tests that pin it. Three places in
`CoachPromptBuilder` number a sortie — the week lines and the `planning:` line in `thisWeek()`, which print
`index 0 — run — …`, and the Flight Plan's Task line in `task()`, which prints the 0-based index under the
word "sortie". Each gets the display number (`index + 1`, the number `MissionScreen` and `MissionState`
already print) with the index kept beside it, because the reply schema and the validator's error lines still
speak in 0-based indices. One added line, as the last line of the `This week` section, tells the coach to
refer to sorties in notes by that number or by type and focus, never by index. The builder stays a pure
formatter with no new input field, and the three golden files are edited in step with it, so the
byte-for-byte contract is updated deliberately instead of being loosened. DESIGN.md §7.4 gets an inline mark
and an amendment entry, which is what CLAUDE.md asks for when an approved decision changes.

## Files

- `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` — `thisWeek()`: each week line and the
  `planning:` line carry the display number with the index, and the numbering convention line is the
  section's last line; `task()`: the Flight Plan line names the sortie by its display number with the index
  in parentheses. The comments at 57–58 and 134 describe the old shape and are corrected. Line inventory in
  the map.
- `app/src/test/resources/prompts/outline.txt`, `lift-flight-plan-with-outline.txt`, `run-flight-plan.txt`
  — hand-edited to the new bytes: the five week lines in all three, the `planning:` line in the two Flight
  Plan files, the Task line in the two Flight Plan files, and the convention line in all three. Every other
  byte stays as it is.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt` — the exact-line assertions at 354–356,
  450–453 and 458 move to the new text; the new tests for the convention line and for display number =
  index + 1 go here.
- `app/src/test/java/com/liftoff/app/coach/CoachPromptGoldenFileTest.kt` — no change expected: its byte
  comparison covers the new text, and its outline assertions (Task line, no `planning:`, no `focus:`) still
  hold.
- `DESIGN.md` — §7.4 items 1 and 5 marked inline, plus an amendment entry at the end of the Amendments
  section, newest-first.
- `coach/COACH.md` — not changed; see Decisions.

## Order

1. Builder and golden files and the three exact-line assertions in `CoachPromptBuilderTest` together, then
   run both coach test classes in one Gradle invocation. Splitting them leaves the gate red in between.
2. The new tests: the convention line in all three prompts, and the display number matching `index + 1` for
   every position. Green from the start.
3. DESIGN.md inline marks and the amendment entry.
4. Full gate: `assembleDebug` plus `testDebugUnitTest`, confirming the test task executed rather than being
   served from cache.

## Testing

- The three prompts match their golden files byte for byte, with the new numbering and the convention line
  in them — existing golden-file tests, updated fixtures.
- An outline prompt's week lines read `sortie 1 (index 0) — run — landed` … `sortie 5 (index 4) — run — pending`,
  and still carry no `focus:` and no `planning:` — existing unit test, updated expectations.
- A Flight Plan prompt keeps `— focus: …` after the state on its week lines, and its `planning:` line names
  the same sortie with both numbers — existing unit test, updated expectations.
- The Flight Plan Task line names the display number and the index, so the coach is told the number the
  screen uses for the sortie it is writing — existing unit test, updated expectation.
- Every prompt, outline and both Flight Plan kinds, carries the numbering convention line in its `This week`
  section — new unit test, three cases.
- For each of the five positions the number printed for a sortie is its `index + 1`, the same rule the
  screens use for that sortie (map). Asserted from the prompt text alone — `coach/` is pure Kotlin and does
  not import the UI (ARCHITECTURE.md invariant 2) — new unit test, looping over the week lines.
- Unchanged and still green: the Output schema section is the schema resource verbatim, building the same
  input twice gives identical bytes, the validator fixture tests, `GenerationWorkerTest`'s repair-text
  comparison, `PromptInputLoaderTest`.
- Gate command for this machine is in the map; the brief's `bash gradlew.sh …` pins Windows paths.

## Risks

- Editing the builder without editing the golden files in the same step leaves the gate red; do both at once
  and run `CoachPromptBuilderTest` and `CoachPromptGoldenFileTest` in one invocation before the full gate.
- Regenerating the golden files from the builder would hide an unintended change anywhere else in them;
  hand-edit only the lines that must change and let the byte comparison prove the rest.
- The golden files must stay LF (`.gitattributes`, CLAUDE.md) and keep the em-dash separator the builder
  writes (map); an editor writing CRLF, or a hand-typed `-` where the builder writes `—`, fails the byte
  comparison loudly but for a reason the test name does not suggest. Check both in the edited files.
- The convention line's wording becomes part of the locked contract, so it is chosen once, kept to one line,
  and any later rewording is a fixture change rather than a tweak.
- The outline golden test asserts that prompt contains neither `focus:` nor `planning:` (map). The convention
  line must not contain either substring — "by type and focus" is safe, "focus:" is not — or the test fails
  for a reason that has nothing to do with what it is named for.
- Nothing validates the coach's prose, so the tests prove what the coach is asked, not that it complies.
  Whether the notes actually switch to 1-based numbers is a live-measurement question (`LiveCoachTest`,
  M0, not yet run) and is out of scope here.
- The owner's coach workspace on the PC is a hand-made copy of `coach/` (map), so it is untouched by this
  change; the rule reaches the coach through the prompt instead.
- The run's later gate counts test records; two new tests are added, so the work step records the before and
  after counts in its findings.

## Decisions

**What the sortie line looks like in the prompt.** The brief gives one example shape, `sortie 1 (index 0) — run — pending`. The plan uses that shape for every sortie line, rather than a bare `sortie 1` with the index dropped, or the index first with the display number in parentheses. The coach needs both numbers: the display number is what it writes in notes and what the athlete sees on the Mission tab, and the index is what the coach must echo back in its structured output, which the brief says stays 0-based. Keeping the brief's wording also keeps the diff against the golden files small and reviewable.

**Where the "refer to sorties by number or by type and focus, never by index" instruction lives.** The brief offers the prompt or `coach/COACH.md`. The plan puts it in the prompt, in the `This week` section next to the sortie list, and leaves `coach/COACH.md` as it is. `coach/` in this repo is a template: the coach actually runs in a hand-made copy on the PC that is its own repository, so a rule added only to the template never reaches the coach that is writing the notes. The prompt is sent on every turn, and DESIGN.md decision 9 already puts the app-owned contract wording there rather than in COACH.md. The alternative — COACH.md, or the same line in both — either relies on the owner re-copying their workspace by hand or leaves two copies of one rule that can drift.

**The Flight Plan's Task line, which the brief's example does not show.** Today it reads `Write the Flight Plan for sortie 1 of this week` for the sortie the Mission tab calls 02, so the coach is told a 0-based number under the word "sortie" in the very line that names the session it is writing. The plan gives that line the display number too, with the index in parentheses. The alternative is to change only the week lines the brief quotes, which would leave one prompt disagreeing with itself: the list would say `sortie 2 (index 1)` while the Task line calls the same sortie "sortie 1".

**Existing coach notes that already use 0-based numbers.** The plan fixes what the coach is asked from this change onward and leaves stored notes as they are. Notes are prose the athlete has already read, and rewriting them would put words in the coach's mouth; the Mission tab already numbers the same sorties 01–05, so the mismatch is only in the coach's own sentences. The alternative — a one-off scrub of stored notes, or a UI annotation — rewrites history for a wording habit that the new prompt stops at source.

**How the display number is derived, and how it is written.** It is the sortie's position in the week, 1-based — `index + 1`, which is what `MissionScreen`, `MissionState`, `LandedState` and `InFlightState` all print for that sortie. The alternative — a stable id that survives reordering — would not match what the screen shows, which is the whole point of the fix. The prompt writes it plain (`sortie 2`), not the screens' zero-padded `02`: the coach is writing prose, and a padded number reads like a code.
