GenerationWorker: any non-daemon exception after markRunning strands the row

In `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`, `runGeneration` only catches `DaemonException`. Anything else thrown after `markRunning` fails the WorkManager work and leaves the row RUNNING with no worker behind it, and the UI shows Planning. That includes a throw from the validator, the store transaction, `PromptInputLoader`, Room or a `requireNotNull`. The app-open sweep then re-runs the same failing path. The empty exercise name in the validator is one real trigger (see the related issue).

**Fix:** wrap the work after `markRunning` in one `catch (e: Exception)` that rethrows `CancellationException` and otherwise calls `failGeneration` with `e.message`.

**Test:** make the store throw (a fake or a bad reply that reaches `writePlan`) and assert the row ends FAILED with the message.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#55: https://github.com/corporealshift/liftoff/issues/55)
