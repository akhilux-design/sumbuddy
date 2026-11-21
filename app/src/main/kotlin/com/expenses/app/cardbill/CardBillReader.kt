package com.expenses.app.cardbill

import com.expenses.app.data.model.Account
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.Expense
import com.expenses.app.data.repository.AccountRepository
import com.expenses.app.data.repository.CardBillRepository
import com.expenses.app.data.repository.ExpenseRepository
import com.expenses.app.data.settings.AppSettings
import com.expenses.app.data.settings.TrackingMode
import com.expenses.app.groups.data.OwedPart
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One card bill with everything the Card bill view, Home and the reminder show of it (docs/card-bills.md 2.3).
 *
 * @param bill the bill.
 * @param account its card.
 * @param statementDate the end of the bill's cycle ([CardBillMath.statementDate]).
 * @param paidOn the day the card's payments reached the bill, or null.
 * @param signedIn whether someone is signed in: signed out, nothing of groups is on the phone.
 * @param friends who owes what of it, the largest first; empty signed out.
 * @param current true while it is the card's latest bill ([CardBillMath.isCurrent]).
 */
data class CardBillDetail(
    val bill: CardBill,
    val account: Account,
    val statementDate: LocalDate,
    val paidOn: LocalDate?,
    val signedIn: Boolean,
    val friends: List<FriendOwes>,
    val current: Boolean,
) {
    /** The card's name, or null when neither the person nor the SMS named it. */
    val cardName: String? get() = CardBillMath.cardName(account, bill)

    /** The card's last digits, or null when unknown. */
    val last4: String? get() = account.last4 ?: bill.cardLast4

    /** What friends owe of it altogether, in paise. */
    val friendsMinor: Long get() = CardBillMath.friendsTotal(friends)

    /** The person's own part of it, in paise. */
    val ownMinor: Long get() = CardBillMath.ownMinor(bill.totalDueMinor, friendsMinor)

    /** Where it stands on [today]. */
    fun state(today: LocalDate): BillState = CardBillMath.state(bill, paidOn, today)

    /** The facts the Home list and the reminder rules read. */
    val facts: BillFacts get() = BillFacts(bill, account, paidOn, friendsMinor)
}

/**
 * One row of Home's CARD BILLS list (docs/card-bills.md 2.4).
 *
 * @param billId the bill; a tap opens its view.
 * @param cardName the card's name, or null for neither.
 * @param totalMinor the bill, in paise.
 * @param state where it stands today.
 * @param friendsMinor what friends owe of it, in paise.
 */
data class CardBillSummary(
    val billId: Long,
    val cardName: String?,
    val totalMinor: Long,
    val state: BillState,
    val friendsMinor: Long,
)

/**
 * Reads card bills together with their card, the card's payments and credits, what friends owe for the split ones,
 * the session and the settings (docs/card-bills.md 2.10). Everything stays on the phone.
 *
 * @param bills the stored bills.
 * @param accounts the cards.
 * @param expenses the payments and credits on each card.
 * @param owedOn what friends owe for a set of group expenses (`GroupsRepository.observeOwedOn`).
 * @param signedIn whether someone is signed in.
 * @param settings the person's settings (tracking mode).
 * @param zone the app's time zone, India.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardBillReader(
    private val bills: CardBillRepository,
    private val accounts: AccountRepository,
    private val expenses: ExpenseRepository,
    private val owedOn: (Set<String>) -> Flow<List<OwedPart>>,
    private val signedIn: Flow<Boolean>,
    private val settings: Flow<AppSettings>,
    private val zone: ZoneId,
) {
    /**
     * Bill [id] as the Card bill view shows it; null while it is gone: deleted, naming no card, or its card deleted or
     * switched off (D23). In manual mode it still shows what is stored.
     */
    fun observeBill(id: Long): Flow<CardBillDetail?> = bills.observe(id)
        .map { it?.let { bill -> bill.id to bill.accountId } }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            val accountId = key?.second ?: return@flatMapLatest flowOf(null)
            combine(bills.observe(id), accounts.observe(accountId), bills.observeForAccount(accountId)) { bill, account, siblings ->
                if (bill == null || account == null || !account.tracked) null else Triple(bill, account, siblings)
            }.distinctUntilChanged().flatMapLatest { found ->
                found?.let { (bill, account, siblings) -> detailOf(bill, account, CardBillMath.isCurrent(bill, siblings, zone)) }
                    ?: flowOf(null)
            }
        }
        .distinctUntilChanged()

    /** Bill [id] now; null when it is gone. */
    suspend fun detail(id: Long): CardBillDetail? = observeBill(id).first()

    /**
     * Home's CARD BILLS rows on each of [today] (D14 to D18): the current bill of every tracked card, in SMS mode,
     * until paid; empty in manual mode.
     */
    fun observeHomeBills(today: Flow<LocalDate>): Flow<List<CardBillSummary>> = combine(
        bills.observeAll(),
        accounts.observeAll(),
        settings.map { it.trackingMode == TrackingMode.SMS }.distinctUntilChanged(),
    ) { all, cards, sms -> Triple(all, cards.associateBy { it.id }, sms) }
        .flatMapLatest { (all, cards, sms) ->
            val live = all.filter { bill ->
                CardBillMath.isLive(BillFacts(bill, bill.accountId?.let(cards::get), null), all, sms, zone)
            }
            if (live.isEmpty()) {
                flowOf(emptyList())
            } else {
                val details = combine(live.map { bill -> detailOf(bill, cards.getValue(bill.accountId!!), current = true) }) { it.toList() }
                combine(details, today.distinctUntilChanged()) { shown, day ->
                    val byBill = shown.associateBy { it.bill.id }
                    CardBillMath.homeList(shown.map { it.facts }, all, sms, day, zone).map { facts ->
                        val detail = byBill.getValue(facts.bill.id)
                        CardBillSummary(
                            billId = facts.bill.id,
                            cardName = detail.cardName,
                            totalMinor = facts.bill.totalDueMinor,
                            state = detail.state(day),
                            friendsMinor = detail.friendsMinor,
                        )
                    }
                }
            }
        }
        .distinctUntilChanged()

    /** [bill] on [account] with its cycle's split card payments, its credits and what friends owe now. */
    private fun detailOf(bill: CardBill, account: Account, current: Boolean): Flow<CardBillDetail> {
        val statement = CardBillMath.statementDate(bill, zone)
        val from = CardBillMath.cycleStart(statement).atStartOfDay(zone).toInstant()
        val to = CardBillMath.paidWindowEnd(bill, statement).plusDays(1).atStartOfDay(zone).toInstant()
        return expenses.observeOnAccount(account.id, from, to)
            .map { rows -> Window.of(bill, rows, zone) }
            .distinctUntilChanged()
            .flatMapLatest { window ->
                combine(signedIn.distinctUntilChanged(), owedOn(window.groupExpenseIds)) { signed, parts ->
                    CardBillDetail(
                        bill = bill,
                        account = account,
                        statementDate = statement,
                        paidOn = window.paidOn,
                        signedIn = signed,
                        friends = if (signed) CardBillMath.friends(parts) else emptyList(),
                        current = current,
                    )
                }
            }
    }

    /** What the card's rows of one window give: the split card payments of the cycle and the day it was paid. */
    private data class Window(val groupExpenseIds: Set<String>, val paidOn: LocalDate?) {
        companion object {
            fun of(bill: CardBill, rows: List<Expense>, zone: ZoneId) = Window(
                groupExpenseIds = CardBillMath.cardPayments(bill, rows, zone).mapNotNullTo(LinkedHashSet()) { it.groupExpenseId },
                paidOn = CardBillMath.paidOn(bill, rows, zone),
            )
        }
    }
}
