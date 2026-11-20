# Option Card

Figma node 20:99. Files: `Option-Card.json` (full dump), `Option-Card.png` (both states). The dashed frame is only Figma's variant container.

**Purpose:** a selectable card for picking one option: icon, title, subtitle and a radio mark.

**Properties**
- `Title` (text, default "This month")
- `Subtitle` (text, default "Payments since the 1st are added.")
- `Icon` (instance swap, default `Icon/receipt`, variant `Style=3D`)
- `Selected`: False (default), True
- `State`: Default (default), Pressed

**Structure:** a wrapper column, 312 wide in the file, with 5 bottom padding (space for the edge), holding `Face`. `Face` is a row: `Icon bubble`, `Text` (fills the remaining width), `Radio`.

**Copy exactly**
- `Face`: padding 16 on all sides, gap 12 (`space/12`), corner radius 16, children centred vertically.
- `Icon bubble`: 44 x 44, corner radius 12, fill `color/bg/surface-raised`, icon 30 x 30 centred.
- `Text`: column, gap 2 (`space/2`). `Title`: style `Title`, `color/text/primary`. `Subtitle`: style `Caption`, `color/text/secondary`, wraps (two lines in the file).
- `Radio`: 24 x 24, corner radius 999.

**Per state**
- Not selected: Face fill `color/bg/surface`, stroke `color/border/subtle` 1.5, no shadow. Radio is empty with stroke `color/border/subtle` 2.
- Selected: Face fill `color/brand/soft`, stroke `color/brand/primary` 2. Radio fill `color/brand/primary` with a 10 x 10 dot in `color/text/on-brand`.
- Edge, selected only: `DROP_SHADOW(0,5,0,color/brand/edge)` on `Face`, a 0-blur shadow straight down (offset x 0, y 5).
- The 5 bottom padding stays when not selected, so the card does not move when it becomes selected.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: inside `Face`, a rectangle the size of the face (312 x 95, or 312 x 96 selected), radius 16 (`radius/lg`), so the 5 bottom edge space is not covered, absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- The face does not move: an Option Card presses flat, even when it is selected and has an edge.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
