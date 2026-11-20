package com.expenses.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.motion.Reveal
import com.expenses.app.ui.motion.fadingColor
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.icons.LineIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/** How an [AppInput] looks. These are the three values of the Figma property `State`. */
enum class AppInputState {
    /**
     * The resting look: a thin grey outline. While the user is typing in the field it shows the
     * [Focused] look by itself, so screens normally leave the state at this value.
     */
    Default,

    /** A thicker green outline, shown even when the field is not being typed in. */
    Focused,

    /**
     * A thicker red outline, with the helper message under the field saying what is wrong. Without a helper
     * message an alert mark is drawn at the end of the field instead, so the error never shows by colour alone.
     */
    Error,
}

/** Outline weight of the resting field (Figma: 1.5). */
private val AppInputRestingStroke = 1.5.dp

/** Outline weight of the focused field and of the field with an error (Figma: 2). */
private val AppInputActiveStroke = 2.dp

/** Size of the alert mark drawn in an error field that has no message. */
private val AppInputAlertSize = 20.dp

/** How see-through the brand colour is behind selected text. */
private const val AppInputSelectionAlpha = 0.4f

/**
 * A text field with a label above it and, when something is wrong, a message below it.
 *
 * It is as wide as the space it is given. The outline is part of the size, as in the design: the field is
 * 59dp high at rest and 60dp when focused or in error, and it grows with the phone's font size.
 * Tapping the label also puts the cursor in the field. Screen readers read the label, the placeholder and
 * the error message together with the field. When the error message appears, the field is scrolled into view with
 * it (inside a scrolling screen) and screen readers read the message out at once.
 *
 * To open a screen with the keyboard already up, pass `Modifier.focusRequester(...)` in [modifier] and
 * request focus; the field then shows the focused look by itself.
 *
 * @param value the text in the field.
 * @param onValueChange called with the new text each time the user types or deletes.
 * @param label the name of the field, shown above it, for example "Phone number".
 * @param modifier layout changes from the caller, applied to the whole component (label, field and message).
 * @param placeholder a hint shown inside the field, in a softer colour, while [value] is empty. Empty for no hint.
 * @param helper the message shown under the field in the [AppInputState.Error] state, for example
 * "That does not look right.". It is not shown in the other states. Always give one with an error, as the design
 * does: the message says what is wrong. Without one the field shows an alert mark at its end, and screen readers
 * report the error with the field's label.
 * @param state which look to show; see [AppInputState].
 * @param keyboardOptions which keyboard to show (numbers, phone, text) and what its action key does.
 * @param keyboardActions what happens when the user presses the keyboard's action key (Done, Next).
 * @param singleLine true keeps the text on one line that scrolls sideways; false lets the field grow taller.
 * @param visualTransformation changes how the text is drawn without changing [value], for example to space
 * out the digits of a code.
 * @param interactionSource pass one to watch the field's focus from outside; leave null otherwise.
 */
@Composable
fun AppInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String = "",
    state: AppInputState = AppInputState.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val hasFocus by source.collectIsFocusedAsState()
    val look = if (state == AppInputState.Default && hasFocus) AppInputState.Focused else state
    val isError = look == AppInputState.Error
    val errorMessage = helper.takeIf { isError && it.isNotEmpty() }
    val colors = AppTheme.colors
    // What was typed stays in the field while the caller still holds the older text; see TypedText.
    val typed = rememberTypedText()
    val shown = typed.shown(value)
    // A message that appears (Save with a wrong value, the keyboard up) is scrolled into view, so the field never
    // says what is wrong by its red outline alone.
    val bring = remember { BringIntoViewRequester() }
    val height = remember { FieldHeight() }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            // The message opens down (Reveal), so follow it until the field stops growing. This only scrolls; no
            // callback waits for it.
            var last = -1
            repeat(RevealFollowFrames) {
                withFrameNanos { } // the message is laid out first
                if (height.px == last) return@LaunchedEffect
                last = height.px
                bring.bringIntoView()
            }
        }
    }
    // The last message stays while it closes.
    var lastMessage by remember { mutableStateOf(errorMessage.orEmpty()) }
    if (errorMessage != null) lastMessage = errorMessage

    CompositionLocalProvider(LocalTextSelectionColors provides appInputSelectionColors()) {
        BasicTextField(
            value = shown,
            onValueChange = {
                onValueChange(it)
                typed.typed(it)
            },
            modifier = modifier
                .onSizeChanged { height.px = it.height }
                .bringIntoViewRequester(bring)
                .then(if (isError) Modifier.semantics { error(errorMessage ?: label) } else Modifier),
            textStyle = AppTheme.type.bodyL.copy(color = colors.textPrimary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            interactionSource = source,
            cursorBrush = SolidColor(colors.textPrimary),
            decorationBox = { innerTextField ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    AppText(text = label, style = AppTheme.type.caption, color = colors.textSecondary)
                    AppInputField(
                        look = look,
                        placeholder = placeholder.takeIf { shown.isEmpty() && it.isNotEmpty() },
                        showAlert = isError && errorMessage == null,
                        singleLine = singleLine,
                        innerTextField = innerTextField,
                    )
                    // A message that appears after the screen is shown opens down (Reveal); one there at first just shows.
                    Reveal(visible = errorMessage != null) {
                        AppText(
                            text = lastMessage,
                            style = AppTheme.type.caption,
                            // Read out when it appears, wherever the screen reader is.
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = colors.statusNegative,
                        )
                    }
                }
            },
        )
    }
}

/**
 * The filled, outlined box that holds the typed text.
 *
 * @param look which outline to draw.
 * @param placeholder the hint to draw under the cursor, or null when there is none to show.
 * @param showAlert true draws the alert mark at the end of the field, for an error that has no message.
 * @param singleLine true keeps the hint on one line, like the text.
 * @param innerTextField the editable text itself, handed over by the text field.
 */
@Composable
private fun AppInputField(
    look: AppInputState,
    placeholder: String?,
    showAlert: Boolean,
    singleLine: Boolean,
    innerTextField: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    // The width is part of the layout and changes at once; the colour changes over 150 ms (focus and error).
    val strokeWidth = appInputStrokeWidth(look)
    val strokeColor by fadingColor(appInputStrokeColor(look), label = "inputOutline")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppTheme.colors.bgSurfaceRaised)
            .drawWithContent {
                drawContent()
                // Inside the field, as `Modifier.border` draws it.
                val w = strokeWidth.toPx()
                val r = (Radius.md.toPx() - w / 2f).coerceAtLeast(0f)
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(w / 2f, w / 2f),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(w),
                )
            }
            .padding(strokeWidth + Space.s16),
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            // The hint and the text are the same height in the design, so top and centre look the same.
            // Top keeps a hint that wraps in a multi-line field level with the cursor.
            contentAlignment = Alignment.TopStart,
            propagateMinConstraints = true,
        ) {
            if (placeholder != null) {
                AppText(
                    text = placeholder,
                    style = AppTheme.type.bodyL,
                    color = AppTheme.colors.textSecondary,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                )
            }
            innerTextField()
        }
        if (showAlert) {
            LineIcon(icon = AppIcon.Alert, size = AppInputAlertSize, tint = AppTheme.colors.statusNegative)
        }
    }
}

/** Outline weight for each look. The weight is added to the padding, so it is part of the field's size. */
/** How many frames at most a new error message is followed into view while it opens. */
private const val RevealFollowFrames = 60

/** The field's last measured height, kept outside the snapshot: it is only compared between frames. */
private class FieldHeight {
    var px: Int = -1
}

private fun appInputStrokeWidth(look: AppInputState): Dp =
    if (look == AppInputState.Default) AppInputRestingStroke else AppInputActiveStroke

/** Outline colour for each look. */
@Composable
private fun appInputStrokeColor(look: AppInputState): Color = when (look) {
    AppInputState.Default -> AppTheme.colors.borderSubtle
    AppInputState.Focused -> AppTheme.colors.brandPrimary
    AppInputState.Error -> AppTheme.colors.statusNegative
}

/** Brand-coloured selection handles and highlight, instead of the system's default blue. */
@Composable
private fun appInputSelectionColors(): TextSelectionColors {
    val brand = AppTheme.colors.brandPrimary
    return remember(brand) {
        TextSelectionColors(handleColor = brand, backgroundColor = brand.copy(alpha = AppInputSelectionAlpha))
    }
}
