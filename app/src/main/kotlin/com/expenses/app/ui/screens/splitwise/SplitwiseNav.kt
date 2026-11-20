package com.expenses.app.ui.screens.splitwise

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.expenses.app.di.appViewModel
import com.expenses.app.ui.nav.GroupImported
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.Home
import com.expenses.app.ui.nav.ImportSplitwise
import com.expenses.app.ui.nav.NewGroup
import com.expenses.app.ui.nav.SignIn
import com.expenses.app.ui.nav.SignInGate
import com.expenses.app.ui.nav.isCurrent
import com.expenses.app.ui.nav.leave
import com.expenses.app.ui.screens.onboarding.CollectEffects
import com.expenses.app.ui.screens.settle.RemindShare

/**
 * Registers Import from Splitwise (signed in only, [SignInGate]) and Group imported (docs/splitwise-import.md 5.6).
 * Owned by the splitwise area.
 */
fun NavGraphBuilder.splitwiseDestinations(navController: NavController) {
    composable<ImportSplitwise> { entry ->
        SignInGate(navController, entry) { ImportSplitwiseRoute(navController, entry) }
    }
    composable<GroupImported> { entry -> GroupImportedRoute(navController, entry) }
}

/**
 * Import from Splitwise with its ViewModel. Choose file opens the system's document picker for any file
 * (`ACTION_OPEN_DOCUMENT`, `*` / `*`), so a wrong file can be named as one: no storage permission and no persistable
 * grant, and the Uri is read once and not kept. Once every part is sent, Group imported opens in place of this screen
 * (and of New group under it). "Open {group}", and Back while importing, open the group's page on the Groups tab; the
 * import goes on by itself.
 */
@Composable
private fun ImportSplitwiseRoute(navController: NavController, entry: NavBackStackEntry) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val viewModel = appViewModel { app ->
        ImportSplitwiseViewModel(
            groups = app.groups,
            readFile = { uri -> readPicked(resolver, uri, app.dispatchers.io) },
            profileName = { app.auth.profile()?.name },
            online = app.network.online,
            saved = createSavedStateHandle(),
            io = app.dispatchers.io,
            default = app.dispatchers.default,
            fileName = { uri -> pickedName(resolver, uri, app.dispatchers.io) },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri)
    }
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            ImportEffect.PickFile -> try {
                picker.launch(arrayOf(ANY_FILE))
            } catch (_: ActivityNotFoundException) {
                // No document picker on this phone: nothing can be picked, and the screen stays as it is.
            }
            is ImportEffect.OpenGroup -> navController.openOnGroupsTab(effect.groupId)
            is ImportEffect.OpenImported -> navController.openImported(entry, effect.groupId)
            ImportEffect.Leave -> navController.leave(entry)
            ImportEffect.SignIn -> navController.navigate(SignIn()) { launchSingleTop = true }
        }
    }
    BackHandler { viewModel.onEvent(ImportEvent.Back) }
    ImportSplitwiseScreen(state = state, onEvent = viewModel::onEvent)
}

/**
 * Group imported with its ViewModel. Share sends the one message from WhatsApp (else WhatsApp Business, else the share
 * sheet) and opens the group's page; Later and Back open it too, on the Groups tab.
 */
@Composable
private fun GroupImportedRoute(navController: NavController, entry: NavBackStackEntry) {
    val context = LocalContext.current
    val resources = context.applicationContext.resources
    val viewModel = appViewModel { app ->
        GroupImportedViewModel(
            groupId = createSavedStateHandle().toRoute<GroupImported>().groupId,
            groups = app.groups,
            online = app.network.online,
            message = { name, lines -> ImportInviteMessage.text(resources, name, lines) },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gone = state is GroupImportedUiState.Gone
    LaunchedEffect(gone) {
        if (gone && navController.isCurrent(entry)) navController.openOnGroupsTab(groupId = null)
    }
    CollectEffects(viewModel.effect) { effect ->
        if (!navController.isCurrent(entry)) return@CollectEffects
        when (effect) {
            is GroupImportedEffect.Share -> {
                RemindShare.send(context, effect.text)
                navController.openOnGroupsTab(effect.groupId)
            }
            is GroupImportedEffect.OpenGroup -> navController.openOnGroupsTab(effect.groupId)
            GroupImportedEffect.SignIn -> navController.navigate(SignIn()) { launchSingleTop = true }
        }
    }
    BackHandler { viewModel.onEvent(GroupImportedEvent.Later) }
    GroupImportedScreen(state = state, onEvent = viewModel::onEvent)
}

/**
 * Opens the Groups tab with nothing above Home under it, then group [groupId]'s page on top (when given), so Back from
 * the page lands on Groups (docs/splitwise-import.md 5.6). Whatever was above Home (You, New group, Import from
 * Splitwise, Group imported) goes.
 */
internal fun NavController.openOnGroupsTab(groupId: String?) {
    navigate(Groups) {
        popUpTo<Home>()
        launchSingleTop = true
    }
    if (groupId != null) navigate(GroupPage(groupId)) { launchSingleTop = true }
}

/**
 * The import of [entry] (Import from Splitwise) is done: Group imported opens in its place, and in place of a New group
 * right under it (opened from the Groups tab, 5.6). Does nothing unless [entry] is on top.
 */
internal fun NavController.openImported(entry: NavBackStackEntry, groupId: String) {
    if (!isCurrent(entry)) return
    val fromNewGroup = previousBackStackEntry?.destination?.hasRoute<NewGroup>() == true
    navigate(GroupImported(groupId)) {
        if (fromNewGroup) popUpTo<NewGroup> { inclusive = true } else popUpTo<ImportSplitwise> { inclusive = true }
        launchSingleTop = true
    }
}

/** Every kind of file: anything can be picked, and a wrong one is named as such (30). */
private const val ANY_FILE = "*/*"
