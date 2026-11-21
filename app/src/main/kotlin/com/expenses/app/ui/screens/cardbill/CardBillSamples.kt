package com.expenses.app.ui.screens.cardbill

import com.expenses.app.cardbill.BillState
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.screens.grouppage.GroupNotice
import java.time.LocalDate

/**
 * Sample Card bill view states with the card, people and amounts the design draws (03-getting-paid-back/04, 20 to
 * 26), for previews and tests: HDFC Millennia ••2217, ₹24,300 due on 21 Nov, seen on 16 Nov; Rahul ₹4,100, Priya
 * ₹2,600 and Kiran ₹1,500 owe ₹8,200 of it, so the person's own part is ₹16,100.
 */
object CardBillSamples {
    private val dueDate: LocalDate = LocalDate.of(2026, 11, 21)

    private val rows = listOf(
        CardBillRow("rahul", "Rahul", "R", AvatarColor.Green, 410_000, "Goa trip", 1, 3, "g-goa"),
        CardBillRow("priya", "Priya", "P", AvatarColor.Pink, 260_000, "Flat 302", 1, 5, "g-flat"),
        CardBillRow("kiran", "Kiran", "K", AvatarColor.Blue, 150_000, "Dinner", 1, 1, "g-dinner"),
    )

    private val friendsOwe = CardBillBody.FriendsOwe(
        friendsMinor = 820_000,
        totalMinor = 2_430_000,
        oneFriend = null,
        caption = CardBillCaption.GetBackBy(dueDate, 1_610_000),
        rows = rows,
    )

    /** 04: due in 5 days, three friends owe ₹8,200 of it. */
    val default: CardBillUiState.Shown = CardBillUiState.Shown(
        cardName = "HDFC Millennia",
        last4 = "2217",
        status = BillState.DueIn(5),
        totalMinor = 2_430_000,
        body = friendsOwe,
    )

    /** 20: signed in, and nobody owes part of it. */
    val nothingOwed: CardBillUiState.Shown = default.copy(body = CardBillBody.NobodyOwes)

    /** 21: signed out; You's sign-in card. */
    val signedOut: CardBillUiState.Shown = default.copy(body = CardBillBody.SignedOut)

    /** 22: past the due date, 14 Nov. */
    val overdue: CardBillUiState.Shown = default.copy(
        status = BillState.Overdue(LocalDate.of(2026, 11, 14)),
        body = friendsOwe.copy(caption = CardBillCaption.OwnIs(1_610_000)),
    )

    /** 23: paid on 19 Nov; friends still owe their part. */
    val paid: CardBillUiState.Shown = default.copy(
        status = BillState.Paid(LocalDate.of(2026, 11, 19)),
        body = friendsOwe.copy(caption = CardBillCaption.OwnWas(1_610_000)),
    )

    /** 24: Remind everyone reached Rahul and Priya. */
    val reminderSent: CardBillUiState.Shown = default.copy(notice = GroupNotice.Reminded(listOf("Rahul", "Priya")))

    /** 25: Remind everyone reached nobody. */
    val noReminderSent: CardBillUiState.Shown = default.copy(notice = GroupNotice.NotReminded)

    /** 26: Remind everyone found the phone offline. */
    val offline: CardBillUiState.Shown = default.copy(notice = GroupNotice.Offline)

    /** Not drawn (copy only, D24): one friend owes all of it, in two groups, and the bill is due tomorrow. */
    val oneFriend: CardBillUiState.Shown = default.copy(
        status = BillState.DueTomorrow,
        body = friendsOwe.copy(
            friendsMinor = 410_000,
            oneFriend = "Rahul",
            caption = CardBillCaption.GetBackBy(dueDate, 2_020_000),
            rows = listOf(rows.first().copy(groupName = null, groupCount = 2, expenseCount = 4)),
        ),
    )
}
