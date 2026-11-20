package com.expenses.app.ui.screens.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.GroupText
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.NewPerson
import com.expenses.app.groups.data.Person
import com.expenses.app.ui.nav.AddedFriend
import com.expenses.app.ui.state.keepForm
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What New group asks its route to do. */
sealed interface NewGroupEffect {
    /** The group [groupId] was made. */
    data class Created(val groupId: String) : NewGroupEffect

    /** Nobody is signed in any more (the session ended meanwhile): Sign in opens. */
    data object SignedOut : NewGroupEffect
}

/**
 * Holds New group (02-everyday/26): the name, the people the person already splits with
 * (`GroupsRepository.observeKnownPeople`, "On SumBuddy" or "Not on SumBuddy · gets a WhatsApp link") with a switch each,
 * and the friends added on Add friend, switched on. People named in [prefilledKeys] and the friends in
 * [prefilledFriends] (Choose people's "Make a group") start switched on. "Create group" makes the group with
 * `GroupsRepository.createGroup`, which works offline.
 *
 * What was typed and switched is kept in [savedState], so it comes back after Android stopped the app.
 *
 * @param groups the group data.
 * @param savedState this screen's saved state.
 * @param prefilledKeys the `Person.key`s to switch on when the screen first opens (`NewGroup.peopleKeys`).
 * @param prefilledFriends friends in no group yet to list and switch on when the screen first opens, as if they came
 * back from Add friend (`NewGroup.newFriends`: added on Choose people, so they have no `Person.key`).
 */
class NewGroupViewModel(
    private val groups: GroupsRepository,
    private val savedState: SavedStateHandle,
    prefilledKeys: List<String>,
    prefilledFriends: List<AddedFriend> = emptyList(),
) : ViewModel() {
    /** The friends Choose people handed over; used only when the screen first opens. */
    private val handedOver: List<AddedPerson> = prefilledFriends
        .mapNotNull { friend ->
            GroupText.clean(friend.name, GroupText.MAX_PERSON_NAME).takeIf { it.isNotEmpty() }?.let { it to GroupText.normalizePhone(friend.phone) }
        }
        .mapIndexed { index, (name, phone) -> AddedPerson("$ADDED_PREFIX${index + 1}", name, phone) }

    private val form = MutableStateFlow(
        Form(
            name = savedState.get<String>(NAME_KEY).orEmpty(),
            selected = savedState.get<ArrayList<String>>(SELECTED_KEY)?.toSet() ?: (prefilledKeys + handedOver.map { it.key }).toSet(),
            added = savedState.get<String>(ADDED_KEY)?.let(::decodeAdded) ?: handedOver,
        ),
    )
    private val creating = MutableStateFlow(false)
    private val effects = Channel<NewGroupEffect>(Channel.BUFFERED)

    /** The people the person already splits with, as last read. */
    private val known: StateFlow<List<Person>> =
        groups.observeKnownPeople().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the screen shows. */
    val state: StateFlow<NewGroupUiState> = combine(form, known, creating) { current, people, busy ->
        NewGroupUiState(
            name = current.name,
            people = people.map { NewGroupPerson(it.key, it.name, it.onApp, it.key in current.selected) } +
                current.added.map { NewGroupPerson(it.key, it.name, onApp = false, selected = it.key in current.selected) },
            creating = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NewGroupUiState(name = form.value.name))

    /** One-off results for the route. */
    val effect: Flow<NewGroupEffect> = effects.receiveAsFlow()

    init {
        savedState.keepForm(viewModelScope, form) { saved ->
            set(NAME_KEY, saved.name)
            set(SELECTED_KEY, ArrayList(saved.selected))
            set(ADDED_KEY, encodeAdded(saved.added))
        }
    }

    /** Handles what the person did. Navigation (Back, Add someone) is the route's. */
    fun onEvent(event: NewGroupEvent) {
        when (event) {
            is NewGroupEvent.NameChanged -> form.update { it.copy(name = event.name.take(GroupText.MAX_GROUP_NAME)) }
            is NewGroupEvent.PersonSwitched -> form.update {
                it.copy(selected = if (event.selected) it.selected + event.key else it.selected - event.key)
            }
            NewGroupEvent.Create -> create()
            NewGroupEvent.AddSomeone, NewGroupEvent.Back, NewGroupEvent.ImportFromSplitwise -> Unit
        }
    }

    /**
     * A friend came back from Add friend: they are listed and switched on. Someone already listed with the same phone
     * number is switched on instead of being listed twice.
     */
    fun onFriendAdded(friend: AddedFriend) {
        val name = GroupText.clean(friend.name, GroupText.MAX_PERSON_NAME)
        if (name.isEmpty()) return
        val phone = GroupText.normalizePhone(friend.phone)
        form.update { current ->
            val same = phone?.let { number ->
                known.value.firstOrNull { it.phone == number }?.key
                    ?: current.added.firstOrNull { it.phone == number }?.key
            }
            if (same != null) {
                current.copy(selected = current.selected + same)
            } else {
                val key = "$ADDED_PREFIX${(current.added.maxOfOrNull { it.number } ?: 0) + 1}"
                val added = AddedPerson(key, name, phone)
                current.copy(added = current.added + added, selected = current.selected + key)
            }
        }
    }

    private fun create() {
        val current = form.value
        if (creating.value || GroupText.clean(current.name, GroupText.MAX_GROUP_NAME).isEmpty()) return
        creating.value = true
        val people = known.value.filter { it.key in current.selected }.map { person ->
            val userId = person.userId
            if (userId != null) NewPerson.KnownUser(userId, person.name) else NewPerson.Friend(person.name, person.phone)
        } + current.added.filter { it.key in current.selected }.map { NewPerson.Friend(it.name, it.phone) }
        viewModelScope.launch {
            try {
                effects.send(NewGroupEffect.Created(groups.createGroup(current.name, people)))
            } catch (signedOut: IllegalStateException) {
                effects.send(NewGroupEffect.SignedOut)
            } finally {
                creating.value = false
            }
        }
    }

    /** What was typed and switched; [added] holds the friends from Add friend, in the order they came. */
    private data class Form(val name: String, val selected: Set<String>, val added: List<AddedPerson>)

    /**
     * A friend added on Add friend.
     *
     * @param key made here: [ADDED_PREFIX] and a number.
     * @param name their name, cleaned.
     * @param phone their phone number as E.164, or null.
     */
    @kotlinx.serialization.Serializable
    private data class AddedPerson(val key: String, val name: String, val phone: String?) {
        val number: Int get() = key.removePrefix(ADDED_PREFIX).toIntOrNull() ?: 0
    }

    private companion object {
        const val NAME_KEY = "name"
        const val SELECTED_KEY = "selected"
        const val ADDED_KEY = "added"

        /** Keys of friends added here; a `Person.key` is a user id, a phone number or "name:" and a name, never this. */
        const val ADDED_PREFIX = "added:"

        val ADDED_LIST = ListSerializer(AddedPerson.serializer())

        fun encodeAdded(added: List<AddedPerson>): String = Json.encodeToString(ADDED_LIST, added)

        fun decodeAdded(json: String): List<AddedPerson>? = runCatching { Json.decodeFromString(ADDED_LIST, json) }.getOrNull()
    }
}
