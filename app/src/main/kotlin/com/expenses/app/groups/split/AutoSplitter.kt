package com.expenses.app.groups.split

import android.content.Context
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.SplitRule
import com.expenses.app.ingest.IngestResult
import com.expenses.app.ingest.NewPaymentsHook
import com.expenses.app.sms.PaymentNotifications
import com.expenses.parser.Direction
import com.expenses.splits.SplitMath
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.flow.first

/**
 * "Do this every time" with "Yes, every month" (design 02-everyday/14-recurring-split-offer; flow lane 03 "If it
 * repeats"): a new detected payment whose merchant has an automatic split rule is split at once with that rule
 * (`GroupsRepository.splitPayment(..., auto = true)`), and a notification says so ("₹24,000 to Rent · split with
 * Flat 302", [PaymentNotifications.showAutoSplit]) instead of "Payment detected" (docs/server.md 9.4, "Recurring
 * rule"). Only while signed in; offline the split is queued as every split is.
 *
 * A payment is left alone (it waits for Add, Split or Ignore as usual) when:
 * - nobody is signed in;
 * - it is no longer a DETECTED rupee debit that is not split yet, when read again here;
 * - its merchant has no rule, or the rule asks each time;
 * - the rule's group is not on this phone any more, or one of the rule's people left it or was removed;
 * - the rule's parts do not fit this payment (fixed amounts that add up to another total).
 *
 * It runs after the settlement matcher: payments the matcher took as a repayment are already left out of the result
 * it gets. Nothing of the SMS goes anywhere: the group expense gets only what every split sends.
 *
 * Owned by the split-flow area (docs/groups-build-plan.md).
 *
 * @param context for the notification.
 * @param groups the rules, the groups and the split itself.
 * @param expenses the payments, read again before each split.
 * @param signedIn whether someone is signed in now (`AuthRepository.isSignedIn` in the app; a plain function in tests).
 * @param clock the time zone, for the month the share counts in.
 */
class AutoSplitter(
    private val context: Context,
    private val groups: GroupsRepository,
    private val expenses: ExpenseRepository,
    private val signedIn: suspend () -> Boolean,
    private val clock: Clock,
) : NewPaymentsHook {
    override suspend fun onNewPayments(result: IngestResult): Set<Long> {
        if (result.detected.isEmpty() || !signedIn()) return emptySet()
        val split = LinkedHashSet<Long>()
        for (detected in result.detected) {
            if (splitOne(detected.id)) split += detected.id
        }
        return split
    }

    /** Splits payment [expenseId] by its merchant's automatic rule; true when it was split. */
    private suspend fun splitOne(expenseId: Long): Boolean {
        val payment = expenses.get(expenseId)?.takeIf(::isWaitingDebit) ?: return false
        val rule = groups.splitRuleFor(payment.counterparty).first()?.takeIf { it.auto } ?: return false
        if (!fits(rule, payment)) return false
        val groupExpenseId = try {
            groups.splitPayment(expenseId, rule.groupId, rule.method, rule.inputs, rememberRule = false, auto = true)
        } catch (_: IllegalArgumentException) {
            return false // The payment or the group changed in the meantime.
        } catch (_: IllegalStateException) {
            return false // Signed out in the meantime.
        }
        val label = groups.observeSplitLabel(groupExpenseId).first()
        if (label != null) {
            PaymentNotifications.showAutoSplit(
                context = context,
                expense = payment,
                groupId = label.groupId,
                groupName = label.groupName,
                myShareMinor = label.myShareMinor,
                month = YearMonth.from(payment.occurredAt.atZone(clock.zone)),
            )
        }
        return true
    }

    private fun isWaitingDebit(expense: Expense): Boolean =
        expense.status == ExpenseStatus.DETECTED &&
            expense.direction == Direction.DEBIT &&
            expense.currency == Expense.INR &&
            expense.groupExpenseId == null

    /** True when [rule]'s group is here, everyone in the rule is still in it, and its parts fit [payment]. */
    private suspend fun fits(rule: SplitRule, payment: Expense): Boolean {
        if (rule.inputs.isEmpty()) return false
        val group = groups.observeGroup(rule.groupId).first() ?: return false
        val active = group.members.filter { !it.member.removed }.mapTo(HashSet()) { it.member.id }
        if (!active.containsAll(rule.inputs.keys)) return false
        return SplitMath.check(rule.method, payment.amountMinor, rule.inputs) == null
    }
}
