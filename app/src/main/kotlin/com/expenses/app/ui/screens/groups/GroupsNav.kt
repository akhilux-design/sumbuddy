package com.expenses.app.ui.screens.groups

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.di.appViewModel
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.app.ui.components.MainTab
import com.expenses.app.ui.nav.ADDED_FRIEND_KEY
import com.expenses.app.ui.nav.AddFriend
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.nav.CREATED_GROUP_KEY
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.ImportSplitwise
import com.expenses.app.ui.nav.NewGroup
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SignInName
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.nav.mainTab
import com.expenses.app.ui.nav.rememberNavigateOnce
import com.expenses.app.ui.screens.onboarding.CollectEffects
import com.expenses.app.ui.theme.LocalAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Registers the Groups tab, New group and Add friend. Owned by the groups-home area (docs/groups-build-plan.md 4.1). */
fun NavGraphBuilder.groupsDestinations(navController: NavController) {
    mainTab<Groups>(MainTab.Groups, navController) { padding ->
        GroupsRoute(contentPadding = padding, navController = navController)
    }
    composable<NewGroup> { entry -> NewGroupRoute(navController, entry) }
    composable<AddFriend> { entry -> AddFriendRoute(navController, entry) }
}

/**
 * The Groups tab with its ViewModel. A group row, and a friend with a direct split, open that Group page; "+ New" and
 * "New group" open New group. Signed out, "Continue with Google" signs in right here: a new account then gives its
 * name on Your name, and a failure opens Sign in, which says what went wrong. Could not load asks for the groups
 * again with Try again, and by itself each time the tab comes into view.
 *
 * @param contentPadding the space to keep clear of the status bar and the bottom navigation.
 * @param navController moves to the screens the tab opens.
 */
@Composable
private fun GroupsRoute(contentPadding: PaddingValues, navController: NavController) {
    val viewModel = appViewModel { app ->
        GroupsViewModel(
            authState = app.auth.state,
            groups = app.groups,
            syncing = app.sync.observeSyncing(),
            online = app.network.online,
            // One full sync, waited for, off the main thread: it stores the server's list and says whether
            // everything arrived.
            loadGroups = { withContext(app.dispatchers.default) { app.syncEngine.sync() == SyncOutcome.DONE } },
            signInWithGoogle = { activity -> app.auth.signInWithGoogle(activity, auto = false) },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val once = rememberNavigateOnce()
    // The tab is opened again, or the app is back in front: groups that could not be fetched are asked for again.
    LifecycleStartEffect(viewModel) {
        viewModel.onShown()
        onStopOrDispose { }
    }
    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            GroupsEffect.OpenSignInName -> navController.navigate(SignInName()) { launchSingleTop = true }
            GroupsEffect.OpenSignIn -> navController.navigate(SignIn()) { launchSingleTop = true }
        }
    }
    state?.let { shown ->
        GroupsScreen(
            state = shown,
            contentPadding = contentPadding,
            onEvent = { event ->
                when (event) {
                    GroupsEvent.NewGroupClicked -> once { navController.navigate(NewGroup()) { launchSingleTop = true } }
                    is GroupsEvent.GroupClicked -> once {
                        navController.navigate(GroupPage(event.groupId)) { launchSingleTop = true }
                    }
                    is GroupsEvent.FriendClicked -> once {
                        navController.navigate(GroupPage(event.groupId)) { launchSingleTop = true }
                    }
                    GroupsEvent.ContinueWithGoogle -> activity?.let(viewModel::continueWithGoogle)
                    GroupsEvent.TryAgainClicked -> viewModel.tryAgain()
                }
            },
        )
    }
}

/**
 * New group with its ViewModel. "Add someone" opens Add friend, whose friend comes back under [ADDED_FRIEND_KEY].
 * Once made, the group's id is set under [CREATED_GROUP_KEY] on the screen below; opened from the Groups tab, the new
 * group's page opens in place of New group, otherwise New group leaves (Split it and Choose people take the id).
 * Opened from the Groups tab with nobody filled in, "Import from Splitwise instead" opens Import from Splitwise
 * ([offersImport]); from a split it is not shown (142).
 */
@Composable
private fun NewGroupRoute(navController: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<NewGroup>()
    // Read while New group is the screen on top (its opener is right under it), and kept for as long as it is shown.
    val showImport = rememberSaveable { navController.offersImport(route) }
    val viewModel = appViewModel { app -> NewGroupViewModel(app.groups, createSavedStateHandle(), route.peopleKeys, route.newFriends) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val once = rememberNavigateOnce()
    val haptics = LocalAppHaptics.current
    var focusName by rememberSaveable { mutableStateOf(true) }
    val added by entry.savedStateHandle.getStateFlow<String?>(ADDED_FRIEND_KEY, null).collectAsStateWithLifecycle()
    LaunchedEffect(added) {
        val json = added ?: return@LaunchedEffect
        runCatching { Json.decodeFromString(AddedFriend.serializer(), json) }.getOrNull()?.let(viewModel::onFriendAdded)
        entry.savedStateHandle[ADDED_FRIEND_KEY] = null
    }
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            is NewGroupEffect.Created -> {
                // Something the person started is done: the group is made (`haptic/confirm`).
                haptics.confirm()
                navController.afterGroupCreated(entry, effect.groupId)
            }
            NewGroupEffect.SignedOut -> navController.navigate(SignIn()) { launchSingleTop = true }
        }
    }
    NewGroupScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                NewGroupEvent.Back -> navController.leave(entry)
                NewGroupEvent.AddSomeone -> once { navController.navigate(AddFriend()) { launchSingleTop = true } }
                NewGroupEvent.ImportFromSplitwise -> once { navController.navigate(ImportSplitwise) { launchSingleTop = true } }
                else -> viewModel.onEvent(event)
            }
        },
        showImport = showImport,
        focusName = focusName && state.name.isEmpty(),
        onNameFocused = { focusName = false },
    )
}

/**
 * Add friend with its ViewModel. Opened without a group (New group, Choose people), the friend goes back to the screen
 * below as an [AddedFriend] under [ADDED_FRIEND_KEY]; opened for a group (Group settings), the friend is added to it.
 * Either way the screen then leaves.
 *
 * "Pick from contacts" opens the system's contact picker for phone numbers. Only the picked row's name and number
 * are read, through the one-time access the picker grants; the app has no contacts permission, and nothing else is
 * read or sent anywhere.
 */
@Composable
private fun AddFriendRoute(navController: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<AddFriend>()
    val viewModel = appViewModel { app -> AddFriendViewModel(route.groupId, app.groups, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var focusName by rememberSaveable { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPickedContact(resolver, uri) }
            if (picked != null) viewModel.onEvent(AddFriendEvent.ContactPicked(picked.name, picked.phone))
        }
    }
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            is AddFriendEffect.HandBack -> navController.handBackFriend(entry, effect.friend)
            AddFriendEffect.Added -> navController.leave(entry)
        }
    }
    AddFriendScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                AddFriendEvent.Back -> navController.leave(entry)
                AddFriendEvent.PickFromContacts -> try {
                    picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                } catch (_: ActivityNotFoundException) {
                    // No contacts app on this phone: the fields stay to be typed in.
                }
                else -> viewModel.onEvent(event)
            }
        },
        focusName = focusName && state.name.isEmpty(),
        onNameFocused = { focusName = false },
    )
}

/**
 * New group [entry] made the group [groupId]: the id goes to the screen below under [CREATED_GROUP_KEY]. When that
 * screen is the Groups tab, the new group's page opens in place of New group; otherwise New group leaves, and the
 * screen below (Split it, Choose people) takes the id. Does nothing unless [entry] is on top.
 */
internal fun NavController.afterGroupCreated(entry: NavBackStackEntry, groupId: String) {
    if (!isCurrent(entry)) return
    val opener = previousBackStackEntry
    opener?.savedStateHandle?.set(CREATED_GROUP_KEY, groupId)
    if (opener?.destination?.hasRoute<Groups>() == true) {
        navigate(GroupPage(groupId)) { popUpTo<NewGroup> { inclusive = true } }
    } else {
        leave(entry)
    }
}

/**
 * True when New group [route], the screen on top, offers "Import from Splitwise instead" (docs/splitwise-import.md
 * 5.9, decision 15): it was opened from the Groups tab with nobody filled in. From a split (Choose people's "Make a
 * group", 142) it is not offered, so an import never breaks the split's way back.
 */
internal fun NavController.offersImport(route: NewGroup): Boolean =
    route.peopleKeys.isEmpty() && route.newFriends.isEmpty() && previousBackStackEntry?.destination?.hasRoute<Groups>() == true

/**
 * Add friend [entry] hands [friend] to the screen below (New group, Choose people) as JSON under [ADDED_FRIEND_KEY]
 * and leaves. Does nothing unless [entry] is on top.
 */
internal fun NavController.handBackFriend(entry: NavBackStackEntry, friend: AddedFriend) {
    if (!isCurrent(entry)) return
    previousBackStackEntry?.savedStateHandle?.set(ADDED_FRIEND_KEY, Json.encodeToString(AddedFriend.serializer(), friend))
    leave(entry)
}

/** The name and number of a contact picked with the system's picker. */
private class PickedContact(val name: String?, val phone: String?)

/**
 * Reads the display name and the number of the one phone row [uri] the picker returned, or null when it cannot be
 * read. Nothing else of the contact is read.
 */
private fun readPickedContact(resolver: ContentResolver, uri: Uri): PickedContact? = try {
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    resolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        PickedContact(name = cursor.getString(0), phone = cursor.getString(1))
    }
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
