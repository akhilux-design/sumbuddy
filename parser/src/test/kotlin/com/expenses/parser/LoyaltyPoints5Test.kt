package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cases for [SkipReason.REWARD_POINTS] and the one-time clean-up, first written against a wider version of the rule:
 * (1) from any sender, the amount is itself points, coins or miles, or only points move; goods and merchants named
 * coin, point or miles are rupees. (2) Money in from a sender that is not a bank is skipped only when it lands in, or
 * is, a store's own balance, or is what points are worth with no bank account, card or wallet receiving it; a real
 * place named for the money wins. (3) Nothing else is skipped, and debits read as before unless the amount is points.
 * (4) The clean-up removes only untouched money in from senders that are not banks which the parser now skips as
 * [SkipReason.REWARD_POINTS].
 *
 * Every message is synthetic: names, digits, references and amounts are invented. A comment "was ..., now ..." gives
 * the reading before the points rule was added and the reading now, where they differ; a case with no such comment
 * reads the same in both.
 *
 * - The `...ReadAsBefore` tests and [noMessageThatMustReadAsBeforeIsRemovable] hold what must not change.
 * - [storeLoyaltyMessageIsSkipped] and [pointsAndStoreBalancesInNewWordingsAreSkipped] hold what the rules are for.
 * - The `regression...` tests hold what the wider version got wrong: the parser read a payment before the points
 *   rule, the wider version skipped it, and the amount is not points. Each expects the reading before the rule and
 *   passes with the rule as it is: only money in from a sender that is not a bank, whose amount is labelled as points
 *   right after it, is skipped. Cases that the wider version skipped and the rule does not are in
 *   [PointsAcceptedLimitsTest].
 */
class LoyaltyPoints5Test {
    private val parser = SmsParser()

    private sealed interface Expect
    private data class Pays(val direction: Direction, val amountMinor: Long) : Expect
    /** Not a payment. [reason] null accepts any skip reason. */
    private data class Skips(val reason: SkipReason?) : Expect
    private data class Bill(val totalDueMinor: Long) : Expect

    private data class Case(val id: String, val sender: String, val body: String, val expect: Expect)

    private fun pays(id: String, sender: String, body: String, direction: Direction, amountMinor: Long) =
        Case(id, sender, body, Pays(direction, amountMinor))

    private fun skips(id: String, sender: String, body: String, reason: SkipReason?) =
        Case(id, sender, body, Skips(reason))

    private fun sms(case: Case) = SmsMessage(case.sender, case.body, RECEIVED_AT)

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

    private fun describe(result: ParseResult): String = when (result) {
        is ParseResult.Payment -> "payment(${result.payment.direction} ${result.payment.amountMinor})"
        is ParseResult.CardBill -> "cardBill(${result.bill.totalDueMinor})"
        is ParseResult.Skipped -> "skipped(${result.reason})"
        is ParseResult.Unreadable -> "unreadable(${result.hint})"
    }

    /**
     * The parser's side of the clean-up (ParserRulesCleanup in the app): no bank sent it, the parser now skips it as
     * [SkipReason.REWARD_POINTS], and it still reads as money in. The app also needs the stored row to be untouched.
     */
    private fun removable(sms: SmsMessage): Boolean {
        val result = parser.parse(sms)
        return !parser.isFromBank(sms) && result is ParseResult.Skipped && result.reason in REMOVING_REASONS &&
            parser.readIgnoringSkips(sms)?.direction == Direction.CREDIT
    }

    private fun noneRemovable(cases: List<Case>) {
        val removed = cases.filter { removable(sms(it)) }.map { it.id }
        assertEquals("rows the clean-up would delete", emptyList<String>(), removed)
    }

    // ---- What the rules are for ----

    /**
     * The store loyalty message the points rule is for, and fresh messages of the same shape: the amount is
     * itself loyalty points, so it is skipped, and a money in row an older version stored from it is one the clean-up
     * takes.
     */
    @Test
    fun storeLoyaltyMessageIsSkipped() {
        val storeLoyalty = listOf(
            skips(
                "store-loyalty-message", "VM-SHPCLB",
                "An update has been made to your ShopClub Loyalty account XXXXXX7731. " +
                    "Rs.200 loyalty points have been credited. Valid until 30th November. *T&C Apply.",
                SkipReason.REWARD_POINTS,
            ),
            skips(
                "store-loyalty-points-sentence", "VM-SHPCLB",
                "Rs.200 loyalty points have been credited.",
                SkipReason.REWARD_POINTS,
            ),
        )
        val fresh = listOf(
            // was payment(CREDIT 20000), now skipped(REWARD_POINTS)
            skips(
                "f-store-loyalty-shape-showroom", "VM-SHPCLB",
                "Hello! Rs.200 loyalty points have been credited to your ShopClub Loyalty account. Visit any ShopClub " +
                    "showroom to redeem.",
                SkipReason.REWARD_POINTS,
            ),
            // was payment(CREDIT 25000), now skipped(REWARD_POINTS)
            skips(
                "f-store-loyalty-shape-balance", "VM-SHPCLB",
                "Rs.250 loyalty points have been credited against your purchase. Your loyalty balance is " +
                    "Rs.900.",
                SkipReason.REWARD_POINTS,
            ),
            // was payment(CREDIT 18000), now skipped(REWARD_POINTS)
            skips(
                "f-store-loyalty-shape-other-store", "VM-HOMCEN",
                "Dear Customer, Rs.180 loyalty points have been credited to your HomeCentre account XX2219. " +
                    "T&C apply.",
                SkipReason.REWARD_POINTS,
            ),
        )
        check(storeLoyalty + fresh)
        for (case in storeLoyalty + fresh.take(3)) assertTrue(case.id, removable(sms(case)))
    }

    /** Points programmes and store wallets in wordings no earlier test has: skipped as [SkipReason.REWARD_POINTS]. */
    @Test
    fun pointsAndStoreBalancesInNewWordingsAreSkipped() = check(
        listOf(
            // was payment(CREDIT 2500), now skipped(REWARD_POINTS)
            skips(
                "b-fi-coins-credited", "VM-FICOIN",
                "Rs 25 Fi-Coins credited for your card spends this week.",
                SkipReason.REWARD_POINTS,
            ),
            // was unreadable, now skipped(REWARD_POINTS)
            skips(
                "b-bonus-miles-count", "VM-VISTRA",
                "300 Bonus Miles credited to your Club Vistara account for flight UK 812.",
                SkipReason.REWARD_POINTS,
            ),
        ),
    )

    // ---- What must read as before ----

    /**
     * Goods, merchants and funds whose names hold coin, point, chip or miles, where the rule reads them as rupees. The
     * last four were skipped before the points rule too and are still skipped (only the reason changed).
     */
    private val goods = listOf(
        pays(
            "a-bluechip-fund-sip-debited", "VM-MFCENT",
            "Rs 5,000 SIP in Axis Bluechip Fund debited from your bank account on 20-11-26.",
            Direction.DEBIT, 500_000,
        ),
        pays(
            "a-coin-zerodha-redemption-credited", "VM-ZRDHAC",
            "Rs 7,500 redemption from your Coin mutual fund holdings credited to your bank account.",
            Direction.CREDIT, 750_000,
        ),
        pays(
            "a-gold-coin-refund-upi", "VM-JWLKRT",
            "Rs 6,200 refund for gold coin GC-22K credited to your UPI ID asha@okhdfc.",
            Direction.CREDIT, 620_000,
        ),
        pays(
            "a-chips-and-dips-order-upi", "VM-SWGYIN",
            "Rs 349 Chips and Dips Cafe paid via UPI. Order 88124.",
            Direction.DEBIT, 34_900,
        ),
        pays(
            "a-bank-coins-recharge-card-named", "VM-ICICIB",
            "Rs 499 Bigo Live coins recharge paid on ICICI Bank Credit Card XX6004 on 20-Nov-26.",
            Direction.DEBIT, 49_900,
        ),
        skips(
            "a-pizza-point-payment-successful", "VM-RZRPAY",
            "Rs 450 Pizza Point payment successful. Txn ID pay_Nk44129XQ",
            SkipReason.BILLER_RECEIPT,
        ),
        skips(
            "a-bigo-coins-recharge-upi", "VM-BIGOLV",
            "Rs 499 Bigo Live coins recharge successful. Paid via UPI Ref 632455120090.",
            SkipReason.BILLER_RECEIPT,
        ),
        pays(
            "v-gold-coins-brand-paid-card", "VM-CRTLNE",
            "Rs 15,800 Caratlane 24KT coins paid with card ending 6004.",
            Direction.DEBIT, 1_580_000,
        ),
        skips(
            "a-bonvoy-points-bought-card", "VM-MARIOT",
            "Rs 5,400 Bonvoy points bought with card ending 9031. Points will reflect in 72 hours.",
            null,
        ),
        skips(
            "a-miles-and-more-fee-charged", "VM-MILESM",
            "INR 1,999 Miles & More membership fee charged to your card ending 4410.",
            null,
        ),
        skips(
            "a-bank-four-points-no-place", "VM-HDFCBK",
            "Rs 8,400.00 Four Points Pune spend approved on 20-11-26. -HDFC Bank",
            null,
        ),
        skips(
            "v-pocketfm-coins-bought-upi", "VM-POCKFM",
            "Rs 299 Pocket FM coins bought successfully via UPI.",
            null,
        ),
    )

    /** Money in that names its real destination next to a store balance: the destination wins. */
    private val realDestinations = listOf(
        pays(
            "c-egift-refund-credited-to-ac-line", "VM-GYFTRI",
            "Rs 1,500 refund for your e-gift card order\nCredited to A/c XX8812\nRef GY55120",
            Direction.CREDIT, 150_000,
        ),
        pays(
            "c-rewards-balance-transferred-kotak", "VM-SUPSAV",
            "Rs 300 Rewards account balance transferred to your Kotak 811 account.",
            Direction.DEBIT, 30_000,
        ),
        pays(
            "c-gift-card-refund-to-amex", "VM-LFSTYL",
            "Your Lifestyle gift card refund of Rs 1,200 has been credited to your Amex card.",
            Direction.CREDIT, 120_000,
        ),
        pays(
            "c-store-wallet-refunded-hdfc-cc", "VM-NYKAAA",
            "Rs 400 from your Nykaa wallet has been refunded to your HDFC Credit Card.",
            Direction.CREDIT, 40_000,
        ),
        pays(
            "c-neupass-wallet-to-hdfc-card", "JD-TATNEU",
            "Rs 1,000 from your NeuPass wallet has been credited to your Tata Neu HDFC Bank card.",
            Direction.CREDIT, 100_000,
        ),
        pays(
            "c-gift-card-cashback-savings", "VM-WOOHOO",
            "Rs 650 cashback on your gift card purchase credited to your savings account.",
            Direction.DEBIT, 65_000,
        ),
        pays(
            "c-voucher-refund-masked-account", "VM-GYFTRI",
            "Rs 2,000 refund for gift voucher GV5512 credited to your account XX9031.",
            Direction.CREDIT, 200_000,
        ),
        pays(
            "c-gift-card-cashback-bank-account", "VM-AMZGFT",
            "Your cashback of Rs 250 for using a gift card has been credited to your bank account.",
            Direction.CREDIT, 25_000,
        ),
        pays(
            "c-points-worth-to-upi-id", "VM-PAYBCK",
            "Your 1,600 points worth Rs 400 have been converted to cash and credited to your UPI ID " +
                "ravi@okicici.",
            Direction.CREDIT, 40_000,
        ),
    )

    /** Real wallets, meal cards and wallets reached through a listed sender. */
    private val realWallets = listOf(
        pays(
            "d-amazon-wallet-refund", "VM-AMAZON",
            "Rs 499 refund has been credited to your Amazon wallet for order 405-5512.",
            Direction.CREDIT, 49_900,
        ),
        pays(
            "d-ticket-restaurant-card-ending", "VM-EDNRED",
            "Your Ticket Restaurant card ending 4471 has been credited with Rs 2,200.",
            Direction.CREDIT, 220_000,
        ),
        skips(
            "d-phonepe-wallet-added", "VM-PHNPEW",
            "Rs 500 added to your PhonePe wallet successfully.",
            SkipReason.SELF_TRANSFER,
        ),
        pays(
            "d-freecharge-cashback", "VM-FCHRGE",
            "Rs 75 cashback credited to your Freecharge wallet.",
            Direction.CREDIT, 7_500,
        ),
        pays(
            "d-jiomoney-wallet-cashback", "VM-JIOPAY",
            "Rs 50 cashback credited to your JioMoney wallet.",
            Direction.CREDIT, 5_000,
        ),
        pays(
            "d-airtel-payments-bank-wallet", "VM-AIRPAY",
            "Rs 250 has been credited to your Airtel Payments Bank wallet.",
            Direction.CREDIT, 25_000,
        ),
        skips(
            "d-uber-cash-wallet", "VM-UBERIN",
            "Rs 300 has been added to your Uber Cash wallet. Use it on your next ride.",
            SkipReason.SELF_TRANSFER,
        ),
        skips(
            "d-hp-pay-wallet-loaded", "VM-HPPAYW",
            "Rs 1,000 has been loaded to your HP Pay wallet. Use it at any HP pump.",
            SkipReason.SELF_TRANSFER,
        ),
    )

    /** Unlisted banks, neobanks, pay-later, FASTag, UPI apps, funds and insurers: no store balance, so as before. */
    private val others = listOf(
        pays(
            "e-esaf-credit-neft", "VM-ESAFSB",
            "Rs 4,200.00 credited to your ESAF account 01XX7781 on 20-11-26 by NEFT.",
            Direction.CREDIT, 420_000,
        ),
        pays(
            "e-jana-imps-credit", "VM-JANASF",
            "Rs 6,000.00 credited to A/c XX3381 on 20-11-26 by IMPS. Bal Rs 26,000.00 -Jana",
            Direction.CREDIT, 600_000,
        ),
        pays(
            "e-jupiter-payroll-credit", "VM-JUPMNY",
            "Rs 12,000 credited to your Jupiter account from ACME PAYROLL.",
            Direction.CREDIT, 1_200_000,
        ),
        skips(
            "e-niyox-loaded", "VM-NIYOXB",
            "Rs 2,500 has been loaded to your NiyoX account.",
            SkipReason.NOT_FINANCIAL,
        ),
        pays(
            "e-simpl-refund-account", "VM-SIMPLE",
            "Rs 799 refund credited to your Simpl account for order SM5512.",
            Direction.CREDIT, 79_900,
        ),
        pays(
            "e-fastag-toll-reversal-account", "VM-FSTAGB",
            "Rs 95.00 toll reversal credited to your FASTag account XX4410.",
            Direction.CREDIT, 9_500,
        ),
        pays(
            "e-phonepe-received-bank", "VM-PHPEIN",
            "Rs 1,250 received from RAHUL S in your bank account via PhonePe.",
            Direction.CREDIT, 125_000,
        ),
        pays(
            "e-bhim-received-ref", "VM-BHIMUP",
            "You have received Rs 640 from Neha on BHIM. UPI Ref 632412341234",
            Direction.CREDIT, 64_000,
        ),
        pays(
            "e-gpay-rewards-cashback", "VM-GPAYRW",
            "Rs 21 cashback credited to your Google Pay Rewards.",
            Direction.CREDIT, 2_100,
        ),
        pays(
            "e-mf-redemption-registered-bank", "VM-MFCENT",
            "Redemption of Rs 10,000 from Mirae Large Cap fund credited to your registered bank account.",
            Direction.CREDIT, 1_000_000,
        ),
        skips(
            "e-insurance-claim-account", "VM-STRHLT",
            "Claim of Rs 8,400 has been settled to your account XX7731. Policy SH5512.",
            SkipReason.NOT_FINANCIAL,
        ),
    )

    /** Spending from a store's balance is a debit, read as before (rule 4). */
    private val storeDebits = listOf(
        pays(
            "h-store-wallet-paid-invoice", "VM-CROMAR",
            "Rs 1,200 paid from your Croma loyalty wallet for invoice 5512.",
            Direction.DEBIT, 120_000,
        ),
        pays(
            "h-store-wallet-debited-order", "VM-NYKAAA",
            "Rs 500 debited from your Nykaa wallet for order NK8812.",
            Direction.DEBIT, 50_000,
        ),
    )

    @Test
    fun goodsMerchantsAndFundsNamedCoinPointOrMilesReadAsBefore() = check(goods)

    @Test
    fun aRealDestinationNamedBesideAStoreBalanceReadsAsBefore() = check(realDestinations)

    @Test
    fun realWalletsReadAsBefore() = check(realWallets)

    @Test
    fun unlistedBanksNeobanksPayLaterFastagAndUpiAppsReadAsBefore() = check(others)

    @Test
    fun storeDebitsReadAsBefore() = check(storeDebits)

    /** None of the messages that must read as before is a row the clean-up would delete. */
    @Test
    fun noMessageThatMustReadAsBeforeIsRemovable() =
        noneRemovable(goods + realDestinations + realWallets + others + storeDebits)

    // ---- Regressions of the wider version: fixed by the rule as it is, each reads as before the points rule ----

    /**
     * Rule 1 reads a merchant or product right after the amount as a points unit when the word after it is not one of
     * purchase, order, sale, sold, trip, ride, POS, txn, transaction, proceeds, refund or booking: "bill", "stay",
     * "renewal", "pack", "top-up", "delivered", "dispatched", "is complete" and plain "paid" all pass. Any word ending
     * in point or points is a unit (Chai Point, Juice Point, Four Points, Checkpoint), crypto coins other than the five
     * named are units, and gold coins named by brand ("MMTC-PAMP coins") are units. All are real debits from senders
     * that are not banks; the clean-up does not remove debits, but new ones are lost.
     */
    @Test
    fun regressionMerchantsAndGoodsNamedPointOrCoinsReadAsPoints() {
        val cases = listOf(
            pays(
                "a-chai-point-bill-paid-upi", "VM-GPAYIN",
                "Rs 180.00 Chai Point bill paid via UPI. Ref 632412340017",
                Direction.DEBIT, 18_000,
            ),
            pays(
                "v-juice-point-paid-upi", "VM-BHIMUP",
                "Rs 120 Juice Point paid via UPI. Ref 632455009911",
                Direction.DEBIT, 12_000,
            ),
            pays(
                "a-four-points-hotel-stay-paid-card", "VM-MMTRIP",
                "Rs 8,400.00 Four Points Pune stay paid with card ending 4410. Booking ID MT55120.",
                Direction.DEBIT, 840_000,
            ),
            pays(
                "a-checkpoint-vpn-renewal-upi", "VM-PAYUIN",
                "Rs 799 Checkpoint VPN renewal paid via UPI on 20-11-26.",
                Direction.DEBIT, 79_900,
            ),
            pays(
                "a-pocketfm-coins-pack-paid", "VM-POCKFM",
                "Rs 149 Pocket FM coins pack paid via UPI. Enjoy listening!",
                Direction.DEBIT, 14_900,
            ),
            pays(
                "a-kukufm-coins-topup-debited", "VM-KUKUFM",
                "Rs 99 KukuFM coins top-up debited from your UPI account. Ref 632477001234",
                Direction.DEBIT, 9_900,
            ),
            pays(
                "a-solana-coins-worth-paid", "VM-CNVALT",
                "Your order for Rs 2,000 worth of Solana coins is complete. Paid via UPI.",
                Direction.DEBIT, 200_000,
            ),
            pays(
                "a-mmtc-pamp-coins-delivered", "VM-MMTCPM",
                "Rs 10,000 MMTC-PAMP coins delivered to you. Paid by HDFC Bank Debit Card.",
                Direction.DEBIT, 1_000_000,
            ),
            pays(
                "a-lakshmi-ganesh-coins-dispatched", "VM-SHRGLD",
                "Rs 4,500 Lakshmi Ganesh coins dispatched. Paid via UPI Ref 632433214455.",
                Direction.DEBIT, 450_000,
            ),
            pays(
                "a-bluchip-electronics-bill-upi", "VM-BLUCHP",
                "Rs 12,500 BluChip Electronics bill paid by UPI. Thank you for shopping.",
                Direction.DEBIT, 1_250_000,
            ),
            pays(
                "a-supercash-mart-bill-upi", "VM-SPCMRT",
                "Rs 860 SuperCash Mart bill paid via UPI. Visit again.",
                Direction.DEBIT, 86_000,
            ),
        )
        check(cases)
    }

    /**
     * Rule 2 lets the destination win only when [RealMoney] recognises it as bank money. "your SBI Card", "your
     * OneCard", "your Amex card", "your ICICI account" and "your HDFC account" without digits, and "your bank" are not
     * recognised, so a store balance named earlier in the sentence ("from your Croma loyalty wallet", "on your
     * membership card renewal") or a points value ("points worth Rs 500. Amount credited to your SBI Card") skips the
     * money. All are money in that the clean-up would delete.
     */
    @Test
    fun regressionARealCardOrAccountNotRecognisedLosesToAStoreBalance() {
        val cases = listOf(
            pays(
                "c-store-wallet-to-sbi-card", "VM-CROMAR",
                "Rs 650 from your Croma loyalty wallet has been credited to your SBI Card.",
                Direction.CREDIT, 65_000,
            ),
            pays(
                "c-store-wallet-credited-onecard", "VM-NYKAAA",
                "Rs 400 from your Nykaa wallet has been credited to your OneCard.",
                Direction.CREDIT, 40_000,
            ),
            pays(
                "v-store-wallet-to-amex-card", "VM-NYKAAA",
                "Rs 420 from your Nykaa wallet has been credited to your Amex card.",
                Direction.CREDIT, 42_000,
            ),
            pays(
                "c-membership-renewal-cashback-icici", "VM-GLDCLB",
                "Rs 750 cashback on your membership card renewal has been credited to your ICICI account.",
                Direction.CREDIT, 75_000,
            ),
            pays(
                "v-rewards-cashback-to-hdfc-account-no-digits", "VM-SUPSAV",
                "Rs 300 cashback from your Rewards account has been credited to your HDFC account.",
                Direction.CREDIT, 30_000,
            ),
            pays(
                "v-loyalty-wallet-to-your-bank", "VM-CROMAR",
                "Rs 350 from your Croma loyalty wallet has been credited to your bank.",
                Direction.CREDIT, 35_000,
            ),
            pays(
                "c-points-worth-then-sbi-card", "VM-PAYBCK",
                "Redeemed 2,000 points worth Rs 500. Amount credited to your SBI Card.",
                Direction.CREDIT, 50_000,
            ),
        )
        noneRemovable(cases)
        check(cases)
    }

    /**
     * "Refund of Rs 2,000 on your gift card GC5512 has been credited to your original payment method": STORE_PLACE
     * takes "on your gift card" for where the money lands (only order, purchase, booking, payment, transaction, bill
     * and spend after it block that), although the sentence says the refund goes to the original payment method or
     * source account.
     */
    @Test
    fun regressionARefundOnAGiftCardThatGoesElsewhereIsReadAsLandingInIt() {
        val cases = listOf(
            pays(
                "c-gift-card-refund-original-method", "VM-WOOHOO",
                "Refund of Rs 2,000 on your gift card GC5512 has been credited to your original payment " +
                    "method.",
                Direction.CREDIT, 200_000,
            ),
            pays(
                "v-gift-card-refund-source", "VM-WOOHOO",
                "Refund of Rs 1,000 on your gift card has been initiated to the source account.",
                Direction.CREDIT, 100_000,
            ),
        )
        noneRemovable(cases)
        check(cases)
    }

    /**
     * "Privilege account", "Club account" and "member account" are store balances to STORE_BALANCE, and the sentence's
     * own NEFT and masked account number do not count, because only the place after the credit word is tested. A bank
     * the parser does not list, or a co-operative society, sends exactly these words for real money (dividend,
     * interest, a Rs 25,000 NEFT credit).
     */
    @Test
    fun regressionAStoreWordInABankOrSocietyAccountNameSkipsMoneyIn() {
        val cases = listOf(
            pays(
                "e-unlisted-bank-privilege-account-neft", "VM-UJJVNS",
                "Rs 25,000.00 credited to your Privilege account XX7781 by NEFT from ACME LTD.",
                Direction.CREDIT, 2_500_000,
            ),
            pays(
                "v-club-account-neft-masked", "VM-SAHKAR",
                "Rs 15,000.00 credited to your Club account XX4412 via NEFT from ORBIT TRADERS.",
                Direction.CREDIT, 1_500_000,
            ),
            pays(
                "e-credit-society-dividend-member-account", "VM-SHYDRI",
                "Dividend of Rs 840.00 credited to your member account on 20-11-26. -Sahyadri Credit Society",
                Direction.CREDIT, 84_000,
            ),
            pays(
                "v-member-account-interest", "VM-NAGRIK",
                "Dear Member, interest of Rs 1,240.00 has been credited to your member account. -Nagrik " +
                    "Sahakari",
                Direction.CREDIT, 124_000,
            ),
        )
        noneRemovable(cases)
        check(cases)
    }

    /**
     * Any "<name> wallet" not in MONEY_LINK's list is a store wallet. Edenred and Zaggle are employer meal and benefit
     * wallets like Pluxee, Sodexo and Zeta (which the list has); a FASTag "Toll wallet" is the tag wallet under another
     * name; "Amazon wallet" is Amazon Pay balance (in the list as "Amazon Pay") when it comes from a header Banks does
     * not know.
     */
    @Test
    fun regressionRealWalletsMissingFromTheListAreReadAsStoreWallets() {
        val cases = listOf(
            pays(
                "d-zaggle-wallet-employer", "VM-ZAGGLE",
                "Rs 2,200.00 has been credited to your Zaggle wallet by your employer.",
                Direction.CREDIT, 220_000,
            ),
            pays(
                "d-edenred-wallet-meal-allowance", "VM-EDNRED",
                "Rs 2,200 meal allowance credited to your Edenred wallet for Nov-26.",
                Direction.CREDIT, 220_000,
            ),
            pays(
                "e-toll-wallet-refund", "VM-NETCTL",
                "Rs 95.00 has been refunded to your Toll wallet for duplicate deduction at Kherki plaza.",
                Direction.CREDIT, 9_500,
            ),
            pays(
                "d-amazon-wallet-refund-shop", "VM-AMZNIN",
                "Rs 499 refund has been credited to your Amazon wallet for order 405-5513.",
                Direction.CREDIT, 49_900,
            ),
        )
        noneRemovable(cases)
        check(cases)
    }

    /**
     * AS_STORE_NOUN reads "credited to your account as meal voucher reimbursement" as money given as a voucher, and
     * STORE_BEFORE_AMOUNT reads "Refund for voucher Rs 600 credited" as a voucher of Rs 600 given. A reimbursement or a
     * refund for a voucher is money.
     */
    @Test
    fun regressionAVoucherNamedBesideRealMoneyMakesTheAmountAVoucher() {
        val cases = listOf(
            pays(
                "c-meal-voucher-reimbursement", "VM-PAYRLL",
                "Rs 1,500 credited to your account as meal voucher reimbursement for Oct-26.",
                Direction.CREDIT, 150_000,
            ),
            pays(
                "v-voucher-cashback-as-in-sentence", "VM-PAYRLL",
                "Rs 900 has been credited to your account as gift voucher allowance reimbursement.",
                Direction.CREDIT, 90_000,
            ),
            pays(
                "c-voucher-refund-your-account", "VM-GYFTRI",
                "Refund for voucher Rs 600 credited to your account.",
                Direction.CREDIT, 60_000,
            ),
        )
        noneRemovable(cases)
        check(cases)
    }

    /**
     * Multi-line: "Rs 1,500 refund for your e-gift card order" on the amount's line and "A/c XX8812 credited" on the
     * next. The first line alone is a credit naming a store balance (storeLine), and "A/c XX8812 credited" without
     * "your ... is" is not a credit place, so the bank account does not win. ("Credited to A/c XX8812" on the next line
     * does.)
     */
    @Test
    fun regressionAStoreLineHidesTheBankAccountOnTheNextLine() {
        val cases = listOf(
            pays(
                "c-egift-refund-ac-credited-line", "VM-GYFTRI",
                "Rs 1,500 refund for your e-gift card order\nA/c XX8812 credited on 20-11-26\nRef GY55121",
                Direction.CREDIT, 150_000,
            ),
        )
        noneRemovable(cases)
        check(cases)
    }

    private companion object {
        const val RECEIVED_AT = 1_763_280_000_000L
        val REMOVING_REASONS = setOf(SkipReason.REWARD_POINTS)
    }
}
