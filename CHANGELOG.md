# Changelog

## 0.3.2: notifications with the app's ₹, and smoother motion

**Notifications**

- Every notification now shows the app's ₹ as its small icon, tinted in the app's green, instead of a different line
  icon for each kind. On Android 12 and later Android draws it in a circle and picks the exact shade of green.
- On Android 11 and older, the quiet "Reading SMS" notification now shows once when an SMS arrives, not again each
  time the app tries again to find that SMS in the inbox.

**Motion**

- A back swipe now settles with a spring from your finger's speed after you let go, instead of gliding at a fixed
  speed and stopping dead.
- Review payments, a group page and joining a group no longer show a near-blank screen for a moment when their data
  comes quickly: the content shows at once.
- With Remove animations on, loading placeholders no longer flash for a moment: they still wait 200 ms before they
  show, and then stay still.

## 0.3.1: an SMS right after another is read within seconds

**Fixes**

- An SMS that arrived seconds after another one (often a bank SMS and a UPI SMS for one payment) could be missed
  until the next SMS came or the app next started: no "Payment detected", and Home and card bills did not show it.
  It is now read, usually within seconds of arriving.
- A bank SMS whose sender comes without the hyphen (VMHDFCBK rather than VM-HDFCBK) is now known by its bank, so a
  UPI payment whose text names no bank goes on the right account. Such payments read before this update stay on the
  account with no bank name.
- Two payments of the same amount from such a sender within 15 minutes are no longer counted as one.

## 0.3.0: card bills and See the code

**Card bills**

- A card bill screen for each credit card: the bill, when it is due, how much of it friends owe you for payments
  you split with that card, and who owes what. Your own part is what is left.
- Home lists each card's latest bill under CARD BILLS until it is paid.
- Remind everyone: friends on the app get a reminder; WhatsApp opens for the friend without the app who owes the most.
- Automatic reminders now work: a notification 3 days before a card bill is due, at 9 in the morning. It goes to you
  only; "Remind friends" on it opens the card bill and reminds them. Switch it off in You.
- A bill counts as paid once the card's payment SMS add up to it.

**See the code**

- You and What we've read link to the app's public code; the privacy promise says the code that reads SMS is public.

## 0.2.0: first public release

SumBuddy (formerly the working name "Xpense") is now open source under the GPL-3.0-or-later and published on GitHub
Releases.

**Track your month from SMS**

- Reads bank, card, wallet and UPI SMS on the phone and finds payments; SMS never leave the phone.
- Each payment found asks to Add, Split or Ignore; nothing is added silently. Review payments goes through several
  at once, one card at a time, with Add all and Undo.
- Choose how far back to read when you start: start fresh, this month, the last 3 months or from a date. Or use the
  app without SMS access and add payments by hand.
- Home shows the month's spending, what friends owe you and what you owe; Month shows spending by category against
  last month; Activity lists everything.
- Card statement SMS are read for each card's due date and amount, never as spending (a card bill screen comes later).
- Skips OTPs, offers, and reward or loyalty points that are not money.
- "Do this every time" rules for merchants you always categorise or split the same way.

**Split with friends** (optional sign-in with Google)

- Groups and one-to-one splits: equally, by amounts, by percentages or by shares.
- Settle up by UPI, cash or bank transfer; the other person confirms.
- Remind on WhatsApp with a link: friends without the app see what they owe and can pay by UPI.
- Works offline: splits wait and sync when there is a network.

**Family month**

- Share one monthly total with your partner, with no owing between you; only the payments you mark as Family are
  shared.

**Import from Splitwise**

- Bring a group across from a Splitwise export file, read on the phone.

**Also**

- Light and dark themes, motion that follows the system's Remove animations setting, TalkBack actions on review
  cards.
- Fonts are bundled in the app, so text looks right at once and on phones without Google Play services.

## 0.1.0

Private test build, not published.
