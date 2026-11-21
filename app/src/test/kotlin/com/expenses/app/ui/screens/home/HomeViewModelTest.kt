package com.expenses.app.ui.screens.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.expenses.app.data.category.Category
import com.expenses.app.cardbill.BillState
import com.expenses.app.cardbill.CardBillReader
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.AccountType
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.CardBillRepository
import com.expenses.app.data.repository.TestZone
import com.expenses.app.data.repository.nov
import com.expenses.app.data.settings.AppSettings
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.data.settings.SettingsRepository
import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.auth.AuthState
import com.expenses.app.ui.screens.activity.ActivityItemDetail
import com.expenses.app.ui.screens.activity.ActivityItemId
import com.expenses.app.ui.screens.family.ClearedViewModels
import com.expenses.splits.SplitMethod
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/** Home with splitting: the split tiles, "Last time you split", the offline banner and split rows. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    private val env = GroupsTestEnv()
    private val folder: File = Files.createTempDirectory("home").toFile()
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ME_USER, "Arjun"))
    private val online = MutableStateFlow(true)
    private val homeView = MutableStateFlow(HomeView.Me)
    private val viewModels = ClearedViewModels()
    private val cardBillSettings = MutableStateFlow(AppSettings(onboardingDone = true, trackingMode = TrackingMode.SMS))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
        env.close()
        folder.deleteRecursively()
    }

    private fun TestScope.viewModel(): HomeViewModel {
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") },
        )
        val reader = CardBillReader(
            CardBillRepository(env.db.cardBillDao(), env.transactions),
            AccountRepository(env.db.accountDao(), env.transactions, env.clock),
            env.expenses,
            env.groups::observeOwedOn,
            auth.map { it is AuthState.SignedIn },
            cardBillSettings,
            TestZone,
        )
        val vm = viewModels.keep {
            HomeViewModel(
                env.expenses, settings, env.clock, env.groups, auth, online, env.family, homeView,
                cardBills = { today -> reader.observeHomeBills(today) },
            )
        }
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun HomeViewModel.tracking(predicate: (HomeContent.Tracking) -> Boolean = { true }): HomeContent.Tracking =
        state.first { (it?.content as? HomeContent.Tracking)?.let(predicate) == true }!!.content as HomeContent.Tracking

    /** Flat 302, and the person's ₹450 at Swiggy split equally three ways: they are owed ₹300. Returns the payment. */
    private suspend fun splitSwiggy(rememberRule: Boolean = false): Long {
        env.seedGroup(G1)
        val payment = env.smsPayment(45_000, "Swiggy")
        env.groups.splitPayment(
            payment, G1, SplitMethod.EQUAL, mapOf(M_ME to null, M_RAHUL to null, M_PRIYA to null), rememberRule = rememberRule, auto = false,
        )
        return payment
    }

    @Test fun tilesAreLeftOutForSomeoneWhoSplitsWithNobody() = runTest {
        env.smsPayment(30_000, "Uber")
        val vm = viewModel()
        assertNull(vm.tracking { it.detected != null }.splitTotals)
    }

    @Test fun tilesShowTheTotalsWhileSignedInAndGoWhenSignedOut() = runTest {
        splitSwiggy()
        val vm = viewModel()
        assertEquals(SplitTotals(owedToYouMinor = 30_000, youOweMinor = 0), vm.tracking { it.splitTotals != null }.splitTotals)
        auth.value = AuthState.SignedOut
        vm.tracking { it.splitTotals == null }
    }

    @Test fun theLastSplitGroupIsNamedUnderTheNextPaymentToTheSameMerchant() = runTest {
        splitSwiggy(rememberRule = true)
        env.smsPayment(30_000, "Swiggy")
        val vm = viewModel()
        val detected = vm.tracking { it.detected?.lastSplitWith != null }.detected!!
        assertEquals("Swiggy", detected.merchant)
        assertEquals("Flat 302", detected.lastSplitWith)
        // Signed out: no line.
        auth.value = AuthState.SignedOut
        assertNull(vm.tracking { it.detected?.lastSplitWith == null }.detected!!.lastSplitWith)
    }

    @Test fun noRuleNoLine() = runTest {
        splitSwiggy(rememberRule = false)
        env.smsPayment(30_000, "Swiggy")
        val vm = viewModel()
        val tracking = vm.tracking { it.detected != null && it.splitTotals != null }
        assertNull(tracking.detected!!.lastSplitWith)
    }

    @Test fun theOfflineBannerShowsOnlyWhileSignedIn() = runTest {
        online.value = false
        val vm = viewModel()
        assertTrue(vm.state.first { it != null }!!.offline)
        auth.value = AuthState.SignedOut
        vm.state.first { it?.offline == false }
        auth.value = AuthState.SignedIn(ME_USER, "Arjun")
        vm.state.first { it?.offline == true }
        online.value = true
        assertFalse(vm.state.first { it?.offline == false }!!.offline)
    }

    @Test fun aSplitPaymentReadsAsSharedInLatest() = runTest {
        val payment = splitSwiggy()
        val vm = viewModel()
        val row = vm.tracking { content -> content.latest.any { it.detail is ActivityItemDetail.Split } }
            .latest.single { it.id == ActivityItemId.Expense(payment) }
        assertEquals(ActivityItemDetail.Split(Category.FOOD, 45_000, "Flat 302"), row.detail)
        assertEquals(15_000L, row.amountPaise)
    }
    // ---- The Family side (docs/family.md 1.5; 02-everyday/51, 06-family/02 to 07) ----

    private suspend fun HomeViewModel.familySide(predicate: (HomeUiState) -> Boolean = { true }): HomeUiState =
        state.first { it != null && it.view == HomeView.Family && predicate(it) }!!

    private val HomeUiState.familyMonth: HomeContent.FamilyMonth get() = content as HomeContent.FamilyMonth

    /** A Family payment of this phone, shared in the family month on [day] Nov. */
    private suspend fun sharedPayment(amountMinor: Long = 234_000, day: Int = 15): Long {
        val id = env.familyPayment(amountMinor, day = day)
        env.transactions.run { env.familyLinker.reconcile() }
        return id
    }

    @Test fun theToggleSideIsTheAppScopedOne() = runTest {
        val vm = viewModel()
        vm.state.first { it != null }
        vm.onEvent(HomeEvent.ViewChanged(HomeView.Family))
        assertEquals(HomeView.Family, homeView.value)
        assertEquals(HomeView.Family, vm.state.first { it?.view == HomeView.Family }!!.view)
    }

    @Test fun withNoFamilyTheFamilySideShowsThisPhonesFamilyExpensesAndTheSetUpCard() = runTest {
        env.familyPayment(234_000, day = 15)
        homeView.value = HomeView.Family
        val vm = viewModel()

        val shown = vm.familySide { it.spentMinor == 234_000L }
        assertEquals(FamilyCard.SetUp, shown.familyCard)
        assertEquals(FamilyCaption.Local, shown.familyCaption)
        assertTrue(shown.content is HomeContent.Tracking)
        // The Me side has neither.
        homeView.value = HomeView.Me
        val me = vm.state.first { it?.view == HomeView.Me }!!
        assertNull(me.familyCard)
        assertNull(me.familyCaption)
    }

    @Test fun waitingForThePartner() = runTest {
        env.seedFamily(partner = false, placeholder = true)
        sharedPayment()
        homeView.value = HomeView.Family
        val vm = viewModel()

        val shown = vm.familySide { it.content is HomeContent.FamilyMonth && it.familyMonth.latest.isNotEmpty() }
        assertEquals(FamilyCard.Waiting, shown.familyCard)
        assertEquals(FamilyCaption.Alone(1), shown.familyCaption)
        assertEquals(234_000L, shown.spentMinor)
        val person = shown.familyMonth.people.single()
        assertTrue(person.isMe)
        assertEquals(1, person.count)
        assertTrue(shown.familyMonth.latest.single().paidByMe)
    }

    @Test fun aloneTheCardOffersInvitePartner() = runTest {
        env.seedFamily(partner = false, placeholder = false)
        homeView.value = HomeView.Family
        val vm = viewModel()

        val shown = vm.familySide { it.content is HomeContent.FamilyMonth }
        assertEquals(FamilyCard.Invite("Home"), shown.familyCard)
        assertEquals(FamilyCaption.Alone(0), shown.familyCaption)
    }

    @Test fun togetherNoCardAndBothPeopleEvenAtNothing() = runTest {
        env.seedFamily()
        homeView.value = HomeView.Family
        val vm = viewModel()

        val empty = vm.familySide { it.content is HomeContent.FamilyMonth && it.familyMonth.people.size == 2 }
        assertNull(empty.familyCard)
        assertEquals(FamilyCaption.Together("Arjun", "Meera", 0), empty.familyCaption)
        assertEquals(0L, empty.spentMinor)
        assertTrue(empty.familyMonth.latest.isEmpty())
        assertTrue(empty.familyMonth.topCategories.isEmpty())
        assertTrue(empty.familyMonth.people.all { it.count == 0 && it.totalMinor == 0L })

        sharedPayment(99_000, day = 9)
        val one = vm.familySide { it.spentMinor == 99_000L }
        assertEquals(FamilyCaption.Together("Arjun", "Meera", 1), one.familyCaption)
        assertEquals(Category.GROCERIES, one.familyMonth.topCategories.single().category)
    }

    @Test fun theFirstPullShowsThePlaceholdersAndNoCard() = runTest {
        env.seedFamily(version = 0, latestVersion = 4, partner = false, placeholder = false)
        homeView.value = HomeView.Family
        val vm = viewModel()

        val shown = vm.familySide { it.content is HomeContent.FamilyMonth }
        assertTrue(shown.familyMonth.loading)
        assertNull(shown.familyCard)
    }

    @Test fun offlineOnTheFamilySideTheBannerShows() = runTest {
        env.seedFamily()
        homeView.value = HomeView.Family
        online.value = false
        val vm = viewModel()

        val shown = vm.familySide { it.content is HomeContent.FamilyMonth }
        assertTrue(shown.offline)
    }

    // ---- Card bills (docs/card-bills.md 2.4; 02-everyday/145) ----

    private suspend fun cardWithBill(name: String, last4: String, due: Int, total: Long, smsId: Long): Long {
        val card = env.db.accountDao().insert(
            Account(bank = "$name Bank", type = AccountType.CREDIT_CARD, last4 = last4, displayName = name, firstSeenAt = nov(1)),
        )
        return env.db.cardBillDao().upsert(
            CardBill(
                accountId = card, bank = "$name Bank", cardLast4 = last4, totalDueMinor = total, minimumDueMinor = null,
                dueDate = LocalDate.of(2026, 11, due), receivedAt = nov(2), smsId = smsId,
            ),
        )
    }

    @Test fun cardBillsShowAboveLatestSoonestFirst() = runTest {
        env.smsPayment(30_000, "Uber", status = ExpenseStatus.ADDED)
        val icici = cardWithBill("ICICI Amazon Pay", "1100", due = 28, total = 648_000, smsId = 91)
        val hdfc = cardWithBill("HDFC Millennia", "2217", due = 21, total = 2_430_000, smsId = 92)
        val vm = viewModel()
        val shown = vm.state.first { it?.cardBills?.size == 2 }!!
        assertEquals(listOf(hdfc, icici), shown.cardBills.map { it.billId })
        assertEquals(listOf("HDFC Millennia", "ICICI Amazon Pay"), shown.cardBills.map { it.cardName })
        // 16 Nov: due in 5 and 12 days.
        assertEquals(listOf<BillState>(BillState.DueIn(5), BillState.DueIn(12)), shown.cardBills.map { it.state })
        assertTrue(shown.content is HomeContent.Tracking)
    }

    @Test fun cardBillsAreLeftOutOnTheFamilySide() = runTest {
        cardWithBill("HDFC Millennia", "2217", due = 21, total = 2_430_000, smsId = 92)
        val vm = viewModel()
        vm.state.first { it?.cardBills?.size == 1 }
        homeView.value = HomeView.Family
        assertTrue(vm.familySide().cardBills.isEmpty())
    }

    @Test fun aCardBillAloneIsNotAFreshStart() = runTest {
        cardWithBill("HDFC Millennia", "2217", due = 21, total = 2_430_000, smsId = 92)
        val vm = viewModel()
        assertTrue(vm.state.first { it?.cardBills?.size == 1 }!!.content is HomeContent.Tracking)
    }
}
