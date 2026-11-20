package com.expenses.app.data.repository

import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.ReadOutcome
import com.expenses.app.data.model.ReadSms
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.data.model.SmsKey
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordRepositoriesTest {
    private val test = TestDatabase()

    @After fun close() = test.close()

    @Test fun findOrCreateReusesAccountsAndMatchesNulls() = runTest {
        val first = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, null)
        val again = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, null)
        val other = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "2217")
        val upi = test.accounts.findOrCreate(null, AccountType.UPI_ID, null, upiId = "arjun@okhdfc")

        assertTrue(first.isNew)
        assertFalse(again.isNew)
        assertEquals(first.account.id, again.account.id)
        assertTrue(other.isNew)
        assertFalse(test.accounts.findOrCreate(null, AccountType.UPI_ID, null, upiId = "arjun@okhdfc").isNew)
        assertEquals(TestClock.instant(), first.account.firstSeenAt)
        assertTrue(upi.isNew)
        assertEquals(3, test.accounts.observeAll().first().size)
    }

    @Test fun aCardOfUnknownKindIsUpgradedToCreditCardInPlace() = runTest {
        val vague = test.accounts.findOrCreate("HDFC Bank", AccountType.OTHER, "4521").account
        test.accounts.rename(vague.id, "Swiggy card")
        test.accounts.setTracked(vague.id, false)

        val credit = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "4521")

        assertFalse(credit.isNew)
        assertEquals(vague.id, credit.account.id)
        val stored = test.accounts.get(vague.id)!!
        assertEquals(AccountType.CREDIT_CARD, stored.type)
        assertEquals("Swiggy card", stored.displayName)
        assertFalse(stored.tracked)
        assertEquals(1, test.accounts.observeAll().first().size)
    }

    @Test fun aCardOfUnknownKindIsNotUpgradedWhenABankAccountSharesItsDigits() = runTest {
        val vague = test.accounts.findOrCreate("HDFC Bank", AccountType.OTHER, "4521").account
        val bankAccount = test.accounts.findOrCreate("HDFC Bank", AccountType.BANK_ACCOUNT, "4521")
        assertTrue(bankAccount.isNew)

        val credit = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "4521")

        assertTrue(credit.isNew)
        assertEquals(AccountType.CREDIT_CARD, credit.account.type)
        assertEquals(AccountType.OTHER, test.accounts.get(vague.id)!!.type)
        assertEquals(AccountType.BANK_ACCOUNT, test.accounts.get(bankAccount.account.id)!!.type)
        assertEquals(3, test.accounts.observeAll().first().size)
        // The next credit card SMS finds the new card.
        assertEquals(credit.account.id, test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "4521").account.id)
    }

    @Test fun aCreditCardIsNeverDowngraded() = runTest {
        val card = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "4521").account

        val vague = test.accounts.findOrCreate("HDFC Bank", AccountType.OTHER, "4521")

        assertFalse(vague.isNew)
        assertEquals(card.id, vague.account.id)
        assertEquals(AccountType.CREDIT_CARD, test.accounts.get(card.id)!!.type)
    }

    @Test fun onlyAnAccountOfUnknownKindWithTheSameBankAndDigitsIsUpgraded() = runTest {
        val bankAccount = test.accounts.findOrCreate("HDFC Bank", AccountType.BANK_ACCOUNT, "4521").account
        val debitCard = test.accounts.findOrCreate("HDFC Bank", AccountType.DEBIT_CARD, "7781").account
        val otherBank = test.accounts.findOrCreate("ICICI Bank", AccountType.OTHER, "9012").account
        val noDigits = test.accounts.findOrCreate("Axis Bank", AccountType.OTHER, null).account

        assertTrue(test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "4521").isNew)
        assertTrue(test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "7781").isNew)
        assertTrue(test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "9012").isNew)
        assertTrue(test.accounts.findOrCreate("Axis Bank", AccountType.CREDIT_CARD, null).isNew)
        assertTrue(test.accounts.findOrCreate(null, AccountType.CREDIT_CARD, "9012").isNew)

        assertEquals(AccountType.BANK_ACCOUNT, test.accounts.get(bankAccount.id)!!.type)
        assertEquals(AccountType.DEBIT_CARD, test.accounts.get(debitCard.id)!!.type)
        assertEquals(AccountType.OTHER, test.accounts.get(otherBank.id)!!.type)
        assertEquals(AccountType.OTHER, test.accounts.get(noDigits.id)!!.type)
    }

    @Test fun trackingAndRenaming() = runTest {
        val id = test.accounts.findOrCreate("SBI", AccountType.BANK_ACCOUNT, "0034").account.id
        test.accounts.setTracked(id, false)
        test.accounts.rename(id, "  Salary  ")
        val account = test.accounts.get(id)!!
        assertFalse(account.tracked)
        assertEquals("Salary", account.displayName)
        test.accounts.rename(id, " ")
        assertNull(test.accounts.get(id)!!.displayName)
    }

    @Test fun cardBillOfSameDueDateIsReplaced() = runTest {
        val card = test.accounts.findOrCreate("HDFC Bank", AccountType.CREDIT_CARD, "2217").account
        val due = LocalDate.of(2026, 11, 21)
        val bill = CardBill(
            accountId = card.id,
            bank = "HDFC Bank",
            cardLast4 = "2217",
            totalDueMinor = 1_000_000,
            minimumDueMinor = 50_000,
            dueDate = due,
            receivedAt = nov(5),
            smsId = 1,
        )
        val id = test.cardBills.upsert(bill)
        val sameId = test.cardBills.upsert(bill.copy(totalDueMinor = 900_000, receivedAt = nov(16), smsId = 2))
        test.cardBills.upsert(bill.copy(dueDate = due.plusMonths(1), smsId = 3))

        assertEquals(id, sameId)
        val all = test.cardBills.observeAll().first()
        assertEquals(2, all.size)
        assertEquals(900_000L, all.single { it.dueDate == due }.totalDueMinor)
        assertEquals(listOf(due, due.plusMonths(1)), test.cardBills.observeDueFrom(LocalDate.of(2026, 11, 16)).first().map { it.dueDate })
        assertEquals(2, test.cardBills.observeForAccount(card.id).first().size)
    }

    @Test fun resolvingAReviewItemClearsItsText() = runTest {
        val id = test.reviews.add(
            ReviewItem(smsId = 9, sender = "VM-HDFCBK", body = "Rs debited from A/c XX4521", receivedAt = nov(15), hint = "amount"),
        )
        assertEquals(1, test.reviews.observeOpenCount().first())
        test.reviews.resolve(id)
        val item = test.reviews.get(id)!!
        assertTrue(item.resolved)
        assertEquals("", item.body)
        assertTrue(test.reviews.observeOpen().first().isEmpty())
    }

    @Test fun readSmsIsRecordedOnceAndCounted() = runTest {
        val entries = (1L..2_000L).map {
            ReadSms(it, nov(15), SENDER, nov(16), if (it % 10 == 0L) ReadOutcome.PAYMENT else ReadOutcome.SKIPPED)
        }
        test.readSms.markRead(entries)
        test.readSms.markRead(listOf(ReadSms(10, nov(15), SENDER, nov(16), ReadOutcome.REVIEW)))

        val asked = ((1_990L..2_010L) + (1L..950L)).map { SmsKey(it, nov(15), SENDER) }
        val read = test.readSms.alreadyRead(asked).map { it.smsId }.toSet()
        assertEquals((1L..950L).toSet() + (1_990L..2_000L), read)

        val counts = test.readSms.observeCounts().first()
        assertEquals(2_000, counts.total)
        assertEquals(200, counts.used)
        assertEquals(1_800, counts.notStored)
        assertNull(counts.byOutcome[ReadOutcome.REVIEW])
    }

    @Test fun aReusedProviderIdIsANewMessage() = runTest {
        test.readSms.markRead(listOf(ReadSms(5, nov(15), SENDER, nov(15), ReadOutcome.SKIPPED)))

        val sameMessage = SmsKey(5, nov(15), SENDER)
        val laterMessage = SmsKey(5, nov(16), SENDER)
        val otherSender = SmsKey(5, nov(15), "VM-SBIINB")
        assertEquals(setOf(sameMessage), test.readSms.alreadyRead(listOf(sameMessage, laterMessage, otherSender)))

        test.readSms.forgetBefore(nov(16))
        assertTrue(test.readSms.alreadyRead(listOf(sameMessage)).isEmpty())
    }

    @Test fun billsWithoutADueDateOrCardAreNeverMerged() = runTest {
        val bill = CardBill(
            accountId = null,
            bank = null,
            cardLast4 = null,
            totalDueMinor = 1_000_000,
            minimumDueMinor = null,
            dueDate = LocalDate.of(2026, 11, 21),
            receivedAt = nov(5),
            smsId = 1,
        )
        val first = test.cardBills.upsert(bill)
        val second = test.cardBills.upsert(bill.copy(smsId = 2, receivedAt = nov(6)))
        val third = test.cardBills.upsert(bill.copy(bank = "HDFC Bank", dueDate = null, smsId = 3))
        val fourth = test.cardBills.upsert(bill.copy(bank = "HDFC Bank", dueDate = null, smsId = 4, receivedAt = nov(7)))

        assertEquals(4, setOf(first, second, third, fourth).size)
        assertEquals(4, test.cardBills.observeAll().first().size)
    }

    private companion object {
        const val SENDER = "VM-HDFCBK"
    }
}
