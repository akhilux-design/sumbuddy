package com.expenses.app.ui.screens.groups

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.NewPerson
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.state.keepForm
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Add friend asks its route to do. */
sealed interface AddFriendEffect {
    /** Opened for New group or Choose people: hand [friend] back to the screen below and leave. */
    data class HandBack(val friend: AddedFriend) : AddFriendEffect

    /** Opened for a group: the friend was added to it, or the group is no longer on this phone; leave. */
    data object Added : AddFriendEffect
}

/**
 * Holds Add friend (02-everyday/27): a name and an optional phone number, typed or picked from contacts. "Add friend"
 * checks the phone number with `GroupText.isPhoneOk` (the field shows "That does not look right." until it is fixed or
 * emptied), then, for a group ([groupId], from Group settings' "Add someone"), adds the friend to it with
 * `GroupsRepository.addMember`, which works offline; without a group it hands the friend back as an [AddedFriend].
 *
 * What was typed is kept in [savedState], so it comes back after Android stopped the app (also while the contact
 * picker is open).
 *
 * @param groupId the group to add the friend to, or null to hand the friend back.
 * @param groups the group data.
 * @param savedState this screen's saved state.
 */
class AddFriendViewModel(
    private val groupId: String?,
    private val groups: GroupsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AddFriendUiState(name = savedState.get<String>(NAME_KEY).orEmpty(), phone = savedState.get<String>(PHONE_KEY).orEmpty()),
    )
    private val effects = Channel<AddFriendEffect>(Channel.BUFFERED)

    /** What the screen shows. */
    val state: StateFlow<AddFriendUiState> = mutableState.asStateFlow()

    /** One-off results for the route. */
    val effect: Flow<AddFriendEffect> = effects.receiveAsFlow()

    init {
        savedState.keepForm(viewModelScope, mutableState) {
            set(NAME_KEY, it.name)
            set(PHONE_KEY, it.phone)
        }
    }

    /** Handles what the person did. Navigation (Back, the contact picker) is the route's. */
    fun onEvent(event: AddFriendEvent) {
        when (event) {
            is AddFriendEvent.NameChanged -> mutableState.update { it.copy(name = event.name.take(GroupText.MAX_PERSON_NAME)) }
            is AddFriendEvent.PhoneChanged -> mutableState.update { it.copy(phone = event.phone.take(MAX_PHONE), phoneInvalid = false) }
            is AddFriendEvent.ContactPicked -> mutableState.update { current ->
                current.copy(
                    name = event.name?.take(GroupText.MAX_PERSON_NAME)?.takeIf { it.isNotBlank() } ?: current.name,
                    phone = event.phone?.take(MAX_PHONE) ?: current.phone,
                    phoneInvalid = false,
                )
            }
            AddFriendEvent.Add -> add()
            AddFriendEvent.PickFromContacts, AddFriendEvent.Back -> Unit
        }
    }

    private fun add() {
        val current = mutableState.value
        if (!current.canAdd) return
        if (!GroupText.isPhoneOk(current.phone)) {
            mutableState.update { it.copy(phoneInvalid = true, refusedSubmits = it.refusedSubmits + 1) }
            return
        }
        val name = GroupText.clean(current.name, GroupText.MAX_PERSON_NAME)
        val phone = current.phone.trim().takeIf { it.isNotEmpty() }
        val group = groupId
        if (group == null) {
            effects.trySend(AddFriendEffect.HandBack(AddedFriend(name, phone)))
            return
        }
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                groups.addMember(group, NewPerson.Friend(name, phone))
            } catch (groupGone: SQLiteConstraintException) {
                // A sync removed the group while this screen was open (deleted, or the person was removed): there is
                // nothing to add the friend to. Group settings below then finds the group gone (plan 6.14).
            }
            effects.send(AddFriendEffect.Added)
        }
    }

    private companion object {
        const val NAME_KEY = "name"
        const val PHONE_KEY = "phone"

        /** Longer than any phone number with spaces and a country code; the field takes no more. */
        const val MAX_PHONE = 24
    }
}
