# Task 2 — the `work` package row. Evidence.

HEAD `02ee205`. Task 2's own commit is `09b068c` "docs: cut the work package row down to a short ✅ cell":
`ARCHITECTURE.md` only, line 36, 1 insertion / 1 deletion. Nothing pushed, no PR, `verify.sh` untouched.

## Done-when, each with its command and its real output

- row 891 bytes or fewer — `grep -m1 'com\.liftoff\.app\.work' ARCHITECTURE.md | tr -d '\n' | wc -c` → **636**
- differs from its version at `8de2795^` — same grep on `git show '8de2795^:ARCHITECTURE.md'` vs on the file, md5sum
  → base 1782 bytes `ab02f1f5…`, now 636 bytes `17cbd791…`
- last cell free of partly/partial/not started/in progress — `awk -F'|' '{print $5}' | grep -icE '…'` → **0**, exit 1
- the five names resolve — `git ls-files app/src/main/java/com/liftoff/app/work/<Name>.kt` ×5 → all five returned:
  CoachTurnRunner, GenerationWorker, GenerationEnqueuer, GenerationCanceller, AppOpenSweep (`.kt` each)
- `coach` row unchanged — `cmp` of the row at `8de2795^` against the row now → **byte-identical, 642 bytes**, so no
  claim was wrong and no edit was made
- shape: line 36 has 4 `|`; `git ls-files --eol` → `i/lf w/lf`; 0 CR bytes, 0 U+FFFD in the file

## The new row

Responsibility cell byte-identical ("Android-side workers (`GenerationWorker`). Uses WorkManager and Android
APIs."). Status cell ✅, one phrase per file, each read off that file's own class KDoc, not from the run-on it
replaced: CoachTurnRunner — one coach turn over the copied rho client, no Room or WorkManager; GenerationWorker —
one generation end to end, read the `Generation` row by id, run the turn, store the reply or end the row FAILED
with its reason; GenerationEnqueuer — the enqueue helper, unique one-time work per generation kept rather than
replaced; GenerationCanceller — cancel that work and the row, then give up its rho session; AppOpenSweep —
re-enqueue or cancel every open row at each app open. `work/` holds exactly these five files.

The cut resume, supersede and offline detail is not lost: `DESIGN.md` §7.3 and §10, and the comments in these five
files. Stated in `09b068c`'s body.

## The `coach` row (line 35): read, confirmed, not edited

The row names all seven files in `app/src/main/java/com/liftoff/app/coach/`, and `coach/` holds exactly those
seven. Claims checked: the three schemas are `app/src/main/resources/schemas/{lift-plan,outline,run-plan}.json`;
the fixtures are the 14 files in `app/src/test/resources/fixtures/coach/`; the golden prompts are the three in
`app/src/test/resources/prompts/` (`.txt`, so not backticked as `.md`); no file in `coach/` has `import android`;
§7.7 is "Exercise identity". All hold → row left byte-identical, reason recorded in `09b068c`'s body.

## The run's own checks at this HEAD

`verify.sh` was not run as a whole (its Gradle gate is task 6's). Its document + scope sections were run on their
own: `sed -n '58,74p'` + `sed -n '248,531p'` into `app/build/doc-checks.sh`, `bash -n` clean, log at
`app/build/t2-docchecks.log` → **41 passed, 9 failed, exit 1**.

Task 2's lines: `✓ package row com.liftoff.app.work has been rewritten`; `✓ … cut down (636 bytes against the 1782
it started as)`; `✓ … 'Exists yet?' cell no longer carries the stale wording`; `✓ … no longer carries the stale
wording`; both `com.liftoff.app.coach` wording checks ✓; `✓ nothing outside ARCHITECTURE.md and the run folder
changed since 8de2795…`.

The 9 failures belong to tasks 3–5, which have not run: `ui` "stub" + `ui` unchanged (2), M2 "placeholder" +
"comes with generation" (2), M3 3230 bytes / "in progress" / "come next" (3), M5 "Partial" (1), M6 "Not started"
(1). None is task 2's.

## Why `tasks.md` still shows 2 unchecked, and 3–6 too

`tasks.md` reads `- [x] 1` and `- [ ]` for 2–6. A session may not edit that file — the runner ticks the boxes when
the session ends — so an open box for task 2 is not missing work: every condition above measures true at HEAD.
Boxes 3–6 are open because those tasks belong to later sessions, and the 9 failures listed above are their checks
failing by design.
