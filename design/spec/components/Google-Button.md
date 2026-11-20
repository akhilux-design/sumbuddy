# Google Button

Figma node 432:661 (component set, added 2026-11-20). Full dump: `Google-Button.json`. Picture: `Google-Button.png`.

"Continue with Google": the Google sign-in button on Sign in (`01-onboarding/01`, `02`), Sign in (family)
(`06-family/08`), Join group (sign in first) (`02-everyday/49`) and Groups (not signed in) (`02-everyday/39`). Until
2026-11-20 each screen drew it as a frame named `Google button`; they are now instances of this component with the same
layer name.

## Properties
- `State` (variant): Default (default), Pressed.
- No text property: the label is always "Continue with Google".

## Copy exactly
- 328 wide here; on screens it fills its parent (328 in a footer, 277 in the Groups empty state). Height hugs (52).
- Horizontal, padding 16 top and bottom, 12 left and right, gap 10, children centred, radius 999, clips.
- Fill `color/google/button-fill`, 1 px stroke inside in `color/google/button-stroke`.
- Google logo: 20 x 20, the four Google colours as plain fills (#EA4335, #4285F4, #FBBC05, #34A853). They are Google's
  own colours, not tokens.
- Label: "Continue with Google", Roboto Medium 14 (no text style: Google's branding asks for Roboto Medium), fill
  `color/google/button-text`.
- No drop shadow or edge.

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4; 2.3 lists
  "GoogleButton" among the elements covered by the overlay, and its overlay placement row says: keep Google's
  branding; the overlay is allowed by Google's state guidance; check it in both themes).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a pill the size of the button (328 x 52), radius
  999 (`radius/full`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill
  `color/text/primary`. Logo, label, fill and stroke do not change, so the branding stays as Google draws it.
- Checked in both themes on 2026-11-20: a slight darkening of the white button in Light and a slight lightening of the
  dark button in Dark; the logo and the label stay clear.
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled
  press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour
  `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is
  what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
- The 378 x 178 frame with the dashed `color/border/subtle` outline is only the Figma variant container. Do not build it.
