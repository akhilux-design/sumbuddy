package com.expenses.app.ui.screens.home

import androidx.compose.runtime.Immutable
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.groups.data.FamilyPersonTotal
import com.expenses.app.groups.data.FamilyRow
import com.expenses.app.groups.data.LoadState
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.format.PayeeWording
import com.expenses.app.ui.screens.activity.ActivityItem
import com.expenses.app.ui.screens.activity.ActivityItemId
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the Home tab shows. One value covers every design state: the normal tracking view, a fresh start,
 * manual mode and the offline banner on top of any of them.
 *
 * @param user the person the greeting is for.
 * @param today today's date, shown under the greeting as "Sunday, 16 Nov".
 * @param view whose spending is shown: the person's own or the family's.
 * @param month the month the spent card counts; the card reads "Spent in November".
 * @param spentMinor what the person spent this month, in paise.
 * @param offline true when the phone has no connection while the person is signed in; a banner then says that
 * splits wait until it is back (on the Family side with a family: that the family month syncs when it is back).
 * @param content what follows the spent card; see [HomeContent].
 * @param familyCard the Family card under the spent card on the Family side (02-everyday/51, 06-family/03 and 04), or
 * null for none.
 * @param familyCaption the spent card's caption on the Family side, or null on the Me side.
 */
@Immutable
data class HomeUiState(
    val user: HomeUser,
    val today: LocalDate,
    val view: HomeView,
    val month: YearMonth,
    val spentMinor: Long,
    val offline: Boolean,
    val content: HomeContent,
    val familyCard: FamilyCard? = null,
    val familyCaption: FamilyCaption? = null,
)

/** The Family card on Home's Family side (docs/family.md 1.5): a raised card with a Tag, a title, a line and a button. */
@Immutable
sealed interface FamilyCard {
    /** No family on this phone (02-everyday/51): Tag "Family", "Share this month with your partner", "Set up Family". */
    data object SetUp : FamilyCard

    /** An invite is out and nobody joined yet (06-family/03): Tag "Invite sent", "Waiting for your partner", "Send link again". */
    data object Waiting : FamilyCard

    /**
     * The partner left, was removed, or the invite was cancelled (06-family/04): Tag "Family", "Invite your partner",
     * "Home has just you now. …", "Invite partner".
     *
     * @param familyName the family's name.
     */
    data class Invite(val familyName: String) : FamilyCard
}

/** The spent card's caption on the Family side. */
@Immutable
sealed interface FamilyCaption {
    /** No family: "your Family expenses on this phone" (02-everyday/51). The label stays "Spent in November". */
    data object Local : FamilyCaption

    /**
     * A family with only the person in it: "Just you · 12 expenses". The label reads "Family spent in November".
     *
     * @param count the family month's expenses.
     */
    data class Alone(val count: Int) : FamilyCaption

    /**
     * The person and the partner: "Arjun and Meera · 64 expenses". The label reads "Family spent in November".
     *
     * @param me the person's first name.
     * @param partner the partner's first name.
     * @param count the family month's expenses.
     */
    data class Together(val me: String, val partner: String, val count: Int) : FamilyCaption
}

/**
 * The person using the app, as the greeting shows them.
 *
 * @param firstName the name used in "Hey <name>" and for the avatar's letter, or null when the person gave none;
 * the greeting then reads "Hey there" without an avatar.
 * @param avatarColor colour of the avatar circle.
 */
@Immutable
data class HomeUser(
    val firstName: String?,
    val avatarColor: AvatarColor,
)

/** Whose spending Home shows. It is chosen with the Me or Family toggle. */
enum class HomeView {
    /** The person's own month: their spending plus their share of splits. */
    Me,

    /** The family's pooled month. */
    Family,
}

/** The part of Home under the spent card. It differs by how the person tracks expenses. */
sealed interface HomeContent {
    /**
     * Expenses are read from SMS: a payment waiting for a decision, what is owed either way, and the latest
     * expenses.
     *
     * @param detected the oldest payment found in SMS that still needs Add, Split or Ignore, or null when none waits.
     * @param splitTotals "Owed to you" and "You owe", or null when the person splits with nobody (signed out, or in
     * no group or direct split); the tiles are then left out.
     * @param latest the most recent expenses, newest first.
     */
    @Immutable
    data class Tracking(
        val detected: DetectedPayment?,
        val splitTotals: SplitTotals?,
        val latest: List<ActivityItem>,
    ) : HomeContent

    /** The person chose to start fresh and nothing has been spent yet: an empty state invites the first expense. */
    data object StartFresh : HomeContent

    /**
     * The person did not allow SMS access and adds expenses by hand. The split tiles are left out even for someone who
     * splits, as 02 draws them; their splits show in Latest and on the Groups tab.
     *
     * @param showTrackingTip true while the tip that offers automatic tracking is shown; false once dismissed.
     * @param latest the most recent expenses, newest first.
     */
    @Immutable
    data class Manual(
        val showTrackingTip: Boolean,
        val latest: List<ActivityItem>,
    ) : HomeContent

    /**
     * The Family side with a family on this phone (06-family/02 to 07): By person, Top categories and Latest of the
     * family month. No payment card and no split tiles.
     *
     * @param load [LoadState.LOADING] until the first pull after Join Family or a sign-in ended: the placeholder shapes
     * of 06-family/06 show instead of the card and the sections.
     * @param people the person first, then the partner on the app, with ₹0 rows when they paid nothing.
     * @param topCategories the two largest categories, largest first; empty hides Top categories.
     * @param latest the newest five family expenses; empty shows "Nothing in the family month yet".
     */
    @Immutable
    data class FamilyMonth(
        val load: LoadState,
        val people: List<FamilyPersonTotal>,
        val topCategories: List<CategoryTotal>,
        val latest: List<FamilyRow>,
    ) : HomeContent {
        /** True while the family month loads for the first time. */
        val loading: Boolean get() = load == LoadState.LOADING
    }
}

/**
 * The two split tiles: what others owe the person and what the person owes, over every group and direct split
 * (docs/server.md 6.4).
 *
 * @param owedToYouMinor the sum of the person's positive nets, in paise.
 * @param youOweMinor the sum of the person's negative nets, as a positive amount in paise.
 */
@Immutable
data class SplitTotals(val owedToYouMinor: Long, val youOweMinor: Long)

/**
 * A payment read from a bank SMS that waits for the person to add, split or ignore it.
 *
 * @param id the expense id of the payment, sent back with the person's choice.
 * @param merchant who was paid, for example "Swiggy", or null when the SMS names nobody.
 * @param payeeWording how the amount and [merchant] read together ("₹450 at Swiggy", "₹350 to Ramesh").
 * @param amountMinor the amount paid, in paise.
 * @param accountLast4 the last four digits of the card or account the SMS names, or null when it names none.
 * @param seenAt when the payment was seen, for example "now".
 * @param lastSplitWith the group or person this merchant was split with last time, or null when it never was.
 * @param othersWaiting how many more payments wait after this one. When there are any, the card offers "Review all"
 * with every waiting payment, this one included.
 */
@Immutable
data class DetectedPayment(
    val id: Long,
    val merchant: String?,
    val payeeWording: PayeeWording = PayeeWording.AT,
    val amountMinor: Long,
    val accountLast4: String?,
    val seenAt: SeenAt,
    val lastSplitWith: String?,
    val othersWaiting: Int,
)

/** How long ago a payment was seen, as the payment card writes it. */
@Immutable
sealed interface SeenAt {
    /** Less than a minute ago: "now". */
    data object Now : SeenAt

    /**
     * Less than an hour ago: "5 min ago".
     *
     * @param minutes whole minutes, 1 to 59.
     */
    data class MinutesAgo(val minutes: Int) : SeenAt

    /**
     * Earlier today: "3 h ago".
     *
     * @param hours whole hours, 1 or more.
     */
    data class HoursAgo(val hours: Int) : SeenAt

    /**
     * Before today: "14 Nov".
     *
     * @param date the day it was seen.
     */
    data class OnDate(val date: LocalDate) : SeenAt
}

/** Everything the person can tap on Home. */
sealed interface HomeEvent {
    /** The Me or Family toggle was switched to [view]. */
    data class ViewChanged(val view: HomeView) : HomeEvent

    /** The spent card was tapped: open Month view, or Month view (Family) on the Family side with a family. */
    data object SpentClicked : HomeEvent

    /** "Add" on the detected payment [paymentId]: count it as the person's own expense. */
    data class AddDetectedPayment(val paymentId: Long) : HomeEvent

    /** "Split" on the detected payment [paymentId]: share it with others. */
    data class SplitDetectedPayment(val paymentId: Long) : HomeEvent

    /** "Ignore" on the detected payment [paymentId]: do not count it. */
    data class IgnoreDetectedPayment(val paymentId: Long) : HomeEvent

    /** "Review all N" on the payment card: open Review payments with every waiting payment. */
    data object ReviewAll : HomeEvent

    /** One of the add buttons ("+ Add", "Add one by hand", "Add expense"): add an expense by hand. */
    data object AddExpense : HomeEvent

    /** The expense row [id] was tapped: open its details. */
    data class ExpenseTapped(val id: ActivityItemId) : HomeEvent

    /** "Turn on" on the tip: start automatic tracking, which asks for SMS access. */
    data object TurnOnTracking : HomeEvent

    /** "Not now" on the tip: hide the tip. */
    data object DismissTrackingTip : HomeEvent

    /** "Owed to you" or "You owe": open the Groups tab. */
    data object SplitTotalsClicked : HomeEvent

    /** "Set up Family" on the Family card: open Set up Family (Sign in first when signed out). */
    data object SetUpFamily : HomeEvent

    /** "Send link again" or "Invite partner" on the Family card: open Family settings, which shares the link at once. */
    data object SendFamilyLink : HomeEvent

    /** One of the person's own family rows: open Expense detail of [expenseId]. The partner's rows are not tappable. */
    data class FamilyRowTapped(val expenseId: Long) : HomeEvent
}
