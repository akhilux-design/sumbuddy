package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The points rule of [RealMoney] and [SmsParser.isFromBank], which the app's one-time clean-up uses. A message is
 * skipped as [SkipReason.REWARD_POINTS] only when it is money in, no bank sent it, and its amount is labelled as points
 * right after it: the amount, at most two label words (loyalty, reward, rewards, bonus, welcome, cashback, club,
 * member, membership, or one capitalised brand word), a unit (points, coins, miles, SuperCoins, NeuCoins, a gift card,
 * a voucher, store credit, a credit note and the rest of the closed list) and a credit phrase; or the amount, a credit
 * phrase, "as" or "in", the labels and the unit. A message that prints no money amount and moves only points is skipped
 * too, in place of a Needs review item. Everything else reads exactly as the parser did before the rule was added:
 * each expectation below is that reading, or the skip. A comment "before: ..." gives the reading without the rule.
 * Every message is synthetic.
 */
class PointsRuleTest {
    private val parser = SmsParser()

    private fun sms(sender: String, body: String) = SmsMessage(sender, body, NOV_16)

    /** "PAY CREDIT 12000", "UNREAD amount", "SKIP REWARD_POINTS", "BILL 500000": the short form used here. */
    private fun reading(sender: String, body: String): String = when (val result = parser.parse(sms(sender, body))) {
        is ParseResult.Payment -> "PAY ${result.payment.direction} ${result.payment.amountMinor}"
        is ParseResult.CardBill -> "BILL ${result.bill.totalDueMinor}"
        is ParseResult.Skipped -> "SKIP ${result.reason}"
        is ParseResult.Unreadable -> "UNREAD ${result.hint}"
    }

    /** Each case is (expected reading, sender, body). */
    private fun check(vararg cases: Triple<String, String, String>) {
        for ((expected, sender, body) in cases) assertEquals("$sender: $body", expected, reading(sender, body))
    }

    /** The parser's side of the clean-up: no bank sent it, it is skipped as points, and it still reads as money in. */
    private fun removable(sender: String, body: String): Boolean {
        val message = sms(sender, body)
        return !parser.isFromBank(message) && reading(sender, body) == POINTS &&
            parser.readIgnoringSkips(message)?.direction == Direction.CREDIT
    }

    /**
     * The store loyalty message the rule is for: skipped, and a row stored from it is one the clean-up takes.
     */
    @Test
    fun theStoreLoyaltyMessageIsSkipped() {
        check(
            Triple(POINTS, STORE, STORE_LOYALTY_MESSAGE),  // before: PAY CREDIT 20000
            Triple(POINTS, "VM-SHPCLB", "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points have been credited. Valid until 30th November. *T&C Apply."),  // before: PAY CREDIT 20000
        )
        assertTrue(removable(STORE, STORE_LOYALTY_MESSAGE))
    }

    /**
     * Money in from a sender that is not a bank, whose amount is followed by at most two label words, a unit and a
     * credit phrase, or by a credit phrase, "as" or "in", at most two label words and a unit.
     */
    @Test
    fun anAmountLabelledAsPointsRightAfterItIsSkipped() {
        check(
            Triple(POINTS, "VM-SHPCLB-S", "Rs.200 loyalty points have been credited. Valid till 30-Nov-26."),  // before: PAY CREDIT 20000
            Triple(POINTS, "VM-TIMESP", "Rs 50 TimesPoints credited to your account. Redeem now."),  // before: PAY CREDIT 5000
            Triple(POINTS, "VM-MOBIKW", "Rs 100 SuperCash credited to your MobiKwik wallet for your bill payment."),  // before: PAY CREDIT 10000
            Triple(POINTS, "VM-PAYBCK", "Rs 150 PAYBACK points credited to your PAYBACK account for your purchase at HPCL."),  // before: PAY CREDIT 15000
            Triple(POINTS, "VM-FLPKRT", "Rs 100 Flipkart gift card credited to your account. Gift card balance: Rs 100."),  // before: PAY CREDIT 10000
            Triple(POINTS, "VM-BMSHOW", "Rs 150 BookMyShow voucher has been credited to your account for your UPI payment of Rs 600."),  // before: PAY CREDIT 15000
            Triple(POINTS, "VM-SHPSTP", "Rs 300 bonus loyalty points are credited to your First Citizen account."),  // before: PAY CREDIT 30000
            Triple(POINTS, "JD-TATNEU", "Rs 100 has been credited as NeuCoins to your Tata Neu account."),  // before: PAY CREDIT 10000
            Triple(POINTS, "VM-FLPKRT", "Rs 50 credited in SuperCoins for your order OD12345678909876."),  // before: PAY CREDIT 5000
            Triple(POINTS, "VM-DECTLN", "Rs 799 has been credited as credit note to your Decathlon account for your return."),  // before: PAY CREDIT 79900
            Triple(POINTS, "VM-TRVLGO", "Rs 300 GoCash added to your TravelGo account. Use it on your next booking."),  // before: PAY CREDIT 30000
        )
    }

    /**
     * Anything else after the amount is read as before: a label not in the list (gold, silver, Solana, meal, Four), two
     * brand words, a word between the unit and the credit phrase, or no credit phrase at all.
     */
    @Test
    fun onlyTheClosedListsOfLabelsAndUnitsCount() {
        check(
            Triple("PAY CREDIT 1850000", "VM-GLDJWL", "Rs 18,500 gold coin credited to your Gold Savings account."),
            Triple("PAY CREDIT 1850000", "VM-GLDJWL", "Rs 18,500 Gold coins credited to your locker account."),
            Triple("PAY CREDIT 200000", "VM-CNVALT", "Rs 2,000 Solana coins credited to your wallet."),
            Triple("PAY CREDIT 150000", "VM-PAYRLL", "Rs 1,500 meal voucher reimbursement credited to your account for Oct-26."),
            Triple("PAY CREDIT 200000", "VM-COINDX", "Rs 2,000 Bitcoin sale proceeds credited to your bank account."),
            Triple("PAY CREDIT 30000", "VM-MRRIOT", "Rs 300 Four Points refund credited to your card."),
            Triple("PAY CREDIT 200000", "VM-WOOHOO", "Rs 2,000 on your gift card has been credited to your bank."),
            Triple("PAY CREDIT 20000", "JD-TATNEU", "Rs 200 Tata Neu points credited to your account."),
            Triple("PAY CREDIT 2500", "VM-FLPKRT", "Rs 25 in SuperCoins credited for your order OD12345678909876."),
            Triple("PAY CREDIT 5000", "VM-FLPKRT", "Rs 50 cashback credited as SuperCoins for your UPI payment on Flipkart."),
            Triple("PAY CREDIT 6000", "VM-MEDPLS", "Rs 60 HealthPoints credited for order MP5512. Redeem on your next order."),
        )
    }

    /**
     * Everything a bank sends reads as before, also points labelled right after the amount: a header in Banks, a header
     * code that reads as a bank's, a bank's sign-off.
     */
    @Test
    fun aBanksMessageReadsAsBefore() {
        check(
            Triple("PAY CREDIT 20000", "VM-ICICIB", "Rs.200 loyalty points have been credited for your festive spends."),
            Triple("PAY CREDIT 15000", "VM-HDFCBK", "Rs 150 CashPoints have been credited to your HDFC Bank Credit Card XX4521."),
            Triple("PAY CREDIT 20000", "VK-COOPBK", "Rs.200 loyalty points have been credited."),
            Triple("PAY CREDIT 20000", "VM-EQUTAS", "Rs.200 loyalty points have been credited. -Equitas SFB"),
        )
    }

    /**
     * Debits read as before, whatever they say about points or a store's balance.
     */
    @Test
    fun debitsReadAsBefore() {
        check(
            Triple("PAY DEBIT 12000", "VM-SHPCLB-S", "Rs.120 loyalty points have been debited for your bill MG7781."),
            Triple("PAY DEBIT 150000", "VM-SHPSTP", "Rs 1,500.00 debited from your Shoppers Stop gift card XX9876 at Shoppers Stop Andheri on 16-11-26."),
            Triple("PAY DEBIT 45000", "VM-GPAYIN", "Paid Rs 450.00 to Swiggy. Ref 632045671299."),
            Triple("PAY DEBIT 8500", "VM-FSTAGP", "Rs.85.00 deducted from your FASTag wallet for toll at Electronic City Plaza on 16-11-26."),
            Triple("PAY DEBIT 125000", "VM-SCAPIA", "Your Scapia card has been used for INR 1,250.00 at SWIGGY on 16-11-26."),
            Triple("PAY DEBIT 18000", "VM-GPAYIN", "Rs 180.00 Chai Point bill paid via UPI. Ref 632412340017"),
        )
    }

    /**
     * No money amount, and every debit or credit word belongs to a count of points: skipped instead of a Needs review
     * item. Without a money amount it was never a payment, so this only relabels it.
     */
    @Test
    fun aMessageThatMovesOnlyPointsIsSkippedInsteadOfUnreadable() {
        check(
            Triple(POINTS, "VM-FLPKRT", "Yay! 50 SuperCoins have been credited to your Flipkart account."),  // before: UNREAD amount
            Triple(POINTS, "VM-HDFCBK", "1,250 Reward Points have been credited to your HDFC Bank Credit Card XX4521."),  // before: UNREAD amount
            Triple(POINTS, "VM-MYNTRA", "Congrats! 500 Insider points credited to your Myntra Insider account for order 1234567."),  // before: UNREAD amount
        )
    }

    /**
     * A points count beside another debit or credit word, a money amount, or a bare number the parser without the
     * points rule reads as rupees: as before.
     */
    @Test
    fun pointsWithAnotherActionOrAnAmountReadAsBefore() {
        check(
            Triple("PAY CREDIT 50000", "AX-SBICRD", "Your SBI Card ending 6789 has been credited with 500 bonus reward points."),
            Triple("UNREAD amount", "VM-MARIOT", "You earned 2,000 Marriott Bonvoy points for your stay paid with HDFC Bank Credit Card XX4521."),
            Triple("UNREAD amount", "VM-HDFCBK", "1,250 Reward Points have been credited to your Card XX4521. Avl Limit Rs 50,000"),
            Triple("UNREAD amount", "VM-INDUSB", "Thank you for your purchase at DECATHLON on IndusInd Bank Credit Card XX6677. 32 Reward Points have been credited."),
            Triple("SKIP UPCOMING", "VM-HDFCBK", "500 reward points will be credited to your HDFC Bank Credit Card XX4521 in 3 days."),
        )
    }

    /**
     * Rupees paid for points, or spent beside a points count, are money.
     */
    @Test
    fun realRupeesThatMentionPointsArePayments() {
        check(
            Triple("PAY CREDIT 62500", "VM-HDFCBK", "Rs 625 Reward Points redemption cashback credited to your HDFC Bank Credit Card XX4521."),
            Triple("PAY CREDIT 50000", "VM-KOTAKB", "You have received Rs 500.00 as Reward Points redemption in your Kotak Bank A/c XX1234."),
            Triple("PAY CREDIT 50000", "VM-HDFCBK", "Reward Points worth Rs 500.00 redeemed. Amount credited to your HDFC Bank Credit Card XX4521 on 16-11-26."),
            Triple("PAY DEBIT 89900", "VM-KOTAKB", "Kotak Credit Card x7788: Spent Rs.899.00 Reward Points 18 at MYNTRA on 16/11/2026"),
            Triple("PAY DEBIT 325000", "VM-INDUSB", "Thank you for your purchase of INR 3,250.00 at DECATHLON on IndusInd Bank Credit Card XX6677. 32 Reward Points have been credited."),
            Triple("PAY CREDIT 62500", "VM-GPAYIN", "Rs 625 Reward Points redemption cashback credited to your HDFC Bank Credit Card XX4521."),
            Triple("PAY DEBIT 500000", "VM-COINDX", "Your purchase of Rs 5,000 worth of Bitcoin is complete. Order ID CD4412."),
            Triple("PAY DEBIT 1850000", "VM-HDFCBK", "Rs 18,500 gold coin purchase at GOLDLINE JEWELS approved on 19-11-26. -HDFC Bank"),
            Triple("PAY DEBIT 9000", "VM-GPAYIN", "Rs 90.00 Miles Cabs trip paid via UPI. Ref 632300990011"),
            Triple("PAY DEBIT 200000", "VM-COINMF", "Your purchase of Rs 2,000 in Coin for BlueChip Fund is confirmed."),
        )
    }

    /**
     * Money in to the user's bank account, card or a real wallet, from a sender that is not a bank: as before.
     */
    @Test
    fun realMoneyInFromASenderThatIsNotABankReadsAsBefore() {
        check(
            Triple("PAY CREDIT 25000", "VM-SWIGGY", "Refund of Rs 250.00 for order 1234567 has been processed to your HDFC Bank Credit Card XX4521."),
            Triple("PAY CREDIT 50000", "VM-GPAYIN", "Rs 500 credited to your Kotak account XX1234 from RAHUL."),
            Triple("PAY CREDIT 50000", "VM-SARSWT", "Your account no. 1234XXXX5678 is credited with Rs 500.00 on 16-11-26 towards interest."),
            Triple("PAY CREDIT 50000", "VM-NKGSBB", "Rs 500.00 has been credited to your Ac No 0123XXXX4521 on 16/11/26 by NEFT."),
            Triple("PAY CREDIT 50000", "VM-UJJIVN", "Your SB account ending 4521 is credited with Rs.500.00 on 16-11-2026 by cash deposit."),
            Triple("PAY CREDIT 50000", "VM-PRMCOB", "Sir, Rs.500 Cr to your ac 4521 on 16/11/26 by transfer."),
            Triple("PAY CREDIT 50000", "VM-MOBIKW", "Rs 500 cashback credited to your MobiKwik wallet for your bill payment."),
        )
    }

    /**
     * The accepted limits: a store credit, a store wallet or points "worth" rupees whose amount is not labelled as
     * points right after it shows as money in, which is never counted as spending. Real money in reads the same way.
     */
    @Test
    fun storeCreditsStoreWalletsAndPointsWorthReadAsMoneyIn() {
        check(
            Triple("PAY CREDIT 20000", "VM-SHPCLB-S", "An update has been made to your ShopClub account XXXXXX7731. Rs.200 has been credited. Valid until 30th November. *T&C Apply."),
            Triple("PAY CREDIT 15000", "VM-CROMAR", "Your Croma wallet has been credited with Rs.150 cashback for paying with ICICI Bank Credit Card."),
            Triple("PAY CREDIT 15000", "VM-CROMAR", "Rs.150 has been credited to your Croma loyalty wallet. Extra 10% off with ICICI Bank Credit Cards."),
            Triple("SKIP BALANCE_INFO", "VM-WSTSID", "Your Westside gift card XX4410 has been loaded with Rs 2,000. Gift card balance Rs 2,000."),
            Triple("PAY CREDIT 12000", "VM-SHPKRT", "You got Rs 120 worth of ShopCoins for your order. Coins credited to your ShopKart account."),
            Triple("PAY CREDIT 10000", "VM-PAYBCK", "Dear Member, 400 PAYBACK points worth Rs 100 have been credited to your PAYBACK account for your purchase at HPCL."),
            Triple("PAY CREDIT 7500", "VM-SWIGGY", "Rs 75 cashback has been added to your wallet for order 1234567."),
            Triple("PAY CREDIT 30000", "VM-PAYBCK", "Points worth Rs 300 redeemed. Amount credited to your HDFC Bank A/c XX4521."),
            Triple("PAY CREDIT 120000", "VM-GPAYIN", "You received Rs 1,200.00 from PRIYA R on Google Pay. UPI transaction ID 632300445566."),
            Triple("PAY CREDIT 30000", "VM-WOOHOO", "Rs 300 cashback on your gift card order has been credited to your Axis Bank Credit Card ending 4470."),
            Triple("PAY CREDIT 50000", "VM-CROMAR", "Rs 500 from your Croma loyalty wallet has been credited to your HDFC Bank A/c XX7731."),
            Triple("PAY CREDIT 200000", "VM-GYFTR", "Refund of Rs 2,000 on your gift card GC4412 has been credited to your UPI ID meera@okaxis."),
            Triple("PAY CREDIT 120000", "VM-ACMEHR", "Your refund of Rs 1,200.00 has been credited to your bank account ending 4521."),
            Triple("PAY CREDIT 50000", "VM-PLUXEE", "Rs.500.00 has been credited to your Pluxee meal wallet by ACME CORP on 01-11-26."),
            Triple("PAY CREDIT 50000", "VM-FSTAGP", "Rs 500 has been credited to your FASTag wallet as refund for toll at XYZ Plaza."),
            Triple("PAY CREDIT 50000", "VM-BHIMUP", "Rs 500 received from rahul@okaxis via UPI."),
        )
    }

    @Test
    fun fromBankByAnyTest() {
        // A header in Banks (wallets included), a header code that reads as a bank's, a bank's sign-off.
        for (sender in listOf("VM-HDFCBK", "AX-SBICRD", "JD-PAYTM", "VM-PHONPE", "AX-AMZPAY", "VK-COOPBK", "VM-DBSBNK", "VM-UTKSFB", "VM-KGBANK", "VM-FINOPB")) {
            assertTrue(sender, parser.isFromBank(sms(sender, "Rs.200 credited.")))
        }
        assertTrue(parser.isFromBank(sms("VM-EQUTAS", "Rs.200 credited. -Equitas SFB")))
        assertTrue(parser.isFromBank(sms("VM-SARSWT", "Rs.200 credited. - Saraswat Co-op Bank Ltd")))
        // Stores and apps, also wallet apps that are not in Banks.
        for (sender in listOf(STORE, "VM-SHPSTP", "VM-MOBIKW", "VM-FRCHRG")) {
            assertFalse(sender, parser.isFromBank(sms(sender, "Rs.200 credited. -Shoppers Stop")))
        }
    }

    private companion object {
        const val NOV_16 = 1_763_280_000_000L
        const val STORE = "VM-SHPCLB-S"
        const val POINTS = "SKIP REWARD_POINTS"
        const val STORE_LOYALTY_MESSAGE = "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points have been credited. Valid until 30th November. *T&C Apply."
    }
}
