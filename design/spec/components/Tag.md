# Tag

Figma node 20:48. Files: `Tag.json` (full dump), `Tag.png` (all tones). The dashed frame in the picture is only Figma's variant container.

**Purpose:** a small, non-interactive label badge in uppercase (default text "Auto").

**Properties**
- `Label` (text, default "Auto")
- `Tone`: Brand (default), Yellow, Purple, Blue, Negative, Neutral

**Structure:** one row that hugs its content and centres the label. Same size in every tone.

**Copy exactly**
- Corner radius 8.
- Padding 4 top/bottom, 8 left/right. Gap 0. Height 24.
- Label: text style `Label`, shown in UPPERCASE (16 high).
- No stroke and no shadow in any tone.

**Colours per tone (fill / label)**
- Brand: `color/brand/soft` / `color/text/brand`
- Yellow: `color/accent/yellow` / `color/text/on-brand`
- Purple: `color/accent/purple` / `color/text/on-brand`
- Blue: `color/accent/blue-soft` / `color/text/info`
- Negative: `color/status/negative-soft` / `color/status/negative`
- Neutral: `color/bg/surface-raised` / `color/text/secondary`
