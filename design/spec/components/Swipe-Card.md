# Swipe Card

Figma node 151:292. Files: `Swipe-Card.json` (full dump), `Swipe-Card.png` (all stamps). The dashed frame in the picture is only Figma's variant container.

**Purpose:** one waiting payment on Review payments, shown as the top card of a stack. Swipe right to add it to
spending, left to ignore it. `Stamp=Add` and `Stamp=Ignore` are the dragging states.

**Properties**
- `Amount` (text, default "₹450")
- `Payee` (text, default "Swiggy"; the UPI ID when the SMS has no name)
- `When` (text, default "Today, 9:12 pm")
- `Account` (text, default "HDFC Credit Card ••4521")
- `Category` (text, default "Food"), the suggested category
- `Category icon` (instance swap, default `Icon/food`, Style=3D)
- `Stamp`: None (default, resting), Add (dragged right), Ignore (dragged left)

**Structure:** one column, 328 wide, hugging its height (413 to 414; 437 with a two-line payee), children centred. From
the top:
1. `Top`: a row that fills the width, fixed 40 high, children centred vertically. None: the `Tag` (Tone=Blue, "From SMS")
   centred. Add: the stamp at the start (left). Ignore: the stamp at the end (right).
2. `Amount`: a column, gap 4 (`space/4`), centred: amount in `Amount/XL`, payee in `Heading/M`, both `color/text/primary`.
   The payee text is centre-aligned, so a payee on two lines (a UPI ID broken after its "@", see `02-everyday/127`) is
   centred line by line.
3. `Divider`: 1 high, fills the width, `color/border/subtle`.
4. `Details`: a column, gap 8 (`space/8`), of two rows that fill the width with space between: label in `Body/M`
   `color/text/secondary` ("When", "Account"), value in `Body/M Strong` `color/text/primary`.
5. `Divider` again.
6. `Category row`: "Category" (`Body/M`, `color/text/secondary`) at the start, `Category chip` at the end. The chip is a
   button that opens Choose category: a pill (radius 999), fill `color/bg/surface`, stroke `color/border/subtle` 1.5,
   padding 8 top/bottom, 8 left, 16 right, gap 8 (`space/8`), 51 high: the 3D category icon (32), the category name
   (`Body/M Strong`, `color/text/primary`) and "Change" (`Body/M Strong`, `color/text/brand`).
7. `Split button`: Button Style=Ghost, Size=Medium, "Split it instead", hugging its width. Opens Add to expenses.

**Copy exactly**
- Padding 20 on every side (`space/20`), gap 16 (`space/16`). Corner radius 24 (`radius/xl`).
- Fill `color/bg/surface-raised` in every stamp. Nothing clips.

**Per stamp (the only things that change)**
- None: stroke `color/border/subtle` 1.5, effect `Edge/Neutral` (0, 5, blur 0, `color/edge/neutral`).
- Add: stroke `color/brand/primary` 2, effect `Edge/Brand` (`color/brand/edge`). Stamp: fill `color/brand/soft`,
  stroke `color/brand/primary` 2, radius 12 (`radius/md`), padding 6 top/bottom, 12 left, 14 right, gap 8; `Icon/check`
  Style=Line (24) and the word "Add" in `Heading/M`, both `color/text/brand`.
- Ignore: stroke `color/status/negative` 2, effect `Edge/Neutral`. Stamp: fill `color/status/negative-soft`, stroke
  `color/status/negative` 2, same size and padding; `Icon/ban` Style=Line (24) and the word "Ignore" in `Heading/M`, both
  `color/status/negative`.
- The stamp is always a word plus an icon, never colour alone.

**On the screen** (Review payments): the card sits on top of two plain "next card" frames (fill
`color/bg/surface-raised`, stroke `color/border/subtle` 1.5, radius 24), each 12 lower and 24 narrower than the one
above. The next cards are 1 taller than the card and the `Card stack` frame is 35 taller (414 and 448 for a 413 card),
so a taller card makes the whole stack taller: 438 and 472 for the 437 card with a two-line payee on `127`. The two
cards behind always peek out under it, and the hint below moves down with it. While dragging, the card moves with the finger and tilts: about 6 degrees clockwise when dragged right,
anticlockwise when dragged left (the dragging screens show 64 across). With animations off, the card is not moved or
tilted; the next card simply takes its place.

## Commit line (checked 2026-11-20)
- `docs/motion.md` 2.6 describes the stamp at the commit line only as motion: when the card crosses the line the stamp
  pops (scale 1 to 1.08 and back to 1, `motion/spring/pop`) and `haptic/threshold-in` plays; crossing back (8 dp under
  the line) plays `haptic/threshold-out`. The stamp first fades in at 24 dp of drag (alpha 0 to 1 over 100 ms, no scale).
- Once armed, the stamp rests at scale 1, so it looks exactly like `Stamp=Add` or `Stamp=Ignore`. There is no separate
  look to draw, so no variant was added. The pop is described on the Review swipe fling pattern card in `../motion.md`.
