package com.expenses.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The accepted limits of the points rule: messages that are, or may be, points or a store's own balance but that the
 * rule does not skip, because it skips only money in from a sender that is not a bank whose amount is labelled as
 * points right after it. Each reads exactly as the parser did before the rule was added: a store credit, a store
 * wallet or points "worth" rupees shows as money in, which is never counted as spending, and debits and bank messages
 * read as before. A real payment lost would be worse.
 *
 * Every message is synthetic, and each expectation (`beforeReading`) is the parser's reading without the rule. None is
 * a row the one-time clean-up would remove.
 */
class PointsAcceptedLimitsTest {
    private val parser = SmsParser()

    private data class Case(val id: String, val sender: String, val body: String, val beforeReading: String)

    private fun case(id: String, sender: String, body: String, beforeReading: String) = Case(id, sender, body, beforeReading)

    private fun sms(case: Case) = SmsMessage(case.sender, case.body, RECEIVED_AT)

    /** "PAY CREDIT 12000", "UNREAD amount", "SKIP NOT_FINANCIAL", "BILL 500000": the short form used here. */
    private fun reading(result: ParseResult): String = when (result) {
        is ParseResult.Payment -> "PAY ${result.payment.direction} ${result.payment.amountMinor}"
        is ParseResult.CardBill -> "BILL ${result.bill.totalDueMinor}"
        is ParseResult.Skipped -> "SKIP ${result.reason}"
        is ParseResult.Unreadable -> "UNREAD ${result.hint}"
    }

    private fun check(vararg cases: Case) {
        val failures = cases.mapNotNull { case ->
            val actual = reading(parser.parse(sms(case)))
            if (actual == case.beforeReading) null else "  ${case.id}: expected ${case.beforeReading}, actual $actual"
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} failed\n" + failures.joinToString("\n"))
    }

    /**
     * Store credits, gift cards, vouchers, credit notes, store accounts and points that are not labelled as points in
     * the closed form right after the amount ("Rs 799 refund as a credit note", "Rs 40 in BharatPe Coins credited",
     * "Rs 60 HealthPoints credited"): money in, as before the points rule.
     */
    @Test
    fun storeCreditsWithoutAPointsLabelReadAsMoneyIn() = check(*CREDITS)

    /**
     * Money credited to a store's own wallet ("your DMart Ready wallet", "Croma loyalty wallet", "bbwallet"): money in,
     * as before the points rule. The rule no longer guesses which wallets are a store's.
     */
    @Test
    fun storeWalletsReadAsMoneyIn() = check(*WALLETS)

    /**
     * Points "worth" rupees from a store ("400 PAYBACK points worth Rs 100 have been credited"): the amount is labelled
     * by "worth" before it, not as points after it, so it reads as before the points rule.
     */
    @Test
    fun pointsWorthRupeesFromAStoreReadAsMoneyIn() = check(*WORTH)

    /**
     * Points from a bank or a sender [SmsParser.isFromBank] takes for a bank ("Rs 500 bonus reward points credited"
     * from VM-HDFCBK, a store header ending in BK): everything a bank sends reads as before the points rule.
     */
    @Test
    fun aBanksPointsMessagesReadAsBefore() = check(*BANKS)

    /**
     * Debits that mention points, vouchers or store credit: debits always read as before the points rule (a waiting
     * debit the person can ignore).
     */
    @Test
    fun debitsThatMentionPointsReadAsBefore() = check(*DEBITS)

    /**
     * Points or store messages that print no payment the parser can read, or move points with another action word ("for
     * your stay paid with HDFC Bank Credit Card", "redeemed for fuel"): the reading before the points rule (Needs
     * review or skipped), unchanged.
     */
    @Test
    fun pointsMessagesTheRuleDoesNotCoverReadAsBefore() = check(*NOTMONEY)

    /** The one-time clean-up's parser tests never take one of these: none is skipped as points. */
    @Test
    fun noAcceptedLimitIsRemovedByTheCleanUp() {
        val all = listOf(CREDITS, WALLETS, WORTH, BANKS, DEBITS, NOTMONEY).flatMap { it.toList() }
        val removed = all.filter { case ->
            val result = parser.parse(sms(case))
            !parser.isFromBank(sms(case)) && result is ParseResult.Skipped && result.reason == SkipReason.REWARD_POINTS
        }.map { it.id }
        assertEquals("rows the clean-up would delete", emptyList<String>(), removed)
    }

    private companion object {
        const val RECEIVED_AT = 1_763_280_000_000L

        val CREDITS = arrayOf(
            Case(
                "skip-rewards-account-credit", "VM-LFSTYL",
                "Rs 500 credited to your Landmark Rewards account. Use it on your next purchase at Lifestyle. Valid till 31-Dec-26.",
                "PAY CREDIT 50000",
            ),
            Case(
                "skip-starbucks-rewards-account", "VM-SBUXIN",
                "Rs 50 has been credited to your Starbucks Rewards account. Enjoy!",
                "PAY CREDIT 5000",
            ),
            Case(
                "gap-store-loyalty-message-with-upi-footer", "VM-SHPCLB-S",
                "Rs.200 has been credited to your ShopClub Loyalty account XXXXXX7731. Pay using UPI at any ShopClub store. Valid until 30th November.",
                "PAY CREDIT 20000",
            ),
            Case(
                "skip2-gift-card-credited-paid-upi-next-sentence", "VM-SHPSTP",
                "Your Shoppers Stop gift card XX9876 has been credited with Rs 2,000. Paid using UPI.",
                "PAY CREDIT 200000",
            ),
            Case(
                "skip2-credit-note-card-refund-next-sentence", "VM-DECTLN",
                "A credit note of Rs 799 has been issued to your Decathlon account. Refund to your HDFC Bank Credit Card is not applicable.",
                "PAY CREDIT 79900",
            ),
            Case(
                "skip2-gocash-refund", "VM-GOIBIB",
                "Rs 300 goCash+ has been credited to your Goibibo account as refund for booking GB12345.",
                "PAY CREDIT 30000",
            ),
            Case(
                "gap2-loyalty-account-without-your-upi", "VM-SHPCLB-S",
                "Rs.200 cashback for your UPI payment has been credited to ShopClub Loyalty account XXXXXX7731.",
                "PAY CREDIT 20000",
            ),
            Case(
                "gap2-store-credit-to-brand-account-masked", "VM-LFSTYL",
                "Rs.300 refund has been credited to your Lifestyle account XXXX5678 as store credit.",
                "PAY CREDIT 30000",
            ),
            Case(
                "skip3-refund-as-credit-note-card-payment", "VM-LFSTYL",
                "Rs 1,299 refund has been processed as a credit note against your card payment for bill 7788.",
                "PAY CREDIT 129900",
            ),
            Case(
                "skip3-one-word-coins-unit", "VM-BIGBSK",
                "Rs 150 bbcoins credited for your order 98765. 1 bbcoin = Rs 1.",
                "PAY CREDIT 15000",
            ),
            Case(
                "gap3-store-account-paying-via-upi", "VM-CROMAR",
                "Rs 200 credited to your Croma account for paying via UPI at Croma Indiranagar.",
                "PAY CREDIT 20000",
            ),
            Case(
                "gap3-store-credit-brand-you-paid-upi", "VM-MYNTRA",
                "Refund of Rs 499 for order 12345 has been credited to your Myntra Credit as you paid via UPI.",
                "PAY CREDIT 49900",
            ),
            Case(
                "gap3-store-money-refund-paid-upi", "VM-SWIGGY",
                "Rs 30 Swiggy Money credited to your account as refund for order 1234 paid via UPI.",
                "PAY CREDIT 3000",
            ),
            Case(
                "gap3-store-cash-balance-paid-upi", "VM-UBERIN",
                "Rs 75 has been added to your Uber Cash balance as refund for your trip paid via UPI.",
                "PAY CREDIT 7500",
            ),
            Case(
                "gap3-store-currency-paid-bank-card", "VM-MMTRIP",
                "Rs 500 myCash credited for your booking paid with HDFC Bank Credit Card XX4521.",
                "PAY CREDIT 50000",
            ),
            Case(
                "gap3-store-account-paid-using-upi", "VM-DMARTR",
                "Hi! Rs 50 DMart cashback credited to your DMart Ready account for order paid using UPI.",
                "PAY CREDIT 5000",
            ),
            Case(
                "gap3-store-a-c-loyalty-bonus", "VM-SHPCLB",
                "Rs.250 credited to your ShopClub A/c XXXXXX7731 as loyalty bonus.",
                "PAY CREDIT 25000",
            ),
            Case(
                "gap3-loyalty-bonus-store-account-upi", "VM-SHPCLB",
                "Rs.200 loyalty bonus credited to your ShopClub account XXXXXX7731 on purchase via UPI.",
                "PAY CREDIT 20000",
            ),
            Case(
                "gap3-points-for-purchase-paid-using-upi", "VM-IOCLXT",
                "Dear XTRAREWARDS member, 1,200 points have been credited to your account for fuelling of Rs 2,400 at IOCL outlet paid using UPI.",
                "PAY CREDIT 240000",
            ),
            Case(
                "gap3-cashback-credited-as-supercoins-upi", "VM-FLPKRT",
                "Rs 50 cashback credited as SuperCoins for your UPI payment on Flipkart.",
                "PAY CREDIT 5000",
            ),
            Case(
                "l-smartpoints-store-card", "VM-SUPSAV",
                "Rs 60 SmartPoints credited to your SuperSave card 4412. Redeem at any outlet.",
                "PAY CREDIT 6000",
            ),
            Case(
                "l-gift-card-credited-bank-card-named", "VM-WESTND",
                "Rs 500 has been credited to your Westend gift card XX4410 for paying with ICICI Bank Credit Card.",
                "PAY CREDIT 50000",
            ),
            Case(
                "l-egift-voucher-credited", "VM-CAFEBR",
                "Your Rs 250 e-gift voucher from CafeBrew has been credited to your account. Code sent separately.",
                "PAY CREDIT 25000",
            ),
            Case(
                "l-club-card-credited-multiline-upi", "VM-TRENDZ",
                "Your Trendz club card is credited with Rs.80\nUPI and cards accepted at all stores",
                "PAY CREDIT 8000",
            ),
            Case(
                "l-store-account-credited-upi-same-sentence", "VM-GDGTHB",
                "Rs.150 has been credited to your GadgetHub account, pay using UPI at any store.",
                "PAY CREDIT 15000",
            ),
            Case(
                "b-bharatpe-coins-credited", "VM-BHRTPE",
                "Rs 40 in BharatPe Coins credited for your QR payment streak.",
                "PAY CREDIT 4000",
            ),
            Case(
                "b-healthpoints-credited-order", "VM-MEDPLS",
                "Rs 60 HealthPoints credited for order MP5512. Redeem on your next order.",
                "PAY CREDIT 6000",
            ),
            Case(
                "b-zepto-cash-credited-account", "VM-ZEPTOC",
                "Rs 75 Zepto Cash credited to your account. Use it on your next order.",
                "PAY CREDIT 7500",
            ),
        )

        val WALLETS = arrayOf(
            Case(
                "skip-store-wallet-refund", "VM-DMARTR",
                "Dear Customer, Rs.120 has been credited to your DMart Ready wallet as refund for order 12345.",
                "PAY CREDIT 12000",
            ),
            Case(
                "skip-club-wallet", "VM-WSTSID",
                "Dear ClubWest member, Rs.300 has been credited to your ClubWest wallet. Expires 31-12-2026.",
                "PAY CREDIT 30000",
            ),
            Case(
                "skip-future-pay-wallet-cashback", "VM-FUTPAY",
                "Rs 200 cashback credited to your FuturePay wallet.",
                "PAY CREDIT 20000",
            ),
            Case(
                "skip-nykaa-wallet", "VM-NYKAAA",
                "Hi! Rs 150 Nykaa Rewards credited to your Nykaa wallet. Use within 30 days.",
                "PAY CREDIT 15000",
            ),
            Case(
                "skip-store-loyalty-wallet-variant", "VM-SHPCLB-S",
                "Rs.200 has been credited to your ShopClub wallet. Valid until 30th November. *T&C Apply.",
                "PAY CREDIT 20000",
            ),
            Case(
                "skip-ajio-wallet-refund", "VM-AJIOIN",
                "Rs 300 has been credited to your AJIO wallet as refund for order FN1234567890.",
                "PAY CREDIT 30000",
            ),
            Case(
                "gap-store-wallet-with-bank-offer-footer", "VM-CROMAR",
                "Rs.150 has been credited to your Croma loyalty wallet. Extra 10% off with ICICI Bank Credit Cards this week.",
                "PAY CREDIT 15000",
            ),
            Case(
                "gap-one-word-store-wallet", "VM-BIGBKT",
                "Rs.50 cashback has been credited to your bbwallet for order BB12345.",
                "PAY CREDIT 5000",
            ),
            Case(
                "skip2-cashback-to-your-store-wallet", "VM-NYKAAA",
                "Rs 75 cashback has been added to your Nykaa wallet for order 1234567.",
                "PAY CREDIT 7500",
            ),
            Case(
                "gap2-store-wallet-passive-paying-with-bank-card", "VM-CROMAR",
                "Your Croma wallet has been credited with Rs.150 cashback for paying with ICICI Bank Credit Card.",
                "PAY CREDIT 15000",
            ),
            Case(
                "gap2-store-wallet-without-your-bank-card", "VM-CROMAR",
                "Rs.150 cashback has been added to Croma wallet for your purchase with ICICI Bank Credit Card.",
                "PAY CREDIT 15000",
            ),
            Case(
                "gap2-multiline-store-wallet-upi-line", "VM-CROMAR",
                "Rs.150 has been credited to Croma loyalty wallet\nPay using UPI at any Croma store\nValid till 30-Nov-26",
                "PAY CREDIT 15000",
            ),
            Case(
                "skip3-store-wallet-refund-booking-paid-upi", "VM-PVRCIN",
                "Rs 120 has been credited to your PVR Passport wallet as refund for booking paid via UPI.",
                "PAY CREDIT 12000",
            ),
            Case(
                "skip3-store-wallet-booking-paid-bank-card", "VM-MMTRIP",
                "Rs 500 myCash has been credited to your MakeMyTrip wallet for your booking paid with HDFC Bank Credit Card XX4521.",
                "PAY CREDIT 50000",
            ),
            Case(
                "skip3-store-wallet-as-you-paid-upi", "VM-CROMAR",
                "Rs 150 credited to your Croma wallet as you paid via UPI at Croma.",
                "PAY CREDIT 15000",
            ),
            Case(
                "gap3-store-wallet-cash-as-amount-upi", "VM-NYKAAA",
                "Rs 100 Nykaa Wallet cash credited for your order paid via UPI.",
                "PAY CREDIT 10000",
            ),
            Case(
                "l-wallet-cash-expiring", "VM-STYLHB",
                "Rs 200 StyleHub cash credited to your StyleHub wallet expires on 30-Nov-26. Shop today.",
                "PAY CREDIT 20000",
            ),
            Case(
                "k-nykaa-refund-store-wallet", "VM-NYKAA",
                "Rs 650 refund has been added to your Nykaa wallet for order NK4412.",
                "PAY CREDIT 65000",
            ),
            Case(
                "k-dmart-refund-store-wallet", "VM-DMARTR",
                "Rs 120.00 refund credited to your DMart Ready wallet.",
                "PAY CREDIT 12000",
            ),
            Case(
                "d-rapido-wallet-refund", "VM-RAPIDO",
                "Rs 120 refund credited to your Rapido wallet for ride RD5512.",
                "PAY CREDIT 12000",
            ),
            Case(
                "d-zomato-money-refund", "VM-ZOMATO",
                "Rs 200 refund credited to your Zomato Money wallet for order 77120.",
                "PAY CREDIT 20000",
            ),
        )

        val WORTH = arrayOf(
            Case(
                "skip-payback-points-worth", "VM-PAYBCK",
                "Dear Member, 400 PAYBACK points worth Rs 100 have been credited to your PAYBACK account for your purchase at HPCL.",
                "PAY CREDIT 10000",
            ),
            Case(
                "skip2-tata-neu-points-to-your-account-bank-elsewhere", "JD-TATNEU",
                "Rs 120 worth of NeuCoins credited to your Tata Neu account. Pay with Tata Neu HDFC Bank Credit Card to earn more.",
                "PAY CREDIT 12000",
            ),
            Case(
                "gap2-neucoins-worth-earned-with-bank-card", "JD-TATNEU",
                "NeuCoins worth Rs 250 have been credited to your Tata Neu account for paying with Tata Neu HDFC Bank Credit Card.",
                "PAY CREDIT 25000",
            ),
            Case(
                "gap2-supercoins-worth-earned-with-bank-card", "VM-FLPKRT",
                "SuperCoins worth Rs 50 credited to your Flipkart account for your purchase using Axis Bank Credit Card.",
                "PAY CREDIT 5000",
            ),
            Case(
                "skip3-rs-worth-points-to-programme-account", "VM-PAYBCK",
                "Rs 250 worth PAYBACK points credited to your PAYBACK account XX4521 for HDFC Bank card spends.",
                "PAY CREDIT 25000",
            ),
            Case(
                "skip3-points-worth-to-rewards-account-upi", "VM-AIRASA",
                "500 airasia points worth Rs 50 credited to your airasia rewards account for booking paid with UPI.",
                "PAY CREDIT 5000",
            ),
            Case(
                "l-worth-of-shopcoins", "VM-SHPKRT",
                "You got Rs 120 worth of ShopCoins for your order. Coins credited to your ShopKart account.",
                "PAY CREDIT 12000",
            ),
            Case(
                "l-points-worth-store-account", "VM-SPCRTE",
                "Congrats! 300 points worth Rs 75 credited to your SpiceRoute account.",
                "PAY CREDIT 7500",
            ),
            Case(
                "b-club-points-worth-added", "VM-WOWCLB",
                "Rs 350 worth of Club points added to your WOW Club account. Shop more, earn more.",
                "PAY CREDIT 35000",
            ),
        )

        val BANKS = arrayOf(
            Case(
                "gap3-store-header-ends-bk", "VM-FOODBK",
                "Rs 100 loyalty points credited to your FoodBuddy account.",
                "PAY CREDIT 10000",
            ),
            Case(
                "gap3-store-cobranded-bank-signoff", "VM-CROMAR",
                "Rs 200 has been credited to your Croma wallet for your purchase. Pay with HDFC Bank cards for more -Croma & HDFC Bank",
                "PAY CREDIT 20000",
            ),
            Case(
                "gap3-bank-credited-with-points-count-and-limit", "VM-HDFCBK",
                "Your HDFC Bank Credit Card XX4521 has been credited with 500 bonus reward points. Avl limit Rs 1,00,000",
                "PAY CREDIT 50000",
            ),
            Case(
                "l-bank-bonus-points-no-place", "VM-HDFCBK",
                "Congratulations! Rs 500 bonus reward points credited for your festive spends.",
                "PAY CREDIT 50000",
            ),
            Case(
                "c-dbs-bank-points-no-place", "VM-DBSBNK",
                "Rs 300 bonus points credited for your festive spends. Redeem on the digibank app.",
                "PAY CREDIT 30000",
            ),
            Case(
                "c-coop-bank-points-as-amount-card", "VM-SRSWBK",
                "Rs 150 reward points credited for your debit card spends in October.",
                "PAY CREDIT 15000",
            ),
            Case(
                "l-bank-edge-points-card-named", "VM-AXISBK",
                "Rs 250 EDGE Reward points credited to your Axis Bank Credit Card XX4470 for October spends.",
                "PAY CREDIT 25000",
            ),
            Case(
                "b-bank-reward-points-worth-no-place", "VM-FEDBNK",
                "Rs 500 worth reward points credited for your festive spends. -Federal Bank",
                "PAY CREDIT 50000",
            ),
        )

        val DEBITS = arrayOf(
            Case(
                "skip-points-worth-credited", "VM-DECTLN",
                "You earned 300 Decathlon points on your purchase. Points worth Rs 30 credited to your account.",
                "PAY DEBIT 3000",
            ),
            Case(
                "skip2-club-card-redeemed-rest-paid-upi", "VM-WSTSID",
                "Rs 500 redeemed from your Westside Club card XX4321 towards your purchase. Balance paid via UPI.",
                "PAY DEBIT 50000",
            ),
            Case(
                "gap2-store-credit-paid-by-bank-card", "VM-LFSTYL",
                "Store credit of Rs 300 has been issued on your Lifestyle account against return of order 1234 paid by HDFC Bank Debit Card.",
                "PAY DEBIT 30000",
            ),
            Case(
                "skip3-voucher-issued-for-purchase-with-bank-card", "VM-TANISQ",
                "Gift voucher of Rs 1,000 has been issued to you for your purchase with ICICI Bank Credit Card XX4521.",
                "PAY DEBIT 100000",
            ),
            Case(
                "skip3-points-worth-redeemed-bill-paid-upi", "VM-SHPSTP",
                "First Citizen points worth Rs 300 redeemed against your bill paid by UPI at Shoppers Stop Andheri.",
                "PAY DEBIT 30000",
            ),
            Case(
                "skip3-store-credit-issued-return-paid-by-card", "VM-DECTLN",
                "Store credit of Rs 300 has been issued to your Decathlon account for return paid by card XX4521.",
                "PAY DEBIT 30000",
            ),
            Case(
                "rp-store-gift-card-debit-10", "VM-SHPSTP",
                "Rs 1,500.00 debited from your Shoppers Stop gift card XX9876 at Shoppers Stop Andheri on 16-11-26. Balance Rs 500.00",
                "PAY DEBIT 150000",
            ),
            Case(
                "rp-store-points-redeemed-11", "VM-CROMAR",
                "You have redeemed 500 points worth Rs 125 on your purchase at Croma Bandra. Available points: 1,200.",
                "PAY DEBIT 12500",
            ),
            Case(
                "nb-store-gift-card-line-break-14", "VM-WSTSID",
                "Rs 2,000 debited from your Westside gift card XX4410.\nShop with HDFC Bank cards for 5% off",
                "PAY DEBIT 200000",
            ),
            Case(
                "l-loyalty-points-debited", "VM-FRSHMT",
                "Rs.120 loyalty points have been debited from your FreshMart Loyalty account 81XXXXXX07 for bill FM/4412.",
                "PAY DEBIT 12000",
            ),
            Case(
                "b-neucoins-earned-paid-upi", "VM-BGBSKT",
                "Rs 120 NeuCoins earned on your BigBasket order. Paid via UPI.",
                "PAY DEBIT 12000",
            ),
        )

        val NOTMONEY = arrayOf(
            Case(
                "skip-hotel-points-count", "VM-MARIOT",
                "1,500 Marriott Bonvoy points have been credited to your account XX1234.",
                "UNREAD amount",
            ),
            Case(
                "skip2-payback-points-worth-redeemed-upi-next-sentence", "VM-PAYBCK",
                "You have redeemed 400 PAYBACK points worth Rs 100 at HPCL. Pay the balance using UPI.",
                "SKIP BALANCE_INFO",
            ),
            Case(
                "gap2-hotel-points-for-stay-paid-with-card", "VM-MARIOT",
                "You earned 2,000 Marriott Bonvoy points for your stay paid with HDFC Bank Credit Card XX4521.",
                "UNREAD amount",
            ),
            Case(
                "skip3-gift-card-loaded-ending", "VM-WSTSID",
                "Rs 500 has been loaded on your Westside gift card ending 4521. Balance Rs 500.",
                "SKIP BALANCE_INFO",
            ),
            Case(
                "skip3-hotel-points-stay-paid-card-ending", "VM-TAJHTL",
                "Dear Member, 1,800 NeuPass points credited for your stay paid with card ending 4521.",
                "UNREAD amount",
            ),
            Case(
                "gap3-airline-points-flight-paid-with-card", "VM-AIRIND",
                "Dear Member, 2,450 Maharaja Points have been credited to your Flying Returns account for flight AI 504 paid with your HDFC Bank card.",
                "UNREAD amount",
            ),
            Case(
                "l-miles-count-club-account", "VM-SKYLIN",
                "2,400 SkyMiles have been credited to your SkyLine Club account for flight SK 441.",
                "UNREAD amount",
            ),
            Case(
                "l-hotel-points-stay-paid-with-card", "VM-STYWEL",
                "Dear Member, 3,200 points have been added to your StayWell Rewards account for your stay paid with HDFC Bank Credit Card.",
                "UNREAD amount",
            ),
            Case(
                "l-store-credit-issued", "VM-URBNWR",
                "Store credit of Rs 799 has been issued against your return at UrbanWear. Use it within 6 months.",
                "SKIP NOT_FINANCIAL",
            ),
            Case(
                "v-store-loyalty-exact-points-amount-debited", "VM-SHPCLB",
                "Rs.150 loyalty points have been redeemed against bill MG7781.",
                "SKIP NOT_FINANCIAL",
            ),
            Case(
                "b-firstclub-points-worth-earned", "VM-FRSTCL",
                "You have earned 120 FirstClub points worth Rs 30 on your order. Use them next time.",
                "SKIP NOT_FINANCIAL",
            ),
            Case(
                "b-points-redeemed-fuel", "VM-PAYBCK",
                "1,000 points worth Rs 250 redeemed for fuel at HPCL Bandra on 20-11-26.",
                "SKIP NOT_FINANCIAL",
            ),
        )
    }
}
