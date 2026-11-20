package com.expenses.app.groups.match

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.expenses.app.MainActivity
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.data.model.PaidWith
import com.expenses.app.data.repository.TestClock
import com.expenses.app.groups.G1
import com.expenses.app.groups.G2
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.M_ME
import com.expenses.app.groups.M_ME2
import com.expenses.app.groups.M_PRIYA
import com.expenses.app.groups.M_RAHUL
import com.expenses.app.groups.M_RAHUL2
import com.expenses.app.groups.RAHUL_USER
import com.expenses.app.groups.error
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.SettleMethod
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.data.SyncState
import com.expenses.app.ingest.IngestResult
import com.expenses.app.ui.nav.AppLink
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settling up from SMS on the phone (docs/server.md 9.5, docs/groups-build-plan.md 4.4 task 3). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettlementMatcherTest {
    private val clock = MovingClock(TestClock.instant(), TestClock.zone)
    private val env = GroupsTestEnv(clock)
    private val extras = env.db.groupExtrasDao()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val matcher = SettlementMatcher(context, env.groups, env.expenses, extras, env.signedIn, clock)

    @Before fun allowNotifications() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @After fun close() = env.close()

    private suspend fun settlements(groupId: String = G1): List<SettlementEntity> = env.groups.observeGroup(groupId).first()!!.settlements

    private suspend fun debits(vararg ids: Long) = IngestResult(detected = ids.map { env.expenses.get(it)!! })

    private suspend fun credits(vararg ids: Long) = IngestResult(credits = ids.map { env.expenses.get(it)!! })

    /** The notifications posted, without the summary that heads their bundle (it is posted with every one). */
    private fun notifications(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun Notification.opens(link: AppLink): Boolean =
        shadowOf(contentIntent).savedIntent.filterEquals(MainActivity.openLinkIntent(context, link))

    /** The person owes Rahul ₹1,200 in Flat 302 and has tapped Pay by UPI. */
    private suspend fun payingRahul() {
        env.seedGroup()
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        matcher.startUpiPayment(G1, M_RAHUL, 120_000)
    }

    @Test fun aUpiDebitWithin30MinutesSettlesAndIsNotSpending() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(10))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())

        assertEquals(setOf(debit), matcher.onNewPayments(debits(debit)))

        val settlement = settlements().single()
        assertEquals(M_ME, settlement.fromMemberId)
        assertEquals(M_RAHUL, settlement.toMemberId)
        assertEquals(120_000L, settlement.amountMinor)
        assertEquals(SettleMethod.UPI, settlement.method)
        assertTrue(settlement.matchedFromSms)
        assertEquals(ExpenseStatus.IGNORED, env.expenses.get(debit)!!.status)
        assertTrue(extras.allPendingUpi().isEmpty())
        assertEquals(0L, env.groups.observeGroup(G1).first()!!.members.single { it.member.id == M_RAHUL }.pairMinor)
        // The payer's side: "Both people are notified" (flow lane 05).
        val posted = notifications().single()
        assertEquals("You paid Rahul ₹1,200", posted.title())
        assertEquals("Flat 302 balance cleared. Nothing to tick.", posted.text())
        assertTrue(posted.opens(AppLink.Group(G1)))
    }

    @Test fun anSmsWithoutAUpiIdStillMatchesOnTheAmount() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(29))
        val debit = env.smsDebit(120_000, vpa = null, at = clock.instant())

        assertEquals(setOf(debit), matcher.onNewPayments(debits(debit)))
        assertEquals(1, settlements().size)
    }

    @Test fun aDebitAfter30MinutesIsSpendingAndTheOldPaymentIsForgotten() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(31))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())

        assertTrue(matcher.onNewPayments(debits(debit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertTrue(extras.allPendingUpi().isEmpty())
        assertTrue(notifications().isEmpty())
    }

    @Test fun anotherAmountIsNotMatched() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(110_000, "rahul@okhdfc", clock.instant())

        assertTrue(matcher.onNewPayments(debits(debit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertEquals(1, extras.allPendingUpi().size)
    }

    @Test fun anotherUpiIdIsNotMatched() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, "someone.else@okicici", clock.instant())

        assertTrue(matcher.onNewPayments(debits(debit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertEquals(1, extras.allPendingUpi().size)
    }

    @Test fun aCardPaymentOfTheSameAmountIsSpending() = runTest {
        // Pay by UPI was cancelled in the UPI app, then ₹1,200 was paid by card at a shop: a card SMS shows no UPI ID.
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, vpa = null, at = clock.instant(), counterparty = "CROMA", paidWith = PaidWith.CARD)

        assertTrue(matcher.onNewPayments(debits(debit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertEquals(1, extras.allPendingUpi().size)
        assertTrue(notifications().isEmpty())
    }

    @Test fun aBankTransferOfTheSameAmountIsNotTakenForUpi() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, vpa = null, at = clock.instant(), counterparty = "LANDLORD", paidWith = PaidWith.BANK_TRANSFER)

        assertTrue(matcher.onNewPayments(debits(debit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertEquals(1, extras.allPendingUpi().size)
    }

    @Test fun aDebitWhoseSmsNamesNoChannelStillMatches() = runTest {
        // Some banks' UPI SMS ("Sent Rs.1200.00 ... To RAHUL") name neither UPI nor a UPI ID.
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, vpa = null, at = clock.instant(), paidWith = PaidWith.OTHER)

        assertEquals(setOf(debit), matcher.onNewPayments(debits(debit)))
        assertEquals(1, settlements().size)
    }

    @Test fun signedOutMatchesNothing() = runTest {
        payingRahul()
        env.seedGoaWithRahul()
        env.groupExpense(G2, M_ME2, 160_000, listOf(M_ME2, M_RAHUL2))
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())
        val credit = env.smsCredit(80_000, "RAHUL KUMAR")
        env.me = null

        assertTrue(matcher.onNewPayments(IngestResult(detected = listOf(env.expenses.get(debit)!!), credits = listOf(env.expenses.get(credit)!!))).isEmpty())

        assertTrue(settlements(G1).isEmpty())
        assertTrue(settlements(G2).isEmpty())
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertEquals(1, extras.allPendingUpi().size)
        assertTrue(notifications().isEmpty())
    }

    @Test fun aDebitAfterTheBalanceWasMarkedPaidDoesNotSettleTwice() = runTest {
        payingRahul()
        env.groups.recordSettlement(G1, M_ME, M_RAHUL, 120_000, SettleMethod.OTHER, matchedFromSms = false)
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())

        assertTrue(matcher.onNewPayments(debits(debit)).isEmpty())

        assertEquals(1, settlements().size)
        assertEquals(ExpenseStatus.DETECTED, env.expenses.get(debit)!!.status)
        assertTrue(extras.allPendingUpi().isEmpty())
    }

    @Test fun aCreditThatNamesTheFriendSettlesEveryGroupWithOneNotification() = runTest {
        env.seedGroup()
        env.seedGoaWithRahul()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        env.groupExpense(G2, M_ME2, 160_000, listOf(M_ME2, M_RAHUL2))
        val credit = env.smsCredit(200_000, "RAHUL KUMAR", "rahulk@okaxis")

        assertEquals(setOf(credit), matcher.onNewPayments(credits(credit)))

        val inFlat = settlements(G1).single()
        assertEquals(M_RAHUL, inFlat.fromMemberId)
        assertEquals(M_ME, inFlat.toMemberId)
        assertEquals(120_000L, inFlat.amountMinor)
        assertEquals(SettleMethod.UPI, inFlat.method)
        assertTrue(inFlat.matchedFromSms)
        // The person received it and the SMS shows it: confirmed at once (docs/server.md 4.8).
        assertNotNull(inFlat.confirmedAt)
        val inGoa = settlements(G2).single()
        assertEquals(M_RAHUL2, inGoa.fromMemberId)
        assertEquals(M_ME2, inGoa.toMemberId)
        assertEquals(80_000L, inGoa.amountMinor)
        assertTrue(env.groups.observeFriends().first().none { it.name == "Rahul" })
        // One credit, one notification, naming the group with the largest transfer.
        val posted = notifications().single()
        assertEquals("Rahul paid you ₹2,000", posted.title())
        assertEquals("Balances in 2 groups cleared. Nothing to tick.", posted.text())
        assertTrue(posted.opens(AppLink.AllSquare(G1, M_RAHUL, 200_000)))
        // Money in stays money in.
        assertEquals(ExpenseStatus.ADDED, env.expenses.get(credit)!!.status)
    }

    @Test fun aCreditOfOneGroupsAmountSettlesThatGroupOnly() = runTest {
        env.seedGroup()
        env.seedGoaWithRahul()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        env.groupExpense(G2, M_ME2, 160_000, listOf(M_ME2, M_RAHUL2))
        val credit = env.smsCredit(120_000, "Rahul")

        assertEquals(setOf(credit), matcher.onNewPayments(credits(credit)))

        assertEquals(1, settlements(G1).size)
        assertTrue(settlements(G2).isEmpty())
        val posted = notifications().single()
        assertEquals("Rahul paid you ₹1,200", posted.title())
        assertEquals("Flat 302 balance cleared. Nothing to tick.", posted.text())
        assertTrue(posted.opens(AppLink.AllSquare(G1, M_RAHUL, 120_000)))
    }

    @Test fun anUnclearNameAsksAndSettlesNothing() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "NEFT HDFC0001234")

        assertTrue(matcher.onNewPayments(credits(credit)).isEmpty())

        assertTrue(settlements().isEmpty())
        val posted = notifications().single()
        assertEquals("Was this Rahul paying you back?", posted.title())
        assertEquals("₹1,200 arrived. It matches what Rahul owes.", posted.text())
        assertTrue(posted.opens(AppLink.MoneyReceived(credit)))
    }

    @Test fun aNameThatIsNotTheFriendsFirstNameIsUnclear() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "SURESH KUMAR")

        assertTrue(matcher.onNewPayments(credits(credit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertEquals("Was this Rahul paying you back?", notifications().single().title())
    }

    @Test fun twoFriendsOwingTheSameAmountClearTheOneNamed() = runTest {
        env.seedGroup()
        // Rahul and Priya each owe ₹1,000.
        env.groupExpense(G1, M_ME, 300_000, listOf(M_ME, M_RAHUL, M_PRIYA))
        val credit = env.smsCredit(100_000, "PRIYA S")

        assertEquals(setOf(credit), matcher.onNewPayments(credits(credit)))

        val settled = settlements().single()
        assertEquals(M_PRIYA, settled.fromMemberId)
        assertEquals("Priya paid you ₹1,000", notifications().single().title())
    }

    @Test fun aCreditReadDaysLaterSettlesNothingAndAsksNothing() = runTest {
        // The app catches up on SMS after being stopped: money that arrived two days ago may predate the debt.
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val named = env.smsCredit(120_000, "Rahul", at = clock.instant().minus(Duration.ofDays(2)))

        assertTrue(matcher.onNewPayments(credits(named)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertTrue(notifications().isEmpty())
        assertEquals(120_000L, env.groups.observeGroup(G1).first()!!.members.single { it.member.id == M_RAHUL }.pairMinor)
    }

    @Test fun aCreditFromEarlierTodayStillSettles() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "Rahul", at = clock.instant().minus(Duration.ofHours(20)))

        assertEquals(setOf(credit), matcher.onNewPayments(credits(credit)))
        assertEquals(1, settlements().size)
    }

    @Test fun moneyNobodyOwesIsLeftAlone() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(50_000, "Rahul")

        assertTrue(matcher.onNewPayments(credits(credit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertTrue(notifications().isEmpty())
    }

    @Test fun nothingOfTheSmsReachesTheServer() = runTest {
        env.seedGroup()
        env.seedGoaWithRahul()
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        env.groupExpense(G2, M_ME2, 160_000, listOf(M_ME2, M_RAHUL2))
        matcher.startUpiPayment(G1, M_RAHUL, 120_000)
        clock.advance(Duration.ofMinutes(3))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())
        val credit = env.smsCredit(80_000, "RAHUL KUMAR", "rahulk@okaxis")
        val handled = matcher.onNewPayments(IngestResult(detected = listOf(env.expenses.get(debit)!!), credits = listOf(env.expenses.get(credit)!!)))
        assertEquals(setOf(debit, credit), handled)

        env.engine.sync()

        val bodies = env.fake.requests.map { it.body.readUtf8() + " " + it.requestUrl }
        val settlementPuts = env.fake.requests.filter { it.method == "PUT" && it.requestUrl!!.encodedPath.contains("/settlements/") }
        assertEquals(2, settlementPuts.size)
        for (secret in listOf(SMS_TEXT_MARK, "VM-HDFCBK", "112233445566", "998877665544", "RAHUL KUMAR", "rahulk@okaxis", "debited", "credited")) {
            assertFalse("the server got \"$secret\"", bodies.any { it.contains(secret, ignoreCase = true) })
        }
    }

    @Test fun thePayersDebitAndTheReceiversCreditAreOneSettlement() = runTest {
        // This phone pays Rahul ₹1,200 by UPI and reads the bank's debit SMS.
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())
        matcher.onNewPayments(debits(debit))
        val paid = settlements().single()

        // Rahul's phone sees the same group from his side and reads the bank's credit SMS a little later.
        val rahul = GroupsTestEnv(clock)
        try {
            val dao = rahul.db.groupDao()
            dao.upsertGroup(GroupEntity(G1, GroupKind.GROUP, "Flat 302", ME_USER, M_RAHUL, 5, 5, syncState = SyncState.SYNCED, createdAt = Instant.EPOCH))
            dao.upsertMembers(
                listOf(
                    MemberEntity(M_RAHUL, G1, RAHUL_USER, "Rahul", null, "rahul@okhdfc", onApp = true, removed = false, version = 1),
                    MemberEntity(M_ME, G1, ME_USER, "Arjun", null, null, onApp = true, removed = false, version = 1),
                ),
            )
            rahul.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
            clock.advance(Duration.ofSeconds(20))
            val credit = rahul.smsCredit(120_000, "ARJUN")
            val rahulsMatcher = SettlementMatcher(context, rahul.groups, rahul.expenses, rahul.db.groupExtrasDao(), rahul.signedIn, clock)

            assertEquals(setOf(credit), rahulsMatcher.onNewPayments(IngestResult(credits = listOf(rahul.expenses.get(credit)!!))))

            // The same id on both phones: the server keeps one settlement, not two (docs/server.md 9.5).
            val received = rahul.groups.observeGroup(G1).first()!!.settlements.single()
            assertEquals(paid.id, received.id)
            assertEquals(M_ME, received.fromMemberId)
            assertEquals(M_RAHUL, received.toMemberId)
            assertEquals(paid.amountMinor, received.amountMinor)
        } finally {
            rahul.close()
        }
    }

    @Test fun moneyIntoAnAccountSwitchedOffSettlesNothingAndPostsNothing() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "RAHUL", accountId = env.untrackedAccount())

        assertTrue(matcher.onNewPayments(credits(credit)).isEmpty())

        assertTrue(settlements().isEmpty())
        assertTrue(notifications().isEmpty())
        assertEquals(120_000L, env.groups.observeGroup(G1).first()!!.members.single { it.member.id == M_RAHUL }.pairMinor)
        // Nothing was queued for the server either.
        assertTrue(env.db.outboxDao().all().none { it.path.contains("/settlements/") })
    }

    @Test fun anUnclearCreditIntoAnAccountSwitchedOffAsksNothing() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "NEFT HDFC0001234", accountId = env.untrackedAccount())

        assertTrue(matcher.onNewPayments(credits(credit)).isEmpty())

        assertTrue(notifications().isEmpty())
    }

    @Test fun aSettlementMatchedFromSmsIsDatedTheDayTheSmsArrived() = runTest {
        // 20 hours before noon on 16 Nov is 15 Nov in India.
        env.seedGroup()
        env.groupExpense(G1, M_ME, 240_000, listOf(M_ME, M_RAHUL))
        val credit = env.smsCredit(120_000, "Rahul", at = clock.instant().minus(Duration.ofHours(20)))

        assertEquals(setOf(credit), matcher.onNewPayments(credits(credit)))

        val settlement = settlements().single()
        assertEquals(LocalDate.of(2026, 11, 15), settlement.occurredOn)
        assertEquals(GroupsRepository.smsSettlementId(G1, M_RAHUL, M_ME, 120_000, LocalDate.of(2026, 11, 15)), settlement.id)
    }

    @Test fun twoEqualRepaymentsOnOneDayAreTwoSettlements() = runTest {
        payingRahul()
        clock.advance(Duration.ofMinutes(5))
        val first = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())
        assertEquals(setOf(first), matcher.onNewPayments(debits(first)))

        // Rahul pays for another dinner, and the person pays him back the same amount later that day.
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        clock.advance(Duration.ofHours(1))
        matcher.startUpiPayment(G1, M_RAHUL, 120_000)
        clock.advance(Duration.ofMinutes(5))
        val second = env.smsDebit(120_000, "rahul@okhdfc", clock.instant(), counterparty = "RAHUL K")
        assertEquals(setOf(second), matcher.onNewPayments(debits(second)))

        val both = settlements()
        assertEquals(2, both.size)
        val day = clock.instant().atZone(clock.zone).toLocalDate()
        assertEquals(
            setOf(
                GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day),
                GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day, n = 1),
            ),
            both.map { it.id }.toSet(),
        )
        assertEquals(0L, env.groups.observeGroup(G1).first()!!.members.single { it.member.id == M_RAHUL }.pairMinor)
        assertEquals(ExpenseStatus.IGNORED, env.expenses.get(second)!!.status)
    }

    @Test fun aSettlementOnThePhoneIsNeverReplaced() = runTest {
        env.seedGroup()
        val id = env.groups.recordSettlement(G1, M_ME, M_RAHUL, 50_000, SettleMethod.CASH, matchedFromSms = false)

        val refused = runCatching { env.groups.recordSettlement(G1, M_ME, M_RAHUL, 70_000, SettleMethod.UPI, matchedFromSms = true, id = id) }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)

        val kept = settlements().single()
        assertEquals(50_000L, kept.amountMinor)
        assertEquals(SettleMethod.CASH, kept.method)
    }

    @Test fun aDebitAfterTheFriendsSmsRecordArrivedIsThatPayment() = runTest {
        // Rahul's phone recorded his ₹1,200 from its credit SMS first, and this phone pulled it.
        payingRahul()
        val day = clock.instant().atZone(clock.zone).toLocalDate()
        val friends = GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day)
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                friends, G1, M_ME, M_RAHUL, 120_000, SettleMethod.OTHER, matchedFromSms = true, occurredOn = day,
                recordedByMemberId = M_RAHUL, confirmedAt = clock.instant().plus(Duration.ofMinutes(2)), deleted = false,
                version = 6, syncState = SyncState.SYNCED,
            ),
        )
        clock.advance(Duration.ofMinutes(5))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())

        // The debit is that payment: not spending, and nothing new goes to the server.
        assertEquals(setOf(debit), matcher.onNewPayments(debits(debit)))

        assertEquals(listOf(friends), settlements().map { it.id })
        assertEquals(ExpenseStatus.IGNORED, env.expenses.get(debit)!!.status)
        assertTrue(extras.allPendingUpi().isEmpty())
        assertTrue(env.db.outboxDao().all().none { it.path.contains("/settlements/") })
        // Rahul's record already told the person; "You paid Rahul" would say it twice.
        assertTrue(notifications().isEmpty())
    }

    @Test fun aDebitAfterTheFriendsRecordArrivedIsThatPaymentEvenWithANewDebt() = runTest {
        payingRahul()
        val day = clock.instant().atZone(clock.zone).toLocalDate()
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day), G1, M_ME, M_RAHUL, 120_000, SettleMethod.UPI,
                matchedFromSms = true, occurredOn = day, recordedByMemberId = M_RAHUL, confirmedAt = clock.instant().plus(Duration.ofSeconds(30)),
                deleted = false, version = 6, syncState = SyncState.SYNCED,
            ),
        )
        // Rahul paid for something else meanwhile: the person owes him again.
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        clock.advance(Duration.ofMinutes(2))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())

        assertEquals(setOf(debit), matcher.onNewPayments(debits(debit)))

        assertEquals(1, settlements().size)
        assertEquals(-120_000L, env.groups.observeGroup(G1).first()!!.members.single { it.member.id == M_RAHUL }.pairMinor)
    }

    @Test fun theFriendsRecordOfAnEarlierRepaymentIsNotThisOne() = runTest {
        env.seedGroup()
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        val day = clock.instant().atZone(clock.zone).toLocalDate()
        // Rahul's phone recorded a repayment this morning, before today's debt and before Pay by UPI was tapped.
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day), G1, M_ME, M_RAHUL, 120_000, SettleMethod.UPI,
                matchedFromSms = true, occurredOn = day, recordedByMemberId = M_RAHUL, confirmedAt = clock.instant().minus(Duration.ofHours(2)),
                deleted = false, version = 6, syncState = SyncState.SYNCED,
            ),
        )
        env.groupExpense(G1, M_RAHUL, 240_000, listOf(M_ME, M_RAHUL))
        matcher.startUpiPayment(G1, M_RAHUL, 120_000)
        clock.advance(Duration.ofMinutes(3))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())

        assertEquals(setOf(debit), matcher.onNewPayments(debits(debit)))

        // A second settlement, under the id Rahul's phone works out for the second one of the day.
        assertEquals(2, settlements().size)
        assertNotNull(settlements().singleOrNull { it.id == GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day, n = 1) })
        assertEquals("You paid Rahul ₹1,200", notifications().single().title())
    }

    @Test fun aRefusedSecondRecordNeverRemovesTheSettlementThePhonePulled() = runTest {
        env.seedGroup()
        val day = clock.instant().atZone(clock.zone).toLocalDate()
        val earlier = GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day)
        // This morning's repayment, recorded by Rahul's phone and pulled here.
        env.db.groupDao().upsertSettlement(
            SettlementEntity(
                earlier, G1, M_ME, M_RAHUL, 120_000, SettleMethod.UPI, matchedFromSms = true, occurredOn = day,
                recordedByMemberId = M_RAHUL, confirmedAt = clock.instant().minus(Duration.ofHours(2)), deleted = false,
                version = 6, syncState = SyncState.SYNCED,
            ),
        )
        env.groupExpense(G1, M_RAHUL, 480_000, listOf(M_ME, M_RAHUL))
        env.db.outboxDao().deleteAll() // as if the expense was sent long ago
        matcher.startUpiPayment(G1, M_RAHUL, 120_000)
        clock.advance(Duration.ofMinutes(3))
        val debit = env.smsDebit(120_000, "rahul@okhdfc", clock.instant())
        matcher.onNewPayments(debits(debit))
        val second = GroupsRepository.smsSettlementId(G1, M_ME, M_RAHUL, 120_000, day, n = 1)
        // Rahul's phone recorded the second one first, and its SMS named no UPI: the server refuses this phone's copy.
        env.fake.on("PUT", "/v1/groups/$G1/settlements/$second", error(409, "exists_different"))

        env.engine.push()

        // Only the refused record goes; this morning's stays, so the balance is never counted twice.
        assertEquals(listOf(earlier), settlements().map { it.id })
        assertEquals(-120_000L, env.groups.observeGroup(G1).first()!!.members.single { it.member.id == M_RAHUL }.pairMinor)
    }
}
