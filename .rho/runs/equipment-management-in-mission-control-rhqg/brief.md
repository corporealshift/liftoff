# Equipment management in Mission Control

Add equipment management to the Mission Control screen. The coach later uses the equipment list to plan lifts (DESIGN.md §7.1, §8). Read DESIGN.md §8 and `design/README.md` first.

Build on what's already in the repo:
- the Mission Control screen and its state logic, which already edits the settings. Add an Equipment section to it.
- `EquipmentDao` in `com.liftoff.app.data`, reached through `AppContainer`'s database. It has add (rejects a key that doesn't match `[a-z0-9_]+`), edit of name and notes, active-only and all lists as flows, deactivate and reactivate. The key column is unique, so inserting a duplicate key throws.
- the theme composables (`InkRuledListRow`, the buttons, paper cards with ink borders)

**Behaviour.**
- The section lists active equipment, one row per item, showing key — name — notes.
- Add: enter key, name and optional notes. An invalid key, or one already used by any item (active or inactive), gets an inline error and nothing is saved. Name is required.
- Edit: change an item's name and notes. The key is fixed once created, because history and prompts refer to it.
- Deactivate: removes the item from the main list. Nothing is ever deleted.
- A way to show deactivated items, each with a Reactivate action.
The list updates live from Room.

Use the same design parts as the rest of Mission Control. No stock M3 tonal surfaces or pill shapes. Dialogs and text fields should use the theme's fonts and ink borders.

Put the validation and state in plain Kotlin that a JVM or Robolectric test can drive against an in-memory database. Compose UI tests are not required.

Must not break: the settings editing in Mission Control, the shell, the data layer and its tests, and the build gate. Follow CLAUDE.md conventions.

Done when:
- Equipment can be added, edited, deactivated and reactivated from Mission Control, and changes persist.
- Tests cover the key rules (invalid pattern, duplicate against an active item, duplicate against an inactive item), the name requirement, editing, and deactivate/reactivate moving an item between the two lists without deleting it.
- ARCHITECTURE.md's milestone table marks M1 (skeleton and data, theme, shell, Mission Control with equipment) as done, with a note that test connection and export/import come later.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
