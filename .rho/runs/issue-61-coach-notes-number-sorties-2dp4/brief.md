Coach notes number sorties from 0, while the app numbers them from 1

Found running the app: the outline's week note said *"runs 0 and 2 stay easy … run 4, placed last … Lift 1 carries the legs … lift 3 is push/pull"*. The Mission tab lists the same sorties as 01–05.

The prompt lists sorties as `index 0 — run — pending` (`CoachPromptBuilder.kt`, the `"index ${sortie.index} — ..."` lines), and the outline schema's `index` is 0-based. So the coach writes 0-based numbers in its prose.

**Fix:** keep the schema's 0-based `index`, but give each sortie its display number in the prompt too, e.g. `sortie 1 (index 0) — run — pending`. Add a line to the prompt (or `coach/COACH.md`) asking the coach to refer to sorties in notes by that number or by type and focus, never by index. Update the golden prompt files.

**Test:** the golden files cover it.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#61: https://github.com/corporealshift/liftoff/issues/61)
