GenerationWorker: NotConfigured and AuthenticationRejected retry with no limit

In `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` `doWork`, the connection-check `else -> Result.retry()` branch covers `ConnectionOutcome.NotConfigured` and `AuthenticationRejected`. By then the row is already RUNNING (`markRunning`). This branch has no give-up and no status mark, so a cleared host or a wrong token leaves the card on "Coach is planning…" indefinitely. WorkManager's backoff caps at 5 hours and never stops. The 8-attempt give-up only applies to `Unreachable`.

**Fix:** fail the generation in both cases with a reason the Launchpad shows. Use "Mission Control refused the token" for `AuthenticationRejected` and "Mission Control isn't set up" for `NotConfigured`. The card then offers Retry and Re-fly, as for any FAILED generation.

**Test:** one GenerationWorkerTest case for each outcome, asserting FAILED with that reason.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#54: https://github.com/corporealshift/liftoff/issues/54)
