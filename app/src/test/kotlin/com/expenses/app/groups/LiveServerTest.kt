package com.expenses.app.groups

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.expenses.app.data.category.Category
import com.expenses.app.data.db.AppDatabase
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.repository.RoomTransactionRunner
import com.expenses.app.data.repository.TestClock
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.auth.AuthHooks
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.GoogleIdTokens
import com.expenses.app.groups.auth.GoogleResult
import com.expenses.app.groups.auth.SignInResult
import com.expenses.app.groups.auth.SignOutResult
import com.expenses.app.groups.auth.TokenStore
import com.expenses.app.groups.auth.ValueCipher
import com.expenses.app.groups.auth.devSignIn
import com.expenses.app.groups.data.GroupExpenseDraft
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.MirrorWriter
import com.expenses.app.groups.data.NewPerson
import com.expenses.app.groups.sync.LocalGroupData
import com.expenses.app.groups.sync.Outbox
import com.expenses.app.groups.sync.SyncEngine
import com.expenses.app.groups.sync.SyncOutcome
import com.expenses.splits.SplitMethod
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDate
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * One phone, two people taking turns, against the real local server (docs/server.md 14 C): run `npm run dev` in
 * server/, then `./gradlew :app:testDebugUnitTest --tests '*LiveServerTest' -PliveApi=http://127.0.0.1:8787`.
 * Skipped without `-PliveApi`.
 *
 * Alice makes a group with Bob (no app yet), shares his link and adds an expense; she signs out, Bob signs in on the
 * same phone, joins through the link and sees her expense; he adds one; she signs in again and sees his, with her own
 * payment linked back to her typed row.
 */
@RunWith(RobolectricTestRunner::class)
class LiveServerTest {
    private val base: String? = System.getProperty("live.api")?.takeIf { it.isNotBlank() }

    private object Plain : ValueCipher {
        override fun encrypt(plain: String) = plain

        override fun decrypt(stored: String) = stored
    }

    private object NoGoogle : GoogleIdTokens {
        override val configured = false

        override suspend fun idToken(activity: android.app.Activity, nonce: String, auto: Boolean, autoSelect: Boolean) = GoogleResult.Failed

        override suspend fun clearState() = Unit
    }

    @Test fun twoPeopleShareAGroupOnOnePhone() = runTest {
        assumeTrue("set -PliveApi to run against npm run dev", base != null)
        val clock: Clock = Clock.system(TestZone)
        val folder: File = Files.createTempDirectory("live").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        val transactions = RoomTransactionRunner(db)
        val outbox = Outbox(db.outboxDao(), clock)
        val mirrors = MirrorWriter(db.expenseDao(), db.groupDao(), TestZone)
        val local = LocalGroupData(transactions, db.groupDao(), db.outboxDao(), outbox, db.groupExtrasDao(), mirrors)
        lateinit var auth: AuthRepository
        val api = GroupsApi(base!!, OkHttpClient(), { auth })
        val notifier = RecordingNotifier()
        val engine = SyncEngine(api, transactions, db.groupDao(), outbox, local, mirrors, notifier, clock)
        val hooks = object : AuthHooks {
            override suspend fun flushOutbox() = engine.flush()

            override suspend fun clearGroupData() = local.clearAll()

            override suspend fun switchAccount() = local.switchAccount()

            override suspend fun onSignedIn() = Unit

            override suspend fun onSignedOut() = Unit

            override fun clearAppData() = Unit
        }
        val store = TokenStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "auth.preferences_pb") }, Plain)
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") })
        auth = AuthRepository(api, store, NoGoogle, devSignIn(api), settings, hooks, clock, backgroundScope)
        val groups = GroupsRepository(
            api, transactions, db.groupDao(), db.groupExtrasDao(), db.expenseDao(), outbox, mirrors, local, RecordingScheduler(),
            currentUser = { auth.profile() }, clock = clock, linkHost = "links.test",
        )
        val run = Random.nextInt(100_000, 999_999)
        val today = LocalDate.now(TestZone)

        // Alice: a group with Bob, who has no app yet, his link, and an expense she paid.
        assertTrue(auth.signInDev("alice$run", "Alice") is SignInResult.Success)
        val group = groups.createGroup("Flat $run", listOf(NewPerson.Friend("Bob", null)))
        assertEquals(SyncOutcome.DONE, engine.sync())
        val bobMember = db.groupDao().membersOf(group).first { it.name == "Bob" }.id
        val aliceMember = db.groupDao().getGroup(group)!!.myMemberId
        val link = groups.shareLink(group, bobMember)
        assertTrue("link: $link", link is ApiResult.Ok)
        val token = (link as ApiResult.Ok).value.substringAfterLast('/')
        val dinner = groups.addGroupExpense(
            group,
            GroupExpenseDraft("Dinner", Category.FOOD, 45_000, today, aliceMember, SplitMethod.EQUAL, mapOf(aliceMember to null, bobMember to null)),
        )
        assertEquals(SyncOutcome.DONE, engine.sync())
        assertEquals(SignOutResult.DONE, auth.signOut())
        assertEquals(0, db.groupDao().allGroups().size)

        // Bob on the same phone: joins through the link and sees what Alice added.
        assertTrue(auth.signInDev("bob$run", "Bob") is SignInResult.Success)
        val preview = groups.previewInvite(token)
        assertTrue("preview: $preview", preview is ApiResult.Ok)
        assertEquals(22_500L, (preview as ApiResult.Ok).value.youOweMinor)
        assertEquals(ApiResult.Ok(group, 200), groups.acceptInvite(token).let { if (it is ApiResult.Ok) ApiResult.Ok(it.value, 200) else it })
        assertEquals(SyncOutcome.DONE, engine.sync())
        assertNotNull(db.groupDao().getExpense(dinner))
        val bobMirror = db.expenseDao().findByGroupExpense(dinner)!!
        assertEquals(ExpenseSource.GROUP, bobMirror.source)
        assertEquals(22_500L, bobMirror.myShareMinor)
        val taxi = groups.addGroupExpense(
            group,
            GroupExpenseDraft("Taxi", Category.TRAVEL, 10_000, today, bobMember, SplitMethod.EQUAL, mapOf(aliceMember to null, bobMember to null)),
        )
        assertEquals(SyncOutcome.DONE, engine.sync())
        assertEquals(-17_500L, groups.observeGroup(group).first()!!.myNetMinor)
        assertEquals(SignOutResult.DONE, auth.signOut())

        // Alice again: the group comes back from the server with Bob's expense; her own payment links to her row.
        assertTrue(auth.signInDev("alice$run", "Alice") is SignInResult.Success)
        assertEquals(SyncOutcome.DONE, engine.sync())
        val detail = groups.observeGroup(group).first()!!
        assertEquals(17_500L, detail.myNetMinor)
        assertEquals(setOf(dinner, taxi), detail.expenses.map { it.expense.id }.toSet())
        assertEquals(5_000L, db.expenseDao().findByGroupExpense(taxi)!!.myShareMinor)
        val mine = db.expenseDao().findByGroupExpense(dinner)!!
        assertEquals(ExpenseSource.MANUAL, mine.source)
        assertEquals(22_500L, mine.myShareMinor)
        assertTrue("no notifications on a pull from 0", notifier.events.isEmpty())

        assertEquals(SignOutResult.DONE, auth.signOut())
        db.close()
        folder.deleteRecursively()
    }
}
