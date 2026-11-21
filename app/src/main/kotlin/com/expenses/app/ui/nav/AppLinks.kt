package com.expenses.app.ui.nav

import androidx.navigation.NavController
import com.expenses.app.ExpensesApplication
import com.expenses.app.ui.screens.home.HomeView
import kotlinx.serialization.Serializable

/**
 * A screen the app opens from outside the navigation: a notification tap or action, or a friend's pay link. Read by
 * `MainActivity` (notification links only through its non-exported `NotificationTarget` alias; pay links from the
 * App Link intent filter) and followed by [openLink] once onboarding is done.
 */
@Serializable
sealed interface AppLink {
    /**
     * A detected payment: Add to expenses, or Split it when [split] (the notification's Split button).
     *
     * @param expenseId the payment.
     * @param split true for Split.
     */
    @Serializable
    data class Payment(val expenseId: Long, val split: Boolean = false) : AppLink

    /** A group's page ("Groups" notifications). */
    @Serializable
    data class Group(val groupId: String) : AppLink

    /** Money received that may be a friend paying back: "Was this Rahul paying you back?" (03-getting-paid-back/02). */
    @Serializable
    data class MoneyReceived(val creditExpenseId: Long) : AppLink

    /** A balance that closed by itself from an SMS: "All square with Rahul" (03-getting-paid-back/03). */
    @Serializable
    data class AllSquare(val groupId: String, val memberId: String, val amountMinor: Long) : AppLink

    /** A friend's pay link opened in the app: Join group (02-everyday/32). [token] is never logged. */
    @Serializable
    data class Join(val token: String) : AppLink

    /**
     * A card bill's view (the card bill reminder, docs/card-bills.md 2.5): its tap, or "Remind friends" when [remind].
     *
     * @param billId the bill.
     * @param remind true for "Remind friends": Remind everyone runs once as the view opens.
     */
    @Serializable
    data class CardBill(val billId: Long, val remind: Boolean = false) : AppLink

    /**
     * Home with the Family side of the toggle chosen ("Meera joined Home"; docs/family.md 1.1). [openLink] sets
     * `AppContainer.homeView` to Family before it opens Home.
     */
    @Serializable
    data object Family : AppLink
}

/** The screen [link] opens. */
fun AppLink.route(): Any = when (this) {
    is AppLink.Payment -> if (split) SplitPayment(expenseId) else AddToExpenses(expenseId)
    is AppLink.Group -> GroupPage(groupId)
    is AppLink.MoneyReceived -> PaymentMatch(creditExpenseId)
    is AppLink.AllSquare -> BalanceCleared(groupId, memberId, amountMinor)
    is AppLink.Join -> JoinGroup(token)
    is AppLink.CardBill -> CardBillView(billId, remind)
    AppLink.Family -> Home
}

/**
 * Opens [link] the way a notification or a link should: on a fresh stack above Home, so Back from it goes to Home
 * whatever screen was open before (the screens above Home are saved as a tab switch saves them). Before this, a
 * notification's Split opened on top of whatever was open, and Back went there.
 */
fun NavController.openLink(link: AppLink) {
    if (link == AppLink.Family) {
        openFamilyMonth(keepScreens = true)
        return
    }
    navigate(link.route()) {
        popUpTo<Home> { saveState = true }
    }
}

/**
 * Opens Home with the Family side of the toggle chosen ([AppLink.Family], Join Family, Set up Family, Leave Family):
 * the app-scoped `AppContainer.homeView` is set to Family first, then every screen above Home is closed and Home shows.
 *
 * @param keepScreens true for "Meera joined Home" ([openLink]): nothing was finished, so the closed screens are saved
 * as a tab switch saves them and their tab shows them again. False, the default, for a family screen that finished
 * (Set up Family after Invite partner, Family settings after Leave Family, Join Family, a family's Group page): the
 * closed screens are dropped. Saved, the next tap on their tab would bring the finished screen back with its old
 * ViewModel: Set up Family still busy, so Back and Invite partner did nothing, or Family settings still "left", which
 * opened Home again on every tap of You.
 */
fun NavController.openFamilyMonth(keepScreens: Boolean = false) {
    (context.applicationContext as? ExpensesApplication)?.let { app -> runCatching { app.container.homeView.value = HomeView.Family } }
    navigate(Home) {
        popUpTo<Home> { saveState = keepScreens }
        launchSingleTop = true
    }
}

/** Reading a friend's pay link (docs/server.md 9.8). */
object PayLinks {
    private val PATH = Regex("^/pay/([A-Za-z0-9_-]{24})$")

    /**
     * The token of a pay link `https://<linkHost>/pay/<token>`, or null for anything else (another scheme or host, a
     * path that is not exactly `/pay/` and a 24 character token).
     */
    fun tokenOf(scheme: String?, host: String?, path: String?, linkHost: String): String? {
        if (scheme != "https" || host == null || !host.equals(linkHost, ignoreCase = true)) return null
        return PATH.matchEntire(path.orEmpty())?.groupValues?.get(1)
    }
}
