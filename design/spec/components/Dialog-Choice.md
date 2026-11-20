# Dialog Choice

Figma node 432:670 (component set, added 2026-11-20). Full dump: `Dialog-Choice.json`. Picture: `Dialog-Choice.png`.

One choice of a confirm dialog (the app's ChoiceDialog), such as "Leave group" and "Stay" on `02-everyday/92`. Two of
them stack in the dialog card's `Choices` column, gap 8 (`space/8`). Until 2026-11-20 each dialog drew them as frames
named `Choice`; they are now instances of this component with the same layer name, on `02-everyday/47`, `60`, `92`,
`93`, `04-you/03`, `08` and `06-family/21`, `22`, `26`. The Android permission dialog on `01-onboarding/06` is the
system's own dialog and keeps its frames.

## Properties
- `State` (variant): Default (default), Pressed.
- Text: `Label` ("Leave group").

## Copy exactly
- 280 wide here; in a dialog it fills the card's width (280). Height hugs (44).
- Horizontal, padding 12 top and bottom, 16 left and right, children centred, radius 999, fill `color/bg/surface`,
  clips.
- `Label`: style Body/M Strong, `color/text/primary`, centred.
- The touch area is at least 48 high (the app's outer touch box); it is not drawn.
- No stroke, drop shadow or edge.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4; 2.2: "DialogChoice
  pills ... put the press overlay on the inner clipped pill, not on the outer 48 dp touch box"; 2.3 overlay placement:
  "DialogChoice: the same, on the inner pill"; section 4 asks for one dialog choice pill drawn pressed).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a pill the size of the choice (280 x 44), radius
  999 (`radius/full`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill
  `color/text/primary`. Nothing else changes.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled
  press fades out over 100 ms. No movement, no scale, no ripple and no haptic on the press (the result of the dialog
  plays confirm or reject, 2.7). `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the
  layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use
  `State=Default`: Pressed is drawn only here.
- The 330 x 162 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.
