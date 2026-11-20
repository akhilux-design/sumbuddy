package com.expenses.app.ui.screens.settle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import com.expenses.app.R
import com.expenses.app.ui.format.formatRupees

/**
 * The reminder a friend gets on WhatsApp (design 03-getting-paid-back/05-remind-on-whatsapp), line by line as the
 * preview draws it: "Hey Priya You owe Arjun ₹1,150 for Flat 302.", "See the breakdown and pay by UPI:", the link,
 * "No app needed.".
 *
 * @param owe the first line.
 * @param linkIntro the line before the link.
 * @param link the friend's pay link (`https://<LINK_HOST>/pay/<token>`), or null while it is not made yet.
 * @param noApp the last line.
 */
data class RemindMessage(val owe: String, val linkIntro: String, val link: String?, val noApp: String) {
    /** The link as the preview shows it, without "https://". */
    val shownLink: String? get() = link?.removePrefix(HTTPS)

    /** The text sent: the lines one under the other, the link in full. */
    val text: String get() = listOfNotNull(owe, linkIntro, link, noApp).joinToString("\n")

    /** Building the message from the app's strings. */
    companion object {
        private const val HTTPS = "https://"

        /**
         * The reminder [friendName] gets from [myName] for [amountMinor] in [groupName] (null for a direct split),
         * with [link].
         */
        fun of(res: Resources, friendName: String, myName: String, amountMinor: Long, groupName: String?, link: String?): RemindMessage {
            val amount = formatRupees(amountMinor)
            val owe = if (groupName != null) {
                res.getString(R.string.settle_remind_message_owe, friendName, myName, amount, groupName)
            } else {
                res.getString(R.string.settle_remind_message_owe_direct, friendName, myName, amount)
            }
            return RemindMessage(
                owe = owe,
                linkIntro = res.getString(R.string.settle_remind_message_link),
                link = link,
                noApp = res.getString(R.string.settle_remind_message_no_app),
            )
        }
    }
}

/**
 * Sending the reminder from the person's own WhatsApp, and copying the link. Nothing is logged: the text holds the
 * friend's pay link.
 */
internal object RemindShare {
    /** WhatsApp, then WhatsApp Business (both listed under `<queries>` in the manifest). */
    val WHATSAPP_PACKAGES: List<String> = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * What "Send on WhatsApp" starts: [text] sent to WhatsApp, else to WhatsApp Business, else the system share
     * sheet, so the person can send it another way.
     */
    fun intentFor(packageManager: PackageManager, text: String): Intent {
        for (pkg in WHATSAPP_PACKAGES) {
            val intent = sendText(text).setPackage(pkg)
            if (intent.resolveActivity(packageManager) != null) return intent
        }
        return Intent.createChooser(sendText(text), null)
    }

    /** Starts [intentFor]. False when nothing on the phone could take it. */
    fun send(context: Context, text: String): Boolean = try {
        context.startActivity(intentFor(context.packageManager, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }

    /** Puts [link] on the clipboard ("Copy link"). Android 13 and later confirm it themselves. */
    fun copy(context: Context, label: String, link: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, link))
    }

    /**
     * True when the system shows nothing after [copy] (Android 8 to 12), so the screen confirms it itself. Android 13
     * and later show their own confirmation, and a second one would repeat it.
     */
    fun needsCopyConfirmation(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

    /** Plain [text] to send (`ACTION_SEND`, text/plain); also the family invite's (docs/family.md 1.3). */
    fun sendText(text: String): Intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
}
