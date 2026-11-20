package com.expenses.parser

import org.junit.Assert.fail
import org.junit.Test

/**
 * More cases for [SkipReason.REWARD_POINTS], not used in [LoyaltyPoints1Test]. Every message is synthetic: account
 * digits, references and amounts are invented, and no message prints a phone number.
 *
 * - [realMoneyStaysAPayment]: bank, card, UPI and wallet messages (listed banks, small finance, co-op, payments and
 *   rural banks, unlisted issuers and wallets) that mention points, coins, vouchers, gift cards, loyalty, offers or UPI
 *   footers but move real money.
 * - [realMoneyIsNeverRemovedByTheCleanUp]: the same messages, checked against what the one-off clean-up removes
 *   (money in from a sender that is no bank, skipped as REWARD_POINTS, while the message still reads as the stored
 *   amount and direction).
 * - [storeBalancesAreSkipped]: store and brand points, loyalty, store wallet, voucher and credit note messages, with
 *   and without bank or UPI words.
 * - [cardBillsMentioningPointsStayCardBills]: statements and reminders that print reward points.
 * - The `problem...` tests hold cases an earlier version of the rule got wrong, each with the reason, kept as
 *   regression tests. One expectation is the safe outcome (money in), marked where it is.
 *
 * The rule: a message is skipped as points only when it is money in from a sender that is not a bank and its amount is
 * labelled as points right after it, or it prints no money amount and only points move. Cases the rule does not skip
 * are in [PointsAcceptedLimitsTest].
 */
class LoyaltyPoints2Test {
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
     * The clean-up removes a stored money in row when no bank sent the message, it is now skipped as REWARD_POINTS,
     * and it still reads as the row's amount and direction. A real payment
     * must never be in that set.
     */
    private fun checkNotRemovable(cases: List<Case>) {
        val removable = cases.filter { case ->
            val expect = case.expect as Pays
            val result = parser.parse(sms(case))
            val read = parser.readIgnoringSkips(sms(case))
            !parser.isFromBank(sms(case)) && result is ParseResult.Skipped &&
                result.reason == SkipReason.REWARD_POINTS &&
                read != null && read.direction == Direction.CREDIT &&
                read.direction == expect.direction && read.amountMinor == expect.amountMinor
        }
        if (removable.isNotEmpty()) {
            fail(
                "${removable.size} real payments would be removed by the clean-up if stored and untouched\n" +
                    removable.joinToString("\n") { "  ${it.id}" },
            )
        }
    }

    private fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Payment -> "payment(${result.payment.direction} ${result.payment.amountMinor})"
        is ParseResult.CardBill -> "cardBill(${result.bill.totalDueMinor})"
        is ParseResult.Skipped -> "skipped(${result.reason})"
        is ParseResult.Unreadable -> "unreadable(${result.hint})"
    }

    // ---- Real money that mentions points, coins, vouchers, gift cards, loyalty, offers or UPI ----

    private val listedBankMoney = listOf(
        pays(
            "keep2-icici-upi-merchant-chai-point-credited", "AX-ICICIB",
            "ICICI Bank Acct XX012 debited for Rs 60.00 on 16-Nov-26; CHAI POINT credited. UPI:632012345678. Call the number on your card for dispute.",
            Direction.DEBIT, 6000,
        ),
        pays(
            "keep2-sbi-upi-bare-amount-coins-merchant", "VM-SBIUPI",
            "Dear UPI user A/C X4521 debited by 150.0 on date 16Nov26 trf to 3 COINS CAFE Refno 632012345678. If not u? report in YONO. -SBI",
            Direction.DEBIT, 15000,
        ),
        pays(
            "keep2-axis-cashback-and-bonus-points-credited", "VM-AXISBK",
            "Rs 500 cashback and 50 bonus EDGE Reward Points credited to your Axis Bank A/c XX2231 on 16-11-26.",
            Direction.CREDIT, 50000,
        ),
        pays(
            "keep2-hdfc-points-count-before-cashback", "VM-HDFCBK",
            "50 Reward Points and Rs 250.00 cashback credited to your HDFC Bank A/c XX4521 on 16-11-26 for Millennia card spends.",
            Direction.CREDIT, 25000,
        ),
        pays(
            "keep2-sbicard-points-of-rs-statement-credit", "AX-SBICRD",
            "Reward Points of Rs 500.00 have been credited to your SBI Card ending 6789 as statement credit on 16-11-26.",
            Direction.CREDIT, 50000,
        ),
        pays(
            "keep2-federal-salary-fi-coins-offer-footer", "VM-FEDBNK",
            "Rs 45,000.00 credited to your A/c XX1122 by NEFT from ACME PAYROLL on 16-11-26. Earn Fi-Coins on every UPI spend! -Federal Bank",
            Direction.CREDIT, 4500000,
        ),
        pays(
            "keep2-yes-gift-card-purchase-upi", "VM-YESBNK",
            "INR 2,000.00 debited from YES BANK A/c XX5566 for Amazon Pay Gift Card purchase on 16-11-2026 via UPI. Avl Bal INR 12,000.00",
            Direction.DEBIT, 200000,
        ),
        pays(
            "keep2-hdfc-upi-multiline-voucher-store", "VM-HDFCBK",
            "Sent Rs.1,000.00\nFrom HDFC Bank A/C *4521\nTo GIFT VOUCHER STORE\nOn 16/11/26\nRef 632012345678\nNot You? Call the number on your card.",
            Direction.DEBIT, 100000,
        ),
        pays(
            "keep2-icici-card-loyalty-points-cafe", "AX-ICICIB",
            "INR 1,850.00 spent using ICICI Bank Card XX9012 on 16-Nov-26 on LOYALTY POINTS CAFE. Avl Limit: INR 98,150.00. If not you, call the number on your card.",
            Direction.DEBIT, 185000,
        ),
        pays(
            "keep2-paytm-wallet-paid-to-gift-voucher", "VM-PAYTMB",
            "Paid Rs.500 to Myntra Gift Voucher from Paytm Wallet. Txn ID 43123456792. Updated Balance: Paytm Wallet- Rs 100",
            Direction.DEBIT, 50000,
        ),
        pays(
            "keep2-phonepe-wallet-cashback-and-points", "VM-PHONPE",
            "Rs 30 cashback credited to your PhonePe wallet for your payment to Chai Point. You also earned 30 PhonePe reward points.",
            Direction.CREDIT, 3000,
        ),
        pays(
            "keep2-amazonpay-refund-and-points", "AX-AMZPAY",
            "Refund of Rs 449 for your order has been credited to your Amazon Pay balance. You earned 22 Amazon reward points.",
            Direction.CREDIT, 44900,
        ),
        pays(
            "keep2-hdfc-upi-from-coins-vpa", "VM-HDFCBK",
            "Rs. 250.00 credited to HDFC Bank A/c XX4521 on 16-11-26 from VPA supercoins.refund@axisbank (UPI 632012345671)",
            Direction.CREDIT, 25000,
        ),
        pays(
            "keep2-axis-points-worth-credited-to-card", "VM-AXISBK",
            "Dear Customer, your redemption is complete. EDGE Reward Points worth INR 500.00 have been credited to your Axis Bank Credit Card XX2231.",
            Direction.CREDIT, 50000,
        ),
        pays(
            "keep2-bob-upi-debit-points-footer", "VM-BOBTXN",
            "Rs.500.00 debited from A/c ...4567 on 16-11-26 to VPA shop@okhdfc. Earn BoB World reward points on UPI spends. -Bank of Baroda",
            Direction.DEBIT, 50000,
        ),
        pays(
            "keep2-canara-voucher-loyalty-offers-footer", "VM-CANBNK",
            "An amount of INR 1,200.00 has been DEBITED to your account XXX2345 on 16/11/2026 towards gift voucher purchase. Check exciting offers on loyalty points. - Canara Bank",
            Direction.DEBIT, 120000,
        ),
    )

    private val unlistedSenderMoney = listOf(
        pays(
            "keep2-coop-cosmos-gift-voucher-ac-no", "VM-COSMOS",
            "Dear Customer, your A/c No XXXX1234 is debited for Rs.2,000.00 on 16-11-2026 towards purchase of Gift Voucher. Avl Bal Rs.8,000.00",
            Direction.DEBIT, 200000,
        ),
        pays(
            "keep2-sfb-equitas-signoff-voucher", "VM-EQUTAS",
            "Rs.1,000.00 debited from your account on 16-11-26 towards e-Gift Voucher purchase. -Equitas SFB",
            Direction.DEBIT, 100000,
        ),
        pays(
            "keep2-sfb-ujjivan-cashback-points", "VM-UJJIVN",
            "Rs.750.00 cashback credited to your Ujjivan SFB A/c XX1234 on 16-11-26 for debit card spends. 75 reward points also added.",
            Direction.CREDIT, 75000,
        ),
        pays(
            "keep2-sfb-utkarsh-header-card-points", "VM-UTKSFB",
            "INR 1,200.00 spent on your Utkarsh Debit Card XX4321 at CROMA on 16-11-26. You earned 12 reward points.",
            Direction.DEBIT, 120000,
        ),
        pays(
            "keep2-coop-saraswat-signoff-points-redemption", "VM-SARSWT",
            "Your A/c XX7788 is credited with INR 300.00 on 16-11-26 towards reward points redemption. - Saraswat Co-op Bank Ltd",
            Direction.CREDIT, 30000,
        ),
        pays(
            "keep2-payments-bank-fino-upi-points-promo", "VM-FINOPB",
            "Rs 1,500 received in your Fino Payments Bank account from RAHUL via UPI on 16-11-26. Earn Fino Points on every transaction.",
            Direction.CREDIT, 150000,
        ),
        pays(
            "keep2-payments-bank-nsdl-upi-chai-point", "VM-NSDLPB",
            "Rs.250.00 debited from your NSDL Payments Bank A/c XX5678 for UPI payment to CHAI POINT on 16-11-26.",
            Direction.DEBIT, 25000,
        ),
        pays(
            "keep2-rrb-header-bank-points-redemption", "VM-KGBANK",
            "Your A/c XX9988 is credited by Rs.2,500.00 on 16-11-26 (IMPS Ref 632012345670) as cashback for reward points redemption.",
            Direction.CREDIT, 250000,
        ),
        pays(
            "keep2-dbs-bnk-header-rewards-footer", "VM-DBSBNK",
            "INR 750.00 credited to your digibank A/c XX3456 on 16-11-26 as cashback. DBS Rewards points: 120",
            Direction.CREDIT, 75000,
        ),
        pays(
            "keep2-freecharge-wallet-paid-points", "VM-FRCHRG",
            "Rs.99 paid to Airtel Prepaid from your Freecharge wallet on 16-11-26. You earned 10 Freecharge points.",
            Direction.DEBIT, 9900,
        ),
        pays(
            "keep2-uni-card-ending-reward-coins", "VM-UNICRD",
            "Rs 2,400.00 spent at Myntra using your Uni Card ending 4455 on 16-11-26. 24 reward coins credited.",
            Direction.DEBIT, 240000,
        ),
        pays(
            "keep2-navi-upi-coins-won", "VM-NAVIUP",
            "Rs.350 paid to Chai Point via Navi UPI from your A/c XX1234 on 16-11-26. You won 35 Navi coins!",
            Direction.DEBIT, 35000,
        ),
    )

    /** Real money the rule as built skips; each is explained in [problemRealMoneySkippedAsPoints] and below. */
    private val realMoneyRuleGetsWrong = listOf(
        pays(
            "bug2-hdfc-points-redemption-cashback-after-amount", "VM-HDFCBK",
            "Rs 625.00 Reward Points redemption cashback has been credited to your HDFC Bank Credit Card XX4521 on 16-11-26.",
            Direction.CREDIT, 62500,
        ),
        pays(
            "bug2-kotak-received-as-points-redemption", "VM-KOTAKB",
            "You have received Rs 500.00 as Reward Points redemption in your Kotak Bank A/c XX1234 on 16-11-26.",
            Direction.CREDIT, 50000,
        ),
        pays(
            "bug2-indusind-thank-you-for-your-purchase", "VM-INDUSB",
            "Thank you for your purchase of INR 3,250.00 at DECATHLON on IndusInd Bank Credit Card XX6677 on 16-11-26. 32 Reward Points have been credited.",
            Direction.DEBIT, 325000,
        ),
        pays(
            "bug2-sbicard-on-purchase-points-credited", "AX-SBICRD",
            "On purchase of Rs 2,499.00 with your SBI Card ending 6789 at MYNTRA on 16/11/2026, 25 reward points have been credited.",
            Direction.DEBIT, 249900,
        ),
        pays(
            "bug2-kotak-one-line-points-after-amount", "VM-KOTAKB",
            "Kotak Credit Card x7788: Spent Rs.899.00 Reward Points 18 at MYNTRA on 16/11/2026",
            Direction.DEBIT, 89900,
        ),
        pays(
            "bug2-hdfc-points-worth-then-credited-next-sentence", "VM-HDFCBK",
            "Reward Points worth Rs 500.00 redeemed. Amount credited to your HDFC Bank Credit Card XX4521 on 16-11-26.",
            Direction.CREDIT, 50000,
        ),
        pays(
            "bug2-coop-unmasked-account-gift-voucher", "VM-KALUPR",
            "Dear Customer, your account has been debited by Rs.2,000.00 on 16-11-2026 for purchase of Amazon Gift Voucher. Available balance Rs.8,000.00",
            Direction.DEBIT, 200000,
        ),
        pays(
            "bug2-mobikwik-own-sms-your-wallet", "VM-MOBIKW",
            "Rs.120 debited from your wallet for payment to Chai Point. Updated balance Rs.30. TxnID MBK1234567890",
            Direction.DEBIT, 12000,
        ),
        pays(
            "bug2-bajaj-pay-wallet-spend", "VM-BAJAJP",
            "Rs.499 paid to Zomato from your Bajaj Pay Wallet on 16-11-26. Wallet balance Rs.1. You earned 5 reward points.",
            Direction.DEBIT, 49900,
        ),
    )

    @Test
    fun realMoneyStaysAPayment() = check(listedBankMoney + unlistedSenderMoney)

    @Test
    fun realMoneyIsNeverRemovedByTheCleanUp() = checkNotRemovable(listedBankMoney + unlistedSenderMoney)

    // ---- Store and brand balances ----

    @Test
    fun storeBalancesAreSkipped() = check(
        listOf(
            skips(
                "skip2-points-count-bank-promo-next-sentence", "VM-SHPSTP",
                "Dear First Citizen, 300 points have been credited to your First Citizen account. Pay with HDFC Bank Credit Card to earn 2X points.",
            ),
            skips(
                "skip2-store-wallet-debit-add-money-upi-footer", "VM-DMARTR",
                "Rs.300 debited from your DMart Ready wallet for order 1234. Add money to your wallet using UPI.",
                reason = null,
            ),
            skips(
                "skip2-intermiles-for-card-spends", "VM-INTRML",
                "1,250 InterMiles have been credited to your InterMiles account for your spends on ICICI Bank Credit Card.",
            ),
            // Money in, as before the points rule: "Uber Cash" and "Uber account" name no store balance the rule knows.
            pays("skip2-uber-cash-no-store-word", "VM-UBERIN", "Rs 50 Uber Cash has been added to your Uber account for your trip refund.", Direction.CREDIT, 5_000),
        ),
    )

    // ---- Card bills ----

    @Test
    fun cardBillsMentioningPointsStayCardBills() = check(
        listOf(
            bill(
                "bill2-hdfc-total-then-points-worth", "VM-HDFCBK",
                "HDFC Bank Credit Card XX4521: Total Amount Due Rs 23,456.00, Minimum Due Rs 1,180.00, due by 07-12-2026. You earned 1,250 Reward Points this cycle; points worth Rs 312 can be redeemed.",
                2345600,
            ),
            bill(
                "bill2-axis-total-due-edge-points", "VM-AXISBK",
                "Axis Bank Credit Card XX2231 statement generated. Total due: INR 45,600.00. Min due: INR 2,280.00. Due date: 08-12-2026. EDGE Reward Points: 3,200.",
                4560000,
            ),
            bill(
                "bill2-kotak-total-points-worth", "VM-KOTAKB",
                "Kotak Credit Card x7788 bill: Total Amount Due Rs.6,210.00 Due Date 04/12/2026. You have 1,500 reward points worth Rs.375.",
                621000,
            ),
            bill(
                "bill2-icici-reminder-is-due-points-balance", "AX-ICICIB",
                "Reminder: INR 7,654.00 is due on your ICICI Bank Credit Card XX9012 on 06-Dec-26. Reward Points balance: 4,321.",
                765400,
            ),
            bill(
                "bill2-amex-total-membership-rewards", "AD-AMEXIN",
                "Your American Express Card ending 41005 statement: Total Amount Due INR 18,900.00, Minimum Amount Due INR 945.00, Due Date 09-12-2026. Membership Rewards points earned: 1,890.",
                1890000,
            ),
            bill(
                "bill2-idfc-points-worth-before-total", "VM-IDFCFB",
                "IDFC FIRST Bank Credit Card XX3344: 2,400 reward points (worth Rs 600) available. Total Amount Due Rs 15,000.00. Minimum Amount Due Rs 750.00. Pay by 10-12-2026.",
                1500000,
            ),
        ),
    )

    // ---- Problems ----

    /**
     * Real money skipped as REWARD_POINTS, so new messages are lost and stored rows are removed by the clean-up:
     * - a points name right after a bank amount with no "against" or "as ... redemption" lookahead on the first branch
     *   ("Rs 625.00 Reward Points redemption cashback", "Rs.899.00 Reward Points 18");
     * - "received Rs 500.00 as Reward Points redemption": the redemption lookahead is on the second branch only;
     * - "for your purchase of" / "On purchase of" with points credited: the purchase word is dropped from the actions
     *   and the amount has no action word within reach, so only points seem to move;
     * - "Reward Points worth Rs 500.00 redeemed. Amount credited to your HDFC Bank Credit Card": the bank is in the
     *   next sentence;
     * - a sender not recognised as a bank or wallet whose amount sentence says "your account" or "your wallet" next to
     *   a voucher or wallet word: `\b[a-z]*wallet\b` and "gift voucher" make it a store balance (MobiKwik and
     *   Freecharge headers are not in Banks; Bajaj Pay is not in the real-wallet list).
     */
    @Test
    fun problemRealMoneySkippedAsPoints() = check(realMoneyRuleGetsWrong)

    @Test
    fun problemRealMoneyRemovableByTheCleanUp() = checkNotRemovable(realMoneyRuleGetsWrong)

    /**
     * Store balances that become payments: "destination wins" only reads "to/into/in/from/on your ...", and the masked
     * account branch of the money words accepts any "<brand> account XX1234". A points value ("worth Rs X") is treated
     * as money when the sentence names the card the points were earned with, not only the card that receives money.
     */
    @Test
    fun problemStoreBalancesBecomePayments() = check(
        listOf(
            // Money in, as before the points rule: a brand's programme account is not a store balance the rule knows.
            pays(
                "gap2-programme-account-masked-with-x", "VM-TANISQ",
                "Dear Member, Rs.250 has been credited to your Tanishq Encircle account XXXXXX1248. Valid till 31-Dec-26.",
                Direction.CREDIT, 25_000,
            ),
            skips(
                "gap2-voucher-credited-for-upi-payment", "VM-BMSHOW",
                "Rs 150 BookMyShow voucher has been credited to your account for your UPI payment of Rs 600.",
            ),
            // The safe outcome: from a bank, "points worth Rs X" is money, because a real
            // credit worded that way must never be lost. Shown as money in, which is never counted as spending.
            pays(
                "gap2-bank-points-worth-for-purchase-on-card", "VM-HDFCBK",
                "Reward points worth Rs 120 have been credited for your purchase on HDFC Bank Credit Card XX4521.",
                Direction.CREDIT, 12000,
            ),
        ),
    )

    /**
     * Card bills lost to a points line: [CardBills] gives up on a reminder that says "credited" anywhere (here, the
     * points), and the promotion check runs before card bills, so "earn bonus reward points" drops a due reminder.
     */
    @Test
    fun problemCardBillsLostToPointsWording() = check(
        listOf(
            bill(
                "bug2-bill-amount-due-points-credited", "AX-SBICRD",
                "Your SBI Card ending 6789 statement: Amount Due Rs 9,875.00, Min Due Rs 500.00, payable by 05-12-2026. 300 reward points credited this cycle.",
                987500,
            ),
            bill(
                "bug2-bill-reminder-earn-reward-points", "VM-HDFCBK",
                "Payment of Rs 18,240.00 for your HDFC Bank Credit Card XX4521 is due on 04-Dec-26. Pay on time to earn bonus reward points.",
                1824000,
            ),
        ),
    )

    private companion object {
        const val NOV_16 = 1_763_280_000_000L
    }
}
