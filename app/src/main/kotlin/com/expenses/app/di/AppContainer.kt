package com.expenses.app.di

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.expenses.app.BuildConfig
import com.expenses.app.cardbill.CardBillReader
import com.expenses.app.cardbill.CardBillReminders
import com.expenses.app.data.db.AppDatabase
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.CardBillRepository
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.repository.ReadSmsRepository
import com.expenses.app.data.repository.ReviewRepository
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TransactionRunner
import com.expenses.app.data.settings.ReviewSessionStore
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.serverHttpClient
import com.expenses.app.groups.auth.AndroidCredentials
import com.expenses.app.groups.auth.AuthHooks
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.auth.GoogleSignIn
import com.expenses.app.groups.auth.KeystoreCipher
import com.expenses.app.groups.auth.TokenStore
import com.expenses.app.groups.auth.devSignIn
import com.expenses.app.groups.data.FamilyLinker
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.MirrorWriter
import com.expenses.app.groups.data.RejectedChanges
import com.expenses.app.groups.match.SettlementMatcher
import com.expenses.app.groups.push.PushSetup
import com.expenses.app.groups.split.AutoSplitter
import com.expenses.app.groups.sync.AndroidNetworkStatus
import com.expenses.app.groups.sync.GroupNotifications
import com.expenses.app.groups.sync.GroupNotifier
import com.expenses.app.groups.sync.LiveRoom
import com.expenses.app.groups.sync.LocalGroupData
import com.expenses.app.groups.sync.NetworkStatus
import com.expenses.app.groups.sync.Outbox
import com.expenses.app.groups.sync.SyncEngine
import com.expenses.app.groups.sync.SyncScheduler
import com.expenses.app.groups.sync.WorkSyncScheduler
import com.expenses.app.ingest.IngestPipeline
import com.expenses.app.ingest.NewPaymentsHook
import com.expenses.app.sms.AndroidSmsInbox
import com.expenses.app.sms.PagedSmsInbox
import com.expenses.app.sms.ParserRulesCleanup
import com.expenses.app.sms.SmsIngestPipeline
import com.expenses.app.ui.screens.home.HomeView
import com.expenses.parser.SmsParser
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The coroutine dispatchers the app uses, passed in so tests can replace them.
 *
 * @param io for blocking I/O such as reading the SMS content provider.
 * @param default for CPU work such as parsing many messages.
 * @param main for the user interface.
 */
data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)

/**
 * The app's dependencies, made once per process by [com.expenses.app.ExpensesApplication] and handed to
 * ViewModels through [appViewModelFactory]. Everything is created on first use.
 *
 * @param context any context; only its application context is kept.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /**
     * The clock for "now" and for the time zone that months and days use: always India time ([ZONE]), the same zone
     * the parser reads printed SMS dates in, so a payment falls on the same day and month everywhere in the app
     * whatever zone the phone is set to.
     */
    val clock: Clock = Clock.system(ZONE)

    /** Dispatchers for work off the main thread. */
    val dispatchers: AppDispatchers = AppDispatchers()

    /** A scope that lives as long as the process, for work that must finish even when a screen closes. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** The SMS parser. Stateless, so one is shared. */
    val parser: SmsParser = SmsParser(ZONE)

    /** The app's database. */
    val database: AppDatabase by lazy { AppDatabase.open(appContext) }

    /** Runs several repository writes as one transaction. */
    val transactions: TransactionRunner by lazy { RoomTransactionRunner(database) }

    /** Expenses and payments. Writes tell [familyLinker], so the family month follows them (docs/family.md 7.4). */
    val expenses: ExpenseRepository by lazy {
        ExpenseRepository(database.expenseDao(), database.categoryRuleDao(), transactions, clock, personalChanges = { familyLinker })
    }

    /** Accounts and cards. */
    val accounts: AccountRepository by lazy { AccountRepository(database.accountDao(), transactions, clock) }

    /** Credit card bills. */
    val cardBills: CardBillRepository by lazy { CardBillRepository(database.cardBillDao(), transactions) }

    /** Needs review items. */
    val reviews: ReviewRepository by lazy { ReviewRepository(database.reviewDao()) }

    /** Which SMS were already read. */
    val readSms: ReadSmsRepository by lazy { ReadSmsRepository(database.readSmsDao()) }

    /** The person's settings. */
    val settings: SettingsRepository by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile(SETTINGS_FILE) })
    }

    /** The phone's SMS inbox, read page by page. */
    val smsInbox: PagedSmsInbox by lazy { AndroidSmsInbox(appContext, dispatchers.io) }

    /** Turns SMS into stored payments, card bills and review items. */
    val ingestPipeline: IngestPipeline by lazy {
        SmsIngestPipeline(
            parser = parser,
            transactions = transactions,
            expenses = expenses,
            accounts = accounts,
            cardBills = cardBills,
            reviews = reviews,
            readSms = readSms,
            clock = clock,
            parsing = dispatchers.default,
            parserZone = ZONE,
        )
    }

    /** Removes, once per parser rules version, stored SMS rows the current parser no longer reads as payments. */
    val parserRulesCleanup: ParserRulesCleanup by lazy {
        ParserRulesCleanup(
            parser = parser,
            transactions = transactions,
            expenseDao = database.expenseDao(),
            expenses = expenses,
            accountDao = database.accountDao(),
            readSmsDao = database.readSmsDao(),
            settings = settings,
            parsing = dispatchers.default,
        )
    }

    // ---- Splitting (docs/server.md 9). Only group data reaches the server; SMS and personal expenses never do. ----

    /** The HTTP client of the splitting server: nothing is logged, and a whole call is cut off after 20 seconds. */
    val httpClient: OkHttpClient by lazy { serverHttpClient() }

    /** The splitting server's API (`BuildConfig.API_BASE_URL`). */
    val api: GroupsApi by lazy { GroupsApi(BuildConfig.API_BASE_URL, httpClient, session = { auth }) }

    /** The stored session, encrypted with a key in the Android Keystore. */
    private val tokenStore: TokenStore by lazy {
        TokenStore(PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile(AUTH_FILE) }, KeystoreCipher())
    }

    /** Sign-in, session and profile. */
    val auth: AuthRepository by lazy {
        AuthRepository(
            api = api,
            store = tokenStore,
            google = GoogleSignIn(BuildConfig.GOOGLE_WEB_CLIENT_ID, AndroidCredentials(appContext)),
            dev = devSignIn(api),
            settings = settings,
            hooks = authHooks,
            clock = clock,
            scope = appScope,
        )
    }

    /** Asks for group syncs through WorkManager. */
    val sync: SyncScheduler by lazy { WorkSyncScheduler(appContext) }

    /** Group changes waiting to be sent. */
    val outbox: Outbox by lazy { Outbox(database.outboxDao(), clock) }

    /** Shares the person's Family payments in the family month (docs/family.md 7). */
    val familyLinker: FamilyLinker by lazy {
        FamilyLinker(
            expenses = database.expenseDao(),
            groups = database.groupDao(),
            outbox = outbox,
            scheduler = sync,
            clock = clock,
            zone = ZONE,
            categoryName = { appContext.getString(it.label) },
        )
    }

    /** Keeps personal rows in step with group expenses, and family expenses through [familyLinker]. */
    private val mirrors: MirrorWriter by lazy { MirrorWriter(database.expenseDao(), database.groupDao(), ZONE, familyLinker) }

    /** Removes group data from this phone, and the "Groups" notifications with every group (sign out, account deletion). */
    private val localGroups: LocalGroupData by lazy {
        LocalGroupData(
            transactions, database.groupDao(), database.outboxDao(), outbox, database.groupExtrasDao(), mirrors, groupNotifier,
            family = familyLinker,
        )
    }

    /** The "Groups" notifications. */
    val groupNotifier: GroupNotifier by lazy { GroupNotifications(appContext) }

    /** Sends the outbox and pulls group changes. */
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            api = api,
            transactions = transactions,
            groups = database.groupDao(),
            outbox = outbox,
            local = localGroups,
            mirrors = mirrors,
            notifier = groupNotifier,
            clock = clock,
            visibleGroup = { liveRoom.visibleGroupId.value },
            family = familyLinker,
        )
    }

    /** The live connection of the group whose screen is open. */
    val liveRoom: LiveRoom by lazy {
        LiveRoom(
            api, database.groupDao(),
            pullGroup = { syncEngine.pullGroup(it) },
            pullChanged = { groupId, version -> syncEngine.pullChanged(groupId, version) },
            scheduler = sync, scope = appScope,
        )
    }

    /** Push through Firebase, when this build has a Firebase project. */
    val push: PushSetup by lazy { PushSetup(api = { api }, store = tokenStore, clock = clock) }

    /** Groups, group expenses, settlements, links and invites. */
    val groups: GroupsRepository by lazy {
        GroupsRepository(
            api = api,
            transactions = transactions,
            groups = database.groupDao(),
            extras = database.groupExtrasDao(),
            expenses = database.expenseDao(),
            outbox = outbox,
            mirrors = mirrors,
            local = localGroups,
            scheduler = sync,
            currentUser = { auth.profile() },
            clock = clock,
            linkHost = BuildConfig.LINK_HOST,
            categoryName = { appContext.getString(it.label) },
            family = familyLinker,
        )
    }

    /** The family on this phone: its month, set up, invite, rename, remove, leave (docs/family.md 8.1). */
    val family: FamilyRepository by lazy {
        FamilyRepository(
            api = api,
            transactions = transactions,
            groups = database.groupDao(),
            expenses = database.expenseDao(),
            linker = familyLinker,
            groupsRepository = groups,
            local = localGroups,
            scheduler = sync,
            currentUser = { auth.profile() },
            clock = clock,
            zone = ZONE,
        )
    }

    /**
     * The side of Home's Me / Family toggle, app-scoped so Join Family, Set up Family and "Meera joined Home" can open
     * Home on the Family side (docs/family.md 1.5). Back to [HomeView.Me] in a new process.
     */
    val homeView: MutableStateFlow<HomeView> = MutableStateFlow(HomeView.Me)

    /** Changes the server refused, for the Group page's "Not saved" row (Try again, Dismiss). */
    val rejectedChanges: RejectedChanges by lazy {
        RejectedChanges(transactions, database.groupDao(), outbox, mirrors, database.expenseDao(), sync, clock, groups)
    }

    /** Whether the phone is online now (Home's offline banner, online-only actions). */
    val network: NetworkStatus by lazy { AndroidNetworkStatus(appContext) }

    /** Splits a new payment by itself when its merchant has an automatic split rule (docs/server.md 9.4). */
    val autoSplitter: AutoSplitter by lazy {
        AutoSplitter(appContext, groups, expenses, signedIn = { auth.isSignedIn() }, clock = clock)
    }

    /**
     * Card bills with their card, payments and what friends owe (docs/card-bills.md 2.10). It waits until the session
     * is read, so a signed-in person never sees the signed-out view for a moment.
     */
    val cardBillReader: CardBillReader by lazy {
        CardBillReader(
            bills = cardBills,
            accounts = accounts,
            expenses = expenses,
            owedOn = { ids -> groups.observeOwedOn(ids) },
            signedIn = auth.state.filter { it != AuthState.Loading }.map { it is AuthState.SignedIn },
            settings = settings.settings,
            zone = ZONE,
        )
    }

    /** The automatic card bill reminder: a notification to the person only (docs/card-bills.md 2.7). */
    val cardBillReminders: CardBillReminders by lazy {
        CardBillReminders(appContext, cardBills, cardBillReader, settings.settings, clock)
    }

    /** Settles balances from payment and money-received SMS, and Pay by UPI (docs/server.md 9.5). */
    val settlementMatcher: SettlementMatcher by lazy {
        SettlementMatcher(appContext, groups, expenses, database.groupExtrasDao(), signedIn = { auth.isSignedIn() }, clock = clock)
    }

    /**
     * What runs after new SMS were read, before "Payment detected" notifications (`IncomingSmsWorker`), in this order:
     * a payment the matcher took as a repayment never reaches the auto splitter ([com.expenses.app.ingest.runNewPaymentsHooks]).
     */
    val newPaymentsHooks: List<NewPaymentsHook> by lazy { listOf(settlementMatcher, autoSplitter) }

    /** The current run of Review payments, kept until All sorted's Done (Undo after a restart, All sorted counts). */
    val reviewSession: ReviewSessionStore by lazy {
        ReviewSessionStore(PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile(REVIEW_SESSION_FILE) })
    }

    private val authHooks: AuthHooks = object : AuthHooks {
        override suspend fun flushOutbox(): Boolean = syncEngine.flush()

        override suspend fun clearGroupData() = localGroups.clearAll()

        override suspend fun switchAccount() {
            localGroups.switchAccount()
            push.forgetSentToken()
        }

        override suspend fun onSignedIn() {
            sync.requestSync()
            sync.schedulePeriodic()
            push.register()
        }

        override suspend fun onSignedOut() {
            sync.cancelAll()
            push.unregister()
        }

        override fun clearAppData() {
            appContext.getSystemService(ActivityManager::class.java).clearApplicationUserData()
        }
    }

    @Volatile private var lastForegroundSync = 0L

    /**
     * The app came to the foreground (or started): sync when signed in, at most once every 30 seconds
     * (docs/server.md 5.2). The push token is checked too, so a PUT that failed is sent again without a new process;
     * while the server accepted this token recently, that is a local read and nothing is sent. The profile is read
     * again from the server, so a change made on another phone shows, but at most once in 10 minutes (also across
     * processes).
     */
    fun onForeground() {
        val now = clock.millis()
        if (now - lastForegroundSync < FOREGROUND_SYNC_GAP_MS) return
        lastForegroundSync = now
        appScope.launch {
            if (auth.isSignedIn()) {
                sync.requestSync()
                launch { auth.refreshProfile(onlyIfDue = true) }
                push.register()
            }
        }
    }

    /** Constants of the container. */
    companion object {
        /** The one time zone of the app: India time, where bank SMS print their dates. */
        val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        private const val SETTINGS_FILE = "settings"
        private const val AUTH_FILE = "auth"
        private const val REVIEW_SESSION_FILE = "review_session"
        private const val FOREGROUND_SYNC_GAP_MS = 30_000L
    }
}
