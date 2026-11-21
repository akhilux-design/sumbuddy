# Card bills and See the code

Spec for two features, written 2026-11-21. A) the Card bill view, its Home entry and the automatic reminder; B) "See
the code" now that the repository is public. Sources: design `03-getting-paid-back/04`, `02-everyday/04`,
`04-you/00`, `04`, `06`, `09`, `01-onboarding/05` (and its first export, commit `cb077ce`); `design/spec/flow.md`
lanes 02, 06 and 07; `docs/server.md` 4.11, 6.4, 9.4, 12, 13.15; the first chat (15 Nov "credit card angle", 17 Nov
card due reminders); `parser/.../CardBills.kt`, `CardBillRepository`, `CardBillDao`, `SettingsRepository`.

## 1. Decisions

| # | Decision | Reason |
|---|---|---|
| D1 | The automatic reminder only ever notifies the person. Friends get something only when the person taps Remind everyone or the notification's Remind friends. | Lead decision 2026-11-21. Closes `docs/server.md` 13.15. |
| D2 | N = 3 days, one constant `CardBillReminders.DAYS_BEFORE = 3`; `YouViewModel.REMINDER_DAYS_BEFORE` points at it. Not user-editable. | You (`04-you/00`, `06`, `09`) draws "3 days before a card bill is due" on a switch row with no picker. |
| D3 | One reminder per bill, at 09:00 India time on due date − 3. Quiet hours 21:00 to 09:00. | Banks send their own reminders in the morning; a money nudge at night is unwelcome. |
| D4 | Scheduling uses WorkManager one-time work, no exact alarms. | WorkManager is already used, survives reboot by itself, and exact alarms need a special permission. A reminder a few minutes late is fine. |
| D5 | Card bills, the reminder and its text stay on the phone. The server gets nothing new. | `docs/server.md` 12; README privacy table ("card bills ... Never"). |
| D6 | The bill's cycle is estimated: statement date = the earlier of the bill's first SMS day and due date − 15 days; the cycle is the month that ends on it. | No SMS prints the statement period. A statement SMS arrives on the statement day; a due reminder alone needs the 15-day bound (Indian issuers give 15 to 25 days). |
| D7 | `CardBillRepository.upsert` keeps the first SMS's `receivedAt` and `smsId`, and `remindedAt`, when another SMS of the same bill replaces it. Amounts and account come from the newer SMS. This holds in either order: "add older payments" stores older SMS after newer ones. | D6 needs when the bill first arrived; a reminder SMS must not reset the reminder. |
| D8 | New column `card_bills.remindedAt: Instant?`, database version 7, `AutoMigration(6 → 7)`. | "Already reminded" belongs to the bill and goes with it (delete, cascade). |
| D9 | "Friends owe you X of it" per friend per group = min(their shares of this cycle's split card payments, what they owe the person in that group now). | Settlements are not tied to expenses (`docs/server.md` 6.2). Treating repayments as clearing the oldest debts first is what people expect, and min never shows more than is owed now. |
| D10 | The pair amount is the Group page's (`pairBalance(myMemberId, memberId)`, the simplified transfer). Removed members and families never count. | Same number Remind and Settle up use; the server answers `nothing_owed` for a removed member. |
| D11 | The heading shows the true sum; only the bar (at most full) and "your own" (at least ₹0) are bounded. | The cycle is an estimate and can reach a little into the next bill; no shown number is made up. |
| D12 | The Insight bar is friends' part ÷ bill total, not money collected. | The drawn fill (100 of 293) is 8,200 ÷ 24,300; the bar and the amount share the yellow. |
| D13 | Paid = credits on the card after the statement date, up to due date + 7 days, add up to the bill. A smaller sum (minimum due) changes nothing. | The issuer's "Payment of Rs … has been received on your … Card" is read as a CREDIT on the card (parser sample `ct-hdfc-cardmember-payment-received`). Strictly after the statement day, so a payment of the previous bill never marks this one paid. |
| D14 | Only the latest bill of a card is current (latest due date; without due dates, latest first SMS; a bill with no due date replaces a dated one only when its SMS arrived after that due date, so a bank's undated reminder inside the cycle does not). Older ones are "replaced": no Home row, no reminder. | A new statement means the old bill is history. |
| D15 | Bills of a card the person switched off, and bills that name neither bank nor card (`accountId` null), are never shown or reminded. | The app's rule for switched-off accounts; with no card, nothing can be matched or marked paid. |
| D16 | Manual mode: no Home rows and no reminders. The switch keeps its value. | Without SMS the app cannot see a bill paid or the next bill. |
| D17 | Entry point: a "Card bills" list card on Home (Me side) above Latest, one row per card. No entry on You or Accounts and cards. | The design draws only the notification. Home is where due things are; on Accounts and cards a tap renames (`04-you/10`). |
| D18 | Home shows an unpaid bill from its arrival until paid, or until 7 days past the due date (no due date: 30 days after the statement date). | A late fee is worth a week of nagging; after that the payment SMS or the next statement has arrived. |
| D19 | Remind everyone: a push to every friend on the app who owes part (one `POST /reminders` per group); then Remind on WhatsApp for the friend without the app who owes the most. Other friends without the app are reached from their row. | Android cannot send several WhatsApp messages for the person; the push route and the WhatsApp screen already exist (Group page does the same per group). |
| D20 | A friend row opens the Group page of the group where their part of this bill is largest. | Settle up and Remind for that friend live there; no new screen. |
| D21 | The notification's "Remind friends" opens the Card bill view with `remind = true`, which runs Remind everyone once. Shown only when friends owe part. | It is the person's tap, so D1 holds; the view shows the result banner and the WhatsApp step. |
| D22 | Minimum due is never shown. | The design shows only the total, and "you only pay your own" is about the total. |
| D23 | A bill that is gone (deleted, switched off, no card) opened from an old notification leaves at once, to Home. | Same as Remind on WhatsApp's `Gone`; nothing useful to show. |
| D24 | Copy-only variants (due today, tomorrow, no due date, one friend, several groups, "Could not do that now") get no frame; they are listed in `design/spec/README.md`. Every variant that changes which elements show gets a frame. | Same layout, text only; the app already keeps such strings marked "Not in the design". |
| D25 | "See the code" opens `https://github.com/akhilux-design/sumbuddy` (repository root) in the browser with `ACTION_VIEW`. The URL lives only in `strings.xml` as `source_code_url` (`translatable="false"`). | `docs/product.md` names this link and a string resource; a deep path into `parser/` would break if folders move, and the README points to `parser/`. |
| D26 | The Privacy promise gets its fourth row back, "Check it yourself" / "The code that reads SMS is public." with `Icon/receipt` 3D. It is not a link. | The first design had it (commit `cb077ce`); `Icon/eye` is now taken by "What we keep stays here"; `Icon/receipt` is You's See the code icon. Leaving onboarding for a browser mid-flow is not wanted; the link is on You. |

## 2. Card bills

### 2.1 Data

Exists: `CardBill` (`card_bills`: `id`, `accountId?` FK cascade, `bank?`, `cardLast4?`, `totalDueMinor` (always
known: the parser returns no bill without an amount), `minimumDueMinor?`, `dueDate?`, `receivedAt`, `smsId?`).
`SmsIngestPipeline.storeCardBill` makes a `CREDIT_CARD` account for it (`accountFor`), so `accountId` is null only
when the SMS names neither bank nor card. Card reminder SMS are never spending (`docs/product.md`).

Changes:

- `CardBill.remindedAt: Instant?` (D8). Schema `app/schemas/.../7.json`, `AutoMigration(from = 6, to = 7)`.
- `CardBillRepository.upsert`: when it replaces a bill, copy `receivedAt`, `smsId` and `remindedAt` from the stored
  one (D7).
- `CardBillDao`: `get(id)`, `observe(id): Flow<CardBill?>`, `all(): List<CardBill>`, `setRemindedAt(id, at)`.
- `ExpenseDao` / `ExpenseRepository`: `observeOnAccount(accountId, from: Instant, to: Instant): Flow<List<Expense>>`
  (every direction and status; the rules below filter).
- `GroupsRepository.observeOwedOn(groupExpenseIds: Set<String>): Flow<List<OwedPart>>` from its snapshot: for each
  of those group expenses that is not deleted, not in a family, and paid by the person's member, one `OwedPart` per
  (group, other member with a share > 0): `groupId`, `groupName` (null for a direct split), `memberId`, `personKey`
  (`personKeyOf`), `name`, `onApp` (`onApp && userId != null`), `removed`, `avatar` (`avatars.ofPerson`),
  `shareMinor` (sum of their shares), `expenseCount`, `pairMinor` (`pairBalance(myMemberId, memberId)`, positive =
  they owe the person). Signed out the `grp_*` tables are empty, so the list is empty.

### 2.2 Rules (`com.expenses.app.cardbill.CardBillMath`, pure Kotlin)

All dates are India dates (`AppContainer.ZONE`).

- **Statement date** `S` = min(date of `receivedAt`, `dueDate − 15 days`); without a due date, the date of
  `receivedAt` (D6).
- **Cycle**: dates after `S − 1 month` up to and including `S`.
- **Card payments of the cycle**: expenses with `accountId` = the bill's, `DEBIT`, `INR`, `ADDED`, a
  `groupExpenseId`, and an `occurredAt` date in the cycle. Their `groupExpenseId`s go to `observeOwedOn`.
- **Owed per part**: `min(shareMinor, max(pairMinor, 0))`, 0 for `removed` (D9, D10). Parts at 0 are dropped.
- **Friend rows**: parts grouped by `personKey`. Amount = sum; expenses = sum of `expenseCount`; groups = distinct
  `groupId`s; target group = the one with the largest owed (ties: lower group id). Sorted by amount, largest first,
  then name.
- **Friends' part** `F` = sum of the rows. **Own** = max(`totalDueMinor − F`, 0). **Bar** = min(`F / total`, 1)
  (D11, D12).
- **Paid**: credits (`CREDIT`, `INR`, any status) on the bill's account with a date after `S` and up to
  `dueDate + 7 days` (no due date: `S + 37 days`), in time order; paid on the date the running sum reaches
  `totalDueMinor` (D13).
- **State** (today `T`): `PAID(on)` if paid; else no due date → `NO_DATE`; `dueDate − T` = 0 → `DUE_TODAY`, 1 →
  `DUE_TOMORROW`, ≥ 2 → `DUE_IN(days)`, < 0 → `OVERDUE`.
- **Current bill** of a card: latest due date, then latest `receivedAt` (D14). A bill without a due date ranks by
  the date of its `receivedAt`, below a dated bill due that same day: it replaces a dated bill only when it arrived
  after that bill's due date.
- **On Home** (D15 to D18): current, `accountId` not null, account tracked, SMS mode, Me side; state not `PAID`; `OVERDUE` only until `dueDate + 7 days`; `NO_DATE` only until `S + 30 days`.
  Sorted by due date (no date last), then card name.
- **Reminder time** `fireAt(dueDate, now)` (D3), `target` = `dueDate − 3 days` at 09:00:
  - now's date after `dueDate` → none;
  - now before `target` → `target`;
  - else now between 09:00 and 21:00 → now;
  - else before 09:00 → 09:00 today; after 21:00 → 09:00 tomorrow, or none if tomorrow is after `dueDate`.

### 2.3 Card bill view (design `03-getting-paid-back/04`)

Route `CardBillView(billId: Long, remind: Boolean = false)`. `PageFrame`: Top Bar "Card bill" with Back, scrolling
Body (padding 8/16, gap 12), Footer.

| Element (design) | Shows | Source |
|---|---|---|
| Card, `color/brand/primary`, radius 24, 5 edge (`color/brand/edge`), blob at 35% | — | one-off frame, built like `BalanceCard`'s edge |
| "HDFC Millennia" (Title, on-brand) | card name | account `displayName ?: bank ?: "Credit card"` (`onboarding_account_credit_card`) |
| "•• 2217" (Title, on-brand) | `•• ` + last digits | account `last4` (bill `cardLast4` if none); hidden when unknown |
| "BILL DUE IN 5 DAYS" (Label, upper) | state line | 2.2 State, copy 2.8 |
| "₹24,300" (Amount/L) | bill total | `totalDueMinor`, `formatRupees` |
| Insight: Heading/M, amount in `color/text/warning` | "Friends owe you ₹8,200 of this" | `F` |
| Progress 10dp, track `surface-raised`, fill `accent/yellow` | `F / total` | 2.2 Bar; `ProgressBar` gains a `fillColor` parameter and a `Long` overload |
| Caption (`text/secondary`) | "Get it back before 21 Nov and you only pay your own ₹16,100." | `dueDate` ("d MMM"), Own |
| "WHO OWES WHAT" (Label) | — | |
| Balance Row, `Owes you` | "Rahul", "Goa trip · 3 expenses", "+₹4,100", avatar | friend rows (2.2); detail = group name or for a direct split none, then the count; several groups: "2 groups · 4 expenses" |
| Footer: Button Primary Large | "Remind everyone" | 2.6 |

Insight card: `color/bg/surface`, stroke `color/accent/yellow` 1.5, radius 16, padding 16 inside the stroke (the
frame counts the stroke in its layout, so the content starts 17.5 from the edge), gap 12. Rows: tap opens
`GroupPage(targetGroupId)` (D20). The brand card is one accessibility node ("HDFC Millennia, card ending 2217. Bill
due in 5 days. ₹24,300."); the bar has progress semantics and the caption says the same in words.

States (the design draws only the first):

| State | Body | Footer | Frame |
|---|---|---|---|
| Friends owe part (signed in, `F > 0`) | as drawn | Remind everyone | `03/04` |
| Nobody owes part (signed in, `F = 0`) | card, then a List card with one Info Row (`Icon/users` 3D): "Nobody owes you for this bill" / "Split a payment made with this card and what friends owe of it shows here." | none | `03/20` new |
| Signed out | card, then You's sign-in card (`04-you/06`: `Icon/user` 3D, "Sign in with Google", "To split with friends. Your SMS stay on this phone."); tap → `SignInGate`, back to this view | none | `03/21` new |
| Overdue | label "BILL WAS DUE 14 NOV"; caption "Your own part of it is ₹16,100." | as above | `03/22` new |
| Paid | label "BILL PAID ON 19 NOV"; caption "Your own part of it was ₹16,100." | Remind everyone while `F > 0` | `03/23` new |
| Remind result | Banner at the top of the body, the Group page's: Reminder sent (`02/87`), No reminder sent (`02/110`), You're offline, Could not do that now | Remind everyone, disabled while running | `03/24`, `25`, `26` new |
| No due date, due today, tomorrow, one friend, several groups | text only (2.8) | as above | none (D24) |
| Gone (deleted, switched off, no card) | leaves to Home (D23) | — | none |

Several cards: one Home row and one view per card. Manual mode: the view still opens from an old notification and
shows what is stored. There is no loading state: the data is local; the canvas shows until the first emission.

### 2.4 Home row

On the Me side, between Split totals and the "Latest" header: Label "CARD BILLS" (Label, upper, `text/secondary`),
then one List card (`surface`, radius 16, padding 12, gap 12) with one Info Row per bill on Home (2.2), `Icon/card`
3D, tap → `CardBillView(billId)`. Hidden when there is none, in manual mode and on the Family side.

- Title: "HDFC Millennia bill".
- Subtitle: "₹24,300 due in 5 days" (or "due today", "due tomorrow", "was due 14 Nov", "₹24,300 to pay" without a
  date), then " · friends owe ₹8,200" when `F > 0`.

### 2.5 Navigation

- `Routes.kt`: `@Serializable data class CardBillView(val billId: Long, val remind: Boolean = false)`, registered by
  `ui/screens/cardbill/CardBillNav.kt`.
- `AppLink.CardBill(billId: Long, remind: Boolean = false)` → `CardBillView(billId, remind)`; opened by `openLink`
  above Home, so Back goes to Home. Handling it cancels that bill's notification.
- `remind = true` runs Remind everyone once; a `SavedStateHandle` flag stops it running again after rotation or
  process death.
- Back on the Top Bar and the system back: previous screen (Home).
- Out of the view: Group page (row), Remind on WhatsApp (`RemindOnWhatsApp(groupId, memberId)`), Sign in.

### 2.6 Remind everyone (D19)

1. Targets = friend parts with owed > 0 (2.2). Busy guard as on the Group page.
2. If any target is on the app: offline → banner "You're offline", stop. Else one `groups.remind(groupId, memberIds)`
   per group with such targets. Sent names (short names) → "Reminder sent" / "Sent to Rahul and Priya."; none sent
   → "No reminder sent"; `needsSignIn` → Sign in; other errors → "Could not do that now".
3. Then, if any target is not on the app: open Remind on WhatsApp for the one who owes the most (ties: name) in their
   target group. The banner from step 2 is there on return.
4. Haptics as the Group page: confirm on Reminded, reject on the others.

The push carries only group and member ids (`docs/server.md` 4.11). Nothing about the card leaves the phone.
`RL_WRITE` (30 a minute) is far above one call per group.

### 2.7 Automatic reminder (D1 to D4)

**Pieces** (`com.expenses.app.cardbill`):

- `CardBillReminders.sync()`: reads every bill and enqueues, for each one that is due a reminder, unique work
  `card_bill_reminder_<billId>` (tag `card_bill_reminder`, `ExistingWorkPolicy.REPLACE`, initial delay
  `fireAt − now`, input `billId`); cancels the work of every other bill and the posted notification of bills now
  paid, replaced, deleted or switched off. A bill's work that is running is neither cancelled nor replaced (REPLACE
  would cancel it between its post and `remindedAt`, and the new work would post again). "Due a reminder": current,
  account tracked, `accountId` not null, due date known, `remindedAt` null, `remindersOn`, SMS mode, state not
  `PAID`, `fireAt` not none.
- `CardBillReminderWorker` (`CoroutineWorker`): re-checks everything above at run time (the source of truth); if
  `fireAt(now)` is more than a minute away (late run into quiet hours), re-enqueues itself for it; if notifications
  are off (`areNotificationsEnabled` false), does nothing; else posts and sets `remindedAt = now`.
- `CardBillNotifications`: channel, post, cancel.

**`sync()` is called**: after each `ingest` whose result has `cardBills` or `credits` (`IncomingSmsWorker`,
`ScanWorker`); after `setRemindersOn`, `setTrackingMode`, `setTracked` and any bill deletion (`deleteBefore`); once
in `ExpensesApplication.onCreate` (heals after an update or a missed run). Group changes need no sync: the text is
made at run time.

**Reboot**: WorkManager keeps the work and re-schedules it after a reboot itself (its own boot receiver comes with
the library). The start-up `sync()` covers the rest.

**A bill arriving inside the window**: `fireAt` gives "now" (daytime) or the next 09:00 still on or before the due
date, so a bill read 2 days before its due date is reminded at once with "due in 2 days". Past due on arrival: no
reminder. Bills found by the onboarding scan follow the same rule.

**Channel**: id `card_bills`, name "Card bills", description "A credit card bill is due in a few days.",
`IMPORTANCE_DEFAULT`; created with the others (`PaymentNotifications.createChannel` pattern). Tag `card_bill`, id
`billId.hashCode()`, same small icon and visibility as Payment detected, `setAutoCancel(true)`.

**Text** (design `02-everyday/04`, third notification):

- Title: "HDFC Millennia bill due in 3 days" ("due in 2 days", "due tomorrow", "due today").
- Body, friends owe part: "Friends owe you ₹8,200 of the ₹24,300." (one friend: "Rahul owes you ₹4,100 of the
  ₹24,300.").
- Body, nobody owes part or signed out: "Pay ₹24,300 by 21 Nov."
- Tap → `AppLink.CardBill(billId)`. Action, only when `F > 0`: "Remind friends" → `AppLink.CardBill(billId, remind =
  true)`. Both through `MainActivity`'s non-exported `NotificationTarget` alias, like the other notifications.

**Cancelled** when the bill is paid, replaced by a newer bill of the card, deleted, its card switched off, reminders
switched off, or manual mode: the scheduled work always; the posted notification for paid, replaced, deleted and
switched off. Switching reminders off leaves an already posted notification alone.

### 2.8 Copy (`strings_cardbill.xml`, new)

| Name | Text |
|---|---|
| `cardbill_title` | Card bill |
| `cardbill_digits` | •• %1$s |
| `cardbill_due_in` (plural) | Bill due in %d day / Bill due in %d days |
| `cardbill_due_today` / `_tomorrow` | Bill due today / Bill due tomorrow |
| `cardbill_was_due` | Bill was due %1$s |
| `cardbill_paid_on` | Bill paid on %1$s |
| `cardbill_no_date` | Latest bill |
| `cardbill_friends_owe` | Friends owe you %1$s of this |
| `cardbill_friend_owes` | %1$s owes you %2$s of this |
| `cardbill_get_back_by` | Get it back before %1$s and you only pay your own %2$s. |
| `cardbill_get_back_no_date` | Get it back before it is due and you only pay your own %1$s. |
| `cardbill_own_overdue` / `_paid` | Your own part of it is %1$s. / Your own part of it was %1$s. |
| `cardbill_who_owes` | Who owes what |
| `cardbill_row_detail` | %1$s · %2$s |
| `cardbill_expenses` (plural) | %d expense / %d expenses |
| `cardbill_groups` (plural) | %d group / %d groups |
| `cardbill_remind_everyone` | Remind everyone |
| `cardbill_nothing_owed_title` / `_body` | Nobody owes you for this bill / Split a payment made with this card and what friends owe of it shows here. |
| `cardbill_home_label` | Card bills |
| `cardbill_home_title` | %1$s bill |
| `cardbill_home_due_in` (plural) | %1$s due in %2$d day / %1$s due in %2$d days |
| `cardbill_home_due_today` / `_tomorrow` | %1$s due today / %1$s due tomorrow |
| `cardbill_home_was_due` | %1$s was due %2$s |
| `cardbill_home_no_date` | %1$s to pay |
| `cardbill_home_friends` | %1$s · friends owe %2$s |
| `cardbill_channel` / `_description` | Card bills / A credit card bill is due in a few days. |
| `cardbill_notify_due_in` (plural) | %1$s bill due in %2$d day / %1$s bill due in %2$d days |
| `cardbill_notify_due_today` / `_tomorrow` | %1$s bill due today / %1$s bill due tomorrow |
| `cardbill_notify_friends` | Friends owe you %1$s of the %2$s. |
| `cardbill_notify_friend` | %1$s owes you %2$s of the %3$s. |
| `cardbill_notify_pay_by` | Pay %1$s by %2$s. |
| `cardbill_notify_remind` | Remind friends |

Reused: `you_sign_in_title`, `you_sign_in_body`, `grouppage_reminded_*`, `grouppage_not_reminded_*`,
`grouppage_offline_*`, `grouppage_failed_*`, `onboarding_account_credit_card`. Dates are "d MMM" ("21 Nov").

### 2.9 Server

Nothing. No route, table, field or push changes. Card bills, card payments, the cycle, the reminder and its text stay
on the phone. Remind everyone uses the existing `POST /v1/groups/{gid}/reminders` and share links unchanged.
`docs/server.md` 13.15 becomes: "Decided 2026-11-21 (lead): automatic reminders are local notifications to the person
only (docs/card-bills.md); the phone never calls `/reminders` by itself."

### 2.10 Code layout

- `cardbill/CardBillMath.kt` (2.2), `CardBillReader.kt` (combines bills, accounts, expenses, `observeOwedOn`, auth and
  settings into `observeBill(id): Flow<CardBillDetail?>`, `observeHomeBills(): Flow<List<CardBillSummary>>`,
  `reminderContent(id, now)`), `CardBillReminders.kt`, `CardBillReminderWorker.kt`, `CardBillNotifications.kt`. Made
  in `AppContainer`.
- `ui/screens/cardbill/`: `CardBillScreen`, `CardBillUiState`, `CardBillViewModel`, `CardBillNav.kt`,
  `CardBillSamples`; previews in `src/debug/.../preview/screens/CardBillScreenPreviews.kt` for every frame of 2.11.
- Home: `HomeUiState.cardBills`, `HomeViewModel` reads `observeHomeBills()`.
- `gradle/libs.versions.toml`: `androidx-work-testing` (`androidx.work:work-testing`, `work` 2.12.0) as
  `testImplementation`.

### 2.11 Tests

JVM (`app/src/test/.../cardbill/CardBillMathTest.kt`):

- Statement date: statement SMS first; due reminder only (due − 15); no due date.
- Cycle edges: a payment on `S` counts, on `S − 1 month` does not, the day after it does.
- Owed: min(share, pair); pair 0 or negative → 0; removed → 0; one person in two groups → one row, "2 groups", summed
  count; rows ordered; `F` above the total keeps the heading, bar full, own ₹0.
- Paid: exact sum; two part payments; minimum only → not paid; credit on `S` ignored; credit after due + 7 ignored.
- State: today, tomorrow, in 5, overdue, no date, paid wins over overdue.
- `fireAt`: before the window; inside at 14:00 (now); 07:00 (09:00 today); 22:00 (09:00 tomorrow); 22:00 on the due
  date (none); after the due date (none).
- Home list: current bill only, paid hidden, overdue day 7 shown and day 8 hidden, switched off, no card, manual mode.

Robolectric:

- `CardBillRepositoryTest`: a reminder after a statement keeps `receivedAt`, `smsId`, `remindedAt`, takes the new
  amount.
- `DatabaseMigrationTest`: 6 → 7.
- `CardBillReaderTest` (in-memory Room with group tables): split card payments with settlements give the drawn
  numbers (₹24,300; Rahul 4,100, Priya 2,600, Kiran 1,500; ₹8,200; own ₹16,100); signed out → no friends part.
- `CardBillRemindersTest` (`WorkManagerTestInitHelper`): enqueue with the right delay; cancel on paid, replaced,
  switched off, reminders off, manual mode; a second `sync()` changes nothing.
- `CardBillReminderWorkerTest` (`TestListenableWorkerBuilder`): posts on `card_bills` with the exact title and body;
  "Remind friends" only with `F > 0`; nothing when reminded, paid or notifications off; quiet hours re-enqueue; sets
  `remindedAt`.
- `CardBillViewModelTest`: each state of 2.3; Remind everyone calls `remind` once per group, opens WhatsApp for the
  top friend without the app, each banner, busy guard; `remind = true` runs once.
- `HomeViewModelTest`: rows, order of several cards, hidden on Family side.
- `AppLinkTest`: `AppLink.CardBill` route and JSON round trip.

### 2.12 Design additions (both boards, `design/spec` JSON and both PNGs, `README.md` entry)

| Frame | Base | Change |
|---|---|---|
| `02-everyday/145-home-card-bill` | `52` (nothing waiting) | Label "CARD BILLS" and a List card above Latest with two Info Rows (`Icon/card` 3D): "HDFC Millennia bill" / "₹24,300 due in 5 days · friends owe ₹8,200"; "ICICI Amazon Pay bill" / "₹6,480 due in 12 days" |
| `02-everyday/146-notification-card-bill` | `54` | "SumBuddy · now": "HDFC Millennia bill due in 3 days" / "Friends owe you ₹8,200 of the ₹24,300." with Button Primary Medium "Remind friends"; "SumBuddy · 2h": "ICICI Amazon Pay bill due tomorrow" / "Pay ₹6,480 by 17 Nov." |
| `03-getting-paid-back/20-card-bill-view-nothing-owed` | `04` | 2.3 row 2 |
| `03/21-card-bill-view-signed-out` | `04` | 2.3 row 3 |
| `03/22-card-bill-view-overdue` | `04` | label "BILL WAS DUE 14 NOV", caption "Your own part of it is ₹16,100." |
| `03/23-card-bill-view-paid` | `04` | label "BILL PAID ON 19 NOV", caption "Your own part of it was ₹16,100." |
| `03/24-card-bill-view-reminder-sent` | `04` | `02/87`'s banner at the top of the body: "Reminder sent" / "Sent to Rahul and Priya." |
| `03/25-card-bill-view-no-reminder-sent` | `04` | `02/110`'s banner |
| `03/26-card-bill-view-offline` | `04` | `02/99`'s banner (`Icon/wifi-off` 3D, "You're offline" / "Check your connection and try again. Nothing is lost.") |
| `01-onboarding/05-privacy-promise` (changed) | itself | fourth Info Row (D26), 16 under "What we keep stays here" |

243 screens per theme after this. README lists the copy-only variants of 2.8 (D24).

Flow page and `flow.md`:

- Lane 02, "Other notifications": new `SCREEN` `NEW` **Card bill reminder** - "3 days before a card bill is due.
  Remind friends shows when friends owe part of it. Nothing goes to friends by itself."
- Lane 06 step 5 **Remind everyone**: "One tap reminds each friend who owes part: a push on the app, or WhatsApp with
  their pay link."
- Lane 06 branch "If reminders are switched on": **Automatic reminders** - "A notification to you 3 days before the
  due date. You choose whether to remind friends."
- Lane 06 new branch "Where it shows": `SCREEN` **Home row** - "Each card's latest bill until it is paid. Tap for the
  card page."
- Lane 06 new branch "Other states" (side by side): **Nobody owes part**, **Signed out**, **Past due**, **Paid** ("The
  card's payment SMS closes it."), **Reminder sent**, **No reminder sent**, **Offline**.
- Lane 07 **Automatic reminders**: "Before a card due date." ("or at month end" is not built.)
- Lane 01 step 3 **Privacy promise**: "... only payments are kept, and the code that reads them is public."

## 3. See the code

### 3.1 Where (as the design draws it)

| Screen | Element | Action |
|---|---|---|
| You (`04-you/00`, `06`, `09`) | PRIVACY list card, second Info Row under "What we've read" (12 gap inside the card): `Icon/receipt` 3D, "See the code" / "The SMS reader is public" | opens the link |
| What we've read (`04-you/04`) | Footer, Button Secondary Large "See the code" | opens the link |
| Privacy promise (`01-onboarding/05`) | fourth Info Row "Check it yourself" / "The code that reads SMS is public." (D26) | none |

Strings: `you_see_code_title` "See the code" (also the What we've read button), `you_see_code_body` "The SMS reader is
public", `onboarding_privacy_source_title` "Check it yourself", `onboarding_privacy_source_body` "The code that reads
SMS is public.". In `strings.xml`, next to `app_name`: `<string name="source_code_url"
translatable="false">https://github.com/akhilux-design/sumbuddy</string>`, the only place the URL is written. Remove
the "Plus and See the code are left out" note in `strings_you.xml` (Plus stays out).

### 3.2 How it opens

`openInBrowser(context, url)` in `ui/nav/ExternalLinks.kt`: `Intent(ACTION_VIEW, url.toUri())` with
`CATEGORY_BROWSABLE`, `startActivity`; `ActivityNotFoundException` is caught and nothing happens (a phone with no
browser cannot open any link). No WebView, no Custom Tabs dependency. The You and What we've read view models send an
`OpenSourceCode` event; the Nav file calls it.

### 3.3 Tests and docs

Robolectric Compose: You shows the row in `00`, `06` and `09` states; What we've read shows the button; a tap starts
an `ACTION_VIEW` intent whose data is `source_code_url` with `CATEGORY_BROWSABLE` (`shadowOf(application)
.nextStartedActivity`); the Privacy promise shows the fourth row. `BuildRulesTest`: the URL appears in no other
resource or source file. `docs/product.md` "Privacy gaps": the first bullet becomes done (2026-11-2x); the privacy
policy link stays open.

## 4. Where the design and the code disagree

1. You (`00`, `06`, `09`) and What we've read (`04`) draw See the code; the app leaves it out. Fixed by 3.
2. The Privacy promise lost "Check it yourself" in commit `44bcfda` (design) while `docs/product.md` still names it.
   The design gains the row back (D26).
3. You's "Automatic reminders" promises a reminder; the app only stores the switch (`docs/architecture.md`). Fixed by
   2.7.
4. Flow lane 06 says the app "sends the reminders itself"; that contradicts D1. Flow text changes (2.12).
5. Flow lane 06 "One tap sends each friend their amount and pay link": Android cannot send WhatsApp messages for the
   person; friends without the app are reminded one at a time (D19).
6. Flow lane 07 "Before a card due date or at month end": month-end reminders are not built.
7. `02-everyday/04` draws the card bill notification with no action and "due in 5 days"; reminders go 3 days before
   and may carry "Remind friends". New frame `146`; `04`'s sample stays as it is.
8. The design has no in-app way into the Card bill view; the app adds the Home row (`145`).
9. `03/04`'s progress layer is named "Collected" but its fill is the friends' share of the bill (D12).
10. Digits: the Card bill card reads "•• 2217" (space), the payment card and notifications "••4521", Accounts "Credit
    card ••2217". Each screen follows its own frame.
11. `docs/groups-build-plan.md` lists the card bill view as out of scope; that note is now history.

## 5. Docs to update with the build

`docs/product.md` (a "Card bills (decided 2026-11-21)" section with D1, D9, D13, D16, D17; Privacy gaps),
`docs/architecture.md` (`CardBill.remindedAt`, version 7, the `upsert` rule, `remindersOn` no longer "only stored",
the `cardbill` packages, the route and `AppLink`), `docs/server.md` 13.15, `design/spec/flow.md`,
`design/spec/README.md`, `CHANGELOG.md`.

## 6. Accepted limits

- The cycle is estimated (D6): with only a due reminder from an issuer with a 20 to 25 day gap, the last 5 to 10 days
  of card payments before the due date − 15 may belong to the next bill.
- A refund to the card in the window counts towards Paid.
- A reminder SMS after a part payment that prints only what is left becomes the bill's total, so earlier part
  payments can mark it paid early.
- WorkManager can run a reminder late while the phone is idle; the worker never posts in quiet hours.
- D7 compares an SMS with the bill's first SMS only (no column keeps when the amounts' SMS came): an SMS stored
  after a newer one of the same bill, but not older than the bill's first, gives the amounts. That needs two older
  SMS of one bill read by "add older payments" after a newer one; banks' reminders mostly repeat the same total.
