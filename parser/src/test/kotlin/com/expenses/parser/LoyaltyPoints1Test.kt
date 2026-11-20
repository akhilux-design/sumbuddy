package com.expenses.parser

import org.junit.Assert.fail
import org.junit.Test

/**
 * Cases for [SkipReason.REWARD_POINTS]. Every message is synthetic: account digits, references and amounts are
 * invented, and no message prints a phone number.
 *
 * - [realMoneyThatMentionsPointsStaysAPayment]: bank, card, UPI and wallet messages that mention points, rewards,
 *   coins, miles, loyalty, gift cards or vouchers but move real money. They must stay payments with the right direction
 *   and amount.
 * - [storeBalancesAreSkipped]: store and brand points, loyalty, store wallet and store credit messages that must not
 *   become payments.
 * - The `problem...` tests hold cases an earlier version of the rule got wrong, kept as regression tests. One
 *   expectation is the safe outcome (money in), marked where it is.
 *
 * The rule: a message is skipped as points only when it is money in from a sender that is not a bank and its amount is
 * labelled as points right after it, or it prints no money amount and only points move. Cases the rule does not skip
 * are in [PointsAcceptedLimitsTest].
 */
class LoyaltyPoints1Test {
    private val parser = SmsParser()

    private sealed interface Expect
    private data class Pays(val direction: Direction, val amountMinor: Long) : Expect
    /** Not a payment. [reason] null accepts any skip reason. */
    private data class Skips(val reason: SkipReason? = SkipReason.REWARD_POINTS) : Expect
    private data class Bill(val totalDueMinor: Long) : Expect
    /** Shown to the person: a payment or a Needs review item, never skipped. */
    private data object Review : Expect

    private data class Case(val id: String, val sender: String, val body: String, val expect: Expect)

    private fun pays(id: String, sender: String, body: String, direction: Direction, amountMinor: Long) =
        Case(id, sender, body, Pays(direction, amountMinor))

    private fun skips(id: String, sender: String, body: String, reason: SkipReason? = SkipReason.REWARD_POINTS) =
        Case(id, sender, body, Skips(reason))

    private fun check(cases: List<Case>) {
        val ids = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (ids.isNotEmpty()) fail("duplicate ids $ids")
        val failures = cases.mapNotNull { case ->
            val result = parser.parse(SmsMessage(case.sender, case.body, NOV_16))
            val ok = when (val expect = case.expect) {
                is Pays -> result is ParseResult.Payment && result.payment.direction == expect.direction &&
                    result.payment.amountMinor == expect.amountMinor
                is Skips -> result is ParseResult.Skipped && (expect.reason == null || result.reason == expect.reason)
                is Bill -> result is ParseResult.CardBill && result.bill.totalDueMinor == expect.totalDueMinor
                Review -> result is ParseResult.Payment || result is ParseResult.Unreadable
            }
            if (ok) null else "  ${case.id}: expected ${case.expect}, actual ${describe(result)}"
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} failed\n" + failures.joinToString("\n"))
    }

    private fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Payment -> "payment(${result.payment.direction} ${result.payment.amountMinor})"
        is ParseResult.CardBill -> "cardBill(${result.bill.totalDueMinor})"
        is ParseResult.Skipped -> "skipped(${result.reason})"
        is ParseResult.Unreadable -> "unreadable(${result.hint})"
    }

    @Test
    fun realMoneyThatMentionsPointsStaysAPayment() = check(
        listOf(
            pays(
                "keep-hdfc-cc-spend-points", "VM-HDFCBK",
                "Spent Rs.2,450.00 On HDFC Bank Card 4521 At AMAZON PAY INDIA On 2026-11-16:14:22:10. You earned 49 Reward Points. Not You? Call the number on your card.",
                Direction.DEBIT, 245000,
            ),
            pays(
                "keep-icici-credit-points-balance", "AX-ICICIB",
                "Dear Customer, Acct XX9012 is credited with Rs 1,000.00 on 16-Nov-26 from ACME LTD. UPI:632012345678. Reward Points balance 2,345 -ICICI Bank",
                Direction.CREDIT, 100000,
            ),
            pays(
                "keep-axis-multiline-miles", "VM-AXISBK",
                "Spent INR 12,000.00\nAxis Bank Card no. XX2231\n16-11-26 10:15:00 IST\nMAKEMYTRIP\nEDGE Miles earned: 600\nAvl Limit: INR 2,38,000.00",
                Direction.DEBIT, 1200000,
            ),
            pays(
                "keep-kotak-cc-points", "VM-KOTAKB",
                "Rs.899.00 spent on Kotak Credit Card x7788 at MYNTRA on 16/11/2026. 18 Kotak Reward Points earned. Avl limit Rs.45,000.00",
                Direction.DEBIT, 89900,
            ),
            pays(
                "keep-idfc-cc-points", "VM-IDFCFB",
                "INR 1,499.00 spent on your IDFC FIRST Bank Credit Card ending XX3344 at ZOMATO on 16 NOV 2026 at 08:15 PM. Reward points earned: 9. Available limit: INR 80,000.00",
                Direction.DEBIT, 149900,
            ),
            pays(
                "keep-yes-cc-gift-card-merchant", "VM-YESBNK",
                "INR 5,000.00 spent on YES BANK Credit Card XX5566 at WOOHOO GIFT CARDS on 16-11-2026. Avl Lmt INR 95,000.00. 125 YES Rewardz points earned.",
                Direction.DEBIT, 500000,
            ),
            pays(
                "keep-indusind-cc-gift-card", "VM-INDUSB",
                "Rs 2,000.00 debited from your IndusInd Bank Credit Card XX6677 for purchase of Amazon Pay Gift Card on 16-11-26. Reward Points earned: 30.",
                Direction.DEBIT, 200000,
            ),
            pays(
                "keep-au-cashback-savings", "VM-AUBANK",
                "Rs.750.00 cashback credited to your AU Bank Savings A/c XX8899 on 16-11-26 for card spends. Keep earning rewards!",
                Direction.CREDIT, 75000,
            ),
            pays(
                "keep-federal-coins-redemption-towards", "VM-FEDBNK",
                "Rs 300.00 credited to your Federal Bank A/c XX1122 on 16-11-26 towards Fi-Coins redemption.",
                Direction.CREDIT, 30000,
            ),
            pays(
                "keep-bob-upi-credit-points", "VM-BOBTXN",
                "Rs.1,250.00 Credited to A/c ...4567 thru UPI/632012345678 by friend@okaxis on 16-11-26. BoB World Rewards points: 120 -Bank of Baroda",
                Direction.CREDIT, 125000,
            ),
            pays(
                "keep-pnb-e-gift-voucher-debit", "AD-PNBSMS",
                "Your A/c XX7890 is debited for Rs 2,000.00 on 16-11-2026 towards purchase of e-Gift Voucher. Avl Bal Rs 18,000.00 -PNB",
                Direction.DEBIT, 200000,
            ),
            pays(
                "keep-canara-merchant-named-point", "VM-CANBNK",
                "An amount of INR 450.00 has been DEBITED to your account XXX2345 on 16/11/2026 towards POS PLAY POINT ARCADE. Total Avail.bal INR 9,550.00 - Canara Bank",
                Direction.DEBIT, 45000,
            ),
            pays(
                "keep-union-fuel-point", "VM-UNIONB",
                "A/c *3456 Debited for Rs:350.00 on 16-11-2026 at FUEL POINT by POS. Avl Bal Rs:12,000.00 -Union Bank of India",
                Direction.DEBIT, 35000,
            ),
            pays(
                "keep-sbicard-spend-points", "AX-SBICRD",
                "Rs.4,999.00 spent on your SBI Credit Card ending 6789 at CROMA on 16/11/26. 50 reward points earned. Trxn. not done by you? Report it in the SBI Card app.",
                Direction.DEBIT, 499900,
            ),
            pays(
                "keep-amex-membership-rewards", "AD-AMEXIN",
                "Alert: You've spent INR 2,300.00 on your AMEX card ** 41005 at TAJ HOTELS on 16 November 2026 at 08:30 PM IST. Membership Rewards points for this spend will be credited to your account.",
                Direction.DEBIT, 230000,
            ),
            pays(
                "keep-onecard-points", "JD-ONECRD",
                "Rs 1,200 spent at SWIGGY using OneCard XX4321 on 16-11-26. You earned 12 reward points. Avl limit Rs 48,800",
                Direction.DEBIT, 120000,
            ),
            pays(
                "keep-paytm-wallet-cashback-received", "VM-PAYTMB",
                "Rs 25 cashback received in your Paytm Wallet for payment to Sharma Kirana. Paytm Wallet balance Rs 125",
                Direction.CREDIT, 2500,
            ),
            pays(
                "keep-phonepe-cashback-plus-points", "VM-PHONPE",
                "Congrats! Rs 50 cashback credited to your PhonePe Wallet. You also earned 100 PhonePe reward points.",
                Direction.CREDIT, 5000,
            ),
            pays(
                "keep-amazonpay-balance-cashback", "AX-AMZPAY",
                "Rs 100 cashback has been credited to your Amazon Pay balance for your Amazon Pay UPI payment.",
                Direction.CREDIT, 10000,
            ),
            pays(
                "keep-gpay-reward-to-bank", "AX-ICICIB",
                "Dear Customer, Acct XX9012 is credited with Rs 21.00 on 16-Nov-26 from Google Pay Rewards. UPI:632012349999-ICICI Bank",
                Direction.CREDIT, 2100,
            ),
            pays(
                "keep-gpay-sender-cashback-to-bank", "VM-GPAYIN",
                "Rs 51 cashback won on your Google Pay scratch card has been credited to your ICICI Bank account XX9012.",
                Direction.CREDIT, 5100,
            ),
            pays(
                "keep-kotak-gift-card-refund", "VM-KOTAKB",
                "Received Rs.500.00 in your Kotak Bank AC X1234 from Amazon Pay Gift Card refund on 16-11-26. UPI Ref 632012341111.",
                Direction.CREDIT, 50000,
            ),
            pays(
                "keep-hdfc-voucher-refund-to-card", "VM-HDFCBK",
                "Refund of Rs 1,999.00 for your e-Gift Voucher order has been credited to your HDFC Bank Credit Card XX4521 on 16-11-26.",
                Direction.CREDIT, 199900,
            ),
            pays(
                "keep-mobikwik-wallet-spend-supercash", "VM-MOBIKW",
                "Rs 100.00 paid to Sharma Store from your MobiKwik Wallet on 16-11-26. You earned 10 SuperCash points.",
                Direction.DEBIT, 10000,
            ),
            pays(
                "keep-paytm-wallet-merchant-gems", "VM-PAYTMB",
                "Paid Rs.120 to GEMS BAKERY from Paytm Wallet. Transaction ID: 43123456791. Updated Balance: Paytm Wallet- Rs 230.5",
                Direction.DEBIT, 12000,
            ),
            pays(
                "keep-hdfc-upi-multiline-smile-point", "VM-HDFCBK",
                "Sent Rs.250.00\nFrom HDFC Bank A/C *4521\nTo SMILE POINT\nOn 16/11/26\nRef 632012345678\nNot You? Call the number on your card.",
                Direction.DEBIT, 25000,
            ),
            pays(
                "keep-icici-amazonpay-card-balance-later", "AX-ICICIB",
                "INR 3,200.00 spent using ICICI Bank Card XX9012 on 16-Nov-26 on AMAZON. Avl Limit: INR 96,800.00. Rs 96 will be credited as Amazon Pay balance.",
                Direction.DEBIT, 320000,
            ),
            pays(
                "keep-sbi-upi-bare-amount-gems", "VM-SBIUPI",
                "Dear UPI user A/C X4521 debited by 320.0 on date 16Nov26 trf to GEMS STORE Refno 632012345678. If not u? report in YONO. -SBI",
                Direction.DEBIT, 32000,
            ),
            pays(
                "keep-hdfc-card-credit-against-redemption", "VM-HDFCBK",
                "Dear Cardmember, Rs 625.00 has been credited to your HDFC Bank Credit Card XX4521 against redemption of 2,500 Reward Points.",
                Direction.CREDIT, 62500,
            ),
            pays(
                "keep-hdfc-upi-to-coinswitch", "VM-HDFCBK",
                "Rs.1,000.00 debited from A/c XX4521 to VPA coinswitch@icici (UPI Ref No 632012345679) on 16-11-26 -HDFC Bank",
                Direction.DEBIT, 100000,
            ),
            pays(
                "keep-freecharge-wallet-cashback", "VM-FRCHRG",
                "Rs 50 cashback credited to your Freecharge wallet for your recharge. Wallet balance Rs 50.",
                Direction.CREDIT, 5000,
            ),
            pays(
                "keep-unlisted-bank-voucher-with-bank-word", "VM-MAHABK",
                "Rs 2,000.00 debited from A/c XX1234 on 16-11-26 for purchase of Gift Voucher. -Bank of Maharashtra",
                Direction.DEBIT, 200000,
            ),
            pays(
                "keep-icici-card-statement-credit-as", "AX-ICICIB",
                "Dear Customer, INR 500.00 has been credited to your ICICI Bank Credit Card XX9012 as statement credit against reward points redeemed.",
                Direction.CREDIT, 50000,
            ),
            pays(
                "keep-tata-neu-card-spend-neucoins", "VM-HDFCBK",
                "Rs 2,000.00 spent on Tata Neu HDFC Bank Credit Card XX1234 at BIGBASKET on 16-11-26. NeuCoins worth Rs 100 will be credited. 1 NeuCoin = Rs 1",
                Direction.DEBIT, 200000,
            ),
            pays(
                "keep-airtel-payments-bank-cashback", "VM-AIRBNK",
                "Rs 20 cashback credited to your Airtel Payments Bank wallet on 16-11-26. Rewards123 points also added.",
                Direction.CREDIT, 2000,
            ),
        ),
    )

    @Test
    fun storeBalancesAreSkipped() = check(
        listOf(
            skips("skip-insider-points-count", "VM-MYNTRA", "Congrats! 500 Insider points credited to your Myntra Insider account for order 1234567."),
            skips("skip-gift-voucher-account", "VM-BMSHOW", "Rs 100 BookMyShow gift voucher has been credited to your account."),
            skips("skip-flipkart-gift-card", "VM-FLPKRT", "Rs 100 Flipkart gift card credited to your account. Gift card balance: Rs 100."),
            skips("skip-jiomart-points-named", "VM-JIOMRT", "Rs 100 JioMart Points credited to your account for order 1234567890."),
            skips("skip-store-credit-note", "VM-DECTLN", "Rs 799 has been credited as credit note to your Decathlon account for your return."),
            skips("skip-neucoins-later", "JD-TATNEU", "You have earned 250 NeuCoins on your purchase of Rs 5,000 at Croma. NeuCoins will be credited in 3 days.", reason = null),
            skips("skip-greencard-points-on-bill", "VM-PNTLNS", "Greencard: 150 points earned on your bill of Rs 3,000 at Pantaloons. Points balance 900.", reason = null),
        ),
    )

    /**
     * Real money into a bank account or onto a card, whose payment amount is followed within three words by a points
     * word ("against EDGE Reward Points", "as Reward Points redemption") or preceded by "points worth". The points rule
     * applies to banks too, so these real credits are skipped (and the one-off clean-up deletes rows already stored).
     */
    @Test
    fun problemRealCreditsNearPointsWordsAreSkipped() = check(
        listOf(
            pays(
                "bug-axis-card-credit-against-points", "VM-AXISBK",
                "Your Axis Bank Credit Card XX2231 has been credited with INR 500.00 against EDGE Reward Points redeemed on 16-11-26.",
                Direction.CREDIT, 50000,
            ),
            pays(
                "bug-hdfc-cashback-of-against-points", "VM-HDFCBK",
                "Cashback of Rs 625.00 against Reward Points redemption has been credited to your HDFC Bank A/c XX4521 on 16-11-26.",
                Direction.CREDIT, 62500,
            ),
            pays(
                "bug-hdfc-credited-as-points-redemption", "VM-HDFCBK",
                "INR 1,000.00 credited to A/c XX4521 as Reward Points redemption on 16-11-26. Avl Bal INR 21,000.00 -HDFC Bank",
                Direction.CREDIT, 100000,
            ),
            pays(
                "bug-federal-coins-worth-credited-to-ac", "VM-FEDBNK",
                "Fi-Coins worth Rs 300.00 have been redeemed and credited to your Federal Bank A/c XX1122 on 16-11-26.",
                Direction.CREDIT, 30000,
            ),
            pays(
                "bug-sbicard-cashback-against-points", "AX-SBICRD",
                "Rs 500.00 cashback against reward points credited to your SBI Card ending 6789 on 16-11-26.",
                Direction.CREDIT, 50000,
            ),
        ),
    )

    /**
     * A card statement on one line whose total due is followed by a points line, or that mentions a points value before
     * the total: the points check runs before [CardBills], so the bill is lost.
     */
    @Test
    fun problemCardBillsWithPointsNearTheTotalAreSkipped() = check(
        listOf(
            Case(
                "bug-statement-one-line-points-after-total", "VM-HDFCBK",
                "HDFC Bank Credit Card XX4521 statement: Total due Rs 12,345.00 Reward Points 8,400 Min due Rs 620.00 Due by 05-Dec-26",
                Bill(1234500),
            ),
            Case(
                "bug-statement-points-worth-before-total", "AX-SBICRD",
                "Reward Points worth Rs 250 on your SBI Card ending 6789 expire on 30-11-26. Statement: Total Amt Due Rs 12,345.00, Min Amt Due Rs 620.00, Payable by 05-12-2026.",
                Bill(1234500),
            ),
        ),
    )

    /**
     * A real debit whose amount the parser cannot read used to go to Needs review. When the message also counts points,
     * it is now skipped without a trace. And a card issuer that is not in [Banks] and names no bank, A/c or
     * "credit card" loses a real gift card purchase to the store rule.
     */
    @Test
    fun problemRealDebitsNowSilentlySkipped() = check(
        listOf(
            Case(
                "bug-no-currency-debit-with-points-count", "VM-HDFCBK",
                "Your a/c XX4521 debited 500.00 on 16-11-26 at AMAZON. 10 reward points earned.",
                Review,
            ),
            pays(
                "bug-unlisted-issuer-gift-card-spend", "VM-EQUTAS",
                "Rs 1,500.00 spent on your card ending 1234 at WOOHOO GIFT CARD on 16-11-26. -Equitas SFB",
                Direction.DEBIT, 150000,
            ),
        ),
    )

    /**
     * Points named as one word ("CashPoints", "BluChips", "TimesPoints") are not points units, so a bank's points
     * message printed with "Rs" becomes Money in, and a count of them goes to Needs review.
     */
    @Test
    fun problemOneWordPointsNamesAreMissed() = check(
        listOf(
            // The safe outcome: from a bank, an amount followed by a points name is
            // points only when the message names no account or card it could go to ("Rs 625.00 Reward Points
            // redemption cashback has been credited to your Credit Card" is real money). Shown as money in, never
            // spent.
            pays(
                "gap-hdfc-cashpoints-with-rs", "VM-HDFCBK",
                "Rs 150 CashPoints have been credited to your HDFC Bank Millennia Credit Card XX4521 for Oct-26 spends.",
                Direction.CREDIT, 15000,
            ),
            skips(
                "gap-hdfc-cashpoints-count", "VM-HDFCBK",
                "Dear Customer, 150 CashPoints have been credited to your HDFC Bank Millennia Credit Card XX4521 for Oct-26 spends.",
            ),
            skips(
                "gap-airline-bluchips-count", "VM-INDIGO",
                "Congratulations! 1,250 BluChips have been credited to your IndiGo BluChip account.",
            ),
            skips(
                "gap-timespoints-with-rs", "VM-TIMESP",
                "Rs 50 TimesPoints credited to your account. Redeem now.",
            ),
        ),
    )

    /**
     * A store loyalty message, from a store that is not a bank, when it does not use one of the rule's keywords
     * ("loyalty", "club", "member account", "wallet" as its own word, "gift card", "voucher", or a points word right
     * by the amount), or when a promotional line names a bank, card or UPI anywhere in the text. The three that name a
     * store's balance as where the money lands are skipped. The rule skips only on positive evidence, so the four that
     * name none still become Money in, exactly as before the points rule: money in is never counted as
     * spending, and the same shape from a bank the parser does not know is real money.
     */
    @Test
    fun problemStoreCreditsStillBecomeMoneyIn() = check(
        listOf(
            pays(
                "gap-store-loyalty-message-without-points-word", "VM-SHPCLB-S",
                "An update has been made to your ShopClub account XXXXXX7731. Rs.200 has been credited. Valid until 30th November. *T&C Apply.",
                Direction.CREDIT, 20_000,
            ),
            pays(
                "gap-brand-programme-account", "VM-TANISQ",
                "Dear Member, Rs.200 has been credited to your Tanishq Encircle account XXXXXX7731. Valid until 30th November. *T&C Apply.",
                Direction.CREDIT, 20_000,
            ),
            pays(
                "gap-points-count-with-purchase-amount", "VM-SHPSTP",
                "Dear First Citizen, 250 points have been credited to your First Citizen account for your purchase of Rs 2,500 at Shoppers Stop Andheri. Total points: 1,450.",
                Direction.CREDIT, 250_000,
            ),
            pays(
                "gap-points-for-purchase-amount", "AD-RELDIG",
                "Congratulations! 120 points credited to your Reliance One account for purchase of Rs 12,000 at Reliance Digital.",
                Direction.CREDIT, 1_200_000,
            ),
        ),
    )

    private companion object {
        const val NOV_16 = 1_763_280_000_000L
    }
}
