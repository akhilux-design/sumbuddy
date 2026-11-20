package com.expenses.app.ui.screens.home

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.PaidWith
import com.expenses.app.groups.data.LoadState
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.app.ui.format.PayeeWording
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.screens.activity.ActivityItem
import com.expenses.app.ui.screens.activity.ActivityItemDetail
import com.expenses.app.ui.screens.activity.ActivityItemId
import com.expenses.app.ui.screens.activity.PaidWithLabel
import com.expenses.app.ui.screens.family.FamilySamples
import java.time.LocalDate
import java.time.YearMonth

/** Sample Home states with the people, merchants, amounts and dates drawn in the design, for previews. */
object HomeSamples {
    private val arjun = HomeUser(firstName = "Arjun", avatarColor = SelfAvatarColor)

    private val uber = ActivityItem(
        id = ActivityItemId.Expense(1),
        title = "Uber",
        icon = AppIcon.Car,
        amountPaise = 18_600,
        detail = ActivityItemDetail.Split(category = Category.TRAVEL, totalPaise = 55_800, groupName = "Goa trip"),
    )

    private val tracking = HomeContent.Tracking(
        detected = DetectedPayment(
            id = 2,
            merchant = "Swiggy",
            amountMinor = 45_000,
            accountLast4 = "4521",
            seenAt = SeenAt.Now,
            lastSplitWith = "Flat 302",
            othersWaiting = 0,
        ),
        splitTotals = SplitTotals(owedToYouMinor = 345_000, youOweMinor = 80_000),
        latest = listOf(uber),
    )

    /** Design "Home": a payment waiting for a decision, split totals and the latest expense. */
    val default: HomeUiState = HomeUiState(
        user = arjun,
        today = LocalDate.of(2026, 11, 16),
        view = HomeView.Me,
        month = YearMonth.of(2026, 11),
        spentMinor = 1_824_000,
        offline = false,
        content = tracking,
    )

    /** Design "Home (start fresh)": nothing spent yet and an empty state. */
    val startFresh: HomeUiState = default.copy(spentMinor = 0, content = HomeContent.StartFresh)

    /** Design "Home (manual mode)": one expense added by hand and the tip that offers automatic tracking. */
    val manualMode: HomeUiState = default.copy(
        spentMinor = 20_000,
        content = HomeContent.Manual(
            showTrackingTip = true,
            latest = listOf(
                ActivityItem(
                    id = ActivityItemId.Expense(3),
                    title = "Dinner at Truffles",
                    icon = AppIcon.Food,
                    amountPaise = 20_000,
                    detail = ActivityItemDetail.Split(
                        category = Category.FOOD,
                        totalPaise = 60_000,
                        groupName = "Flat 302",
                    ),
                ),
            ),
        ),
    )

    /** Design "Home (offline)": the normal Home with the offline banner on top. */
    val offline: HomeUiState = default.copy(offline = true)

    /**
     * Home for someone who splits with nobody (signed out, or in no group): no "Last time you split" line, no split
     * tiles, and the latest expense is their own.
     */
    val noSplits: HomeUiState = default.copy(
        content = tracking.copy(
            detected = tracking.detected?.copy(lastSplitWith = null),
            splitTotals = null,
            latest = listOf(
                uber.copy(
                    amountPaise = 55_800,
                    detail = ActivityItemDetail.Personal(Category.TRAVEL, PaidWithLabel.Method(PaidWith.UPI)),
                ),
            ),
        ),
    )

    /** Design "Home (several waiting)": 14 payments wait, so the card offers "Review all 14". */
    val severalWaiting: HomeUiState = default.copy(
        content = tracking.copy(detected = tracking.detected?.copy(othersWaiting = 13)),
    )

    /**
     * Not drawn: payees the bank names only by UPI ID, shown whole and broken only after the "@" when too long
     * ("uberindia.rides@" / "hdfcbank"), and nothing owed either way, so both tiles show ₹0 in the neutral colour.
     */
    val upiPayees: HomeUiState = default.copy(
        content = tracking.copy(
            detected = tracking.detected?.copy(
                merchant = "uberindia.rides@hdfcbank",
                payeeWording = PayeeWording.TO,
                amountMinor = 31_200,
                lastSplitWith = null,
            ),
            splitTotals = SplitTotals(owedToYouMinor = 0, youOweMinor = 0),
            latest = listOf(
                moneyIn(id = 4, from = "rahul.kumar@okaxis", amountPaise = 30_000),
                moneyIn(id = 5, from = "9000000002@ybl", amountPaise = 120_000),
                moneyIn(id = 6, from = "priyanka.example.1990@okhdfcbank", amountPaise = 45_000),
            ),
        ),
    )

    // ---- The Family side (docs/family.md 1.5) ----

    /**
     * 02-everyday/51 "Home (family)": no family on this phone. The person's own Family expenses (₹6,840), the Set up
     * Family card, then the payment card, the split tiles and Latest as on Home.
     */
    val familyNoFamily: HomeUiState = default.copy(
        view = HomeView.Family,
        spentMinor = 684_000,
        content = tracking.copy(
            latest = listOf(
                ActivityItem(
                    id = ActivityItemId.Expense(9),
                    title = "BigBasket",
                    icon = AppIcon.Cart,
                    amountPaise = 234_000,
                    detail = ActivityItemDetail.Pooled(Category.GROCERIES, PaidWithLabel.Account("HDFC", "4521"), groupName = null),
                ),
            ),
        ),
        familyCard = FamilyCard.SetUp,
        familyCaption = FamilyCaption.Local,
    )

    /** 06-family/02 "Home (family month)": Arjun and Meera, 64 expenses, ₹42,300. */
    val familyMonth: HomeUiState = default.copy(
        view = HomeView.Family,
        spentMinor = 4_230_000,
        content = HomeContent.FamilyMonth(
            load = LoadState.READY,
            people = FamilySamples.people,
            topCategories = FamilySamples.topCategories,
            latest = listOf(FamilySamples.bigBasketByMeera, FamilySamples.swiggyByMe),
        ),
        familyCaption = FamilyCaption.Together("Arjun", "Meera", 64),
    )

    /** 06-family/03 "waiting for partner": just Arjun, 12 expenses, ₹6,840, and the Invite sent card. */
    val familyWaiting: HomeUiState = familyMonth.copy(
        spentMinor = 684_000,
        content = HomeContent.FamilyMonth(
            load = LoadState.READY,
            people = FamilySamples.peopleAlone,
            topCategories = FamilySamples.topCategoriesAlone,
            latest = listOf(FamilySamples.bigBasketByMe),
        ),
        familyCard = FamilyCard.Waiting,
        familyCaption = FamilyCaption.Alone(12),
    )

    /** 06-family/04 "just you": the partner left; the card offers Invite partner. */
    val familyAlone: HomeUiState = familyWaiting.copy(familyCard = FamilyCard.Invite("Home"))

    /** 06-family/05 "nothing yet": ₹0, both people at ₹0, no Top categories, and the empty card under Latest. */
    val familyNothingYet: HomeUiState = familyMonth.copy(
        spentMinor = 0,
        content = HomeContent.FamilyMonth(
            load = LoadState.READY,
            people = FamilySamples.people.map { it.copy(count = 0, totalMinor = 0) },
            topCategories = emptyList(),
            latest = emptyList(),
        ),
        familyCaption = FamilyCaption.Together("Arjun", "Meera", 0),
    )

    /** 06-family/06 "loading": the first pull after Join Family; placeholder shapes only. */
    val familyLoading: HomeUiState = familyMonth.copy(
        spentMinor = 0,
        content = HomeContent.FamilyMonth(load = LoadState.LOADING, people = emptyList(), topCategories = emptyList(), latest = emptyList()),
        familyCaption = FamilyCaption.Alone(0),
    )

    /** 06-family/07 "offline": 02 with the offline banner, which says the family month syncs when back online. */
    val familyOffline: HomeUiState = familyMonth.copy(offline = true)

    private fun moneyIn(id: Long, from: String, amountPaise: Long) = ActivityItem(
        id = ActivityItemId.Expense(id),
        title = from,
        icon = AppIcon.Cash,
        amountPaise = amountPaise,
        detail = ActivityItemDetail.MoneyIn(paidWith = PaidWithLabel.Method(PaidWith.UPI)),
    )
}
