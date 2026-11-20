package com.expenses.app.ui.screens.groups

import android.app.Activity
import com.expenses.app.data.category.Category
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.auth.SignInResult
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.Person
import com.expenses.app.groups.data.SyncState
import com.expenses.splits.DirectIds
import com.expenses.splits.SplitMethod
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The Groups tab from real data: each design state, the overall card, friends, 39's sign-in outcomes, and what shows
 * while the server's list of groups is fetched, when it arrived empty and when it could not be fetched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroupsViewModelTest {
    private val env = GroupsTestEnv()
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ME_USER, "Arjun"))
    private val syncing = MutableStateFlow(false)
    private val online = MutableStateFlow(true)
    private var signInResult: SignInResult = SignInResult.Cancelled
    private var signInCalls = 0

    // The reads of the database answer on its own threads, so what the tab asks for and shows is written from them.

    /** How many times the tab fetched the server's list. */
    @Volatile private var loads = 0

    /** What a fetch does: true when the server answered. The start: it answers, with no groups. */
    @Volatile private var load: suspend () -> Boolean = { true }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.viewModel(): GroupsViewModel {
        val vm = GroupsViewModel(
            authState = auth,
            groups = env.groups,
            syncing = syncing,
            online = online,
            loadGroups = {
                loads++
                load()
            },
            signInWithGoogle = {
                signInCalls++
                signInResult
            },
        )
        backgroundScope.launch { vm.state.collect { seen += it } }
        return vm
    }

    /** Every state the tab went through, in order. */
    private val seen = CopyOnWriteArrayList<GroupsUiState?>()

    private suspend fun GroupsViewModel.shows(wanted: GroupsUiState) {
        state.first { it == wanted }
    }

    private suspend fun GroupsViewModel.loaded(): GroupsUiState.Loaded =
        state.first { it is GroupsUiState.Loaded } as GroupsUiState.Loaded

    /** [payer] paid [amount] in [groupId], split equally between [members]. */
    private suspend fun paid(groupId: String, payer: String, amount: Long, members: List<String>) {
        env.groups.addGroupExpense(
            groupId,
            GroupExpenseDraft("Dinner", Category.FOOD, amount, LocalDate.of(2026, 11, 12), payer, SplitMethod.EQUAL, members.associateWith { null }),
        )
    }

    @Test fun signedOutShowsSignInAndNothingShowsWhileTheSessionIsRead() = runTest {
        auth.value = AuthState.Loading
        val vm = viewModel()
        assertNull(vm.state.value)
        auth.value = AuthState.SignedOut
        vm.state.first { it == GroupsUiState.NotSignedIn }
    }

    @Test fun theFirstSyncShowsPlaceholdersThenNoGroupsYet() = runTest {
        syncing.value = true
        val vm = viewModel()
        vm.state.first { it == GroupsUiState.Loading }
        syncing.value = false
        vm.state.first { it == GroupsUiState.Empty }
    }

    // ---- The server's list of groups, while nothing is on this phone (21, 22 and the card of 23) ----

    @Test fun noGroupsYetShowsOnlyOnceTheListArrivedEmpty() = runTest {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        load = {
            asked.complete(Unit)
            answer.await()
        }
        val vm = viewModel()

        // No sync runs and nothing is stored, but the server has not answered: placeholders, not "No groups yet".
        asked.await()
        vm.shows(GroupsUiState.Loading)
        assertEquals(1, loads)
        assertFalse(GroupsUiState.Empty in seen)

        answer.complete(true)
        vm.shows(GroupsUiState.Empty)
        assertEquals(1, loads)
    }

    @Test fun aFetchThatFailedShowsCouldNotLoadAndNeverNoGroupsYet() = runTest {
        load = { false }
        val vm = viewModel()

        vm.shows(GroupsUiState.CouldNotLoad)
        assertFalse(GroupsUiState.Empty in seen)
        assertEquals(1, loads)
    }

    @Test fun aFetchThatThrowsCountsAsFailed() = runTest {
        load = { error("database is busy") }
        val vm = viewModel()

        vm.shows(GroupsUiState.CouldNotLoad)
    }

    @Test fun theGroupsAFetchBroughtShowWithoutNoGroupsYetInBetween() = runTest {
        load = {
            // What a sync does: the groups are stored by the time it says it is done.
            env.seedGroup(G1)
            true
        }
        val vm = viewModel()

        assertEquals(1, vm.loaded().groupCount)
        assertFalse(GroupsUiState.Empty in seen)
        assertFalse(GroupsUiState.CouldNotLoad in seen)
    }

    @Test fun tryAgainFetchesAgainAndShowsThePlaceholdersMeanwhile() = runTest {
        load = { false }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)

        val answer = CompletableDeferred<Boolean>()
        load = { answer.await() }
        vm.tryAgain()
        vm.shows(GroupsUiState.Loading)
        assertEquals(2, loads)
        // A second tap while it runs asks nothing more.
        vm.tryAgain()

        env.seedGroup(G1)
        answer.complete(true)
        assertEquals(1, vm.loaded().groupCount)
        assertEquals(2, loads)
        assertFalse(GroupsUiState.Empty in seen)
    }

    @Test fun tryAgainThatFailsAgainStaysOnCouldNotLoad() = runTest {
        load = { false }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)

        // Waited for by the fetch itself: a fetch that fails at once leaves the state as it was, with nothing new to
        // see in between.
        val asked = CompletableDeferred<Unit>()
        load = {
            asked.complete(Unit)
            false
        }
        vm.tryAgain()
        asked.await()
        vm.shows(GroupsUiState.CouldNotLoad)
        assertEquals(2, loads)
        assertFalse(GroupsUiState.Empty in seen)
    }

    @Test fun openingTheTabAgainFetchesAgainByItself() = runTest {
        load = { false }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)

        load = { true }
        vm.onShown()

        vm.shows(GroupsUiState.Empty)
        assertEquals(2, loads)
    }

    @Test fun openingTheTabAgainAsksNothingOnceTheListIsKnown() = runTest {
        val vm = viewModel()
        vm.shows(GroupsUiState.Empty)

        vm.onShown()
        vm.tryAgain()

        assertEquals(1, loads)
        assertEquals(GroupsUiState.Empty, vm.state.value)
    }

    @Test fun comingBackOnlineFetchesAgainByItself() = runTest {
        online.value = false
        load = { false }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)
        // Offline, opening the tab again asks nothing: it could only fail.
        vm.onShown()
        assertEquals(1, loads)

        load = {
            env.seedGroup(G1)
            true
        }
        online.value = true

        assertEquals(1, vm.loaded().groupCount)
        assertEquals(2, loads)
        assertFalse(GroupsUiState.Empty in seen)
    }

    @Test fun tryAgainAsksEvenWhenThePhoneSaysItIsOffline() = runTest {
        online.value = false
        load = { false }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)

        load = { true }
        vm.tryAgain()

        vm.shows(GroupsUiState.Empty)
        assertEquals(2, loads)
    }

    @Test fun aSyncInTheBackgroundThatEndedFetchesAgain() = runTest {
        load = { false }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)

        // WorkManager's retry runs: placeholders while it does, then the tab asks for itself what it brought.
        load = { true }
        syncing.value = true
        vm.shows(GroupsUiState.Loading)
        syncing.value = false

        vm.shows(GroupsUiState.Empty)
        assertEquals(2, loads)
    }

    @Test fun groupsOnThisPhoneStayOnShowWhileRefreshingFails() = runTest {
        env.seedGroup(G1)
        load = { false }
        val vm = viewModel()

        assertEquals(1, vm.loaded().groupCount)
        // A sync runs and fails: the list stays, and the tab fetched nothing itself.
        syncing.value = true
        syncing.value = false
        vm.onShown()
        assertEquals(1, (vm.state.value as GroupsUiState.Loaded).groupCount)
        assertEquals(0, loads)
        assertFalse(GroupsUiState.CouldNotLoad in seen)
    }

    @Test fun whenTheLastGroupGoesTheListIsFetchedBeforeNoGroupsYet() = runTest {
        env.seedGroup(G1)
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        load = {
            asked.complete(Unit)
            answer.await()
        }
        val vm = viewModel()
        assertEquals(1, vm.loaded().groupCount)
        assertEquals(0, loads)

        // The group leaves this phone (deleted, or the person was removed). The list was never fetched by this tab.
        env.local.removeGroup(G1)

        asked.await()
        vm.shows(GroupsUiState.Loading)
        assertEquals(1, loads)
        assertFalse(GroupsUiState.Empty in seen)
        answer.complete(true)
        vm.shows(GroupsUiState.Empty)
    }

    @Test fun onlySettledSplitsWithOneFriendIsNoGroupsYetWithoutAsking() = runTest {
        // A direct split is on this phone, settled: nothing to list, and the phone was in step with the server.
        env.groups.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null))
        load = { false }
        val vm = viewModel()

        vm.shows(GroupsUiState.Empty)
        assertEquals(0, loads)
    }

    // ---- A split with one friend whose data has not come (SyncEngine.pullLocked stores the server's list first) ----

    /** The split with one friend [D1] as the server's list stores it: its row, with none of its data pulled yet. */
    private suspend fun storeSplitNotPulled() {
        env.db.groupDao().upsertGroup(
            GroupEntity(D1, GroupKind.DIRECT, null, ME_USER, "m-me-d1", version = 0, latestVersion = 7, syncState = SyncState.SYNCED, createdAt = Instant.EPOCH),
        )
    }

    @Test fun aSplitWithOneFriendWhoseDataDidNotComeIsCouldNotLoadThenTryAgainFetches() = runTest(timeout = 10.seconds) {
        // The fetch stored the server's list, a split with one friend, then pulling that split's data failed.
        load = {
            storeSplitNotPulled()
            false
        }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)
        assertFalse(GroupsUiState.Empty in seen)

        // Try again fetches, although a split is on this phone. Its data comes, settled: now "No groups yet".
        load = {
            env.db.groupDao().setVersion(D1, 7, 7)
            true
        }
        vm.tryAgain()
        vm.shows(GroupsUiState.Empty)
        assertEquals(2, loads)
    }

    @Test fun aSplitWithOneFriendWhoseDataDidNotComeIsFetchedWhenTheTabOpens() = runTest(timeout = 10.seconds) {
        // A sync in the background stored the split and then could not pull it (or the app was stopped meanwhile).
        storeSplitNotPulled()
        env.db.groupDao().setLoadFailed(D1, true)
        load = { false }
        val vm = viewModel()

        vm.shows(GroupsUiState.CouldNotLoad)
        assertEquals(1, loads)
        assertFalse(GroupsUiState.Empty in seen)
    }

    @Test fun aSplitWithOneFriendThatCameAfterNoGroupsYetWithoutItsDataIsFetched() = runTest(timeout = 10.seconds) {
        val vm = viewModel()
        vm.shows(GroupsUiState.Empty)

        // Later a sync in the background stores a new split with one friend and cannot pull its data: the list
        // known before no longer says it all.
        load = { false }
        storeSplitNotPulled()
        env.db.groupDao().setLoadFailed(D1, true)

        vm.shows(GroupsUiState.CouldNotLoad)
        assertEquals(2, loads)
    }

    @Test fun theSplitAFailedFetchStoredAsksNothingMoreByItself() = runTest(timeout = 10.seconds) {
        // What the fetch itself stored is not a reason to fetch again: only Try again, opening the tab, back online
        // and a sync that ended are. The database tells its watchers on its own threads, so give it time to.
        load = {
            storeSplitNotPulled()
            env.db.groupDao().setLoadFailed(D1, true)
            false
        }
        val vm = viewModel()
        vm.shows(GroupsUiState.CouldNotLoad)
        withContext(Dispatchers.Default) { delay(500) }

        assertEquals(1, loads)
        assertEquals(GroupsUiState.CouldNotLoad, vm.state.value)
    }

    @Test fun aNewSignInForgetsWhatWasKnownOfTheList() = runTest {
        val vm = viewModel()
        vm.shows(GroupsUiState.Empty)

        // Signed out and in again (another account, or the same one): its list has not been fetched.
        auth.value = AuthState.SignedOut
        vm.shows(GroupsUiState.NotSignedIn)
        seen.clear()
        load = { false }
        auth.value = AuthState.SignedIn(ME_USER, "Arjun")

        vm.shows(GroupsUiState.CouldNotLoad)
        assertFalse(GroupsUiState.Empty in seen)
        assertEquals(2, loads)
    }

    @Test fun signedOutNothingIsFetched() = runTest {
        auth.value = AuthState.SignedOut
        val vm = viewModel()
        vm.shows(GroupsUiState.NotSignedIn)

        vm.onShown()
        vm.tryAgain()

        assertEquals(0, loads)
    }

    /** Goa trip as stored: at [version], while the server is at [latestVersion]. */
    private suspend fun storeGoaTrip(version: Long, latestVersion: Long) {
        env.db.groupDao().upsertGroup(
            GroupEntity(G2, GroupKind.GROUP, "Goa trip", ME_USER, M_ME2, version = version, latestVersion = latestVersion, syncState = SyncState.SYNCED, createdAt = Instant.EPOCH),
        )
    }

    @Test fun aGroupWhoseDataIsStillComingShowsPlaceholdersDuringTheSync() = runTest {
        env.seedGroup(G1)
        storeGoaTrip(version = 0, latestVersion = 5)
        syncing.value = true
        val vm = viewModel()
        vm.state.first { it == GroupsUiState.Loading }
        // Pulled: the list shows.
        storeGoaTrip(version = 5, latestVersion = 5)
        assertEquals(2, vm.loaded().groupCount)
    }

    @Test fun withNoSyncRunningAGroupBehindShowsWhatThePhoneHas() = runTest {
        // A pull cut off (the network dropped, or the app was stopped): the tab does not wait for the next sync.
        env.seedGroup(G1)
        storeGoaTrip(version = 3, latestVersion = 5)
        val shown = viewModel().state.first { it != null }
        assertEquals(2, (shown as GroupsUiState.Loaded).groupCount)
    }

    @Test fun aGroupAlreadyShownKeepsItsRowWhileAChangeIsPulled() = runTest {
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        val vm = viewModel()
        assertEquals(0L, vm.loaded().groups.single().balancePaise)
        // A sync finds the group changed on the server and pulls it: until the pull is done the row stays, with what
        // the phone has (here an expense added meanwhile), instead of the placeholders.
        syncing.value = true
        env.db.groupDao().setLatestVersion(G2, 6)
        paid(G2, M_ME2, 20_000, listOf(M_ME2, M_RAHUL2))
        vm.state.first { (it as? GroupsUiState.Loaded)?.groups?.single()?.balancePaise == 10_000L }
    }

    @Test fun owedOverallCountsTheGroupsListed() = runTest {
        env.seedGroup(G1)
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, false, 1)))
        // Flat 302: I paid 45,000 for three. Office lunch: nothing yet, all settled.
        paid(G1, M_ME, 45_000, listOf(M_ME, M_RAHUL, M_PRIYA))
        val loaded = viewModel().loaded()
        assertEquals(30_000L, loaded.overallPaise)
        assertEquals(2, loaded.groupCount)
        val rows = loaded.groups.associateBy { it.id }
        assertEquals(30_000L, rows.getValue(G1).balancePaise)
        assertEquals(3, rows.getValue(G1).memberCount)
        assertEquals(0L, rows.getValue(G2).balancePaise)
        assertEquals(setOf(15_000L), loaded.friends.map { it.balancePaise }.toSet())
    }

    @Test fun owingOverallIsBelowZero() = runTest {
        env.seedGroup(G1)
        paid(G1, M_RAHUL, 90_000, listOf(M_ME, M_RAHUL, M_PRIYA))
        val loaded = viewModel().loaded()
        assertEquals(-30_000L, loaded.overallPaise)
        assertEquals(1, loaded.groupCount)
        assertEquals(-30_000L, loaded.groups.single().balancePaise)
        val rahul = loaded.friends.single()
        assertEquals(RAHUL_USER, rahul.id)
        assertEquals(-30_000L, rahul.balancePaise)
    }

    @Test fun aDirectSplitCountsOverallButNotAsAGroup() = runTest {
        val direct = env.groups.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null))
        paid(direct, DirectIds.memberId(direct, ME_USER), 20_000, listOf(DirectIds.memberId(direct, ME_USER), DirectIds.memberId(direct, RAHUL_USER)))
        val loaded = viewModel().loaded()
        assertEquals(10_000L, loaded.overallPaise)
        assertEquals(0, loaded.groupCount)
        assertEquals(emptyList<GroupBalance>(), loaded.groups)
    }

    @Test fun aFriendOpensTheirDirectSplitOnlyWhenThereIsOne() = runTest {
        env.seedGroup(G1)
        paid(G1, M_ME, 45_000, listOf(M_ME, M_RAHUL, M_PRIYA))
        val direct = env.groups.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null))
        paid(direct, DirectIds.memberId(direct, ME_USER), 20_000, listOf(DirectIds.memberId(direct, ME_USER), DirectIds.memberId(direct, RAHUL_USER)))
        val vm = viewModel()
        val friends = (vm.state.first { state -> (state as? GroupsUiState.Loaded)?.friends?.any { it.groupId != null } == true } as GroupsUiState.Loaded)
            .friends.associateBy { it.id }
        // Rahul: 15,000 in Flat 302 and 10,000 in the direct split; a tap opens the direct split.
        assertEquals(25_000L, friends.getValue(RAHUL_USER).balancePaise)
        assertEquals(direct, friends.getValue(RAHUL_USER).groupId)
        // Priya is only in Flat 302: her row opens nothing.
        assertNull(friends.getValue("+919000000001").groupId)
    }

    private companion object {
        /** A split with one friend. */
        const val D1 = "d1"
    }

    @Test fun continueWithGoogleOutcomes() = runTest {
        auth.value = AuthState.SignedOut
        val vm = viewModel()
        val effects = mutableListOf<GroupsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.toList(effects) }
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        signInResult = SignInResult.Success(isNew = true, name = "Arjun", phone = null)
        vm.continueWithGoogle(activity)
        signInResult = SignInResult.Success(isNew = false, name = "Arjun", phone = null)
        vm.continueWithGoogle(activity)
        signInResult = SignInResult.Cancelled
        vm.continueWithGoogle(activity)
        signInResult = SignInResult.Failed
        vm.continueWithGoogle(activity)
        signInResult = SignInResult.NotConfigured
        vm.continueWithGoogle(activity)

        assertEquals(5, signInCalls)
        // A new account gives its name; a known one just shows the tab; Cancelled stays; failures open Sign in.
        assertEquals(listOf(GroupsEffect.OpenSignInName, GroupsEffect.OpenSignIn, GroupsEffect.OpenSignIn), effects)
    }
}
