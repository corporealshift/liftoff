# liftoff

Android app (Kotlin, Compose, Room). DESIGN.md holds the decisions; ARCHITECTURE.md is the map.

- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.
- Read failures from `app/build/test-results/testDebugUnitTest/TEST-*.xml`. LiftoffShellTest flakes in the full suite; rerun that class alone before chasing it.
- Large files: grep for the section, then read only that range. DESIGN.md is 47 KB; `.rho/runs/*/plan.md` up to 30 KB. Never open `.rho/goals/*/roadmap.md` (767 KB).
- Don't re-argue DESIGN.md; propose a change as an amendment at its end.
- Vocabulary: Mission, Sortie, FlightPlan, Generation. Never "session" for a sortie.
- Commits: `area: lowercase summary`. Stage named files, never `git add -A`. Comments are short and say why.
