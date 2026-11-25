PENDING Launchpad card puts the whole outline focus in display type

Found running the app: while sortie 2's Flight Plan was generating, the Launchpad title was the outline focus **"LOWER STRENGTH – SQUAT & HINGE, PLUS CORE"** in display type. It ran to five lines and pushed the status to mid-screen. Once the plan arrived, the title was the plan's short "LOWER — SQUAT & HINGE", which fits.

**Fix:** cap the PENDING title at two lines and shrink the type to fit (or ellipsize), so the PENDING card keeps the same layout as PLANNED (design/screens/launchpad.html).

**Test:** a Launchpad screen test with a long focus asserting the title is at most two lines.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#62: https://github.com/corporealshift/liftoff/issues/62)
