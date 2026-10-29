# Plan

## Approach

The id is already derived and live: `LaunchpadState.Pending.rhoSessionId` carries the newest
non-cancelled FLIGHT_PLAN generation row's `nabuSessionId`, whatever that row's status is. So nothing
in the data, derivation or view-model layer changes. The only change is what the PENDING card draws. The
brief's two halves sit in two different composables today (`PendingContent` draws the status lines in
the Scaffold's content Column, `PendingActions` draws the bar's controls in the Scaffold's bottomBar),
so the reveal is one piece of screen state held in `LaunchpadContent`, where both are composed, and
passed down to each, the same way the bar's ten view-model lambdas are already passed. It is a
`rememberSaveable` boolean keyed on the PENDING card's `sortieId` (null when the card is not PENDING),
following the `rememberSaveable` screen state in `LandedScreen` and `MissionScreen`. That key is what
makes the line survive the status line changing but not a new sortie. The id line is drawn only while
the reveal is set **and** the current state's `rhoSessionId` is not null, and it always shows the
current id. Everything drawn uses the parts the card already uses: `UnderlinedTextButton` for the
control, `LiftoffType.note()` in `Muted` for the id line. See `map.md` for where each piece lives.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` is the only production change:
  reveal state held in `LaunchpadContent` keyed on the PENDING sortie's id; a
  `UnderlinedTextButton("View in rho")` drawn in `PendingActions` after the Re-fly block and directly
  above Scrub (with the bar's usual 12 dp spacer) when that card's `rhoSessionId` is not null; the
  `rho session <id>` note line at the end of `PendingContent`, after the status line and any validator
  lines; the two new arguments (revealed flag, reveal callback) threaded through `ActionButtons` to the
  Pending arm only, defaulted so the other arms' call sites are untouched. `map.md` lists what the file
  already holds.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadViewInRhoTest.kt` is new: three Robolectric
  Compose cases on the real PENDING card, using the harness `LaunchpadPendingStatusTest` already uses
  (which is `LaunchpadDraftStatusTest`'s).
- No other file. `LaunchpadState.kt`, `SortieGenerationStatus.kt`, `GenerationDao.kt`, the theme and
  `ARCHITECTURE.md` are not touched.

## Order

1. Draw it: reveal state in `LaunchpadContent` keyed on the PENDING `sortieId`, the button in
   `PendingActions` above Scrub, the id line at the end of `PendingContent`, the new arguments
   threaded through `ActionButtons`. It is green when `bash gradlew.sh :app:compileDebugKotlin` passes
   and the existing Launchpad card tests pass unchanged.
2. Write `LaunchpadViewInRhoTest.kt` with the cases below, and run only that class.
3. Prove each case catches a fault with a one-token mutation of the screen file (drop the id condition
   on the button; make the tap set nothing; drop the reveal condition on the id line), one log per
   mutation. Then restore the file from a backup taken before the mutations and confirm `git diff`
   against the backup is empty.
4. Run the whole gate detached with `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
   --no-build-cache`, and read the XML counts, not just `BUILD SUCCESSFUL`.

## Testing

All cases are in `LaunchpadViewInRhoTest`, as Robolectric Compose over the real PENDING card the
screen builds (host + token, pattern `LRL`, `onAppOpen` then `continueWithoutOutline`, which leaves one
QUEUED FLIGHT_PLAN row for sortie 0). Each case puts the id on that row with
`generationDao().update(row.copy(nabuSessionId = "sess-7f3a-liftoff"))`. There are three cases, as the
brief lists them:

- **Row with an id:** wait (5 s) for `VIEW IN RHO` to be shown; check that no text containing
  `sess-7f3a-liftoff` (substring match) is on screen before the tap; after the tap, the line
  `rho session sess-7f3a-liftoff` is displayed.
- **Row with no id:** first wait for the PENDING card itself (its status line, or `SCRUB`) to be
  displayed, then assert there is no `VIEW IN RHO`.
- **Not configured** (no host or token, so `continueWithoutOutline` queued no row at all): the same
  wait for the PENDING card first, then assert there is no `VIEW IN RHO`.

The rule that the line stays until the sortie changes, and that a new sortie starts with it hidden, is
carried by the `sortieId` key rather than by a test, because no case the brief asks for changes the
sortie.

## Risks

- Asserting absence before the card has rendered proves nothing: during Loading there is no button
  either. Both absence cases must wait for the PENDING card first.
- A new control on the PENDING bar can push a node out of the fixed `@Config` window
  (`w411dp-h2000dp`) and break an existing `assertIsDisplayed` in another Launchpad class or
  `LiftoffShellTest`. It should not: the button exists only when the row carries an id, which no
  existing test seeds. If a class does break, give it the same `@Config`. Never weaken the assertion.
- The reveal key decides the behaviour. Keyed on nothing, it survives a new sortie. Keyed on the state
  object, it resets on every emission and the line vanishes the moment the status line changes. Key it
  on the PENDING card's `sortieId` and nothing else.
- Drawing the line from the reveal flag alone would print `rho session null` when a Retry queues a
  fresh row that has no id yet. Guard on both the flag and a non-null `rhoSessionId`.
- `ActionButtons` is the dispatch every card arm goes through. Keep the new arguments defaulted so the
  Draft, Planned, In-Flight and Closed arms and their call sites are untouched.
- The screen's `observe()` is still collecting when the test's `@After` runs. Copy
  `LaunchpadPendingStatusTest`'s teardown that cancels the scope Job before `db.close()`, or the test
  fails with a closed connection pool.
- Assert the whole line, `rho session sess-7f3a-liftoff`, not just the id as a substring, or a line
  with the wrong wording still passes. The theme upper-cases the button label, so look for
  `VIEW IN RHO`. The id line is a plain `Text` and is not upper-cased.
- The button is in the Scaffold's bottomBar slot and the id line is in its content Column, so do not
  assert their order by node bounds across the two slots. Assert the line's presence after the tap,
  and its absence before it.
- The gate can hang once for about five minutes and write no XML. Run it detached. If it hangs,
  re-run the same tree once without `--stop`. Move `app/build/test-results` aside with `mv`; never
  delete it.

## Decisions

**Does the revealed line survive leaving Launchpad and coming back?** No: the reveal is the Launchpad screen's own state, so it resets when the card's sortie changes and when the screen is torn down by navigation. The alternative was to hold it in the view model so it persisted across tabs. The brief's rule is about the sortie, not about navigation, and the id is always one tap away, so persisting a reveal across tabs would need view-model state the brief does not ask for.
Changed by review: the plan chose plain `remember`, which also resets the line on rotation or any other activity recreation. The review chose `rememberSaveable` keyed on `sortieId`, so the line survives recreation but still resets when the owner leaves the tab (the shell has no saveable state holder per tab). The owner is likely to rotate the phone or switch apps while copying the id into rho, and losing the line then would be surprising. The other screens already use `rememberSaveable` for this kind of UI state.

**What does the line show when the sortie's session id changes or disappears?** Added by review. The line always shows the current state's `rhoSessionId`. If a Retry or Re-fly queues a fresh row that has no id yet, the button and the line are both absent. When the new row gets its id, the line comes back showing the new id without another tap, because the sortie has not changed. The alternative was to keep showing the old id, but that would point the owner at a stale rho session.

**Does "View in rho" open rho, or copy the id?** It does neither: the tap only reveals the id as a text line, and the owner opens the session in rho's own app by hand. The alternative was to copy the id to the clipboard, or launch rho directly. DESIGN.md §9 says the card shows the id *so the owner can open it in rho's app*, so the opening is theirs. Copying would be an unannounced side effect on a label that says "view", launching another app is a bigger change than this brief covers, and the revealed line is the same plain note line the card already uses for everything else it reports, not a field the owner has to reach for.

**Can the revealed id be hidden again?** The reveal is one-way: a second tap does nothing, and the line stays until the sortie changes. The alternative was a toggle. The brief says the line stays until the sortie changes, and a toggle would let a mis-tap make the id vanish while the owner is typing it into rho.

**Is the button shown while a generation is still running?** Yes: the button appears whenever `rhoSessionId` is not null, whatever the card's coach status line says. The alternative was to hide it while the card reads Planning or Offline. The id comes from the newest non-cancelled FLIGHT_PLAN generation row, so while a turn is open it is the session actually running, which is exactly the one the owner would want to open.
