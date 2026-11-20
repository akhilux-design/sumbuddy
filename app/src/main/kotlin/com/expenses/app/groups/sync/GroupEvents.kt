package com.expenses.app.groups.sync

/**
 * Something another person did in a group that involves the person (docs/server.md 9.7), found after a pull. Only
 * names and amounts, as with payment notifications.
 */
sealed interface GroupEvent {
    /** The group it happened in. */
    val groupId: String

    /** [actorName] added [description] and the person's share is [myShareMinor]: "Rahul added Swiggy · you owe ₹150". */
    data class ExpenseAdded(
        override val groupId: String,
        val expenseId: String,
        val actorName: String,
        val description: String,
        val myShareMinor: Long,
    ) : GroupEvent

    /** [actorName] marked [amountMinor] as paid between them and the person; [canConfirm] when the person may confirm. */
    data class SettlementRecorded(
        override val groupId: String,
        val settlementId: String,
        val actorName: String,
        val amountMinor: Long,
        val canConfirm: Boolean,
    ) : GroupEvent

    /** [actorName] confirmed the settlement of [amountMinor] the person recorded. */
    data class SettlementConfirmed(
        override val groupId: String,
        val settlementId: String,
        val actorName: String,
        val amountMinor: Long,
    ) : GroupEvent

    /**
     * [memberName] (the joining account's own name) joined [groupName]: how a wrong join by a forwarded link shows.
     * [family] is true when [groupName] is a family ("Meera joined Home", docs/family.md 1.1): the tap opens Home on
     * the Family side, not a Group page.
     */
    data class MemberJoined(
        override val groupId: String,
        val memberId: String,
        val memberName: String,
        val groupName: String,
        val family: Boolean = false,
    ) : GroupEvent

    /** [actorName] reminded the person that they owe [amountMinor] in [groupName]. */
    data class Reminded(
        override val groupId: String,
        val actorName: String,
        val amountMinor: Long,
        val groupName: String,
    ) : GroupEvent
}

/** Shows [GroupEvent]s to the person. [GroupNotifications] is the real one; tests record them. */
fun interface GroupNotifier {
    /** Posts one notification per event. */
    fun post(events: List<GroupEvent>)

    /** Removes every "Groups" notification (sign out, account deletion, another account signed in). */
    fun cancelAll() = Unit

    /** Removes the bundle's summary once no notification is left under it (after a sync). */
    fun tidy() = Unit
}
