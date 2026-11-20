package com.expenses.app.ui.screens.split

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.ADDED_FRIEND_KEY
import com.expenses.app.ui.nav.AddExpense
import com.expenses.app.ui.nav.AddFriend
import com.expenses.app.ui.nav.AddToExpenses
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.nav.CHOSEN_SPLIT_GROUP_KEY
import com.expenses.app.ui.nav.CREATED_GROUP_KEY
import com.expenses.app.ui.nav.ChoosePeople
import com.expenses.app.ui.nav.NewGroup
import com.expenses.app.ui.nav.RecurringSplitOffer
import com.expenses.app.ui.nav.SignInGate
import com.expenses.app.ui.nav.SplitAdded
import com.expenses.app.ui.nav.SplitPayment
import com.expenses.app.ui.nav.SplitTyped
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.rememberNavigateOnce
import kotlinx.serialization.json.Json

/**
 * Registers the split flow: Split it (for a payment, or for an expense typed in), Choose people, Split. Sorted. and
 * Same split?. Split it needs sign-in: signed out, Sign in opens first and Not now leaves the payment waiting
 * ([SignInGate]). Owned by the split-flow area (docs/groups-build-plan.md).
 *
 * Split. Sorted. and Same split? open in place of Split it, so Done goes back to the screen that opened it (Home,
 * Review payments, Expense detail, a Group page), past an Add to expenses of the same payment and past the Add expense
 * a typed split came from.
 */
fun NavGraphBuilder.splitDestinations(navController: NavController) {
    composable<SplitPayment> { entry ->
        SignInGate(navController, entry) {
            SplitRoute(navController, entry) { SplitSource.Payment(it.toRoute<SplitPayment>().expenseId) }
        }
    }
    composable<SplitTyped> { entry ->
        SignInGate(navController, entry) {
            SplitRoute(navController, entry) { SplitSource.Typed(TypedExpense.decode(it.toRoute<SplitTyped>().draft)) }
        }
    }
    composable<ChoosePeople> { entry -> ChoosePeopleRoute(navController, entry) }
    composable<SplitAdded> { entry -> SplitAddedRoute(navController, entry) }
    composable<RecurringSplitOffer> { entry -> SameSplitRoute(navController, entry) }
}

@Composable
private fun SplitRoute(navController: NavController, entry: NavBackStackEntry, sourceOf: (NavBackStackEntry) -> SplitSource) {
    val source = remember(entry) { sourceOf(entry) }
    val viewModel = appViewModel { app -> SplitViewModel(source, app.groups, app.expenses, app.clock, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()

    // The group Choose people handed back.
    val chosen by entry.savedStateHandle.getStateFlow<String?>(CHOSEN_SPLIT_GROUP_KEY, null).collectAsStateWithLifecycle()
    LaunchedEffect(chosen) {
        val id = chosen ?: return@LaunchedEffect
        viewModel.onEvent(SplitEvent.GroupChosen(id))
        entry.savedStateHandle[CHOSEN_SPLIT_GROUP_KEY] = null
    }

    LaunchedEffect(done) {
        val result = done ?: return@LaunchedEffect
        if (!navController.isCurrent(entry)) return@LaunchedEffect
        val next: Any = if (result.sameSplitOffer && result.expenseId != null) {
            RecurringSplitOffer(result.expenseId)
        } else {
            SplitAdded(result.groupExpenseId, result.priorStatus?.name)
        }
        navController.openInPlaceOfSplit(entry, next)
        viewModel.doneHandled()
    }

    SplitScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                SplitEvent.Back -> navController.leave(entry)
                SplitEvent.NewGroup -> once {
                    val selected = (state as? SplitUiState.Form)?.chosenGroupId
                    navController.navigate(ChoosePeople(selected)) { launchSingleTop = true }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun ChoosePeopleRoute(navController: NavController, entry: NavBackStackEntry) {
    val viewModel = appViewModel { app ->
        val saved = createSavedStateHandle()
        ChoosePeopleViewModel(saved.toRoute<ChoosePeople>().selectedGroupId, app.groups, saved)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()

    // A friend from Add friend, and the group New group made.
    val added by entry.savedStateHandle.getStateFlow<String?>(ADDED_FRIEND_KEY, null).collectAsStateWithLifecycle()
    LaunchedEffect(added) {
        val json = added ?: return@LaunchedEffect
        runCatching { FriendJson.decodeFromString(AddedFriend.serializer(), json) }.getOrNull()?.let {
            viewModel.onEvent(ChoosePeopleEvent.FriendAdded(it))
        }
        entry.savedStateHandle[ADDED_FRIEND_KEY] = null
    }
    val created by entry.savedStateHandle.getStateFlow<String?>(CREATED_GROUP_KEY, null).collectAsStateWithLifecycle()
    LaunchedEffect(created) {
        val id = created ?: return@LaunchedEffect
        viewModel.onEvent(ChoosePeopleEvent.GroupCreated(id))
        entry.savedStateHandle[CREATED_GROUP_KEY] = null
    }

    LaunchedEffect(chosen) {
        val id = chosen ?: return@LaunchedEffect
        if (!navController.isCurrent(entry)) return@LaunchedEffect
        navController.previousBackStackEntry?.savedStateHandle?.set(CHOSEN_SPLIT_GROUP_KEY, id)
        navController.leave(entry)
    }

    ChoosePeopleScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ChoosePeopleEvent.Back -> navController.leave(entry)
                ChoosePeopleEvent.MakeGroup -> once {
                    navController.navigate(NewGroup.with(viewModel.pickedKeys(), viewModel.pickedNewFriends())) { launchSingleTop = true }
                }
                ChoosePeopleEvent.AddSomeone -> once {
                    navController.navigate(AddFriend()) { launchSingleTop = true }
                }
                else -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun SplitAddedRoute(navController: NavController, entry: NavBackStackEntry) {
    // Opened from Add expense (a typed split) when the screen under it is Add expense.
    val typed = remember(entry) { navController.previousBackStackEntry?.destination?.hasRoute<AddExpense>() == true }
    val viewModel = appViewModel { app ->
        val route = createSavedStateHandle().toRoute<SplitAdded>()
        val prior = route.priorStatus?.let { name -> ExpenseStatus.entries.firstOrNull { it.name == name } }
        SplitAddedViewModel(route.groupExpenseId, typed, app.groups, app.expenses, prior)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == SplitAddedUiState.Undone) navController.leaveSplit(entry, undo = true, paymentId = null)
    }
    SplitAddedScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                SplitAddedEvent.Done ->
                    navController.leaveSplit(entry, undo = false, paymentId = (state as? SplitAddedUiState.Shown)?.paymentId)
                SplitAddedEvent.Undo -> viewModel.onEvent(event)
            }
        },
    )
}

@Composable
private fun SameSplitRoute(navController: NavController, entry: NavBackStackEntry) {
    val expenseId = entry.toRoute<RecurringSplitOffer>().expenseId
    val viewModel = appViewModel { app ->
        SameSplitViewModel(createSavedStateHandle().toRoute<RecurringSplitOffer>().expenseId, app.groups, app.expenses, app.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == SameSplitUiState.Saved || state == SameSplitUiState.Gone) {
            navController.leaveSplit(entry, undo = false, paymentId = expenseId)
        }
    }
    SameSplitScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                SameSplitEvent.Back -> navController.leaveSplit(entry, undo = false, paymentId = expenseId)
                else -> viewModel.onEvent(event)
            }
        },
    )
}

/**
 * Opens [next] (Split. Sorted. or Same split?) in place of Split it, [entry], the screen on top: Split it leaves the
 * stack, so Done and Back go to the screen that opened it. Split it is popped as the topmost entry of its destination
 * (`entry.id` is no route, and a route string that matches nothing pops nothing).
 */
internal fun NavController.openInPlaceOfSplit(entry: NavBackStackEntry, next: Any) {
    if (!isCurrent(entry)) return
    navigate(next) {
        popUpTo(entry.destination.id) { inclusive = true }
    }
}

/** Leaves Split. Sorted. or Same split? ([entry]) as [splitAddedScreensToLeave] says. */
internal fun NavController.leaveSplit(entry: NavBackStackEntry, undo: Boolean, paymentId: Long?) {
    if (!isCurrent(entry)) return
    val below = previousBackStackEntry
    val kind = when {
        below == null -> SplitAddedBelow.OTHER
        below.destination.hasRoute<AddExpense>() -> SplitAddedBelow.TYPED_FORM
        below.destination.hasRoute<AddToExpenses>() && paymentId != null && below.toRoute<AddToExpenses>().expenseId == paymentId ->
            SplitAddedBelow.SAME_PAYMENT_FORM
        else -> SplitAddedBelow.OTHER
    }
    repeat(splitAddedScreensToLeave(undo, kind)) { popBackStack() }
}

private val FriendJson = Json { ignoreUnknownKeys = true }
