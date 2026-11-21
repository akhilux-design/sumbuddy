package com.expenses.app.cardbill

import com.expenses.app.data.model.Account
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.data.OwedPart
import com.expenses.app.ui.components.AvatarColor
import com.expenses.parser.Direction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Where a card bill stands on a given day (docs/card-bills.md 2.2, State). */
sealed interface BillState {
    /**
     * The card's payments after the statement add up to the bill.
     *
     * @param on the day they reached it.
     */
    data class Paid(val on: LocalDate) : BillState

    /** Due today. */
    data object DueToday : BillState

    /** Due tomorrow. */
    data object DueTomorrow : BillState

    /**
     * Due in [days] days, 2 or more.
     *
     * @param days whole days from today to the due date.
     */
    data class DueIn(val days: Int) : BillState

    /**
     * The due date passed and the bill is not paid.
     *
     * @param dueDate the due date.
     */
    data class Overdue(val dueDate: LocalDate) : BillState

    /** The SMS printed no due date. */
    data object NoDate : BillState
}

/**
 * One friend's part of a card bill: what they owe the person for the bill's split card payments, over every group
 * (docs/card-bills.md 2.2, Friend rows).
 *
 * @param personKey the friend across groups.
 * @param name the friend's whole name.
 * @param avatar the colour behind their initial.
 * @param owedMinor what they owe of this bill, in paise; above 0.
 * @param expenseCount how many of the bill's split payments they have a share of.
 * @param groupIds the groups it is owed in, each once, the largest part first.
 * @param groupName the group's name when it is all owed in one named group; null for a direct split or several groups.
 * @param targetGroupId the group where their part is largest (ties: the lower id): a tap on their row opens it.
 * @param targetMemberId their member in [targetGroupId].
 * @param onApp true when they are on the app: Remind everyone sends them a push.
 * @param parts their parts that owe something, one per group.
 */
data class FriendOwes(
    val personKey: String,
    val name: String,
    val avatar: AvatarColor,
    val owedMinor: Long,
    val expenseCount: Int,
    val groupIds: List<String>,
    val groupName: String?,
    val targetGroupId: String,
    val targetMemberId: String,
    val onApp: Boolean,
    val parts: List<OwedPart>,
)

/**
 * A bill with what the rules need to place it: its card and the day its payments added up to it.
 *
 * @param bill the bill.
 * @param account its card, or null when the bill names none or the card is gone.
 * @param paidOn the day the card's payments reached the bill, or null.
 * @param friendsMinor what friends owe of it ([CardBillMath.friendsTotal]).
 */
data class BillFacts(val bill: CardBill, val account: Account?, val paidOn: LocalDate?, val friendsMinor: Long = 0)

/**
 * The rules of card bills (docs/card-bills.md 2.2): the bill's cycle, which card payments belong to it, what friends
 * owe of it, whether it is paid, its state, which bill of a card is current, which bills Home shows and when the
 * reminder goes. Pure: every date is an India date of the [ZoneId] given.
 */
object CardBillMath {
    /** A due reminder alone dates the statement this many days before the due date (D6). */
    const val STATEMENT_GAP_DAYS: Long = 15

    /** Payments up to this many days after the due date still pay the bill (D13); an overdue bill shows as long (D18). */
    const val GRACE_DAYS: Long = 7

    /** Without a due date, payments up to this many days after the statement pay the bill (D13). */
    const val NO_DATE_PAID_DAYS: Long = 37

    /** Without a due date, Home shows the bill this many days after the statement (D18). */
    const val NO_DATE_SHOWN_DAYS: Long = 30

    /** The reminder's time of day, and the end of quiet hours (D3). */
    val REMIND_AT: LocalTime = LocalTime.of(9, 0)

    /** The start of quiet hours (D3). */
    val QUIET_FROM: LocalTime = LocalTime.of(21, 0)

    /** The statement date: the earlier of the bill's first SMS day and the due date less 15 days (D6). */
    fun statementDate(bill: CardBill, zone: ZoneId): LocalDate {
        val first = bill.receivedAt.atZone(zone).toLocalDate()
        val due = bill.dueDate ?: return first
        return minOf(first, due.minusDays(STATEMENT_GAP_DAYS))
    }

    /** The first day of the cycle that ends on [statement]: the day after the same day a month before. */
    fun cycleStart(statement: LocalDate): LocalDate = statement.minusMonths(1).plusDays(1)

    /** True when [date] is in the cycle that ends on [statement]. */
    fun inCycle(date: LocalDate, statement: LocalDate): Boolean = date > statement.minusMonths(1) && !date.isAfter(statement)

    /** The last day a payment on the card still pays [bill]: due date + 7 days, or statement + 37 days without one. */
    fun paidWindowEnd(bill: CardBill, statement: LocalDate): LocalDate =
        bill.dueDate?.plusDays(GRACE_DAYS) ?: statement.plusDays(NO_DATE_PAID_DAYS)

    /**
     * The card payments of [bill]'s cycle: rupee debits on its card, added, split (linked to a group expense), dated in
     * the cycle.
     */
    fun cardPayments(bill: CardBill, expenses: List<Expense>, zone: ZoneId): List<Expense> {
        val accountId = bill.accountId ?: return emptyList()
        val statement = statementDate(bill, zone)
        return expenses.filter {
            it.accountId == accountId && it.direction == Direction.DEBIT && it.currency == Expense.INR &&
                it.status == ExpenseStatus.ADDED && it.groupExpenseId != null &&
                inCycle(it.occurredAt.atZone(zone).toLocalDate(), statement)
        }
    }

    /** What one part counts: the smaller of their shares and what they owe now; 0 for a removed member (D9, D10). */
    fun owedOf(part: OwedPart): Long = if (part.removed) 0 else minOf(part.shareMinor, maxOf(part.pairMinor, 0))

    /** The friend rows: parts that owe something, grouped by person, the largest amount first, then by name. */
    fun friends(parts: List<OwedPart>): List<FriendOwes> =
        parts.filter { owedOf(it) > 0 }
            .groupBy { it.personKey }
            .map { (key, owing) ->
                val byGroup = owing.sortedWith(compareByDescending<OwedPart> { owedOf(it) }.thenBy { it.groupId })
                val target = byGroup.first()
                val groupIds = byGroup.map { it.groupId }.distinct()
                FriendOwes(
                    personKey = key,
                    name = target.name,
                    avatar = target.avatar,
                    owedMinor = owing.sumOf(::owedOf),
                    expenseCount = owing.sumOf { it.expenseCount },
                    groupIds = groupIds,
                    groupName = if (groupIds.size == 1) target.groupName else null,
                    targetGroupId = target.groupId,
                    targetMemberId = target.memberId,
                    onApp = owing.any { it.onApp },
                    parts = byGroup,
                )
            }
            .sortedWith(compareByDescending<FriendOwes> { it.owedMinor }.thenBy { it.name.lowercase() })

    /** What friends owe of the bill altogether: the heading's amount (never cut, D11). */
    fun friendsTotal(friends: List<FriendOwes>): Long = friends.sumOf { it.owedMinor }

    /** The person's own part: the bill less what friends owe, never below ₹0 (D11). */
    fun ownMinor(totalMinor: Long, friendsMinor: Long): Long = maxOf(totalMinor - friendsMinor, 0)

    /** How full the bar is: friends' part of the bill, at most full (D12). */
    fun barFraction(totalMinor: Long, friendsMinor: Long): Float = when {
        friendsMinor <= 0 -> 0f
        totalMinor <= 0 -> 1f
        else -> (friendsMinor.toDouble() / totalMinor).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The day [bill] was paid: rupee credits on its card (any status) dated after the statement and up to
     * [paidWindowEnd], in time order, the day their running sum reaches the bill (D13); null when they never do.
     */
    fun paidOn(bill: CardBill, expenses: List<Expense>, zone: ZoneId): LocalDate? {
        val accountId = bill.accountId ?: return null
        val statement = statementDate(bill, zone)
        val end = paidWindowEnd(bill, statement)
        var sum = 0L
        expenses
            .filter { it.accountId == accountId && it.direction == Direction.CREDIT && it.currency == Expense.INR }
            .map { it to it.occurredAt.atZone(zone).toLocalDate() }
            .filter { (_, day) -> day.isAfter(statement) && !day.isAfter(end) }
            .sortedWith(compareBy({ it.first.occurredAt }, { it.first.id }))
            .forEach { (credit, day) ->
                sum += credit.amountMinor
                if (sum >= bill.totalDueMinor) return day
            }
        return null
    }

    /** Where [bill] stands on [today]: paid wins over everything; then by the days left to the due date. */
    fun state(bill: CardBill, paidOn: LocalDate?, today: LocalDate): BillState {
        if (paidOn != null) return BillState.Paid(paidOn)
        val due = bill.dueDate ?: return BillState.NoDate
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days < 0 -> BillState.Overdue(due)
            days == 0L -> BillState.DueToday
            days == 1L -> BillState.DueTomorrow
            else -> BillState.DueIn(days.toInt())
        }
    }

    /**
     * True when [bill] is the current bill of its card among [all] (D14): the latest due date, then the latest first
     * SMS. A bill without a due date counts by its estimated due date (statement + 15 days), so a newer statement that
     * printed none still takes over from an older bill.
     */
    fun isCurrent(bill: CardBill, all: List<CardBill>, zone: ZoneId): Boolean {
        val accountId = bill.accountId ?: return false
        val order = compareBy<CardBill>({ dueKey(it, zone) }, { it.receivedAt }, { it.id })
        val latest = all.filter { it.accountId == accountId }.maxWithOrNull(order) ?: return true
        return latest.id == bill.id
    }

    private fun dueKey(bill: CardBill, zone: ZoneId): LocalDate =
        bill.dueDate ?: statementDate(bill, zone).plusDays(STATEMENT_GAP_DAYS)

    /**
     * True when [facts]' bill may show on Home or be reminded at all (D14 to D16): SMS mode, a card that is still
     * tracked, and the current bill of that card among [all].
     */
    fun isLive(facts: BillFacts, all: List<CardBill>, smsMode: Boolean, zone: ZoneId): Boolean {
        val account = facts.account ?: return false
        return smsMode && facts.bill.accountId == account.id && account.tracked && isCurrent(facts.bill, all, zone)
    }

    /**
     * True when Home shows a live bill on [today] (D18): until it is paid, an overdue bill until 7 days after its due
     * date, and one without a due date until 30 days after its statement.
     */
    fun showsOnHome(facts: BillFacts, today: LocalDate, zone: ZoneId): Boolean =
        when (val state = state(facts.bill, facts.paidOn, today)) {
            is BillState.Paid -> false
            is BillState.Overdue -> !today.isAfter(state.dueDate.plusDays(GRACE_DAYS))
            BillState.NoDate -> !today.isAfter(statementDate(facts.bill, zone).plusDays(NO_DATE_SHOWN_DAYS))
            else -> true
        }

    /**
     * The bills Home lists on [today] (docs/card-bills.md 2.2, On Home), sorted by due date (no date last), then card
     * name. [all] is every stored bill, to tell the current one of each card.
     */
    fun homeList(facts: List<BillFacts>, all: List<CardBill>, smsMode: Boolean, today: LocalDate, zone: ZoneId): List<BillFacts> =
        facts.filter { isLive(it, all, smsMode, zone) && showsOnHome(it, today, zone) }
            .sortedWith(
                compareBy<BillFacts>({ it.bill.dueDate == null }, { it.bill.dueDate }, { cardName(it.account, it.bill).orEmpty().lowercase() }),
            )

    /** The card's name as the bill view and Home write it: the person's name for it, else the bank; null for neither. */
    fun cardName(account: Account?, bill: CardBill): String? = account?.displayName ?: account?.bank ?: bill.bank

    /**
     * When the reminder of a bill due on [dueDate] goes, seen at [now] (D3), or null for none: 09:00 three days before
     * the due date; inside those days at once in the day, at 09:00 when it is still before 09:00, and at the next
     * 09:00 that is not after the due date when it is past 21:00. Nothing after the due date.
     */
    fun fireAt(dueDate: LocalDate, now: Instant, zone: ZoneId): Instant? {
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        if (today.isAfter(dueDate)) return null
        val target = dueDate.minusDays(CardBillReminders.DAYS_BEFORE.toLong()).atTime(REMIND_AT).atZone(zone).toInstant()
        if (now.isBefore(target)) return target
        val time = local.toLocalTime()
        return when {
            time.isBefore(REMIND_AT) -> today.atTime(REMIND_AT).atZone(zone).toInstant()
            time.isBefore(QUIET_FROM) -> now
            else -> {
                val tomorrow = today.plusDays(1)
                if (tomorrow.isAfter(dueDate)) null else tomorrow.atTime(REMIND_AT).atZone(zone).toInstant()
            }
        }
    }

    /**
     * When the automatic reminder of [bill] goes, seen at [now], or null when it is not due one (docs/card-bills.md
     * 2.7): it must be [live], have a due date, not be reminded yet nor paid, reminders on, and [fireAt] must give a time.
     */
    fun reminderAt(bill: CardBill, live: Boolean, paidOn: LocalDate?, remindersOn: Boolean, now: Instant, zone: ZoneId): Instant? {
        val due = bill.dueDate ?: return null
        if (!live || !remindersOn || bill.remindedAt != null || paidOn != null) return null
        return fireAt(due, now, zone)
    }

    /** How long a late run may be from its time and still post (a WorkManager run is rarely to the second). */
    val ON_TIME: Duration = Duration.ofMinutes(1)
}
