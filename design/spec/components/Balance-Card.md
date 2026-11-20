# Balance Card

Figma node 21:198 (component set). Full dump: `Balance-Card.json`. Picture: `Balance-Card.png`.

The large summary card: a small upper-case label, a big amount, and a caption under it.

## Variants and properties
- Tone (variant): Positive (default), Negative, Neutral.
- State (variant): Default (default), Pressed.
- Text: Label ("YOU ARE OWED"), Amount ("₹3,450"), Caption ("from 4 friends").

## Copy exactly
- Wrapper: 328 wide, height hugs (145), column, 5 bottom padding (room for the edge), no fill.
- Face: fills the width, height hugs (140), column, padding 20 on all sides, gap 4 (`space/4`), radius 24, content left aligned.
- Label: style Label, upper case. Amount: style Amount/XL. Caption: style Body/M Strong.
- Edge: a drop shadow on the Face with x 0, y 5, blur 0. It is a solid darker band 5 px under the card, not a soft shadow.
- Per Tone:

| Tone | Face fill | Edge (`DROP_SHADOW(0,5,0,...)`) | All three texts |
|---|---|---|---|
| Positive | `color/brand/primary` | `color/brand/edge` | `color/text/on-brand` |
| Negative | `color/accent/pink` | `color/accent/pink-edge` | `color/text/on-brand` |
| Neutral | `color/bg/surface-raised` | `color/edge/neutral` | `color/text/primary` |

- No border on any variant. All three variants show the same sample text.
- The 1082 x 364 frame with the dashed outline is only the Figma variant container. Do not build it.

## Pressed (added 2026-11-20)
- Only a card that opens something presses (Home's spent card). It is the same 3D press as the Primary button.
- Pressed: the wrapper's padding becomes 4 top and 1 bottom (still 145 high), so the Face sits 4 lower
  (`motion/distance/press`), and the edge becomes `DROP_SHADOW(0,1,0,...)` with the tone's edge colour from the table
  above: a 1 dp lip stays.
- Motion: down with `motion/spring/press`, at least 100 ms pressed, back up with `motion/spring/press-release`;
  `haptic/press` on phones with rich haptics. Screens always use `State=Default`.
