package com.expenses.app.ui.screens.groups

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.expenses.app.R
import com.expenses.app.groups.data.GroupText
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.motion.shakeOn

/**
 * What Add friend shows.
 *
 * @param name the name being typed or picked.
 * @param phone the phone number being typed or picked; optional.
 * @param phoneInvalid true after "Add friend" with something in the phone field that is not a phone number.
 * @param saving true while the friend is being added to a group, so a second tap does nothing.
 * @param refusedSubmits how many times "Add friend" was refused for the phone number; each one shakes the phone field
 * and plays the reject haptic (a count, so a second refused tap shakes again).
 */
@Immutable
data class AddFriendUiState(
    val name: String = "",
    val phone: String = "",
    val phoneInvalid: Boolean = false,
    val saving: Boolean = false,
    val refusedSubmits: Int = 0,
) {
    /** True when "Add friend" can be used: the name is not blank once cleaned. */
    val canAdd: Boolean get() = GroupText.clean(name, GroupText.MAX_PERSON_NAME).isNotEmpty() && !saving
}

/** What the person does on Add friend. */
sealed interface AddFriendEvent {
    /** The name field changed. */
    data class NameChanged(val name: String) : AddFriendEvent

    /** The phone field changed. */
    data class PhoneChanged(val phone: String) : AddFriendEvent

    /** A contact was picked: its [name] and [phone] fill the fields. */
    data class ContactPicked(val name: String?, val phone: String?) : AddFriendEvent

    /** "Add friend", or the keyboard's Done key. */
    data object Add : AddFriendEvent

    /** "Pick from contacts": the system's contact picker opens. */
    data object PickFromContacts : AddFriendEvent

    /** The back button. */
    data object Back : AddFriendEvent
}

/**
 * Add friend (design 02-everyday/27-add-friend): the friend's name, an optional phone number, the note that they get
 * a WhatsApp link, "Add friend" and "Pick from contacts". A whole Indian mobile number is shown as the design writes
 * it, "+91 90000 00001" ([IndianMobileTransformation]); [AddFriendUiState.phone] stays what was typed.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier layout changes from the caller.
 * @param focusName true to put the cursor in the name field when the screen opens, as the design draws it.
 * @param onNameFocused called once the cursor was put there, so it is not put there again when the person comes back.
 */
@Composable
fun AddFriendScreen(
    state: AddFriendUiState,
    onEvent: (AddFriendEvent) -> Unit,
    modifier: Modifier = Modifier,
    focusName: Boolean = false,
    onNameFocused: () -> Unit = {},
) {
    val nameFocus = remember { FocusRequester() }
    if (focusName) {
        LaunchedEffect(Unit) {
            nameFocus.requestFocus()
            onNameFocused()
        }
    }
    PageFrame(
        title = stringResource(R.string.groups_add_friend_title),
        onBack = { onEvent(AddFriendEvent.Back) },
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.groups_add_friend_action),
                onClick = { onEvent(AddFriendEvent.Add) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
                enabled = state.canAdd,
            )
            AppButton(
                label = stringResource(R.string.groups_add_friend_pick),
                onClick = { onEvent(AddFriendEvent.PickFromContacts) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        AppInput(
            value = state.name,
            onValueChange = { onEvent(AddFriendEvent.NameChanged(it)) },
            label = stringResource(R.string.groups_add_friend_name),
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        AppInput(
            value = state.phone,
            onValueChange = { onEvent(AddFriendEvent.PhoneChanged(it)) },
            label = stringResource(R.string.groups_add_friend_phone),
            // A refused "Add friend" shakes the field and plays the reject haptic.
            modifier = Modifier
                .fillMaxWidth()
                .shakeOn(state.refusedSubmits),
            helper = stringResource(R.string.groups_add_friend_phone_invalid),
            state = if (state.phoneInvalid) AppInputState.Error else AppInputState.Default,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(AddFriendEvent.Add) }),
            // A whole Indian mobile number reads "+91 90000 00001", grouped as drawn; the text itself stays as typed.
            visualTransformation = IndianMobileTransformation,
        )
        val firstName = firstNameOf(state.name)
        InfoRow(
            title = stringResource(R.string.groups_add_friend_info_title),
            subtitle = if (firstName != null) {
                stringResource(R.string.groups_add_friend_info_body, firstName)
            } else {
                stringResource(R.string.groups_add_friend_info_body_no_name)
            },
            icon = AppIcon.Message,
        )
    }
}

/** The first word of [name] ("Kiran" of "Kiran Rao"), or null when nothing is typed. */
private fun firstNameOf(name: String): String? =
    GroupText.clean(name, GroupText.MAX_PERSON_NAME).substringBefore(' ').takeIf { it.isNotEmpty() }
