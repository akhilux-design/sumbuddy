package com.expenses.app.ui.screens.settle

import com.expenses.app.BuildConfig
import com.expenses.app.ui.screens.groups.SampleAvatars

/**
 * The states of the settle-up screens with the design's own data (Rahul, Priya, Arjun, Flat 302, ₹1,200, ₹1,150),
 * for the debug previews and tests.
 */
object SettleSamples {
    /** 00-settle-up: Pay by UPI picked. */
    val settleUp: SettleUpUiState.Shown = SettleUpUiState.Shown(
        name = "Rahul",
        amountMinor = 120_000,
        expenseCount = 4,
        groupName = "Flat 302",
        upiId = "rahul@okhdfc",
        friendOnApp = true,
        choice = SettleChoice.Upi,
        avatar = SampleAvatars.rahul,
    )

    /** 00 with Paid another way picked (not drawn): the button reads "Mark ₹1,200 as paid". */
    val settleUpOther: SettleUpUiState.Shown = settleUp.copy(choice = SettleChoice.Other)

    /** 07-settle-up-no-upi-id. */
    val settleUpNoUpiId: SettleUpUiState.Shown = settleUp.copy(upiId = null, choice = SettleChoice.Other)

    /** 00 after Pay by UPI found no UPI app on the phone (not drawn). */
    val settleUpNoUpiApp: SettleUpUiState.Shown = settleUp.copy(choice = SettleChoice.Other, noUpiApp = true)

    /** 01-mark-as-paid: Rahul is on the app and can confirm. */
    val markAsPaid: MarkAsPaidUiState.Shown = MarkAsPaidUiState.Shown(
        name = "Rahul",
        amountMinor = 120_000,
        groupName = "Flat 302",
        canConfirm = true,
        how = PaidHow.Cash,
        avatar = SampleAvatars.rahul,
    )

    /** 01 for a friend without the app, who gets no note (not drawn). */
    val markAsPaidNoApp: MarkAsPaidUiState.Shown =
        markAsPaid.copy(name = "Priya", canConfirm = false, how = PaidHow.Other, avatar = SampleAvatars.priya)

    /** 02-payment-match. */
    val paymentMatch: PaymentMatchUiState.Shown = PaymentMatchUiState.Shown(
        amountMinor = 120_000,
        accountName = "HDFC Bank",
        accountLast4 = "4521",
        minutesAgo = 0,
        friendName = "Rahul",
        owedFor = OwedFor.Group("Flat 302"),
        owedMinor = 120_000,
        friendAvatar = SampleAvatars.rahul,
    )

    /**
     * 02 for money into an account known only by its UPI ID (not drawn): the line under the amount wraps whole, the ID
     * breaking only after its "@" and "· 12 min ago" kept together, instead of breaking inside the ID or being cut off.
     */
    val paymentMatchUpiAccount: PaymentMatchUiState.Shown =
        paymentMatch.copy(accountName = "arjun.mehta.payments.2026@okhdfcbank", accountLast4 = null, minutesAgo = 12)

    /** 03-balance-cleared. */
    val balanceCleared: BalanceClearedUiState.Shown = BalanceClearedUiState.Shown(
        name = "Rahul",
        amountMinor = 120_000,
        byUpi = true,
        friendNetMinor = 0,
        avatar = SampleAvatars.rahul,
    )

    /** 05-remind-on-whatsapp, with a link as long as a real one on this build's link host. */
    val remind: RemindUiState.Shown = RemindUiState.Shown(
        friendName = "Priya",
        myName = "Arjun",
        amountMinor = 115_000,
        groupName = "Flat 302",
        link = "https://${BuildConfig.LINK_HOST}/pay/q7Lm2XvR9tKp4WcZ8bNf3hYd",
    )

    /** 05 offline before the link was made (not drawn). */
    val remindOffline: RemindUiState.Shown = remind.copy(link = null, problem = SettleProblem.Offline)

    /** 05 when the server refused to make the link (not drawn). */
    val remindFailed: RemindUiState.Shown = remind.copy(link = null, problem = SettleProblem.Failed)

    /** 02 for a friend who owes in two groups and paid both at once (not drawn). */
    val paymentMatchSeveral: PaymentMatchUiState.Shown = paymentMatch.copy(amountMinor = 200_000, owedFor = OwedFor.Several(2), owedMinor = 200_000)

    /** 03 after a bank transfer, with the friend still owing in another group (not drawn). */
    val balanceClearedStillOwes: BalanceClearedUiState.Shown = balanceCleared.copy(byUpi = false, friendNetMinor = 80_000)
}
