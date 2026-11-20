package com.expenses.app.ui.screens.groups

import androidx.lifecycle.SavedStateHandle
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.ui.nav.AddedFriend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

/** Add friend: the phone number check, and the friend handed back (New group) or added to a group (Group settings). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddFriendViewModelTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    @Test fun aPhoneNumberThatIsNotOneStopsAddUntilItIsFixed() = runTest {
        val vm = AddFriendViewModel(groupId = null, groups = env.groups, savedState = SavedStateHandle())
        val effects = mutableListOf<AddFriendEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.toList(effects) }
        // No name: nothing to add.
        assertFalse(vm.state.value.canAdd)
        vm.onEvent(AddFriendEvent.NameChanged("Kiran Rao"))
        vm.onEvent(AddFriendEvent.PhoneChanged("98450"))
        vm.onEvent(AddFriendEvent.Add)
        assertTrue(vm.state.value.phoneInvalid)
        assertEquals(emptyList<AddFriendEffect>(), effects)
        // Typing again clears the error; a real number goes through.
        vm.onEvent(AddFriendEvent.PhoneChanged("90000 00002"))
        assertFalse(vm.state.value.phoneInvalid)
        vm.onEvent(AddFriendEvent.Add)
        assertEquals(listOf<AddFriendEffect>(AddFriendEffect.HandBack(AddedFriend("Kiran Rao", "90000 00002"))), effects)
    }

    @Test fun withoutAGroupTheFriendIsHandedBackAndNothingIsStored() = runTest {
        val vm = AddFriendViewModel(groupId = null, groups = env.groups, savedState = SavedStateHandle())
        vm.onEvent(AddFriendEvent.NameChanged("  Kiran Rao "))
        vm.onEvent(AddFriendEvent.Add)
        val handBack = vm.effect.first() as AddFriendEffect.HandBack
        // The phone is optional.
        assertEquals(AddedFriend("Kiran Rao", null), handBack.friend)
        assertEquals(0, env.outbox.entries().size)
    }

    @Test fun forAGroupTheFriendIsAddedToIt() = runTest {
        env.seedGroup(G1)
        val vm = AddFriendViewModel(groupId = G1, groups = env.groups, savedState = SavedStateHandle())
        vm.onEvent(AddFriendEvent.NameChanged("Kiran Rao"))
        vm.onEvent(AddFriendEvent.PhoneChanged("90000 00002"))
        vm.onEvent(AddFriendEvent.Add)
        assertEquals(AddFriendEffect.Added, vm.effect.first())
        val kiran = env.db.groupDao().membersOf(G1).single { it.name == "Kiran Rao" }
        assertEquals("+919000000002", kiran.phone)
        assertNull(kiran.userId)
        assertEquals(1, env.outbox.entries().size)
    }

    @Test fun aGroupThatLeftThePhoneMeanwhileJustLeavesTheScreen() = runTest {
        // A sync removed the group (deleted, or the person removed) while Add friend was open.
        val vm = AddFriendViewModel(groupId = G1, groups = env.groups, savedState = SavedStateHandle())
        vm.onEvent(AddFriendEvent.NameChanged("Kiran Rao"))
        vm.onEvent(AddFriendEvent.Add)
        assertEquals(AddFriendEffect.Added, vm.effect.first())
        assertEquals(emptyList<Any>(), env.db.groupDao().membersOf(G1))
        assertEquals(0, env.outbox.entries().size)
    }

    @Test fun aPickedContactFillsTheFields() = runTest {
        val saved = SavedStateHandle()
        val vm = AddFriendViewModel(groupId = null, groups = env.groups, savedState = saved)
        vm.onEvent(AddFriendEvent.ContactPicked("Kiran Rao", "+91 90000 00002"))
        assertEquals("Kiran Rao", vm.state.value.name)
        assertEquals("+91 90000 00002", vm.state.value.phone)
        // Kept for when Android stops the app while the picker is open.
        val again = AddFriendViewModel(groupId = null, groups = env.groups, savedState = saved)
        assertEquals("Kiran Rao", again.state.value.name)
    }
}
