# Design spec

Everything needed to build the SumBuddy Android app without opening Figma. Exported read-only from the Figma file
(pages Flow, Design System, Screens) with `design/tools/dump.js`. Sizes are Figma px on a 360 x 800 frame (1 px = 1 dp).

## Layout

| Path | Contents |
|---|---|
| `tokens.json` | Figma variables and effect styles: `primitives`, `color`, `size`, `effects` (below). |
| `typography.json` | The 14 local text styles, in Figma order. |
| `components/` | One `<Name>.json` (structure) and `<Name>.png` per component on the Design System page. |
| `screens/<group>/` | One folder per group (`01-onboarding` to `06-family`). Per screen: `NN-name.json`, `NN-name.dark.png`, `NN-name.light.png`. |
| `flow.md` | How the screens connect (from the Flow page). |
| `motion.md` | Motion and haptics: spring, duration-target, distance and haptic tokens, rules and the 18 patterns (from `Section/Motion` on the Design System page). |
| `../icons/` | Icon assets exported from the Design System page (the `Icon/*` components). |

A screen `.json` holds `name`, `group`, `index`, `darkId` and `lightId` (Figma node ids of the two themes) and `tree`,
the dump of the screen frame. Both themes share one tree: colours are token names, resolved per theme in `tokens.json`.

There are 243 screens per theme. File numbers never change: a screen added later gets the next number in its folder,
so the numbers do not follow the order on the Figma board.

## Card bill footers and the notification action, 2026-11-21

Two tidy-ups from the device check of release 0.3.0, on both boards.

- Card bill view footers (`03-getting-paid-back/04`, `22` to `26`): the footer Button (Primary Large, "Remind
  everyone") now hugs its height: 52 face plus 5 edge is 57. So the Footer is 81 high, like `02-everyday/99` and
  `04-you/04`, with the same padding (16, 16, 8, 16) and layout. Before, the Button was fixed at 52, the Footer was 76
  and the button's edge spilled out of it. Now the Footer runs 695 to 776, the button face 711 to 763 and its edge to
  768, which is what the app draws. The Body is 5 shorter (585). In `24` to `26` the Body clips 5 earlier. In `04` the
  last Balance Row ends at 698, so its bottom 3 sit under the Footer. Nothing else moved. `20` and `21` have no
  footer and did not change.
- `02-everyday/146` Notification (card bill): Android draws a notification action as plain text under the body, with
  no fill and no 3D edge. So the first notification's "Remind friends" is no longer a Primary Medium Button. It is a
  text-only action: a frame `Action` (row, 48 high, no fill, label centred in its height) with the label in the
  `Button` text style and `color/text/brand`, like the Ghost button's "Ignore" on Payment detected. It is left aligned
  with the body text (x 32 on the frame). The `Actions` row is 48 high (it was 49), so the first card is 168 high and
  the second card sits 1 higher (y 360 in the Body). Nothing else changed.
- Left as they were: `02-everyday/04`, `53` and `54` still draw their notification actions as Buttons.
- Exported: the JSON and both PNGs of the seven screens. Each JSON was checked by hash against a fresh `dump.js` dump
  of its Dark frame, and the Dark and Light dumps are equal. `flow.md` did not change.
- 243 screens per theme.

## Card bills and See the code 2026-11-21

The design additions of `docs/card-bills.md` 2.12 (Card bill view states, the Home entry, the reminder notification)
and 3 (See the code), on both boards, the Flow page and `flow.md`.

- New: `02-everyday/145` and `146`, `03-getting-paid-back/20` to `26`. Changed in place: `01-onboarding/05`.
- `145` Home (card bill) is `52` with Label "Card bills" (Label, upper, `color/text/secondary`) and a `List card`
  (You's: `color/bg/surface`, radius 16, padding 12, gap 12) under the Balance Card, above Latest. Two Info Rows with
  Icon/card 3D: "HDFC Millennia bill" / "₹24,300 due in 5 days · friends owe ₹8,200" (wraps to two lines, row 64
  high) and "ICICI Amazon Pay bill" / "₹6,480 due in 12 days". 52's third Latest row (Uber) is left out so nothing
  shows cut. This is a new look: show it to the owner before it is built.
- `146` Notification (card bill) is `54` with two notifications in place of its four, each `04`'s card bill
  notification: "SumBuddy · now", "HDFC Millennia bill due in 3 days" / "Friends owe you ₹8,200 of the ₹24,300." and
  an `Actions` row (54's) with Button Primary Medium "Remind friends"; then "SumBuddy · 2h", "ICICI Amazon Pay bill due
  tomorrow" / "Pay ₹6,480 by 17 Nov." with no action. The clock stays Monday, 16 November, as on 54. `04` is unchanged
  (its sample still reads "due in 5 days" with no action).
- Card bill view states, each `04` with:
  - `20` nothing owed: the brand card, then a `List card` with one Info Row (Icon/users 3D) "Nobody owes you for this
    bill" / "Split a payment made with this card and what friends owe of it shows here." No Insight, no Who owes what,
    no footer.
  - `21` signed out: the brand card, then You's `Sign in card` (`04-you/06`: Icon/user 3D, "Sign in with Google" /
    "To split with friends. Your SMS stay on this phone."). No footer.
  - `22` overdue: the card label reads "Bill was due 14 Nov" and the Insight caption "Your own part of it is
    ₹16,100." (one line, so the Insight is 135 high and the rows sit 18 higher). `23` paid: "Bill paid on 19 Nov" and
    "Your own part of it was ₹16,100.". Both keep Remind everyone.
  - `24` reminder sent, `25` no reminder sent, `26` offline: the Group page's banner frame (`02-everyday/87`, `110`,
    `99`) at the top of the body. The body clips, so the last Balance Row runs under the footer, as on the Group
    page.
- Copy-only variants (`docs/card-bills.md` D24), not drawn: no due date (label "Latest bill", caption "Get it back
  before it is due and you only pay your own ₹16,100."), due today and tomorrow ("Bill due today", "Bill due
  tomorrow"), one friend (heading "Rahul owes you ₹4,100 of this"), several groups (row detail "2 groups · 4
  expenses"), and the Problem banner ("Could not do that now" / "Try again in a moment.") in place of `26`'s. Home row
  subtitles: "₹24,300 due today", "due tomorrow", "was due 14 Nov", "₹24,300 to pay" without a date, each followed by
  " · friends owe ₹8,200" when friends owe part. Notification: "due in 2 days", "due tomorrow", "due today"; one
  friend "Rahul owes you ₹4,100 of the ₹24,300."; nobody owes part or signed out "Pay ₹24,300 by 21 Nov." with no
  action.
- `01-onboarding/05` Privacy promise: a fourth Info Row, 16 under "What we keep stays here": Icon/receipt 3D, "Check
  it yourself" / "The code that reads SMS is public." It is not a link. The Note sits 60 lower and nothing reaches the
  footer.
- See the code on You (`04-you/00`, `06`, `09`) and What we've read (`04`) was already drawn; no change.
- Left as it was: `04`'s progress layer is still named "Collected", though its fill is the friends' share of the bill.
- On the board: `20` to `26` sit right after `04` Card bill view, `145` after `52`, `146` after `54`.
- Flow page and `flow.md`: lane 01 Privacy promise mentions the public code; lane 02 Other notifications gains Card bill
  reminder; lane 06 Remind everyone and Automatic reminders have new text, and two new branches, Where it shows (Home
  row) and Other states (seven cards side by side); lane 07 Automatic reminders reads "Before a card due date."
- Exported: the JSON and both PNGs of the ten screens. Each JSON was checked by hash against a fresh `dump.js` dump of
  its Dark frame, and the Dark and Light dumps are equal.
- 243 screens per theme.

## Name: SumBuddy 2026-11-20

The app's final name is SumBuddy (Xpense was the working name). Every visible "Xpense" in the Figma file now reads
SumBuddy, on both boards.

- Screens: 72 text layers per board on 36 screens. Notification headers ("SumBuddy · now", "· 1h", "· 2h", "· 20m",
  "· 1 Nov", "· yesterday"), the person rows' "On SumBuddy", "Not on SumBuddy", "Not on SumBuddy · link sent" and
  "Not on SumBuddy · gets a WhatsApp link", the welcome intro, the Android SMS permission dialog ("Allow SumBuddy to
  send and view SMS messages?"), Allow notifications, Home (manual mode)'s tip "Let SumBuddy spot your payments", the
  You rows and the Plus paywall title ("SumBuddy Plus"), and the friend's web pages ("Get SumBuddy (optional)", "Get
  SumBuddy", "Install SumBuddy on your phone, …", "Home is a family month on SumBuddy: …").
- Link host: the web pages' address bar read the placeholder `xpense.app/pay/k3x9`; it now shows the real host,
  `xpense-pay.pages.dev/pay/k3x9` (still centred in the bar, one line). The WhatsApp message previews already showed
  `xpense-pay.pages.dev` and are unchanged.
- No text gained a line and nothing clips or overlaps: the wrapping texts (Body and Caption) keep their line count, with
  the break in a new place; the buttons stay on one line; the notification headers hug their text. Text is 14 or more.
- Flow page: the header is "SumBuddy: app flow (v3)" (frame "SumBuddy: app flow v3"); `flow.md` follows. Design
  System page: the title is "SumBuddy design system".
- Exported: the JSON and both PNGs of `01-onboarding/00`, `06`, `07`; `02-everyday/02`, `04`, `12`, `25`, `26`, `45`,
  `46`, `48`, `53`, `54`, `55`, `56`, `89`, `92`, `93`, `100`, `103`, `106`, `130`, `131`, `132`, `142`, `143`;
  `03-getting-paid-back/06`, `12`, `13`, `14`, `15`; `04-you/00`, `06`, `09`; `05-plus/00`; `06-family/28`. Each JSON
  was checked by hash against a fresh `dump.js` dump of both frames. `04-you/00` and `06` show the Plus row below the
  fold, so their PNGs did not change.
- Known difference left as it was: on the Light board, `02-everyday/02` Home (manual mode) has Body padding 8 top and
  bottom where the Dark board and the JSON have 12 (the other Home screens use 8). Its Light PNG shows the 8.
- Sample phone number: `02-everyday/27` reads "+91 98765 43210" and `105` "+91 98765 432", the design's own obvious
  placeholder, on both boards (the Input's Value property), in place of a number that looked real. The JSON
  (hash-checked against a fresh dump) and both PNGs of each were re-exported.
- 234 screens per theme.

## Family fixes from the app 2026-11-20

The Family screens are built and live. Where the app and the design differed, the app was right (lead decision
2026-11-20), so the design changed to match it, on both boards.

- `06-family/00`, `09`, `11` and `12`: the "Family name" Input is `State=Default` with "Home" filled in, not focused.
  The screen opens with no field focused; the focused look appears only once it is tapped. The Default field is 59
  high (Focused 60), so everything under it sits 1 higher.
- `02` and `07`: the first Top categories tile reads "Rent and bills" (drawn RENT AND BILLS), the category's own name,
  as `27` already says. It fits on one line in the 158 wide tile, as in the app; the tile's layout is unchanged.
- `08` Sign in (family): the Display/L headline breaks as the app draws it on Android, "Share a family" / "month"
  (Display/L renders about 2% wider there). The text holds a soft line break (U+2028) in place of the space after
  "family", the same device as `02-everyday/140`; the app string has a plain space. The headline is 72 high, so the
  Reason and both Info Rows sit 36 lower.
- New `29` Month view (Family, nothing yet): `27` for a month with no family expense, as `FamilyMonthScreen` draws it:
  Family spent ₹0 and Last month ₹0, the month buttons, By person with both people at ₹0 / "0 expenses", no By
  category and no day list, then `05`'s `Empty state` card (Icon/users 3D, "Nothing in the family month yet", "When
  you add an expense, choose Family under Count it in.", no button) 12 under the People rows. Last month is ₹0 for a
  new family; a later empty month shows the month before's total there.
- New `30` Home (family month, one category): `02` with a single top category. Its Stat Tile keeps the half width (158,
  `size` FIXED) at the start of the row and the space beside it stays empty, as `StatTilePair` lays out one tile. The
  numbers add up: the card reads ₹9,840 and "Arjun and Meera · 14 expenses", Arjun (you) 6 expenses ₹4,320, Meera
  8 expenses ₹5,520, the tile Groceries ₹9,840, and Latest is BigBasket and Blinkit / "Groceries · You paid" / ₹450
  (Icon/cart 3D).
- On the board: `29` and `30` sit right after `27` (Month view (Family)), before Family settings, on both boards.
- Flow page and `flow.md`: Branch Family month (03B) gains two cards beside Nothing yet and Family month list:
  "Nothing in the list yet" and "One category".
- Exported: the JSON and both PNGs of `00`, `02`, `07`, `08`, `09`, `11`, `12`, `29` and `30`. Each JSON was checked
  by hash against a fresh `dump.js` dump of its frame; the Dark and Light dumps are equal. `00` and `02` stay
  pretty-printed, the others compact.
- 234 screens per theme.

## Pressed states for bars, Google and dialogs 2026-11-20

The pressed looks the first pressed-state pass could not draw (`docs/motion.md` 2.1, 2.2, 2.3 and 4). Every screen
still shows the Default look: no screen PNG changed.

- New component sets on the Design System page, each with `State` Default and Pressed (see each `.md`):
  - `Top Bar Back` (`430:569`) and `Top Bar Action` (`430:576`, text property `Label`), in Block/Top Bar. Top Bar
    (`21:66`) now holds one exposed instance of each, named `Back` and `Action`, in place of its frame and its text.
    Its `Action` text property was removed; every Top Bar on the screens kept its action text as the nested `Label`
    (empty where the bar shows no action). Pressed: the flat overlay in the 40 circle, and for the action a full pill
    over its touch area (text plus 12 each side, 48 high).
  - `Bottom Nav Pill` (`431:679`) and `Bottom Nav Icon` (`431:680`), both with `Tab` (Home, Groups, Activity, You), in
    Block/Bottom Nav. Each Bottom Nav variant now holds one Pill and three Icon instances, exposed, with the old layer
    names `Tab/Home` to `Tab/You`. Pressed: the pill gets the 3D press of Button (padding 4 top and 1 bottom, edge
    `DROP_SHADOW(0,1,0,color/brand/edge)`); an icon gets the flat overlay in a 48 circle.
  - `Google Button` (`432:661`, new Block/Google Button). The 10 `Google button` frames (5 screens, both boards) are now
    instances of it with the same name and sizing. Pressed: the flat overlay over the whole pill; logo, label, fill and
    stroke unchanged (Google's branding), checked in both themes.
  - `Dialog Choice` (`432:670`, text property `Label`, new Block/Dialog Choice). The 36 `Choice` frames of the app's
    confirm dialogs (9 screens, both boards) are now instances with the same name, sizing and text. The Android
    permission dialog on `01-onboarding/06` is the system's dialog and keeps its frames. Pressed: the flat overlay on
    the pill, not on the 48 touch box.
  - The flat overlay is the one of the first pass: layer `Pressed overlay`, the element's shape, `color/text/primary`
    at 10% layer opacity (`state/pressed`), radius bound to `radius/full`.
- Swipe Card: no variant. `docs/motion.md` 2.6 gives the commit line only a pop (1 to 1.08 to 1) and a tick; the armed
  stamp then looks like `Stamp=Add` or `Stamp=Ignore`. See `components/Swipe-Card.md`.
- Exported: the JSON and PNG of Top Bar and Bottom Nav (both PNGs are pixel-identical to the old ones) and of the six
  new sets, each with a `.md`. 176 screen JSON files changed, every one holding a Top Bar, Bottom Nav, Google button or
  dialog choice; each was checked by hash against a fresh `dump.js` dump of its frame.
- `02-everyday/05` had not been exported since the chip size change of 2026-11-17: its JSON now has the 36 high chips
  and the rows below them 3 and 6 higher, as the board and its PNGs already showed.
- Still 232 screens per theme.

## Pressed states and fixes 2026-11-20

- Components: a `State` variant property (Default, Pressed) on Button, Balance Card, Expense Row, Balance Row, Stat
  Tile, Chip, Option Card, Segmented Toggle, Setting Row and Info Row. Setting Row (`417:514`) and Info Row (`417:522`)
  became component sets; their Default variants are the old components (`21:56`, `22:32`), so no instance changed.
  - Button Primary and Secondary, Balance Card: the 3D press. Wrapper padding 4 top and 1 bottom, so the Face sits 4
    lower, and its edge is `DROP_SHADOW(0,1,0,<edge colour>)`: a 1 dp lip (`docs/motion.md` 1.4 `motion/distance/press`).
  - Button Ghost: the Face fills with `color/brand/soft` (the plan's `state/pressed-ghost`).
  - The others: a `Pressed overlay` layer on top, the element's own shape, `color/text/primary` at 10% layer opacity
    (the plan's `state/pressed`). Option Card draws it on `Face`, Segmented Toggle on the unselected `Segment`, Info
    Row on its bounds with no radius.
  - `state/pressed` and `state/pressed-ghost` are not Figma variables; the layers use the variables the plan defines
    them as. Every screen instance stays `State=Default`. See each component's `.md`.
  - The owner approved the two new looks, the Ghost pill and the 10% flat overlay, from before and after pictures in
    both themes (2026-11-20).
  - The component JSON and PNG of all ten were exported again. `Chip.json` also picks up the label position (16, 8)
    that changed on 2026-11-17 and had not been exported.
- Every screen JSON that holds one of these components now reads `State=Default` in its `variant` (Setting Row and Info
  Row instances gain `"variant": "State=Default"`), as a fresh dump does. 225 files changed only in that string,
  `components/Swipe-Card.json` among them. No PNG changed.
- `06-family/25`: drawn scrolled the way `09` and `02-everyday/76` are: the From SMS tag is left out and the body aligns
  to the top again, so nothing shows cut. `06-family/10`: Invite partner disabled (opacity 0.4, `Button (disabled)`),
  as 133, 142 and 144 do while the name is blank. Both boards, JSON and both PNGs.
- Flow page and `flow.md`: "Already in a family" moved into the 03B branch "On the partner's phone" (end of the row, no
  arrow: an alternative to joining), as `docs/family.md` says; the separate branch is gone.
- Motion section (`382:220`) and `motion.md`: the Review swipe fling card stays inside its panel; `motion/duration/press`
  is 60 ms, the same as spring/press; the Tab change tokens line no longer breaks `haptic/selection`; the 3D press card
  uses the real `State=Pressed` variant; six new pattern cards (Ghost pressed, Flat pressed overlay, Option radio pop,
  Result arrival, Reveal, Placeholder pulse). 18 patterns in all.
- Still 232 screens per theme.

## Family sharing added 2026-11-20 (part B)

The rest of the design additions of `docs/family.md` (Family settings, Add to expenses, Expense detail, Month view and
the friends' web page), on both boards, the Flow page and `flow.md`.

- New: `06-family/17` to `28`. No frame was changed in place.
- Family settings: `17` is 25's layout with Top Bar "Family", Input "Family name" / "Home", People (Setting Rows with
  Icon/user: Arjun Mehta / You, with the Owner Tag hidden, and Meera Iyer / Partner) and Leaving (one Info Row,
  Icon/logout, "Leave Family"). There is no Add someone row and no Delete row. `18` invite sent: the partner row is
  Icon/user-plus "Your partner" / "Invite sent · not joined yet". `19` no partner: the partner row is Icon/plus "Invite
  partner" / "They join with a link and need the app.". The subtitle wraps to two lines, so that row is 84 high.
- `20` invite sheet: 46's sheet over 18, with "Your partner", one Info Row (Icon/link, "Link is on"), the note and three
  buttons: Send link again, Cancel invite and Close. Cancel invite is a Secondary Large whose Face is on
  `color/bg/on-raised`. The sheet is 455 high and sits on the bottom edge.
- `21` leave and `22` remove partner use 92's dialog, with Icon/logout and Icon/user. Each dialog keeps its bottom at
  772, so 21's dialog is 306 high. `23` offline and `24` could not do that add 100's and 103's banner frames at the top
  of the body.
- `25` Add to expenses (Family every time) is 05 with Family selected under Count it in, the Switch on and "Swiggy is
  always Food · Family". That subtitle wraps to two lines, so the body is drawn scrolled by the height of the From SMS
  tag, which is left out (as on `09` and `02-everyday/76`): the Amount block starts with ₹450 and nothing shows cut.
- `26` Expense detail (delete, Family) is 60 with the scope row Icon/users "Family" / "Counted in November and in the
  family month" and the family delete body. Its dialog is 306 high.
- `27` Month view (Family) uses 18's layout:
  - Top Bar "Home · November 2026"; Stat Tiles "Family spent" ₹42,300 and "Last month" ₹38,100.
  - Label "By person" with 06/02's Balance Rows, then two category rows (Rent and bills, Groceries).
  - Today with 06/02's BigBasket and Swiggy rows. The Swiggy row runs under the gesture bar.
  - Its "By person" and "By category" labels use the `Label` text style. On 18, "By category" has no text style
    (a plain DM Sans Bold 14); 18 itself is unchanged.
- `28` Friend web page (family invite) uses 06's browser bar and footer with 01's heart Sticker tile, Display/L, Body/M
  and one Info Row (Icon/user-plus, "Get the app") in a List card. The footer holds only Get SumBuddy, a Primary Large
  that hugs its height (57). On 06 the Primary is fixed at 52.
- Not drawn, because the spec does not list them: Family settings with a blank name (as on `106`, with the family copy),
  and Family settings as the partner sees it (the partner row cannot be tapped there).
- On the board: `28` sits right after Join Family (already in a family), `27` after Home (family month, offline), then
  `17` to `24`, `25` and `26` close the row.
- Flow page and `flow.md`:
  - New branches: Without the app (Link without the app) and Family settings (Family settings, then Invite sent).
  - If they leave or are removed: Leave Family now comes before Just you.
  - Family month: its cards now sit side by side (Nothing yet, Family month list).
  - If it repeats gains Family chosen for you.
  - Lane 10: the offline card names Family settings.

## Family sharing added 2026-11-20 (part A)

The design additions of `docs/family.md` for Home, the entry points and joining, on both boards, the Flow page and
`flow.md`.

- New: `06-family/03` to `16`. `06-family/00` and `01` are unchanged: they already match the spec.
- Changed in place: `02-everyday/51` (a `Family card` under the Balance Card: 02's `Auto-tracking tip` frame with its
  styling kept, Tag `Tone=Brand` "Family", Heading/M "Share this month with your partner", the Body/M and one Primary
  Medium "Set up Family" that fills the card; the payment card, split tiles and Latest follow), `06-family/02` (Latest
  gets a second row, Swiggy / "Food · You paid" / ₹450 / Family with Icon/food; Meera's avatar is Green by the avatar
  colour rule), `04-you/00` and `04-you/06` (after the Privacy card: Label "Family", then a List card with one Info Row,
  Icon/users, "Family" / "Home · Arjun and Meera" on 00 and "Share a monthly total with your partner" on 06; on 06 the
  card is below the fold, under the bottom bar). These four bodies, and those of `03`, `04`, `06`, `07` and `09` to `12`,
  clip at the bottom bar or footer, because their content runs under it (`09` is scrolled).
- Home (family month): `03` waiting for your partner (the Family card with Tag `Tone=Yellow` "Invite sent" and Send
  link again; "Just you · 12 expenses", one By person row, Groceries and Food, one Latest row "Groceries · You paid");
  `04` just you (03 with Tag `Tone=Brand` "Family", "Invite your partner", Invite partner); `05` nothing yet (₹0, both
  people at ₹0, Top categories hidden, 21's `Empty state` card under Latest without its button); `06` loading (97's
  `Placeholder card` and `Placeholder row` shapes in place of the Balance Card, the By person rows and the Latest rows,
  and two `Placeholder tile` frames, 68 high, in place of the Stat Tiles; only the section labels have text); `07`
  offline (03's `Offline banner` between the greeting and the Balance Card, with the family copy).
- `08` Sign in (family): 49's layout with the family copy (Icon/users "Family month", Icon/lock "Personal stays
  private"). Only Set up Family and the You row lead here; a family link opened signed out keeps `49`.
- Set up Family: `09` earlier expenses (a fifth Info Row, Icon/calendar, "Earlier Family expenses"; drawn scrolled to
  the end, as `76`, so the sticker is above the fold), `10` name blank (Input `State=Error`, empty Value, the helper;
  everything under it sits 26 lower; Invite partner is disabled: opacity 0.4, layer `Button (disabled)`, as on 133, 142 and 144), `11` offline
  and `12` could not do that (101's and 104's banner frames at the top of the body).
- Join Family: `13` earlier expenses (a third Info Row in the List card), `14` offline and `15` could not do that
  (banners above the sticker), `16` already in a family (50 with 00's pink `Sticker tile`, Icon/users, "You're already in
  a family", OK).
- Alternatives drawn once: the You row's subtitle also reads "Home · waiting for your partner" and "Home · just you".
  The Family card keeps the tip card's yellow stroke and edge whatever its Tag tone.
- On the board: `08` sits before Set up Family, `09` to `12` after it, `13` to `16` after Join Family and `03` to `07`
  after Home (family month).
- Flow page and `flow.md`: 03B rewritten. Main path step 2 reads "By a link they open on their phone."; new branches
  Where to start (Home (Family), no family; You: Family), Signed out, Earlier Family expenses, While waiting, Family
  month (Nothing yet) and If they leave or are removed (Just you); On the partner's phone gains Loading the family month
  and, at the end of its row without an arrow, Already in a family. Family settings, Leave Family, Family month list, Link without the app and Family chosen for
  you come with part B. Lane 10: the offline card names Set up Family and Join Family, and the Field errors card names
  Set up Family (blank name).

## Motion added 2026-11-20

- Design System page: new `Section/Motion` (node `382:220`), between Components and Explorations. It holds the
  spring, duration-target, distance and haptic tables, three rule cards (Physics, Remove animations, Haptic rules) and
  one card per pattern with a static diagram (dashed outline = start, solid = where it settles, arrow = the move),
  built from component instances, colour variables and text styles only.
- `motion.md` is written from that section (`dump.js` does not export it). It follows the owner rules of
  `docs/motion.md`: springs everywhere, durations only as spring targets, a selection tick on a tab change.
- No screen frame, component or Flow change came with the Motion section itself; the screen count (232 per theme) comes
  from Family sharing parts A and B.
- Lead decision 2026-11-20: `haptic/press` plays on every 3D button, Done, Cancel and Not now included; the haptic
  table row (`382:408`) and a new bullet on the Haptic rules card (`439:643`) say so, and so does `motion.md`.

## Splitwise import fixes 2026-11-20

Three places where the app was right and the design did not match it (found by the design check of the Splitwise
import build). Changed on both boards.

- New: `02-everyday/144` Import from Splitwise (name blank): `29` with the Group name blank. The Group name Input is
  `State=Error` with an empty Value and the helper "That does not look right." (as on `106`), so everything under it
  sits 27 lower. The Primary is disabled (opacity 0.4, as on `133`) and reads "Import group". On the board it sits
  right after 29, before `133`.
- Changed in place: `142` (Create group is the disabled Primary, opacity 0.4, layer `Button (disabled)`, because the
  Group name is empty) and `140` (the title breaks as the app draws it: "Flat 302 did not" / "come across").
- `140`: Display/L renders about 2% wider on Android than in Figma, so "come" does not fit on the first line there.
  The title holds a soft line break (U+2028) in place of the space after "not". Read it as "breaks here on a 360
  wide screen", not as part of the text: the app string has a plain space.
- Flow page and `flow.md`: the Field errors card (10 When things go wrong) now lists "Import from Splitwise (blank
  group name)" next to "Group settings (blank name)". The card is 30 taller. No new card.

## Button size fix 2026-11-19

- The Secondary Button's 1.5 inside stroke no longer counts towards its size: every Secondary face is now 52 (Large)
  or 44 (Medium) high, 57 or 49 with the edge space, and as wide as a Primary with the same label (it was 3 taller and
  3 wider). See `components/Button.md`. All frames with a visible Secondary button were re-exported.
- Group page footers (`24`, `40`, `41`, `43`, `87`, `88`, `90`, `91`, `99`, `102`, `110`, `114` to `118`): both
  footer buttons hug their height (57), so the footer is 81 high like the other two-button footers.
- `130` and `131`: the bottom sheet is 3 shorter and still sits on the bottom edge.
- The "People" and "Leaving" section labels on the Group settings frames use the `Label` text style.

## Splitwise import added 2026-11-18

The design additions of `docs/splitwise-import.md`, on both boards, the Flow page and `flow.md`.

- New: `02-everyday/133` to `143`.
- Changed in place: `29` (after the File row: the Group name Input `State=Default` "Flat 302", the Label "Which one
  is you?" and a `People` stack, gap 8, of three Option Cards with Icon/user 3D and the person's balance as subtitle,
  Arjun Mehta `Selected=True`; the three Info Rows follow and the body clips under the footer) and `31` (the card is
  `Invite links`: Label "Invite links", Title in `color/text/brand` "Priya and Kiran", Caption "One message with a link
  for each person."; the button reads "Share invite links").
- `133` no name matches yours: no Option Card selected, and Import Flat 302 is the disabled Primary (opacity 0.4, as on
  `04-you/12`). `134` other currencies: a `Result note` under the File row. `135` reading: 28's drop area reads "Reading
  the file" / "flat-302-export.csv", Choose file disabled. `136` cannot import: 30's red drop area. `137` already here:
  a `Status note` (Body/M, `color/text/primary`) under the File row, Open Flat 302 and Choose a different file.
- `138` Importing: no top bar, 09's `Progress block` (fill at 20 of 46) under the Body/M, and only Ghost Medium
  "Open Flat 302" in the footer. `139` and `141` add the `Offline banner` at the top of the body of `138` and `31`.
  `140` Import failed: the Sticker tile in yellow (`color/accent/yellow-soft`, as on `121`) with Icon/alert 3D.
- `142` New group from a split (Choose people > Make a group): Group name empty and focused, the three friends switched
  on, and no "Import from Splitwise instead". `143` Group settings while nobody else is on the app: Priya Nair and
  Kiran Rao "Not on SumBuddy", and Delete group reads "Removes it for everyone. Nobody else has joined it yet." (the
  row can be tapped; its confirm is `93`).
- Alternatives drawn once:
  - `136` shows "This file does not add up". The other refusals use the same drop area (Title, then the red Caption):
    "This group is too big" / "flat-302-export.csv is over 5 MB. Only smaller groups can come across.", "… has more
    than 50 people. Only groups of up to 50 people can come across." and "… has more than 5,000 expenses. Only groups
    with up to 5,000 can come across."; "An expense is too big" / "… has an expense over ₹1 crore, the most one expense
    can be here."; "Nothing to bring across" / "… has no expenses in rupees. Only rupees can come across."; "Nobody else
    is in this file" / "… has only one person. Pick the export of a group you share." (… is the file name). 30 stays
    "That is not a Splitwise export".
  - `139` and `141` show the Offline banner; the Problem banner (same frame, Icon/alert, "Could not do that now" / "Try
    again in a moment.") takes its place for other errors.
  - `31` with one friend: "Invite link" / "Priya" / "One message with the link for Priya." / "Share invite link". Four
    or more friends: "Priya, Kiran and 3 more".
  - `29` with the Group name blank is drawn since 2026-11-20: `144`.
- On the board: `135` sits after 28, `133` and `134` after 29, `136` and `137` after 30, then `138` to `140`, then 31
  and `141`; `142` sits after 26 and `143` after Group settings (friend's link).

## App states added 2026-11-18 (second round)

States the app shows after its second polish round, added to both boards, the Flow page and `flow.md`.

- New: `02-everyday/113` to `132`, `03-getting-paid-back/18` and `19`.
- Changed in place: `02-everyday/21` (the "Coming from Splitwise?" row is gone; the screen ends after the card),
  `42` (Meera's avatar is Yellow, by the avatar colour rule), `91` (the note "Not deleted. The group changed before it
  was sent." now comes with Try again (Secondary Medium) and Dismiss).
- `113` Groups (could not load): 23's card under 39's title-only header, title "Could not load your groups", no "+ New",
  Groups active in the Bottom Nav. On the board it sits right after 23, beside 21 and 22.
- A Not saved row that cannot be tried again has Dismiss only and says why in its note: `114` and `115` (an add),
  `116` and `117` (an edit), `118` (a delete).
- Split. Sorted.: `119` a split with one friend where nobody owes ("Split with Rahul.", no Balance Row, "All of the ₹450
  counts in your month."); `120` paid for one friend alone ("None of the ₹450 counts in your month."); `124` a payee
  known only by its UPI ID in the sentence and the Remembered row. The Remembered line reads "Your next
  rahul.kumar.sharma@" / "okhdfcbank order can split the same way.", as the app draws it (checked on the emulator in
  both themes); the sentence under the title keeps the ID whole on its second line.
- Same split?: `121` the earlier split was not in the month before the payment's own month ("You've split Swiggy with
  Flat 302 before."); `122` a UPI-ID payee.
- `123` Join group with four other members, all named, the sender first.
- `125` Home (all settled): both split tiles at ₹0 use the Stat Tile's `Tone=Neutral` (`color/text/primary`), never green
  or red. `126` is the same Home with a UPI-ID payee in the payment card and a money-in row.
- UPI IDs: a payee known only by its UPI ID breaks only after the "@" (`122`, `124` and `126` to `129`,
  `03-getting-paid-back/18`). Where a frame forces that break, the text holds a soft line break (U+2028) after the "@";
  read it as "may break here", not as part of the ID. A sentence the app does not break this way keeps the ID whole
  (for example the line under "Split. Sorted." on `124`); 124's Remembered line does break after the "@".
- `127` Review payments: the payee is centred line by line (the Swipe Card's `Payee` text is now centre-aligned in
  every stamp, which changes nothing for a one-line name). The card is 24 taller, and so is the whole stack (472, next
  cards 438), so the two cards behind still peek out under it and the hint sits 24 lower, as in the app.
- `129` Add to expenses: the two-line "Do this every time" row runs under the footer, as the app's first view does.
- `130` and `131`: 46's sheet for a friend with no link from this phone. Remind Priya (Primary Large), Turn off link
  (Secondary Large), Cancel. While the friend owes nothing, Remind is the disabled Primary (opacity 0.4, as on
  `04-you/12`) and the note says why.
- `132` Notification (groups, bundled): one card with the app header and a count, then each notification's line. A lone
  notification stays as drawn on `54`. On the board it sits right after 54.
- `03-getting-paid-back/18` Money received with an account known only by its UPI ID: the caption is centred and "· 12 min
  ago" moves whole to the next line. `19` Remind on WhatsApp with the "Link copied" banner (41's banner frame,
  `color/bg/surface`, Icon/check-circle), shown on Android 12 and older.
- Design System page: an "App icon (stand-in)" block (adaptive icon 108 with the 72 visible circle, and masked as a
  circle and a rounded square at 48; background `green/500`, mark `neutral/950`) and the avatar colour rule as a note
  under the Avatar component (also in `components/Avatar.md`).

## App states added 2026-11-18

States and elements the app shows that the design did not draw, added to both boards and to the Flow page.

- New: `01-onboarding/12` to `18`, `02-everyday/51` to `112`, `03-getting-paid-back/08` to `17`, `04-you/09` to `16`.
- Changed in place: `01-onboarding/04`, `05`, `06`, `08`, `11` (copy; "Bank account" instead of "Savings");
  `02-everyday/06` (check on the chosen category), `08` and `47` (ORIGINAL MESSAGE section), `10` and `11` ("Flat 302,
  by amounts"), `12`, `48` and `26` ("Add someone" row), `15` (CATEGORY and DATE after Split this), `18` (no Change
  action; Previous month and Next month buttons; payments by day), `19` (intro copy), `24` and `40` to `43` ("Group
  settings" row at the end); `04-you/01`, `04`, `05` (copy).
- One frame per state family. A banner that several screens show is drawn on each screen family:
  `Offline banner` (`color/accent/yellow-soft`, Icon/wifi-off, "You're offline" / "Check your connection and try
  again. Nothing is lost.") and `Problem banner` (same frame, Icon/alert, "Could not do that now" / "Try again in a
  moment.") at the top of the body on Group page, Group settings, Join group, Your name (after sign-in), Account and
  Remind on WhatsApp. Remind loses its link line in both.
- Field errors use the Input component's `State=Error` with its helper. Add expense's amount is not an Input: its error
  is an `Amount error` row under the amount (Icon/alert Line 20, Caption in `color/status/negative`).
- Notifications: `02-everyday/53` to `56` reuse the lock-screen `Notification` frames of `04`. `54` has one action
  (Confirm).
- Notes after an action (Needs review result, Tracking start date progress or manual note) are the Privacy promise
  `Note` frame with Body/M in `color/text/primary`, named `Result note` or `Status note`.
- `02-everyday/06`: the chosen category's check is an Icon/check Line instance (24, `color/text/brand`), placed at the
  end of the Food row (`Chosen`, absolute in `Categories`); the row's own trailing slot is hidden.
- Long forms scroll. `02-everyday/15` keeps CATEGORY and DATE below the fold (Body clips at the footer); `76` shows the
  same form scrolled to the end. Bodies that run under a footer clip: `15`, `24`, `40` to `43`, `87`, `90`, `91`,
  `99`, `102`, `110`.
- Remind on the Group page: `87` shows "Reminder sent" (Icon/check-circle); `110` shows "No reminder sent" / "They were
  reminded recently. Try again later." with Icon/clock in the same `Reminder banner`.
- Join group: `32` is a group where you owe, `96` a group that is owed, `111` a direct split (headline "Rahul added you
  to a split", the card titled with the friend's name) and `112` nothing owed ("You owe nothing so far", "0 expenses in
  November"). A group with no other member to name reads "Just you" under its name.
- A direct split has no owner: `89` (Group settings, direct split) shows no Owner tag.
- Friend not on the app: `03-getting-paid-back/09` and `10` keep the group tag ("4 expenses · Flat 302", "Flat 302");
  only "Rahul can confirm" goes (Paid another way reads "Mark it as paid.", Mark as paid has no confirm note).
- `04-you/12`: Add older payments is the disabled Primary (as on `13`) while messages are being read.
- Alternatives drawn once: `73` shows "This payment is already split. You can see it in Activity."
  (Icon/split); "This payment is not here any more." uses Icon/search, and "Only payments in rupees can be split."
  Icon/split. `72` shows parts above the total; "Give everyone a part above 0." and "One of the parts does not look
  right." use the same red note. `83` shows "Added ₹1,200 to November."; the others are "Listed ₹1,200 as money in for
  November. Money in never counts as spending." and "₹1,200 is already in your November payments from another message,
  so it was not added again." `57` shows the added case; an ignored payment reads "You said this payment is not an
  expense." with no button. `95` shows the offline wording; otherwise "Try again in a moment."
- The friends' web page (`03-getting-paid-back/12` to `15`) also has states not drawn yet: several people to pay
  ("Who to pay", a Pay button each), all settled (neutral card, "All settled · Flat 302") and a long list ("Showing the
  latest 50 expenses.").

## Splitting screens added 2026-11-17

- New: `02-everyday/40` to `50`, `03-getting-paid-back/07`, `04-you/07` and `08`.
- Changed in place: `01-onboarding/03` (UPI ID field), `02-everyday/25` (the owner is shown), `03-getting-paid-back/05`
  (a link as long as a real one), `04-you/02` and `03` (UPI ID field, delete copy with "Former member").
- Owner label: the owner's Setting Row in Group settings has a Tag (Tone=Brand, "Owner") where Choose people rows have
  the switch. The tree lists it under `nested` as `Tag`.
- Group page banners reuse the Home offline banner frame: `Joined banner` (`color/accent/yellow-soft`, with a
  Secondary Medium "Undo" button) and `Undone banner` (`color/bg/surface`, no button).
- A change the server refused: the row is Expense Row `Kind=Review` with Meta "Not saved", then a note and the
  Try again (Secondary Medium) and Dismiss (Ghost Medium) buttons. When trying again would be refused too, Dismiss
  only (`02-everyday/114` to `118`).
- Confirm dialogs have a `Body` text under the title: style Body/M, `color/text/secondary`, centred.

## tokens.json and typography.json

- `primitives`: raw palette, name to `#RRGGBB` (for example `green/400`). The semantic colours point at these.
- `color`: semantic colours. `dark` / `light` are the final values; `darkRef` / `lightRef` name the primitive each
  theme points at (`null` would mean a raw value). These names (`color/bg/canvas`) are what the trees refer to.
  `color/google/*` are raw values (refs `null`) taken from Google's sign-in branding guidelines for the
  "Continue with Google" button; do not change them. The four-colour G logo in that button is the one place a
  tree has raw `#RRGGBB` fills: it is Google's brand mark, not a theme colour.
- `size`: numbers in dp, `space/*` for gaps and padding, `radius/*` for corners (`radius/full` = 999, a pill).
- `effects`: the `Edge/*` styles, each a hard drop shadow (x 0, y 5, blur 0): the solid edge under buttons and cards.
  `color` is the dark value; take the per-theme colour from the token in `boundVariables.color`.
- Text styles: `lineHeight` is in px; `letterSpacing` is a percent of the font size (em = value / 100); `textCase:
  "UPPER"` means capitals. Fonts: Bricolage Grotesque (Bold, ExtraBold), DM Sans (Regular, Medium, SemiBold, Bold).
- No local paint styles. `UI/Status` (Status Bar clock) is a library style, not in `typography.json`: Archivo SemiBold 15 / 18.

## Keys in the dumped trees

- Layers that are hidden in Figma are left out, and a key appears only when it applies.
- `t` node type (`FRAME`, `TEXT`, `INSTANCE`, ...); `n` layer name; `x`, `y` position inside the parent (whole px);
  `w`, `h` size (to 0.1 px); `op` opacity 0 to 1, only when below 1; `rot` rotation in degrees, only when rotated.
- `lay` auto layout direction, `row` or `col`. Without it, children are placed by their `x` / `y`.
- `gap` space between children; `gapVar` the `size` token bound to that gap (for example `space/32`);
  `pad` padding as `[top, right, bottom, left]`.
- `main`, `cross` alignment along and across the layout direction (`MIN`, `CENTER`, `MAX`, `SPACE_BETWEEN`, `BASELINE`).
- `wrap` true when children wrap onto new lines (`crossGap` is then the space between lines);
  `abs` true when the layer ignores its parent's auto layout and sits at `x` / `y`.
- `size` `[horizontal, vertical]` sizing, each `FIXED`, `HUG` (fit content) or `FILL` (fill parent).
- `r` corner radius, or `[topLeft, topRight, bottomRight, bottomLeft]`; `clip` true when the frame clips its content.
- `fill`, `stroke` lists of visible paints: a colour token name, or `#RRGGBB` when no token is bound. A trailing
  `@NN` is NN percent opacity. Gradients read `GRADIENT_LINEAR(#stop,#stop@NN)`; other paints show their type (`IMAGE`).
- `sw` stroke weight, or `[top, right, bottom, left]`; `dash` the dash pattern.
- `fx` effects, for example `DROP_SHADOW(0,5,0,color/accent/pink-edge)` = (x, y, blur, colour), where the colour is
  a token name or `#RRGGBB@NN` (NN percent alpha). Blurs read `LAYER_BLUR(radius)`.
- `text` the string; `style` its text style name (see `typography.json`); `font` is `Family Style Size` when the text
  has no style; `align` horizontal alignment when not left; `textCase` when not original.
- `segs` for text with more than one colour: the runs, each `{ text, fill }`.
- `comp` the component an instance comes from; `variant` its variant, for example `Style=Primary, Size=Large, State=Default`.
- `props` the instance's other properties (text, on/off, swapped icon), for example `{ "Label": "Not now" }`.
- `texts` visible text layers inside the instance (`n`, `text`, `style` or `font`, `fill`).
- `nested` visible instances inside it (`n`, `w`, `h`, `comp`, `variant`, `props`; the insides of icons are skipped);
  `hidden` names of layers switched off inside this instance.
- `c` child nodes, in layer order (which is also the auto layout order). In screen files instances have no `c`:
  look the component up in `components/`, whose files use `dumpDeep` and do expand instances.
