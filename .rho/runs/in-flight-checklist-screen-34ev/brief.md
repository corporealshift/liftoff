# In-Flight checklist screen

Replace the placeholder In-Flight screen with the real checklist UI. Read DESIGN.md §6, the 'In-Flight' section of `design/README.md`, and `design/screens/in-flight.html` first. The HTML is a static mockup where 1 px = 1 dp.

Build on what's already in the repo:
- the In-Flight state, its derivation, its actions and its plain state holder in `com.liftoff.app.ui.inflight`, added by the previous brief. Use them; don't re-derive state in the UI.
- `InFlightScreen(sortieId, container, onBack)`, which Launch and Resume on the Launchpad already open. Keep its signature.
- the theme in `com.liftoff.app.ui.theme`:
  - color tokens (Teal, TealLight, Mustard, Red, Sand, Paper, Ink, Cream, Muted, Rule, White)
  - `DuoStripe`, the offset-shadow helpers, `LiftoffType`, `LiftoffIcons` (including check), `UnderlinedTextButton` and `PrimaryButton`

**Header.**
- A teal header with the mustard eyebrow, the title in cream, and the counter on the right ('05' large in mustard, '/14' in cream, 'SETS' under it).
- Under it, a progress bar with one segment per set:
  - mustard when done
  - outlined teal_light when open
  - filled teal_light when skipped
- Then the mustard and red bands.

**Cards.**
- **Done:** a sand row with a 2 dp ink border, the name struck through, and 'n/n LANDED' in teal.
- **Skipped:** the same row, saying 'SKIPPED'.
- **Active:** a paper card with a 2 dp ink border, an ink header bar (a mustard index and the uppercase name in cream) and the ink offset shadow.
- **Upcoming:** the same card without the shadow.
- Tapping a collapsed done or skipped row expands it into a full card, so a mis-tap can be fixed. Tapping its header collapses it again.
- The footer of an active or upcoming card has '+ Add set · Note · Skip' (Unskip on an expanded skipped card). Note is shown disabled; the next brief makes it work.

**Set rows (58 dp).**
- Each row shows 'SET n' (red for the current set, muted otherwise), the weight, the reps or seconds, and a 48 dp check box with a 2 dp ink border and 4 dp corners.
- The box is white when open, red with a white check when done, and shows a muted dash when skipped.
- One tap on the box checks the set. Tapping a done box unchecks it, and tapping a skipped box reopens it.
- A deviating value shows the actual in red with a small muted 'of <planned>'.
- Tapping the numbers, or a long press on the row, opens a stepper in the theme's style. Use a plain Dialog with custom content, not AlertDialog or a bottom sheet. It has:
  - − and + controls for weight (±5 lb or ±2.5 kg; 0 means bodyweight) and for reps (±1) or seconds (±5)
  - Save, which marks the set done with those values
  - Skip set
  - Cancel

**Bottom area.** A 3 dp ink rule at the top of a cream footer that stays in view. It holds a Back text button for now. The Land button replaces it in the next brief, so don't show a Land button that does nothing.

A sortie with no checklist shows the header without the counter, with a short muted line.

The UI must not cache state. Every tap goes through the actions and the screen re-renders from Room, so after a process kill, reopening the route shows exactly what was left. Nothing uses the network.

Must not break: the state and action tests from the previous brief, the Launchpad and Mission screens, Mission Control, the shell, and the build gate. `InFlightScreenTest` must keep passing; update it only where the placeholder text is gone. Follow CLAUDE.md conventions and the §2 vocabulary.

Done when:
- For a seeded lift plan, the screen shows the header, progress and cards in done, active and upcoming states as in the mockup.
- A Robolectric Compose test on a seeded in-memory or test database shows that tapping a check box marks the set done in Room and updates the counter.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
