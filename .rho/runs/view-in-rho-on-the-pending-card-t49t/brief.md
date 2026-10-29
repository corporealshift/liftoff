# View in rho on the Pending card

Let the owner find a pending sortie's coach session in rho's own app. DESIGN.md §9 says the PENDING card offers 'View in rho', which shows the rho session id so the owner can open it in rho's app. The design doc says 'nabu', which is the harness's old name; use 'rho'.

Builds on work already on this branch: `LaunchpadState.Pending.rhoSessionId: String?` is the session id from the sortie's newest non-cancelled FLIGHT_PLAN generation row. It is read from the `Generation.nabuSessionId` column; leave that column name alone. The PENDING card already draws its coach status lines and a Retry, and its bottom bar already holds Retry, Re-fly (or 'No earlier plan to re-fly.') and Scrub. Don't change any of those.

Change only `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`. When `rhoSessionId` is not null, the PENDING bottom bar gets an `UnderlinedTextButton(text = "View in rho", ...)` above Scrub. Tapping it reveals the id as a `LiftoffType.note()` line under the card's status lines, reading 'rho session <id>'. Use an inline line, not a dialog. The line stays until the sortie changes, and a new sortie starts with it hidden. With no session id there is no button. Use theme parts only. The label is upper-cased, so tests look for 'VIEW IN RHO'.

Tests: add a Robolectric Compose class `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadViewInRhoTest.kt` with `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`. Use the harness from LaunchpadDraftStatusTest.kt (in-memory Room, a real SettingsStore, MissionManager on `Clock.fixed(2026-01-12T00:00:00Z, UTC)`, `LaunchpadContent(viewModel, onOpenInFlight = {})` in `LiftoffTheme`) with no-op generation lambdas, no WorkManager and 5 s bounded waits. To reach PENDING: set host '127.0.0.1' and a token, `setDefaultPattern("LRL")`, `manager.onAppOpen()`, `manager.continueWithoutOutline(missionId)`. That leaves one QUEUED FLIGHT_PLAN row for sortie index 0 (`db.generationDao().getOpenFlightPlanForSortie(sortieId)`). Give it a session id with `generationDao().update(row.copy(nabuSessionId = "sess-7f3a-liftoff"))`. Cases:
- with the id: VIEW IN RHO is shown, the id isn't on screen until the tap, and after the tap the text containing 'sess-7f3a-liftoff' is displayed
- with the row but no id: no VIEW IN RHO
- not configured (no host or token, so no row): no VIEW IN RHO

Must not break: the other Launchpad tests (including LaunchpadPendingStatusTest and LaunchpadPlannedActionsTest if present), LiftoffShellTest, or the build gate. Do not change ARCHITECTURE.md in this run. Follow CLAUDE.md conventions. Decide unclear points, record them in the plan, and carry on. The gate can hang once for about 5 minutes: run it detached and re-run it once if it hangs.

Done when: the three tests pass and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
