package com.expenses.parser

import org.junit.Assert.fail
import org.junit.Test

/**
 * Cases for [SkipReason.REWARD_POINTS], the card bill rules and the listed wallets, not used in [LoyaltyPoints1Test] or
 * [LoyaltyPoints2Test]. Every message is synthetic: account digits, references and amounts are invented, and no
 * message prints a phone number. A comment "was ..." says how the parser read a message before the points rule was
 * added.
 *
 * - [realMoneyStaysAPayment], [realMoneyIsNeverRemovedByTheCleanUp]: bank, card, UPI and wallet messages that mention
 *   points, coins, vouchers, gift cards, loyalty or UPI footers and move real money.
 * - [storeBalancesAreSkipped]: store and brand points, loyalty, store wallet, voucher and credit note messages.
 * - [moneyInWithNoStoreBalanceReadsAsBefore]: money in from a store with no evidence that it is points or the store's
 *   own balance, read as before the points rule.
 * - [cardBillsMentioningPointsStayCardBills]: statements and reminders that print reward points.
 * - [cleanUpGuards]: what the one-off clean-up may remove (money in that no bank sent, skipped by the points rule).
 * - The `regression...` tests hold real money or card bills that the parser read right before the points rule and an
 *   earlier version of the rule skipped or read wrong. [Banks] and the card bill rules are as before (plus
 *   points-aware reminders), the points rule never skips a debit, and more real wallets and account forms count for
 *   money in.
 *
 * The rule: a message is skipped as points only when it is money in from a sender that is not a bank and its amount is
 * labelled as points right after it, or it prints no money amount and only points move. Cases the rule does not skip
 * are in [PointsAcceptedLimitsTest].
 */
class LoyaltyPoints3Test {
    private val parser = SmsParser()

    private sealed interface Expect
    private data class Pays(val direction: Direction, val amountMinor: Long) : Expect
    /** Not a payment. [reason] null accepts any skip reason. */
    private data class Skips(val reason: SkipReason? = SkipReason.REWARD_POINTS) : Expect
    private data class Bill(val totalDueMinor: Long) : Expect

    private data class Case(val id: String, val sender: String, val body: String, val expect: Expect)

    private fun pays(id: String, sender: String, body: String, direction: Direction, amountMinor: Long) =
        Case(id, sender, body, Pays(direction, amountMinor))

    private fun skips(id: String, sender: String, body: String, reason: SkipReason? = SkipReason.REWARD_POINTS) =
        Case(id, sender, body, Skips(reason))

    private fun bill(id: String, sender: String, body: String, totalDueMinor: Long) = Case(id, sender, body, Bill(totalDueMinor))

    private fun sms(case: Case) = SmsMessage(case.sender, case.body, NOV_16)

    private fun check(cases: List<Case>) {
        val ids = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (ids.isNotEmpty()) fail("duplicate ids $ids")
        val failures = cases.mapNotNull { case ->
            val result = parser.parse(sms(case))
            val ok = when (val expect = case.expect) {
                is Pays -> result is ParseResult.Payment && result.payment.direction == expect.direction &&
                    result.payment.amountMinor == expect.amountMinor
                is Skips -> result is ParseResult.Skipped && (expect.reason == null || result.reason == expect.reason)
                is Bill -> result is ParseResult.CardBill && result.bill.totalDueMinor == expect.totalDueMinor
            }
            if (ok) null else "  ${case.id}: expected ${case.expect}, actual ${describe(result)}"
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} failed\n" + failures.joinToString("\n"))
    }

    /**
     * The clean-up's tests 1 to 3 (ParserRulesCleanup in the app): no bank
     * sent it, the parser now skips it as [SkipReason.REWARD_POINTS], and it still reads as money in. Never a debit.
     */
    private fun removable(sms: SmsMessage): Boolean {
        val result = parser.parse(sms)
        return !parser.isFromBank(sms) && result is ParseResult.Skipped && result.reason in REMOVING_REASONS &&
            parser.readIgnoringSkips(sms)?.direction == Direction.CREDIT
    }

    /**
     * A real payment stored by an older version and left untouched must never be removed: the clean-up removes a row
     * when [removable] holds and the message still reads as the row's amount and direction.
     */
    private fun checkNotRemovable(cases: List<Case>) {
        val bad = cases.filter { case ->
            val expect = case.expect as Pays
            val read = parser.readIgnoringSkips(sms(case))
            removable(sms(case)) && read != null && read.direction == expect.direction && read.amountMinor == expect.amountMinor
        }
        if (bad.isNotEmpty()) {
            fail("${bad.size} real payments would be removed by the clean-up if stored and untouched\n" + bad.joinToString("\n") { "  ${it.id}" })
        }
    }

    private fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Payment -> "payment(${result.payment.direction} ${result.payment.amountMinor})"
        is ParseResult.CardBill -> "cardBill(${result.bill.totalDueMinor})"
        is ParseResult.Skipped -> "skipped(${result.reason})"
        is ParseResult.Unreadable -> "unreadable(${result.hint})"
    }

    // ---- Real money that mentions points, coins, vouchers, gift cards, loyalty or UPI ----

    private val listedSenderMoney = listOf(
        pays(
            "keep3-hdfc-card-petro-point-points-earned", "VM-HDFCBK",
            "Rs.2,340.00 spent on HDFC Bank Card x4521 at PETRO POINT FUELS on 2026-11-16:10:12:33. 47 Reward Points earned. Not you? Call the number on your card.",
            Direction.DEBIT, 234000,
        ),
        pays(
            "keep3-icici-credit-from-loyalty-company", "AX-ICICIB",
            "Dear Customer, Acct XX345 is credited with Rs 1,250.00 on 16-Nov-26 from ACME LOYALTY SOLUTIONS. UPI:632099887766-ICICI Bank.",
            Direction.CREDIT, 125000,
        ),
        pays(
            "keep3-sbicard-spend-points-will-be-credited", "AX-SBICRD",
            "Rs.3,999 spent on your SBI Credit Card ending 4521 at CROMA on 16/11/26. 80 reward points will be credited in your next statement.",
            Direction.DEBIT, 399900,
        ),
        pays(
            "keep3-axis-multiline-gift-cards-merchant", "VM-AXISBK",
            "INR 1,000.00 debited\nA/c no. XX2231\n16-11-26, 14:22:10\nUPI/P2M/632011223344/QWIKCILVER GIFT CARDS\nNot you? Call the number on your card - Axis Bank",
            Direction.DEBIT, 100000,
        ),
        pays(
            "keep3-kotak-upi-to-wallet-vpa", "VM-KOTAKB",
            "Sent Rs.450.00 from Kotak Bank AC X4521 to bigbasket.wallet@hdfcbank on 16-11-26.UPI Ref 632055667788. Not you, visit kotak.com/fraud",
            Direction.DEBIT, 45000,
        ),
        pays(
            "keep3-indusind-card-amazon-pay-gift-card", "VM-INDUSB",
            "INR 5,000.00 spent on your IndusInd Bank Credit Card XX7788 at AMAZON PAY GIFT CARD on 16-11-2026. Avl Lmt INR 95,000.00",
            Direction.DEBIT, 500000,
        ),
        pays(
            "keep3-yes-cashback-for-points-redemption", "VM-YESBNK",
            "INR 300.00 credited to your YES BANK A/c XX5566 on 16-11-26 towards cashback for Reward Points redemption. Avl Bal INR 8,300.00",
            Direction.CREDIT, 30000,
        ),
        pays(
            "keep3-paytm-wallet-received-cashback-points", "VM-PAYTMB",
            "Rs 200 received in your Paytm Wallet from Rahul K. Updated Balance Rs 450. You also earned 20 cashback points.",
            Direction.CREDIT, 20000,
        ),
        pays(
            "keep3-mobikwik-wallet-paid-supercash-points", "VM-MOBIKW",
            "Rs.349 paid to Swiggy from your MobiKwik Wallet. 10 SuperCash points credited. Balance: Rs.651",
            Direction.DEBIT, 34900,
        ),
        pays(
            "keep3-bajajpay-wallet-paid-coins", "VM-BAJAJP",
            "You have paid Rs 1,200.00 to RELIANCE DIGITAL using Bajaj Pay Wallet. You earned 120 Bajaj Coins. Txn ID BP123456789.",
            Direction.DEBIT, 120000,
        ),
        pays(
            "keep3-amazonpay-balance-used-points", "AX-AMZPAY",
            "Rs.500 Amazon Pay balance used for your order 408-1234567-1234567. You earned 25 Amazon Pay reward points.",
            Direction.DEBIT, 50000,
        ),
        pays(
            "keep3-olamoney-wallet-paid-coins", "VM-OLAMNY",
            "Rs 120 paid to Ola for your ride from Ola Money wallet. You earned 12 Ola coins.",
            Direction.DEBIT, 12000,
        ),
        pays(
            "keep3-jiomoney-wallet-recharge-coins", "VM-JIOMNY",
            "Rs 299 paid for Jio recharge from your JioMoney wallet. 30 JioCoins credited.",
            Direction.DEBIT, 29900,
        ),
        pays(
            "keep3-payzapp-wallet-spend-cashpoints", "VM-PAYZAP",
            "Rs.1,500 spent at DECATHLON using PayZapp wallet on 16-11-26. Collect 5X CashPoints on PayZapp.",
            Direction.DEBIT, 150000,
        ),
        pays(
            "keep3-freecharge-cashback-added-points", "VM-FRCHRG",
            "Rs 75 cashback has been added to your Freecharge wallet for your bill payment. Also earned 75 Freecharge points.",
            Direction.CREDIT, 7500,
        ),
        pays(
            "keep3-canara-upi-credit-giftcard-refund", "VM-CANBNK",
            "Your A/c XX4521 credited with Rs.1,000.00 by UPI ref 632099990000 from GIFTCARD REFUND. Bal Rs.5,000. -Canara Bank",
            Direction.CREDIT, 100000,
        ),
        pays(
            "keep3-rbl-card-refund-from-gift-cards", "VM-RBLBNK",
            "Refund of Rs 2,000.00 from WOOHOO GIFT CARDS credited to your RBL Bank Credit Card XX5678 on 16-11-26.",
            Direction.CREDIT, 200000,
        ),
        pays(
            "keep3-hsbc-card-used-points-earned", "AD-HSBCIN",
            "Your HSBC Credit Card ending 4521 has been used for INR 899.00 at CROMA on 16NOV26. You earned 9 reward points.",
            Direction.DEBIT, 89900,
        ),
        pays(
            "keep3-bob-upi-to-points-vpa", "VM-BOBTXN",
            "Rs.320.00 debited from A/c XXXXXX4521 on 16-11-26 to VPA paytmqr.points@paytm. Not you? Call the number on your card -Bank of Baroda",
            Direction.DEBIT, 32000,
        ),
        pays(
            "keep3-paytm-fastag-wallet-toll", "VM-PAYTMB",
            "Rs.85.00 deducted from your Paytm FASTag wallet for toll at Electronic City Plaza on 16-11-26. Avl bal Rs.415.00",
            Direction.DEBIT, 8500,
        ),
        pays(
            "keep3-cred-cashback-to-bank-and-coins", "AD-CREDIN",
            "Rs 150 cashback credited to your bank account for paying your card bill on CRED. You also earned 1,500 CRED coins.",
            Direction.CREDIT, 15000,
        ),
    )

    private val unlistedSenderMoney = listOf(
        pays(
            "keep3-unlisted-bank-dash-tmb-voucher-purchase", "VM-TMBLTD",
            "Your A/c XX7788 debited for Rs.2,500.00 towards GIFT VOUCHER PURCHASE on 16-11-2026. Avl Bal Rs.10,000.00 -TMB",
            Direction.DEBIT, 250000,
        ),
        pays(
            "keep3-kiwi-credit-card-on-upi-points", "VM-KIWIAP",
            "Paid Rs.250.00 to Swiggy via Kiwi credit card on UPI. Ref 632012340000. You earned 2.5 Kiwi Kash points.",
            Direction.DEBIT, 25000,
        ),
        pays(
            "keep3-supermoney-upi-supercoins", "VM-SUPRMN",
            "Paid Rs 99 to Blinkit using super.money UPI. Cashback of 5 SuperCoins earned.",
            Direction.DEBIT, 9900,
        ),
        pays(
            "keep3-jio-payments-bank-header-cashback", "VM-JIOPBK",
            "Rs 500 credited to your Jio Payments Bank account as cashback. Earn JioCoins on every spend!",
            Direction.CREDIT, 50000,
        ),
        pays(
            "keep3-kvb-dash-signoff-reward-points", "VM-KVBSMS",
            "Your a/c XX4521 is debited for Rs.1,100.00 on 16-11-26 for UPI/SWIGGY. Earn KVB reward points on DLite. - Karur Vysya Bank",
            Direction.DEBIT, 110000,
        ),
        pays(
            "keep3-kotak-unlisted-card-header-reward-offer", "VM-KOTAKC",
            "INR 1,250.00 spent on Kotak Credit Card x4521 at SWIGGY on 16-11-26. Avl Lmt INR 98,750.00. Earn 2X reward points on dining!",
            Direction.DEBIT, 125000,
        ),
        pays(
            "keep3-suryoday-sfb-bonus-reward", "VM-SURYOD",
            "Dear Customer, Rs 500 Bonus credited to your Suryoday SFB account XX4521 as reward for UPI spends.",
            Direction.CREDIT, 50000,
        ),
        pays(
            "keep3-airtel-money-wallet-cashback-points", "VM-AIRTEL",
            "Rs 100 cashback credited to your Airtel Money wallet for your recharge. Collect more Airtel Thanks points.",
            Direction.CREDIT, 10000,
        ),
        pays(
            "keep3-bhim-sent-from-linked-ac", "VM-BHIMUP",
            "You have sent Rs 300 to rahul@oksbi from your A/c linked to BHIM. Ref 632011110000.",
            Direction.DEBIT, 30000,
        ),
        pays(
            "keep3-uni-card-ending-amazon-gift-card", "VM-UNICRD",
            "Rs 2,000 spent on Amazon Gift Card using your Uni Pay 1/3rd card ending 4521.",
            Direction.DEBIT, 200000,
        ),
        pays(
            "keep3-sbm-niyo-card-ending-gift-voucher", "VM-SBMNIY",
            "INR 1,000.00 spent on your SBM Niyo card ending 4521 at AMAZON GIFT VOUCHER on 16-11-26.",
            Direction.DEBIT, 100000,
        ),
        pays(
            "keep3-fastag-bank-signoff", "VM-HDFCFT",
            "Rs.85.00 deducted from your FASTag wallet for toll at Electronic City Plaza on 16-11-26. Avl bal Rs.415.00 - HDFC Bank FASTag",
            Direction.DEBIT, 8500,
        ),
        pays(
            "keep3-sfb-signoff-before-safety-footer", "VM-EQUTAS",
            "INR 1,200.00 debited on 16-11-26 towards POS/SWIGGY. Earn reward points on every spend -Equitas SFB. Never share your OTP with anyone.",
            Direction.DEBIT, 120000,
        ),
    )

    @Test
    fun realMoneyStaysAPayment() = check(listedSenderMoney + unlistedSenderMoney)

    @Test
    fun realMoneyIsNeverRemovedByTheCleanUp() = checkNotRemovable(listedSenderMoney + unlistedSenderMoney)

    // ---- Store and points messages ----

    @Test
    fun storeBalancesAreSkipped() = check(
        listOf(
            skips("skip3-credits-added-to-store-wallet", "VM-ZOMATO", "Rs 50 Zomato Credits added to your Zomato wallet for your cancelled order.", reason = null),
            skips("skip3-rs-neucoins-purchase-upi", "JD-TATNEU", "Rs 120 NeuCoins credited to your Tata Neu account for your purchase via UPI."),
            skips(
                "skip3-voucher-credited-booking-paid-card-ending", "VM-BMSHOW",
                "Rs 150 BookMyShow voucher credited for your booking paid with card ending 4521.",
            ),
        ),
    )

    /**
     * Money in from a store with no evidence that the money is points or the store's own balance: a store's account,
     * "Swiggy Money", "ShopClub A/c", and cashback credited "as SuperCoins" (not read as points; see
     * [gapPointsBesidePurchaseOrCashbackWords]). An earlier version skipped these as not from a bank; they read as
     * before the points rule (money in, never counted as spending).
     */
    @Test
    fun moneyInWithNoStoreBalanceReadsAsBefore() = check(
        listOf(
            pays("skip3-store-money-no-bank-words", "VM-SWIGGY", "Rs 30 Swiggy Money credited to your account as refund for order 1234.", Direction.CREDIT, 3_000),
            pays("skip3-store-account-no-bank-words", "VM-CROMAR", "Rs 200 credited to your Croma account.", Direction.CREDIT, 20_000),
            pays(
                "skip3-cashback-as-supercoins-no-bank-words", "VM-FLPKRT",
                "Rs 50 cashback credited as SuperCoins to your Flipkart account.", Direction.CREDIT, 5_000,
            ),
            pays(
                "skip3-store-loyalty-a-c-update", "VM-SHPCLB",
                "An update has been made to your ShopClub A/c XXXXXX7731. Rs.200 has been credited.", Direction.CREDIT, 20_000,
            ),
        ),
    )

    // ---- Card bills that print reward points ----

    @Test
    fun cardBillsMentioningPointsStayCardBills() = check(
        listOf(
            bill(
                "bill3-hdfc-statement-points-earned", "VM-HDFCBK",
                "HDFC Bank Credit Card XX4521 statement: Total due Rs 23,456.00, Min due Rs 1,200.00, due by 05-12-2026. Reward Points earned this cycle: 456.",
                2345600,
            ),
            bill(
                "bill3-sbicard-reminder-ignore-if-already-paid", "AX-SBICRD",
                "Payment of Rs 8,765 for your SBI Credit Card ending 6789 is due on 04-Dec-26. Ignore if already paid.",
                876500,
            ),
            // was skipped(UPCOMING): the points line said "credited"
            bill(
                "bill3-sbicard-reminder-points-credited-cycle", "AX-SBICRD",
                "Payment of Rs 8,765 for your SBI Credit Card ending 6789 is due on 04-Dec-26. 250 reward points credited this cycle.",
                876500,
            ),
            // was skipped(PROMOTION)
            bill(
                "bill3-axis-reminder-pay-now-earn-points", "VM-AXISBK",
                "Your Axis Bank Credit Card XX2231 bill of Rs 15,400.00 is due on 06-12-26. Pay now to earn 2X EDGE Reward Points.",
                1540000,
            ),
            bill(
                "bill3-kotak-statement-points-worth", "VM-KOTAKB",
                "Kotak Credit Card XX7788: Statement generated. Total Amount Due: Rs.9,876.50. Minimum Due: Rs.500. Due date: 03-Dec-26. Reward points balance: 3,450 (worth Rs 862).",
                987650,
            ),
            bill(
                "bill3-amex-statement-membership-rewards", "AD-AMEXIN",
                "Your American Express Card ending 31004 statement: Total due INR 45,000.00, payment due by 10 Dec 2026. You have 12,500 Membership Rewards points.",
                4500000,
            ),
            bill(
                "bill3-idfc-reminder-points-worth", "VM-IDFCFB",
                "Reminder: Rs 6,540 is due on your IDFC FIRST Bank Credit Card XX3321 on 07-Dec-2026. You have 2,100 reward points worth Rs 525 to redeem.",
                654000,
            ),
            bill(
                "bill3-icici-total-points-were-credited", "AX-ICICIB",
                "ICICI Bank Credit Card XX4521: Total amount due Rs 12,000.00 is payable by 08-Dec-26. 1,200 reward points were credited this month.",
                1200000,
            ),
            bill(
                "bill3-onecard-reminder-points-earned", "JD-ONECRD",
                "Your OneCard bill of Rs 4,321 is due on 09-Dec-26. You earned 432 reward points this cycle.",
                432100,
            ),
            // was skipped(PROMOTION)
            bill(
                "bill3-rbl-amount-due-earn-points-tc", "VM-RBLBNK",
                "RBL Bank Credit Card XX5678: Amount due Rs 7,800 by 06-Dec-26. Pay before due date and earn 500 bonus reward points. T&C apply",
                780000,
            ),
            bill(
                "bill3-hdfc-total-last-payment-was-paid", "VM-HDFCBK",
                "HDFC Bank Credit Card XX4521: Total due Rs 9,000, due by 05-Dec-26. Last payment Rs 5,000 was paid on 01-Nov-26.",
                900000,
            ),
        ),
    )

    // ---- The clean-up's guards ----

    /**
     * What the one-off clean-up may remove: money in from a sender that is no bank, which the
     * parser now skips as points or as not from a bank. A bank's message and a debit always stay.
     */
    @Test
    fun cleanUpGuards() {
        // Money in from a store whose amount is labelled as points or a store voucher right after it (the closed rule).
        val shouldRemove = listOf(
            SmsMessage(
                "VM-SHPCLB-S",
                "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points have been credited. Valid until 30th November. *T&C Apply.",
                NOV_16,
            ),
            SmsMessage("VM-DECTLN", "Rs 799 has been credited as credit note to your Decathlon account for your return.", NOV_16),
        )
        val mustKeep = listOf(
            // Store money whose amount is not labelled as points right after it: money in as before the points rule
            // (accepted limit).
            SmsMessage("VM-BIGBSK", "Rs 150 bbcoins credited for your order 98765. 1 bbcoin = Rs 1.", NOV_16),
            SmsMessage("VM-PVRCIN", "Rs 120 has been credited to your PVR Passport wallet as refund for booking paid via UPI.", NOV_16),
            SmsMessage("VM-LFSTYL", "Rs 1,299 refund has been processed as a credit note against your card payment for bill 7788.", NOV_16),
            // A bank's points-only message: skipped as points, but a bank sent it.
            SmsMessage("VM-HDFCBK", "Dear Customer, 2,000 bonus Reward Points have been credited to your HDFC Bank Credit Card XX4521 for annual spends.", NOV_16),
            // An unlisted bank found by its sign-off, with a points-only line.
            SmsMessage("VM-KVBSMS", "1,000 reward points have been credited to your account for DLite spends. - Karur Vysya Bank", NOV_16),
            // A bank's points as the amount: read as before the points rule, because a bank sent it.
            SmsMessage("VM-ICICIB", "Rs.200 loyalty points have been credited for your festive spends.", NOV_16),
            // Debits from a store: read as before, never removed.
            SmsMessage("VM-SHPSTP", "First Citizen points worth Rs 300 redeemed against your bill paid by UPI at Shoppers Stop Andheri.", NOV_16),
            SmsMessage("VM-SHPSTP", "Rs 1,500.00 debited from your Shoppers Stop gift card XX9876 at Shoppers Stop Andheri on 16-11-26.", NOV_16),
            // Points with no money amount were Needs review items before, never a stored payment.
            SmsMessage("VM-TAJHTL", "Dear Member, 1,800 NeuPass points credited for your stay paid with card ending 4521.", NOV_16),
            // Money in to a bank account from a sender that is no bank: still a payment.
            SmsMessage("VM-ACMEHR", "Your refund of Rs 1,200.00 has been credited to your bank account ending 4521.", NOV_16),
            // Money in from a store with no store balance named: a payment as before the points rule.
            SmsMessage("VM-CROMAR", "Rs 200 credited to your Croma account.", NOV_16),
        )
        val problems = shouldRemove.filterNot(::removable).map { "  not removable: ${it.sender} ${it.body}" } +
            mustKeep.filter(::removable).map { "  removable: ${it.sender} ${it.body}" }
        // A removed row must still read as what was stored, or test 3 of the clean-up never matches it.
        val unreadable = shouldRemove.filter { parser.readIgnoringSkips(it) == null }
        if (problems.isNotEmpty() || unreadable.isNotEmpty()) {
            fail((problems + unreadable.map { "  readIgnoringSkips null: ${it.body}" }).joinToString("\n"))
        }
    }

    // ---- Regressions: real money or card bills read right before this change, now skipped or wrong ----

    /**
     * Card payment-due reminders that close with "ignore if ... has been paid / was paid / paid successfully", or say
     * the last bill was paid: an earlier version made [CardBills] give up on any "has been paid", "was paid" or "paid
     * successfully", so the bill was lost and the reminder became UPCOMING. Each one was a card bill before the points
     * rule, and is again: the card bill rules are as they were before the points rule, and only a points count ("250
     * reward points credited") no longer cancels a reminder.
     */
    @Test
    fun regressionCardRemindersWithAPaidFooter() = check(
        listOf(
            bill(
                "reg3-bill-ignore-if-amount-has-been-paid", "AX-SBICRD",
                "Payment of Rs 8,765 for your SBI Credit Card ending 6789 is due on 04-Dec-26. Please ignore if the amount has been paid.",
                876500,
            ),
            bill(
                "reg3-bill-ignore-if-bill-was-paid", "AX-SBICRD",
                "Your SBI Card ending 6789 bill of Rs 3,210 is due on 04-Dec-26. Ignore if the bill was paid.",
                321000,
            ),
            bill(
                "reg3-bill-ignore-if-paid-successfully", "VM-AXISBK",
                "Your Axis Bank Credit Card XX2231 payment of Rs 4,000 is due on 06-12-26. Ignore if paid successfully.",
                400000,
            ),
            bill(
                "reg3-bill-minimum-due-ignore-if-has-been-paid", "AX-ICICIB",
                "Minimum amount due of Rs 1,500 on your ICICI Bank Credit Card XX4521 is due on 08-Dec-26. Please ignore if payment has been paid already.",
                150000,
            ),
            bill(
                "reg3-bill-last-bill-was-paid", "VM-AXISBK",
                "Payment of Rs 4,000 for your Axis Bank Credit Card XX2231 is due on 06-12-26. Your last bill was paid on 05-11-26.",
                400000,
            ),
            bill(
                "reg3-bill-part-paid-remaining-due", "VM-HDFCBK",
                "Rs 2,000 was paid towards your HDFC Bank Credit Card XX4521. Remaining amount of Rs 3,000 is due on 05-Dec-26.",
                300000,
            ),
        ),
    )

    /**
     * Real prepaid wallets read as a store's wallet: the "<word> wallet" branch of the store balance words takes any
     * brand ("FASTag wallet", "Pluxee meal wallet", "Pockets wallet", "YES PAY wallet", "Bajaj Finserv Wallet"), so the
     * money was a store's balance, which the clean-up removed (an earlier version). Each was a payment before the
     * points rule and is again: debits are never skipped by the new rules, and these wallets are on the list of real
     * wallets for money in.
     */
    private val realWalletsReadAsStoreWallets = listOf(
        pays(
            "reg3-fastag-wallet-toll-unlisted-header", "VM-FSTAGP",
            "Rs.85.00 deducted from your FASTag wallet for toll at Electronic City Plaza, vehicle KA01AB1234 on 16-11-26. Avl bal Rs.415.00",
            Direction.DEBIT, 8500,
        ),
        pays(
            "reg3-fastag-wallet-bank-name-without-bank", "VM-ICIFTG",
            "Rs.85.00 deducted from your ICICI FASTag wallet for toll at Electronic City Plaza on 16-11-26. Avl bal Rs.415.00",
            Direction.DEBIT, 8500,
        ),
        pays(
            "reg3-meal-wallet-spend", "VM-PLUXEE",
            "Rs.180.00 debited from your Pluxee meal wallet for ZOMATO on 16-11-26. Bal Rs.2,020.00. Collect Pluxee points on every spend.",
            Direction.DEBIT, 18000,
        ),
        pays(
            "reg3-meal-wallet-employer-load", "VM-PLUXEE",
            "Rs.2,200.00 has been credited to your Pluxee meal wallet by ACME CORP on 01-11-26.",
            Direction.CREDIT, 220000,
        ),
        pays(
            "reg3-meal-wallet-spent-from", "VM-ZETAAP",
            "Rs 250 spent from your Zeta meal wallet at CANTEEN on 16-11-26.",
            Direction.DEBIT, 25000,
        ),
        pays(
            "reg3-bank-app-wallet-pockets", "VM-POCKTS",
            "Rs 200 debited from your Pockets wallet for payment to Swiggy on 16-11-26. Avl bal Rs 300.",
            Direction.DEBIT, 20000,
        ),
        pays(
            "reg3-bank-app-wallet-yes-pay", "VM-YESPAY",
            "Rs 300 debited from your YES PAY wallet to Swiggy on 16-11-26. Bal Rs 700.",
            Direction.DEBIT, 30000,
        ),
        pays(
            "reg3-finserv-wallet", "VM-BAJFIN",
            "Rs 500 debited from your Bajaj Finserv Wallet for payment to Swiggy. Bal Rs 1,000.",
            Direction.DEBIT, 50000,
        ),
    )

    @Test
    fun regressionRealWalletsReadAsStoreWallets() = check(realWalletsReadAsStoreWallets)

    @Test
    fun regressionRealWalletsRemovedByTheCleanUp() = checkNotRemovable(realWalletsReadAsStoreWallets)

    /**
     * Banks the parser does not know as banks (header code does not read as a bank's, no bank sign-off), whose sentence
     * with the amount names the account in a form [RealMoney] does not read: "account no. 1234XXXX5678", "Ac No
     * 0123XXXX4521", "SB account ending 4521", "ac 4521", or the account in the next sentence, or a sign-off eaten by
     * the safety footer. Each was a payment before the points rule, NOT_FROM_BANK in an earlier version, and is a
     * payment again: debits are never skipped by the new rules, and money in reads these account forms.
     */
    @Test
    fun regressionUnlistedBanksNotRecognised() = check(
        listOf(
            pays(
                "reg3-coop-account-no-digits-first-mask", "VM-SARSWT",
                "Your account no. 1234XXXX5678 is credited with Rs 5,000.00 on 16-11-26 towards interest. Earn reward points with our Debit Card!",
                Direction.CREDIT, 500000,
            ),
            pays(
                "reg3-coop-ac-no-card-load", "VM-NKGSBB",
                "Dear Customer, Rs 2,000.00 has been debited from your Ac No 0123XXXX4521 on 16/11/26 towards PLUXEE CARD LOAD. Thank you for banking with us.",
                Direction.DEBIT, 200000,
            ),
            pays(
                "reg3-coop-ac-no-gift-voucher", "VM-NKGSBB",
                "Rs 1,500 debited from your Ac No 0123XXXX4521 towards GIFT VOUCHER on 16/11/26.",
                Direction.DEBIT, 150000,
            ),
            pays(
                "reg3-sfb-sb-account-ending", "VM-UJJIVN",
                "Your SB account ending 4521 is credited with Rs.750.00 on 16-11-2026 by cash deposit. Earn rewards on every UPI spend. Team Ujjivan SFB",
                Direction.CREDIT, 75000,
            ),
            pays(
                "reg3-coop-sb-account-ending-gift-card", "VM-SARSWT",
                "Rs 1,500 has been debited from your SB account ending 4521 for Gift Card purchase on 16-11-26.",
                Direction.DEBIT, 150000,
            ),
            pays(
                "reg3-bank-account-in-next-sentence", "VM-SHIVLK",
                "INR 2,500.00 debited on 16-11-26. Your A/c XX4521. Info: UPI/SWIGGY/632011112222.",
                Direction.DEBIT, 250000,
            ),
            pays(
                "reg3-coop-ac-digits-cr-to", "VM-PRMCOB",
                "Sir, Rs.5000 Cr to your ac 4521 on 16/11/26 by transfer. Collect reward points on our card.",
                Direction.CREDIT, 500000,
            ),
            pays(
                "reg3-sfb-signoff-inside-safety-footer", "VM-EQUTAS",
                "Your SB account ending 4521 is debited for INR 1,200.00 on 16-11-26 towards POS/SWIGGY. Never share your OTP or PIN with anyone - Equitas SFB",
                Direction.DEBIT, 120000,
            ),
        ),
    )

    /**
     * Card issuers, prepaid cards and pay-later services the parser does not know, whose SMS is the only record of the
     * spend: a card named by brand only ("your Scapia card", "your Niyo Global card", "your FamPay card", "your Zaggle
     * Prepaid card", "your Pluxee Meal card", "your Uni card"), pay later (LazyPay, Simpl, Flipkart Pay Later) and a
     * FASTag account. Each was a payment before the points rule, NOT_FROM_BANK in an earlier version, and is a payment
     * again: debits are never skipped by the new rules.
     */
    @Test
    fun regressionUnlistedIssuersAndWalletsNotRecognised() = check(
        listOf(
            pays(
                "reg3-issuer-card-by-brand-coins", "VM-SCAPIA",
                "Your Scapia card has been used for INR 1,250.00 at SWIGGY on 16-11-26. Earned 125 Scapia Coins. Avl limit INR 48,750.",
                Direction.DEBIT, 125000,
            ),
            pays(
                "reg3-forex-card-by-brand-points", "VM-NIYOGL",
                "INR 3,200.00 spent on your Niyo Global card at DUBAI MALL on 16-11-26. Rewards: 32 points.",
                Direction.DEBIT, 320000,
            ),
            pays(
                "reg3-prepaid-card-by-brand-coins", "VM-FAMPAY",
                "Rs 150 spent at Zepto using your FamPay card. Balance Rs 850. You earned 15 FamCoins!",
                Direction.DEBIT, 15000,
            ),
            pays(
                "reg3-corporate-prepaid-card", "VM-ZAGGLE",
                "INR 2,000.00 spent on your Zaggle Prepaid card at UBER on 16-11-26. Bal INR 3,000.00",
                Direction.DEBIT, 200000,
            ),
            pays(
                "reg3-meal-card", "VM-PLUXEE",
                "Rs.180.00 has been debited from your Pluxee Meal card at ZOMATO on 16-11-26. Available balance: Rs.2,020.00",
                Direction.DEBIT, 18000,
            ),
            pays(
                "reg3-issuer-card-by-brand-gift-voucher-merchant", "VM-UNICRD",
                "Rs 2,000 spent on your Uni card at MYNTRA GIFT VOUCHER on 16-11-26. Avl limit Rs 48,000.",
                Direction.DEBIT, 200000,
            ),
            pays(
                "reg3-pay-later-lazypay", "VM-LZYPAY",
                "Rs 349.00 paid to Swiggy using LazyPay on 16-11-26. Total due Rs 1,200 payable by 03-Dec-26.",
                Direction.DEBIT, 34900,
            ),
            pays(
                "reg3-pay-later-simpl", "VM-SMPLPY",
                "Rs 220 paid to Zomato via Simpl. Your Simpl bill is due on 01-Dec-26.",
                Direction.DEBIT, 22000,
            ),
            pays(
                "reg3-pay-later-store-brand-supercoins", "VM-FKPAYL",
                "Rs 1,200 spent on Flipkart using Flipkart Pay Later. Due by 05-Dec-26. Earn SuperCoins on every order.",
                Direction.DEBIT, 120000,
            ),
            pays(
                "reg3-fastag-account", "VM-IHMCLF",
                "Rs 65 has been deducted from your FASTag account for toll at XYZ Plaza on 16-11-26.",
                Direction.DEBIT, 6500,
            ),
        ),
    )

    /**
     * An earlier version put MobiKwik, Freecharge, Bajaj Pay, Ola Money, JioMoney and PayZapp in [Banks] (since
     * reverted), so their SMS was a bank copy.
     * When one of these apps reports a UPI payment from the user's bank account, the bank's own SMS and the app's SMS
     * are both bank copies, and two bank copies are one payment only with the same reference: the app's own transaction
     * id differs, so the payment is counted twice ([Duplicates.keepBankCopies]; the app's SmsIngestPipeline uses the
     * same rule). Before that version, and again now, the app's copy is dropped. The app's copy also took the app's
     * name as its bank, so the account became "MobiKwik XX4521" instead of "HDFC Bank XX4521".
     */
    @Test
    fun regressionNewlyListedWalletAppsCountABankPaymentTwice() {
        val bankCopy = SmsMessage(
            "VM-HDFCBK",
            "Rs 500.00 debited from HDFC Bank A/c XX4521 to VPA swiggy@icici on 16-11-26. UPI Ref 632011112222. Not you? Call the number on your card.",
            NOV_16,
        )
        val appCopy = SmsMessage(
            "VM-MOBIKW",
            "Rs 500 paid to Swiggy from your HDFC Bank a/c XX4521 via MobiKwik UPI. Txn ID MBK1234567890.",
            NOV_16 + 20_000,
        )
        val read = listOf(bankCopy, appCopy).map { ReadPayment(it, (parser.parse(it) as ParseResult.Payment).payment) }
        val kept = Duplicates.keepBankCopies(read)
        val problems = buildList {
            if (kept.size != 1) add("  kept ${kept.size} copies of one payment: ${kept.map { it.sms.sender }}")
            if (read[1].payment.bank != Banks.HDFC) add("  the app's copy names bank ${read[1].payment.bank}, not ${Banks.HDFC}")
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private companion object {
        const val NOV_16 = 1_763_280_000_000L

        /** ParserRulesCleanup.REMOVING_REASONS in the app. */
        val REMOVING_REASONS = setOf(SkipReason.REWARD_POINTS)
    }
}
