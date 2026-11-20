package com.expenses.app.ui.screens.signin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.json
import com.expenses.app.ui.screens.you.MainThreadViewModels
import com.expenses.app.ui.screens.you.YouJoinTestAuth
import com.expenses.app.ui.screens.you.jsonBody
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Your name (after sign-in)" (01-onboarding/03) with its UPI ID field, on a real session over the fake server. The
 * ViewModels run on a real main thread and are cleared before it goes ([MainThreadViewModels]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SignInNameViewModelTest {
    private val env = GroupsTestEnv()
    private val folder: File = Files.createTempDirectory("your-name").toFile()
    private val online = MutableStateFlow(true)
    private val saved = CopyOnWriteArrayList<Unit>()
    private val main = MainThreadViewModels()
    private var session: YouJoinTestAuth? = null

    @Before fun setUp() = main.start()

    @After fun tearDown() {
        main.finish(close = env::close, idle = { session?.idle() ?: true })
        folder.deleteRecursively()
    }

    private suspend fun TestScope.signIn(user: UserDto): YouJoinTestAuth =
        YouJoinTestAuth(env, backgroundScope, folder).also {
            session = it
            it.signIn(user, isNew = true)
        }

    private suspend fun TestScope.yourName(user: UserDto): SignInNameViewModel {
        val session = signIn(user)
        val vm = main.kept { SignInNameViewModel(session.auth, SavedStateHandle(), online) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.saved.collect { saved += it } }
        vm.state.first { it.name == user.name }
        return vm
    }

    private fun patches() = env.fake.requestsTo("PATCH", "/v1/me")

    /** Runs [block] in real time, not the test's virtual time, giving up after 5 s. */
    private suspend fun inRealTime(block: suspend () -> Unit) = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    @Test fun theUpiIdIsCheckedAndSavedWithTheName() = runTest {
        val vm = yourName(UserDto(ME_USER, "Arjun Mehta", "+919876543210", null))
        assertEquals("+91 98765 43210", vm.state.value.phone)
        assertEquals("", vm.state.value.upiId)

        // Not a UPI ID: nothing is saved.
        vm.onEvent(SignInNameEvent.UpiIdChanged("arjun.okhdfc"))
        vm.onEvent(SignInNameEvent.Continue)
        assertTrue(vm.state.value.upiInvalid)
        assertTrue(patches().isEmpty())
        assertTrue(saved.isEmpty())

        env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun Mehta", "+919876543210", "arjun@okhdfc")))
        vm.onEvent(SignInNameEvent.UpiIdChanged(" arjun@okhdfc"))
        assertFalse(vm.state.value.upiInvalid)
        vm.onEvent(SignInNameEvent.Continue)
        vm.state.first { !it.saving }

        val body = patches().single().jsonBody()
        assertEquals("Arjun Mehta", body["name"]!!.jsonPrimitive.content)
        assertEquals("+919876543210", body["phone"]!!.jsonPrimitive.content)
        assertEquals("arjun@okhdfc", body["upiId"]!!.jsonPrimitive.content)
        assertEquals(1, saved.size)
    }

    @Test fun noUpiIdIsSentAsNull() = runTest {
        val vm = yourName(UserDto(ME_USER, "Arjun", null, null))
        env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun", null, null)))

        vm.onEvent(SignInNameEvent.Continue)
        vm.state.first { !it.saving }

        assertEquals(JsonNull, patches().single().jsonBody()["upiId"])
        assertEquals(1, saved.size)
    }

    @Test fun aSaveTheServerTookIsKeptWhenTheScreenGoes() = runTest {
        val session = signIn(UserDto(ME_USER, "Arjun", null, null))
        val store = ViewModelStore()
        val vm = main.kept(store) { SignInNameViewModel(session.auth, SavedStateHandle(), online) }
        vm.state.first { it.name == "Arjun" }
        // The answer comes late, so the save still runs once the server has the request.
        env.fake.on(
            "PATCH", "/v1/me",
            json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun M", null, "arjun@okhdfc")).setHeadersDelay(1, TimeUnit.SECONDS),
        )

        vm.onEvent(SignInNameEvent.NameChanged("Arjun M"))
        vm.onEvent(SignInNameEvent.UpiIdChanged("arjun@okhdfc"))
        vm.onEvent(SignInNameEvent.Continue)
        inRealTime { while (patches().isEmpty()) delay(10) }
        // The route's Back waits while this is true; a screen that goes anyway does not cut the save off.
        assertTrue(vm.state.value.saving)
        store.clear()

        inRealTime { session.settings.settings.first { it.userName == "Arjun M" } }
        assertEquals("arjun@okhdfc", session.auth.profile()!!.upiId)
        assertEquals(1, patches().size)
    }

    @Test fun theServerRefusingTheUpiIdShowsTheErrorAndOfflineTriesNothing() = runTest {
        val vm = yourName(UserDto(ME_USER, "Arjun", null, null))
        env.fake.on("PATCH", "/v1/me", json(400, """{"error":{"code":"bad_request","message":"x","field":"upiId"}}"""))
        vm.onEvent(SignInNameEvent.UpiIdChanged("arjun@okhdfc"))
        vm.onEvent(SignInNameEvent.Continue)
        assertTrue(vm.state.first { !it.saving && it.upiInvalid }.problem == null)

        online.value = false
        vm.onEvent(SignInNameEvent.UpiIdChanged("arjun@okaxis"))
        vm.onEvent(SignInNameEvent.Continue)
        assertEquals(ActionProblem.OFFLINE, vm.state.value.problem)
        assertEquals(1, patches().size)
        assertTrue(saved.isEmpty())
    }
}
