package com.expenses.splits

/**
 * A group expense as balances see it: who paid how much and each person's share.
 *
 * @param paidBy member id of the payer.
 * @param amountMinor the whole expense in paise.
 * @param shares member id to share in paise; adds up to [amountMinor].
 */
data class BalanceExpense(val paidBy: String, val amountMinor: Long, val shares: Map<String, Long>)

/**
 * Money paid back between two members.
 *
 * @param from member id of the person who paid.
 * @param to member id of the person who received.
 * @param amountMinor the amount in paise.
 */
data class BalanceSettlement(val from: String, val to: String, val amountMinor: Long)

/**
 * One payment that settles part of a group: [from] pays [to] [amountMinor] paise.
 */
data class Transfer(val from: String, val to: String, val amountMinor: Long)

/**
 * Balances inside one group (docs/server.md 6.2 and 6.3). Never across groups. Same integer rules as the server,
 * checked by `shared/vectors/balances.json`.
 */
object Balances {
    /**
     * Each member's net: what they paid minus their shares, plus settlements they paid minus settlements they
     * received. Positive means the group owes them. Pass only expenses and settlements that are not deleted. Every
     * member named anywhere gets an entry, 0 included; the nets add up to 0.
     */
    fun nets(expenses: Collection<BalanceExpense>, settlements: Collection<BalanceSettlement>): Map<String, Long> {
        val nets = HashMap<String, Long>()
        fun add(member: String, delta: Long) {
            nets[member] = (nets[member] ?: 0L) + delta
        }
        for (expense in expenses) {
            add(expense.paidBy, expense.amountMinor)
            for ((member, share) in expense.shares) add(member, -share)
        }
        for (settlement in settlements) {
            add(settlement.from, settlement.amountMinor)
            add(settlement.to, -settlement.amountMinor)
        }
        return nets.toSortedMap()
    }

    /**
     * The "fewest payments" that settle [nets]: repeatedly the debtor who owes the most pays the creditor who is owed
     * the most (ties: the lower member id) the smaller of the two amounts. At most n - 1 transfers. Deterministic,
     * which matters more here than being the true minimum.
     */
    fun simplify(nets: Map<String, Long>): List<Transfer> {
        val creditors = nets.filterValues { it > 0 }.toMutableMap()
        val debtors = nets.filterValues { it < 0 }.mapValues { -it.value }.toMutableMap()
        val transfers = ArrayList<Transfer>()
        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            val creditor = creditors.entries.sortedWith(largestFirst).first()
            val debtor = debtors.entries.sortedWith(largestFirst).first()
            val amount = minOf(creditor.value, debtor.value)
            transfers += Transfer(from = debtor.key, to = creditor.key, amountMinor = amount)
            settle(creditors, creditor.key, amount)
            settle(debtors, debtor.key, amount)
        }
        return transfers
    }

    private val largestFirst: Comparator<Map.Entry<String, Long>> =
        compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }

    private fun settle(amounts: MutableMap<String, Long>, member: String, paid: Long) {
        val left = amounts.getValue(member) - paid
        if (left == 0L) amounts.remove(member) else amounts[member] = left
    }
}
