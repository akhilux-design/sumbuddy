package com.expenses.app.ui.nav

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links from outside the app open the right screen, and only the exact pay link shape is read. */
class AppLinksTest {
    private val host = "xpense-pay.pages.dev"
    private val token = "q7Lm2XvR9tKp4WcZ8bNf3hYd"

    @Test fun onlyAnExactPayLinkGivesItsToken() {
        assertEquals(token, PayLinks.tokenOf("https", host, "/pay/$token", host))
        assertEquals(token, PayLinks.tokenOf("https", "XPENSE-PAY.pages.dev", "/pay/$token", host))
        assertNull(PayLinks.tokenOf("http", host, "/pay/$token", host))
        assertNull(PayLinks.tokenOf("https", "evil.example", "/pay/$token", host))
        assertNull(PayLinks.tokenOf("https", host, "/pay/$token/more", host))
        assertNull(PayLinks.tokenOf("https", host, "/pay/short", host))
        assertNull(PayLinks.tokenOf("https", host, "/pay/q7Lm2XvR9tKp4WcZ8bNf3hY!", host))
        assertNull(PayLinks.tokenOf("https", host, "/join/$token", host))
        assertNull(PayLinks.tokenOf("https", host, null, host))
    }

    @Test fun eachLinkOpensItsScreen() {
        assertEquals(AddToExpenses(7), AppLink.Payment(7).route())
        assertEquals(SplitPayment(7), AppLink.Payment(7, split = true).route())
        assertEquals(GroupPage("g"), AppLink.Group("g").route())
        assertEquals(PaymentMatch(9), AppLink.MoneyReceived(9).route())
        assertEquals(BalanceCleared("g", "m", 120_000), AppLink.AllSquare("g", "m", 120_000).route())
        assertEquals(JoinGroup(token), AppLink.Join(token).route())
        // The card bill reminder: its tap, and "Remind friends".
        assertEquals(CardBillView(3), AppLink.CardBill(3).route())
        assertEquals(CardBillView(3, remind = true), AppLink.CardBill(3, remind = true).route())
        // "Meera joined Home": Home, with the Family side chosen by openLink.
        assertEquals(Home, AppLink.Family.route())
    }

    @Test fun linksSurviveTheNotificationExtra() {
        val json = Json { ignoreUnknownKeys = true }
        val links = listOf(
            AppLink.Payment(7, split = true), AppLink.Group("g"), AppLink.AllSquare("g", "m", 5), AppLink.Join(token), AppLink.Family,
            AppLink.CardBill(3), AppLink.CardBill(3, remind = true),
        )
        for (link in links) {
            assertEquals(link, json.decodeFromString(AppLink.serializer(), json.encodeToString(AppLink.serializer(), link)))
        }
    }

    @Test fun theFamilyRoutesCarryTheirArguments() {
        assertEquals(java.time.YearMonth.of(2026, 11), FamilyMonthView.of(java.time.YearMonth.of(2026, 11)).month)
        assertEquals(false, FamilySettings().sendLink)
        assertEquals(false, SignIn().forFamily)
    }

    @Test fun newGroupCarriesItsPeople() {
        assertEquals(listOf("u1", "+919000000001"), NewGroup.with(listOf("u1", "+919000000001")).peopleKeys)
        assertEquals(emptyList<String>(), NewGroup().peopleKeys)
    }
}
