package com.expenses.app.groups

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.ShareDto
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.data.familyLoadState
import com.expenses.app.groups.data.shareEntities
import com.expenses.app.groups.data.toEntity
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Code review (Family build), docs/family.md 7.3: "Family missing or not READY → nothing". FamilyLinker shares as soon
 * as `version > 0`, also while the family's pull failed half way (LoadState.FAILED). On a restored phone the person's
 * own family expense for that payment may sit in a page not pulled yet: it is then shared twice, and the pulled copy
 * becomes a GROUP mirror, so the payment counts twice in the family month and twice in the person's own month.
 */
@RunWith(RobolectricTestRunner::class)
class FamilyLinkerReviewTest {
    private val env = GroupsTestEnv()
    private val dao = env.db.groupDao()

    @After fun close() = env.close()

    @Test fun nothingIsSharedWhileTheFamilyPullFailedSoAPaymentNeverCountsTwice() = runTest {
        // A restored phone: the family's pull got versions up to 3 of 9, then failed.
        env.seedFamily(version = 3, latestVersion = 9)
        dao.upsertGroup(dao.getGroup(FAMILY)!!.copy(loadFailed = true))
        assertEquals(LoadState.FAILED, familyLoadState(dao.getGroup(FAMILY)!!))

        // The payment, read again from SMS on this phone, added as Family before the pull could finish.
        val id = env.smsPayment(45_000)
        env.expenses.add(id, Category.FOOD, ExpenseScope.FAMILY)

        // The next pull brings the family expense the person's old phone shared for that very payment.
        val old = "f1000000-0000-4000-8000-000000000001"
        val stored = ExpenseDto(
            id = old, groupId = FAMILY, version = 6, description = "Swiggy", category = "FOOD", amountPaise = 45_000,
            currency = "INR", occurredOn = "2026-11-10", paidByMemberId = M_FME, splitMethod = "EQUAL",
            shares = listOf(ShareDto(M_FME, 45_000, null)), createdByMemberId = M_FME, updatedByMemberId = M_FME, createdAt = 0, updatedAt = 0,
        )
        env.transactions.run {
            dao.upsertExpense(stored.toEntity(SyncState.SYNCED, env.clock.instant())!!)
            dao.deleteShares(old)
            dao.insertShares(stored.shareEntities())
            env.mirrors.sync(old)
        }

        val month = env.family.observeMonth(YearMonth.of(2026, 11)).first()!!
        assertEquals("family expenses in the family month", 1, month.count)
        assertEquals("₹450 counted once in the family month", 45_000L, month.totalMinor)
        val counted = env.db.expenseDao().familyCandidates().filter { it.status == ExpenseStatus.ADDED }
        assertEquals("personal rows that count the payment (no mirror next to the SMS row)", 1, counted.size)
        assertEquals("the SMS row links the pulled family expense", old, env.expenses.get(id)!!.familyExpenseId)
        assertEquals("nothing queued while the family is not READY", 0, env.outbox.entries().size)
    }
}
