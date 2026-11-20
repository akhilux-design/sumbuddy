# Expense Row

Figma node 21:29 (component set). Full dump: `Expense-Row.json`. Picture: `Expense-Row.png`.

One expense in a list: an icon bubble, a title with a subtitle, and on the right the amount with a short note (Meta) under it.

## Variants and properties
- Kind (variant): Personal (default), Shared, Review.
- State (variant): Default (default), Pressed.
- Text: Title ("Swiggy"), Subtitle ("Food · HDFC card ••4521"), Amount ("₹450"), Meta ("Just you").
- Icon (instance swap): default is Icon/receipt, Style=3D.

## Copy exactly
- Row: 328 wide, height hugs (68), horizontal, padding 12 on all sides, gap 12 (`space/12`), children centred vertically, radius 16, fill `color/bg/surface`.
- Icon bubble: 44 x 44, radius 12, fill `color/bg/surface-raised`, clips; the icon is 30 x 30, centred.
- Text column: fills the remaining width, gap 2 (`space/2`), left aligned. Title: style Title, `color/text/primary`. Subtitle: style Caption, `color/text/secondary`.
- Amount column: hugs, gap 2 (`space/2`), right aligned. Amount: style Amount/M, `color/text/primary`. Meta: style Caption, colour by Kind.
- Only this changes per Kind:
  - Personal: Meta `color/text/tertiary`, no border.
  - Shared: Meta `color/text/brand`, no border.
  - Review: Meta `color/text/warning`, plus a 1.5 border in `color/accent/yellow`. The border takes space: the row is 71 tall and the content starts at 14 instead of 12.
- No drop shadow or edge: this row is flat.
- The 378 x 584 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a rectangle the size of the row (328 x 68, or 328 x 71 for Review), radius 16 (`radius/lg`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- For Review the overlay also covers the yellow border.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
