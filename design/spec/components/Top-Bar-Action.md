# Top Bar Action

Figma node 430:576 (component set, added 2026-11-20). Full dump: `Top-Bar-Action.json`. Picture: `Top-Bar-Action.png`.

The text action at the end of the Top Bar ("Edit", "Add", "Add all", "Cancel", "Restore"). It is used only inside Top
Bar (`Top-Bar.md`), as the nested instance `Action`.

## Properties
- `State` (variant): Default (default), Pressed.
- Text: `Label` ("Edit"). Screens with no action leave it empty; the app then draws no action.

## Copy exactly
- A row that hugs its text, no padding, no fill, no clip. In the Top Bar it is 27 x 20 for "Edit".
- `Label`: style Body/M Strong, `color/text/brand`, one line.
- The touch area is the text plus 12 on the left and right (equal to the gap to the title) and 14 above and below,
  so it is 48 high (at least 48 wide). It is not drawn in Default.
- No drop shadow or edge.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3: "TopBar back
  and action").
- Pressed adds one layer on top of the text, `Pressed overlay`: a full pill over the touch area, x -12 and y -14 from
  the text, 24 wider than the text and 48 high (51 x 48 for "Edit"), radius 999 (`radius/full`), absolute with both
  constraints Stretch (it keeps 12 and 14 beyond the text for any label), layer opacity 10% (`op` 0.1) with fill
  `color/text/primary`. The component does not clip, so the pill shows outside the text's box. Nothing else changes.
- The app clips the touch box to `RoundedCornerShape(Radius.full)` and draws the press there, so the overlay is that
  pill, not the text box.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled
  press fades out over 100 ms. No movement, no scale, no ripple and no haptic (Cancel never plays one). `docs/motion.md`
  calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer
  opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
- The 176 x 98 frame with the dashed `color/border/subtle` outline is only the Figma variant container (padding 38 top
  and bottom, 36 left and right, gap 48, so the overlay fits inside). Do not build it.
