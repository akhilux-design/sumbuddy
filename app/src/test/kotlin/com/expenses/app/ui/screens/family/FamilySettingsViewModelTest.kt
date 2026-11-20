package com.expenses.app.ui.screens.family

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.groups.FAMILY
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.MEERA_USER
import com.expenses.app.groups.M_MEERA
import com.expenses.app.groups.M_PLACEHOLDER
import com.expenses.app.groups.api.LinkResponse
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.json
import com.expenses.app.ui.screens.signin.ActionProblem
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Family settings (06-family/17 to 24; docs/family.md 1.6) on the family data layer over the fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FamilySettingsViewModelTest {
    private val env = GroupsTestEnv()
    private val online = MutableStateFlow(true)
    private val effects = CopyOnWriteArrayList<FamilySettingsEffect>()
    private val viewModels = ClearedViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.settings(sendLink: Boolean = false, saved: SavedStateHandle = SavedStateHandle()): FamilySettingsViewModel {
        val vm = viewModels.keep { FamilySettingsViewModel(env.family, online, sendLink, saved, backgroundScope) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        return vm
    }

    /** Waits, in real time, for a state that matches [predicate]. */
    private suspend fun FamilySettingsViewModel.await(predicate: (FamilySettingsUiState) -> Boolean): FamilySettingsUiState =
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first(predicate) } }

    private suspend fun FamilySettingsViewModel.shown(predicate: (FamilySettingsUiState.Shown) -> Boolean = { true }) =
        await { it is FamilySettingsUiState.Shown && predicate(it) } as FamilySettingsUiState.Shown

    /** Waits, in real time, until [count] effects arrived. */
    private suspend fun awaitEffects(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (effects.size < count) delay(10) }
    }

    private fun linkAnswer(linkId: String) = env.fake.on(
        "PUT", "/v1/groups/$FAMILY/members/$M_PLACEHOLDER/links/$linkId",
        json(201, LinkResponse.serializer(), LinkResponse(linkId, "https://links.test/pay/abcdefghijklmnopqrstuvwx")),
    )

    @Test fun thePeopleAsDrawn() = runTest {
        env.seedFamily()
        val vm = settings()

        val shown = vm.shown()
        assertEquals("Home", shown.familyName)
        assertEquals("Home", shown.nameField)
        assertEquals("Arjun Mehta", shown.me)
        assertEquals(FamilyPartnerRow.Joined(M_MEERA, "Meera Iyer", "Meera", canRemove = true), shown.partner)
    }

    @Test fun theNameIsRenamedQueuedAndABlankOneIsNotSaved() = runTest {
        env.seedFamily()
        val vm = settings()
        vm.shown()

        vm.onEvent(FamilySettingsEvent.NameChanged("  "))
        vm.onEvent(FamilySettingsEvent.NameDone)
        assertTrue(vm.shown { it.nameError }.nameError)

        // Offline too: renaming is queued.
        online.value = false
        vm.onEvent(FamilySettingsEvent.NameChanged("Our home"))
        vm.onEvent(FamilySettingsEvent.NameDone)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (env.db.groupDao().getGroup(FAMILY)?.name != "Our home") delay(10) }
        }
        val rename = env.outbox.entries().single { it.entityKind == EntityKind.GROUP }
        assertEquals(OutboxOp.UPDATE, rename.op)
        assertTrue(env.fake.requests.isEmpty())
    }

    @Test fun onlyTheOwnerIsAskedToRemoveThePartner() = runTest {
        env.seedFamily()
        env.db.groupDao().run { upsertGroup(getGroup(FAMILY)!!.copy(ownerUserId = MEERA_USER, ownerMemberId = M_MEERA)) }
        val vm = settings()
        assertEquals(false, (vm.shown { it.partner is FamilyPartnerRow.Joined }.partner as FamilyPartnerRow.Joined).canRemove)

        vm.onEvent(FamilySettingsEvent.PartnerTapped)

        assertNull(vm.shown().confirm)
    }

    @Test fun theOwnerRemovesThePartner() = runTest {
        env.seedFamily()
        env.fake.on("DELETE", "/v1/groups/$FAMILY/members/$M_MEERA", MockResponse().setResponseCode(204))
        val vm = settings()
        vm.shown()

        vm.onEvent(FamilySettingsEvent.PartnerTapped)
        assertEquals(FamilyConfirm.Remove(M_MEERA, "Meera"), vm.shown().confirm)
        vm.onEvent(FamilySettingsEvent.Confirm)

        val after = vm.shown { it.partner == FamilyPartnerRow.None && !it.busy }
        assertNull(after.confirm)
        assertNull(after.problem)
        assertEquals(1, env.fake.requestsTo("DELETE", "/v1/groups/$FAMILY/members/$M_MEERA").size)
    }

    @Test fun theInviteSheetCancelsTheInvite() = runTest {
        env.seedFamily(partner = false, placeholder = true)
        env.fake.on("DELETE", "/v1/groups/$FAMILY/members/$M_PLACEHOLDER", MockResponse().setResponseCode(204))
        val vm = settings()
        assertEquals(FamilyPartnerRow.Invited, vm.shown().partner)

        vm.onEvent(FamilySettingsEvent.PartnerTapped)
        assertTrue(vm.shown().inviteSheet)
        vm.onEvent(FamilySettingsEvent.CancelInvite)

        val after = vm.shown { it.partner == FamilyPartnerRow.None && !it.busy }
        assertEquals(false, after.inviteSheet)
    }

    @Test fun sendLinkAgainSharesTheInvite() = runTest {
        env.seedFamily(partner = false, placeholder = true)
        linkAnswer(env.madeId(1))
        val vm = settings()
        vm.shown()

        vm.onEvent(FamilySettingsEvent.PartnerTapped)
        vm.onEvent(FamilySettingsEvent.SendLinkAgain)
        awaitEffects(1)

        val invite = (effects.single() as FamilySettingsEffect.Share).invite
        assertTrue(invite.link.startsWith("https://links.test/pay/"))
        assertEquals("Arjun" to "Home", invite.senderFirstName to invite.familyName)
        assertEquals(false, vm.shown { !it.busy }.inviteSheet)
    }

    @Test fun openedToSendTheLinkItSharesAtOnceAndOnlyOnce() = runTest {
        env.seedFamily(partner = false, placeholder = true)
        linkAnswer(env.madeId(1))
        val saved = SavedStateHandle()
        settings(sendLink = true, saved = saved)
        awaitEffects(1)
        assertTrue(effects.single() is FamilySettingsEffect.Share)

        // Recreated with the same saved state: not sent again.
        settings(sendLink = true, saved = saved).shown()
        delay(100)
        assertEquals(1, effects.size)
    }

    @Test fun openedToSendTheLinkWithThePartnerInDoesNothing() = runTest {
        env.seedFamily()
        settings(sendLink = true).shown()
        assertTrue(env.fake.requests.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test fun leaveAsksThenLeavesForHome() = runTest {
        env.seedFamily()
        env.fake.on("POST", "/v1/groups/$FAMILY/leave", MockResponse().setResponseCode(204))
        val vm = settings()
        vm.shown()

        vm.onEvent(FamilySettingsEvent.Leave)
        assertEquals(FamilyConfirm.Leave, vm.shown().confirm)
        vm.onEvent(FamilySettingsEvent.Confirm)

        assertEquals(FamilySettingsUiState.Left, vm.await { it == FamilySettingsUiState.Left })
        assertNull(env.db.groupDao().familyGroup())
    }

    @Test fun offlineTheOnlineActionsShowTheBannerAndSendNothing() = runTest {
        env.seedFamily()
        online.value = false
        val vm = settings()
        vm.shown()

        vm.onEvent(FamilySettingsEvent.Leave)
        vm.onEvent(FamilySettingsEvent.Confirm)

        val shown = vm.shown { it.problem != null }
        assertEquals(ActionProblem.OFFLINE, shown.problem)
        assertNull(shown.confirm)
        assertTrue(env.fake.requests.isEmpty())
        // Back online, the banner goes.
        online.value = true
        assertNull(vm.shown { it.problem == null }.problem)
    }

    @Test fun aRefusedRemoveShowsTheProblemBanner() = runTest {
        env.seedFamily()
        env.fake.on("DELETE", "/v1/groups/$FAMILY/members/$M_MEERA", com.expenses.app.groups.error(403, "not_owner"))
        val vm = settings()
        vm.shown()

        vm.onEvent(FamilySettingsEvent.PartnerTapped)
        vm.onEvent(FamilySettingsEvent.Confirm)

        val shown = vm.shown { it.problem != null }
        assertEquals(ActionProblem.FAILED, shown.problem)
        assertTrue(shown.partner is FamilyPartnerRow.Joined)
    }

    @Test fun theFamilyLeavingThisPhoneOtherwiseClosesTheScreen() = runTest {
        env.seedFamily()
        val vm = settings()
        vm.shown()

        env.local.removeGroup(FAMILY)

        assertEquals(FamilySettingsUiState.Gone, vm.await { it == FamilySettingsUiState.Gone })
    }
}
