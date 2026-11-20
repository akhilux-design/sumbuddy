package com.expenses.app.groups.match

import com.expenses.app.data.category.Category
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.SyncState
import com.expenses.parser.Direction
import com.expenses.splits.SplitMethod
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Data for the settle-up tests (docs/groups-build-plan.md 4.4): balances in the groups of GroupsTestEnv and the SMS
// that may settle them. Owned by the settle-up area.

/** The text of every SMS these tests store: none of it may reach the server. */
internal const val SMS_TEXT_MARK = "XX9876"

/** Seeds G2 "Goa trip" with the person (M_ME2) and Rahul (M_RAHUL2, the same user as M_RAHUL in G1). */
internal suspend fun GroupsTestEnv.seedGoaWithRahul() {
    val dao = db.groupDao()
    dao.upsertGroup(GroupEntity(G2, GroupKind.GROUP, "Goa trip", ME_USER, M_ME2, 3, 3, syncState = SyncState.SYNCED, createdAt = Instant.EPOCH))
    dao.upsertMembers(
        listOf(
            MemberEntity(M_ME2, G2, ME_USER, "Arjun", null, null, onApp = true, removed = false, version = 1),
            MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, "rahul@okhdfc", onApp = true, removed = false, version = 1),
        ),
    )
}

/** An expense of [amountMinor] in [groupId], paid by [paidBy] and split equally between [between], on [on]. */
internal suspend fun GroupsTestEnv.groupExpense(
    groupId: String,
    paidBy: String,
    amountMinor: Long,
    between: List<String>,
    on: LocalDate = LocalDate.of(2026, 11, 12),
): String = groups.addGroupExpense(
    groupId,
    GroupExpenseDraft("Dinner", Category.FOOD, amountMinor, on, paidBy, SplitMethod.EQUAL, between.associateWith { null }),
)

/** A money-in SMS of [amountMinor] from [counterparty] (and [vpa]) that arrived at [at], stored as ingest stores it. */
internal suspend fun GroupsTestEnv.smsCredit(
    amountMinor: Long,
    counterparty: String?,
    vpa: String? = null,
    at: Instant = clock.instant(),
    paidWith: PaidWith = PaidWith.UPI,
    accountId: Long? = null,
): Long = expenses.insert(
    Expense(
        amountMinor = amountMinor,
        direction = Direction.CREDIT,
        counterparty = counterparty,
        counterpartyVpa = vpa,
        category = Category.OTHER,
        occurredAt = at,
        source = ExpenseSource.SMS,
        status = ExpenseStatus.ADDED,
        paidWith = paidWith,
        accountId = accountId,
        referenceId = "998877665544",
        smsId = 500L + amountMinor,
        smsSender = "VM-HDFCBK",
        smsReceivedAt = at,
        originalMessage = "Rs.$amountMinor credited to a/c $SMS_TEXT_MARK by ${counterparty.orEmpty()}. Ref 998877665544",
    ),
)

/** A bank account ending 4521 that the person switched off ("Switch off any you do not want tracked"); returns its id. */
internal suspend fun GroupsTestEnv.untrackedAccount(): Long =
    db.accountDao().insert(Account(bank = "HDFC", type = AccountType.BANK_ACCOUNT, last4 = "4521", tracked = false, firstSeenAt = clock.instant()))

/** A waiting debit SMS of [amountMinor] to [vpa] that arrived at [at], paid with [paidWith], stored as ingest stores it. */
internal suspend fun GroupsTestEnv.smsDebit(
    amountMinor: Long,
    vpa: String?,
    at: Instant,
    counterparty: String? = "RAHUL",
    paidWith: PaidWith = PaidWith.UPI,
): Long =
    expenses.insert(
        Expense(
            amountMinor = amountMinor,
            direction = Direction.DEBIT,
            counterparty = counterparty,
            counterpartyVpa = vpa,
            category = Category.OTHER,
            occurredAt = at,
            source = ExpenseSource.SMS,
            status = ExpenseStatus.DETECTED,
            paidWith = paidWith,
            referenceId = "112233445566",
            smsId = 900L + amountMinor,
            smsSender = "VM-HDFCBK",
            smsReceivedAt = at,
            originalMessage = "Rs.$amountMinor debited from a/c $SMS_TEXT_MARK to ${vpa.orEmpty()}. Ref 112233445566",
        ),
    )

/** A clock that tests move forward. */
internal class MovingClock(private var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MovingClock(now, zone)

    override fun instant(): Instant = now

    /** Moves the clock on by [duration]. */
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
