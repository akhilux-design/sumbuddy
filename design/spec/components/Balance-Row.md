# Balance Row

Figma node 21:53 (component set). Full dump: `Balance-Row.json`. Picture: `Balance-Row.png`.

One person's balance in a list: an avatar, a name with a detail line, and the amount on the right.

## Variants and properties
- Direction (variant): Owes you (default), You owe, Settled, Plain.
- State (variant): Default (default), Pressed.
- Text: Name ("Rahul", shown in the Title layer), Detail ("owes you", shown in the Subtitle layer), Amount ("+₹1,200").

## Copy exactly
- Row: 328 wide, height hugs (66), horizontal, padding 12 on all sides, gap 12 (`space/12`), children centred vertically, radius 16, fill `color/bg/surface`.
- Avatar: instance of Avatar, Color=Green. 40 x 40, radius 999, fill `color/brand/primary`, initial "R" centred in style Title, `color/text/on-brand`.
- Text column: fills the remaining width, gap 2 (`space/2`), left aligned. Title: style Title, `color/text/primary`. Subtitle: style Caption, `color/text/secondary`.
- Amount: one text that hugs its content, style Amount/M. Its colour is the only thing that changes per Direction:
  - Owes you: `color/status/positive`
  - You owe: `color/status/negative`
  - Settled: `color/text/tertiary`
  - Plain: `color/text/primary`
- All four variants show the same sample text ("owes you", "+₹1,200"); set the real text and sign through the properties.
- No border, no drop shadow or edge: this row is flat.
- The 378 x 746 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a rectangle the size of the row (328 x 66), radius 16 (`radius/lg`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
