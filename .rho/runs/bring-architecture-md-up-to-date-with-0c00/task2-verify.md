# Task 2 — the `work` package row. Evidence.

Commit `09b068c` "docs: cut the work package row down to a short ✅ cell". One file changed:
`ARCHITECTURE.md`, line 36, 1 insertion / 1 deletion. Nothing pushed, no PR.

## Each Done-when, and what was actually measured

| Done-when | Command | Output |
|---|---|---|
| row is 891 bytes or fewer | `grep -m1 'com\.liftoff\.app\.work' ARCHITECTURE.md \| tr -d '\n' \| wc -c` | `636` |
| row differs from its version at `8de2795^` | same grep on `git show '8de2795^:ARCHITECTURE.md'` vs on the file, `md5sum` | base `1782` bytes `ab02f1f5…`, now `636` bytes `17cbd791…` |
| last cell has none of partly/partial/not started/in progress | `… \| awk -F'\|' '{print $5}' \| grep -icE 'partly\|partial\|not started\|in progress'` | `0` matches, grep exit 1 |
| the five names resolve | `git ls-files app/src/main/java/com/liftoff/app/work/<Name>.kt` ×5 | all five paths returned (see below) |
| `coach` row unchanged, or the body says what was wrong | `cmp` of the row at `8de2795^` against the row now | byte-identical, 642 bytes — so no claim was wrong |

Shape of the file after the edit: line 36 has 4 `|`; `git ls-files --eol` is `i/lf w/lf attr/text=auto eol=lf`;
0 CR bytes and 0 U+FFFD in the file.

## The new row

Responsibility cell left byte-identical: "Android-side workers (`GenerationWorker`). Uses WorkManager and
Android APIs." Status cell is now ✅ with one phrase per file, each phrase read off that file's own class KDoc
(not copied from the run-on it replaced):

- `CoachTurnRunner.kt` — one coach turn over the copied rho client, with no Room or WorkManager
- `GenerationWorker.kt` — one generation end to end: read the `Generation` row by id, run the turn, store the
  reply or end the row FAILED with its reason
- `GenerationEnqueuer.kt` — the enqueue helper, unique one-time work per generation kept rather than replaced
- `GenerationCanceller.kt` — cancel that work and the row, then give up its rho session
- `AppOpenSweep.kt` — re-enqueue or cancel every open row at each app open

`work/` holds exactly these five files, so the row names the whole package.

The resume, supersede and offline detail that was cut is not lost: it lives in `DESIGN.md` §7.3 and §10 and in
the comments in these five files. That is stated in `09b068c`'s commit body.

## The `coach` row (line 35): read, confirmed, not edited

Checked against the seven files in `app/src/main/java/com/liftoff/app/coach/` — the row names all seven, and
`coach/` holds exactly those seven. Every claim in the row holds: the three schemas are
`app/src/main/resources/schemas/{lift-plan,outline,run-plan}.json`; the fixtures are the 14 files in
`app/src/test/resources/fixtures/coach/`; the golden prompts are the three in `app/src/test/resources/prompts/`
(they are `.txt`, so they are not backticked as `.md`); no file in `coach/` has `import android`; §7.7 is
"Exercise identity". The row is byte-identical to its version at `8de2795^`, and the commit body records that
it was checked and left alone.

## What the run's own checks say right now

`verify.sh` was not touched. Its document + scope sections were run on their own, without the Gradle gate
(`sed -n '58,74p'` + `sed -n '248,531p'` into `app/build/doc-checks.sh`, `bash -n` clean), full log at
`app/build/t2-docchecks.log`: **41 passed, 9 failed, exit 1.**

Task 2's own lines all pass:

```
✓ package row `com.liftoff.app.work` has been rewritten
✓ package `com.liftoff.app.work` row has been cut down (636 bytes against the 1782 it started as)
✓ package row `com.liftoff.app.work` 'Exists yet?' cell no longer carries the stale wording
✓ package row `com.liftoff.app.work` no longer carries the stale wording
✓ package row `com.liftoff.app.coach` … (both wording checks)
✓ nothing outside ARCHITECTURE.md and the run folder changed since 8de2795…
```

The 9 failures are the checks owned by tasks 3, 4 and 5, which have not run yet — `ui` row still says "stub"
and is unchanged (2), `M3` 3230 bytes / "in progress" / "come next" (3), `M2` "placeholder" / "comes with
generation" (2), `M5` "Partial" (1), `M6` "Not started" (1). No task 2 check is among them.

The Gradle gate is task 6's, and no task writes a test: the 19 classes `verify.sh` names already exist. Task 2
changed no Kotlin, resource or build file, so nothing in that gate can move.

## Why `tasks.md` still shows task 2 unchecked

`tasks.md` lists `- [x] 1` and `- [ ] 2` … `- [ ] 6` at HEAD `cbc37e4`. A session is told not to edit `tasks.md` —
the runner ticks the box when the task finishes — so the unchecked box for task 2 is the runner's to close, not a
claim that its work is missing: every Done-when condition of task 2 measures true at HEAD, as tabulated above.
Boxes 3–6 are unchecked because those tasks belong to later sessions; their checks (`ui` "stub", `M3` length and
"In progress", `M2` "placeholder", `M5` "Partial", `M6` "Not started") are the 9 failures listed above and are
meant to fail until each task runs.
