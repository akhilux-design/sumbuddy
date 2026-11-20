package com.expenses.app.ui.screens.payment

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Sample payment screen states with the merchant, amounts and dates drawn in the design, for previews. */
object PaymentSamples {
    private val today = LocalDate.of(2026, 11, 16)
    private val november = YearMonth.of(2026, 11)
    private val nineTwelve = PaymentTime(at = LocalDateTime.of(2026, 11, 16, 9, 12), today = today)
    private val hdfcCard = PaymentAccount(name = "HDFC", type = AccountType.CREDIT_CARD, last4 = "4521", upiId = null)

    /** Design 05 "Add to expenses": ₹450 at Swiggy, Food, Just me, with a note. */
    val addToExpenses: AddToExpensesUiState = AddToExpensesUiState.Form(
        amountMinor = 45_000,
        time = nineTwelve,
        account = hdfcCard,
        paidWith = PaidWith.CARD,
        payee = "Swiggy",
        category = Category.FOOD,
        scope = ExpenseScope.ME,
        note = "Friday dinner",
        alwaysThisCategory = false,
        month = november,
        saving = false,
    )

    /** Design 06 "Choose category" with Food chosen. */
    val chooseCategory: ChooseCategoryUiState = ChooseCategoryUiState(current = Category.FOOD)

    /** Design 07 "Expense added": ₹450 at Swiggy, November at ₹18,690. */
    val expenseAdded: ExpenseAddedUiState = ExpenseAddedUiState.Shown(
        expenseId = 1,
        merchant = "Swiggy",
        amountMinor = 45_000,
        category = Category.FOOD,
        account = hdfcCard,
        paidWith = PaidWith.CARD,
        scope = ExpenseScope.ME,
        month = november,
        monthTotalMinor = 1_869_000,
    )

    private val swiggyDetails = ExpenseDetails(
        source = ExpenseSource.SMS,
        status = ExpenseStatus.ADDED,
        direction = Direction.DEBIT,
        merchant = "Swiggy",
        amountMinor = 45_000,
        currency = "INR",
        category = Category.FOOD,
        scope = ExpenseScope.ME,
        note = "Friday dinner",
        time = nineTwelve,
        account = hdfcCard,
        paidWith = PaidWith.CARD,
        originalMessage = null,
        month = november,
    )

    /** 06-family/25 "Add to expenses (Family every time)": Family chosen, "Swiggy is always Food · Family". */
    val addToExpensesFamilyEveryTime: AddToExpensesUiState =
        (addToExpenses as AddToExpensesUiState.Form).copy(scope = ExpenseScope.FAMILY, alwaysThisCategory = true)

    /**
     * 06-family/26 "Expense detail (delete, Family)": the row is shared in the family month, so the question says it
     * leaves that too.
     */
    val expenseDetailFamilyDelete: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails.copy(
            scope = ExpenseScope.FAMILY,
            inFamilyMonth = true,
            originalMessage = "Rs 450.00 spent on HDFC card XX4521 at SWIGGY on 16-11-26",
        ),
        edit = null,
        confirmingDelete = true,
    )

    /** Design 08 "Expense detail". */
    val expenseDetail: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails,
        edit = null,
        confirmingDelete = false,
    )

    private val flat302Split = ExpenseSplit(
        groupName = "Flat 302",
        method = SplitMethod.EQUAL,
        myShareMinor = 15_000,
        splitsItself = false,
        paidByName = null,
    )

    /** Design 47 under the dialog: Expense detail of a split, with the Flat 302 row and no "Split this expense". */
    val expenseDetailSplit: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails.copy(linked = true),
        edit = null,
        confirmingDelete = false,
        split = flat302Split,
    )

    /** Design 47 "Expense detail (delete split)": "Delete Swiggy from Flat 302?". */
    val expenseDetailSplitDelete: ExpenseDetailUiState =
        (expenseDetailSplit as ExpenseDetailUiState.Shown).copy(confirmingDelete = true)

    /** Not designed: rent that splits itself with Flat 302 ("· splits itself"; a tap opens Same split?). */
    val expenseDetailSplitsItself: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails.copy(
            merchant = "Rent",
            amountMinor = 2_400_000,
            category = Category.RENT_AND_BILLS,
            note = null,
            account = PaymentAccount(name = "HDFC", type = AccountType.BANK_ACCOUNT, last4 = "4521", upiId = null),
            linked = true,
        ),
        edit = null,
        confirmingDelete = false,
        split = flat302Split.copy(myShareMinor = 800_000, splitsItself = true),
    )

    /** Not designed: a friend's expense the person has a share in ("From Flat 302", "Today · Rahul paid"). */
    val expenseDetailFriendPaid: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails.copy(
            source = ExpenseSource.GROUP,
            merchant = "Dinner at Truffles",
            amountMinor = 60_000,
            note = null,
            time = PaymentTime(at = LocalDateTime.of(2026, 11, 16, 0, 0), today = today),
            account = null,
            paidWith = PaidWith.OTHER,
            linked = true,
        ),
        edit = null,
        confirmingDelete = false,
        split = flat302Split.copy(myShareMinor = 20_000, paidByName = "Rahul"),
    )

    /** Expense detail with the bank's original message under the rows. */
    val expenseDetailWithMessage: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails.copy(
            originalMessage = "Rs.450.00 spent on HDFC Bank Card x4521 at SWIGGY on 16-11-26. Not you? Call 18002586161",
        ),
        edit = null,
        confirmingDelete = false,
    )

    /** Expense detail while editing. */
    val expenseDetailEdit: ExpenseDetailUiState = ExpenseDetailUiState.Shown(
        details = swiggyDetails,
        edit = ExpenseEdit(
            amountText = null,
            amountError = false,
            payee = "Swiggy",
            note = "Friday dinner",
            scope = ExpenseScope.ME,
            category = swiggyDetails.category,
            saving = false,
        ),
        confirmingDelete = false,
    )

    /** Add expense with "Split this" off (not designed): ₹600 for dinner, paid in cash today. */
    val addExpense: AddExpenseUiState = AddExpenseUiState.empty(today).copy(
        amountText = "600",
        whatFor = "Dinner at Truffles",
        category = Category.FOOD,
    )

    /** Design 15 "Add expense": opened from Flat 302, "Split this" on, Paid by You, Rahul or Priya. */
    val addExpenseSplit: AddExpenseUiState = addExpense.copy(
        splitThis = true,
        payers = listOf(Payer("rahul", "Rahul"), Payer("priya", "Priya")),
    )

    /** Add expense with Rahul as the payer (not designed): "Paid with" is not asked. */
    val addExpenseFriendPaid: AddExpenseUiState = addExpenseSplit.copy(paidBy = "rahul")

    /** Add expense after Add with no amount: the error under the amount. */
    val addExpenseError: AddExpenseUiState = AddExpenseUiState.empty(today).copy(amountError = true)

    /** Design 19 "Needs review": two messages, the first open with its amount and who was paid. */
    val needsReview: NeedsReviewUiState = NeedsReviewUiState.Shown(
        items = listOf(
            ReviewEntry(
                id = 1,
                sender = "VM-HDFCBK",
                body = "Rs 1200.00 debited from A/c XX4521 to VPA rk.menon@okaxis on 15-11-26",
                date = LocalDate.of(2026, 11, 15),
                amountMinor = 120_000,
            ),
            ReviewEntry(
                id = 2,
                sender = "AX-ICICIB",
                body = "Txn of INR 2,499 on ICICI Card XX8890. Avl lmt updated.",
                date = LocalDate.of(2026, 11, 14),
                amountMinor = 249_900,
            ),
        ),
        form = ReviewForm(itemId = 1, amountText = "1,200", payee = "Rahul Menon", amountError = false, saving = false),
        today = today,
        added = null,
    )

    /** Needs review with nothing left to check. */
    val needsReviewEmpty: NeedsReviewUiState =
        NeedsReviewUiState.Shown(items = emptyList(), form = null, today = today, added = null)
}
