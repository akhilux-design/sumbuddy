package com.expenses.app.ui.screens.family

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.groups.data.FamilyMember
import com.expenses.app.groups.data.FamilyPersonTotal
import com.expenses.app.groups.data.FamilyRow
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.app.ui.screens.activity.ActivityDate
import com.expenses.app.ui.screens.month.MonthCategory
import com.expenses.app.ui.screens.signin.ActionProblem
import java.time.LocalDate
import java.time.YearMonth

/** Sample states of the family screens, one per design state of 06-family, with the design's names and numbers. */
object FamilySamples {
    /** Arjun, the person, with his own colour. */
    val arjun: FamilyMember = FamilyMember("c0000000-0000-4000-8000-000000000021", "Arjun Mehta", "Arjun", SelfAvatarColor)

    /** Meera, the partner: the first other person is Green when no other group exists (the avatar colour rule). */
    val meera: FamilyMember = FamilyMember("c0000000-0000-4000-8000-000000000022", "Meera Iyer", "Meera", AvatarColor.Green)

    private val today: LocalDate = LocalDate.of(2026, 11, 16)

    /** BigBasket, Groceries, ₹2,340, paid by Meera (not tappable here). */
    val bigBasketByMeera: FamilyRow = FamilyRow(
        groupExpenseId = "e1000000-0000-4000-8000-000000000001",
        title = "BigBasket",
        category = Category.GROCERIES,
        amountMinor = 234_000,
        paidByMe = false,
        paidByName = "Meera",
        occurredOn = today,
        personalExpenseId = null,
    )

    /** Swiggy, Food, ₹450, paid by the person: it opens Expense detail. */
    val swiggyByMe: FamilyRow = FamilyRow(
        groupExpenseId = "e1000000-0000-4000-8000-000000000002",
        title = "Swiggy",
        category = Category.FOOD,
        amountMinor = 45_000,
        paidByMe = true,
        paidByName = "Arjun",
        occurredOn = today,
        personalExpenseId = 7,
    )

    /** BigBasket, Groceries, ₹2,340, paid by the person (06-family/03 and 04). */
    val bigBasketByMe: FamilyRow = bigBasketByMeera.copy(
        groupExpenseId = "e1000000-0000-4000-8000-000000000003",
        paidByMe = true,
        paidByName = "Arjun",
        personalExpenseId = 8,
    )

    /** 06-family/02: Arjun 38 expenses ₹24,100, Meera 26 expenses ₹18,200. */
    val people: List<FamilyPersonTotal> = listOf(
        FamilyPersonTotal(arjun, isMe = true, count = 38, totalMinor = 2_410_000),
        FamilyPersonTotal(meera, isMe = false, count = 26, totalMinor = 1_820_000),
    )

    /** 06-family/02: Rent ₹24,000 and Groceries ₹9,840. */
    val topCategories: List<CategoryTotal> = listOf(
        CategoryTotal(Category.RENT_AND_BILLS, count = 1, totalMinor = 2_400_000),
        CategoryTotal(Category.GROCERIES, count = 14, totalMinor = 984_000),
    )

    /** 06-family/03 and 04: only Arjun, 12 expenses ₹6,840. */
    val peopleAlone: List<FamilyPersonTotal> = listOf(FamilyPersonTotal(arjun, isMe = true, count = 12, totalMinor = 684_000))

    /** 06-family/03 and 04: Groceries ₹3,120 and Food ₹1,860. */
    val topCategoriesAlone: List<CategoryTotal> = listOf(
        CategoryTotal(Category.GROCERIES, count = 5, totalMinor = 312_000),
        CategoryTotal(Category.FOOD, count = 4, totalMinor = 186_000),
    )

    // ---- Set up Family ----

    /** 06-family/00: "Home", no earlier Family expenses. */
    val setUp: SetUpFamilyUiState = SetUpFamilyUiState(name = "Home")

    /** 06-family/09: 12 earlier Family expenses on this phone are shared too. */
    val setUpEarlier: SetUpFamilyUiState = setUp.copy(earlierCount = 12)

    /** 06-family/10: the name left blank; "Invite partner" is off. */
    val setUpNameBlank: SetUpFamilyUiState = setUp.copy(name = "", nameError = true)

    /** 06-family/11: "Invite partner" offline. */
    val setUpOffline: SetUpFamilyUiState = setUp.copy(problem = ActionProblem.OFFLINE)

    /** 06-family/12: "Invite partner" refused. */
    val setUpFailed: SetUpFamilyUiState = setUp.copy(problem = ActionProblem.FAILED)

    // ---- Family settings ----

    /** 06-family/17: Arjun Mehta (You) and Meera Iyer (Partner); Arjun owns the family. */
    val settings: FamilySettingsUiState.Shown = FamilySettingsUiState.Shown(
        familyName = "Home",
        nameField = "Home",
        me = "Arjun Mehta",
        partner = FamilyPartnerRow.Joined(meera.memberId, meera.name, meera.shortName, canRemove = true),
    )

    /** 06-family/18: an invite is out. */
    val settingsInviteSent: FamilySettingsUiState.Shown = settings.copy(partner = FamilyPartnerRow.Invited)

    /** 06-family/19: no partner and no invite. */
    val settingsNoPartner: FamilySettingsUiState.Shown = settings.copy(partner = FamilyPartnerRow.None)

    /** 06-family/20: the invite sheet over 18. */
    val settingsInviteSheet: FamilySettingsUiState.Shown = settingsInviteSent.copy(inviteSheet = true)

    /** 06-family/21: "Leave Home?". */
    val settingsLeave: FamilySettingsUiState.Shown = settings.copy(confirm = FamilyConfirm.Leave)

    /** 06-family/22: "Remove Meera from Home?". */
    val settingsRemove: FamilySettingsUiState.Shown = settings.copy(confirm = FamilyConfirm.Remove(meera.memberId, meera.shortName))

    /** 06-family/23: an online action offline. */
    val settingsOffline: FamilySettingsUiState.Shown = settings.copy(problem = ActionProblem.OFFLINE)

    /** 06-family/24: an online action refused. */
    val settingsFailed: FamilySettingsUiState.Shown = settings.copy(problem = ActionProblem.FAILED)

    // ---- Month view (Family) ----

    /**
     * 06-family/27: Home · November 2026, ₹42,300 next to ₹38,100, by person, Rent and bills 57% and Groceries 23%, and
     * today's BigBasket (Meera paid) and Swiggy (you paid).
     */
    val month: FamilyMonthUiState = FamilyMonthUiState(
        month = YearMonth.of(2026, 11),
        familyName = "Home",
        totalMinor = 4_230_000,
        lastMonthMinor = 3_810_000,
        people = people,
        categories = listOf(
            MonthCategory(Category.RENT_AND_BILLS, count = 1, totalMinor = 2_400_000, percent = 57),
            MonthCategory(Category.GROCERIES, count = 14, totalMinor = 984_000, percent = 23),
        ),
        days = listOf(FamilyDay(ActivityDate.Today, listOf(bigBasketByMeera, swiggyByMe))),
        canGoNext = false,
    )
}
