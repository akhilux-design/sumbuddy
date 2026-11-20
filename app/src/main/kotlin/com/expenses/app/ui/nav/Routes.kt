package com.expenses.app.ui.nav

import com.expenses.app.ui.components.MainTab
import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Type-safe navigation routes. Each is registered by exactly one feature file (see docs/architecture.md).

/** The onboarding graph. Its start is [Welcome]. Removed from the back stack when onboarding finishes. */
@Serializable
data object OnboardingGraph

/** Welcome (design 01-onboarding/00-welcome). */
@Serializable
data object Welcome

/**
 * Sign in (design 01-onboarding/01-sign-in and 02-sign-in-could-not-sign-in). Asked only when splitting first needs
 * it (Groups, Split on a payment, You), or from Welcome's "I already have an account". Owned by the sign-in feature.
 *
 * @param fromWelcome true from Welcome: once signed in, onboarding goes on at Privacy promise. Otherwise Sign in
 * returns to the screen that opened it and sets [SIGNED_IN_KEY] in that screen's saved state.
 * @param forJoin true when a friend's link opened the app signed out: the screen reads "Join a group" (design
 * 02-everyday/49-join-group-sign-in-first), and Join group opens again after sign-in.
 * @param forFamily true from Set up Family or Family settings (the You row, Home's Set up card): the screen reads
 * "Share a family month" (design 06-family/08-sign-in-family). A family invite link opened signed out keeps [forJoin]:
 * its kind is not known before sign-in (docs/family.md 1.1).
 */
@Serializable
data class SignIn(val fromWelcome: Boolean = false, val forJoin: Boolean = false, val forFamily: Boolean = false)

/**
 * Your name (after sign-in) (design 01-onboarding/03-your-name-after-sign-in), after the first sign-in of a new
 * account.
 *
 * @param fromWelcome as in [SignIn].
 */
@Serializable
data class SignInName(val fromWelcome: Boolean = false)

/** The saved state key under which Sign in tells the screen that opened it that the person is now signed in. */
const val SIGNED_IN_KEY: String = "signed_in"

/** Your name (design 01-onboarding/04-your-name). */
@Serializable
data object YourName

/** Privacy promise (design 01-onboarding/05-privacy-promise), shown before the system SMS prompt. */
@Serializable
data object PrivacyPromise

/** The step that asks for SMS access with the real system dialog. */
@Serializable
data object SmsPermission

/** Allow notifications (design 01-onboarding/07-allow-notifications); skipped below Android 13. */
@Serializable
data object NotificationPermission

/** How far back (design 01-onboarding/08-how-far-back). */
@Serializable
data object HowFarBack

/** Scanning SMS (design 01-onboarding/09-scanning-sms). */
@Serializable
data object Scanning

/** Nothing found (design 01-onboarding/10-nothing-found). */
@Serializable
data object NothingFound

/** Confirm accounts (design 01-onboarding/11-confirm-accounts). */
@Serializable
data object ConfirmAccounts

/** The graph of the four main tabs. Its start is [Home]. */
@Serializable
data object MainGraph

/** The Home tab (design 02-everyday/00-home and its states). */
@Serializable
data object Home

/** The Groups tab (design 02-everyday/20-groups). */
@Serializable
data object Groups

/** The Activity tab (design 02-everyday/16-activity). */
@Serializable
data object Activity

/** The You tab (design 04-you/00-you). */
@Serializable
data object You

/**
 * Add to expenses for a detected payment (design 02-everyday/05-add-to-expenses). Also opened from the payment
 * detected notification.
 *
 * @param expenseId id of the DETECTED expense.
 * @param split true when the person tapped Split: the note that splitting comes with sign-in shows at once.
 */
@Serializable
data class AddToExpenses(val expenseId: Long, val split: Boolean = false)

/**
 * Choose category (design 02-everyday/06-choose-category).
 *
 * @param expenseId id of the expense whose category is chosen.
 * @param draft the category picked so far in an unsaved edit (a [com.expenses.app.data.category.Category] name), or
 * null. With a draft the choice is not stored: it goes back to the screen below under [CHOSEN_CATEGORY_KEY], so
 * Cancel there can still undo it.
 */
@Serializable
data class ChooseCategory(val expenseId: Long, val draft: String? = null)

/** The saved state key under which Choose category hands a draft choice back to the screen below it. */
const val CHOSEN_CATEGORY_KEY: String = "chosen_category"

/**
 * Expense added (design 02-everyday/07-expense-added).
 *
 * @param expenseId id of the expense just added.
 */
@Serializable
data class ExpenseAdded(val expenseId: Long)

/**
 * Expense detail (design 02-everyday/08-expense-detail).
 *
 * @param expenseId id of the expense shown.
 */
@Serializable
data class ExpenseDetail(val expenseId: Long)

/**
 * Add expense by hand (design 02-everyday/15-add-expense): amount, what for, who paid, paid with, and "Split this".
 *
 * @param groupId the group to split it with, when opened from a Group page's "Add" ("Split this" starts on, with that
 * group); null from Home and Activity.
 */
@Serializable
data class AddExpense(val groupId: String? = null)

/**
 * Review payments (design 02-everyday/34 to 38): the payments waiting for a decision as a stack of cards, swiped
 * right to add and left to ignore, with Add all. Opened from "Review all" on Home's payment card.
 */
@Serializable
data object ReviewPayments

/** Needs review (design 02-everyday/19-needs-review). */
@Serializable
data object NeedsReview

/**
 * Month view (design 02-everyday/18-month-view).
 *
 * @param yearMonth the month as ISO text, for example "2026-11"; read it with [month].
 */
@Serializable
data class MonthView(val yearMonth: String) {
    /** The month shown. */
    val month: YearMonth get() = YearMonth.parse(yearMonth)

    /** Making the route from a month. */
    companion object {
        /** The route for [month]. */
        fun of(month: YearMonth): MonthView = MonthView(month.toString())
    }
}

/** Accounts and cards (design 04-you/01-accounts-and-cards). */
@Serializable
data object AccountsAndCards

/** What we've read (design 04-you/04-what-we-ve-read). */
@Serializable
data object WhatWeHaveRead

/** Tracking start date (design 04-you/05-tracking-start-date). */
@Serializable
data object TrackingStartDate

/**
 * Privacy promise (design 01-onboarding/05-privacy-promise) shown again outside onboarding, right before Android's
 * SMS dialog, when the person turns SMS tracking on from the Home tip or the switch on You.
 */
@Serializable
data object TurnOnSmsTracking

// ---- Groups and splitting (docs/groups-build-plan.md). Ids are the server's lowercase UUID strings. ----

/**
 * A group's page (design 02-everyday/24-group-page; 23-could-not-load, 40-group-page-someone-joined, 41-join-undone,
 * 42-you-owe, 43-change-not-saved). Also a direct split with one friend. Owned by the group-page area.
 *
 * @param groupId the group.
 */
@Serializable
data class GroupPage(val groupId: String)

/**
 * Group settings (design 02-everyday/25-group-settings, 45-not-owner, 46-friend-s-link). Owned by the group-page area.
 *
 * @param groupId the group.
 */
@Serializable
data class GroupSettings(val groupId: String)

/**
 * New group (design 02-everyday/26-new-group). Owned by the groups-tab area. When made, the new group's id is set
 * under [CREATED_GROUP_KEY] in the previous screen's saved state (Split it, Choose people "Make a group") and New
 * group leaves; opened from the Groups tab, it opens the new group's page instead.
 *
 * @param people the people to fill in, as [com.expenses.app.groups.data.Person.key]s joined with commas (Choose
 * people "Make a group", 02-everyday/48); empty for none. Read with [peopleKeys].
 * @param friends friends to fill in who are in no group yet (added on Choose people with "Add someone", so New group
 * cannot find them by key), as a JSON list of [AddedFriend]; empty for none. Read with [newFriends].
 */
@Serializable
data class NewGroup(val people: String = "", val friends: String = "") {
    /** The keys of the people to fill in. */
    val peopleKeys: List<String> get() = people.split(',').filter { it.isNotBlank() }

    /** The friends in no group yet to fill in, switched on as if they came back from Add friend. */
    val newFriends: List<AddedFriend>
        get() = if (friends.isBlank()) emptyList() else runCatching { RouteJson.decodeFromString(FRIENDS, friends) }.getOrDefault(emptyList())

    /** Making the route from a list of people. */
    companion object {
        private val FRIENDS = ListSerializer(AddedFriend.serializer())
        private val RouteJson = Json { ignoreUnknownKeys = true }

        /** New group with [keys] and the friends in no group yet [friends] filled in. */
        fun with(keys: List<String>, friends: List<AddedFriend> = emptyList()): NewGroup =
            NewGroup(keys.joinToString(","), if (friends.isEmpty()) "" else RouteJson.encodeToString(FRIENDS, friends))
    }
}

/** The saved state key under which New group hands the id of the group it made back to the screen below it. */
const val CREATED_GROUP_KEY: String = "created_group"

/**
 * Add friend (design 02-everyday/27-add-friend): a name and an optional phone number, or one picked from contacts.
 * Owned by the groups-tab area.
 *
 * @param groupId the group to add the friend to at once (Group settings "Add someone"); null from New group, where
 * the friend is handed back under [ADDED_FRIEND_KEY] (an [AddedFriend] as JSON) and added when the group is made.
 */
@Serializable
data class AddFriend(val groupId: String? = null)

/** The saved state key under which Add friend hands the new friend back to New group. */
const val ADDED_FRIEND_KEY: String = "added_friend"

/**
 * The friend Add friend hands back to New group under [ADDED_FRIEND_KEY], encoded as JSON.
 *
 * @param name the name typed or picked.
 * @param phone the phone number as typed or picked, or null.
 */
@Serializable
data class AddedFriend(val name: String, val phone: String? = null)

/**
 * Join group from a friend's link (design 02-everyday/32-join-group; 50-join-group-link-no-longer-works). Signed out,
 * Sign in opens first ([SignIn] with `forJoin`, 02-everyday/49). Opened by the App Link `https://<LINK_HOST>/pay/<token>`.
 * Owned by the you-and-join area.
 *
 * @param token the link's 24 character token. Never logged.
 */
@Serializable
data class JoinGroup(val token: String)

/**
 * Split it for a payment of the person's own (design 02-everyday/09-split-details, 10, 11): from Split on Home, the
 * notification, Review payments, Add to expenses "Split it instead" and Expense detail "Split this expense". Signed
 * out, Sign in opens first and Not now leaves the payment waiting (see `SignInGate`). Owned by the split-flow area.
 *
 * @param expenseId the personal payment (a DETECTED debit, or an ADDED one not yet split).
 */
@Serializable
data class SplitPayment(val expenseId: Long)

/**
 * Split it for an expense typed in on Add expense with "Split this" on (design 02-everyday/15-add-expense, "Next:
 * choose split"); it may be paid by someone else. Owned by the split-flow area.
 *
 * @param draft the typed expense, in a JSON form the split-flow area defines (amount, what for, category, date,
 * who paid, paid with).
 */
@Serializable
data class SplitTyped(val draft: String)

/**
 * Choose people (design 02-everyday/12-choose-people, 48-choose-people-several-friends): one group or one friend for
 * a split. The choice goes back under [CHOSEN_SPLIT_GROUP_KEY] (a group id; a friend's direct split is found or made
 * first). Owned by the split-flow area.
 *
 * @param selectedGroupId the group chosen so far, or null.
 */
@Serializable
data class ChoosePeople(val selectedGroupId: String? = null)

/** The saved state key under which Choose people hands the chosen group id back to Split it. */
const val CHOSEN_SPLIT_GROUP_KEY: String = "chosen_split_group"

/**
 * Split. Sorted. (design 02-everyday/13-split-added), with Undo. Owned by the split-flow area.
 *
 * @param groupExpenseId the group expense just made.
 * @param priorStatus the name of the `ExpenseStatus` the payment had before it was split, or null for a typed expense.
 * Kept in the route so Undo still puts the payment back after Android stopped the app in the background.
 */
@Serializable
data class SplitAdded(val groupExpenseId: String, val priorStatus: String? = null)

/**
 * Same split? (design 02-everyday/14-recurring-split-offer): a payment to a merchant split the same way before.
 * Owned by the split-flow area.
 *
 * @param expenseId the new payment.
 */
@Serializable
data class RecurringSplitOffer(val expenseId: Long)

/**
 * Settle up with one person in one group (design 03-getting-paid-back/00-settle-up, 07-settle-up-no-upi-id): what the
 * person owes them, Pay by UPI or Paid another way. Owned by the settle-up area.
 *
 * @param groupId the group.
 * @param memberId the other person.
 */
@Serializable
data class SettleUp(val groupId: String, val memberId: String)

/**
 * Mark as paid (design 03-getting-paid-back/01-mark-as-paid): the other person paid the person in cash or another way.
 * Owned by the settle-up area.
 *
 * @param groupId the group.
 * @param memberId who paid.
 */
@Serializable
data class MarkAsPaid(val groupId: String, val memberId: String)

/**
 * Money received (design 03-getting-paid-back/02-payment-match): "Was this Rahul paying you back?". Opened from the
 * notification of an unclear match. Owned by the settle-up area.
 *
 * @param creditExpenseId the money-in row read from SMS.
 */
@Serializable
data class PaymentMatch(val creditExpenseId: Long)

/**
 * All square (design 03-getting-paid-back/03-balance-cleared): a balance closed by itself from an SMS. Owned by the
 * settle-up area.
 *
 * @param groupId the group.
 * @param memberId the friend who paid.
 * @param amountMinor what arrived, in paise.
 */
@Serializable
data class BalanceCleared(val groupId: String, val memberId: String, val amountMinor: Long)

/**
 * Remind on WhatsApp (design 03-getting-paid-back/05-remind-on-whatsapp): the message and the friend's pay link, sent
 * from the person's own WhatsApp. Owned by the settle-up area.
 *
 * @param groupId the group.
 * @param memberId the friend who owes.
 */
@Serializable
data class RemindOnWhatsApp(val groupId: String, val memberId: String)

/**
 * Import from Splitwise (design 02-everyday/28 to 30, 133 to 140; docs/splitwise-import.md 5.6): pick a group's export
 * file, say which column is you, and bring the group across. From New group opened on the Groups tab, and You > More.
 * Signed in only (`SignInGate`). Owned by the splitwise area (`ui/screens/splitwise/SplitwiseNav.kt`).
 */
@Serializable
data object ImportSplitwise

/**
 * Group imported (design 02-everyday/31, 141), right after an import finished: one message with every friend's link.
 * Owned by the splitwise area.
 *
 * @param groupId the imported group.
 */
@Serializable
data class GroupImported(val groupId: String)

/**
 * Account (design 04-you/02-account; 03-delete-account-confirm, 07-account-upi-id-not-valid,
 * 08-sign-out-changes-not-sent): name, phone, UPI ID, Sign out, Delete account. Owned by the you-and-join area.
 */
@Serializable
data object YouAccount

// ---- Family (docs/family.md 8.3). Owned by the family area (`ui/screens/family/FamilyNav.kt`). ----

/**
 * Set up Family (design 06-family/00, 09 to 12): the family's name, what is shared, and "Invite partner", which sets
 * the family up, makes the partner's link and opens the share sheet. From Home's Set up Family card and the You row.
 * Signed in only (`SignInGate` with `SignIn(forFamily = true)`).
 */
@Serializable
data object SetUpFamily

/**
 * Family settings (design 06-family/17 to 24): the family's name, the two people, the invite sheet, and Leave Family.
 * From the You row and Home's family card. Signed in only (`SignInGate` with `SignIn(forFamily = true)`).
 *
 * @param sendLink true from Home's "Send link again" and "Invite partner": the link is made and the share sheet
 * opens as soon as the screen does ("Invite partner" when there is no invite out).
 */
@Serializable
data class FamilySettings(val sendLink: Boolean = false)

/**
 * Month view (Family) (design 06-family/27): every family expense of a month by day, by person and by category. From
 * the Balance Card on Home's Family side with a family.
 *
 * @param yearMonth the month as ISO text, for example "2026-11"; read it with [month].
 */
@Serializable
data class FamilyMonthView(val yearMonth: String) {
    /** The month shown. */
    val month: YearMonth get() = YearMonth.parse(yearMonth)

    /** Making the route from a month. */
    companion object {
        /** The route for [month]. */
        fun of(month: YearMonth): FamilyMonthView = FamilyMonthView(month.toString())
    }
}

/** The route of each main tab. */
val MainTab.route: Any
    get() = when (this) {
        MainTab.Home -> Home
        MainTab.Groups -> Groups
        MainTab.Activity -> Activity
        MainTab.You -> You
    }
