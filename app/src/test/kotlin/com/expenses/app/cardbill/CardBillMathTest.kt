package com.expenses.app.cardbill

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.data.OwedPart
import com.expenses.app.ui.components.AvatarColor
import com.expenses.parser.Direction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card bill rules (docs/card-bills.md 2.2), on plain JVM. */
class CardBillMathTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(month: Int, day: Int, hour: Int = 12, minute: Int = 0): Instant =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant()

    private fun nov(day: Int): LocalDate = LocalDate.of(2026, 11, day)

    private fun bill(
        id: Long = 1,
        accountId: Long? = 1,
        total: Long = 2_430_000,
        due: LocalDate? = nov(21),
        received: Instant = at(11, 1),
        remindedAt: Instant? = null,
    ) = CardBill(id, accountId, "HDFC Bank", "2217", total, 121_500, due, received, smsId = id, remindedAt = remindedAt)

    private var nextId = 1L

    private fun row(
        amount: Long,
        day: Instant,
        direction: Direction = Direction.DEBIT,
        status: ExpenseStatus = ExpenseStatus.ADDED,
        groupExpenseId: String? = "x${nextId}",
        accountId: Long? = 1,
        currency: String = Expense.INR,
    ) = Expense(
        id = nextId++,
        amountMinor = amount,
        currency = currency,
        direction = direction,
        counterparty = "Shop",
        category = Category.FOOD,
        occurredAt = day,
        source = ExpenseSource.SMS,
        status = status,
        accountId = accountId,
        groupExpenseId = groupExpenseId,
    )

    private fun part(
        person: String,
        group: String,
        share: Long,
        pair: Long,
        count: Int = 1,
        removed: Boolean = false,
        onApp: Boolean = true,
        groupName: String? = "Goa trip",
        name: String = person.replaceFirstChar(Char::titlecase),
    ) = OwedPart(group, groupName, "m-$person-$group", person, name, onApp, removed, AvatarColor.Green, share, count, pair)

    private val card = Account(id = 1, bank = "HDFC Bank", type = AccountType.CREDIT_CARD, last4 = "2217", displayName = "HDFC Millennia", firstSeenAt = at(10, 1))

    // ---- Statement date and cycle ----

    @Test fun aStatementSmsDatesTheStatementOnItsDay() {
        assertEquals(nov(1), CardBillMath.statementDate(bill(received = at(11, 1)), zone))
    }

    @Test fun aDueReminderAloneDatesItFifteenDaysBeforeTheDueDate() {
        assertEquals(nov(6), CardBillMath.statementDate(bill(received = at(11, 18)), zone))
    }

    @Test fun withoutADueDateTheFirstSmsDatesIt() {
        assertEquals(nov(3), CardBillMath.statementDate(bill(due = null, received = at(11, 3)), zone))
    }

    @Test fun theCycleIsTheMonthEndingOnTheStatement() {
        // Statement 6 Nov (a due reminder on 18 Nov for 21 Nov).
        val b = bill(received = at(11, 18))
        val onStatement = row(100, at(11, 6, 23, 59))
        val monthBefore = row(100, at(10, 6, 12))
        val dayAfterThat = row(100, at(10, 7, 0, 1))
        val afterStatement = row(100, at(11, 7, 9))
        val paid = CardBillMath.cardPayments(b, listOf(onStatement, monthBefore, dayAfterThat, afterStatement), zone)
        assertEquals(listOf(onStatement.id, dayAfterThat.id), paid.map { it.id })
    }

    @Test fun onlyAddedSplitRupeeDebitsOnTheCardCount() {
        val b = bill()
        val counted = row(100, at(10, 20))
        val rows = listOf(
            counted,
            row(100, at(10, 20), groupExpenseId = null),
            row(100, at(10, 20), status = ExpenseStatus.DETECTED),
            row(100, at(10, 20), direction = Direction.CREDIT),
            row(100, at(10, 20), accountId = 2),
            row(100, at(10, 20), currency = "USD"),
        )
        assertEquals(listOf(counted.id), CardBillMath.cardPayments(b, rows, zone).map { it.id })
        assertTrue(CardBillMath.cardPayments(bill(accountId = null), rows, zone).isEmpty())
    }

    // ---- What friends owe ----

    @Test fun aPartOwesTheSmallerOfItsSharesAndWhatIsOwedNow() {
        assertEquals(30_000L, CardBillMath.owedOf(part("rahul", "g1", share = 30_000, pair = 50_000)))
        assertEquals(20_000L, CardBillMath.owedOf(part("rahul", "g1", share = 30_000, pair = 20_000)))
        assertEquals(0L, CardBillMath.owedOf(part("rahul", "g1", share = 30_000, pair = 0)))
        assertEquals(0L, CardBillMath.owedOf(part("rahul", "g1", share = 30_000, pair = -5_000)))
        assertEquals(0L, CardBillMath.owedOf(part("rahul", "g1", share = 30_000, pair = 50_000, removed = true)))
    }

    @Test fun onePersonInTwoGroupsIsOneRow() {
        val friends = CardBillMath.friends(
            listOf(
                part("rahul", "g1", share = 30_000, pair = 30_000, count = 2, groupName = "Goa trip"),
                part("rahul", "g2", share = 50_000, pair = 90_000, count = 2, groupName = "Flat 302"),
            ),
        )
        val rahul = friends.single()
        assertEquals(80_000L, rahul.owedMinor)
        assertEquals(4, rahul.expenseCount)
        assertEquals(listOf("g2", "g1"), rahul.groupIds)
        assertNull("several groups: no one group name", rahul.groupName)
        // The group where their part is largest opens on a tap.
        assertEquals("g2", rahul.targetGroupId)
        assertEquals("m-rahul-g2", rahul.targetMemberId)
    }

    @Test fun rowsAreTheLargestFirstThenByNameAndNothingOwedIsLeftOut() {
        val friends = CardBillMath.friends(
            listOf(
                part("kiran", "g3", share = 150_000, pair = 150_000, groupName = "Dinner"),
                part("rahul", "g1", share = 410_000, pair = 500_000),
                part("priya", "g2", share = 260_000, pair = 260_000, groupName = "Flat 302"),
                part("anu", "g2", share = 150_000, pair = 150_000, groupName = "Flat 302"),
                part("zed", "g2", share = 100_000, pair = 0, groupName = "Flat 302"),
            ),
        )
        assertEquals(listOf("Rahul", "Priya", "Anu", "Kiran"), friends.map { it.name })
        assertEquals(970_000L, CardBillMath.friendsTotal(friends))
        assertEquals("Goa trip", friends.first().groupName)
    }

    @Test fun moreThanTheBillKeepsTheHeadingAndBoundsTheBarAndOwnPart() {
        val friends = CardBillMath.friends(listOf(part("rahul", "g1", share = 3_000_000, pair = 3_000_000)))
        val total = 2_430_000L
        val owed = CardBillMath.friendsTotal(friends)
        assertEquals(3_000_000L, owed)
        assertEquals(1f, CardBillMath.barFraction(total, owed))
        assertEquals(0L, CardBillMath.ownMinor(total, owed))
    }

    @Test fun theDrawnNumbers() {
        assertEquals(1_610_000L, CardBillMath.ownMinor(2_430_000, 820_000))
        assertEquals(820_000f / 2_430_000f, CardBillMath.barFraction(2_430_000, 820_000), 0.0001f)
        assertEquals(0f, CardBillMath.barFraction(2_430_000, 0))
    }

    // ---- Paid ----

    @Test fun creditsThatAddUpToTheBillPayIt() {
        val b = bill(received = at(11, 1))
        val exact = row(2_430_000, at(11, 15), direction = Direction.CREDIT, groupExpenseId = null)
        assertEquals(nov(15), CardBillMath.paidOn(b, listOf(exact), zone))
    }

    @Test fun twoPartPaymentsPayItOnTheSecond() {
        val b = bill()
        val first = row(1_000_000, at(11, 10), direction = Direction.CREDIT, groupExpenseId = null)
        val second = row(1_430_000, at(11, 19), direction = Direction.CREDIT, groupExpenseId = null)
        assertEquals(nov(19), CardBillMath.paidOn(b, listOf(second, first), zone))
    }

    @Test fun theMinimumDueAloneDoesNotPayIt() {
        val b = bill()
        val minimum = row(121_500, at(11, 18), direction = Direction.CREDIT, groupExpenseId = null)
        assertNull(CardBillMath.paidOn(b, listOf(minimum), zone))
    }

    @Test fun aCreditOnTheStatementDayOrAfterTheGraceIsNotThisBills() {
        val b = bill(received = at(11, 1))
        val onStatement = row(2_430_000, at(11, 1, 18), direction = Direction.CREDIT, groupExpenseId = null)
        val late = row(2_430_000, at(11, 29), direction = Direction.CREDIT, groupExpenseId = null)
        val lastDay = row(2_430_000, at(11, 28), direction = Direction.CREDIT, groupExpenseId = null)
        assertNull(CardBillMath.paidOn(b, listOf(onStatement, late), zone))
        assertEquals(nov(28), CardBillMath.paidOn(b, listOf(onStatement, lastDay), zone))
    }

    // ---- State ----

    @Test fun theStateFollowsTheDaysLeft() {
        val b = bill(due = nov(21))
        assertEquals(BillState.DueToday, CardBillMath.state(b, null, nov(21)))
        assertEquals(BillState.DueTomorrow, CardBillMath.state(b, null, nov(20)))
        assertEquals(BillState.DueIn(5), CardBillMath.state(b, null, nov(16)))
        assertEquals(BillState.Overdue(nov(21)), CardBillMath.state(b, null, nov(22)))
        assertEquals(BillState.NoDate, CardBillMath.state(bill(due = null), null, nov(22)))
        // Paid wins over overdue.
        assertEquals(BillState.Paid(nov(19)), CardBillMath.state(b, nov(19), nov(25)))
    }

    // ---- Reminder time ----

    @Test fun beforeTheWindowItIsNineOnDueLessThree() {
        assertEquals(at(11, 18, 9), CardBillMath.fireAt(nov(21), at(11, 10, 14), zone))
    }

    @Test fun insideTheWindowInTheDayItIsNow() {
        val now = at(11, 19, 14)
        assertEquals(now, CardBillMath.fireAt(nov(21), now, zone))
    }

    @Test fun earlyInTheMorningItIsNineToday() {
        assertEquals(at(11, 19, 9), CardBillMath.fireAt(nov(21), at(11, 19, 7), zone))
    }

    @Test fun lateAtNightItIsNineTomorrow() {
        assertEquals(at(11, 20, 9), CardBillMath.fireAt(nov(21), at(11, 19, 22), zone))
    }

    @Test fun lateOnTheDueDateThereIsNone() {
        assertNull(CardBillMath.fireAt(nov(21), at(11, 21, 22), zone))
    }

    @Test fun afterTheDueDateThereIsNone() {
        assertNull(CardBillMath.fireAt(nov(21), at(11, 22, 10), zone))
    }

    @Test fun aReminderNeedsEverything() {
        val now = at(11, 19, 14)
        val b = bill()
        assertEquals(now, CardBillMath.reminderAt(b, live = true, paidOn = null, remindersOn = true, now = now, zone = zone))
        assertNull(CardBillMath.reminderAt(b, live = false, paidOn = null, remindersOn = true, now = now, zone = zone))
        assertNull(CardBillMath.reminderAt(b, live = true, paidOn = nov(18), remindersOn = true, now = now, zone = zone))
        assertNull(CardBillMath.reminderAt(b, live = true, paidOn = null, remindersOn = false, now = now, zone = zone))
        assertNull(CardBillMath.reminderAt(bill(remindedAt = at(11, 18, 9)), live = true, paidOn = null, remindersOn = true, now = now, zone = zone))
        assertNull(CardBillMath.reminderAt(bill(due = null), live = true, paidOn = null, remindersOn = true, now = now, zone = zone))
    }

    // ---- Home list ----

    @Test fun homeShowsOnlyTheCurrentBillOfACard() {
        val older = bill(id = 1, due = nov(21), received = at(11, 1))
        val newer = bill(id = 2, due = LocalDate.of(2026, 12, 21), received = at(12, 1))
        val all = listOf(older, newer)
        val shown = CardBillMath.homeList(all.map { BillFacts(it, card, null) }, all, smsMode = true, today = LocalDate.of(2026, 12, 2), zone = zone)
        assertEquals(listOf(2L), shown.map { it.bill.id })
        assertTrue(CardBillMath.isCurrent(newer, all, zone))
        assertFalse(CardBillMath.isCurrent(older, all, zone))
    }

    @Test fun aNewerStatementWithoutADueDateTakesOver() {
        val older = bill(id = 1, due = nov(21), received = at(11, 1))
        val newer = bill(id = 2, due = null, received = at(12, 1))
        assertTrue(CardBillMath.isCurrent(newer, listOf(older, newer), zone))
    }

    @Test fun aPaidBillLeavesHome() {
        val b = bill()
        assertTrue(CardBillMath.homeList(listOf(BillFacts(b, card, null)), listOf(b), true, nov(16), zone).isNotEmpty())
        assertTrue(CardBillMath.homeList(listOf(BillFacts(b, card, nov(15))), listOf(b), true, nov(16), zone).isEmpty())
    }

    @Test fun anOverdueBillStaysAWeek() {
        val b = bill(due = nov(21))
        val facts = listOf(BillFacts(b, card, null))
        assertEquals(1, CardBillMath.homeList(facts, listOf(b), true, nov(28), zone).size)
        assertEquals(0, CardBillMath.homeList(facts, listOf(b), true, nov(29), zone).size)
    }

    @Test fun aBillWithoutADueDateStaysThirtyDays() {
        val b = bill(due = null, received = at(11, 1))
        val facts = listOf(BillFacts(b, card, null))
        assertEquals(1, CardBillMath.homeList(facts, listOf(b), true, LocalDate.of(2026, 12, 1), zone).size)
        assertEquals(0, CardBillMath.homeList(facts, listOf(b), true, LocalDate.of(2026, 12, 2), zone).size)
    }

    @Test fun aSwitchedOffCardANoCardBillAndManualModeShowNothing() {
        val b = bill()
        val off = card.copy(tracked = false)
        assertTrue(CardBillMath.homeList(listOf(BillFacts(b, off, null)), listOf(b), true, nov(16), zone).isEmpty())
        val noCard = bill(accountId = null)
        assertTrue(CardBillMath.homeList(listOf(BillFacts(noCard, null, null)), listOf(noCard), true, nov(16), zone).isEmpty())
        assertTrue(CardBillMath.homeList(listOf(BillFacts(b, card, null)), listOf(b), false, nov(16), zone).isEmpty())
    }

    @Test fun severalCardsAreSortedByDueDateThenName() {
        val icici = Account(id = 2, bank = "ICICI Bank", type = AccountType.CREDIT_CARD, last4 = "1100", displayName = "ICICI Amazon Pay", firstSeenAt = at(10, 1))
        val axis = Account(id = 3, bank = "Axis Bank", type = AccountType.CREDIT_CARD, last4 = "9000", firstSeenAt = at(10, 1))
        val hdfcBill = bill(id = 1, accountId = 1, due = nov(21))
        val iciciBill = bill(id = 2, accountId = 2, due = nov(28))
        val axisBill = bill(id = 3, accountId = 3, due = null, received = at(11, 10))
        val sameDay = bill(id = 4, accountId = 3, due = null, received = at(11, 2))
        val all = listOf(hdfcBill, iciciBill, axisBill, sameDay)
        val facts = listOf(BillFacts(iciciBill, icici, null), BillFacts(axisBill, axis, null), BillFacts(hdfcBill, card, null), BillFacts(sameDay, axis, null))
        assertEquals(listOf(1L, 2L, 3L), CardBillMath.homeList(facts, all, true, nov(16), zone).map { it.bill.id })
    }
}
