# Button

Figma node 20:26. Files: `Button.json` (full dump), `Button.png` (all variants). The dashed frame in the picture is only Figma's variant container.

**Purpose:** the tappable action button with a text label.

**Properties**
- `Label` (text, default "Button")
- `Style`: Primary (default), Secondary, Ghost
- `Size`: Large (default), Medium
- `State`: Default (default), Pressed

**Structure:** a wrapper column with 5 bottom padding (space kept for the edge) holding one frame, `Face`. `Face` fills the wrapper width, hugs its height, and centres the label.

**Copy exactly**
- Corner radius 16 on `Face`, for both sizes.
- Large: padding 16 top/bottom, 24 left/right. Face 52 high, 57 with the edge space.
- Medium: padding 12 top/bottom, 16 left/right. Face 44 high, 49 with the edge space.
- Label: text style `Button` (20 high), one line, centred.
- Primary: fill `color/brand/primary`, label `color/text/on-brand`.
- Secondary: fill `color/bg/surface-raised`, stroke `color/border/subtle` 1.5 inside, label `color/text/primary`. The
  stroke is drawn inside the face and does not add to its size (Figma: stroke not included in layout), so a Secondary
  face is the same size as a Primary or Ghost face with the same label: 52 high (Large) or 44 high (Medium), 57 or 49
  with the edge space, with the same padding.
  On a container that is itself `color/bg/surface-raised` (Home's payment card and tip, a bottom
  sheet, a dialog card), both boards override `Face` to `color/bg/on-raised` (neutral/50 Light, neutral/700 Dark),
  so the button never matches the card and never looks disabled.
- Ghost: no fill, no stroke, no shadow, label `color/text/brand`. The wrapper still keeps the 5 bottom padding, so all styles line up.

**Edge (0-blur shadow straight down)**
- Primary: `DROP_SHADOW(0,5,0,color/brand/edge)` on `Face`: offset x 0, y 5, blur 0.
- Secondary: `DROP_SHADOW(0,5,0,color/edge/neutral)` on `Face`.

**Pressed (added 2026-11-20)**
- Screens always use `State=Default`. Pressed is drawn only here, on the second row of the variant container (734 x 188).
- Primary and Secondary: the 3D press. The wrapper's padding becomes 4 top and 1 bottom (it stays 57 or 49 high), so
  `Face` sits 4 lower (`motion/distance/press`, 4 dp). Its edge becomes `DROP_SHADOW(0,1,0,color/brand/edge)` (Primary)
  or `DROP_SHADOW(0,1,0,color/edge/neutral)` (Secondary), so a 1 dp lip of edge stays under the face. The effect has the
  same colour variable as the `Edge/*` style it replaces. A Secondary on a raised card keeps its `color/bg/on-raised`
  Face override when pressed.
- Ghost: a pill appears behind the label. `Face` gets fill `color/brand/soft` at 100% (radius 16, the face's own shape,
  52 or 44 high); the 5 bottom padding stays empty and nothing moves. `docs/motion.md` calls this colour
  `state/pressed-ghost`; it is not a Figma variable, so the face uses `color/brand/soft`, which is what the plan defines
  it as.
- Motion: the face springs down with `motion/spring/press` (lands in about 60 ms) and plays `haptic/press` on phones with
  rich haptics; a press shows for at least 100 ms; it springs back with `motion/spring/press-release`. The Ghost pill
  fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms, with no haptic. See `../motion.md`.
