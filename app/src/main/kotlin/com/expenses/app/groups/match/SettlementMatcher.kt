package com.expenses.app.groups.match

import android.content.Context
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.groups.data.GroupExtrasDao
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.PendingUpiEntity
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.sync.GroupNotifications
import com.expenses.app.ingest.IngestResult
import com.expenses.app.ingest.NewPaymentsHook
import com.expenses.app.ui.format.shortName
import com.expenses.app.ui.nav.AppLink
import com.expenses.parser.Direction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Settling up from SMS, on this phone only (docs/server.md 9.5; design 03-getting-paid-back/02 and 03, flow lane 05):
 *
 * - **Pay by UPI**: a UPI debit (or one whose SMS names no channel; never card, ATM, wallet or bank transfer) of
 *   exactly the amount of a pending UPI payment (`grp_pending_upi`, made by [startUpiPayment] when Settle up opened
 *   the UPI app) within 30 minutes, to the friend's UPI ID when the SMS shows one, records the settlement
 *   (`method = UPI`, `matchedFromSms = true`), sets that payment to IGNORED (repaying a friend is not spending) and
 *   forgets the pending row. Pending rows older than 30 minutes are forgotten.
 * - **Money received**: a new credit, at most a day old, of exactly what a friend owes the person ([CreditMatching]).
 *   One candidate whose name matches the SMS: settled at once, one settlement per group, and "Rahul paid you ₹1,200"
 *   is posted (02-everyday/04; a tap opens All square). Same amount, unclear name: "Was this Rahul paying you back?"
 *   is posted (a tap opens Money received) and nothing is settled. Money into an account the person switched off is
 *   left alone.
 *
 * When both people have the app, both phones see the payment: the payer's debit and the receiver's credit. Every
 * settlement matched from SMS takes an id both phones work out the same way (`GroupsRepository.recordSmsSettlement`),
 * so the server keeps one. When the receiver's record reached this phone before the payer's debit SMS did, the debit
 * is that payment: it is set to IGNORED and nothing new is recorded.
 *
 * It is the first hook: the ids it returns are left out of what the auto splitter sees, so a repayment is never also
 * split as spending. It does nothing while nobody is signed in.
 *
 * Only the settlement goes to the server; the SMS stays on the phone and nothing is logged.
 *
 * Owned by the settle-up area (docs/groups-build-plan.md).
 *
 * @param signedIn whether someone is signed in now (`AuthRepository.isSignedIn` in the app; a plain function in tests).
 */
class SettlementMatcher(
    private val context: Context,
    private val groups: GroupsRepository,
    private val expenses: ExpenseRepository,
    private val extras: GroupExtrasDao,
    private val signedIn: suspend () -> Boolean,
    private val clock: Clock,
) : NewPaymentsHook {
    private val notifications by lazy { GroupNotifications(context) }

    /**
     * Pay by UPI was tapped on Settle up: remembers that the person is paying [toMemberId] [amountMinor] in [groupId],
     * so the bank's debit SMS can settle it. Tapping again for the same friend starts the 30 minutes again.
     */
    suspend fun startUpiPayment(groupId: String, toMemberId: String, amountMinor: Long) {
        extras.upsertPendingUpi(PendingUpiEntity(pendingId(groupId, toMemberId), groupId, toMemberId, amountMinor, clock.instant()))
    }

    /** The UPI app did not open: forgets the payment [startUpiPayment] remembered. */
    suspend fun forgetUpiPayment(groupId: String, toMemberId: String) {
        extras.deletePendingUpi(pendingId(groupId, toMemberId))
    }

    override suspend fun onNewPayments(result: IngestResult): Set<Long> {
        if (!signedIn()) return emptySet()
        val handled = HashSet<Long>()
        val pending = extras.allPendingUpi().toMutableList()
        for (debit in result.detected) {
            if (pending.isEmpty()) break
            if (guard { matchDebit(debit, pending) } == true) handled += debit.id
        }
        val now = clock.instant()
        pending.filter { it.startedAt.isBefore(now.minus(UPI_WINDOW)) }.forEach { extras.deletePendingUpi(it.id) }
        for (credit in result.credits) {
            if (guard { matchCredit(credit) } == true) handled += credit.id
        }
        return handled
    }

    /**
     * Settles the pending UPI payment [debit] is, if any. True when it did. Only a payment that may be UPI counts: a
     * card, ATM, wallet or bank transfer SMS never shows a UPI ID, and taking it would hide real spending for good.
     */
    private suspend fun matchDebit(debit: Expense, pending: MutableList<PendingUpiEntity>): Boolean {
        if (debit.direction != Direction.DEBIT || debit.currency != Expense.INR) return false
        if (debit.paidWith !in MAY_BE_UPI) return false
        val at = debit.smsReceivedAt ?: debit.occurredAt
        for (row in pending.sortedBy { it.startedAt }) {
            if (row.amountMinor != debit.amountMinor || !within(at, row.startedAt)) continue
            val detail = groups.observeGroup(row.groupId).first()
            val balance = detail?.members?.firstOrNull { it.member.id == row.toMemberId && !it.isMe }
            if (detail == null || balance == null || detail.group.kind == GroupKind.FAMILY) {
                // The group or the friend left, or it is a family (nobody owes anybody there): nothing to settle.
                forget(row, pending)
                continue
            }
            val friend = balance.member
            val smsVpa = debit.counterpartyVpa?.trim()?.takeIf { it.isNotEmpty() }
            val friendVpa = friend.upiId?.trim()?.takeIf { it.isNotEmpty() }
            if (smsVpa != null && friendVpa != null && !smsVpa.equals(friendVpa, ignoreCase = true)) continue
            val me = detail.group.myMemberId
            if (detail.settlements.any { it.isFriendsSmsRecordOf(row, me) }) {
                // The friend's phone recorded this repayment from its money-in SMS first, and this phone has it: the
                // debit is that payment. It is not spending, and nothing new is recorded.
                if (expenses.get(debit.id)?.status != ExpenseStatus.DETECTED) return false
                expenses.ignore(debit.id)
                forget(row, pending)
                return true
            }
            if (balance.pairMinor >= 0) {
                // The person no longer owes them (marked as paid meanwhile): nothing to settle.
                forget(row, pending)
                continue
            }
            if (expenses.get(debit.id)?.status != ExpenseStatus.DETECTED) return false
            groups.recordSmsSettlement(row.groupId, me, row.toMemberId, row.amountMinor, SettleMethod.UPI, smsDate(debit, clock.zone))
            expenses.ignore(debit.id)
            forget(row, pending)
            val name = shortName(friend.name, detail.members.map { it.member.name })
            notifications.postPaidByUpi(debit.id, name, row.amountMinor, row.groupId, detail.group.kind, detail.name)
            return true
        }
        return false
    }

    /**
     * Whether this settlement is the friend's own record, from their money-in SMS, of the payment [row] stands for:
     * the person paid them that amount, their phone matched it to an SMS (so it was confirmed when recorded), and it
     * was recorded after Pay by UPI was tapped. An earlier repayment of the same amount was confirmed before the tap.
     */
    private fun SettlementEntity.isFriendsSmsRecordOf(row: PendingUpiEntity, me: String): Boolean {
        val recordedAt = confirmedAt ?: return false
        return !deleted && matchedFromSms && fromMemberId == me && toMemberId == row.toMemberId &&
            recordedByMemberId == row.toMemberId && amountMinor == row.amountMinor &&
            !recordedAt.isBefore(row.startedAt.minus(CLOCK_SKEW))
    }

    private suspend fun forget(row: PendingUpiEntity, pending: MutableList<PendingUpiEntity>) {
        extras.deletePendingUpi(row.id)
        pending.remove(row)
    }

    /**
     * Settles, or asks about, the friend money in [credit] may be from. True when it settled. A credit older than
     * [CREDIT_WINDOW] (read late, when the app catches up) is left alone: it may have arrived before the debt existed.
     * So is money into an account the person switched off: the app keeps out of it, as it does everywhere else.
     */
    private suspend fun matchCredit(credit: Expense): Boolean {
        if (credit.direction != Direction.CREDIT || credit.currency != Expense.INR) return false
        if (expenses.observe(credit.id).first()?.account?.tracked == false) return false
        val at = credit.smsReceivedAt ?: credit.occurredAt
        if (at.isBefore(clock.instant().minus(CREDIT_WINDOW))) return false
        val details = groups.observeGroupDetails().first()
        val friends = CreditMatching.owingFriends(details)
        // First names, as the design writes them ("Rahul paid you"); whole names where two people share one.
        val everyone = details.flatMap { detail -> detail.members.map { it.member.name } }.distinct()
        return when (val match = CreditMatching.match(credit.amountMinor, credit.counterparty, credit.counterpartyVpa, friends)) {
            CreditMatch.None -> false
            is CreditMatch.Ask -> {
                notifications.postAskRepaid(credit.id, shortName(match.friend.name, everyone), credit.amountMinor)
                false
            }
            is CreditMatch.Clear -> {
                val settled = settleCredit(groups, match.friend, credit, clock.zone)
                if (settled.isEmpty()) return false
                val largest = settled.maxBy { it.amountMinor }
                notifications.postRepaid(
                    creditId = credit.id,
                    friendName = shortName(match.friend.name, everyone),
                    amountMinor = credit.amountMinor,
                    cleared = settled.map { it.groupKind to it.groupName },
                    open = AppLink.AllSquare(largest.groupId, largest.memberId, credit.amountMinor),
                )
                true
            }
        }
    }

    private fun within(at: Instant, startedAt: Instant): Boolean =
        !at.isBefore(startedAt.minus(CLOCK_SKEW)) && !at.isAfter(startedAt.plus(UPI_WINDOW))

    /** Runs [block]; a failure (a group gone meanwhile, a database error) skips this payment, and nothing is logged. */
    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Constants and the settling that Money received's "Yes, clear it" shares. */
    companion object {
        /** How long after Pay by UPI the bank's debit SMS may arrive. */
        val UPI_WINDOW: Duration = Duration.ofMinutes(30)

        /**
         * How old a money-received SMS may be to settle or ask about a debt: the same day as the "Payment detected"
         * notifications of IncomingSmsWorker.
         */
        val CREDIT_WINDOW: Duration = Duration.ofDays(1)

        /** How much earlier than Pay by UPI the SMS may be stamped (the bank's clock and the phone's differ a little). */
        private val CLOCK_SKEW: Duration = Duration.ofMinutes(1)

        /**
         * How a debit that settles Pay by UPI may be paid: by UPI, or not known (some banks' UPI SMS name no channel).
         * The settlement says UPI, so a card, ATM, wallet, cash or bank transfer payment is never taken.
         */
        private val MAY_BE_UPI = setOf(PaidWith.UPI, PaidWith.OTHER)

        private fun pendingId(groupId: String, toMemberId: String): String = "$groupId/$toMemberId"

        /** The day [sms] arrived (else the day it names) in [zone], India time in the app. */
        private fun smsDate(sms: Expense, zone: ZoneId): LocalDate = (sms.smsReceivedAt ?: sms.occurredAt).atZone(zone).toLocalDate()

        /**
         * Records that [friend] paid the person what they owe, one settlement per group, matched from the money-in SMS
         * [credit] (UPI when it came by UPI, else another way). Confirmed at once: the person received it. Each
         * settlement is dated and named after the SMS's day in [zone] (`GroupsRepository.recordSmsSettlement`), so the
         * payer's own match of the payment is the same settlement.
         *
         * @return the transfers settled.
         */
        suspend fun settleCredit(groups: GroupsRepository, friend: OwingFriend, credit: Expense, zone: ZoneId): List<OwedTransfer> {
            val method = if (credit.paidWith == PaidWith.UPI) SettleMethod.UPI else SettleMethod.OTHER
            val day = smsDate(credit, zone)
            return friend.transfers.filter { transfer ->
                try {
                    groups.recordSmsSettlement(transfer.groupId, transfer.memberId, transfer.myMemberId, transfer.amountMinor, method, day)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
        }
    }
}
