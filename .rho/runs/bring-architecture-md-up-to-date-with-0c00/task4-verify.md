# Task 4 — milestone rows M0–M3

One file changed: `ARCHITECTURE.md`, two rows replaced whole by a byte-level `perl -CSD` splice from
`app/build/t4-m2.txt` and `app/build/t4-m3.txt`. `git diff --stat` is `ARCHITECTURE.md | 4 ++--` — two rows out,
two in, nothing else in the document moved. No Kotlin, resource or build file touched.

## The rows

**M2** — 177 bytes before, 210 after. Before (`8de2795^`):

    | M2 | Missions and sorties — domain state machines, draft/confirm UI, Launchpad, Scrub | Done — Regenerate
    comes with generation (M3); In-Flight is a placeholder until M4 |

After:

    | M2 | Missions and sorties — domain state machines, draft/confirm UI, Launchpad, Scrub | Done — the week,
    pattern and sortie rules, the draft and confirm flow, the Launchpad cards and Scrub are all built |

Both stale clauses are gone: "Regenerate comes with generation (M3)" and "In-Flight is a placeholder until M4".
The cell now reads Done over what M2 actually is, and says nothing about what follows it.

**M3** — 3230 bytes before (7 em dashes, ends "…come next", opens "In progress"), **649 after** (2 em dashes).
Ceiling is 1615, so the row is at 20% of the ceiling, not at it: the four things the brief names are each one
short phrase pointing at a file, and the resume / offline / supersede rules the old cell restated at length are
left to `DESIGN.md` §7.3 and §10 and to the code's own comments, exactly as plan Risks says. After:

    | M3 | Generation — rho client copy, `GenerationWorker`, outline + lift plan end to end | Done —
    `GenerationWorker.kt` runs one coach turn per `Generation` row through `CoachTurnRunner.kt` and stores the
    validated reply, with resume, offline and supersede handled there; `GenerationEnqueuer.kt` and
    `GenerationCanceller.kt` queue and cancel that work; `MissionManager.kt` queues the outline on Confirm and a
    Flight Plan row as each sortie becomes current; `LaunchpadScreen.kt` draws the status derived from that row
    through `SortieGenerationStatus.kt`, and `MissionManager.regenerateFlightPlan` queues a replacement plan for
    the current sortie |

The title cell is unchanged, so the row is still found by its label and the table still has 7 milestone rows.

**M0 and M1 are unchanged, on purpose.** M0 already says what the task requires — the coach workspace template
(`coach/README.md`, `coach/COACH.md`), the three schemas, the validator (`CoachReplyValidator.kt`) and the prompt
builder (`CoachPromptBuilder.kt`, `PromptInputs.kt`) exist, and live measurement (`LiveCoachTest`) has not been
run — and plan Decision 1 keeps it short of Done, so `Partial:` stays. verify.sh exempts M0 from the "reads as
done" check (line 425) and from file resolution (line 473), because `LiveCoachTest` does not exist under
`app/src/test`; that claim was re-checked this session and still holds. M1 already reads Done in one short
sentence, which is what the task asks it to read, so it was left alone rather than churned. M4–M6 are task 5's.

## Claims in the new cells, each read off the source on this branch

| Claim | Where it was checked |
|---|---|
| week, pattern and sortie rules | `domain/Week.kt`, `Pattern.kt`, `Sorties.kt`, `Missions.kt`, `Rollover.kt`; `WeekTest`, `SortiesTest`, `MissionsTest`, `RolloverTest` |
| the draft and confirm flow | `LaunchpadScreen.kt` draft card; `LaunchpadViewModel.kt:92 confirm()`; `MissionManager.kt:125 confirm(missionId)` inserting one QUEUED `OUTLINE` row (line 140) |
| the Launchpad cards | `LaunchpadScreen.kt` `PlannedContent` (190) / `PendingContent` (206), whose actions are `PlannedActions` (413; `Regenerate` beside `Scrub` at 432–436) and `PendingActions` (441; `Retry` at 451–452) |
| Scrub | `MissionManager.kt:257 scrub(sortieId, reason)`; `LaunchpadViewModel.kt:180 confirmScrub`; `ui/inflight/ScrubDialog.kt` |
| one coach turn per `Generation` row, through `CoachTurnRunner.kt` | `GenerationWorker.kt:47` class, `:59` `generations.get(generationId)` reads the row by id, `:133` and `:277` call `runCoachTurn`, which is `CoachTurnRunner.kt:78` |
| stores the validated reply | `GenerationWorker.kt` imports `CoachReplyValidator`, `:307` `StoreOutcome.Stored` → `markSucceeded` |
| resume handled there | `GenerationWorker.kt:152` "the progress already saved is where it resumes", `:260` repairs read off the row, `:416`/`:422` `rho.session.resume` |
| offline handled there | `GenerationWorker.kt:176–177` `setCoachOfflineMark` while `runAttemptCount < GIVE_UP_AT_ATTEMPT`, `:471` `COACH_UNREACHABLE = "coach unreachable"` |
| supersede handled there | `GenerationWorker.kt:299` `markCancelled`, `:322` `StoreOutcome.Refused` — the currency check and the store share one Room transaction |
| `GenerationEnqueuer.kt` queues that work | `GenerationEnqueuer.kt:32` `enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, …)`, `:41` `"gen-$generationId"`, `:30` 1-minute exponential backoff |
| `GenerationCanceller.kt` cancels it | `GenerationCanceller.kt:32–33` `cancel(generationId)` → `cancelUniqueWork(uniqueName(id))` |
| the outline is queued on Confirm | `MissionManager.kt:116` doc and `:125` `confirm`, `GenerationKind.OUTLINE` at `:140` |
| a Flight Plan row as each sortie becomes current | `MissionManager.kt:443` `private suspend fun advance`, `GenerationKind.FLIGHT_PLAN` at `:469` |
| the Launchpad draws the status derived from that row | `LaunchpadScreen.kt:196, 241–248` over `SortieGenerationStatus`; `LaunchpadState.kt:9` `derivePendingSortieGenerationStatus` from `domain/SortieGenerationStatus.kt` |
| `regenerateFlightPlan` queues a replacement plan | `MissionManager.kt:345` `regenerateFlightPlan(sortieId, onQueued)`, `:373` `GenerationKind.FLIGHT_PLAN`, `:332` doc: returns the sortie's other open Flight Plan ids for the canceller |

Every backticked name in both rows resolves to a tracked path (`git ls-files`): `GenerationWorker.kt`,
`CoachTurnRunner.kt`, `GenerationEnqueuer.kt`, `GenerationCanceller.kt`, `MissionManager.kt`, `LaunchpadScreen.kt`,
`SortieGenerationStatus.kt`. `MissionManager.regenerateFlightPlan` is a method, not a file, and does not end in
`.kt/.json/.md`; `gen-<generationId>` is a WorkManager name, likewise. verify.sh only scans lines containing
`com.liftoff.app`, so the milestone table is not scanned at all (line 477) — the names were checked anyway.

## Done-when, with the commands and their real output

`app/build/t4-checks.sh`, run from the repo root:

1. `grep -m1 '^| M3 |' ARCHITECTURE.md | tr -d '\n' | wc -c` → **649** (≤ 1615).
2. `git diff 8de2795^ -- ARCHITECTURE.md` → one `-| M2 |` / one `+| M2 |`, one `-| M3 |` / one `+| M3 |`, so both
   rows differ from their version at `8de2795^`.
3. `grep -m100 '^| M[0-6] |' ARCHITECTURE.md | grep -icE
   'placeholder|comes next|come next|coming next|next milestone|next up|until M[0-9]|comes? with generation'` →
   **0**, grep exit 1 (nothing found).
4. M3's last cell, `grep -m1 '^| M3 |' … | awk -F '|' '{print $(NF-1)}' | grep -ic 'in progress'` → **0**, exit 1.
5. `tr -cd '\r' < ARCHITECTURE.md | wc -c` → **0**. `git ls-files --eol` agrees: `i/lf w/lf attr/text=auto eol=lf`.
6. `grep -c $'\ufffd' ARCHITECTURE.md` → **0**, exit 1. The em dashes survived the splice: `od -c` on each new row
   counts **2** occurrences of `342 200 224` in M2 and **2** in M3, which is what the rows were written with.
7. pipes per milestone row → `4 4 4 4 4 4 4`, one per row, matching the 4-pipe header.

## verify.sh's document checks

`{ sed -n '58,74p' verify.sh; sed -n '248,531p' verify.sh; } > app/build/doc-checks.sh` (the document + scope
sections only; task 6 runs the gate, tasks 1–5 are checked structurally per tasks.md), then `bash -n` and `bash`:

    ✓ milestone `M3` row has been cut down (649 bytes against the 3230 it started as)
    ✓ all seven milestone rows are present
    ✓ milestone M1/M2/M3/M4 status cell no longer carries the stale wording
    ✗ milestone M5 status cell still carries wording the brief says is gone: …partial|partly
    ✗ milestone M6 status cell still carries wording the brief says is gone: …
    ✓ milestone M0–M6 carry no 'placeholder' (7 checks)
    ✓ milestone M0–M6 name nothing that comes next (7 checks)
    ✓ milestone table has 9 rows, every one with 4 pipes like its header
    ✓ every file the package table names in backticks resolves to a tracked path (42 named)
    ✓ ARCHITECTURE.md has LF line endings
    ✓ nothing outside ARCHITECTURE.md and the run folder changed since 8de2795…

**48 passed, 2 failed, exit 1.** Before this task it was 43 passed / 7 failed. The 5 that flipped are M3's length,
M3's "In progress", M3's "come next", M2's "placeholder"/"until M4" and M2's "comes with generation". The 2 left
are M5 "Partial" and M6 "Not started", which are task 5's rows and are deliberately not touched here.
