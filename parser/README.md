# SMS parser

Pure Kotlin. Turns one bank or UPI SMS into a `ParseResult` (see `Model.kt`). No Android classes, no I/O,
no network, and it never logs message text.

## Rules (the contract)

**Who is read.** Only messages from business sender headers are considered: 3 to 9 letters or digits, with an
optional two-letter operator prefix and hyphen (`VM-HDFCBK`) and an optional suffix `-S`, `-T`, `-P` or `-G`
(`VM-HDFCBK-S`). A sender that is a phone number (`+9198...`, 10 digits) is always
`Skipped(NOT_FINANCIAL)`, whatever the text says.

**What each message becomes.**

| Message | Result |
|---|---|
| Money left or entered an account, card or wallet | `Payment` (`DEBIT` or `CREDIT`) |
| A refund, reversal or cashback that arrived as its own message | `Payment` with `CREDIT` |
| Credit card statement, bill notice or payment-due reminder with the amount due | `CardBill` (never spending; used only for the card bill view and due dates) |
| One-time password or verification code, even if it mentions an amount | `Skipped(OTP)` |
| Offer, loan or other marketing, even if it mentions an amount | `Skipped(PROMOTION)` |
| Payment failed, declined, not processed, or reversed in the same message | `Skipped(FAILED)` |
| Collect request, payment request, mandate or autopay being set up | `Skipped(REQUEST)` |
| "Will be debited", "is due", "scheduled", bill reminders from billers | `Skipped(UPCOMING)` |
| UPI Lite top-up (including auto top-up), UPI Lite balance moved back to the bank, money loaded into the user's own wallet | `Skipped(SELF_TRANSFER)` |
| A biller, merchant or insurer (sender is not a bank) confirming it received the user's payment | `Skipped(BILLER_RECEIPT)` |
| Money in from a sender that is not a bank whose amount is labelled as points, coins, miles or a store voucher right after it ("Rs.200 loyalty points have been credited", "Rs 100 credited as NeuCoins"); a message that prints no money amount and moves only points ("500 SuperCoins credited") | `Skipped(REWARD_POINTS)` |
| Balance enquiry, low balance alert, statement with no total due | `Skipped(BALANCE_INFO)` |
| Nothing about money | `Skipped(NOT_FINANCIAL)` |
| Clearly a payment (debited, credited, spent, paid, sent, received, withdrawn) but the amount or direction cannot be read | `Unreadable` |

**Fields.**

- `amountMinor` is in paise and always positive: `Rs 450` is `45000`, `Rs.1,23,456.78` is `12345678`.
  When a message has several amounts, the payment amount is the one tied to the action word, never the balance.
- Card spends in a foreign currency use that currency's code and minor units, unless the message also prints the
  rupee amount, in which case the rupee amount is used.
- `counterparty` is cleaned for display: no "VPA", no reference numbers, no trailing punctuation.
  Text in ALL CAPS becomes Title Case (`SWIGGY` becomes `Swiggy`). Null when the message does not say.
- `vpa` is the other side's UPI address when printed.
- `bank` uses the display names in `Banks.kt`. Null when the sender and text do not identify one.
- `instrumentLast4` is the last digits as printed (`XX4521`, `**4521`, `ending 4521` all give `4521`).
- `instrumentType` follows the words closest before the digits. A card not called credit or debit there is a
  `DEBIT_CARD` when the message names a debit card anywhere or prints a debit block line (`SMS BLOCK DC 1234`), and a
  `CREDIT_CARD` when it names a credit card, prints a credit block line (HDFC `SMS BLOCK CC 4521`, Yes Bank
  `BLKCC 2207`), says `CC XX4521` or `Cardmember`, prints a limit (`Avl Lmt`, `available limit`), or comes from an
  issuer that only gives credit cards (SBI Card, American Express, OneCard). Otherwise it is `UNKNOWN`: a plain
  "Card XX5566" is not guessed. Samples, negatives included: `samples/card-types.json`.
- `referenceId` is the UPI reference, RRN or transaction id as printed.
- `balanceMinor` is the balance after the payment when stated.
- `occurredAtMillis` is set only when the message prints a date; time defaults to 00:00 when only a date is given.
- `confidence` is `HIGH` when amount and direction are certain and the message identifies the account or card
  (last digits) or the bank. Otherwise `LOW`.

**Decisions where the table leaves room.** Each one picks the reading where a wrong expense is less likely.

- A sender with no letter (a numeric short code, or a 12-digit `91...` number) is a person, so `Skipped(NOT_FINANCIAL)`.
  An unknown business header is still read; its payments get `bank` null and `LOW` confidence.
- A credit card's available or total limit is not a balance: `balanceMinor` stays null when only a limit is printed.
- Minor units follow ISO 4217, so a zero-decimal currency such as JPY uses whole units (`JPY 4,500.00` is `4500`).
- A rupee amount printed for the spend itself wins over the foreign amount; an INR limit or balance does not count.
- **Points: a closed rule** (`RealMoney`, lead decision 2026-11-20). Every rule that guessed from words near the
  amount lost real money, so a message is skipped as `REWARD_POINTS` only when ALL of these hold, and is otherwise read
  exactly as before:
  1. It reads as money in. Debits read as before, whatever they say about points ("Rs.120 loyalty points have been
     debited" is a debit).
  2. No bank sent it (`SmsParser.isFromBank` is false): its header is not in `Banks.kt`, its header code does not read
     as a bank's (ends in `BK`, `BNK`, `SFB` or `PB`, or contains `BANK`), and it does not sign off with a bank's name
     (`-Equitas SFB`). Everything a bank sends reads as before.
  3. The amount is labelled as points in the words right after it: the amount, at most two label words, a unit and a
     credit phrase ("Rs.200 loyalty points have been credited", "Rs 150 PAYBACK points credited", "Rs 100 Flipkart gift
     card credited", "Rs 50 TimesPoints credited"); or the amount, a credit phrase, "as" or "in", at most two label
     words and a unit that ends the phrase: the end, a punctuation mark, a code, or to, for, on, at, in, from,
     against, with, valid or and ("Rs 100 credited as NeuCoins", "Rs 799 has been credited as credit note CN4412").
     - Units, a closed list: points, point, pts, coins, miles, reward points, SuperCoins, NeuCoins, Fi-Coins,
       CashPoints, TimesPoints, InterMiles, BluChips, GoCash, SuperCash, CRED coins, gift card, e-gift card, gift
       voucher, e-gift voucher, voucher, store credit, credit note.
     - Label words: loyalty, reward, rewards, bonus, welcome, cashback, club, member, membership, or one capitalised
       brand word ("PAYBACK", "Flipkart", "Tata"). Nothing else: gold, silver, platinum, Solana, meal and Four are not
       labels.
     - Credit phrase: optionally has been, have been, is, was, are or were, then credited, added, awarded, earned or
       issued. The unit must touch it.
  - Read as before, so money in or a payment: "Rs 18,500 gold coin credited", "Rs 1,500 meal voucher reimbursement
    credited", "Rs 2,000 Bitcoin sale proceeds credited", "Rs 300 Four Points refund credited", "Rs 2,000 on your gift
    card has been credited to your bank", "Rs 200 Tata Neu points credited" (two brand words), "Rs 50 cashback
    credited as SuperCoins" (a word before the credit phrase), "Rs 2,000 credited as gift card refund to your bank" and
    "Rs 1,500 has been credited as voucher reimbursement" (the unit does not end the phrase), "Rs 625 Reward Points
    redemption cashback credited to your HDFC Bank Credit Card", and any of the skipped shapes sent by a bank.
  - A message that prints no money amount and in which every debit or credit word belongs to a count of points ("500
    SuperCoins credited", "1,250 Reward Points have been credited to your Credit Card") is skipped too, from any sender.
    Without a money amount it was never a payment, so this only replaces a Needs review item. A points count beside
    another debit or credit word ("for your stay paid with HDFC Bank Credit Card") stays `Unreadable`, and so does a
    message that prints money without Rs or INR, a number with paise left once the points are taken out ("Salary
    52,000.00 and 100 bonus points credited").
  - **Accepted limits.** A store credit, a store wallet or points "worth" rupees from a store whose amount is not
    labelled that way reads as money in, which is never counted as spending: "Rs.150 has been credited to your Croma
    loyalty wallet", "Rs.300 refund has been credited to your Lifestyle account as store credit", "400 PAYBACK points
    worth Rs 100 have been credited", "Your Westside gift card has been loaded with Rs 2,000", "Rs 40 in BharatPe Coins
    credited". So does a bank's points message with an amount ("Rs 500 bonus reward points credited" from HDFC Bank).
    A store's points or voucher debit is a waiting debit the person can ignore. A points count after "credited with"
    with no currency is read as rupees, as before ("credited with 500 bonus reward points").
- Precedence: OTP, then promotion, then failed, then request, then card bill, then upcoming, then the payment as read,
  then points, which can only turn that reading into `Skipped(REWARD_POINTS)`. A card statement that says "is due" or "due by" is still a `CardBill`, and so is a
  payment-due reminder with a due date that also reads as an offer ("Pay on time to earn bonus reward points"), unless
  it mentions a loan or EMI. A count of points credited ("250 reward points credited this cycle") does not make a
  reminder a payment already made when no money moved in the message. "Reversal of" a failed payment arriving as its
  own credit is a `Payment`.
- A message with a debit or credit word is never `BALANCE_INFO`; `BALANCE_INFO` is only for messages with no such word.
- `occurredAtMillis` needs a printed date with a year; a time alone or a day and month alone leaves it null.

## Test samples

`src/test/resources/samples/*.json`. Each file is a JSON array of cases:

```json
{
  "id": "hdfc-upi-debit-01",
  "sender": "VM-HDFCBK",
  "body": "Rs.450.00 debited from A/c XX4521 ...",
  "receivedAt": "2026-11-16T10:15:00+05:30",
  "expect": { "type": "payment", "direction": "DEBIT", "amountMinor": 45000, "counterparty": "Swiggy" }
}
```

`expect.type` is `payment`, `cardBill`, `skipped` or `unreadable`. Only the keys present in `expect` are checked:

- payment: `direction`, `amountMinor`, `currency`, `counterparty`, `vpa`, `bank`, `instrumentType`,
  `instrumentLast4`, `channel`, `referenceId`, `balanceMinor`, `occurredAt` (ISO date-time with offset), `confidence`
- cardBill: `bank`, `cardLast4`, `totalDueMinor`, `minimumDueMinor`, `dueDate` (`yyyy-MM-dd`)
- skipped: `reason`

A key set to `null` means the parser must return null for it. `counterparty` and `bank` are compared ignoring
case, spaces and punctuation. Every case `id` is unique across all files.

The samples are written from knowledge of common formats, not copied from real inboxes. Real messages found
during testing on a phone should be added here (with names and numbers changed) whenever the parser gets one wrong.

## Product decisions (2026-11-17)

- **UPI Lite:** a top-up only moves the user's own money, so it is `Skipped(SELF_TRANSFER)`. Each spend paid from
  UPI Lite ("Paid Rs 20 to X via UPI Lite", "Rs 60 spent from your UPI Lite") is a `DEBIT` payment, even when the
  message also suggests topping up. Loading the user's own wallet (Paytm, Amazon Pay) is treated the same way;
  cashback and refunds into a wallet stay `CREDIT`.
- **Card payment-due reminders** are read only for the amount and due date (`CardBill`) and never count as spending.
- **Biller receipts** (recharge successful, premium received, bill payment received) from senders that are not banks
  are `Skipped(BILLER_RECEIPT)`; the bank's SMS for the same payment is the one counted.
- **Points (2026-11-18, owner; closed rule 2026-11-20, lead):** a store's loyalty SMS ("Rs.200 loyalty points have
  been credited") became Money in. It is skipped now, by the closed rule above. Five rounds of rules that guessed from
  words near the amount (a store balance somewhere in the sentence, a word ending in coin or point, points "worth", a
  store wallet) kept losing real money, so they were removed: store credits, store wallets and points "worth" rupees
  from a store show as money in, which is never counted as spending. `SmsParser.isFromBank` and
  `SmsParser.readIgnoringSkips` (the amount, direction and payee a message gives with every skip rule left out) serve
  the app's one-time clean-up of money in rows stored by older versions.
- **Duplicates:** `Duplicates.keepBankCopies` drops a second report of the same payment: same direction, exactly the
  same amount and currency, within 15 minutes or with the same reference number. The bank's SMS always wins. Two bank
  SMS with the same amount but different references are two real payments and both are kept.
