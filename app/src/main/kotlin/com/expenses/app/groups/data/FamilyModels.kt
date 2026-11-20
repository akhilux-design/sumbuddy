package com.expenses.app.groups.data

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.CategoryTotal
import com.expenses.app.ui.components.AvatarColor
import java.time.LocalDate
import java.time.YearMonth

// Read models and results of FamilyRepository (docs/family.md 8.1). Money in paise; dates are India dates.

/**
 * A person of the family (Home's By person, Family settings' People).
 *
 * @param memberId their member in the family.
 * @param name their whole name ("Meera Iyer"), as Family settings shows it.
 * @param shortName their first name, or the whole name when another member shares it ("Meera"), as Home shows it.
 * @param avatar the colour behind their initial ([com.expenses.app.ui.components.AvatarColors.ofMember]): the person's
 * own colour for the person; a partner keeps the colour they have in the person's groups.
 */
data class FamilyMember(val memberId: String, val name: String, val shortName: String, val avatar: AvatarColor)

/** Who is in the family besides the person ([FamilyInfo.state]). */
enum class FamilyState {
    /** An invite is out and nobody joined yet: "Invite sent" / "Waiting for your partner" / "Home · waiting for your partner". */
    WAITING,

    /** Nobody else and no invite (the partner left or was removed, or the invite was cancelled): "Home · just you". */
    ALONE,

    /** The partner is in: "Arjun and Meera" / "Home · Arjun and Meera". */
    TOGETHER,
}

/**
 * The family on this phone ([FamilyRepository.observeFamily]; null there means no family).
 *
 * @param groupId the family's group id.
 * @param name its name ("Home").
 * @param myMemberId the person's member in it.
 * @param isOwner true when the person set it up (or ownership passed to them): only the owner may remove the partner.
 * @param me the person.
 * @param partner the partner on the app, or null.
 * @param placeholderId the member waiting for the partner (the invite's place), or null when there is no invite out.
 * Send link again and Cancel invite act on it.
 * @param load [LoadState.LOADING] until the first pull after Join Family or a sign-in ended (Home's placeholders),
 * [LoadState.FAILED] when the last pull failed (what was pulled before still shows), else [LoadState.READY].
 */
data class FamilyInfo(
    val groupId: String,
    val name: String,
    val myMemberId: String,
    val isOwner: Boolean,
    val me: FamilyMember,
    val partner: FamilyMember?,
    val placeholderId: String?,
    val load: LoadState,
) {
    /** Who is in the family besides the person. */
    val state: FamilyState
        get() = when {
            partner != null -> FamilyState.TOGETHER
            placeholderId != null -> FamilyState.WAITING
            else -> FamilyState.ALONE
        }
}

/**
 * One person's part of the family month (Home's By person: "Arjun (you)" / "38 expenses" / "₹24,100").
 *
 * @param member the person.
 * @param isMe true for the person themself ("(you)"); they come first.
 * @param count how many family expenses of the month they paid.
 * @param totalMinor what those add up to.
 */
data class FamilyPersonTotal(val member: FamilyMember, val isMe: Boolean, val count: Int, val totalMinor: Long)

/**
 * One family expense (Home's Latest, Month view (Family)): Expense Row Kind=Shared, Meta "Family".
 *
 * @param groupExpenseId the family expense.
 * @param title what it was for ("BigBasket"): the description the payer's phone shared.
 * @param category its category.
 * @param amountMinor the whole amount.
 * @param paidByMe true when the person paid ("Food · You paid"); only these rows open Expense detail.
 * @param paidByName the payer's short name ("Groceries · Meera paid").
 * @param occurredOn its India date.
 * @param personalExpenseId the person's own payment it shares, for Expense detail; null for the partner's rows (not
 * tappable) and while this phone has no row for it.
 */
data class FamilyRow(
    val groupExpenseId: String,
    val title: String,
    val category: Category,
    val amountMinor: Long,
    val paidByMe: Boolean,
    val paidByName: String,
    val occurredOn: LocalDate,
    val personalExpenseId: Long?,
)

/**
 * The family month ([FamilyRepository.observeMonth]): every family expense of [month] that is not deleted, at its
 * full amount, waiting changes of this phone included. Dates are India dates, as the server's.
 *
 * @param month the month.
 * @param familyName the family's name (Month view's "Home · November 2026").
 * @param totalMinor what the family spent ("FAMILY SPENT IN NOVEMBER").
 * @param count how many expenses ("Arjun and Meera · 64 expenses").
 * @param people the person first, then the partner on the app, each with their part; ₹0 and 0 expenses when they
 * paid nothing.
 * @param topCategories the two largest categories, largest first (Top categories: hidden when empty, one tile when
 * one category). Ties go by category name.
 * @param latest the newest five expenses (Latest; when empty, "Nothing in the family month yet").
 * @param lastMonthTotalMinor what the family spent the month before (Month view's "Last month").
 * @param load how the family's data stands ([FamilyInfo.load]).
 * @param expenses every expense of the month, newest first (Month view (Family)).
 * @param categories every category of the month, largest first (Month view's By category).
 */
data class FamilyMonth(
    val month: YearMonth,
    val familyName: String,
    val totalMinor: Long,
    val count: Int,
    val people: List<FamilyPersonTotal>,
    val topCategories: List<CategoryTotal>,
    val latest: List<FamilyRow>,
    val lastMonthTotalMinor: Long,
    val load: LoadState,
    val expenses: List<FamilyRow> = latest,
    val categories: List<CategoryTotal> = topCategories,
)

/** The outcome of a family action that needs the server at once (Set up Family, Invite partner, Send link again). */
sealed interface FamilyResult<out T> {
    /** Done: [value] is the family id ([FamilyRepository.create]) or the link ([FamilyRepository.inviteLink]). */
    data class Ok<out T>(val value: T) : FamilyResult<T>

    /** No connection: the Offline banner ("You're offline"). Nothing was made. */
    data object Offline : FamilyResult<Nothing>

    /** Nobody is signed in. */
    data object SignedOut : FamilyResult<Nothing>

    /**
     * The person is already in a family (`409 already_in_family`: made on another phone, or restored after sign-in). A
     * sync was asked for; open Home on the Family side.
     */
    data object AlreadyInFamily : FamilyResult<Nothing>

    /**
     * Anything else ([code] is the server's error code, or [FamilyRepository.NO_FAMILY]): the Problem banner ("Could
     * not do that now").
     */
    data class Failed(val code: String) : FamilyResult<Nothing>
}
