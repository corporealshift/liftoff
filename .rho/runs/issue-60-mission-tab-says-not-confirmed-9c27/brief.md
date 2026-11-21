Mission tab says 'Not confirmed yet' while the outline is generating

Found running the app: after pressing **Confirm** on the Launchpad draft, while the outline generation was running ("Coach is planning the week…" on the Launchpad), the Mission tab said **"Not confirmed yet. Confirm this Mission on the Launchpad."**

`app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` shows that line for every `MissionStatus.DRAFT`, including a draft with an open OUTLINE generation.

**Fix:** when the draft has an open outline generation, show the same coach status the Launchpad draft shows: planning, offline, or failed with Retry/Continue. Keep "Not confirmed yet" for a draft nobody has confirmed.

**Test:** a MissionState test for the draft-with-open-outline case.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#60: https://github.com/corporealshift/liftoff/issues/60)
