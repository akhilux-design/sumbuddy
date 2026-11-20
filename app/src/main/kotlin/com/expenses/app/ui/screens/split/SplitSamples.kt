package com.expenses.app.ui.screens.split

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.PaidWith
import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.screens.groups.SampleAvatars
import com.expenses.app.ui.screens.payment.PaymentAccount
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.YearMonth

/** Sample split flow states with the people, groups and amounts drawn in the design, for previews. */
object SplitSamples {
    private val today = LocalDate.of(2026, 11, 16)
    private val november = YearMonth.of(2026, 11)
    private val hdfcCard = PaymentAccount(name = "HDFC", type = AccountType.CREDIT_CARD, last4 = "4521", upiId = null)

    private val swiggy = SplitHeader(
        title = "Swiggy",
        category = Category.FOOD,
        amountMinor = 45_000,
        date = today,
        today = today,
        account = hdfcCard,
        paidWith = PaidWith.CARD,
        payerName = null,
        typed = false,
    )

    private fun person(id: String, name: String, shareMinor: Long, input: String, avatar: AvatarColor, me: Boolean = false) =
        SplitPerson(
            memberId = id,
            name = name,
            initial = if (me) "A" else name.take(1),
            isMe = me,
            isPayer = me,
            shareMinor = shareMinor,
            input = input,
            avatar = avatar,
        )

    /** Design 09 "Split details": ₹450 at Swiggy with Flat 302, equally, "Do this every time" on. */
    val equally: SplitUiState = SplitUiState.Form(
        header = swiggy,
        groups = listOf(GroupChip("flat", "Flat 302"), GroupChip("goa", "Goa trip")),
        chosenGroupId = "flat",
        chosenGroupName = "Flat 302",
        canChangeGroup = true,
        method = SplitMethod.EQUAL,
        people = listOf(
            person("me", "Arjun", 15_000, "", SampleAvatars.arjun, me = true),
            person("rahul", "Rahul", 15_000, "", SampleAvatars.rahul),
            person("priya", "Priya", 15_000, "", SampleAvatars.priya),
        ),
        note = SplitNote.YourShare(15_000),
        month = november,
        rememberRule = true,
        inputs = mapOf("me" to null, "rahul" to null, "priya" to null),
        saving = false,
    )

    /** Design 10 "Split details (amounts)": ₹200, ₹150 and ₹100 add up to ₹450. */
    val amounts: SplitUiState = (equally as SplitUiState.Form).copy(
        method = SplitMethod.AMOUNTS,
        people = listOf(
            person("me", "Arjun", 20_000, "200", SampleAvatars.arjun, me = true),
            person("rahul", "Rahul", 15_000, "150", SampleAvatars.rahul),
            person("priya", "Priya", 10_000, "100", SampleAvatars.priya),
        ),
        note = SplitNote.AmountsAddUp(45_000, 20_000),
        inputs = mapOf("me" to 20_000L, "rahul" to 15_000L, "priya" to 10_000L),
    )

    /** Design 11 "Split details (amounts do not add up)": ₹50 still to assign, "Split ₹450" off. */
    val amountsLeft: SplitUiState = (amounts as SplitUiState.Form).copy(
        people = listOf(
            person("me", "Arjun", 20_000, "200", SampleAvatars.arjun, me = true),
            person("rahul", "Rahul", 15_000, "150", SampleAvatars.rahul),
            person("priya", "Priya", 5_000, "50", SampleAvatars.priya),
        ),
        note = SplitNote.LeftToAssign(5_000, 45_000),
        inputs = null,
    )

    /** Not designed: a person with no group yet sees only "+ New". */
    val noGroup: SplitUiState = (equally as SplitUiState.Form).copy(
        groups = emptyList(),
        chosenGroupId = null,
        chosenGroupName = "",
        people = emptyList(),
        note = null,
        rememberRule = null,
        inputs = null,
    )

    private val chooseGroups = listOf(ChoiceGroup("flat", "Flat 302", 3), ChoiceGroup("goa", "Goa trip", 5))
    private val chooseFriends = listOf(
        ChoiceFriend("rahul", "Rahul Menon", onApp = true),
        ChoiceFriend("priya", "Priya Nair", onApp = false),
    )

    /** Design 12 "Choose people": Flat 302 on. */
    val choosePeople: ChoosePeopleUiState = ChoosePeopleUiState(
        query = "",
        groups = chooseGroups,
        friends = chooseFriends,
        selectedGroupId = "flat",
        selectedFriends = emptyList(),
        working = false,
    )

    /** Design 48 "Choose people (several friends)": Rahul and Priya on, "Make a group". */
    val choosePeopleSeveral: ChoosePeopleUiState = choosePeople.copy(
        selectedGroupId = null,
        selectedFriends = listOf("rahul", "priya"),
    )

    /** Not designed: nobody known yet, only the search and "Add someone". */
    val choosePeopleEmpty: ChoosePeopleUiState = choosePeople.copy(groups = emptyList(), friends = emptyList(), selectedGroupId = null)

    /** Design 13 "Split added": Rahul and Priya each owe ₹150 for Swiggy, Remembered. */
    val added: SplitAddedUiState = SplitAddedUiState.Shown(
        sentence = AddedSentence.EachOwesYou(listOf("Rahul", "Priya"), 15_000, "Swiggy"),
        balances = listOf(
            AddedBalance("Rahul", "R", "Flat 302", 15_000, owesYou = true, avatar = SampleAvatars.rahul),
            AddedBalance("Priya", "P", "Flat 302", 15_000, owesYou = true, avatar = SampleAvatars.priya),
        ),
        myShareMinor = 15_000,
        totalMinor = 45_000,
        month = november,
        rememberedFor = "Swiggy",
        paymentId = 1,
        busy = false,
    )

    /**
     * Not designed: a direct split with one friend. "Rahul owes you ₹225 for Swiggy.", the row "Rahul" / "owes you",
     * nothing remembered.
     */
    val addedOneFriend: SplitAddedUiState = SplitAddedUiState.Shown(
        sentence = AddedSentence.OneOwesYou("Rahul", 22_500, "Swiggy"),
        balances = listOf(AddedBalance("Rahul", "R", group = null, 22_500, owesYou = true, avatar = SampleAvatars.rahul)),
        myShareMinor = 22_500,
        totalMinor = 45_000,
        month = november,
        rememberedFor = null,
        paymentId = 1,
        busy = false,
    )

    /** Design 14 "Recurring split offer": Rent again, ₹24,000 on 1 Dec, "Yes, every month" chosen. */
    val sameSplit: SameSplitUiState = SameSplitUiState.Shown(
        payee = "Rent",
        groupName = "Flat 302",
        category = Category.RENT_AND_BILLS,
        amountMinor = 2_400_000,
        date = LocalDate.of(2026, 12, 1),
        today = LocalDate.of(2026, 12, 5),
        account = PaymentAccount(name = "HDFC Bank", type = AccountType.BANK_ACCOUNT, last4 = "4521", upiId = null),
        paidWith = PaidWith.UPI,
        everyMonth = true,
        saving = false,
        splitLastMonth = true,
    )

    /**
     * Not designed: Swiggy split with Flat 302 again, but the earlier split was not last month (it was today), so the
     * line reads "You've split Swiggy with Flat 302 before."
     */
    val sameSplitBefore: SameSplitUiState = SameSplitUiState.Shown(
        payee = "Swiggy",
        groupName = "Flat 302",
        category = Category.FOOD,
        amountMinor = 45_000,
        date = LocalDate.of(2026, 11, 16),
        today = LocalDate.of(2026, 11, 16),
        account = PaymentAccount(name = "HDFC", type = AccountType.CREDIT_CARD, last4 = "4521", upiId = null),
        paidWith = PaidWith.CARD,
        everyMonth = true,
        saving = false,
        splitLastMonth = false,
    )

    /**
     * Not designed: nobody owes anybody through a split with one friend (the whole amount is the person's part), so
     * the line reads "Split with Rahul.", never "Added to Rahul."
     */
    val addedOneFriendNothingOwed: SplitAddedUiState = SplitAddedUiState.Shown(
        sentence = AddedSentence.WithFriend("Rahul"),
        balances = emptyList(),
        myShareMinor = 45_000,
        totalMinor = 45_000,
        month = november,
        rememberedFor = null,
        paymentId = 1,
        busy = false,
    )
}
