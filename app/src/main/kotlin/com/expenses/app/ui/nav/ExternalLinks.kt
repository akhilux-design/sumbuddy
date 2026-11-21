package com.expenses.app.ui.nav

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.expenses.app.R

/**
 * Opens [url] in the phone's browser (`ACTION_VIEW`, browsable apps only). No WebView and no Custom Tabs: the page is
 * the browser's, with its own address bar. A phone with no app that can open it does nothing (docs/card-bills.md 3.2).
 *
 * @param context a context that can start an activity; any other gets the new-task flag.
 * @param url the address.
 */
fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing can open any link on this phone.
    }
}

/** Opens the public repository ("See the code" on You and What we've read; the address is `source_code_url`). */
fun openSourceCode(context: Context) = openInBrowser(context, context.getString(R.string.source_code_url))
