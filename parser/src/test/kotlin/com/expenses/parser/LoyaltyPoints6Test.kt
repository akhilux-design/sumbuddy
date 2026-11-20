package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cases for [SkipReason.REWARD_POINTS] and the one-time clean-up, against the rule: a message is skipped as points only
 * when the parser without the rule reads it as money in, no bank sent it ([SmsParser.isFromBank]), and its amount is
 * labelled as points, coins, miles or a store voucher right after it (the amount, at most two label words, a unit and
 * a credit phrase; or the amount, a credit phrase, "as" or "in", at most two label words and a unit). A message that
 * prints no money amount and only moves points may be relabelled from unreadable or skipped. Debits and everything a
 * bank sends read as before.
 *
 * Every message is synthetic: names, digits, references and amounts are invented. A comment "before ..." gives the
 * reading without the points rule where it differs from the expectation; a case without one reads the same in both.
 *
 * - Tests named `...ReadAsBefore` and [noFreshMessageThatMustReadAsBeforeIsRemovable] hold what must not change.
 * - [storeLoyaltyMessageAndSameShapeStoreMessagesAreSkipped] and [amountsLabelledAsPointsInNewWordingsAreSkipped] hold
 *   what the rule is for.
 * - [brandWordAndUnitEdgesFollowTheRuleText] records what the rule's literal words do with a capitalised word that
 *   names goods, a metal or a shop, and with two brand words.
 * - [shapeTwoUnitMustEndThePhrase] and [relabelNeverHidesAMoneyAmountWithoutACurrencyWord] hold two edges of the rule,
 *   both fixed.
 */
class LoyaltyPoints6Test {
    private val parser = SmsParser()

    private sealed interface Expect
    private data class Pays(val direction: Direction, val amountMinor: Long) : Expect
    private data class Skips(val reason: SkipReason) : Expect
    private data class Bill(val totalDueMinor: Long) : Expect
    private data object Unread : Expect

    private data class Case(val id: String, val sender: String, val expect: Expect, val body: String)

    private val storeLoyalty = listOf(
        Case("o01", "VM-SHPCLB", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 20_000
            "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points have been credited. Valid until 30th November. *T&C Apply."),
        Case("o02", "VM-RELDGT", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 15_000
            "An update has been made to your Reliance Digital Loyalty account 81XXXXXX20. Rs.150 loyalty points have been credited. Valid until 31st December. *T&C Apply."),
        Case("o03", "VM-CROMAR", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 30_000
            "An update has been made to your Croma Club account 77XXXXXX31. Rs.300 loyalty points have been credited. Valid until 15th January. *T&C Apply."),
        Case("o04", "VM-SNGTHA", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 12_000
            "An update has been made to your Sangeetha Rewards account 98XXXXXX12. Rs.120 loyalty points have been credited. Valid until 28th February. *T&C Apply."),
    )

    private val labelledAsPoints = listOf(
        Case("p01", "VM-SWIGGY", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 7_500
            "Rs 75 cashback points have been added to your Swiggy One account."),
        Case("p03", "VM-LNSKRT", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 6_000
            "Rs.60 welcome bonus coins are credited to your Lenskart account."),
        Case("p04", "VM-FLPKRT", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 4_000
            "₹40 SuperCoins have been credited for your Flipkart order OD4455."),
        Case("p05", "VM-INTMLS", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 30_000
            "Rs 300 InterMiles credited to your InterMiles account for your stay at Taj."),
        Case("p09", "VM-NYKAAA", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 12_000
            "Rs 120 credited as cashback points to your Nykaa account."),
        Case("p10", "VM-WOOHOO", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 30_000
            "Rs 300 has been credited in Gift Card GC1234. Balance Rs 300."),
        Case("p11", "VM-LIFSTY", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 9_900
            "Rs 99 was credited as store credit. Valid for 30 days."),
        Case("p12", "VM-ZEPTOC", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 8_000
            "Rs.80/- loyalty points credited to your Zepto Pass."),
        Case("p13", "VM-TIMESP", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 20_000
            "RS 200 LOYALTY POINTS HAVE BEEN CREDITED TO YOUR ACCOUNT."),
        Case("p16", "VM-CREDRW", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 15_000
            "Rs 150 CRED coins have been credited to your CRED account."),
        Case("p17", "VM-NEUPAS", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 10_000
            "Rs 100 credited in NeuCoins for your Tata Neu purchase."),
        Case("l05", "VM-DREAMX", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 50_000
            "Rs 500 Welcome Bonus Coins credited to your Dream11 account."),
        Case("s10", "VM-LIFSTY", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 45_000
            "Rs 450 is credited as store credit to your Lifestyle account."),
    )

    private val lookAlikes = listOf(
        Case("g01", "VM-ZRDHAC", Pays(Direction.CREDIT, 1_240_000L),
            "Rs 12,400 Bluechip Fund redemption credited to your bank account XX4410."),
        Case("g02", "VM-CRCKTP", Pays(Direction.CREDIT, 500_000L),
            "Rs 5,000 Points Table prize money has been credited to your account."),
        Case("g03", "VM-CLBMHD", Pays(Direction.CREDIT, 250_000L),
            "Rs 2,500 Club Mahindra refund has been credited to your account."),
        Case("g04", "VM-MILESC", Pays(Direction.CREDIT, 84_000L),
            "Rs 840 Miles Cabs ride refund credited to your UPI ID rider@okicici."),
        Case("g05", "VM-COINSH", Pays(Direction.CREDIT, 320_000L),
            "Rs 3,200 Coin Shop sale proceeds credited to your account."),
        Case("g06", "VM-MMTCPM", Pays(Direction.CREDIT, 1_850_000L),
            "Rs 18,500 gold coin buyback amount credited to your account XX7781."),
        Case("g07", "VM-AUGMNT", Pays(Direction.CREDIT, 980_000L),
            "Rs 9,800 Silver coins sale proceeds have been credited to your bank account."),
        Case("g08", "VM-PAYRLL", Pays(Direction.CREDIT, 150_000L),
            "Rs 1,500 Fuel allowance has been credited to your salary account."),
        Case("g09", "VM-PAYRLL", Pays(Direction.CREDIT, 4_800_000L),
            "Rs 48,000 Salary for Nov-26 has been credited to your account."),
        Case("g10", "VM-PAYRLL", Pays(Direction.CREDIT, 2_500_000L),
            "Rs 25,000 Joining Bonus has been credited to your account."),
        Case("g11", "VM-PAYRLL", Pays(Direction.CREDIT, 1_000_000L),
            "Rs 10,000 Welcome Bonus amount credited to your account by Orbit Tech."),
        Case("g12", "VM-JUICEP", Pays(Direction.CREDIT, 45_000L),
            "Rs 450 Juice Point refund credited to your UPI ID."),
        Case("g13", "VM-FOURPT", Pays(Direction.CREDIT, 620_000L),
            "Rs 6,200 Four Points stay refund has been credited to your card."),
        Case("g14", "VM-REWRDS", Pays(Direction.CREDIT, 199_900L),
            "Rs 1,999 membership fee refund has been credited to your account."),
        Case("g15", "VM-CRYPTX", Pays(Direction.CREDIT, 640_000L),
            "Rs 6,400 Litecoin sale proceeds have been credited to your bank account."),
        Case("g16", "VM-MUTFND", Pays(Direction.CREDIT, 750_000L),
            "Rs 7,500 Points Fund dividend has been credited to your account."),
        Case("g17", "VM-TICKTS", Pays(Direction.CREDIT, 120_000L),
            "Rs 1,200 Coins Stadium booking refund credited to your account."),
        Case("g18", "VM-AIRLIN", Pays(Direction.CREDIT, 430_000L),
            "Rs 4,300 Miles fare difference refund credited to your card ending 1290."),
        Case("g19", "VM-ACMECO", Pays(Direction.CREDIT, 300_000L),
            "Rs 3,000 Voucher reimbursement has been credited to your bank account."),
        Case("g20", "VM-ACMECO", Pays(Direction.CREDIT, 200_000L),
            "Rs 2,000 gift voucher allowance credited to your salary account for Diwali."),
        Case("g21", "VM-WOOHOO", Pays(Direction.CREDIT, 200_000L),
            "Rs 2,000 gift card refund has been credited to your original payment method."),
        Case("g22", "VM-GYFTRI", Pays(Direction.CREDIT, 100_000L),
            "Rs 1,000 e-gift card amount has been credited back to your UPI ID."),
        Case("g23", "VM-QWIKCL", Pays(Direction.CREDIT, 75_000L),
            "Rs 750 on your gift card GC8812 has been refunded to your bank account."),
        Case("g24", "VM-SODEXO", Pays(Direction.CREDIT, 220_000L),
            "Rs 2,200 meal voucher reimbursement credited to your account for Nov-26."),
        Case("g25", "VM-SODEXO", Pays(Direction.CREDIT, 220_000L),
            "Rs 2,200 Meal voucher credited to your Pluxee card for Nov-26."),
        Case("g26", "VM-PLUXEE", Pays(Direction.CREDIT, 220_000L),
            "Rs 2,200 Pluxee meal voucher credited to your card for Nov-26."),
        Case("g27", "VM-ZOMATO", Pays(Direction.CREDIT, 34_000L),
            "Rs 340 refund for your order from Points of Origin Cafe credited to your UPI ID."),
        Case("g28", "VM-DUNZOD", Pays(Direction.CREDIT, 21_000L),
            "Rs 210 Coins & Co. order refund credited to your account."),
        Case("g29", "VM-MGMTRS", Pays(Direction.CREDIT, 300_000L),
            "Rs 3,000 Member Fund withdrawal credited to your account. -Mahila Credit Society"),
        Case("g30", "VM-SHRECR", Pays(Direction.CREDIT, 61_500L),
            "Interest of Rs 615.00 credited to your Club member savings account. -Shree Credit Co-op"),
        Case("g31", "VM-FLPKRT", Pays(Direction.CREDIT, 120_000L),
            "Rs 1,200 refunded to your bank account and Rs 60 SuperCoins credited for order OD5521."),
        Case("l01", "VM-ORBITT", Pays(Direction.CREDIT, 2_500_000L),
            "Rs 25,000 loyalty bonus has been credited to your account. -HR, Orbit Tech"),
        Case("l02", "VM-ORBITT", Pays(Direction.CREDIT, 1_000_000L),
            "Rs 10,000 loyalty reward credited to your account for 5 years of service."),
        Case("l03", "VM-DREAMX", Pays(Direction.CREDIT, 100_000L),
            "Rs 1,000 welcome bonus credited to your Dream11 account."),
        Case("l04", "VM-CLUBMR", Pays(Direction.CREDIT, 500_000L),
            "Rs 5,000 club membership refund credited to your account."),
    )

    private val headNotMoneyIn = listOf(
        Case("p02", "VM-BBQNTN", Skips(SkipReason.NOT_FINANCIAL),
            "INR 120 Club Points earned on your last visit. See you soon!"),
        Case("p06", "VM-SHPRST", Pays(Direction.DEBIT, 25_000L),
            "Rs 250 member rewards points were awarded for your purchase at Shoppers Stop."),
        Case("p07", "VM-PNTLNS", Skips(SkipReason.PROMOTION),
            "Rs 100 e-gift voucher issued to you by Pantaloons. Use code PNT100."),
        Case("p08", "VM-DECTLN", Skips(SkipReason.NOT_FINANCIAL),
            "Rs 500 store credit has been issued to your Decathlon account for your return."),
        Case("p14", "VM-AIRVST", Skips(SkipReason.NOT_FINANCIAL),
            "Rs 1,000 Club Vistara points earned on your flight UK812."),
        Case("p15", "VM-HPCLPY", Skips(SkipReason.NOT_FINANCIAL),
            "Rs 25 Fuel reward points awarded for your fill at HPCL."),
        Case("p18", "VM-AJIOIN", Skips(SkipReason.NOT_FINANCIAL),
            "Rs 400 gift voucher has been issued for your AJIO return."),
    )

    private val banks = listOf(
        Case("b01", "VM-HDFCBK", Pays(Direction.CREDIT, 20_000L),
            "Rs.200 loyalty points have been credited to your HDFC Bank Credit Card XX4410."),
        Case("b02", "VM-ICICIB", Pays(Direction.CREDIT, 50_000L),
            "Rs 500 reward points credited to your ICICI Bank account. Redeem on iMobile."),
        Case("b03", "VM-SARSBK", Pays(Direction.CREDIT, 20_000L),
            "Rs.200 loyalty points have been credited. -Saraswat Bank"),
        Case("b04", "VM-ESAFIN", Pays(Direction.CREDIT, 10_000L),
            "Rs 100 bonus points credited to your account. -ESAF Small Finance Bank"),
        Case("b05", "VM-EQUTAS", Pays(Direction.CREDIT, 5_000L),
            "Rs 50 cashback points credited to your savings account. -Equitas SFB"),
        Case("b06", "VM-SBICRD", Pays(Direction.CREDIT, 30_000L),
            "Rs 300 reward points have been credited to your SBI Card ending 5512."),
        Case("b07", "JD-PAYTM", Pays(Direction.CREDIT, 7_500L),
            "Rs 75 cashback points credited to your Paytm Wallet."),
        Case("b08", "VM-AXISBK", Pays(Direction.CREDIT, 100_000L),
            "Rs 1,000 credited as EDGE reward points to your Axis Bank Card."),
        Case("b09", "VM-FEDBNK", Pays(Direction.CREDIT, 25_000L),
            "Rs 250 Fi-Coins credited to your Federal Bank account."),
        Case("b10", "VK-COOPBK", Pays(Direction.CREDIT, 15_000L),
            "Rs 150 member reward points credited to your account."),
    )

    private val debits = listOf(
        Case("d01", "VM-SHPCLB", Pays(Direction.DEBIT, 20_000L),
            "Rs.200 loyalty points have been debited from your ShopClub Loyalty account for your purchase."),
        Case("d02", "VM-FLPKRT", Pays(Direction.DEBIT, 5_000L),
            "Rs 50 SuperCoins debited for your order OD99812."),
        Case("d03", "VM-SHPRST", Pays(Direction.DEBIT, 30_000L),
            "Rs 300 gift card balance used for your order. Rs 1,200 paid via UPI."),
        Case("d04", "VM-WOOHOO", Pays(Direction.DEBIT, 15_000L),
            "Rs 150 voucher redeemed and Rs 850 paid by card ending 1234."),
        Case("d05", "VM-PAYBCK", Skips(SkipReason.NOT_FINANCIAL),
            "Rs 500 PAYBACK points redeemed at HPCL Andheri."),
        Case("d06", "VM-NYKAAA", Pays(Direction.DEBIT, 29_900L),
            "Rs 299 paid using Nykaa reward points for order NYK2210."),
        Case("d07", "VM-POCKFM", Skips(SkipReason.NOT_FINANCIAL),
            "Rs 149 Pocket FM coins pack purchased via UPI."),
        Case("d08", "VM-MMTRIP", Pays(Direction.DEBIT, 450_000L),
            "Rs 4,500 InterMiles debited for your booking MT8812."),
    )

    private val cardBills = listOf(
        Case("c01", "VM-SBICRD", Skips(SkipReason.UPCOMING),
            "Your SBI Card bill of Rs 5,400 is due on 05-Dec-26. 250 reward points credited this cycle."),
        Case("c02", "VM-HDFCBK", Pays(Direction.CREDIT, 987_500L),
            "Payment of Rs 9,875 received on your HDFC Bank Credit Card XX4410. 300 reward points credited."),
        Case("c03", "VM-AXISBK", Pays(Direction.CREDIT, 987_500L),
            "Rs 9,875 credited to your Axis Bank Credit Card XX1290 on 20-11-26. 100 reward points credited. Total due Rs 0."),
        Case("c04", "VM-ICICIB", Bill(1_234_000L), // before: skipped PROMOTION
            "ICICI Bank Credit Card XX3310 statement: Total due Rs 12,340, minimum due Rs 620, due by 08-Dec-26. Pay on time to earn bonus reward points."),
        Case("c05", "VM-ONECRD", Bill(321_000L),
            "Your OneCard bill of Rs 3,210 is due tomorrow. 50 reward points were added this month."),
    )

    private val pointCounts = listOf(
        Case("n01", "VM-HDFCBK", Skips(SkipReason.REWARD_POINTS), // before: unreadable
            "1,250 Reward Points have been credited to your Credit Card XX4410."),
        Case("n03", "VM-ICICIB", Pays(Direction.CREDIT, 500_000L),
            "Your A/c XX1234 credited with 5,000.00 on 20-11-26. 50 bonus points earned."),
        Case("n04", "VM-FLPKRT", Unread,
            "500 SuperCoins credited for your order. Paid 1,240 via UPI."),
        Case("n07", "VM-SBICRD", Skips(SkipReason.NOT_FINANCIAL),
            "Txn of 1,499.00 on SBI Card ending 5512 at MYNTRA. 15 reward points earned."),
        Case("n08", "VM-ICICIB", Unread,
            "Card XX3310 used for 1,250.50 at DMART on 20-11-26. 12 reward points added."),
        Case("n09", "VM-SWIGGY", Unread,
            "Order total 640.00 paid. 20 Swiggy points credited for this order."),
        Case("n10", "VM-AMEXIN", Skips(SkipReason.NOT_FINANCIAL),
            "Your Card ending 31004 was charged 3,200.00 at Croma. 32 Membership Rewards points earned."),
        Case("n11", "VM-FLPKRT", Unread,
            "Refund of 1,240.00 for order OD5521 initiated. 50 SuperCoins credited."),
    )

    private val edges = listOf(
        Case("x01", "VM-AUGMNT", Pays(Direction.CREDIT, 2_000_000L),
            "Rs 20,000 Platinum coins credited to your SafeGold vault."),
        Case("x02", "VM-TNSHQD", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 1_850_000
            "Rs 18,500 Tanishq coins credited to your Digital Gold locker."),
        Case("x03", "VM-CRYPTX", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 200_000
            "Rs 2,000 Ethereum coins credited to your wallet."),
        Case("x04", "VM-JUICEP", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 12_000
            "Rs 120 Juice Point credited to your account via UPI."),
        Case("x05", "VM-OFFICE", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 100_000
            "Rs 1,000 Fuel voucher credited to your account for Nov-26."),
        Case("x06", "VM-AIRLIN", Pays(Direction.CREDIT, 50_000L),
            "Rs 500 Gold Points credited to your account."),
        Case("x07", "VM-HOTELM", Pays(Direction.CREDIT, 30_000L),
            "Rs 300 Four Points credited for your stay."),
        Case("l06", "VM-PTMFST", Pays(Direction.CREDIT, 10_000L),
            "Rs 100 Paytm First Points credited to your account."),
        Case("s11", "VM-SUPRDL", Pays(Direction.CREDIT, 60_000L),
            "Rs 600 was credited in your wallet as SuperCash."),
        Case("e01", "VM-PAYBCK", Skips(SkipReason.REWARD_POINTS), // before: CREDIT 10_000
            "Rs 100 PAYBACK points credited and Rs 500 cashback credited to your HDFC account."),
    )

    private val shapeTwoRegressions = listOf(
        Case("s01", "VM-PAYRLL", Pays(Direction.CREDIT, 150_000L), // now skipped REWARD_POINTS
            "Rs 1,500 has been credited as voucher reimbursement for Oct-26."),
        Case("s02", "VM-PAYBCK", Pays(Direction.CREDIT, 50_000L), // now skipped REWARD_POINTS
            "Rs 500 credited as PAYBACK points redemption to your SBI account XX4412."),
        Case("s03", "VM-WOOHOO", Pays(Direction.CREDIT, 200_000L), // now skipped REWARD_POINTS
            "Rs 2,000 credited as gift card refund to your original payment method."),
        Case("s04", "VM-LOYLTY", Pays(Direction.CREDIT, 125_000L), // now skipped REWARD_POINTS
            "Rs 1,250 has been credited as reward points encashment to your bank account."),
        Case("s05", "VM-ACMECO", Pays(Direction.CREDIT, 90_000L), // now skipped REWARD_POINTS
            "Rs 900 credited as gift voucher allowance in your salary for Nov-26."),
        Case("s06", "VM-MILESC", Pays(Direction.CREDIT, 80_000L), // now skipped REWARD_POINTS
            "Rs 800 credited in Miles Cabs wallet as refund for your cancelled ride."),
        Case("s07", "VM-PAYRLL", Pays(Direction.CREDIT, 240_000L), // now skipped REWARD_POINTS
            "Rs 2,400 has been credited as Gift Voucher reimbursement to your salary account XX1102."),
        Case("s08", "VM-PAYBCK", Pays(Direction.CREDIT, 25_000L), // now skipped REWARD_POINTS
            "Rs 250 credited in PAYBACK points value to your HDFC Bank account."),
        Case("s09", "VM-GYFTRI", Pays(Direction.CREDIT, 100_000L), // now skipped REWARD_POINTS
            "Rs 1,000 credited as gift card balance refund to your bank account."),
    )

    private val relabelRegressions = listOf(
        Case("n02", "VM-SWIGGY", Unread, // now skipped REWARD_POINTS
            "Cashback of 150.00 and 30 reward points credited to your account."),
        Case("n05", "VM-KOTAKB", Unread, // now skipped REWARD_POINTS
            "5,000.00 and 50 bonus points credited to A/c XX1234."),
        Case("n06", "VM-HDFCBK", Unread, // now skipped REWARD_POINTS
            "Thank you for using your HDFC Bank Card XX4410 for 2,340.00 at AMAZON on 20-11-26. 23 reward points credited."),
        Case("n12", "VM-KOTAKB", Unread, // now skipped REWARD_POINTS
            "Salary 52,000.00 and 100 bonus points credited to your A/c XX1234."),
    )

    private fun sms(case: Case) = SmsMessage(case.sender, case.body, RECEIVED_AT)

    private fun check(cases: List<Case>) {
        val ids = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (ids.isNotEmpty()) fail("duplicate ids $ids")
        val failures = cases.mapNotNull { case ->
            val result = parser.parse(sms(case))
            val ok = when (val expect = case.expect) {
                is Pays -> result is ParseResult.Payment && result.payment.direction == expect.direction &&
                    result.payment.amountMinor == expect.amountMinor
                is Skips -> result is ParseResult.Skipped && result.reason == expect.reason
                is Bill -> result is ParseResult.CardBill && result.bill.totalDueMinor == expect.totalDueMinor
                Unread -> result is ParseResult.Unreadable
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
    private fun removable(case: Case): Boolean {
        val sms = sms(case)
        val result = parser.parse(sms)
        return !parser.isFromBank(sms) && result == ParseResult.Skipped(SkipReason.REWARD_POINTS) &&
            parser.readIgnoringSkips(sms)?.direction == Direction.CREDIT
    }

    private fun assertRemovable(cases: List<Case>, expected: Boolean) {
        val wrong = cases.filter { removable(it) != expected }.map { it.id }
        assertEquals(if (expected) "rows the clean-up would keep" else "rows the clean-up would delete", emptyList<String>(), wrong)
    }

    // ---- What the rule is for ----

    /** The store loyalty message (not fresh) and three store messages of the same shape: skipped, and removable. */
    @Test
    fun storeLoyaltyMessageAndSameShapeStoreMessagesAreSkipped() {
        val cases = storeLoyalty
        check(cases)
        assertRemovable(cases, expected = true)
    }

    /** Money in from a store whose amount is labelled as points or a store voucher, in wordings not seen before. */
    @Test
    fun amountsLabelledAsPointsInNewWordingsAreSkipped() {
        val cases = labelledAsPoints
        check(cases)
        assertRemovable(cases, expected = true)
    }

    // ---- What must not change ----

    /**
     * Money in from senders that are not banks whose amount is followed by words that look like labels or units but
     * name goods, shops, funds, salary, allowances, bonuses, refunds or sale proceeds; a unit that does not touch the
     * credit phrase; and vouchers and gift cards in refund and reimbursement wordings.
     */
    @Test
    fun moneyInFollowedByLookAlikeWordsReadsAsBefore() {
        check(lookAlikes)
    }

    /** Points messages the parser without the points rule did not read as money in (skipped, or a debit): the rule does not touch them. */
    @Test
    fun pointsMessagesHeadDidNotReadAsMoneyInReadAsBefore() {
        check(headNotMoneyIn)
    }

    /** A bank's points messages, by a listed header, a bank-like header code or a bank's sign-off. */
    @Test
    fun aBanksPointsMessagesReadAsBefore() {
        check(banks)
    }

    /** Debits that mention points, coins, miles, gift cards or vouchers. */
    @Test
    fun debitsThatMentionPointsReadAsBefore() {
        check(debits)
    }

    /**
     * Card bills and card payments with a points count or a points offer (the two points additions to CardBills): a
     * reminder skipped as a promotion becomes a bill (c04); the rest read as before. c01 stays UPCOMING, as before the
     * points rule: the points count there does not make it a bill.
     */
    @Test
    fun cardBillsAndCardPaymentsWithPointsReadAsBeforeOrBecomeABill() {
        check(cardBills)
    }

    /**
     * Messages with a points count and no Rs or INR amount: relabelled from unreadable where no money amount is
     * printed (n01); n07 and n10 print money without Rs and stay NOT_FINANCIAL as before the points rule; the rest as
     * before.
     */
    @Test
    fun pointsCountsWithoutARupeeAmountReadAsAllowed() {
        check(pointCounts)
    }

    @Test
    fun noFreshMessageThatMustReadAsBeforeIsRemovable() {
        assertRemovable(lookAlikes + headNotMoneyIn + banks + debits + cardBills + pointCounts, expected = false)
    }

    // ---- The rule's literal words ----

    /**
     * What the rule's literal words do (passing, as a record). ONE capitalised word counts as a brand label, so
     * a jeweller, a coin or a shop named "... Point" before a unit and a credit phrase is skipped (x02 to x04); the
     * parser without the rule read each as money in, which never counts as spending, and x02 and x03 are goods bought,
     * so no money is lost there; x04 would be a shop's money. Platinum, like gold and silver, is never a label (x01
     * reads as before). A capitalised non-label word before a voucher makes it a store voucher (x05). Gold Points and
     * Four Points are not points (x06, x07), two brand words are not a label (l06), "credited in your wallet as
     * SuperCash" is not the closed shape (s11), and when the first amount is points the money named after it is not
     * read (e01, read without the rule as the points).
     */
    @Test
    fun brandWordAndUnitEdgesFollowTheRuleText() {
        check(edges)
    }

    // ---- The two edges this check found (fixed 2026-11-20) ----

    /**
     * In shape 2 ("credited as/in" + labels + unit) the unit must end the phrase: the end, a punctuation mark, a code
     * with a digit, or to/for/on/at/in/from/against/with/valid/and. A unit that is only the first word of what the
     * amount is ("voucher reimbursement", "gift card refund", "PAYBACK points redemption", "reward points encashment",
     * "gift voucher allowance", "Miles Cabs wallet") leaves real money in, read as before the points rule and never
     * removed by the clean-up.
     */
    @Test
    fun shapeTwoUnitMustEndThePhrase() {
        val cases = shapeTwoRegressions
        check(cases)
        assertRemovable(cases, expected = false)
    }

    /**
     * The "only points move" relabel never applies when a number with paise is left once the points phrases are taken
     * out ("for 2,340.00 at AMAZON", "Salary 52,000.00"): such a message prints money without Rs or INR and goes to
     * Needs review as before the points rule, among them a bank's card spend (n06) and salary (n12).
     */
    @Test
    fun relabelNeverHidesAMoneyAmountWithoutACurrencyWord() {
        check(relabelRegressions)
    }

    private companion object {
        const val RECEIVED_AT = 1_763_280_000_000L
    }
}
