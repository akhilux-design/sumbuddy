package com.expenses.app.ui.screens.groups

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.nav.NewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** New group: people filled in from Choose people, a friend from Add friend, and Create. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NewGroupViewModelTest {
    private val env = GroupsTestEnv()
    private val priyaKey = "+919000000001"

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private suspend fun NewGroupViewModel.awaitPeople(count: Int): List<NewGroupPerson> =
        state.first { it.people.size == count }.people

    @Test fun peopleNamedByChoosePeopleStartSwitchedOn() = runTest {
        env.seedGroup(G1)
        val vm = NewGroupViewModel(env.groups, SavedStateHandle(), prefilledKeys = listOf(RAHUL_USER))
        val people = vm.awaitPeople(2).associateBy { it.key }
        assertTrue(people.getValue(RAHUL_USER).selected)
        assertTrue(people.getValue(RAHUL_USER).onApp)
        assertFalse(people.getValue(priyaKey).selected)
        assertFalse(people.getValue(priyaKey).onApp)
        // Nothing can be made without a name.
        assertFalse(vm.state.value.canCreate)
    }

    @Test fun aFriendFromAddFriendIsListedSwitchedOnAndNeverTwice() = runTest {
        env.seedGroup(G1)
        val vm = NewGroupViewModel(env.groups, SavedStateHandle(), prefilledKeys = emptyList())
        vm.awaitPeople(2)
        vm.onFriendAdded(AddedFriend("Kiran Rao", "90000 00002"))
        val kiran = vm.awaitPeople(3).last()
        assertEquals("Kiran Rao", kiran.name)
        assertTrue(kiran.selected)
        assertFalse(kiran.onApp)
        // Priya again, typed another way: her row is switched on, not listed a second time.
        vm.onFriendAdded(AddedFriend("Priya N", "+91 90000 00001"))
        val people = vm.state.first { state -> state.people.first { it.key == priyaKey }.selected }.people
        assertEquals(3, people.size)
        // The same new friend again is not listed twice either.
        vm.onFriendAdded(AddedFriend("Kiran", "9000000002"))
        assertEquals(3, vm.state.value.people.size)
    }

    @Test fun createMakesTheGroupWithThePeopleSwitchedOn() = runTest {
        env.seedGroup(G1)
        val vm = NewGroupViewModel(env.groups, SavedStateHandle(), prefilledKeys = listOf(RAHUL_USER, priyaKey))
        vm.awaitPeople(2)
        vm.onFriendAdded(AddedFriend("Kiran Rao", null))
        vm.awaitPeople(3)
        vm.onEvent(NewGroupEvent.PersonSwitched(priyaKey, selected = false))
        vm.onEvent(NewGroupEvent.NameChanged("  Goa trip "))
        assertTrue(vm.state.first { it.name == "  Goa trip " }.canCreate)
        vm.onEvent(NewGroupEvent.Create)
        val created = vm.effect.first() as NewGroupEffect.Created
        val dao = env.db.groupDao()
        val group = dao.getGroup(created.groupId)!!
        assertEquals("Goa trip", group.name)
        assertEquals(GroupKind.GROUP, group.kind)
        val others = dao.membersOf(created.groupId).filter { it.id != group.myMemberId }
        assertEquals(setOf("Rahul", "Kiran Rao"), others.map { it.name }.toSet())
        assertEquals(RAHUL_USER, others.first { it.name == "Rahul" }.userId)
        assertEquals(null, others.first { it.name == "Kiran Rao" }.userId)
    }

    @Test fun friendsChoosePeopleAddedAreListedSwitchedOnAndMadeMembers() = runTest {
        env.seedGroup(G1)
        // Choose people's "Make a group" with Rahul and two friends added there with "Add someone".
        val route = NewGroup.with(listOf(RAHUL_USER), listOf(AddedFriend("Sam Rao", "90000 00002"), AddedFriend("Kiran", null)))
        val vm = NewGroupViewModel(env.groups, SavedStateHandle(), route.peopleKeys, route.newFriends)
        val people = vm.awaitPeople(4)
        assertEquals(listOf("Sam Rao", "Kiran"), people.filter { !it.onApp && it.key != priyaKey }.map { it.name })
        assertEquals(setOf("Rahul", "Sam Rao", "Kiran"), people.filter { it.selected }.map { it.name }.toSet())

        vm.onEvent(NewGroupEvent.NameChanged("Goa trip"))
        vm.onEvent(NewGroupEvent.Create)
        val created = vm.effect.first() as NewGroupEffect.Created
        val dao = env.db.groupDao()
        val group = dao.getGroup(created.groupId)!!
        val others = dao.membersOf(created.groupId).filter { it.id != group.myMemberId }
        assertEquals(setOf("Rahul", "Sam Rao", "Kiran"), others.map { it.name }.toSet())
        assertEquals("+919000000002", others.first { it.name == "Sam Rao" }.phone)
    }

    @Test fun signedOutMeanwhileOpensSignIn() = runTest {
        env.me = null
        val vm = NewGroupViewModel(env.groups, SavedStateHandle(), prefilledKeys = emptyList())
        vm.onEvent(NewGroupEvent.NameChanged("Goa trip"))
        vm.onEvent(NewGroupEvent.Create)
        assertEquals(NewGroupEffect.SignedOut, vm.effect.first())
    }

    @Test fun whatWasTypedComesBackAfterTheAppWasStopped() = runTest {
        env.seedGroup(G1)
        val saved = SavedStateHandle()
        val first = NewGroupViewModel(env.groups, saved, prefilledKeys = emptyList())
        first.awaitPeople(2)
        first.onEvent(NewGroupEvent.NameChanged("Goa trip"))
        first.onEvent(NewGroupEvent.PersonSwitched(RAHUL_USER, selected = true))
        first.onFriendAdded(AddedFriend("Kiran Rao", null))
        first.awaitPeople(3)
        // The prefill is not applied again over what the person chose.
        val again = NewGroupViewModel(env.groups, saved, prefilledKeys = listOf(priyaKey))
        val state = again.state.first { it.people.size == 3 }
        assertEquals("Goa trip", state.name)
        assertEquals(setOf(RAHUL_USER, "Kiran Rao"), state.people.filter { it.selected }.map { if (it.onApp) it.key else it.name }.toSet())
    }
}
