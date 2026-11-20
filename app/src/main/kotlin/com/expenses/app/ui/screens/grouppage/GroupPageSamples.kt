package com.expenses.app.ui.screens.grouppage

import com.expenses.app.data.category.Category
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.ui.screens.groups.SampleAvatars
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.YearMonth

/** Sample states of the Group page and Group settings, with the names, amounts and counts drawn in the design. */
object GroupPageSamples {
    private val today: LocalDate = LocalDate.of(2026, 11, 16)
    private val november: YearMonth = YearMonth.of(2026, 11)

    private val rahul = MemberBalanceRow("rahul", "Rahul", BalanceKind.OwesYou, 120_000, SampleAvatars.rahul, SettleTarget.MarkAsPaid("rahul"))
    private val priya = MemberBalanceRow("priya", "Priya", BalanceKind.OwesYou, 115_000, SampleAvatars.priya, SettleTarget.MarkAsPaid("priya"))
    private val kiran = MemberBalanceRow("kiran", "Kiran", BalanceKind.AllSettled, 0, SampleAvatars.kiran, null)

    private val rent = MonthExpenseRow(
        id = "rent", description = "Rent", category = Category.RENT_AND_BILLS, date = LocalDate.of(2026, 11, 1),
        method = RowMethod.Auto, amountMinor = 2_400_000, paidByMe = true, payerName = "Arjun", opensDetail = true,
    )
    private val swiggy = MonthExpenseRow(
        id = "swiggy", description = "Swiggy", category = Category.FOOD, date = today,
        method = RowMethod.Split(SplitMethod.EQUAL), amountMinor = 45_000, paidByMe = true, payerName = "Arjun", opensDetail = true,
    )

    private val flat302Card = GroupCard(name = "Flat 302", netMinor = 235_000, people = 3, expensesThisMonth = 12, month = november)

    /** 24: Flat 302 owes the person ₹2,350. */
    val page: GroupPageUiState.Shown = GroupPageUiState.Shown(
        title = "Flat 302",
        isDirect = false,
        today = today,
        card = flat302Card,
        balances = listOf(rahul, priya, kiran),
        expenses = listOf(rent, swiggy),
        footer = GroupFooter(settleUp = SettleTarget.MarkAsPaid("rahul"), canRemind = true),
    )

    /** 40: Priya joined by her link; the owner can undo it. */
    val someoneJoined: GroupPageUiState.Shown = page.copy(
        joins = listOf(JoinBanner(memberId = "priya", joiner = "Priya", placeName = "Priya")),
        balances = listOf(rahul, priya),
    )

    /** 41: the join was undone. */
    val joinUndone: GroupPageUiState.Shown = page.copy(undone = listOf("Priya"), balances = listOf(rahul, priya))

    /** 42: the person owes Goa trip ₹800; Rahul settles with others. */
    val youOwe: GroupPageUiState.Shown = GroupPageUiState.Shown(
        title = "Goa trip",
        isDirect = false,
        today = today,
        card = GroupCard(name = "Goa trip", netMinor = -80_000, people = 5, expensesThisMonth = 9, month = november),
        balances = listOf(
            MemberBalanceRow("kiran", "Kiran", BalanceKind.YouOwe, 50_000, SampleAvatars.kiran, SettleTarget.SettleUp("kiran")),
            MemberBalanceRow("meera", "Meera", BalanceKind.YouOwe, 30_000, SampleAvatars.meera, SettleTarget.SettleUp("meera")),
            MemberBalanceRow("rahul", "Rahul", BalanceKind.SettlesWithOthers, 0, SampleAvatars.rahul, null),
            MemberBalanceRow("dev", "Dev", BalanceKind.AllSettled, 0, SampleAvatars.dev, null),
        ),
        expenses = listOf(
            MonthExpenseRow(
                id = "villa", description = "Villa", category = Category.RENT_AND_BILLS, date = LocalDate.of(2026, 11, 14),
                method = RowMethod.Split(SplitMethod.EQUAL), amountMinor = 1_200_000, paidByMe = false, payerName = "Kiran", opensDetail = true,
            ),
            MonthExpenseRow(
                id = "scooters", description = "Scooters", category = Category.TRAVEL, date = LocalDate.of(2026, 11, 15),
                method = RowMethod.Split(SplitMethod.EQUAL), amountMinor = 150_000, paidByMe = false, payerName = "Meera", opensDetail = true,
            ),
        ),
        footer = GroupFooter(settleUp = SettleTarget.SettleUp("kiran"), canRemind = false),
    )

    /** 43: the server refused the Swiggy split. */
    val changeNotSaved: GroupPageUiState.Shown = page.copy(
        balances = listOf(rahul, priya),
        notSaved = listOf(
            NotSavedRow(
                id = 1, op = OutboxOp.CREATE, description = "Swiggy", category = Category.FOOD, date = today,
                method = RowMethod.Split(SplitMethod.EQUAL), amountMinor = 45_000,
            ),
        ),
        expenses = listOf(rent),
    )

    /** 90: the server refused an edit of the Swiggy split. */
    val editNotSaved: GroupPageUiState.Shown =
        changeNotSaved.copy(notSaved = changeNotSaved.notSaved.map { it.copy(op = OutboxOp.UPDATE) })

    /**
     * Not designed: the refused Swiggy split names someone who has left since, so it cannot be tried again. No Try
     * again; the note says why.
     */
    val changeNotSavedSomeoneLeft: GroupPageUiState.Shown =
        changeNotSaved.copy(notSaved = changeNotSaved.notSaved.map { it.copy(noRetry = NoRetryReason.SomeoneLeft) })

    /**
     * 91 draws a refused delete with Dismiss only. Here that is a delete that cannot be tried again, and its note
     * says so (not designed); a delete that can be tried again keeps Try again.
     */
    val deleteNotSaved: GroupPageUiState.Shown = changeNotSaved.copy(
        notSaved = changeNotSaved.notSaved.map { it.copy(op = OutboxOp.DELETE, noRetry = NoRetryReason.Other) },
    )

    /** Not designed: Undo pressed while offline. */
    val offline: GroupPageUiState.Shown = someoneJoined.copy(notice = GroupNotice.Offline)

    /** Not designed: Remind sent a push to Rahul and Priya. */
    val reminded: GroupPageUiState.Shown = page.copy(notice = GroupNotice.Reminded(listOf("Rahul", "Priya")))

    /** Not designed: nobody owes anybody, so the card is grey and there is no footer. */
    val allSettled: GroupPageUiState.Shown = page.copy(
        card = flat302Card.copy(netMinor = 0),
        balances = listOf(
            rahul.copy(kind = BalanceKind.AllSettled, amountMinor = 0, target = null),
            priya.copy(kind = BalanceKind.AllSettled, amountMinor = 0, target = null),
            kiran,
        ),
        footer = null,
    )

    /** Not designed: a direct split with Rahul, titled with his name; its Group settings row says less. */
    val direct: GroupPageUiState.Shown = GroupPageUiState.Shown(
        title = "Rahul",
        isDirect = true,
        today = today,
        card = GroupCard(name = "Rahul", netMinor = 45_000, people = 2, expensesThisMonth = 3, month = november),
        balances = listOf(rahul.copy(amountMinor = 45_000)),
        expenses = listOf(swiggy),
        footer = GroupFooter(settleUp = SettleTarget.MarkAsPaid("rahul"), canRemind = true),
    )

    /** 22 on the Group page: the group's data is still loading. */
    val loading: GroupPageUiState = GroupPageUiState.Loading(title = "Flat 302")

    /** 23: Flat 302 could not be loaded. */
    val couldNotLoad: GroupPageUiState = GroupPageUiState.CouldNotLoad(title = "Flat 302")

    private val people = listOf(
        PersonRow("arjun", "Arjun Mehta", PersonKind.You, isOwner = true),
        PersonRow("rahul", "Rahul Menon", PersonKind.OnApp, isOwner = false),
        PersonRow("priya", "Priya Nair", PersonKind.FriendLinkSent, isOwner = false),
    )

    /** 25: Group settings of the owner. */
    val settings: GroupSettingsUiState.Shown = GroupSettingsUiState.Shown(
        groupName = "Flat 302",
        isDirect = false,
        nameField = "Flat 302",
        people = people,
        canLeave = false,
        delete = DeleteRow.Owner(canDelete = false),
    )

    /**
     * 143: the person owns a group nobody else has joined (Priya and Kiran are not on the app), balances open: Delete
     * group can be tapped and says why.
     */
    val settingsNobodyJoined: GroupSettingsUiState.Shown = settings.copy(
        people = listOf(
            people[0],
            PersonRow("priya", "Priya Nair", PersonKind.Friend, isOwner = false),
            PersonRow("kiran", "Kiran Rao", PersonKind.Friend, isOwner = false),
        ),
        delete = DeleteRow.Owner(canDelete = true, nobodyElseJoined = true),
    )

    /** 45: Rahul Menon owns the group. */
    val settingsNotOwner: GroupSettingsUiState.Shown = settings.copy(
        people = listOf(people[0].copy(isOwner = false), people[1].copy(isOwner = true), people[2]),
        delete = DeleteRow.NotOwner(ownerName = "Rahul Menon"),
    )

    /** 46: the friend's link sheet for Priya Nair. */
    val settingsFriendsLink: GroupSettingsUiState.Shown = settings.copy(sheet = FriendSheet(memberId = "priya", name = "Priya Nair"))

    /** Not designed, laid out as 46: Priya Nair has no link yet and owes the person, so Remind can send her one. */
    val settingsFriendNoLink: GroupSettingsUiState.Shown = settings.copy(
        people = listOf(people[0], people[1], people[2].copy(kind = PersonKind.Friend)),
        sheet = FriendSheet(memberId = "priya", name = "Priya Nair", linkOn = false, canRemind = true),
    )

    /** Not designed: the same while Priya owes the person nothing, so Remind is off and the note says why. */
    val settingsFriendNoLinkOwesNothing: GroupSettingsUiState.Shown =
        settingsFriendNoLink.copy(sheet = settingsFriendNoLink.sheet?.copy(canRemind = false))

    /** Not designed: Group settings of a direct split with Rahul: no name, no Add someone, no Delete, no owner. */
    val settingsDirect: GroupSettingsUiState.Shown = GroupSettingsUiState.Shown(
        groupName = "Rahul Menon",
        isDirect = true,
        nameField = "",
        people = listOf(people[0].copy(isOwner = false), people[1]),
        canLeave = false,
        delete = null,
    )

    /** Not designed: the confirm before leaving. */
    val settingsLeaveConfirm: GroupSettingsUiState.Shown = settings.copy(canLeave = true, confirm = SettingsConfirm.Leave)

    /** Not designed: the confirm before deleting. */
    val settingsDeleteConfirm: GroupSettingsUiState.Shown =
        settings.copy(delete = DeleteRow.Owner(canDelete = true), confirm = SettingsConfirm.Delete)
}
