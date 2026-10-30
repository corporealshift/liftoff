# Task 5 — milestone rows M4–M6

One file changed: `ARCHITECTURE.md`, three rows replaced whole by a byte-level `perl` splice from
`app/build/t5-M4.txt`, `app/build/t5-M5.txt` and `app/build/t5-M6.txt` (no `-CSD`, so the title cells' UTF-8
bytes pass through untouched). `git diff --stat` is `ARCHITECTURE.md | 6 +++---` — three rows out, three in,
nothing else in the document moved. No Kotlin, resource or build file touched.

## The rows

**M4** — 245 bytes before, **229 after**. It already read Done; its three semicolon clauses are now one
sentence. Before (`8de2795^`, unchanged by tasks 1–4):

    | M4 | In flight — In-Flight screen, Launch, Land, resume after process kill | Done; the In-Flight screen is
    a real screen; the shell reopens into the in-flight sortie on a cold start; the screen leaves when its sortie
    is no longer in flight |

After:

    | M4 | In flight — In-Flight screen, Launch, Land, resume after process kill | Done — the In-Flight screen is
    a real screen that the shell reopens into on a cold start, and that leaves when its sortie is no longer in
    flight |

**M5** — 294 bytes before, **217 after**. Before:

    | M5 | Landed and safety nets — history screens, Re-fly, export/import | Partial: the Landed history list and
    its detail view are built; Re-fly is built — the pure source chooser, the copy through MissionManager and the
    Launchpad's PENDING action are in place; export and import are built |

After:

    | M5 | Landed and safety nets — history screens, Re-fly, export/import | Done — the Landed history list and
    its detail view, Re-fly through `ReFly.kt` and `MissionManager.kt`, and export and import are all built |

"Partial:" is gone and the three legs the brief names — Landed history, Re-fly, export/import — are each one
phrase. The old cell's detail about the Launchpad's PENDING action is not lost: the `ui` row (line 37) already
names Re-fly among the Launchpad's controls.

**M6** — 76 bytes before, **212 after**. Before:

    | M6 | Run plans — run-generation toggle, run Flight Plans | Not started |

After:

    | M6 | Run plans — run-generation toggle, run Flight Plans | Done — the run-generation toggle is honoured: a
    Run sortie gets a simple "Run" plan when it is off, and a generated run Flight Plan when it is on |

M6 has no length ceiling — only the `work` row and M3 do — so the row grows from a bare "Not started" to the
one sentence the brief asks it to say.

## The claims, checked against the source

| Claim in the new cells | Where it holds |
|---|---|
| The In-Flight screen is a real screen | `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` |
| The shell reopens into it on a cold start | `LiftoffShell.kt:92` `nav = ShellNav.startNav(null, ids)`; `:125` draws `InFlightScreen` when `nav.inFlightSortieId != null` |
| The screen leaves when its sortie is no longer in flight | `InFlightViewModel.kt:20` `sortie == null \|\| sortie.state != SortieState.IN_FLIGHT`, `:52` `watchLeave` — "the first moment it is gone or no longer IN_FLIGHT, leave — once" |
| Landed history list and its detail view | `ui/landed/LandedScreen.kt`, `LandedState.kt`, `LandedStates.kt`, `LandedViewModel.kt` |
| Re-fly through `ReFly.kt` and `MissionManager.kt` | `domain/ReFly.kt` (the pure chooser), `data/ReFlyCopy.kt` (the copy), `MissionManager.refly(sortieId)`; offered from the Launchpad at `LaunchpadScreen.kt:106` / `:457` |
| Export and import | `data/DatabaseExport.kt`, `data/DatabaseImport.kt`, hosted by `ui/control/MissionControlScreen.kt` |
| A Run sortie gets a simple "Run" plan when the toggle is off | `domain/SortiePlanning.kt:13` `prepareCurrent` returns `Preparation.SimplePlan` for `SortieType.RUN && !generateRunPlans`; `:20` `simpleRunPlan` writes `source = SIMPLE_RUN`, `title = "Run"` |
| …and a generated run Flight Plan when it is on | `SortiePlanning.kt:16` otherwise `Preparation.AwaitGeneration`; `MissionManager.kt:457` `prepareCurrent(current, settings.generateRunPlans)` and `:463` the `AwaitGeneration` arm queues generation |
| The toggle is the authority, not a suggestion | `MissionManager.kt:365` `check(sortie.type != SortieType.RUN \|\| settings.generateRunPlans)` — a RUN regeneration is refused while the toggle is off (`:343` comment: "this function stays the authority") |
| The run content and its landing are tested | `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt`, `RunLandingTest.kt` — both tracked, both named by `verify.sh` |

`ReFly.kt` and `MissionManager.kt` are tracked paths (`git ls-files`), so the two names M5 puts in backticks
resolve. `reflyDraft` is a function, not a file, and is not named.

## The task's Done-when, run

`bash app/build/t5-check.sh` (script kept at `app/build/t5-check.sh`; scratch under gitignored `app/build/`):

```
M4 pipes                                                   4 ok
M4 last cell                                               clean
M4 row rewritten                                           yes (was untouched by tasks 1-4)
M4 title cell                                              unchanged
M4 row bytes                                               229 (was 245)
M5 pipes                                                   4 ok
M5 last cell                                               clean
M5 differs from 8de2795^                                   yes
M5 title cell                                              unchanged
M5 row bytes                                               217 (was 294)
M6 pipes                                                   4 ok
M6 last cell                                               clean
M6 differs from 8de2795^                                   yes
M6 title cell                                              unchanged
M6 row bytes                                               212 (was 76)
milestone header                                           unchanged
milestone table lines                                      header + separator + 7 milestone rows
milestone rows                                             7
CR bytes                                                   0
U+FFFD                                                     0
M4 em dashes                                               2
M5 em dashes                                               2
M6 em dashes                                               2
scope                                                      only ARCHITECTURE.md + .rho/ differ

task-5 checks: 0 failure(s)
```

"last cell clean" is the grep `partial|partly|not started|not begun|not yet|in progress` finding none in each of
the three Status cells; "title cell unchanged" compares cell 2 of each row byte-for-byte with its version at
`8de2795^`; the header line is still `| Milestone | Title | Status |` and the table still holds M0–M6.

## verify.sh's document and scope sections

`{ sed -n '58,74p' "$V"; sed -n '248,531p' "$V"; } > app/build/t5-doc-checks.sh; bash -n … && bash …` — the
script's own document and scope checks, without its gate-report section:

```
=== Result: 50 passed, 0 failed ===
BRIEF DONE.
EXIT=0
```

The two failures that were this task's (milestone M5 status cell, milestone M6 status cell) now pass, as do
M4's status cell and the milestone table-shape check ("milestone table has 9 rows, every one with 4 pipes like
its header"). The 19 named test classes are not re-run here: tasks.md says only task 6 runs the gate, and this
task changed no Kotlin, resource or build file.

## Left alone

M0 stays `Partial:` with `LiveCoachTest` named as not run (plan Decision 1; `verify.sh` exempts M0 from the
"reads as done" check at line 426). M1–M3 were rewritten by task 4 and are byte-identical to their task-4 state.
The package table, topology, resource locations, data flow and the invariants are untouched — task 6 does the
whole-document sweep and the gate.
