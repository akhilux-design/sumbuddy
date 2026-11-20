# Info Row

Figma node 417:522 (component set since 2026-11-20; its `State=Default` variant is the old component, 22:32, so existing instances did not change). Full dump: `Info-Row.json`. Picture: `Info-Row.png`.

A plain information line: an icon bubble with a title and one line of explanation. It has no background of its own and no control on the right.

## Properties
- State (variant): Default (default), Pressed.
- Text: Title ("Read on your phone only"), Subtitle ("Nothing is uploaded, ever.").
- Icon (instance swap): default is Icon/receipt, Style=3D.

## Copy exactly
- Row: 328 wide, height hugs (44), horizontal, no padding, gap 12 (`space/12`), children centred vertically.
- The row has no fill, no radius, no border and no shadow.
- Icon bubble: 44 x 44, radius 12, fill `color/bg/surface-raised`, clips; the icon is 30 x 30, centred.
- Text column: fills the remaining width (272), gap 2 (`space/2`), left aligned.
- Title: style Title, `color/text/primary`.
- Subtitle: style Body/M, `color/text/secondary`. Note this is Body/M, not Caption as in Expense Row and Setting Row.
- No drop shadow or edge anywhere in this component.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a rectangle the size of the row (328 x 44) with no radius, because the row has no shape of its own (the app draws the overlay in the row bounds, without a clip), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- Only Info Rows that open something (links) press. Plain information rows never show Pressed.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
- The 378 x 162 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.
