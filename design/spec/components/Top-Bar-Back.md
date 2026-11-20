# Top Bar Back

Figma node 430:569 (component set, added 2026-11-20). Full dump: `Top-Bar-Back.json`. Picture: `Top-Bar-Back.png`.

The round back button of the Top Bar. It is used only inside Top Bar (`Top-Bar.md`), as the nested instance `Back`.

## Properties
- `State` (variant): Default (default), Pressed.

## Copy exactly
- 40 x 40, horizontal, children centred, radius 999 (a circle), fill `color/bg/surface-raised`, clips.
- Inside it an icon frame of 20 x 20, centred. The arrow is one vector, 5.8 x 11.7, drawn with a 2 px stroke in
  `color/text/primary` (no fill). It is the line icon of the top bar.
- The touch area is 48 x 48: 4 past the circle on every side (the app's `touchOverhang`). It is not drawn.
- No drop shadow or edge.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3: "TopBar back
  and action").
- Pressed adds one layer on top of everything else, `Pressed overlay`: a rectangle the size of the circle (40 x 40),
  radius 999 (`radius/full`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill
  `color/text/primary`. The circle clips it. Nothing else changes.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled
  press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour
  `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is
  what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
- The 154 x 90 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.
