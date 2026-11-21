# Product notes

Name: **SumBuddy** (sum + buddy), chosen by the owner on 2026-11-20 in place of the working name "Xpense". The name
the app shows lives in one place, the `app_name` string resource; the friends' page build reads it from there too.

The internal names stay as they are, because renaming any of them breaks installed apps or live links: the
application id `com.akhilux.xpense`, the Kotlin package `com.expenses.app`, the link host `xpense-pay.pages.dev`
(`linkHost`), the Worker `xpense-api`, the D1 database `xpense` and the Google Cloud project `xpense-515014`.

## What the app is

An Android-first app for people in India that:

1. **Tracks monthly expenses automatically** by reading bank and UPI SMS on the phone. This is the main feature.
2. **Splits shared payments** with friends and groups. This is the second feature.

Nothing from the SMS inbox leaves the phone. Only expense entries the user chooses to share go to the server.

## Decisions that the design does not show

- A detected payment asks the user to **Add**, **Split** or **Ignore**. It is never added silently.
- When more than one payment is waiting, Home's payment card shows **Review all N**. With exactly one waiting, Home
  is unchanged. Notifications are unchanged.
- **Review payments** shows the waiting payments one card at a time. Only debits waiting for a decision are in it;
  money in never is. Swipe right adds to spending, swipe left ignores.
- The Add and Ignore buttons under the card do the same as a swipe, so swiping is never the only way. TalkBack
  offers Add and Ignore as actions on the card.
- While dragging, the card tilts and shows a stamp: "Add" with a check icon, or "Ignore" with a ban icon.
- The card's category chip opens Choose category. Split on the card opens Add to expenses (splitting needs sign-in;
  see Server below for where Sign in appears).
- Progress reads "3 of 14". Undo brings back the last decision.
- Remove animations (animator duration scale 0) turns all motion off app-wide: the review card is not flung or
  tilted, and the next card simply replaces the last. Haptics follow the system Touch feedback setting.
- **Add all** (top bar) asks first in a sheet: count, total, a breakdown by suggested category, and that each
  payment gets the category shown and can be changed later in Activity.
- When none are left, **All sorted** shows how many were added (with the total) and ignored, with Done back to Home.
  Undo there reverses the last action; after Add all it puts every one of them back to waiting.
- Only the user's **own share** of a split counts in their month.
- **Family** is a pooled group with no owing. A Family expense counts in the payer's month and in the family month.
- The user chooses how far back to read SMS during onboarding: start fresh, this month, last 3 months, or from a date.
- UPI Lite top-ups and loading the user's own wallet are not spending; each payment made from UPI Lite or the wallet is.
- Credit card payment-due reminders are used only for the card's due date and amount, never as spending.
- Only the bank's SMS counts for a payment. Biller and merchant receipts are skipped, and when two messages report
  exactly the same amount close together, the bank's one is kept.
- Reward and loyalty points, coins, miles and store vouchers are not money, even when the SMS prints them in rupees
  ("Rs.200 loyalty points have been credited") (2026-11-18, owner). A real payment lost is worse than points shown as
  money in, which is never counted as spending, so the rule is closed (2026-11-20, lead, after five wider rules each
  lost real money in testing). A message is skipped as points only when all three hold:
  - it is money in (every debit reads as before; a store's points or voucher spend is a waiting debit the user can
    ignore);
  - no bank sent it;
  - its amount is labelled as points right after it: the amount, up to two label words (loyalty, reward, bonus,
    welcome, cashback, club, member, or one brand name such as PAYBACK or Flipkart), a unit (points, coins, miles,
    SuperCoins, NeuCoins and the like, a gift card, a voucher, store credit or a credit note), then "credited",
    "added", "awarded", "earned" or "issued"; or the amount, "credited as" or "credited in", the same labels and a
    unit that ends the phrase ("Rs 100 credited as NeuCoins"; "Rs 2,000 credited as gift card refund to your bank"
    stays money in).
  A message that prints no money amount and only moves points ("500 SuperCoins credited") is skipped instead of going
  to Needs review, from any sender; one that prints money without Rs ("Salary 52,000.00 and 100 bonus points
  credited") still goes to Needs review. Everything else reads as before. Accepted limits: store credits, store wallets
  and points "worth" rupees whose amount is not labelled that way ("Rs 150 credited to your Croma loyalty wallet",
  "400 PAYBACK points worth Rs 100 credited"), and a bank's points message that prints a rupee amount, still show as
  money in. Money in rows already stored are removed once after the update only when no bank sent the SMS, the parser
  now skips it as points, and the user had not touched the row (amount, direction, payee, category, note, split,
  group); debits and Needs review items are never removed.
- Friends do not need the app. They get a web link.
- Splitwise import is a file upload of one group's export, reached from New group and from You. There is no account linking.
- Text and number entry use the system keyboard. The app draws no keypad.
- Minimum text size is 14sp and touch targets are at least 48dp. State is never shown by colour alone.

## Version 1 scope

- Everything in the design is free.
- **Plus is not in version 1.** The Plus paywall, Plus unlocked, the "SumBuddy Plus" row on the You screen and the
  "Plus (paid)" flow branch are drawn in the design but must not be built or shown yet.
- Bank linking through Account Aggregator is later, not version 1.
- Splitwise import is built on the phone (2026-11-20): "Import from Splitwise instead" on New group opened from the
  Groups tab, and You > More. It needs sign-in. Its server route is live since 2026-11-20. Groups (empty) does not offer it, in the app or the design (2026-11-18).

## Server (decided 2026-11-17)

- The app must run at zero cost. The server is only for splitting: sign-in, groups, shared expenses, sync, push
  and the friends' web link. Personal tracking never needs it.
- Cloudflare: Workers (API), D1 (database), Durable Objects (live sync per group), Pages (the web link on a free
  `*.pages.dev` address). Push goes through FCM, using a free Firebase project for messaging only.
- Sign-in is "Continue with Google". There is no phone OTP, because SMS codes always cost money. The design's
  Sign up (phone) and Enter code screens are gone; the phone number is optional and is not verified.
- Onboarding has no sign-in: personal tracking needs no server, so a person can use the app without an account.
  Sign in is asked only when it is first needed: opening Groups (Groups shows "Split with friends" with the
  Google button), tapping Split on a payment (Sign in opens first; Not now leaves the payment waiting), or "Sign in
  with Google" on You. "I already have an account" on Welcome opens the same Sign in screen.
- Sign in shows the reason ("Sign in to split with friends. Your SMS stay on this phone."), the "Continue with
  Google" button drawn to Google's branding guidelines, and Not now. Android's account sheet (Credential Manager)
  may appear by itself; the button stays for phones with no Google account on them.
- After the first sign-in, "Your name (after sign-in)" asks for the name (filled from the Google account) and an
  optional phone number, labelled as not checked and only used to help friends find the person. Saving it also
  sets the name used on Home. The phone number is never sent before sign-in.
- Options and sources: `docs/backend-options.md`.
- Accounts (set up 2026-11-17, all free): Cloudflare (D1 `xpense`, Worker `xpense-api` on
  `akhilu-design.workers.dev`, Pages `xpense-pay.pages.dev`), Google Cloud project `xpense-515014` (sign-in clients,
  no billing), Firebase on the same project (Spark plan, push only). The app id is `com.akhilux.xpense`, because
  `com.expenses.app` is taken on Google Play. Steps and where each value lives: `docs/server-setup.md`.

## Splitting decisions (2026-11-17)

- **UPI ID.** "Your name (after sign-in)" and You > Account have an optional "UPI ID" field (for example
  name@okaxis). Its note says friends in your groups see it on pay links. It is what "Pay by UPI" pays to; a person
  with no UPI ID gets "Ask {name} how to pay" on the friends' page instead of a Pay button.
- **Forwarded links.** Opening a friend's link in the app joins the group in that friend's place at once. For 7 days
  the group owner sees "{name} joined · Not {name}? Undo" on the group; Undo removes the person who joined, puts the
  friend's place back as a friend without the app, and turns that link off. The owner is the person who made the
  group and is shown on the group's Members list.
- **A friend's link.** Any member can send a friend a link, and Turn off link stops every link of that friend, whoever
  sent it. A phone knows only the links it made, so "link sent" and "Link is on" (46) appear only for those. For a
  friend with none, the same sheet says "No link from this phone" (never that the link is off), offers Remind, and
  still offers Turn off link for a link sent from another phone.
- **Delete account.** Groups where the person is the only app user are deleted. In groups shared with other app
  users the person's name becomes "Former member", so everyone else's balances stay right. The Delete account
  screen says this in place of "your groups".
- **Category on a split expense.** Add expense keeps its CATEGORY chips when "Split this" is on, so a split expense
  still gets a category for the month view. Design 15 does not draw them; the owner chose to keep them (2026-11-18).
- **Avatar colours.** The person is always Purple. Other people take Green, Pink, Blue, Orange, Yellow, then Green
  again, in the order they first appear (groups oldest first, members in stored order). Named groups take Green,
  Yellow, Blue, Pink, Orange, then Green again, oldest first. A split with one friend takes that friend's colour.
- **Chip outline.** A chip that is not selected keeps the design's light `color/border/subtle` outline. A darker
  `color/text/tertiary` outline was tried and the owner preferred the design. The selected chip is solid with no
  outline, so the states still differ by shape as well as colour.
- **Buttons on grey cards.** A Secondary button on a grey (`color/bg/surface-raised`) card or sheet, such as Split on
  Home's payment card, Not now on the tip or Turn off link, uses `color/bg/on-raised`: lighter than the card in both
  themes. A button the colour of its card reads as disabled (owner, 2026-11-18).

## Family (decided 2026-11-18)

Spec: `docs/family.md` (rules, server, phone, screens, decisions). Design: `design/spec/screens/06-family/00` to `28`,
`02-everyday/51`, `04-you/00` and `06`; flow lane "03B Family month". Built 2026-11-20 (server, data layer, screens).

- **What it is.** A family is a pooled month for two people with no owing: a server group of kind `family`, free (no
  Plus). A Family expense counts in full in the payer's own month and in the family month; nets are always 0, so
  settle up, reminders and balances never apply.
- **Who.** 2 people in version 1 (the invite counts as the second), one family per person, both enforced by the
  server. The owner (who set it up) removes the partner; anyone cancels an invite or leaves. There is no Delete
  family: the server ends a family when its last member on the app leaves.
- **What is shared.** Only the payer's own payments marked Family, added, in rupees and not split: payee (with the
  UPI-ID rule of splits), category, amount, India date and who paid. Never the note, account, card, "paid with", SMS
  text or sender, exact time, or a Just me expense. Only the payer edits or deletes a family expense; the partner's
  rows on Home and Month view (Family) are not tappable. A payment cannot be both split and Family.
- **Earlier Family expenses.** Those already tagged Family on the phone are shared when it sets up or joins a family;
  Set up Family and Join Family say how many first ("Your 12 Family expenses on this phone are shared too."), which is
  the consent. After leaving, being removed or deleting the account, that person's expenses leave the family month
  and keep their Family tag on their phone.
- **Invite.** Set up Family's "Invite partner" makes a link (`https://<LINK_HOST>/pay/<token>`, the friends' link
  mechanism) and opens the system share sheet, not WhatsApp only. Family links live 7 days. No lookup by phone
  number. The partner needs the app; the web page says who invited them and to get the app.
- **Where it shows.** Home's Me / Family toggle (the side is app-scoped and resets to Me in a new process): without a
  family, this phone's Family rows and a Set up Family card; with one, the family month (By person, Top categories,
  Latest) and the Balance Card opens Month view (Family). The You tab has a FAMILY row. Activity's Family filter is
  unchanged (this phone's own rows).
- **Online only.** Set up, join, invite, cancel invite, remove and leave need the network (Offline or Problem banner);
  renaming and Family expenses are queued and work offline.
- **Notifications.** Only "{name} joined {family}" (it opens Home on the Family side). Family expenses sync in the
  background with no notification.
- **"Do this every time"** remembers Family too ("Swiggy is always Food · Family"): the next payment to that merchant
  waits with Family chosen; it is still never added without a tap.
- **Sign in.** Set up Family and Family settings ask to sign in first with the family copy ("Share a family month"); a
  family invite link opened signed out uses the Join group copy, since its kind is known only after sign-in.

## Card bills (decided 2026-11-21)

Spec: `docs/card-bills.md`. Design: `03-getting-paid-back/04` and `20` to `26`, `02-everyday/145` and `146`.

- **Reminders go to the person only.** The automatic reminder is a notification on the phone at 09:00 three days
  before a card bill is due (never between 21:00 and 09:00). Friends get something only when the person taps Remind
  everyone, or the notification's Remind friends (D1).
- **What friends owe of a bill** is, per friend and group, the smaller of their shares of the bill's split card
  payments and what they owe the person in that group now, because repayments are not tied to expenses (D9).
- **Paid** is read from the card's own "payment received" SMS after the statement: a bill is paid once they add up
  to it. A minimum-due payment does not mark it paid (D13).
- **Manual mode** shows no card bills on Home and sends no reminders (D16).
- **Where it shows:** a CARD BILLS card on Home (Me side), one row per card's latest bill, until it is paid. The
  design draws no in-app entry; Home is the only one (D17).
- Card bills, the reminder and its text stay on the phone; the server gets nothing new.

## Distribution and privacy (2026-11-20)

- SumBuddy is not on Google Play. It is open source (GPL-3.0-or-later) and ships as a signed APK on GitHub Releases
  (`akhilux-design/sumbuddy`), which Obtainium and Orion Store read. F-Droid's main repository comes later, with a
  build without Google sign-in and Firebase, which it does not allow. IzzyOnDroid is not a target: its policy rejects
  apps made with generative AI. Steps: `docs/publishing.md`, `docs/releasing.md`.
- The privacy policy is `PRIVACY.md` at the repository root. It is also the policy URL Google's sign-in branding
  needs (`docs/server-setup.md` 7.2).

## Privacy gaps to close after publishing

- Done 2026-11-21: "See the code" is back (You > Privacy and What we've read open the public repository in the
  browser; the Privacy promise says "The code that reads SMS is public."). The address is written once, in the
  `source_code_url` string resource (docs/card-bills.md 3).
- The app does not link the privacy policy yet. A "Privacy policy" row in You > Privacy and a link on the Privacy
  promise need the design and the app changed together.

## Open questions

- What happens to personal expenses when the user moves to a new phone.
- How refunds are treated.
- Plus features and price.

## Design source

- Figma file (private; `design/spec` is the exported copy anyone can read):
  https://www.figma.com/design/eb0tjEItIpuHkVkKloxqgC/Xpense-Track---Research---Design
- Pages: Flow, Design System, Screens (a Dark board and a Light board, 234 screens each). The Splitwise import states
  are `02-everyday/133` to `144` (2026-11-18 and 20); Family sharing is `06-family/00` to `30` (2026-11-20). The Design
  System page has a Motion section (springs, haptics, patterns), exported as `design/spec/motion.md`.
- An exported copy of the design (tokens, components, screens, icons) lives in `design/`.
