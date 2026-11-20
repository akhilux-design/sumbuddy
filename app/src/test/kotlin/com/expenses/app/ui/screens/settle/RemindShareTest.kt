package com.expenses.app.ui.screens.settle

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Send on WhatsApp" and "Copy link" on Remind on WhatsApp (design 03-getting-paid-back/05). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RemindShareTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val text = "Hey Priya You owe Arjun ₹1,150 for Flat 302.\nSee the breakdown and pay by UPI:\nhttps://links.test/pay/q7Lm2XvR9tKp4WcZ8bNf3hYd\nNo app needed."

    private fun install(pkg: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.ShareActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg), info)
    }

    private fun Intent.text(): String? = getStringExtra(Intent.EXTRA_TEXT)

    @Test fun whatsAppGetsTheMessageFirst() {
        install("com.whatsapp.w4b")
        install("com.whatsapp")

        val intent = RemindShare.intentFor(context.packageManager, text)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.whatsapp", intent.`package`)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.text())
    }

    @Test fun whatsAppBusinessWhenOnlyItIsThere() {
        install("com.whatsapp.w4b")

        val intent = RemindShare.intentFor(context.packageManager, text)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.whatsapp.w4b", intent.`package`)
        assertEquals(text, intent.text())
    }

    @Test fun withoutWhatsAppTheShareSheetOpens() {
        val intent = RemindShare.intentFor(context.packageManager, text)

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertNull(inner.`package`)
        assertEquals(text, inner.text())
    }

    @Test fun copyLinkPutsOnlyTheLinkOnTheClipboard() {
        RemindShare.copy(context, "Pay link", "https://links.test/pay/q7Lm2XvR9tKp4WcZ8bNf3hYd")

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("https://links.test/pay/q7Lm2XvR9tKp4WcZ8bNf3hYd", clip.getItemAt(0).text.toString())
    }

    @Test fun onlyAndroid8To12NeedTheScreenToConfirmTheCopy() {
        // Android 13 and later show their own confirmation after a copy; before that, nothing is shown or spoken.
        assertTrue(RemindShare.needsCopyConfirmation(Build.VERSION_CODES.O))
        assertTrue(RemindShare.needsCopyConfirmation(Build.VERSION_CODES.S_V2))
        assertFalse(RemindShare.needsCopyConfirmation(Build.VERSION_CODES.TIRAMISU))
        assertFalse(RemindShare.needsCopyConfirmation(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }
}
