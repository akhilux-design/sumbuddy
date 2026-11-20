package com.expenses.app.ui.shell

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.expenses.app.MainActivity
import com.expenses.app.ui.nav.AppLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How links from notifications and friends reach the app: each notification link stays its own intent, and a pay
 * link opened before onboarding is done waits for it.
 */
@RunWith(RobolectricTestRunner::class)
class LinkDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val token = "q7Lm2XvR9tKp4WcZ8bNf3hYd"

    @Test fun differentLinksNeverShareAnIntent() {
        val links = listOf(
            AppLink.Payment(7), AppLink.Payment(7, split = true), AppLink.Payment(8), AppLink.Group("g1"), AppLink.Group("g2"),
            AppLink.MoneyReceived(7), AppLink.AllSquare("g1", "m1", 120_000), AppLink.Join(token),
        )
        val intents = links.map { MainActivity.openLinkIntent(context, it) }
        for (i in intents.indices) {
            for (j in intents.indices) {
                assertEquals("${links[i]} vs ${links[j]}", i == j, intents[i].filterEquals(intents[j]))
            }
        }
        // The same link twice is the same intent, so its notification updates in place.
        assertTrue(MainActivity.openLinkIntent(context, AppLink.Group("g1")).filterEquals(intents[3]))
    }

    @Test fun theIntentDataHoldsNoTokenOrId() {
        val data = MainActivity.openLinkIntent(context, AppLink.Join(token)).data!!
        assertFalse(data.toString().contains(token))
        // What the system's log of started activities prints: the scheme and this part, never the fragment.
        assertEquals("app-link", data.scheme)
        assertEquals("open", data.schemeSpecificPart)
        val group = MainActivity.openLinkIntent(context, AppLink.Group("b0000000-0000-4000-8000-000000000001")).data!!
        assertFalse(group.toString().contains("b0000000"))
    }

    @Test fun aPayLinkWaitsForOnboardingAndOtherLinksAreDropped() {
        assertEquals(LinkAction.Wait, linkAction(AppLink.Join(token), onboardingDone = false))
        assertEquals(LinkAction.Drop, linkAction(AppLink.Group("g"), onboardingDone = false))
        assertEquals(LinkAction.Drop, linkAction(AppLink.Payment(7, split = true), onboardingDone = false))
        assertEquals(LinkAction.Open, linkAction(AppLink.Join(token), onboardingDone = true))
        assertEquals(LinkAction.Open, linkAction(AppLink.Group("g"), onboardingDone = true))
    }
}
