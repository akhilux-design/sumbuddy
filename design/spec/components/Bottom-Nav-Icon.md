# Bottom Nav Icon

Figma node 431:680 (component set, added 2026-11-20). Full dump: `Bottom-Nav-Icon.json`. Picture: `Bottom-Nav-Icon.png`.

An inactive tab of the Bottom Nav: only the tab's line icon in a 48 touch area. It is used only inside Bottom Nav
(`Bottom-Nav.md`), as the nested instances of the three tabs that are not active.

## Properties
- `Tab` (variant): Home (default), Groups, Activity, You. It sets the icon.
- `State` (variant): Default (default), Pressed.

## Copy exactly
- 48 x 48, horizontal, children centred, no fill, clips.
- Icon frame 24 x 24, centred; the icon is drawn with a 2 px stroke in `color/text/tertiary`. No label (screen readers
  read the tab's name).

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3: "nav icons").
- Pressed adds one layer on top of the icon, `Pressed overlay`: a 48 x 48 circle (radius 999, `radius/full`), the
  app's `CircleShape` clip, absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill
  `color/text/primary`. Nothing else changes.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled
  press fades out over 100 ms. No movement, no scale, no ripple and no press haptic (the tab change itself plays
  `haptic/selection`). `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer
  uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use
  `State=Default`: Pressed is drawn only here.
- The 314 x 170 frame with the dashed `color/border/subtle` outline is only the Figma variant container (Default on the
  first row, Pressed on the second). Do not build it.
