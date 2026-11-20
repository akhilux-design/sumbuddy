# Status Bar

Figma node 78:2921 (single component, no variants, no properties). Full dump: `Status-Bar.json`. Picture: `Status-Bar.png`.

The phone status bar drawn at the top of each screen: the time on the left, signal and battery on the right. It is a fixed drawing with nothing to configure.

## Copy exactly
- Bar: 360 wide, height hugs (46), horizontal, padding 14 top and bottom, 20 left and right, space-between, children centred vertically. No fill, no border, no shadow.
- Time: "9:41", style UI/Status, `color/text/primary`.
- Icons group: horizontal, gap 8 (`space/8`), 52 x 14.
- Signal: four bars, each 3 wide with radius 1, heights 5, 8, 11 and 14, gap 2 (`space/2`), aligned to the bottom, fill `color/text/primary`.
- Battery: 26 x 13, radius 4, 1.5 border in `color/text/primary`, no fill. Inside it a 15 x 10 rectangle at x 2, y 2, fill `color/text/primary`.
- Every colour here is the variable `color/text/primary`; there are no fixed hex colours.
- No drop shadow or edge anywhere in this component.
