# SumBuddy privacy policy

Last updated: 20 November 2026

SumBuddy is a free, open-source Android app that tracks your monthly spending from the bank and UPI SMS on your
phone, and lets you split shared expenses with friends and family. It is made and run by its maintainer, akhilux
(<https://github.com/akhilux-design>, akhilu.design@gmail.com). This policy says what the app and its server do with your information. The
code that does all of this is public in this repository, so every statement here can be checked.

**In short:** your SMS never leave your phone. Nothing is sent anywhere unless you sign in to split. There are no ads,
no analytics, no tracking, and your data is never sold.

## 1. On your phone

- **SMS.** With your permission, SumBuddy reads the SMS on your phone and the new ones that arrive, to find payments
  and card bills. This happens on the phone only. SMS text and senders are never uploaded, never sent to us or to
  anyone else, and never written to logs. You choose during set-up how far back to read, and you can refuse the SMS
  permission and add payments by hand instead.
- **What the app keeps.** The payments it found, your categories and notes, accounts and cards named in the SMS,
  card bills, and your rules (for example "Swiggy is always Food"). It also keeps the text and sender of each SMS a
  payment came from, so you can see the original message, and of each SMS it could not read, so you can fix it in
  Needs review. All of this is stored in the app's private storage on the phone only.
- **Backups.** Android backup is switched off for SumBuddy, so this data is not copied to Google Drive or another
  phone. Uninstalling the app, or "Delete account and data", deletes it from the phone.
- **Notifications.** If you allow them, the app shows a notification when it finds a payment, and when something
  changes in your groups. The notification text is written on the phone.

## 2. When you sign in (optional)

Sign-in is needed only to split with friends, to share a Family month and to import a group from Splitwise. If you
never sign in, nothing about you is stored on the server.

You sign in with your Google account through Android's Credential Manager. The server checks the token Google gives
and keeps:

- your **Google account id** (a number that identifies the account) and your **name**. Your email address and
  profile picture are never stored;
- the **phone number** and **UPI ID** you choose to add, if any. Your phone number is shown only to people in your
  groups and is never shown on the friends' web page. Your UPI ID is shown to people in your groups and, on the
  friends' web page, to someone who owes you, so they can pay you. It is not checked by us;
- **sessions**: hashed sign-in tokens, so you stay signed in;
- a **push token** for each of your phones (at most three), so the server can tell the phone that a group changed.
  It is stored with an **installation id**, a random id the app makes once when it is installed, so the server knows
  which phone a token belongs to.

## 3. What you share, and with whom

The server stores only what you put in a shared place:

- **Groups and splits:** the group's name, its members, and each expense you split: description, category, amount,
  date, who paid and each person's share; and settlements (who paid whom, how much, and how: UPI, cash, bank transfer or other).
  Everyone in the group sees these. Your own payments that you do not split never leave the phone, and neither do the
  account, card, note, exact time or SMS behind a split.
- **Friends without the app:** when you add a friend who does not use SumBuddy, the name and phone number you type
  are stored and shown to the group. Their phone number is never shown on the web page.
- **Links for friends:** "Remind on WhatsApp" and invites make a link (`https://<link host>/pay/...`). Anyone who has
  the link can open a page with your name, what that friend owes and the expenses behind it, and your UPI ID for Pay
  by UPI. Links expire after 90 days (Family invite links after 7 days) and can be revoked. You send the message from
  your own WhatsApp; SumBuddy does not send it.
- **Family month:** only the payments you mark as Family are shared with your family: description, category, amount,
  date and who paid. Never the note, account, card, SMS or exact time.
- **Import from Splitwise:** the export file you pick is read once on the phone and never saved, logged or uploaded.
  Only the group it holds (names, expenses, amounts, dates and shares) is sent, as for any group you make. Friends'
  phone numbers are not imported.

## 4. Who processes the data

- **Cloudflare** hosts the server (Cloudflare Workers, the D1 database and Durable Objects) and the friends' web page
  (Cloudflare Pages). Their privacy policy: <https://www.cloudflare.com/privacypolicy/>.
- **Google** provides sign-in (Google Identity Services, through Android's Credential Manager) and push messages
  (Firebase Cloud Messaging). Push messages carry only a group id and a version number; the phone then fetches the
  change from our server and writes any notification itself, so no expense passes through Google. When push is on
  (after you sign in), Google Play services on the phone registers a Firebase installation id with Google to get the
  push token. Firebase Analytics is not included in the app, and Firebase's automatic start-up is switched off.
  Google's privacy policy: <https://policies.google.com/privacy>.

Nobody else receives your data. We do not sell or rent it, and we do not use it for advertising.

## 5. Logs

The server logs one line per request: the method, the route pattern (for example `/v1/groups/:gid/expenses`, never
the real ids), the status, an error code and how long it took. It never logs names, phone numbers, UPI IDs, amounts,
descriptions, tokens, request or response bodies, or IP addresses. Cloudflare keeps these lines for a few days.

## 6. How long data is kept

- Your account, and the groups you are in, are kept until you delete them.
- Expired or signed-out sessions are deleted 30 days later; expired or revoked links 30 days after they end.
- A deleted group is removed from the database by a daily job, a little at a time.

## 7. Deleting your data

- **On the phone:** uninstall the app, or use "Delete account and data".
- **On the server:** You > Account > "Delete account and data" deletes your account, your name, phone number, UPI ID,
  sessions and push tokens. Groups that only you use are deleted. In groups shared with others, your expenses stay so
  everyone's balances stay right, and your name becomes "Former member". Your Family payments leave the family.
- **Removing someone else's details:** anyone in a group can edit or remove a friend who does not use the app.
  If you were added to a group without using SumBuddy and want your name or number removed, ask anyone in that group,
  or contact us (below).

## 8. Security

Connections to the server use HTTPS. Sign-in tokens are stored hashed on the server and encrypted on the phone with
a key from the Android Keystore. Share links are random, stored hashed and expire. Security problems can be reported
privately: see [SECURITY.md](SECURITY.md).

## 9. Children

SumBuddy is not directed at children.

## 10. Changes

When this policy changes, the new version is published here with a new date. The history of every change is in this
repository.

## 11. Contact

Email **akhilu.design@gmail.com** for any privacy question or request, such as removing your details. You do not
need a GitHub account or the app. You can also open an issue at <https://github.com/akhilux-design/sumbuddy/issues>;
please do not post SMS text, account numbers or other personal details in a public issue. Security problems can be
reported privately as described in [SECURITY.md](SECURITY.md).
