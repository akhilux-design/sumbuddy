package com.expenses.app.ui.screens.grouppage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.ExpensesApplication
import com.expenses.app.di.appViewModel
import com.expenses.app.groups.sync.LiveGroup
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.AddExpense
import com.expenses.app.ui.nav.AddFriend
import com.expenses.app.ui.nav.ExpenseDetail
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.GroupSettings
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.MarkAsPaid
import com.expenses.app.ui.nav.RemindOnWhatsApp
import com.expenses.app.ui.nav.SettleUp
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SplitPayment
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.openFamilyMonth
import com.expenses.app.ui.nav.openTab
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.theme.LocalAppHaptics
import kotlinx.coroutines.flow.map

/**
 * Registers the Group page and Group settings. Owned by the group-page area (docs/groups-build-plan.md 4.2).
 */
fun NavGraphBuilder.groupPageDestinations(navController: NavController) {
    composable<GroupPage> { entry -> GroupPageRoute(navController, entry) }
    composable<GroupSettings> { entry -> GroupSettingsRoute(navController, entry) }
}

@Composable
private fun GroupPageRoute(navController: NavController, entry: NavBackStackEntry) {
    val groupId = entry.toRoute<GroupPage>().groupId
    val viewModel = appViewModel { app ->
        val saved = createSavedStateHandle()
        GroupPageViewModel(
            groupId = saved.toRoute<GroupPage>().groupId,
            groups = app.groups,
            rejectedChanges = app.rejectedChanges,
            expenses = app.expenses,
            online = app.network.online,
            sync = app.sync,
            clock = app.clock,
            saved = saved,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = (LocalContext.current.applicationContext as ExpensesApplication).container
    FamilyRedirect(navController, entry, groupId)
    // The group's live connection while the page is visible (docs/server.md 5.3).
    LiveGroup(app.liveRoom, groupId)
    // "Join undone" (41) shows until the person leaves the page for another screen.
    LeftPageEffect(navController, entry) { viewModel.onEvent(GroupPageEvent.Left) }
    val once = rememberNavigateOnce()
    val gone = state is GroupPageUiState.Gone
    LaunchedEffect(gone) {
        if (gone) navController.leaveForGroups(entry)
    }
    NoticeHaptics((state as? GroupPageUiState.Shown)?.notice)
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            if (!navController.isCurrent(entry)) return@collect
            val route: Any = when (effect) {
                is GroupPageEffect.RemindOnWhatsApp -> RemindOnWhatsApp(groupId, effect.memberId)
                is GroupPageEffect.ExpenseDetail -> ExpenseDetail(effect.expenseId)
                is GroupPageEffect.SplitPayment -> SplitPayment(effect.expenseId)
                GroupPageEffect.SignIn -> SignIn()
            }
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    GroupPageScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                GroupPageEvent.Back -> navController.leave(entry)
                GroupPageEvent.Add -> once { navController.navigate(AddExpense(groupId)) { launchSingleTop = true } }
                GroupPageEvent.OpenSettings -> once { navController.navigate(GroupSettings(groupId)) { launchSingleTop = true } }
                is GroupPageEvent.Settle -> once { navController.navigate(event.target.route(groupId)) { launchSingleTop = true } }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

/**
 * The haptic for a banner that a tap left on the page (`design/spec/motion.md`, Haptics): `haptic/confirm` for
 * "Reminder sent", `haptic/reject` for "No reminder sent", offline and "Could not do that now". It plays once when the
 * banner appears, not for a banner already there when the page opens, and not again when the page is rebuilt.
 */
@Composable
private fun NoticeHaptics(notice: GroupNotice?) {
    val haptics = LocalAppHaptics.current
    var heard by rememberSaveable { mutableStateOf(noticeKey(notice)) }
    LaunchedEffect(notice) {
        val key = noticeKey(notice)
        if (key == heard) return@LaunchedEffect
        heard = key
        when (notice) {
            is GroupNotice.Reminded -> haptics.confirm()
            GroupNotice.NotReminded, GroupNotice.Offline, GroupNotice.Failed -> haptics.reject()
            null -> Unit
        }
    }
}

/** A saveable name for [notice], so a rebuilt page knows the banner it already played for. */
private fun noticeKey(notice: GroupNotice?): String = when (notice) {
    null -> ""
    is GroupNotice.Reminded -> "reminded:" + notice.names.joinToString(",")
    GroupNotice.NotReminded -> "not-reminded"
    GroupNotice.Offline -> "offline"
    GroupNotice.Failed -> "failed"
}

@Composable
private fun GroupSettingsRoute(navController: NavController, entry: NavBackStackEntry) {
    val groupId = entry.toRoute<GroupSettings>().groupId
    val viewModel = appViewModel { app ->
        GroupSettingsViewModel(
            groupId = createSavedStateHandle().toRoute<GroupSettings>().groupId,
            groups = app.groups,
            online = app.network.online,
            sync = app.sync,
            writeScope = app.appScope,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    FamilyRedirect(navController, entry, groupId)
    val once = rememberNavigateOnce()
    val haptics = LocalAppHaptics.current
    // True once the person confirmed Leave or Delete: the group then leaving this phone is their action being done.
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val gone = state is GroupSettingsUiState.Gone
    LaunchedEffect(gone) {
        if (gone) {
            if (confirmed) haptics.confirm()
            navController.leaveForGroups(entry)
        }
    }
    val settingsNotice = (state as? GroupSettingsUiState.Shown)?.notice
    NoticeHaptics(settingsNotice)
    // A Leave or Delete that was refused (offline, failed) is not done; a later sync removing the group is not theirs.
    LaunchedEffect(settingsNotice) { if (settingsNotice != null) confirmed = false }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            if (!navController.isCurrent(entry)) return@collect
            when (effect) {
                GroupSettingsEffect.SignIn -> navController.navigate(SignIn()) { launchSingleTop = true }
            }
        }
    }
    GroupSettingsScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                GroupSettingsEvent.Back -> {
                    viewModel.onEvent(GroupSettingsEvent.NameDone)
                    navController.leave(entry)
                }
                GroupSettingsEvent.AddSomeone -> once { navController.navigate(AddFriend(groupId)) { launchSingleTop = true } }
                GroupSettingsEvent.Confirm -> {
                    confirmed = true
                    viewModel.onEvent(event)
                }
                // A friend with no link yet: Remind on WhatsApp makes it and sends it; the sheet closes.
                is GroupSettingsEvent.Remind -> {
                    viewModel.onEvent(event)
                    once { navController.navigate(RemindOnWhatsApp(groupId, event.memberId)) { launchSingleTop = true } }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

/**
 * Calls [onLeft] when the person leaves the screen of [entry] for another screen of the app: Back, a screen opened
 * from it, or another tab.
 *
 * It is not called while [entry] stays the screen on top: a theme change or a rotation (the screen is only drawn
 * again), the screen going off, or another app in front. A lifecycle stop by itself cannot tell these apart, so this
 * asks the back stack too. Two moments are watched, and [onLeft] is called at the first:
 *
 * - [entry] stops while another screen is on top. Navigation stops a screen it puts away before it stores that
 *   screen's saved state (a tab's screens are kept that way), so what [onLeft] changes is in the state that is kept.
 * - the screen leaves the composition while another screen is on top. A screen opened from this one covers it first
 *   and stops it only afterwards, when this effect may already be gone.
 *
 * @param navController the app's navigation.
 * @param entry the screen's own back stack entry.
 * @param onLeft called once each time the person leaves the screen.
 */
@Composable
internal fun LeftPageEffect(navController: NavController, entry: NavBackStackEntry, onLeft: () -> Unit) {
    val currentOnLeft by rememberUpdatedState(onLeft)
    DisposableEffect(navController, entry) {
        var told = false
        fun tellOnceIfLeft() {
            if (told || navController.isCurrent(entry)) return
            told = true
            currentOnLeft()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> tellOnceIfLeft()
                // On top again without having left the composition: the next time away is a new leave.
                Lifecycle.Event.ON_START -> told = false
                else -> Unit
            }
        }
        entry.lifecycle.addObserver(observer)
        onDispose {
            entry.lifecycle.removeObserver(observer)
            tellOnceIfLeft()
        }
    }
}

/**
 * A family is never shown as a group (docs/family.md 8.2): when [groupId] is the family on this phone (a "Meera joined
 * Home" notification of an older build, a link to its page), Home opens on the Family side in place of this screen.
 */
@Composable
private fun FamilyRedirect(navController: NavController, entry: NavBackStackEntry, groupId: String) {
    val app = (LocalContext.current.applicationContext as ExpensesApplication).container
    val isFamily by remember(groupId) { app.family.observeFamily().map { it?.groupId == groupId } }.collectAsStateWithLifecycle(false)
    LaunchedEffect(isFamily) {
        if (isFamily && navController.isCurrent(entry)) navController.openFamilyMonth()
    }
}

/** The settle-up screen for [this] in [groupId]. */
private fun SettleTarget.route(groupId: String): Any = when (this) {
    is SettleTarget.SettleUp -> SettleUp(groupId, memberId)
    is SettleTarget.MarkAsPaid -> MarkAsPaid(groupId, memberId)
}

/**
 * Leaves a screen of a group that is no longer on this phone for the Groups tab. The group's page (and Group settings
 * above it) are popped first: opening the tab restores its saved stack, which must never bring a gone group's page
 * back. A page restored from an older saved stack finds the group gone and pops itself the same way.
 */
private fun NavController.leaveForGroups(entry: NavBackStackEntry) {
    if (!isCurrent(entry)) return
    if (!popBackStack<GroupPage>(inclusive = true)) popBackStack()
    if (currentBackStackEntry?.destination?.hasRoute<Groups>() != true) openTab(MainTab.Groups)
}
