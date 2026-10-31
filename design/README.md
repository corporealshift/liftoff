# Liftoff — visual design

The look the app is built to: direction **B, "Space Age"**. A 1960s space-program poster:
warm cream paper, heavy ink outlines, hard offset shadows, condensed display type, and a
red / mustard / teal stripe. It was chosen over two other directions on 2026-10-06 (see
`DESIGN.md` §9).

```
design/
  README.md               this file: tokens, components, how the mockups map to §9
  screens/launchpad.html  Launchpad, PLANNED state
  screens/in-flight.html  In-Flight checklist, mid-sortie
  icon/liftoff-icon.svg   launcher icon source (108×108 adaptive-icon canvas)
```

The two screens are static HTML mockups of a 390×844 phone. Open them in a browser. Every
size in them is CSS px, and **1 px = 1 dp**. The values are sample data. Words that
appear in the mockups (Launch, Land, Flight Plan, Sortie, Landed, Mission Control)
are the §2 vocabulary, and the strings must keep them.

The app is Compose + Material 3 (§9, §11). Build this look as a custom `MaterialTheme`
(color scheme, typography, shapes) plus a few small composables for the parts M3 has no
component for: the stripes, the offset shadow and the pattern track. Do not ship stock
M3 visuals (tonal surfaces, pill buttons, ripples on cream) where the mockup shows
something else.

---

## Color

| Token | Hex | Used for |
|---|---|---|
| `cream` | `#F2EADB` | Screen background |
| `paper` | `#FBF6EC` | Cards and raised surfaces (exercise cards) |
| `sand` | `#E8DFCD` | A finished exercise collapsed into one row |
| `ink` | `#1D1B19` | Text, every border, the bottom bar, offset shadows |
| `red` | `#C23F14` | The primary action (Launch, a done set's check), exercise numbers, deviations |
| `red_pressed` | `#8F2E0E` | Pressed state of red |
| `mustard` | `#E3A72F` | Active bottom-bar item, filled progress, numbers on dark |
| `teal` | `#1F5F6B` | In-Flight header, the "Coach:" label, "LANDED" status |
| `teal_light` | `#9FBFC4` | Unfilled progress segments on teal |
| `muted` | `#5E5850` | Secondary text, eyebrows, set labels |
| `rule` | `#CFC6B6` | Hairlines between set rows; inactive bottom-bar items on ink |
| `white` | `#FFFFFF` | Text on red; an unchecked set box |

The app is light only. There is no dark theme in v1.

## Type

Two Google Fonts, both under the Open Font License. Bundle them in `res/font/`.

- **Big Shoulders Display** (700, 800, 900): everything that is a headline, a label in
  capitals, or a number.
- **Work Sans** (400, 500, 600): body text and small labels.

| Role | Font | Size / weight | Notes |
|---|---|---|---|
| Wordmark "LIFTOFF" | Big Shoulders | 24 / 900 | letter-spacing 0.14em |
| Screen title (Launchpad) | Big Shoulders | 68 / 900 | uppercase, line-height 0.9 |
| Screen title (In-Flight header) | Big Shoulders | 44 / 900 | uppercase |
| Primary button (Launch) | Big Shoulders | 34 / 900 | letter-spacing 0.16em |
| Primary button (Land) | Big Shoulders | 30 / 900 | letter-spacing 0.16em |
| Section head ("FLIGHT PLAN") | Big Shoulders | 20 / 800 | letter-spacing 0.1em |
| Exercise card title | Big Shoulders | 22 / 800 | uppercase, letter-spacing 0.04em |
| Weight / reps in a set row | Big Shoulders | 24 / 800 | "35 LB", "× 10" |
| Load in the plan list | Big Shoulders | 20 / 800 | "3×8 · 135" |
| Index ("01") | Big Shoulders | 16 / 800 | red on cream, mustard on ink |
| Bottom-bar label | Big Shoulders | 14 / 800 | uppercase, letter-spacing 0.12em |
| Eyebrow | Work Sans | 12 / 600 | uppercase, letter-spacing 0.16em, `muted` |
| Exercise name (plan list) | Work Sans | 16 / 500 | |
| Text button | Work Sans | 15 / 600 | underlined, offset 4 |
| Secondary / coach note | Work Sans | 13 / 500–600 | |

## Shape and depth

- **Borders:** 2 dp `ink` on buttons and cards. The current pattern chip has a 3 dp border.
- **Corners:** 4 dp on buttons and checkboxes, square on cards. Circles only for pattern chips.
- **Offset shadow:** a solid `ink` copy of the shape, 4 dp right and 4 dp down, no blur.
  It is drawn on the primary button and the active exercise card. The Land button's
  shadow is `red`. Nothing else has elevation.
- **Stripes:** the Launchpad opens with three full-width 6 dp bands: red, mustard, teal.
  The In-Flight header ends with two 5 dp bands: mustard, then red.
- **Touch targets:** at least 44 dp. The set check box is 48 dp.

---

## Screens and components

### Launchpad, PLANNED (`screens/launchpad.html`)

Top to bottom:

1. **Stripes**, then a top bar: the wordmark on the left and a 44 dp outlined icon button
   (sliders icon) on the right that opens **Mission Control**.
2. **Eyebrow + title:** "WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT", then the sortie title in
   68 sp display type.
3. **Pattern track:** one chip per sortie in the week's pattern (`R L R L R`), joined by a
   2 dp ink line.
   - Landed: 44 dp, filled `ink`, cream check mark.
   - Current: 52 dp, filled `red`, 3 dp ink border, white letter.
   - Upcoming: 44 dp, `cream` with a 2 dp ink border.
4. **Flight Plan:** a "FLIGHT PLAN" head with "≈55 min · 14 sets" at the right, over a
   3 dp ink rule. Below it, one row per exercise: red index, name, then load at the right,
   with 1 dp ink rules between rows. Under the list sits the coach's note: "Coach:" in
   teal, then the text in `muted`.
5. **Launch:** a 72 dp, full-width red button with a 2 dp ink border, the offset shadow,
   and a rocket glyph. Under it are two underlined text buttons, **Regenerate** and
   **Scrub**.
6. **Bottom bar:** `ink` background with three items: Launchpad, Mission, Landed. Each is
   an icon over a label. The active item is `mustard`; the others are `rule`.

The other Launchpad states in §9 (PENDING, IN_FLIGHT → Resume, no Mission this week) are
not mocked up. Build them from the same parts. For example, PENDING replaces the Flight
Plan list with the status line and puts Retry / Re-fly / Scrub where Launch is.

### In-Flight (`screens/in-flight.html`)

1. **Header,** `teal`: the eyebrow "IN FLIGHT · SORTIE 2" in mustard and the title in
   cream. At the right is the set counter "05/14" (done in mustard, total in cream) over
   "SETS". Below that is a progress bar of one segment per set: done segments are filled
   `mustard`, open ones are outlined in `teal_light`. Then the mustard and red bands.
2. **Exercise cards,** scrolling. Each card has one of three states:
   - **Done:** collapsed to one `sand` row with the index, the name struck through, and
     "3/3 LANDED" in teal at the right.
   - **Active:** a `paper` card with the offset shadow. Its header is an `ink` bar holding
     a mustard index and the uppercase name. Its set rows follow, and a footer row holds
     **+ Add set · Note · Skip**, split by 1 dp ink rules.
   - **Upcoming:** the same card without the shadow.
3. **Set row,** 58 dp: "SET n" label, then weight and reps, which are tap targets to edit.
   At the right is a 48 dp check box: `red` with a white check when done, white when open.
   A set logged differently from the plan shows the actual value in `red` with a small
   "of 10" beside it (§6: edit only on deviation). The current set's label is red.
4. **Land:** a footer with a 3 dp ink rule above it and a full-width 64 dp `ink` button
   with a cream label, a flag glyph, and a red offset shadow.

Mission, Landed and Mission Control are not mocked up. Use the same parts: the eyebrow
and display title, ink-ruled lists, `paper` cards with ink borders, the bottom bar.

### Icons inside the app

The mockups draw stroke icons inline: a 24 dp grid, 2 dp stroke, round caps. They are
rocket (Launchpad / Launch), ringed planet (Mission), flag (Landed / Land) and sliders
(Mission Control). They can be written as vector drawables with the same paths, or
replaced with the closest Material Symbols icon at the same weight.

---

## Launcher icon

A dumbbell satellite. The plates form the body, teal solar-panel wings stretch out on
either side, and a dish sits on top. It is tilted 25° on a mustard field.

- Source: `icon/liftoff-icon.svg`, on the 108×108 adaptive-icon canvas.
- In the app: `res/drawable/ic_launcher_foreground.xml` (a vector drawable translated
  from the SVG), the background color `ic_launcher_background` (`#E3A72F`), and
  `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`. `minSdk` is 26, so no raster
  fallbacks are needed.
- Everything sits inside the 66 dp safe zone with room to spare (the panel tips reach a 30 dp radius), so no
  launcher mask clips it.
- There is no monochrome (themed-icon) layer yet.
