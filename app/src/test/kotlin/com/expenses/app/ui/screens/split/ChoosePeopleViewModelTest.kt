package com.expenses.app.ui.screens.split

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.Person
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.splits.DirectIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Choose people (02-everyday/12, 48): one group, one friend, several friends and a friend added there. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChoosePeopleViewModelTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun TestScope.choose(selected: String? = null): ChoosePeopleViewModel {
        val vm = ChoosePeopleViewModel(selected, env.groups, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun ChoosePeopleViewModel.loaded(check: (ChoosePeopleUiState) -> Boolean = { true }): ChoosePeopleUiState =
        state.first { it.friends.isNotEmpty() && check(it) }

    @Test fun oneGroupIsHandedBack() = runTest {
        env.seedGroup()
        val vm = choose()
        val state = vm.loaded()
        assertEquals(listOf(ChoiceGroup(G1, "Flat 302", 3)), state.groups)
        assertEquals(listOf("Priya", "Rahul"), state.friends.map { it.name })
        assertEquals(listOf(false, true), state.friends.map { it.onApp })
        assertFalse(state.canFinish)

        vm.onEvent(ChoosePeopleEvent.GroupSwitched(G1, on = true))
        assertTrue(vm.loaded { it.selectedGroupId == G1 }.canFinish)
        vm.onEvent(ChoosePeopleEvent.Done)
        assertEquals(G1, vm.chosen.first { it != null })
    }

    @Test fun theGroupChosenOnSplitItStartsSwitchedOn() = runTest {
        env.seedGroup()
        val vm = choose(selected = G1)
        assertEquals(G1, vm.loaded { it.selectedGroupId != null }.selectedGroupId)
    }

    @Test fun aGroupWhereEveryoneElseLeftIsNotOffered() = runTest {
        env.seedGroup()
        // A second group whose only other member has left: the person is alone in it.
        env.seedGroup(G2, myMember = M_ME2, others = listOf(MemberEntity(M_RAHUL2, G2, RAHUL_USER, "Rahul", null, null, true, true, 1)))

        assertEquals(listOf(G1), choose().loaded().groups.map { it.id })
        // The group already chosen on Split it stays, so its switch can be seen and turned off.
        assertEquals(setOf(G1, G2), choose(selected = G2).loaded { it.selectedGroupId == G2 }.groups.map { it.id }.toSet())
    }

    @Test fun oneFriendGetsTheirDirectSplit() = runTest {
        env.seedGroup()
        val vm = choose()
        vm.loaded()
        vm.onEvent(ChoosePeopleEvent.FriendSwitched(RAHUL_USER, on = true))
        vm.loaded { it.selectedFriends == listOf(RAHUL_USER) }
        vm.onEvent(ChoosePeopleEvent.Done)

        val chosen = vm.chosen.first { it != null }!!
        assertEquals(DirectIds.groupId(ME_USER, RAHUL_USER), chosen)
        val direct = env.groups.observeGroup(chosen).first()!!
        assertEquals(GroupKind.DIRECT, direct.group.kind)
        assertEquals("Rahul", direct.name)
    }

    @Test fun severalFriendsMakeAGroupAndTheNewGroupIsTheChoice() = runTest {
        env.seedGroup()
        val vm = choose()
        vm.loaded()
        vm.onEvent(ChoosePeopleEvent.GroupSwitched(G1, on = true))
        vm.onEvent(ChoosePeopleEvent.FriendSwitched(RAHUL_USER, on = true))
        vm.onEvent(ChoosePeopleEvent.FriendSwitched("+919000000001", on = true))

        // A group and friends never mix: picking a friend switched the group off.
        val state = vm.loaded { it.selectedFriends.size == 2 }
        assertNull(state.selectedGroupId)
        assertTrue(state.severalFriends)
        assertFalse(state.canFinish)
        assertEquals(listOf(RAHUL_USER, "+919000000001"), vm.pickedKeys())

        // Done does nothing with several friends: "Make a group" is the way on.
        vm.onEvent(ChoosePeopleEvent.Done)
        assertNull(vm.chosen.value)

        // New group made a group of them: it is the choice.
        vm.onEvent(ChoosePeopleEvent.GroupCreated("new-group"))
        assertEquals("new-group", vm.chosen.first { it != null })
    }

    @Test fun aFriendFromAddFriendIsSwitchedOnAndSplitWith() = runTest {
        // Nobody known yet: only the search and "Add someone".
        val vm = choose()
        val empty = vm.state.first()
        assertTrue(empty.groups.isEmpty())
        assertTrue(empty.friends.isEmpty())

        vm.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("Sam Rao", "90000 00002")))
        val state = vm.loaded()
        assertEquals(listOf(ChoiceFriend("+919000000002", "Sam Rao", onApp = false)), state.friends)
        assertEquals(listOf("+919000000002"), state.selectedFriends)
        assertTrue(state.canFinish)

        vm.onEvent(ChoosePeopleEvent.Done)
        val chosen = vm.chosen.first { it != null }!!
        val direct = env.groups.observeGroup(chosen).first()!!
        assertEquals(GroupKind.DIRECT, direct.group.kind)
        val sam = direct.members.single { !it.isMe }.member
        assertEquals("Sam Rao", sam.name)
        assertEquals("+919000000002", sam.phone)
    }

    @Test fun makeAGroupHandsOnFriendsFromAddFriend() = runTest {
        env.seedGroup()
        val vm = choose()
        vm.loaded()
        vm.onEvent(ChoosePeopleEvent.FriendSwitched(RAHUL_USER, on = true))
        vm.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("Sam Rao", "90000 00002")))
        vm.loaded { it.selectedFriends.size == 2 }
        vm.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("Kiran", null)))
        assertTrue(vm.loaded { it.selectedFriends.size == 3 }.severalFriends)

        // New group finds Rahul by key; the friends added here are in no group yet, so they go as they were added.
        assertEquals(listOf(RAHUL_USER), vm.pickedKeys())
        assertEquals(listOf(AddedFriend("Sam Rao", "90000 00002"), AddedFriend("Kiran", null)), vm.pickedNewFriends())

        // Switched off, a friend added here is not handed on.
        vm.onEvent(ChoosePeopleEvent.FriendSwitched("name:kiran", on = false))
        vm.loaded { it.selectedFriends.size == 2 }
        assertEquals(listOf(AddedFriend("Sam Rao", "90000 00002")), vm.pickedNewFriends())
    }

    @Test fun aDirectSplitChosenOnSplitItStartsWithItsFriendOn() = runTest {
        env.seedGroup()
        val direct = env.groups.directGroupWith(Person(RAHUL_USER, "Rahul", RAHUL_USER, null))
        val vm = choose(selected = direct)
        assertEquals(listOf(RAHUL_USER), vm.loaded { it.selectedFriends.isNotEmpty() }.selectedFriends)
    }

    @Test fun aDirectSplitWithAFriendWithoutAPhoneStartsWithThemOn() = runTest {
        val first = choose()
        first.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("Kiran", null)))
        first.loaded { it.canFinish }
        first.onEvent(ChoosePeopleEvent.Done)
        val direct = first.chosen.first { it != null }!!

        val again = choose(selected = direct)
        val state = again.loaded { it.selectedFriends.isNotEmpty() }
        assertEquals(listOf("Kiran"), state.friends.filter { it.key in state.selectedFriends }.map { it.name })
    }

    @Test fun aFriendWithoutAPhoneAddedAgainIsTheSameDirectSplit() = runTest {
        val first = choose()
        first.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("Kiran", null)))
        first.loaded { it.canFinish }
        first.onEvent(ChoosePeopleEvent.Done)
        val direct = first.chosen.first { it != null }!!

        // A second split with Kiran: the one Kiran listed, chosen again, is the same direct split.
        val again = choose()
        val kiran = again.loaded().friends.single()
        assertEquals("Kiran", kiran.name)
        again.onEvent(ChoosePeopleEvent.FriendSwitched(kiran.key, on = true))
        again.loaded { it.canFinish }
        again.onEvent(ChoosePeopleEvent.Done)
        assertEquals(direct, again.chosen.first { it != null })
        // Added once more on Add friend, still one Kiran.
        again.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("kiran ", null)))
        assertEquals(listOf("Kiran"), again.loaded { it.selectedFriends.isNotEmpty() }.friends.map { it.name })
        assertEquals(1, env.groups.observeKnownPeople().first().size)
    }

    @Test fun aFriendAddedAgainByPhoneIsTheKnownPerson() = runTest {
        env.seedGroup()
        val vm = choose()
        vm.loaded()
        vm.onEvent(ChoosePeopleEvent.FriendAdded(AddedFriend("Priya N", "+91 90000 00001")))
        val state = vm.loaded { it.selectedFriends.isNotEmpty() }
        assertEquals(2, state.friends.size)
        assertEquals(listOf("+919000000001"), state.selectedFriends)
    }

    @Test fun searchMatchesNamesAndPhoneNumbers() = runTest {
        env.seedGroup()
        val vm = choose()
        vm.loaded()
        vm.onEvent(ChoosePeopleEvent.QueryChanged("rah"))
        assertEquals(listOf("Rahul"), vm.loaded { it.query == "rah" }.friends.map { it.name })
        vm.onEvent(ChoosePeopleEvent.QueryChanged("000001"))
        val byPhone = vm.state.first { it.query == "000001" }
        assertEquals(listOf("Priya"), byPhone.friends.map { it.name })
        assertTrue(byPhone.groups.isEmpty())
    }
}
