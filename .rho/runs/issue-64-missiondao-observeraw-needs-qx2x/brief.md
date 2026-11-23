MissionDao.observeRaw needs @Transaction

From rho's review of #52. `app/src/main/java/com/liftoff/app/data/MissionDao.kt` (~line 46): `observeRaw` returns `MissionWithSorties?`, whose sorties come from an `@Relation` (`Relations.kt`), with no `@Transaction`. It is the one warning the gate prints. Room runs the parent and relation SELECTs separately, so a land/advance transaction between them can make `observeWeek` emit a sortie still PLANNED next to a plan row that was already replaced. LaunchpadScreen and MissionScreen consume that Flow.

**Fix:** annotate `observeRaw` (and any other `@Relation` query in the DAOs) with `@Transaction`, and confirm the gate prints no Room warnings.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#64: https://github.com/corporealshift/liftoff/issues/64)
