package com.expenses.app.ui.nav

import com.expenses.app.ui.nav.ScreenKind.Modal
import com.expenses.app.ui.nav.ScreenKind.Push
import com.expenses.app.ui.nav.ScreenKind.Result
import com.expenses.app.ui.nav.ScreenKind.Tab
import java.io.File
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every route has the kind of `docs/motion.md` 2.1, and each pair of kinds gives the move of the design's patterns. */
class ScreenTransitionsTest {
    private val expectedKinds: Map<KClass<*>, ScreenKind> = mapOf(
        Welcome::class to Push,
        SignIn::class to Modal,
        SignInName::class to Push,
        YourName::class to Push,
        PrivacyPromise::class to Push,
        SmsPermission::class to Push,
        NotificationPermission::class to Push,
        HowFarBack::class to Push,
        Scanning::class to Push,
        NothingFound::class to Push,
        ConfirmAccounts::class to Push,
        Home::class to Tab,
        Groups::class to Tab,
        Activity::class to Tab,
        You::class to Tab,
        AddToExpenses::class to Push,
        ChooseCategory::class to Modal,
        ExpenseAdded::class to Result,
        ExpenseDetail::class to Push,
        AddExpense::class to Modal,
        ReviewPayments::class to Push,
        NeedsReview::class to Push,
        MonthView::class to Push,
        AccountsAndCards::class to Push,
        WhatWeHaveRead::class to Push,
        TrackingStartDate::class to Push,
        TurnOnSmsTracking::class to Push,
        GroupPage::class to Push,
        GroupSettings::class to Push,
        NewGroup::class to Modal,
        AddFriend::class to Modal,
        JoinGroup::class to Push,
        SplitPayment::class to Push,
        SplitTyped::class to Push,
        ChoosePeople::class to Push,
        SplitAdded::class to Result,
        RecurringSplitOffer::class to Push,
        SettleUp::class to Push,
        MarkAsPaid::class to Push,
        PaymentMatch::class to Push,
        BalanceCleared::class to Result,
        RemindOnWhatsApp::class to Push,
        CardBillView::class to Push,
        ImportSplitwise::class to Modal,
        GroupImported::class to Result,
        YouAccount::class to Push,
        SetUpFamily::class to Push,
        FamilySettings::class to Push,
        FamilyMonthView::class to Push,
    )

    @Test fun everyRouteMapsToItsKind() {
        expectedKinds.forEach { (route, kind) -> assertEquals(route.java.simpleName, kind, screenKindOf(route)) }
    }

    /** A new route in Routes.kt must be added to the table above, so someone decides how it moves. */
    @Test fun theTableCoversEveryRoute() {
        val notScreens = setOf("OnboardingGraph", "MainGraph", "AddedFriend") // two graphs and a result handed back
        val declared = Regex("""@Serializable\s+data (?:object|class) (\w+)""")
            .findAll(File("src/main/kotlin/com/expenses/app/ui/nav/Routes.kt").readText())
            .map { it.groupValues[1] }
            .filterNot { it in notScreens }
            .toSortedSet()
        assertEquals(declared, expectedKinds.keys.map { it.java.simpleName }.toSortedSet())
    }

    @Test fun anUnlistedRouteIsAPush() {
        assertEquals(Push, screenKindOf(String::class))
    }

    @Test fun tabToTabMovesInsideTheTabs() {
        assertEquals(Move.Tab, move(Tab, Tab, pop = false))
        assertEquals(Move.Tab, move(Tab, Tab, pop = true))
        assertEquals(Move.None, move(Tab, Tab, pop = false, reducedMotion = true))
    }

    @Test fun forwardMoves() {
        assertEquals(Move.Forward, move(Tab, Push, pop = false))
        assertEquals(Move.Forward, move(Push, Push, pop = false))
        assertEquals(Move.Forward, move(Modal, Push, pop = false)) // Sign in > Your name
        assertEquals(Move.Forward, move(Result, Push, pop = false))
        assertEquals(Move.ModalRise, move(Tab, Modal, pop = false))
        assertEquals(Move.ModalRise, move(Push, Modal, pop = false))
        assertEquals(Move.ModalRise, move(Result, Modal, pop = false))
        assertEquals(Move.Forward, move(Modal, Modal, pop = false)) // New group > Add friend, Import
        assertEquals(Move.Arrive, move(Modal, Result, pop = false)) // Add expense > Expense added
        assertEquals(Move.Arrive, move(Push, Result, pop = false))
        assertEquals(Move.Arrive, move(Tab, Result, pop = false))
        assertEquals(Move.Arrive, move(Result, Tab, pop = false)) // Group imported > Done > Groups
        assertEquals(Move.Arrive, move(Push, Tab, pop = false)) // finishing onboarding > Home
        assertEquals(Move.Arrive, move(Modal, Tab, pop = false))
    }

    @Test fun popMoves() {
        assertEquals(Move.Back, move(Push, Tab, pop = true))
        assertEquals(Move.Back, move(Push, Push, pop = true))
        assertEquals(Move.Back, move(Push, Modal, pop = true)) // Your name > back to Sign in
        assertEquals(Move.Back, move(Tab, Push, pop = true))
        assertEquals(Move.Back, move(Modal, Modal, pop = true)) // Add friend > back to New group
        assertEquals(Move.ModalDrop, move(Modal, Tab, pop = true))
        assertEquals(Move.ModalDrop, move(Modal, Push, pop = true))
        assertEquals(Move.ModalDrop, move(Modal, Result, pop = true))
        ScreenKind.entries.forEach { to -> assertEquals("$to", Move.FadeThrough, move(Result, to, pop = true)) }
    }

    @Test fun reducedMotionNeverMoves() {
        ScreenKind.entries.forEach { from ->
            ScreenKind.entries.forEach { to ->
                listOf(false, true).forEach { pop ->
                    assertEquals("$from > $to pop=$pop", Move.None, move(from, to, pop, reducedMotion = true))
                }
            }
        }
    }
}
