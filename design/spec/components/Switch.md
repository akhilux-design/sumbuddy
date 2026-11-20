# Switch

Figma node 20:70. Files: `Switch.json` (full dump), `Switch.png` (off and on). The dashed frame in the picture is only Figma's variant container.

**Purpose:** an on/off toggle.

**Properties**
- `On`: False (default), True. There are no text properties.

**Structure:** a fixed 52 x 32 track (a row) holding one circle, `Knob`. The knob sits at the start when off and at the end when on.

**Copy exactly**
- Track: 52 x 32, fixed. Corner radius 999. Padding 4 on all sides.
- Knob: 24 x 24 circle, centred vertically (y 4).
- Off: track fill `color/bg/surface-raised`, stroke `color/border/subtle` 1.5, knob `color/text/secondary`, knob aligned to the start (x 6 in the dump).
- On: track fill `color/brand/primary`, no stroke, knob `color/text/on-brand`, knob aligned to the end (x 24 in the dump).

**Edge:** none. Neither the track nor the knob has a shadow.

**Note:** the dump only has the two end states. Any animation between them is not defined in the file.
