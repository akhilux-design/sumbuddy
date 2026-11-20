package com.expenses.app.ui.screens.you

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.api.serverHttpClient
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.OutboxOp
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.app.ui.screens.signin.ActionProblem
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Account (04-you/02, 03, 07, 08) on a real session and outbox over the fake server. The ViewModels run on a real main
 * thread and are cleared before it goes ([MainThreadViewModels]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class YouAccountViewModelTest {
    private val env = GroupsTestEnv()
    private val folder: File = Files.createTempDirectory("account").toFile()
    private val online = MutableStateFlow(true)
    private val main = MainThreadViewModels()
    private var session: YouJoinTestAuth? = null

    @Before fun setUp() = main.start()

    @After fun tearDown() {
        main.finish(close = env::close, idle = { session?.idle() ?: true })
        folder.deleteRecursively()
    }

    /** The signed-in session of the test. */
    private val auth get() = session!!.auth

    /** Signs Arjun in on [client]. */
    private suspend fun TestScope.signIn(client: OkHttpClient = OkHttpClient()): YouJoinTestAuth =
        YouJoinTestAuth(env, backgroundScope, folder, client).also {
            session = it
            it.signIn()
        }

    /** Account as it opens, with [saved] as Android kept it; [effects] collects what it asks. */
    private fun TestScope.open(
        effects: MutableList<YouAccountEffect> = CopyOnWriteArrayList(),
        saved: SavedStateHandle = SavedStateHandle(),
    ): YouAccountViewModel {
        val vm = main.kept { YouAccountViewModel(auth, env.groups.observeUnsentCount(), online, saved) }
        // Heard at once, before the screen stops waiting.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effect.collect { effects += it } }
        return vm
    }

    /** Account for Arjun, signed in, with the form filled from his account; [effects] collects what it asks. */
    private suspend fun TestScope.account(effects: MutableList<YouAccountEffect> = CopyOnWriteArrayList()): YouAccountViewModel {
        signIn()
        val vm = open(effects)
        vm.state.first { it.name == "Arjun" }
        return vm
    }

    private fun reads() = env.fake.requestsTo("GET", "/v1/me")

    private fun patches() = env.fake.requestsTo("PATCH", "/v1/me")

    private fun answerProfile(user: UserDto) = env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), user))

    /** Answers the save with [user] a second after the request is in. */
    private fun answerProfileLate(user: UserDto) =
        env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), user).setHeadersDelay(1, TimeUnit.SECONDS))

    /** Runs [block] in real time, not the test's virtual time, giving up after 5 s. */
    private suspend fun inRealTime(block: suspend () -> Unit) = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    /** Lets the main thread run what waits for it, while the stores (on the test's thread) go on answering. */
    private suspend fun settle() = inRealTime {
        repeat(SETTLE_ROUNDS) {
            delay(SETTLE_PAUSE_MS)
            withContext(Dispatchers.Main) {}
        }
    }

    @Test fun theFormIsFilledFromTheAccount() = runTest {
        val shown = account().state.value
        assertEquals("Arjun", shown.name)
        // As the design writes it; the spaces do not matter when it is saved again.
        assertEquals("+91 98765 43210", shown.phone)
        assertEquals("arjun@okhdfc", shown.upiId)
        assertNull(shown.dialog)
        assertNull(shown.problem)
    }

    @Test fun aUpiIdThatBreaksTheRuleIsNotSaved() = runTest {
        val vm = account()
        for (bad in listOf("arjunokhdfc", "a@okaxis", "arjun@1bank", "arjun@ok hdfc", "arjun@@okhdfc", "arjun+1@okhdfc", "arjun@o")) {
            vm.onEvent(YouAccountEvent.UpiIdChanged(bad))
            assertFalse(vm.state.value.upiInvalid)
            vm.onEvent(YouAccountEvent.Save)
            assertTrue(bad, vm.state.value.upiInvalid)
        }
        assertTrue(patches().isEmpty())
        // Typing clears the error until the next Save.
        vm.onEvent(YouAccountEvent.UpiIdChanged("arjun@okaxis"))
        assertFalse(vm.state.value.upiInvalid)
    }

    @Test fun aWrongNameOrPhoneIsNotSaved() = runTest {
        val vm = account()
        vm.onEvent(YouAccountEvent.PhoneChanged("98765"))
        vm.onEvent(YouAccountEvent.Save)
        assertTrue(vm.state.value.phoneInvalid)
        vm.onEvent(YouAccountEvent.PhoneChanged(""))
        vm.onEvent(YouAccountEvent.NameChanged("   "))
        vm.onEvent(YouAccountEvent.Save)
        assertTrue(vm.state.value.nameInvalid)
        assertFalse(vm.state.value.phoneInvalid)
        assertTrue(patches().isEmpty())
    }

    @Test fun aGoodUpiIdIsSavedTrimmedAndABlankOneAsNull() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        answerProfile(UserDto(ME_USER, "Arjun M", "+919876543210", "arjun@okaxis"))

        vm.onEvent(YouAccountEvent.NameChanged("Arjun M"))
        vm.onEvent(YouAccountEvent.UpiIdChanged("  arjun@okaxis "))
        vm.onEvent(YouAccountEvent.Save)
        vm.state.first { !it.busy }

        val body = patches().single().jsonBody()
        assertEquals("Arjun M", body["name"]!!.jsonPrimitive.content)
        assertEquals("+919876543210", body["phone"]!!.jsonPrimitive.content)
        assertEquals("arjun@okaxis", body["upiId"]!!.jsonPrimitive.content)
        assertEquals(listOf(YouAccountEffect.Leave), effects.toList())
        assertEquals("arjun@okaxis", auth.profile()!!.upiId)
        // The name used on Home follows.
        assertEquals("Arjun M", session!!.settings.settings.first().userName)

        answerProfile(UserDto(ME_USER, "Arjun M", null, null))
        vm.onEvent(YouAccountEvent.PhoneChanged(""))
        vm.onEvent(YouAccountEvent.UpiIdChanged("   "))
        vm.onEvent(YouAccountEvent.Save)
        vm.state.first { !it.busy }
        val cleared = patches().last().jsonBody()
        assertEquals(JsonNull, cleared["upiId"])
        assertEquals(JsonNull, cleared["phone"])
        assertEquals(2, effects.size)
    }

    @Test fun backWaitsWhileTheSaveRuns() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        // The answer comes late, so the save still runs once the server has the request.
        answerProfileLate(UserDto(ME_USER, "Arjun M", "+919876543210", "arjun@okhdfc"))

        vm.onEvent(YouAccountEvent.NameChanged("Arjun M"))
        vm.onEvent(YouAccountEvent.Save)
        inRealTime { while (patches().isEmpty()) delay(10) }

        // Account's route lets Back go only when nothing runs (`busy`), so Back during Save does not leave.
        assertEquals(YouAccountWork.SAVING, vm.state.value.working)
        assertTrue(vm.state.value.busy)
        assertTrue(effects.isEmpty())

        vm.state.first { !it.busy }
        assertEquals(listOf(YouAccountEffect.Leave), effects.toList())
        assertEquals("Arjun M", session!!.settings.settings.first().userName)
    }

    @Test fun aSaveTheServerTookIsKeptWhenTheScreenGoes() = runTest {
        account()
        val store = ViewModelStore()
        val vm = main.kept(store) { YouAccountViewModel(auth, env.groups.observeUnsentCount(), online, SavedStateHandle()) }
        vm.state.first { it.name == "Arjun" }
        answerProfileLate(UserDto(ME_USER, "Arjun M", "+919876543210", "arjun@okaxis"))

        vm.onEvent(YouAccountEvent.NameChanged("Arjun M"))
        vm.onEvent(YouAccountEvent.UpiIdChanged("arjun@okaxis"))
        vm.onEvent(YouAccountEvent.Save)
        inRealTime { while (patches().isEmpty()) delay(10) }
        // The screen goes while the answer is on its way (a link opened over Account clears it).
        store.clear()

        // What the server took is kept on this phone: Account's fields and Home's name.
        inRealTime { session!!.settings.settings.first { it.userName == "Arjun M" } }
        assertEquals("arjun@okaxis", auth.profile()!!.upiId)
        assertEquals(1, patches().size)
    }

    @Test fun theServerRefusingTheUpiIdShowsTheSameError() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        env.fake.on(
            "PATCH", "/v1/me",
            json(400, """{"error":{"code":"bad_request","message":"Expected a UPI ID.","field":"upiId"}}"""),
        )

        vm.onEvent(YouAccountEvent.Save)

        val shown = vm.state.first { it.upiInvalid }
        assertFalse(shown.busy)
        assertNull(shown.problem)
        assertEquals(1, patches().size)
        assertTrue(effects.isEmpty())
    }

    @Test fun savingOfflineTriesNothingAndALostAnswerSaysOffline() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        online.value = false
        vm.onEvent(YouAccountEvent.Save)
        assertEquals(ActionProblem.OFFLINE, vm.state.value.problem)
        assertTrue(patches().isEmpty())

        online.value = true
        // The connection drops once the request is in (the fake server's dispatcher honours only this socket policy).
        env.fake.on("PATCH", "/v1/me", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        vm.onEvent(YouAccountEvent.Save)
        assertEquals(ActionProblem.OFFLINE, vm.state.first { !it.busy && it.problem != null }.problem)

        env.fake.on("PATCH", "/v1/me", error(409, "something_else"))
        vm.onEvent(YouAccountEvent.Save)
        assertEquals(ActionProblem.FAILED, vm.state.first { !it.busy && it.problem == ActionProblem.FAILED }.problem)
        assertTrue(effects.isEmpty())
    }

    @Test fun aSessionTheServerEndedOpensSignIn() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        // The session goes behind the screen's back (a refused refresh does the same).
        auth.signOut(force = true)

        vm.onEvent(YouAccountEvent.Save)

        vm.state.first { !it.busy }
        assertEquals(listOf(YouAccountEffect.SignIn), effects.toList())
        assertTrue(patches().isEmpty())
    }

    @Test fun signOutWithUnsentChangesAsksThenSignsOutAnyway() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        repeat(2) { env.outbox.append(G1, EntityKind.EXPENSE, "x$it", OutboxOp.CREATE, "POST", "/v1/groups/$G1/expenses", null, null) }
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))
        session!!.hooks.flushed = false

        vm.onEvent(YouAccountEvent.SignOut)
        var shown = vm.state.first { it.dialog == YouAccountDialog.UNSENT_CHANGES && it.unsentCount == 2 }
        assertFalse(shown.busy)
        assertTrue(auth.state.value is AuthState.SignedIn)
        assertTrue(env.fake.requestsTo("POST", "/v1/auth/signout").isEmpty())

        // "Stay signed in" keeps everything as it was.
        vm.onEvent(YouAccountEvent.DismissDialog)
        assertNull(vm.state.value.dialog)
        assertTrue(effects.isEmpty())

        vm.onEvent(YouAccountEvent.SignOut)
        vm.state.first { it.dialog == YouAccountDialog.UNSENT_CHANGES }
        vm.onEvent(YouAccountEvent.SignOutAnyway)
        shown = vm.state.first { !it.busy }
        assertNull(shown.dialog)
        assertEquals(listOf(YouAccountEffect.Leave), effects.toList())
        assertEquals(AuthState.SignedOut, auth.state.first { it == AuthState.SignedOut })
        assertEquals(1, env.fake.requestsTo("POST", "/v1/auth/signout").size)
        assertTrue("clearGroupData" in session!!.hooks.calls)
    }

    @Test fun signOutWithEverythingSentDoesNotAsk() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        env.fake.on("POST", "/v1/auth/signout", MockResponse().setResponseCode(204))

        vm.onEvent(YouAccountEvent.SignOut)

        vm.state.first { !it.busy }
        assertNull(vm.state.value.dialog)
        assertEquals(listOf(YouAccountEffect.Leave), effects.toList())
        auth.state.first { it == AuthState.SignedOut }
    }

    @Test fun deleteAsksFirstThenDeletesEverything() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        val vm = account(effects)
        env.fake.on("DELETE", "/v1/me", MockResponse().setResponseCode(204))

        vm.onEvent(YouAccountEvent.Delete)
        assertEquals(YouAccountDialog.DELETE, vm.state.value.dialog)
        // "Keep my account".
        vm.onEvent(YouAccountEvent.DismissDialog)
        assertNull(vm.state.value.dialog)
        assertTrue(env.fake.requestsTo("DELETE", "/v1/me").isEmpty())

        // Offline nothing is tried.
        online.value = false
        vm.onEvent(YouAccountEvent.Delete)
        vm.onEvent(YouAccountEvent.ConfirmDelete)
        assertEquals(ActionProblem.OFFLINE, vm.state.value.problem)
        assertTrue(env.fake.requestsTo("DELETE", "/v1/me").isEmpty())

        // A refusal keeps the account and says so.
        online.value = true
        env.fake.on("DELETE", "/v1/me", error(500, "internal"))
        vm.onEvent(YouAccountEvent.Delete)
        vm.onEvent(YouAccountEvent.ConfirmDelete)
        assertEquals(ActionProblem.FAILED, vm.state.first { !it.busy && it.problem == ActionProblem.FAILED }.problem)
        assertTrue(auth.state.value is AuthState.SignedIn)
        assertFalse("clearAppData" in session!!.hooks.calls)

        env.fake.on("DELETE", "/v1/me", MockResponse().setResponseCode(204))
        vm.onEvent(YouAccountEvent.Delete)
        vm.onEvent(YouAccountEvent.ConfirmDelete)
        val shown = vm.state.first { !it.busy && it.problem == null }
        assertNull(shown.dialog)
        assertEquals(listOf(YouAccountEffect.Leave), effects.toList())
        assertTrue("clearAppData" in session!!.hooks.calls)
        auth.state.first { it == AuthState.SignedOut }
    }

    @Test fun theFormAndTheDialogComeBackAfterAndroidStoppedTheApp() = runTest {
        val vm = account()
        val saved = SavedStateHandle()
        val first = open(saved = saved)
        first.state.first { it.name == "Arjun" }
        first.onEvent(YouAccountEvent.UpiIdChanged("arjun@ybl"))
        first.onEvent(YouAccountEvent.Delete)
        // The form reaches the saved state on the main thread.
        settle()

        val again = open(saved = saved)

        assertEquals("arjun@ybl", again.state.value.upiId)
        assertEquals(YouAccountDialog.DELETE, again.state.value.dialog)
        assertEquals("Arjun", vm.state.value.name)
    }

    @Test fun aChangeMadeOnAnotherPhoneShowsWhenAccountOpens() = runTest {
        signIn()
        // Arjun changed his profile on his other phone.
        val changed = UserDto(ME_USER, "Arjun Mehta", null, "arjun@ybl")
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), changed))

        val shown = open().state.first { it.name == "Arjun Mehta" }

        assertEquals("", shown.phone)
        assertEquals("arjun@ybl", shown.upiId)
        assertEquals(1, reads().size)
        // Kept on this phone too: the next Account and the name used on Home.
        assertEquals(changed, auth.profile())
        assertEquals("Arjun Mehta", session!!.settings.settings.first().userName)
        // Each time Account opens it asks again.
        open().state.first { it.name == "Arjun Mehta" }
        inRealTime { while (reads().size < 2) delay(10) }
    }

    @Test fun whatThePersonTypedStaysWhenTheServersAnswerComes() = runTest {
        signIn()
        val changed = UserDto(ME_USER, "Arjun Mehta", null, "arjun@ybl")
        env.fake.on("GET", "/v1/me", json(200, UserDto.serializer(), changed).setHeadersDelay(1, TimeUnit.SECONDS))
        val vm = open()
        vm.state.first { it.name == "Arjun" }

        vm.onEvent(YouAccountEvent.PhoneChanged("90000 00001"))
        inRealTime { session!!.settings.settings.first { it.userName == "Arjun Mehta" } }
        settle()
        assertEquals(changed, auth.profile())

        // The person's form stays as they left it; the stored profile follows the server.
        val shown = vm.state.value
        assertEquals("Arjun", shown.name)
        assertEquals("90000 00001", shown.phone)
        assertEquals("arjun@okhdfc", shown.upiId)
    }

    @Test fun anUnansweredReadLeavesTheFormAsStored() = runTest {
        signIn()
        env.fake.on("GET", "/v1/me", error(503, "internal"))

        val vm = open()
        vm.state.first { it.name == "Arjun" }
        inRealTime { while (reads().isEmpty()) delay(10) }
        settle()

        assertEquals("arjun@okhdfc", vm.state.value.upiId)
        assertEquals("Arjun", auth.profile()!!.name)
    }

    @Test fun backWaitsNoLongerThanTheCallTimeout() = runTest {
        val effects = CopyOnWriteArrayList<YouAccountEffect>()
        signIn(serverHttpClient(callTimeoutMs = CALL_TIMEOUT_MS))
        val vm = open(effects)
        vm.state.first { it.name == "Arjun" }
        // The server takes the save but its answer never comes in time.
        env.fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun M", null, null)).setHeadersDelay(3, TimeUnit.SECONDS))

        vm.onEvent(YouAccountEvent.NameChanged("Arjun M"))
        vm.onEvent(YouAccountEvent.Save)
        assertTrue(vm.state.value.busy)
        val started = System.nanoTime()
        inRealTime { vm.state.first { !it.busy } }
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        // Back is free again after the call timeout, and the banner says to check the connection.
        assertTrue("waited $waitedMs ms", waitedMs < 2_500)
        assertEquals(ActionProblem.OFFLINE, vm.state.value.problem)
        assertTrue(effects.isEmpty())
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 500L
        const val SETTLE_ROUNDS = 3
        const val SETTLE_PAUSE_MS = 20L
    }
}
