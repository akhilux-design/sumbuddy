package com.expenses.app.ui.screens.you

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.repository.TestClock
import com.expenses.app.data.repository.TestDatabase
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.data.FamilyInfo
import com.expenses.app.groups.data.FamilyMember
import com.expenses.app.groups.data.LoadState
import com.expenses.app.ui.components.AvatarColor
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/** The You tab follows the session: "Signed in with Google" (00) or "Not signed in" with the Sign in card (06). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class YouViewModelTest {
    private val test = TestDatabase()
    private val folder: File = Files.createTempDirectory("you").toFile()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        test.close()
        folder.deleteRecursively()
    }

    @Test fun signedInAndOut() = runTest {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") })
        val auth = MutableStateFlow<AuthState>(AuthState.Loading)
        val vm = YouViewModel(settings, test.accounts, test.readSms, TestClock, auth)
        backgroundScope.launch { vm.state.collect {} }

        // Nothing is drawn while the session is read, so the wrong state never flashes.
        settings.settings.first()
        assertNull(vm.state.value)

        auth.value = AuthState.SignedOut
        var shown = vm.state.filterNotNull().first()
        assertFalse(shown.signedIn)
        assertNull(shown.profile.name)

        // Signed in without a name on this phone: the account's name.
        auth.value = AuthState.SignedIn(ME_USER, "Arjun")
        shown = vm.state.filterNotNull().first { it.signedIn }
        assertEquals("Arjun", shown.profile.name)

        // The name used on Home wins once there is one (saving the profile sets it too).
        settings.setUserName("Arjun Mehta")
        shown = vm.state.filterNotNull().first { it.profile.name == "Arjun Mehta" }
        assertTrue(shown.signedIn)

        auth.value = AuthState.SignedOut
        shown = vm.state.filterNotNull().first { !it.signedIn }
        assertEquals("Arjun Mehta", shown.profile.name)
    }
    @Test fun theFamilyRowFollowsTheFamily() = runTest {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") })
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ME_USER, "Arjun"))
        val family = MutableStateFlow<FamilyInfo?>(null)
        val vm = YouViewModel(settings, test.accounts, test.readSms, TestClock, auth, family)
        backgroundScope.launch { vm.state.collect {} }

        assertEquals(YouFamily.None, vm.state.filterNotNull().first().family)

        val me = FamilyMember("m1", "Arjun Mehta", "Arjun", AvatarColor.Purple)
        val waiting = FamilyInfo("f", "Home", "m1", isOwner = true, me = me, partner = null, placeholderId = "p", load = LoadState.READY)
        family.value = waiting
        assertEquals(YouFamily.Waiting("Home"), vm.state.filterNotNull().first { it.family != YouFamily.None }.family)

        family.value = waiting.copy(placeholderId = null)
        assertEquals(YouFamily.Alone("Home"), vm.state.filterNotNull().first { it.family is YouFamily.Alone }.family)

        family.value = waiting.copy(placeholderId = null, partner = FamilyMember("m2", "Meera Iyer", "Meera", AvatarColor.Green))
        assertEquals(YouFamily.Together("Home", "Arjun", "Meera"), vm.state.filterNotNull().first { it.family is YouFamily.Together }.family)
    }
}
