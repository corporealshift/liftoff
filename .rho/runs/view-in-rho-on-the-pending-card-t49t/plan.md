# Plan

## Approach

The id is already derived and live: `LaunchpadState.Pending.rhoSessionId` carries the newest
non-cancelled FLIGHT_PLAN generation row's `nabuSessionId`, whatever that row's status is. So nothing
in the data, derivation or view-model layer changes — this is only what the PENDING card draws. The
brief's two halves sit in two different composables today (`PendingContent` draws the status lines in
the Scaffold's content Column, `PendingActions` draws the bar's controls in the Scaffold's bottomBar),
so the reveal is one piece of screen state held in `LaunchpadContent`, where both are composed, and
passed down to each — the same way the bar's ten view-model lambdas are already passed. It is keyed on
the PENDING card's `sortieId`, which is what makes the line survive the status line changing but not a
new sortie. Everything drawn uses the parts the card already uses: `UnderlinedTextButton` for the
control, `LiftoffType.note()` in the card's quiet colour for the id line. See the map for where each
piece lives.

## Files

- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — the only production change:
  reveal state held in `LaunchpadContent` keyed on the PENDING sortie's id; a
  `UnderlinedTextButton("View in rho")` drawn above Scrub in `PendingActions` when that card's
  `rhoSessionId` is not null; the `rho session <id>` note line at the end of `PendingContent`, after
  the status line and any validator lines; the two new arguments threaded through `ActionButtons` to
  the Pending arm only, defaulted so the other arms' call sites are untouched. What the file already
  holds is in the map.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadViewInRhoTest.kt` — new: three Robolectric
  Compose cases on the real PENDING card, on the harness `LaunchpadPendingStatusTest` already uses
  (which is `LaunchpadDraftStatusTest`'s).
- No other file. `LaunchpadState.kt`, `SortieGenerationStatus.kt`, `GenerationDao.kt`, the theme and
  `ARCHITECTURE.md` are not touched.

## Order

1. Draw it: reveal state in `LaunchpadContent` keyed on the PENDING `sortieId`, the button in
   `PendingActions` above Scrub, the id line at the end of `PendingContent`, the new arguments
   threaded through `ActionButtons`. Green with `bash gradlew.sh :app:compileDebugKotlin` and the
   existing Launchpad card tests unchanged.
2. Write `LaunchpadViewInRhoTest.kt` with the cases below, and run it class-scoped.
3. Prove each case bites with a one-token mutation of the screen file (drop the id condition on the
   button; make the tap set nothing; drop the condition on the id line), one log per mutation, then
   restore the file from a backup taken before the mutations and confirm `git status` is clean.
4. Run the whole gate detached — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
   --no-build-cache` — and read the XML counts, not just `BUILD SUCCESSFUL`.

## Testing

All in `LaunchpadViewInRhoTest`, Robolectric Compose over the real PENDING card the screen builds
(host + token, pattern `LRL`, `onAppOpen` then `continueWithoutOutline`, which leaves one QUEUED
FLIGHT_PLAN row for sortie 0), with the id put on that row by `generationDao().update(row.copy(…))`.
Three cases, as the brief lists them:

- **Row with an id:** `VIEW IN RHO` is shown; no `sess-7f3a-liftoff` text is on screen before the
  button is tapped; after the tap the line `rho session sess-7f3a-liftoff` is displayed under the
  card's status lines.
- **Row with no id:** no `VIEW IN RHO`.
- **Not configured** — no host or token, so `continueWithoutOutline` queued no row at all: no
  `VIEW IN RHO`.

The rule that the line stays until the sortie changes, and that a new sortie starts with it hidden, is
carried by the `sortieId` key rather than by a test: no case the brief asks for changes the sortie.

## Risks

- A new control on the PENDING bar can push a node out of the fixed `@Config` window
  (`w411dp-h2000dp`) and redden an existing `assertIsDisplayed` in another Launchpad class or
  `LiftoffShellTest`. It should not: the button exists only when the row carries an id, which no
  existing test seeds. If a class does break, give it the same `@Config` — never weaken the assertion.
- The reveal key decides the behaviour: keyed on nothing it survives a new sortie; keyed on the state
  object it resets on every emission and the line vanishes the moment the status line changes. Key it
  on the PENDING card's `sortieId` and nothing else.
- `ActionButtons` is the dispatch every card arm goes through. Keep the new arguments defaulted so the
  Draft, Planned, In-Flight and Closed arms and their call sites are untouched.
- The screen's `observe()` is still collecting when the test's `@After` runs: copy
  `LaunchpadPendingStatusTest`'s teardown that cancels the scope Job before `db.close()`, or the test
  fails with a closed connection pool.
- Assert the whole line — `rho session sess-7f3a-liftoff` — not just the id as a substring, or a line
  with the wrong wording still passes. The button label is upper-cased by the theme, so look for
  `VIEW IN RHO`; the id line is a plain `Text` and is not.
- The button is in the Scaffold's bottomBar slot and the id line in its content Column, so do not
  assert their order by node bounds across the two slots. Assert the line's presence after the tap,
  and its absence before it.
- The gate can hang once for about five minutes and write no XML. Run it detached, and if it does,
  re-run the identical tree once without `--stop`; move `app/build/test-results` aside with `mv`,
  never delete it.

## Decisions

**Does "View in rho" open rho, or copy the id?** It does neither: the tap only reveals the id as a text line, and the owner opens the session in rho's own app by hand. The alternative was to copy the id to the clipboard, or launch rho directly. DESIGN.md §9 says the card shows the id *so the owner can open it in rho's app* — the opening is theirs. Copying would be an unannounced side effect on a label that says "view", launching another app is a bigger change than this brief covers, and the revealed line is the same plain note line the card already uses for everything else it reports, not a field the owner has to reach for.

**Can the revealed id be hidden again?** The reveal is one-way: a second tap does nothing, and the line stays until the sortie changes. The alternative was a toggle. The brief says the line stays until the sortie changes, and a toggle would let a mis-tap make the id vanish when the owner is mid-way through typing it into rho.

**Is the button shown while a generation is still running?** Yes — the button appears whenever `rhoSessionId` is not null, whatever the card's coach status line says. The alternative was to hide it while the card reads Planning or Offline. The id comes from the newest non-cancelled FLIGHT_PLAN generation row, so while a turn is open it is the session actually running — exactly the one the owner would want to open.

**Does the revealed line survive leaving Launchpad and coming back?** No: the reveal is the Launchpad screen's own state, so it resets when the card's sortie changes and when the screen is torn down by navigation. The alternative was to hold it in the view model so it persisted across tabs. The brief's rule is about the sortie, not about navigation, and the id is always one tap away, so persisting a reveal across tabs would need view-model state the brief does not ask for.
