# Avatar

Figma node 20:63. Files: `Avatar.json` (full dump), `Avatar.png` (all colours). The dashed frame in the picture is only Figma's variant container.

**Purpose:** a round avatar showing one initial letter on a coloured circle.

**Properties**
- `Initial` (text, default "R")
- `Color`: Green (default), Yellow, Pink, Purple, Blue, Orange

**Structure:** a fixed 40 x 40 frame with the initial centred both ways. No inner frames.

**Copy exactly**
- Size 40 x 40, fixed. Corner radius 999 (circle). No padding.
- Initial: text style `Title` (22 high), colour `color/text/on-brand` in every variant.
- No stroke and no shadow.

**Which colour** (a note under the component on the Design System page)
- The person is always Purple.
- Other people take Green, Pink, Blue, Orange, Yellow, then Green again, in the order they first appear (groups
  oldest first, members in stored order).
- Named groups take Green, Yellow, Blue, Pink, Orange, then Green again, oldest first.
- A split with one friend takes that friend's colour.

**Fill per colour**
- Green: `color/brand/primary`
- Yellow: `color/accent/yellow`
- Pink: `color/accent/pink`
- Purple: `color/accent/purple`
- Blue: `color/accent/blue`
- Orange: `color/accent/orange`
