# Stat Tile

Figma node 41:145. Files: `Stat-Tile.json` (full dump), `Stat-Tile.png` (all tones). The dashed frame in the picture is only Figma's variant container.

**Purpose:** a small tile showing one number with an uppercase label above it (default "Spent in Nov" / "₹18,240").

**Properties**
- `Label` (text, default "Spent in Nov")
- `Value` (text, default "₹18,240")
- `Tone`: Neutral (default), Positive, Negative
- `State`: Default (default), Pressed

**Structure:** one column, 158 wide in the file, hugging its height (70). `Label` on top, `Value` below, both aligned to the start.

**Copy exactly**
- Padding 12 top/bottom, 16 left/right. Gap 2 (`space/2`).
- Corner radius 16. Fill `color/bg/surface` in every tone.
- `Label`: text style `Label`, shown in UPPERCASE (16 high), colour `color/text/secondary`.
- `Value`: text style `Heading/L` (28 high).
- No stroke and no shadow.

**Value colour per tone** (the only thing that changes)
- Neutral: `color/text/primary`
- Positive: `color/status/positive`
- Negative: `color/status/negative`

## Pressed (added 2026-11-20)
- `State`: Default (default), Pressed. Pressed is the flat pressed overlay of `docs/motion.md` (1.4, 2.3).
- Pressed adds one layer on top of everything else, `Pressed overlay`: a rectangle the size of the tile (158 x 70), radius 16 (`radius/lg`), absolute (it stretches with its parent), layer opacity 10% (`op` 0.1) with fill `color/text/primary`. Nothing else changes.
- Only tiles that open something press (Home's "Owed to you" and "You owe" tiles).
- Motion: the overlay fades in over 50 ms, stays until 100 ms after the touch and fades out over 150 ms; a cancelled press fades out over 100 ms. No movement, no scale, no ripple and no haptic. `docs/motion.md` calls this colour `state/pressed`; it is not a Figma variable, so the layer uses `color/text/primary` at 10% layer opacity, which is what the plan defines it as. Screens always use `State=Default`: Pressed is drawn only here.
