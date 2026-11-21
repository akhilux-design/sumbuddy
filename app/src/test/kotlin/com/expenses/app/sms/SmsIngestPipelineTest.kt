package com.expenses.app.sms

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.data.repository.nov
import com.expenses.app.ingest.InboxSms
import com.expenses.app.ingest.IngestResult
import com.expenses.app.ingest.SmsTimes
import com.expenses.parser.Direction
import com.expenses.parser.SmsMessage
import com.expenses.parser.SmsParser
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsIngestPipelineTest {
    private val test = TestDatabase()
    private val pipeline = SmsIngestPipeline(
        parser = SmsParser(),
        transactions = RoomTransactionRunner(test.db),
        expenses = test.expenses,
        accounts = test.accounts,
        cardBills = test.cardBills,
        reviews = test.reviews,
        readSms = test.readSms,
        clock = test.clock,
        parsing = Dispatchers.Unconfined,
    )

    @After fun close() = test.close()

    @Test fun aMerchantRuleWithFamilyMakesTheNewPaymentWaitAsFamily() = runTest {
        test.expenses.rememberCategory("Swiggy", Category.FOOD, ExpenseScope.FAMILY)

        val result = pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2)), sms(2, HDFC, CREDIT, nov(14, 19, 5))))

        val debit = result.detected.single()
        assertEquals(ExpenseScope.FAMILY, debit.scope)
        // Still waiting: it is never added without a tap.
        assertEquals(ExpenseStatus.DETECTED, debit.status)
        assertEquals(ExpenseScope.ME, result.credits.single().scope)
        // Without a rule a payment waits as Just me.
        val other = pipeline.ingest(listOf(sms(3, HDFC, SWIGGY_BANK.replace("SWIGGY", "ZOMATO").replace("632045671234", "632045679999"), nov(16, 15, 0))))
        assertEquals(ExpenseScope.ME, other.detected.single().scope)
    }

    @Test fun printedDateOfAnEarlierDayIsKept() = runTest {
        val debit = pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(17, 8, 30)))).detected.single()

        assertEquals(nov(16, 0, 0), debit.occurredAt)
    }

    @Test fun debitWaitsAsDetectedAndCreditIsListedButNotCounted() = runTest {
        val result = pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2)), sms(2, HDFC, CREDIT, nov(14, 19, 5))))

        val debit = result.detected.single()
        assertEquals(ExpenseStatus.DETECTED, debit.status)
        assertEquals(Direction.DEBIT, debit.direction)
        assertEquals(45_000L, debit.amountMinor)
        assertEquals(Category.FOOD, debit.category)
        assertEquals(1L, debit.smsId)
        assertEquals(HDFC, debit.smsSender)
        assertEquals(SWIGGY_BANK, debit.originalMessage)
        assertEquals(nov(16, 13, 2), debit.smsReceivedAt)
        // The SMS prints only the date, and it arrived that day: the arrival time is used.
        assertEquals(nov(16, 13, 2), debit.occurredAt)

        val credit = result.credits.single()
        assertEquals(ExpenseStatus.ADDED, credit.status)
        assertEquals(Direction.CREDIT, credit.direction)

        val account = result.newAccounts.single()
        assertEquals(AccountType.BANK_ACCOUNT, account.type)
        assertEquals("4521", account.last4)
        assertEquals(account.id, debit.accountId)
        assertEquals(account.id, credit.accountId)

        assertEquals(0L, test.expenses.observeMonthTotal(NOVEMBER).first())
        assertEquals(listOf(debit.id), test.expenses.observeDetected().first().map { it.expense.id })
        assertEquals(2, test.readSms.observeCounts().first().byOutcome[ReadOutcome.PAYMENT])
    }

    @Test fun eachMessageIsReadExactlyOnce() = runTest {
        val messages = listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2)), sms(2, OTP_SENDER, OTP, nov(16, 13, 5)))
        pipeline.ingest(messages)
        val again = pipeline.ingest(messages + sms(3, HDFC, CREDIT, nov(16, 14)))

        assertEquals(2, again.alreadyRead)
        assertTrue(again.detected.isEmpty())
        assertEquals(1, again.credits.size)
        assertEquals(2, count("expenses"))
        assertEquals(3, test.readSms.observeCounts().first().total)
    }

    @Test fun firstReadHoldsTheMessagesThisCallMarkedRead() = runTest {
        // An OTP stores nothing but is read for the first time all the same. The service-centre time comes along.
        val sentAt = nov(16, 13, 4)
        val first = pipeline.ingest(
            listOf(sms(2, OTP_SENDER, OTP, nov(16, 13, 5)).copy(sentAt = sentAt), sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2))),
        )
        assertEquals(listOf(SmsTimes(nov(16, 13, 2), null), SmsTimes(nov(16, 13, 5), sentAt)), first.firstRead)

        // Read before: none of them counts, even the newest of the call.
        val again = pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2)), sms(2, OTP_SENDER, OTP, nov(16, 13, 5))))
        assertTrue(again.firstRead.isEmpty())

        // An older message read for the first time counts, though a newer one in the call was read before.
        val older = pipeline.ingest(listOf(sms(3, HDFC, CREDIT, nov(16, 12)), sms(2, OTP_SENDER, OTP, nov(16, 13, 5))))
        assertEquals(listOf(SmsTimes(nov(16, 12), null)), older.firstRead)
        assertEquals(1, older.alreadyRead)
    }

    @Test fun aMessageAnotherReaderStoredMeanwhileIsNotFirstReadHere() = runTest {
        val message = sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2))
        var otherReader: (suspend () -> Unit)? = { pipeline.ingest(listOf(message)) }
        val real = RoomTransactionRunner(test.db)
        // Another reader stores the message between this call's first check and its transaction.
        val racing = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                otherReader?.invoke()
                otherReader = null
                return real.run(block)
            }
        }
        val late = SmsIngestPipeline(
            parser = SmsParser(),
            transactions = racing,
            expenses = test.expenses,
            accounts = test.accounts,
            cardBills = test.cardBills,
            reviews = test.reviews,
            readSms = test.readSms,
            clock = test.clock,
            parsing = Dispatchers.Unconfined,
        )

        val result = late.ingest(listOf(message))

        assertTrue(result.firstRead.isEmpty())
        assertEquals(1, result.alreadyRead)
        assertTrue(result.detected.isEmpty())
        assertEquals(1, count("expenses"))
    }

    @Test fun appCopyInTheSameBatchIsDroppedForTheBankSms() = runTest {
        val result = pipeline.ingest(
            listOf(sms(1, APP, SWIGGY_APP, nov(16, 13, 0)), sms(2, HDFC, SWIGGY_BANK, nov(16, 13, 2))),
        )

        assertEquals(1, result.duplicates)
        assertEquals(HDFC, result.detected.single().smsSender)
        assertEquals(1, count("expenses"))
        assertEquals(1, test.readSms.observeCounts().first().byOutcome[ReadOutcome.DUPLICATE])
    }

    @Test fun bankSmsInALaterBatchReplacesTheStoredAppCopyInPlace() = runTest {
        val first = pipeline.ingest(listOf(sms(1, APP, SWIGGY_APP, nov(16, 13, 0)))).detected.single()
        assertNull(first.accountId)
        test.expenses.setCategory(first.id, Category.FUN)

        val second = pipeline.ingest(listOf(sms(2, HDFC, SWIGGY_BANK, nov(16, 13, 2))))

        assertTrue(second.detected.isEmpty())
        assertEquals(0, second.duplicates)
        assertEquals(1, count("expenses"))
        val stored = test.expenses.get(first.id)!!
        assertEquals(HDFC, stored.smsSender)
        assertEquals(2L, stored.smsId)
        assertEquals(SWIGGY_BANK, stored.originalMessage)
        assertNotNull(stored.accountId)
        assertEquals(ExpenseStatus.DETECTED, stored.status)
        assertEquals(Category.FUN, stored.category)
        assertEquals(2, test.readSms.observeCounts().first().byOutcome[ReadOutcome.PAYMENT])
    }

    @Test fun appCopyInALaterBatchIsADuplicateOfTheStoredBankSms() = runTest {
        pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2))))
        val later = pipeline.ingest(listOf(sms(2, APP, SWIGGY_APP, nov(16, 13, 6))))

        assertEquals(1, later.duplicates)
        assertTrue(later.detected.isEmpty())
        assertEquals(1, count("expenses"))
    }

    @Test fun twoBankPaymentsOfTheSameAmountInTwoBatchesAreBothKept() = runTest {
        pipeline.ingest(listOf(sms(1, HDFC, TEA_1, nov(16, 9, 0))))
        val second = pipeline.ingest(listOf(sms(2, HDFC, TEA_2, nov(16, 9, 3))))
        val repeated = pipeline.ingest(listOf(sms(3, HDFC_OTHER_HEADER, TEA_2, nov(16, 9, 4))))

        assertEquals(1, second.detected.size)
        assertEquals(1, repeated.duplicates)
        assertEquals(2, count("expenses"))
    }

    @Test fun topUpsOtpsAndWalletLoadsAreNeverStored() = runTest {
        val result = pipeline.ingest(
            listOf(
                sms(1, HDFC, UPI_LITE_TOP_UP, nov(16, 9)),
                sms(2, PAYTM, WALLET_LOAD, nov(16, 10)),
                sms(3, OTP_SENDER, OTP, nov(16, 11)),
            ),
        )

        assertEquals(3, result.skipped)
        assertTrue(result.detected.isEmpty())
        assertTrue(result.newAccounts.isEmpty())
        assertEquals(0, count("expenses"))
        assertEquals(3, test.readSms.observeCounts().first().byOutcome[ReadOutcome.SKIPPED])
    }

    @Test fun cardBillsAreStoredWithTheirCard() = runTest {
        val result = pipeline.ingest(listOf(sms(1, SBI_CARD, CARD_DUE, nov(16, 10))))

        val bill = result.cardBills.single()
        assertEquals(1_824_000L, bill.totalDueMinor)
        assertEquals(LocalDate.of(2026, 12, 4), bill.dueDate)
        assertEquals("6789", bill.cardLast4)
        val card = result.newAccounts.single()
        assertEquals(AccountType.CREDIT_CARD, card.type)
        assertEquals(card.id, bill.accountId)
        assertEquals(listOf(bill), test.cardBills.observeAll().first())
        assertEquals(0, count("expenses"))
        assertEquals(1, test.readSms.observeCounts().first().byOutcome[ReadOutcome.CARD_BILL])
    }

    @Test fun aCardOfUnknownKindBecomesACreditCardWhenItsBillArrives() = runTest {
        val first = pipeline.ingest(listOf(sms(1, HDFC, CARD_SPEND_NO_KIND, nov(14, 16))))
        val card = first.newAccounts.single()
        assertEquals(AccountType.OTHER, card.type)

        val billed = pipeline.ingest(listOf(sms(2, HDFC, HDFC_CARD_DUE, nov(16, 10))))

        assertTrue(billed.newAccounts.isEmpty())
        assertEquals(card.id, billed.cardBills.single().accountId)
        assertEquals(AccountType.CREDIT_CARD, test.accounts.get(card.id)!!.type)
        assertEquals(card.id, test.expenses.get(first.detected.single().id)!!.accountId)

        // A later spend that again does not say what kind of card it is keeps the card a credit card.
        val later = pipeline.ingest(listOf(sms(3, HDFC, CARD_SPEND_NO_KIND_2, nov(16, 18))))
        assertTrue(later.newAccounts.isEmpty())
        assertEquals(card.id, test.expenses.get(later.detected.single().id)!!.accountId)
        assertEquals(AccountType.CREDIT_CARD, test.accounts.get(card.id)!!.type)
        assertEquals(1, test.accounts.observeAll().first().size)
    }

    @Test fun unreadableMessagesBecomeReviewItems() = runTest {
        val result = pipeline.ingest(listOf(sms(1, SBI, UNREADABLE, nov(10, 11, 26))))

        val item = result.reviewItems.single()
        assertEquals(UNREADABLE, item.body)
        assertEquals(SBI, item.sender)
        assertEquals(listOf(item), test.reviews.observeOpen().first())
        assertEquals(0, count("expenses"))
        assertEquals(1, test.readSms.observeCounts().first().byOutcome[ReadOutcome.REVIEW])
    }

    @Test fun paymentsOfASwitchedOffAccountAreStoredButNotOffered() = runTest {
        val account = pipeline.ingest(listOf(sms(1, HDFC, TEA_1, nov(16, 9)))).newAccounts.single()
        test.accounts.setTracked(account.id, false)

        val result = pipeline.ingest(listOf(sms(2, HDFC, SWIGGY_BANK, nov(16, 13, 2))))

        assertTrue(result.detected.isEmpty())
        assertEquals(2, count("expenses"))
        assertTrue(test.expenses.observeDetected().first().isEmpty())
        // A switched-off account's payment keeps no SMS text.
        assertNull(stored(2)!!.originalMessage)
        test.accounts.setTracked(account.id, true)
        assertEquals(2, test.expenses.observeDetected().first().size)
    }

    @Test fun creditsOfASwitchedOffAccountAreHiddenFromTheLists() = runTest {
        val account = pipeline.ingest(listOf(sms(1, HDFC, CREDIT, nov(14, 19, 5)))).newAccounts.single()
        assertEquals(1, test.expenses.observeLatest(10).first().size)

        test.accounts.setTracked(account.id, false)

        assertTrue(test.expenses.observeLatest(10).first().isEmpty())
        assertTrue(test.expenses.observeMonth(NOVEMBER).first().isEmpty())
    }

    @Test fun aNewMessageUnderTheIdOfADeletedOneIsRead() = runTest {
        pipeline.ingest(listOf(sms(1, OTP_SENDER, OTP, nov(16, 9))))

        // The OTP was deleted, and the provider gave its id to the bank SMS that came next.
        val result = pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2))))

        assertEquals(0, result.alreadyRead)
        assertEquals(1, result.detected.size)
        assertEquals(2, test.readSms.observeCounts().first().total)
    }

    @Test fun theSameMessageUnderANewIdIsNotStoredTwice() = runTest {
        pipeline.ingest(listOf(sms(1, HDFC, TEA_NO_REFERENCE, nov(16, 9))))

        // After an SMS restore the same message comes back with another id.
        val again = pipeline.ingest(listOf(sms(40, HDFC, TEA_NO_REFERENCE, nov(16, 9))))

        assertEquals(1, again.duplicates)
        assertTrue(again.detected.isEmpty())
        assertEquals(1, count("expenses"))
    }

    @Test fun ignoringAPaymentDropsItsText() = runTest {
        val debit = pipeline.ingest(listOf(sms(1, HDFC, SWIGGY_BANK, nov(16, 13, 2)))).detected.single()

        test.expenses.ignore(debit.id)

        assertEquals(ExpenseStatus.IGNORED, stored(debit.id)?.status)
        assertNull(stored(debit.id)?.originalMessage)
    }

    @Test fun scannerReadsPageByPageAndFindsCopiesAcrossPages() = runTest {
        val inbox = FakeInbox(
            listOf(
                sms(1, APP, SWIGGY_APP, nov(16, 13, 0)),
                sms(2, OTP_SENDER, OTP, nov(16, 13, 1)),
                sms(3, HDFC, SWIGGY_BANK, nov(16, 13, 2)),
                sms(4, SBI_CARD, CARD_DUE, nov(16, 14)),
                sms(5, HDFC, CREDIT, nov(16, 15)),
            ),
            pageSize = 2,
        )
        val progress = mutableListOf<Int>()

        val summary = SmsScanner(inbox, pipeline).scan(nov(16, 0), until = null) { progress += it }

        assertEquals(listOf(2, 4, 5), progress)
        assertEquals(5, summary.read)
        assertEquals(
            listOf(nov(16, 13, 0), nov(16, 13, 1), nov(16, 13, 2), nov(16, 14), nov(16, 15)),
            summary.result.firstRead.map { it.receivedAt },
        )
        // The app copy came first and was replaced by the bank SMS on the next page, so it is one detected payment.
        assertEquals(1, summary.result.detected.size)
        assertEquals(1, count("expenses", "direction = 'DEBIT'"))
        assertEquals(1, summary.result.credits.size)
        assertEquals(1, summary.result.cardBills.size)
        assertEquals(1, summary.result.skipped)
        assertEquals(HDFC, test.expenses.observeDetected().first().single().expense.smsSender)

        // The same period again: every message was read before, so none is read for the first time.
        val again = SmsScanner(inbox, pipeline).scan(nov(16, 0), until = null)
        assertEquals(5, again.read)
        assertEquals(5, again.result.alreadyRead)
        assertTrue(again.result.firstRead.isEmpty())
    }

    @Test fun resultsAddUp() {
        val sum = IngestResult(duplicates = 1, skipped = 2) + IngestResult(skipped = 3, alreadyRead = 4)
        assertEquals(IngestResult(duplicates = 1, skipped = 5, alreadyRead = 4), sum)
        val two = SmsTimes(nov(16, 14), null)
        val three = SmsTimes(nov(16, 15), nov(16, 15))
        assertEquals(listOf(two), (IngestResult(firstRead = listOf(two)) + IngestResult()).firstRead)
        assertEquals(listOf(two), (IngestResult() + IngestResult(firstRead = listOf(two))).firstRead)
        assertEquals(listOf(two, three), (IngestResult(firstRead = listOf(two)) + IngestResult(firstRead = listOf(three))).firstRead)
    }

    private fun sms(id: Long, sender: String, body: String, at: Instant) =
        InboxSms(providerId = id, message = SmsMessage(sender = sender, body = body, receivedAtMillis = at.toEpochMilli()))

    private suspend fun stored(id: Long) = test.expenses.get(id)

    private fun count(table: String, where: String = "1"): Int =
        test.db.query("SELECT COUNT(*) FROM $table WHERE $where", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** An inbox over a fixed list, handed out [pageSize] messages at a time. */
    private class FakeInbox(private val messages: List<InboxSms>, private val pageSize: Int) : PagedSmsInbox {
        override fun canRead(): Boolean = true

        override suspend fun count(from: Instant, until: Instant?): Int = inRange(from, until).size

        override suspend fun forEachPage(
            from: Instant,
            until: Instant?,
            pageSize: Int,
            onPage: suspend (List<InboxSms>) -> Unit,
        ) {
            inRange(from, until).chunked(this.pageSize).forEach { onPage(it) }
        }

        private fun inRange(from: Instant, until: Instant?): List<InboxSms> = messages
            .filter { it.message.receivedAtMillis >= from.toEpochMilli() }
            .filter { until == null || it.message.receivedAtMillis < until.toEpochMilli() }
            .sortedBy { it.message.receivedAtMillis }
    }

    private companion object {
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)

        const val HDFC = "VM-HDFCBK"
        const val HDFC_OTHER_HEADER = "JD-HDFCBK-S"
        const val APP = "VM-GPAYIN"
        const val PAYTM = "VM-PAYTMB"
        const val OTP_SENDER = "VM-HDFCBK-T"
        const val SBI = "VM-SBIINB"
        const val SBI_CARD = "VM-SBICRD-S"

        // Message texts follow the parser's samples (parser/src/test/resources/samples); names and numbers are made up.
        const val SWIGGY_BANK = "Sent Rs.450.00\nFrom HDFC Bank A/C *4521\nTo SWIGGY\nOn 16/11/26\nRef 632045671234\n" +
            "Not You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"
        const val SWIGGY_APP = "Paid Rs.450.00 to Swiggy via UPI. UPI Ref 632045671234."
        const val CREDIT = "Rs. 500.00 credited to a/c XXXXXX4521 on 14-11-26 by a/c linked to VPA " +
            "rahul.example22@okaxis (UPI Ref No 631812345678)."
        const val TEA_1 = "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000001). -HDFC Bank"
        const val TEA_2 = "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl (UPI Ref No 632000000002). -HDFC Bank"
        const val TEA_NO_REFERENCE = "Rs.20.00 debited from A/c XX4521 on 16-11-26 to VPA chai@ybl. -HDFC Bank"
        const val UPI_LITE_TOP_UP = "Rs.500.00 debited from A/c XX4521 on 16-11-26 for UPI Lite top-up. UPI Ref 632011223344 -HDFC Bank"
        const val WALLET_LOAD = "Rs.500 added to your Paytm Wallet. Updated Balance: Rs.850.50. Txn ID: 43198765432"
        const val OTP = "OTP is 482911 for txn of INR 2,499.00 at AMAZON PAY INDIA on HDFC Bank card ending 4521. " +
            "Valid till 10:22. Do not share OTP for security reasons"
        const val CARD_DUE = "Payment of Rs 18,240.00 for your SBI Credit Card ending 6789 is due on 04-Dec-26. " +
            "Please pay on time to avoid late fee."
        const val CARD_SPEND_NO_KIND = "Rs 300.00 spent on HDFC Bank Card XX4521 at CAFE COFFEE DAY on 14-11-2026."
        const val CARD_SPEND_NO_KIND_2 = "Rs 120.00 spent on HDFC Bank Card XX4521 at CHAAYOS on 16-11-2026."
        const val HDFC_CARD_DUE = "Payment of Rs 12,000.00 for your HDFC Bank Credit Card ending 4521 is due on 04-Dec-26. " +
            "Please pay on time to avoid late fee."
        const val UNREADABLE = "Your a/c no. XXXXXXXX7788 is credited by Rs. on 10-11-26 by a/c linked to mobile " +
            "8XXXXXX456-KAVITA RAO (IMPS Ref no 631445612378). -SBI"
    }
}
