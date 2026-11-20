package com.expenses.app.ui.screens.split

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.PaidWith
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An expense typed in on Add expense with "Split this" on (design 02-everyday/15), handed to Split it as the
 * `draft` of [com.expenses.app.ui.nav.SplitTyped]. It stays on the phone: only the group expense made from it goes to
 * the server, as every split does.
 *
 * @param amountMinor the whole amount in paise.
 * @param whatFor "What was it for?" as typed; may be blank (the group then reads the category's name).
 * @param category the category.
 * @param epochDay the day it was paid, as [LocalDate.toEpochDay].
 * @param paidByMemberId who paid: a member of [groupId], or null for the person.
 * @param paidWith how the person paid (kept on the person's own row; never sent).
 * @param groupId the group it is for when Add expense knew one (opened from a Group page), else null.
 */
@Serializable
data class TypedExpense(
    val amountMinor: Long,
    val whatFor: String,
    val category: Category,
    val epochDay: Long,
    val paidByMemberId: String? = null,
    val paidWith: PaidWith = PaidWith.CASH,
    val groupId: String? = null,
) {
    /** The day it was paid. */
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    /** This draft as the text the route carries. */
    fun encode(): String = DraftJson.encodeToString(serializer(), this)

    /** Reading a draft back. */
    companion object {
        private val DraftJson = Json { ignoreUnknownKeys = true }

        /** The draft in [text], or null when it is not one (an old or broken route). */
        fun decode(text: String): TypedExpense? = runCatching { DraftJson.decodeFromString(serializer(), text) }.getOrNull()
    }
}
