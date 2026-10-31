# Draw the draft's coach status on the Launchpad

Draw the coach status that `LaunchpadState.Draft` now carries. Read DESIGN.md §4.2 and §9 ('Launchpad'), `design/README.md` and `design/screens/launchpad.html`.

Build on: the Draft's derived coach status (editable, planning, offline, failed with reason, not configured) in `com.liftoff.app.ui.launchpad`; `LaunchpadViewModel.confirm()`, `continueWithoutOutline()` and `retry()`; the screen's `internal fun LaunchpadContent(viewModel, onOpenInFlight)` test seam; and the theme parts in `com.liftoff.app.ui.theme` (`UnderlinedTextButton(text, onClick, modifier)`, which upper-cases its label; `PrimaryButton`; the type styles; the ink and red colours). An earlier run added an always-visible 'Continue without outline' button under Confirm in `DraftActions`, and a not-configured line driven by `viewModel.notConfiguredMessage`.

Change the draft screen:
- **editable**: today's pattern chips, −/+ controls and Confirm.
- **planning**: the line 'Coach is planning the week…'. The pattern is shown read-only, with no −/+ and no chip toggling. Offer 'Continue without outline'.
- **offline**: the line 'Mission Control offline — retrying'. Otherwise the same as planning.
- **failed**: the reason in red, with Retry and 'Continue without outline'.
- **not configured**: a line saying Mission Control isn't set up (host and token go in Mission Control), with 'Continue without outline'.
- Remove the always-visible button, so 'Continue without outline' appears only in the states above. Remove the separate `notConfiguredMessage` line too, unless a Confirm tapped with nothing configured would otherwise show nothing. In that case keep whichever single line reads best and record the decision.
- Use the design's parts only, with no stock M3 visuals.

Tests (Robolectric Compose, `@Config(sdk = [34])`, every wait bounded, CI ubuntu-latest UTC). Render the draft for each status by putting rows in an in-memory database, or through a render seam you add. Don't type into text fields, and don't use WorkManager:
- each status shows its line and exactly its buttons
- the −/+ controls are absent while planning

Update the ARCHITECTURE.md M3 row: Confirm queues the outline and the draft shows its status; queueing Flight Plans and the sortie status display come next.

Must not break: the Launchpad tests. Update any that assumed the always-visible button (for example `draftAlwaysOffersContinueWithoutOutline`), and say so in the PR. Also keep Confirm's outline queueing, the worker tests, In-Flight, Landed, Mission Control, the shell tests, the domain tests and the build gate working. Follow CLAUDE.md conventions and the §2 vocabulary. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: these tests pass, the M3 row is updated, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
