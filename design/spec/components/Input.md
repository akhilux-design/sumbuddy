# Input

Figma node 20:81. Files: `Input.json` (full dump), `Input.png` (all states). The dashed frame in the picture is only Figma's variant container.

**Purpose:** a text field with a label above it and, in the error state, a helper message below it.

**Properties**
- `Label` (text, default "Phone number")
- `Value` (text, default "98765 43210")
- `Helper` (text, default "That does not look right.")
- `State`: Default (default), Focused, Error

**Structure:** a column, 312 wide in the file, hugging its height: `Label`, then `Field` (a row that fills the width and holds `Value`), then `Helper` (visible only in Error, fills the width).

**Copy exactly**
- Gap between label, field and helper: 8 (`space/8`).
- `Label`: text style `Caption` (18 high), colour `color/text/secondary`.
- `Field`: padding 16 on all sides, corner radius 12, fill `color/bg/surface-raised`, content clipped, value aligned to the start and centred vertically. No shadow (no edge) in any state.
- `Value`: text style `Body/L` (24 high), colour `color/text/primary`.
- `Helper`: text style `Caption`, colour `color/status/negative`.

**Field stroke per state**
- Default: `color/border/subtle`, weight 1.5 (field 59 high).
- Focused: `color/brand/primary`, weight 2 (field 60 high).
- Error: `color/status/negative`, weight 2 (field 60 high), plus the helper line.
