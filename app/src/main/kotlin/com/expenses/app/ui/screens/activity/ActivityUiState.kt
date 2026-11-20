package com.expenses.app.ui.screens.activity

import androidx.compose.runtime.Immutable
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.icons.AppIcon
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the Activity tab shows: the two numbers for the month at the top and, under them, either the list of
 * payments grouped by day or the empty state.
 *
 * @param month the month the numbers are for; the tiles read "Spent in Nov".
 * @param spentPaise what the user spent this month, in paise. Only the user's own share of a split counts.
 * @param fromSplitsPaise the part of [spentPaise] that came from split payments, in paise: the person's share of the
 * month's payments linked to a group expense.
 * @param content the list of payments, or [ActivityContent.Empty] when there is nothing to list yet.
 */
@Immutable
data class ActivityUiState(
    val month: YearMonth,
    val spentPaise: Long,
    val fromSplitsPaise: Long,
    val content: ActivityContent,
)

/** What is shown under the month numbers. */
@Immutable
sealed interface ActivityContent {
    /** Nothing was added or split yet: no filters, only a note and a button to add a payment by hand. */
    data object Empty : ActivityContent

    /**
     * Payments grouped by day, newest day first, under a row of filters.
     *
     * @param selectedFilter the filter that is switched on; the list holds only payments that match it.
     * @param days the days to show, newest first, each with its payments newest first. Empty when nothing matches
     * the filter; a short note then says so.
     */
    @Immutable
    data class Days(
        val selectedFilter: ActivityFilter,
        val days: List<ActivityDay>,
    ) : ActivityContent
}

/** The filters above the list, in the order they are shown. */
enum class ActivityFilter {
    /** Every payment. */
    All,

    /** Only the user's own payments. */
    Me,

    /** Only payments in the Family group. */
    Family,

    /** Only payments split with other people: the rows shown as [ActivityItemDetail.Split]. */
    Splits,

    /** Only payments the user chose to ignore. */
    Ignored,
}

/**
 * One day of payments.
 *
 * @param date which day it is; it gives the heading above the rows.
 * @param items the payments of that day, newest first.
 */
@Immutable
data class ActivityDay(
    val date: ActivityDate,
    val items: List<ActivityItem>,
)

/** The heading of a day in the list. */
@Immutable
sealed interface ActivityDate {
    /** The current day, shown as "Today". */
    data object Today : ActivityDate

    /** The day before, shown as "Yesterday". */
    data object Yesterday : ActivityDate

    /**
     * Any earlier day, shown as "12 Nov", or "12 Nov 2025" when [showYear] is true.
     *
     * @param date the day.
     * @param showYear true when the day is in another year than today.
     */
    data class Earlier(val date: LocalDate, val showYear: Boolean) : ActivityDate
}

/** What a row in the list stands for, sent back when it is tapped. */
@Immutable
sealed interface ActivityItemId {
    /**
     * A stored expense.
     *
     * @param expenseId the expense's id.
     */
    data class Expense(val expenseId: Long) : ActivityItemId

    /**
     * A message waiting in Needs review.
     *
     * @param reviewId the review item's id.
     */
    data class Review(val reviewId: Long) : ActivityItemId
}

/**
 * One payment in the list.
 *
 * @param id what the row stands for; it keeps the row in place while the list changes and is sent back on tap.
 * @param title the merchant or payee, for example "Swiggy", or what the SMS called it when it could not be read.
 * Null when the payment names nobody; the row then says "Payment" or "Money in".
 * @param icon the 3D icon of the payment's category.
 * @param amountPaise the amount that counts for the user, in paise: their own share for a split. Null when the
 * amount could not be read.
 * @param detail what sort of payment it is and the facts the row shows about it; see [ActivityItemDetail].
 */
@Immutable
data class ActivityItem(
    val id: ActivityItemId,
    val title: String?,
    val icon: AppIcon,
    val amountPaise: Long?,
    val detail: ActivityItemDetail,
)

/** How a payment was made, as the second line of a row names it. */
@Immutable
sealed interface PaidWithLabel {
    /**
     * A way of paying, for example "UPI" or "Cash".
     *
     * @param method the way of paying.
     */
    data class Method(val method: PaidWith) : PaidWithLabel

    /**
     * An account or card, for example "HDFC ••4521".
     *
     * @param name the account's name as the person sees it.
     * @param last4 its last digits, or null when unknown.
     */
    data class Account(val name: String, val last4: String?) : PaidWithLabel
}

/** What sort of payment an [ActivityItem] is. It decides the second line and the note under the amount. */
@Immutable
sealed interface ActivityItemDetail {
    /**
     * A payment that is only the user's own. The note reads "Just you".
     *
     * @param category the payment's category.
     * @param paidWith how it was paid.
     */
    data class Personal(val category: Category, val paidWith: PaidWithLabel) : ActivityItemDetail

    /**
     * A payment split with other people. The second line gives the whole bill; the note is the group name.
     *
     * @param category the payment's category.
     * @param totalPaise the whole bill before splitting, in paise.
     * @param groupName the group it was split in, for example "Flat 302".
     */
    data class Split(val category: Category, val totalPaise: Long, val groupName: String) : ActivityItemDetail

    /**
     * A payment in a pooled group such as Family, where nobody owes anybody. The note is the group name.
     *
     * @param category the payment's category.
     * @param paidWith how it was paid.
     * @param groupName the pooled group, for example "Family"; null for the person's own family, shown as "Family".
     */
    data class Pooled(val category: Category, val paidWith: PaidWithLabel, val groupName: String?) :
        ActivityItemDetail

    /**
     * Money that came in: a refund, cashback or money received. It is listed but never counts as spending; the
     * amount is shown with a plus sign and the note says so.
     *
     * @param paidWith the account or way it came in.
     */
    data class MoneyIn(val paidWith: PaidWithLabel) : ActivityItemDetail

    /**
     * A payment read from SMS that the user ignored. It is listed only under the Ignored filter, never counts, and
     * the note says "Ignored".
     *
     * @param category the payment's category.
     * @param paidWith how it was paid.
     */
    data class Ignored(val category: Category, val paidWith: PaidWithLabel) : ActivityItemDetail

    /** A payment the app could not read fully. The row is outlined and asks the user to check it. */
    data object NeedsReview : ActivityItemDetail
}

/** Everything the user can tap on the Activity tab. */
sealed interface ActivityEvent {
    /** The "+ Add" button next to the title. */
    data object AddClicked : ActivityEvent

    /** The "Add one by hand" button in the empty state. */
    data object AddByHandClicked : ActivityEvent

    /** One of the two month tiles: open Month view for this month. */
    data object MonthClicked : ActivityEvent

    /**
     * A filter chip.
     *
     * @param filter the filter that was tapped.
     */
    data class FilterSelected(val filter: ActivityFilter) : ActivityEvent

    /**
     * A payment row.
     *
     * @param id the [ActivityItem.id] of the row that was tapped.
     */
    data class ItemClicked(val id: ActivityItemId) : ActivityEvent
}
