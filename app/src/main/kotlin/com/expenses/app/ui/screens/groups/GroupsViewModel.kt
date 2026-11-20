package com.expenses.app.ui.screens.groups

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.auth.SignInResult
import com.expenses.app.groups.data.GroupsHome
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.LoadState
import com.expenses.app.groups.data.StoredGroups
import com.expenses.app.ui.format.shortName
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Groups tab asks its route to open. */
sealed interface GroupsEffect {
    /** Signed in with a new account: "Your name (after sign-in)" asks for the name. */
    data object OpenSignInName : GroupsEffect

    /** Sign-in did not work, or this build has no Google project: Sign in shows why (and Dev sign-in in debug). */
    data object OpenSignIn : GroupsEffect
}

/**
 * Holds the Groups tab (02-everyday/20, 21, 22, 23's card, 39, 44) from the data on this phone:
 * - signed out: Sign in (39), with "Continue with Google";
 * - signed in, while a sync runs and nothing is here yet, or a group's first data is still coming: the grey
 *   placeholders (22); never while no sync runs, so offline the tab shows what this phone has;
 * - nothing on this phone, or nothing to list and a split with one friend whose data did not come: the server's list
 *   of groups is fetched first (22 meanwhile). "No groups yet" (21) shows only once that list arrived without a group,
 *   or when every split with one friend on this phone is here and settled; when it could not be fetched, Could not
 *   load (23's card) with Try again shows in its place, since the person may well have groups or balances;
 * - otherwise the overall card ("OVERALL YOU ARE OWED", or "OVERALL YOU OWE" when below zero, 44), the named groups
 *   with the person's net in each, and the friends with a balance over every group (docs/server.md 6.4). Groups on
 *   this phone stay on show while a later sync fails.
 *
 * A list that could not be fetched is asked for again by itself when the tab is opened again ([onShown]), when the
 * phone comes back online, and when a sync in the background ended; Try again asks at once ([tryAgain]).
 *
 * Google's sheet needs the activity, which is passed in for the one call and not kept.
 *
 * @param authState who is signed in (`AuthRepository.state`).
 * @param groups the group data.
 * @param syncing true while a full sync runs (`SyncScheduler.observeSyncing()`).
 * @param online whether the phone is online now (`NetworkStatus.online`).
 * @param loadGroups fetches the person's groups from the server and stores them on this phone, waiting until that is
 * done; true when the server answered and everything was stored, false when it could not be reached or failed (one
 * `SyncEngine.sync()`).
 * @param signInWithGoogle signs in with Google's sheet, asked by the person (`auth.signInWithGoogle(activity, auto =
 * false)`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModel(
    authState: Flow<AuthState>,
    private val groups: GroupsRepository,
    private val syncing: Flow<Boolean>,
    private val online: StateFlow<Boolean>,
    private val loadGroups: suspend () -> Boolean,
    private val signInWithGoogle: suspend (Activity) -> SignInResult,
) : ViewModel() {
    /** What the tab knows of the server's list of the person's groups, for one sign-in. */
    private enum class ListLoad {
        /** Not fetched by this tab: groups on this phone are shown as they are. */
        Unknown,

        /** Being fetched now. */
        Loading,

        /** The server answered and what it listed is on this phone, be it nothing. */
        Loaded,

        /** It could not be fetched. */
        Failed,
    }

    /** Who uses the tab. A new [SignedIn] is made for every sign-in, so nothing known of one account reaches the next. */
    private sealed interface Who {
        /** The stored session is still being read. */
        data object Reading : Who

        /** Nobody is signed in. */
        data object SignedOut : Who

        /** Someone is signed in; [listLoad] is what is known of their list of groups. */
        class SignedIn(val listLoad: MutableStateFlow<ListLoad> = MutableStateFlow(ListLoad.Unknown)) : Who
    }

    private val effects = Channel<GroupsEffect>(Channel.BUFFERED)
    private var signingIn = false

    /** Asks for the list again; one waiting request is enough. */
    private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Watched for as long as the tab's state is kept, also while another tab shows: a sign-out and sign-in that happen
    // meanwhile must not leave the old account's "no groups" behind.
    private val who: StateFlow<Who> = authState
        .distinctUntilChanged { old, new -> old.signedIn == new.signedIn && (old is AuthState.Loading) == (new is AuthState.Loading) }
        .map { auth ->
            when (auth) {
                AuthState.Loading -> Who.Reading
                AuthState.SignedOut -> Who.SignedOut
                is AuthState.SignedIn -> Who.SignedIn()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Who.Reading)

    /** One-off navigation for the route. */
    val effect: Flow<GroupsEffect> = effects.receiveAsFlow()

    /** What the tab shows; null while the stored session is still being read. */
    val state: StateFlow<GroupsUiState?> = who
        .flatMapLatest { session ->
            when (session) {
                Who.Reading -> flowOf(null)
                Who.SignedOut -> flowOf(GroupsUiState.NotSignedIn)
                is Who.SignedIn -> {
                    // The groups this tab has shown complete since it started watching, for this sign-in.
                    val shownComplete = HashSet<String>()
                    // The stored data is read again each time the list's state changes, and the state before stays
                    // on show until that read has answered: when a fetch ends, what it stored is on show with its
                    // result, never "No groups yet" for the moment before the groups it brought are read.
                    session.listLoad.flatMapLatest { listLoad ->
                        combine(groups.observeHome(), syncing) { home, isSyncing ->
                            signedInState(home, isSyncing, listLoad, shownComplete)
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        viewModelScope.launch {
            who.collectLatest { session -> if (session is Who.SignedIn) keepListLoaded(session.listLoad) }
        }
    }

    /**
     * The tab came into view (it was opened, or the app came back to the front): a list that could not be fetched
     * is asked for again. Offline nothing is asked: coming back online asks by itself.
     */
    fun onShown() {
        if (online.value) retryIfFailed()
    }

    /** "Try again" on Could not load: asks for the list again, whatever the phone says about the connection. */
    fun tryAgain() = retryIfFailed()

    private fun retryIfFailed() {
        val session = who.value as? Who.SignedIn ?: return
        if (session.listLoad.value == ListLoad.Failed) retries.tryEmit(Unit)
    }

    /** Why [keepListLoaded] looks at the list again. */
    private enum class Ask {
        /** What this phone holds changed while nothing could be listed. */
        Changed,

        /** A retry of a fetch that failed. */
        Again,
    }

    /**
     * Keeps [load] right for one sign-in, until it is cancelled (sign-out). While nothing can be listed and this phone
     * cannot tell that there is nothing ([listNeeded]: it holds no group, or a group whose data did not come), the
     * server's list is fetched: at the start, when the last group on this phone went, when a group whose data did not
     * come was stored by a sync in the background, and on every retry. What a fetch stored itself asks nothing more.
     * Fetches run one at a time.
     */
    private suspend fun keepListLoaded(load: MutableStateFlow<ListLoad>) = coroutineScope {
        launch {
            // Back online after a failed fetch.
            online.collect { isOnline -> if (isOnline && load.value == ListLoad.Failed) retries.tryEmit(Unit) }
        }
        launch {
            // A sync in the background ended after a failed fetch: it may have reached the server.
            var wasSyncing = false
            syncing.collect { isSyncing ->
                if (wasSyncing && !isSyncing && load.value == ListLoad.Failed && online.value) retries.tryEmit(Unit)
                wasSyncing = isSyncing
            }
        }
        // What the phone held, while nothing could be listed, when the last fetch ended: the database tells of a
        // change on its own threads, so the groups a fetch stored can be told after it ended. They ask nothing more.
        var afterFetch: StoredGroups? = null
        val changes = groups.observeHome().map(::unknownStored).distinctUntilChanged().map { Ask.Changed }
        merge(changes, retries.map { Ask.Again }).collect { ask ->
            val home = groups.home()
            val now = unknownStored(home)
            if (now == null) afterFetch = null
            if (ask == Ask.Changed && now == afterFetch) return@collect
            // Fetched already, or groups are here to show, or only settled splits with one friend: nothing to ask.
            if (!listNeeded(home, load.value)) return@collect
            load.value = ListLoad.Loading
            val loaded = try {
                loadGroups()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            afterFetch = unknownStored(groups.home())
            // An answer that left a group's data missing, with nothing to list, did not bring the list either.
            load.value = if (loaded && afterFetch != StoredGroups.INCOMPLETE) ListLoad.Loaded else ListLoad.Failed
        }
    }

    /** What this phone holds while nothing can be listed and it cannot tell that there is nothing; null otherwise. */
    private fun unknownStored(home: GroupsHome): StoredGroups? =
        home.stored.takeIf { home.nothingToList && it != StoredGroups.COMPLETE }

    /**
     * True when the server's list is needed: nothing can be listed, and this phone holds a group whose data did not
     * come, or no group while the list was not fetched.
     */
    private fun listNeeded(home: GroupsHome, listLoad: ListLoad): Boolean = when (unknownStored(home)) {
        null, StoredGroups.COMPLETE -> false
        StoredGroups.NONE -> listLoad != ListLoad.Loaded
        StoredGroups.INCOMPLETE -> true
    }

    /**
     * "Continue with Google" on 39. Success with a new account opens Your name; a failure, or a build without a Google
     * project, opens Sign in, which says why; the person closing the sheet changes nothing. A second tap while the
     * sheet is up is ignored.
     */
    fun continueWithGoogle(activity: Activity) {
        if (signingIn) return
        signingIn = true
        viewModelScope.launch {
            try {
                when (val result = signInWithGoogle(activity)) {
                    is SignInResult.Success -> if (result.isNew) effects.send(GroupsEffect.OpenSignInName)
                    SignInResult.Failed, SignInResult.NotConfigured -> effects.send(GroupsEffect.OpenSignIn)
                    SignInResult.Cancelled -> Unit
                }
            } finally {
                signingIn = false
            }
        }
    }

    /**
     * The tab for someone signed in. The placeholders (22) show only while a sync runs, or while the server's list is
     * fetched because nothing is here: outside those (offline, or a pull cut off) every group shows what this phone
     * has, so the tab never waits for a sync that is not running ("Splits sync when you are back online", 03). During
     * a sync they show while nothing is here yet, or while a group this tab has not shown complete is still coming
     * (the first pull after sign-in or a join); a group already shown keeps its row while a later change of it is
     * pulled, so the list does not flash.
     *
     * With nothing to list, "No groups yet" (21) needs to be known: this phone holds groups whose data is all here
     * (only settled splits with one friend, which are not listed), or it holds none and the server's list arrived
     * empty. A split with one friend whose data did not come may still have a balance, so it is not known then. When
     * the list could not be fetched, Could not load shows; until it is known either way, the placeholders.
     *
     * @param home what this phone holds, from one read.
     * @param listLoad what is known of the server's list. While it is fetched the tab is as during a sync.
     * @param shownComplete the groups shown complete so far; the complete groups of [home] are added to it.
     */
    private fun signedInState(home: GroupsHome, isSyncing: Boolean, listLoad: ListLoad, shownComplete: MutableSet<String>): GroupsUiState {
        val named = home.groups
        val friends = home.friends
        named.forEach { if (it.load == LoadState.READY) shownComplete += it.id }
        val nothingHere = named.isEmpty() && friends.isEmpty()
        val firstDataComing = named.any { it.load == LoadState.LOADING && it.id !in shownComplete }
        val fetching = isSyncing || listLoad == ListLoad.Loading
        return when {
            fetching && (nothingHere || firstDataComing) -> GroupsUiState.Loading
            nothingHere -> when {
                home.stored == StoredGroups.COMPLETE -> GroupsUiState.Empty
                home.stored == StoredGroups.NONE && listLoad == ListLoad.Loaded -> GroupsUiState.Empty
                listLoad == ListLoad.Failed -> GroupsUiState.CouldNotLoad
                else -> GroupsUiState.Loading
            }
            else -> GroupsUiState.Loaded(
                groups = named.map { group ->
                    GroupBalance(
                        id = group.id,
                        name = group.name,
                        memberCount = group.memberCount,
                        balancePaise = group.myNetMinor,
                        avatarColor = group.avatar,
                    )
                },
                friends = friends.map { friend ->
                    FriendBalance(
                        id = friend.key,
                        // First names, as 20 writes them; whole names where two friends share one.
                        name = shortName(friend.name, friends.map { it.name }),
                        balancePaise = friend.netMinor,
                        // The same colour as on the Group page and the settle screens.
                        avatarColor = friend.avatar,
                        groupId = friend.directGroupId,
                    )
                },
                overallPaise = home.totals.overallMinor,
                groupCount = home.totals.groupCount,
            )
        }
    }

    private val AuthState.signedIn: Boolean get() = this is AuthState.SignedIn

    private companion object {
        /** How long the reads stay active after the screen stops watching, so a rotation does not restart them. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
