package com.expenses.app.ui.screens.month

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.repository.nov
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.ui.screens.activity.ActivityItemDetail
import com.expenses.app.ui.screens.activity.toActivityItem
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Month view, Activity and Home show a split payment the same way: "Travel · your share of ₹558" and the group. */
class SplitRowsTest {
    private val label = SplitLabel(
        groupExpenseId = "e1", groupId = "g1", groupKind = GroupKind.GROUP, groupName = "Goa trip", method = SplitMethod.EQUAL,
        totalMinor = 55_800, myShareMinor = 18_600, paidByMe = true, paidByName = "Arjun", notSent = false,
    )

    private fun row(groupExpenseId: String?) = ExpenseWithAccount(
        Expense(
            id = 1, amountMinor = 55_800, direction = Direction.DEBIT, counterparty = "Uber", category = Category.TRAVEL,
            occurredAt = nov(12), source = ExpenseSource.SMS, status = ExpenseStatus.ADDED, myShareMinor = 18_600,
            groupExpenseId = groupExpenseId,
        ),
        account = null,
    )

    @Test fun aSplitPaymentReadsAsShared() {
        val item = row("e1").toActivityItem(mapOf("e1" to label))
        assertEquals(ActivityItemDetail.Split(Category.TRAVEL, 55_800, "Goa trip"), item.detail)
        assertEquals(18_600L, item.amountPaise)
    }

    @Test fun withoutItsLabelItStaysPersonal() {
        assertTrue(row("e1").toActivityItem() .detail is ActivityItemDetail.Personal)
        assertTrue(row(null).toActivityItem(mapOf("e1" to label)).detail is ActivityItemDetail.Personal)
    }
}
