# Bottom Nav Pill

Figma node 431:679 (component set, added 2026-11-20). Full dump: `Bottom-Nav-Pill.json`. Picture: `Bottom-Nav-Pill.png`.

The active tab of the Bottom Nav: the raised green pill with the tab's icon and name. It is used only inside Bottom Nav
(`Bottom-Nav.md`), as the nested instance of the active tab.

## Properties
- `Tab` (variant): Home (default), Groups, Activity, You. It sets the icon and the label.
- `State` (variant): Default (default), Pressed.

## Copy exactly
- Wrapper: a column that hugs, padding 5 bottom (room for the edge), clips. 53 tall.
- Pill: 48 tall, width hugs (Home 104, Groups 114, Activity 117, You 89), horizontal, padding 12 top and bottom, 16
  left and right, gap 8 (`space/8`), radius 999, fill `color/brand/primary`, clips.
- Edge: effect style `Edge/Brand`, `DROP_SHADOW(0,5,0,color/brand/edge)`.
- Content: the line icon (24 x 24, 2 px stroke, `color/text/on-brand`), then the label in Body/M Strong,
  `color/text/on-brand`.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the 3D press, the same as Button Primary (`Button.md`):
  `docs/motion.md` 2.1 (BottomNav pill: "the pill gets the 3D press (2.3) without the press haptic") and 2.3
  (RaisedSurface: "the active nav pill"), with `motion/distance/press` from 1.4.
- The wrapper's padding becomes 4 top and 1 bottom (it stays 53 tall), so the Pill sits 4 lower, and its edge becomes
  `DROP_SHADOW(0,1,0,color/brand/edge)` in place of the `Edge/Brand` style: a 1 dp lip of edge stays under the pill.
  The effect has the same colour variable as the style it replaces. Nothing else changes.
- No flat overlay on the pill: the overlay its inner row drew before is removed (2.1).
- Motion: the pill springs down with `motion/spring/press` (lands in about 60 ms), shows pressed for at least 100 ms
  and springs back with `motion/spring/press-release`. No press haptic. The whole tab is the touch target; the pill
  presses through its `pressSource`. Screens always use `State=Default`: Pressed is drawn only here.
- The 546 x 180 frame with the dashed `color/border/subtle` outline is only the Figma variant container (Default on the
  first row, Pressed on the second). Do not build it.
