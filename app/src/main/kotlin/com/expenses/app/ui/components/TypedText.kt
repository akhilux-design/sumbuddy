package com.expenses.app.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * What was typed into a text field a moment ago, shown until the field's owner has had time to answer.
 *
 * A field tells its owner (a ViewModel) each typed text and shows the text the owner gives back. Most owners here build
 * their state with `combine`, which hands back the second of two quick changes one main-thread message later. When a
 * frame was drawn in between, the field was given the older text again and the next key landed in the wrong place:
 * "Filter coffee" typed fast came out as "Filtcoffeeer". So the field shows what was typed until the main thread has
 * run what was waiting when the key came, the owner's answer among it, and the owner's text after that. The owner
 * still has the last word: a text it changes or refuses shows as the owner keeps it, as before.
 *
 * Use it with [rememberTypedText]: show [shown], and call [typed] with each new text after telling the owner.
 */
@Stable
internal class TypedText {
    private val mainThread = Handler(Looper.getMainLooper())

    /** The text typed last, or null once the owner has had time to answer. */
    private var text: String? by mutableStateOf(null)

    /** How many texts were typed; the wait starts again with each one. */
    private var keys = 0

    /** The text to show: what was just typed, or [owner], the owner's text. */
    fun shown(owner: String): String = text ?: owner

    /**
     * The person typed [text]. Call it after telling the owner, so that whatever the owner queued to answer runs
     * before the field shows the owner's text again.
     */
    fun typed(text: String) {
        this.text = text
        val key = ++keys
        mainThread.post { if (key == keys) this.text = null }
    }
}

/** A [TypedText] for one text field, kept while the field is on screen. */
@Composable
internal fun rememberTypedText(): TypedText = remember { TypedText() }
