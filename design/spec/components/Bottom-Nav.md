# Bottom Nav

Figma node 21:183 (component set). Full dump: `Bottom-Nav.json`. Picture: `Bottom-Nav.png`.

The bottom navigation: a pill-shaped dock with four tabs (Home, Groups, Activity, You). The active tab is a raised pill with icon and label; the other three show only an icon.

## Variants and properties
- Active (variant): Home (default), Groups, Activity, You.
- No text or swap properties. Labels are "Home", "Groups", "Activity", "You".
- Since 2026-11-20 the four tabs are instances, exposed in the instance panel: the active tab is a Bottom Nav Pill
  (`Bottom-Nav-Pill.md`) and the other three are Bottom Nav Icons (`Bottom-Nav-Icon.md`). Each has `Tab` (the tab it
  shows) and `State` (Default, Pressed). The layers keep their names, `Tab/Home` to `Tab/You`.

## Copy exactly
- Outer: 360 wide, height hugs (96), padding 16 top, 16 left and right, 8 bottom. No fill.
- Dock: fills the width (328), height hugs (72), radius 999, padding 8 on all sides, fill `color/bg/surface-raised`, 1.5 border in `color/border/subtle`, clips. Tabs are spread with space-between and centred vertically.
- Inactive tab: 48 x 48, icon frame 24 x 24 centred, icon drawn with a 2 px stroke in `color/text/tertiary`. No label.
- Active tab: a column that hugs, with 5 bottom padding (room for the edge), so it is 53 tall. It holds the Pill.
- Pill: 48 tall, width hugs (Home 104, Groups 114, Activity 117, You 89), horizontal, padding 12 top and bottom, 16 left and right, gap 8 (`space/8`), radius 999, fill `color/brand/primary`.
- Pill edge: `DROP_SHADOW(0,5,0,color/brand/edge)`. That is x 0, y 5, blur 0: a solid darker band 5 px under the pill, not a soft shadow.
- Pill content: icon 24 x 24 with a 2 px stroke in `color/text/on-brand`, then the label in style Body/M Strong, `color/text/on-brand`.
- The same icon is used in both states; only the stroke colour changes (`color/text/tertiary` to `color/text/on-brand`).
- Tab x positions move per variant because of space-between. Do not hard-code them.
- The 410 x 506 frame with the dashed outline is only the Figma variant container. Do not build it.

## Pressed (added 2026-11-20)
- The bar has several press targets, so `Active` stays its only variant. Each tab carries its own `State`; every
  screen uses `State=Default` for all four.
- Active pill pressed: the 3D press, as on Button (`Bottom-Nav-Pill.md`). `docs/motion.md` 2.1 (BottomNav pill: "the
  pill gets the 3D press (2.3) without the press haptic", and the overlay its inner row drew is removed) and 2.3
  (RaisedSurface, "the active nav pill").
- Inactive icon pressed: the flat pressed overlay in a 48 circle (`Bottom-Nav-Icon.md`). `docs/motion.md` 2.3
  ("nav icons" are covered by the flat `state/pressed` overlay) and 1.4.
- A tab change plays the `haptic/selection` tick (owner rule 3); the press itself plays no haptic. The pill slide is in
  `../motion.md` (Tab change).
