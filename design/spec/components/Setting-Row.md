# Setting Row

Figma node 417:514 (component set since 2026-11-20; its `State=Default` variant is the old component, 21:56, so existing instances did not change). Full dump: `Setting-Row.json`. Picture: `Setting-Row.png`.

A row for a setting or a linked account: an icon bubble, a title with a subtitle, and a switch on the right.

## Properties
- State (variant): Default (default), Pressed.
- Text: Title ("HDFC Bank"), Subtitle ("Savings ••4521").
- Icon (instance swap): default is Icon/receipt, Style=3D.
- There is no property for the switch state or for hiding the switch. The dump shows it as Switch, On=True.

## Copy exactly
- Row: 328 wide, height hugs (68), horizontal, padding 12 on all sides, gap 12 (`space/12`), children centred vertically, radius 16, fill `color/bg/surface`.
- Icon bubble: 44 x 44, radius 12, fill `color/bg/surface-raised`, clips; the icon is 30 x 30, centred.
- Text column: fills the remaining width, gap 2 (`space/2`), left aligned. Title: style Title, `color/text/primary`. Subtitle: style Caption, `color/text/secondary`.
- Switch (instance of Switch, On=True): 52 x 32, radius 999, padding 4, fill `color/brand/primary`. Knob: 24 x 24 circle, fill `color/text/on-brand`, pushed to the right end.
- No border, no drop shadow or edge: this row is flat.
- Same shell as Expense Row (328 wide, padding 12, gap 12, radius 16, 44 icon bubble), so the two can share one layout.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a rectangle the size of the row (328 x 68), radius 16 (`radius/lg`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- The overlay also covers the switch. A tap on the row flips the switch, and its haptic is the toggle one, not a press haptic.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
- The 378 x 210 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.
