package com.expenses.app.groups.match

import com.expenses.app.groups.data.GroupDetail
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.personKeyOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * What one friend owes the person in one group: the transfer between them there (docs/server.md 6.4).
 *
 * @param groupId the group.
 * @param groupKind a named group or a direct split.
 * @param groupName the group's name; for a direct split, the friend's name.
 * @param myMemberId the person's member in that group (who receives).
 * @param memberId the friend's member in that group (who pays).
 * @param name the friend's name in that group.
 * @param personKey the friend across groups (`personKeyOf`, the same key as `FriendBalance.key`).
 * @param upiId the friend's UPI ID, or null.
 * @param amountMinor what they owe there, in paise; always above 0.
 */
data class OwedTransfer(
    val groupId: String,
    val groupKind: GroupKind,
    val groupName: String,
    val myMemberId: String,
    val memberId: String,
    val name: String,
    val personKey: String,
    val upiId: String?,
    val amountMinor: Long,
)

/**
 * A friend who owes the person, with what they owe in each group.
 *
 * @param key the friend across groups (see [OwedTransfer.personKey]).
 * @param name the friend's name.
 * @param transfers what they owe, one per group, largest first.
 */
data class OwingFriend(val key: String, val name: String, val transfers: List<OwedTransfer>) {
    /** Everything they owe the person, over every group listed. */
    val totalMinor: Long get() = transfers.sumOf { it.amountMinor }

    /** The group with the largest transfer: the one an "All square" link names. */
    val largest: OwedTransfer get() = transfers.first()
}

/** What a money-received SMS is to the person's balances (docs/server.md 9.5; design 03-getting-paid-back/02, 03). */
sealed interface CreditMatch {
    /** No friend owes exactly that amount. */
    data object None : CreditMatch

    /** One friend owes exactly that amount and the SMS names them: the balance clears at once ("All square"). */
    data class Clear(val friend: OwingFriend) : CreditMatch

    /** A friend owes exactly that amount but the name on the SMS was unclear: "Was this Rahul paying you back?". */
    data class Ask(val friend: OwingFriend) : CreditMatch
}

/**
 * Matching money received to what friends owe, on this phone only. Nothing here reads or keeps SMS text: only the
 * amount, the counterparty name and UPI ID the parser already took from it.
 */
object CreditMatching {
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private const val MIN_WORD = 2

    /**
     * The friends who owe the person, from [groups]: every member other than the person whose transfer to the
     * person is above 0, put together across groups by [OwedTransfer.personKey]. Groups whose data is still loading
     * are left out, as their balances are not known yet, and so is a family (nobody owes anybody there,
     * docs/family.md 8.2). Sorted by name, then key, so the result is the same each time.
     */
    fun owingFriends(groups: List<GroupDetail>): List<OwingFriend> {
        val transfers = groups.filter { it.load != LoadState.LOADING && it.group.kind != GroupKind.FAMILY }.flatMap { detail ->
            detail.members.filter { !it.isMe && it.pairMinor > 0 }.map { balance ->
                val member = balance.member
                OwedTransfer(
                    groupId = detail.group.id,
                    groupKind = detail.group.kind,
                    groupName = detail.name,
                    myMemberId = detail.group.myMemberId,
                    memberId = member.id,
                    name = member.name,
                    personKey = personKeyOf(member),
                    upiId = member.upiId,
                    amountMinor = balance.pairMinor,
                )
            }
        }
        return transfers.groupBy { it.personKey }
            .map { (key, list) ->
                val sorted = list.sortedWith(compareByDescending<OwedTransfer> { it.amountMinor }.thenBy { it.groupId })
                OwingFriend(key, sorted.first().name, sorted)
            }
            .sortedWith(compareBy<OwingFriend> { it.name.lowercase() }.thenBy { it.key })
    }

    /**
     * Whether money in of [amountMinor] from [counterparty] (the name the SMS prints) or [counterpartyVpa] (the UPI ID
     * it prints) is one of [friends] paying back.
     *
     * A friend is a candidate when [amountMinor] is exactly everything they owe the person, or exactly what they owe
     * in one group (and in no other group the same amount); in the second case only that group clears. One candidate
     * whose name matches the SMS clears at once ([CreditMatch.Clear]); otherwise the first candidate, preferring one
     * whose name matches, is asked about ([CreditMatch.Ask]).
     */
    fun match(amountMinor: Long, counterparty: String?, counterpartyVpa: String?, friends: List<OwingFriend>): CreditMatch {
        if (amountMinor <= 0) return CreditMatch.None
        val candidates = friends.mapNotNull { friend ->
            val single = friend.transfers.filter { it.amountMinor == amountMinor }
            when {
                friend.totalMinor == amountMinor -> friend
                single.size == 1 -> friend.copy(transfers = single)
                else -> null
            }
        }
        if (candidates.isEmpty()) return CreditMatch.None
        val named = candidates.filter { nameMatches(it, counterparty, counterpartyVpa) }
        return if (named.size == 1) CreditMatch.Clear(named.single()) else CreditMatch.Ask(named.ifEmpty { candidates }.first())
    }

    /**
     * Whether the SMS names [friend]: the UPI ID it prints is the friend's, or the friend's first name is one of the
     * words of the name it prints (or of the part of its UPI ID before the `@`), ignoring case. Only the first name
     * counts, so "Suresh Kumar" paying does not clear what "Rahul Kumar" owes.
     */
    fun nameMatches(friend: OwingFriend, counterparty: String?, counterpartyVpa: String?): Boolean {
        val vpa = counterpartyVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (vpa != null && friend.transfers.any { it.upiId?.trim()?.lowercase() == vpa }) return true
        val smsWords = words(counterparty) + words(vpa?.substringBefore('@'))
        if (smsWords.isEmpty()) return false
        val names = friend.transfers.map { it.name }.distinct()
        return names.any { name -> words(name).firstOrNull()?.let { it in smsWords } == true }
    }

    private fun words(text: String?): Set<String> =
        text.orEmpty().lowercase().split(WORD_SPLIT).filter { it.length >= MIN_WORD }.toSet()
}

/**
 * Every group and direct split on this phone with its balances, emitted again on every change: named groups, plus
 * the direct splits that have expenses or a balance with someone. For matching money received and for All square.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun GroupsRepository.observeGroupDetails(): Flow<List<GroupDetail>> =
    combine(observeGroups(), observeSplitLabels(), observeFriends()) { named, labels, friends ->
        (named.map { it.id } + labels.values.map { it.groupId } + friends.mapNotNull { it.directGroupId }).distinct().sorted()
    }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map(::observeGroup)) { details -> details.filterNotNull() }
        }
