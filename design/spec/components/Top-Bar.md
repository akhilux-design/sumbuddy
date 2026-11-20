# Top Bar

Figma node 21:66 (single component, no variants). Full dump: `Top-Bar.json`. Picture: `Top-Bar.png`.

The header of a screen: a round back button on the left, the screen title, and a text action on the right.

## Properties
- Text: Title ("Flat 302").
- Nested instances, exposed in the instance panel (since 2026-11-20):
  - `Back`: Top Bar Back (`Top-Bar-Back.md`), `State` Default or Pressed.
  - `Action`: Top Bar Action (`Top-Bar-Action.md`), `State` Default or Pressed, text `Label` ("Edit").
- The action text is the `Label` of the nested `Action` instance. The old `Action` text property of the bar was removed
  on 2026-11-20; every screen kept its text (an empty `Label` where the bar showed no action).
- There is no property for hiding the back button or the action. In the app the action is not drawn when the screen
  has none (an empty `Label` on the screens).

## Copy exactly
- Bar: 360 wide, height hugs (64), horizontal, padding 12 top and bottom, 16 left and right, gap 12 (`space/12`), children centred vertically.
- The bar has no fill, no border and no shadow: the screen background shows through.
- Back button: 40 x 40, radius 999 (a circle), fill `color/bg/surface-raised`, clips. Inside it an icon frame of 20 x 20, centred. The arrow is one vector, 5.8 x 11.7, drawn with a 2 px stroke in `color/text/primary` (no fill).
- Title: fills the remaining width (237 here), left aligned, style Heading/M, `color/text/primary`.
- Action: hugs its text, style Body/M Strong, `color/text/brand`.
- No drop shadow or edge anywhere in this component.

## Pressed (added 2026-11-20)
- The bar has two press targets, so it has no `State` of its own. The back button and the action are their own
  components, each with `State` Default (default) and Pressed, and the bar holds one instance of each. Every screen
  uses `State=Default` for both.
- Back pressed: the flat pressed overlay in the 40 circle (`Top-Bar-Back.md`).
- Action pressed: the flat pressed overlay as a full pill over the action's touch area, the text plus 12 on each side
  and 48 high (`Top-Bar-Action.md`).
- Both come from `docs/motion.md` 2.3 ("TopBar back and action" are covered by the flat `state/pressed` overlay, drawn
  inside the element's own clip) and 1.4 (`state/pressed` is `color/text/primary` at 10%).
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled
  press fades out over 100 ms. No movement, no scale, no ripple and no haptic (Back never plays a haptic).
