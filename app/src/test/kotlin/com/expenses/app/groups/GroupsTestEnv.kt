package com.expenses.app.groups

import androidx.room.Room
import com.expenses.app.data.category.Category
import com.expenses.app.data.db.AppDatabase
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TestClock
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.repository.nov
import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ChangeDto
import com.expenses.app.groups.api.ChangesPage
import com.expenses.app.groups.api.ExpenseDto
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.LostGroupDto
import com.expenses.app.groups.api.MeGroupDto
import com.expenses.app.groups.api.MeGroupsResponse
import com.expenses.app.groups.api.MemberDto
import com.expenses.app.groups.api.SettlementDto
import com.expenses.app.groups.api.ShareDto
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.auth.SessionProvider
import com.expenses.app.groups.auth.TokenResult
import com.expenses.app.groups.data.FamilyLinker
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.MirrorWriter
import com.expenses.app.groups.data.RejectedChanges
import com.expenses.app.groups.data.SyncState
import com.expenses.app.groups.sync.GroupEvent
import com.expenses.app.groups.sync.GroupNotifier
import com.expenses.app.groups.sync.LocalGroupData
import com.expenses.app.groups.sync.Outbox
import com.expenses.app.groups.sync.SyncEngine
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.parser.Direction
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.KSerializer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.robolectric.RuntimeEnvironment

// Ids used across the group tests: lowercase UUIDs, as the server requires.
const val ME_USER = "a0000000-0000-4000-8000-000000000001"
const val RAHUL_USER = "a0000000-0000-4000-8000-000000000002"
const val G1 = "b0000000-0000-4000-8000-000000000001"
const val G2 = "b0000000-0000-4000-8000-000000000002"
const val M_ME = "c0000000-0000-4000-8000-000000000001"
const val M_RAHUL = "c0000000-0000-4000-8000-000000000002"
const val M_PRIYA = "c0000000-0000-4000-8000-000000000003"
const val M_ME2 = "c0000000-0000-4000-8000-000000000011"
const val M_RAHUL2 = "c0000000-0000-4000-8000-000000000012"
const val MEERA_USER = "a0000000-0000-4000-8000-000000000003"
const val FAMILY = "b0000000-0000-4000-8000-000000000031"
const val M_FME = "c0000000-0000-4000-8000-000000000021"
const val M_MEERA = "c0000000-0000-4000-8000-000000000022"
const val M_PLACEHOLDER = "c0000000-0000-4000-8000-000000000023"

/**
 * A session for tests of everything but auth: [token] while it is set, signed out when it is null, and
 * [unavailable] (a refresh that failed for now) when that is set.
 */
class FakeSession(@Volatile var token: String? = "access-1") : SessionProvider {
    /** When set, every token request fails for a passing reason with this error. */
    @Volatile var unavailable: ApiError? = null

    override suspend fun installationId(): String = "d0000000-0000-4000-8000-000000000001"

    override suspend fun token(): TokenResult =
        unavailable?.let { TokenResult.Unavailable(it) } ?: token?.let { TokenResult.Valid(it) } ?: TokenResult.SignedOut

    override suspend fun refreshAfterRejected(rejected: String?): TokenResult = TokenResult.SignedOut
}

/** Counts sync requests instead of using WorkManager. */
class RecordingScheduler : SyncScheduler {
    /** How many syncs were asked for. */
    val requests = AtomicInteger()

    override fun requestSync(expedited: Boolean) {
        requests.incrementAndGet()
    }

    override fun requestReminder(groupId: String, memberId: String) = Unit

    override fun requestGroupPull(groupId: String, version: Long) = Unit

    override fun schedulePeriodic() = Unit

    override fun cancelAll() = Unit
}

/** Keeps the posted notifications. */
class RecordingNotifier : GroupNotifier {
    /** Every event posted. */
    val events = CopyOnWriteArrayList<GroupEvent>()

    /** How many times every notification was removed. */
    val cancelledAll = AtomicInteger()

    override fun post(events: List<GroupEvent>) {
        this.events += events
    }

    override fun cancelAll() {
        cancelledAll.incrementAndGet()
    }
}

/** A tiny fake server: answers by "METHOD /path" (query ignored), the last answer repeating; 404 otherwise. */
class FakeServer : Dispatcher() {
    private val routes = ConcurrentHashMap<String, ArrayDeque<MockResponse>>()

    /** Every request received, in order. */
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Answers [method] [path] with [responses] in turn; the last one repeats. */
    fun on(method: String, path: String, vararg responses: MockResponse) {
        routes[key(method, path)] = ArrayDeque(responses.toList())
    }

    /** The requests to [method] [path]. */
    fun requestsTo(method: String, path: String): List<RecordedRequest> =
        requests.filter { it.method == method && it.requestUrl!!.encodedPath == path }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val queue = routes[key(request.method!!, request.requestUrl!!.encodedPath)] ?: return error(404, "not_found")
        synchronized(queue) { return if (queue.size > 1) queue.removeFirst() else queue.first() }
    }

    private fun key(method: String, path: String) = "$method $path"
}

/** A JSON answer. */
fun json(status: Int, body: String): MockResponse =
    MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

/** A JSON answer made from [value]. */
fun <T> json(status: Int, serializer: KSerializer<T>, value: T): MockResponse = json(status, ApiJson.encodeToString(serializer, value))

/** Our error body. */
fun error(status: Int, code: String): MockResponse = json(status, """{"error":{"code":"$code","message":"test"}}""")

/**
 * Everything of the group data layer on an in-memory database and a fake server, for one test. Close it after.
 */
class GroupsTestEnv(val clock: Clock = TestClock) : Closeable {
    /** The fake server's routes. */
    val fake = FakeServer()

    /** The server. */
    val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }

    /** The database. */
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    /** Transactions. */
    val transactions = RoomTransactionRunner(db)

    /** The session. */
    val session = FakeSession()

    /** The API client. */
    val api = GroupsApi(server.url("/").toString().trimEnd('/'), OkHttpClient(), { session })

    /** The outbox. */
    val outbox = Outbox(db.outboxDao(), clock)

    /** Sync requests. */
    val scheduler = RecordingScheduler()

    private val nextFamilyId = AtomicInteger()

    /** The family month's sharing. Family expense ids it makes are e1000000-…-000000000001, 2, … in order. */
    val familyLinker = FamilyLinker(
        db.expenseDao(), db.groupDao(), outbox, scheduler, clock, TestZone,
        newId = { "e1000000-0000-4000-8000-%012d".format(nextFamilyId.incrementAndGet()) },
    )

    /** The n-th family expense id [familyLinker] made. */
    fun familyExpenseId(n: Int): String = "e1000000-0000-4000-8000-%012d".format(n)

    /** Mirrors. */
    val mirrors = MirrorWriter(db.expenseDao(), db.groupDao(), TestZone, familyLinker)

    /** Posted notifications. */
    val notifier = RecordingNotifier()

    /** Local group removal. */
    val local = LocalGroupData(transactions, db.groupDao(), db.outboxDao(), outbox, db.groupExtrasDao(), mirrors, notifier, familyLinker)

    /** Waits the engine asked for, in milliseconds. */
    val sleeps = CopyOnWriteArrayList<Long>()

    /** The sync engine. */
    val engine = SyncEngine(api, transactions, db.groupDao(), outbox, local, mirrors, notifier, clock, sleep = { sleeps += it }, family = familyLinker)

    /** Personal expenses; writes tell [familyLinker]. */
    val expenses = ExpenseRepository(db.expenseDao(), db.categoryRuleDao(), transactions, clock, personalChanges = { familyLinker })

    /** The signed-in user; null for signed out. */
    var me: UserDto? = UserDto(ME_USER, "Arjun", null, null)

    /**
     * Whether someone is signed in, for code that takes `signedIn: suspend () -> Boolean` (the auto splitter, the
     * settlement matcher): true while [me] is set.
     */
    val signedIn: suspend () -> Boolean = { me != null }

    private val nextId = AtomicInteger()

    /** Group data. Ids it makes are e0000000-…-000000000001, 2, … in order. */
    val groups = GroupsRepository(
        api, transactions, db.groupDao(), db.groupExtrasDao(), db.expenseDao(), outbox, mirrors, local, scheduler,
        currentUser = { me }, clock = clock, linkHost = "links.test",
        newId = { "e0000000-0000-4000-8000-%012d".format(nextId.incrementAndGet()) },
        family = familyLinker,
    )

    /** The family on this phone, on the same data and ids as [groups]. */
    val family = FamilyRepository(
        api, transactions, db.groupDao(), db.expenseDao(), familyLinker, groups, local, scheduler,
        currentUser = { me }, clock = clock, zone = TestZone,
        newId = { "e0000000-0000-4000-8000-%012d".format(nextId.incrementAndGet()) },
    )

    /** Refused changes ("Not saved" on the Group page), on the same data as [groups]. */
    val rejectedChanges = RejectedChanges(transactions, db.groupDao(), outbox, mirrors, db.expenseDao(), scheduler, clock, groups)

    /** The n-th id [groups] made. */
    fun madeId(n: Int): String = "e0000000-0000-4000-8000-%012d".format(n)

    /** A synced group [id] at [version] with the person, Rahul (on the app) and Priya (no app). */
    suspend fun seedGroup(id: String = G1, version: Long = 5, myMember: String = M_ME, others: List<MemberEntity>? = null) {
        val group = db.groupDao()
        group.upsertGroup(
            GroupEntity(id, GroupKind.GROUP, "Flat 302", ME_USER, myMember, version, version, syncState = SyncState.SYNCED, createdAt = Instant.EPOCH),
        )
        group.upsertMembers(
            listOf(MemberEntity(myMember, id, ME_USER, "Arjun", null, null, onApp = true, removed = false, version = 1)) +
                (others ?: listOf(
                    MemberEntity(M_RAHUL, id, RAHUL_USER, "Rahul", null, "rahul@okhdfc", onApp = true, removed = false, version = 1),
                    MemberEntity(M_PRIYA, id, null, "Priya", "+919000000001", null, onApp = false, removed = false, version = 1),
                )),
        )
    }

    /**
     * A family [FAMILY] at [version] with the person (Arjun, [M_FME]), the partner Meera on the app ([M_MEERA]) when
     * [partner], and the invite placeholder ([M_PLACEHOLDER]) when [placeholder].
     */
    suspend fun seedFamily(
        version: Long = 3,
        partner: Boolean = true,
        placeholder: Boolean = false,
        latestVersion: Long = version,
        createdAt: Instant = Instant.EPOCH,
    ) {
        val group = db.groupDao()
        group.upsertGroup(
            GroupEntity(
                FAMILY, GroupKind.FAMILY, "Home", ME_USER, M_FME, version, latestVersion, syncState = SyncState.SYNCED,
                createdAt = createdAt, ownerMemberId = M_FME,
            ),
        )
        group.upsertMembers(
            listOfNotNull(
                MemberEntity(M_FME, FAMILY, ME_USER, "Arjun Mehta", null, null, onApp = true, removed = false, version = 1),
                MemberEntity(M_MEERA, FAMILY, MEERA_USER, "Meera Iyer", null, null, onApp = true, removed = false, version = 2).takeIf { partner },
                MemberEntity(M_PLACEHOLDER, FAMILY, null, "Partner", null, null, onApp = false, removed = false, version = 1).takeIf { placeholder },
            ),
        )
    }

    /** An added Family SMS debit of [amountMinor] to [counterparty] on [day] Nov, stored without telling anyone; returns its id. */
    suspend fun familyPayment(amountMinor: Long = 234_000, counterparty: String? = "BigBasket", day: Int = 15, smsId: Long = 500L + amountMinor): Long =
        db.expenseDao().insert(
            Expense(
                amountMinor = amountMinor,
                direction = Direction.DEBIT,
                counterparty = counterparty,
                category = Category.GROCERIES,
                occurredAt = nov(day, 19, 42),
                source = ExpenseSource.SMS,
                status = ExpenseStatus.ADDED,
                paidWith = PaidWith.CARD,
                accountId = null,
                smsId = smsId,
                smsSender = "VM-HDFCBK",
                smsReceivedAt = nov(day, 19, 42),
                originalMessage = "Rs.$amountMinor spent on card XX4521 at $counterparty. Ref 998877665544",
                note = "monthly stock-up, private",
                referenceId = "998877665544",
                scope = ExpenseScope.FAMILY,
            ),
        )

    /** A waiting SMS debit of [amountMinor] to [counterparty] on 10 Nov; returns its id. */
    suspend fun smsPayment(amountMinor: Long = 45_000, counterparty: String? = "Swiggy", status: ExpenseStatus = ExpenseStatus.DETECTED): Long =
        expenses.insert(
            Expense(
                amountMinor = amountMinor,
                direction = Direction.DEBIT,
                counterparty = counterparty,
                category = Category.FOOD,
                occurredAt = nov(10, 20, 15),
                source = ExpenseSource.SMS,
                status = status,
                paidWith = PaidWith.UPI,
                smsId = 100L + amountMinor,
                smsSender = "VM-HDFCBK",
                smsReceivedAt = nov(10, 20, 15),
                originalMessage = "Rs.$amountMinor debited from a/c XX1234 to $counterparty. Ref 123456789012",
                note = "dinner with the team",
                referenceId = "123456789012",
            ),
        )

    override fun close() {
        db.close()
        runCatching { server.shutdown() }
    }
}

/** A member as the server writes it. */
fun memberDto(id: String, groupId: String = G1, userId: String? = null, name: String, version: Long = 1, removed: Boolean = false) =
    MemberDto(id, groupId, userId, name, onApp = userId != null, removed = removed, version = version)

/** An expense as the server writes it, split equally between [shares]. */
fun expenseDto(
    id: String,
    paidBy: String,
    amount: Long,
    shares: Map<String, Long>,
    version: Long,
    groupId: String = G1,
    description: String = "Swiggy",
    occurredOn: String = "2026-11-12",
) = ExpenseDto(
    id = id, groupId = groupId, version = version, description = description, category = "FOOD", amountPaise = amount,
    currency = "INR", occurredOn = occurredOn, paidByMemberId = paidBy, splitMethod = "EQUAL",
    shares = shares.map { (m, s) -> ShareDto(m, s, null) }, createdByMemberId = paidBy, updatedByMemberId = paidBy,
    createdAt = 0, updatedAt = 0,
)

/** One page of changes. */
fun page(
    groupId: String = G1,
    from: Long,
    to: Long,
    latest: Long = to,
    hasMore: Boolean = false,
    members: List<MemberDto> = emptyList(),
    expenses: List<ExpenseDto> = emptyList(),
    settlements: List<SettlementDto> = emptyList(),
    changes: List<ChangeDto> = emptyList(),
    name: String = "Flat 302",
) = ChangesPage(groupId, from, to, latest, hasMore, GroupDto(groupId, "group", name, ME_USER, latest, 0), members, expenses, settlements, changes)

/** `/v1/me/groups` listing [groups] (id to version) with the person as [myMember], and [lost] (id to reason). */
fun meGroups(vararg groups: Pair<String, Long>, myMember: String = M_ME, lost: Map<String, String> = emptyMap()) =
    MeGroupsResponse(
        groups.map { (id, version) -> MeGroupDto(id, "group", "Flat 302", version, myMember, ME_USER) },
        lost = lost.map { (id, reason) -> LostGroupDto(id, reason, at = 0) },
    )

/** `403 not_member` with the [reason] the server gives. */
fun notMember(reason: String): MockResponse =
    json(403, """{"error":{"code":"not_member","message":"test","reason":"$reason"}}""")
