# Segmented Toggle

Figma node 47:49. Files: `Segmented-Toggle.json` (full dump), `Segmented-Toggle.png` (both states). The dashed frame is only Figma's variant container.

**Purpose:** a two-option switch inside one pill-shaped track (default labels "Me" and "Family").

**Properties**
- `Left` (text, default "Me")
- `Right` (text, default "Family")
- `Selected`: Left (default), Right
- `State`: Default (default), Pressed

**Structure:** a track (row, hugs its content) with two children side by side. The chosen side is a frame `Selected` that wraps a raised `Pill`; the other side is a flat frame `Segment`.

**Copy exactly**
- Track: padding 4 on all sides, gap 0, corner radius 999, fill `color/bg/surface-raised`, stroke `color/border/subtle` 1.5, children centred vertically. 44 high in the file.
- `Pill` and `Segment`: padding 4 top/bottom, 12 left/right, corner radius 999, 28 high, label centred. Widths come from the labels (45 for "Me", 68 for "Family"); the two sides are not equal width.
- Labels: text style `Body/M Strong` (20 high).
- Selected side: `Pill` fill `color/brand/primary`, label `color/text/on-brand`.
- Other side: `Segment` has no fill, label `color/text/secondary`.

**Edge (0-blur shadow straight down)**
- `Pill` has `DROP_SHADOW(0,5,0,color/brand/edge)`: offset x 0, y 5, blur 0.
- The `Selected` wrapper has 5 bottom padding (33 high in total) and clips its content, so exactly 5 of the edge shows under the pill.
- `Segment` and the track have no shadow.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: inside the unselected `Segment` (the label area, 68 x 28 for "Family" or 45 x 28 for "Me"), radius 999 (`radius/full`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- The raised `Pill` does not press: tapping the chosen side does nothing. The touch area is taller than the segment, but the overlay stays on the label area.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
