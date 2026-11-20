package com.expenses.app.sms

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.model.ReadSms
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.data.model.SmsKey
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.nov
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.parser.Direction
import com.expenses.parser.ParseResult
import com.expenses.parser.SkipReason
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one-time removal of stored money in rows the current parser now skips as points or as a store's own balance
 * (lead decisions 2026-11-19 and 2026-11-20), with a test for each guard: money in only (never a debit or a Needs review
 * item), not from a bank, skipped as REWARD_POINTS, untouched (state, amount, currency, direction, payee, UPI address)
 * and the guessed category. All SMS text is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ParserRulesCleanupTest {
    private val test = TestDatabase()
    private val parser = SmsParser()
    private val folder: File = Files.createTempDirectory("cleanup").toFile()

    @After fun close() {
        test.close()
        folder.deleteRecursively()
    }

    @Test fun removesTheUntouchedLoyaltyMoneyInAndTheAccountOnlyItMade() = runTest {
        val account = test.accounts.findOrCreate(null, AccountType.BANK_ACCOUNT, "48").account
        val row = storeAsRead(1, STORE, LOYALTY, nov(16, 12), Direction.CREDIT, 20_000, accountId = account.id)

        val removed = cleanup(settings()).runIfNeeded()

        assertEquals(1, removed)
        assertNull(test.expenses.get(row.id))
        assertNull(test.accounts.get(account.id))
        // Still marked read, now as skipped, so the message is never offered again.
        val key = SmsKey(1, nov(16, 12), STORE)
        assertEquals(setOf(key), test.readSms.alreadyRead(listOf(key)))
        val counts = test.readSms.observeCounts().first().byOutcome
        assertEquals(1, counts[ReadOutcome.SKIPPED])
        assertNull(counts[ReadOutcome.PAYMENT])
    }

    @Test fun keepsDebitsFromAStoreEvenWhenTheyAreAStoreBalance() = runTest {
        // A store's gift card spend, still waiting, and the same added to spending: debits are read as before.
        val kept = listOf(
            storeAsRead(1, GIFT_CARD_SENDER, GIFT_CARD_DEBIT, nov(16, 13), Direction.DEBIT, 150_000, counterparty = "Shoppers Stop Andheri"),
            storeAsRead(2, GIFT_CARD_SENDER, GIFT_CARD_DEBIT, nov(16, 14), Direction.DEBIT, 150_000, status = ExpenseStatus.ADDED),
            // Read as a debit: "purchase" comes before "credited".
            storeAsParsed(3, DECATHLON, POINTS_VALUE, nov(16, 15)),
        )
        assertEquals(Direction.DEBIT, kept[2].direction)

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
    }

    @Test fun keepsOpenReviewItemsEvenWhenTheParserNowSkipsThem() = runTest {
        // Points only, from a store and from a bank: the parser now skips both, but review items are never removed.
        assertEquals(ParseResult.Skipped(SkipReason.REWARD_POINTS), parser.parse(SmsMessage(GIFT_CARD_SENDER, STORE_POINTS_ONLY, 0)))
        val store = test.reviews.add(ReviewItem(smsId = 1, sender = GIFT_CARD_SENDER, body = STORE_POINTS_ONLY, receivedAt = nov(16, 9), hint = "amount"))
        val bank = test.reviews.add(ReviewItem(smsId = 2, sender = HDFC, body = BANK_POINTS_ONLY, receivedAt = nov(16, 10), hint = "amount"))
        test.readSms.markRead(listOf(read(1, GIFT_CARD_SENDER, nov(16, 9), ReadOutcome.REVIEW), read(2, HDFC, nov(16, 10), ReadOutcome.REVIEW)))

        assertEquals(0, cleanup(settings()).runIfNeeded())

        assertNotNull(test.reviews.get(store))
        assertNotNull(test.reviews.get(bank))
        assertEquals(2, test.readSms.observeCounts().first().byOutcome[ReadOutcome.REVIEW])
    }

    @Test fun keepsLoyaltyRowsThePersonActedOn() = runTest {
        val account = test.accounts.findOrCreate(null, AccountType.BANK_ACCOUNT, "48").account
        val kept = listOf(
            // Money in turned into a debit added to spending.
            storeAsRead(1, STORE, LOYALTY, nov(16, 13), Direction.CREDIT, 20_000) {
                it.copy(direction = Direction.DEBIT, status = ExpenseStatus.ADDED)
            },
            // Ignored: its text was dropped then.
            storeAsRead(2, STORE, null, nov(16, 14), Direction.CREDIT, 20_000, status = ExpenseStatus.IGNORED),
            // A note, Family, a split in a group, a category or a payee the person chose.
            storeAsRead(3, STORE, LOYALTY, nov(16, 15), Direction.CREDIT, 20_000) { it.copy(note = "points") },
            storeAsRead(4, STORE, LOYALTY, nov(16, 16), Direction.CREDIT, 20_000) { it.copy(scope = ExpenseScope.FAMILY) },
            storeAsRead(5, STORE, LOYALTY, nov(16, 17), Direction.CREDIT, 20_000) {
                it.copy(groupExpenseId = "group-expense-1", myShareMinor = 10_000)
            },
            storeAsRead(6, STORE, LOYALTY, nov(16, 18), Direction.CREDIT, 20_000) { it.copy(category = Category.SHOPPING) },
            storeAsRead(7, STORE, LOYALTY, nov(16, 19), Direction.CREDIT, 20_000, accountId = account.id) { it.copy(counterparty = "Mom") },
        )

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
        assertNotNull(test.accounts.get(account.id))
        assertEquals(7, test.readSms.observeCounts().first().byOutcome[ReadOutcome.PAYMENT])
    }

    @Test fun keepsRealMoneyInThatMentionsPointsOrCashback() = runTest {
        val kept = listOf(
            storeAsRead(1, HDFC, BANK_CREDIT_WITH_POINTS, nov(16, 9), Direction.CREDIT, 50_000),
            storeAsRead(2, AMAZON_PAY, AMAZON_PAY_CASHBACK, nov(16, 10), Direction.CREDIT, 5_000),
            storeAsRead(3, HDFC, BANK_CASHBACK, nov(16, 11), Direction.CREDIT, 7_500),
            storeAsRead(4, HDFC, CARD_SPEND_WITH_POINTS, nov(16, 12), Direction.DEBIT, 249_900, counterparty = "Amazon"),
        )

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
    }

    @Test fun keepsALoyaltyRowWhoseAmountThePersonEdited() = runTest {
        val kept = listOf(
            // Money in: the person corrected Rs 200 to Rs 250.
            storeAsRead(1, STORE, LOYALTY, nov(16, 12), Direction.CREDIT, 20_000) { it.copy(amountMinor = 25_000, myShareMinor = 25_000) },
            // A store's refund whose amount the person changed.
            storeAsParsed(2, GIFT_CARD_SENDER, STORE_REFUND, nov(16, 13)) { it.copy(amountMinor = 140_000, myShareMinor = 140_000) },
        )
        // The same message, untouched, is removed.
        val untouched = storeAsRead(3, STORE, LOYALTY, nov(16, 14), Direction.CREDIT, 20_000)

        assertEquals(1, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
        assertNull(test.expenses.get(untouched.id))
        val counts = test.readSms.observeCounts().first().byOutcome
        assertEquals(2, counts[ReadOutcome.PAYMENT])
        assertEquals(1, counts[ReadOutcome.SKIPPED])
    }

    @Test fun keepsRealBankCreditsNearPointsWords() = runTest {
        val kept = BANK_CREDITS_NEAR_POINTS.mapIndexed { index, (sender, text, amountMinor) ->
            storeAsRead(index + 1L, sender, text, nov(16, 9 + index), Direction.CREDIT, amountMinor)
        }

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
    }

    @Test fun keepsMoneyInFromANonBankSenderWhoseAmountIsNotLabelledAsPoints() = runTest {
        // The amount is not labelled as points right after it: the parser reads these as money in, as before (the closed
        // rule of 2026-11-20). That includes the accepted limits, a store wallet, store credit and points "worth"
        // rupees, which show as money in and are never counted as spending. None is removed.
        val texts = listOf(
            STORE to STORE_CREDIT_NO_LOYALTY_WORDS,
            "VM-ACMEHR" to REFUND_TO_BANK_ACCOUNT,
            "VM-GPAYIN" to UPI_APP_RECEIVED,
            "AD-RELDIG" to POINTS_BESIDE_PURCHASE,
            CROMA to STORE_WALLET_PAID_WITH_CARD,
            LIFESTYLE to AS_STORE_CREDIT,
            "VM-SPCRTE" to POINTS_WORTH_TO_STORE_ACCOUNT,
        )
        val kept = texts.mapIndexed { index, (sender, text) ->
            assertTrue(text, parser.parse(SmsMessage(sender, text, 0)) is ParseResult.Payment)
            storeAsParsed(index + 1L, sender, text, nov(16, 9 + index))
        }
        for (row in kept) assertEquals(Direction.CREDIT, row.direction)

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
        assertEquals(kept.size, test.readSms.observeCounts().first().byOutcome[ReadOutcome.PAYMENT])
    }

    @Test fun keepsAnOpenReviewItemForARealDebitThatCountsPoints() = runTest {
        val open = test.reviews.add(ReviewItem(smsId = 1, sender = HDFC, body = DEBIT_NO_CURRENCY_WITH_POINTS, receivedAt = nov(16, 9), hint = "amount"))
        test.readSms.markRead(listOf(read(1, HDFC, nov(16, 9), ReadOutcome.REVIEW)))

        assertEquals(0, cleanup(settings()).runIfNeeded())

        assertNotNull(test.reviews.get(open))
    }

    @Test fun keepsRowsABankSentEvenWhenTheyAreLabelledAsPoints() = runTest {
        // The same points message: from a store it is removed; from a bank, card issuer or wallet by any test it reads
        // as before (money in) and stays.
        val removed = listOf(STORE, "VM-MOBIKW").mapIndexed { index, sender ->
            assertFalse(sender, parser.isFromBank(SmsMessage(sender, BANK_STYLE_POINTS, 0)))
            storeAsParsed(index + 1L, sender, BANK_STYLE_POINTS, nov(16, 8 + index))
        }
        val kept = listOf("VM-ICICIB", "JD-PAYTM", "VK-COOPBK", "VM-FINOPB").mapIndexed { index, sender ->
            assertTrue(sender, parser.isFromBank(SmsMessage(sender, BANK_STYLE_POINTS, 0)))
            assertEquals(sender, Direction.CREDIT, (parser.parse(SmsMessage(sender, BANK_STYLE_POINTS, 0)) as ParseResult.Payment).payment.direction)
            storeAsParsed(index + 3L, sender, BANK_STYLE_POINTS, nov(16, 10 + index))
        } + storeAsParsed(7, "VM-EQUTAS", "$BANK_STYLE_POINTS -Equitas SFB", nov(16, 15))

        assertEquals(removed.size, cleanup(settings()).runIfNeeded())

        for (row in removed) assertNull(test.expenses.get(row.id))
        for (row in kept) assertEquals(row, test.expenses.get(row.id))
    }

    @Test fun removesMoneyInWhoseAmountIsLabelledAsPoints() = runTest {
        val skipped = listOf(
            // The amount, a label word, a unit and a credit phrase.
            STORE to LOYALTY,
            // The amount, a brand word, a store voucher and a credit phrase.
            BOOKMYSHOW to VOUCHER_CREDITED,
            // The amount, a credit phrase, "as" and a store voucher.
            DECATHLON to CREDIT_NOTE_CREDITED_AS,
        )
        val rows = skipped.mapIndexed { index, (sender, text) ->
            assertEquals(text, ParseResult.Skipped(SkipReason.REWARD_POINTS), parser.parse(SmsMessage(sender, text, 0)))
            storeAsParsed(index + 1L, sender, text, nov(16, 9 + index))
        }
        for (row in rows) assertEquals(Direction.CREDIT, row.direction)

        assertEquals(rows.size, cleanup(settings()).runIfNeeded())

        for (row in rows) assertNull(test.expenses.get(row.id))
    }

    @Test fun keepsARowWhosePayeeThePersonTypedEvenWhenTheSmsPrintsIt() = runTest {
        val kept = listOf(
            // No payee was read; the person typed one the SMS prints.
            storeAsParsed(1, STORE, LOYALTY, nov(16, 9)) { it.copy(counterparty = "ShopClub") },
            // "Shoppers Stop Andheri" was read; the person shortened it.
            storeAsParsed(2, GIFT_CARD_SENDER, STORE_REFUND, nov(16, 10)) { it.copy(counterparty = "Shoppers Stop") },
            // A UPI address that the SMS does not give.
            storeAsParsed(3, STORE, LOYALTY, nov(16, 11)) { it.copy(counterpartyVpa = "store@okaxis") },
        )

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
    }

    @Test fun keepsARowWhoseCurrencyOrDirectionDiffersFromTheSms() = runTest {
        val kept = listOf(
            storeAsParsed(1, STORE, LOYALTY, nov(16, 9)) { it.copy(currency = "USD") },
            // Money in turned into a waiting debit.
            storeAsParsed(2, STORE, LOYALTY, nov(16, 10)) { it.copy(direction = Direction.DEBIT, status = ExpenseStatus.DETECTED) },
        )

        assertEquals(0, cleanup(settings()).runIfNeeded())

        for (row in kept) assertEquals(row, test.expenses.get(row.id))
    }

    @Test fun keepsASplitRowEvenOutsideAGroup() = runTest {
        val kept = storeAsParsed(1, STORE, LOYALTY, nov(16, 9)) { it.copy(myShareMinor = it.amountMinor / 2) }

        assertEquals(0, cleanup(settings()).runIfNeeded())

        assertEquals(kept, test.expenses.get(kept.id))
    }

    @Test fun keepsARowWhenTheCategoryIsNotTheGuessWithoutThePersonsRules() = runTest {
        // Stored with the keyword guess (Other), then the person made Shoppers Stop Andheri always Shopping.
        val ruleLater = storeAsParsed(1, GIFT_CARD_SENDER, STORE_REFUND, nov(16, 9))
        assertEquals("Shoppers Stop Andheri", ruleLater.counterparty)
        assertEquals(Category.OTHER, ruleLater.category)
        // The person changed this row to Shopping and later made a rule with the same category: it looks unedited
        // against the guess with rules, but not against the keyword guess.
        val changedThenRule = storeAsParsed(2, GIFT_CARD_SENDER, STORE_REFUND, nov(16, 10)) { it.copy(category = Category.SHOPPING) }
        test.expenses.rememberCategory("Shoppers Stop Andheri", Category.SHOPPING)
        assertEquals(Category.SHOPPING, test.expenses.guessCategory("Shoppers Stop Andheri"))

        assertEquals(0, cleanup(settings()).runIfNeeded())

        assertEquals(ruleLater, test.expenses.get(ruleLater.id))
        assertEquals(changedThenRule, test.expenses.get(changedThenRule.id))
    }

    @Test fun runsOncePerRulesVersion() = runTest {
        val settings = settings()
        storeAsRead(1, STORE, LOYALTY, nov(16, 12), Direction.CREDIT, 20_000)

        assertEquals(1, cleanup(settings).runIfNeeded())
        assertEquals(ParserRulesCleanup.RULES_VERSION, settings.cleanedParserRules())

        // A row stored afterwards (as if by an older build) is not looked at again: the version has run.
        val later = storeAsRead(2, STORE, LOYALTY, nov(16, 13), Direction.CREDIT, 20_000)
        assertEquals(0, cleanup(settings).runIfNeeded())
        assertEquals(later, test.expenses.get(later.id))
    }

    // ---- helpers ----

    private fun TestScope.settings() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") },
    )

    private fun cleanup(settings: SettingsRepository) = ParserRulesCleanup(
        parser = SmsParser(),
        transactions = RoomTransactionRunner(test.db),
        expenseDao = test.db.expenseDao(),
        expenses = test.expenses,
        accountDao = test.db.accountDao(),
        readSmsDao = test.db.readSmsDao(),
        settings = settings,
        parsing = Dispatchers.Unconfined,
    )

    /** Stores a row the way the SMS reader of an older build stored it, with the message marked read as a payment. */
    private suspend fun storeAsRead(
        smsId: Long,
        sender: String,
        text: String?,
        at: Instant,
        direction: Direction,
        amountMinor: Long,
        status: ExpenseStatus = if (direction == Direction.DEBIT) ExpenseStatus.DETECTED else ExpenseStatus.ADDED,
        accountId: Long? = null,
        counterparty: String? = null,
        vpa: String? = null,
        currency: String = Expense.INR,
        change: (Expense) -> Expense = { it },
    ): Expense {
        val asRead = Expense(
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            counterparty = counterparty,
            counterpartyVpa = vpa,
            category = test.expenses.guessCategory(counterparty, vpa),
            occurredAt = at,
            source = ExpenseSource.SMS,
            status = status,
            accountId = accountId,
            smsId = smsId,
            smsSender = sender,
            smsReceivedAt = at,
            originalMessage = text,
        )
        val row = change(asRead)
        test.readSms.markRead(listOf(read(smsId, sender, at, ReadOutcome.PAYMENT)))
        return row.copy(id = test.expenses.insert(row))
    }

    /** Stores [text] exactly as the SMS reads (amount, currency, direction, payee, UPI address), then applies [change]. */
    private suspend fun storeAsParsed(
        smsId: Long,
        sender: String,
        text: String,
        at: Instant,
        change: (Expense) -> Expense = { it },
    ): Expense {
        val payment = parser.readIgnoringSkips(SmsMessage(sender, text, at.toEpochMilli()))!!
        return storeAsRead(
            smsId, sender, text, at, payment.direction, payment.amountMinor,
            counterparty = payment.counterparty, vpa = payment.vpa, currency = payment.currency, change = change,
        )
    }

    private fun read(smsId: Long, sender: String, at: Instant, outcome: ReadOutcome) = ReadSms(smsId, at, sender, at, outcome)

    private companion object {
        const val STORE = "VM-SHPCLB-S"
        const val GIFT_CARD_SENDER = "VM-SHPSTP"
        const val LIFESTYLE = "VM-LFSTYL"
        const val DECATHLON = "VM-DECTLN"
        const val BOOKMYSHOW = "VM-BMSHOW"
        const val CROMA = "VM-CROMAR"
        const val HDFC = "VM-HDFCBK"
        const val AMAZON_PAY = "AX-AMAZON"

        // Synthetic messages: invented numbers, masked like the real ones.
        const val LOYALTY = "An update has been made to your ShopClub Loyalty account XXXXXX7731. Rs.200 loyalty points " +
            "have been credited. Valid until 30th November. *T&C Apply."
        const val GIFT_CARD_DEBIT = "Rs 1,500.00 debited from your Shoppers Stop gift card XX9876 at Shoppers Stop " +
            "Andheri on 16-11-26. Balance Rs 500.00"
        const val BANK_POINTS_ONLY = "Dear Customer, 1,250 Reward Points have been credited to your HDFC Bank Credit " +
            "Card XX4521 for Oct-26 spends. -HDFC Bank"
        const val BANK_CREDIT_WITH_POINTS = "Rs 500.00 credited to A/c XX4521 on 16-11-26 by NEFT from ACME PAYROLL. " +
            "You have earned 25 reward points. -HDFC Bank"
        const val AMAZON_PAY_CASHBACK = "Cashback of Rs 50 credited to your Amazon Pay balance for your order 403-1234567-7654321."
        const val BANK_CASHBACK = "Rs 75 cashback credited to your HDFC Bank A/c XX4521 on 16-11-26 for card spends. -HDFC Bank"
        const val CARD_SPEND_WITH_POINTS = "Rs 2,499.00 spent on HDFC Bank Credit Card XX4521 at AMAZON on 2026-11-16. " +
            "You earned 50 Reward Points on this spend."
        const val STORE_CREDIT_NO_LOYALTY_WORDS = "An update has been made to your ShopClub account XXXXXX7731. Rs.200 has been " +
            "credited. Valid until 30th November. *T&C Apply."
        const val DEBIT_NO_CURRENCY_WITH_POINTS = "Your a/c XX4521 debited 500.00 on 16-11-26 at AMAZON. 10 reward points earned."
        const val STORE_POINTS_ONLY = "Dear First Citizen, 300 points have been credited to your First Citizen account."
        const val BANK_STYLE_POINTS = "Rs.200 loyalty points have been credited for your festive spends."
        const val STORE_REFUND = "Rs 1,500.00 refund has been credited to your Shoppers Stop account from Shoppers Stop " +
            "Andheri on 16-11-26."
        const val REFUND_TO_BANK_ACCOUNT = "Your refund of Rs 1,200.00 has been credited to your bank account ending 4521."
        const val AS_STORE_CREDIT = "Rs.300 refund has been credited to your Lifestyle account XXXX5678 as store credit."
        const val POINTS_VALUE = "You earned 300 Decathlon points on your purchase. Points worth Rs 30 credited to your account."
        const val POINTS_BESIDE_PURCHASE = "Congratulations! 120 points credited to your Reliance One account for purchase " +
            "of Rs 12,000 at Reliance Digital."
        const val UPI_APP_RECEIVED = "You received Rs 1,200.00 from PRIYA R on Google Pay. UPI transaction ID 632300445566."
        const val POINTS_WORTH_TO_STORE_ACCOUNT = "Congrats! 300 points worth Rs 75 credited to your SpiceRoute account."
        const val CREDIT_NOTE_CREDITED_AS = "Rs 799 has been credited as credit note to your Decathlon account for your return."
        const val VOUCHER_CREDITED = "Rs 150 BookMyShow voucher has been credited to your account for your UPI payment of Rs 600."
        const val STORE_WALLET_PAID_WITH_CARD = "Your Croma wallet has been credited with Rs.150 cashback for paying with " +
            "ICICI Bank Credit Card."

        /** Real money into a bank account or card, worded next to points (sender, text, amount in paise). */
        val BANK_CREDITS_NEAR_POINTS = listOf(
            Triple(
                "VM-AXISBK",
                "Your Axis Bank Credit Card XX2231 has been credited with INR 500.00 against EDGE Reward Points redeemed on 16-11-26.",
                50_000L,
            ),
            Triple(
                HDFC,
                "Cashback of Rs 625.00 against Reward Points redemption has been credited to your HDFC Bank A/c XX4521 on 16-11-26.",
                62_500L,
            ),
            Triple(HDFC, "INR 1,000.00 credited to A/c XX4521 as Reward Points redemption on 16-11-26. -HDFC Bank", 100_000L),
            Triple(
                "VM-FEDBNK",
                "Fi-Coins worth Rs 300.00 have been redeemed and credited to your Federal Bank A/c XX1122 on 16-11-26.",
                30_000L,
            ),
            Triple("AX-SBICRD", "Rs 500.00 cashback against reward points credited to your SBI Card ending 6789 on 16-11-26.", 50_000L),
        )
    }
}
