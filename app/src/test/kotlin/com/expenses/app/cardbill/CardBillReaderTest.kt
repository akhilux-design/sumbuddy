package com.expenses.app.cardbill

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.CardBillRepository
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.settings.AppSettings
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.ExpenseShareEntity
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupExpenseEntity
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.data.SyncState
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Card bill view's numbers from stored data (docs/card-bills.md 2.11): split card payments of the cycle with a
 * settlement give the drawn ₹24,300 bill, Rahul ₹4,100, Priya ₹2,600, Kiran ₹1,500, ₹8,200 owed and ₹16,100 own.
 */
@RunWith(RobolectricTestRunner::class)
class CardBillReaderTest {
    private val env = GroupsTestEnv()
    private val bills = CardBillRepository(env.db.cardBillDao(), env.transactions)
    private val accounts = AccountRepository(env.db.accountDao(), env.transactions, env.clock)
    private val signedIn = MutableStateFlow(true)
    private val settings = MutableStateFlow(AppSettings(onboardingDone = true, trackingMode = TrackingMode.SMS))
    private val reader = CardBillReader(bills, accounts, env.expenses, env.groups::observeOwedOn, signedIn, settings, TestZone)

    @After fun close() = env.close()

    private fun at(month: Int, day: Int, hour: Int = 12): Instant = LocalDateTime.of(2026, month, day, hour, 0).atZone(TestZone).toInstant()

    private suspend fun card(): Long = env.db.accountDao().insert(
        Account(bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "2217", displayName = "HDFC Millennia", firstSeenAt = at(10, 1)),
    )

    private suspend fun bill(accountId: Long?): Long = bills.upsert(
        CardBill(
            accountId = accountId, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = 2_430_000, minimumDueMinor = 121_500,
            dueDate = LocalDate.of(2026, 11, 21), receivedAt = at(11, 1, 10), smsId = 900,
        ),
    )

    private suspend fun group(id: String, name: String, me: String, friend: MemberEntity) {
        env.db.groupDao().upsertGroup(GroupEntity(id, GroupKind.GROUP, name, ME_USER, me, 1, 1, syncState = SyncState.SYNCED, createdAt = Instant.EPOCH))
        env.db.groupDao().upsertMembers(listOf(MemberEntity(me, id, ME_USER, "Arjun", null, null, onApp = true, removed = false, version = 1), friend))
    }

    private var next = 0

    /** A card payment of [share] + the person's [mine], split with [friend] in [groupId], on [day] October. */
    private suspend fun split(card: Long, groupId: String, me: String, friend: String, share: Long, mine: Long = 10_000, day: Int = 10) {
        val id = "f0000000-0000-4000-8000-%012d".format(++next)
        val amount = share + mine
        env.db.groupDao().upsertExpense(
            GroupExpenseEntity(
                id, groupId, "Dinner $next", Category.FOOD, amount, LocalDate.of(2026, 10, day), me, SplitMethod.AMOUNTS, false, me, me,
                Instant.EPOCH, deleted = false, version = 1, syncState = SyncState.SYNCED,
            ),
        )
        env.db.groupDao().insertShares(listOf(ExpenseShareEntity(id, me, mine, mine), ExpenseShareEntity(id, friend, share, share)))
        env.db.expenseDao().insert(
            Expense(
                amountMinor = amount, direction = Direction.DEBIT, counterparty = "Shop $next", category = Category.FOOD,
                occurredAt = at(10, day), source = ExpenseSource.SMS, status = ExpenseStatus.ADDED, paidWith = PaidWith.CARD,
                accountId = card, smsId = 1_000L + next, smsReceivedAt = at(10, day), myShareMinor = mine, groupExpenseId = id,
            ),
        )
    }

    /** The drawn people: Rahul (on the app) in Goa trip, Priya in Flat 302, Kiran in Dinner. */
    private suspend fun drawn(card: Long) {
        group(G1, "Goa trip", "m-me-1", MemberEntity("m-rahul", G1, RAHUL_USER, "Rahul Menon", null, null, onApp = true, removed = false, version = 1))
        group(G2, "Flat 302", "m-me-2", MemberEntity("m-priya", G2, null, "Priya", "+919000000001", null, onApp = false, removed = false, version = 1))
        group(G3, "Dinner", "m-me-3", MemberEntity("m-kiran", G3, null, "Kiran", "+919000000002", null, onApp = false, removed = false, version = 1))
        // Rahul's shares are ₹5,000 over three payments; he paid ₹900 back, so he owes ₹4,100 now.
        split(card, G1, "m-me-1", "m-rahul", 200_000)
        split(card, G1, "m-me-1", "m-rahul", 200_000, day = 12)
        split(card, G1, "m-me-1", "m-rahul", 100_000, day = 14)
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                "s1", G1, "m-rahul", "m-me-1", 90_000, SettleMethod.UPI, false, LocalDate.of(2026, 10, 20), "m-rahul", null,
                deleted = false, version = 1, syncState = SyncState.SYNCED,
            ),
        )
        repeat(5) { split(card, G2, "m-me-2", "m-priya", 52_000, day = 15 + it) }
        split(card, G3, "m-me-3", "m-kiran", 150_000, day = 25)
    }

    @Test fun theDrawnNumbers() = runTest {
        val card = card()
        val id = bill(card)
        drawn(card)
        val detail = reader.observeBill(id).first()!!
        assertEquals("HDFC Millennia", detail.cardName)
        assertEquals("2217", detail.last4)
        assertEquals(LocalDate.of(2026, 11, 1), detail.statementDate)
        assertEquals(listOf("Rahul Menon" to 410_000L, "Priya" to 260_000L, "Kiran" to 150_000L), detail.friends.map { it.name to it.owedMinor })
        assertEquals(listOf(3, 5, 1), detail.friends.map { it.expenseCount })
        assertEquals(listOf("Goa trip", "Flat 302", "Dinner"), detail.friends.map { it.groupName })
        assertEquals(listOf(true, false, false), detail.friends.map { it.onApp })
        assertEquals(820_000L, detail.friendsMinor)
        assertEquals(1_610_000L, detail.ownMinor)
        assertEquals(BillState.DueIn(5), detail.state(LocalDate.of(2026, 11, 16)))
        assertTrue(detail.current)
    }

    @Test fun signedOutNoFriendsPart() = runTest {
        val card = card()
        val id = bill(card)
        drawn(card)
        signedIn.value = false
        val detail = reader.observeBill(id).first { it != null && !it.signedIn }!!
        assertTrue(detail.friends.isEmpty())
        assertEquals(2_430_000L, detail.ownMinor)
    }

    @Test fun aPaymentOfTheCardMarksItPaid() = runTest {
        val card = card()
        val id = bill(card)
        env.db.expenseDao().insert(
            Expense(
                amountMinor = 2_430_000, direction = Direction.CREDIT, counterparty = null, category = Category.OTHER,
                occurredAt = at(11, 18), source = ExpenseSource.SMS, status = ExpenseStatus.ADDED, accountId = card,
                smsId = 77, smsReceivedAt = at(11, 18),
            ),
        )
        assertEquals(LocalDate.of(2026, 11, 18), reader.observeBill(id).first()!!.paidOn)
    }

    @Test fun aSwitchedOffCardOrABillWithoutOneIsGone() = runTest {
        val card = card()
        val id = bill(card)
        assertTrue(reader.observeBill(id).first() != null)
        accounts.setTracked(card, false)
        assertNull(reader.observeBill(id).first())
        val noCard = bills.upsert(
            CardBill(
                accountId = null, bank = null, cardLast4 = null, totalDueMinor = 100_000, minimumDueMinor = null, dueDate = null,
                receivedAt = at(11, 2), smsId = 901,
            ),
        )
        assertNull(reader.observeBill(noCard).first())
        assertNull(reader.observeBill(9_999).first())
    }

    @Test fun homeListsTheCurrentBillsInSmsModeOnly() = runTest {
        val card = card()
        val id = bill(card)
        drawn(card)
        val rows = reader.observeHomeBills(flowOf(LocalDate.of(2026, 11, 16))).first()
        assertEquals(listOf(CardBillSummary(id, "HDFC Millennia", 2_430_000, BillState.DueIn(5), 820_000)), rows)
        settings.value = settings.value.copy(trackingMode = TrackingMode.MANUAL)
        assertTrue(reader.observeHomeBills(flowOf(LocalDate.of(2026, 11, 16))).first().isEmpty())
    }

    @Test fun aNewerStatementReplacesTheOlderOnHome() = runTest {
        val card = card()
        val old = bill(card)
        val newer = bills.upsert(
            CardBill(
                accountId = card, bank = "HDFC Bank", cardLast4 = "2217", totalDueMinor = 1_200_000, minimumDueMinor = null,
                dueDate = LocalDate.of(2026, 12, 21), receivedAt = at(12, 1), smsId = 902,
            ),
        )
        val rows = reader.observeHomeBills(flowOf(LocalDate.of(2026, 12, 2))).first()
        assertEquals(listOf(newer), rows.map { it.billId })
        assertFalse(reader.observeBill(old).first()!!.current)
    }

    private companion object {
        const val G3 = "b0000000-0000-4000-8000-000000000003"
    }
}
