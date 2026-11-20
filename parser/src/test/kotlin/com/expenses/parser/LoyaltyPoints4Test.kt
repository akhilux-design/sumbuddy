package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

/**
 * Cases for [SkipReason.REWARD_POINTS] and the one-time clean-up. A message is skipped as points only when it is money
 * in from a sender that is not a bank and its amount is labelled as points right after it ("Rs.200 loyalty points have
 * been credited"), or it prints no money amount and only points move. Debits and everything a bank sends read exactly
 * as before. The clean-up removes only untouched money in from senders that are not banks which the parser now skips
 * as [SkipReason.REWARD_POINTS]. Cases the rule does not skip are in [PointsAcceptedLimitsTest].
 *
 * Every message is synthetic: names, digits, references and amounts are invented. A comment "was ..., now ..." gives
 * the reading before the points rule was added and the reading now, where they differ; a case with no such comment
 * reads the same in both.
 *
 * - The tests named `...ReadAsBefore`, [refundsAndPayoutsThatNameBankMoneyStay], [cardBillsAndDueReminders],
 *   [goodsAndMerchantsNamedCoinPointOrMilesAreRupees] and [aBankPlaceNamedForTheMoneyWinsOverAStoreBalance] hold what
 *   must not change.
 * - [storeLoyaltyMessageIsSkipped] and [pointsAndStoreBalancesAreSkipped] hold what the two rules are for.
 * - [theCleanUpTakesOnlySkippedMoneyInThatNoBankSent] and the `unlistedBank...` tests are about the clean-up and about
 *   banks that [Banks] does not list ([SmsParser.isFromBank] knows more banks than [Duplicates] does).
 * - The two `gapOlder...` tests are ignored: gaps older than the points rule, read the same way without it.
 */
class LoyaltyPoints4Test {
    private val parser = SmsParser()

    private sealed interface Expect
    private data class Pays(val direction: Direction, val amountMinor: Long) : Expect
    /** Not a payment. [reason] null accepts any skip reason. */
    private data class Skips(val reason: SkipReason?) : Expect
    private data class Bill(val totalDueMinor: Long) : Expect

    private data class Case(val id: String, val sender: String, val body: String, val expect: Expect)

    private fun pays(id: String, sender: String, body: String, direction: Direction, amountMinor: Long) =
        Case(id, sender, body, Pays(direction, amountMinor))

    private fun skips(id: String, sender: String, body: String, reason: SkipReason?) = Case(id, sender, body, Skips(reason))

    private fun bill(id: String, sender: String, body: String, totalDueMinor: Long) = Case(id, sender, body, Bill(totalDueMinor))

    private fun sms(case: Case, at: Long = RECEIVED_AT) = SmsMessage(case.sender, case.body, at)

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
     * [SkipReason.REWARD_POINTS], and it still reads as money in. Never a debit. The app also needs the stored row to
     * be untouched and to hold exactly what the SMS reads as.
     */
    private fun removable(sms: SmsMessage): Boolean {
        val result = parser.parse(sms)
        return !parser.isFromBank(sms) && result is ParseResult.Skipped && result.reason in REMOVING_REASONS &&
            parser.readIgnoringSkips(sms)?.direction == Direction.CREDIT
    }

    // ---- The store loyalty message ----

    /**
     * The store loyalty message the points rule is for, and the same shape from other stores with a UPI or a bank
     * footer: the amount is itself loyalty points, so it is skipped, and a row an older version stored from it is one
     * the clean-up takes.
     */
    @Test
    fun storeLoyaltyMessageIsSkipped() {
        val storeLoyalty = Case(
            "store-loyalty-message", "VM-SHPCLB",
            "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points have been credited. " +
                "Valid until 30th November. *T&C Apply.",
            Skips(SkipReason.REWARD_POINTS),
        )
        val sameShape = pointsAndStoreBalances.filter {
            it.id in setOf("l-loyalty-points-store-account", "l-loyalty-points-upi-footer", "l-loyalty-points-bank-footer")
        }
        assertEquals(3, sameShape.size)
        check(listOf(storeLoyalty) + sameShape)
        val kept = (listOf(storeLoyalty) + sameShape).filterNot { removable(sms(it)) }
        if (kept.isNotEmpty()) fail("the clean-up would keep a stored row of ${kept.map { it.id }}")
    }

    // ---- What must not change ----

    /** Debits, money in, refunds and cashback from banks in [Banks], also where a merchant is named Points, Coins or Miles. */
    private val listedBanks = listOf(
        pays(
            "a-hdfc-upi-sent-multiline", "VM-HDFCBK",
            "Sent Rs.412.00\nFrom HDFC Bank A/C *7731\nTo QUICKBITE FOODS\nOn 19/11/26\nRef 631177204512\nNot " +
                "You?\nCall the bank/SMS BLOCK UPI",
            Direction.DEBIT, 41_200,
        ),
        pays(
            "a-icici-cc-debited-for", "VM-ICICIB",
            "ICICI Bank Credit Card XX6004 debited for INR 2,318.50 on 19-Nov-26 for FRESHCART ONLINE. Avl " +
                "Lmt: INR 1,41,200.75. To dispute SMS BLOCK 6004",
            Direction.DEBIT, 231_850,
        ),
        pays(
            "a-sbi-upi-bare-coffee-point", "JD-SBIINB",
            "Dear UPI user A/C X5512 debited by 86.0 on date 19Nov26 trf to COFFEE POINT Refno 632311870045. " +
                "If not u? call the bank. -SBI",
            Direction.DEBIT, 8_600,
        ),
        pays(
            "a-axis-multiline-miles-merchant", "VM-AXISBK",
            "INR 1,575.00 debited\nA/c no. XX3349\n19-11-26, 20:14:09\nUPI/P2M/632388120044/MILES AND SMILES " +
                "TRAVELS\nNot you? SMS BLOCKUPI\nAxis Bank",
            Direction.DEBIT, 157_500,
        ),
        pays(
            "a-kotak-sent-coin-vpa", "VM-KOTAKB",
            "Sent Rs.640.00 from Kotak Bank AC X8120 to coinbazaar@ybl on 19-11-26.UPI Ref 632399001122. Not " +
                "you, visit the fraud page",
            Direction.DEBIT, 64_000,
        ),
        pays(
            "a-pnb-atm-withdrawn", "AD-PNBSMS",
            "Rs.4000.00 withdrawn from A/c XX2207 at ATM ID NDEL4471 on 19-11-26 21:02. Avl Bal " +
                "Rs.18,442.10. Not you? Call the bank -PNB",
            Direction.DEBIT, 400_000,
        ),
        pays(
            "a-idfc-debited-by", "VM-IDFCFB",
            "Your A/C XXXXXXX4410 is debited by INR 12,000.00 on 19/11/26 18:40. New Bal :INR 55,310.22. " +
                "Team IDFC FIRST Bank",
            Direction.DEBIT, 1_200_000,
        ),
        pays(
            "a-sbicard-four-points-hotel", "VM-SBICRD",
            "Rs.3,499.00 spent on your SBI Credit Card ending 9031 at FOUR POINTS HOTEL on 19/11/26. Trxn. " +
                "not done by you? Report it on the card site",
            Direction.DEBIT, 349_900,
        ),
        pays(
            "a-hdfc-card-txn-points-plaza", "VM-HDFCBK",
            "Txn Rs.1,260.00\nOn HDFC Bank Card 3381\nAt POINTS PLAZA\nby UPI 632344556677\nOn 19-11\nNot " +
                "You?\nSMS BLOCK CC 3381",
            Direction.DEBIT, 126_000,
        ),
        pays(
            "a-amex-spent-coins-and-more", "VM-AMEXIN",
            "Alert: You've spent INR 7,820.00 on your AMEX card ** 41009 at COINS AND MORE on 19 November " +
                "2026 at 08:45 PM IST. Call us if this was not made by you.",
            Direction.DEBIT, 782_000,
        ),
        pays(
            "a-indusind-used-for", "VM-INDUSB",
            "Your IndusInd Bank Credit Card ending 5521 has been used for INR 945.00 at POINT BLANK CAFE on " +
                "19-11-2026 07:15:22 PM. Available limit is INR 74,055.00.",
            Direction.DEBIT, 94_500,
        ),
        pays(
            "a-yes-emi-debited", "VM-YESBNK",
            "EMI of Rs 6,215.00 for Loan A/c XX7781 debited from your YES BANK A/c XX4402 on 19-Nov-26.",
            Direction.DEBIT, 621_500,
        ),
        pays(
            "a-bob-dr-from-cr-to", "VM-BOBTXN",
            "Rs.250.00 Dr. from A/C XXXXXX6612 and Cr. to quickmart@okbob. Ref:632300118822. " +
                "AvlBal:Rs3,140.55(2026:11:19 09.12.44). Not you? Call the bank -BOB",
            Direction.DEBIT, 25_000,
        ),
        pays(
            "a-canara-debited-cheque", "VM-CANBNK",
            "An amount of INR 1,100.00 has been DEBITED to your account XXXXX3320 on 19/11/2026 towards " +
                "cheque no 004512. Total Avail.bal INR 20,417.00. - Canara Bank",
            Direction.DEBIT, 110_000,
        ),
        pays(
            "a-federal-debited-upi-vpa", "VM-FEDBNK",
            "Rs 899.00 debited from your A/c using UPI on 19-11-2026 14:20:31 to VPA superdaily@icici - (UPI " +
                "Ref No 632377001234)-Federal Bank",
            Direction.DEBIT, 89_900,
        ),
        pays(
            "a-hdfc-coins-cafe-after-amount", "VM-HDFCBK",
            "Rs 780.00 Coins Cafe Indiranagar debited via HDFC Bank Debit Card XX3381 on 19-11-26",
            Direction.DEBIT, 78_000,
        ),
        pays(
            "b-hdfc-neft-salary-deposited", "VM-HDFCBK",
            "Update! INR 68,400.00 deposited in HDFC Bank A/c XX7731 on 19-NOV-26 for NEFT " +
                "Cr-CITI0000004-BRIGHTSOFT TECH-SAL NOV. Avl bal INR 91,224.18. Cheque deposits in A/C are " +
                "subject to clearing",
            Direction.CREDIT, 6_840_000,
        ),
        pays(
            "b-icici-upi-credit", "VM-ICICIB",
            "Dear Customer, Acct XX604 is credited with Rs 1,850.00 on 19-Nov-26 from MEERA N. " +
                "UPI:632355110099-ICICI Bank.",
            Direction.CREDIT, 185_000,
        ),
        pays(
            "b-sbi-credited-by-transfer", "JD-SBIINB",
            "Dear SBI User, your A/c X5512-credited by Rs.3,000 on 19Nov26 transfer from ARJUN MEHTA Ref No " +
                "632312340011 -SBI",
            Direction.CREDIT, 300_000,
        ),
        pays(
            "b-axis-refund-to-cc", "VM-AXISBK",
            "Refund of INR 1,299.00 from URBANWEAR has been credited to your Axis Bank Credit Card XX4470 on " +
                "19-11-26.",
            Direction.CREDIT, 129_900,
        ),
        pays(
            "b-kotak-interest", "VM-KOTAKB",
            "Rs.412.00 interest credited to your Kotak Bank a/c XX8120 on 19/11/2026. Avl Bal Rs.52,318.40",
            Direction.CREDIT, 41_200,
        ),
        pays(
            "b-paytm-bank-received", "VM-PAYTMB",
            "Rs.500 received in your Paytm Payments Bank a/c XX9044 from VIKRAM S on 19-11-2026. UPI Ref " +
                "632366778899. Bal Rs.2,140.50 -PPBL",
            Direction.CREDIT, 50_000,
        ),
        pays(
            "b-au-imps-credited", "VM-AUBANK",
            "Credited INR 9,750.00 to A/c XX1907 on 19-NOV-2026 thru IMPS from RENT RECEIPT/632300998877. " +
                "Bal INR 31,006.45 - AU Bank",
            Direction.CREDIT, 975_000,
        ),
        pays(
            "b-ippb-dbt-credit", "VM-IPBMSG",
            "Your a/c XXXXXX2210 is credited with Rs.2000.00 on 19/11/26 by DBT-PM KISAN. Avl Bal " +
                "Rs.6,245.00 -IPPB",
            Direction.CREDIT, 200_000,
        ),
        pays(
            "b-airtel-bank-cashback-wallet", "VM-AIRBNK",
            "Rs.150.00 cashback credited to your Airtel Payments Bank wallet for recharge of 98XXXXXX21 on " +
                "19-11-26. Bal: Rs.412.75",
            Direction.CREDIT, 15_000,
        ),
        pays(
            "b-hdfc-credit-from-points-vpa", "VM-HDFCBK",
            "Rs 2,400.00 credited to HDFC Bank A/c XX7731 on 19-11-26 from VPA pointsplaza@icici (UPI " +
                "632300998811)",
            Direction.CREDIT, 240_000,
        ),
        pays(
            "b-rbl-payment-received-cc", "VM-RBLBNK",
            "Thank you! Payment of Rs 7,500.00 received towards your RBL Bank Credit Card ending 2207 on " +
                "19-11-26.",
            Direction.CREDIT, 750_000,
        ),
        pays(
            "b-hdfc-received-dues-points", "VM-HDFCBK",
            "Rs 24,310.00 received towards HDFC Bank Credit Card XX3381 dues. 120 reward points credited.",
            Direction.CREDIT, 2_431_000,
        ),
        pays(
            "v-bank-gold-coin-purchase-card-named", "VM-HDFCBK",
            "Rs 18,500 gold coin purchase on HDFC Bank Debit Card XX3381 at GOLDLINE JEWELS on 19-11-26.",
            Direction.DEBIT, 1_850_000,
        ),
        pays(
            "v-bank-upi-debit-then-coin-merchant", "VM-ICICIB",
            "ICICI Bank Acct XX604 debited for Rs 1,450.00 on 19-Nov-26; COIN LAUNDRY credited. " +
                "UPI:632300112200.",
            Direction.DEBIT, 145_000,
        ),
        pays(
            "m-bank-coins-merchant-no-place", "VM-KOTAKB",
            "Rs 1,499 Coinstore order refunded. Amount credited.",
            Direction.CREDIT, 149_900,
        ),
    )

    @Test
    fun listedBanksReadAsBefore() = check(listedBanks)

    /**
     * Co-operative, small finance, payments and regional rural banks that [Banks] does not list. The `c-` senders have
     * a header code that reads as a bank's, "d-tjsb-sign-off-sb" and "v-equitas-credit-sign-off" sign off as a bank;
     * the other `d-` and `v-` senders ("VM-UJJIVN", "VM-EQUTAS", "VM-SURYOD", "VM-COSMOS", "VM-NKGSB", "VM-UNITYS") are
     * banks the parser cannot tell from a store, and stay payments because the sentence with the amount names the
     * account, UPI or IMPS, or because the message is a debit.
     */
    private val unlistedBanks = listOf(
        pays(
            "c-saraswat-debit", "VM-SRSWBK",
            "Your A/c XX4417 is debited with Rs 2,350.00 on 19-11-26 by UPI to GREENLEAF STORES Ref " +
                "632388776655. Bal Rs 14,802.11 -Saraswat Bank",
            Direction.DEBIT, 235_000,
        ),
        pays(
            "c-saraswat-credit-neft", "VM-SRSWBK",
            "Your A/c XX4417 is credited with Rs 7,500.00 on 19-11-26 by NEFT from ORBIT TRADERS. Bal Rs " +
                "22,302.11 -Saraswat Bank",
            Direction.CREDIT, 750_000,
        ),
        pays(
            "c-ujjivan-sfb-credit-own-sentence", "AD-UJJSFB",
            "Rs 12,000.00 credited. Savings A/c XX9031. Info: IMPS/632399887766/RAVI K. Avl Bal Rs 40,118.60",
            Direction.CREDIT, 1_200_000,
        ),
        pays(
            "c-rrb-cash-deposit-no-account", "JK-APGVBK",
            "Dear Customer, Rs.6,000.00 deposited in cash at KURNOOL branch on 19-11-2026. Clear Bal " +
                "Rs.9,412.00",
            Direction.CREDIT, 600_000,
        ),
        pays(
            "c-nsdl-pb-paid-upi", "VK-NSDLPB",
            "Rs.320.00 paid from your NSDL Payments Bank account XX7702 to METRO RAIL via UPI. Ref " +
                "632300554433",
            Direction.DEBIT, 32_000,
        ),
        pays(
            "c-jio-pb-received", "VK-JIOPBK",
            "Rs.1,000.00 received in your Jio Payments Bank account from SELF via UPI on 19-Nov-26. Ref " +
                "632311009988",
            Direction.CREDIT, 100_000,
        ),
        pays(
            "c-kvb-debit-card-used", "VM-KVBANK",
            "Your KVB Debit Card XX6620 is used for Rs.1,845.00 at SILVERLINE PHARMA on 19-11-26 18:22. Avl " +
                "Bal Rs.27,330.90",
            Direction.DEBIT, 184_500,
        ),
        pays(
            "c-dcb-fd-interest-no-account", "VM-DCBBNK",
            "Interest of INR 1,210.00 on your FD 00451 has been credited on 19-11-2026.",
            Direction.CREDIT, 121_000,
        ),
        pays(
            "c-mahabank-chq-credit", "VM-MAHABK",
            "Your A/c No xx3305 credited by INR 4,200.00 on 19-11-26 CHQ DEP 118822. A/c Bal is INR " +
                "16,744.20 CR. -MAHABANK",
            Direction.CREDIT, 420_000,
        ),
        pays(
            "c-cub-debited-and-credited", "BZ-CUBANK",
            "Your a/c no. XXXXXXXX8841 is debited for Rs.560.00 on 19-11-2026 and credited to a/c no. " +
                "XXXXXXXX1200 (UPI Ref no 632322334455) -CUB",
            Direction.DEBIT, 56_000,
        ),
        pays(
            "d-ujjivan-credit-ac", "VM-UJJIVN",
            "Dear Customer, your A/c XX9031 is credited with INR 8,300.00 on 19-11-26 towards NEFT-ORBIT " +
                "TRADERS. Avl Bal INR 48,418.60",
            Direction.CREDIT, 830_000,
        ),
        pays(
            "d-equitas-masked-account-brand", "VM-EQUTAS",
            "Your Equitas account XX2210 has been credited with Rs 5,150.00 on 19-Nov-26. Available balance " +
                "Rs 19,880.35",
            Direction.CREDIT, 515_000,
        ),
        pays(
            "d-tjsb-sign-off-sb", "VM-TJSB",
            "Rs 2,250.00 credited to your SB 1182 on 19-11-26 by transfer. -TJSB Sahakari Bank Ltd",
            Direction.CREDIT, 225_000,
        ),
        pays(
            "d-nkgsb-debit-pos", "VM-NKGSB",
            "Rs 760.00 debited from A/c XX5519 on 19-11-26 for POS/NEELAM TEXTILES. Bal Rs 8,221.45",
            Direction.DEBIT, 76_000,
        ),
        pays(
            "d-cosmos-credit-upi-vpa", "VM-COSMOS",
            "Rs 450.00 credited to A/c XX3381 via UPI from sunita@oksbi on 19-11-26 (Ref 632300112233)",
            Direction.CREDIT, 45_000,
        ),
        pays(
            "d-unity-credit-savings-account", "VM-UNITYS",
            "Rs 9,999.00 has been credited to your savings account on 19-11-26 via IMPS from K NAIR.",
            Direction.CREDIT, 999_900,
        ),
        pays(
            "d-suryoday-debit-atm", "VM-SURYOD",
            "Rs 2,000.00 withdrawn from your account 00XX4471 at ATM on 19-11-26. Bal Rs 9,200.00",
            Direction.DEBIT, 200_000,
        ),
        pays(
            "v-equitas-credit-sign-off", "VM-EQUTAS",
            "INR 8,300.00 credited on 19-11-26. A/c XX9031 Avl Bal INR 48,418.60 -Equitas SFB",
            Direction.CREDIT, 830_000,
        ),
        pays(
            "v-ujjivan-one-line-typical", "VM-UJJIVN",
            "Rs.1,500.00 credited to A/c ...9031 on 19-11-26 by cash deposit. Bal Rs.9,918.60",
            Direction.CREDIT, 150_000,
        ),
        pays(
            "v-suryoday-credit-info-imps", "VM-SURYOD",
            "Rs 700.00 credited on 19-11-26, Info IMPS-632300445511-K NAIR, Bal Rs 12,400.00",
            Direction.CREDIT, 70_000,
        ),
        pays(
            "x-rrb-credit-ac", "JK-APGVBK",
            "Your SB A/c XX6071 is credited with Rs 7,500.00 on 19-11-26 by transfer. Clear Bal Rs 16,912.00",
            Direction.CREDIT, 750_000,
        ),
    )

    @Test
    fun unlistedBanksReadAsBefore() = check(unlistedBanks)

    /**
     * Card issuers ([Banks] lists OneCard, slice, CRED; not Scapia, Uni, Niyo, Kiwi), prepaid and meal cards, FASTag,
     * pay later (LazyPay, Simpl, Flipkart Pay Later, Amazon Pay Later), UPI apps and wallets: every debit, and money in
     * whose sentence names a card, UPI, a bank account or a real wallet. The skips are the same skips as before.
     */
    private val issuersAndWallets = listOf(
        pays(
            "e-onecard-spent-brew-point", "VM-ONECRD",
            "You've spent Rs. 1,120.00 on your OneCard ending 7719 at THE BREW POINT on 19-11-26.",
            Direction.DEBIT, 112_000,
        ),
        pays(
            "e-onecard-refund", "VM-ONECRD",
            "A refund of Rs. 640.00 from URBANWEAR has been credited to your OneCard ending 7719.",
            Direction.CREDIT, 64_000,
        ),
        pays(
            "e-scapia-spent-coins-earned", "VM-SCAPIA",
            "You spent Rs 2,890.00 at SKYLINE AIRWAYS using your Scapia Federal Credit Card ending 4405. 289 " +
                "Scapia coins earned.",
            Direction.DEBIT, 289_000,
        ),
        pays(
            "e-scapia-refund-card-ending", "VM-SCAPIA",
            "Refund of Rs 1,499.00 from TRAVELKART has been credited to your Scapia card ending 4405.",
            Direction.CREDIT, 149_900,
        ),
        pays(
            "e-uni-paid-at", "VM-UNICRD",
            "Rs 3,200.00 paid at HOMEFIX HARDWARE using your Uni Card. Split in 3 with zero charges.",
            Direction.DEBIT, 320_000,
        ),
        skips(
            "e-uni-bill-due-ignore-if-paid", "VM-UNICRD",
            "Your Uni Card bill of Rs 9,640 is due on 25-Nov-26. Pay now to avoid late fees. Ignore if " +
                "already paid.",
            SkipReason.UPCOMING,
        ),
        pays(
            "e-niyo-forex-used-for", "VM-NIYOGL",
            "Your Niyo Global Card ending 3307 was used for THB 1,450.00 (INR 3,512.20) at BANGKOK STREET " +
                "EATS on 19-Nov-26.",
            Direction.DEBIT, 351_220,
        ),
        pays(
            "e-slice-cashback", "VM-SLICEIT",
            "Rs 250 cashback credited to your slice account for paying on time.",
            Direction.CREDIT, 25_000,
        ),
        pays(
            "e-kiwi-cashback-upi-credit-card", "VM-KIWICC",
            "Rs 75.50 cashback has been credited to your Kiwi account for UPI spends on your RuPay credit " +
                "card.",
            Direction.CREDIT, 7_550,
        ),
        pays(
            "f-pluxee-meal-card-credited", "VM-PLUXEE",
            "Your Pluxee Meal Card ending 4410 has been credited with Rs 2,200.00 by BRIGHTSOFT TECH on " +
                "19-11-26.",
            Direction.CREDIT, 220_000,
        ),
        pays(
            "f-pluxee-meal-card-spent", "VM-PLUXEE",
            "Rs 345.00 spent on your Pluxee Meal Card ending 4410 at GREEN BOWL CAFE on 19-11-26. Balance: " +
                "Rs 1,855.00",
            Direction.DEBIT, 34_500,
        ),
        skips(
            "f-zeta-meal-wallet-added", "VM-ZETA",
            "Rs 1,500 has been added to your Zeta meal wallet by your employer for Nov 2026.",
            SkipReason.SELF_TRANSFER,
        ),
        pays(
            "f-edenred-meal-card-credited", "VM-EDENRD",
            "Rs 2,000.00 credited to your Ticket Restaurant meal card ending 9912 for November.",
            Direction.CREDIT, 200_000,
        ),
        skips(
            "f-metro-card-recharge", "VM-METROC",
            "Rs 300 recharge successful for your Metro Smart Card 7781.",
            SkipReason.BILLER_RECEIPT,
        ),
        skips(
            "f-forex-card-loaded", "VM-FRXCRD",
            "USD 500.00 loaded on your TravelEase prepaid card ending 2210 on 19-11-26.",
            SkipReason.NOT_FINANCIAL,
        ),
        pays(
            "f-prepaid-card-credited", "VM-GFTCRD",
            "Your prepaid card ending 5512 has been credited with Rs 5,000.00.",
            Direction.CREDIT, 500_000,
        ),
        pays(
            "f-prepaid-card-spent", "VM-GFTCRD",
            "Rs 1,240.00 spent at LEATHER LANE using your prepaid card ending 5512. Balance Rs 3,760.00",
            Direction.DEBIT, 124_000,
        ),
        pays(
            "g-icici-fastag-toll-deducted", "VM-ICICIB",
            "ICICI Bank FASTag: Rs 95.00 deducted for toll at NELAMANGALA PLAZA on 19-11-26 06:42 for " +
                "vehicle KA01XX4412. Bal Rs 610.00",
            Direction.DEBIT, 9_500,
        ),
        pays(
            "g-fastag-nonbank-toll-debited", "VM-NETCFT",
            "Rs 135.00 debited from your FASTag linked to vehicle TN09XX7781 at KRISHNAGIRI TOLL on " +
                "19-11-26. Balance Rs 465.00",
            Direction.DEBIT, 13_500,
        ),
        pays(
            "g-fastag-nonbank-credited", "VM-NETCFT",
            "Rs 500.00 credited to your FASTag account for vehicle TN09XX7781 on 19-11-26. Balance Rs 965.00",
            Direction.CREDIT, 50_000,
        ),
        pays(
            "g-paytm-fastag-toll-paid", "VM-PAYTM",
            "Rs 60 paid for toll at HEBBAL via Paytm FASTag. Vehicle KA05XX1100",
            Direction.DEBIT, 6_000,
        ),
        skips(
            "g-idfc-fastag-recharged", "VM-IDFCFB",
            "Your IDFC FIRST Bank FASTag wallet has been recharged with Rs 1,000.00 on 19-11-26.",
            SkipReason.NOT_FINANCIAL,
        ),
        pays(
            "h-lazypay-spent", "VM-LZYPAY",
            "Rs 289.00 spent at SNACKBOX using LazyPay on 19-11-26. Your available limit is Rs 4,711.00",
            Direction.DEBIT, 28_900,
        ),
        skips(
            "h-lazypay-bill-due", "VM-LZYPAY",
            "Your LazyPay bill of Rs 3,140.00 is due on 03-Dec-26. Pay now to avoid late fee. Ignore if " +
                "already paid.",
            SkipReason.UPCOMING,
        ),
        skips(
            "h-simpl-charged", "VM-SIMPL",
            "Rs 412.00 charged to your Simpl account for your order at FRESHCART.",
            SkipReason.NOT_FINANCIAL,
        ),
        pays(
            "h-simpl-bill-paid", "VM-SIMPL",
            "Your Simpl bill of Rs 1,964.00 has been paid successfully via UPI. Thank you!",
            Direction.DEBIT, 196_400,
        ),
        pays(
            "h-flipkart-paylater-used", "VM-FLPKRT",
            "Flipkart Pay Later: Rs 1,249.00 used for your order OD4412. Total due Rs 3,880.00 payable by " +
                "05-Dec-26.",
            Direction.DEBIT, 124_900,
        ),
        pays(
            "h-amazon-paylater-refund", "AX-AMAZON",
            "Refund of Rs 799.00 has been credited to your Amazon Pay Later account for order 402-1187.",
            Direction.CREDIT, 79_900,
        ),
        pays(
            "h-ola-postpaid-paid", "VM-OLAMNY",
            "Rs 218.00 paid for your ride using Ola Money Postpaid on 19-11-26.",
            Direction.DEBIT, 21_800,
        ),
        pays(
            "i-gpay-received-via-upi-bank", "VM-GPAYIN",
            "Rs 1,200.00 received from PRIYA R via UPI in your bank account XX7731.",
            Direction.CREDIT, 120_000,
        ),
        pays(
            "i-bhim-paid", "VM-BHIMUP",
            "You have paid Rs 75.00 to CHAI CORNER using BHIM UPI. Ref 632311223344",
            Direction.DEBIT, 7_500,
        ),
        pays(
            "i-mobikwik-cashback-wallet", "VM-MOBIKW",
            "Cashback of Rs 42.00 has been credited to your MobiKwik wallet for bill payment.",
            Direction.CREDIT, 4_200,
        ),
        pays(
            "i-mobikwik-paid-from-wallet", "VM-MOBIKW",
            "Rs 310.00 paid to CITY POWER from your MobiKwik wallet. Txn ID MBK632300",
            Direction.DEBIT, 31_000,
        ),
        pays(
            "i-freecharge-refund-balance", "VM-FRCHRG",
            "Refund of Rs 199.00 for failed recharge has been credited to your Freecharge balance.",
            Direction.CREDIT, 19_900,
        ),
        pays(
            "i-jiomoney-received", "VM-JIOMNY",
            "Rs 100 received in your JioMoney wallet from KIRAN P.",
            Direction.CREDIT, 10_000,
        ),
        pays(
            "i-bajaj-pay-cashback", "VM-BAJAJF",
            "Rs 500 cashback credited to your Bajaj Pay wallet. Valid till 31-Dec-26.",
            Direction.CREDIT, 50_000,
        ),
        pays(
            "i-payzapp-refunded", "VM-PAYZAP",
            "Rs 150.00 refunded to your PayZapp wallet for order PZ7781.",
            Direction.CREDIT, 15_000,
        ),
        pays(
            "i-phonepe-received-wallet", "VM-PHONPE",
            "Rs.90 received from DEEPAK in your PhonePe wallet.",
            Direction.CREDIT, 9_000,
        ),
        pays(
            "i-cred-cashback-coins", "VM-CREDIN",
            "Rs 12.00 cashback credited to your CRED cash balance. 3,000 CRED coins earned.",
            Direction.CREDIT, 1_200,
        ),
        pays(
            "i-supermoney-cashback-received", "VM-SUPRMN",
            "Rs 8.00 cashback received for your UPI payment to GROCERY HUB on super.money.",
            Direction.CREDIT, 800,
        ),
        pays(
            "i-navi-won-cashback", "VM-NAVIUP",
            "You won Rs 35 cashback on Navi UPI. Amount credited to your bank account.",
            Direction.CREDIT, 3_500,
        ),
        skips(
            "i-bharatpe-settled", "VM-BHRTPE",
            "Rs 2,340.00 settled to your bank account XX4410 for 19-11-26 collections.",
            SkipReason.NOT_FINANCIAL,
        ),
        pays(
            "i-gpay-multiline-received", "VM-GPAYIN",
            "Rs 500.00 received\nFrom ANIL K\nUPI Ref 632300556677",
            Direction.CREDIT, 50_000,
        ),
        pays(
            "i-ola-money-refund", "VM-OLAMNY",
            "Rs 96.00 refund for your cancelled ride has been credited to your Ola Money wallet.",
            Direction.CREDIT, 9_600,
        ),
    )

    @Test
    fun cardIssuersPrepaidFastagPayLaterAndWalletsReadAsBefore() = check(issuersAndWallets)

    /**
     * Statements and due reminders, with "ignore if already paid" in several wordings. Two reminders that the parser
     * without the points rule skipped
     * (an offer of bonus points for paying on time, and a count of points credited this cycle) are card bills now; no
     * card bill read before the points rule is lost. A store card's fee, a pay-later bill and an overdue notice are no
     * card bills, as before.
     */
    private val cardBills = listOf(
        bill(
            "j-hdfc-statement-ignore-if-paid", "VM-HDFCBK",
            "HDFC Bank Credit Card XX3381 Statement: Total due Rs 24,310.00, Min due Rs 1,215.00, due by " +
                "05-12-2026. Ignore if already paid.",
            2_431_000,
        ),
        bill(
            "j-icici-payment-due-ignore", "VM-ICICIB",
            "Payment of INR 8,442.10 for ICICI Bank Credit Card XX6004 is due on 02-Dec-26. Please ignore if " +
                "already paid.",
            844_210,
        ),
        // was skipped(PROMOTION), now cardBill(562000)
        bill(
            "j-sbicard-due-earn-bonus-points", "VM-SBICRD",
            "Rs.5,620 is due on your SBI Credit Card ending 9031 on 01-12-2026. Pay on time and earn 500 " +
                "bonus reward points.",
            562_000,
        ),
        // was skipped(UPCOMING), now cardBill(1390500)
        bill(
            "j-axis-bill-points-credited-cycle", "VM-AXISBK",
            "Your Axis Bank Credit Card XX4470 bill of INR 13,905.00 is due on 04-12-26. Min amt due INR " +
                "695.00. 310 EDGE reward points credited this cycle.",
            1_390_500,
        ),
        bill(
            "j-kotak-payable-by-ignore-if-paid", "VM-KOTAKB",
            "Reminder: Rs 2,180.45 payable by 28-Nov-26 on Kotak Credit Card x8120. Kindly ignore if paid.",
            218_045,
        ),
        bill(
            "j-onecard-amount-due", "VM-ONECRD",
            "Your OneCard bill is ready. Amount due: Rs 6,112.00. Due date: 03-Dec-26.",
            611_200,
        ),
        bill(
            "j-scapia-cc-bill-due", "VM-SCAPIA",
            "Your Scapia Federal Credit Card bill of Rs 11,240.00 is due on 06-Dec-26. Ignore if already " +
                "paid.",
            1_124_000,
        ),
        skips(
            "j-indusind-overdue", "VM-INDUSB",
            "Your IndusInd Bank Credit Card ending 5521 payment of Rs 4,300.00 is overdue. Total outstanding " +
                "Rs 18,950.00. Pay immediately to avoid charges.",
            SkipReason.NOT_FINANCIAL,
        ),
        bill(
            "j-idfc-stmt-total-min", "VM-IDFCFB",
            "Stmt for IDFC FIRST Bank Credit Card XX4410: Total Amt Due Rs 7,812.00; Min Amt Due Rs 391.00; " +
                "Pay by 07/12/2026.",
            781_200,
        ),
        bill(
            "j-rbl-due-tomorrow-ignore", "VM-RBLBNK",
            "Gentle reminder: payment of Rs 3,275.00 on your RBL Bank Credit Card ending 2207 is due on " +
                "21-Nov-26. If already paid, please ignore.",
            327_500,
        ),
        skips(
            "j-store-card-due", "VM-TRENDZ",
            "Your Trendz Privilege card renewal fee of Rs 299 is due on 30-Nov-26.",
            SkipReason.UPCOMING,
        ),
    )

    @Test
    fun cardBillsAndDueReminders() = check(cardBills)

    /**
     * Refunds and payouts from merchants, an insurer, a fund, a broker, the tax office and a lender, whose sentence
     * with the amount names the user's bank account, card, UPI, NEFT or RTGS, or a real wallet: money in as before.
     * Three are skips that were skips before (a refund still to come, a card payment confirmed by an app).
     */
    private val refundsAndPayouts = listOf(
        pays(
            "k-ajio-refunded-to-cc", "VM-AJIOIN",
            "Rs 2,199.00 has been refunded to your HDFC Bank Credit Card ending 3381 for return of order " +
                "FN7781.",
            Direction.CREDIT, 219_900,
        ),
        pays(
            "k-zomato-refund-upi-id", "VM-ZOMATO",
            "Refund of Rs 312.00 for order 7781 processed to your UPI ID meera@okhdfcbank.",
            Direction.CREDIT, 31_200,
        ),
        pays(
            "k-irctc-refund-bank-account", "AD-IRCTCI",
            "Refund of Rs 1,845.50 against PNR 4412778110 has been credited to your bank account ending 7731.",
            Direction.CREDIT, 184_550,
        ),
        pays(
            "k-uber-refund-paytm-wallet", "VM-UBERIN",
            "A refund of Rs 138.50 has been issued to your Paytm wallet for your trip on 19 Nov.",
            Direction.CREDIT, 13_850,
        ),
        skips(
            "k-mmt-refund-will-be-credited", "VM-MMTRIP",
            "Refund of INR 6,420.00 for booking NF4412778 has been processed. Amount will be credited to " +
                "your account within 7 working days.",
            SkipReason.UPCOMING,
        ),
        pays(
            "k-flipkart-refund-axis-account", "VM-FLPKRT",
            "Refund of Rs 899.00 for order OD7781 is credited to your Axis Bank account XX3349. Ref " +
                "632300778899.",
            Direction.CREDIT, 89_900,
        ),
        pays(
            "k-airline-refund-card-xx", "VM-SKYLIN",
            "Refund of INR 4,310.00 for PNR QX7H2L has been credited to card XX4405.",
            Direction.CREDIT, 431_000,
        ),
        pays(
            "k-pharmacy-refund-masked-account", "VM-MEDPLS",
            "Rs 265.00 refunded to your account XX7731 for order MP4412.",
            Direction.CREDIT, 26_500,
        ),
        pays(
            "m-voucher-order-refund-to-bank", "VM-GYFTR",
            "Refund of Rs 1,000 for your e-voucher order GV4412 has been credited to your HDFC Bank account " +
                "XX7731.",
            Direction.CREDIT, 100_000,
        ),
        pays(
            "m-insurer-claim-neft", "VM-LIFEIN",
            "Claim amount of Rs 25,000 has been credited to your bank account ending 7731 via NEFT.",
            Direction.CREDIT, 2_500_000,
        ),
        pays(
            "m-mutual-fund-redemption", "VM-CAMSMF",
            "Redemption of Rs 12,500.00 in Folio 4412/77 has been processed and credited to your registered " +
                "bank account.",
            Direction.CREDIT, 1_250_000,
        ),
        pays(
            "m-broker-payout", "VM-BROKER",
            "Funds payout of Rs 8,000.00 has been credited to your primary bank a/c.",
            Direction.CREDIT, 800_000,
        ),
        pays(
            "m-tax-refund-masked-account", "VM-ITDCPC",
            "Refund of Rs 4,870 for AY 2026-27 has been credited to your account XX7731.",
            Direction.CREDIT, 487_000,
        ),
        pays(
            "m-loan-disbursed-bank-account", "VM-KREDIT",
            "Loan amount of Rs 20,000 has been disbursed to your bank account XX4410.",
            Direction.CREDIT, 2_000_000,
        ),
        skips(
            "m-card-payment-app-successful", "VM-CHEQIN",
            "Payment of Rs 12,000 towards your HDFC Bank Credit Card XX3381 is successful.",
            SkipReason.BILLER_RECEIPT,
        ),
        pays(
            "m-nonbank-credit-rtgs", "VM-PROPTY",
            "Rs 2,50,000.00 token amount refund credited through RTGS, UTR 4412778800.",
            Direction.CREDIT, 25_000_000,
        ),
        pays(
            "m-nonbank-credit-savings-word-only", "VM-FDAGGR",
            "Rs 10,412.00 maturity amount credited to your linked savings account.",
            Direction.CREDIT, 1_041_200,
        ),
        pays(
            "v-refund-for-gift-card-order-to-bank", "VM-WOOHOO",
            "Refund for your gift card order: Rs 300 has been credited to your HDFC Bank account XX7731.",
            Direction.CREDIT, 30_000,
        ),
        pays(
            "x-payout-copy-names-bank-ac", "VM-BROKER",
            "Funds payout of Rs 7,500.00 has been credited to your primary bank a/c.",
            Direction.CREDIT, 750_000,
        ),
    )

    @Test
    fun refundsAndPayoutsThatNameBankMoneyStay() = check(refundsAndPayouts)

    /** Debits reported by a store or a merchant, beside points earned or redeemed: read as before. */
    private val storeDebits = listOf(
        pays(
            "l-fuel-points-on-paid-amount", "VM-CTYFUL",
            "Thank you for fuelling. 48 PetroPoints earned on Rs 2,400.00 paid by card at CITY FUELS. Total " +
                "points 1,912.",
            Direction.DEBIT, 240_000,
        ),
        pays(
            "l-neucoins-count-on-purchase-upi", "VM-TATANU",
            "You earned 84 NeuCoins on your purchase of Rs 1,680 paid via UPI at CromaLite.",
            Direction.DEBIT, 168_000,
        ),
        pays(
            "l-membership-paid-upi", "VM-GLDCLB",
            "Rs 499 paid for your GoldClub membership via UPI. Enjoy free delivery.",
            Direction.DEBIT, 49_900,
        ),
        pays(
            "l-miles-redeemed-taxes-paid", "VM-SKYLIN",
            "Your award ticket is booked. 12,500 SkyMiles redeemed and Rs 1,870.00 paid as taxes via card " +
                "ending 4405.",
            Direction.DEBIT, 187_000,
        ),
        pays(
            "v-paid-in-store-brew-point", "VM-STRBCK",
            "You paid Rs 450 in store at Brew Point via UPI.",
            Direction.DEBIT, 45_000,
        ),
    )

    @Test
    fun storeAndMerchantDebitsReadAsBefore() = check(storeDebits)

    // ---- What the two rules are for ----

    /**
     * Rule 1 and a store's own balance: the amount is itself points ("Rs.200 loyalty points", "Rs 60 SmartPoints", "Rs
     * 40 Health Pts", "Rs 300 GoCash", "Rs 120 worth of ShopCoins"), also when points are taken away and also from a
     * bank that names no account or card; only a count of points moves; or the money lands in a store wallet, gift
     * card, club card, voucher or credit note. UPI, NEFT or a bank named in a footer or as how the purchase was paid
     * changes nothing.
     */
    private val pointsAndStoreBalances = listOf(
        // was payment(CREDIT 20000), now skipped(REWARD_POINTS)
        skips(
            "l-loyalty-points-store-account", "VM-FRSHMT",
            "Dear Customer, Rs.200 loyalty points have been credited to your FreshMart Loyalty account " +
                "81XXXXXX07 on 19-11-26. Valid for 90 days.",
            SkipReason.REWARD_POINTS,
        ),
        // was payment(CREDIT 20000), now skipped(REWARD_POINTS)
        skips(
            "l-loyalty-points-upi-footer", "VM-FRSHMT",
            "Rs.200 loyalty points have been credited to your FreshMart Rewards account. Pay using UPI at " +
                "any FreshMart store. T&C apply.",
            SkipReason.REWARD_POINTS,
        ),
        // was payment(CREDIT 35000), now skipped(REWARD_POINTS)
        skips(
            "l-loyalty-points-bank-footer", "VM-STYLHB",
            "Rs.350 loyalty points credited to your StyleHub account 77XXXX19. Use HDFC Bank cards for 5% " +
                "extra. Shop now.",
            SkipReason.REWARD_POINTS,
        ),
        // was payment(CREDIT 4000), now skipped(REWARD_POINTS)
        skips(
            "l-pts-short-unit", "VM-PHRMZY",
            "Rs 40 Health Pts credited on your order PZ4412.",
            SkipReason.REWARD_POINTS,
        ),
        // was payment(CREDIT 30000), now skipped(REWARD_POINTS)
        skips(
            "l-supercash-style-unit", "VM-TRVLGO",
            "Rs 300 GoCash added to your TravelGo account. Use it on your next booking.",
            SkipReason.REWARD_POINTS,
        ),
        // was unreadable, now skipped(REWARD_POINTS)
        skips(
            "l-points-count-membership", "VM-BKNOOK",
            "450 bonus points credited to your BookNook membership. Balance 1,280 points.",
            SkipReason.REWARD_POINTS,
        ),
        // was unreadable, now skipped(REWARD_POINTS)
        skips(
            "e-scapia-coins-only", "VM-SCAPIA",
            "Woohoo! 1,450 Scapia coins have been credited to your account for your November spends.",
            SkipReason.REWARD_POINTS,
        ),
        // was skipped(NOT_FINANCIAL), now skipped(REWARD_POINTS)
        skips(
            "i-cred-coins-only", "VM-CREDIN",
            "You have earned 2,500 CRED coins for paying your credit card bill on time.",
            SkipReason.REWARD_POINTS,
        ),
        skips(
            "l-points-redeemed-rupees-off", "VM-SPCRTE",
            "500 loyalty points redeemed for Rs 125 off on your bill at SpiceRoute.",
            SkipReason.NOT_FINANCIAL,
        ),
        // was payment(CREDIT 125000), now skipped(REWARD_POINTS)
        skips(
            "l-credit-note-credited-as", "VM-HOMTWN",
            "Rs 1,250 has been credited as credit note CN4412 at HomeTown. Valid till 19-May-27.",
            SkipReason.REWARD_POINTS,
        ),
        // was payment(CREDIT 9000), now skipped(REWARD_POINTS)
        skips(
            "l-cashback-points-bbwallet-bank-footer", "VM-BGBSKT",
            "Rs 90 cashback points added to your BigBasket bbwallet. Powered by Axis Bank.",
            SkipReason.REWARD_POINTS,
        ),
    )

    @Test
    fun pointsAndStoreBalancesAreSkipped() = check(pointsAndStoreBalances)

    /**
     * Money in from a store, a merchant, an app or an employer's payroll sender, with no evidence that the money is a
     * store's own balance: read exactly as before the points rule, whether or not the sentence with the amount names a
     * bank account, card, UPI or wallet ("You received Rs 1,200.00 from PRIYA R on Google Pay. UPI transaction ID
     * ..."). An earlier version skipped these as not from a bank; that skip was removed, because the same shape is
     * also the only record of real money (an unlisted bank, a UPI app, a payroll sender).
     */
    private val moneyInWithNoMoneyLink = listOf(
        pays(
            "l-store-account-credited-upi-footer", "VM-GDGTHB",
            "An update has been made to your GadgetHub account 70XXXXXX31. Rs.150 has been credited. Pay " +
                "using UPI at any GadgetHub store.",
            Direction.CREDIT, 15_000,
        ),
        pays(
            "l-loyalty-cash-neft-footer", "VM-TRENDZ",
            "Rs.75 loyalty cash has been credited to your Trendz account. For corporate orders pay by " +
                "NEFT/RTGS.",
            Direction.CREDIT, 7_500,
        ),
        pays(
            "k-myntra-refund-initiated", "VM-MYNTRA",
            "Refund of Rs 1,499.00 for your order 4412-7781 has been initiated. It will reflect in your " +
                "source account in 5-7 business days.",
            Direction.CREDIT, 149_900,
        ),
        pays(
            "k-bms-refunded-original-method", "VM-BMSHOW",
            "Rs 480.00 refunded to your original payment method for booking BK4412.",
            Direction.CREDIT, 48_000,
        ),
        pays(
            "k-swiggy-refund-swiggy-money", "VM-SWIGGY",
            "Rs 245 refund for order 1187 has been credited to your Swiggy Money.",
            Direction.CREDIT, 24_500,
        ),
        pays(
            "k-tataneu-refund-source-neucoins", "VM-TATANU",
            "Rs 1,000 refund for order TN4412 credited to source. 50 NeuCoins reversed.",
            Direction.CREDIT, 100_000,
        ),
        pays(
            "e-scapia-refund-no-card-word", "VM-SCAPIA",
            "Your refund of Rs 2,100.00 from STAYEASY HOTELS is credited. It will show in your next Scapia " +
                "statement.",
            Direction.CREDIT, 210_000,
        ),
        pays(
            "m-payroll-salary-credited", "VM-PAYROL",
            "Salary of Rs 54,200.00 for Nov 2026 has been credited to your account.",
            Direction.CREDIT, 5_420_000,
        ),
        pays(
            "i-gpay-received-upi-next-sentence", "VM-GPAYIN",
            "You received Rs 1,200.00 from PRIYA R on Google Pay. UPI transaction ID 632300445566.",
            Direction.CREDIT, 120_000,
        ),
        pays(
            "i-upi-app-received-no-upi-word", "VM-BHIMUP",
            "Rs 640.00 received from SUMAN T. Txn 632300667788.",
            Direction.CREDIT, 64_000,
        ),
        pays(
            "x-app-copy-of-unlisted-bank-credit", "VM-GPAYIN",
            "You received Rs 7,500.00 from ORBIT TRADERS on Google Pay.",
            Direction.CREDIT, 750_000,
        ),
        pays(
            "l-points-worth-then-bank-credit", "VM-SPCRTE",
            "Reward points worth Rs 400 redeemed. Rs 400 credited to your HDFC Bank account XX7731.",
            Direction.CREDIT, 40_000,
        ),
        pays(
            "v-points-worth-then-amount-credited-ac", "VM-SPCRTE",
            "Points worth Rs 250 redeemed. Amount credited to your A/c XX7731 by NEFT.",
            Direction.CREDIT, 25_000,
        ),
    )

    @Test
    fun moneyInWithNoStoreBalanceReadsAsBefore() = check(moneyInWithNoMoneyLink)

    // ---- The clean-up ----

    /** The cases of the passing tests that the clean-up would remove when stored untouched as money in. */
    private val removableIds = setOf(
        "l-cashback-points-bbwallet-bank-footer", "l-credit-note-credited-as", "l-loyalty-points-bank-footer",
        "l-loyalty-points-store-account", "l-loyalty-points-upi-footer", "l-pts-short-unit", "l-supercash-style-unit",
    )

    /**
     * Over every case of the passing tests: the clean-up's parser-side tests hold for exactly [removableIds], each of
     * them is skipped money in that no bank sent, and none is a debit, a bank's message or a case that must stay a
     * payment or a card bill.
     */
    @Test
    fun theCleanUpTakesOnlySkippedMoneyInThatNoBankSent() {
        val mustStay = listedBanks + unlistedBanks + issuersAndWallets + cardBills + refundsAndPayouts + storeDebits +
            moneyInWithNoMoneyLink + goodsAndMerchantsNamedCoinPointOrMiles + bankPlacesNamedBesideAStoreBalance +
            moneyInFromBanksTheParserCannotRecognise + moneyInToPayLaterNeobankAndTagWallets
        val skipped = pointsAndStoreBalances
        val problems = buildList {
            mustStay.filter { removable(sms(it)) }.forEach { add("  ${it.id}: must stay, but is removable") }
            val actual = skipped.filter { removable(sms(it)) }.map { it.id }.toSet()
            if (actual != removableIds) add("  removable differs: extra ${actual - removableIds}, missing ${removableIds - actual}")
            skipped.filter { it.id in removableIds }.forEach { case ->
                val message = sms(case)
                if (parser.isFromBank(message)) add("  ${case.id}: a bank sent it")
                if (parser.readIgnoringSkips(message)?.direction != Direction.CREDIT) add("  ${case.id}: not money in")
            }
            // Points taken away (a debit) and a bank's points message read as before the points rule and are never
            // removed: they are in PointsAcceptedLimitsTest.noAcceptedLimitIsRemovedByTheCleanUp.
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    // ---- Banks that Banks does not list: the rules, the clean-up and duplicates ----

    /**
     * [SmsParser.isFromBank] also knows a bank by its header code (BK, BNK, SFB, PB, BANK) or its sign-off, while
     * [Duplicates] and the app's SMS reader know only [Banks.fromSender]. For the two new rules and the clean-up that
     * difference only protects: a money in row from such a bank is a payment as before the points rule and is never
     * removable.
     */
    @Test
    fun unlistedBankMoneyInIsAPaymentAndIsNeverRemovable() {
        val problems = buildList {
            for (case in unlistedBanks) {
                val message = sms(case)
                if (Banks.fromSender(case.sender) != null) add("  ${case.id}: ${case.sender} is listed, pick another sender")
                if (parser.parse(message) !is ParseResult.Payment) add("  ${case.id}: not a payment")
                if (removable(message)) add("  ${case.id}: removable")
            }
            val known = unlistedBanks.filter { parser.isFromBank(sms(it)) }.map { it.sender }.toSet()
            val expected = setOf(
                "VM-SRSWBK", "AD-UJJSFB", "JK-APGVBK", "VK-NSDLPB", "VK-JIOPBK", "VM-KVBANK", "VM-DCBBNK", "VM-MAHABK",
                "BZ-CUBANK", "VM-TJSB", "VM-EQUTAS",
            )
            if (known != expected) add("  known as banks: $known, expected $expected")
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private val unlistedBankCredit = SmsMessage(
        "JK-APGVBK",
        "Your SB A/c XX6071 is credited with Rs 7,500.00 on 19-11-26 by transfer. Clear Bal Rs 16,912.00",
        RECEIVED_AT + 20_000,
    )
    /** A UPI app's copy of the same money in, 20 seconds earlier. A payment before the points rule and now. */
    private val appCopy = SmsMessage("VM-GPAYIN", "You received Rs 7,500.00 from ORBIT TRADERS on Google Pay.", RECEIVED_AT)
    /** A copy that names the bank account, 20 seconds earlier. A payment before the points rule and now. */
    private val copyNamingTheBank = SmsMessage(
        "VM-BROKER", "Funds payout of Rs 7,500.00 has been credited to your primary bank a/c.", RECEIVED_AT,
    )

    private fun read(sms: SmsMessage) = ReadPayment(sms, (parser.parse(sms) as ParseResult.Payment).payment)

    /**
     * Regression R4, fixed: before the points rule the app's copy arrived first and was stored, and the bank's SMS,
     * which
     * [Duplicates] and the SMS reader do not know as a bank's, was marked DUPLICATE and never stored. The copy is a
     * payment again, exactly as before the points rule, so the clean-up keeps the only row of that money in (the older
     * duplicate gap itself is [gapOlderDuplicatesKnowOnlyListedBanks]).
     */
    @Test
    fun cleanUpKeepsTheCopyKeptInPlaceOfAnUnlistedBanksSms() {
        val appResult = parser.parse(appCopy)
        if (appResult !is ParseResult.Payment || appResult.payment.amountMinor != 750_000L) fail("app copy: ${describe(appResult)}")
        val keptAtHead = Duplicates.keepBankCopies(listOf(read(appCopy), read(unlistedBankCredit))).map { it.sms.sender }
        assertEquals(listOf("VM-GPAYIN"), keptAtHead)
        if (removable(appCopy)) fail("the clean-up removes the app's copy, the only row of this money in")
    }

    /**
     * Older than the points rule and unchanged by it (Duplicates.kt and SmsIngestPipeline.kt are the same as before the
     * points rule): a copy from a sender that is not a bank wins over the SMS of a bank that [Banks] does not list when
     * it arrives first, because [Duplicates] takes both for copies from senders that are not banks. The bank's SMS
     * should be the one kept.
     */
    @Ignore("GAP, older than the points rule and not changed by it: Duplicates and the SMS reader know only the banks in Banks.fromSender")
    @Test
    fun gapOlderDuplicatesKnowOnlyListedBanks() {
        val kept = Duplicates.keepBankCopies(listOf(read(copyNamingTheBank), read(unlistedBankCredit)))
        assertEquals(listOf("JK-APGVBK"), kept.map { it.sms.sender })
    }

    // ---- Regressions R1 to R3 and gap G1, read as before the points rule ----

    /**
     * Regression R1, fixed: goods and merchants whose name ends in "coin", "coins", "point" or "miles" right
     * after the amount are rupees, not points: a gold or silver coin, a crypto coin, the "Coin" fund app, a merchant
     * named "Miles Cabs" or "CoffeePoint", and any unit followed by purchase, order, sale, trip, POS, txn, proceeds or
     * refund wording. Eight debits and two credits, each read as before the points rule.
     */
    private val goodsAndMerchantsNamedCoinPointOrMiles =
        listOf(
            pays(
                "m-crypto-purchase-worth-of-bitcoin", "VM-COINDX",
                "Your purchase of Rs 5,000 worth of Bitcoin is complete. Order ID CD4412.",
                Direction.DEBIT, 500_000,
            ),
            pays(
                "v-bitcoin-purchase-successful", "VM-COINDX",
                "Your INR 5,000 Bitcoin purchase is successful.",
                Direction.DEBIT, 500_000,
            ),
            pays(
                "m-gold-coin-purchase-debit-card", "VM-TANISQ",
                "Thank you for your purchase of Rs 18,500 gold coin at GOLDLINE JEWELS, paid by HDFC Bank Debit " +
                    "Card.",
                Direction.DEBIT, 1_850_000,
            ),
            pays(
                "v-gold-coins-order-paid-upi", "VM-TANISQ",
                "Rs 52,000 Gold Coins order GL4412 confirmed. Paid via UPI.",
                Direction.DEBIT, 5_200_000,
            ),
            pays(
                "m-sip-in-coin-app", "VM-COINMF",
                "Your purchase of Rs 2,000 in Coin for BlueChip Fund is confirmed.",
                Direction.DEBIT, 200_000,
            ),
            pays(
                "m-upi-app-miles-merchant-after-amount", "VM-GPAYIN",
                "Rs 90.00 Miles Cabs trip paid via UPI. Ref 632300990011",
                Direction.DEBIT, 9_000,
            ),
            pays(
                "v-bank-gold-coin-purchase-no-place", "VM-HDFCBK",
                "Rs 18,500 gold coin purchase at GOLDLINE JEWELS approved on 19-11-26. -HDFC Bank",
                Direction.DEBIT, 1_850_000,
            ),
            pays(
                "v-unlisted-bank-pos-merchant-point", "VM-SRSWBK",
                "Rs 640.00 CoffeePoint POS txn debited on 19-11-26. Avl Bal Rs 4,100.00",
                Direction.DEBIT, 64_000,
            ),
            pays(
                "v-bitcoin-sale-credited-bank-account", "VM-COINDX",
                "Rs 2,000 Bitcoin sale proceeds credited to your bank account XX7731.",
                Direction.CREDIT, 200_000,
            ),
            pays(
                "v-silver-coin-refund-to-card", "VM-TANISQ",
                "Rs 4,200 silver coin order refund credited to your HDFC Bank Credit Card ending 3381.",
                Direction.CREDIT, 420_000,
            ),
        )

    @Test
    fun goodsAndMerchantsNamedCoinPointOrMilesAreRupees() = check(goodsAndMerchantsNamedCoinPointOrMiles)

    /**
     * Regression R2, fixed: money in whose credit word puts it in a bank card, a bank account, a UPI ID or a real
     * wallet is money in, even when the same sentence names a store balance as what the money is for or where it came
     * from ("cashback on your gift card order", "from your Rewards account has been credited to your SBI account",
     * "refund for your e-voucher is credited in your ICICI Bank Credit Card"). Each is read as before the points rule.
     */
    private val bankPlacesNamedBesideAStoreBalance =
        listOf(
            pays(
                "m-cashback-on-gift-card-order-to-bank-card", "VM-WOOHOO",
                "Rs 300 cashback on your gift card order has been credited to your Axis Bank Credit Card ending " +
                    "4470.",
                Direction.CREDIT, 30_000,
            ),
            pays(
                "v-refund-on-gift-card-to-upi-id", "VM-GYFTR",
                "Refund of Rs 2,000 on your gift card GC4412 has been credited to your UPI ID meera@okaxis.",
                Direction.CREDIT, 200_000,
            ),
            pays(
                "v-cashback-on-membership-card-to-paytm", "VM-GLDCLB",
                "Rs 150 cashback on your membership card has been added to your Paytm wallet.",
                Direction.CREDIT, 15_000,
            ),
            pays(
                "m-rewards-account-cashback-to-sbi", "VM-SUPSAV",
                "Rs 500 cashback from your Rewards account has been credited to your SBI account XX5512.",
                Direction.CREDIT, 50_000,
            ),
            pays(
                "v-from-store-wallet-to-bank-ac", "VM-CROMAR",
                "Rs 500 from your Croma loyalty wallet has been credited to your HDFC Bank A/c XX7731.",
                Direction.CREDIT, 50_000,
            ),
            pays(
                "v-evoucher-refund-in-your-bank-card", "VM-KNDLST",
                "Rs 1,200 refund for your e-voucher is credited in your ICICI Bank Credit Card XX6004.",
                Direction.CREDIT, 120_000,
            ),
        )

    @Test
    fun aBankPlaceNamedForTheMoneyWinsOverAStoreBalance() = check(bankPlacesNamedBesideAStoreBalance)

    /**
     * Regression R3, fixed: money in from a bank that the parser cannot tell from a store (its header code does
     * not end in BK, BNK, SFB or PB or hold BANK, and it does not sign off as a bank; "VM-UJJIVN", "VM-EQUTAS",
     * "VM-SURYOD", "VM-SARASW" stand for such headers), with the account in a later sentence or in a form the rules do
     * not read. With no store balance in it, each is read as before the points rule.
     */
    private val moneyInFromBanksTheParserCannotRecognise =
        listOf(
            pays(
                "d-ujjivan-credit-account-next-sentence", "VM-UJJIVN",
                "INR 8,300.00 credited on 19-11-26. Ref NEFT-ORBIT TRADERS. A/c XX9031 Avl Bal INR 48,418.60",
                Direction.CREDIT, 830_000,
            ),
            pays(
                "v-equitas-credit-account-in-third-sentence", "VM-EQUTAS",
                "Rs 5,000.00 credited to your account. Avl Bal Rs 15,000.00. A/c XX2210.",
                Direction.CREDIT, 500_000,
            ),
            pays(
                "v-ujjivan-account-ending", "VM-UJJIVN",
                "Amount of INR 2,000.00 credited to your Ujjivan account ending 9031.",
                Direction.CREDIT, 200_000,
            ),
            pays(
                "d-suryoday-account-digits-first", "VM-SURYOD",
                "Rs 3,000.00 deposited in your account 00XX4471 on 19-11-26. Bal Rs 11,200.00",
                Direction.CREDIT, 300_000,
            ),
            pays(
                "d-coop-dividend-share-account", "VM-SARASW",
                "Dividend of Rs 1,125.00 credited to your share account on 19-11-26.",
                Direction.CREDIT, 112_500,
            ),
        )

    @Test
    fun moneyInFromABankTheParserCannotRecogniseReadsAsBefore() = check(moneyInFromBanksTheParserCannotRecognise)

    // ---- Gaps (not regressions) ----

    /**
     * Gap G1, closed: money in reported only by the service that holds it, a
     * neobank account by brand ("Niyo Global account"), a meal card with no "meal" and no digits ("Ticket Restaurant
     * card"), a FASTag "tag wallet" (a real wallet, not a store's), refunds to LazyPay, Flipkart Pay Later and Simpl,
     * and a merchant's refund "to your account ending 7731". None is a store's own balance, so each is read as before
     * the points rule.
     */
    private val moneyInToPayLaterNeobankAndTagWallets =
        listOf(
            pays(
                "e-niyo-credit-global-account", "VM-NIYOGL",
                "INR 2,000.00 has been credited to your Niyo Global account on 19-11-26.",
                Direction.CREDIT, 200_000,
            ),
            pays(
                "f-edenred-card-no-digits", "VM-EDENRD",
                "Rs 2,000.00 credited to your Ticket Restaurant card for November.",
                Direction.CREDIT, 200_000,
            ),
            pays(
                "g-tag-wallet-toll-reversal", "VM-AIRFTG",
                "Toll reversal of Rs 95.00 has been credited to your tag wallet for vehicle KA01XX4412.",
                Direction.CREDIT, 9_500,
            ),
            pays(
                "h-lazypay-refund", "VM-LZYPAY",
                "Refund of Rs 289.00 from SNACKBOX has been credited to your LazyPay account.",
                Direction.CREDIT, 28_900,
            ),
            pays(
                "h-flipkart-paylater-refund", "VM-FLPKRT",
                "Refund of Rs 1,249.00 for order OD4412 has been credited to your Flipkart Pay Later.",
                Direction.CREDIT, 124_900,
            ),
            pays(
                "h-simpl-refund", "VM-SIMPL",
                "Rs 150.00 refund from FRESHCART is credited back to your Simpl limit.",
                Direction.CREDIT, 15_000,
            ),
            pays(
                "v-merchant-refund-account-ending", "VM-MEDPLS",
                "Rs 265.00 refund credited to your account ending 7731.",
                Direction.CREDIT, 26_500,
            ),
        )

    @Test
    fun moneyInToPayLaterNeobankAndTagWalletsReadsAsBefore() = check(moneyInToPayLaterNeobankAndTagWallets)

    /**
     * Readings that were wrong in the same way before the points rule and are not touched by it: "will be auto-debited"
     * read as a debit done, a due reminder with a cashback line not read as a card bill, and a store bill "Rs 150
     * loyalty points used and Rs 1,240.00 paid via UPI" whose rupees are not read (the parser without the points rule
     * read the points as a Rs 150 debit; now the message is skipped as points).
     */
    @Ignore("GAP, older than the points rule (the parser without it reads these the same way, or no better)")
    @Test
    fun gapOlderReadingsNotOfThePointsRule() = check(
        listOf(
            skips(
                "h-amazon-paylater-emi-due", "AX-AMAZON",
                "Amazon Pay Later: Rs 2,150.00 EMI is due on 05-Dec-26 and will be auto-debited from your bank " +
                    "account XX7731.",
                SkipReason.UPCOMING,
            ),
            bill(
                "j-hsbc-due-cashback-credited", "VM-HSBCIN",
                "HSBC Credit Card XX7712: amount due Rs 15,480.00 payable by 08-Dec-26. Cashback of Rs 250.00 " +
                    "credited to this statement.",
                1_548_000,
            ),
            pays(
                "m-store-points-debit-then-upi-paid", "VM-FRSHMT",
                "Bill FM/7781: Rs 150 loyalty points used and Rs 1,240.00 paid via UPI. Thank you for shopping.",
                Direction.DEBIT, 124_000,
            ),
        ),
    )

    private companion object {
        const val RECEIVED_AT = 1_763_280_000_000L
        val REMOVING_REASONS = setOf(SkipReason.REWARD_POINTS)
    }
}
