package com.expenses.app.ui.screens.groups

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.expenses.app.ui.nav.ADDED_FRIEND_KEY
import com.expenses.app.ui.nav.AddFriend
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.nav.CREATED_GROUP_KEY
import com.expenses.app.ui.nav.ChoosePeople
import com.expenses.app.ui.nav.GroupPage
import com.expenses.app.ui.nav.Groups
import com.expenses.app.ui.nav.NewGroup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * New group's and Add friend's hand-offs (docs/groups-build-plan.md section 5): the new group's id under
 * [CREATED_GROUP_KEY], the Group page in place of New group from the Groups tab, and a friend under [ADDED_FRIEND_KEY].
 */
@RunWith(RobolectricTestRunner::class)
class NewGroupNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Groups) {
                composable<Groups> {}
                composable<ChoosePeople> {}
                composable<NewGroup> {}
                composable<AddFriend> {}
                composable<GroupPage> {}
            }
        }
    }

    @Test fun fromTheGroupsTabTheNewGroupsPageOpensInItsPlace() {
        show()
        compose.runOnIdle { nav.navigate(NewGroup()) }
        compose.runOnIdle { nav.afterGroupCreated(nav.currentBackStackEntry!!, "g1") }
        compose.runOnIdle {
            assertEquals("g1", nav.currentBackStackEntry!!.toRoute<GroupPage>().groupId)
            val below = nav.previousBackStackEntry!!
            assertTrue(below.destination.hasRoute<Groups>())
            assertEquals("g1", below.savedStateHandle.get<String>(CREATED_GROUP_KEY))
        }
        // Back from the Group page goes to the Groups tab, not to New group.
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<Groups>()) }
    }

    @Test fun fromChoosePeopleNewGroupLeavesWithTheId() {
        show()
        compose.runOnIdle { nav.navigate(ChoosePeople()) }
        compose.runOnIdle { nav.navigate(NewGroup.with(listOf("a", "b"))) }
        compose.runOnIdle { nav.afterGroupCreated(nav.currentBackStackEntry!!, "g2") }
        compose.runOnIdle {
            val current = nav.currentBackStackEntry!!
            assertTrue(current.destination.hasRoute<ChoosePeople>())
            assertEquals("g2", current.savedStateHandle.get<String>(CREATED_GROUP_KEY))
        }
    }

    @Test fun addFriendHandsTheFriendBackAsJson() {
        show()
        compose.runOnIdle { nav.navigate(NewGroup()) }
        compose.runOnIdle { nav.navigate(AddFriend()) }
        compose.runOnIdle { nav.handBackFriend(nav.currentBackStackEntry!!, AddedFriend("Kiran Rao", "90000 00001")) }
        compose.runOnIdle {
            val current = nav.currentBackStackEntry!!
            assertTrue(current.destination.hasRoute<NewGroup>())
            val json = current.savedStateHandle.get<String>(ADDED_FRIEND_KEY)!!
            assertEquals(AddedFriend("Kiran Rao", "90000 00001"), Json.decodeFromString(AddedFriend.serializer(), json))
        }
    }
}
