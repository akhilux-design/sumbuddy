package com.expenses.app.groups.data

import com.expenses.splits.BalanceExpense
import com.expenses.splits.BalanceSettlement
import com.expenses.splits.Balances
import com.expenses.splits.Transfer

/**
 * The balances of one group, worked out on the phone with the server's own rules (`:splits`, docs/server.md 6).
 *
 * @param nets member id to net in paise (positive: the group owes them).
 * @param transfers the fewest payments that settle the group.
 */
data class GroupBalance(val nets: Map<String, Long>, val transfers: List<Transfer>) {
    /** The net of [memberId], 0 when they have none. */
    fun netOf(memberId: String): Long = nets[memberId] ?: 0L

    /**
     * Between [me] and [other]: positive when [other] owes [me], negative when [me] owes [other], 0 when no transfer
     * joins them (docs/server.md 6.4).
     */
    fun pairBalance(me: String, other: String): Long =
        transfers.sumOf {
            when {
                it.from == other && it.to == me -> it.amountMinor
                it.from == me && it.to == other -> -it.amountMinor
                else -> 0L
            }
        }

    /** Balances of a group from its stored expenses and settlements (deleted ones are skipped). */
    companion object {
        /** Computes the balances of one group. */
        fun of(expenses: Collection<GroupExpenseWithShares>, settlements: Collection<SettlementEntity>): GroupBalance {
            val nets = Balances.nets(
                expenses = expenses.filter { !it.expense.deleted }.map { stored ->
                    BalanceExpense(
                        paidBy = stored.expense.paidByMemberId,
                        amountMinor = stored.expense.amountMinor,
                        shares = stored.shares.associate { it.memberId to it.shareMinor },
                    )
                },
                settlements = settlements.filter { !it.deleted }.map { BalanceSettlement(it.fromMemberId, it.toMemberId, it.amountMinor) },
            )
            return GroupBalance(nets, Balances.simplify(nets))
        }
    }
}
