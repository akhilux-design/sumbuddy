package com.expenses.app.ui.screens.family

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.expenses.app.R
import com.expenses.app.ui.screens.settle.RemindShare

/**
 * The partner's invite (docs/family.md 1.3): who sends it, the family, and the link `https://<LINK_HOST>/pay/<token>`
 * that [com.expenses.app.groups.data.FamilyRepository.inviteLink] made from `BuildConfig.LINK_HOST`. The link holds a
 * secret token: it is never logged or shown on screen, only handed to the share sheet.
 *
 * @param link the partner's link.
 * @param senderFirstName the person's first name ("Arjun").
 * @param familyName the family's name ("Home").
 */
data class FamilyInvite(val link: String, val senderFirstName: String, val familyName: String) {
    /**
     * The message the share sheet sends, line by line: "Arjun invited you to Home on SumBuddy: one monthly total for the
     * two of you.", "Get the app, then open this link on your phone to join:", the link, "Only expenses you mark Family
     * are shared. Never your SMS.".
     */
    fun message(res: Resources): String = listOf(
        res.getString(R.string.family_invite_line1, senderFirstName, familyName, res.getString(R.string.app_name)),
        res.getString(R.string.family_invite_line2),
        link,
        res.getString(R.string.family_invite_line3),
    ).joinToString("\n")

    /** Making it from what the family screens know. */
    companion object {
        /** The invite of [link], from [senderName] (whole or first name) for [familyName]. */
        fun of(link: String, senderName: String, familyName: String): FamilyInvite =
            FamilyInvite(link, senderName.trim().substringBefore(' '), familyName)
    }
}

/** Sending the invite: the system share sheet, so the person picks WhatsApp or any app (decision 11). */
internal object FamilyShare {
    /** What [share] starts: the chooser over `ACTION_SEND` text/plain with [text]. */
    fun intentFor(text: String): Intent = Intent.createChooser(RemindShare.sendText(text), null)

    /** Opens the share sheet with [invite]'s message. False when nothing on the phone could take it. */
    fun share(context: Context, invite: FamilyInvite): Boolean = try {
        context.startActivity(intentFor(invite.message(context.resources)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
