# SumBuddy: app flow (v3)

Android first  ·  Bank SMS read on the phone  ·  Monthly expenses first, splitting second  ·  Friends don't need the app  ·  Everything here is free

> Text export of the Figma Flow page (frame 3:2). The wording of every card is unchanged.
> Each step is written as: `KIND` **Title** - Description. `NEW` and `V3` are the badges shown on a card (see Legend).
> Numbered steps are joined by arrows in Figma and happen in that order. Bulleted cards sit side by side without arrows.
> A bulleted card after numbered steps sits at the end of the same row, without an arrow: an alternative to those steps.

## Legend

- Screen the user sees
- App does it automatically
- Decision point
- Outcome
- Paid feature
- Still to decide
- Later version
- `NEW` Added in v2
- `V3` Changed in v3

## 01 Onboarding

First launch to a working home screen.

### Main path

1. `SCREEN` **Install and open** - Short intro: expenses track themselves, splitting is free.
2. `SCREEN` `V3` **Your name** - Shown on Home. Kept on this phone: tracking needs no sign-in.
3. `SCREEN` `V3` **Privacy promise** - Shown before the Android prompt: messages are read on the phone, never uploaded, only payments are kept, and the code that reads them is public.
4. `DECISION` **Allow SMS access?** - Android permission prompt.
5. `DECISION` **Allow notifications?** - Android 13 and later ask for this. Needed to show payment detected.
6. `DECISION` `NEW` **How far back?** - User picks: start fresh (no past payments), this month (suggested), last 3 months, or from a date.
7. `AUTOMATIC` **Scan past SMS** - Only messages from the chosen start date. Finds banks, cards and UPI IDs, and adds those payments.
8. `SCREEN` **Confirm accounts** - User ticks which accounts and cards to track, and can rename them.
9. `OUTCOME` `V3` **Home** - This month's expenses first, then new payments to sort, then balances with friends.

### Branch: When they first split or open Groups

1. `SCREEN` `NEW` **Sign in** - Only when first needed: from Groups, Split, or Sign in on You. Continue with Google, or Not now.
2. `SCREEN` `V3` **Your name (after sign-in)** - Name from Google. Phone number (not checked) and UPI ID are optional. Friends in their groups see the UPI ID on pay links.
3. `OUTCOME` `NEW` **Back where they were** - Groups opens, or the split they started carries on.

### Branch: If starting fresh

1. `AUTOMATIC` `NEW` **Start fresh** - No past payments are added. The list begins empty and fills as new payments happen.

### Branch: While the inbox is counted

1. `SCREEN` `NEW` **Looking for messages** - The bar is empty until the messages are counted.

### Branch: If reading stops

1. `SCREEN` `NEW` **Reading stopped** - Nothing was lost. Try again reads the rest.

### Branch: To rename an account

1. `SCREEN` `NEW` **Rename account** - On Confirm accounts, tap an account: a dialog with its name, Save and Cancel.

### Branch: To change it later

1. `SCREEN` `NEW` **Tracking start date** - In Settings. Shows the current start date and lets the user move it.
2. `AUTOMATIC` `NEW` **Add older payments** - Moving it earlier scans only the extra period. Payments already listed are not added twice.
3. `SCREEN` `NEW` **Reading older payments** - A note shows 'Reading your bank messages: 12 of 80', then the result.
4. `OUTCOME` `NEW` **They wait on Home** - Older payments wait on Home to be added. Or 'No older payments were found'.

### Branch: If SMS access is denied

1. `SCREEN` **Manual mode** - App still works; expenses are typed in by hand.
2. `SCREEN` `V3` **Offer again later** - Prompt to turn on auto-tracking from Home and Settings. Privacy promise shows again before Android's prompt.
3. `SCREEN` `NEW` **Turn on SMS access in Settings** - If Android will not ask again. Open Settings, or type them in. Moves on by itself once access is on.

### Branch: In manual mode

- `SCREEN` `NEW` **You (manual mode)** - SMS auto-tracking is off. Tracking start date reads Not started.
- `SCREEN` `NEW` **Tracking start date (manual mode)** - Nothing can be read yet, so Add older payments is off. A note says to turn on SMS auto-tracking.

### Branch: If no bank messages are found

1. `SCREEN` `NEW` **Nothing found** - Says why, and offers to start from today or look further back.
2. `OUTCOME` `NEW` **Home (start fresh)** - Empty Home. New payments appear as SMS arrive.

### Branch: If they already have an account

1. `SCREEN` `V3` **Sign in** - Continue with Google, with the same Google account as before.
2. `AUTOMATIC` `NEW` **Groups and balances restored** - They come back from the account.
3. `AUTOMATIC` `NEW` **Scan SMS on this phone** - Personal expenses are read again from this phone.

### Branch: To change tracked accounts later

1. `SCREEN` `V3` **Accounts and cards** - From You. Switch accounts on or off, or rename them.
2. `AUTOMATIC` `NEW` **New accounts added** - When their first SMS arrives.

### Branch: To rename an account later

1. `SCREEN` `NEW` **Rename in Accounts and cards** - Tap an account: the row becomes a name field with Save and Cancel.

### Branch: If there are no accounts yet

1. `SCREEN` `NEW` **No accounts yet** - Until the first bank SMS arrives, for example in manual mode.

### Branch: If sign-in does not work

1. `SCREEN` `V3` **Sign in (could not sign in)** - Says so. Try again, or Not now: everything except groups keeps working.

## 02 Payment detected

A payment is read from SMS and the user decides what it is.

### Main path

1. `SCREEN` **User pays** - UPI, credit card, debit card or net banking, in any app.
2. `AUTOMATIC` **Bank SMS arrives** - Debit alert from the bank or card issuer.
3. `AUTOMATIC` **Read on the phone** - Pick out amount, merchant, account, date and reference number.
4. `DECISION` **Is it a spend?** - Skip OTPs, promotions, money received and transfers between own accounts.
5. `SCREEN` `V3` **Notify: payment detected** - "Payment detected: ₹450 at Swiggy" with three actions: Add, Split, Ignore.
6. `DECISION` `V3` **What does the user tap?**

### Branch: Other notifications

- `SCREEN` `NEW` **Payment detected variants** - 'Read from a bank SMS' when no account is named. Another currency reads 'USD 12.50'. An automatic split says 'Nothing to tick'.
- `SCREEN` `NEW` **Group notifications** - A friend added an expense, marked a payment as paid (with Confirm), confirmed it, or joined a group.
- `SCREEN` `NEW` **Group notifications (bundled)** - Several show under one header: the app name and a count. Tapping one opens its group and removes only that one. Sound, no pop-up.
- `SCREEN` `NEW` **Paid back notifications** - Balance cleared, directly or in several groups. You paid Rahul. 'Was this Rahul paying you back?' when unsure.
- `SCREEN` `NEW` **Reminders and background** - 'Rahul reminded you'. On Android 11 and older, quiet notes while SMS are read or groups sync.
- `SCREEN` `NEW` **Card bill reminder** - 3 days before a card bill is due. Remind friends shows when friends owe part of it. Nothing goes to friends by itself.

### Branch: If Add

1. `SCREEN` `V3` **Add to expenses** - The app opens on this payment. Paid to, category and note are pre-filled. Choose Just me or Family.
2. `OUTCOME` `V3` **In this month's expenses** - The full amount counts towards the month.

### Branch: If the payment was already sorted

1. `SCREEN` `NEW` **Already sorted** - Add to expenses opened from an old notification. See expense when it was added.

### Branch: If the payment is gone

1. `SCREEN` `NEW` **Payment not here** - From Add to expenses, Expense added or Expense detail. Go back.

### Branch: If Split

1. `DECISION` `NEW` **Signed in?** - If not, Sign in opens first (lane 01). Not now leaves the payment waiting.
2. `AUTOMATIC` `V3` **Go to lane 03** - The app opens on the split details for this payment.

### Branch: If Ignore

1. `OUTCOME` `V3` **Ignored** - Not counted. Listed under Ignored in Activity. Count it again on Expense detail brings it back.
2. `SCREEN` `NEW` **Activity: Ignored** - The Ignored filter lists them with 'Ignored'.
3. `SCREEN` `NEW` **Expense detail (ignored)** - Tag Not counted. Count it again brings it back.

### Branch: If the notification is not answered

1. `OUTCOME` `V3` **Waits on Home** - Shown as a new payment with the same three choices until it is sorted.

### Branch: If nothing is waiting

1. `SCREEN` `NEW` **Home (nothing waiting)** - No payment card. No Owed to you or You owe tiles when signed out or splitting with nobody.

### Branch: When nothing is owed

1. `SCREEN` `NEW` **Home (all settled)** - Owed to you and You owe both read ₹0, in the normal text colour, not green or red.

### Branch: If several are waiting

1. `SCREEN` `NEW` **Review payments** - From Review all on Home. One payment at a time: swipe right to add, left to ignore, or tap Add or Ignore.
2. `OUTCOME` `NEW` **All sorted** - How many were added and ignored, and the total. Undo brings back the last choice.

### Branch: To add them all at once

1. `SCREEN` `NEW` **Add all** - From Review payments. Shows the total and a breakdown by category before adding.
2. `OUTCOME` `NEW` **All added** - Each gets the category shown. Undo puts them all back to waiting.

### Branch: If the message cannot be read confidently

1. `SCREEN` `V3` **Needs review list** - User types the amount to add it, or marks it as not a spend.
2. `SCREEN` `NEW` **Result note** - Says it was added, listed as money in, or already counted.
3. `OUTCOME` `NEW` **All sorted** - Nothing left to review.

### Branch: Privacy check, any time

1. `SCREEN` `NEW` **What was read** - Settings screen listing each message used, and confirming nothing left the phone.
2. `SCREEN` `NEW` **See the code** - Link to the public reading code so the claim can be checked.

### Branch: To change an expense later

1. `SCREEN` `NEW` **Expense detail** - Open any expense from Home or Activity.
2. `SCREEN` `V3` **Edit, split or delete** - Change category or note, turn it into a split, or delete it. Delete asks first.
3. `OUTCOME` `NEW` **Month total updates** - The change shows on Home straight away.

### Branch: Edit or delete

- `SCREEN` `NEW` **Edit** - Cancel in the top bar. Fields replace the rows. Save changes.
- `SCREEN` `NEW` **Delete this expense?** - Asks first. The bank message stays on the phone.

### Branch: Other kinds of expense

- `SCREEN` `NEW` **Added by hand** - Tag Added by hand, the way paid, no original message.
- `SCREEN` `NEW` **Waiting for a decision** - Not added yet. Add or ignore it opens Add to expenses.
- `SCREEN` `NEW` **Money in** - Tag Money in. Never counted as spending. Cannot be split.
- `SCREEN` `NEW` **Another currency** - USD 12.50. Not counted in the month. Cannot be split.

### Branch: If the expense is split

1. `SCREEN` `NEW` **Delete split expense** - The confirm names the group. It is removed for everyone, and your share leaves the month.
2. `OUTCOME` `NEW` **Removed for everyone** - Friends no longer owe for it. The month total updates.

### Branch: To see the month

1. `SCREEN` `NEW` **Month view** - Total, last month and spending by category.
2. `SCREEN` `V3` **Change month** - Previous month and Next month buttons look back at earlier months.
3. `SCREEN` `NEW` **Nothing added** - A month with no payments.

### Branch: If the category needs changing

1. `SCREEN` `NEW` **Choose category** - Food, Groceries, Travel, Rent and bills, Shopping, Fun, Other.

### Branch: If there are no expenses yet

1. `SCREEN` `NEW` **Activity (empty)** - Explains the list and offers Add one by hand.

### Branch: If a filter matches nothing

1. `SCREEN` `NEW` **Nothing here** - No payments match this filter yet.

### Branch: Rows in lists

1. `SCREEN` `NEW` **More row kinds** - Money in, an untitled payment and an amount that could not be read.

### Branch: Payee known only by its UPI ID

1. `SCREEN` `NEW` **UPI ID as the name** - Kept whole and broken only after the @: Home, Review payments, Add to expenses, Expense added, Same split?, Split. Sorted., Money received.

## 03 Split a payment

Only the user's own share counts in their monthly expenses.

### Main path

1. `SCREEN` `V3` **Split details** - Who with and how. Pre-filled from the last time this merchant was split.
2. `SCREEN` **Choose split** - Equally, exact amounts, percentages or shares.
3. `AUTOMATIC` `V3` **Work out shares** - Each person's part, including the user's own.
4. `OUTCOME` **Added to group** - Balances update. Friends are notified.
5. `OUTCOME` `V3` **Share added to the month** - Only the user's part goes into monthly expenses, not the full payment.

### Branch: If no group is chosen yet

1. `SCREEN` `NEW` **Split it (no group yet)** - Only the payment and the With chips. Split stays off until a group is chosen.

### Branch: If a friend paid

1. `AUTOMATIC` `V3` **Share added to your month** - When a friend pays and includes the user, that share counts in the user's month too.

### Branch: If a friend paid and it is typed in

1. `SCREEN` `NEW` **Add expense (paid by a friend)** - Paid by Rahul. Paid with is not asked. Next: choose split.
2. `SCREEN` `NEW` **Split it (someone else paid)** - You owe your share. The others will owe Rahul. No Do this every time.
3. `OUTCOME` `NEW` **You owe Rahul** - Split. Sorted. 'You owe Rahul ₹200 for Dinner at Truffles.'

### Branch: If there was no SMS (cash, missed alert)

1. `SCREEN` `V3` **Add manually** - Amount, what for, who paid, category and date. Then Add to November, or Split this.

### Branch: Add manually states

- `SCREEN` `NEW` **Split this off** - The default from Home and Activity. Just me or Family, then Add to November.
- `SCREEN` `NEW` **Split this on** - Category and Date stay below Split this. Next: choose split.

### Branch: If it repeats (rent, same merchant)

1. `SCREEN` `V3` **Do this every time** - A switch on the details screen: add or split this merchant the same way without asking.
2. `AUTOMATIC` `V3` **Handled automatically** - The next payment is added or split by itself, and a notification confirms it.
3. `SCREEN` `NEW` **Turn it off** - On Expense detail the split row ends 'splits itself'. Tap it to open Same split? and turn it off.

### Branch: When the payee was split before

1. `SCREEN` `NEW` **Same split?** - 'Last month too' only when that split was in the month before the payment's own month. Otherwise 'You've split Swiggy with Flat 302 before.'

### Branch: If it is not split equally

1. `SCREEN` `NEW` **Amounts, % or shares** - Each person's part is typed in.
2. `AUTOMATIC` `NEW` **Check the total** - Parts must add up to the full amount.

### Branch: % or shares

- `SCREEN` `NEW` **By percentage** - Each row ends with its part, for example 40%. The money is in the line under the name.
- `SCREEN` `NEW` **By shares** - Each row ends with 2 shares or 1 share. The money is in the line under the name.

### Branch: If splitting outside a group

1. `SCREEN` `V3` **Choose people** - Pick groups or single friends. Add someone opens Add friend.
2. `DECISION` `NEW` **Several friends picked?** - A split goes to one group or one friend.
3. `SCREEN` `NEW` **Make a group** - Offered instead, with those friends filled in. New group from a split has no Import from Splitwise.

### Branch: If split with one friend

1. `OUTCOME` `NEW` **Rahul owes you** - Split. Sorted. 'Rahul owes you ₹150 for Swiggy.' The row says owes you.

### Branch: Your share in the month

- `OUTCOME` `NEW` **Split with Rahul** - Nobody owes anybody: no balance row. All of the ₹450 counts in your month.
- `OUTCOME` `NEW` **Paid for Rahul alone** - 'Rahul owes you ₹450 for Swiggy.' None of the ₹450 counts in your month.

### Branch: If the amounts do not add up

1. `SCREEN` `NEW` **Shows what is left** - The Split button stays off until the parts match the total.

### Branch: If the parts are above the total

1. `SCREEN` `NEW` **Parts above the total** - 'The parts add up to ₹500. They must add up to ₹450.' Also 'Give everyone a part above 0.'

### Branch: If the payment cannot be split

1. `SCREEN` `NEW` **Nothing to split here** - Gone, already split, or not in rupees. Go back.

## 03B Family month

A couple or family pools expenses into one monthly total. Nobody owes anybody.

### Main path

1. `SCREEN` `V3` **Set up Family** - Name it, e.g. Home.
2. `SCREEN` `V3` **Invite partner** - By a link they open on their phone. Family members need the app.
3. `AUTOMATIC` `V3` **Partner joins** - Each phone keeps reading only its own bank SMS.
4. `SCREEN` `V3` **Mark an expense as Family** - On the Add to expenses screen: count it in Just me or Family.
5. `AUTOMATIC` `V3` **Share the expense** - Only the expense entry goes to the family, never the SMS.
6. `OUTCOME` `V3` **Family month** - One total for the household, by person and by category.

### Branch: Where to start

- `SCREEN` `NEW` **Home (Family), no family** - The Family side shows Set up Family under the card.
- `SCREEN` `NEW` **You: Family** - Opens Set up Family, or Family settings.

### Branch: Signed out

1. `SCREEN` `NEW` **Sign in first** - From Set up Family or You. Not now goes back.

### Branch: Earlier Family expenses

1. `AUTOMATIC` `NEW` **Shared when you set up or join** - Set up Family and Join Family say how many first.

### Branch: While waiting

1. `SCREEN` `NEW` **Waiting for your partner** - Home's Family side and Family settings say the invite is sent. Send link again.

### Branch: On Home

1. `SCREEN` `V3` **Me or Family switch** - Flips the main card between my month and the family's month.
2. `SCREEN` `NEW` **Home (Family)** - The card shows this phone's Family total. Latest lists only Family expenses.

### Branch: Family month

- `SCREEN` `NEW` **Nothing yet** - A new month, or a new family, starts at ₹0.
- `SCREEN` `NEW` **Family month list** - Tap the card on Home's Family side. Every family expense by day.
- `SCREEN` `NEW` **Nothing in the list yet** - A month with no family expense shows the empty card under By person.
- `SCREEN` `NEW` **One category** - Top categories shows one tile at half width.

### Branch: Privacy

1. `OUTCOME` `V3` **Personal stays private** - Expenses marked Just me are never shown to the family.

### Branch: If it repeats

1. `SCREEN` `V3` **Do this every time** - Rent, groceries and bills can go to Family by default.
2. `AUTOMATIC` `NEW` **Family chosen for you** - The next payment to that merchant waits with Family selected; Add still needs a tap.

### Branch: Counting

1. `OUTCOME` `V3` **Counts in both** - A Family expense shows in the payer's own month and in the family month.

### Branch: On the partner's phone

1. `SCREEN` `NEW` **Join Family** - Explains the pooled month before they accept.
2. `SCREEN` `NEW` **Loading the family month** - First time only.
3. `OUTCOME` `NEW` **Family month** - Both now see the same total.

- `SCREEN` `NEW` **Already in a family** - One family at a time.

### Branch: Without the app

1. `SCREEN` `NEW` **Link without the app** - The web page says who invited them and to get the app first.

### Branch: Family settings

1. `SCREEN` `NEW` **Family settings** - From You. Rename, the partner, leave.
2. `SCREEN` `NEW` **Invite sent** - Tap Your partner: Send link again, or Cancel invite to turn the link off.

### Branch: If they leave or are removed

1. `SCREEN` `NEW` **Leave Family** - Their Family expenses leave the family month and stay on their phone.
2. `OUTCOME` `NEW` **Just you** - Their Family expenses leave the family month. Invite partner again.

## 04 Groups and friends

Where shared expenses live. Friends do not need the app.

### Main path

1. `SCREEN` `NEW` **Groups (not signed in)** - Before sign-in, Groups explains splitting and offers Continue with Google.
2. `SCREEN` **Create group** - Trip, flat, office lunch. Name and optional photo.
3. `SCREEN` `NEW` **Add friends** - From contacts or by phone number. No install needed on their side.
4. `OUTCOME` **Group page** - Expenses, who paid, who owes whom.
5. `OUTCOME` **Balances** - Debts simplified to the fewest payments needed.

### Branch: If a friend does not have the app

1. `SCREEN` `NEW` **Share on WhatsApp** - Sent from the user's own WhatsApp: what they owe, what for, and a pay link.
2. `SCREEN` `NEW` **Friend opens web page** - Shows the breakdown and a Pay by UPI button. No install or sign-up.
3. `AUTOMATIC` `NEW` **Repayment detected** - The money-received SMS on the user's phone clears the balance (lane 05).
4. `OUTCOME` `NEW` **Invite to install** - Friend can join later and keep their history.

### Branch: If the link is copied

1. `SCREEN` `NEW` **Link copied** - Copy link on Remind on WhatsApp. Android 12 and older show a Link copied banner; newer Android shows its own.

### Branch: To remind a friend who has the app

1. `AUTOMATIC` `NEW` **Push reminder** - Remind on the Group page sends 'Rahul reminded you: ₹1,150 for Flat 302'. No WhatsApp.
2. `SCREEN` `NEW` **Reminder sent** - A banner on the Group page: 'Sent to Rahul and Priya.'

### Branch: If they were reminded recently

1. `SCREEN` `NEW` **No reminder sent** - The same banner with a clock: 'They were reminded recently. Try again later.'

### Branch: Friend's web page states

- `SCREEN` `NEW` **You are owed** - Who owes you, and Nothing to pay.
- `SCREEN` `NEW` **This link no longer works** - Ask the person who sent it for a new link.
- `SCREEN` `NEW` **Could not load this page** - Check your connection. Try again.

### Branch: If moving from Splitwise

1. `SCREEN` `NEW` **Import from Splitwise** - From New group (Import from Splitwise instead) and You (More). Upload the group's Splitwise export file. One file is one group. 'Reading the file' shows while the file is read on the phone.
2. `SCREEN` `NEW` **Which one is you?** - Pick your own name. The group name comes from the file name and can be changed. Nothing is chosen when no name matches yours. Import stays off until you choose.
3. `AUTOMATIC` `NEW` **Recreate the group** - Members (names only), past expenses, payments and balances are copied from the file. Rupees only.
4. `SCREEN` `NEW` **Bringing it across** - Progress. You can leave; it finishes by itself.
5. `OUTCOME` `NEW` **Invite the group** - One message with a link for each person brings the rest of the group across. Links need the internet; offline, a banner says so.

### Branch: To add someone new

1. `SCREEN` `NEW` **Add friend** - Name and phone number, or pick from contacts.

### Branch: When Add friend opens

1. `SCREEN` `NEW` **Add friend (empty)** - Before a name is typed the note says 'Your friend'.

### Branch: To manage a group

1. `SCREEN` `V3` **Group settings** - From the Group settings row on the Group page. Rename, add or remove people. Shows who the owner is.
2. `OUTCOME` `V3` **Leave or delete** - Only once balances are settled, except that the owner can delete a group nobody else has joined yet. Only the owner can delete the group. Both ask first.

### Branch: If nobody else has joined

1. `SCREEN` `NEW` **Delete before anyone joins** - While nobody else is on the app, the owner can delete the group without settling first.

### Branch: Leave or delete asks first

- `SCREEN` `NEW` **Leave Flat 302?** - You can come back only if someone adds you again. Leave group or Stay.
- `SCREEN` `NEW` **Delete Flat 302 for everyone?** - Everyone loses its expenses and balances. Delete group or Keep it.

### Branch: If it is a split with one friend

1. `SCREEN` `NEW` **Group page (one friend)** - Titled with the friend's name: 'Rahul owes you'. One balance row.
2. `SCREEN` `NEW` **Group settings (one friend)** - Only People and Leave group.

### Branch: When everyone is settled

- `SCREEN` `NEW` **Group page (all settled)** - Neutral card, all rows settled, no Settle up.
- `SCREEN` `NEW` **Groups (all settled)** - Overall all settled, ₹0.

### Branch: To see a friend's expense

1. `SCREEN` `NEW` **Expense detail (friend's expense)** - Tap a This month row on the Group page. 'From Flat 302', who paid, your share.

### Branch: To turn off a friend's link

1. `SCREEN` `NEW` **Friend's link** - Tap the friend in Group settings. Turn off link stops it working.
2. `OUTCOME` `NEW` **Link off** - Remind makes a new link when needed.

### Branch: If a friend has no link from this phone

1. `SCREEN` `NEW` **No link from this phone** - Tap a friend without the app in Group settings. Turn off link still stops a link sent from another phone.
2. `SCREEN` `NEW` **Remind on WhatsApp** - Remind Priya makes the link and sends it with what they owe. Off, and the note says why, while they owe nothing.

### Branch: If invited by link and has the app

1. `SCREEN` `NEW` **Join group** - Shows the group and what they owe before joining.
2. `OUTCOME` `NEW` **Group page** - They see expenses and balances.

### Branch: If the group cannot be loaded

1. `SCREEN` `NEW` **Join group (loading)** - Grey placeholders, and Not now.
2. `SCREEN` `NEW` **Could not load this group** - Try again, or Not now.

### Branch: If the place is owed money

1. `SCREEN` `NEW` **Join group (owed)** - 'Flat 302 owes you ₹420 so far'.

### Branch: If the link is for a split with one friend

1. `SCREEN` `NEW` **Join group (one friend)** - 'Rahul added you to a split'. The card is titled with the friend's name.

### Branch: If nothing is owed yet

1. `SCREEN` `NEW` **Join group (nothing owed)** - 'You owe nothing so far'. Nothing is split with them yet.

### Branch: If the group has more people

1. `SCREEN` `NEW` **Join group (four others)** - Up to four others are all named, the sender first. Five or more: three names, then 'you and 2 more'.

### Branch: If invited by link and not signed in

1. `SCREEN` `NEW` **Sign in first** - Sign in to join a group. Your SMS stay on this phone.
2. `OUTCOME` `NEW` **Back to Join group** - Join group opens again after sign-in.

### Branch: If the link no longer works

1. `SCREEN` `NEW` **Link no longer works** - Turned off, used already, or too old. Ask for a new link.

### Branch: When someone joins by link

1. `SCREEN` `NEW` **Joined banner** - For 7 days the owner sees "Priya joined · Not Priya? Undo" on the group.
2. `DECISION` `NEW` **Right person?** - If not, the owner taps Undo.
3. `OUTCOME` `NEW` **Join undone** - Priya is back as not on the app, and that link is off.

### Branch: If you owe the group

1. `SCREEN` `NEW` **You owe the group** - Header reads "You owe Goa trip". Someone who only settles with others shows ₹0 and "settles with others".
2. `SCREEN` `NEW` **Overall you owe** - Groups shows "Overall you owe" when your total is below zero.

### Branch: When the Groups tab loads

1. `SCREEN` `NEW` **Groups (loading)** - After sign-in, grey placeholders until the server has answered.
2. `DECISION` `NEW` **Did the groups load?** - Yes: the groups, or No groups yet. No, with nothing to list: Could not load.
3. `SCREEN` `NEW` **Groups (could not load)** - Also when a split with one friend has not come in yet. Try again, opening the tab or coming back online loads again.

### Branch: If there are no groups yet

1. `SCREEN` `V3` **Groups (empty)** - Offers New group. Only after the server answered with no groups, or when every split with one friend here is settled.

### Branch: If there are only splits with friends

1. `SCREEN` `NEW` **Groups (friends only)** - No group yet, only splits with one friend. The card counts friends: 'across 2 friends'.

### Branch: If the file is not a Splitwise export

1. `SCREEN` `NEW` **Wrong file** - Says so and asks for another file.

### Branch: If some expenses are not in rupees

1. `SCREEN` `NEW` **Other currencies stay out** - The file's rupee expenses come across. A note says how many stay out.

### Branch: If the file cannot come across

1. `SCREEN` `NEW` **Cannot import** - It does not add up (changed after export), it is too big, it has nothing in rupees, or only one person. Choose another file.

### Branch: If the file came across before

1. `SCREEN` `NEW` **Already here** - Open the group, or choose a different file.

### Branch: If offline while importing

1. `SCREEN` `NEW` **Importing (offline)** - The group is already on this phone. It finishes sending when you are back online.

### Branch: If it could not be saved

1. `OUTCOME` `NEW` **Did not come across** - The group is removed everywhere. Export again and try once more.

## 05 Settle up

Clearing what people owe each other, without marking anything paid.

### Main path

1. `SCREEN` **Open a balance** - "You owe Rahul ₹1,200"
2. `DECISION` **How to pay?**
3. `SCREEN` **Pay by UPI** - Opens the user's own UPI app with payee and amount filled in.
4. `AUTOMATIC` **Payment SMS detected** - Matched to the settlement automatically.
5. `OUTCOME` **Balance cleared** - Both people are notified.

### Branch: If there is no UPI app

1. `SCREEN` `NEW` **No UPI app on this phone** - Paid another way is chosen. Pay another way, then mark it as paid.
2. `SCREEN` `NEW` **Mark as paid** - For cash or other methods.

### Branch: If paid outside UPI

1. `SCREEN` **Mark as paid** - For cash or other methods. The other person can confirm.

### Branch: If the friend is not on the app

- `SCREEN` `NEW` **Settle up (friend not on the app)** - Paid another way says only 'Mark it as paid.'
- `SCREEN` `NEW` **Mark as paid (friend not on the app)** - No can confirm row.

### Branch: If they have no UPI ID

1. `SCREEN` `NEW` **No UPI ID** - Pay by UPI is not offered. Ask them how to pay, then Mark as paid.
2. `SCREEN` `NEW` **Ask how to pay** - The friend's page shows 'Ask Arjun how to pay' instead of a Pay button.

### Branch: When a friend pays you

1. `AUTOMATIC` `NEW` **Money-received SMS** - Credit alert from the bank.
2. `AUTOMATIC` `NEW` **Match to a balance** - Amount and sender compared with what each friend owes.
3. `DECISION` `V3` **Sure of the match?** - Clear automatically if exact. If unsure, a notification asks 'Was this Rahul paying you back?'.
4. `OUTCOME` `NEW` **Balance cleared** - No one had to mark it paid.

### Branch: If it no longer matches

1. `SCREEN` `NEW` **Nothing to clear** - The balance was settled meanwhile or the payment is gone. Go back.

### Branch: If a friend pays you in cash

1. `SCREEN` `NEW` **Mark as paid** - Pick cash or bank transfer. The friend can confirm.
2. `OUTCOME` `NEW` **Balance cleared** - Same end as a matched SMS.

## 06 Card bill view

For people who pay for the group on their credit card.

### Main path

1. `AUTOMATIC` `NEW` **Card spend detected** - Credit card alert, tagged to that card.
2. `SCREEN` `NEW` **Split as usual** - Through lane 03.
3. `AUTOMATIC` `NEW` **Bill SMS detected** - Statement amount and due date read from the card issuer's message.
4. `OUTCOME` `NEW` **Card page** - "Bill of ₹24,300 due in 5 days. Friends owe you ₹8,200 of it."
5. `SCREEN` `NEW` **Remind everyone** - One tap reminds each friend who owes part: a push on the app, or WhatsApp with their pay link.
6. `OUTCOME` `NEW` **Paid back before the due date**

### Branch: If reminders are switched on

1. `AUTOMATIC` `NEW` **Automatic reminders** - A notification to you 3 days before the due date. You choose whether to remind friends.

### Branch: Where it shows

1. `SCREEN` `NEW` **Home row** - Each card's latest bill until it is paid. Tap for the card page.

### Branch: Other states

- `SCREEN` `NEW` **Nobody owes part**
- `SCREEN` `NEW` **Signed out**
- `SCREEN` `NEW` **Past due**
- `SCREEN` `NEW` **Paid** - The card's payment SMS closes it.
- `SCREEN` `NEW` **Reminder sent**
- `SCREEN` `NEW` **No reminder sent**
- `SCREEN` `NEW` **Offline**

## 07 Free and Plus

Everything designed so far is free. Plus will be a set of niche extras, still to be chosen. Plus is not in version 1; it ships as a later update.

### Free, always

- `FREE` `NEW` **Automatic capture** - Unlimited expenses from SMS.
- `FREE` `NEW` **Splitting and groups** - Unlimited groups, friends and split types. One-tap suggestions.
- `FREE` `NEW` **Settling up** - UPI hand-off, repayment detection, friends without the app.
- `FREE` `NEW` **Splitwise import** - So a whole group can switch.
- `FREE` `NEW` **Card bill view** - Bill amount, due date and what friends owe of it.
- `FREE` `NEW` **Automatic reminders** - Before a card due date.

### (blank label)

This row has a blank label in Figma (layer name "Branch:  "). It is the row directly below "Free, always".

- `FREE` `NEW` **Receipt scan** - Photo of a bill, split item by item.
- `FREE` `NEW` **Insights and budgets** - Category trends, monthly budgets, yearly summary.
- `FREE` `NEW` **Export** - Excel and PDF reports for a group, a trip or a year.
- `FREE` `NEW` **Foreign trips** - Other currencies with automatic conversion.
- `FREE` `NEW` **Bank linking (later)** - Account Aggregator, lane 08. Each statement fetch costs money to provide.
- `FREE` `V3` **Family month** - A shared monthly total for a couple or family.

### Plus (paid)

- `TO DECIDE` `NEW` **Plus features** - Niche extras to be chosen. Nothing above moves behind the paywall.

### Branch: Where the paywall shows

1. `SCREEN` `NEW` **Tap a Plus feature** - Marked with a small Plus label wherever it appears.
2. `SCREEN` `NEW` **Plus screen** - What it adds, the price, and a free trial.
3. `DECISION` `NEW` **Subscribe?** - Paid through Google Play.
4. `OUTCOME` `NEW` **Unlocked** - On every phone signed in to the account.

### Question

- `TO DECIDE` `NEW` **Price and trial length** - Depends on which Plus features are chosen.

## 08 Later: bank linking

Account Aggregator, for iPhone and full statement history.

### Main path

1. `LATER` **Link a bank** - User picks their bank inside the app.
2. `LATER` **Give consent** - OTP approval through a licensed Account Aggregator.
3. `LATER` **Fetch statements** - Pulled on a schedule, not instantly.
4. `LATER` **Same path as lane 02** - Joins at "Is it a spend?" and continues unchanged.

## 09 Account and data

Name, sign out, and deleting everything.

### Main path

1. `SCREEN` `V3` **Account** - Name, optional phone number (not checked) and optional UPI ID. Signed in with Google. Opened from You.
2. `DECISION` **Sign out or delete?** - Two separate choices.
3. `OUTCOME` `NEW` **Signed out** - Groups stay. Expenses on this phone are kept.

### Branch: If delete

1. `SCREEN` `V3` **Confirm delete** - Says what happens to groups, and that it cannot be undone.
2. `AUTOMATIC` `V3` **Remove account and data** - Account and this phone's data. Groups only they use are deleted. In shared groups they become "Former member".
3. `OUTCOME` `NEW` **Back to Welcome** - The app is as new.

### Branch: If changes are not sent yet

1. `SCREEN` `NEW` **Sign out anyway?** - Says how many changes are not sent. Signing out now loses them.

### Branch: If the UPI ID is not valid

1. `SCREEN` `NEW` **UPI ID not valid** - Says so under the field. Nothing is saved until it is fixed.

## 10 When things go wrong

No connection, slow loading and failed loading.

### Main path

1. `SCREEN` `NEW` **Offline banner** - Own expenses still work. Splits wait and sync when back online.
2. `SCREEN` `NEW` **Loading** - Grey placeholders while groups and balances load.
3. `SCREEN` `NEW` **Could not load** - Says nothing is lost and offers Try again.

### Branch: Online-only actions

1. `SCREEN` `NEW` **You're offline (actions)** - Join, leave, delete, undo, remind and save profile say You're offline at the top. Group page, Group settings, Join group, Set up Family, Join Family, Family settings, Your name, Account, Remind (no link line).

### Branch: Loading a group

1. `SCREEN` `NEW` **Group page (loading)** - The group name, then grey placeholders.

### Branch: If the server refuses a change

1. `SCREEN` `NEW` **Change not saved** - The row says Not saved and why. Try again, or Dismiss to put it back as it was.

### Branch: Other refused changes

- `SCREEN` `NEW` **Edit not saved** - Your edit was not saved. Try again or Dismiss.
- `SCREEN` `V3` **Delete not saved** - Not deleted. Try again or Dismiss.
- `SCREEN` `NEW` **Dismiss only** - An add, an edit or a delete that would be refused again. The note says why: someone in it has left, or it cannot be tried again.
- `SCREEN` `NEW` **Could not do that now** - A banner when the server refuses an action for a reason the screen does not name. Same screens as offline.

### Branch: If a field is not right

1. `SCREEN` `NEW` **Field errors** - 'That does not look right.' under the field: Add friend, Group settings (blank name), Import from Splitwise (blank group name), Set up Family (blank name), Your name, Account, Needs review, Expense detail edit. Add expense shows it under the amount.

## 11 Website (sumbuddy.akhilux.design)

Not part of the app. The public site, designed on the Figma page "Website" (`screens/07-website/`), in the app's design
system; it follows the phone or computer's light or dark setting.

- **Home** (`/`): nav (Privacy, Source code, Download), hero, How it works, What SumBuddy does, A look inside (the eight
  store screens), Private by default, Get SumBuddy (APK, Obtainium, Orion Store, Installing an APK safely), footer.
- **Privacy policy** (`/PRIVACY.html`): `PRIVACY.md` with the same nav and footer, the "In short:" box and an "On this
  page" list of the sections.
- Every "Download" goes to `github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk`; "Privacy" and
  "Read the privacy policy" go to `/PRIVACY.html`, which Google's approved Branding uses.
- Keyboard: "Skip to content" appears on the first Tab (`04-skip-link-focus`).
