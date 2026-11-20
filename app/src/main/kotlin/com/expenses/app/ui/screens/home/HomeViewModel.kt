package com.expenses.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseWithAccount
import com.expenses.app.data.model.payee
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.settings.AppSettings
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.data.FamilyInfo
import com.expenses.app.groups.data.FamilyMonth
import com.expenses.app.groups.data.FamilyRepository
import com.expenses.app.groups.data.FamilyState
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.SplitLabel
import com.expenses.app.ui.components.SelfAvatarColor
import com.expenses.app.ui.format.payeeWording
import com.expenses.app.ui.screens.activity.toActivityItem
import com.expenses.app.ui.screens.you.turnSmsTrackingOn
import com.expenses.app.ui.time.minuteTicks
import com.expenses.app.ui.time.months
import com.expenses.parser.Direction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the Home tab: the greeting with the person's name and today's date, this month's spending, the oldest
 * payment that waits for Add, Split or Ignore, and the latest expenses. On the Family side without a family it is the
 * person's own Family expenses on this phone, at their full amount, with the Set up Family card; with a family it is
 * the family month (docs/family.md 1.5): its total, By person, Top categories and Latest, and the card while an invite
 * is out or the person is alone.
 *
 * Splitting shows only while someone is signed in: "Owed to you" and "You owe" over the person's groups and direct
 * splits (left out when they split with nobody, and in manual mode, as 02 draws), "Last time you split Swiggy with Flat 302." under a payment whose
 * merchant has a remembered split, and the offline banner while the phone has no connection (splits wait for it; the
 * person's own expenses do not). Latest rows of split payments read "Travel · your share of ₹558" with the group.
 *
 * The content follows how the person tracks: manual mode (SMS access was not given) shows the tip that offers
 * automatic tracking until "Not now" hides it for the rest of the session; with SMS tracking and nothing found or
 * added yet, the fresh start empty state is shown.
 *
 * @param expenses the expenses.
 * @param settings the person's settings: name and tracking mode.
 * @param clock gives today, the month and the time zone.
 * @param groups the group data: totals, remembered splits and what each split payment is part of.
 * @param authState who is signed in (`AuthRepository.state`).
 * @param online true while the phone can reach the internet (`NetworkStatus.online`).
 * @param family the family on this phone: with one, the Family side shows the family month (docs/family.md 1.5);
 * without, the person's own Family expenses with the Set up Family card.
 * @param homeView the side of the Me or Family toggle, app-scoped (`AppContainer.homeView`) so Join Family, Set up
 * Family and the joined notification can open Home on the Family side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val expenses: ExpenseRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    groups: GroupsRepository,
    authState: Flow<AuthState>,
    online: Flow<Boolean>,
    family: FamilyRepository,
    private val homeView: MutableStateFlow<HomeView> = MutableStateFlow(HomeView.Me),
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    /** "Now", every minute, so the month, today's date and "5 min ago" stay right while Home stays open. */
    private val now = minuteTicks(clock).shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 1)

    /** The current month with its spending, read again when the month changes. */
    private val monthData: Flow<MonthData> = now.months(clock).flatMapLatest { month ->
        combine(expenses.observeMonthTotal(month), expenses.observeMonth(month)) { spent, added ->
            MonthData(month, spent, added)
        }
    }

    private val timed = combine(now, monthData) { instant, data -> instant to data }

    /** The payments that wait, oldest first. */
    private val waiting = expenses.observeWaiting()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 1)

    /** What each split payment is part of, by group expense id. */
    private val splitLabels = groups.observeSplitLabels()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 1)

    private val lists = combine(waiting, expenses.observeLatest(LATEST_LIMIT), splitLabels) { detected, latest, labels ->
        Lists(detected, latest, labels)
    }

    /**
     * The name of the group the oldest waiting payment's merchant was split with last time ("Do this every time"), or
     * null when there is no such rule or its group is gone. A direct split reads as the friend's name.
     */
    private val lastSplitWith: Flow<String?> = waiting
        .map { it.firstOrNull()?.expense?.counterparty }
        .distinctUntilChanged()
        .flatMapLatest { counterparty -> groups.splitRuleFor(counterparty) }
        .map { it?.groupId }
        .distinctUntilChanged()
        .flatMapLatest { groupId -> groupId?.let { id -> groups.observeGroup(id).map { it?.name } } ?: flowOf(null) }

    /** What splitting adds to Home; nothing while signed out. */
    private val splits: Flow<SplitData> = authState
        .map { it is AuthState.SignedIn }
        .distinctUntilChanged()
        .flatMapLatest { signedIn ->
            if (!signedIn) {
                flowOf(SplitData())
            } else {
                combine(groups.observeTotals(), splitLabels, lastSplitWith, online) { totals, labels, lastGroup, isOnline ->
                    // In a named group, or a direct split: groupCount counts only named groups, and a direct split shows
                    // through its balance or its expenses.
                    val splitsWithSomeone = totals.groupCount > 0 || totals.owedToMeMinor != 0L || totals.iOweMinor != 0L ||
                        labels.values.any { it.groupKind == GroupKind.DIRECT }
                    SplitData(
                        totals = if (splitsWithSomeone) SplitTotals(totals.owedToMeMinor, totals.iOweMinor) else null,
                        lastSplitWith = lastGroup?.takeIf { it.isNotBlank() },
                        offline = !isOnline,
                    )
                }
            }
        }

    /** The family on this phone and its month, read again when the month changes; both null without a family. */
    private val familyData: Flow<FamilyData> = now.months(clock).flatMapLatest { month ->
        combine(family.observeFamily(), family.observeMonth(month)) { info, familyMonth -> FamilyData(info, familyMonth) }
    }

    /** The toggle side, what only this screen decides, and the family. */
    private val side = combine(homeView, local, familyData) { view, local, familyData -> Side(view, local, familyData) }

    /** What the tab shows; null until the first read from the database and the settings. */
    val state: StateFlow<HomeUiState?> = combine(
        settings.settings,
        timed,
        lists,
        side,
        splits,
    ) { appSettings, (instant, thisMonth), shown, side, split ->
        uiState(appSettings, instant, thisMonth, shown, side, split)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * Handles the events that change data or what the tab shows: the Me or Family toggle, Ignore, and "Not now" on
     * the tip. Navigation (Add, Split, rows, the spent card, the split tiles) is handled by the caller.
     */
    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.ViewChanged -> homeView.value = event.view
            is HomeEvent.IgnoreDetectedPayment -> viewModelScope.launch { expenses.ignore(event.paymentId) }
            HomeEvent.DismissTrackingTip -> local.update { it.copy(tipDismissed = true) }
            else -> Unit
        }
    }

    /** Turns automatic tracking on from the tip when SMS access is already allowed. */
    fun onSmsAccessGranted() {
        viewModelScope.launch { settings.turnSmsTrackingOn(clock) }
    }

    private fun uiState(
        appSettings: AppSettings,
        now: Instant,
        thisMonth: MonthData,
        lists: Lists,
        side: Side,
        split: SplitData,
    ): HomeUiState {
        val user = HomeUser(
            firstName = appSettings.userName?.substringBefore(' ')?.takeIf { it.isNotBlank() },
            avatarColor = SelfAvatarColor,
        )
        val today = now.atZone(clock.zone).toLocalDate()
        val info = side.family.info
        if (side.view == HomeView.Family && info != null) {
            return familyMonthState(user, today, thisMonth.month, info, side.family.month, split.offline)
        }
        val local = side.local
        val detected = lists.detected
        val latest = lists.latest
        val family = side.view == HomeView.Family
        val familyMonth = thisMonth.added.filter { it.expense.scope == ExpenseScope.FAMILY }
        val shownLatest = if (family) familyMonth.take(LATEST_LIMIT) else latest
        val latestItems = shownLatest.map { it.toActivityItem(lists.splitLabels) }
        val content = when {
            // 02 draws no split tiles, also for someone who splits (its Latest has a Flat 302 share).
            appSettings.trackingMode == TrackingMode.MANUAL -> HomeContent.Manual(
                showTrackingTip = !local.tipDismissed,
                latest = latestItems,
            )
            !family && detected.isEmpty() && latest.isEmpty() -> HomeContent.StartFresh
            else -> HomeContent.Tracking(
                // The waiting list is oldest first, as on Review payments; the oldest waits longest, so it is shown.
                detected = detected.firstOrNull()?.toDetectedPayment(now, detected.size - 1, split.lastSplitWith),
                splitTotals = split.totals,
                latest = latestItems,
            )
        }
        return HomeUiState(
            user = user,
            today = today,
            view = side.view,
            month = thisMonth.month,
            spentMinor = if (family) familyMonth.sumOf { it.expense.familySpend() } else thisMonth.spentMinor,
            offline = split.offline,
            content = content,
            // No family on this phone: the person's own Family expenses, and the card that sets one up (51).
            familyCard = if (family) FamilyCard.SetUp else null,
            familyCaption = if (family) FamilyCaption.Local else null,
        )
    }

    /**
     * The Family side with a family (06-family/02 to 07): the family month's total and count, the card while an invite
     * is out or the person is alone, and By person, Top categories and Latest. While the month loads for the first time
     * no card shows: who is in is not known yet.
     */
    private fun familyMonthState(
        user: HomeUser,
        today: LocalDate,
        month: YearMonth,
        info: FamilyInfo,
        familyMonth: FamilyMonth?,
        offline: Boolean,
    ): HomeUiState {
        val count = familyMonth?.count ?: 0
        val partner = info.partner
        val content = HomeContent.FamilyMonth(
            load = info.load,
            people = familyMonth?.people.orEmpty(),
            topCategories = familyMonth?.topCategories.orEmpty(),
            latest = familyMonth?.latest.orEmpty(),
        )
        return HomeUiState(
            user = user,
            today = today,
            view = HomeView.Family,
            month = month,
            spentMinor = familyMonth?.totalMinor ?: 0,
            offline = offline,
            content = content,
            familyCard = when {
                content.loading -> null
                info.state == FamilyState.WAITING -> FamilyCard.Waiting
                info.state == FamilyState.ALONE -> FamilyCard.Invite(info.name)
                else -> null
            },
            familyCaption = if (partner != null) {
                FamilyCaption.Together(info.me.shortName, partner.shortName, count)
            } else {
                FamilyCaption.Alone(count)
            },
        )
    }

    /** What a Family expense adds to the family's month: the whole amount of an Indian rupee debit. */
    private fun Expense.familySpend(): Long =
        if (direction == Direction.DEBIT && currency == Expense.INR) amountMinor else 0

    private fun ExpenseWithAccount.toDetectedPayment(
        now: Instant,
        othersWaiting: Int,
        lastSplitWith: String?,
    ): DetectedPayment = DetectedPayment(
        id = expense.id,
        merchant = expense.payee,
        payeeWording = expense.payeeWording(),
        amountMinor = expense.amountMinor,
        accountLast4 = account?.last4,
        seenAt = seenAt(expense, now),
        lastSplitWith = lastSplitWith,
        othersWaiting = othersWaiting,
    )

    private fun seenAt(expense: Expense, now: Instant): SeenAt {
        val seen = expense.smsReceivedAt ?: expense.occurredAt
        val ago = Duration.between(seen, now)
        val seenDay = seen.atZone(clock.zone).toLocalDate()
        return when {
            ago < Duration.ofMinutes(1) -> SeenAt.Now
            ago < Duration.ofHours(1) -> SeenAt.MinutesAgo(ago.toMinutes().toInt())
            seenDay == now.atZone(clock.zone).toLocalDate() -> SeenAt.HoursAgo(ago.toHours().toInt())
            else -> SeenAt.OnDate(seenDay)
        }
    }

    /** The current month, its counted spending and its added expenses, newest first. */
    private data class MonthData(val month: YearMonth, val spentMinor: Long, val added: List<ExpenseWithAccount>)

    /** The waiting payments (oldest first), the latest expenses and what each split payment is part of. */
    private data class Lists(
        val detected: List<ExpenseWithAccount>,
        val latest: List<ExpenseWithAccount>,
        val splitLabels: Map<String, SplitLabel>,
    )

    /**
     * What splitting adds to Home.
     *
     * @param totals the split tiles, or null to leave them out.
     * @param lastSplitWith the group the waiting payment's merchant was split with last time, or null.
     * @param offline true while signed in with no connection: the offline banner shows.
     */
    private data class SplitData(val totals: SplitTotals? = null, val lastSplitWith: String? = null, val offline: Boolean = false)

    /** What only this screen decides: whether the tip was put away this session. */
    private data class LocalState(val tipDismissed: Boolean = false)

    /** The family on this phone ([info]) and its month this month; both null without a family. */
    private data class FamilyData(val info: FamilyInfo?, val month: FamilyMonth?)

    /** The toggle side, what only this screen decides, and the family. */
    private data class Side(val view: HomeView, val local: LocalState, val family: FamilyData)

    private companion object {
        /** How many of the newest expenses Home lists. */
        const val LATEST_LIMIT = 5

        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
