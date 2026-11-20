# Chip

Figma node 20:33. Files: `Chip.json` (full dump), `Chip.png` (both variants). The dashed frame in the picture is only Figma's variant container.

**Purpose:** a small pill with a text label that can be selected or not (default label "Equally").

**Properties**
- `Label` (text, default "Equally")
- `Selected`: False (default), True
- `State`: Default (default), Pressed

**Structure:** one row that hugs its content and centres the label. No inner frames.

**Copy exactly**
- Corner radius 999 (full pill).
- Padding 8 top/bottom, 16 left/right. Gap 0.
- Label: text style `Body/M Strong` (20 high).
- Not selected: fill `color/bg/surface-raised`, stroke `color/border/subtle` 1.5, label `color/text/primary`. Height 36, the same as selected: the stroke is drawn inside and is not counted in the layout.
- Selected: fill `color/brand/primary`, no stroke, label `color/text/on-brand`. Height 36.

**Edge:** none. The chip has no shadow in either state.

**Note:** both states are the same size (81 x 36 for the same label). Changed in Figma on 2026-11-17.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a pill the size of the chip (81 x 36 for "Equally"), radius 999 (`radius/full`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- Both Selected values have a Pressed variant. The chip keeps its size.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
